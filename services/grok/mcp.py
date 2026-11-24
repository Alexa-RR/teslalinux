"""The Model Context Protocol over Streamable HTTP, for one endpoint with a fixed set of tools.

Both eras of the protocol are served on the same endpoint (the 2026-07-28 revision's "dual-era
server"), statelessly:

  * 2026-07-28 (current): no handshake and no sessions. Every POST carries MCP-Protocol-Version and
    the request's params._meta["io.modelcontextprotocol/protocolVersion"] (they must agree) and
    ["io.modelcontextprotocol/clientCapabilities"]; methods server/discover, tools/list,
    tools/call. Results carry resultType "complete" and the server's info in _meta.
  * 2025-03-26, 2025-06-18, 2025-11-25 (earlier Streamable HTTP): initialize (the version answered
    is the client's when supported, else the newest of these), notifications/initialized, ping,
    tools/list, tools/call. No Mcp-Session-Id is issued: sessions are optional in those revisions,
    and this server keeps no state between requests. A POST without MCP-Protocol-Version and
    without the current era's _meta is a 2025-03-26 request (the revisions' fallback); batches
    (a JSON array) are answered for 2025-03-26 only, the one revision that had them.

HTTP: POST only (GET and DELETE are 405: no server-initiated stream, no session to end). The body is
JSON; a request is answered with application/json, or with one text/event-stream event when the
client accepts only that. Notifications and responses are 202 with no body. Authentication and the
Origin check happen before this module (grok.py).
"""
import json

CURRENT = "2026-07-28"
LEGACY = ("2025-11-25", "2025-06-18", "2025-03-26")
SUPPORTED = (CURRENT,) + LEGACY
META_VERSION = "io.modelcontextprotocol/protocolVersion"
META_CAPS = "io.modelcontextprotocol/clientCapabilities"
META_SERVER = "io.modelcontextprotocol/serverInfo"

PARSE_ERROR = -32700
INVALID_REQUEST = -32600
METHOD_NOT_FOUND = -32601
INVALID_PARAMS = -32602
INTERNAL_ERROR = -32603
HEADER_MISMATCH = -32020
UNSUPPORTED_VERSION = -32022

MAX_BODY = 1 << 20


class Tool:
    """One tool: its name, title, description, input schema, annotations and the function that runs it.

    run(arguments, context) returns (text, structured, is_error): the text block an LLM reads, an
    optional JSON object (structuredContent) and whether the call failed (a tool execution error,
    which the model can read and act on, as opposed to a protocol error).
    """

    def __init__(self, name, title, description, input_schema, run, annotations=None):
        self.name = name
        self.title = title
        self.description = description
        self.input_schema = input_schema
        self.run = run
        self.annotations = annotations or {}

    def listing(self):
        d = {"name": self.name, "title": self.title, "description": self.description, "inputSchema": self.input_schema}
        if self.annotations:
            d["annotations"] = dict(self.annotations, title=self.title)
        return d


