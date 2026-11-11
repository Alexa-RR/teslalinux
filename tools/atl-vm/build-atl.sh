#!/bin/sh
# teslalinux ATL test VM: build ATL from source inside the guest so we can patch api-impl for Waze.
#   ./build-atl.sh            clone (first time) + meson setup + ninja + install into ~/atl-prefix
#   ./build-atl.sh rebuild    incremental ninja + install
# Our patches live in ~/atl-src (a git checkout); run-waze.sh prefers ~/atl-prefix/bin if present.
set -eu
SRC="$HOME/atl-src"
PREFIX="$HOME/atl-prefix"
log() { printf '\033[1;33m[build-atl]\033[0m %s\n' "$*"; }
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-1.8-openjdk}"
[ -d "$JAVA_HOME" ] || JAVA_HOME="$(ls -d /usr/lib/jvm/java-*-openjdk 2>/dev/null | head -1)"
export PATH="$JAVA_HOME/bin:$PATH"

# Same commit Alpine's package is built from (known to build against the packaged art_standalone /
# bionic_translation / libandroidfw). Override with ATL_COMMIT=master to try upstream head.
ATL_COMMIT="${ATL_COMMIT:-f4622e36186f5757b75a605deff543145df3094b}"
SUDO="$(command -v sudo || command -v doas)"
$SUDO apk add -q fontconfig-dev libopensles-standalone 2>/dev/null || true
if [ ! -d "$SRC/.git" ]; then
  log "cloning ATL"
  git clone -q https://gitlab.com/android_translation_layer/android_translation_layer.git "$SRC"
  git -C "$SRC" checkout -q "$ATL_COMMIT"
  git -C "$SRC" checkout -q -b teslalinux
fi
cd "$SRC"
# teslalinux native-side patches (idempotent): activity lifecycle -> Activity.perform*()
for p in "$HOME"/atl-native-patches/*.sh; do [ -f "$p" ] && sh "$p"; done
if [ "${1:-}" != "rebuild" ] || [ ! -d builddir ]; then
  log "meson setup"
  rm -rf builddir
  meson setup builddir --prefix="$PREFIX" --libdir=lib -Dbuildtype=debugoptimized 2>&1 | tail -15
fi
log "ninja ($(nproc) jobs)"
ninja -C builddir 2>&1 | tail -25
log "install -> $PREFIX"
meson install -C builddir --quiet
# The Java side (api-impl.jar) is built on the host by hostbuild.sh, which also leaves a copy at
# ~/api-impl-host.jar; meson install just overwrote the prefix jar with the stock guest build, so put
# the host-built one back (otherwise the natives and the jar disagree, e.g. Activity.performCreate).
if [ -f "$HOME/api-impl-host.jar" ]; then
  cp "$HOME/api-impl-host.jar" "$PREFIX/lib/java/dex/android_translation_layer/api-impl.jar"
  log "restored host-built api-impl.jar"
fi
# ATL resolves its jars relative to libart.so's directory (lib/art/../java/dex/...). The binary's
# runpath expects libart in $PREFIX/lib/art, so give it a real directory of per-file symlinks to the
# system ART (a single symlink to /usr/lib/art would make ../java resolve to the SYSTEM api-impl.jar
# and silently ignore every rebuild), plus a link to the system boot jars.
rm -rf "$PREFIX/lib/art"; mkdir -p "$PREFIX/lib/art" "$PREFIX/lib/java/dex"
for f in /usr/lib/art/*; do ln -sfn "$f" "$PREFIX/lib/art/$(basename "$f")"; done
ln -sfn /usr/lib/java/dex/art "$PREFIX/lib/java/dex/art"
# ART keeps an AOT-compiled copy of api-impl.jar in ~/.cache/art and will happily keep using the
# stale one after a rebuild (the new classes then "fail resolution"). Drop it.
rm -f "$HOME"/.cache/art/*/*api-impl* 2>/dev/null || true
ls -la "$PREFIX/bin/android-translation-layer" "$PREFIX/lib/"*.so* 2>/dev/null | head
log "done"
