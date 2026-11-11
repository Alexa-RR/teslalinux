# teslalinux

Android apps, Waze first, on the **Tesla in-car browser**, served by a **Raspberry Pi 4** with an
LTE modem in the car.

The car joins the Pi's Wi-Fi hotspot and its browser opens a launcher page served by the Pi. The page
shows the app's screen, streamed over WebSockets by a small Wayland compositor, sends touches back,
and passes the car's GPS position and audio between the car and the Pi.

teslalinux started as a fork of [Tesla Android](https://github.com/tesla-android), which runs a full
AOSP build on the Pi's bare metal. teslalinux runs **plain Linux underneath** instead (Raspberry Pi OS
Lite, or an Alpine image) and runs the app under the
[Android Translation Layer](https://gitlab.com/android_translation_layer/android_translation_layer)
(ATL): Android framework APIs reimplemented on GTK 4, with a standalone ART runtime. There is no
Android system image, no Google Play services and no container. The web frontend and the protocol
between the browser and the Pi (ports, frame formats, touch, GPS and audio messages) are kept
compatible with Tesla Android's, so its frontend runs unchanged apart from the changes listed in
`web/README.teslalinux.md`.

## Status

Under active development. Waze runs end to end in the test VM: map, search, routes, turn-by-turn
navigation with voice prompts, settings and the on-screen keyboard. First boots on real Pi hardware
(GPU, hardware video encoder, LTE, RTC) are in progress. Expect rough edges.

## Install on a Raspberry Pi 4

You need a Raspberry Pi 4 (4 B, 400 or CM4) with **Raspberry Pi OS Lite 64-bit (Debian 13
trixie)** on its microSD card, internet access during the install (Ethernet is best: the installer
turns the Pi's Wi-Fi into the car's hotspot), and for use in the car a USB LTE modem. Nothing is
compiled on the Pi: the runtime comes prebuilt from this repository's
[Releases](https://github.com/Alexa-RR/teslalinux/releases).

**One command**, on the Pi:

```sh
curl -fsSL https://github.com/Alexa-RR/teslalinux/releases/latest/download/install.sh | sudo bash -s -- \
  --set AP_PSK='your-hotspot-password' \
  --waze apkpure
```

- `AP_PSK`: the password of the Pi's Wi-Fi hotspot (`AP_SSID`, default `Teslalinux`). Leave it out
  and the installer generates one and prints it at the end.
- The certificate works out of the box: every car uses `https://car.alexahub.dev/`, with one shared,
  publicly trusted certificate that this project renews automatically (the Tesla browser only opens
  pages over HTTPS with such a certificate). The release carries the current one and the Pi keeps it
  up to date by itself. Self-hosting your own name and issuer is optional (`DEVICE_HOST`,
  `TLS_BASE_URL`); design and threat model: `docs/CAR-STACK-READINESS.md`, section "Certificate".
- Waze: `--waze apkpure` downloads it (checked against Waze's own signing certificate), or
  `--apk /path/to/waze.apkm` installs a bundle you downloaded yourself, or `--waze none` leaves it
  to the upload screen of the car page.
- More settings: `--config FILE` with `KEY=VALUE` lines (the keys of `host/phase1-pios/env`, plus
  `LTE_APN`, `LTE_USER`, `LTE_PASSWORD`, `LTE_ROAMING` for the modem). `--dist vX.Y.Z` picks a
  release instead of the latest. The full option list is the header of `tools/pios/install.sh`.

The installer can be run again at any time (it only redoes what changed), keeps a backup of every
system file it replaces, and asks before the final reboot. Afterwards:

```sh
sudo teslalinux doctor      # health check
sudo teslalinux status      # installed release, previous release, backups
sudo teslalinux rollback    # back to the previous release and system files
sudo teslalinux uninstall   # remove everything (--keep-data keeps the app and settings)
```

In the car: join the Pi's Wi-Fi, then open `https://car.alexahub.dev/` in the browser.

No app is included. The Pi downloads Waze itself and keeps it updated (APKPure's public API by
default; every APK must carry the app's original signature), or you upload an app bundle from the
car page. See `docs/APP-UPDATES.md`.

The Alpine image (`tools/image/`, `docs/ALPINE-IMAGE.md`) is the second, experimental target.

## Repository map

| Path | What |
|---|---|
| `docs/` | Architecture and boot-time budget, the upstream protocol contract, the car stack, app updates, the Alpine image |
| `web/` | The browser frontend, forked from Tesla Android's `frontend` |
| `services/display/` | `tacomp`: a headless wlroots compositor that streams frames (JPEG, or H.264/JPEG through the Pi's V4L2 M2M encoder) and injects touches |
| `services/gps/`, `services/audio/`, `services/api/` | GPS bridge, audio stream, the settings and status API |
| `host/` | Host configuration (hotspot, DNS, nftables, nginx), service units for systemd and OpenRC, certificate handling, Pi firmware config |
| `tools/apps/`, `tools/appupdate/` | App installation from split APKs with signature verification; automatic app updates |
| `tools/pios/` | The Raspberry Pi OS installer and release packaging |
| `tools/debvm/`, `tools/atl-vm/`, `tools/debian/` | The Debian and Alpine aarch64 build and test VMs, the ATL patch history and test tools |
| `tools/image/` | The Alpine SD-card image builder |
| `android/` | Tuning for the earlier Waydroid-based design (see `docs/ARCHITECTURE.md`) |

The ATL changes made for this project live in a fork of ATL,
[Alexa-RR/android_translation_layer](https://github.com/Alexa-RR/android_translation_layer), pinned in
`tools/debvm/versions.env`. `tools/atl-vm/patches/` is the patch set it was built from.

## License

GNU General Public License, version 3 (`LICENSE`). Parts come from projects under other licenses that
are compatible with it:

* `web/`: Tesla Android's frontend, GPL-3.0 (`web/LICENSE`).
* `services/display/m2m/`: derived from PiKVM's ustreamer, GPL-3.0 (`services/display/m2m/LICENSE.upstream`).
* `tools/atl-vm/patches/`: changes to ATL (GPL-3.0); many files there are ports of AOSP framework
  sources under the Apache License 2.0 and keep their original headers.
* `tools/appupdate/device.properties`: from gpapi / Aurora's gplayapi, GPL-3.0-or-later.
* Fonts in `web/fonts/` (Roboto, Material Icons, shipped with the upstream frontend): Google's open
  font licenses.

## Credits

* [Tesla Android](https://github.com/tesla-android) for the idea, the frontend and the
  browser-to-device protocol this project keeps.
* [Android Translation Layer](https://gitlab.com/android_translation_layer/android_translation_layer),
  art_standalone and bionic_translation, which make running an Android app on plain Linux possible.
* [wlroots](https://gitlab.freedesktop.org/wlroots/wlroots), [ustreamer](https://github.com/pikvm/ustreamer),
  and the Android Open Source Project.

This project is not affiliated with or endorsed by Tesla, Waze or Google. Tesla and Waze are
trademarks of their respective owners.
