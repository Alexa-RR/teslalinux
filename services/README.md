# services/

Host-side daemons. Each one speaks exactly the upstream protocol (see `docs/UPSTREAM-NOTES.md`) on the
upstream port, so the browser frontend in `web/` is untouched.

| Dir | Port | Implementation | Status |
|---|---|---|---|
| `display/` | 9091 (frames), 9999 (touch), 9996 (device keyboard) | `tacomp`: headless wlroots compositor that is the app's Wayland server; encodes every frame (CPU JPEG, or the Pi's V4L2 M2M encoder from `m2m/`, vendored from upstream); WebSocket out; WebSocket touch in → `wl_touch` | runs on the VMs; V4L2 M2M untested on hardware (see `display/README.md`) |
| `gps/` | 9998 → 9997 | `gps.py`: browser geolocation JSON → line protocol; consumed by the ATL LocationManager (`ATL_LOCATION_SOURCE=tesla:9997`, tools/atl-vm/patches/atl-waze/android/location) | written, untested on hardware |
| `audio/` | 8080 | `audio.py`: PipeWire `android.monitor` → ffmpeg FLAC fMP4 → WebSocket with cached init segment | written, untested on hardware |
| `api/` | 8081 | `api.py`: `/api/*` state + display re-configuration | written, untested on hardware |

Install location on the Pi: `/usr/local/lib/teslalinux/*.py`; tacomp in `/opt/teslalinux/tacomp`
(from the release dist, `tools/pios/install.sh`). All daemons run as the unprivileged `tesla` user;
the display re-configure path needs one sudo rule for `teslalinux-apply-display`.

`tacomp` is the only piece that needs C. Everything else is small enough to stay Python and is
rewritten only if it shows up in the boot profile.

## The app itself

`host/phase1-pios/teslalinux-atl` (unit `teslalinux-atl.service`) starts Waze under our Android
Translation Layer fork on tacomp's Wayland socket, after the compositor and `gps.py`. It carries
the environment the VM runs proved (`tools/atl-vm/run-waze.sh`): `ATL_LOCATION_SOURCE=tesla:127.0.0.1:9997`,
fullscreen without decorations, the built-in keyboard, the car density, the split APK set from
`tools/apps/install-app.sh`; rendering goes through V3D (`GSK_RENDERER=ngl`), not llvmpipe. The
ATL prefix (ATL, its `api-impl`, standalone ART, bionic_translation) is `/opt/teslalinux` on Pi OS
(the release dist) and `/opt/atl` on the Alpine image.
