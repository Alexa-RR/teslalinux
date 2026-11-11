#!/bin/sh
# teslalinux ATL test VM: provision the Alpine guest (run inside the VM as the 'atl' user via sudo).
# Installs ATL + its prebuilt deps from edge/testing, a headless Wayland compositor, Mesa (llvmpipe),
# screenshot tooling, and the ATL build toolchain so we can patch api-impl and rebuild.
set -eu
log() { printf '\033[1;35m[guest]\033[0m %s\n' "$*"; }
# Alpine ships doas; sudo may or may not be present.
if command -v sudo >/dev/null 2>&1; then :; else sudo() { doas "$@"; }; fi

log "repositories -> edge (main, community, testing)"
sudo tee /etc/apk/repositories >/dev/null <<EOF
https://dl-cdn.alpinelinux.org/alpine/edge/main
https://dl-cdn.alpinelinux.org/alpine/edge/community
https://dl-cdn.alpinelinux.org/alpine/edge/testing
EOF
sudo apk update -q
sudo apk upgrade -q --available || true

log "ATL + runtime deps"
sudo apk add -q --no-progress android-translation-layer art_standalone bionic_translation libandroidfw \
  gtk4.0 mesa mesa-dri-gallium mesa-egl mesa-gles mesa-gbm mesa-vulkan-swrast \
  cage weston weston-backend-headless weston-shell-desktop grim wl-clipboard wayland-utils \
  pipewire pipewire-pulse wireplumber \
  font-dejavu font-noto ttf-dejavu \
  openjdk17-jdk \
  seatd dbus xdg-desktop-portal xdg-desktop-portal-wlr \
  bash coreutils procps tmux htop strace ltrace gdb file

log "ATL build toolchain (to patch api-impl)"
sudo apk add -q build-base meson ninja git clang clang-extra-tools java-common openjdk8-jdk \
  alsa-lib-dev glib-dev gtk4.0-dev libgudev-dev libportal-dev openxr-dev vulkan-loader-dev \
  webkit2gtk-6.0-dev libsecret-dev ffmpeg-dev sqlite-dev libcap-dev libdrm-dev \
  bionic_translation-dev art_standalone-dev libandroidfw-dev wolfssl-dev wolfssl-jni 2>&1 | tail -3 || true

log "services"
sudo rc-update add dbus default >/dev/null 2>&1 || true
sudo rc-update add seatd default >/dev/null 2>&1 || true
sudo rc-service dbus start >/dev/null 2>&1 || true
sudo rc-service seatd start >/dev/null 2>&1 || true
sudo addgroup atl seat 2>/dev/null || true
mkdir -p "$HOME/waze" "$HOME/.local/share/android_translation_layer"

log "versions"
apk info -v android-translation-layer art_standalone bionic_translation 2>/dev/null | sort -u
log "done"

# Android apps (Godot's Android port in Waze) open the system fonts by their Android path.
doas apk add -q font-roboto font-noto font-noto-hebrew font-noto-arabic font-noto-cjk 2>&1 | tail -1 || true
doas mkdir -p /system/fonts
for f in /usr/share/fonts/roboto/*.ttf /usr/share/fonts/noto/*.ttf /usr/share/fonts/noto-cjk/*; do [ -f "$f" ] && doas ln -sf "$f" /system/fonts/; done
