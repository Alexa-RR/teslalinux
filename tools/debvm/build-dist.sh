#!/bin/bash
# teslalinux: build the native Raspberry Pi OS (Debian trixie arm64, glibc) runtime inside the Debian
# build VM, into /opt/teslalinux (out/pios-plan/PLAN.md, section 1). Runs as `atl` in the guest.
#
#   build-dist.sh [all]          every step in order, skipping steps already done for the same pins
#   build-dist.sh STEP...        only these: vixl wolfssl bionic art libcored8 coreoj wolfjsse opensles atl
#                                wayland wayland-protocols libdrm pixman xkbcommon wlroots tacomp mesa tlplay
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
# every C/C++ compile through ccache when it is installed (guest-provision.sh): Debian's masquerade
# links come first in PATH, so make (art_standalone's HOST_CC := gcc), meson and autotools all use it.
# A step's sources are cleaned before it builds (git clean), so without it every changed pin, patch
# or flag rebuilt the whole step; with it, only the files whose preprocessed input changed compile.
if [[ -z "${CCACHE_DISABLE:-}" && -d /usr/lib/ccache ]] && command -v ccache >/dev/null; then
  export PATH="/usr/lib/ccache:$PATH"
  export CCACHE_DIR="${CCACHE_DIR:-$BHOME/.ccache}" CCACHE_MAXSIZE="${CCACHE_MAXSIZE:-5G}"
