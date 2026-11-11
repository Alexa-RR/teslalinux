#!/usr/bin/env python3
"""teslalinux: a TCP relay in front of a QEMU user-net port forward.

  tcp-relay.py LISTEN_PORT TARGET_PORT [--host 127.0.0.1] [--bind 127.0.0.1]

--bind 0.0.0.0 also serves other devices on the local network (a phone on the same Wi-Fi opens
https://<the Mac's LAN address>:LISTEN_PORT; nginx serves the car page to private-range addresses).

QEMU's hostfwd (libslirp) listens with a backlog of 1, so a client that opens many connections at
once (a phone loading the car page through ngrok, which dials one upstream connection per
request) gets most of them reset. This relay accepts with a normal backlog and retries each
upstream connect until slirp takes it, then copies bytes both ways (one thread per direction).
TLS passes through untouched.
"""
import argparse
import socket
import threading
import time


def connect(host, port, tries=300):
    delay = 0.005
    for _ in range(tries):
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        try:
            s.connect((host, port))
            s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            return s
        except OSError:
            s.close()
            time.sleep(delay)
            delay = min(delay * 1.5, 0.1)
    return None


def pipe(src, dst):
    try:
        while True:
            data = src.recv(65536)
            if not data:
                break
            dst.sendall(data)
    except OSError:
        pass
    finally:
        for s in (src, dst):
            try:
                s.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass


def handle(client, host, port):
    upstream = connect(host, port)
    if upstream is None:
        client.close()
        return
    t = threading.Thread(target=pipe, args=(upstream, client), daemon=True)
    t.start()
    pipe(client, upstream)
    t.join()
    client.close()
    upstream.close()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("listen", type=int)
    ap.add_argument("target", type=int)
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--bind", default="127.0.0.1")
    a = ap.parse_args()
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind((a.bind, a.listen))
    srv.listen(256)
    while True:
        client, _ = srv.accept()
        client.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        threading.Thread(target=handle, args=(client, a.host, a.target), daemon=True).start()


if __name__ == "__main__":
    main()
