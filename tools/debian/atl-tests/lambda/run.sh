#!/bin/bash
# lambda/run.sh [ATL_BUILDDIR]: build LambdaTest.java and run it under the installed ATL's dalvikvm (the boot
# class path's libcore lambdas: metafactory and altMetafactory call sites). Prints PASS/FAIL per check and
# "RESULT passed/total"; exits non-zero on a failure.
#
#   ATL_BUILDDIR  the meson build directory whose src/api-impl/hax.jar the test compiles against
#                 (default ~/atl-src/builddir)
#   ATL_PREFIX    the installed ATL (default /opt/teslalinux)
#   R8_JAR        D8 for dexing (default ~/build/r8-9.5.29.jar)
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILDDIR="${1:-${ATL_BUILDDIR:-$HOME/atl-src/builddir}}"
PREFIX="${ATL_PREFIX:-/opt/teslalinux}"
R8="${R8_JAR:-$HOME/build/r8-9.5.29.jar}"
BOOT="$PREFIX/lib/java"
work="$(mktemp -d /tmp/lambda-tests.XXXXXX)"
chmod 755 "$work"
trap 'rm -rf "$work"' EXIT
javac -nowarn -source 8 -target 8 -bootclasspath "$BOOT/core-all_classes.jar" -d "$work/classes" "$HERE/LambdaTest.java" 2>&1 | grep -v '^warning: \[options\]' || true
(cd "$work/classes" && jar cf "$work/test.jar" .)
java -cp "$R8" com.android.tools.r8.D8 --min-api 24 --lib "$BOOT/core-all_classes.jar" --output "$work" "$work/test.jar"
chmod 644 "$work/classes.dex"
LD_LIBRARY_PATH="$PREFIX/lib:$PREFIX/lib/art" ANDROID_LOG_TAGS='*:w' "$PREFIX/bin/dalvikvm" -cp "$work/classes.dex" org.teslalinux.lambdatest.LambdaTest
