#!/bin/bash
# platform/run.sh [ATL_BUILDDIR]: build PlatformTest.java into a minimal APK (mkapk.py: a manifest
# with the permissions the checks are about) and run it as the primary app on dalvikvm with the
# installed ATL, as the `tesla` user, in separate processes:
#   main       permissions and app ops (from the APK's manifest), Intent and Location through a
#              Parcel, the AndroidKeyStore (KeyGenParameterSpec, key authorizations, KeyInfo),
#              storage statistics, Choreographer frame data; with a location source and no
#              microphone (ATL_LOCATION_SOURCE set, ATL_UGLY_ENABLE_MICROPHONE unset)
#   keystore2  the keys of "main" keep their authorizations in a new process
#   stage      (automotive) a UX restrictions configuration staged for the next start
#   moving     (automotive) a start while moving: not promoted
#   idle       (automotive) a start standing still: promoted
#   network    the default network (transport, metered, bandwidth, roaming, callbacks) against a
#              mock NetworkManager and ModemManager (mock-nm.c) on a private D-Bus system bus, in
#              a network namespace whose default route is a dummy interface; GLib's main loop runs
#              on a thread (glibloop.c), as GTK's does in android-translation-layer
# Prints PASS/FAIL per check and "RESULT passed/total" per phase, then "TOTAL passed/total";
# exits non-zero on a failure.
#
#   ATL_BUILDDIR  the meson build directory whose src/api-impl/hax.jar the test compiles against
#                 (default ~/atl-src/builddir)
#   ATL_PREFIX    the installed ATL (default /opt/teslalinux)
#   R8_JAR        D8 for dexing (default ~/build/r8-9.5.29.jar)
#   TEST_USER     the user to run as (default tesla)
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILDDIR="${1:-${ATL_BUILDDIR:-$HOME/atl-src/builddir}}"
PREFIX="${ATL_PREFIX:-/opt/teslalinux}"
R8="${R8_JAR:-$HOME/build/r8-9.5.29.jar}"
USER_="${TEST_USER:-tesla}"
DEX="$PREFIX/lib/java/dex/android_translation_layer"
BOOT="$PREFIX/lib/java"

work="$(mktemp -d /tmp/platform-tests.XXXXXX)"
chmod 755 "$work"
trap 'sudo rm -rf "$work"' EXIT

javac -nowarn -source 8 -target 8 -bootclasspath "$BOOT/core-all_classes.jar" \
	-cp "$BUILDDIR/src/api-impl/hax.jar" -d "$work/classes" "$HERE/PlatformTest.java" 2>&1 | grep -v '^warning: \[options\]' || true
[[ -f "$work/classes/org/teslalinux/platformtest/PlatformTest.class" ]] || { echo "compile failed" >&2; exit 1; }
(cd "$work/classes" && jar cf "$work/test.jar" .)
java -cp "$R8" com.android.tools.r8.D8 --min-api 24 --lib "$BOOT/core-all_classes.jar" \
	--classpath "$BUILDDIR/src/api-impl/hax.jar" --output "$work" "$work/test.jar" || exit 1
mv "$work/classes.dex" "$work/test.dex"
python3 "$HERE/mkapk.py" "$work/platform-test.apk" "$work/test.dex"
mkdir -p "$work/lib"
head -c 12345 /dev/urandom > "$work/lib/libplatformtest.so"
chmod -R a+rX "$work"

# api-impl's natives use symbols of android-translation-layer's executable; dalvikvm gets them here
gcc -shared -fPIC -o "$work/host-symbols.so" "$HERE/../host-symbols.c"

data="$work/data"
mkdir -p "$data"
chmod -R 777 "$data"

