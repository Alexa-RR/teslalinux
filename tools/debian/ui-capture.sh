#!/usr/bin/env bash
# teslalinux: capture one app screen in the Debian VM for UI comparison: optional taps (through
# tacomp's touch socket, as the car page sends them), then the frame tacomp streams and the full view
# dump of every window (android.atl.ViewDump, needs TESLALINUX_ATL_DEBUG=1 + ATL_DEBUG_DUMPVIEWS=1 in
# /etc/teslalinux/atl.env, see host/phase1-pios/teslalinux-atl).
#
#   tools/debian/ui-capture.sh NAME [--tap X,Y]... [--wait SECONDS]
#     -> out/ui-survey/NAME.jpg (the frame) and out/ui-survey/NAME.txt (the view dump)
#   --tap X,Y     a tap at display pixels (several run in order, 1.5 s apart)
#                 (going back: tap the app's own back arrow; the car has no BACK key either)
#   --wait S      seconds to wait after the taps before capturing (default 3)
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
V="$REPO/tools/debvm/vm-deb.sh"
NAME=${1:?usage: ui-capture.sh NAME [--tap X,Y]... [--wait S]}; shift
OUT="$REPO/out/ui-survey"; mkdir -p "$OUT"
TAPS=() WAIT=3
while [[ $# -gt 0 ]]; do
  case "$1" in
    --tap) TAPS+=("$2"); shift ;;
    --wait) WAIT=$2; shift ;;
    *) echo "unknown option $1" >&2; exit 2 ;;
  esac
  shift
done
CMD="set -e; rm -rf /tmp/uic; mkdir -p /tmp/uic;"
for t in ${TAPS[@]+"${TAPS[@]}"}; do
  CMD+=" timeout 15 python3 ~/teslalinux/services/display/test/ws-client.py --out /tmp/uic/t --frames 1 --wait 1 --tap $t >/dev/null 2>&1 || true; sleep 1.5;"
done
CMD+=" sleep $WAIT; since=\$(date '+%Y-%m-%d %H:%M:%S'); sudo -u tesla touch /tmp/atl-dumpviews; sleep 3;"
CMD+=" sudo journalctl -q --no-pager -u teslalinux-atl --since \"\$since\" -o cat | grep -a 'I/ViewDump' | sed -E 's/^I\\/ViewDump *\\([0-9]+\\): //' > /tmp/uic/dump.txt;"
CMD+=" timeout 15 python3 ~/teslalinux/services/display/test/ws-client.py --out /tmp/uic/f --frames 1 --wait 3 >/dev/null 2>&1 || true;"
CMD+=" ls -t /tmp/uic/f/*.jpg | head -1 > /tmp/uic/frame.name; wc -l < /tmp/uic/dump.txt"
lines=$(bash "$V" ssh "$CMD" | tail -1)
bash "$V" pull /tmp/uic/dump.txt "$OUT/$NAME.txt"
bash "$V" pull "$(bash "$V" ssh 'cat /tmp/uic/frame.name')" "$OUT/$NAME.jpg"
echo "ui-capture: $NAME: $lines dump lines -> out/ui-survey/$NAME.{jpg,txt}"
