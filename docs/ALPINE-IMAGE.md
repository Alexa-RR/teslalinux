# The Alpine car image

**Secondary target.** The primary target is stock Raspberry Pi OS Lite 64-bit with everything
installed natively (`host/phase1-pios/provision.sh`); this image shares its templates, services and
renderer. See `docs/CAR-STACK-READINESS.md`.

One SD image for the Raspberry Pi (aarch64) with everything on it: the hotspot and the hijack DNS,
nginx with the car frontend, the display compositor that streams frames to the Tesla browser and
takes touch back, the GPS/audio/config daemons, and Waze under the patched Android Translation
Layer (ATL). Alpine because the ATL stack (`android-translation-layer`, `art_standalone`,
`bionic_translation`, `libandroidfw`) exists only as Alpine packages, and the whole stack was proven
on the Alpine test VM (`tools/atl-vm`); the image is that VM's userland plus the Pi boot files and
the car services. Pi OS stays as the documented Phase-1 host for the Waydroid variant.

## Pieces

| Piece | Where | State |
|---|---|---|
| Hotspot, hijack DNS, DNAT | hostapd, dnsmasq, nftables rendered by `host/teslalinux-render` (templates in `host/phase1-pios/etc`), re-rendered at boot by `teslalinux-netconfig` when settings changed | written, needs hardware |
| Web | nginx (upstream lighttpd parity) + `web/` + TLS | runs locally: `host/local/web-local.sh start`, `check` passes |
| Display to the car | `services/display` tacomp: wlroots 0.20 headless compositor, frames to `/sockets/display`, touch from `/sockets/touchscreen` | being written (first: CPU JPEG; then V4L2 M2M H.264 on the Pi) |
| GPS / audio / config | `services/gps/gps.py`, `services/audio/audio.py`, `services/api/api.py` | written, untested on hardware |
| App runtime | `/opt/atl`: the patched ATL prefix built on the VM (`tools/atl-vm/build-atl.sh`, `hostbuild.sh`, `patches/bionic_translation-waze.sh`) | works on the VM |
| App | Waze split APKs via `tools/apps/install-app.sh` into `/var/lib/teslalinux/apps/com.waze` | works on the VM |
| Launcher | `host/phase1-pios/teslalinux-atl` (OpenRC service in `host/alpine`) | written |
| Time | chrony + the DS3231 RTC (`hwclock` boot service; `teslalinux-tls` defers new chains while the clock is before the image build time) | in the image, RTC needs hardware |

## Certificate and hostname

The hostname is `car.alexahub.dev` (`DEVICE_HOST` in `host/phase1-pios/env`). At the registrar
(Cloudflare): A records for `car.alexahub.dev`, `www.`, `fullscreen.` and `www.fullscreen.`
pointing at the hijack IP `192.0.2.1`, DNS-only (grey cloud: a proxied record would send the car to
Cloudflare's edge).

The certificate is split so that no secret ever sits on the Pi or in the image:
* the Pi generates its ECDSA P-256 key at first boot and keeps it in `/etc/teslalinux/tls`
  (root, 0700/0600); `teslalinux-tls export-csr` (or `GET /api/tls/csr`) gives the CSR, which is
  committed once as `host/tls/device.csr.pem`;
* a GitHub Actions workflow in this private repo signs that CSR through Let's Encrypt (DNS-01, the
  Cloudflare token lives only in the workflow's environment secrets) and publishes the public
  chain;
* `teslalinux-tls` on the Pi fetches the chain, accepts it only if it belongs to our key, is
  trusted, has exactly the four names and is newer, then swaps it in and reloads nginx. Until then
  nginx serves a self-signed certificate for the same key (the browser warns).

Status: both sides are written: the Pi side (`host/tls/teslalinux-tls`, 36 tests in
`host/tls/test/run.sh`) and the cloud renewer (`.github/workflows/tls-renew.yml`,
`host/tls/renew.sh`). Setup steps and threat model: `docs/CAR-STACK-READINESS.md`, "Certificate". A wrong clock (no RTC, no NTP yet) only delays
accepting a new chain; it never breaks the one in use.

### Why not upstream's name

The car browser needs a real certificate on a real hostname (secure-context APIs). Upstream ships
`device.teslaandroid.com` with a certificate that is a private Jenkins secret baked into their
release image; it is not in any source tree, and a certificate for someone else's domain cannot be
obtained or renewed by us. So: our own hostname with a public A record pointing at the hijack IP,
a Let's Encrypt certificate via DNS-01 (renewed over the LTE link), configured in
`/etc/teslalinux/env` (`DEVICE_HOST`, `TLS_CERT`, `TLS_KEY`). Everything else of the upstream scheme
(hijack IP + DNAT, `youtu.be` fullscreen trick, connman spoof) is reused unchanged.

## Build

`tools/image/build-alpine-pi.sh` runs on an Alpine aarch64 host (the test VM is one):

1. `apk --root rootfs --initdb add` the base (`alpine-base`, `linux-rpi`, `raspberrypi-bootloader`,
   OpenRC) and the runtime set (the list in `tools/image/packages.txt`: mesa with v3d, gtk4,
   pipewire, seatd, fonts, python3 + websockets, nginx, hostapd, dnsmasq, nftables, chrony,
   usb-modeswitch, the ATL stack).
2. Copy in: `/opt/atl` (a tarball of the VM's `~/atl-prefix` with the host-built api-impl.jar and
   the patched bionic libs), the apps directory, `web/` to `/var/www/teslalinux`, the service
   scripts, the OpenRC init scripts from `host/alpine/init.d`, `/etc/teslalinux/env`.
3. Enable services for the default runlevel; write `/boot` (config.txt, cmdline.txt from `host/rpi4`).
4. Assemble the image: a FAT boot partition + an ext4 root, `sfdisk` + loop mounts; first boot
   expands the root partition and generates a self-signed placeholder certificate if none.

The dalvik cache for the boot image and api-impl is warmed at bake time (`dex2oat` runs on first
start otherwise, tens of seconds on the Pi).

## 60 fps

The target is 60 frames per second end to end, and the pipeline is set up for it: tacomp's output
runs at 60 Hz (`TACOMP_SIZE=1088x832@60`, which also paces ATL's Choreographer through the GTK
frame clock), the display stream is pushed per rendered frame, and the frontend's WebCodecs
renderer (renderer 1) decodes H.264 in hardware in the car. What decides the real number:
* Pi 4: the V4L2 H.264 encoder (`/dev/video11`) is rated about 1080p30; 1088x832@60 is near its
  edge and must be measured (`TACOMP_ENCODER=m2m-h264`, the frame rate printed by
  `services/display/test/ws-client.py`). The JPEG encoder (`/dev/video31`) is an alternative for
  renderer 0 at a higher bitrate.
* Pi 5: no hardware video encoder; H.264 is software (x264 ultrafast on four cores manages
  1088x832@60 with margin, to be added as `TACOMP_ENCODER=x264`), the GPU side is stronger.
* The car: the MCU's browser must decode 60 frames/s; MCU3 does for H.264, MCU2 is to be tested.
Below the target the stream simply follows the rendered rate: nothing waits.

Measured on the test VM (software GL, pixman renderer, CPU JPEG, 1280x720): tacomp at 60 Hz
delivered 52.7 frames/s to the WebSocket client with every frame event rendered and published,
while the app itself rendered at 35 Hz under llvmpipe. The compositor path is not the limit.
