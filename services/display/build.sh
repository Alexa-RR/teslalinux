#!/bin/sh
# teslalinux: build tacomp (services/display/src) against wlroots 0.20, libwebsockets, libjpeg-turbo,
# with the vendored V4L2 M2M encoder (m2m/) for the Pi (TACOMP_ENCODER=m2m-jpeg|m2m-h264; the CPU
# JPEG path is the fallback and the default) and, when x264 is installed, a software H.264 encoder
# for renderers 1/2 where the hardware one is missing (TACOMP_ENCODER=m2m-h264 falls back to it,
# sw-h264 asks for it directly).
# Run on an Alpine host with: apk add build-base wlroots0.20-dev libwebsockets-dev wayland-dev
#   wayland-protocols pixman-dev libdrm-dev libxkbcommon-dev libjpeg-turbo-dev linux-headers x264-dev
#   (Debian: libx264-dev)
#   ./build.sh [outdir]   -> outdir/tacomp (default: ./build)
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
OUT=${1:-$HERE/build}
mkdir -p "$OUT"
PROTO=$(pkg-config --variable=pkgdatadir wayland-protocols)
wayland-scanner server-header "$PROTO/stable/xdg-shell/xdg-shell.xml" "$OUT/xdg-shell-protocol.h"
PKGS="wlroots-0.20 wayland-server libwebsockets libturbojpeg xkbcommon pixman-1 libdrm"
M2M=""
if [ -f /usr/include/linux/videodev2.h ]; then
	M2M="-DTACOMP_M2M $HERE/m2m/m2m.c $HERE/m2m/frame.c $HERE/m2m/logging.c"
else
	echo "no linux/videodev2.h: building without the V4L2 M2M encoder (apk add linux-headers)"
fi
X264=""
if pkg-config --exists x264; then
	PKGS="$PKGS x264"
	X264="-DTACOMP_X264"
else
	echo "no x264: building without the software H.264 encoder (apk add x264-dev / apt install libx264-dev)"
fi
# shellcheck disable=SC2046,SC2086
cc -std=gnu11 -D_GNU_SOURCE -O2 -g ${CFLAGS:-} -Wall -Wno-unused-parameter -Wno-unused-function -DWLR_USE_UNSTABLE -I"$OUT" -I"$HERE/m2m" $X264 \
   $(pkg-config --cflags $PKGS) "$HERE/src/main.c" $M2M -o "$OUT/tacomp" $(pkg-config --libs $PKGS) -lpthread -lm
echo "built $OUT/tacomp"
