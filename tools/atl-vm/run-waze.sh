#!/bin/bash
# teslalinux ATL test VM: launch Waze under ATL inside a headless Weston, capture the log and
# periodic screenshots. Run inside the guest:   ./run-waze.sh [seconds] [extra atl args...]
#
# Compositors: COMPOSITOR=weston (default, screenshots only) or COMPOSITOR=cage (wlroots headless with
# the pixman renderer; accepts wlrctl virtual pointer / wtype keyboard input and grim screenshots, so
# the app can be driven -- see tap.sh). cage needs WLR_RENDERER=pixman here: the VM has no DRM render
# node for the GLES2 renderer, and an earlier attempt without it was what "never answered".
set -u
COMPOSITOR="${COMPOSITOR:-weston}"
# The app is a directory holding the original signed base.apk plus the split_config.*.apk files this
# device needs (ATL takes several APKs: the first is the package, the rest add natives/resources).
# A merged, re-signed APK would lose the Waze signing certificate that Google APIs fingerprint.
# tools/apps/install-app.sh fills such a directory from an .apkm bundle (and updates it in place).
# WAZE_APP_DIR picks the directory; WAZE_APK (space-separated paths) overrides the whole set.
WAZE_APP_DIR="${WAZE_APP_DIR:-$HOME/waze/split}"
if [[ -n "${WAZE_APK:-}" ]]; then
  read -r -a APKS <<<"$WAZE_APK"
else
  APKS=("$WAZE_APP_DIR/base.apk")
  for f in "$WAZE_APP_DIR"/split_config.*.apk; do [[ -f "$f" ]] && APKS+=("$f"); done
fi
[[ -f "${APKS[0]}" ]] || { echo "no base.apk in $WAZE_APP_DIR" >&2; exit 1; }
SECS="${1:-60}"; shift || true
OUT="$HOME/waze/run-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$OUT"
# teslalinux: core dumps are 1.6 GB each; keep only the one of the previous run and give the freed
# blocks back to the host image (the Mac filled up once).
ls -t "$HOME"/waze/core.* 2>/dev/null | tail -n +2 | xargs rm -f 2>/dev/null; sudo -n fstrim / >/dev/null 2>&1 || true
export XDG_RUNTIME_DIR=/tmp/xdg-atl; mkdir -p "$XDG_RUNTIME_DIR"; chmod 700 "$XDG_RUNTIME_DIR"
if [[ "$COMPOSITOR" == "cage" ]]; then export WAYLAND_DISPLAY=wayland-0; else export WAYLAND_DISPLAY=wayland-atl; fi
export LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe MESA_GL_VERSION_OVERRIDE=3.3 MESA_GLES_VERSION_OVERRIDE=3.2
export GDK_BACKEND=wayland GSK_RENDERER=${GSK_RENDERER:-cairo}
# ATL knobs (see upstream doc/Envs.md)
# the test VM has no IPv6 route; without this the resolver hands Java AAAA records first and gRPC/okhttp
# connects fail with ENETUNREACH before falling back (the car LTE link will have a real v6 or none)
doas sysctl -q -w net.ipv6.conf.all.disable_ipv6=1 net.ipv6.conf.default.disable_ipv6=1 2>/dev/null || true
export ATL_UGLY_ENABLE_LOCATION=1 ATL_FORCE_FULLSCREEN=1 ATL_DISABLE_WINDOW_DECORATIONS=1
# location for the app (see android.location.LocationManager): the VM has no GNSS, so report a fixed
# position like the emulator geo fix does; the car uses tesla:9997 (the browser GPS bridge, services/gps/gps.py)
export ATL_LOCATION_SOURCE="${ATL_LOCATION_SOURCE:-fix:32.0853,34.7818,30}"
# display density (ro.sf.lcd_density equivalent); 160 is ATL default, the car screen wants more
[[ -n "${ATL_DENSITY_DPI:-}" ]] && export ATL_DENSITY_DPI
export ANDROID_LOG_TAGS="${ANDROID_LOG_TAGS:-*:d}"
export ANDROID_APP_DATA_DIR="${ANDROID_APP_DATA_DIR:-$HOME/waze/data}"
mkdir -p "$ANDROID_APP_DATA_DIR" "$HOME/.cache/art" "$HOME/.local/share/android_translation_layer"
# Prefer a locally built (patched) ATL if present.
if [[ -x "$HOME/atl-prefix/bin/android-translation-layer" ]]; then
  export PATH="$HOME/atl-prefix/bin:$PATH" LD_LIBRARY_PATH="$HOME/atl-prefix/lib:${LD_LIBRARY_PATH:-}"
