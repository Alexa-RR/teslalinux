#!/usr/bin/env python3
"""tls-server.py PORT CHAIN.pem KEY.pem [ALPN,...]: the TLS test server for TlsCheck.

One thread per connection (parallel handshakes really run in parallel), TLS 1.3 with two session
tickets per connection, ALPN when given. A request gets "New, <version>, Cipher is <suite>" or
"Reused, ..." (the wording of openssl s_server -www) and the connection closes; a connection that
negotiates h2 is only held open until the client closes it (no HTTP/2 here: TlsCheck only checks the
negotiation).
"""
import socket
import ssl
import sys
import threading

port, chain, key = int(sys.argv[1]), sys.argv[2], sys.argv[3]
ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
ctx.load_cert_chain(chain, key)
ctx.num_tickets = 2
if len(sys.argv) > 4:
    ctx.set_alpn_protocols(sys.argv[4].split(","))


def serve(conn):
    try:
        with ctx.wrap_socket(conn, server_side=True) as s:
            s.settimeout(15)
            if s.selected_alpn_protocol() == "h2":
                s.recv(1)
                return
            data = b""
            while b"\r\n\r\n" not in data:
                d = s.recv(4096)
                if not d:
                    return
                data += d
            body = "%s, %s, Cipher is %s\n" % ("Reused" if s.session_reused else "New", s.version(), s.cipher()[0])
            s.sendall(("HTTP/1.0 200 OK\r\nContent-Type: text/plain\r\nContent-Length: %d\r\n\r\n%s"
                       % (len(body), body)).encode())
    except (OSError, ssl.SSLError):
        pass


ls = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
ls.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
ls.bind(("127.0.0.1", port))
ls.listen(64)
print("listening", port, flush=True)
while True:
    c, _ = ls.accept()
    threading.Thread(target=serve, args=(c,), daemon=True).start()
