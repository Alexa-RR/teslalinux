#!/bin/bash
# gltest/run.sh [ATL_BUILDDIR]: builds GlTest into a minimal APK (../embed/mkapk.py) and runs it with
# the installed ATL on a private headless weston (its own Wayland socket: the car display and its
# app are left alone), as the `tesla` user, with ATL_DEBUG_SURFACE=1. GlTest checks SurfaceTexture
# (an EGL producer and a GL_TEXTURE_EXTERNAL_OES consumer on two threads and contexts, and
# Surface.lockCanvas as a CPU producer) itself, once more with ATL_DIRECT_EGL=1 (SurfaceTexture part
# only); this script then checks EGL_ANDROID_presentation_time on the SurfaceView from the latch
# lines: the held frame is latched no earlier than one refresh before its time (and not much
# later), and of two frames due together the older is dropped. GlesTest (gles-gl-test.apk) checks
# the GL bindings (GLES10..GLES32, GLImpl, KHR_debug) and calls every native method once.
# Prints PASS/FAIL lines and "RESULT passed/total".
#
#   ATL_BUILDDIR  meson build directory to compile against (default ~/atl-src/builddir)
#   ATL_PREFIX    installed ATL (default /opt/teslalinux)
#   R8_JAR        D8 (default ~/build/r8-9.5.29.jar)
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILDDIR="${1:-${ATL_BUILDDIR:-$HOME/atl-src/builddir}}"
PREFIX="${ATL_PREFIX:-/opt/teslalinux}"
R8="${R8_JAR:-$HOME/build/r8-9.5.29.jar}"
BOOT="$PREFIX/lib/java"
work="$(mktemp -d /tmp/gl-test.XXXXXX)"
chmod 755 "$work"
weston_pid=""
trap 'sudo pkill -f "[g]l-test.apk" 2>/dev/null; [[ -n "$weston_pid" ]] && sudo kill "$weston_pid" 2>/dev/null; sleep 1; sudo rm -rf "$work"' EXIT

passed=0 total=0
check() { total=$((total + 1)); if [ "$2" = 0 ]; then passed=$((passed + 1)); echo "PASS $1"; else echo "FAIL $1${3:+ ($3)}"; fi; }

javac -nowarn -source 8 -target 8 -bootclasspath "$BOOT/core-all_classes.jar" -cp "$BUILDDIR/src/api-impl/hax.jar" \
	-d "$work/classes" "$HERE/GlTest.java" "$HERE/GlesTest.java" 2>&1 | grep -v '^warning: \[options\]'
(cd "$work/classes" && jar cf "$work/test.jar" .)
java -cp "$R8" com.android.tools.r8.D8 --min-api 24 --lib "$BOOT/core-all_classes.jar" \
	--classpath "$BUILDDIR/src/api-impl/hax.jar" --output "$work" "$work/test.jar" || exit 1
python3 "$HERE/../embed/mkapk.py" "$work/gl-test.apk" "$work/classes.dex" org.teslalinux.gltest org.teslalinux.gltest.GlTest
# GlesTest: the GL bindings (GLES10..GLES32, GLImpl) on pbuffers, every native method called once
python3 "$HERE/../embed/mkapk.py" "$work/gles-gl-test.apk" "$work/classes.dex" org.teslalinux.glestest org.teslalinux.gltest.GlesTest
chmod 644 "$work/gl-test.apk" "$work/gles-gl-test.apk"

# a private compositor: headless weston on a socket of its own, in a runtime directory of tesla's
xdg="$work/xdg"
mkdir -p "$xdg" "$work/data"
sudo chown tesla "$xdg" && sudo chmod 700 "$xdg"
chmod 777 "$work/data"
sock="tl-gltest-$$"
sudo -u tesla env XDG_RUNTIME_DIR="$xdg" weston --backend=headless --socket="$sock" --width=640 --height=480 --idle-time=0 \
	> "$work/weston.log" 2>&1 &
weston_pid=$!
for _ in $(seq 50); do sudo test -S "$xdg/$sock" && break; sleep 0.1; done
sudo test -S "$xdg/$sock"; check "a private headless weston" $? "$(tail -3 "$work/weston.log")"

log="$work/app.log"
sudo -u tesla env XDG_RUNTIME_DIR="$xdg" WAYLAND_DISPLAY="$sock" GDK_BACKEND=wayland \
	ATL_FORCE_FULLSCREEN=1 ATL_DISABLE_WINDOW_DECORATIONS=1 ATL_DEBUG_SURFACE=1 ANDROID_LOG_TAGS='*:w' ANDROID_APP_DATA_DIR="$work/data" \
	PATH="$PREFIX/bin:/usr/bin:/bin" LD_LIBRARY_PATH="$PREFIX/lib" \
	timeout 120 android-translation-layer "$work/gl-test.apk" > "$log" 2>&1
