# services/

Host-side daemons. Each one speaks exactly the upstream protocol (see `docs/UPSTREAM-NOTES.md`) on the
upstream port, so the browser frontend in `web/` is untouched.

| Dir | Port | Phase 1 implementation | Status |
|---|---|---|---|
| `display/` | 9091 (frames), 9999 (touch) | `tacomp`: headless wlroots compositor that *is* Waydroid's Wayland server; V4L2 M2M encoder (`m2m/`, vendored from upstream); WebSocket out; WebSocket touch in → `wl_touch` | **to build** (see `display/README.md`) |
| `gps/` | 9998 → 9997 | `gps.py`: browser geolocation JSON → line protocol; consumed by the ATL LocationManager (`ATL_LOCATION_SOURCE=tesla:9997`, tools/atl-vm/patches/atl-waze/android/location) | written, untested on hardware |
| `audio/` | 8080 | `audio.py`: PipeWire `android.monitor` → ffmpeg FLAC fMP4 → WebSocket with cached init segment | written, untested on hardware |
| `api/` | 8081 | `api.py`: `/api/*` state + display re-configuration | written, untested on hardware |

Install location on the Pi: `/usr/local/lib/teslalinux/*.py`, `/usr/local/bin/tacomp`
(`host/phase1-pios/provision.sh` copies them). All daemons run as the unprivileged `tesla` user; the
display re-configure path needs one sudo rule for `teslalinux-apply-display`.

`tacomp` is the only piece that needs C. Everything else is small enough to stay Python in Phase 1 and
is rewritten only if it shows up in the boot profile.

## The app itself (ATL variant)

`host/phase1-pios/teslalinux-atl` (unit `teslalinux-atl.service`) starts Waze under the patched
Android Translation Layer on tacomp's Wayland socket, after the compositor and `gps.py`. It carries
the environment the VM runs proved (`tools/atl-vm/run-waze.sh`): `ATL_LOCATION_SOURCE=tesla:127.0.0.1:9997`,
fullscreen without decorations, the built-in keyboard, the car density, the split APK set from
`tools/apps/install-app.sh`; rendering goes through V3D (`GSK_RENDERER=gl`), not llvmpipe. The baked
image provides `/opt/atl` (ATL, the patched `api-impl`, host ART) with the dalvik cache warmed. It
conflicts with `waydroid-session.service`: one Android runtime per compositor.
