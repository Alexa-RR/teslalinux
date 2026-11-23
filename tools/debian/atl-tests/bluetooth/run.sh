#!/bin/bash
# bluetooth/run.sh [ATL_BUILDDIR]: build BluetoothTest.java into minimal APKs (mkapk.py) and run it
# with the installed ATL on dalvikvm, as the `tesla` user, against mock-bluez (mock-bluez.c) on a
# private D-Bus system bus, one process per phase, each with a fresh mock:
#   bt1         the adapter (feature, service, state, name, address), LE scans: filters,
#               ALL_MATCHES, FIRST_MATCH/MATCH_LOST, report delay batches, startLeScan, the
#               already-started / unsupported / too-frequent errors, the discovery session
#               (SetDiscoveryFilter le + DuplicateData, StartDiscovery, StopDiscovery), remote
#               devices (name, type, bond state, UUIDs); with a location source
#   bt2         opportunistic scans, the adapter powering off and on, removed and added again,
#               bluetoothd stopping and starting, ACTION_STATE_CHANGED
#   nolocation  no location permission: no scan results (AOSP)
#   disavow     BLUETOOTH_SCAN neverForLocation: results without location, but no beacons
#   off         ATL_BLUETOOTH=off: no Bluetooth at all
# Prints PASS/FAIL per check and "RESULT passed/total" per phase, then "TOTAL passed/total";
# exits non-zero on a failure.
#
# bluetooth/run.sh --build DIR [ATL_BUILDDIR]   only build (test.dex, the APK, glibloop and the
#               preload library into DIR; in the build VM)
# bluetooth/run.sh --live DIR [SECONDS]         scan the host's real BlueZ adapter (the Pi) with a
#               built DIR for SECONDS (default 15) and print every Eddystone frame heard
#
#   ATL_BUILDDIR  the meson build directory whose src/api-impl/hax.jar the test compiles against
#                 (default ~/atl-src/builddir)
#   ATL_PREFIX    the installed ATL (default /opt/teslalinux)
#   R8_JAR        D8 for dexing (default ~/build/r8-9.5.29.jar)
#   TEST_USER     the user to run as (default tesla)
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
mode=all out=""
case "${1:-}" in
	--build) mode=build out="$2"; shift 2 ;;
	--live) mode=live out="$2"; shift 2 ;;
esac
PREFIX="${ATL_PREFIX:-/opt/teslalinux}"
R8="${R8_JAR:-$HOME/build/r8-9.5.29.jar}"
USER_="${TEST_USER:-tesla}"
DEX="$PREFIX/lib/java/dex/android_translation_layer"
BOOT="$PREFIX/lib/java"

build() { # DIR BUILDDIR
	local w=$1 builddir=$2
	mkdir -p "$w/classes"
	javac -nowarn -source 8 -target 8 -bootclasspath "$BOOT/core-all_classes.jar" \
		-cp "$builddir/src/api-impl/hax.jar" -d "$w/classes" "$HERE/BluetoothTest.java" 2>&1 | grep -v '^warning: \[options\]' || true
	[[ -f "$w/classes/org/teslalinux/bttest/BluetoothTest.class" ]] || { echo "compile failed" >&2; return 1; }
	(cd "$w/classes" && jar cf "$w/test.jar" .)
	java -cp "$R8" com.android.tools.r8.D8 --min-api 24 --lib "$BOOT/core-all_classes.jar" \
		--classpath "$builddir/src/api-impl/hax.jar" --output "$w" "$w/test.jar" || return 1
	mv "$w/classes.dex" "$w/test.dex"
	python3 "$HERE/mkapk.py" "$w/bt-test.apk" "$w/test.dex"
	python3 "$HERE/mkapk.py" "$w/bt-test-disavow.apk" "$w/test.dex" --disavow
	local jdk; jdk="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
	# api-impl's natives use symbols of android-translation-layer's executable; dalvikvm gets them here
	gcc -shared -fPIC -o "$w/host-symbols.so" "$HERE/../host-symbols.c" &&
	gcc -shared -fPIC -o "$w/libglibloop.so" "$HERE/glibloop.c" -I"$jdk/include" -I"$jdk/include/linux" \
		$(pkg-config --cflags --libs glib-2.0) || return 1
	chmod -R a+rX "$w"
}

work="$(mktemp -d /tmp/bt-tests.XXXXXX)"
chmod 755 "$work"
dbus_pid="" mock_pid=""
cleanup() {
	[[ -n "$mock_pid" ]] && kill "$mock_pid" 2>/dev/null
	[[ -n "$dbus_pid" ]] && kill "$dbus_pid" 2>/dev/null
	sudo rm -rf "$work"
}
trap cleanup EXIT

if [[ $mode == build ]]; then
	build "$out" "${1:-${ATL_BUILDDIR:-$HOME/atl-src/builddir}}"
	exit
