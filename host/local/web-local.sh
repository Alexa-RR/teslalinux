#!/usr/bin/env bash
# teslalinux: the car's web stack on this machine (the Mac, or any Linux box), for working on nginx,
# the frontend and the /api + /sockets services without a Pi or the VM.
#
#   host/local/web-local.sh start | stop | restart | status | check | logs
#
# What runs (all real components, rendered from the same templates as the car):
#   nginx    https :8443 and http :8880 with host/phase1-pios/etc rendered by host/teslalinux-render
#            (--live, ports moved above 1024), web/ served straight from the repo (edit + reload)
#   api.py   :8081 with its own settings file; display changes go to the real
#            host/alpine/teslalinux-apply-display, which only writes display.env here
#   gps.py   :9998 (feed :9997), audio.py :8080 (the PipeWire source does not exist on macOS, so the
#            socket opens and ffmpeg retries; browser audio is off by default anyway)
#   TLS      teslalinux-tls init makes a key and a self-signed certificate for DEVICE_HOST and its
#            www./fullscreen. names in out/web-local/tls
# Not here: tacomp (wlroots, Linux only) and the app, so /sockets/display and /sockets/touchscreen
# answer 502 and the page shows its loading state. The full chain with Waze is
# tools/atl-vm/carstack.sh in the test VM.
#
# Open it: Chrome with the hostname mapped to this machine (the Host header stays the car's name, so
# nginx picks the same server block as in the car); accept the self-signed certificate once
# ("Advanced" -> "Proceed"):
#   open -na "Google Chrome" --args --user-data-dir=/tmp/teslalinux-chrome \
#     --host-resolver-rules="MAP car.alexahub.dev:443 127.0.0.1:8443, MAP *.car.alexahub.dev:443 127.0.0.1:8443" \
#     https://car.alexahub.dev/
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
W="${WEB_LOCAL_DIR:-$REPO/out/web-local}"
ROOT="$W/root"
HTTPS_PORT="${WEB_LOCAL_HTTPS_PORT:-8443}"
HTTP_PORT="${WEB_LOCAL_HTTP_PORT:-8880}"
NGINX="${NGINX:-$(command -v nginx || echo /opt/homebrew/bin/nginx)}"
PY="$W/venv/bin/python"
log() { printf '\033[1;36m[web-local]\033[0m %s\n' "$*"; }
pidof_file() { [[ -s "$1" ]] && kill -0 "$(cat "$1")" 2>/dev/null && cat "$1"; }

setup() {
  mkdir -p "$W"/{logs,run,state,tmp}
  [[ -x "$NGINX" ]] || { log "no nginx ($NGINX); on macOS: brew install nginx"; exit 1; }
  if [[ ! -x "$PY" ]] || ! "$PY" -c 'import websockets' 2>/dev/null; then
    log "python venv with websockets in $W/venv"
    python3 -m venv "$W/venv" && "$PY" -m pip install -q websockets
  fi
  # the car's env with local TLS paths
  { cat "$REPO/host/phase1-pios/env"
    printf '\n# web-local overrides\nTLS_CERT=%s\nTLS_KEY=%s\nTLS_BASE_URL=\n' "$W/tls/fullchain.pem" "$W/tls/privkey.pem"
  } >"$W/env"
  export TESLALINUX_ENV="$W/env" TLS_STATE="$W/state/tls.json" \
    TLS_BUILD_TIME_FILE=/nonexistent TLS_BUNDLE_DIR=/nonexistent TLS_NO_RELOAD=1 TLS_PIDFILE="$W/run/tls.pid"
  sh "$REPO/host/tls/teslalinux-tls" init 2>>"$W/logs/tls.log"
  python3 "$REPO/host/teslalinux-render" --root "$ROOT" --live --layout alpine --only web \
    --templates "$REPO/host/phase1-pios/etc" --env "$W/env" --state "$W/state/props.json" \
    --web-root "$REPO/web" --http-port "$HTTP_PORT" --https-port "$HTTPS_PORT"
  cat >"$W/nginx.conf" <<EOF
# web-local: a self-contained nginx around the car's rendered site
worker_processes 1;
pid $W/run/nginx.pid;
error_log $W/logs/nginx-error.log warn;
events { worker_connections 256; }
http {
  access_log $W/logs/nginx-access.log;
  client_body_temp_path $W/tmp/body;
  proxy_temp_path $W/tmp/proxy;
  fastcgi_temp_path $W/tmp/fastcgi;
  uwsgi_temp_path $W/tmp/uwsgi;
  scgi_temp_path $W/tmp/scgi;
  sendfile on;
  include $ROOT/etc/nginx/http.d/teslalinux.conf;
}
EOF
  "$NGINX" -t -q -c "$W/nginx.conf" -p "$W/"
}

start_daemon() { # name cmd...
  local name=$1; shift
  if pidof_file "$W/run/$name.pid" >/dev/null; then log "$name already running"; return; fi
  nohup "$@" >>"$W/logs/$name.log" 2>&1 &
  echo $! >"$W/run/$name.pid"
}

start() {
  setup
  export TESLALINUX_ENV="$W/env" TESLALINUX_STATE="$W/state/props.json" TESLALINUX_APPLY_SUDO=0 \
    TESLALINUX_APPLY_DISPLAY="$REPO/host/alpine/teslalinux-apply-display" TESLALINUX_DISPLAY_ENV="$W/state/display.env" \
    TESLALINUX_CERT_STATE="$W/state/tls.json"
  start_daemon api env PYTHONPATH="$REPO/services/api" "$PY" "$REPO/services/api/api.py"
  start_daemon gps "$PY" "$REPO/services/gps/gps.py"
  start_daemon audio "$PY" "$REPO/services/audio/audio.py"
  if pidof_file "$W/run/nginx.pid" >/dev/null; then
    "$NGINX" -c "$W/nginx.conf" -p "$W/" -s reload
  else
    "$NGINX" -c "$W/nginx.conf" -p "$W/"
  fi
  sleep 1
  status
  log "open: https://car.alexahub.dev/ via Chrome --host-resolver-rules (see the header of this script)"
}

stop() {
  for name in api gps audio; do
    if p=$(pidof_file "$W/run/$name.pid"); then kill "$p" 2>/dev/null || true; fi
    rm -f "$W/run/$name.pid"
  done
  if pidof_file "$W/run/nginx.pid" >/dev/null; then "$NGINX" -c "$W/nginx.conf" -p "$W/" -s quit || true; fi
  log "stopped"
}

status() {
  for name in nginx api gps audio; do
    if p=$(pidof_file "$W/run/$name.pid"); then printf '  %-6s running (pid %s)\n' "$name" "$p"; else printf '  %-6s stopped\n' "$name"; fi
  done
}

case "${1:-}" in
  start) start ;;
  stop) stop ;;
  restart) stop; start ;;
  status) status ;;
  check) exec python3 "$HERE/check.py" --https-port "$HTTPS_PORT" --http-port "$HTTP_PORT" --env "$W/env" ;;
  logs) tail -n "${2:-30}" "$W"/logs/*.log ;;
  *) sed -n '2,27s/^# \{0,1\}//p' "$0"; exit 2 ;;
esac