class Server:
    def __init__(self, name, version, tools, instructions=None):
        self.info = {"name": name, "version": version}
        self.tools = {t.name: t for t in tools}
        self.order = [t.name for t in tools]
        self.instructions = instructions

    # ------------------------------------------------------------------ HTTP

    def handle_http(self, method, headers, body, context=None):
        """(status, response headers, body bytes) for one HTTP request to the endpoint.

        headers: a case-insensitive mapping (http.server's message) or a dict with lower-case keys.
        context: passed to the tools (who is calling, for the action log)."""
        h = _Headers(headers)
        if method in ("GET", "DELETE"):
            return 405, {"Allow": "POST"}, b""
        if method != "POST":
            return 405, {"Allow": "POST"}, b""
        ctype = (h.get("content-type") or "").split(";")[0].strip().lower()
        if ctype != "application/json":
            return self._http_error(415, None, INVALID_REQUEST, "Content-Type must be application/json")
        if body is None or len(body) > MAX_BODY:
            return self._http_error(413, None, INVALID_REQUEST, "request body too large")
        try:
            msg = json.loads(body.decode("utf-8"))
        except (ValueError, UnicodeDecodeError):
            return self._http_error(400, None, PARSE_ERROR, "parse error")
        sse = _wants_sse_only(h.get("accept"))
        hdr_version = (h.get("mcp-protocol-version") or "").strip() or None

        if isinstance(msg, list):
            # JSON-RPC batches: 2025-03-26 only
            version = hdr_version or "2025-03-26"
            if version != "2025-03-26" or not msg:
                return self._http_error(400, None, INVALID_REQUEST, "batches are not part of protocol version %s" % version)
            out = []
            for m in msg:
                status, resp = self._one(m, hdr_version, h, context, in_batch=True)
                if resp is not None:
                    out.append(resp)
            if not out:
                return 202, {}, b""
            return self._reply(200, out, sse)

        status, resp = self._one(msg, hdr_version, h, context)
        if resp is None:
            return 202, {}, b""
        return self._reply(status, resp, sse)

    def _reply(self, status, obj, sse):
        data = json.dumps(obj, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
        if sse:
            return status, {"Content-Type": "text/event-stream", "Cache-Control": "no-store", "X-Accel-Buffering": "no"}, \
                b"event: message\ndata: " + data + b"\n\n"
        return status, {"Content-Type": "application/json", "Cache-Control": "no-store"}, data

    def _http_error(self, status, mid, code, message, data=None):
        return status, {"Content-Type": "application/json", "Cache-Control": "no-store"}, \
            json.dumps(_error(mid, code, message, data)).encode()

    # ------------------------------------------------------------------ JSON-RPC

    def _one(self, msg, hdr_version, h, context, in_batch=False):
        """(HTTP status, response object or None) for one JSON-RPC message."""
        if not isinstance(msg, dict) or msg.get("jsonrpc") != "2.0":
            return 400, _error(msg.get("id") if isinstance(msg, dict) else None, INVALID_REQUEST, "not a JSON-RPC 2.0 message")
        if "method" not in msg:
            # a response or an error from the client (this server sends no requests): accepted
            if "result" in msg or "error" in msg:
                return 202, None
            return 400, _error(msg.get("id"), INVALID_REQUEST, "no method")
        method = msg.get("method")
        mid = msg.get("id")
        is_request = "id" in msg
        if not isinstance(method, str) or (is_request and (not isinstance(mid, (str, int)) or isinstance(mid, bool))):
            return 400, _error(mid if is_request else None, INVALID_REQUEST, "bad method or id")
        params = msg.get("params")
        if params is None:
            params = {}
        if not isinstance(params, dict):
            return 400, _error(mid, INVALID_PARAMS, "params must be an object")
        meta = params.get("_meta") if isinstance(params.get("_meta"), dict) else {}
        hm = h.get("mcp-method")
        if hm is not None and hm != method:
            return 400, _error(mid, HEADER_MISMATCH, "Header mismatch: Mcp-Method header value %r does not match body value %r" % (hm, method))

        if not is_request:
            # notifications: notifications/initialized, cancelled, ... nothing to do statelessly
            return 202, None

        modern = META_VERSION in meta
        if method == "initialize" and not modern:
            return 200, self._initialize(mid, params)
        if modern:
            version = meta.get(META_VERSION)
            if hdr_version is None:
                return 400, _error(mid, HEADER_MISMATCH, "Header mismatch: MCP-Protocol-Version header missing")
            if hdr_version != version:
                return 400, _error(mid, HEADER_MISMATCH, "Header mismatch: MCP-Protocol-Version header value %r does not match body value %r" % (hdr_version, version))
            if version != CURRENT:
                return 400, _error(mid, UNSUPPORTED_VERSION, "Unsupported protocol version",
                                   {"supported": list(SUPPORTED), "requested": version})
            if not isinstance(meta.get(META_CAPS), dict):
                return 400, _error(mid, INVALID_PARAMS, "params._meta is missing %s" % META_CAPS)
        else:
            version = hdr_version or "2025-03-26"
            if version not in LEGACY:
                if version == CURRENT:
                    return 400, _error(mid, INVALID_PARAMS, "protocol version %s needs params._meta with %s and %s" % (CURRENT, META_VERSION, META_CAPS))
                return 400, _error(mid, UNSUPPORTED_VERSION, "Unsupported protocol version",
                                   {"supported": list(SUPPORTED), "requested": version})
        if modern and h.get("mcp-name") is not None and method == "tools/call" and h.get("mcp-name") != params.get("name"):
            return 400, _error(mid, HEADER_MISMATCH, "Header mismatch: Mcp-Name header value %r does not match body value %r" % (h.get("mcp-name"), params.get("name")))

        if method == "ping" and not modern:
            return 200, _result(mid, {})
        if method == "server/discover" and modern:
            return 200, _result(mid, self._stamp({
                "supportedVersions": list(SUPPORTED),
                "capabilities": {"tools": {}},
                "instructions": self.instructions or "",
            }))
        if method == "tools/list":
            res = {"tools": [self.tools[n].listing() for n in self.order]}
            return 200, _result(mid, self._stamp(res) if modern else res)
        if method == "tools/call":
            name = params.get("name")
            tool = self.tools.get(name) if isinstance(name, str) else None
            if tool is None:
                return 200, _error(mid, INVALID_PARAMS, "Unknown tool: %r" % (name,))
            args = params.get("arguments")
            if args is None:
                args = {}
            if not isinstance(args, dict):
                return 200, _error(mid, INVALID_PARAMS, "arguments must be an object")
            try:
                text, structured, is_error = tool.run(args, context)
            except Exception as e:  # a bug in a tool: a tool error the model reads, not a crash
                text, structured, is_error = "Internal error in %s: %s" % (name, e), None, True
            res = {"content": [{"type": "text", "text": text}], "isError": bool(is_error)}
            if structured is not None:
                res["structuredContent"] = structured
            return 200, _result(mid, self._stamp(res) if modern else res)
        # modern: unknown methods are 404 on HTTP as well
        return (404 if modern else 200), _error(mid, METHOD_NOT_FOUND, "Method not found: %s" % method)

    def _stamp(self, res):
        res = dict(res)
        res["resultType"] = "complete"
        res["_meta"] = {META_SERVER: dict(self.info)}
        return res

    def _initialize(self, mid, params):
        asked = params.get("protocolVersion")
        version = asked if asked in LEGACY else LEGACY[0]
        res = {"protocolVersion": version, "capabilities": {"tools": {"listChanged": False}}, "serverInfo": dict(self.info)}
        if self.instructions:
            res["instructions"] = self.instructions
        return _result(mid, res)


def _result(mid, res):
    return {"jsonrpc": "2.0", "id": mid, "result": res}


def _error(mid, code, message, data=None):
    e = {"code": code, "message": message}
    if data is not None:
        e["data"] = data
    return {"jsonrpc": "2.0", "id": mid, "error": e}


def _wants_sse_only(accept):
    """True when the client accepts text/event-stream but not JSON (then one SSE event is sent)."""
    if not accept:
        return False
    kinds = [a.split(";")[0].strip().lower() for a in accept.split(",")]
    if "application/json" in kinds or "*/*" in kinds or "application/*" in kinds:
        return False
    return "text/event-stream" in kinds


class _Headers:
    def __init__(self, headers):
        self._h = headers

    def get(self, name):
        if self._h is None:
            return None
        if isinstance(self._h, dict):
            for k, v in self._h.items():
                if k.lower() == name:
                    return v
            return None
        return self._h.get(name)
