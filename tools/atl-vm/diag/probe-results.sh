#!/bin/bash
# from the main screen: open search, type "te", press Search, then inspect the results activity
d=$(ls -d ~/waze/run-* | tail -1)
dump() { m=$(wc -l < $d/atl.log); echo "$1" > /tmp/atl-dumpviews; sleep ${2:-3}; tail -n +$m $d/atl.log | grep -a "I/ViewDump" | sed -E "s/^I.ViewDump\( *[0-9]+\): *//"; }
bash ~/probe-search.sh | grep -a "legacyText"
~/tap.sh tap 1203 553 >/dev/null; sleep 4; ~/tap.sh tap 1172 679 >/dev/null; sleep 10
echo "== GTK widgets of the search activity (tail of dump):"; dump "@SearchV2 dump" 4 | sed -n '/=== end ===/q;p' | grep -av "^~node" | tail -40 | cut -c1-170
for pt in "170 222" "400 160" "250 88" "72 28"; do echo "== hit $pt:"; dump "@SearchV2 hit $pt" | head -3 | cut -c1-220; done
pgrep -f "[a]ndroid-translation-layer /home" >/dev/null && echo alive || echo DEAD
