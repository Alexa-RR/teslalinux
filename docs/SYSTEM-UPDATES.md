# Teslalinux updates: the Pi installs new releases by itself

The Pi checks GitHub once a day for a new Teslalinux release, downloads it over Wi-Fi or Ethernet,
verifies it against the release's own checksums, installs it while the car is not connected, makes
sure the new release starts, and goes back to the previous one if it does not. Settings > Updates >
Teslalinux shows all of it and has the buttons. The app's own updates are separate
(`docs/APP-UPDATES.md`), and so are the operating system's (apt, Settings > Updates > System).

## How it works

| Step | What happens |
|---|---|
| Check | `GET https://api.github.com/repos/OWNER/REPO/releases`, once a day (24 h ±10 %, the hourly timer adds up to 10 min) and on "Check now"; a failure backs off from 15 min to a day. Unauthenticated: one request a day is far below GitHub's 60 an hour. |
| Which release | The newest **stable** one by version number: tag `vX.Y.Z` (no `-rc` suffix), not a draft, not a pre-release, with all four files (`teslalinux-dist-*-arm64.tar.zst`, `MANIFEST`, `install.sh`, `SHA256SUMS`). It is offered only when newer than the installed release (the dist's `MANIFEST`, `release=`). |
| Which repository | The one the device was installed from: the dist's `MANIFEST` (`release_repo=`, written by `pack-dist.sh --release-repo`), else the installed installer's `TL_RELEASE_REPO`, else `Alexa-RR/teslalinux`. A fork's releases update the fork's installs. |
| Download | Only when the uplink is unmetered, with the app updater's rule (`teslalinux-appupdate`'s `download_allowed`): LTE, a USB modem or phone tethering is metered even when NetworkManager guesses otherwise; unknown counts as metered. The uplink is checked again every 20 s on the way, and the download pauses (resumable, HTTP ranges) if it becomes metered. "Download over mobile data" (two taps, with the size) allows it once for that release. Files come from `https://github.com/OWNER/REPO/releases/download/TAG/NAME`; the URL is built from checked names, not taken from the API's answer. |
| Verify | Every file has the size GitHub gives and the SHA-256 the release's `SHA256SUMS` lists; the `MANIFEST` names this tag, `arm64` and this tarball. Anything else is refused and deleted; the release is not downloaded again until "Check now". Checked again right before the install. |
| Install | The release's own `install.sh install --dist <downloaded tarball> --yes --no-reboot`: the installer checks the tarball against the `SHA256SUMS` next to it and fetches nothing again. Waze is stopped first; the installer unpacks the dist into `/opt/teslalinux.d/<build>`, moves `/opt/teslalinux`, keeps the previous dist and a backup set of every system file it replaces, rebuilds ART's boot image and compiles Waze (a few minutes on a Pi 4). Then the device restarts. |
| When | With "Update Teslalinux automatically" on (the default): while the car is not connected (no station on the hotspot and no display or touch connection, twice 20 s apart, as the app updater checks; and in Car mode Waze not navigating, from the car-app host), or at the next start, before the app (`teslalinux-release-update-boot.service`). Always: "Install now" (two taps). Never during a Waze update or an apt run (their locks). |
| Health check | At the start after the install (`teslalinux-release-update-health.service`): nginx, tacomp, the API, hostapd and dnsmasq active, and the app's first picture after this start (the launcher's state file, as the car page's start overlay sees it), within 25 minutes, with at most 3 app crashes and 3 device restarts. |
| Rollback | A failed health check or a failed install goes back to the previous dist and the system files as they were: the previous dist's own `install.sh rollback --yes` with the backup set that install made, or, when that run made none, the `/opt/teslalinux` link alone. Then the device restarts. Settings shows what happened and why; that release is not installed again automatically ("Install anyway" does). |
| Go back | "Go back to X" (two taps) does the same by hand while a previous dist is kept. |

Turned off, nothing is downloaded or installed by itself; Settings still shows a new release with a
Download button, and "Install now" installs a downloaded one.

**Trust.** The release's files and its `SHA256SUMS` come from the same GitHub release over HTTPS (the
same trust as the one-line installer): the checks catch a damaged or mixed-up download, not a
compromised GitHub account. The installer runs as root.

## Pieces

| Piece | Where | What it does |
|---|---|---|
| `teslalinux-release-update` | `host/` (Python 3, root), with `host/releaseupdate/relgithub.py` (GitHub, download, verification) and `relsystem.py` (dists, backup sets, rollback, probes) | the state machine, the commands below |
| units | `host/phase1-pios/etc/systemd/teslalinux-release-update{.service,.timer,-boot.service,-health.service}` | hourly timer, the boot-time install, the health check |
| NetworkManager hook | `host/phase1-pios/etc/NetworkManager/92-teslalinux-release-update` | an uplink came up while a release waits for Wi-Fi |
| sudoers | `/etc/sudoers.d/teslalinux-release-update` (written by `tools/pios/install.sh`) | the API (user `tesla`) may run exactly `check`, `download`, `download --metered`, `install-now`, `go-back`, `auto on`, `auto off` |
| API | `GET/POST /api/releaseUpdate` (`services/api/api.py`) | the state for the page; the actions above |
| settings page | Settings > Updates > Teslalinux (`web/js/settings/settings-controller.js`, `_releaseUpdateGroup`) | installed and available release, release notes, progress, the buttons |

```sh
sudo teslalinux-release-update status     # state as JSON (also readable without root: the files below)
sudo teslalinux-release-update check      # what "Check now" does (records it, starts the service)
journalctl -u teslalinux-release-update   # or /var/log/teslalinux/release-update.log
```

Files (root's, readable by everyone; nothing secret): `/var/lib/teslalinux/release-update/`
`settings.json` (`autoUpdate`), `state.json`, `progress.json`, `staging/` and `pending/` (the
downloaded release), `health.json` (while a health check is pending). Logs:
`/var/log/teslalinux/release-update.log`, and the installer's own `install-<time>.log`.

## Where it does not apply

The Alpine image (`docs/ALPINE-IMAGE.md`) is updated by writing a new image; it has no release
installer and no dists. A device installed from a repository copy, or running a local build
(`MANIFEST` without `release=`), has no release to compare with. In each case Settings shows
"Release updates: Not available" with the reason, and the API refuses the actions.

## Tests

`python3 host/test/test_release_update.py` runs without a network, GitHub, root or a Pi: a local
HTTP server stands in for GitHub's API and downloads (with ranges), each release is a small fake
dist with a real `SHA256SUMS`, `host/test/fake-install.sh` stands in for the installer and a script
for systemctl. It covers newer, equal and older releases and the semver order, drafts,
pre-releases and incomplete releases, a checksum mismatch, a MANIFEST of another release, the
metered wait and the mobile-data download, a resumed download, the car-connected wait and Install
now, automatic updates off, the boot install, GitHub down (backoff), the install, the health check
passing and failing (with and without the installer's backup set), a failed install, going back by
hand, the unavailable cases, the repository choice, the app updater's uplink rule and the API.