fi

# Reuse a running headless Weston if its socket answers; otherwise (re)start it.
# --debug authorizes external screenshot clients (weston_capture_v1).
if ! timeout 10 wayland-info >/dev/null 2>&1; then
  if [[ "$COMPOSITOR" == "cage" ]]; then
    pkill -f "[c]age --" 2>/dev/null; sleep 1; rm -f "$XDG_RUNTIME_DIR"/wayland-0 "$XDG_RUNTIME_DIR"/wayland-0.lock
    # cage lives as long as its client; ATL connects as a second client and is shown fullscreen (kiosk)
    WLR_BACKENDS=headless WLR_LIBINPUT_NO_DEVICES=1 WLR_RENDERER=pixman \
      cage -- sleep 1000000 >"$OUT/cage.log" 2>&1 &
  else
    pkill -f "[w]eston --backend=headless" 2>/dev/null; sleep 1
    weston --backend=headless --renderer=pixman --socket="$WAYLAND_DISPLAY" --width=1088 --height=832 \
           --idle-time=0 --debug --log="$OUT/weston.log" >/dev/null 2>&1 &
  fi
  for _ in $(seq 1 60); do [[ -S "$XDG_RUNTIME_DIR/$WAYLAND_DISPLAY" ]] && break; sleep 0.5; done
  sleep 2
fi
timeout 10 wayland-info >/dev/null 2>&1 || { echo "compositor not answering" | tee "$OUT/info"; exit 1; }
echo "compositor $WAYLAND_DISPLAY ok; atl=$(command -v android-translation-layer)" | tee "$OUT/info"
# Persistent virtual pointer for tap.sh (see vpointer.c): one device for the life of this compositor,
# commands via ~/vpointer.fifo. Started per run because it must connect to this cage instance.
if [[ "$COMPOSITOR" == "cage" && -x "$HOME/vpointer/vpointer" ]]; then
  pkill -x -f "tail -f $HOME/vpointer.fifo" 2>/dev/null; pkill -x vpointer 2>/dev/null; sleep 0.2
  rm -f "$HOME/vpointer.fifo"; mkfifo "$HOME/vpointer.fifo"
  ( tail -f "$HOME/vpointer.fifo" | "$HOME/vpointer/vpointer" >"$OUT/vpointer.log" 2>&1 & )
fi

shot() {  # screenshot into $1 (grim on wlroots, weston-screenshooter on Weston)
  if command -v grim >/dev/null && grim "$1" 2>/dev/null; then return; fi
  if command -v weston-screenshooter >/dev/null; then
    ( cd "$OUT" && timeout 20 weston-screenshooter >/dev/null 2>&1 ) ; mv -f "$OUT"/wayland-screenshot*.png "$1" 2>/dev/null
  fi
}

# Session D-Bus for GtkApplication / portals: one persistent bus per XDG_RUNTIME_DIR, reused across
# runs (dbus-run-session would take the app down with it when its daemon exits). Then ATL, directly.
if [[ ! -S "$XDG_RUNTIME_DIR/bus" ]] || ! DBUS_SESSION_BUS_ADDRESS="unix:path=$XDG_RUNTIME_DIR/bus" dbus-send --session --dest=org.freedesktop.DBus --print-reply / org.freedesktop.DBus.ListNames >/dev/null 2>&1; then
  rm -f "$XDG_RUNTIME_DIR/bus"
  dbus-daemon --session --fork --address="unix:path=$XDG_RUNTIME_DIR/bus" >"$OUT/dbus.log" 2>&1
  sleep 1
