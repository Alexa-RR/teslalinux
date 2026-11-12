# web/

Fork of [tesla-android/frontend](https://github.com/tesla-android/frontend) (2026.3.1), the plain
HTML/JS client that runs in the Tesla browser. Served by nginx from `/var/www/teslalinux`.

Changes from upstream:
* `js/core/shared.js`: endpoints derive from `location.host` instead of `device.teslaandroid.com`.
* `js/core/cert-warning.js` (new, one `<script defer>` line in `index.html`): a small dismissable
  notice, bottom left, when the car's TLS certificate is a placeholder, has fewer than 14 days left,
  or its last update failed or was rejected. It reads the new read-only `GET /api/certificate`
  (`services/api/api.py`), shows nothing if that route is missing or fails, and touches no upstream
  code, style or protocol.
* Settings, Device: a "Restart Waze" tile (`js/settings/settings-controller.js`, `css/app.css`
  `.settings-action-button`, two `js/core/api-client.js` methods). It shows the app's service state from
  the new `GET /api/appState` and restarts only the app with `POST /api/appRestart` (two taps within 4 s,
  so a stray touch cannot restart navigation), then follows the state until the app runs again. The
  tile is left out when the API lacks the route. `services/api/api.py` runs `host/teslalinux-restart-app`
  (systemd or OpenRC) through one sudoers rule, like `teslalinux-apply-display`.
* Settings reworked for this device (`js/settings/settings-controller.js`, `css/app.css`,
  `js/core/api-client.js`), new routes in `services/api/api.py`, no upstream route changed:
  * **Status** (first section, opens by default): what the device is connected to right now, refreshed
    every 3 s: the car and other clients on the hotspot (DHCP name, address, signal, link rate,
    connected time), pages showing the app, the internet uplink (LTE / Wi-Fi / Ethernet, address,
    reachability and latency, data since start), the LTE modem (operator, network type, signal bars,
    roaming, connection, radio values, data this session and month, SIM problems: a HiLink stick's web
    API or ModemManager, read by `host/teslalinux-uplink`) and its automatic recovery (status, last
    step, next step), the hotspot (SSID, band, channel, width), the car's last GPS
    fix, the app's state, and the device (OS, kernel, uptime, load, memory, storage, CPU temperature,
    Pi under-voltage/throttling). `GET /api/status`.
  * **Wi-Fi & LTE** replaces upstream's "Network": hotspot name, password (never sent to the page),
    country, band and channel; the "no internet" switches (upstream's offline mode, telemetry,
    firmware downloads); LTE APN/user/password/roaming/mobile data where NetworkManager runs (Pi OS),
    otherwise what the modem is and why it needs no APN here. One "Save and apply now" (restarts the
    hotspot a moment later) or "Save for next start", instead of upstream's apply-at-boot banner (the
    banner is gone). `GET/POST /api/network`, `host/teslalinux-network` (root, sudoers).
    **Backup Wi-Fi** below it (changes apply at once): a second (USB) Wi-Fi card joins one of the saved
    networks (scan list or typed, hidden networks too; stored as the WPA key, never the password) and
    `host/teslalinux-uplink` (root daemon, systemd/OpenRC) routes the car and the app through it when LTE
    has no internet, e.g. underground ("When LTE has no internet"), or whenever it is connected
    ("Whenever connected", saves mobile data). The daemon checks every uplink through its own interface
    every 5 s and installs its own default route (metric 5) through the best one that works; Status
    shows which uplink is in use, why, and each uplink's check. `GET /api/wifiScan`.
  * **System update**: the OS underneath (apt on Pi OS/Debian, apk on Alpine): check, the list of
    updates, install (two taps; warns when the download would go over LTE), a live progress bar fed by
    the package manager's own progress (apt's Status-Fd, apk's --progress-fd) and a terminal that
    streams the run's output line by line (`?log=OFFSET`), "restart needed" after a new kernel, restart
    the device. `GET/POST /api/systemUpdate`,
    `host/teslalinux-system-update` (root, sudoers; holds from `/etc/teslalinux/system-update.hold`).
  * Removed: Rear Display (a navigation app on the main screen), the Device tiles CarPlay Module, LTE
    Modem (now on Status) and Release type; Android wording in GPS.
  * Dropdown menus open above the page (moved to `<body>` with fixed positioning from the trigger,
    flipped upwards when there is no room below), so the scrolling settings panel no longer cuts them.
  * The page's own text fields count as "keyboard open" for `js/keyboard/device-keyboard.js`: typing in
    them neither resizes the app's display nor hands the keyboard to the app.
