# Upstream protocol contract (distilled from tesla-android 2026.22)

Everything here was read from the upstream sources in `upstream/`. Keep these contracts and the forked
frontend in `web/` works unchanged.

## Ports and routes (upstream lighttpd → our nginx)

| Browser path | Backend | Daemon |
|---|---|---|
| `/` static | nginx | launcher + app |
| `/api/*` | 127.0.0.1:8081 | config API (cpp-httplib upstream) |
| `/sockets/display` | 127.0.0.1:9091 | display WebSocket, binary frames |
| `/sockets/touchscreen` | 127.0.0.1:9999 | touch WebSocket, text frames |
| `/sockets/gps` | 127.0.0.1:9998 | GPS WebSocket, JSON text |
| `/sockets/audio` | 127.0.0.1:8080 | audio WebSocket, binary fMP4 |
| `/stream` | 9090 | dead upstream endpoint, drop |

Headers upstream sends: `Cross-Origin-Embedder-Policy: credentialless`,
`Cross-Origin-Opener-Policy: same-origin` (SharedArrayBuffer/wasm for Broadway), `Cache-Control: no-store`
on everything except `/api`. TLS cipher list `AES128-SHA`. Hosts `connman.vn.tesla.services` and
`connman.vn.cloud.tesla.cn` get `X-ConnMan-Status: online`. Host `youtu.be` → redirect page (fullscreen).

## Display (`/sockets/display`, port 9091)

* One binary WebSocket message = one frame.
  * renderer 0 (default): a complete JPEG. Frontend does `URL.createObjectURL(blob)` → `<img>`.
  * renderer 1: H.264 Annex-B access unit, Constrained Baseline, SPS/PPS repeated on every IDR,
    GOP 30, QP 16-32. Frontend uses WebCodecs `VideoDecoder` (`prefer-hardware`, `optimizeForLatency`).
  * renderer 2: same bytes, Broadway wasm decoder.
* On client connect: push one full frame immediately and force an IDR.
* Bitrate (H.264): `2000 + 16000*(quality-1)/99` kbit/s, quality default 70.
* Ping every 1 s. Frontend reconnects with 150 ms–1 s backoff, 900 ms timeout.
* Defaults: 1088×832 @30, density 200. Responsive mode computes `max 1920×1088`, short side preset
  832/720/640/544/480, width aligned to 64, height to 32, then `POST /api/displayState`.

## Touch (`/sockets/touchscreen`, port 9999)

Text frames, one per pointer event, lines of `<letter><int>\n`:

```
s <slot>      select MT slot (0-9, assigned randomly by the frontend)
T <id>        ABS_MT_TRACKING_ID (-1 = lift)
a <0|1>       finger state
X <x>  Y <y>  ABS_MT_POSITION_X/Y in Android display pixels
e <0|1>       pointer emulation
S 0           SYN_REPORT
```

Down: `s N\nT ID\na 1\nX x\nY y\ne 0\nS 0\n`. Move: `s N\nX x\nY y\nS 0\n`. Up: `s N\nT -1\na 0\ne 0\nS 0\n`.
On socket open and on `pointerleave` the frontend lifts all slots. tacomp maps these to `wl_touch`.

## GPS (`/sockets/gps`, port 9998)

JSON text every 1 s (frontend polls `getCurrentPosition`), **all values are strings**:

```json
{"latitude":"32.08","longitude":"34.78","speed":"12.3","bearing":"270","vertical_accuracy":"8","timestamp":"1759900000000"}
```

`speed`/`bearing` may be missing → upstream stores `not-available`. Altitude is always 0 upstream.
GPS is **off by default** upstream because granting geolocation can crash the MCU2 browser.

## Audio (`/sockets/audio`, port 8080)

Binary fMP4, `audio/mp4; codecs="flac"`, 48 kHz stereo, FLAC compression level 0,
`movflags empty_moov+separate_moof+default_base_moof+frag_discont`, ~40 ms fragments.
Server caches the init segment (`ftyp`+`moov`) and re-sends it to every new client before the next
`moof`+`mdat`. Frontend: MSE `SourceBuffer` in `sequence` mode, keeps a 0.2 s tail, jumps to live edge
if >1 s buffered, playback must be primed from a user gesture.

## Config API (`/api`, port 8081)

GET `configuration`, `displayState`, `deviceInfo`, `health` (polled every 30 s online / 5 s offline;
`location.reload()` on recovery), `openUpdater`.
POST `displayState` `{width,height,density,resolutionPreset,renderer,isResponsive,isH264,quality,
refreshRate,isRearDisplayEnabled,isRearDisplayPrioritised}`, `gpsState`, `browserAudioState`,
`browserAudioVolume`, `softAp*`, `offlineMode*`, `overrideReleaseType`, `overrideOtaUrl`.

## Network scheme

* AP SSID `Tesla-Android`, WPA2 `changeit`, 5 GHz ch 44 / 80 MHz (upstream default); car ticks
  "Remain connected in Drive".
* Tether subnet `172.16.0.0/24`, Pi = `172.16.0.1`.
* dnsmasq `--address=/device.teslaandroid.com/104.248.101.213` (+ `www.`, `fullscreen.`), nftables DNAT
  `104.248.101.213 → 172.16.0.1` on `wlan0`, SNAT back. Public DNS also points there; real cert shipped.
* Offline mode additionally spoofs `connman.vn.tesla.services`, `hermes-prd.*` and telemetry hosts.
* `youtu.be → 216.58.215.78`, DNAT `:6969 → 172.16.0.1:80` (fullscreen launcher trick).
* RFC-1918 addresses were blocked by the car browser in older firmware; keep the public-IP DNAT trick.

## LTE modems (supported upstream)

Huawei E3372 (not the `-325` Brovi), ZTE MF833, Alcatel IK41VE1/IK41UC (sends `AT+USBMODE=1` on
`/dev/ttyUSB2`), Android USB tethering (`cdc_ncm`), iPhone tethering (`ipheth` + `usbmuxd`).
`usb_modeswitch` for `1bbb:f000`, `12d1:*`, `12d1:1f1d`, `3566:2001`.

## Boot config upstream ships (keep the useful bits)

`arm_64bit=1 enable_gic=1 dtoverlay=vc4-kms-v3d dtoverlay=cma,cma-512 dtoverlay=rpivid-v4l2
dtoverlay=dwc2,dr_mode=peripheral hdmi_force_hotplug=1 initial_turbo=60 arm_boost=1 start_x=1`
plus `gpio-fan` at 50 °C and `i2c-rtc,ds3231`. Kernel cmdline `coherent_pool=1M pcie_aspm=off`.
