# Architecture

## 1. What upstream does, and why we are not keeping it

Upstream Tesla Android (2026.x) is **AOSP 14 (GloDroid) on bare metal**: Pi firmware → ARM-TF → U-Boot →
Android boot image → full Android init. Every user-facing service (the hotspot, the web server, the
display streamer) is an Android init service that starts `on property:sys.boot_completed=1`. So the car
cannot even see a web page until Android has fully booted. Upstream publishes no boot number; the
release notes talk about shaving "~10 s" and first boot taking "5-15 minutes".

This fork puts **Linux underneath**. Everything the car needs to *see something* (Wi-Fi AP, DHCP/DNS,
TLS web server, launcher page) runs on Linux and is up in seconds. Android runs in a
[Waydroid](https://github.com/waydroid/waydroid) LXC container that boots *in parallel*, and the page
switches from the splash to the live stream when the container is ready.

## 2. Decisions

### 2.1 Waydroid (LXC) rather than Lepton

[Lepton](https://gitlab.steamos.cloud/frame-public/lepton) (Valve, open-sourced 2026-09) was examined
in full (`upstream/lepton`). It is a Waydroid fork re-plumbed for the Steam Frame:

* rootless **podman** container, every Android process runs as the same host uid (fake-uid patches);
* AOSP 14 with Qualcomm sm8150 display HALs (Snapdragon-first; Mesa/minigbm is a secondary path);
* removes Telephony, Tethering, wificond; FusedLocation is kept only "because required"; hides the
  flatscreen window; is explicitly "not designed for general Android use";
* hard-wired to Steam (steam.pipe mount, Steamworks libs, SteamVR paths).

Waze needs Google Play Services (or microG), a location provider, a working network stack and a normal
window. Lepton strips exactly those. So the container is **Waydroid**, which:

* runs on aarch64 today, has LineageOS 20 (Android 13) images with GApps on `ota.waydro.id`;
* needs only `binder`/`binderfs` and PSI, which the stock Raspberry Pi kernel ships (see 2.3);
* talks to the host through a plain Wayland socket (hwcomposer is a Wayland client) and a PulseAudio
  socket. That is the hook we use for display, touch and audio.

What we **borrow from Lepton** (all host-side or property-level, no AOSP rebuild needed for Phase 1):

| Lepton trick | Our equivalent |
|---|---|
| `sysbake`: boot once, snapshot `/data`, overlay it on every launch | Waydroid keeps `/data` persistent; `android/bake.sh` does the one-time first boot + `pm disable` pass so the SD card ships pre-baked |
| `config.disable_cameraservice/otadexopt/systemtextclassifier/networktime=true` | same props in `android/waydroid_base.prop` |
| `RemovePackages` override list (~60 packages) | `android/disable-packages.txt`, applied with `pm disable-user` during bake |
| no `bootanimation`, lmkd/dalvik tuning, `debug.sf.*` latency props | same props |
| frameworks/base patch "disable many more services" | Phase 3 (custom image) only |

### 2.2 Host OS: Pi OS Lite first, Buildroot second

Phase 1 uses **Raspberry Pi OS Lite arm64 (trixie)** because Waydroid, hostapd, nginx, ModemManager and
Mesa are all packaged and the Pi kernel is maintained. `host/phase1-pios/provision.sh` turns a fresh
image into the full stack and strips systemd down to the critical path.

Phase 2 replaces it with a **Buildroot** image (`host/buildroot/`, external tree, to be added):
no initramfs, kernel with built-in drivers, LZ4 kernel, squashfs root on an f2fs data partition,
busybox init or a 30-line static init that starts the critical path in parallel, then `lxc-start`.
Waydroid's Python tool is not needed at runtime: its job (mount images, write LXC config, inject
props, create `waydroid0` bridge) can be pre-generated at image build time.

### 2.3 Kernel

Stock `bcm2711_defconfig` (rpi-6.12.y, verified from `raspberrypi/linux`) already has:
`CONFIG_ANDROID_BINDER_IPC=y`, `CONFIG_ANDROID_BINDERFS=y`, `CONFIG_PSI=y` (default disabled, so we pass
`psi=1`), cgroups/namespaces, `VETH`/`BRIDGE`/`TUN` as modules, `DRM_VC4`/`DRM_V3D` as modules, the V4L2
M2M codec (`/dev/video11` H.264 encoder, `/dev/video31` JPEG encoder), `QMI_WWAN`, `CDC_MBIM`, `CDC_NCM`,
`CDC_ETHER`, `RNDIS_HOST`, `USB_SERIAL_OPTION`, overlayfs, squashfs, erofs, f2fs.

There is **no ashmem**; Waydroid detects that and sets `sys.use_memfd=true`. No custom kernel is
needed for Phase 1. Phase 2 rebuilds the same tree with the modules we use built in (saves module
loading and udev), LZ4 compression, and without the USB/PCIe drivers we don't own.

Upstream kernel patches we do **not** need: the `virtual_touchscreen` char driver (touch goes in via
Wayland, see 3.3), and the `cdc_*` `FLAG_ETHER` renaming patches (NetworkManager doesn't care).

### 2.4 Networking: identical to upstream on purpose

The Tesla browser constraints, learned from upstream's code and release notes:

* It needs a **real HTTPS certificate on a real hostname** (MSE, WebCodecs, geolocation and Permissions
  API are secure-context only; a wrong clock breaks TLS, hence an RTC).
* Upstream resolves `device.teslaandroid.com` to a public IP (`104.248.101.213`) via the hotspot's
  dnsmasq and DNATs that IP to the Pi (`172.16.0.1`). Old builds used RFC-1918 IPs and were blocked.
* Fullscreen is obtained by spoofing `youtu.be` to a redirect page (Tesla opens YouTube in theater mode).
* "Offline mode" answers Tesla's connectivity probes (`connman.vn.tesla.services`) with
  `X-ConnMan-Status: online` so the car keeps the Wi-Fi even without LTE.

We reproduce this 1:1 on Linux with hostapd + dnsmasq (`--address` overrides) + nftables DNAT + nginx.
You must bring **your own hostname and certificate** (the upstream certificate is a private Jenkins
secret). `host/phase1-pios/env` holds the hostname, the public IP to hijack and the cert paths.

### 2.5 LTE modem

Upstream has no RIL; modems are USB-Ethernet. Same here:

* **Preferred: HiLink / ECM-mode sticks** (Huawei E3372h HiLink, ZTE MF833, Alcatel IK41 in `AT+USBMODE=1`).
  They enumerate as `cdc_ether`/`cdc_ncm`, DHCP gives an address, no ModemManager at all. Zero boot cost.
* **Supported: QMI/MBIM modems** (Quectel EC25/EM05, Sierra, Telit) via ModemManager + NetworkManager.
  ModemManager is started *after* the hotspot and web server, off the critical path.
* `usb_modeswitch` rules from upstream are carried over for sticks that enumerate as mass storage.

### 2.6 GPS

Browser `navigator.geolocation` → `/sockets/gps` JSON → host daemon → Android.

* ATL path (current): the Tesla browser geolocation reaches `services/gps/gps.py` (WebSocket 9998) and is re-emitted as a line feed on 9997; the ATL `android.location.LocationManager` reads it directly (`ATL_LOCATION_SOURCE=tesla:9997`), so no mock-location app is needed. The Waydroid-era plan below is kept for reference.
* Phase 1: feed Waydroid through **mock location** (a tiny bundled app with `ACCESS_MOCK_LOCATION`,
  `appops set ... android:mock_location allow`, listening on a TCP port the container reaches via
  `waydroid0`). Works with Waze.
* Phase 3: port upstream's property-driven `android.hardware.gnss@2.0-service.tesla-android` into the
  custom image. Identical browser JSON contract either way.

## 3. Data path

### 3.1 Display: `tacomp` (services/display)

Waydroid's hwcomposer is a Wayland client that needs only: `wl_compositor`, `wl_shm`, `wl_seat`,
`wl_output`, `xdg_wm_base`, `wl_subcompositor`, `wp_viewporter`, `wp_presentation`,
`wl_data_device_manager`, optionally `zwp_linux_dmabuf_v1` (verified in
`upstream/android_hardware_waydroid/hwcomposer`). It submits the whole screen as one surface in
non-multi-window mode.

`tacomp` is a small **headless wlroots compositor** that *is* that Wayland server. Because we own the
compositor, every Android frame lands in our hands with zero copies:

```
Android SurfaceFlinger -> hwcomposer.waydroid -> wl_surface.commit (dmabuf from Mesa v3d)
  -> tacomp: on commit, take the dmabuf fd
  -> V4L2 M2M /dev/video11 (H.264 CBP) or /dev/video31 (JPEG)
  -> one binary WebSocket message per frame on :9091 (/sockets/display)
```

* The V4L2 M2M encoder code is upstream's `encode/m2m.c` (from PiKVM ustreamer) and is vendored as-is:
  it already takes DMABUF input, forces IDR on client connect, repeats SPS/PPS, 2-18 Mbit/s.
* Frame pacing is event-driven (nothing sent while the screen is static), newest-frame-wins.
* Resolution is fixed at image build time (default **1088x832@30, density 200**, upstream's default
  for the Model 3/Y screen) and set in Android via `persist.waydroid.width/height`. Resizing from the
  browser (`POST /api/displayState`) restarts tacomp with a new output size; same contract as upstream.
* No screencopy, no PipeWire video, no second compositor.

### 3.2 Codec choice

Upstream's default renderer is **MJPEG into an `<img>`** (renderer 0). H.264 via WebCodecs (renderer 1)
is lighter on Wi-Fi but depends on the MCU generation. tacomp supports both through the same `m2m.c`;
the frontend chooses. Keep MJPEG as the default for the first in-car tests.

### 3.3 Touch

Browser Pointer Events → `/sockets/touchscreen` text protocol (upstream format, unchanged) →
`tacomp` → `wl_touch` down/motion/up events on the Android surface. Waydroid's hwcomposer already
turns `wl_touch` into `ABS_MT_*` evdev events for Android's InputFlinger (`wayland-hwc.cpp`,
`touch_handle_*`). No uinput, no kernel driver.

### 3.4 Audio

Waydroid's audio HAL is a PulseAudio client. The host runs **PipeWire + pipewire-pulse** with a null
sink `android`; `services/audio` reads `android.monitor` and encodes **FLAC in fragmented MP4** (upstream
contract: `movflags empty_moov+separate_moof+default_base_moof+frag_discont`, ~40 ms fragments, init
segment re-sent on connect) to `/sockets/audio` on :8080. Phase 1 implements this as an `ffmpeg`
wrapper; later as a libavformat daemon.

### 3.5 Config API

`services/api` re-implements upstream's `/api/*` (health, displayState, gpsState, browserAudioState,
softAp*, offlineMode*) on :8081 so `web/` (the forked frontend) runs unchanged.

