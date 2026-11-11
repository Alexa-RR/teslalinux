#!/usr/bin/env bash
# teslalinux: the whole car stack inside the ATL test VM, wired to Waze, reachable from the Mac's
# browser: nginx (the car's rendered site, TLS with a self-signed certificate for the car's names),
# web/, tacomp (built from the repo's services/display), api.py, gps.py, audio.py, and Waze on
# tacomp's Wayland socket with its location from gps.py (ATL_LOCATION_SOURCE=tesla:127.0.0.1:9997).
#
# On the Mac (drives the guest through vm-mac.sh / vm.sh):
#   tools/atl-vm/carstack.sh up [--no-waze]   push the repo bits, install + build (first time), start
#   tools/atl-vm/carstack.sh push | install | start [--no-waze] | stop | status | check | logs
# In the guest (what the Mac side runs over ssh, detached, with a log in ~/carstack/logs):
#   sh ~/carstack/src/tools/atl-vm/carstack.sh guest-install | guest-start [--no-waze] | guest-stop | guest-status
#
# Reaching it from the Mac needs the VM started with ATL_VM_FWD_CAR=1 (tools/atl-vm/vm-mac.sh adds
# hostfwd 127.0.0.1:9443 -> guest 8443 and 9880 -> guest 8880; it only takes effect on the next VM
# start). Then open Chrome with the car's names mapped there, accept the self-signed certificate once:
#   open -na "Google Chrome" --args --user-data-dir=/tmp/teslalinux-chrome-vm \
#     --host-resolver-rules="MAP car.alexahub.dev:443 127.0.0.1:9443, MAP *.car.alexahub.dev:443 127.0.0.1:9443" \
#     https://car.alexahub.dev/
# (or an /etc/hosts line "127.0.0.1 car.alexahub.dev fullscreen.car.alexahub.dev" plus
# https://car.alexahub.dev:9443/; the Host header then carries :9443, which nginx ignores when
# matching server names).
#
# Shared VM etiquette: everything lives in ~/carstack and /tmp/xdg-atl (run-waze.sh's runtime dir);
# nothing system-wide changes except packages added on first install. CARSTACK_AUDIO=1 also routes
# Waze's audio to the browser: PipeWire with a null sink in ~/carstack/config and an ALSA "default"
# override that only carstack's processes see (ALSA_CONFIG_PATH); without it the app's sound stays on
# the VM's ALSA device as in every other run.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
log() { printf '\033[1;35m[carstack]\033[0m %s\n' "$*"; }
GUEST_HTTPS=8443
GUEST_HTTP=8880
MAC_HTTPS="${ATL_VM_FWD_CAR_HTTPS:-9443}"
MAC_HTTP="${ATL_VM_FWD_CAR_HTTP:-9880}"

# ================================================================ Mac side
mac_vm() { bash "$HERE/vm.sh" "$@"; }
REPO="$(cd "$HERE/../.." && pwd)"

mac_push() {
  local tgz="${TMPDIR:-/tmp}/carstack-src.tgz"
  # only what the car stack needs: web, services, host configs, the TLS tool, this script
  COPYFILE_DISABLE=1 tar -C "$REPO" -czf "$tgz" web services host tools/atl-vm/carstack.sh
  mac_vm push "$tgz" /tmp/carstack-src.tgz
  mac_vm ssh 'rm -rf ~/carstack/src && mkdir -p ~/carstack/src ~/carstack/logs && tar -xzf /tmp/carstack-src.tgz -C ~/carstack/src && rm -f /tmp/carstack-src.tgz'
  rm -f "$tgz"
  log "pushed web/ services/ host/ to the guest's ~/carstack/src"
}

