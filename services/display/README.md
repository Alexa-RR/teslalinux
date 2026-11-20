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
   wait for an encoder or the GPU. On the Pi (H.264) the output buffers are CMA (below) and the
   committed buffer's dmabuf is a `V4L2_MEMORY_DMABUF` input of `m2m/m2m.c` on `/dev/video11`,
   handed over once the GPU has drawn it; otherwise the frame is read back (CPU map, GLES read-back,
   or for JPEG the GPU's YUV conversion) and compressed by the hardware encoder from its own
   buffers (`/dev/video11` H.264, `/dev/video31` JPEG), libjpeg-turbo or x264. Newest-frame-wins: if
   the encoder is busy, the pending frame is replaced.
4. Encoded frames are broadcast: one binary WebSocket message per frame on `TACOMP_DISPLAY_PORT`
   (9091).
5. On client connect: resend the last encoded JPEG (MJPEG) or force an IDR (H.264), exactly like
   upstream; on a static screen the held last buffer is encoded again as that IDR.

## CMA output buffers (the Pi)

The Pi 4's codec (bcm2835-codec, vb2-dma-contig) reads only physically contiguous, linear memory.
V3D's own render buffers are neither: queued as a dmabuf they never produced a picture. tacomp
therefore gives the output swapchain buffers of its own (`cma_alloc` in `src/main.c`): linear 32-bit
buffers from the dma-heap `/dev/dma_heap/linux,cma` (the `video` group may open it), stride the
width's 4 bytes on a 64-byte bound, height padded to 16 rows. V3D renders into them (its MMU maps any
pages, and it draws linear targets) and the codec imports the same dmabuf. `m2m.c` keeps one DMABUF
slot per buffer, so each is imported once. tacomp checks at start that the renderer can draw into
such a buffer and otherwise keeps wlroots' allocator. It also turns off wlroots' direct scan-out
then: a client buffer handed over as it is would be V3D memory again. `TACOMP_CMA=0` turns all of
this off; `TACOMP_DMA_HEAP` names another heap. If the codec refuses the buffers, tacomp logs it and
reads frames back from then on.

Nothing waits for the GPU on the main thread. A composite of a client's frame queues behind the
client's own GPU work (implicit sync on its buffer), so each frame waits in the event loop until its
dmabuf polls readable (its write fence has signalled), then goes to the encoder; once one frame is
ready, older ones still waiting are dropped.

