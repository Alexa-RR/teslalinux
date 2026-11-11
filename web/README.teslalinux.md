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
  as supporting the original project), a line on the Release Notes page saying they are Tesla
  Android's history, and "Teslalinux" in the user-facing strings of `index.html`,
  `js/settings/settings-controller.js`, `js/shell/shell-controller.js` and the audio media session
  title (`js/audio/audioplayback.js`). The Settings "Release type" note no longer points at
  beta.teslaandroid.com. The `persist.tesla-android.*` configuration keys are protocol and stay.
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
