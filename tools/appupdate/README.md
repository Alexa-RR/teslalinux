# tools/appupdate

The Pi keeps Waze current by itself: APKPure automatically (default), manual uploads from the car
page, or Google Play through a token dispenser (off by default). Design, rules, risks and the setup are in
[docs/APP-UPDATES.md](../../docs/APP-UPDATES.md).

| File | What |
|---|---|
| `src/main.rs`, `src/apkpure.rs`, `Cargo.toml`, `Cargo.lock`, `.cargo/config.toml` | `tlplay --source apkpure\|play`: `details`, resumable `download` of only our APKs (APKPure: the XAPK read over HTTP ranges; Play: EFF's `gpapi` 6.1.0 with a dispensed anonymous login) |
| `device.properties` | the Play device profile (`px_tablet`: SDK 33, xhdpi, arm64-v8a only; Play source only) |
| `teslalinux-appupdate` | the updater (Python 3, root): check, policy, uploads (`receive-upload`), verify, install, health check, rollback, state |
| `install.sh` | installs everything on a Pi OS / Debian system (root) |
| `build.sh` | `cargo build/test --release --locked` for the machine it runs on |
| `test/run.sh` | all offline tests (updater, API routes, cargo tests) |

```sh
tools/appupdate/build.sh                     # Pi / Debian arm64 VM: apt-get install cargo rustc libssl-dev pkg-config
tools/appupdate/test/run.sh                  # no network, no account, no VM (needs out/apps/com.waze/)
sudo tools/appupdate/install.sh              # on the Pi
target/release/tlplay details com.waze        # APKPure (default source)
target/release/tlplay download com.waze --version-code N --out DIR \
    --split config.arm64_v8a --split 'config.xhdpi|config.xxhdpi|config.xxxhdpi|config.hdpi|config.mdpi' --split config.en
```

tlplay prints one JSON object. Exit status: 0 ok, 2 usage, 10 dispenser unavailable, 11 Play rejected
the login (a fresh one too), 12 source unreachable or answered nonsense, 13 not offered, 14 download failed.
It always sends its own User-Agent; no option changes it.
