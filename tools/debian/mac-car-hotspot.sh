#!/usr/bin/env bash
# teslalinux: serve the Debian VM's car page to a real Tesla through the Mac's own Wi-Fi hotspot.
#
# The car's browser opens https://car.alexahub.dev, whose public DNS A record is the hijack IP
# (HIJACK_IP in host/phase1-pios/env, 192.0.2.1). On the Pi, nftables catches that address; here the
# Mac takes it instead: the address goes on lo0, so a car on the Mac's Internet Sharing network
# (default route = the Mac) reaches the Mac itself. macOS lets only root bind ports below 1024 on a
# specific address, so a pf redirect in the anchor com.apple/teslalinux (only for that address)
# turns 443 and 80 into 10443 and 10080, where two relays (tcp-relay.py) listen and pass the
# connections to the VM's forwarded nginx ports. TLS passes through untouched, so the car sees
# the shared, publicly trusted car certificate the VM fetched (carstack.sh install --cert-fetch).
#
#   mac-car-hotspot.sh up       check Internet Sharing, add the address, start the relays, keep awake
#   mac-car-hotspot.sh down     stop the relays and remove the address
#   mac-car-hotspot.sh status
#   mac-car-hotspot.sh check    fetch the page through the hijack address and show the certificate
#
# Internet Sharing itself is switched on by hand (System Settings > General > Sharing > Internet
# Sharing): share from a wired or USB uplink (phone USB tethering, Ethernet), to Wi-Fi, with a WPA2/
# WPA3 password. Wi-Fi cannot be both the uplink and the hotspot. The car needs that uplink to see
# the network as online, and Waze in the VM uses it too. `up` and `down` use sudo for the address
# and the pf rules.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
VMDIR="${DEB_VM_DIR:-$HOME/teslalinux-debvm}"
RUN="$VMDIR/hotspot"
ANCHOR=com.apple/teslalinux   # /etc/pf.conf loads rdr-anchor "com.apple/*"
RELAY_HTTPS=10443 RELAY_HTTP=10080
SUDO="sudo${SUDO_ASKPASS:+ -A}"   # SUDO_ASKPASS set: sudo asks through that helper
log() { printf '\033[1;36m[car-hotspot]\033[0m %s\n' "$*"; }
die() { log "ERROR: $*"; exit 1; }

envval() { sed -n "s/^$1=\([^ #]*\).*/\1/p" "$REPO/host/phase1-pios/env" | tr -d "'\"" | head -1; }
HIJACK="${HIJACK_IP:-$(envval HIJACK_IP)}"
HOST="${DEVICE_HOST:-$(envval DEVICE_HOST)}"
[[ -n $HIJACK && -n $HOST ]] || die "HIJACK_IP/DEVICE_HOST not found in host/phase1-pios/env"

vm_ports() { # https http, as forwarded by vm-deb.sh at the VM's start
  if [[ -f $VMDIR/car-ports ]]; then sed -n 's/^https=\([0-9]*\) http=\([0-9]*\)$/\1 \2/p' "$VMDIR/car-ports"; else echo "8443 8080"; fi
}

sharing_iface() { # the Internet Sharing bridge with its address, if sharing is on
  local i
  for i in $(ifconfig -l); do
    case $i in bridge1[0-9][0-9]) ifconfig "$i" | awk -v i="$i" '/inet /{print i, $2; exit}' ;; esac
  done
}

has_alias() { ifconfig lo0 | grep -q "inet $HIJACK "; }
pf_rules() {
  printf 'rdr pass inet proto tcp from any to %s port 443 -> %s port %s\n' "$HIJACK" "$HIJACK" "$RELAY_HTTPS"
  printf 'rdr pass inet proto tcp from any to %s port 80 -> %s port %s\n' "$HIJACK" "$HIJACK" "$RELAY_HTTP"
}
has_pf() { $SUDO pfctl -a "$ANCHOR" -s nat 2>/dev/null | grep -q "$HIJACK"; }
alive() { [[ -f $RUN/$1.pid ]] && kill -0 "$(cat "$RUN/$1.pid")" 2>/dev/null; }

start_bg() { # name cmd...
  local name=$1; shift
  alive "$name" && { log "$name already running (pid $(cat "$RUN/$name.pid"))"; return; }
  nohup "$@" > "$RUN/$name.log" 2>&1 < /dev/null &
  echo $! > "$RUN/$name.pid"
}

