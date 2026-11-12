#!/bin/bash
# teslalinux: build the native Raspberry Pi OS (Debian trixie arm64, glibc) runtime inside the Debian
# build VM, into /opt/teslalinux (out/pios-plan/PLAN.md, section 1). Runs as `atl` in the guest.
#
#   build-dist.sh [all]          every step in order, skipping steps already done for the same pins
#   build-dist.sh STEP...        only these: vixl wolfssl bionic art coreoj wolfjsse opensles atl
#                                wayland wayland-protocols libdrm pixman xkbcommon wlroots tacomp tlplay
#   build-dist.sh --force STEP   redo a step although its stamp says done
#   build-dist.sh status         which steps are done, for which pins
# Then tools/debvm/pack-dist.sh packs the prefix for tools/pios/install.sh.
#
# Where it runs: in the Debian build VM as `atl` (inputs pushed into the guest's home by vm-deb.sh:
# ~/versions.env, ~/patch-bionic.sh, ~/debvm-patches, ~/debvm-bootcp, ~/tacomp, ~/appupdate), or
# from a repo checkout on any Debian trixie arm64 machine or container, root or not (the release
# workflow, .github/workflows/release.yml): when this script sits in tools/debvm of a checkout, or
# TL_REPO names one, every input comes from that checkout. guest-provision.sh [--ci] installs the
# build dependencies either way. TL_PREFIX (default /opt/teslalinux) and TL_BUILD_HOME (default
# $HOME: src/ and build/ go there) move the outputs.
#
# Layout (the same relative layout as ATL's own install, so ATL finds lib/art/../java/dex/...):
#   /opt/teslalinux/{bin,lib,lib/art,lib/java/dex/{art,android_translation_layer},include,share}
#   /opt/teslalinux/tacomp/{bin,lib}   tacomp + its private wlroots 0.20 stack (newer wayland, libdrm,
#                                      xkbcommon than trixie's); never on ATL's pkg-config path
# Sources in ~/src/<name>; per-step logs in ~/build/logs/<step>.log; stamps in ~/build/stamps.
# The ATL checkout is ~/atl-src and the prefix is also reachable as ~/atl-prefix (a symlink made by
# guest-provision.sh), so tools/atl-vm/hostbuild.sh and patch-core-oj.sh work against this VM:
#   ATL_VM_DIR=~/teslalinux-debvm ATL_VM_SSH_PORT=2223 ATL_WORK=out/atl-work-deb \
#     ATL_GUEST_CORE_OJ=/opt/teslalinux/lib/java/dex/art/core-oj-hostdex.jar tools/atl-vm/...
# (tools/debvm/hostenv-deb.sh prints those exports.)
#
# Needs: guest-provision.sh run first; ~/versions.env; the repo's ATL patches pushed as
# ~/atl-native-patches/{*.sh,files/*} and the bionic patch as ~/patch-bionic.sh +
# ~/patch-bionic-sysconf.py (vm-deb.sh push-patches does that); tools/debvm/patches as ~/debvm-patches.
set -euo pipefail
SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [[ -z "${TL_REPO:-}" && -f "$SELF_DIR/../../services/display/build.sh" ]]; then
  TL_REPO="$(cd "$SELF_DIR/../.." && pwd)"
fi
BHOME="${TL_BUILD_HOME:-$HOME}"
if [[ -n "${TL_REPO:-}" ]]; then
  # a checkout: the inputs where they live in the repo
  VERSIONS_ENV="${VERSIONS_ENV:-$TL_REPO/tools/debvm/versions.env}"
  BIONIC_PATCH="$TL_REPO/tools/atl-vm/patches/bionic_translation-waze.sh"
  BIONIC_SYSCONF_PY="$TL_REPO/tools/atl-vm/patches/bionic_translation-sysconf.py"
  DEBVM_PATCHES="$TL_REPO/tools/debvm/patches"
  BOOTCP="$TL_REPO/tools/atl-vm/patches/atl-waze/bootcp"
  TACOMP_SRC="$TL_REPO/services/display"
  TLPLAY_SRC="$TL_REPO/tools/appupdate"
