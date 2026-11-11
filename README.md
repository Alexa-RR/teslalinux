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

You need a Raspberry Pi 4, a microSD card with **Raspberry Pi OS Lite 64-bit (Debian 13 trixie)**,
and for use in the car a USB LTE modem.

1. Get the latest release from this repository's **Releases** page on GitHub. It carries the
   prebuilt runtime (ATL stack and compositor) for Raspberry Pi OS.
2. On the Pi, run the release installer, `tools/pios/install.sh` (its header lists the options). It
   installs the runtime, the services and the host configuration (hotspot, DNS, nginx).
3. Before the first start, set your own values in `/etc/teslalinux/env` (template:
   `host/phase1-pios/env`): at least the hotspot password `AP_PSK`, and `DEVICE_HOST`, a hostname
   under a domain you control. The Tesla browser only opens pages over HTTPS with a publicly trusted
   certificate, so that hostname needs a real certificate; `docs/CAR-STACK-READINESS.md`, section
   "Certificate", describes the setup (the key stays on the Pi; issuance runs elsewhere).
4. In the car: join the Pi's Wi-Fi (`AP_SSID`, default `Teslalinux`), then open
   `https://<DEVICE_HOST>/` in the browser.

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
