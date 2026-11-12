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
| `/sockets/keyboard` | 127.0.0.1:9996 | teslalinux only: device keyboard (tacomp) |
| `/sockets/carapp` | 127.0.0.1:9995 | teslalinux only: the app's car templates, JSON text (ATL's car-app host) |

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

## Car app templates (`/sockets/carapp`, port 9995; teslalinux only, no upstream counterpart)

Added next to the upstream channels, which are unchanged; only used in Car mode (Settings > Waze
interface: Car, `GET/POST /api/appInterface`, `TESLALINUX_APP_INTERFACE=car` in
`/etc/teslalinux/interface.env`, which makes the launcher set `ATL_CAR_HOST=1`). ATL's car-app host
(`android/atl/carhost` in the ATL fork) binds the app's `CarAppService` as a car head unit's host
would (Car App Library handshake, API level up to 9), gives the app the whole display as its map
surface (the map is then the normal `/sockets/display` stream, touches on it the normal
`/sockets/touchscreen` ones, turned into the library's surface gestures), and serves the templates
here. The page (`web/js/carapp`) draws them over the display. JSON text frames, one message each:

Host to page:
- `{"type":"surface","width":W,"height":H,"dpi":D,"mapDpi":M}`: the map surface (display pixels and
  density) the templates are laid out on, and `mapDpi` the density the app's map is drawn at (the
  display's times the map scale). Sent first, again when the display or the map scale changes.
- `{"type":"icon","id":N,"png":"<base64>"}`: an image the templates refer to by `icon` id (the app's
  drawables rendered to PNG; id 0 is the app's own icon). Sent before the first template using it,
  and to every new page.
- `{"type":"template","seq":N,"template":T}`: the current template (`TemplateWrapper`), as the
  library's model objects: `{"$":"ClassName", property: value, ...}` from their public getters;
  text as strings, colours as `CarColor` (`type`, `color`/`colorDark` signed ARGB), icons as
  `{"$":"IconCompat","icon":N}`, callbacks as `{"$":"OnClickDelegateImpl","$cb":ID}`. Callback ids
  are valid until the next template. A `CarText` with spans is `{"$":"CarText","text":S,"spans":
  [{"start":A,"end":B,"span":{"$":"ForegroundCarColorSpan"|"CarIconSpan"|"DurationSpan"|"DistanceSpan",...}}]}`.
- `{"type":"navigation","active":B}`: whether the app navigates (`NavigationManager.navigationStarted`
  / `navigationEnded`); state, sent to every new page. While it does, the page offers the end-drive
  control and keeps the next step in view as a pill over side panels.
- `{"type":"restrictions","active":B,"noKeyboard":B,"maxItems":N,"maxString":N}`: the car's UX
  restrictions (driving state from the GPS speed, AOSP's default restriction map); state, sent to
  every new page. While they apply the page caps lists at the host's content limits and turns the
  search field off when `noKeyboard`.
- `{"type":"dayNight","night":B}`: the car's night mode as the app hears it (`CarContext.isDarkMode`);
  state, sent to every new page and again when it changes. The page's Graphite look draws its
  panels by day or by night with it (unless the driver fixed day or night on the page).
- `{"type":"mapScale","scale":S,"set":B}`: the map scale, and whether a page has set it since the app
  started; state, sent to every new page and again when it changes. A page with a choice of its own
  sends it while `set` is false, and adopts `scale` once it is true, so all pages agree.
- `{"type":"toast","text":S,"long":B}`: `CarToast` (2 s, or 3.5 s when long).
- `{"type":"alert","alert":A}`: an `Alert` (car API 5), its callbacks valid until the next alert;
  `{"type":"alertDismiss","id":N}`: the app took it back (`AppManager.dismissAlert`).

Page to host:
- `{"type":"click","cb":ID}`: an action, row or item tapped (`OnClickDelegate`).
- `{"type":"selected","cb":ID,"index":I}`: an item of a selectable list chosen (`OnSelectedDelegate`).
- `{"type":"checked","cb":ID,"checked":B}`: a toggle switched (`OnCheckedChangeDelegate`).
- `{"type":"search","cb":ID,"text":T,"submit":B}`: search text typed or submitted (`SearchCallbackDelegate`).
- `{"type":"pan","cb":ID,"on":B}`: pan mode on or off (the map action strip's PAN action;
  `PanModeDelegate`).
- `{"type":"back"}`: back (`AppManager.onBackPressed`).
- `{"type":"visibleArea","left":L,"top":T,"right":R,"bottom":B}`: the part of the map the panels leave
  free, inside their margins (display pixels), passed to the app as its visible and stable areas
  (the app keeps its car, route and speedometer in it).
- `{"type":"stopNavigation"}`: the end-drive control (`NavigationManagerCallback.onStopNavigation`).
- `{"type":"alertCancel","cb":ID,"reason":R}` / `{"type":"alertDismiss","cb":ID}`: an alert timed out
  (R 1) or was closed (R 2), or went away after one of its actions (`AlertCallback`).
- `{"type":"dayNight","mode":"day"|"night"|"auto"}`: the driver's day or night choice; "auto" (the
  default, or `ATL_CAR_DAY_NIGHT`) follows the sun at the car's position. The app hears it as the
  configuration's night mode (`CarContext.isDarkMode`). The page sends it when the driver changes
  it (Settings > Waze interface > Day and night) and, when it is not "auto", on every connection.
- `{"type":"mapScale","scale":S}`: the size of the app's map text and symbols (Settings > Waze
  interface > Map size: 0.85, 1, 1.25 or 1.5), a factor 0.5 to 2 on the display's density. The car
  display's density becomes the display's times S: the host gives it to the app as the
  `SurfaceContainer`'s dpi (which Waze draws its map at) and as the `CarContext` configuration's
  `densityDpi` (`onConfigurationChanged`), and, as a host does when its surface is replaced, sends
  `onSurfaceDestroyed`, then `onSurfaceAvailable` at the new density and the visible and stable areas
  again: the change applies live, without restarting the app (Waze 5.25 redraws its map text,
  signs, icons and position arrow at once; its speedometer keeps the size it was created with
  until Waze next starts). The page's panels keep the display's density.

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