else
  # the VM: the copies vm-deb.sh pushes (provision, push-patches, push-tacomp)
  VERSIONS_ENV="${VERSIONS_ENV:-$HOME/versions.env}"
  BIONIC_PATCH="$HOME/patch-bionic.sh"
  BIONIC_SYSCONF_PY="$HOME/patch-bionic-sysconf.py"
  DEBVM_PATCHES="$HOME/debvm-patches"
  BOOTCP="$HOME/debvm-bootcp"
  TACOMP_SRC="$HOME/tacomp"
  TLPLAY_SRC="$HOME/appupdate"
fi
# shellcheck source=versions.env
. "$VERSIONS_ENV"
PREFIX="${TL_PREFIX:-/opt/teslalinux}"
TPREFIX=$PREFIX/tacomp
SRCDIR="$BHOME/src"
B="$BHOME/build"
LOGS="$B/logs"
STAMPS="$B/stamps"
JOBS="${JOBS:-$(nproc)}"
mkdir -p "$SRCDIR" "$LOGS" "$STAMPS"
export JAVA_HOME="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}"
export PATH="$JAVA_HOME/bin:$PREFIX/bin:$PATH"
# root in a container has no sudo; the VM's atl user has passwordless sudo
asroot() { if [[ $EUID -eq 0 ]]; then "$@"; else sudo "$@"; fi; }
# the Pi 4's CPU (versions.env): meson and the C compiler calls take CFLAGS/CXXFLAGS from the
# environment on top of their own optimisation flags
CPU_CFLAGS="${TL_CPU_CFLAGS:-}"
# ATL-stack steps see our prefix first (vixl and wolfSSL headers, art-standalone.pc, bionic .pc);
# the tacomp steps override these with their own prefix (tacomp_env)
atl_env() {
  export PKG_CONFIG_PATH="$PREFIX/lib/pkgconfig:$PREFIX/share/pkgconfig"
  export C_INCLUDE_PATH="$PREFIX/include" CPLUS_INCLUDE_PATH="$PREFIX/include"
  export LIBRARY_PATH="$PREFIX/lib:$PREFIX/lib/art"
  export LDFLAGS="-Wl,-rpath,$PREFIX/lib:$PREFIX/lib/art"
  cpu_env
}
# only when set: an empty but exported CFLAGS makes autoconf drop its default -O2
cpu_env() { if [[ -n "$CPU_CFLAGS" ]]; then export CFLAGS="$CPU_CFLAGS" CXXFLAGS="$CPU_CFLAGS"; fi; }
tacomp_env() {
  export PKG_CONFIG_PATH="$TPREFIX/lib/pkgconfig:$TPREFIX/share/pkgconfig"
  unset C_INCLUDE_PATH CPLUS_INCLUDE_PATH LIBRARY_PATH
  export LDFLAGS="-Wl,-rpath,$TPREFIX/lib"
  cpu_env
}
log() { printf '\033[1;32m[build-dist]\033[0m %s\n' "$*"; }
die() { log "ERROR: $*"; exit 1; }

# clone REPO into ~/src/NAME at REF (commit, short commit or tag); a clean checkout every time
fetch_git() {
  local name=$1 repo=$2 ref=$3 d="$SRCDIR/$1"
  if [[ ! -d "$d/.git" ]]; then
    git clone -q "$repo" "$d"
  fi
  git -C "$d" fetch -q --tags origin 2>/dev/null || true
  git -C "$d" checkout -q -f "$ref"
  # NOCLEAN=1 keeps the previous build tree (an incremental retry after a fix, e.g. art_standalone)
  [[ "${NOCLEAN:-0}" == 1 ]] || git -C "$d" clean -q -fdx
  log "$name at $(git -C "$d" rev-parse --short=12 HEAD) ($ref)"
}
meson_build() { # srcdir prefix [meson options...]
  local s=$1 p=$2; shift 2
  rm -rf "$s/builddir"
  meson setup "$s/builddir" "$s" --prefix="$p" --libdir=lib -Dbuildtype="${BUILDTYPE:-release}" "$@"
  ninja -C "$s/builddir" -j "$JOBS"
  meson install -C "$s/builddir" --quiet
}