fi
export DBUS_SESSION_BUS_ADDRESS="unix:path=$XDG_RUNTIME_DIR/bus"
# JDWP=port: start ART with a debugger port (ATL suspends until a debugger attaches); JDB_AT=secs:
# attach jdb, let it run, then at that moment suspend and dump every thread's Java stack to jdb.txt.
[[ -n "${JDWP:-}" ]] && export JDWP_LISTEN="$JDWP"
# core dumps for native crashes (kernel.core_pattern=/home/atl/waze/core.%p is set in the guest):
# gdb ~/atl-prefix/bin/android-translation-layer core.PID -ex "thread apply all bt 12"
ulimit -c unlimited 2>/dev/null || true
# GDB_RUN=1: run ATL under gdb so a native crash leaves a backtrace in atl.log (ART uses SIGSEGV for
# implicit null checks and a few realtime signals for suspension; those are passed through).
if [[ -n "${GDB_RUN:-}" ]]; then
  ( gdb -q -batch -ex "set pagination off" -ex "handle SIGSEGV SIGBUS SIGUSR1 SIGUSR2 SIGXCPU SIGPWR SIG33 SIG34 SIG35 SIGPIPE nostop noprint pass"       -ex run -ex "echo === crash backtrace ===
" -ex "bt 25" -ex "info threads" -ex "thread apply all bt 6"       --args android-translation-layer "${APKS[@]}" -w 1088 -h 832 --sdk-int ${ATL_SDK_INT:-32} "$@"; echo "exit=$?" ) >"$OUT/atl.log" 2>&1 &
else
  ( android-translation-layer "${APKS[@]}" -w 1088 -h 832 --sdk-int ${ATL_SDK_INT:-32} "$@"; echo "exit=$?" ) >"$OUT/atl.log" 2>&1 &
fi
RUNNER=$!
if [[ -n "${JDWP:-}" ]]; then
  ( for _ in $(seq 1 60); do (exec 3<>/dev/tcp/127.0.0.1/$JDWP) 2>/dev/null && break; sleep 3; done
    echo "jdwp port open, attaching jdb" | tee -a "$OUT/info"
    { echo "cont"; sleep "${JDB_AT:-60}"; echo "threads"; sleep 8; echo "suspend"; sleep 8; echo "threads"; sleep 5; echo "where all"; sleep 30
      for t in 0x1 0x2 0x3 0x4 0x5 0x6 0x7 0x8; do echo "thread $t"; sleep 2; echo "where"; sleep 4; done; echo "resume"; echo "quit"; } | /usr/lib/jvm/java-17-openjdk/bin/jdb -attach "$JDWP" > "$OUT/jdb.txt" 2>&1 ) &
fi
ATLPID=""
for i in $(seq 1 "$SECS"); do
  sleep 1
  # track the real ATL process (dbus-run-session wraps it; its exit is not the signal we want)
  if [[ -z "$ATLPID" ]]; then ATLPID=$(pgrep -f "^android-translation-layer " | head -1); [[ -n "$ATLPID" ]] && echo "atl pid=$ATLPID (after ${i}s)" | tee -a "$OUT/info"; fi
  if [[ -n "$ATLPID" ]] && ! kill -0 "$ATLPID" 2>/dev/null; then echo "ATL exited after ${i}s" | tee -a "$OUT/info"; break; fi
  if (( i % 20 == 0 )); then shot "$OUT/shot-$i.png"; fi
  if [[ "${STRACE:-}" == "1" && $i -eq 15 && -n "$ATLPID" ]]; then
    (doas strace -f -tt -e trace=kill,tgkill,tkill,exit_group,exit -o "$OUT/strace.txt" -p "$ATLPID" >/dev/null 2>&1 &)
  fi
  # GDB_AT=secs: native backtraces of every thread (gdb is in the guest); lands in gdb.txt
  if [[ -n "${GDB_AT:-}" && $i -eq ${GDB_AT} && -n "$ATLPID" ]]; then
    echo "gdb attach at ${i}s" | tee -a "$OUT/info"
    doas gdb -p "$ATLPID" -batch -ex "set pagination off" -ex "thread apply all bt 14" > "$OUT/gdb.txt" 2>&1
  fi
  # DUMP_AT=secs: ask ART for a full thread dump (SIGQUIT) at that moment; it lands in atl.log
  if [[ -n "${DUMP_AT:-}" && $i -eq ${DUMP_AT} && -n "$ATLPID" ]]; then kill -QUIT "$ATLPID" && echo "sent SIGQUIT to $ATLPID at ${i}s" | tee -a "$OUT/info"; fi
done
shot "$OUT/shot-final.png"
pkill -9 -f "[a]ndroid-translation-layer ${APKS[0]}" 2>/dev/null
kill $RUNNER 2>/dev/null
echo "--- last 40 log lines:"; tail -40 "$OUT/atl.log" | cut -c1-240
echo "--- output in $OUT"; ls "$OUT"
