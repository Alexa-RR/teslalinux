#!/bin/bash
# embed/run.sh [ATL_BUILDDIR]: builds EmbedActivity into a minimal APK (mkapk.py) and runs it with
# the installed ATL on the running compositor (the car display: WAYLAND_DISPLAY of the
# teslalinux-atl service, which must be stopped first). Checks that a SurfaceControlViewHost
# hierarchy embedded through SurfaceView.setChildSurfacePackage is attached and laid out, saves a
# screenshot to /tmp/embed-test.png, taps the embedded button and checks the click.
# Prints PASS/FAIL lines and "RESULT passed/total".
#
#   ATL_BUILDDIR  meson build directory to compile against (default ~/merge-test/builddir)
#   ATL_PREFIX    installed ATL (default /opt/teslalinux)
#   R8_JAR        D8 (default ~/build/r8-9.5.29.jar)
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILDDIR="${1:-${ATL_BUILDDIR:-$HOME/merge-test/builddir}}"
PREFIX="${ATL_PREFIX:-/opt/teslalinux}"
R8="${R8_JAR:-$HOME/build/r8-9.5.29.jar}"
BOOT="$PREFIX/lib/java"
work="$(mktemp -d /tmp/embed-test.XXXXXX)"
chmod 755 "$work"
trap 'sudo pkill -f "[e]mbed-test.apk" 2>/dev/null; sleep 1; sudo rm -rf "$work"' EXIT

passed=0 total=0
check() { total=$((total + 1)); if [ "$2" = 0 ]; then passed=$((passed + 1)); echo "PASS $1"; else echo "FAIL $1"; fi; }

javac -nowarn -source 8 -target 8 -bootclasspath "$BOOT/core-all_classes.jar" -cp "$BUILDDIR/src/api-impl/hax.jar" \
	-d "$work/classes" "$HERE/EmbedActivity.java" 2>&1 | grep -v '^warning: \[options\]'
(cd "$work/classes" && jar cf "$work/test.jar" .)
java -cp "$R8" com.android.tools.r8.D8 --min-api 24 --lib "$BOOT/core-all_classes.jar" \
	--classpath "$BUILDDIR/src/api-impl/hax.jar" --output "$work" "$work/test.jar" || exit 1
python3 "$HERE/mkapk.py" "$work/embed-test.apk" "$work/classes.dex" org.teslalinux.embedtest org.teslalinux.embedtest.EmbedActivity
chmod 644 "$work/embed-test.apk"

mkdir -p "$work/data" && chmod 777 "$work/data"
log="$work/app.log"
sudo -u tesla env XDG_RUNTIME_DIR=/run/tesla WAYLAND_DISPLAY=tesla-0 GDK_BACKEND=wayland \
	ATL_FORCE_FULLSCREEN=1 ATL_DISABLE_WINDOW_DECORATIONS=1 ANDROID_LOG_TAGS='*:w' ANDROID_APP_DATA_DIR="$work/data" \
	PATH="$PREFIX/bin:/usr/bin:/bin" LD_LIBRARY_PATH="$PREFIX/lib" \
	android-translation-layer "$work/embed-test.apk" > "$log" 2>&1 &
for _ in $(seq 1 60); do grep -aq "EMBED layout" "$log" && break; sleep 1; done
grep -a "EMBED\|Exception\|FATAL" "$log" | head -20
grep -aq "EMBED attached hostToken=true rootAttached=true" "$log"; check "the embedded hierarchy is attached to its window" $?
grep -aq "EMBED layout root=[1-9][0-9]*x[1-9][0-9]* .* shown=true attached=true" "$log"; check "it is laid out at its size and shown" $?
sh "$HOME/shot.sh" /tmp/embed-test.png >/dev/null 2>&1; check "screenshot /tmp/embed-test.png" $?
pos=$(grep -ao "button=[0-9]*,[0-9]* [0-9]*x[0-9]*" "$log" | head -1)
if [[ $pos =~ button=([0-9]+),([0-9]+)\ ([0-9]+)x([0-9]+) ]]; then
	x=$((BASH_REMATCH[1] + BASH_REMATCH[3] / 2)) y=$((BASH_REMATCH[2] + BASH_REMATCH[4] / 2))
	SECS=2 sh "$HOME/shot.sh" /tmp/embed-test-tapped.png --tap "$x,$y" >/dev/null 2>&1
	sleep 1
fi
grep -aq "EMBED click" "$log"; check "a tap on the embedded button reaches it" $?
echo "RESULT $passed/$total"