# run a guest step detached (ssh steps that stay silent ~30 s or run longer than ~4 min come back
# with no output, so they must stay short) and wait for it
mac_guest_step() { # name args...
  local name=$1; shift
  mac_vm ssh "cd ~ && rm -f ~/carstack/logs/$name.rc && (nohup sh -c 'bash ~/carstack/src/tools/atl-vm/carstack.sh $name $*; echo \$? > ~/carstack/logs/$name.rc' > ~/carstack/logs/$name.log 2>&1 &)"
  for _ in $(seq 1 180); do
    sleep 5
    if mac_vm ssh "test -f ~/carstack/logs/$name.rc" 2>/dev/null; then
      local rc; rc=$(mac_vm ssh "cat ~/carstack/logs/$name.rc")
      mac_vm ssh "tail -n 25 ~/carstack/logs/$name.log"
      [[ "$rc" == 0 ]] || { log "$name failed (rc $rc), full log: ~/carstack/logs/$name.log in the guest"; return 1; }
      return 0
    fi
  done
  log "$name still running after 15 min; see ~/carstack/logs/$name.log in the guest"
  return 1
}

mac_check() {
  python3 "$REPO/host/local/check.py" --addr 127.0.0.1 --https-port "$MAC_HTTPS" --http-port "$MAC_HTTP" \
    --env "$REPO/host/phase1-pios/env" --expect-display
}

# ================================================================ guest side
G="$HOME/carstack"
SRC="$G/src"
ROOT="$G/root"
RUN="$G/run"
XDG=/tmp/xdg-atl
SOCK=wayland-atl
SIZE="${CARSTACK_SIZE:-1088x832@60}"  # run-waze.sh launches the app at 1088x832

pidof_file() { [[ -s "$1" ]] && kill -0 "$(cat "$1")" 2>/dev/null && cat "$1"; }
start_daemon() { # name cmd...
  local name=$1; shift
  if pidof_file "$RUN/$name.pid" >/dev/null; then log "$name already running"; return; fi
  nohup "$@" >>"$G/logs/$name.log" 2>&1 &
  echo $! >"$RUN/$name.pid"
}

guest_install() {
  [[ -f "$SRC/host/teslalinux-render" ]] || { log "no sources in $SRC: run 'carstack.sh push' on the Mac"; exit 1; }
  local pkgs="nginx python3 py3-websockets openssl curl ffmpeg build-base pkgconf wlroots0.20-dev libwebsockets-dev
    wayland-dev wayland-protocols pixman-dev libdrm-dev libxkbcommon-dev libjpeg-turbo-dev linux-headers"
  [[ "${CARSTACK_AUDIO:-0}" == 1 ]] && pkgs="$pkgs pipewire pipewire-pulse wireplumber pipewire-alsa"
  local missing=""
  for p in $pkgs; do apk info -e "$p" >/dev/null 2>&1 || missing="$missing $p"; done
  if [[ -n "$missing" ]]; then
    log "installing:$missing"
    # an ALSA default that pipewire-alsa may add globally is taken back out below, so the other
    # Waze runs in this VM keep their ALSA setup; carstack scopes its own override (ALSA_CONFIG_PATH)
    local had_default=0; [[ -e /etc/alsa/conf.d/99-pipewire-default.conf ]] && had_default=1
    # shellcheck disable=SC2086
    doas apk add -q $missing
    if [[ $had_default == 0 && -L /etc/alsa/conf.d/99-pipewire-default.conf ]]; then
      doas rm -f /etc/alsa/conf.d/99-pipewire-default.conf
      log "removed the global ALSA default pipewire-alsa installed (carstack sets its own per process)"
    fi
  fi
  log "building tacomp from the pushed services/display"
  sh "$SRC/services/display/build.sh" "$G/tacomp"
}

