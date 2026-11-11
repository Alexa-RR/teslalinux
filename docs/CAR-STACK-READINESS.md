# Car stack readiness

Everything between the Tesla browser and the app: hotspot, hijack DNS, NAT, nginx, the launcher page
(`web/`), the WebSocket daemons (`services/`), the certificate, and the host plumbing that starts them.
State as of 2026-11-09.

**Targets.** The **primary target is stock Raspberry Pi OS Lite 64-bit** (Debian trixie, systemd,
NetworkManager) with everything installed natively by one installer (`host/phase1-pios/provision.sh`
today; the one-liner wrapper is a later step, see "Future"). The **Alpine image**
(`tools/image/build-alpine-pi.sh`, OpenRC, `host/alpine/`) is the secondary target and the test VM's
platform. Both render their host configuration from the same templates with the same renderer
(`host/teslalinux-render`), so a fix lands in both.

## 1. How to check it

| What | Command | Result now |
|---|---|---|
| Web stack on the Mac (nginx + api + gps + audio, the car's rendered site) | `host/local/web-local.sh start`, then `host/local/web-local.sh check` | 63/63 checks pass |
| Config API against upstream's contract (in-process) | `python3 services/api/test/test_api.py` | 7/7 pass |
| Shared certificate: acceptance per slot, install, fetch, active.json switching and fallback, rollback, CRL, issuer decisions (throwaway CA, local HTTPS) | `sh host/tls/test/run.sh` (also `TLS_TEST_SH=dash dash host/tls/test/run.sh`) | 66/66 pass under sh and dash |
| Whole chain with Waze in the test VM | `ATL_VM_FWD_CAR=1 tools/atl-vm/vm-mac.sh start` (fresh start), then `tools/atl-vm/carstack.sh up`, `carstack.sh check` | written, **not run yet** (shared VM) |
| Syntax | `sh -n`/`bash -n` on every script, `python3 -m py_compile`, workflow YAML parsed | clean |

Open the local stack in Chrome (accept the self-signed certificate once):
```
open -na "Google Chrome" --args --user-data-dir=/tmp/teslalinux-chrome \
  --host-resolver-rules="MAP car.alexahub.dev:443 127.0.0.1:8443, MAP *.car.alexahub.dev:443 127.0.0.1:8443" \
  https://car.alexahub.dev/
```
For the VM stack use port 9443 instead of 8443 (`carstack.sh` header). Alternative to the resolver
rules: `/etc/hosts` with `127.0.0.1 car.alexahub.dev fullscreen.car.alexahub.dev` and
`https://car.alexahub.dev:8443/`.

## 2. Upstream parity (Tesla Android's web setup, translated to nginx / dnsmasq / nftables)

Upstream sources: `upstream/android-vendor-tesla-android/services/lighttpd/lighttpd.conf` (L),
`.../www-redirect/index.html`, the netd patch series
`upstream/android-raspberry-pi/patches-aosp/system/netd/000{1..5}*.patch` (N1..N5), the configuration
manager `upstream/android-external-tesla-android-configuration-manager/tesla-android-configuration-manager.cpp`
(C), build properties `upstream/android-vendor-tesla-android/vendor.mk` (V). Ours: the templates in
`host/phase1-pios/etc/`, rendered by `host/teslalinux-render`. "Checked" = asserted by `host/local/check.py`.

| # | Upstream | Ours | Status |
|---|---|---|---|
| 1 | TLS on 443, session cache (L:53-59) | `teslalinux-tls.inc`: TLS 1.2/1.3, own `shared:teslalinux` cache zone | done, checked (TLS 1.3) |
| 2 | Cipher list `AES128-SHA` (L:57) | ChaCha20-Poly1305 first, ECDHE-AES-GCM, AES128-SHA kept for RSA placeholders: our key is ECDSA, which AES128-SHA cannot use, and the Pi 4 has no AES instructions | deliberate deviation, checked (server prefers ChaCha20) |
| 3 | Separate certificate for `fullscreen.` (L:65-68) | one certificate with SANs `car`, `www.car`, `fullscreen.car`, `www.fullscreen.car` | done, checked (SAN set) |
| 4 | Host block: teslaandroid.com hosts + 192.168/10/172.16-31 IP literals (L:71) | app server: `DEVICE_HOST`, `*.DEVICE_HOST`, the same private-range regex | done, checked (6 hosts, 172.15.x excluded) |
| 5 | COEP `credentialless` + COOP `same-origin` on every response of those hosts (L:72-75) | server-level `add_header ... always`, repeated in `teslalinux-nocache.inc` where locations add headers | done, checked (`/`, `/api`) |
| 6 | Non-/api: `Cache-Control: no-store, no-cache, must-revalidate, max-age=0`, `Pragma: no-cache`, `Expires: 0`, ETags off (L:77-86) | `teslalinux-nocache.inc`, `etag off` | done, checked (also on 403) |
| 7 | WebSocket upgrades: touchscreen 9999, gps 9998, audio 8080, display 9091 (L:88-114) | `teslalinux-app.inc` + `teslalinux-ws.inc` (Upgrade/Connection, 1 h timeouts, no buffering, nodelay) | done; gps and audio checked (101); display/touch need tacomp (VM: `carstack.sh check`) |
| 8 | `/stream` -> 9090 (L:116-118) | **omitted**: nothing in `web/js` (nor upstream's frontend or its `beta/` copy) requests `/stream`, and no upstream daemon listens on 9090 any more (the old MJPEG-over-HTTP endpoint, replaced by `/sockets/display`); a proxy to a closed port would only produce 502s. `/stream` is a plain 404 | documented, checked (404) |
| 9 | `/api` -> 8081 with 1 s expiry, URL regex `^/api(/.*)?$` (L:120-123) | `location = /api`, `location /api/`, `expires 1s` (Expires + `Cache-Control: max-age=1`) | done, checked |
| 10 | mimetype map incl. `.wasm application/wasm`, `.mjs` (L:31-51) | `teslalinux-mime.inc`, same map, `charset=utf-8` on the text types | done, checked (7 types) |
| 11 | `url.access-deny` `~ .inc .md .yml .ini` (L:28) | regex location, 403 | done, checked |
| 12 | `server.error-handler-404 = /404.html` (L:14; upstream ships no such file) | `error_page 404 /404.html` + `web/404.html` | done, checked |
| 13 | Base server for every other host: www-default, no extra headers (L:13-19) | `default_server` on 80 and 443 with `teslalinux-static.inc` | done, checked (www.teslamotors.com/.tesla.com/.tesla.cn, no /api proxy) |
| 14 | HTTP on 80 serves the same app as 443 (no redirect; the host blocks apply to both ports) | app server also on 80 | done, checked; plain http has no secure-context APIs, the car uses https |
| 15 | connman hosts: `X-ConnMan-Status: online`, `X-Cache: Hit from cloudfront` (L:131-144) | own server block, `teslalinux-static.inc` + both headers | done, checked; `web/online/{status.html,connectivity_check.txt}` copied byte-for-byte from upstream's www-default |
| 16 | youtu.be: www-redirect page, 3 s `setTimeout` then `https://fullscreen.device...`, manual button (L:126-129, www-redirect/index.html) | `host/phase1-pios/etc/www-redirect/index.html`: upstream's markup, target `https://fullscreen.DEVICE_HOST` | done, checked (youtu.be, www.youtu.be) |
| 17 | Frontend behaviour on the fullscreen host | none: upstream's frontend has no host-specific code (it hardcodes `device.teslaandroid.com` endpoints; ours derive them from `location.host`, so `fullscreen.` talks to itself). The difference is the browser: Tesla shows the page full-screen when reached through the youtu.be launcher | verified by reading `web/js`, upstream `frontend/js`, `main.dart.js` |
| 18 | dnsmasq: device names -> hijack IP (N1:46-50, N3 removed app.teslaandroid.com) | `address=` for `DEVICE_HOST`, `www.`, `fullscreen.`, `www.fullscreen.` -> `HIJACK_IP` (192.0.2.1) | done (rendered; dnsmasq run needs hardware) |
| 19 | youtu.be + www.youtu.be -> 216.58.215.78 (N2:20-21) | same two lines | done |
| 20 | DNAT hijack IP tcp+udp -> Pi, SNAT Pi -> hijack IP (N1:95-98) | nftables `meta l4proto { tcp, udp } dnat`, matching `snat`, same order | done (rendered; needs hardware) |
| 21 | youtu.be :6969 -> Pi :80, tcp+udp, plus its SNAT (N2:39-42) | same; upstream's SNAT order makes the youtu.be SNAT unreachable, ours keeps the order and says so | done |
| 22 | Offline mode (`offline-mode.is_enabled == 1`): connman.vn.tesla.services, www.teslamotors.com, www.tesla.com, connman.vn.cloud.tesla.cn, www.tesla.cn (N1:59-65, N4 removed both hermes-prd hosts, N5 added www.tesla.com) | `OFFLINE_HOSTS` in the renderer, to the hijack IP. hermes-prd is not spoofed because upstream's final state does not (N4: "not required anymore") | done |
| 23 | Telemetry block (`telemetry.is_enabled == 0`): 7 hosts (N1:68-75) | `TELEMETRY_HOSTS`, same list | done |
| 24 | Firmware-download block (`tesla-firmware-downloads == 0`): va.teslamotors.com (N1:78-79) | `FIRMWARE_HOSTS` | done |
| 25 | Defaults: offline 1, telemetry 1, firmware downloads 1, band 2 / ch 44 / width 3, GPS 0, browser audio 0 (V:68-89) | `tlprops.UPSTREAM_DEFAULTS`; env switches `OFFLINE_MODE`, `OFFLINE_MODE_TELEMETRY`, `OFFLINE_MODE_FIRMWARE_DOWNLOADS` | done, API-tested |
| 26 | Settings through the config manager: `POST /api/offlineModeState`, `offlineModeTelemetryState`, `offlineModeTeslaFirmwareDownloads`, `softApBand/Channel/ChannelWidth/State` as text/plain property values (C:493-590), applied when the hotspot next starts (the frontend says "during the next system startup") | `api.py` stores them under upstream's property names; `teslalinux-netconfig` re-renders hostapd/dnsmasq at boot when they changed | done, API-tested; boot re-render needs hardware |
| 27 | `softap.is_enabled` is stored but not honoured (C:328-332 has the check commented out) | same: stored, the hotspot always starts (turning it off would lock the car out of the page that turns it on) | parity |
| 28 | Config API: configuration (C:394), displayState GET/POST (C:591, C:617), deviceInfo (C:358), health (C:386), openUpdater (C:349), preflight 204 (C:160), CORS headers (C:673) | `services/api/api.py`, same shapes and status codes. openUpdater answers 501: there is no system-update screen on this platform | done, API-tested; deviceInfo hardware fields (temperature, serial, modem, CarPlay) need hardware |

## 3. Gaps found and fixed

| Gap | Fix | Commit |
|---|---|---|
| `/api/configuration` returned our own nested shape; the frontend reads flat `persist.tesla-android.*` keys, so GPS and audio showed as on and every setting was wrong | `api.py` rewritten to the configuration-manager contract; settings in `tlprops.py` | eab450e |
| The frontend's setting POSTs are `text/plain` (`"1"`, `"44"`); `api.py` answered 404 to all of them, so every toggle failed | text routes store the raw value, reply `OK` | eab450e |
| `api.py` wrote `/etc/teslalinux/state.json` as the unprivileged `tesla` user: every POST died | state in `/var/lib/teslalinux/state` owned by tesla; root work through one sudoers command | eab450e, 49cadd0 |
| GPS feed: Python writes unknown speed/heading as `nan`, ATL's `Double.parseDouble` only accepts `NaN`: the browser sends `"null"` when standing still, so the feed dropped on every such fix | `NaN`, range checks, no crash on a bad timestamp, loopback binds | eab450e |
| gps/audio logged tracebacks whenever the car dropped off the Wi-Fi (no close frame) | handled as the normal case | 49cadd0 |
| audio: a client could get a fragment before the init segment; one slow client stalled all | init queued before joining; per-client queues capped at ~1 s; ffmpeg only while someone listens | eab450e |
| tacomp's WebSockets listened on every interface (any hotspot client could inject touches without TLS) | `TACOMP_BIND`, the services set 127.0.0.1 | eab450e |
| H.264: a client joining mid-GOP waited for the next IDR, forever on a static screen; the frame request ran on the libwebsockets thread (wlroots is not thread-safe) | IDR forced per new client, the held last buffer re-encoded; requests moved to the main thread | eab450e (**not compiled yet**) |
| Encoder names: services set `mjpeg`/`h264`, tacomp only knows `m2m-*` (H.264 never selected) | `m2m-jpeg` / `m2m-h264` everywhere; the API maps renderer -> encoder | eab450e |
| Alpine nginx: our `ssl_session_cache shared:SSL:1m` clashes with Alpine's `shared:SSL:2m` (nginx -t fails); `$connection_upgrade` defined twice | own zone and variable names | 49cadd0 |
| Offline mode was blanked on the Alpine image (`@OFFLINE_BLOCK@` -> empty); Pi OS spoofed to the private Pi address, included hermes, missed www.tesla.com/.cn, telemetry blocks unsupported | one renderer with upstream's final host lists and switches, to the hijack IP | 49cadd0 |
| youtu.be answered an instant 302 to `DEVICE_HOST` (not upstream's 3 s page, not the fullscreen host) | upstream's page | 49cadd0 |
| connman probes got 204 with only one header | 200 + both headers + upstream's `online/` files | 49cadd0 |
| hostapd: fixed `HT40+` and centre index 42, so channels 36/149/157 or 2.4 GHz from the frontend would break the AP | computed per band/channel/width | 49cadd0 |
| ATL's audio went to the Pi's own ALSA device; WirePlumber picked hardware sinks over the null sink | `pipewire-alsa` + `/etc/asound.conf` default -> PipeWire, hardware monitors off, sink priority | 49cadd0, bbf5288 |
| Pi OS units used `/run/user/%U` (in system units `%U` is root's uid): PipeWire paths of ATL, audio and Waydroid all pointed at `/run/user/0` | PipeWire, WirePlumber, pipewire-pulse as system units for tesla in `/run/tesla` | bbf5288 |
| Pi OS trim disabled `systemd-timesyncd` with no replacement: no NTP at all without an RTC | timesyncd kept | bbf5288 |
| Pi OS installed `nginx-light` (gone in trixie) and `libwlroots-0.18` (tacomp needs 0.20) | `nginx`; the wlroots question is a blocker below | bbf5288 |
| Alpine: `mdev` and `udev` both enabled; no `render` group (tacomp's GLES2 renderer cannot open the render node); `dhcpcd` never enabled (no LTE uplink) | eudev only (+ udev-settle so wlan0 exists before hostapd), render group, dhcpcd on uplinks with `denyinterfaces wlan0` | 49cadd0 |
| Alpine boot: `config.txt` from Pi OS pointed at `kernel8.img`; Alpine's linux-rpi installs `vmlinuz-rpi` | `kernel=vmlinuz-rpi` + `initramfs initramfs-rpi followkernel` | 49cadd0 |
| Certificate renewal ran weekly at 03:00 (a car Pi is off then) with Cloudflare credentials on the Pi | cloud renewer, no credentials on the Pi (section 4) | 0d3a9cd, 99651e6 |
| The frontend posts its display state on every page load; the old helper restarted the compositor and the app each time | helpers restart only when something changed | 49cadd0 |

## 4. Certificate

### Design

* **One certificate for every car.** Every teslalinux car serves `car.alexahub.dev`,
  `www.car.alexahub.dev`, `fullscreen.car.alexahub.dev` and `www.fullscreen.car.alexahub.dev`. The
  Tesla browser only opens the page with a publicly trusted certificate, and TLS ends on each Pi, so
  every Pi holds the same certificate **and private key**. Tesla Android does the same (its images
  ship `device.teslaandroid.com`'s `fullchain.pem` and `privkey.pem`); teslalinux adds automatic
  renewal, a standby certificate and checks on the Pi. Per-car names and per-user domains were
  rejected: every user would need a domain, a DNS token and an issuer before the first start.
* **Two slots.** `current` holds the four names; `standby` the four names plus
  `standby.car.alexahub.dev`. A different name set means Let's Encrypt's duplicate-certificate limit
  (5 per week per exact set) counts each slot on its own, and revoking one leaves the other.
  `active.json` names the slot the Pis serve.
* **Issuer: `.github/workflows/tls-certs.yml` + `host/tls/renew.sh`** in this repository: a
  GitHub-hosted runner, environment `tls`, only on `main`, every 3 hours and on demand (`check`,
  `issue-current`, `issue-standby`, `issue-both`; `staging` runs the whole flow against Let's Encrypt
  staging and publishes nothing). Each run, per slot, it reads what is published (R2), runs the Pi's
  checks on it, looks for the leaf's serial in its CRL (the CRL Distribution Points URL; the CRL must
  verify against the chain's issuer; Let's Encrypt ended OCSP in 2025), and asks the CA's ARI endpoint
  (RFC 9773) for the suggested renewal window. A slot is re-issued when it is missing, fails a check,
  is revoked, has less than a third of its lifetime left, or its ARI window has opened (a window in
  the past is how a CA asks for an early renewal). When `current` is unusable and `standby` is fine,
  `active.json` moves to `standby` first, `current` is re-issued, then `active.json` moves back; the
  unusable slots are always issued first, so both are never left invalid when it can be avoided.
  Every decision is written to the job summary; a failed issuance fails the run (GitHub mails the
  maintainer).
* **A fresh key per issuance**, ECDSA P-256, generated on the runner and never reused (the key is
  public in effect, and Let's Encrypt refuses keys that were reported compromised). Then the CSR,
  DNS-01 on Cloudflare through acme.sh (pinned by commit and sha256), and **the Pi's own acceptance
  checks** (`teslalinux-tls verify`: key matches the leaf, exact names, chain to the runner's system
  roots with `-purpose sslserver -x509_strict`, validity, newer than the published pair) before
  anything is published.
* **Published** (R2 bucket `teslalinux-certs`, custom domain `certs.alexahub.dev`, prefix `car`):
  `car/<slot>/fullchain.pem`, `privkey.pem`, `meta.json` (slot, names, serial, issuer,
  notBefore/notAfter, chain and SPKI sha256, issuedAt); the pair before under `car/<slot>-previous/`;
  `car/active.json` = `{"slot": "current" | "standby", "reason": ..., "updated": ...}`.
* **On the Pi: `teslalinux-tls`.** `fetch` downloads `active.json` and both slots over HTTPS (system
  CAs, 64 KiB limit, timeouts) and installs a slot's pair only if: PEM only (1 to 5 certificates;
  exactly one private key), the key matches the leaf, the SANs are exactly the slot's set,
  digitalSignature + serverAuth, the chain verifies against the system store (`-purpose sslserver
  -x509_strict`), notBefore <= now + 10 min, notAfter > now, and notBefore later than the installed
  pair of that slot. A clock before the image build time defers instead of judging. Slots live in
  `/etc/teslalinux/tls/slots/<slot>/` (root, directories 0700, keys 0600). nginx serves
  `/etc/teslalinux/tls/fullchain.pem` + `privkey.pem`: a copy of the slot `active.json` names, or of
  the other slot when that one is missing, expired or invalid on this Pi, and a self-signed
  certificate only when there is no trusted pair at all. Every swap: temp files, fsync, rename,
  `nginx -t`, reload; on failure the previous files and the slot come back. Decisions go to
  `/var/log/teslalinux/tls.log` and `/var/lib/teslalinux/tls-state/tls.json`.
* **Works offline from the first start.** `tools/debvm/pack-dist.sh` downloads both pairs and
  `active.json` when a release is built, checks them with `teslalinux-tls verify` and bundles them
  (`share/teslalinux/tls/`); the installer copies them to `/usr/share/teslalinux/tls` and
  `teslalinux-tls init` installs them. If that download fails the release still builds (with a
  warning) and the Pi fetches at its first uplink. The Alpine image takes the same directory as
  `TLS_BUNDLE_SRC`.
* **When it runs (Pi OS):** `teslalinux-tls-fetch.timer` (1 min after boot, then every 6 h, 15 min
  random delay), the NetworkManager dispatcher hook `90-teslalinux-tls` when an uplink comes up (not
  within 10 min of a success), and `teslalinux-tls-fetch.service`'s `Restart=on-failure` with
  `RestartSteps`/`RestartMaxDelaySec` as exponential backoff from 1 min to 6 h. Never ordered before
  nginx or anything on the boot path. (Alpine: the same tool as an OpenRC daemon, dhcpcd exit hook.)
* **Visibility:** `GET /api/certificate` (served slot, active slot, expiry, days left, per-slot
  status, last result, self-signed or trusted); the launcher shows a small dismissable notice when
  fewer than 14 days are left, the last update failed or was rejected, or the certificate is the
  self-signed fallback (`web/js/core/cert-warning.js`). There is no write route for anything TLS.
* **Self-hosting** is optional: run the same workflow in your own fork with your own environment,
  then set `DEVICE_HOST` and `TLS_BASE_URL` (installer: `--set`; release builds: `pack-dist.sh
  --cert-url`). Defaults: `car.alexahub.dev`, `https://certs.alexahub.dev/car`.

### Threat model

The private key is effectively public: it is in every release and on every Pi. The certificate is
what the Tesla browser requires to open the page, not proof of whose Pi it is.

| Event | Impact | What limits it / what to do |
|---|---|---|
| Someone takes the key (from a release or a Pi) | They can present a valid certificate for the car names. | The names resolve publicly to `192.0.2.1` (TEST-NET-1, never routed) and reach a Pi only inside its own hotspot. Misusing the key needs control of the victim's network and DNS as well (for example a rogue hotspot the car joins), and then it impersonates only the car page (launcher, app stream, the browser's GPS position), no other site. Tesla Android's shared certificate has the same exposure. |
| Revocation griefing: a key holder revokes the certificate through ACME (key possession is enough; reason keyCompromise also blocks that key) | `current` becomes revoked. | The `standby` slot keeps a valid certificate; the issuer sees the revocation within 3 h (CRL, ARI), moves `active.json` to `standby`, re-issues `current` with a fresh key and moves back. Pis follow at their next fetch (6 h, or the next uplink). Chromium-based browsers check revocation of ordinary certificates only through CRLSets, so a revoked certificate often keeps working in the car meanwhile. |
| Sustained griefing (revoking each new certificate) | Each slot can be re-issued 5 times per week (duplicate-certificate limit per name set). | The two slots double that; a Pi keeps serving its last good pair while nothing newer validates. If it ever becomes real: add more standby name sets, shorten the check interval, report the abuse to Let's Encrypt. |
| Leaked Actions secret: ACME account key | An account that can request certificates. | DNS-01 still needs the DNS token; rotate the account key (new key -> new account URI -> update the CAA record). |
| Leaked Actions secret: Cloudflare DNS token | DNS edits in the scoped zone until the token expires. | The CAA `accounturi` pins issuance to our ACME account; in hardened mode the token cannot touch alexahub.dev at all; the token expires; CT monitoring reports any issuance. |
| Leaked R2 token, or the download URL / CDN compromised | Can publish any file. | The Pi accepts only a trusted, exactly named, current pair whose key matches and that is newer than its own. The worst case is withholding renewals (the Pi keeps its pair; the launcher warns at 14 days) or flipping `active.json` between two valid slots (harmless). |
| Push access to `main` | Can change the workflow to exfiltrate secrets. | The trust boundary is the repository: 2FA, branch protection (no force pushes), the `tls` environment limited to `main`, GitHub-authored actions pinned by SHA, approval required for workflows from outside contributors. Pull requests from forks never get the environment's secrets. |

### Setup (maintainer, once; exact steps)

1. **ACME account key** (on your own computer, not a Pi):
   `openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out acme-account.key`; paste the
   whole file into the secret below, then keep the file offline (password manager) or delete it.
2. **Cloudflare DNS token** (default mode): Cloudflare dashboard -> My Profile -> API Tokens -> Create
   Token -> Custom token: Permissions `Zone` / `DNS` / `Edit`; Zone Resources `Include` / `Specific
   zone` / `alexahub.dev`; TTL: an end date (e.g. one year; put a reminder in the calendar); no IP
   filter (GitHub runners have no fixed IPs). Zone ID: the alexahub.dev overview page, right column.
   **Hardened mode** (the token cannot edit alexahub.dev at all): put a separate validation zone on
   Cloudflare (`<validation-zone>`), create the token for that zone only, and add in alexahub.dev
   five CNAMEs, DNS only: `_acme-challenge.car.alexahub.dev`, `_acme-challenge.www.car.alexahub.dev`,
   `_acme-challenge.fullscreen.car.alexahub.dev`, `_acme-challenge.www.fullscreen.car.alexahub.dev`
   and `_acme-challenge.standby.car.alexahub.dev` -> `_acme-challenge.<validation-zone>`. Then set
   `CHALLENGE_ALIAS=<validation-zone>` and `CF_ZONE_ID` = the validation zone's ID.
3. **R2 bucket**: R2 -> Create bucket `teslalinux-certs` -> Settings -> Custom Domains -> Connect
   `certs.alexahub.dev` (keep the r2.dev URL disabled). R2 -> Manage R2 API Tokens -> Create API
   token: `Object Read & Write`, "Apply to specific buckets only" = `teslalinux-certs`, TTL as above
   -> note Access Key ID and Secret Access Key. Account ID: R2 overview page.
4. **GitHub Environment** in this repository (Settings -> Environments -> New environment `tls`):
   Deployment branches and tags: "Selected branches and tags", rule `main`. Required reviewers: **off**
   (a reviewer gate would hold every scheduled run).
   Secrets: `ACME_ACCOUNT_KEY` (step 1, whole PEM), `CF_DNS_API_TOKEN` (step 2), `R2_ACCESS_KEY_ID`,
   `R2_SECRET_ACCESS_KEY` (step 3).
   Variables: `DEVICE_HOST=car.alexahub.dev`, `CF_ZONE_ID=<zone id>`, `R2_ACCOUNT_ID=<account id>`,
   `R2_BUCKET=teslalinux-certs`, `CERT_PATH_PREFIX=car`, `PUBLIC_BASE_URL=https://certs.alexahub.dev/car`;
   optional `CHALLENGE_ALIAS`, `ACME_EMAIL`, `CF_ACCOUNT_ID`.
5. **Repository settings**: Settings -> Actions -> General: allow only actions created by GitHub,
   require actions to be pinned to a full-length commit SHA, default workflow permissions read-only,
   require approval for workflows from outside collaborators. Branch protection on `main`: block force
   pushes and deletion.
6. **First runs**: Actions -> tls-certs -> Run workflow with `staging: true` (the whole flow, nothing
   published), then with `action: issue-both`. Check `https://certs.alexahub.dev/car/active.json`. The
   job summary shows the **ACME account URI** for the CAA record.
7. **DNS** (Cloudflare DNS for alexahub.dev):
   * A records, **DNS only** (grey cloud): `car`, `www.car`, `fullscreen.car`, `www.fullscreen.car`
     -> `192.0.2.1` (`standby.car` needs none: it is only a name in the standby certificate).
   * CAA, after step 6, on the **`car.alexahub.dev`** node (CAA lookups climb from each name, so this
     covers all five names and leaves the apex and `certs.alexahub.dev` to Cloudflare's own edge
     certificates): `car.alexahub.dev CAA 0 issue "letsencrypt.org; accounturi=<ACME account URI>;
     validationmethods=dns-01"`, `car.alexahub.dev CAA 0 issuewild ";"`,
     `car.alexahub.dev CAA 0 iodef "mailto:<your address>"`.
   * DNSSEC: DNS -> Settings -> Enable DNSSEC, then the DS record at the registrar.
   * Certificate Transparency monitoring: SSL/TLS -> Edge Certificates -> "Certificate Transparency
     Monitoring", and/or Cert Spotter watching `alexahub.dev` with subdomains.
8. **Keep the schedule alive**: GitHub disables scheduled workflows of a public repository after 60
   days without repository activity. The Actions page then shows a banner with "Enable workflow";
   re-enable it (or keep committing). The 3-hourly runs stop silently otherwise, and the certificates
   expire about 30 days after the last renewal.
9. **Pi**: nothing to set up. `TLS_BASE_URL` is in `/etc/teslalinux/env`; `sudo teslalinux-tls fetch`
   tries at once, `teslalinux-tls status` shows the result.

Operations: a revoked or broken slot needs nothing (the next run, at most 3 h later, handles it; run
the workflow with `check` to do it now, or `issue-current` / `issue-standby` to force a new
certificate). **Rollback**: copy `<slot>-previous/` over `<slot>/` in the bucket (the Pi only accepts
a pair newer than its own, so this matters only for Pis that never got the bad one).

Pinned in `host/tls/renew.sh`: acme.sh 3.1.6, commit `807da6498377ee5e0cf43a78091f46f12dc59a89`,
sha256 `acme.sh` `c7d68b021cfd6380ea83a82962abde5b484779fee0b97d38681dfa1396bbc8d7`, `dnsapi/dns_cf.sh`
`9628ee8238cb3f9cfa1b1a985c0e9593436a3e4f8a9d65a6f775b981be9e76c8`; `actions/checkout` v5.1.0
`fbc6f3992d24b796d5a048ff273f7fcc4a7b6c09` (checked with `git ls-remote`).

## 5. Pi OS blockers (outside this stack, must be solved before the one-line installer works)

1. **ATL on Debian.** `android-translation-layer`, `art_standalone`, `bionic_translation` and
   `libandroidfw` exist only as Alpine packages. The installer needs them built for Debian trixie
   arm64 (from source, or a prebuilt `/opt/atl` tree with its libraries). ATL side of the project.
2. **tacomp needs wlroots 0.20; trixie ships 0.18.** Either the installer builds wlroots 0.20 (meson,
   into a private prefix) or tacomp gets a 0.18 build path. `provision.sh` installs a tacomp binary
   only when one was built for the system.
3. **One-liner distribution.** The repo is private, so `curl ... | sudo bash` cannot fetch from it.
   Options: publish a signed installer bundle next to the certificate (R2, verified by a pinned
   minisign/ssh-signature key in the one-liner), or clone with a read-only deploy key.

## 6. Needs the VM run (`tools/atl-vm/carstack.sh`)

* tacomp compiles with the new code (`carstack.sh install` builds it: the bind option, the forced
  IDR, the main-thread frame request).
* `/sockets/display` and `/sockets/touchscreen` upgrade through nginx (`carstack.sh check` expects
  101) and the page shows Waze; taps reach the app; GPS from the page reaches Waze.
* Alpine's nginx accepts the rendered site next to its own `nginx.conf` (the zone/variable clash fix).
* `CARSTACK_AUDIO=1`: ATL -> ALSA -> PipeWire null sink -> audio.py (`ffmpeg -f pulse
  -fragment_size`; Homebrew's ffmpeg has no Pulse input, so this was not exercised on the Mac).
* `sh host/tls/test/run.sh` under busybox ash.

## 7. Needs hardware

* hostapd on brcmfmac: 5 GHz ch 44 80 MHz (and 36/149/157, 2.4 GHz HT40), country code.
* The hijack path with the car: dnsmasq answers, DNAT/SNAT, connman probes keep the Wi-Fi in offline
  mode, the youtu.be launcher goes full-screen.
* TLS from the car (ChaCha20 preference, CPU cost of the stream), the issued certificate.
* tacomp on V3D (GLES2, render group), the V4L2 M2M encoders (`m2m-jpeg`, `m2m-h264`), the forced IDR
  with WebCodecs in the car.
* PipeWire/WirePlumber as system units without a session bus.
* LTE uplink, NetworkManager dispatcher kick, timesyncd, the DS3231 RTC.
* `teslalinux-tls-fetch.service` sandbox (`nginx -t` and the reload under `ProtectSystem=strict`).
* deviceInfo: CPU temperature, serial, modem and CarPlay detection.
* Boot time: `teslalinux-netconfig` (Python only when settings changed), first boot certificate install.
* Alpine image only: firmware boot with `vmlinuz-rpi`/`initramfs-rpi`, DTB locations, udev-settle cost.

## 8. In-car test procedure

1. Pi: provisioned, rebooted. `systemctl --failed` empty; `systemctl status hostapd dnsmasq nftables
   nginx tacomp teslalinux-api teslalinux-gps teslalinux-audio teslalinux-pipewire`;
   `teslalinux-tls status` shows `"trusted": true` (else `sudo teslalinux-tls fetch` with an uplink; with the self-signed fallback the
   car shows a certificate error).
2. Car: Wi-Fi -> join `Teslalinux` (`AP_SSID`; password from `AP_PSK`), tick "Remain connected in Drive".
3. Browser: `https://car.alexahub.dev/`. Expect the launcher, no certificate warning, the stream
   (MJPEG default), Waze after its start time. Tap: Waze reacts.
4. Fullscreen: open the youtu.be launcher on port 6969, `http://youtu.be:6969/` (how a youtu.be
   link is opened so the car switches to its full-screen video mode is the car's behaviour; upstream
   documents no exact URL, so try the plain address first, then through a YouTube redirect link). The
   redirect page appears and after 3 s loads `https://fullscreen.car.alexahub.dev`.
5. Settings -> Display: switch to `h264 (WebCodecs)`: the page reloads the stream; picture within a
   second (forced IDR). Back to MJPEG.
6. Settings -> GPS on, allow location: Waze follows the car (check `journalctl -u teslalinux-gps` and the
   feed: `nc 127.0.0.1 9997` on the Pi shows lines).
7. Settings -> Audio on, press the speaker button: Waze voice prompts play in the car.
8. Settings -> Network: toggle telemetry blocking, apply, reboot the Pi: `grep telemetry
   /etc/dnsmasq.conf` follows the setting.
9. Pull the LTE stick and put it back: `tail /var/log/teslalinux/tls.log` shows a fetch attempt
   (or "fetched a moment ago" within 10 min).
10. Offline: no LTE, the car keeps the hotspot connection (connman probe answered locally).

## 9. Future

* **Update `web/` without reflashing**: pull a signed frontend bundle over LTE when available (same
  pattern as the certificate: public bundle, signature checked on the Pi against a pinned key, atomic
  swap of `/var/www/teslalinux`, nginx reload, rollback copy).
* The frontend's update button checks upstream's GitHub releases (tesla-android/android-raspberry-pi);
  for this fork it should point at our own release feed once there is one.
* The one-line installer (section 5).
