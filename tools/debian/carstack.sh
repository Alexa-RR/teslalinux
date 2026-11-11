#!/usr/bin/env bash
# teslalinux: the car stack installed natively on Debian trixie arm64 the way the Raspberry Pi OS car
# runs it (systemd units from host/phase1-pios/etc/systemd as the `tesla` user, nginx on 443/80 with the
# car's site rendered by host/teslalinux-render --layout pios, api.py/gps.py/audio.py, PipeWire with
# the "android" null sink, tacomp, Waze under our ATL in /opt/teslalinux), first for the Debian test
# VM (tools/debvm). The ATL stack and tacomp come from tools/debvm/build-dist.sh (/opt/teslalinux,
# /opt/teslalinux/tacomp); this script installs and wires them, it builds nothing.
#
# On the Mac (drives the Debian VM through tools/debvm/vm-deb.sh):
#   tools/debian/carstack.sh push                 the repo bits the stack needs -> guest ~/teslalinux
#   tools/debian/carstack.sh install [--radio]    push + system install in the guest (default: --no-radio --vm)
#   tools/debian/carstack.sh app DIR|BUNDLE       install the app (tools/apps/install-app.sh) as tesla
#   tools/debian/carstack.sh start|stop|restart|status|logs [N]
#   tools/debian/carstack.sh check                host/local/check.py against the forwarded ports
#   tools/debian/carstack.sh frame OUT.jpg [--tap X,Y]   one display frame (and a tap) over tacomp's WS
#   tools/debian/carstack.sh chrome               open Chrome on the car page through the forwards
# In the guest (as root; what `install` runs):
#   sudo bash ~/teslalinux/tools/debian/carstack.sh guest-install [--no-radio] [--vm] [--self-signed]
#
# --no-radio  no hotspot and no LTE: hostapd, dnsmasq, nftables, the static AP address, ModemManager,
#             NetworkManager tweaks and the Pi boot config are skipped (a VM or a bench box has no radios)
# --vm        the VM has no GPU, no V4L2 encoder and no IPv6 route: tacomp on pixman (its m2m mode
#             falls back to CPU JPEG by itself), ATL on GTK's cairo renderer with Mesa llvmpipe for the
#             app's GLES, IPv6 off (Java resolves AAAA first and gRPC/okhttp fail with ENETUNREACH)
# --self-signed  keep teslalinux-tls's self-signed fallback (no fetch timer); without it the machine
#             fetches the shared car certificate like a car does (--cert-fetch)
# guest-install defaults to all three when run in a VM (systemd-detect-virt), to none otherwise.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
log() { printf '\033[1;36m[carstack-deb]\033[0m %s\n' "$*"; }
die() { log "ERROR: $*"; exit 1; }

# ================================================================ Mac side
VMDEB="$REPO/tools/debvm/vm-deb.sh"
vm() { bash "$VMDEB" "$@"; }
car_ports() { # https http, as forwarded by vm-deb.sh at the VM's start
  local f="${DEB_VM_DIR:-$HOME/teslalinux-debvm}/car-ports"
  if [[ -f "$f" ]]; then sed -n 's/^https=\([0-9]*\) http=\([0-9]*\)$/\1 \2/p' "$f"; else echo "8443 8080"; fi
}

mac_push() {
  local tgz; tgz="$(mktemp -t carstack-deb).tgz"
  COPYFILE_DISABLE=1 tar --no-mac-metadata --no-xattrs -C "$REPO" -czf "$tgz" --exclude '__pycache__' --exclude 'services/display/build' \
    web services host tools/apps tools/debian tools/atl-vm/mk-android-tzdata.py
  vm push "$tgz" /tmp/carstack-src.tgz
  vm ssh 'rm -rf ~/teslalinux.new && mkdir ~/teslalinux.new && tar -xzf /tmp/carstack-src.tgz -C ~/teslalinux.new && rm -f /tmp/carstack-src.tgz && rm -rf ~/teslalinux && mv ~/teslalinux.new ~/teslalinux && mkdir -p ~/carstack-logs'
  rm -f "$tgz"
  log "pushed web/ services/ host/ tools/{apps,debian} to the guest's ~/teslalinux"
}