# --- ATL stack ---
step_vixl() {
  atl_env
  local d="$SRCDIR/vixl"
  rm -rf "$d"; mkdir -p "$d"
  curl -fsSL --retry 3 "$VIXL_URL" | tar -xz -C "$d" --strip-components=1
  curl -fsSL --retry 3 -o "$B/vixl-meson.patch" "$VIXL_MESON_PATCH_URL"
  local sum; sum=$(sha256sum "$B/vixl-meson.patch" | cut -d' ' -f1)
  if [[ -n "$VIXL_MESON_PATCH_SHA256" ]]; then
    [[ "$sum" == "$VIXL_MESON_PATCH_SHA256" ]] || die "vixl meson patch sha256 $sum != pinned $VIXL_MESON_PATCH_SHA256"
  else
    log "vixl meson patch sha256 $sum (record it as VIXL_MESON_PATCH_SHA256 in versions.env)"
  fi
  patch -d "$d" -p1 < "$B/vixl-meson.patch"
  meson_build "$d" "$PREFIX"
}
step_wolfssl() {
  atl_env
  fetch_git wolfssl "$WOLFSSL_REPO" "$WOLFSSL_TAG"
  cd "$SRCDIR/wolfssl"
  ./autogen.sh >/dev/null 2>&1 || autoreconf -fi
  # a CFLAGS from the environment replaces autoconf's "-g -O2": keep the optimisation explicit
  [[ -z "$CPU_CFLAGS" ]] || export CFLAGS="-g -O2 $CPU_CFLAGS"
  # shellcheck disable=SC2086
  ./configure --prefix="$PREFIX" --libdir="$PREFIX/lib" $WOLFSSL_FLAGS
  make -j "$JOBS"
  make install
}
step_bionic() {
  atl_env
  fetch_git bionic_translation "$BIONIC_REPO" "$BIONIC_COMMIT"
  [[ -f "$BIONIC_PATCH" && -f "$BIONIC_SYSCONF_PY" ]] || die "push the bionic patch first (vm-deb.sh push-patches)"
  # the repo's patch script builds and installs too (meson setup --prefix=$ATL_PREFIX)
  BIONIC_SRC="$SRCDIR/bionic_translation" ATL_PREFIX="$PREFIX" BIONIC_SYSCONF_PY="$BIONIC_SYSCONF_PY" sh "$BIONIC_PATCH"
}
step_art() {
  atl_env
  fetch_git art_standalone "$ART_REPO" "$ART_COMMIT"
  cd "$SRCDIR/art_standalone"
  python3 "$DEBVM_PATCHES/art_standalone-gcc14.py"
  # Alpine: make ____PREFIX=/usr ____LIBDIR=lib, then make ... install. Build and install with
  # the same prefix (art-standalone.pc is not regenerated when the prefix changes between the two).
  # C sources here are written for C23 (bool/true/false without <stdbool.h>, e.g. liblog's
  # fake_log_device.c): Alpine edge builds with GCC 15, whose default is gnu23; trixie's GCC 14
  # defaults to gnu17. HOST_GLOBAL_CONLYFLAGS is unset in the AOSP make files, so setting it on the
  # command line (inherited by sub-makes) only adds this.
  local cstd="HOST_GLOBAL_CONLYFLAGS=-std=gnu2x"
  make -j "$JOBS" ____PREFIX="$PREFIX" ____LIBDIR=lib "$cstd"
  make ____PREFIX="$PREFIX" ____INSTALL_ETC="$PREFIX/etc" ____LIBDIR=lib "$cstd" install
  # a fresh core-oj-hostdex.jar and wolfssljni-hostdex.jar: the coreoj and wolfjsse steps patch them
  # again (their .orig is the one to start from)
  rm -f "$PREFIX/lib/java/dex/art/core-oj-hostdex.jar.orig" "$PREFIX/lib/java/dex/art/wolfssljni-hostdex.jar.orig"
}
# the ALPN API in libcore's core-oj-hostdex.jar: tools/atl-vm/patch-core-oj.sh, which ran on the Mac
# against the VM, as a build step (same sources, same result: classes.dex = the new classes,
# classes2.dex = the original dex)
step_coreoj() {
  local jar="$PREFIX/lib/java/dex/art/core-oj-hostdex.jar" w="$B/coreoj" f want got
  [[ -f "$jar" ]] || die "no $jar (step art first)"
  [[ -d "$BOOTCP" ]] || die "no boot classpath sources in $BOOTCP (vm-deb.sh push-patches)"
  # the unpatched jar is kept as .orig; every run starts from it
  [[ -f "$jar.orig" ]] || cp -p "$jar" "$jar.orig"
  rm -rf "$w"; mkdir -p "$w/aosp" "$w/src/javax/net/ssl" "$w/classes"
  cp -r "$BOOTCP/." "$w/src/"
  for f in SSLParameters SSLSocket SSLEngine; do
    curl -fsSL --retry 3 "$LIBCORE_URL/+/$LIBCORE_COMMIT/ojluni/src/main/java/javax/net/ssl/$f.java?format=TEXT" | base64 -d > "$w/aosp/$f.java"
    want=$(tr ' ' '\n' <<<"$LIBCORE_SSL_SHA256" | sed -n "s/^$f=//p")
    got=$(sha256sum "$w/aosp/$f.java" | cut -d' ' -f1)
    [[ "$got" == "$want" ]] || die "libcore $f.java sha256 $got != pinned $want"
  done
  python3 "$DEBVM_PATCHES/core-oj-alpn.py" edit "$w/aosp" "$w/src/javax/net/ssl"
  local srcs=(); mapfile -t srcs < <(find "$w/src" -name '*.java' | sort)
  javac -g -nowarn -source 1.8 -target 1.8 -encoding UTF-8 -Xlint:-options \
    -bootclasspath "$PREFIX/lib/java/core-all_classes.jar" -d "$w/classes" "${srcs[@]}"
  java -jar "$PREFIX/lib/java/dx.jar" --dex --core-library --output="$w/tl-ssl.dex" "$w/classes"
  python3 "$DEBVM_PATCHES/core-oj-alpn.py" assemble "$jar.orig" "$w/tl-ssl.dex" "$w/core-oj-hostdex.jar"
  install -m 0755 "$w/core-oj-hostdex.jar" "$jar"
  # this user's ART cache holds a boot image compiled from the old jar
  rm -f "$HOME"/.cache/art/*/*boot* "$HOME"/.cache/art/*/*api-impl* 2>/dev/null || true
}
# the wolfSSL JSSE provider's SSLParameters bridge (WolfSSLJDK8Helper), which art_standalone leaves out
# of wolfssljni-hostdex.jar: without it the ALPN list OkHttp sets through SSLParameters is dropped and
# every OkHttp client speaks HTTP/1.1 (patches/wolfjsse-sslparameters.py explains). Built against
# libcore's SSLParameters with the ALPN API (the pinned file the coreoj step also uses), put in front of
# the jar's own dex like core-oj's.
step_wolfjsse() {
  local jar="$PREFIX/lib/java/dex/art/wolfssljni-hostdex.jar" w="$B/wolfjsse" want got
  local wsrc="$SRCDIR/art_standalone/external/wolfssljni/src/java/com/wolfssl/provider/jsse"
  local wcls="$SRCDIR/art_standalone/out/host/common/obj/JAVA_LIBRARIES/wolfssljni-hostdex_intermediates/classes.jar"
  [[ -f "$jar" && -f "$wcls" && -d "$wsrc" ]] || die "no wolfssljni jar, classes or sources (step art first)"
  [[ -f "$jar.orig" ]] || cp -p "$jar" "$jar.orig"
  rm -rf "$w"; mkdir -p "$w/libcore/javax/net/ssl" "$w/src/com/wolfssl/provider/jsse" "$w/classes"
  curl -fsSL --retry 3 "$LIBCORE_URL/+/$LIBCORE_COMMIT/ojluni/src/main/java/javax/net/ssl/SSLParameters.java?format=TEXT" | base64 -d > "$w/libcore/javax/net/ssl/SSLParameters.java"
  want=$(tr ' ' '\n' <<<"$LIBCORE_SSL_SHA256" | sed -n "s/^SSLParameters=//p")
  got=$(sha256sum "$w/libcore/javax/net/ssl/SSLParameters.java" | cut -d' ' -f1)
  [[ "$got" == "$want" ]] || die "libcore SSLParameters.java sha256 $got != pinned $want"
  python3 "$DEBVM_PATCHES/wolfjsse-sslparameters.py" "$wsrc" "$w/src/com/wolfssl/provider/jsse"
  # libcore's SSLParameters only resolves names (-implicit:none): the runtime class is core-oj's
  javac -g -nowarn -source 1.8 -target 1.8 -encoding UTF-8 -Xlint:-options -implicit:none \
    -bootclasspath "$PREFIX/lib/java/core-all_classes.jar" -sourcepath "$w/libcore" -cp "$wcls" \
    -d "$w/classes" "$w"/src/com/wolfssl/provider/jsse/*.java
  java -jar "$PREFIX/lib/java/dx.jar" --dex --core-library --output="$w/tl-wolfjsse.dex" "$w/classes"
  python3 "$DEBVM_PATCHES/core-oj-alpn.py" assemble "$jar.orig" "$w/tl-wolfjsse.dex" "$w/wolfssljni-hostdex.jar"
  install -m 0755 "$w/wolfssljni-hostdex.jar" "$jar"
  # the jar is on the boot class path: the boot image in this user's ART cache is stale
  rm -f "$HOME"/.cache/art/*/*boot* "$HOME"/.cache/art/*/*api-impl* 2>/dev/null || true
}
step_opensles() {
  atl_env
  fetch_git libopensles-standalone "$OPENSLES_REPO" "$OPENSLES_COMMIT"
  meson_build "$SRCDIR/libopensles-standalone" "$PREFIX"
}
step_atl() {
  atl_env
  # ATL's meson runs art_standalone's `dx` wrapper, which finds dx.jar as <dir>/java/dx.jar for the
  # linker's search dirs and LD_LIBRARY_PATH (Alpine: /usr/lib/java); ours is $PREFIX/lib/java
  export LD_LIBRARY_PATH="$PREFIX/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
  # the fork carries every ATL change; ~/atl-src stays the checkout (the scripts in tools/atl-vm use it)
  local d="$BHOME/atl-src"
  if [[ ! -d "$d/.git" ]] || [[ "$(git -C "$d" remote get-url origin)" != "$ATL_REPO" ]]; then
    rm -rf "$d"; git clone -q "$ATL_REPO" "$d"
  fi
  git -C "$d" fetch -q origin 2>/dev/null || true
  git -C "$d" checkout -q -f "$ATL_COMMIT"
  git -C "$d" clean -q -fdx
  log "ATL at $(git -C "$d" rev-parse --short=12 HEAD)"
  # D8 for the Java code (the fork's -Dr8_jar), checksummed
  local r8="$B/r8-$R8_VERSION.jar"
  if [[ ! -f "$r8" ]] || [[ "$(sha256sum "$r8" | cut -d' ' -f1)" != "$R8_SHA256" ]]; then
    curl -fsSL --retry 3 -o "$r8.tmp" "$R8_URL"
    [[ "$(sha256sum "$r8.tmp" | cut -d' ' -f1)" == "$R8_SHA256" ]] || die "r8.jar checksum mismatch"
    mv "$r8.tmp" "$r8"
  fi
  # upstream's upcall wrapper generator (tools/jni_upcall_gen) reads the jar with ASM; trixie's
  # libasm-java and libgetopt-java (guest-provision.sh) provide the jars, no Maven download
  BUILDTYPE=debugoptimized meson_build "$d" "$PREFIX" -Dr8_jar="$r8" \
    -Dasm_jar_path=/usr/share/java/asm.jar -Dasm_tree_jar_path=/usr/share/java/asm-tree.jar \
    -Dgetopt_jar_path=/usr/share/java/gnu-getopt.jar
  rm -f "$HOME"/.cache/art/*/*api-impl* 2>/dev/null || true
}