* Settings, **App updates** (new sidebar section between "Language & region" and "Device";
  `js/settings/settings-controller.js` `_renderAppUpdateSection`, four `js/core/api-client.js` methods,
  the upload one with XMLHttpRequest for its progress events; `css/app.css` `.settings-inline-field`,
  `.settings-text-input`): Waze updates (`tools/appupdate`, docs/APP-UPDATES.md). Update source
  (APKPure automatic / manual uploads only / Google Play via Aurora, "unavailable" unless enabled under
  Advanced), "Update Waze automatically", "Allow updates over LTE", the status ("Update server
  unavailable since ...", a verified update waiting, the health check, the last result and notice),
  "Check for an update now", "Upload an update" (.apkm/.xapk/.apks/.zip/.apk up to 400 MB, progress,
  then the verification result), and Advanced: the Google Play switch and the dispenser address.
  Routes: `GET /api/appUpdate`, `POST /api/appUpdateSettings` (JSON), `POST /api/appUpdateCheck`,
  `POST /api/appUpdateUpload?name=` (the file as the body), all new in `services/api/api.py`; nginx
  passes the upload through unbuffered (`teslalinux-app.inc`). No upstream route or response changed.
* Branding: the page is **Teslalinux**, a fork of Tesla Android, and says so. Title, favicon and
  home-screen icon (`assets/teslalinux-logo.svg` + `.png`, our own mark; upstream's logo is removed),
  the About page (what Teslalinux is, that it is a fork of Tesla Android and how it differs, and the
  upstream web app version it is based on, in place of the 2nd-generation hardware notice), the
  Donations tab renamed "Credits" (it credits Tesla Android and keeps upstream's donation QR, labelled
  as supporting the original project), a line on the Release Notes page crediting Tesla Android
  for the page, and "Teslalinux" in the user-facing strings of `index.html`,
  `js/settings/settings-controller.js`, `js/shell/shell-controller.js` and the audio media session
  title (`js/audio/audioplayback.js`). The Settings "Release type" note no longer points at
  beta.teslaandroid.com. The `persist.tesla-android.*` configuration keys are protocol and stay.
* Release Notes: the page, `js/release-notes/release-notes-controller.js` (unchanged) and its
  styles are Tesla Android's; the data, `js/release-notes/release-notes-data.js`, now holds
  Teslalinux's own releases instead of upstream's. It is generated from the repository's
  `CHANGELOG.md` by `tools/changelog/changelog.py page` (one card per "###" topic, its italic first
  line as the subtitle, plain text; the developers' "Platform" part and the images are left out),
  and `tools/debvm/pack-dist.sh` refuses to pack a stale copy. Upstream's own release history is
  at github.com/tesla-android.
* No system update check: `js/home/home-controller.js` `checkForUpdates()` no longer compares the app
  version with the latest GitHub release of tesla-android/android-raspberry-pi (those are Tesla
  Android system images; Teslalinux updates apps from Settings > App updates), and
  `api-client.js` `fetchLatestRelease()` is gone.
* Browser audio on iPhone: `js/audio/audioplayback.js` `setupMSE()` falls back to
  `ManagedMediaSource` (Safari on iPhone has no `MediaSource`; iOS 17.1+) and disables remote
  playback on the audio element, as that API requires. The car's Chromium keeps `MediaSource`.
* Settings, **Keyboard**: "Use this device's keyboard" (`_renderKeyboardSection`, `fetchKeyboard` /
  `setKeyboard` in `js/core/api-client.js`, `GET/POST /api/keyboard`; the root helper
  `host/teslalinux-set-keyboard` writes `/etc/teslalinux/keyboard.env` and restarts the app, whose
  launcher then turns the app's own on-screen keyboard off). `js/keyboard/device-keyboard.js` (new,
  one `<script defer>` line): in that mode it connects to `/sockets/keyboard` (tacomp's text-input-v3
  bridge), focuses a hidden input with the field's input mode when a text field in the app wants input,
  and sends what is typed back ("t TEXT", "k enter|backspace"), as the difference of the input's value
  so autocorrect and composed input work. Safari on iPhone opens a keyboard only from a tap: a keyboard
  button shows while a field wants input and the keyboard is not up, and any tap on the page opens it.
  While the keyboard is up, `js/display/display-controller.js` `triggerResize()` ignores viewport
  changes (one guard): the keyboard shrinks the viewport, and resizing the display for it would
  restart tacomp and close the keyboard. A field that turns text input off and on again within 600 ms
  (a tap on it) keeps the keyboard.
* Settings, **Waze interface**: Phone (default) or Car (`_renderInterfaceSection`,
  `fetchAppInterface` / `setAppInterface` in `js/core/api-client.js`, `GET/POST /api/appInterface`;
  the root helper `host/teslalinux-set-app-interface` writes `/etc/teslalinux/interface.env` and
  restarts the app; the page reloads once the API reports the new interface, and a page left open
  elsewhere follows through the `X-Teslalinux-App-Interface` header of its health checks, without
  polling of its own). In Car mode the app runs its Car App Library
  interface through ATL's car-app host: its map is the display stream, and `js/carapp/` (new: one
  stylesheet and two `<script defer>` lines in `index.html`) draws its templates over it from
  `/sockets/carapp` (docs/UPSTREAM-NOTES.md, "Car app templates"). `carapp-controller.js` does
  nothing unless the API says "car"; it adds `#carapp-overlay` inside `#display-root`, above the
  touch layer, where only the panels take touches (map touches reach `/sockets/touchscreen` as
  before). `carapp-renderer.js` places the panels where the AOSP car Templates Host places them,
  with its spacing and text sizes from `host-style.json` (extracted from the host APK by
  `tools/carhost/host-style.py`); colours and shapes are our own (`carapp.css`). What a car host
  adds by itself is drawn here too: coloured and image text spans, the default action's countdown,
  the map buttons folded into ± over the bare map, the next step as a pill over side panels, paging
  arrows on full-screen lists, the end-drive control, toasts and alerts, and the driving
  restrictions (lists capped, search typing off while moving). `test.html` +
  `test-templates.json` render sample templates without a device (serve `web/` over HTTP).
  Two looks, chosen in Settings > Waze interface > Car interface style and kept in the browser's
  local storage (`teslalinux.carInterfaceStyle`, like the page's other display preferences; the
  device keeps nothing): "Rounded" (the default: dark graphite cards, pill buttons, round map
  buttons) and "Graphite" (frosted light or dark cards with hairline dividers, a single turn card
  with lanes and the next step, a trip card with an "End drive" foot, the map buttons as frosted
  tile columns, the search as a card over the live map; `.carapp-graphite` in `carapp.css`, a few
  structural branches in the renderer; the page's own mute and settings buttons, `#home-controls`,
  follow it as a frosted chip through `carapp-page-graphite`/`carapp-page-night` on the body).
  Graphite follows day and night: the driver's choice
  (Settings > Waze interface > Day and night, `teslalinux.carDayNight`, sent to the car-app host
  as `dayNight`, so the app's map switches too) or, on Automatic, the host's `dayNight` state.
  With Graphite, "Map colours: Muted" (the default; `teslalinux.carMapColours`) shows the app's
  map in the look's colours: `js/display/map-tone.js` (new) is one function added to the H.264
  renderers' fragment shaders (`h264-webcodecs/h264WebCodecsWorker.js` after its texture read,
  `h264-broadway/YUVCanvas.js` after its YUV conversion; each worker loads it by the URL its page
  script passes, `window.setDisplayMapTone("day"|"night"|null)` switches it and redraws the last
  frame). Map surfaces turn neutral grey, the app's purple (day) or cyan (night) route the look's
  blue; strong and warm colours (traffic, pins, reports, the speedometer) stay. No extra pass, about
  60 shader operations per pixel; off, the shader returns at once. It runs only while the page
  knows which of the app's two palettes is on screen (day or night chosen, or reported by the
  host). The MJPEG renderer shows the map as it is. The app itself is not changed.
  "Map colours: Graphite" is the device's choice instead (`/api/appInterface` `mapColours`,
  `teslalinux-set-app-interface map graphite`, the app restarts): ATL serves the app its own map
  palette recoloured to the look (docs/MAP-COLOURS.md), so the page draws no shader pass. It applies
  only to the app versions on the device's verified list (`mapSkin` in the same reply); for any other
  version the page falls back to Muted and Settings shows a warning tile. "Muted" and "Waze's own"
  are also saved on the device; until one is, the browser's earlier `teslalinux.carMapColours` holds.
  "Map size" (Settings > Waze interface, Car mode only; `teslalinux.carMapSize`: Smaller, Default,
  Larger, Largest = 0.85, 1, 1.25, 1.5) sizes the app's own map text and symbols: the page sends
  `mapScale` to the car-app host, which gives the app the display's density times that factor as the
  car display's density, live (the app gets its surface again at the new dpi). The panels drawn
  here keep the display's density (`surface.dpi`; the map's is `surface.mapDpi`). The browser keeps
  the choice and sends it while the host reports none set since the app started (`mapScale` state
  with `set` false); once a page has set one, every page adopts the host's, so all agree.
