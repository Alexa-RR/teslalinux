#!/bin/bash
# adaptive: open the sheet, tap the disabled search field by its bounds, then the enabled one, type, dump
d=$(ls -d ~/waze/run-* | tail -1)
dump() { m=$(wc -l < $d/atl.log); echo "$1" > /tmp/atl-dumpviews; sleep 3; tail -n +$m $d/atl.log | grep -a "I/ViewDump" | sed -E "s/^I.ViewDump\( *[0-9]+\): *//"; }
# center of the field with enabled=$1 : "x y" (owner offset + bounds)
center() { grep -a "CoreTextField" | grep -a "enabled=$1" | head -1 | sed -E 's/.*textfield in [0-9]+x[0-9]+@([0-9]+),([0-9]+).*Rect\.fromLTRB\(([0-9.]+), ([0-9.]+), ([0-9.]+), ([0-9.]+)\).*/\1 \2 \3 \4 \5 \6/' | awk '{printf "%d %d\n", $1+($3+$5)/2, $2+($4+$6)/2}'; }
~/tap.sh tap 300 668 >/dev/null; sleep 5
c=$(dump textfields | center false)
echo "disabled field at: $c"
if [ -n "$c" ]; then ~/tap.sh tap $c >/dev/null; sleep 6; fi
c=$(dump textfields | center true)
echo "enabled field at: $c"
[ -z "$c" ] && { echo "no enabled field"; exit 0; }
~/tap.sh tap $c >/dev/null; sleep 5
~/tap.sh tap 576 489 >/dev/null; sleep 1; ~/tap.sh tap 323 489 >/dev/null; sleep 4
echo "== field:"; dump textfields | grep -a "enabled=true" | grep -ao "legacyText=[^ ]*\|buffer=[^ ]*" | tr "\n" " "; echo
echo "== compose state:"; dump composestate | sed -E "s/^composestate: //; s/ cc\? [^ ]* [^ ]*//" | cut -c1-420
pgrep -f "[a]ndroid-translation-layer /home" >/dev/null && echo alive || echo DEAD
