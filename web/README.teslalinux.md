# web/

Fork of [tesla-android/frontend](https://github.com/tesla-android/frontend) (2026.3.1), the plain
HTML/JS client that runs in the Tesla browser. Served by nginx from `/var/www/teslalinux`.

Changes from upstream:
* `js/core/shared.js`: endpoints derive from `location.host` instead of `device.teslaandroid.com`.
* Voice search (`js/speech/speech.js`, new, one `<script defer>` line in `index.html`; a
  `speechWebSocket` endpoint in `js/core/shared.js`, started from `js/core/app.js`): this browser's
  microphone and speech recognition (the Web Speech API) in Hebrew or English. It answers the app's
  speech recognizer over `/sockets/speech` (Waze's own microphone button in the phone interface;
  protocol in docs/UPSTREAM-NOTES.md "Speech recognition"), and the car interface's search field has
  a microphone button (`js/carapp/carapp-renderer.js` `voiceButton`, `carapp.css`) that fills the
  field as the words are heard and searches when the driver stops speaking; it stays on while typing
  is off on the move. Settings, Waze interface: "Voice language" (kept by this browser) and "Test the
  microphone"; in a browser without speech recognition a tile says so and the buttons are left out.
* Display, the "Native (screen pixels)" resolution preset (value 5; `js/core/shared.js`
  `nativeDisplaySize`, `js/display/display-controller.js`, `js/display/display-renderer-manager.js`,
  `#display-root` `flex-shrink: 0` in `css/app.css`): the stream is the window's size times
  `devicePixelRatio`, rounded up to 16, and is shown pixel for pixel (the rounding pixels are clipped);
  the app's density scales with it (1 dp = 1.16 CSS px, what 832p gave on a Model 3), so sizes stay and
  sharpness improves. Over 1920x1088 it is scaled to fit. For every preset, the H.264 renderers' canvas
  backing store is now in device pixels (`h264WebCodecs.js`, `h264Brodway.js`): it was in CSS px, so each
  frame was resampled twice. docs/CAR-SCREENS.md has the numbers.
* `probe.html` (new, standalone, linked from nowhere): opened in a car's browser, it shows what the
  browser reports about its screen (viewport, `devicePixelRatio`, screen size, pointer, WebCodecs, user
  agent, a device-pixel checkerboard) and sends it as `GET /probe-report?...`, which lands in nginx's
  access log. Measured cars are in docs/CAR-SCREENS.md.
* `js/core/cert-warning.js` (new, one `<script defer>` line in `index.html`): a small dismissable
  notice, bottom left, when the car's TLS certificate is a placeholder, has fewer than 14 days left,
  or its last update failed or was rejected. It reads the new read-only `GET /api/certificate`
  (`services/api/api.py`), shows nothing if that route is missing or fails, and touches no upstream
  code, style or protocol.
* **Settings**, redesigned for this device and the car's touchscreen (`js/settings/settings-ui.js` and
  `css/settings.css`, new, one `<script defer>` and one stylesheet line in `index.html`;
  `js/settings/settings-controller.js`; `js/core/api-client.js`). Our own look in the spirit of the car
  interface's Graphite panels (no Tesla assets), English only with an RTL-ready layout (logical
  properties). Upstream's settings routes are kept and used as before; the new routes are in
  `services/api/api.py`.
  * The frame: Settings is the page's only page besides the map (see "Pages" below): the map's
    settings button opens it, "Map" goes back; a sticky header with the section's title, search across every setting (by title and the
    words a driver might use; a result opens the section and highlights the row; settings of the other
    Waze interface are not offered), a busy line and a "Waze is restarting" pill; a rail with each
    section's live value and a dot where something needs the driver. The page follows the car
    interface's day and night (Automatic: the sun at the car), `prefers-color-scheme` with the phone
    interface. At 600 px and below the rail is the first screen. Rows of at least 80 px, segmented
    controls for up to six short choices, picker sheets with a filter for long lists (language, time
    zone), editor sheets for text, switches, sliders, picture tiles for looks, two-tap buttons for
    anything that restarts or removes; every row has its own status line (Saving, Saved, Not saved +
    Try again), so a failure never wipes the section, and no `alert()` remains. Hover effects only for
    a fine pointer.
  * Changes that restart Waze (interface, keyboard, language and time zone, map frame rate, map colours
    to or from Graphite) wear a "Restarts Waze" chip and wait in one bar at the bottom ("Restart Waze
    to apply ...", Undo / Restart Waze now): several changes cost one restart, choosing the saved value
    again takes one out, a failed apply keeps them. The page shows the settings of the interface
    picked at once, even while that change waits (the other interface's values and waiting changes are
    kept).
  * **Overview**: a glance (the car on the hotspot, the internet, the car's position, Waze; each opens
    where it is explained), what needs the driver, and quick controls (where Waze's sound plays, day
    and night, Restart Waze); refreshed every 3 s from `GET /api/status`.
  * **Waze**: the interface (Phone / Car), the keyboard (phone interface only; the car's keyboard is the
    default), language and time zone, the car's position (upstream's GPS switch), and in the car
    interface its look (panel style, day and night, panel size) and map (colours, text size, frame
    rate, sharpness).
  * **Display**: the picture sent to the car (format, resolution, quality, follow the window size,
    refresh rate; upstream's `displayState`) and the Pi's graphics speed (`/api/gpuSpeed`, at the next
    device start).
  * **Sound**: where Waze's sound plays (`/api/audioOutput`), the Pi speaker and its volume, a test
    sound (`/api/audioTest`: a chime from the Pi into the sink Waze plays into, so it takes Waze's
    whole path; the press also starts the car's sound stream), the car's browser audio and its
    volume, and voice search (car interface only).
  * **Tesla** (`js/settings/settings-tesla.js`, `/api/carLink`, docs/TESLA-LINK.md): the car's VIN,
    pairing the device as a read-only key (the car's add-key prompt and the key card), the link's
    state and the route on the car's screen, Waze following that route, and the car's GPS as Waze's
    position.
  * **Connections** (replaces upstream's "Network"): the internet now (uplink, LTE operator and bars,
    data, the backup uplinks' own checks, failover and SIM notes, the modem's radio and recovery
    folded; `GET /api/status`, read from `host/teslalinux-uplink`), the hotspot (name, password, country,
    band and channel) and the "no internet" switches (upstream's offline mode) and the LTE APN as one
    draft with an apply bar (Discard / At next start / Apply now; `POST /api/network`,
    `host/teslalinux-network`, upstream's softAp and offline routes; upstream's apply-at-boot banner is
    gone), and the backup Wi-Fi, applied at once (a second USB Wi-Fi card joins a saved network when
    LTE has no internet or whenever connected; Add a network with a scan, `GET /api/wifiScan`, joined
    right after saving; Connect on each saved network with its progress and result,
    `POST/GET /api/wifiConnect`).
  * **Updates**: Teslalinux itself, first (`host/teslalinux-release-update`, docs/SYSTEM-UPDATES.md:
    installed and available release with its notes, Check now, download progress, Download over
    mobile data, Install now, Go back, the automatic switch, or "Not available" with the reason;
    `GET/POST /api/releaseUpdate`), Waze's own (`tools/appupdate`, docs/APP-UPDATES.md: source, automatic updates, version
    pin, LTE downloads, Check now, upload of a bundle with progress, Google Play via a token dispenser
    under Advanced; `GET /api/appUpdate`, `POST /api/appUpdateSettings`, `/api/appUpdateCheck`,
    `/api/appUpdateUpload?name=`, unbuffered in nginx), the system's (apt or apk: check, two-tap
    install, the package manager's progress and a terminal streaming the run, `?log=OFFSET`; restart
    when needed; `GET/POST /api/systemUpdate`, `host/teslalinux-system-update`), and the Teslalinux
    version installed (`system.teslalinux` in `GET /api/status`, from the dist's MANIFEST; "Not
    recorded" for an install without a release package).
  * **This device**: health (temperature, power or heat problems, load, memory, storage, running time),
    the start-up (`system.boot` in `GET /api/status`, measured each boot by `host/teslalinux-boottime`:
    ready for the car, Waze's map ready, the phases on one time axis, the previous starts), restart
    Waze (`GET /api/appState`, `POST /api/appRestart`, `host/teslalinux-restart-app`) or the device,
    what it is, and one Teslalinux row: the version, GPL-3.0 (the licence of this web app), "based
    on Tesla Android", and the link to the source (github.com/Alexa-RR/teslalinux).
  * Removed: Rear Display (a navigation app on the main screen), the Device tiles CarPlay Module and
    Release type; Android wording in GPS.
  * The page's own text fields count as "keyboard open" for `js/keyboard/device-keyboard.js`: typing in
    them neither resizes the app's display nor hands the keyboard to the app.
* Branding: the page is **Teslalinux**, a fork of Tesla Android, and says so. Title, favicon and
  home-screen icon (`assets/teslalinux-logo.png`, our own mark; upstream's logo is removed),
  Settings > This device's Teslalinux row (above), and "Teslalinux" in the user-facing strings of
  `index.html`, `js/settings/settings-controller.js`, `js/shell/shell-controller.js` and the audio
  media session title (`js/audio/audioplayback.js`). The Settings "Release type" note no longer points
  at beta.teslaandroid.com. The `persist.tesla-android.*` configuration keys are protocol and stay.
* Pages: two, the map (`#home-shell`) and Settings (`#page-settings`); `js/shell/shell-controller.js`
  `showMap()` / `showSettings()` switch them (the display pauses while Settings shows). Removed:
  upstream's About, Release Notes and Donations pages, the app bar and the bottom bar between them,
  their styles in `css/app.css`, `js/release-notes/`, `assets/donations-qr.svg` and
  `TAHtml.constants.APP_VERSION`. Upstream's release history is at github.com/tesla-android.
* Main display only: upstream's "Display Type" dialog (main or rear display, kept in `localStorage`)
  and its rear-display sizing are removed from `js/display/display-controller.js` (with
  `DISPLAY_PREF_KEYS` and `utils.parseBoolean` in `js/core/shared.js`); the page always sizes the
  stream from its own window. `/api/displayState` still carries `isRearDisplayEnabled` and
  `isRearDisplayPrioritised` for upstream's frontend, always 0; a POST may leave them out
  (docs/UPSTREAM-NOTES.md).
* No system update check: `js/home/home-controller.js` `checkForUpdates()` no longer compares the app
  version with the latest GitHub release of tesla-android/android-raspberry-pi (those are Tesla
  Android system images; Teslalinux updates apps from Settings > App updates), and
  `api-client.js` `fetchLatestRelease()` is gone.
* Browser audio on iPhone: `js/audio/audioplayback.js` `setupMSE()` falls back to
  `ManagedMediaSource` (Safari on iPhone has no `MediaSource`; iOS 17.1+) and disables remote
  playback on the audio element, as that API requires. The car's Chromium keeps `MediaSource`.
* Settings, **Keyboard** (now Settings > Waze > Keyboard; `fetchKeyboard` /
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
* Settings, **Waze interface** (now Settings > Waze): Phone (default) or Car
  (`fetchAppInterface` / `setAppInterface` in `js/core/api-client.js`, `GET/POST /api/appInterface`;
  the root helper `host/teslalinux-set-app-interface` writes `/etc/teslalinux/interface.env` and
  restarts the app; the page reloads once the API reports the new interface, and a page left open
  elsewhere follows through the `X-Teslalinux-App-Interface` header of its health checks, without
  polling of its own). In Car mode the app runs its Car App Library
  interface through ATL's car-app host: its map is the display stream, and `js/carapp/` (new: one
  stylesheet and three `<script defer>` lines in `index.html`) draws its templates over it from
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
  structural branches in the renderer; the page's own buttons, `#home-controls`, follow it as a
  frosted tile group through `carapp-page-graphite`/`carapp-page-night` on the body).
  The page's own buttons over the display (sound, settings; upstream's black tab in the top right
  corner, `css/app.css`) are floating map buttons like the car interface's: round dark buttons with
  the cards' lift, apart, inset from the corner (also in Phone mode and at phone width), and in the
  Graphite look one frosted tile group. In Car mode `carapp-controller.js` `placePageControls` gives
  them the app's map buttons' size, gap and inset (measured; never below 56 px), the top strip
  starts below them, a panel that reaches under them or past the middle of a top strip button (a
  narrow display) starts below those (`movePanelsBelowPageControls`), and full-screen panels keep
  their right side clear of them. The settings button shows `tune`, not a gear: the app's car
  interface has its own settings gear among its map buttons.
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
  The speedometer (`carapp-speedometer.js`, new): for the app versions the device's
  `tools/apps/waze-speedometer.py` knows (`speedometerSupport` in `/api/appInterface`), the app's
  own speedometer is off and its readings arrive on `/sockets/carapp` (`speedometer`); the page draws
  the current speed in a bubble (red above the limit, by the app's own offset rule) and the road's
  limit as a red-ringed sign beside it (the US rectangle where the app uses that style), at the bottom
  right of the map area the panels leave free, in both looks and sized with Panel size. Settings >
  Waze interface > Speedometer chooses "Speed and limit" (the default), "Limit only" or "Off"
  (`teslalinux.carSpeedometer`, this browser's); Speedometer position "Bottom right" (the default)
  or "Under the directions" (below the turn card, or the next-step pill over side panels, else the
  free map area's top left; `teslalinux.carSpeedometerPosition`). Other app versions keep drawing
  their own.
  "Map size" (Settings > Waze interface, Car mode only; `teslalinux.carMapSize`: Smaller, Default,
  Larger, Largest = 0.85, 1, 1.25, 1.5) sizes the app's own map text and symbols: the page sends
  `mapScale` to the car-app host, which gives the app the display's density times that factor as the
  car display's density, live (the app gets its surface again at the new dpi). The panels drawn
  here keep the display's density (`surface.dpi`; the map's is `surface.mapDpi`). The browser keeps
  the choice and sends it while the host reports none set since the app started (`mapScale` state
  with `set` false); once a page has set one, every page adopts the host's, so all agree.
  "Panel size" (`teslalinux.carPanelSize`: Smallest, Smaller, Default, Larger = 0.7, 0.85, 1, 1.15)
  sizes the panels drawn here: the renderer lays them out at `surface.dpi` times that factor (both
  looks size everything from that density), redrawn at once, and the map area they leave free goes
  to the app again. Kept by this browser only; the app's map is not affected.
  "Waze's settings" (Settings > Waze, the last group, while the car interface runs): the app's own
  car settings drawn by the settings page (`js/carapp/carapp-settings-view.js`, new, one
  `<script defer>` line). `carapp-controller.js` `openAppSettings` taps the app's settings action,
  found by its icon's resource name (`resName` from the car-app host), and while the page shows
  them keeps the app's settings screens off the map: a template whose screen stack
  (`templateInfosForScreenStack`) lies above the map's is handed to the view. The view draws the
  settings screen as the group (sections as headings, rows with switches, chevrons for sub-screens)
  and a sub-screen the app pushes in a sheet (a choice as ticked picker rows, the passes search
  with its filter, questions with their buttons); switches, choices and taps go to the app as the
  car panels' `checked` / `selected` / `click`, and what is shown is always the app's newest
  template, in the app's language. Closing the sheet is back to the settings screen; leaving the
  Waze section or the page (`SettingsController.onHidden`, also from `shell-controller.js`) pops
  the app's screens with `back`, one per answered template, until the map is its screen again.
  The page tells the car-app host when it opens and closes them and on `pagehide`
  (`{"type":"appSettings"}`), so the host pops them itself when the page goes without doing so (its
  socket closed or silent). While the app navigates it offers no settings action (Waze 5.27.5's
  driving map has search, routes and sound only), and while a question or list is on its map
  neither: the group says which and opens them by itself once the map offers the action again;
  during a drive it also offers "End the drive" (`stopNavigation`).
* The app's start over the display (`js/display/app-state-overlay.js`, new, one `<script defer>`
  line; its styles at the end of `css/app.css`): while the app is not drawing, a panel in the
  settings page's look (day or night as that page) says whether the device is preparing its code
  (ART compiling it after an app or runtime update or a mode change, with the real progress: the
  compile's CPU time against the last one on this device, `GET /api/appState` `start.compile`),
  starting it, or restarting it after it stopped, with the start's steps. It follows
  `/api/appState?wait=SEQ` (a long poll) and goes at the first display frame after the app's first
  picture (`js/core/app.js` `onFrameReceived`); it never shows when the device cannot be asked.
  Where the app's current screen has no settings action (a question over the map), the group says
  so instead. `test.html?view=settings` draws the `settings*` samples this way.
  "Typing while driving" (Settings > Waze, While driving, while the car interface runs): Off (the
  default, as Android Auto) or On, the car-app host's UX restriction mode ("baseline" or
  "passenger", `restrictionMode` on `/sockets/carapp`; `carapp-controller.js`
  `getRestrictionMode`/`setRestrictionMode`, which waits for the host's `restrictions` naming the
  mode). On, a passenger can type in the search field while the car moves; lists stay capped. The
  device keeps it; the row says when the runtime has no restriction modes.
* `emulate.html`, `js/core/screen-emulation.js` (new; the script is the first in `index.html`'s
  head, not deferred): Settings > This device > Debug > "Emulate the Model 3 screen", for testing
  on a computer, per browser (`localStorage` `teslalinux.debug.emulateScreen`). While it is on the
  page goes to `emulate.html`, which shows it in a frame with a Model 3 browser's viewport
  (773.2 x 601.3 CSS px, docs/CAR-SCREENS.md) scaled to fit the window (or one car pixel per screen
  pixel), under a bar that says so; in that frame `devicePixelRatio` is the car's 1.53, so the
  layout, the display size the page asks for (Native: 1184 x 928 at density 284) and the stream
  canvas's backing store come out as in the car. `app.js`, `carapp-controller.js` and
  `device-keyboard.js` do not start in the page that is leaving for the emulator.
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
* `js/gates/gates-controller.js`, `js/gates/gates.css`, `js/settings/settings-gates.js` (new):
  PalGate gates (`docs/GATES.md`). The gate button `#home-gates-button` in `index.html`'s
  `#home-controls` (hidden until gates are linked and shown; one gate shown: it opens it, more: a
  tray of them), the notices at the top (opens, failures with their reason, auto-opens from the
  `/api/gatesEvents` long poll), and Settings > Gates (installed into SettingsController's
  sections after Waze: link with a QR code, unlink, the gates shown and their order, per gate its
  name, output, Open now and auto-open). The tray and notices follow the Rounded and Graphite
  looks, day and night.
* `js/settings/settings-grok.js` (new): Settings > Grok, Grok remote control (`docs/GROK.md`,
  `/api/grok*`), installed into SettingsController's sections after Gates: the on/off switch, the
  tunnel's state, the connector address, the last request, the automatic Cloudflare setup (domain,
  hostname, email, API token; the token is sent once and not kept), the manual settings (tunnel
  token, Access team domain, AUD tag, hostname) and the log of what Grok asked for. Hooks: one
  `<script>` in `index.html`, one `install` line in `settings-controller.js`.
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
