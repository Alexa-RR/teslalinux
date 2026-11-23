#!/bin/bash
# lifecycle/run.sh [ATL_BUILDDIR]      build LifecycleTest into an APK and run it
# lifecycle/run.sh --build OUT.apk [ATL_BUILDDIR]   only build (in the build VM: javac, D8)
# lifecycle/run.sh --apk FILE.apk      only run a built APK (on a Pi, which has no JDK)
#
# Runs with the installed ATL on the running compositor (the car display: WAYLAND_DISPLAY of the
# teslalinux-atl service, which must be stopped first). Checks activities started for a result
# that finish at once and of a translucent activity (LifecycleTest.java): every result arrives, the
# starting activity is resumed and shown again, stays started under the translucent one, and the
# app never has 0 started activities (it would read that as "went to the background"). Saves
# /tmp/lifecycle-translucent.png (the translucent activity over the first) and /tmp/lifecycle-test.png.
# Prints PASS/FAIL lines and "RESULT passed/total".
#
#   ATL_BUILDDIR  meson build directory to compile against (default ~/atl-src/builddir)
#   ATL_PREFIX    installed ATL (default /opt/teslalinux)
#   R8_JAR        D8 (default ~/build/r8-9.5.29.jar)
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PREFIX="${ATL_PREFIX:-/opt/teslalinux}"
R8="${R8_JAR:-$HOME/build/r8-9.5.29.jar}"
BOOT="$PREFIX/lib/java"
PKG=org.teslalinux.lifecycletest
MAIN="$PKG.LifecycleTest\$MainActivity"

mode=all apk=""
case "${1:-}" in
	--build) mode=build apk="$2"; shift 2 ;;
	--apk) mode=run apk="$2"; shift 2 ;;
esac
BUILDDIR="${1:-${ATL_BUILDDIR:-$HOME/atl-src/builddir}}"
work="$(mktemp -d /tmp/lifecycle-test.XXXXXX)"
chmod 755 "$work"
trap 'sudo pkill -f "[l]ifecycle-test.apk" 2>/dev/null; sleep 1; sudo rm -rf "$work"' EXIT

if [ "$mode" != run ]; then
	javac -nowarn -source 8 -target 8 -bootclasspath "$BOOT/core-all_classes.jar" -cp "$BUILDDIR/src/api-impl/hax.jar" \
		-d "$work/classes" "$HERE/LifecycleTest.java" 2>&1 | grep -v '^warning: \[options\]'
	(cd "$work/classes" && jar cf "$work/test.jar" .)
	java -cp "$R8" com.android.tools.r8.D8 --min-api 24 --lib "$BOOT/core-all_classes.jar" \
		--classpath "$BUILDDIR/src/api-impl/hax.jar" --output "$work" "$work/test.jar" || exit 1
	[ -n "$apk" ] || apk="$work/lifecycle-test.apk"
	# AskActivity's permission, declared as Waze declares it: an app holds only what its manifest requests
	MKAPK_PERMISSIONS=android.permission.READ_CALENDAR python3 "$HERE/../embed/mkapk.py" "$apk" "$work/classes.dex" "$PKG" "$MAIN" \
		"$PKG.LifecycleTest\$NowActivity" "$PKG.LifecycleTest\$AskActivity" \
		"$PKG.LifecycleTest\$TranslucentActivity:0x01030010" || exit 1
	chmod 644 "$apk"
	[ "$mode" = build ] && { echo "built $apk"; exit 0; }
fi
cp "$apk" "$work/lifecycle-test.apk" && chmod 644 "$work/lifecycle-test.apk"

passed=0 total=0
check() { total=$((total + 1)); if [ "$2" = 0 ]; then passed=$((passed + 1)); echo "PASS $1"; else echo "FAIL $1"; fi; }

mkdir -p "$work/data" && chmod 777 "$work/data"
log="$work/app.log"
sudo -u tesla env XDG_RUNTIME_DIR=/run/tesla WAYLAND_DISPLAY=tesla-0 GDK_BACKEND=wayland \
	ATL_FORCE_FULLSCREEN=1 ATL_DISABLE_WINDOW_DECORATIONS=1 ANDROID_LOG_TAGS='*:w' ANDROID_APP_DATA_DIR="$work/data" \
	PATH="$PREFIX/bin:/usr/bin:/bin" LD_LIBRARY_PATH="$PREFIX/lib" \
	android-translation-layer "$work/lifecycle-test.apk" > "$log" 2>&1 &
for _ in $(seq 1 90); do
	grep -aq "LIFE FINAL\|FATAL" "$log" && break
	# a picture of the translucent activity over MainActivity
	grep -aq "LIFE TranslucentActivity shown" "$log" && [ ! -f "$work/shot-done" ] && { sleep 1; touch "$work/shot-done"
		[ -f /var/tmp/ui-step.py ] && python3 /var/tmp/ui-step.py /tmp/lifecycle-translucent.png wait:0 >/dev/null 2>&1; }
	sleep 1
done
grep -a "LIFE\|Exception\|FATAL" "$log" | sed 's/^.*LIFE /LIFE /' | head -60
grep -aq "LIFE RESULT request=1 code=-1 granted=true" "$log"; check "the result of an activity that finishes in onCreate arrives" $?
grep -aq "LIFE RESULT request=2 code=-1 granted=true" "$log"; check "the result of an activity that finishes after a permission request arrives" $?
grep -aq "LIFE RESULT request=3 code=-1 granted=true" "$log"; check "the result of a translucent activity arrives" $?
grep -aq "LIFE FINAL results=3 mainResumed=true" "$log"; check "the starting activity is resumed again" $?
grep -aq "LIFE FINAL .*stoppedUnderTranslucent=false" "$log"; check "an activity under a translucent one is not stopped" $?
grep -aq "LIFE FINAL .*restartedUnderTranslucent=false" "$log"; check "it gets only onResume back, no onStart" $?
! grep -aq "LIFE BACKGROUND" "$log"; check "the app never has 0 started activities" $?
grep -aq "LIFE NowActivity destroyed" "$log"; check "the activity that finished in onCreate is destroyed" $?
grep -aq "LIFE AskActivity destroyed" "$log"; check "the activity that finished after its request is destroyed" $?
if [ -x "$HOME/shot.sh" ] || [ -f "$HOME/shot.sh" ]; then
	sh "$HOME/shot.sh" /tmp/lifecycle-test.png >/dev/null 2>&1
elif [ -f /var/tmp/ui-step.py ]; then
	python3 /var/tmp/ui-step.py /tmp/lifecycle-test.png wait:0 >/dev/null 2>&1
fi
echo "RESULT $passed/$total"
