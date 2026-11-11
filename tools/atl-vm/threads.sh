#!/bin/sh
# teslalinux test VM: state of the interesting threads of the running ATL app (render threads, main):
# scheduler state, CPU time and the kernel wait channel. usage: threads.sh [name-regex]
pat="${1:-RenderThread|^main$|odot|Native|GLThread}"
pid=$(pgrep -f "[a]ndroid-translation-layer /home" | head -1)
[ -z "$pid" ] && { echo "no app running"; exit 0; }
for t in /proc/$pid/task/*; do
  c=$(cat "$t/comm" 2>/dev/null) || continue
  echo "$c" | grep -Eq "$pat" || continue
  # stat: after the ")" come state, then utime is field 12 and stime field 13 of the remainder
  rest=$(sed -E 's/^[0-9]+ \(.*\) //' "$t/stat")
  state=$(echo "$rest" | cut -d' ' -f1)
  utime=$(echo "$rest" | cut -d' ' -f12)
  stime=$(echo "$rest" | cut -d' ' -f13)
  echo "$c tid=$(basename "$t") state=$state utime=$utime stime=$stime wchan=$(cat "$t/wchan" 2>/dev/null)"
done
