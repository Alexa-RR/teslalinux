# Architecture

## 1. What upstream does, and what this fork does instead

Upstream Tesla Android (2026.x) is **AOSP 14 (GloDroid) on bare metal**: Pi firmware → ARM-TF → U-Boot →
Android boot image → full Android init. Every user-facing service (the hotspot, the web server, the
display streamer) is an Android init service that starts `on property:sys.boot_completed=1`. So the car
cannot even see a web page until Android has fully booted. Upstream publishes no boot number; the
release notes talk about shaving "~10 s" and first boot taking "5-15 minutes".

This fork puts **Linux underneath** and runs no Android system at all. Everything the car needs to
*see something* (Wi-Fi AP, DHCP/DNS, TLS web server, launcher page) is an ordinary Linux service. The
app (Waze) runs as one Linux process under the **Android Translation Layer** (ATL): Android framework
APIs reimplemented on GTK 4, a standalone ART runtime and bionic_translation. There is no Android
image, no container, no Google Play services. The browser side of upstream's protocol is kept as it
is, so its frontend runs unchanged (`docs/UPSTREAM-NOTES.md`).

```
Tesla browser ──Wi-Fi──> hostapd / dnsmasq / nftables ──> nginx (TLS, car.alexahub.dev)
   │  /                    launcher page (web/)
   │  /sockets/display     <── tacomp (9091) <── Wayland ── ATL: Waze (GTK 4, ART)
   │  /sockets/touchscreen ──> tacomp (9999) ──> wl_touch ──┘
   │  /sockets/keyboard    <─> tacomp (9996) <─> text-input-v3
   │  /sockets/gps         ──> gps.py (9998) ──> line feed 9997 ──> ATL LocationManager
   │  /sockets/audio       <── audio.py (8080) <── PipeWire null sink <── ALSA <── ATL AudioTrack
   └  /api/*               <─> api.py (8081)
```

## 2. The app runtime: ATL

