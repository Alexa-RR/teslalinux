#!/bin/bash
d=$(ls -d ~/waze/run-* | tail -1)
dump() { m=$(wc -l < $d/atl.log); echo "$1" > /tmp/atl-dumpviews; sleep ${2:-3}; tail -n +$m $d/atl.log | grep -a "I/ViewDump" | sed -E "s/^I.ViewDump\( *[0-9]+\): *//"; }
echo "== idle main screen:"; dump choreo 4 | grep -a choreo
~/tap.sh tap 300 668 >/dev/null; sleep 5
echo "== sheet open:"; dump choreo 4 | grep -a choreo
echo "== vsync trace lines so far: $(grep -ac '\[vsync\]' $d/atl.log)"
pgrep -f "[a]ndroid-translation-layer /home" >/dev/null && echo alive || echo DEAD