stop_bg() {
  if alive "$1"; then kill "$(cat "$RUN/$1.pid")" 2>/dev/null || true; log "stopped $1"; fi
  rm -f "$RUN/$1.pid"
}

cmd_up() {
  mkdir -p "$RUN"
  local share; share=$(sharing_iface)
  if [[ -z $share ]]; then
    log "WARNING: Internet Sharing is not on (no bridge1xx with an address). Turn it on in System Settings >"
    log "         General > Sharing > Internet Sharing: from the phone's USB tether or Ethernet, to Wi-Fi."
  else
    log "Internet Sharing on: ${share% *} at ${share#* } (cars get DHCP + DNS from it)"
  fi
  read -r https http < <(vm_ports)
  nc -z 127.0.0.1 "$https" 2>/dev/null || die "nothing on 127.0.0.1:$https: start the VM (tools/debvm/vm-deb.sh up) and the stack (carstack.sh start)"
  if ! has_alias; then
    log "adding $HIJACK to lo0 (sudo)"
    $SUDO ifconfig lo0 alias "$HIJACK/32"
  fi
  log "pf: $HIJACK:443 -> :$RELAY_HTTPS, :80 -> :$RELAY_HTTP (anchor $ANCHOR, sudo)"
  pf_rules | $SUDO pfctl -a "$ANCHOR" -f - 2>/dev/null
  if [[ ! -f $RUN/pf.token ]]; then # a reference on pf being enabled, released by down
    $SUDO pfctl -E 2>&1 | sed -n 's/^Token : //p' > "$RUN/pf.token"
  fi
  local py; py=$(command -v python3)
  start_bg relay-https "$py" -I "$HERE/tcp-relay.py" "$RELAY_HTTPS" "$https" --bind "$HIJACK"
  start_bg relay-http "$py" -I "$HERE/tcp-relay.py" "$RELAY_HTTP" "$http" --bind "$HIJACK"
  sleep 1
  alive relay-https || die "the 443 relay died: $(tail -3 "$RUN/relay-https.log")"
  alive relay-http || die "the 80 relay died: $(tail -3 "$RUN/relay-http.log")"
  # keep the Mac (and its display, so the hotspot is not dropped) awake while the relay runs
  start_bg caffeinate caffeinate -dimsu -w "$(cat "$RUN/relay-https.pid")"
  log "serving https://$HOST -> $HIJACK:443 -> VM nginx (127.0.0.1:$https); http :80 -> :$http"
  log "on the car: join the Mac's hotspot, then open https://$HOST in the browser"
}

cmd_down() {
  stop_bg relay-https; stop_bg relay-http; stop_bg caffeinate
  log "removing the pf rules (sudo)"
  $SUDO pfctl -a "$ANCHOR" -F nat 2>/dev/null || true
  if [[ -s $RUN/pf.token ]]; then $SUDO pfctl -X "$(cat "$RUN/pf.token")" 2>/dev/null || true; fi
  rm -f "$RUN/pf.token"
  if has_alias; then log "removing $HIJACK from lo0 (sudo)"; $SUDO ifconfig lo0 -alias "$HIJACK"; fi
}

cmd_status() {
  local share; share=$(sharing_iface)
  echo "internet sharing: ${share:-off}"
  if has_alias; then echo "lo0 alias: $HIJACK"; else echo "lo0 alias: none"; fi
  if has_pf; then echo "pf redirect: on"; else echo "pf redirect: off (or no sudo)"; fi
  local n
  for n in relay-https relay-http caffeinate; do
    if alive "$n"; then echo "$n: running (pid $(cat "$RUN/$n.pid"))"; else echo "$n: stopped"; fi
  done
}

cmd_check() {
  has_alias || die "no $HIJACK on lo0: run up first"
  # pf redirects the Mac's own connections on lo0 too, so this goes the car's way: 443, pf, relay, VM
  curl -sS -m 10 -o /dev/null -w "https://$HOST via $HIJACK:443: HTTP %{http_code}, %{size_download} bytes, cert verify=%{ssl_verify_result} (0 = trusted)\n" \
    --resolve "$HOST:443:$HIJACK" "https://$HOST/"
  echo | openssl s_client -connect "$HIJACK:443" -servername "$HOST" 2>/dev/null | openssl x509 -noout -subject -issuer -enddate
}

case "${1:-}" in
  up) cmd_up ;;
  down) cmd_down ;;
  status) cmd_status ;;
  check) cmd_check ;;
  *) sed -n '2,20p' "$0"; exit 2 ;;
esac
