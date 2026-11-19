#!/usr/bin/env python3
"""teslalinux: what gpu-trace.sh recorded, per DRM client and ring.

  gpu-trace.py TRACE.txt [--client NAME=ID ...] [--tid NAME=TID ...]

For every client (DRM client id, see gpu-split.sh) and ring (v3d_bin, v3d_render, v3d_tfu, ...):
jobs per second, the time a job waited in the scheduler (queued -> run: behind other clients' jobs
and its own dependencies) and the time it ran on the GPU (run -> done), as mean / p50 / p90 / max
in ms. A v3d queue runs one job at a time, so the run time is the GPU's time for it.
With --tid, the off-CPU time of those threads (sched_switch), split by the state they left in
(D: uninterruptible, e.g. a wait inside a driver; S: a sleep, poll or fence wait).
"""
import argparse
import re
import statistics
from collections import defaultdict

EV = re.compile(r"^\s*(?P<task>.+?)-(?P<pid>\d+)\s+\[\d+\].*?\s(?P<ts>\d+\.\d+):\s+(?P<ev>\w+):\s+(?P<rest>.*)$")


def pct(v, p):
    if not v:
        return 0.0
    v = sorted(v)
    return v[min(len(v) - 1, int(p * len(v)))]


def summary(v):
    if not v:
        return "-"
    return "%6.2f %6.2f %6.2f %6.2f" % (statistics.mean(v), pct(v, 0.5), pct(v, 0.9), max(v))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("trace")
    ap.add_argument("--client", action="append", default=[], help="NAME=ID: a name for a DRM client id")
    ap.add_argument("--tid", action="append", default=[], help="NAME=TID: threads whose off-CPU time to show")
    a = ap.parse_args()
    names = {int(c.split("=")[1]): c.split("=")[0] for c in a.client}
    tids = {int(t.split("=")[1]): t.split("=")[0] for t in a.tid}

    jobs = {}  # fence -> dict
    first = last = None
    off = defaultdict(lambda: defaultdict(list))  # tid -> state -> [ms]
    left = {}  # tid -> (ts, state)
    with open(a.trace) as f:
        for line in f:
            m = EV.match(line)
            if not m:
                continue
            ts = float(m["ts"])
            first = ts if first is None else first
            last = ts
            ev, rest = m["ev"], m["rest"]
            if ev in ("drm_sched_job_queue", "drm_sched_job_run"):
                fence = re.search(r"fence=(\d+:\d+)", rest).group(1)
                ring = re.search(r"ring=([\w-]+)", rest).group(1)
                client = int(re.search(r"client_id:(\d+)", rest).group(1))
                j = jobs.setdefault(fence, {"ring": ring, "client": client})
                j["queue" if ev == "drm_sched_job_queue" else "run"] = ts
            elif ev == "drm_sched_job_add_dep":
                fence, dep = re.findall(r"fence=(\d+:\d+)", rest)[:2]
                jobs.setdefault(fence, {}).setdefault("deps", []).append(dep)
            elif ev == "drm_sched_job_done":
                fence = re.search(r"fence=(\d+:\d+)", rest).group(1)
                if fence in jobs:
                    jobs[fence]["done"] = ts
            elif ev == "sched_switch":
                pp = int(re.search(r"prev_pid=(\d+)", rest).group(1))
                ps = re.search(r"prev_state=(\S+)", rest).group(1)
                npid = int(re.search(r"next_pid=(\d+)", rest).group(1))
                if pp in tids:
                    left[pp] = (ts, ps[0])
                if npid in tids and npid in left:
                    t0, st = left.pop(npid)
                    off[npid][st].append((ts - t0) * 1000)
    span = (last - first) if first is not None else 0
    print("trace span %.2f s, %d jobs" % (span, len(jobs)))
    per = defaultdict(lambda: {"wait": [], "run": [], "n": 0})
    busy = defaultdict(float)
    for j in jobs.values():
        if "queue" not in j or "run" not in j or "done" not in j:
            continue
        k = (names.get(j["client"], "client%d" % j["client"]), j["ring"])
        per[k]["n"] += 1
        per[k]["wait"].append((j["run"] - j["queue"]) * 1000)
        per[k]["run"].append((j["done"] - j["run"]) * 1000)
        busy[k] += j["done"] - j["run"]
    print("%-14s %-16s %7s %6s | %-27s | %-27s" % ("client", "ring", "jobs/s", "busy", "wait ms mean p50 p90 max", "run ms mean p50 p90 max"))
    for k in sorted(per):
        p = per[k]
        print("%-14s %-16s %7.1f %5.1f%% | %s | %s" % (k[0], k[1], p["n"] / span if span else 0, 100 * busy[k] / span if span else 0, summary(p["wait"]), summary(p["run"])))
    # what each job waited for: the dependency that finished last before it ran (a job of another
    # client or ring, or a fence that is no scheduler job, e.g. a sync file), else the GPU queue itself
    print("what the waits (queued -> run) were for, ms/s of waiting per client and ring:")
    blame = defaultdict(lambda: defaultdict(float))
    for j in jobs.values():
        if "queue" not in j or "run" not in j or "ring" not in j:
            continue
        k = (names.get(j["client"], "client%d" % j["client"]), j["ring"])
        last_dep, last_t = None, j["queue"]
        for d in j.get("deps", []):
            dj = jobs.get(d)
            t = dj.get("done") if dj else None
            if t is not None and last_t < t <= j["run"] + 1e-4:
                last_dep, last_t = d, t
        dep_wait = last_t - j["queue"]
        if last_dep:
            dj = jobs[last_dep]
            if "client" in dj:
                who = "%s/%s" % (names.get(dj["client"], "client%d" % dj["client"]), dj["ring"])
            else:
                who = "fence %s" % last_dep.split(":")[0]
            blame[k]["dep " + who] += dep_wait
        blame[k]["queue (GPU busy)"] += j["run"] - last_t
    for k in sorted(blame):
        print("  %s %s: %s" % (k[0], k[1], ", ".join("%s %.0f" % (w, 1000 * v / span) for w, v in sorted(blame[k].items(), key=lambda x: -x[1]) if v * 1000 / span >= 1)))
    for tid, states in off.items():
        print("thread %s (%d) off CPU:" % (tids[tid], tid))
        for st, v in sorted(states.items()):
            print("  %s: %5d times, %7.1f ms/s total, %s (mean p50 p90 max ms)" % (st, len(v), sum(v) / span if span else 0, summary(v)))


if __name__ == "__main__":
    main()