Why (measured on a Pi 4 on Nov 20 2026, Waze's car map, 832x608 H.264, `tools/debian/gpu-trace.sh`):
before, every frame was composited, then read back with `glReadPixels` on the main thread
(a second GPU job per frame and a CPU copy into the codec's buffer). With Waze's map uncapped
(`tools/apps/waze-map-pacing.py`), Waze's GPU jobs waited ~56 ms in its own queue. tacomp's composite
depended on the newest of them, and the read-back blocked tacomp's main thread for all of it: asleep
928 ms of every second, 7 frames a second built, 11.2 shown while Waze drew 37. With CMA buffers:
the main thread takes ~0.8 ms a frame, every frame Waze commits is encoded (~27-32 a second),
and tacomp's GPU share went from 6-7% to 3-4% (no read-back job).

Delivered frames a second / slowest tenth of seconds, Pi 4 at stock clocks, Waze 5.27.5 Car mode on
a road route (`tools/debian/pi-bench.py`), H.264, Nov 20 2026. "Uncapped" is Waze's map without its
30 frames/s caps (`tools/apps/waze-map-pacing.py`), "ahead" ATL's `ATL_SWAP_FRAMES_AHEAD`; the
milliseconds are a frame's age from composite to published:

| | read-back (before) | CMA buffers |
|---|---|---|
| 832x608, stock Waze | 22.5 / 8 | 23.6 / 10, 16 ms |
| 832x608, uncapped, 1 ahead | 24.0 / 11 | 27.2 / 12, ~75 ms |
| 832x608, uncapped, 2 ahead | 11.2 / 7 | 26.8 / 10, ~75 ms |
| 1184x928 (Native), stock | 7.5 / 7 | 15.2 / 6, ~150 ms |
| 1184x928, uncapped, 1 ahead | 7.3 / 6 | 15.2 / 4, ~150 ms |
| 1184x928, uncapped, 2 ahead | 7.4 / 7 | 15.3 / 5, ~150 ms |

What is left is Waze itself: its map takes ~24-29 ms of V3D time a frame at 832x608 (30-35 frames/s
while it runs smoothly) and ~51 ms at 1184x928, and it pauses on its own for up to 3 s (most of the
slow tenths). The long ages come from Waze drawing ahead of the GPU: ATL's eglSwapBuffers returns
as soon as the frame is queued, and a frame replaced before GTK showed it gives its token back, so
nothing makes the app wait for its earlier frames' GPU work. Holding the Wayland frame callbacks in
tacomp until the GPU is done was tried (Nov 20) and does not do that. Waze kept drawing, only GTK
committed fewer of its frames, and the results swung between 7 and 17 frames/s at 1184x928. The
place for that limit is ATL's swap: wait for the GPU fence of the frame before last.

The log line every 300 frames built shows the main thread's time per frame, how long frames waited
for the GPU, the encoder's time per frame and the time from capture to published frame.

## Waze's frame on the GPU (Nov 20 2026)

After the CMA buffers, the frame rate is what Waze's own frame costs the Pi 4's V3D. Three changes
in ATL took that from ~28 ms (832x608) and ~45 ms (1184x928) of GPU time a frame to ~14.5 and ~31:

* **The swap waits for the frame before last** (ATL `egl.c`, `tl_swap_gpu_throttle`,
  `ATL_SWAP_GPU_FRAMES`, default 1): eglSwapBuffers returns once the GPU work of all but the newest
  frame is done, as Android's buffer queue holds a producer back with the release fence. Waze could
  queue frame after frame behind the GPU before: its frames waited 78 ms on the GPU, now 26, at the
  same frame rate.
* **Fences no longer split a pass** (ATL `gl_passes.c`): Mesa's v3d flushes the job on every
  `glFenceSync`, which on a tiler means storing colour and depth and loading them again. Godot (Waze's
  map engine) creates three fences in the middle of its main pass, so that pass ran as four. ATL puts
  an app's fence in at its context's next flush instead, as Mesa asks its other drivers to do.
* **Clears of buffers that already hold the value are left out** (ATL `gl_passes.c`): Godot clears a
  2048x2048 shadow atlas twice a frame that nothing is drawn into, and an empty transparent viewport;
  each was a full pass (3 to 6 ms). ATL follows every write to an app's textures and renderbuffers and
  leaves out a clear only when everything it would clear provably holds that value already.

`tools/debian/glpasses.c` (an LD_PRELOAD tracer: each pass's framebuffer, attachments, clears, draws,
blits and what it samples) found these; `tools/debian/gl-passes-test.c` checks the clear and fence
rules against the GL's own results. Waze's frame at 1184x928 is now one 128x128 pass (0.1 ms), the
location arrow's transparent viewport (5.6 ms: 2 draws, but a full-size colour and depth store), the
map (22 ms, 200+ draws) and Godot's blit to the window (2.6 ms).

Then the frame rate is Waze's: it holds its car map at 30 frames/s (Settings > Waze interface > Map
frame rate: "Full" lifts that, verified Waze versions only, `tools/apps/waze-map-pacing.py`).

Delivered frames a second (run 2 of 2, warmed up) / slowest tenth, Waze's own frames a second, the
capture-to-published time in tacomp and the app's share of V3D's render queue; Pi 4 at stock clocks,
Waze 5.27.5 Car mode on a road route (`tools/debian/pi-bench.py`), H.264, max 66 C, never throttled:

| | delivered | Waze | capture to published | V3D render |
|---|---|---|---|---|
| 832x608, Waze's 30, before | 28.6 / 24 | 29.4 | 15 ms | 68% |
| 832x608, Waze's 30, now | 28.8 / 25 | 29.6 | 12 ms | 41% |
| 832x608, uncapped, swap throttle only | 33.6 / 28 | 34.8 | 36 ms | 84% |
| 832x608, uncapped, no throttle | 33.9 / 31 | 35.0 | 77 ms | 84% |
| **832x608, Full frame rate, now** | **48.8 / 38** | **53.4** | **19 ms** | 75% |
| 1184x928, before | 19.1 / 18 | 19.4 | 86 ms | 87% |
| **1184x928, now** (Waze's 30 and Full alike) | **26.3 / 22** | 26.9 | 59 ms | 80% |
| 1184x928, Full, Map sharpness 90% | 30.3 / 26 | 29.8 | 50 ms | 80% |
| 1184x928, Full, Map sharpness 80% | 36.1 / 27 | 36.3 | 39 ms | 78% |
| 1184x928, Godot 3D render scale 0.75 | 16.7 / 15 | 17.1 | 98 ms | 87% |
| 1184x928, Godot 3D render scale 0.5 | 18.1 / 15 | 18.3 | 89 ms | 86% |

At 832x608 the GPU is no longer the limit: Waze's own work on its render thread (~15 ms a frame)
is. At 1184x928 the GPU still is, which is why Map sharpness (the car-app host's render scale: a
smaller map surface, scaled up, Waze's panels and labels laid out as at full size) pays there (80% of the render queue; V3D's binner also waits for each render
job because Godot's vertex shaders read textures, which Mesa's v3d serialises). Godot's own 3D render
scale (`rendering/scaling_3d/scale` in an `override.cfg` beside its pack, which this Godot reads) is
slower: it adds a colour copy and a depth blit for each of Waze's three viewports, more than the
smaller 3D pass saves. Godot's shadow atlas settings in `override.cfg` do not reach Waze's atlas
(set from its code).

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

* `m2m/` — vendored from upstream `android-external-tesla-android-virtual-display/encode/`
  (PiKVM ustreamer derived, GPL-3.0; see `LICENSE.upstream`), our changes marked `teslalinux`
  (bounded waits, colour range, a DMABUF slot per buffer). V4L2 M2M encoder, MMAP or DMABUF input.
* `src/main.c` — tacomp (wlroots 0.20 headless output, scene graph, xdg-shell fullscreen toplevel,
  libwebsockets display, touch and keyboard servers, the encoders). First light on the VM:
  Waze under ATL on the `wayland-atl` socket, 23 frames/s at 1280x720 with the pixman renderer, taps
  through the touch socket drive the app. `build.sh` builds it; `test/ws-client.py` exercises it.
  On a Pi 4: H.264 from CMA buffers by DMABUF, JPEG from the GPU's YUV read-back.

## Device keyboard

`TACOMP_IME_PORT` (9996; nginx `/sockets/keyboard`, 0 = off) bridges Wayland **text-input-v3** to the car
page. GTK's input method context in the app enables text input when a field takes focus; tacomp sends
`{"type":"ime","active":true,"purpose":N,"hint":N}` (and `active:false` when it is disabled) to every
keyboard client. The page sends one command per line: `t TEXT` is committed into the field
(`commit_string`), `k enter|backspace|tab|escape` is a key press on the seat keyboard. Commands are queued
by the lws thread and applied on the main thread. Settings > Keyboard switches the app between its own
on-screen keyboard and this (`host/teslalinux-set-keyboard`).
