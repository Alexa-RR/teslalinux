#!/bin/sh
# teslalinux: one app start; seconds from the restart to the first frame (surfaceChanged) and to "Map shown and ready"
S=$(date +%s.%N)
sudo /usr/local/bin/teslalinux-restart-app restart >/dev/null 2>&1
for i in $(seq 120); do
	sudo journalctl -u teslalinux-atl --since @${S%.*} --no-pager -q -o cat | grep -q "Map shown and ready" && break
	sleep 0.5
done
sleep 1
sudo journalctl -u teslalinux-atl --since @${S%.*} --no-pager -q -o short-unix | awk -v s="$S" '
	/surfaceChanged/ && !f { f = $1 - s }
	/Map shown and ready/ && !m { m = $1 - s }
	END { printf "surface %.1f s, map ready %.1f s\n", f, m }'