* **Our fork of ATL**, [Alexa-RR/android_translation_layer](https://github.com/Alexa-RR/android_translation_layer),
  pinned in `tools/debvm/versions.env` (`ATL_REPO`, `ATL_COMMIT`). It carries the framework classes,
  natives and fixes Waze needs (many of them AOSP ports); `tools/atl-vm/patches/` is the patch set it
  was built from.
* **Built for Debian trixie arm64** by `tools/debvm/build-dist.sh` (bionic_translation, art_standalone,
  OpenSLES, ATL, and tacomp with its private wlroots 0.20 stack) into `/opt/teslalinux`. The Alpine
  image uses Alpine's packages, patched and rebuilt (`/opt/atl`).
* **Started by** `host/phase1-pios/teslalinux-atl` (`teslalinux-atl.service`, OpenRC
  `host/alpine/init.d/teslalinux-atl`) as the unprivileged `tesla` user on tacomp's Wayland socket,
  fullscreen at the display size and density of the frontend's display state, with the location
  source `tesla:127.0.0.1:9997`. GTK renders with `GSK_RENDERER=ngl` on the Pi's V3D (Mesa). The
  unit restarts the app whenever it exits; `teslalinux-restart-app` (Settings, "Restart Waze")
  restarts only the app, the stream and the page stay up.
* **Apps are installed as their original signed split APKs** (`tools/apps/install-app.sh`, into
  `/var/lib/teslalinux/apps/<package>`), never merged or re-signed: Google's APIs check the APK
  Signing Block. Signer and versionCode are checked against the installed app; the previous version
  is kept for rollback.
* **Ahead-of-time compilation** (`host/teslalinux-dexopt`) is opt-in (`TESLALINUX_DEXOPT=1`): measured
  on the VM it costs the main thread 20-25% more CPU than ART's JIT, and only the first start after an
  install is faster (2 s to the map instead of 21 s while ART compiles its cache copy).

## 3. Host

### 3.1 Targets

* **Primary: Raspberry Pi OS Lite 64-bit (Debian 13 trixie)**, systemd, NetworkManager. Installed by
  `tools/pios/install.sh` from a release (section 7). `host/phase1-pios/provision.sh` is the older
  from-source provisioning of the same host setup.
* **Secondary: the Alpine image** (`tools/image/build-alpine-pi.sh`, OpenRC, `host/alpine/`,
  `docs/ALPINE-IMAGE.md`).

Both render their host configuration (hostapd, dnsmasq, nftables, nginx, the display state) from the
same templates (`host/phase1-pios/etc/`) and the same settings file (`/etc/teslalinux/env`) with
`host/teslalinux-render`, so a fix lands in both.

### 3.2 Kernel

The stock Raspberry Pi kernel (`bcm2711_defconfig`, rpi-6.12.y) has everything the stack uses:
`DRM_VC4`/`DRM_V3D`, the V4L2 M2M codec (`/dev/video11` H.264 encoder, `/dev/video31` JPEG encoder),
`QMI_WWAN`, `CDC_MBIM`, `CDC_NCM`, `CDC_ETHER`, `RNDIS_HOST`, `USB_SERIAL_OPTION`. No custom kernel
is needed. Upstream kernel patches we do **not** need: the `virtual_touchscreen` char driver (touch
goes in via Wayland, see 4.3), and the `cdc_*` `FLAG_ETHER` renaming patches (NetworkManager doesn't
care). Firmware and command line: `host/rpi4/`.

### 3.3 Networking: identical to upstream on purpose

The Tesla browser constraints, learned from upstream's code and release notes:

* It needs a **real HTTPS certificate on a real hostname** (MSE, WebCodecs, geolocation and Permissions
  API are secure-context only; a wrong clock breaks TLS, hence an RTC).
* Upstream resolves `device.teslaandroid.com` to a public IP (`104.248.101.213`) via the hotspot's
  dnsmasq and DNATs that IP to the Pi (`172.16.0.1`). Old builds used RFC-1918 IPs and were blocked.
* Fullscreen is obtained by spoofing `youtu.be` to a redirect page (Tesla opens YouTube in theater mode).
* "Offline mode" answers Tesla's connectivity probes (`connman.vn.tesla.services`) with
  `X-ConnMan-Status: online` so the car keeps the Wi-Fi even without LTE.

We reproduce this 1:1 with hostapd + dnsmasq (`address=` overrides) + nftables DNAT/SNAT + nginx.
Our hostname is `car.alexahub.dev`, resolved on the hotspot to `192.0.2.1` (TEST-NET-1, never
routed) and DNATed to the Pi. `host/phase1-pios/env` holds the hostname, the hijack IP and the
certificate settings. The parity table, item by item: `docs/CAR-STACK-READINESS.md`, section 2.

### 3.4 Uplinks: LTE and others

Upstream has no RIL; modems are USB-Ethernet. Same here:

* **Preferred: HiLink / ECM-mode sticks** (Huawei E3372h HiLink, ZTE MF833, Alcatel IK41 in `AT+USBMODE=1`).
  They enumerate as `cdc_ether`/`cdc_ncm` and DHCP gives an address.
* **Supported: QMI/MBIM modems** (Quectel EC25/EM05, Sierra, Telit) via ModemManager + NetworkManager.
  ModemManager is started *after* the hotspot and web server, off the critical path.
* `usb_modeswitch` rules from upstream are carried over for sticks that enumerate as mass storage.
* `host/teslalinux-uplink` checks every uplink (LTE, a second Wi-Fi card, Ethernet, a phone on USB)
  through that interface alone and routes through the best one that reaches the internet; the car's
  NAT follows the route.
* **Stuck-stick recovery** (same daemon): a Huawei E3372 that lost the signal (a garage, a tunnel)
  often stays offline after the signal returns until it is re-plugged. When LTE has no internet for
  90 s while the stick reports coverage, it climbs a ladder, one step per 2 minutes at most:
  reconnect (HiLink mobile data off/on through the stick's web API; ModemManager disconnect and
  reconnect), modem restart (HiLink `/api/device/control`; `mmcli --reset`), USB re-enumeration
  (sysfs `authorized`; straight away when the web API is dead or wants a login), USB power cycle
  with `uhubctl` (a Pi 4 switches all four ports at once, so `LTE_RECOVERY_POWER_CYCLE=auto` only
  does it when the stick is the only USB device). Then 15 minutes of rest and the ladder again;
  working LTE resets it. No coverage, a SIM problem, or `prefer wifi` with a working Wi-Fi: nothing
  is done. `LTE_RECOVERY=off` in `/etc/teslalinux/env` turns it off. The stick's state (signal bars,
  network type, operator, data used, SIM, the last step) is the `modem` object of
  `/run/teslalinux/uplink.json`, shown on the car page's Status.