# a guest step as root, detached (silent ssh calls longer than ~30 s come back empty), then poll
mac_guest_root() { # name args...
  local name=$1; shift
  vm ssh "rm -f ~/carstack-logs/$name.rc; setsid sh -c 'sudo bash ~/teslalinux/tools/debian/carstack.sh $name $*; echo \$? > ~/carstack-logs/$name.rc' > ~/carstack-logs/$name.log 2>&1 < /dev/null &"
  local i
  for i in $(seq 1 360); do
    sleep 5
    if vm ssh "test -f ~/carstack-logs/$name.rc" 2>/dev/null; then
      local rc; rc=$(vm ssh "cat ~/carstack-logs/$name.rc")
      vm ssh "tail -n 30 ~/carstack-logs/$name.log"
      [[ "$rc" == 0 ]] || { log "$name failed (rc $rc); full log: ~/carstack-logs/$name.log in the guest"; return 1; }
      return 0
    fi
    (( i % 12 == 0 )) && vm ssh "tail -n 2 ~/carstack-logs/$name.log" 2>/dev/null | sed 's/^/  ... /'
  done
  log "$name still running after 30 min; see ~/carstack-logs/$name.log in the guest"; return 1
}

mac_app() {
  local src=${1:?usage: carstack.sh app DIR-of-APKs|BUNDLE}
  local tgz; tgz="$(mktemp -t carstack-app).tgz"
  if [[ -d "$src" ]]; then COPYFILE_DISABLE=1 tar --no-mac-metadata --no-xattrs -C "$src" -czf "$tgz" .
  else COPYFILE_DISABLE=1 tar --no-mac-metadata --no-xattrs -C "$(dirname "$src")" -czf "$tgz" "$(basename "$src")"; fi
  vm push "$tgz" /tmp/carstack-app.tgz
  rm -f "$tgz"
  vm ssh 'rm -rf /tmp/carstack-app && mkdir -p /tmp/carstack-app && tar -xzf /tmp/carstack-app.tgz -C /tmp/carstack-app && rm -f /tmp/carstack-app.tgz'
  if [[ -d "$src" ]]; then mac_guest_root guest-app /tmp/carstack-app
  else mac_guest_root guest-app "/tmp/carstack-app/$(basename "$src")"; fi
}

mac_check() {
  local p; read -r -a p <<<"$(car_ports)"
  python3 "$REPO/host/local/check.py" --addr 127.0.0.1 --https-port "${p[0]}" --http-port "${p[1]}" \
    --env "$REPO/host/phase1-pios/env" --expect-display
}

mac_frame() { # OUT.jpg [--tap X,Y ...]: through tacomp's own WebSockets (loopback in the guest)
  local out=${1:?usage: carstack.sh frame OUT.jpg [--tap X,Y]}; shift
  local f
  f=$(vm ssh "rm -rf /tmp/tl-frame && python3 ~/teslalinux/services/display/test/ws-client.py --out /tmp/tl-frame --frames 1 --wait 6 $* >/dev/null 2>&1; ls -t /tmp/tl-frame/*.jpg | head -1" | tail -1) || true
  [[ -n "$f" ]] || die "no frame from tacomp"
  vm pull "$f" "$out"
  log "frame -> $out"
}

mac_chrome() {
  local p; read -r -a p <<<"$(car_ports)"
  local host; host=$(sed -n 's/^DEVICE_HOST=\([^ #]*\).*/\1/p' "$REPO/host/phase1-pios/env")
  open -na "Google Chrome" --args --user-data-dir="${TMPDIR:-/tmp}/teslalinux-chrome-debvm" --ignore-certificate-errors \
    --host-resolver-rules="MAP $host:443 127.0.0.1:${p[0]}, MAP *.$host:443 127.0.0.1:${p[0]}, MAP $host:80 127.0.0.1:${p[1]}" \
    "https://$host/"
  log "Chrome: https://$host/ -> 127.0.0.1:${p[0]} (separate profile; certificate errors ignored in it)"
}

