#!/usr/bin/env python3
"""teslalinux web stack check: talks to a running nginx (host/local/web-local.sh, or the VM's
tools/atl-vm/carstack.sh, or the car) the way the Tesla browser does (SNI + Host = the car's names)
and checks every item of upstream's lighttpd.conf parity list. Stdlib only.

  python3 host/local/check.py [--addr 127.0.0.1] [--https-port 8443] [--http-port 8880] [--env FILE]
                              [--expect-display]   (tacomp running: /sockets/display must upgrade)
"""
import argparse
import base64
import http.client
import json
import os
import socket
import ssl
import subprocess
import sys
import time

A = None
HOST = "car.alexahub.dev"
results = []


def device_host(env_path):
    if env_path and os.path.exists(env_path):
        out = subprocess.run(["sh", "-c", '. "$1"; printf %s "$DEVICE_HOST"', "sh", env_path],
                             capture_output=True, text=True).stdout.strip()
        if out:
            return out
    return HOST


def ctx(alpn=None):
    c = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    c.check_hostname = False
    c.verify_mode = ssl.CERT_NONE  # the local certificate is self-signed; its SANs are checked below
    if alpn:
        c.set_alpn_protocols(alpn)
    return c


def connect(tls, sni):
    s = socket.create_connection((A.addr, A.https_port if tls else A.http_port), timeout=5)
    if tls:
        s = ctx().wrap_socket(s, server_hostname=sni if not sni[0].isdigit() else None)
    return s


def request(path, host=None, tls=True, method="GET", body=None, headers=None):
    host = host or HOST
    s = connect(tls, host)
    hdrs = {"Host": host, "Connection": "close", "User-Agent": "teslalinux-check"}
    hdrs.update(headers or {})
    data = body.encode() if isinstance(body, str) else (body or b"")
    if body is not None:
        hdrs["Content-Length"] = str(len(data))
    req = "%s %s HTTP/1.1\r\n%s\r\n\r\n" % (method, path, "\r\n".join("%s: %s" % kv for kv in hdrs.items()))
    s.sendall(req.encode() + data)
    r = http.client.HTTPResponse(s)
    r.begin()
    out = (r.status, {k.lower(): v for k, v in r.getheaders()}, r.read())
    s.close()
    return out


def ws_upgrade(path, host=None):
    s = connect(True, host or HOST)
    key = base64.b64encode(os.urandom(16)).decode()
    s.sendall(("GET %s HTTP/1.1\r\nHost: %s\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
               "Sec-WebSocket-Key: %s\r\nSec-WebSocket-Version: 13\r\nOrigin: https://%s\r\n\r\n"
               % (path, host or HOST, key, host or HOST)).encode())
    head = b""
    while b"\r\n\r\n" not in head:
        chunk = s.recv(4096)
        if not chunk:
            break
        head += chunk
    status = int(head.split(b" ", 2)[1]) if head.startswith(b"HTTP/") else 0
    return status, s


def ws_send_text(s, text):
    payload = text.encode()
    mask = os.urandom(4)
    hdr = bytes([0x81])
    n = len(payload)
    hdr += bytes([0x80 | n]) if n < 126 else bytes([0x80 | 126]) + n.to_bytes(2, "big")
    s.sendall(hdr + mask + bytes(b ^ mask[i % 4] for i, b in enumerate(payload)))


def check(name, cond, detail=""):
    results.append((bool(cond), name, detail))
    print("%s %s%s" % ("ok  " if cond else "FAIL", name, ("  [" + detail + "]") if detail and not cond else ""))


NOCACHE = "no-store, no-cache, must-revalidate, max-age=0"


def app_headers(h, label):
    check(label + ": COEP credentialless", h.get("cross-origin-embedder-policy") == "credentialless", str(h.get("cross-origin-embedder-policy")))
    check(label + ": COOP same-origin", h.get("cross-origin-opener-policy") == "same-origin", str(h.get("cross-origin-opener-policy")))