fi
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
# an AOSP project at a full commit id: only that commit, shallow (a plain clone of an AOSP project
# brings every branch and tag of Android with it)
fetch_git_commit() {
  local name=$1 repo=$2 commit=$3 d="$SRCDIR/$1"
  if [[ ! -d "$d/.git" ]] || [[ "$(git -C "$d" remote get-url origin 2>/dev/null)" != "$repo" ]]; then
    rm -rf "$d"; git init -q "$d"; git -C "$d" remote add origin "$repo"
  fi
  git -C "$d" cat-file -e "$commit^{commit}" 2>/dev/null || git -C "$d" fetch -q --depth 1 origin "$commit"
  git -C "$d" checkout -q -f "$commit"
  [[ "${NOCLEAN:-0}" == 1 ]] || git -C "$d" clean -q -fdx
  log "$name at $(git -C "$d" rev-parse --short=12 HEAD)"
}
# the pinned R8 (D8) jar, checksummed: ATL's Java code (-Dr8_jar) and libcore (step libcored8)
R8="$B/r8-$R8_VERSION.jar"
fetch_r8() {
  if [[ ! -f "$R8" ]] || [[ "$(sha256sum "$R8" | cut -d' ' -f1)" != "$R8_SHA256" ]]; then
    curl -fsSL --retry 3 -o "$R8.tmp" "$R8_URL"
    [[ "$(sha256sum "$R8.tmp" | cut -d' ' -f1)" == "$R8_SHA256" ]] || die "r8.jar checksum mismatch"
    mv "$R8.tmp" "$R8"
  fi
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
  python3 "$DEBVM_PATCHES/art_standalone-dex2oat-context.py"
  python3 "$DEBVM_PATCHES/art_standalone-conscrypt.py"
  python3 "$DEBVM_PATCHES/art_standalone-androidfw-free.py"
  # Alpine: make ____PREFIX=/usr ____LIBDIR=lib, then make ... install. Build and install with
  # the same prefix (art-standalone.pc is not regenerated when the prefix changes between the two).
  # C sources here are written for C23 (bool/true/false without <stdbool.h>, e.g. liblog's
  # fake_log_device.c): Alpine edge builds with GCC 15, whose default is gnu23; trixie's GCC 14
  # defaults to gnu17. HOST_GLOBAL_CONLYFLAGS is unset in the AOSP make files, so setting it on the
  # command line (inherited by sub-makes) only adds this.
  local cstd="HOST_GLOBAL_CONLYFLAGS=-std=gnu2x"
  make -j "$JOBS" ____PREFIX="$PREFIX" ____LIBDIR=lib "$cstd"
  make ____PREFIX="$PREFIX" ____INSTALL_ETC="$PREFIX/etc" ____LIBDIR=lib "$cstd" install
  # fresh dx-dexed core-oj, core-libart and wolfssljni jars: the libcored8, coreoj and wolfjsse steps
  # rebuild and patch them again (their .dx and .orig copies are the ones to start from)
  rm -f "$PREFIX"/lib/java/dex/art/core-{oj,libart}-hostdex.jar.dx \
    "$PREFIX/lib/java/dex/art/core-oj-hostdex.jar.orig" "$PREFIX/lib/java/dex/art/wolfssljni-hostdex.jar.orig"
}
# libcore's boot class path jars dexed with D8 instead of art_standalone's dx, as Android's build does:
# dx leaves libcore's own lambdas and method references as invoke-custom call sites, and ART cannot run
# the serializable ones (altMetafactory: Comparator.comparing, Map.Entry.comparingByKey, ...) or some
# plain ones (Collectors.groupingBy); D8 desugars them into classes (patches/libcore-d8.py explains).
# Each jar's dx original is kept as JAR.dx and every run starts from it; the D8 core-oj jar becomes the
# unpatched jar (.orig) the coreoj step adds the ALPN API to.
LIBCORE_D8_JARS=(core-oj-hostdex.jar core-libart-hostdex.jar)
step_libcored8() {
  local dex="$PREFIX/lib/java/dex/art" core="$PREFIX/lib/java/core-all_classes.jar" j jars=()
  [[ -f "$core" ]] || die "no $core (step art first)"
  fetch_r8
  for j in "${LIBCORE_D8_JARS[@]}"; do
    [[ -f "$dex/$j" ]] || die "no $dex/$j (step art first)"
    # the dx original: for core-oj the unpatched copy when the coreoj step has patched the jar already
    if [[ ! -f "$dex/$j.dx" ]]; then
      if [[ -f "$dex/$j.orig" ]]; then cp -p "$dex/$j.orig" "$dex/$j.dx"; else cp -p "$dex/$j" "$dex/$j.dx"; fi
    fi
    jars+=("$dex/$j")
  done
  python3 "$DEBVM_PATCHES/libcore-d8.py" "$R8" "$core" "$B/libcore-d8" "${jars[@]}"
  rm -rf "$B/libcore-d8"
  for j in "${LIBCORE_D8_JARS[@]}"; do chmod --reference="$dex/$j.dx" "$dex/$j"; done
  # the coreoj step starts again from the D8 jar (its stamp hashes the .orig, so it reruns)
  rm -f "$dex/core-oj-hostdex.jar.orig"
  rm -f "$HOME"/.cache/art/*/*boot* "$HOME"/.cache/art/*/*api-impl* 2>/dev/null || true
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
# --- ART's TLS and crypto provider: AOSP's Conscrypt on AOSP's BoringSSL, as on Android ---
# (art_standalone-conscrypt.py registers it in libcore and on the default boot class path)
# BoringSSL as AOSP's external/boringssl builds it (its generated top-level CMakeLists.txt, the same
# source lists as Android.bp): static, position independent, for libjavacrypto.so only
step_boringssl() {
  fetch_git_commit boringssl "$BORINGSSL_REPO" "$BORINGSSL_COMMIT"
  local o="$B/boringssl"
  rm -rf "$o"
  cmake -S "$SRCDIR/boringssl" -B "$o" -G Ninja -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON -DCMAKE_C_FLAGS="$CPU_CFLAGS" -DCMAKE_CXX_FLAGS="$CPU_CFLAGS"
  ninja -C "$o" -j "$JOBS" crypto ssl
}
# AOSP's platform Conscrypt (Android.bp: java_library "conscrypt" = repackaged/common + repackaged/platform
# + publicapi + generated NativeConstants and aconfig flags; cc_library_shared "libjavacrypto" with
# -DJNI_JARJAR_PREFIX=com/android/; the runtime library libandroidio it dlopen()s)
step_conscrypt() {
  local c="$SRCDIR/conscrypt" bs="$SRCDIR/boringssl" bo="$B/boringssl" art="$SRCDIR/art_standalone" w="$B/conscrypt"
  local dexdir="$PREFIX/lib/java/dex/art" core="$PREFIX/lib/java/core-all_classes.jar" e r p want got f
  [[ -f "$bo/libcrypto.a" && -f "$bo/libssl.a" ]] || die "no BoringSSL in $bo (step boringssl first)"
  [[ -f "$core" && -d "$dexdir/natives" && -d "$art/libnativehelper/include" ]] || die "no ART core jars, natives or art_standalone tree (step art first)"
  fetch_git_commit conscrypt "$CONSCRYPT_REPO" "$CONSCRYPT_COMMIT"
  python3 "$DEBVM_PATCHES/conscrypt-platform.py" "$c" "$CONSCRYPT_SYSTEM_CACERTS"
  rm -rf "$w"; mkdir -p "$w/gen/com/android/org/conscrypt" "$w/ann" "$w/libcore/javax/net/ssl" "$w/classes"
  # native: AOSP's flags (conscrypt_global/libjavacrypto-defaults), BoringSSL linked in and hidden
  local cxx=(clang++ -std=gnu++17 -O2 $CPU_CFLAGS -fPIC -Wall -Wextra -Werror -Wunused)
  "${cxx[@]}" -I"$bs/include" "$c/constants/src/gen/cpp/generate_constants.cc" "$bo/libssl.a" "$bo/libcrypto.a" -lpthread -o "$w/generate_constants"
  "$w/generate_constants" com.android.org.conscrypt > "$w/gen/com/android/org/conscrypt/NativeConstants.java"
  # -DANDROID as Soong compiles every module, host ones too (here: ART's FileDescriptor.descriptor and
  # JNI signatures); HAVE_SYS_UIO_H as art_standalone's Linux AndroidConfig.h says for liblog's headers
  "${cxx[@]}" -fvisibility=hidden -DANDROID -DHAVE_SYS_UIO_H=1 -DJNI_JARJAR_PREFIX=com/android/ -I"$c/common/src/jni/main/include" \
    -I"$bs/include" -I"$art/libnativehelper/include" -I"$art/libnativehelper/include/nativehelper" -I"$art/system/core/include" \
    -shared -o "$w/libjavacrypto.so" "$c"/common/src/jni/main/cpp/conscrypt/*.cc -Wl,--exclude-libs,ALL -Wl,-z,defs \
    "$bo/libssl.a" "$bo/libcrypto.a" -L"$PREFIX/lib/art" -llog -lpthread -ldl -Wl,-rpath,'$ORIGIN:$ORIGIN/../../../../art'
  # libjavacrypto dlopen()s libandroidio by name: glibc searches the caller's RUNPATH ($ORIGIN above)
  "${cxx[@]}" -fvisibility=hidden -I"$art/libnativehelper/include" -shared -o "$w/libandroidio.so" \
    "$DEBVM_PATCHES/conscrypt-libandroidio.cpp" -Wl,-z,defs -L"$dexdir/natives" -lnativehelper -Wl,-rpath,'$ORIGIN'
  # Java: compile-only annotations (CLASS/SOURCE retention) and libcore's ALPN API (the coreoj step's
  # pinned SSLParameters/SSLSocket/SSLEngine) resolve names only (-implicit:none)
  for e in $CONSCRYPT_ANNOTATIONS; do
    r=${e%%:*}; p=${e#*:}; want=${p#*=}; p=${p%=*}
    f="$w/ann/$(sed -E 's|^.*src/main/java/||; s|^java/||' <<<"${p#*/}")"; mkdir -p "$(dirname "$f")"
    curl -fsSL --retry 3 "https://android.googlesource.com/$r/+/$p?format=TEXT" | base64 -d > "$f"
    got=$(sha256sum "$f" | cut -d' ' -f1)
    [[ "$got" == "$want" ]] || die "$r ${p#*/} sha256 $got != pinned $want"
  done
  for f in SSLParameters SSLSocket SSLEngine; do
    curl -fsSL --retry 3 "$LIBCORE_URL/+/$LIBCORE_COMMIT/ojluni/src/main/java/javax/net/ssl/$f.java?format=TEXT" | base64 -d > "$w/libcore/javax/net/ssl/$f.java"
    want=$(tr ' ' '\n' <<<"$LIBCORE_SSL_SHA256" | sed -n "s/^$f=//p")
    got=$(sha256sum "$w/libcore/javax/net/ssl/$f.java" | cut -d' ' -f1)
    [[ "$got" == "$want" ]] || die "libcore $f.java sha256 $got != pinned $want"
  done
  python3 "$DEBVM_PATCHES/conscrypt-aconfig.py" "$c/conscrypt.aconfig" "$w/gen"
  local srcs=(); mapfile -t srcs < <(find "$c/repackaged/common/src/main/java" "$c/repackaged/platform/src/main/java" \
    "$c/publicapi/src/main/java" "$w/gen" -name '*.java' | sort)
  javac -g -nowarn -source 1.8 -target 1.8 -encoding UTF-8 -Xlint:-options -implicit:none \
    -bootclasspath "$core" -sourcepath "$w/ann:$w/libcore" -d "$w/classes" "${srcs[@]}"
  java -jar "$PREFIX/lib/java/dx.jar" --dex --core-library --min-sdk-version=24 --output="$w/conscrypt-hostdex.jar" "$w/classes"
  install -m 0644 "$w/conscrypt-hostdex.jar" "$dexdir/conscrypt-hostdex.jar"
  install -m 0755 "$w/libjavacrypto.so" "$w/libandroidio.so" "$dexdir/natives/"
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
  # D8 for the Java code (the fork's -Dr8_jar)
  fetch_r8
  # upstream's upcall wrapper generator (tools/jni_upcall_gen) reads the jar with ASM; trixie's
  # libasm-java and libgetopt-java (guest-provision.sh) provide the jars, no Maven download
  BUILDTYPE=debugoptimized meson_build "$d" "$PREFIX" -Dr8_jar="$R8" \
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

# --- the app's Mesa ($PREFIX/mesa): Mesa MESA_VERSION with tools/debvm/patches/mesa-*.patch (V3D
# work for Waze's map: a bin job waits for rendering only when a vertex texture may still be being
# written, and a blit over all of its destination does not load it first). Only the app uses it:
# the launcher puts $PREFIX/mesa/lib first on its library path, so glvnd's libEGL (the system's,
# whose vendor file names libEGL_mesa.so.0) loads this libEGL_mesa and its libgallium; tacomp and
# the rest of the system keep the system's Mesa. v3d (the Pi 4's GPU) and vc4 (its display, which
# hands rendering to v3d), EGL on Wayland, GBM, GL and GLES 1-3 through glvnd: GLES 1 too, because an
# eglChooseConfig without EGL_RENDERABLE_TYPE (GTK's) asks for EGL_OPENGL_ES_BIT.
step_mesa() {
  cpu_env
  local d="$SRCDIR/mesa" t="$B/mesa-$MESA_VERSION.tar.xz"
  if [[ ! -f "$t" ]] || [[ "$(sha256sum "$t" | cut -d' ' -f1)" != "$MESA_SHA256" ]]; then
    curl -fsSL --retry 3 -o "$t.tmp" "$MESA_URL"
    [[ "$(sha256sum "$t.tmp" | cut -d' ' -f1)" == "$MESA_SHA256" ]] || die "Mesa tarball checksum mismatch"
    mv "$t.tmp" "$t"
  fi
  rm -rf "$d"; mkdir -p "$d"
  tar -xJf "$t" -C "$d" --strip-components=1
  local p
  for p in "$DEBVM_PATCHES"/mesa-*.patch; do
    log "mesa: $(basename "$p")"
    patch -d "$d" -p1 --no-backup-if-mismatch -s < "$p"
  done
  # Mesa takes wayland-protocols 1.49 from its own wrap (trixie has 1.45), whose XML needs
  # wayland-scanner 1.24 (trixie has 1.23): tacomp's private wayland provides the scanner only
  local pc="$B/mesa-pkgconfig"
  rm -rf "$pc"; mkdir -p "$pc"
  cp "$TPREFIX/lib/pkgconfig/wayland-scanner.pc" "$pc/"
  PKG_CONFIG_PATH="$pc" meson_build "$d" "$PREFIX/mesa" -Db_ndebug=true -Dplatforms=wayland \
    -Dgallium-drivers=v3d,vc4 -Dvulkan-drivers= -Dglx=disabled -Dgles1=enabled -Dgles2=enabled \
    -Dopengl=true -Degl=enabled -Dgbm=enabled -Dglvnd=enabled -Dllvm=disabled -Dgallium-va=disabled \
    -Dvideo-codecs= -Dvalgrind=disabled -Dlibunwind=disabled -Dbuild-tests=false -Dtools=
  rm -rf "$PREFIX/mesa/include" "$PREFIX/mesa/lib/pkgconfig"
  ls -la "$PREFIX/mesa/lib" | sed 's/^/  /'
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

ALL=(vixl wolfssl bionic art libcored8 coreoj wolfjsse boringssl conscrypt opensles atl wayland wayland-protocols libdrm pixman xkbcommon wlroots tacomp mesa tlplay)
pins_of() { # what a step's stamp records: its pins plus the hash of the patches it applies
  case "$1" in
    vixl) echo "$VIXL_URL $VIXL_MESON_PATCH_SHA256" ;;
    wolfssl) echo "$WOLFSSL_TAG $WOLFSSL_FLAGS" ;;
    bionic) echo "$BIONIC_COMMIT $(cat "$BIONIC_PATCH" "$BIONIC_SYSCONF_PY" 2>/dev/null | sha256sum | cut -c1-16)" ;;
    # wolfSSL's too: art builds wolfssljni's JNI library against libwolfssl's headers (options.h)
    art) echo "$ART_COMMIT $(cat "$DEBVM_PATCHES"/art_standalone-*.py 2>/dev/null | sha256sum | cut -c1-16) $(printf '%s %s' "$WOLFSSL_TAG" "$WOLFSSL_FLAGS" | sha256sum | cut -c1-16)" ;;
    # the dx originals' hashes too: a rebuilt art step installs new ones (and drops the .dx copies)
    libcored8) local j h=""
      for j in "${LIBCORE_D8_JARS[@]}"; do j="$PREFIX/lib/java/dex/art/$j.dx"; h+=" $( [[ -f $j ]] && sha256sum "$j" | cut -c1-16 || echo none)"; done
      echo "$R8_VERSION $(sha256sum "$DEBVM_PATCHES/libcore-d8.py" 2>/dev/null | cut -c1-16) $( [[ -f $PREFIX/lib/java/core-all_classes.jar ]] && sha256sum "$PREFIX/lib/java/core-all_classes.jar" | cut -c1-16 || echo none)$h" ;;
    # the unpatched jar's hash too: a rebuilt art or libcored8 step installs a new one (and drops the .orig)
    coreoj) local j="$PREFIX/lib/java/dex/art/core-oj-hostdex.jar.orig"
      echo "$LIBCORE_COMMIT $(find "$BOOTCP" "$DEBVM_PATCHES/core-oj-alpn.py" -type f 2>/dev/null | sort | xargs cat 2>/dev/null | sha256sum | cut -c1-16) $( [[ -f $j ]] && sha256sum "$j" | cut -c1-16 || echo none)" ;;
    wolfjsse) local j="$PREFIX/lib/java/dex/art/wolfssljni-hostdex.jar.orig"
      echo "$ART_COMMIT $LIBCORE_COMMIT $(sha256sum "$DEBVM_PATCHES/wolfjsse-sslparameters.py" "$DEBVM_PATCHES/core-oj-alpn.py" 2>/dev/null | sha256sum | cut -c1-16) $( [[ -f $j ]] && sha256sum "$j" | cut -c1-16 || echo none)" ;;
    boringssl) echo "$BORINGSSL_COMMIT ${TL_CPU_CFLAGS:-}" ;;
    # BoringSSL's and ART's too: libjavacrypto links the one, compiles against the other's headers
    conscrypt) echo "$CONSCRYPT_COMMIT $BORINGSSL_COMMIT $ART_COMMIT $LIBCORE_COMMIT $CONSCRYPT_SYSTEM_CACERTS $(printf '%s' "$CONSCRYPT_ANNOTATIONS" | sha256sum | cut -c1-16) $(sha256sum "$DEBVM_PATCHES"/conscrypt-* 2>/dev/null | sha256sum | cut -c1-16)" ;;
    opensles) echo "$OPENSLES_COMMIT" ;;
    atl) echo "$ATL_REPO $ATL_COMMIT $R8_VERSION" ;;
    wayland) echo "$WAYLAND_TAG" ;;
    wayland-protocols) echo "$WAYLAND_PROTOCOLS_TAG" ;;
    libdrm) echo "$LIBDRM_TAG" ;;
    pixman) echo "${PIXMAN_TAG:-pixman-0.46.4}" ;;
    xkbcommon) echo "$XKBCOMMON_TAG" ;;
    wlroots) echo "$WLROOTS_TAG" ;;
    tacomp) echo "$(find "$TACOMP_SRC" -path "$TACOMP_SRC/build" -prune -o -type f -exec cat {} + 2>/dev/null | sha256sum | cut -c1-16)" ;;
    # the patches too, and tacomp's wayland (its scanner generates part of the build)
    mesa) echo "$MESA_VERSION $MESA_SHA256 ${TL_CPU_CFLAGS:-} $WAYLAND_TAG $(cat "$DEBVM_PATCHES"/mesa-*.patch 2>/dev/null | sha256sum | cut -c1-16)" ;;
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