grep -a "^GLTEST \(PASS\|FAIL\)" "$log" | sed 's/^GLTEST //'
r="$(grep -a "^GLTEST RESULT" "$log" | awk '{print $3}')"
if [[ -n "$r" ]]; then
	passed=$((passed + ${r%/*})) total=$((total + ${r#*/}))
else
	check "the test app ran to the end" 1 "$(grep -a "Exception\|FATAL\|Error" "$log" | head -5)"
	tail -20 "$log"
fi

# SurfaceTexture (EGL and CPU producers) with ATL_DIRECT_EGL: SurfaceView windows go to the driver
# then, SurfaceTexture windows still to ATL's queue (the presentation-time part is left out)
dlog="$work/app-direct.log"
sudo -u tesla env XDG_RUNTIME_DIR="$xdg" WAYLAND_DISPLAY="$sock" GDK_BACKEND=wayland ATL_DIRECT_EGL=1 GLTEST_ONLY=surfacetexture \
	ATL_FORCE_FULLSCREEN=1 ATL_DISABLE_WINDOW_DECORATIONS=1 ANDROID_LOG_TAGS='*:w' ANDROID_APP_DATA_DIR="$work/data" \
	PATH="$PREFIX/bin:/usr/bin:/bin" LD_LIBRARY_PATH="$PREFIX/lib" \
	timeout 120 android-translation-layer "$work/gl-test.apk" > "$dlog" 2>&1
grep -a "^GLTEST \(PASS\|FAIL\)" "$dlog" | sed 's/^GLTEST /ATL_DIRECT_EGL: /'
r="$(grep -a "^GLTEST RESULT" "$dlog" | awk '{print $3}')"
if [[ -n "$r" ]]; then
	passed=$((passed + ${r%/*})) total=$((total + ${r#*/}))
else
	check "the test app ran to the end with ATL_DIRECT_EGL" 1 "$(grep -a "Exception\|FATAL\|Error" "$dlog" | head -5)"
	tail -20 "$dlog"
fi

# the GL bindings (GLES_TRACE=1 in the environment prints every call of GlesTest's sweep)
glog="$work/gles.log"
sudo -u tesla env XDG_RUNTIME_DIR="$xdg" WAYLAND_DISPLAY="$sock" GDK_BACKEND=wayland GLES_TRACE="${GLES_TRACE:-0}" \
	ATL_FORCE_FULLSCREEN=1 ATL_DISABLE_WINDOW_DECORATIONS=1 ANDROID_LOG_TAGS='*:w' ANDROID_APP_DATA_DIR="$work/data" \
	PATH="$PREFIX/bin:/usr/bin:/bin" LD_LIBRARY_PATH="$PREFIX/lib" \
	timeout 180 android-translation-layer "$work/gles-gl-test.apk" > "$glog" 2>&1
grep -a "^GLTEST \(PASS\|FAIL\|NOTE\)" "$glog" | sed 's/^GLTEST //'
r="$(grep -a "^GLTEST RESULT" "$glog" | awk '{print $3}')"
if [[ -n "$r" ]]; then
	passed=$((passed + ${r%/*})) total=$((total + ${r#*/}))
else
	check "the GL binding test app ran to the end" 1 "$(grep -a "Exception\|FATAL\|Error" "$glog" | head -5)"
	tail -20 "$glog"
fi

# EGL_ANDROID_presentation_time: the latch lines of the held frame and of the pair due together
due="$(grep -ao "GLTEST present-held due=[0-9]*" "$log" | grep -o "[0-9]*$")"
at="$(grep -ao "latched a frame for ${due:-x} at [0-9]*" "$log" | head -1 | awk '{print $NF}')"
if [[ -n "$due" && -n "$at" ]]; then
	early=$(( (due - at) / 1000000 ))
	# latched within one refresh (16.7 ms at 60 Hz) before its time, and within 100 ms after it
	[[ $at -ge $((due - 17000000)) && $at -le $((due + 100000000)) ]]; check "a frame with a presentation time is held until then" $? "latched ${early} ms before its time"
else
	check "a frame with a presentation time is held until then" 1 "due=$due latched=$at"
fi
due2="$(grep -ao "GLTEST present-drop due=[0-9]*" "$log" | grep -o "[0-9]*$")"
grep -aq "frame(s) dropped for a newer one that is due" "$log" && grep -aq "latched a frame for $((due2 + 1)) at" "$log"
check "of two frames due together the older is dropped, the newer shown" $? "due2=$due2"
echo "RESULT $passed/$total"
[[ $passed -eq $total ]]
