#!/bin/sh
# teslalinux Debian trixie arm64 build VM: install the build dependencies of the native Pi OS stack
# (build-dist.sh), and the runtime bits to test Waze under ATL in the VM (cage/weston headless,
# llvmpipe), mirroring tools/atl-vm/guest-provision.sh for Alpine. Idempotent. Run as `atl`.
#
#   guest-provision.sh         the VM (as atl): build deps + test runtime + /opt/teslalinux for atl
#   guest-provision.sh --ci    a build container (the release workflow, as root): build deps only,
#                              plus ccache; reads versions.env from VERSIONS_ENV or next to this script
set -eu
log() { printf '\033[1;35m[debvm]\033[0m %s\n' "$*"; }
MODE=vm
[ "${1:-}" = "--ci" ] && MODE=ci
if [ "$(id -u)" = 0 ]; then SUDO=""; else SUDO=sudo; fi
if [ -z "${VERSIONS_ENV:-}" ]; then
  if [ $MODE = ci ]; then VERSIONS_ENV="$(cd "$(dirname "$0")" && pwd)/versions.env"; else VERSIONS_ENV="$HOME/versions.env"; fi
fi
# shellcheck source=versions.env
. "$VERSIONS_ENV"
export DEBIAN_FRONTEND=noninteractive
# first boot: cloud-init runs its own apt-get update/install and holds the apt lock until done
if [ $MODE = vm ] && command -v cloud-init >/dev/null 2>&1; then
  log "waiting for cloud-init"
  $SUDO cloud-init status --wait >/dev/null 2>&1 || true
fi
$SUDO apt-get update -q
MISSING=""
# install what exists; collect names this release does not have (reported at the end)
inst() {
  avail=""
  for p in "$@"; do
    if apt-cache show "$p" >/dev/null 2>&1; then avail="$avail $p"; else MISSING="$MISSING $p"; fi
  done
  # shellcheck disable=SC2086
  $SUDO apt-get install -y -q --no-install-recommends $avail
}

log "toolchain"
inst \
  build-essential gcc g++ clang make meson ninja-build cmake pkgconf autoconf automake libtool \
  git curl ca-certificates rsync python3 python3-mako zip unzip xz-utils zstd file patchelf \
  "$JDK_PKG" dpkg-dev fakeroot
# tlplay (tools/appupdate, Rust; build-dist.sh step tlplay)
inst cargo rustc libssl-dev
if [ $MODE = ci ]; then inst ccache; else inst doas; fi

log "ATL stack build deps (ATL doc/Build.md Debian list + art_standalone README + Alpine makedepends)"
inst \
  libasound2-dev libavcodec-dev libswscale-dev libcap-dev libdrm-dev libglib2.0-dev libgtk-4-dev \
  libgudev-1.0-dev libopenxr-dev libportal-dev libportal-gtk4-dev libsqlite3-dev libwebkitgtk-6.0-dev \
  libsecret-1-dev libfontconfig-dev libvulkan-dev wayland-protocols libwayland-dev libgtk4-layer-shell-dev \
  libegl-dev libgl-dev libgles-dev libbsd-dev libelf-dev libunwind-dev \
  libicu-dev libssl-dev libexpat1-dev liblz4-dev liblzma-dev zlib1g-dev libpng-dev libselinux1-dev \
  valgrind python-is-python3 \
  libsndfile1-dev libsdl2-dev \
  libasm-java libgetopt-java

log "tacomp private stack deps (wayland, libdrm, xkbcommon, wlroots 0.20 built from source)"
inst \
  libffi-dev libxml2-dev expat libpixman-1-dev libgbm-dev xkb-data bison flex \
  libwebsockets-dev libturbojpeg0-dev libjpeg62-turbo-dev libx264-dev linux-libc-dev hwdata glslang-tools

if [ $MODE = vm ]; then
  log "runtime for testing in the VM (llvmpipe, headless compositors, fonts)"
  inst \
    mesa-utils libgl1-mesa-dri libegl-mesa0 libgles2 cage weston grim wl-clipboard wayland-utils \
    pipewire pipewire-pulse pipewire-alsa wireplumber dbus-user-session seatd \
    fonts-roboto fonts-noto-core fonts-noto-cjk fonts-dejavu-core \
    strace gdb htop tmux procps

  # patch-core-oj.sh and the Alpine-era scripts call doas
  if ! sudo test -f /etc/doas.conf || ! sudo grep -q 'permit nopass atl' /etc/doas.conf; then
    echo 'permit nopass atl' | sudo tee /etc/doas.conf >/dev/null
    sudo chmod 0400 /etc/doas.conf
  fi

  # the prefix the packages are built for; ~/atl-prefix points at it so the repo's Alpine-era
  # scripts (hostbuild.sh pushes to ~/atl-prefix/lib/java/dex/...) work against this VM unchanged
  sudo install -d -o atl -g atl /opt/teslalinux
  [ -L "$HOME/atl-prefix" ] || { [ -e "$HOME/atl-prefix" ] && mv "$HOME/atl-prefix" "$HOME/atl-prefix.old"; ln -s /opt/teslalinux "$HOME/atl-prefix"; }
  sudo usermod -aG video,render,input,audio atl 2>/dev/null || true
else
  $SUDO install -d /opt/teslalinux
fi
# the ATL stack's libraries live in /opt/teslalinux/lib{,/art}: the dynamic loader and GNU ld (which
# resolves a linked library's DT_NEEDED through ld.so.conf, e.g. dex2oat -> libart-compiler ->
# libvixl.so.5) must find them; the Pi installer writes the same file. tacomp's private stack
# (/opt/teslalinux/tacomp/lib) is deliberately NOT listed: it is found through its own RUNPATH.
printf '/opt/teslalinux/lib\n/opt/teslalinux/lib/art\n' | $SUDO tee /etc/ld.so.conf.d/teslalinux.conf >/dev/null
$SUDO ldconfig

if [ $MODE = vm ]; then
  # Android apps open fonts by their Android path (as in the Alpine VM)
  sudo mkdir -p /system/fonts
  for f in /usr/share/fonts/truetype/roboto/unhinted/RobotoTTF/*.ttf /usr/share/fonts/truetype/noto/*.ttf /usr/share/fonts/opentype/noto/*; do
    [ -f "$f" ] && sudo ln -sf "$f" /system/fonts/
  done
fi

[ -z "$MISSING" ] || log "NOT IN THIS RELEASE (check the names):$MISSING"
log "versions"
dpkg-query -W -f '${Package} ${Version}\n' libgtk-4-1 libavcodec61 libwebkitgtk-6.0-4 libicu76 libunwind8 "$JDK_PKG" meson rustc 2>/dev/null || true
java -version 2>&1 | head -1
log "done"
