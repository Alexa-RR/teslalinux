#!/usr/bin/env python3
"""PalGate's cloud API, as a linked device (our own implementation; docs/GATES.md has the protocol
as we understand it and where it was learnt).

The phone app links a device by scanning a QR code with {"id": "<uuid4>"}; the device asks
un/secondary/init/<uuid> until the phone has approved, and gets the account's phone number, a
16-byte session token and the link's token type (1 or 2). Every request after that carries a
fresh derived token in X-Bt-Token:

    K1 = FA D3 25 72 81 29 | phone (48 bits, big-endian) | 3A B4 5A 65
    K2 = AES-128-ECB decrypt(key K1, session token)
    B  = 00 0A 0A 00 00 00 00 00 00 00 | (unix seconds + 2, 32 bits big-endian) | 00 00
    token = hex upper of: (type << 4 | 1) | phone (48 bits) | AES-128-ECB encrypt(key K2, B)

so the device's clock must be right (it is, from NTP or the RTC; a token is accepted for a few
seconds only). Nothing here logs the phone number or the tokens.
"""
import json
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

DEFAULT_BASE = "https://api1.pal-es.com/v1/bt/"
USER_AGENT = "okhttp/4.9.3"
TIMESTAMP_OFFSET = 2
KEY_HEAD = bytes.fromhex("FAD325728129")
KEY_TAIL = bytes.fromhex("3AB45A65")
TOKEN_TYPES = (0, 1, 2)  # SMS login, first linked device, second linked device


class Error(Exception):
    """a failed request: code is a short word for logs, str() the reason in words for the driver,
    fatal whether trying again cannot help (linking)"""

    def __init__(self, code, message, fatal=False, status=None):
        super().__init__(message)
        self.code = code
        self.fatal = fatal
        self.status = status


def _aes_block(key, block, decrypt):
    c = Cipher(algorithms.AES(key), modes.ECB())
    op = c.decryptor() if decrypt else c.encryptor()
    return op.update(block) + op.finalize()


def derive_token(session_token, phone, token_type, now=None):
    """the X-Bt-Token for this second: session_token 16 bytes (or 32 hex), phone an int or digits"""
    if isinstance(session_token, str):
        session_token = bytes.fromhex(session_token)
    if len(session_token) != 16:
        raise ValueError("session token must be 16 bytes")
    if token_type not in TOKEN_TYPES:
        raise ValueError("token type")
    p = int(phone)
    if not 0 < p < (1 << 48):
        raise ValueError("phone number")
    phone6 = p.to_bytes(6, "big")
    k2 = _aes_block(KEY_HEAD + phone6 + KEY_TAIL, session_token, decrypt=True)
    t = int(time.time() if now is None else now) + TIMESTAMP_OFFSET
    block = bytes([0x00, 0x0A, 0x0A]) + bytes(7) + (t & 0xFFFFFFFF).to_bytes(4, "big") + bytes(2)
    return (bytes([(token_type << 4) | 1]) + phone6 + _aes_block(k2, block, decrypt=False)).hex().upper()


def normalise_gate(d):
    """PalGate's device object -> what the car page needs"""
    gid = str(d.get("id") or "")
    if not gid:
        return None
    name = d.get("customName1") or d.get("name1") or d.get("name") or gid
    outputs = []
    names = {}
    for n in (1, 2):
        present = d.get("output%d" % n)
        if present or (n == 1 and present is None):
            outputs.append(n)
            names[str(n)] = str(d.get("customName%d" % n) or d.get("name%d" % n) or ("Output %d" % n))
    if not outputs:
        outputs = [1]
        names["1"] = str(name)
    return {"id": gid, "name": str(name), "address": str(d.get("address") or ""),
            "outputs": outputs, "outputNames": names, "localOnly": bool(d.get("localOnly")),
            "admin": bool(d.get("admin"))}