## 4. Data path

### 4.1 Display: `tacomp` (services/display)

`tacomp` is a small **headless wlroots compositor**, and the app's only Wayland server. ATL's GTK
window is its fullscreen `xdg_toplevel`. On its own frame clock (the output refresh, 60 Hz by
default) it composites the scene into
the output buffer and hands it to an encoder thread:

```
ATL (GTK 4, GSK GL on V3D) -> wl_surface.commit
  -> tacomp: scene -> output buffer (GLES2 on the Pi)
  -> V4L2 M2M /dev/video11 (H.264) or /dev/video31 (JPEG), dmabuf in; CPU JPEG / x264 where missing
  -> one binary WebSocket message per frame on :9091 (/sockets/display)
```

* The V4L2 M2M encoder code is upstream's `encode/m2m.c` (from PiKVM ustreamer), vendored as-is:
  DMABUF input, forced IDR on client connect, repeated SPS/PPS.
* Frames without damage are not encoded; newest-frame-wins when the encoder is busy.
* Size, encoder and quality come from the frontend's display state (`POST /api/displayState` →
  `teslalinux-apply-display` → `/etc/teslalinux/display.env`). tacomp applies a new size, encoder or
  quality in place (SIGHUP); the app gets the new window size as a configuration change, as upstream
  resizes its virtual display under a running app. Only a new density restarts the app.
* Details: `services/display/README.md`.

### 4.2 Codec choice

Upstream's default renderer is **MJPEG into an `<img>`** (renderer 0). H.264 via WebCodecs (renderer 1)
is lighter on Wi-Fi but depends on the MCU generation. tacomp supports both through the same `m2m.c`;
the frontend chooses and the API maps the renderer to tacomp's encoder. MJPEG (`m2m-jpeg`) is the
default.

### 4.3 Touch and keyboard

Browser Pointer Events → `/sockets/touchscreen` text protocol (upstream format, unchanged) →
`tacomp` → `wl_touch` down/motion/up on the app's surface → GTK → ATL's `MotionEvent`s. No uinput,
no kernel driver. The device keyboard (`/sockets/keyboard`, ours) bridges Wayland text-input-v3 to
the car page; Settings > Keyboard switches between it and the app's on-screen keyboard.

### 4.4 GPS

Browser `navigator.geolocation` → `/sockets/gps` JSON (upstream format) → `services/gps/gps.py`
(WebSocket 9998) → one line per fix on `127.0.0.1:9997` → ATL's `android.location.LocationManager`
(`ATL_LOCATION_SOURCE=tesla:127.0.0.1:9997`), which hands the fixes to the app as GPS locations.

### 4.5 Audio

ATL's `AudioTrack` plays through ALSA's default device, which `/etc/asound.conf` points at PipeWire.
PipeWire, WirePlumber and pipewire-pulse run as system units for the `tesla` user with a single null
sink `android` (no hardware sinks). `services/audio` reads `android.monitor` and encodes **FLAC in
fragmented MP4** (upstream contract: `movflags empty_moov+separate_moof+default_base_moof+frag_discont`,
~40 ms fragments, init segment re-sent on connect) to `/sockets/audio` on :8080, running ffmpeg only
while someone listens.

### 4.6 Config API

`services/api` re-implements upstream's `/api/*` (configuration, health, displayState, deviceInfo,
softAp*, offlineMode*, …) on :8081 so `web/` runs unchanged, plus our own routes (certificate status,
app state and restart, app updates, locale, keyboard, network, status, system update). Privileged changes go through one sudoers rule
per helper (`teslalinux-apply-display`, `teslalinux-restart-app`, …).

