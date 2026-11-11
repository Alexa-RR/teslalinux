#!/bin/bash
# detached on the Mac VM, main screen with the "Still driving" prompt or plain: open the sheet, tap
# the first recent destination, inspect the trip overview's map surface
out=/tmp/tripmap-result.txt
: > $out
log() { echo "$(date +%H:%M:%S) $*" >> $out; }
d=$(ls -d ~/waze/run-* | tail -1); log "run $(basename $d)"
dump() { m=$(wc -l < $d/atl.log); echo "$1" > /tmp/atl-dumpviews; sleep 4; tail -n +$m $d/atl.log | grep -a "I/ViewDump" | sed -E "s/^I.ViewDump\( *[0-9]+\): *//"; }
~/tap.sh shot /tmp/t0.png >/dev/null
~/tap.sh tap 300 450 >/dev/null; sleep 16           # first recent row -> trip overview (route chooser)
~/tap.sh shot /tmp/t1.png >/dev/null
n=$(wc -l < $d/atl.log)
log "trip overview surfaces:"
grep -a "I/SurfaceView\|TripOverviewActivity}\|I/Activity(.*): - onResume" $d/atl.log | sed -E "s/I\/SurfaceView\([0-9]+\): //; s/I\/Activity\([0-9]+\): //" | cut -c1-90 | tail -8 >> $out
log "trip overview views (surface, map):"
dump "@TripOverview dump" | grep -a "SurfaceView\|map\.g\|=== view" | sed -E "s/ lp=([^ ]*).*ms=/ lp=\1 ms=/" | cut -c1-170 | head -12 >> $out
log "gtk surface widget allocation:"
grep -a "SurfaceViewWidget\|GtkGraphicsOffload" $d/atl.log | tail -4 | sed -E "s/0x[0-9a-f]+ //; s/^ +//" | cut -c1-170 >> $out
sleep 6; ~/tap.sh shot /tmp/t2.png >/dev/null
log "done"