class Client:
    def __init__(self, base=DEFAULT_BASE, timeout=10.0):
        self.base = base if base.endswith("/") else base + "/"
        self.timeout = timeout

    # ---------------- requests
    def _get(self, path, account=None, timeout=None):
        headers = {"User-Agent": USER_AGENT, "Accept": "*/*", "Content-Type": "application/json"}
        if account is not None:
            headers["X-Bt-Token"] = derive_token(account["token"], account["phone"], int(account["tokenType"]))
        req = urllib.request.Request(self.base + path, headers=headers, method="GET")
        try:
            with urllib.request.urlopen(req, timeout=timeout or self.timeout) as r:
                raw = r.read(1 << 20)
                status = r.status
        except urllib.error.HTTPError as e:
            raw, status = e.read(1 << 16), e.code
        except (urllib.error.URLError, OSError) as e:
            raise Error("network", "PalGate could not be reached (%s)." % getattr(e, "reason", e)) from None
        try:
            body = json.loads(raw.decode("utf-8")) if raw else {}
        except (ValueError, UnicodeDecodeError):
            body = {}
        if not isinstance(body, dict):
            body = {}
        return self._check(status, body)

    @staticmethod
    def _check(status, body):
        st = str(body.get("status", ""))
        if status == 401 or st == "401" or body.get("errId") == 7101:
            raise Error("unauthorized", "PalGate no longer accepts this device's link (or the device's clock is wrong). "
                        "Link it again in Settings > Gates.", fatal=True, status=status)
        if status == 429:
            raise Error("rate-limited", "PalGate asks to slow down. Try again in a minute.", status=status)
        if status >= 500:
            raise Error("server", "PalGate's server had a problem (%d). Try again." % status, status=status)
        if status >= 400:
            raise Error("http-%d" % status, "PalGate refused the request (%d)." % status, fatal=True, status=status)
        if st != "ok" or body.get("err"):
            msg = body.get("msg") or body.get("err") or "no reason given"
            raise Error("failed", "PalGate refused: %s." % str(msg)[:120], status=status)
        return body

    # ---------------- linking
    @staticmethod
    def link_begin():
        """a new link id and the text of its QR code"""
        link_id = str(uuid.uuid4())
        return link_id, json.dumps({"id": link_id})

    def link_poll(self, link_id):
        """the account once the phone has approved this link id, else None"""
        try:
            body = self._get("un/secondary/init/" + urllib.parse.quote(link_id, safe=""), timeout=30)
        except Error as e:
            if e.code == "failed" or (e.status is not None and 400 <= e.status < 500 and e.status not in (401, 429)):
                return None  # not approved yet
            if e.code == "rate-limited":
                time.sleep(10)
                return None
            raise
        user = body.get("user")
        if not isinstance(user, dict) or not user.get("token") or not user.get("id"):
            return None
        try:
            token_type = int(body.get("secondary"))
            phone = int(str(user["id"]))
            bytes.fromhex(str(user["token"]))
        except (TypeError, ValueError):
            raise Error("bad-link", "PalGate's answer to the link was not understood.", fatal=True) from None
        if token_type not in (1, 2) or len(str(user["token"])) != 32:
            raise Error("bad-link", "PalGate's answer to the link was not understood.", fatal=True)
        return {"phone": str(phone), "token": str(user["token"]).lower(), "tokenType": token_type,
                "name": " ".join(x for x in (user.get("firstname"), user.get("lastname")) if isinstance(x, str) and x)}

    def unlink(self, account):
        """ask PalGate to drop this linked device (the device forgets the account either way)"""
        self._get("secondary/unlink?secondary=%d" % int(account["tokenType"]), account)

    # ---------------- the account's gates
    def check(self, account):
        return self._get("user/check-token?ts=%d&ts_diff=0" % int(time.time()), account)

    def devices(self, account):
        body = self._get("devices/", account)
        devs = body.get("devices")
        if not isinstance(devs, list):
            raise Error("bad-list", "PalGate's gate list was not understood.")
        out = [normalise_gate(d) for d in devs if isinstance(d, dict)]
        return [g for g in out if g]

    def open(self, account, gate_id, output=1):
        """open a gate's output (1 or 2). A real-world action: only on the driver's tap or their
        auto-open setting."""
        if output not in (1, 2):
            raise ValueError("output")
        self._get("device/%s/open-gate?openBy=100&outputNum=%d" % (urllib.parse.quote(str(gate_id), safe=""), output), account)
        return True
