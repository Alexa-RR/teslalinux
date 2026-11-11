#!/bin/sh
# teslalinux: start the app once and report Compose layers that stay dirty without being drawn
# (an AndroidComposeView whose dirtyLayers is non-empty in two view dumps 4 s apart is frozen; it
# was the intermittent "map stays on its loading grid" after a start). Needs ATL_DEBUG_DUMPVIEWS=1 in
# /etc/teslalinux/atl.env (the VM default). Run in the VM: sh compose-stuck-check.sh
S=$(date +%s)
sudo /usr/local/bin/teslalinux-restart-app restart >/dev/null 2>&1
for i in $(seq 90); do
	sudo journalctl -u teslalinux-atl --since @$S --no-pager -q -o cat | grep -q "surfaceChanged" && break
	sleep 1
done
sleep 18
P=$(pgrep -f "[a]ndroid-translation-layer" | head -1)
dump() {
	T=$(date +%s)
	echo "fields AndroidComposeView 1" | sudo -u tesla tee /tmp/atl-dumpviews >/dev/null
	sleep 4
	sudo journalctl _PID=$P --since @$T --no-pager -q -o cat | grep "dirtyLayers" | grep -v "= \[\]" | grep -o "@[0-9a-f]*\]" | sort
}
A=$(dump)
B=$(dump)
STUCK=$(printf '%s\n%s\n' "$A" "$B" | grep . | sort | uniq -d | wc -l)
echo "run pid=$P stuck_layers=$STUCK fatal=$(sudo journalctl -u teslalinux-atl --since @$S --no-pager -q -o cat | grep -c 'Fatal signal')"
