#!/bin/sh
# teslalinux: one app start, then a fixed UI sequence (ui-step.py next to it); reports the main thread's CPU time (ms) for the sequence
# and the whole process's CPU time, so builds and runtimes can be compared
sh "$(dirname "$0")/app-starttime.sh"
sleep 10
P=$(pgrep -f "[a]ndroid-translation-layer" | head -1)
TCK=$(getconf CLK_TCK)
main_ms() { sudo awk -v t=$TCK '{ print ($14 + $15) * 1000 / t }' /proc/$P/task/$P/stat; }
proc_ms() { sudo awk -v t=$TCK '{ print ($14 + $15) * 1000 / t }' /proc/$P/stat; }
M0=$(main_ms); A0=$(proc_ms); T0=$(date +%s.%N)
python3 "$(dirname "$0")/ui-step.py" /tmp/bench.png tap:117,514 wait:1 swipe:314,605,314,250,300 wait:2 swipe:314,600,314,300,300 wait:2 \
	tap:314,70 wait:2 type:dizengoff wait:3 key:escape wait:2 key:escape wait:2 tap:34,34 wait:2 key:escape wait:2 \
	swipe:900,300,700,400,400 wait:2 pinch:900,350,150,350,500 wait:2 >/dev/null 2>&1
M1=$(main_ms); A1=$(proc_ms); T1=$(date +%s.%N)
echo "sequence $(echo "$T1 - $T0" | bc) s: main thread $(echo "$M1 - $M0" | bc) ms CPU, process $(echo "$A1 - $A0" | bc) ms CPU"
