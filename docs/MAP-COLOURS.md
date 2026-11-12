# Map colours: Waze's map in the Graphite look

Settings > Waze interface > Map colours (car interface):

| Choice | What draws it | Restart |
|---|---|---|
| **Graphite** | Waze itself, with its map palette recoloured when it starts: grey land, white roads by day and dark roads by night, the route in the look's blue. Traffic, pins, reports and the speedometer stay Waze's. | the app restarts when it is switched on or off |
| **Muted (page)** | the car page, a shader pass over the video (`web/js/display/map-tone.js`), with the Graphite page style | none |
| **Waze's own** | Waze, unchanged | none |

The choice is kept on the device (`/etc/teslalinux/interface.env`, `TESLALINUX_MAP_COLOURS`, written by
`teslalinux-set-app-interface map graphite|muted|app` from `POST /api/appInterface`).

## How Graphite works: a runtime file overlay, nothing on disk changes

Waze's map palette is plain Lua: `waze/skins/default/skin_values.day.lua` and `skin_values.night.lua`
(and an `experiment/` copy the server can switch to, which only changes traffic colours), unpacked from
the APK into its data folder (`/var/lib/tesla/android/waze/base.apk_/`). `libwaze.so` reads them with
`fopen()`/`open()` through bionic_translation.

- **ATL** (our fork, `src/api-impl-jni/file_overlays.c`, `doc/Envs.md`): `ATL_FILE_OVERLAYS=<manifest>`
  is a general feature. The manifest lists, per file, the package, the path, the verified
  (versionCode, sha256 of the original) pairs, and text replacements. When the app opens a listed file
  read-only, ATL checks the running versionCode and the file's sha256 against the pairs, applies the
  replacements (each must occur exactly once), and hands the app a sealed in-memory copy (memfd). Any
  mismatch: the app reads its own file, and the log says why (`ATL file overlays: ...`, once per file).
  Writes (Waze unpacking a new version) always reach the real file.
- **bionic_translation** (`tools/atl-vm/patches/bionic_translation-waze.sh`): the open overlay hook
  (`libc_bio_set_open_overlay_func`) that read-only `open`, `open64`, `fopen`, `fopen64`, `freopen` and
  the fortified `__open_2` ask first.
- **teslalinux**: the manifest with the palette and the verified list,
  `host/appstyle/waze-map-graphite.json` (installed to `/usr/local/lib/teslalinux/appstyle/`); the
  launcher (`host/phase1-pios/teslalinux-atl`) passes it in the car interface when the choice is
  Graphite; `services/api/api.py` reports in `GET /api/appInterface` `mapSkin` whether it applies to
  the installed Waze (the same versionCode + sha256 check), and the settings page warns when it does not.

The palette comes from the Graphite tokens and the mockup's map: land `#D9DADD` / `#2F3032` (by day a
shade darker than the panels, so the white roads stand out), buildings and stations `#CDCED2` /
`#353639`, parks `#C8D2C6` / `#2B332D`, water `#A8AFB9` / `#1C1D20`, roads white (day) or near-black
greys (night), labels dark (`#505257`, strong `#333438`) by day and light greys by night, route
`#2F64E8`, the alternative and preview routes in light or dark blues. By day `global_color_mod`, the
tint Waze fades the 3D view's distance into, is the land colour instead of white, so the horizon does
not wash out. Every replacement keeps the file's length (stat() still describes the file on disk).

Not in the palette file, so Waze's own colours stay: the position arrow (a textured 3D model,
`skins/default/cars/arrow_model.obj` + `arrow_texture_normal.png`) and the route preview's ETA bubbles
("11 min / Best": libwaze puts the colour into its label request, `EtaLabelRequest.labelData.backgroundColor`,
purple by day and cyan by night, and Waze's Java side draws the bitmap with it).

## When Waze updates

`tools/appupdate` installs a new Waze as before. At its next start Waze unpacks the new skin files; if
the new versionCode (or the files) is not on the verified list, ATL leaves them alone, the map shows
Waze's own palette, the page tones it down (Muted, with the Graphite page style), and Settings shows
"Map colours: not available for Waze X.Y". Nothing else changes; nothing breaks.

## Checking a new Waze version and adding it

On the device (or the VM) with the new version installed and started once (so its skin files are
unpacked), as root or the `tesla` user:

```sh
python3 /usr/local/lib/teslalinux/appstyle/mapskin.py check
```

It prints the installed versionCode, each skin file's sha256, whether that pair is verified, and
whether every replacement still occurs exactly once. Then:

1. Every file says "applies, not verified": the palette lines are unchanged in this version. If a
   replacement "occurs 0 times", Waze changed that line: compare the new file with the old one
   (`mapskin.py preview --out /tmp/p` shows what would be served) and adjust the replacement in the
   repo's manifest first.
2. Look at the result before trusting it: with the repo's manifest and the new pairs added to a copy
   (`mapskin.py add --manifest /tmp/m.json --version-name X.Y`), start the app with
   `TESLALINUX_MAP_SKIN_MANIFEST=/tmp/m.json` in `/etc/teslalinux/atl.env` and Graphite chosen, and
   check, day and night (Settings > Day and night), during a simulated drive
   (`tools/debian/gps-route.py`): the log says `applied` for the day and night files, land and roads are
   neutral greys, the route is blue, traffic colours and pins are Waze's, labels are readable, nothing
   is missing (water, parks, ramps, parking).
3. Add the pairs to the repo's manifest (`mapskin.py add --manifest host/appstyle/waze-map-graphite.json
   --data-dir <unpacked data folder> --version-code N --version-name X.Y`, or copy the lines from step
   2), commit, and release. Keep the older versions on the list while cars still run them.

Verified so far: Waze 5.25.0.1 (versionCode 1030736), all four files (day, night, and the experiment
copies), on the Debian VM, Nov 12.
