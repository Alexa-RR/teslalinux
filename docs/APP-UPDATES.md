# App updates: the Pi keeps Waze current by itself

The Pi checks for a new Waze once a day, downloads it, verifies it against the installed one, installs
it only while the app is not in use, makes sure the new version starts, and rolls back if it does
not. It uses no Play Store app, no other APK, no cloud job and **no account of any kind**.

## Sources

| Source (setting `source`) | How | Default |
|---|---|---|
| **APKPure, automatic** (`apkpure`) | APKPure's public app API, no account, as EFF's apkeep uses it | **on** |
| **Manual upload** (`manual`; uploads also work with any source) | a bundle uploaded from the car page: APKMirror `.apkm`, `.xapk`, `.apks`, a zip of APKs or one `.apk` | always available |
| Google Play via a token dispenser (`play`) | an anonymous Play login from a token dispenser (Aurora Store's by default), gpapi | **off**; listed as "unavailable" until "Advanced: Google Play via a token dispenser" is switched on |

Whatever the source, the same verification decides. Every APK must carry Waze's own signature, the
same certificate as the installed app, so a mirror can make an update unavailable but cannot get a
modified or foreign APK installed.

**Why APKMirror is manual-only.** APKMirror offers no public download API and does not allow automated
downloads. Its files are fetched by a person in a browser and uploaded to the Pi.

**Why Google Play is off.** Aurora Store's dispenser (`https://auroraoss.com/api/auth`) refuses clients
other than Aurora Store: tlplay, which identifies itself honestly, got a Cloudflare block page (HTTP 403,
2026-11-09). Getting past it would mean pretending to be Aurora Store. tlplay never does that: it
always sends its own User-Agent (`teslalinux-appupdate/<version>`), and no option changes it. The
code stays in the tree for a dispenser that accepts this Pi (a mirror or one of your own).

| Piece | Where | What it does |
|---|---|---|
| `tlplay` | `tools/appupdate/` (Rust; [`gpapi`](https://crates.io/crates/gpapi) 6.1.0 for Play, its own APKPure client) | `--source apkpure\|play`: `details` (version check), `download` (only our APKs; resumable; size and time limits) |
| `teslalinux-appupdate` | `tools/appupdate/teslalinux-appupdate` (Python 3, root) | when to check, the download policy, uploads, verification, install, health check, rollback, state |
| `apk_verify.py` | `tools/apps/` | the full APK Signature Scheme v3/v2 check (`apk-info.py --verify` uses it too) |
| `install-app.sh` | `tools/apps/` | the install itself: keeps the original signed APKs, `<pkg>.old`, switches by rename |
| units + hook | `host/phase1-pios/etc/systemd/teslalinux-appupdate*`, `etc/NetworkManager/91-teslalinux-appupdate` | hourly timer, boot-time install, post-install health check, run on uplink-up |
| nginx | `host/phase1-pios/etc/teslalinux-app.inc` `location = /api/appUpdateUpload` | uploads up to 400 MB, streamed through (no request buffering) |
| settings page | the car page's Settings > **App updates** (its own section of the settings sidebar, `web/`), `/api/appUpdate*` (`services/api/api.py`) | source, switches, status, "Check now", upload with progress and result, Advanced |

## APKPure (the automatic source)

How it is called, from apkeep 1.1.0 (EFF, MIT; crates.io `apkeep`):
* `src/consts.rs`, `APKPURE_VERSIONS_URL_FORMAT`:
  `GET https://api.pureapk.com/m/v3/cms/app_version?hl=en-US&package_name=<pkg>`;
* `src/download_sources/apkpure.rs`, `http_headers`: the request headers `x-cv: 3172501` (the API client
  version apkeep sends), `x-sv` (the device SDK level; we send ATL's 32), `x-abis` (we send `arm64-v8a`)
  and `x-gp: 1`. Our own User-Agent is sent with them.

apkeep finds the download URL with a regex over the answer (`APKPURE_DOWNLOAD_URL_REGEX`). tlplay
decodes the answer, a protobuf message, field by field instead (`src/apkpure.rs`; the layout was read
off the live answer):

```
answer.1.7.2[]              one entry per (version, variant)
  .3.2                      app detail: 4 package, 5 versionCode (a string), 6 versionName
    .24                     asset: 3 SHA-1, 4 size, 8 type ("XAPK" | "APK"), 9 download URL
```

* **Version check**: one GET of that list, about **305 KB** for Waze (90 entries back to 2023; the API
  takes no limit parameter and does not compress). Daily, that is about 9 MB a month; it is allowed on
  LTE, while the download is not unless the setting says so.
* **Variants**: one version comes as several XAPKs with different ABI/density sets. For 5.26.5.0 (vc
  1030744) the three were: armeabi-v7a + mdpi (174.1 MB), arm64-v8a + xxxhdpi (192.6 MB), and arm64-v8a
  + xxhdpi (191.6 MB). Each also holds 17 language splits. **There is no xhdpi variant.**
* **Only our APKs are downloaded.** An XAPK is a ZIP: tlplay reads it as a remote ZIP over HTTP Range
  requests. The end-of-central-directory record and the central directory, a few KB per variant, list
  each variant's APKs. tlplay picks the variant that has base + our ABI + the nearest density + our
  language, then fetches only those members, each into `NAME.part` with resume, inflated when deflated,
  and checked against its CRC-32. XAPK names are mapped to install-app.sh's: `<pkg>.apk` → `base.apk`,
  `config.X.apk` → `split_config.X.apk`.
* **Size cost**: base.apk and the arm64 split are almost the whole XAPK, so skipping the other languages
  saves only about 1 MB: **190.6 MB fetched** of the 191.6 MB XAPK.
* **Density**: Waze's density split only holds pictures. With no xhdpi split, the nearest one is taken:
  ours first, then higher densities nearest first (scaling down looks better than scaling up), then
  lower ones (`density_order`). The set then holds `split_config.xxhdpi.apk`. Android's resource
  lookup picks the closest density present, so the app works. It decodes somewhat larger pictures,
  which the health check covers.
* apkeep makes its users acknowledge the dangers of APKPure as a source (its `USAGE-apkpure.md`): it is
  a third-party re-host, and files could be altered. Here, the signature check against the installed
  app's certificate stands in for that trust.

### Live test, 2026-11-09 (the Mac)

```
tlplay --source apkpure details com.waze
  -> 5.26.5.0 (versionCode 1030744), 3 XAPK variants, 304,865-byte answer, 2.4 s
tlplay --source apkpure download com.waze --version-code 1030744 --out out/appupdate-test/waze-1030744 \
    --split config.arm64_v8a --split 'config.xhdpi|config.xxhdpi|config.xxxhdpi|config.hdpi|config.mdpi' --split config.en
  -> variant a9162f46 (arm64 + xxhdpi); base.apk 91,447,771 + split_config.arm64_v8a.apk 90,645,377
     + split_config.xxhdpi.apk 8,403,995 + split_config.en.apk 118,994 = 190,616,137 bytes, 24 s
python3 tools/apps/apk_verify.py --sdk 32 out/appupdate-test/waze-1030744/*.apk
  -> all four OK, v3, signer 03637f6c5d8f604e6fdb79a6ffbfa578de4e318f8da22fc6106665247f8807d7
     = out/apps/com.waze's (Waze 5.25.0.1, 1030736)
teslalinux-appupdate verify_candidate(that set, installed out/apps/com.waze)
  -> accepted: com.waze 1030744 > 1030736, minSdk 32, splits arm64_v8a + xxhdpi (nearest) + en
resume: a run cut off at 29.6 MB of base.apk, then a second run fetched the other 161.0 MB;
  byte-identical result
```

The first run without the density fallback took the xxxhdpi variant and lacked a density split. That
is why the fallback exists.

## Manual upload

Settings > App updates > **Upload an update** takes a `.apkm` (APKMirror), `.xapk`, `.apks`
(bundletool or SAI), a zip of APKs, or one `.apk`, up to 400 MB.

1. The browser sends the file as the request body with an upload progress bar (XMLHttpRequest). nginx
   passes it through without buffering (`location = /api/appUpdateUpload`, `client_max_body_size 400m`).
2. api.py (user tesla) refuses the request without a Content-Length or above the limit (413), then
   streams the body straight into `sudo -n teslalinux-appupdate receive-upload --size N --name FILE`.
   The tesla user never writes the file anywhere. The root side stores exactly N bytes in
   `/var/lib/teslalinux/appupdate/upload/` (root 0700).
3. Unpack: only `*.apk` members (at most 1 GB unpacked), under our own file names, so a path inside the
   archive never becomes a path on the Pi. Each APK is named by its **manifest** (no `split` attribute
   = base; `split="config.X"` = `split_config.X.apk`), not by its file name, which differs between
   formats: `.apkm`/SAI use `base.apk` + `split_config.X.apk`, XAPK uses `<pkg>.apk` + `config.X.apk`, and
   bundletool `.apks` uses `splits/base-master.apk` + `splits/base-X.apk`. Kept: the base, our ABI, our
   density (else the nearest) and our language. bundletool's `standalones/` and feature splits are left out.
4. Then the same checks as a download (below). The answer goes to the page right away:
   * **same version** as installed: "same version, nothing to do";
   * **older**: not installed;
   * **refused** (signature, signer, splits, ...): the reason. A refused upload marks no version bad;
     the genuine file can still be uploaded;
   * **staged**: it installs when the car is not connected or at the next start, exactly like an
     automatic update, with the health check and rollback.

   The bundle and its unpacked copy are deleted in every case.

## How it works

1. **Check.** `teslalinux-appupdate.timer` runs `teslalinux-appupdate run` every hour (plus 10 minutes
   after boot), and NetworkManager's dispatcher hook starts it when an uplink comes up. The source is
   asked only when it is due: once a day (24 h +-10 %, plus the timer's random delay), when a retry's
   backoff has expired, or on "Check now". With the manual source, or Google Play while it is not
   enabled, nothing is asked.
2. **Download policy.** If the source has a newer version that is not marked bad, the download (about
   190 MB) starts only when NetworkManager calls the uplink unmetered (`Metered` = no or guess-no:
   Wi-Fi, Ethernet). An LTE modem is guess-yes, and unknown counts as metered. "Allow updates over LTE"
   lifts that. Otherwise the run records "waiting for Wi-Fi or Ethernet", and the next Wi-Fi uplink
   (the dispatcher hook) continues.
3. **Download** into `/var/lib/teslalinux/appupdate/staging/<versionCode>/`, resumable across runs.
   Limits: 600 MB per file, 1 GB in total, a 90-minute deadline, and 2 minutes without data counts as a
   stall. Splits: `config.arm64_v8a`, the density (xhdpi, else the nearest), and the device language
   (Settings > "Language & region", `/etc/teslalinux/locale.env`; English by default; Hebrew also tries
   Java's legacy `config.iw`).
4. **Verify before anything is installed.** Every check must pass:
   * every APK passes the full APK Signature Scheme v3 check (else v2), the same one Android's installer
     runs: the signature over the signed data, the certificate's key equals the signer's, and the
     chunked SHA-256/512 content digest over the whole file. A truncated, corrupted or modified file is
     refused, not just one with a foreign signer. Supported: RSA (PKCS#1 and PSS), ECDSA
     P-256/384/521, and DSA (Waze's key is DSA-SHA256);
   * the same package as the installed app, signed with the same certificate (SHA-256), in every split;
   * `versionCode` strictly higher than installed, and the same in every split;
   * `minSdk` at most the SDK level ATL reports (`ATL_SDK_INT`, 32);
   * each `split_config.<q>.apk` is the split its manifest says it is; the ABI split holds
     `lib/arm64-v8a/`; a density split is there; our language split is there when the source has one.

   A downloaded version that fails is deleted and marked bad, and never downloaded again; the next
   newer one is tried normally.
5. **Install only when Waze is not in use**: either at the next boot, before the app starts
   (`teslalinux-appupdate-boot.service`, `Before=teslalinux-atl`; skipped outright when nothing is
   staged), or during a run while the car is not connected. Not connected means no station on the
   hotspot interface (`iw dev wlan0 station dump`) and no TCP connection to the display or touch sockets
   (9091, 9999), checked twice, 20 s apart. The install stops the app, copies the app's data to
   `data-before/` (skipped, and logged, when the disk lacks room), and runs `install-app.sh` as `tesla`
   with the density and language actually present (it keeps `com.waze.old` and switches by rename).
   Then it deletes the ART AOT files compiled from the old base.apk and starts the app.
6. **Health check.** The first launch must log Waze's `MainScreenVisible` within 900 s (the first start
   AOT-compiles the new base.apk). Three crash restarts before that fail it at once. For that launch,
   `/run/teslalinux/appupdate-health.env` sets `ANDROID_LOG_TAGS=*:d` (the car runs with `*:w`; the
   marker is known to show at the VM's debug level). `teslalinux-atl.service` reads the file.
   After a boot-time install, `teslalinux-appupdate-health.service` watches the journal; after a power
   cut the window starts again.
7. **Rollback** on failure: the app is stopped, `com.waze.old` comes back with the data copy, the ART
   cache is cleared, the app starts again, the version is marked bad, and the page shows a notice.
   `teslalinux-appupdate rollback` does the same by hand.

Failures back off exponentially: 10 min, 20, 40, ... up to 24 h, +-20 % jitter. "Check now" ignores the
backoff. The page shows **"Update server unavailable since <time>"** from the first failed check until
one succeeds. Nothing in the updater can stop the app from running or the Pi from booting: the
installed Waze stays whenever anything goes wrong.

## Google Play via a token dispenser (off by default)

Kept for a dispenser that admits this Pi. The flow is Aurora Store's anonymous login, read from its
source (AuroraStore `89933d4a2c138a3452486c5eb6e5c2e0df578470`, gplayapi
`2f03e8babee8adf791d4f3a3b17c268a704be76c`, both 2026-11-02):
* `POST` to the dispenser (`Constants.kt` `URL_DISPENSER`). The body is the device profile as flat JSON
  (`AuthProvider.kt` `buildAnonymousAuthData`/`postToDispenser`; gplayapi `internal/Serializers.kt`
  `PropertiesSerializer`). The answer is `{"email", "authToken"}` (`data/model/Auth.kt`).
* That AUTH token goes to gpapi's `set_auth_token()` + `login()` (checkin, uploadDeviceConfig, toc).
  Then `details()`, and `get_download_info()` for the splits by name.
* The login is cached in `/etc/teslalinux/play/auth.json` (root 0700/0600; never printed) until Play
  rejects it.
* Device profile `px_tablet` (Pixel Tablet, Android 13: SDK 33, 320 dpi = xhdpi, arm64-v8a only),
  `tools/appupdate/device.properties`, identical to gpapi's and gplayapi's.

**Status**: Aurora's dispenser answers HTTP 403 (Cloudflare) to tlplay. The source is listed as
"Google Play via Aurora (unavailable)" until Advanced > "Google Play via a token dispenser" is on, and
then it needs a dispenser address that accepts the Pi.

## ToS and risks

* Unofficial clients and re-hosts (APKPure, apkeep, Aurora, this tool) are outside Google Play's terms.
  Any source can block, change its API (tlplay then reports "format changed") or go offline. The car
  keeps working with the installed Waze, and the manual upload stays.
* **A new Waze may use Android APIs that ATL lacks.** This is the main risk. Three things guard
  against it: `minSdk` is checked against ATL's SDK level, the health check requires
  `MainScreenVisible`, and a failed check rolls back automatically and marks the version bad. Breakage
  further in (navigation, search) is not caught: use `teslalinux-appupdate rollback`, then turn
  automatic updates off or pick "Manual uploads only".
* **The density fallback** (APKPure, no xhdpi): the closest density is used. That is Android's normal
  behaviour, but it was not the set tested in the VM.
* **Data migration** is covered by the data copy that the rollback restores.
* **Disk**: during an update the Pi holds the staged set plus the installed one and `.old` (~190 MB
  each), the data copy, and during an upload the bundle and its unpacked APKs.
* **LTE data**: about 300 KB per daily check; downloads (~190 MB) are off over LTE by default.
* **A new Waze signing key** (key rotation) is refused like on Android without a rotation proof. It
  needs a manual install with `install-app.sh` on the Pi.

## Settings and status

* Settings > **App updates**: **Update source** (APKPure (automatic) / Manual uploads only / Google Play
  via Aurora (unavailable unless enabled)), **Update Waze automatically**, **Allow updates over LTE**,
  the status ("Update server unavailable since ...", a verified update waiting for the car to
  disconnect, the running health check, the last result, the last notice), **Check for an update now**,
  **Upload an update** (progress, then the verification result), and **Advanced: Google Play via a token
  dispenser** with its dispenser address.
* API (new routes; the upstream ones are unchanged): `GET /api/appUpdate`, `POST /api/appUpdateSettings`
  (JSON with any of `source`, `autoUpdate`, `allowMetered`, `playEnabled`, `dispenserUrl`; 500 on
  anything invalid), `POST /api/appUpdateCheck`, `POST /api/appUpdateUpload?name=FILE` (the body; 411
  without a length, 413 over 400 MB; answers `{ok, state, message}`).
* sudoers (`/etc/sudoers.d/teslalinux-appupdate`): tesla may run `teslalinux-appupdate kick --force`
  and `teslalinux-appupdate receive-upload --size * --name *`, nothing else.
* On the Pi: `teslalinux-appupdate status`, `/var/log/teslalinux/appupdate.log`,
  `/var/lib/teslalinux/appupdate/{state,upload}.json`.

| Knob (`/etc/teslalinux/appupdate.env`) | Default |
|---|---|
| `APPUPDATE_PKG`, `APPS_DIR` | `com.waze`, `/var/lib/teslalinux/apps` |
| `APP_ABI`, `APP_DENSITY`, `APP_LANG`, `ATL_SDK_INT` | `arm64_v8a`, `xhdpi`, the device language, `32` |
| `APPUPDATE_CHECK_INTERVAL`, `APPUPDATE_BACKOFF_BASE`, `APPUPDATE_BACKOFF_MAX` | 86400, 600, 86400 s |
| `APPUPDATE_MAX_FILE_BYTES`, `APPUPDATE_MAX_TOTAL_BYTES`, `APPUPDATE_DOWNLOAD_DEADLINE` | 600 MB, 1 GB, 5400 s |
| `APPUPDATE_MAX_UPLOAD` (and api.py's `TESLALINUX_APPUPDATE_MAX_UPLOAD`, nginx `client_max_body_size`) | 400 MB |
| `APPUPDATE_HEALTH_TIMEOUT`, `APPUPDATE_HEALTH_MARKER`, `APPUPDATE_HEALTH_LOG_TAGS`, `APPUPDATE_HEALTH_MAX_RESTARTS` | 900 s, `MainScreenVisible`, `*:d`, 3 |
| `APPUPDATE_DISPLAY_PORTS` | `9091,9999` |

## Why this tool

tlplay is one small Rust binary (3 MB, no runtime) with two sources:
* **APKPure** is tlplay's own client (`src/apkpure.rs`, about 550 lines with its tests): the request apkeep makes, a
  real decoder for the answer, and reading the XAPK as a remote ZIP so only our APKs are fetched,
  resumable and CRC-checked. apkeep itself downloads the whole XAPK in one piece and cannot resume it,
  and its version list (`-l`) gives version names only (a regex over the answer), not versionCodes.
* **Google Play** goes through the `gpapi` crate (EFF): `set_auth_token`, `login`, `details`,
  `get_download_info`. whyorean's gplayapi (Kotlin) would need a JVM on the Pi: about 200 MB, ~100 MB of
  RAM, and a second of start-up per check.

## Building and installing

```sh
# on the Pi or in the Debian arm64 VM (tools/debvm): native arm64 build
apt-get install -y cargo rustc libssl-dev pkg-config
tools/appupdate/build.sh                 # cargo build/test --release --locked -> target/release/tlplay
sudo tools/appupdate/install.sh          # binaries, profile, units, NM hook, sudoers, dirs
# on the Mac (development): brew install rust && tools/appupdate/build.sh
```

`Cargo.lock` pins every dependency. `.cargo/config.toml` keeps them at versions that build with
trixie's rustc 1.85. TLS goes through OpenSSL (`native-tls`; libssl3 is in every Pi OS image). A cross
build on the Mac would need rustup's `aarch64-unknown-linux-gnu` target and a target OpenSSL, so the
supported path is the native build.

**Installer.** The one-line installer (out/pios-plan/PLAN.md, step 5) runs, after the app is installed
with `install-app.sh`: build or fetch the arm64 `tlplay`, then `tools/appupdate/install.sh [--tlplay PATH]`.
That installs the binaries, the device profile, the apps tools, the units, the NetworkManager hook,
`/etc/teslalinux/play` (0700), the sudoers file and `teslalinux-atl.service` (which reads the health env
file). The rendered nginx site already has the upload location. Nothing has to be configured: the
default is APKPure, automatic, Wi-Fi only.

## Tests

`tools/appupdate/test/run.sh` runs without a network, an account or a VM:
* `test_appupdate.py` (39 tests) uses the real Waze 5.25 set in `out/apps/com.waze/` as the installed
  app:
  * verification: accepted when newer; refused for an equal or lower versionCode, a copy re-signed with
    a throwaway RSA key (valid v2, wrong signer), one foreign split, a flipped byte, a truncated split,
    each missing split, the wrong ABI, a misnamed split, minSdk above 32, or another package; the
    density fallback and the language rules;
  * decisions: metered, backoff, the daily-check rule, the `/proc/net/tcp` parser, the dispenser URL
    rule, the health verdict, the device locale;
  * the state machine (fake tlplay, fake systemctl, the real install-app.sh): source down -> backoff ->
    recovery, LTE waits, verification failure -> marked bad, car connected waits, idle install ->
    health ok, health failure -> rollback with data, boot install -> health service, manual rollback,
    the Play source refused unless enabled, manual-only, and no token in the status;
  * uploads: out/apps/com.waze as an `.apkm` gives "same version, nothing to do"; the re-signed
    `.apkm` is rejected (no version marked bad); an XAPK-named newer set is staged and installed when
    idle; a bundletool `.apks` is staged; a zip without APKs and a non-ZIP are refused; a set without
    the ABI split is refused; the size limit holds.
* `services/api/test/test_api.py`: the status shape, the settings fields and their validation, the
  kick, and an upload streamed byte-exact into the helper (413 from the headers alone).
* `cargo test`: the APKPure answer decoder, XAPK member names, Java-properties unescaping, and the Play
  profile.

Live (network, no account): the commands in "Live test" above.
