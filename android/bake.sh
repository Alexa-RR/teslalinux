#!/usr/bin/env bash
# teslalinux: one-time "bake" of the Waydroid container, run ON THE PI after provision.sh.
# Mirrors Lepton's sysbake: do the slow first boot once (package scan, dex2oat, GApps setup),
# disable everything we never use, install the GPS feeder, then stop. Every later boot starts from
# this baked /data. Re-run after changing disable-packages.txt or the Waydroid image.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

log() { printf '\033[1;32m[bake]\033[0m %s\n' "$*"; }
wd() { sudo waydroid shell -- "$@"; }

if ! sudo waydroid status | grep -q 'Session:.*RUNNING'; then
  log "starting container + headless session"
  sudo systemctl start waydroid-container.service
  sudo systemctl start tacomp.service
  sudo -u tesla XDG_RUNTIME_DIR=/run/tesla WAYLAND_DISPLAY=tesla-0 waydroid session start &
fi

log "waiting for sys.boot_completed (first boot can take minutes)"
for _ in $(seq 1 600); do
  if [[ "$(wd getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; then break; fi
  sleep 2
done
[[ "$(wd getprop sys.boot_completed | tr -d '\r')" == "1" ]] || { echo "boot never completed" >&2; exit 1; }

log "disabling packages"
while read -r pkg; do
  [[ -z "$pkg" || "$pkg" == \#* ]] && continue
  wd pm disable-user --user 0 "$pkg" >/dev/null 2>&1 && echo "  disabled $pkg" || echo "  (absent) $pkg"
done < "$HERE/disable-packages.txt"

log "settings: no animations, no lock screen, no sleep, allow mock location"
wd settings put global window_animation_scale 0
wd settings put global transition_animation_scale 0
wd settings put global animator_duration_scale 0
wd settings put secure lockscreen.disabled 1
wd settings put system screen_off_timeout 2147483647
wd settings put global stay_on_while_plugged_in 7
wd settings put secure location_mode 3
wd settings put global package_verifier_enable 0
wd settings put global development_settings_enabled 1
wd settings put secure mock_location 1

if [[ -f "$HERE/apks/gpsfeeder.apk" ]]; then
  log "installing GPS feeder (mock location provider)"
  sudo waydroid app install "$HERE/apks/gpsfeeder.apk"
  wd appops set com.teslalinux.gpsfeeder android:mock_location allow
fi
if [[ -f "$HERE/apks/waze.apk" ]]; then
  log "installing Waze"
  sudo waydroid app install "$HERE/apks/waze.apk"
fi

log "forcing a full dex2oat now so it never happens at runtime"
wd cmd package compile -m speed-profile -a || true
wd cmd package bg-dexopt-job || true

log "GSF ID for Play certification (register at https://www.google.com/android/uncertified):"
wd sqlite3 /data/data/com.google.android.gsf/databases/gservices.db \
  "select * from main where name = 'android_id';" 2>/dev/null || echo "  (sqlite3 not in image; see docs)"

log "stopping container cleanly; /data is now baked"
sudo waydroid session stop || true
sudo waydroid container stop || true
sync
log "done"