fi
if [[ $mode == all ]]; then
	out="$work/build"
	build "$out" "${1:-${ATL_BUILDDIR:-$HOME/atl-src/builddir}}" || exit 1
fi

data="$work/data"
mkdir -p "$data/home"
chmod -R 777 "$data"

all_passed=0 all_total=0 failed=0
phase() { # NAME APK [VAR=VALUE...]
	local name=$1 apk=$2; shift 2
	echo "== $name"
	local o
	o="$(sudo -u "$USER_" env LD_PRELOAD="$out/host-symbols.so $PREFIX/lib/libdl_bio.so.0" \
		LD_LIBRARY_PATH="$PREFIX/lib:$PREFIX/lib/art" PATH="$PREFIX/bin:/usr/bin:/bin" HOME="$data/home" \
		ANDROID_APP_DATA_DIR="$data" ANDROID_LOG_TAGS='*:w' ATL_TEST_APK_PATH="$apk" TL_GLIBLOOP="$out/libglibloop.so" "$@" \
		"$PREFIX/bin/dalvikvm" -Djava.library.path="$DEX/natives" -DBuild.VERSION.SDK_INT=32 \
		-Datl.app.class.path="$apk" -cp "$DEX/api-impl.jar:$DEX/framework-res.apk:$out/test.dex" \
		org.teslalinux.bttest.BluetoothTest "$name" 2>&1 | tr -d "\\0")"
	local rc=$?
	echo "$o" > "/tmp/bt-test-$name.log"
	echo "$o" | grep -E '^(PASS|FAIL|RESULT|eddystone|adapter)|Exception|Error' | grep -v '^\s*at ' | head -300
	local r; r="$(echo "$o" | grep '^RESULT' | tail -1 | awk '{print $2}')"
	if [[ -n "$r" ]]; then
		all_passed=$((all_passed + ${r%/*})); all_total=$((all_total + ${r#*/}))
	else
		all_total=$((all_total + 1)); echo "FAIL phase $name did not finish (exit $rc)"
		echo "$o" | tail -20
	fi
	[[ $rc -eq 0 ]] || failed=1
}

if [[ $mode == live ]]; then
	phase live "$out/bt-test.apk" ATL_LOCATION_SOURCE=fix:32.0853,34.7818 LIVE_SECONDS="${1:-15}" ${ATL_DEBUG_BLUETOOTH:+ATL_DEBUG_BLUETOOTH=1}
	echo "TOTAL $all_passed/$all_total"
	[[ $failed -eq 0 && $all_passed -eq $all_total ]]
	exit
fi

# a private system bus with the mock bluetoothd
net="$work/bus"
mkdir -p "$net"
gcc -o "$net/mock-bluez" "$HERE/mock-bluez.c" $(pkg-config --cflags --libs gio-2.0) || { echo "FAIL building mock-bluez"; exit 1; }
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
chmod -R a+rwX "$net"
dbus_pid="$( (umask 000; dbus-daemon --config-file="$net/bus.conf" --fork --print-pid) )"
export DBUS_SYSTEM_BUS_ADDRESS="unix:path=$net/system_bus_socket"

mock_phase() { # NAME APK [VAR=VALUE...]: the phase against a fresh mock
	[[ -n "$mock_pid" ]] && { kill "$mock_pid" 2>/dev/null; wait "$mock_pid" 2>/dev/null; }
	rm -f "$net/ctl" "$net/mock.out"
	mkfifo -m 666 "$net/ctl"
	"$net/mock-bluez" "$net/ctl" > "$net/mock.out" 2>&1 &
	mock_pid=$!
	for _ in $(seq 50); do grep -q ready "$net/mock.out" 2>/dev/null && break; sleep 0.1; done
	chmod 644 "$net/mock.out"
	local name=$1; shift
	phase "$name" "$@" DBUS_SYSTEM_BUS_ADDRESS="$DBUS_SYSTEM_BUS_ADDRESS" TL_MOCK_CTL="$net/ctl" TL_MOCK_OUT="$net/mock.out"
	cp "$net/mock.out" "/tmp/bt-test-$name.mock" 2>/dev/null
}

mock_phase bt1 "$out/bt-test.apk" ATL_LOCATION_SOURCE=fix:32.0853,34.7818
mock_phase bt2 "$out/bt-test.apk" ATL_LOCATION_SOURCE=fix:32.0853,34.7818
mock_phase nolocation "$out/bt-test.apk"
mock_phase disavow "$out/bt-test-disavow.apk"
mock_phase off "$out/bt-test.apk" ATL_LOCATION_SOURCE=fix:32.0853,34.7818 ATL_BLUETOOTH=off
echo "TOTAL $all_passed/$all_total"
[[ $failed -eq 0 && $all_passed -eq $all_total ]]
