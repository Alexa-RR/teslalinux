# android/

Everything that shapes the Waydroid container (LineageOS 20 / Android 13, GApps) without rebuilding it.

* `waydroid_base.prop` — properties injected at container start: fixed 1088x832 display, no boot
  animation, Lepton-derived service disables, dalvik/lmk tuning, Mesa v3d pins.
* `disable-packages.txt` — packages switched off once during bake (`pm disable-user`).
* `bake.sh` — one-time first boot on the Pi: waits for `sys.boot_completed`, disables packages,
  sets the settings Waze needs (location on, mock location allowed, no animations), installs the APKs,
  forces dex2oat, prints the GSF ID for Play certification, then stops the container. Every later boot
  starts from this baked `/data`. This is the Waydroid equivalent of Lepton's `sysbake`.
* `apks/` — Waze + GPS feeder APKs (not in git).

Phase 3 (custom image build with the AOSP-level trims and a native GNSS HAL) will live under
`android/image/`.
