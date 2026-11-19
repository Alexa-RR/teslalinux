#!/bin/sh
# teslalinux: a timeline of the GPU jobs on a Pi (v3d) over N seconds, from the kernel's DRM scheduler
# tracepoints (gpu_scheduler: a job queued, run, done; with the DRM client id gpu-split.sh prints per
# process) plus the scheduler switches of the given threads (when tacomp's main thread sleeps, e.g.
# in a read-back). Run as root:
#   sudo gpu-trace.sh SECONDS OUT.txt [TID...]
# then analyse OUT.txt with gpu-trace.py. The trace buffer is cleared and the events are turned off
# again afterwards.
S=${1:-5}
OUT=${2:-/tmp/gpu-trace.txt}
shift 2 2>/dev/null
T=/sys/kernel/tracing
[ -d "$T/events/gpu_scheduler" ] || { echo "no gpu_scheduler tracepoints in $T" >&2; exit 1; }
echo 0 > "$T/tracing_on"
echo > "$T/trace"
echo 16384 > "$T/buffer_size_kb"
for e in drm_sched_job_queue drm_sched_job_run drm_sched_job_done drm_sched_job_add_dep; do
	echo 1 > "$T/events/gpu_scheduler/$e/enable"
done
filter=""
for tid in "$@"; do
	[ -n "$filter" ] && filter="$filter || "
	filter="${filter}prev_pid == $tid || next_pid == $tid"
done
if [ -n "$filter" ]; then
	echo "$filter" > "$T/events/sched/sched_switch/filter"
	echo 1 > "$T/events/sched/sched_switch/enable"
fi
echo 1 > "$T/tracing_on"
sleep "$S"
echo 0 > "$T/tracing_on"
cat "$T/trace" > "$OUT"
for e in drm_sched_job_queue drm_sched_job_run drm_sched_job_done drm_sched_job_add_dep; do
	echo 0 > "$T/events/gpu_scheduler/$e/enable"
done
echo 0 > "$T/events/sched/sched_switch/enable"
echo 0 > "$T/events/sched/sched_switch/filter"
echo > "$T/trace"
echo "$(grep -c drm_sched_job_queue "$OUT") jobs queued in ${S}s -> $OUT"