## 4. Boot-time budget (cold boot, Pi 4B)

Honest numbers. The 5-second target is reachable for "car sees a page", not for "Waze on screen".

| Stage | Phase 1 (Pi OS) | Phase 2 (Buildroot) | Floor / why |
|---|---|---|---|
| EEPROM + `start4.elf` firmware | 1.6 s | 1.5 s | Closed firmware, not tunable. `boot_delay=0`, `disable_splash=1`. `start4cd.elf` is smaller but drops the VPU codec we need. |
| Kernel to rootfs mounted | 2.5 s | 1.0 s | Phase 2: no initramfs, LZ4, built-in drivers, `quiet`, no USB enumeration wait. |
| Userspace to AP beaconing | 4.0 s | 1.5 s | `brcmfmac` firmware load (~0.6 s) is the floor; hostapd ~0.3 s. Phase 2: static IP, no udev, no NM for `wlan0`. |
| nginx + launcher page | +0.2 s | +0.1 s | |
| **Car can load the page** | **~8 s** | **~4-5 s** | Plus the car's own Wi-Fi association time (2-6 s, outside our control). |
| Waydroid container to `sys.boot_completed` | 20-30 s | 15-20 s | zygote preload + system_server on 4x A72 at 1.5-1.8 GHz. Pre-baked `/data`, package trim, no bootanimation. |
| Waze cold start | +5-8 s | +4-6 s | |
| **Waze on screen** | **~35 s** | **~22-25 s** | Phase 3 custom image (Lepton-style service removal): **~15 s**. |