# ================================================================ guest side (root)
ENVF=/etc/teslalinux/env
LIB=/usr/local/lib/teslalinux
PREFIX=/opt/teslalinux
APPS=/var/lib/teslalinux/apps
UNITS="teslalinux-dbus teslalinux-uplink tacomp teslalinux-api teslalinux-gps teslalinux-audio teslalinux-pipewire teslalinux-wireplumber teslalinux-pipewire-pulse teslalinux-atl"

guest_install() {
  [[ $EUID -eq 0 ]] || die "run as root"
  local radio=1 vmmode=0 selfsigned=0
  if systemd-detect-virt -q --vm 2>/dev/null; then radio=0; vmmode=1; selfsigned=1; fi
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --no-radio) radio=0 ;; --radio) radio=1 ;;
      --vm) vmmode=1 ;; --no-vm) vmmode=0 ;;
      --self-signed) selfsigned=1 ;; --cert-fetch) selfsigned=0 ;;
      *) die "unknown option $1" ;;
    esac; shift
  done
  log "radio=$radio vm=$vmmode self-signed=$selfsigned"
  [[ -x $PREFIX/bin/android-translation-layer ]] || log "NOTE: no ATL in $PREFIX yet (tools/debvm/build-dist.sh atl); teslalinux-atl stays inactive until it exists"
  [[ -x $PREFIX/tacomp/bin/tacomp ]] || log "NOTE: no tacomp in $PREFIX/tacomp yet (build-dist.sh tacomp); tacomp.service stays inactive until it exists"
  local render=(python3 "$REPO/host/teslalinux-render" --root / --layout pios --templates /usr/share/teslalinux/templates --env "$ENVF")

  log "1/7 packages"
  export DEBIAN_FRONTEND=noninteractive
  local pkgs="nginx python3 python3-websockets openssl curl rsync ffmpeg ca-certificates dbus
    pipewire pipewire-pulse pipewire-alsa wireplumber libasound2-plugins seatd"
  [[ $radio == 1 ]] && pkgs="$pkgs hostapd dnsmasq nftables network-manager modemmanager usb-modeswitch usb-modeswitch-data"
  local missing="" p
  for p in $pkgs; do dpkg-query -W -f '${Status}' "$p" 2>/dev/null | grep -q 'install ok installed' || missing="$missing $p"; done
  if [[ -n $missing ]]; then
    apt-get update -q
    # shellcheck disable=SC2086
    apt-get install -y -q --no-install-recommends $missing
  fi
  # Debian's nginx package starts the default site on :80 at install; ours replaces it below
  systemctl stop nginx 2>/dev/null || true

  log "2/7 the tesla user (owns the Wayland socket, PipeWire, the app) and its runtime dir"
  id tesla >/dev/null 2>&1 || useradd -r -m -d /var/lib/tesla -s /usr/sbin/nologin tesla
  usermod -aG video,render,input,audio tesla
  echo 'd /run/tesla 0700 tesla tesla -' > /etc/tmpfiles.d/tesla.conf
  systemd-tmpfiles --create /etc/tmpfiles.d/tesla.conf

  log "3/7 files: env, renderer + templates, services, launcher, apply-display"
  install -d -m 0755 /etc/teslalinux $LIB /usr/share/teslalinux/templates /var/lib/teslalinux /var/log/teslalinux
  install -d -m 0750 -o tesla -g tesla /var/lib/teslalinux/state
  install -d -m 0755 -o tesla -g tesla $APPS
  [[ -f $ENVF ]] || install -m 0644 "$REPO/host/phase1-pios/env" $ENVF
  rm -rf /usr/share/teslalinux/templates; install -d /usr/share/teslalinux/templates
  cp -r "$REPO/host/phase1-pios/etc/." /usr/share/teslalinux/templates/
  install -m 0755 "$REPO/host/teslalinux-render" /usr/local/bin/teslalinux-render
  install -m 0644 "$REPO/services/gps/gps.py" "$REPO/services/audio/audio.py" "$REPO/services/api/api.py" \
    "$REPO/services/api/tlprops.py" $LIB/
  install -d $LIB/apps
  install -m 0755 "$REPO/tools/apps/install-app.sh" "$REPO/tools/apps/apk-info.py" $LIB/apps/
  install -m 0755 "$REPO/host/phase1-pios/teslalinux-apply-display" /usr/local/bin/teslalinux-apply-display
  install -m 0755 "$REPO/host/phase1-pios/teslalinux-atl" /usr/local/bin/teslalinux-atl
  install -m 0755 "$REPO/host/teslalinux-restart-app" /usr/local/bin/teslalinux-restart-app
  install -m 0644 "$REPO/tools/atl-vm/mk-android-tzdata.py" $LIB/mk-android-tzdata.py
  install -m 0755 "$REPO/host/teslalinux-set-locale" /usr/local/bin/teslalinux-set-locale
  install -m 0755 "$REPO/host/teslalinux-set-keyboard" /usr/local/bin/teslalinux-set-keyboard
  install -m 0755 "$REPO/host/teslalinux-network" /usr/local/bin/teslalinux-network
  install -m 0755 "$REPO/host/teslalinux-system-update" /usr/local/bin/teslalinux-system-update
  install -m 0755 "$REPO/host/teslalinux-uplink" /usr/local/bin/teslalinux-uplink
  install -m 0755 "$REPO/host/teslalinux-dexopt" /usr/local/bin/teslalinux-dexopt
  echo 'tesla ALL=(root) NOPASSWD: /usr/local/bin/teslalinux-apply-display, /usr/local/bin/teslalinux-restart-app, /usr/local/bin/teslalinux-set-locale, /usr/local/bin/teslalinux-set-keyboard, /usr/local/bin/teslalinux-network, /usr/local/bin/teslalinux-system-update' > /etc/sudoers.d/teslalinux
  chmod 0440 /etc/sudoers.d/teslalinux
  # the binaries built by build-dist.sh, where the units look for them
  [[ -x $PREFIX/tacomp/bin/tacomp ]] && ln -sfn $PREFIX/tacomp/bin/tacomp /usr/local/bin/tacomp
  # Android apps open fonts by their Android path
  install -d /system/fonts
  for p in /usr/share/fonts/truetype/roboto/unhinted/RobotoTTF/*.ttf /usr/share/fonts/truetype/noto/*.ttf; do
    [[ -f $p ]] && ln -sf "$p" /system/fonts/
  done

  if [[ $radio == 1 ]]; then
    log "4/7 hotspot: hostapd + dnsmasq + nftables (rendered), NetworkManager off the AP interface"
    . $ENVF
    "${render[@]}" --only net
    install -d /etc/NetworkManager/conf.d
    printf '[keyfile]\nunmanaged-devices=interface-name:%s\n' "$AP_IFACE" > /etc/NetworkManager/conf.d/teslalinux-unmanaged.conf
    printf 'net.ipv4.ip_forward=1\n' > /etc/sysctl.d/90-teslalinux-net.conf
    systemctl unmask hostapd
    systemctl enable hostapd dnsmasq nftables ModemManager NetworkManager
  else
    log "4/7 hotspot + LTE: skipped (--no-radio)"
  fi
  printf 'net.core.rmem_max=8388608\nnet.core.wmem_max=8388608\n' > /etc/sysctl.d/90-teslalinux.conf
  if [[ $vmmode == 1 ]]; then
    printf 'net.ipv6.conf.all.disable_ipv6=1\nnet.ipv6.conf.default.disable_ipv6=1\n' > /etc/sysctl.d/91-teslalinux-vm.conf
  else rm -f /etc/sysctl.d/91-teslalinux-vm.conf; fi
  sysctl -q --system >/dev/null || true

  log "5/7 nginx + web frontend + TLS"
  install -d /var/www/teslalinux
  rsync -a --delete "$REPO/web/" /var/www/teslalinux/
  "${render[@]}" --only web
  "${render[@]}" --only display
  ln -sf /etc/nginx/sites-available/teslalinux /etc/nginx/sites-enabled/teslalinux
  rm -f /etc/nginx/sites-enabled/default
  install -m 0755 "$REPO/host/tls/teslalinux-tls" /usr/local/bin/teslalinux-tls
  install -d -m 0755 /var/lib/teslalinux/tls-state
  [[ -f /etc/teslalinux/build-time ]] || date +%s > /etc/teslalinux/build-time
  /usr/local/bin/teslalinux-tls init
  nginx -t
  systemctl enable nginx
  systemctl restart nginx

  log "6/7 audio: PipeWire as tesla in /run/tesla, the null sink \"android\" the app plays into"
  install -d /etc/pipewire/pipewire.conf.d /etc/wireplumber/wireplumber.conf.d
  cat > /etc/pipewire/pipewire.conf.d/teslalinux-sink.conf <<'EOF'
context.objects = [
  { factory = adapter
    args = { factory.name = support.null-audio-sink node.name = "android" media.class = Audio/Sink
             object.linger = true audio.position = [ FL FR ] audio.rate = 48000
             priority.session = 3000 priority.driver = 3000 }
  }
]
EOF
  cat > /etc/wireplumber/wireplumber.conf.d/teslalinux.conf <<'EOF'
wireplumber.profiles = {
  main = {
    monitor.alsa = disabled
    monitor.alsa-midi = disabled
    monitor.bluez = disabled
    monitor.v4l2 = disabled
    monitor.libcamera = disabled
  }
}
EOF
  printf '# teslalinux: every ALSA client plays through PipeWire\npcm.!default { type pipewire }\nctl.!default { type pipewire }\n' > /etc/asound.conf

  log "7/7 systemd units (host/phase1-pios/etc/systemd, without Waydroid) + this machine's drop-ins"
  for u in $UNITS; do install -m 0644 "$REPO/host/phase1-pios/etc/systemd/$u.service" /etc/systemd/system/; done
  if [[ $selfsigned == 0 ]]; then
    install -m 0644 "$REPO/host/phase1-pios/etc/systemd/teslalinux-tls-fetch.service" "$REPO/host/phase1-pios/etc/systemd/teslalinux-tls-fetch.timer" /etc/systemd/system/
    systemctl enable teslalinux-tls-fetch.timer
  else
    systemctl disable --now teslalinux-tls-fetch.timer 2>/dev/null || true
  fi
  # ATL lives in /opt/teslalinux (build-dist.sh's prefix), the apps where install-app.sh puts them
  install -d /etc/systemd/system/teslalinux-atl.service.d /etc/systemd/system/tacomp.service.d
  cat > /etc/systemd/system/teslalinux-atl.service.d/10-prefix.conf <<EOF
# teslalinux (tools/debian/carstack.sh): the native Debian ATL prefix
[Unit]
ConditionPathExists=
ConditionPathExists=$PREFIX/bin/android-translation-layer
ConditionPathExists=$APPS/com.waze/base.apk
[Service]
Environment=ATL_PREFIX=$PREFIX
Environment=TESLALINUX_APP_DIR=$APPS/com.waze
Environment=ANDROID_APP_DATA_DIR=/var/lib/tesla/android/waze
EOF
  if [[ $vmmode == 1 ]]; then
    cat > /etc/systemd/system/tacomp.service.d/50-vm.conf <<'EOF'
# teslalinux --vm: no GPU render node in the VM; the pixman renderer and CPU JPEG (tacomp's m2m modes
# fall back to it by themselves when there is no V4L2 encoder)
[Service]
Environment=WLR_RENDERER=pixman
Environment=WLR_LIBINPUT_NO_DEVICES=1
EOF
    cat > /etc/systemd/system/teslalinux-atl.service.d/50-vm.conf <<'EOF'
# teslalinux --vm: no GPU; GTK draws with cairo, the app's GLES runs on Mesa llvmpipe
[Service]
Environment=GSK_RENDERER=cairo
EOF
  else
    rm -f /etc/systemd/system/tacomp.service.d/50-vm.conf /etc/systemd/system/teslalinux-atl.service.d/50-vm.conf
  fi
  systemctl daemon-reload
  # shellcheck disable=SC2086
  systemctl enable $UNITS
  log "done; start with: carstack.sh start (or systemctl start $UNITS)"
}

guest_app() { # DIR|BUNDLE
  [[ $EUID -eq 0 ]] || die "run as root"
  local src=${1:?}
  [[ -x $LIB/apps/install-app.sh ]] || die "run guest-install first ($LIB/apps/install-app.sh missing)"
  sudo -u tesla env APPS_DIR=$APPS sh $LIB/apps/install-app.sh "$src"
  rm -rf /var/lib/tesla/.cache/art/*/*base* 2>/dev/null || true
  ls -la "$APPS"/*/
}

guest_start() {
  # ART keeps AOT copies of the boot jars and api-impl.jar in the tesla user's ~/.cache/art and keeps
  # using stale ones after a rebuild (new classes then "fail resolution"): drop every cache file
  # older than one of the jars it was compiled from
  local j c=/var/lib/tesla/.cache/art
  for j in $PREFIX/lib/java/dex/android_translation_layer/api-impl.jar $PREFIX/lib/java/dex/art/core-oj-hostdex.jar; do
    [[ -d $c && -f $j ]] && find $c -type f ! -newer "$j" -print -delete | sed 's/^/  stale ART cache: /'
  done
  # shellcheck disable=SC2086
  systemctl start nginx $UNITS
  sleep 2; guest_status
}
guest_stop() {
  # shellcheck disable=SC2086
  systemctl stop $(echo $UNITS | tr ' ' '\n' | tac | tr '\n' ' ')
  guest_status
}
guest_status() {
  local u
  for u in nginx $UNITS; do printf '  %-28s %s\n' "$u" "$(systemctl is-active "$u" 2>/dev/null || true)"; done
}

case "${1:-}" in
  push) mac_push ;;
  install) shift; mac_push; mac_guest_root guest-install "$@" ;;
  app) shift; mac_app "$@" ;;
  start) vm ssh 'sudo bash ~/teslalinux/tools/debian/carstack.sh guest-start' ;;
  stop) vm ssh 'sudo bash ~/teslalinux/tools/debian/carstack.sh guest-stop' ;;
  restart) vm ssh 'sudo bash ~/teslalinux/tools/debian/carstack.sh guest-stop >/dev/null; sudo bash ~/teslalinux/tools/debian/carstack.sh guest-start' ;;
  status) vm ssh 'sudo bash ~/teslalinux/tools/debian/carstack.sh guest-status' ;;
  logs) vm ssh "sudo journalctl -q --no-pager -n ${2:-30} -u nginx $(for u in $UNITS; do printf -- '-u %s ' "$u"; done)" ;;
  check) mac_check ;;
  frame) shift; mac_frame "$@" ;;
  chrome) mac_chrome ;;
  guest-install) shift; guest_install "$@" ;;
  guest-app) shift; guest_app "$@" ;;
  guest-start) guest_start ;;
  guest-stop) guest_stop ;;
  guest-status) guest_status ;;
  *) awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"; exit 2 ;;
esac