guest_setup() {
  mkdir -p "$G"/{logs,run,state,tmp,config/pipewire/pipewire.conf.d,config/wireplumber/wireplumber.conf.d}
  { cat "$SRC/host/phase1-pios/env"
    printf '\n# carstack overrides\nTLS_CERT=%s\nTLS_KEY=%s\nTLS_BASE_URL=\n' "$G/tls/fullchain.pem" "$G/tls/privkey.pem"
  } >"$G/env"
  TESLALINUX_ENV="$G/env" TLS_STATE="$G/state/tls.json" TLS_BUILD_TIME_FILE=/nonexistent \
    TLS_BUNDLE_DIR=/nonexistent TLS_NO_RELOAD=1 TLS_PIDFILE="$RUN/tls.pid" sh "$SRC/host/tls/teslalinux-tls" init 2>>"$G/logs/tls.log"
  python3 "$SRC/host/teslalinux-render" --root "$ROOT" --live --layout alpine --only web \
    --templates "$SRC/host/phase1-pios/etc" --env "$G/env" --state "$G/state/props.json" \
    --web-root "$SRC/web" --http-port "$GUEST_HTTP" --https-port "$GUEST_HTTPS"
  cat >"$G/nginx.conf" <<EOF
# carstack: the car's rendered site, as the atl user on unprivileged ports (forwarded to the Mac)
worker_processes 2;
pid $RUN/nginx.pid;
error_log $G/logs/nginx-error.log warn;
events { worker_connections 512; }
http {
  access_log $G/logs/nginx-access.log;
  client_body_temp_path $G/tmp/body;
  proxy_temp_path $G/tmp/proxy;
  fastcgi_temp_path $G/tmp/fastcgi;
  uwsgi_temp_path $G/tmp/uwsgi;
  scgi_temp_path $G/tmp/scgi;
  sendfile on;
  include $ROOT/etc/nginx/http.d/teslalinux.conf;
}
EOF
  nginx -t -q -c "$G/nginx.conf" -p "$G/"
  # the app's sound (CARSTACK_AUDIO=1): the car image's null sink, in carstack's own config dir
  cat >"$G/config/pipewire/pipewire.conf.d/teslalinux.conf" <<'EOF'
context.objects = [
  { factory = adapter
    args = { factory.name = support.null-audio-sink node.name = "android" node.description = "Android"
             media.class = "Audio/Sink" audio.position = [ FL FR ] audio.rate = 48000
             object.linger = true priority.session = 3000 priority.driver = 3000 } }
]
EOF
  cat >"$G/config/wireplumber/wireplumber.conf.d/teslalinux.conf" <<'EOF'
wireplumber.profiles = { main = { monitor.alsa = disabled monitor.alsa-midi = disabled monitor.bluez = disabled monitor.v4l2 = disabled monitor.libcamera = disabled } }
EOF
  printf 'pcm.!default { type pipewire }\nctl.!default { type pipewire }\n' >"$G/asound.conf"
}

