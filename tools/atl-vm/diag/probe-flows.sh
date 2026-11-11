#!/bin/bash
# detached on the Mac VM, on a running instance at the main screen: Hebrew typing in the search
# field, then the Work address flow, then navigation and BACK out of it; screenshots h1 w1 w2 g1 g2
out=/tmp/flows-result.txt
: > $out
log() { echo "$(date +%H:%M:%S) $*" >> $out; }
d=$(ls -d ~/waze/run-* | tail -1); log "run $(basename $d)"
dump() { m=$(wc -l < $d/atl.log); echo "$1" > /tmp/atl-dumpviews; sleep 3; tail -n +$m $d/atl.log | grep -a "I/ViewDump" | sed -E "s/^I.ViewDump\( *[0-9]+\): *//"; }
alive() { pgrep -f "[a]ndroid-translation-layer /home" >/dev/null && echo alive || echo DEAD; }
m=$(wc -l < $d/atl.log); echo "@MainActivity dump" > /tmp/atl-dumpviews; sleep 4
if tail -n +$m $d/atl.log | grep -aq "DialogWrapper\|Dialog"; then log "dialog: Close"; ~/tap.sh tap 450 472 >/dev/null; sleep 4; fi
rm -f /tmp/atl-dumpviews
# 1. Hebrew typing: open search, focus the field, switch the keyboard language, type two letters
bash ~/probe-search.sh | grep -a "legacyText\|no enabled" >> $out
~/tap.sh tap 1203 553 >/dev/null; sleep 1          # backspace
~/tap.sh tap 1203 553 >/dev/null; sleep 1
~/tap.sh tap 371 679 >/dev/null; sleep 1           # language key -> Hebrew
~/tap.sh tap 577 489 >/dev/null; sleep 1           # a key on the top row
~/tap.sh tap 322 489 >/dev/null; sleep 4
~/tap.sh shot /tmp/h1.png >/dev/null
dump "@MainActivity textfields" | grep -a "enabled=true" | grep -ao "legacyText=[^ ]*" | head -1 >> $out
log "hebrew typed: $(alive)"
~/tap.sh key Escape >/dev/null; sleep 2            # hide keyboard
~/tap.sh key Escape >/dev/null; sleep 3            # leave search
# 2. Work address: open sheet, tap Work, type, pick the first suggestion
~/tap.sh tap 300 668 >/dev/null; sleep 5
~/tap.sh tap 190 380 >/dev/null; sleep 6
~/tap.sh tap 577 489 >/dev/null; sleep 1; ~/tap.sh tap 322 489 >/dev/null; sleep 1; ~/tap.sh tap 1203 553 >/dev/null; sleep 8
~/tap.sh shot /tmp/w1.png >/dev/null
~/tap.sh tap 300 190 >/dev/null; sleep 8            # first suggestion row (if any)
~/tap.sh shot /tmp/w2.png >/dev/null
log "work flow: $(alive)"
grep -a "startActivity\|I/Activity(.*): - on\(Create\|Destroy\)" $d/atl.log | sed -E "s/startActivityForResult\(Intent \[component=ComponentInfo\{com.waze\///; s/startActivity\(Intent \[component=ComponentInfo\{com.waze\///; s/\}.*//; s/I\/Activity\([0-9]+\): //" | cut -c1-80 | tail -6 >> $out
~/tap.sh key Escape >/dev/null; sleep 3
~/tap.sh key Escape >/dev/null; sleep 3
# 3. navigation from search, then BACK out of it
bash ~/probe-search.sh | grep -a "legacyText\|no enabled" >> $out
~/tap.sh tap 1203 553 >/dev/null; sleep 4; ~/tap.sh tap 1172 679 >/dev/null; sleep 10; ~/tap.sh tap 170 222 >/dev/null; sleep 6; ~/tap.sh tap 404 680 >/dev/null; sleep 30
m=$(wc -l < $d/atl.log); echo "@MainActivity dump" > /tmp/atl-dumpviews; sleep 4
if tail -n +$m $d/atl.log | grep -aq "DialogWrapper\|Dialog"; then ~/tap.sh tap 830 461 >/dev/null; sleep 3; fi
rm -f /tmp/atl-dumpviews
~/tap.sh shot /tmp/g1.png >/dev/null
log "navigating: $(alive)"
~/tap.sh key Escape >/dev/null; sleep 6
~/tap.sh shot /tmp/g2.png >/dev/null
log "after BACK: $(alive)"
grep -a "Fatal\|\[exit\]\|AndroidRuntime" $d/atl.log | head -3 | cut -c1-120 >> $out
log "done"
