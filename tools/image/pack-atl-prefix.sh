#!/bin/sh
# teslalinux: pack the patched ATL runtime of the test VM for the car image.
#   pack-atl-prefix.sh [out.tar.gz]   (run on the VM where build-atl.sh / hostbuild.sh installed ~/atl-prefix)
# Contents: ~/atl-prefix (binaries, natives, the host-built api-impl.jar) plus the patched
# bionic_translation libraries it depends on (installed into the same prefix by
# bionic_translation-waze.sh). The symlinks to the system ART are recreated by build-alpine-pi.sh.
set -eu
OUT=${1:-$HOME/atl-prefix.tar.gz}
PREFIX=${ATL_PREFIX:-$HOME/atl-prefix}
[ -x "$PREFIX/bin/android-translation-layer" ] || { echo "no ATL in $PREFIX" >&2; exit 1; }
[ -f "$HOME/api-impl-host.jar" ] && cp "$HOME/api-impl-host.jar" "$PREFIX/lib/java/dex/android_translation_layer/api-impl.jar"
tar -C "$PREFIX" --exclude='lib/art' --exclude='lib/java/dex/art' -czf "$OUT" .
ls -la "$OUT"