guest_start() {
  local waze=1
  [[ "${1:-}" == --no-waze ]] && waze=0
  [[ -x "$G/tacomp/tacomp" ]] || { log "no tacomp build: run guest-install first"; exit 1; }
  guest_setup
  mkdir -p "$XDG"; chmod 700 "$XDG"
  export XDG_RUNTIME_DIR="$XDG"
  if [[ "${CARSTACK_AUDIO:-0}" == 1 ]]; then
    export XDG_CONFIG_HOME="$G/config" PIPEWIRE_RUNTIME_DIR="$XDG"
    start_daemon pipewire pipewire
    for _ in $(seq 1 50); do [[ -S "$XDG/pipewire-0" ]] && break; sleep 0.1; done
    start_daemon wireplumber wireplumber
    start_daemon pipewire-pulse pipewire-pulse
    unset XDG_CONFIG_HOME
  fi
  # tacomp on run-waze.sh's socket; its WebSockets only on loopback, behind nginx
  if ! pidof_file "$RUN/tacomp.pid" >/dev/null; then
    rm -f "$XDG/$SOCK" "$XDG/$SOCK.lock"
    WLR_BACKENDS=headless WLR_RENDERER=pixman WAYLAND_DISPLAY="$SOCK" TACOMP_SIZE="$SIZE" TACOMP_BIND=127.0.0.1 \
      start_daemon tacomp "$G/tacomp/tacomp"
    for _ in $(seq 1 50); do [[ -S "$XDG/$SOCK" ]] && break; sleep 0.1; done
  fi
  export TESLALINUX_ENV="$G/env" TESLALINUX_STATE="$G/state/props.json" TESLALINUX_APPLY_SUDO=0 \
    TESLALINUX_APPLY_DISPLAY="$SRC/host/alpine/teslalinux-apply-display" TESLALINUX_DISPLAY_ENV="$G/state/display.env" \
    TESLALINUX_CERT_STATE="$G/state/tls.json" \
    PULSE_SERVER="unix:$XDG/pulse/native"
  start_daemon api python3 "$SRC/services/api/api.py"
  start_daemon gps python3 "$SRC/services/gps/gps.py"
  start_daemon audio python3 "$SRC/services/audio/audio.py"
  if pidof_file "$RUN/nginx.pid" >/dev/null; then nginx -c "$G/nginx.conf" -p "$G/" -s reload; else nginx -c "$G/nginx.conf" -p "$G/"; fi
  if [[ $waze == 1 ]]; then
    if pgrep -f "[r]un-waze.sh" >/dev/null; then
      log "a run-waze.sh is already running in this VM: not starting another (stop it, or use --no-waze)"
    else
      local alsa=""
      [[ "${CARSTACK_AUDIO:-0}" == 1 ]] && alsa="/usr/share/alsa/alsa.conf:$G/asound.conf"
      # COMPOSITOR=weston + an answering socket: run-waze.sh reuses tacomp and starts no compositor
      (cd ~ && env COMPOSITOR=weston WAYLAND_DISPLAY="$SOCK" ATL_LOCATION_SOURCE=tesla:127.0.0.1:9997 \
        ${alsa:+ALSA_CONFIG_PATH="$alsa"} nohup ./run-waze.sh "${CARSTACK_WAZE_SECS:-3600}" >"$G/logs/waze.out" 2>&1 &)
      log "Waze starting on tacomp (about 10 min to a usable screen under llvmpipe); log ~/carstack/logs/waze.out"
    fi
  fi
  guest_status
}

guest_stop() {
  for name in audio gps api tacomp pipewire-pulse wireplumber pipewire; do
    if p=$(pidof_file "$RUN/$name.pid"); then kill "$p" 2>/dev/null || true; fi
    rm -f "$RUN/$name.pid"
  done
  if pidof_file "$RUN/nginx.pid" >/dev/null; then nginx -c "$G/nginx.conf" -p "$G/" -s quit || true; fi
  log "stopped (a Waze started by carstack ends with its compositor; stop run-waze.sh in its own ssh call)"
}

guest_status() {
  for name in nginx tacomp api gps audio pipewire wireplumber pipewire-pulse; do
    if p=$(pidof_file "$RUN/$name.pid"); then printf '  %-15s running (pid %s)\n' "$name" "$p"
    elif [[ -e "$RUN/$name.pid" ]]; then printf '  %-15s DIED (see ~/carstack/logs/%s.log)\n' "$name" "$name"
    fi
  done
  pgrep -f "[r]un-waze.sh" >/dev/null && echo "  waze            run-waze.sh running" || echo "  waze            not running"
}

case "${1:-}" in
  push) mac_push ;;
  install) mac_guest_step guest-install ;;
  start) shift; mac_guest_step guest-start "$@" ;;
  up) shift; mac_push; mac_guest_step guest-install; mac_guest_step guest-start "$@"
      log "from the Mac (VM started with ATL_VM_FWD_CAR=1): carstack.sh check, then Chrome as in this script's header" ;;
  stop) mac_vm ssh 'bash ~/carstack/src/tools/atl-vm/carstack.sh guest-stop'
        mac_vm ssh 'pkill -f "[r]un-waze.sh" || true' ;;
  status) mac_vm ssh 'bash ~/carstack/src/tools/atl-vm/carstack.sh guest-status' ;;
  check) mac_check ;;
  logs) mac_vm ssh "tail -n ${2:-20} ~/carstack/logs/*.log" ;;
  guest-install) guest_install ;;
  guest-start) shift; guest_start "$@" ;;
  guest-stop) guest_stop ;;
  guest-status) guest_status ;;
  *) awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"; exit 2 ;;
esac