# --- tacomp and its private wlroots 0.20 stack ---
step_wayland() {
  tacomp_env
  fetch_git wayland "$WAYLAND_REPO" "$WAYLAND_TAG"
  meson_build "$SRCDIR/wayland" "$TPREFIX" -Ddocumentation=false -Dtests=false -Ddtd_validation=false
}
step_wayland-protocols() {
  tacomp_env
  fetch_git wayland-protocols "$WAYLAND_PROTOCOLS_REPO" "$WAYLAND_PROTOCOLS_TAG"
  meson_build "$SRCDIR/wayland-protocols" "$TPREFIX" -Dtests=false
}
step_libdrm() {
  tacomp_env
  fetch_git libdrm "$LIBDRM_REPO" "$LIBDRM_TAG"
  meson_build "$SRCDIR/libdrm" "$TPREFIX" -Dintel=disabled -Dradeon=disabled -Damdgpu=disabled \
    -Dnouveau=disabled -Dvmwgfx=disabled -Dfreedreno=disabled -Detnaviv=disabled -Dexynos=disabled \
    -Domap=disabled -Dtegra=disabled -Dvc4=enabled -Dcairo-tests=disabled -Dman-pages=disabled -Dtests=false
}
step_pixman() {
  # wlroots 0.20's pixman renderer (the one tacomp uses where there is no GPU, and its software path)
  # needs pixman >= 0.46; trixie has 0.44
  tacomp_env
  fetch_git pixman "${PIXMAN_REPO:-https://gitlab.freedesktop.org/pixman/pixman.git}" "${PIXMAN_TAG:-pixman-0.46.4}"
  meson_build "$SRCDIR/pixman" "$TPREFIX" -Dtests=disabled -Ddemos=disabled -Dgtk=disabled -Dlibpng=disabled
}
step_xkbcommon() {
  tacomp_env
  fetch_git libxkbcommon "$XKBCOMMON_REPO" "$XKBCOMMON_TAG"
  meson_build "$SRCDIR/libxkbcommon" "$TPREFIX" -Denable-x11=false -Denable-wayland=false \
    -Denable-docs=false -Denable-tools=false -Dxkb-config-root=/usr/share/X11/xkb
}
step_wlroots() {
  tacomp_env
  fetch_git wlroots "$WLROOTS_REPO" "$WLROOTS_TAG"
  # headless only: no DRM/libinput/X11 backends, GLES2 renderer on the render node, GBM allocator
  meson_build "$SRCDIR/wlroots" "$TPREFIX" -Dexamples=false -Dxwayland=disabled -Dbackends= \
    -Drenderers=gles2 -Dallocators=gbm -Dsession=disabled
}
step_tacomp() {
  tacomp_env
  [[ -d "$TACOMP_SRC/src" ]] || die "push services/display to ~/tacomp first (vm-deb.sh push-tacomp)"
  # services/display/build.sh links with plain pkg-config; our PKG_CONFIG_PATH selects the private stack
  sh "$TACOMP_SRC/build.sh" "$B/tacomp"
  install -d "$TPREFIX/bin"
  install -m 0755 "$B/tacomp/tacomp" "$TPREFIX/bin/tacomp"
  patchelf --set-rpath "$TPREFIX/lib" "$TPREFIX/bin/tacomp"
  ldd "$TPREFIX/bin/tacomp" | grep -E "wlroots|wayland-server|drm|xkbcommon" | sed 's/^/  /'
}

