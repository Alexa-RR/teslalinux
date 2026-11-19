#!/bin/sh
# teslalinux: GPU time per process and per DRM file (one per GL/EGL context) over N seconds, from the
# kernel's DRM fdinfo (drm-engine-<engine>: nanoseconds busy). On a Pi 4 (v3d) the engines are bin,
# render, tfu, csd, cache_clean; a job counts from its start to its end, so jobs that overlap can add
# up to more than 100%. Run as root (other users' fdinfo):
#   sudo gpu-split.sh [SECONDS]
S=${1:-10}
D=$(mktemp -d)
trap 'rm -rf "$D"' EXIT
snap() {
	for f in /proc/[0-9]*/fdinfo/*; do
		grep -q '^drm-engine-' "$f" 2>/dev/null || continue
		rest=${f#/proc/}
		pid=${rest%%/*}
		fd=${rest##*/}
		comm=$(tr -d '\0 ' < "/proc/$pid/comm" 2>/dev/null)
		client=$(awk '/^drm-client-id:/{print $2}' "$f")
		awk -v k="$pid $comm fd$fd client$client" '/^drm-engine-/{sub(":", "", $1); print k, $1, $2}' "$f"
	done 2>/dev/null
}
snap > "$D/a"
sleep "$S"
snap > "$D/b"
# a client (a DRM context) opened through several fds is counted once: the largest fd wins
awk -v s="$S" '
	NR == FNR { a[$1" "$2" "$4" "$5] = $6; next }
	{ k = $1" "$2" "$4" "$5; d = $6 - a[k]; if (d > 0 && !(k in seen)) { seen[k] = 1; printf "%-34s %-18s %6.1f%%\n", $1" "$2" "$4, $5, 100 * d / (s * 1e9) } }
' "$D/a" "$D/b" | sort -k4 -rn
