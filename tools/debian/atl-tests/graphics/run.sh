#!/bin/bash
# graphics/run.sh [ATL_BUILDDIR]: build GfxTest.java + GfxCases.java against an ATL build and run them
# with the installed ATL (dalvikvm, api-impl.jar, the api-impl natives) as the `tesla` user. Checks
# ATL's canvas on Bitmaps (GSK's cairo renderer): blend-mode, lighting and colour-matrix colour filters
# on solid colours and on bitmaps, stroke styles (FILL_AND_STROKE, hairlines, joins), clipOut,
# Paint.getFillPath, clip bounds, nine-patch optical/outline insets. screen.sh draws the same cases
# on screen. Prints PASS/FAIL per check and "RESULT passed/total"; exits non-zero on a failure.
#
#   ATL_BUILDDIR  the meson build directory whose src/api-impl/hax.jar the test compiles against
#                 (default ~/atl-src/builddir)
#   ATL_PREFIX    the installed ATL (default /opt/teslalinux)
#   R8_JAR        D8 for dexing (default ~/build/r8-9.5.29.jar)
#   TEST_USER     the user to run as (default tesla)
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILDDIR="${1:-${ATL_BUILDDIR:-$HOME/atl-src/builddir}}"
PREFIX="${ATL_PREFIX:-/opt/teslalinux}"
R8="${R8_JAR:-$HOME/build/r8-9.5.29.jar}"
USER_="${TEST_USER:-tesla}"
DEX="$PREFIX/lib/java/dex/android_translation_layer"
BOOT="$PREFIX/lib/java"

work="$(mktemp -d /tmp/gfx-tests.XXXXXX)"
chmod 755 "$work"
trap 'sudo rm -rf "$work"' EXIT

javac -nowarn -source 8 -target 8 -bootclasspath "$BOOT/core-all_classes.jar" \
	-cp "$BUILDDIR/src/api-impl/hax.jar" -d "$work/classes" "$HERE/GfxTest.java" "$HERE/GfxCases.java" 2>&1 | grep -v '^warning: \[options\]' || true
[[ -f "$work/classes/org/teslalinux/gfxtest/GfxTest.class" ]] || { echo "compile failed" >&2; exit 1; }
(cd "$work/classes" && jar cf "$work/test.jar" .)
java -cp "$R8" com.android.tools.r8.D8 --min-api 24 --lib "$BOOT/core-all_classes.jar" \
	--classpath "$BUILDDIR/src/api-impl/hax.jar" --output "$work" "$work/test.jar"
mv "$work/classes.dex" "$work/test.dex"
chmod 644 "$work/test.dex"

# api-impl's natives use symbols of android-translation-layer's executable; dalvikvm gets them here
gcc -shared -fPIC -o "$work/host-symbols.so" "$HERE/../host-symbols.c"
# GTK initialised as the executable does (GSK's render node types)
# shellcheck disable=SC2046
gcc -shared -fPIC -o "$work/gtk-init.so" "$HERE/gtk-init.c" $(pkg-config --cflags --libs gtk4)

data="$work/data"
mkdir -p "$data"
chmod -R 777 "$data"
# GFX_PRELOAD: more libraries to preload (a crash reporter such as tools/atl-vm/diag/tl_segv.c)
sudo -u "$USER_" env LD_PRELOAD="$work/host-symbols.so $work/gtk-init.so $PREFIX/lib/libdl_bio.so.0${GFX_PRELOAD:+ $GFX_PRELOAD}" \
	TL_SEGV_DELAY="${TL_SEGV_DELAY:-15}" LD_LIBRARY_PATH="$PREFIX/lib:$PREFIX/lib/art" PATH="$PREFIX/bin:/usr/bin:/bin" \
	ANDROID_APP_DATA_DIR="$data" ANDROID_LOG_TAGS='*:w' ATL_TEST_APK_PATH="$DEX/framework-res.apk" \
	"$PREFIX/bin/dalvikvm" -Djava.library.path="$DEX/natives" \
	-cp "$DEX/api-impl.jar:$DEX/framework-res.apk:$work/test.dex" org.teslalinux.gfxtest.GfxTest