The big lever is therefore **not booting**: section 5.

## 5. Warm standby (the real 5-second experience)

The Pi 4 has no suspend-to-RAM and the Android container cannot be CRIU-snapshotted (binder fds). So:

1. Power the Pi from an **always-on 12 V** (fuse tap) through a buck converter, or from a UPS HAT with
   a few hours of hold, instead of the car's USB port.
2. Sense the car's state on a GPIO (the center-console USB port's 5 V goes away when the car sleeps).
3. On sleep: `lxc-freeze` the container (Waydroid `suspend_action=freeze`), stop tacomp's encoder,
   `ip link set wlan0 down`, cpufreq to powersave. Idle draw is about 1.5 W.
4. On wake: `wlan0 up` + hostapd (~1 s), `lxc-unfreeze`, encoder on. The car's browser reconnects its
   WebSocket (`ReconnectingWebSocket`, 150 ms-1 s) and the picture is back in **~2-3 s**.
5. Safety: hard power-off after N hours asleep or when the 12 V rail drops below 12.2 V, so a parked car
   never drains its LV battery.

Phase 1 ships the freeze/unfreeze hooks as a systemd unit driven by a GPIO; the hardware side is a
3-wire harness (12 V, GND, USB-5V sense).

## 6. What we keep from upstream, verbatim

* `frontend/` → `web/` (renderers, touch, audio, GPS JS; only the hostname changes).
* `encode/m2m.c` + `encode/m2m.h` from `android-external-tesla-android-virtual-display` (V4L2 M2M).
* `services/usb_modeswitch/*.conf` and the modem quirk list from `usb-networking-initialiser`.
* The lighttpd proxy map, headers and DNS/DNAT scheme, re-expressed for nginx + dnsmasq + nftables.
* Display defaults (1088x832@30, density 200), the touch text protocol, the GPS JSON, the audio fMP4
  framing, so the browser side needs no re-testing in the car.

## 7. Open items

* Pick a hostname + certificate strategy (Let's Encrypt DNS-01 over the LTE link, renew weekly).
* Confirm Mesa v3d works inside the aarch64 Waydroid image on trixie (community reports yes; needs a
  real test on the Pi with `MESA_GL_VERSION_OVERRIDE` fallback ready).
* Verify the Tesla browser's current WebCodecs H.264 state on your MCU; otherwise MJPEG.
* GApps + Play certification: register the GSF ID once (upstream uses `ih8sn` prop spoofing).