## 5. Certificate

Every car serves `car.alexahub.dev` with one shared, publicly trusted certificate (two slots, current
and standby), renewed by `.github/workflows/tls-certs.yml` and fetched by each Pi with
`teslalinux-tls` (a DNS version pointer first, downloads only on change, every pair checked before
nginx serves it; a release carries the pairs of its build day, so a new install works offline).
Design, threat model and setup: `docs/CAR-STACK-READINESS.md`, section 4.

## 6. App updates

The Pi keeps Waze current by itself (`tools/appupdate/`): a daily check (APKPure's public API by
default, manual uploads from the car page always possible), downloads only on unmetered uplinks, the
same signature verification as `install-app.sh`, installation only while the car is not connected or
at boot, a health check of the new version and automatic rollback. `docs/APP-UPDATES.md`.

## 7. Install and releases

Pushing a `v*` tag runs `.github/workflows/release.yml`: on an arm64 runner inside `debian:trixie`,
`tools/debvm/build-dist.sh` builds the ATL stack, tacomp and tlplay, and `tools/debvm/pack-dist.sh`
packs them with the configs, scripts, frontend and the current certificate pairs into
`teslalinux-dist-<ver>-arm64.tar.zst` (with `MANIFEST`, `SHA256SUMS` and `install.sh`). The Pi never
compiles: `tools/pios/install.sh` (the one-liner in `README.md`) downloads and verifies the dist,
installs the runtime packages, units and configs, optionally fetches Waze, and keeps backups for
`teslalinux rollback` and `teslalinux uninstall`. It is idempotent: each step is redone only when its
inputs changed. The same Debian build runs locally in the build VM (`tools/debvm/README.md`).

## 8. Boot and start time

What the car needs first (hotspot, DNS, nginx, the launcher page) does not depend on the app:

* hostapd, dnsmasq and nginx have no dependency on the app, tacomp, ModemManager or the network
  being online; `NetworkManager-wait-online`, udev settle and the other units the car does not need
  are disabled (`--no-trim` keeps them), journald is volatile, and the CPU runs the performance
  governor.
* ModemManager is ordered after hostapd and nginx; the certificate fetch is never ordered before
  nginx (the installed pair is served at once).
* tacomp starts without waiting for the network; the page shows the stream as soon as the app draws
  its first frame. An app restart (crash, density change, update) leaves tacomp and the page
  connected; the next frames come from the new process.
* ART builds its boot image on the app's first start; `install.sh` builds it at install time unless
  `--no-prewarm` is given, so the first start in the car does not pay for it.

No boot time has been measured on Pi hardware yet: `systemd-analyze` and
`systemd-analyze critical-chain hostapd.service` on the Pi, and `tools/debian/app-starttime.sh`
(seconds from an app restart to the first frame and to Waze's "map ready") are the measurements to
take there. Times measured in the VMs are software rendering (llvmpipe), not the Pi.

## 9. What we keep from upstream, verbatim

* `frontend/` → `web/` (renderers, touch, audio, GPS JS; endpoints derived from the page's host,
  changes listed in `web/README.teslalinux.md`).
* `encode/m2m.c` + `encode/m2m.h` from `android-external-tesla-android-virtual-display` (V4L2 M2M).
* `services/usb_modeswitch/*.conf` and the modem quirk list from `usb-networking-initialiser`.
* The lighttpd proxy map, headers and DNS/DNAT scheme, re-expressed for nginx + dnsmasq + nftables.
* Display defaults (1088x832, density 200), the touch text protocol, the GPS JSON, the audio fMP4
  framing, so the browser side needs no re-testing in the car.

## 10. Open items

* First boot on real Pi hardware: GTK's GL renderer on V3D, the V4L2 M2M encoders, LTE, the RTC,
  the GPS/audio/API daemons (`docs/CAR-STACK-READINESS.md`, sections 7 and 8).
* Verify the Tesla browser's current WebCodecs H.264 state on your MCU; otherwise MJPEG.
* ATL: the remaining framework gaps (`tools/atl-vm/patches/atl-waze/TODO-real.md`).
