#!/bin/bash
# scroll/run.sh [ATL_BUILDDIR]: build ScrollTest into an APK and run it on the car display (tacomp, the
# teslalinux-atl service's WAYLAND_DISPLAY; stop that service first), then swipe its ScrollView with
# touches through tacomp (/var/tmp/ui-step.py or ~/ui-step.py): once starting on a TextView row, once
# starting on an EditText row. Both must scroll the view (an EditText inside a scrolling list is how
# Waze's signed-in "Account and login" page is built). Prints PASS/FAIL lines and "RESULT passed/total".
#
#   ATL_BUILDDIR  meson build directory to compile against (default ~/atl-src/builddir)
#   ATL_PREFIX    installed ATL (default /opt/teslalinux)
#   R8_JAR        D8 (default ~/build/r8-9.5.29.jar)
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PREFIX="${ATL_PREFIX:-/opt/teslalinux}"
R8="${R8_JAR:-$HOME/build/r8-9.5.29.jar}"
BOOT="$PREFIX/lib/java"
PKG=org.teslalinux.scrolltest
BUILDDIR="${1:-${ATL_BUILDDIR:-$HOME/atl-src/builddir}}"
UI=/var/tmp/ui-step.py
[ -f "$UI" ] || UI=$HOME/ui-step.py
work="$(mktemp -d /tmp/scroll-test.XXXXXX)"
chmod 755 "$work"
trap 'sudo pkill -f "[s]croll-test.apk" 2>/dev/null; sleep 1; sudo rm -rf "$work"' EXIT

javac -nowarn -source 8 -target 8 -bootclasspath "$BOOT/core-all_classes.jar" -cp "$BUILDDIR/src/api-impl/hax.jar" \
	-d "$work/classes" "$HERE/ScrollTest.java" 2>&1 | grep -v '^warning: \[options\]'
(cd "$work/classes" && jar cf "$work/test.jar" .)
java -cp "$R8" com.android.tools.r8.D8 --min-api 24 --lib "$BOOT/core-all_classes.jar" \
	--classpath "$BUILDDIR/src/api-impl/hax.jar" --output "$work" "$work/test.jar" || exit 1
python3 "$HERE/../embed/mkapk.py" "$work/scroll-test.apk" "$work/classes.dex" "$PKG" "$PKG.ScrollTest" || exit 1
chmod 644 "$work/scroll-test.apk"

passed=0 total=0
check() { total=$((total + 1)); if [ "$2" = 0 ]; then passed=$((passed + 1)); echo "PASS $1"; else echo "FAIL $1"; fi; }

mkdir -p "$work/data" && chmod 777 "$work/data"
log="$work/app.log"
sudo -u tesla env XDG_RUNTIME_DIR=/run/tesla WAYLAND_DISPLAY=tesla-0 GDK_BACKEND=wayland \
	ATL_FORCE_FULLSCREEN=1 ATL_DISABLE_WINDOW_DECORATIONS=1 ANDROID_LOG_TAGS='*:w' ANDROID_APP_DATA_DIR="$work/data" \
	PATH="$PREFIX/bin:/usr/bin:/bin" LD_LIBRARY_PATH="$PREFIX/lib" \
	android-translation-layer "$work/scroll-test.apk" > "$log" 2>&1 &
for _ in $(seq 1 60); do grep -aq "SCROLLTEST ready\|FATAL" "$log" && break; sleep 1; done
grep -a "ROW\|ready" "$log" | sed 's/^.*ScrollTest[^:]*: /INFO /'
last_y() { grep -ao "SCROLL y=[0-9]*" "$log" | tail -1 | cut -d= -f2; }
# a row's centre: "ROW i top bottom kind"; the first fully visible row of the kind below y=300
row_y() { grep -a "ROW " "$log" | tail -16 | awk -v k="$1" -v off="$2" '{ t = $(NF-2) - off; b = $(NF-1) - off; if ($NF == k && t >= 300 && b <= 900) { print int((t + b) / 2); exit } }'; }
y0=$(last_y); y0=${y0:-0}
ys=$(row_y text "$y0")
python3 "$UI" /tmp/scroll-test-1.png "swipe:600,$ys,600,$((ys - 300)),400" wait:2 > /dev/null 2>&1
y1=$(last_y); y1=${y1:-0}
check "a swipe that starts on a TextView row scrolls (y $y0 -> $y1, from y=$ys)" $([ "$y1" -ge $((y0 + 150)) ] && echo 0 || echo 1)
ye=$(row_y edit "$y1")
python3 "$UI" /tmp/scroll-test-2.png "swipe:600,$ye,600,$((ye - 300)),400" wait:2 > /dev/null 2>&1
y2=$(last_y); y2=${y2:-0}
check "a swipe that starts on an EditText row scrolls (y $y1 -> $y2, from y=$ye)" $([ "$y2" -ge $((y1 + 150)) ] && echo 0 || echo 1)
grep -a "FATAL\|Exception" "$log" | head -5
echo "RESULT $passed/$total"