* `fonts/Inter-Regular-car.woff2`, `fonts/Inter-Bold-car.woff2` (new): the Graphite look's text
  face, Inter 4.001 by The Inter Project Authors (SIL Open Font License 1.1, `fonts/Inter-OFL.txt`,
  github.com/rsms/inter), the static Regular and Bold cut to Latin, Latin-1 and Latin Extended-A
  plus a few signs (U+0020-007E, U+00A0-017F, U+0218-021B, U+02C6, U+02DA, U+02DC, U+2013-2014,
  U+2018-201E, U+2022, U+2026, U+2032-2033, U+2039-203A, U+20AA, U+20AC, U+2122, U+2190-2193,
  U+2212) with fontTools (`pyftsubset --flavor=woff2 --no-hinting --desubroutinize
  --layout-features=kern,tnum,calt,case,ccmp,locl,mark,mkmk`). Weights up to 549 use Regular,
  heavier ones Bold. Text outside the subset (Hebrew, Arabic) falls back to the page's fonts.
* `fonts/MaterialSymbolsRounded-car.woff2` (new): the maneuver and control glyphs of the car panels,
  a subset of Google's Material Symbols Rounded (Apache License 2.0,
  github.com/google/material-design-icons, `variablefont/MaterialSymbolsRounded*.ttf` at commit
  10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7), instanced at FILL 0, GRAD 0, opsz 24, wght 400 and
  cut to the code points U+E570 E57B E5C4 E5CC E5CD E89F EB95 EB96 EB98 EB99 EB9A EB9C EBA0-EBA4
  EBA6 EBA7 EBAA EBAB EBAC EFF5 F06E F0C6 with fontTools
  (`fonttools varLib.instancer` then `pyftsubset --flavor=woff2`).
* `404.html` (new): the page nginx serves for unknown paths (upstream's lighttpd names
  `/404.html` as its 404 handler but ships none).
* `online/status.html`, `online/connectivity_check.txt`: byte-for-byte copies of upstream's
  `www-default/online/` files. Upstream serves them to every host, including the spoofed
  `connman.vn.tesla.services` in offline mode; which path the car's connectivity check requests is
  not documented upstream, so the files are kept exactly as shipped.

Everything else (MJPEG/H.264 renderers, touch protocol, FLAC/MSE audio, GPS polling, health polling)
is unchanged and matches the contracts in `docs/UPSTREAM-NOTES.md`.

Future (not done): update `web/` without reflashing, by pulling a signed frontend bundle over the
LTE link when one is available and serving it from nginx (docs/CAR-STACK-READINESS.md, "Future").