def main():
    global A, HOST
    ap = argparse.ArgumentParser()
    ap.add_argument("--addr", default="127.0.0.1")
    ap.add_argument("--https-port", type=int, default=8443)
    ap.add_argument("--http-port", type=int, default=8880)
    ap.add_argument("--env")
    ap.add_argument("--expect-display", action="store_true")
    A = ap.parse_args()
    HOST = device_host(A.env)
    print("checking %s (https :%d, http :%d) as %s" % (A.addr, A.https_port, A.http_port, HOST))

    # --- TLS: names, cipher preference, ALPN per server block
    s = socket.create_connection((A.addr, A.https_port), timeout=5)
    t = ctx(["h2", "http/1.1"]).wrap_socket(s, server_hostname=HOST)
    der = t.getpeercert(binary_form=True)
    cipher, alpn, version = t.cipher()[0], t.selected_alpn_protocol(), t.version()
    t.close()
    pem = ssl.DER_cert_to_PEM_cert(der)
    text = subprocess.run(["openssl", "x509", "-noout", "-ext", "subjectAltName"], input=pem,
                          capture_output=True, text=True).stdout
    sans = sorted(x.strip() for x in text.splitlines()[-1].split(",")) if text else []
    want = sorted("DNS:" + n for n in (HOST, "www." + HOST, "fullscreen." + HOST, "www.fullscreen." + HOST))
    check("certificate SANs = the four car names", sans == want, " ".join(sans))
    check("TLS 1.3 negotiated", version == "TLSv1.3", version)
    check("server prefers ChaCha20-Poly1305 (Pi 4 has no AES instructions)", "CHACHA20" in cipher, cipher)
    check("HTTP/2 offered for the app host", alpn == "h2", str(alpn))

    # --- the app host block (lighttpd.conf:71-124)
    code, h, body = request("/")
    check("GET / 200", code == 200, str(code))
    check("text/html; charset=utf-8", h.get("content-type") == "text/html; charset=utf-8", str(h.get("content-type")))
    app_headers(h, "/")
    check("/: Cache-Control no-store set", h.get("cache-control") == NOCACHE, str(h.get("cache-control")))
    check("/: Pragma no-cache", h.get("pragma") == "no-cache", str(h.get("pragma")))
    check("/: Expires 0", h.get("expires") == "0", str(h.get("expires")))
    check("/: no ETag", "etag" not in h, str(h.get("etag")))
    check("/ is the launcher", b"cert-warning.js" in body and b"app.js" in body)
    for path, ctype in (("/js/display/renderers/h264-broadway/avc.wasm", "application/wasm"),
                        ("/js/core/app.js", "text/javascript; charset=utf-8"),
                        ("/css/app.css", "text/css; charset=utf-8"),
                        ("/fonts/Roboto-Regular.ttf", "font/ttf"),
                        ("/fonts/MaterialIcons-Regular.otf", "font/otf"),
                        ("/assets/donations-qr.svg", "image/svg+xml"),
                        ("/assets/tesla-android-logo.png", "image/png")):
        code, h, _ = request(path)
        check("%s -> %s" % (path, ctype), code == 200 and h.get("content-type") == ctype, "%s %s" % (code, h.get("content-type")))

    code, h, body = request("/api/configuration")
    check("/api/configuration 200 JSON", code == 200 and body.strip().startswith(b"{"), str(code))
    app_headers(h, "/api")
    check("/api: Cache-Control max-age=1 (expire 1 s)", h.get("cache-control") == "max-age=1", str(h.get("cache-control")))
    check("/api: Expires is a date, no Pragma", h.get("expires", "0") != "0" and "pragma" not in h, "%s %s" % (h.get("expires"), h.get("pragma")))
    try:
        cfg = json.loads(body)
        check("/api/configuration has upstream's flat persist.* keys", "persist.tesla-android.gps.is_active" in cfg)
    except ValueError:
        check("/api/configuration parses", False)
    check("/api/health 200", request("/api/health")[0] == 200)
    code, _, body = request("/api/gpsState", method="POST", body="0", headers={"Content-Type": "text/plain"})
    check("POST /api/gpsState text/plain -> OK", (code, body) == (200, b"OK"), "%s %r" % (code, body))
    code, _, body = request("/api/certificate")
    check("/api/certificate state", code == 200 and b"notAfter" in body and b'"slot"' in body, str(code))

    code, h, _ = request("/README.md")
    check("url.access-deny .md -> 403", code == 403, str(code))
    check("403 still carries no-store", h.get("cache-control") == NOCACHE, str(h.get("cache-control")))
    for p in ("/x.inc", "/a.yml", "/b.ini", "/index.html~"):
        check("url.access-deny %s -> 403" % p, request(p)[0] == 403)
    code, h, body = request("/no/such/page")
    check("unknown path -> 404 page", code == 404 and b"Page not found" in body, str(code))
    check("/stream -> 404 (dead upstream endpoint, not proxied)", request("/stream")[0] == 404)

    for host in ("www." + HOST, "fullscreen." + HOST, "www.fullscreen." + HOST, "172.16.0.1", "192.168.1.10", "10.1.2.3"):
        code, h, _ = request("/", host=host)
        check("app on %s" % host, code == 200 and h.get("cross-origin-embedder-policy") == "credentialless", str(code))
    code, h, _ = request("/", host="172.15.0.1")
    check("172.15.x is not private: base server, no COEP", code == 200 and "cross-origin-embedder-policy" not in h)

    code, h, _ = request("/", tls=False)
    check("http app host like upstream (no redirect, same headers)", code == 200 and h.get("cross-origin-embedder-policy") == "credentialless", str(code))

    # --- offline mode probes (lighttpd.conf:131-144) and the base server
    for host in ("connman.vn.tesla.services", "connman.vn.cloud.tesla.cn"):
        code, h, body = request("/online/connectivity_check.txt", host=host, tls=False)
        check("%s: 200 'online'" % host, code == 200 and body == b"online", "%s %r" % (code, body[:20]))
        check("%s: X-ConnMan-Status online + X-Cache" % host,
              h.get("x-connman-status") == "online" and h.get("x-cache") == "Hit from cloudfront", str(h.get("x-connman-status")))
        check("%s: no app headers" % host, "cross-origin-embedder-policy" not in h)
    for host in ("www.teslamotors.com", "www.tesla.com", "www.tesla.cn"):
        code, h, _ = request("/", host=host, tls=False)
        check("%s: base server 200, no COEP, no proxies" % host, code == 200 and "cross-origin-embedder-policy" not in h, str(code))
        check("%s: /api not proxied" % host, request("/api/health", host=host, tls=False)[0] == 404)

    # --- fullscreen launcher (lighttpd.conf:126-129, www-redirect/index.html)
    for host in ("youtu.be", "www.youtu.be"):
        code, h, body = request("/", host=host, tls=False)
        check("%s: redirect page" % host, code == 200 and ("https://fullscreen." + HOST).encode() in body
              and b"setTimeout" in body and b"3000" in body, str(code))

    # --- WebSockets (lighttpd.conf:88-114)
    st, ws = ws_upgrade("/sockets/gps")
    check("/sockets/gps upgrades (101)", st == 101, str(st))
    if st == 101:
        feed = None
        try:
            feed = socket.create_connection((A.addr, 9997), timeout=3) if A.addr in ("127.0.0.1", "localhost") else None
        except OSError:
            feed = None
        time.sleep(0.2)
        ws_send_text(ws, json.dumps({"latitude": "32.08", "longitude": "34.78", "speed": "null", "bearing": "null",
                                     "vertical_accuracy": "8", "timestamp": "1759900000000"}))
        if feed:
            feed.settimeout(3)
            try:
                line = feed.recv(200).decode()
            except OSError:
                line = ""
            check("GPS end to end: browser JSON -> feed line with NaN", line.startswith("32.0800000 34.7800000 NaN NaN 8.0"), line.strip())
            feed.close()
        ws.close()
    st, ws = ws_upgrade("/sockets/audio")
    check("/sockets/audio upgrades (101)", st == 101, str(st))
    ws.close()
    for path in ("/sockets/display", "/sockets/touchscreen"):
        st, ws = ws_upgrade(path)
        ws.close()
        if A.expect_display:
            check("%s upgrades (101)" % path, st == 101, str(st))
        else:
            print("info %s -> %d (tacomp is not part of the local stack; 502 expected)" % (path, st))

    bad = [r for r in results if not r[0]]
    print("--- %d checks, %d failed" % (len(results), len(bad)))
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
