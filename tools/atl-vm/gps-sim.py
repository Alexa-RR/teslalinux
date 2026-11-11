#!/usr/bin/env python3
"""teslalinux test tool: a fake Tesla GPS bridge that drives the car along a scripted path.

Serves the same line protocol as services/gps/gps.py ("lat lon speed_mps bearing_deg accuracy_m
timestamp_ms" once per second) on a TCP port, so ATL can be launched with
ATL_LOCATION_SOURCE=tesla:127.0.0.1:PORT and Waze sees a moving vehicle.

usage: gps-sim.py [--port 9997] [--start LAT,LON] [--leg BEARING,SECONDS,SPEED]... [--loop]
  default: start at the VM's fixed position, drive north for 45 s at 8 m/s, then east for 60 s, stop.
"""
import argparse
import math
import socket
import threading
import time

EARTH_M = 6371000.0


def step(lat, lon, bearing_deg, meters):
    b = math.radians(bearing_deg)
    dlat = meters * math.cos(b) / EARTH_M
    dlon = meters * math.sin(b) / (EARTH_M * math.cos(math.radians(lat)))
    return lat + math.degrees(dlat), lon + math.degrees(dlon)


def serve(conn, args):
    lat, lon = args.start
    t0 = time.time()
    legs = list(args.legs)
    leg_i, leg_t = 0, 0.0
    try:
        while True:
            now = time.time()
            if leg_i < len(legs):
                bearing, seconds, speed = legs[leg_i]
                lat, lon = step(lat, lon, bearing, speed)
                leg_t += 1
                if leg_t >= seconds:
                    leg_i, leg_t = leg_i + 1, 0.0
                    if leg_i >= len(legs) and args.loop:
                        leg_i = 0
            else:
                bearing, speed = legs[-1][0] if legs else 0, 0.0
            line = "%.7f %.7f %.2f %.1f %.1f %d\n" % (lat, lon, speed, bearing, 5.0, int(now * 1000))
            conn.sendall(line.encode())
            time.sleep(max(0.0, 1.0 - (time.time() - now)))
    except (BrokenPipeError, ConnectionResetError, OSError):
        pass
    finally:
        conn.close()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=9997)
    ap.add_argument("--start", default="32.0853,34.7818")
    ap.add_argument("--leg", action="append", default=[], help="BEARING,SECONDS,SPEED_MPS (repeatable)")
    ap.add_argument("--loop", action="store_true")
    args = ap.parse_args()
    args.start = tuple(float(x) for x in args.start.split(","))
    args.legs = [tuple(float(x) for x in leg.split(",")) for leg in args.leg] or [(0, 45, 8.0), (90, 60, 8.0)]
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", args.port))
    srv.listen(4)
    print("gps-sim: serving on %d, start %s, legs %s" % (args.port, args.start, args.legs), flush=True)
    while True:
        conn, _ = srv.accept()
        threading.Thread(target=serve, args=(conn, args), daemon=True).start()


if __name__ == "__main__":
    main()
