# tacomp: the display path

One process replaces three upstream components (SurfaceFlinger virtual display capture, the
virtual-display encoder daemon, and the virtual-touchscreen kernel driver + daemon).

## What it is

A **headless Wayland compositor** built on wlroots 0.20 (`WLR_BACKENDS=headless`) with one output of
`TACOMP_SIZE` (default `1088x832@30`). Waydroid's `hwcomposer.waydroid` connects to it as an ordinary
Wayland client and presents the whole Android screen as a single `xdg_toplevel` (we run Waydroid with
`persist.waydroid.multi_windows=false`).

Protocols the Waydroid hwcomposer binds (verified in `upstream/android_hardware_waydroid/hwcomposer`):
`wl_compositor`, `wl_subcompositor`, `wl_shm`, `wl_seat` (touch + pointer + keyboard), `wl_output`,
`xdg_wm_base`, `wp_viewporter`, `wp_presentation`, `wl_data_device_manager`, `zwp_linux_dmabuf_v1`.
wlroots provides all of them.

## Frame path (zero-copy)

1. Android renders with Mesa v3d inside the container; gralloc is `gbm`, so every buffer is a dmabuf.
2. hwcomposer commits it via `zwp_linux_dmabuf_v1` (falls back to `wl_shm` if dmabuf import fails;
   then we take the shm path, one memcpy).
3. tacomp's `surface.commit` handler grabs the `wlr_buffer`, gets the dmabuf fd
   (`wlr_buffer_get_dmabuf`) and hands it to `m2m/m2m.c` as a `V4L2_MEMORY_DMABUF` input buffer on
   `/dev/video11` (H.264, Constrained Baseline, `REPEAT_SEQ_HEADER`) or `/dev/video31` (JPEG).
   Pixel format negotiation: the container renders `ARGB8888`/`XRGB8888` which the Pi codec accepts as
   `V4L2_PIX_FMT_BGR32`/`RGB32`; if the modifier is not linear (v3d may produce T-tiled/UIF), we
   request `DRM_FORMAT_MOD_LINEAR` through the dmabuf feedback tranche so the client allocates linear.
4. Encoded frames come back and are broadcast: one binary WebSocket message per frame on
   `TACOMP_DISPLAY_PORT` (9091). Newest-frame-wins: if the encoder is busy, the pending frame is replaced.
5. On client connect: resend the last encoded JPEG (MJPEG) or force an IDR (H.264), exactly like upstream.

As built today (`src/main.c`): tacomp composites with its wlroots scene and encodes the output
buffer. Its frame clock is its own periodic timerfd at exactly the output refresh (wlroots' headless
timer restarts a whole interval after every commit, which made each frame period build + encode +
16 ms). Each tick commits, sends the clients their frame callbacks, then hands the buffer to the
encoder thread (dmabuf for V4L2, or a copy / GLES read-back for x264 and libjpeg-turbo), so neither
the clients nor the compositor loop ever wait for an encoder.

Nothing is rendered by the compositor itself: there is no scene graph output, no screencopy, no GL
readback. The headless output exists only so the client gets a `wl_output` and a frame clock
(`wp_presentation` feedback drives Android's vsync).

## Touch path

A second WebSocket server on `TACOMP_TOUCH_PORT` (9999) accepts the upstream text protocol
(`s/T/a/X/Y/e/S` lines). tacomp keeps the 10-slot MT state and emits `wl_touch.down/motion/up/frame`
from a virtual `wlr_touch` device (`wlr_headless_add_input_device`, or a custom `wlr_touch`
implementation) targeting the Android surface. Coordinates are already in Android display pixels, so
the mapping is 1:1 with the output size.

## Audio

Not here. Waydroid talks PulseAudio directly to the host's `pipewire-pulse` socket (see `services/audio`).

## Build

```
meson setup build && ninja -C build      # deps: wlroots-0.18, wayland-server, libwebsockets, libdrm
sudo install -m755 build/tacomp /usr/local/bin/tacomp
```

Runtime env (set by the tacomp service, OpenRC or systemd, and `/etc/teslalinux/display.env`, which
the API's display state writes): `XDG_RUNTIME_DIR=/run/tesla`, `WAYLAND_DISPLAY=tesla-0`,
`TACOMP_SIZE`, `TACOMP_ENCODER` (unset: CPU JPEG; `m2m-jpeg` / `m2m-h264`: the Pi's V4L2 encoders;
where they are missing `m2m-jpeg` falls back to CPU JPEG and `m2m-h264` to software H.264 with x264
(ultrafast/zerolatency, Constrained Baseline, one slice per frame, BT.709 signalled), `sw-h264`: x264
directly). H.264 clients get every frame in order from a ring of the last 64; a new client starts at
the newest IDR in it, and one that falls a whole ring behind restarts at a key frame and gets an IDR, `TACOMP_QUALITY=1..100`, `TACOMP_DISPLAY_PORT`,
`TACOMP_TOUCH_PORT`, `TACOMP_BIND` (the services set `127.0.0.1`: only nginx reaches the sockets).

## Phase-1 fallback if tacomp is not ready

`cage` (wlroots kiosk) with `WLR_BACKENDS=headless WLR_HEADLESS_OUTPUTS=1` plus a `wlr-screencopy`
→ JPEG → WebSocket script gives a working, slower picture (CPU JPEG, ~10-15 fps at 1088x832).
Touch then needs a uinput device bind-mounted into the container. Use only for first-light testing.

## Source layout

* `m2m/` — vendored verbatim from upstream `android-external-tesla-android-virtual-display/encode/`
  (PiKVM ustreamer derived, GPL-3.0; see `LICENSE.upstream`). V4L2 M2M encoder, DMABUF input.
* `src/main.c` — tacomp v1 (wlroots 0.20 headless output, scene graph, xdg-shell fullscreen toplevel,
  libwebsockets display and touch servers, CPU JPEG via libjpeg-turbo). First light on the VM:
  Waze under ATL on the `wayland-atl` socket, 23 frames/s at 1280x720 with the pixman renderer, taps
  through the touch socket drive the app. `build.sh` builds it; `test/ws-client.py` exercises it.
  Next: the V4L2 M2M path (`m2m/`) on the Pi, dmabuf in, H.264 out.

## Device keyboard

`TACOMP_IME_PORT` (9996; nginx `/sockets/keyboard`, 0 = off) bridges Wayland **text-input-v3** to the car
page. GTK's input method context in the app enables text input when a field takes focus; tacomp sends
`{"type":"ime","active":true,"purpose":N,"hint":N}` (and `active:false` when it is disabled) to every
keyboard client. The page sends one command per line: `t TEXT` is committed into the field
(`commit_string`), `k enter|backspace|tab|escape` is a key press on the seat keyboard. Commands are queued
by the lws thread and applied on the main thread. Settings > Keyboard switches the app between its own
on-screen keyboard and this (`host/teslalinux-set-keyboard`).
