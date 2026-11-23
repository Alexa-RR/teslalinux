#!/bin/bash
# graphics/screen.sh [ATL_BUILDDIR]: builds GfxActivity + GfxCases into a minimal APK (embed/mkapk.py)
# and runs it with the installed ATL under a private headless compositor (cage, pixman), once with
# GSK's cairo renderer and once with its GL renderer (ngl, the Pi's), without touching the car
# display. The cases are drawn by a View through the window's renderer; a screenshot (grim) is
# compared with the probe pixels the app logs (check-shot.py), and the app's View state checks are
# collected. Prints PASS/FAIL lines and "RESULT passed/total"; screenshots stay in
# /tmp/gfx-screen-RENDERER.png.
#
#   ATL_BUILDDIR  meson build directory to compile against (default ~/atl-src/builddir)
#   ATL_PREFIX    installed ATL (default /opt/teslalinux)
#   R8_JAR        D8 (default ~/build/r8-9.5.29.jar)
#   RENDERERS     the GSK renderers to run (default "cairo ngl")
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILDDIR="${1:-${ATL_BUILDDIR:-$HOME/atl-src/builddir}}"
PREFIX="${ATL_PREFIX:-/opt/teslalinux}"
R8="${R8_JAR:-$HOME/build/r8-9.5.29.jar}"
BOOT="$PREFIX/lib/java"
work="$(mktemp -d /tmp/gfx-screen.XXXXXX)"
chmod 755 "$work"
trap 'sudo pkill -f "[g]fx-test.apk" 2>/dev/null; sudo pkill -f "[c]age -s -- sh $work" 2>/dev/null; sleep 1; sudo rm -rf "$work"' EXIT

passed=0 total=0
check() { total=$((total + 1)); if [ "$2" = 0 ]; then passed=$((passed + 1)); echo "PASS $1"; else echo "FAIL $1"; fi; }

javac -nowarn -source 8 -target 8 -bootclasspath "$BOOT/core-all_classes.jar" -cp "$BUILDDIR/src/api-impl/hax.jar" \
	-d "$work/classes" "$HERE/GfxActivity.java" "$HERE/GfxCases.java" 2>&1 | grep -v '^warning: \[options\]'
[[ -f "$work/classes/org/teslalinux/gfxtest/GfxActivity.class" ]] || { echo "compile failed" >&2; exit 1; }
(cd "$work/classes" && jar cf "$work/test.jar" .)
java -cp "$R8" com.android.tools.r8.D8 --min-api 24 --lib "$BOOT/core-all_classes.jar" \
	--classpath "$BUILDDIR/src/api-impl/hax.jar" --output "$work" "$work/test.jar" || exit 1
MKAPK_SUPPORTS_RTL=1 python3 "$HERE/../embed/mkapk.py" "$work/gfx-test.apk" "$work/classes.dex" org.teslalinux.gfxtest org.teslalinux.gfxtest.GfxActivity
chmod 644 "$work/gfx-test.apk"

for renderer in ${RENDERERS:-cairo ngl}; do
	run="$work/run-$renderer"
	sudo install -d -o tesla -m 755 "$run"
	xdg="$run/xdg"
	sudo install -d -o tesla -m 700 "$xdg"
	sudo install -d -o tesla -m 777 "$run/data"
	log="$run/app.log"
	# the app runs as cage's client; cage is headless with pixman (no GPU in the VM: GL is Mesa's
	# llvmpipe, whose frames reach the compositor as shared-memory buffers)
	cat > "$work/run-$renderer.sh" <<EOF
exec env GSK_RENDERER=$renderer GSK_DEBUG=renderer ATL_FORCE_FULLSCREEN=1 ATL_DISABLE_WINDOW_DECORATIONS=1 \
	ANDROID_LOG_TAGS='*:w' ANDROID_APP_DATA_DIR="$run/data" PATH="$PREFIX/bin:/usr/bin:/bin" \
	LD_LIBRARY_PATH="$PREFIX/lib" android-translation-layer "$work/gfx-test.apk" > "$log" 2>&1
EOF
	chmod 755 "$work/run-$renderer.sh"
	sudo -u tesla env XDG_RUNTIME_DIR="$xdg" WLR_BACKENDS=headless WLR_RENDERER=pixman WLR_LIBINPUT_NO_DEVICES=1 \
		cage -s -- sh "$work/run-$renderer.sh" > "$work/cage-$renderer.log" 2>&1 &
	for _ in $(seq 1 90); do sudo grep -aq "GFX ready" "$log" 2>/dev/null && sudo grep -aq "GFXCHECK done" "$log" && break; sleep 1; done
	sleep 1
	shot="/tmp/gfx-screen-$renderer.png"
	sock=$(sudo ls "$xdg" | grep -m1 '^wayland-[0-9]*$')
	sudo -u tesla env XDG_RUNTIME_DIR="$xdg" WAYLAND_DISPLAY="$sock" grim "$run/shot.png" && sudo cp "$run/shot.png" "$shot" && sudo chmod 644 "$shot"
	check "[$renderer] screenshot $shot" $?
	sudo grep -a "Using renderer\|renderer of type" "$log" | head -2 | sed "s/^/INFO [$renderer] /"
	sudo grep -a "GFXCHECK PASS\|GFXCHECK FAIL" "$log" | sed "s/.*GFXCHECK \(PASS\|FAIL\) /\1 [$renderer] /"
	np=$(sudo grep -ac "GFXCHECK PASS" "$log" 2>/dev/null)
	nt=$(sudo grep -ac "GFXCHECK PASS\|GFXCHECK FAIL" "$log" 2>/dev/null)
	[ "${nt:-0}" -gt 0 ] || { nt=1; echo "FAIL [$renderer] the app's state checks did not run"; }
	passed=$((passed + ${np:-0}))
	total=$((total + nt))
	sudo grep -a "GFXCASE\|FATAL\|Exception" "$log" | head -10 | sed "s/^/INFO [$renderer] /"
	sudo grep -ao "GFXPROBE .*\\|GFXINK .*" "$log" > "$work/probes-$renderer.txt"
	out=$(python3 "$HERE/check-shot.py" "$shot" "$work/probes-$renderer.txt" "$renderer")
	echo "$out" | grep -v "^COUNT"
	read -r _ p t <<< "$(echo "$out" | grep "^COUNT")"
	passed=$((passed + ${p:-0}))
	total=$((total + ${t:-1}))
	sudo pkill -f "[g]fx-test.apk"
	sleep 2
	sudo pkill -f "[c]age -s -- sh $work/run-$renderer.sh"
	sleep 1
done
echo "RESULT $passed/$total"
