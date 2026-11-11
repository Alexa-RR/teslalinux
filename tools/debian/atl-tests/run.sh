#!/bin/bash
# run.sh [ATL_BUILDDIR]: build ServiceBinderTest.java against an ATL build and run it with the
# installed ATL (dalvikvm, api-impl.jar, framework-res.apk, the api-impl natives) as the `tesla`
# user (the app uid of the car image; the test needs a uid other than the system uid 1000).
#
# It checks ATL's service lifecycle (ATLActiveServices), Binder's calling identity between the app
# and the platform, the framework package ("android": system uid, platform signature) and virtual
# displays, SurfaceControl layers, SurfacePackage and Surface through Parcels, and system apps
# (per-package uids, install-time permission grants, calling identity between the app, the platform
# and a system app). The system-app checks need the car Templates Host installed as a privileged
# system app: ATL's system directory (next to api-impl.jar, or $ATL_SYSTEM_DIR) holding
# priv-app/CarTemplatesHost/CarTemplatesHost.apk (platform-signed, tools/carhost) and
# etc/permissions/privapp-permissions-car-templates-host.xml. Prints PASS/FAIL per check and
# "RESULT passed/total"; exits non-zero on a failure.
#
#   ATL_BUILDDIR  the meson build directory whose src/api-impl/hax.jar (api-impl's classes) the test
#                 compiles against (default ~/merge-test/builddir)
#   ATL_PREFIX    the installed ATL (default /opt/teslalinux)
#   R8_JAR        D8 for dexing (default ~/build/r8-9.5.29.jar)
#   TEST_USER     the user to run as (default tesla)
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILDDIR="${1:-${ATL_BUILDDIR:-$HOME/merge-test/builddir}}"
PREFIX="${ATL_PREFIX:-/opt/teslalinux}"
R8="${R8_JAR:-$HOME/build/r8-9.5.29.jar}"
USER_="${TEST_USER:-tesla}"
DEX="$PREFIX/lib/java/dex/android_translation_layer"
BOOT="$PREFIX/lib/java"

work="$(mktemp -d /tmp/atl-tests.XXXXXX)"
chmod 755 "$work"
trap 'rm -rf "$work"' EXIT

javac -nowarn -source 8 -target 8 -bootclasspath "$BOOT/core-all_classes.jar" \
	-cp "$BUILDDIR/src/api-impl/hax.jar" -d "$work/classes" "$HERE/ServiceBinderTest.java" 2>&1 | grep -v '^warning: \[options\]' || true
[[ -f "$work/classes/android/atl/ServiceBinderTest.class" ]] || { echo "compile failed" >&2; exit 1; }
(cd "$work/classes" && jar cf "$work/test.jar" .)
java -cp "$R8" com.android.tools.r8.D8 --min-api 24 --lib "$BOOT/core-all_classes.jar" \
	--classpath "$BUILDDIR/src/api-impl/hax.jar" --output "$work" "$work/test.jar"
mv "$work/classes.dex" "$work/test.dex"
chmod 644 "$work/test.dex"

# api-impl's natives use symbols of android-translation-layer's executable; dalvikvm gets them here
gcc -shared -fPIC -o "$work/host-symbols.so" "$HERE/host-symbols.c"

data="$work/data"
mkdir -p "$data"
chmod -R 777 "$data"
# (and libdl_bio, which the executable links: bionic_dlopen/bionic_dlsym)
sudo -u "$USER_" env LD_PRELOAD="$work/host-symbols.so $PREFIX/lib/libdl_bio.so.0" \
	LD_LIBRARY_PATH="$PREFIX/lib:$PREFIX/lib/art" PATH="$PREFIX/bin:/usr/bin:/bin" \
	ANDROID_APP_DATA_DIR="$data" ANDROID_LOG_TAGS='*:w' ATL_TEST_APK_PATH="$DEX/framework-res.apk" \
	"$PREFIX/bin/dalvikvm" -Djava.library.path="$DEX/natives" \
	-cp "$DEX/api-impl.jar:$DEX/framework-res.apk:$work/test.dex" android.atl.ServiceBinderTest
