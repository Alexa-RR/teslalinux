# tacomp: the display path

One process replaces three upstream components (SurfaceFlinger virtual display capture, the
virtual-display encoder daemon, and the virtual-touchscreen kernel driver + daemon).

## What it is

A **headless Wayland compositor** built on wlroots 0.20 (`WLR_BACKENDS=headless`) with one output of
`TACOMP_SIZE` (default `1088x832@60`). The app runs under the Android Translation Layer, which is a
GTK 4 program: it connects to tacomp as an ordinary Wayland client and its window is shown
fullscreen as an `xdg_toplevel`; its popups are `xdg_popup`s in the same scene.

Globals tacomp offers: `wl_compositor`, `wl_subcompositor`, `wl_shm` and `zwp_linux_dmabuf_v1` (from
the renderer), `wl_data_device_manager`, `xdg_wm_base`, `wl_output`, `wl_seat` (touch, pointer and a
keyboard with a keymap) and `zwp_text_input_manager_v3` (the device keyboard, below).

## Frame path

1. The app renders with GTK (on the Pi `GSK_RENDERER=ngl`, Mesa v3d; in the VMs llvmpipe or cairo)
   and commits its buffers, dmabuf or shm.
2. tacomp runs its own frame clock: a periodic timerfd at exactly the output refresh (wlroots'
   headless timer restarts a whole interval after every commit, which made each frame period
   build + encode + 16 ms). Each tick composites the scene into the output buffer
   (`WLR_RENDERER=gles2` on the Pi, pixman in the VMs), commits, sends the clients their frame
   callbacks, and skips frames without damage.
3. The committed buffer goes to the encoder thread, so neither the clients nor the compositor loop
   wait for an encoder. On the Pi the output buffer's dmabuf is a `V4L2_MEMORY_DMABUF` input of
   `m2m/m2m.c` on `/dev/video11` (H.264) or `/dev/video31` (JPEG); otherwise the frame is read back
   (CPU map or GLES read-back) and compressed with libjpeg-turbo or x264. Newest-frame-wins: if the
   encoder is busy, the pending frame is replaced.
4. Encoded frames are broadcast: one binary WebSocket message per frame on `TACOMP_DISPLAY_PORT`
   (9091).
5. On client connect: resend the last encoded JPEG (MJPEG) or force an IDR (H.264), exactly like
   upstream; on a static screen the held last buffer is encoded again as that IDR.

## Touch path

A second WebSocket server on `TACOMP_TOUCH_PORT` (9999) accepts the upstream text protocol
(`s/T/a/X/Y/e/S` lines). tacomp keeps the multi-touch slot state and emits
`wl_touch.down/motion/up/frame` on its seat, to the surface under each touch point. Coordinates are
already in output pixels, so the mapping is 1:1 with the output size. The lines are read on the
libwebsockets thread and applied on the main thread (wlroots is not thread-safe).

## Audio

Not here. The app plays through ALSA's default device, which is PipeWire's null sink `android`;
`services/audio` streams its monitor.

## Build

```
./build.sh [outdir]      # -> outdir/tacomp (default ./build)
```

Dependencies (the header of `build.sh` has the package names): wlroots 0.20, wayland-server,
wayland-protocols, libwebsockets, libjpeg-turbo, xkbcommon, pixman, libdrm; `linux/videodev2.h`
for the V4L2 M2M encoder, x264 optionally for software H.264. Debian trixie ships wlroots 0.18, so
`tools/debvm/build-dist.sh` builds tacomp against a private wlroots 0.20 into
`/opt/teslalinux/tacomp`.

Runtime env (set by the tacomp service, OpenRC or systemd, and `/etc/teslalinux/display.env`, which
the API's display state writes): `XDG_RUNTIME_DIR=/run/tesla`, `WAYLAND_DISPLAY=tesla-0`,
`TACOMP_SIZE`, `TACOMP_ENCODER` (unset: CPU JPEG; `m2m-jpeg` / `m2m-h264`: the Pi's V4L2 encoders;
where they are missing `m2m-jpeg` falls back to CPU JPEG and `m2m-h264` to software H.264 with x264
(ultrafast/zerolatency, Constrained Baseline, one slice per frame, BT.709 signalled), `sw-h264`: x264
directly). H.264 clients get every frame in order from a ring of the last 64; a new client starts at
the newest IDR in it, and one that falls a whole ring behind restarts at a key frame and gets an IDR, `TACOMP_QUALITY=1..100`, `TACOMP_DISPLAY_PORT`,
`TACOMP_TOUCH_PORT`, `TACOMP_BIND` (the services set `127.0.0.1`: only nginx reaches the sockets).
SIGHUP re-reads `/etc/teslalinux/display.env` and applies a new size, encoder or quality in place.

## Source layout

* `m2m/` — vendored verbatim from upstream `android-external-tesla-android-virtual-display/encode/`
  (PiKVM ustreamer derived, GPL-3.0; see `LICENSE.upstream`). V4L2 M2M encoder, DMABUF input.
* `src/main.c` — tacomp (wlroots 0.20 headless output, scene graph, xdg-shell fullscreen toplevel,
  libwebsockets display, touch and keyboard servers, the encoders). First light on the VM:
  Waze under ATL on the `wayland-atl` socket, 23 frames/s at 1280x720 with the pixman renderer, taps
  through the touch socket drive the app. `build.sh` builds it; `test/ws-client.py` exercises it.
  Still to verify on the Pi: the V4L2 M2M path (dmabuf in, H.264 or JPEG out).

## Device keyboard

`TACOMP_IME_PORT` (9996; nginx `/sockets/keyboard`, 0 = off) bridges Wayland **text-input-v3** to the car
page. GTK's input method context in the app enables text input when a field takes focus; tacomp sends
`{"type":"ime","active":true,"purpose":N,"hint":N}` (and `active:false` when it is disabled) to every
keyboard client. The page sends one command per line: `t TEXT` is committed into the field
(`commit_string`), `k enter|backspace|tab|escape` is a key press on the seat keyboard. Commands are queued
by the lws thread and applied on the main thread. Settings > Keyboard switches the app between its own
on-screen keyboard and this (`host/teslalinux-set-keyboard`).
