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
| `/sockets/speech` | 127.0.0.1:9994 | teslalinux only: speech recognition by the page for the app, JSON text (ATL) |
| `/api/grok*` | 127.0.0.1:8084 | teslalinux only: Grok remote control's settings (`services/grok`, `docs/GROK.md`) |
| (none) | 127.0.0.1:8083 | teslalinux only: Grok's MCP endpoint, reached by cloudflared only, never routed by nginx |
| (none) | 127.0.0.1:9993 | teslalinux only: ATL's intent channel (view intents from outside the app), never routed |

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
  `{"$":"IconCompat","icon":N}` (one of the app's drawables also with `"resName"`, its resource
  entry name, e.g. `"settings_outline_24px"`: what it shows, the same across app versions,
  where `resId` changes with every build), callbacks as `{"$":"OnClickDelegateImpl","$cb":ID}`.
  Callback ids are valid until the next template. A `CarText` with spans is `{"$":"CarText","text":S,"spans":
  [{"start":A,"end":B,"span":{"$":"ForegroundCarColorSpan"|"CarIconSpan"|"DurationSpan"|"DistanceSpan",...}}]}`.
  The wrapper's `templateInfosForScreenStack` (`[{"$":"TemplateInfo","templateId":S}]`, top first)
  is the app's screen stack (its `ScreenManager`): a screen keeps its id while screens lie above it
  and when it is popped back to, so a page tells a pushed screen from a refreshed one by it (as
  the AOSP host's `TemplateValidator` does).
- `{"type":"navigation","active":B}`: whether the app navigates (`NavigationManager.navigationStarted`
  / `navigationEnded`); state, sent to every new page. While it does, the page offers the end-drive
  control and keeps the next step in view as a pill over side panels.
- `{"type":"restrictions","active":B,"noKeyboard":B,"maxItems":N,"maxString":N,"mode":M}`: the car's
  UX restrictions (driving state from the GPS speed, the car's UX restrictions configuration: AOSP's
  default restriction map unless the car service has a production configuration) in the car's
  restriction mode `M` ("baseline", or "passenger": the moving restrictions without
  `NO_KEYBOARD`); state, sent to every new page and again when the mode changes. While they apply
  the page caps lists at the host's content limits and turns the search field off when
  `noKeyboard`. A runtime without restriction modes sends no `mode`.
- `{"type":"dayNight","night":B}`: the car's night mode as the app hears it (`CarContext.isDarkMode`);
  state, sent to every new page and again when it changes. The page's Graphite look draws its
  panels by day or by night with it (unless the driver fixed day or night on the page).
- `{"type":"mapScale","scale":S,"set":B}`: the map scale, and whether a page has set it since the app
  started; state, sent to every new page and again when it changes. A page with a choice of its own
  sends it while `set` is false, and adopts `scale` once it is true, so all pages agree.
- `{"type":"speedometer","speed":S,"units":U,"speedLimit":L,"speedKph":K,"miles":B}`: the app's
  speedometer reading (ATL's `ATLCarSpeedometer`), for the app versions the device knows
  (`tools/apps/waze-speedometer.py`; there the app's own speedometer is off and the page draws it):
  the speed the app shows in its unit `U` (as the app names it, e.g. "km/h"; below 0: no speed), the
  road's speed limit in the same unit (0 or below: none known), the speed in km/h (below 0: none),
  and whether the unit is miles per hour. State: the latest reading is sent to every new page; a
  reading equal to the last is not sent again (the app makes one per position, about once a
  second). The sign style and the over-the-limit offset are the app's settings, in
  `/api/appInterface` (`speedometerSupport.signStyle`, `.alertOffset`).
- `{"type":"trip","trip":T}`: the app's trip (`NavigationManager.updateTrip`: its `Trip` model as
  above: `destinations` with `name` and `address`, `destinationTravelEstimates` with
  `remainingDistance`, `remainingTimeSeconds`, `arrivalTimeAtDestination`, `steps` with `cue`,
  `maneuver`, `road`, `stepTravelEstimates`, `currentRoad`), its icons sent first; `null` when
  navigation ends. State, sent to every new page (after the icons); a trip equal to the last is
  not sent again.
- `{"type":"navigateResult"|"stopNavigationResult"|"holdResult","id":N,"ok":B,"error":S}`: the
  answer to a request that carried an `id`, to the page that sent it only (`error` when not `ok`).
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
- `{"type":"back"}`: back (`AppManager.onBackPressed`; the app's `ScreenManager` pops its top screen).
  The settings page uses these messages to show the app's own settings (Settings > Waze >
  Waze's settings, `js/carapp/carapp-settings-view.js`): it clicks the action whose icon's
  `resName` names settings (`/(^|_)settings?(_|$)/`), draws the templates whose screen stack lies
  above the one it clicked from in the page instead of over the map, sends `click` / `checked` /
  `selected` for their rows, and when the page stops showing them sends `back`, one at a time
  (each after the template answering the previous), until the stack is the one it started from.
- `{"type":"appSettings","state":"open"|"closed"|"abandon"}`: where the page is with the app's
  settings: `open` right before it taps the settings action, `closed` once it stopped showing them
  (back at the map, or it left the app's own screen where the driver sees it), `abandon` when the
  page goes away for good (`pagehide`) while they are open. The host remembers which page opened
  them and, when that page abandons them, its socket closes, or it goes silent (the host pings
  every page every 10 s and closes one it heard nothing from for 30 s, such as a car browser that
  left the hotspot's range), pops them back to the map itself with the same backs, one at a time
  (each after the template answering the previous, at most 12; a back the app does not answer in
  3 s leaves the app's screen as it is), so they never stay on the car's map. A runtime without
  this ignores the message.
- `{"type":"visibleArea","left":L,"top":T,"right":R,"bottom":B}`: the part of the map the panels leave
  free, inside their margins (display pixels), passed to the app as its visible and stable areas
  (the app keeps its car and route in it; the page's speedometer stands at its bottom right, where
  the app drew its own).
- `{"type":"stopNavigation","id":N?}`: the end-drive control (`NavigationManagerCallback.onStopNavigation`).
  With an `id`, answered with `stopNavigationResult` (not `ok` when the app is not navigating).
- `{"type":"navigate","uri":GEO,"id":N?}`: a destination for the app, as a car's host hands over
  the one its assistant was asked for: `ICarApp.onNewIntent` with `CarContext.ACTION_NAVIGATE`
  (`androidx.car.app.action.NAVIGATE`) and the `geo:` URI, `geo:0,0?q=ADDRESS` or
  `geo:LAT,LON?q=LAT,LON`; the app's `Session.onNewIntent` gets it (Waze searches the query and
  starts driving). Answered with `navigateResult` when it carries an `id` (not `ok` for a URI that
  is not `geo:`, or before the app's car session runs).
- `{"type":"hold","on":B,"id":N?}`: the page works on the app's screens out of the driver's sight
  (Grok's `search_places`, `services/grok/waze.py`): while it holds, new templates go to it alone
  and every other page keeps the template it shows (a page that connects meanwhile gets that one).
  The app's screen stack when it took the hold is remembered as for the app's settings: a page that
  goes away (socket closed, or silent) while holding has the screens it pushed popped back by the
  host before the other pages see the current template again. Released with `on` false (the
  screens are left as they are: the page popped them itself, or a drive it started is on screen),
  then every page gets the current template. One page holds at a time, and none while a page shows
  the app's settings; answered with `holdResult`. A runtime without it ignores the message.
- `{"type":"alertCancel","cb":ID,"reason":R}` / `{"type":"alertDismiss","cb":ID}`: an alert timed out
  (R 1) or was closed (R 2), or went away after one of its actions (`AlertCallback`).
- `{"type":"dayNight","mode":"day"|"night"|"auto"}`: the driver's day or night choice; "auto" (the
  default, or `ATL_CAR_DAY_NIGHT`) follows the sun at the car's position. The app hears it as the
  configuration's night mode (`CarContext.isDarkMode`). The page sends it when the driver changes
  it (Settings > Waze interface > Day and night) and, when it is not "auto", on every connection.
- `{"type":"restrictionMode","mode":M}`: the car's UX restriction mode (AOSP
  `CarUxRestrictionsManager.setRestrictionMode`), Settings > Waze > Typing while driving: "baseline"
  (Off, the default, as Android Auto) or "passenger" (On). The host applies it at once (the next
  `restrictions` names it) and keeps it for the app's next starts (`ux_restrictions_host_mode` in
  the car service's directory, `<app data>/_system_/car`); a mode the configuration does not
  define falls back to the baseline's restrictions. A name that is not a mode name is ignored.
- `{"type":"mapScale","scale":S}`: the size of the app's map text and symbols (Settings > Waze
  interface > Map size: 0.85, 1, 1.25 or 1.5), a factor 0.5 to 2 on the display's density. The car
  display's density becomes the display's times S: the host gives it to the app as the
  `SurfaceContainer`'s dpi (which Waze draws its map at) and as the `CarContext` configuration's
  `densityDpi` (`onConfigurationChanged`), and, as a host does when its surface is replaced, sends
  `onSurfaceDestroyed`, then `onSurfaceAvailable` at the new density and the visible and stable areas
  again: the change applies live, without restarting the app (Waze 5.25 redraws its map text,
  signs, icons and position arrow at once; its speedometer keeps the size it was created with
  until Waze next starts). The page's panels keep the display's density.

## Speech recognition (`/sockets/speech`, port 9994; teslalinux only, no upstream counterpart)

The device has no microphone and no speech recognizer; the driver's browser has both (the Web
Speech API). ATL's default `SpeechRecognizer` (`android/speech/TlSpeechChannel.java`, served while
`ATL_SPEECH_CHANNEL_PORT` is set; `teslalinux-atl` sets 9994) hands the app's recognition requests
to the page (`web/js/speech/speech.js`) and the page answers with the text. JSON text frames:

- page → ATL: `{"type":"hello","recognition":B}` on connect (whether this browser has speech
  recognition; `SpeechRecognizer.isRecognitionAvailable` is true while such a page is connected,
  the newest one gets the requests). Per session `N`: `{"type":"ready","id":N}` (the microphone is
  open: `onReadyForSpeech`), `{"type":"speechStart","id":N}`, `{"type":"speechEnd","id":N}`,
  `{"type":"partial","id":N,"texts":[...]}`, `{"type":"results","id":N,"texts":[...],
  "confidences":[...]}` (-1: unknown), or `{"type":"error","id":N,"error":E}` with the Web Speech
  API's error names plus `no-match` (it ended without text) and `busy`. ATL maps them to
  `SpeechRecognizer.ERROR_*` (`no-speech` → `ERROR_SPEECH_TIMEOUT`, `not-allowed` →
  `ERROR_INSUFFICIENT_PERMISSIONS`, `network` → `ERROR_NETWORK`, ...); a page that disconnects ends
  its sessions with `ERROR_SERVER_DISCONNECTED`.
- ATL → page: `{"type":"listen","id":N,"lang":TAG,"partial":B,"maxResults":M}` (`lang` is the
  app's `EXTRA_LANGUAGE`, else the device locale, as a BCP 47 tag; the page uses it when it is one
  of the voice languages it offers, Hebrew or English, else the browser's choice in Settings),
  `{"type":"stop","id":N}` (`stopListening`: end the audio, deliver the result),
  `{"type":"cancel","id":N}`.

In the car interface the page is the car's host, and the host's microphone button (in the search
field) does voice search itself: it listens the same way and sends the text as the template's
`search` message, with no app involvement.

## Intent channel (127.0.0.1:9993; teslalinux only, never routed by nginx)

How a program on the device hands the app a view intent, as Android's `am start -a ACTION -d URI`
does (ATL's `android/atl/ATLIntentChannel`, on while `ATL_INTENT_CHANNEL_PORT` is set;
`teslalinux-atl` sets 9993). One request per TCP connection, a line of JSON each way:

- in: `{"type":"startActivity","action":"android.intent.action.VIEW","data":URI,"mimeType":T?}`
- out: `{"ok":true,"activity":"com.waze.FreeMapAppActivity"}` (the app's activity its own intent
  filters resolve it to; started as `Context.startActivity` starts it: an existing
  singleTask/singleInstance activity gets `onNewIntent`), or `{"ok":false,"error":S}` (no activity
  takes it, the app is not up yet, or the app runs its car interface, where it shows no phone
  activities: its car session takes intents through the car app channel's `navigate`).

Grok remote control uses it in Phone mode for Waze's deep links (`waze://?q=...&navigate=yes`).

## Config API (`/api`, port 8081)

GET `configuration`, `displayState`, `deviceInfo`, `health` (polled every 30 s online / 5 s offline;
`location.reload()` on recovery), `openUpdater`.
POST `displayState` `{width,height,density,resolutionPreset,renderer,isResponsive,isH264,quality,
refreshRate,isRearDisplayEnabled,isRearDisplayPrioritised}`, `gpsState`, `browserAudioState`,
`browserAudioVolume`, `softAp*`, `offlineMode*`, `overrideReleaseType`, `overrideOtaUrl`.
Teslalinux drives the car's main screen only: GET `displayState` reports `isRearDisplayEnabled` and
`isRearDisplayPrioritised` as 0, and a POST may leave them out (when sent they must be numbers, as
upstream checks, and are not kept). Our page has no Display Type dialog.

teslalinux only (no upstream counterpart): `/api/gates*` (PalGate gates, `docs/GATES.md`), JSON,
forwarded by api.py to `services/gates/gates.py`: GET `gates`, `gatesLink`, `gatesEvents?after=N&wait=S`
(a long poll, at most 25 s); POST `gatesLink {action: start|cancel}`, `gatesUnlink`, `gatesRefresh`,
`gatesSettings`, `gatesPosition {id}`, `gatesOpen {id, output?}` (`{ok, message, event}`).

teslalinux only: `/api/grok*` (Grok remote control, `docs/GROK.md`), JSON, served by
`services/grok/grok.py` on 127.0.0.1:8084 (its own nginx location, not api.py): GET `grok`
(settings without secrets, the tunnel's state, the last request, the last actions, the connector
address, the API token's permissions); POST `grokSettings {enabled?, hostname?, teamDomain?, aud?}`,
`grokToken {token}` or `{clear: true}`, `grokSetup {domain, hostname?, email, apiToken,
redirectUris?}` (answers `{ok, message, steps, note?, settings}`; the API token is not kept).

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