# --- tlplay: the app updater's download tool (tools/appupdate), into $PREFIX/bin for the installer ---
have_tlplay_inputs() { command -v cargo >/dev/null 2>&1 && [[ -f "$TLPLAY_SRC/Cargo.toml" ]]; }
step_tlplay() {
  have_tlplay_inputs || die "tlplay needs cargo (apt-get install cargo rustc libssl-dev pkg-config) and the source in $TLPLAY_SRC"
  # Cargo.lock pins every crate (--locked); the build tree stays out of the source
  export CARGO_TARGET_DIR="$B/tlplay-target"
  [[ -z "${TL_RUST_TARGET_CPU:-}" ]] || export RUSTFLAGS="-C target-cpu=$TL_RUST_TARGET_CPU"
  cargo build --release --locked --manifest-path "$TLPLAY_SRC/Cargo.toml"
  install -d "$PREFIX/bin"
  install -m 0755 "$CARGO_TARGET_DIR/release/tlplay" "$PREFIX/bin/tlplay"
}

ALL=(vixl wolfssl bionic art coreoj wolfjsse opensles atl wayland wayland-protocols libdrm pixman xkbcommon wlroots tacomp tlplay)
pins_of() { # what a step's stamp records: its pins plus the hash of the patches it applies
  case "$1" in
    vixl) echo "$VIXL_URL $VIXL_MESON_PATCH_SHA256" ;;
    wolfssl) echo "$WOLFSSL_TAG $WOLFSSL_FLAGS" ;;
    bionic) echo "$BIONIC_COMMIT $(cat "$BIONIC_PATCH" "$BIONIC_SYSCONF_PY" 2>/dev/null | sha256sum | cut -c1-16)" ;;
    art) echo "$ART_COMMIT $(cat "$DEBVM_PATCHES"/art_standalone-*.py 2>/dev/null | sha256sum | cut -c1-16)" ;;
    # the unpatched jar's hash too: a rebuilt art step installs a new one (and drops the .orig)
    coreoj) local j="$PREFIX/lib/java/dex/art/core-oj-hostdex.jar.orig"
      echo "$LIBCORE_COMMIT $(find "$BOOTCP" "$DEBVM_PATCHES/core-oj-alpn.py" -type f 2>/dev/null | sort | xargs cat 2>/dev/null | sha256sum | cut -c1-16) $( [[ -f $j ]] && sha256sum "$j" | cut -c1-16 || echo none)" ;;
    wolfjsse) local j="$PREFIX/lib/java/dex/art/wolfssljni-hostdex.jar.orig"
      echo "$ART_COMMIT $LIBCORE_COMMIT $(sha256sum "$DEBVM_PATCHES/wolfjsse-sslparameters.py" "$DEBVM_PATCHES/core-oj-alpn.py" 2>/dev/null | sha256sum | cut -c1-16) $( [[ -f $j ]] && sha256sum "$j" | cut -c1-16 || echo none)" ;;
    opensles) echo "$OPENSLES_COMMIT" ;;
    atl) echo "$ATL_REPO $ATL_COMMIT $R8_VERSION" ;;
    wayland) echo "$WAYLAND_TAG" ;;
    wayland-protocols) echo "$WAYLAND_PROTOCOLS_TAG" ;;
    libdrm) echo "$LIBDRM_TAG" ;;
    pixman) echo "${PIXMAN_TAG:-pixman-0.46.4}" ;;
    xkbcommon) echo "$XKBCOMMON_TAG" ;;
    wlroots) echo "$WLROOTS_TAG" ;;
    tacomp) echo "$(find "$TACOMP_SRC" -path "$TACOMP_SRC/build" -prune -o -type f -exec cat {} + 2>/dev/null | sha256sum | cut -c1-16)" ;;
    tlplay) echo "${TL_RUST_TARGET_CPU:-} $(find "$TLPLAY_SRC/src" "$TLPLAY_SRC/Cargo.toml" "$TLPLAY_SRC/Cargo.lock" -type f 2>/dev/null | sort | xargs cat 2>/dev/null | sha256sum | cut -c1-16)" ;;
  esac
}
run_step() {
  local s=$1 force=$2 pins; pins="$(pins_of "$s")"
  if [[ $force == 0 && -f "$STAMPS/$s" && "$(cat "$STAMPS/$s")" == "$pins" ]]; then
    log "$s: done (same pins), skipped"; return 0
  fi
  log "$s: building (log $LOGS/$s.log)"
  asroot ldconfig 2>/dev/null || true   # libraries installed by the previous step
  local t0=$SECONDS
  # not `if ( ... )`: bash ignores errexit inside a condition, so a failing command would not end the step
  local rc=0
  set +e
  ( set -euo pipefail; "step_$s" ) > "$LOGS/$s.log" 2>&1
  rc=$?
  set -e
  if [[ $rc == 0 ]]; then
    # recomputed: a step may create what its pins look at (coreoj keeps the unpatched jar)
    pins_of "$s" > "$STAMPS/$s"
    log "$s: ok in $((SECONDS - t0)) s"
  else
    log "$s: FAILED after $((SECONDS - t0)) s; last lines of $LOGS/$s.log:"
    tail -40 "$LOGS/$s.log"
    exit 1
  fi
}

FORCE=0
[[ "${1:-}" == "--force" ]] && { FORCE=1; shift; }
case "${1:-all}" in
  status)
    for s in "${ALL[@]}"; do
      if [[ -f "$STAMPS/$s" ]]; then
        [[ "$(cat "$STAMPS/$s")" == "$(pins_of "$s")" ]] && st="done" || st="stale"
      else st="-"; fi
      printf '%-18s %s\n' "$s" "$st"
    done
    ;;
  all)
    for s in "${ALL[@]}"; do
      # tlplay is optional in `all`: the VM has no Rust toolchain by default (the release build has one)
      if [[ $s == tlplay ]] && ! have_tlplay_inputs; then log "tlplay: skipped (no cargo or no source in $TLPLAY_SRC)"; continue; fi
      run_step "$s" "$FORCE"
    done
    log "all steps done; prefix: $(du -sh "$PREFIX" | cut -f1)" ;;
  *)
    for s in "$@"; do
      declare -F "step_$s" >/dev/null || die "unknown step $s (steps: ${ALL[*]})"
      run_step "$s" "$FORCE"
    done
    ;;
esac