all_passed=0 all_total=0 failed=0
WRAP=()
phase() { # NAME [VAR=VALUE...]
	local name=$1; shift
	echo "== $name"
	local out
	out="$("${WRAP[@]}" sudo -u "$USER_" env LD_PRELOAD="$work/host-symbols.so $PREFIX/lib/libdl_bio.so.0" \
		LD_LIBRARY_PATH="$PREFIX/lib:$PREFIX/lib/art" PATH="$PREFIX/bin:/usr/bin:/bin" HOME="$data/home" \
		ANDROID_APP_DATA_DIR="$data" ANDROID_LOG_TAGS='*:w' ATL_TEST_APK_PATH="$work/platform-test.apk" "$@" \
		"$PREFIX/bin/dalvikvm" -Djava.library.path="$DEX/natives" -DBuild.VERSION.SDK_INT=32 \
		-Datl.app.class.path="$work/platform-test.apk" -Datl.app.library.path="$work/lib" \
		-cp "$DEX/api-impl.jar:$DEX/framework-res.apk:$work/test.dex" org.teslalinux.platformtest.PlatformTest "$name" 2>&1)"
	local rc=$?
	echo "$out" > "/tmp/platform-test-$name.log"
	echo "$out" | grep -E '^(PASS|FAIL|RESULT)|Exception|Error' | grep -v '^\s*at ' | head -200
	local r; r="$(echo "$out" | grep '^RESULT' | tail -1 | awk '{print $2}')"
	if [[ -n "$r" ]]; then
		all_passed=$((all_passed + ${r%/*})); all_total=$((all_total + ${r#*/}))
	else
		all_total=$((all_total + 1)); echo "FAIL phase $name did not finish (exit $rc)"
		echo "$out" | tail -20
	fi
	[[ $rc -eq 0 ]] || failed=1
}

mkdir -p "$data/home"; chmod 777 "$data/home"
phase main ATL_LOCATION_SOURCE=fix:32.0853,34.7818
phase keystore2
phase stage ATL_IS_AUTOMOTIVE=1
phase moving ATL_IS_AUTOMOTIVE=1
phase idle ATL_IS_AUTOMOTIVE=1

# the network phase: a private system bus with the mock daemons, a network namespace
net="$work/net"
mkdir -p "$net"
jdk="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
gcc -o "$net/mock-nm" "$HERE/mock-nm.c" $(pkg-config --cflags --libs gio-2.0) &&
gcc -shared -fPIC -o "$net/libglibloop.so" "$HERE/glibloop.c" -I"$jdk/include" -I"$jdk/include/linux" $(pkg-config --cflags --libs glib-2.0) || {
	echo "FAIL building the network mocks"; failed=1; all_total=$((all_total + 1)); }
cat > "$net/bus.conf" <<EOF
<!DOCTYPE busconfig PUBLIC "-//freedesktop//DTD D-Bus Bus Configuration 1.0//EN" "http://www.freedesktop.org/standards/dbus/1.0/busconfig.dtd">
<busconfig>
  <type>system</type>
  <listen>unix:path=$net/system_bus_socket</listen>
  <auth>EXTERNAL</auth>
  <policy context="default">
    <allow user="*"/>
    <allow own="*"/>
    <allow send_destination="*"/>
    <allow receive_sender="*"/>
  </policy>
</busconfig>
EOF
mkfifo "$net/ctl"
chmod -R a+rwX "$net"
NS="tlplatform$$"
dbus_pid="" mock_pid=""
cleanup_net() {
	[[ -n "$mock_pid" ]] && kill "$mock_pid" 2>/dev/null
	[[ -n "$dbus_pid" ]] && kill "$dbus_pid" 2>/dev/null
	sudo ip netns del "$NS" 2>/dev/null
}
trap 'cleanup_net; sudo rm -rf "$work"' EXIT
if [[ -x "$net/mock-nm" ]]; then
	dbus_pid="$( (umask 000; dbus-daemon --config-file="$net/bus.conf" --fork --print-pid) )"
	export DBUS_SYSTEM_BUS_ADDRESS="unix:path=$net/system_bus_socket"
	"$net/mock-nm" "$net/ctl" > "$net/mock.out" 2>&1 &
	mock_pid=$!
	for _ in $(seq 50); do grep -q ready "$net/mock.out" 2>/dev/null && break; sleep 0.1; done
	sudo ip netns add "$NS"
	sudo ip netns exec "$NS" sh -c 'ip link set lo up && ip link add tlwwan0 type dummy && ip addr add 10.77.0.2/24 dev tlwwan0 && ip link set tlwwan0 up && ip route add default via 10.77.0.1 dev tlwwan0'
	WRAP=(sudo ip netns exec "$NS")
	phase network DBUS_SYSTEM_BUS_ADDRESS="$DBUS_SYSTEM_BUS_ADDRESS" TL_MOCK_CTL="$net/ctl" TL_GLIBLOOP="$net/libglibloop.so"
	WRAP=()
fi
echo "TOTAL $all_passed/$all_total"
[[ $failed -eq 0 && $all_passed -eq $all_total ]]
