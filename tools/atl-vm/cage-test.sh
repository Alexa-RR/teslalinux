#!/bin/sh
# smoke test: headless cage + virtual pointer (wlrctl) + screenshot (grim)
export XDG_RUNTIME_DIR=/tmp/xdg-cage; mkdir -p "$XDG_RUNTIME_DIR"; chmod 700 "$XDG_RUNTIME_DIR"
pkill -f "[c]age --" 2>/dev/null; sleep 1; rm -f "$XDG_RUNTIME_DIR"/wayland-*
export WLR_BACKENDS=headless WLR_LIBINPUT_NO_DEVICES=1 LIBGL_ALWAYS_SOFTWARE=1 WLR_RENDERER=${WLR_RENDERER:-gles2}
cage -- sleep 25 > /tmp/cage.log 2>&1 &
for i in $(seq 1 20); do [ -S "$XDG_RUNTIME_DIR/wayland-0" ] && break; sleep 0.5; done
ls "$XDG_RUNTIME_DIR"
export WAYLAND_DISPLAY=wayland-0
sleep 3
wlrctl pointer move 200 150 && echo pointer-ok
wlrctl pointer click left && echo click-ok
wtype -d 50 "hello" && echo type-ok
grim /tmp/cage.png && ls -la /tmp/cage.png | cut -c25-
grep -iE "error|renderer|backend|output" /tmp/cage.log | head -8 | cut -c1-150
pkill -f "[c]age --"
