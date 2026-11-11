#!/usr/bin/env bash
# teslalinux: turn a fresh Raspberry Pi OS Lite (arm64, trixie) into the full stack.
#
#   sudo ./provision.sh            # full run
#   sudo ./provision.sh --no-trim  # skip the systemd/boot trimming (for debugging)
#
# Idempotent: safe to re-run. Reads ./env. Reboot afterwards. The app runtime (ATL in /opt/teslalinux)
# and the app itself come from a release dist (tools/pios/install.sh) and tools/apps/install-app.sh.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
# shellcheck source=env
source "$HERE/env"
TRIM=1; [[ "${1:-}" == "--no-trim" ]] && TRIM=0
[[ $EUID -eq 0 ]] || { echo "run as root" >&2; exit 1; }
[[ "$(uname -m)" == "aarch64" ]] || { echo "needs 64-bit Pi OS" >&2; exit 1; }

log() { printf '\033[1;34m[provision]\033[0m %s\n' "$*"; }
# Every host config (hostapd, dnsmasq, nftables, nginx, the redirect page, the display env) comes
# from host/teslalinux-render: the same renderer the Alpine image uses at build time and at boot.
render() { python3 "$REPO/host/teslalinux-render" --root / --layout pios --templates "$HERE/etc" --env /etc/teslalinux/env "$@"; }

# ---------------------------------------------------------------------------------------------
log "1/9 packages"
export DEBIAN_FRONTEND=noninteractive
apt-get update -q
apt-get install -y -q --no-install-recommends \
  hostapd dnsmasq nftables nginx \
  python3 python3-websockets \
  pipewire pipewire-pulse pipewire-alsa wireplumber ffmpeg curl openssl bind9-dnsutils \
  modemmanager network-manager usb-modeswitch usb-modeswitch-data uhubctl \
  mesa-vulkan-drivers libgl1-mesa-dri libegl-mesa0 libgbm1 \
  seatd \
  v4l-utils i2c-tools rtc-tools \
  git curl ca-certificates
# Packages pulled in only for development that we don't want: none of the desktop stack.
apt-get autoremove -y -q

# Dedicated user that owns the Wayland socket and runs the app.
id tesla >/dev/null 2>&1 || useradd -r -m -d /var/lib/tesla -s /usr/sbin/nologin -G video,render,input,audio tesla
install -d -o tesla -g tesla -m 0700 /run/tesla
echo 'd /run/tesla 0700 tesla tesla -' > /etc/tmpfiles.d/tesla.conf

# ---------------------------------------------------------------------------------------------
log "2/9 boot firmware config + kernel cmdline"
BOOT=/boot/firmware
cp -n "$BOOT/config.txt" "$BOOT/config.txt.orig" || true
cp "$REPO/host/rpi4/config.txt" "$BOOT/config.txt"
ROOTPART=$(findmnt -no SOURCE / )
ROOTUUID=$(blkid -s PARTUUID -o value "$ROOTPART")
sed "s|PARTUUID=CHANGEME-02|PARTUUID=$ROOTUUID|" "$REPO/host/rpi4/cmdline.txt" > "$BOOT/cmdline.txt"
# an earlier provisioning preloaded the veth and bridge modules; nothing on the car uses them
rm -f /etc/modules-load.d/teslalinux.conf

# ---------------------------------------------------------------------------------------------
log "3/9 hotspot: hostapd + dnsmasq + nftables (static wlan0, NetworkManager leaves it alone)"
install -d -m 0755 /etc/teslalinux /usr/local/lib/teslalinux /usr/share/teslalinux/templates
install -m 0644 "$HERE/env" /etc/teslalinux/env
# the settings the frontend saves (upstream's persist.* properties), written by the API as tesla
install -d -m 0755 /var/lib/teslalinux
install -d -m 0750 -o tesla -g tesla /var/lib/teslalinux/state
install -m 0644 "$REPO/services/api/tlprops.py" /usr/local/lib/teslalinux/
install -m 0755 "$REPO/host/teslalinux-render" /usr/local/bin/teslalinux-render
cp -r "$HERE/etc/." /usr/share/teslalinux/templates/
render --only net
# wlan0 is managed by systemd-networkd-free static config; keep NM off it.
cat > /etc/NetworkManager/conf.d/teslalinux-unmanaged.conf <<EOF
[keyfile]
unmanaged-devices=interface-name:$AP_IFACE
EOF
cat > /etc/systemd/system/teslalinux-ap-addr.service <<EOF
[Unit]
Description=teslalinux: static address on $AP_IFACE
Before=hostapd.service dnsmasq.service
DefaultDependencies=no
After=sys-subsystem-net-devices-$AP_IFACE.device
[Service]
Type=oneshot
RemainAfterExit=yes
ExecStart=/usr/sbin/rfkill unblock wlan
ExecStart=/usr/sbin/ip addr replace $AP_SUBNET.1/24 dev $AP_IFACE
ExecStart=/usr/sbin/ip link set $AP_IFACE up
[Install]
WantedBy=sysinit.target
EOF
cat > /etc/sysctl.d/90-teslalinux.conf <<'EOF'
net.ipv4.ip_forward=1
net.core.rmem_max=8388608
net.core.wmem_max=8388608
EOF
# dnsmasq: don't wait for the network-online target
mkdir -p /etc/systemd/system/dnsmasq.service.d /etc/systemd/system/hostapd.service.d
printf '[Unit]\nAfter=teslalinux-ap-addr.service\nWants=teslalinux-ap-addr.service\n' > /etc/systemd/system/dnsmasq.service.d/order.conf
printf '[Unit]\nAfter=teslalinux-ap-addr.service\nWants=teslalinux-ap-addr.service\n[Service]\nRestart=always\nRestartSec=1\n' > /etc/systemd/system/hostapd.service.d/order.conf
systemctl unmask hostapd
systemctl enable teslalinux-ap-addr hostapd dnsmasq nftables

# ---------------------------------------------------------------------------------------------
log "4/9 nginx front door + web frontend"
install -d /var/www/teslalinux
cp -r "$REPO/web/." /var/www/teslalinux/
render --only web
render --only display
ln -sf /etc/nginx/sites-available/teslalinux /etc/nginx/sites-enabled/teslalinux
rm -f /etc/nginx/sites-enabled/default
# the shared car certificate: fetched now when there is an uplink, else a self-signed fallback until
# teslalinux-tls-fetch.timer gets it (see docs/CAR-STACK-READINESS.md "Certificate")
install -m 0755 "$REPO/host/tls/teslalinux-tls" /usr/local/bin/teslalinux-tls
install -d -m 0755 /var/lib/teslalinux/tls-state /var/log/teslalinux
date +%s > /etc/teslalinux/build-time
TLS_NO_RELOAD=1 /usr/local/bin/teslalinux-tls init
TLS_NO_RELOAD=1 /usr/local/bin/teslalinux-tls fetch || log "  certificate not fetched yet: the timer retries (self-signed until then)"
# an uplink coming up (NetworkManager owns the LTE/Ethernet links here) fetches at once
install -D -m 0755 "$HERE/etc/NetworkManager/90-teslalinux-tls" /etc/NetworkManager/dispatcher.d/90-teslalinux-tls
render >/dev/null
nginx -t
systemctl enable nginx

# ---------------------------------------------------------------------------------------------
log "5/9 car services: units, daemons, helpers, the compositor"
install -m 0644 "$HERE/etc/systemd/"*.service "$HERE/etc/systemd/"*.timer /etc/systemd/system/
# an earlier provisioning installed the certificate daemon unit; the timer replaces it
systemctl disable --now teslalinux-tls.service 2>/dev/null || true
rm -f /etc/systemd/system/teslalinux-tls.service
install -d /usr/local/lib/teslalinux
install -m 0644 "$REPO/services/gps/gps.py" "$REPO/services/audio/audio.py" "$REPO/services/api/api.py" "$REPO/services/api/tlprops.py" /usr/local/lib/teslalinux/
install -m 0755 "$HERE/teslalinux-apply-display" /usr/local/bin/teslalinux-apply-display
# the ATL launcher (teslalinux-atl.service starts it once /opt/teslalinux holds the ATL prefix and
# the app is installed)
install -m 0755 "$HERE/teslalinux-atl" /usr/local/bin/teslalinux-atl
install -m 0755 "$REPO/host/teslalinux-restart-app" /usr/local/bin/teslalinux-restart-app
install -m 0755 "$REPO/host/teslalinux-set-locale" /usr/local/bin/teslalinux-set-locale
install -m 0755 "$REPO/host/teslalinux-set-keyboard" /usr/local/bin/teslalinux-set-keyboard
install -m 0755 "$REPO/host/teslalinux-network" /usr/local/bin/teslalinux-network
install -m 0755 "$REPO/host/teslalinux-system-update" /usr/local/bin/teslalinux-system-update
install -m 0755 "$REPO/host/teslalinux-uplink" /usr/local/bin/teslalinux-uplink
install -m 0755 "$REPO/host/teslalinux-dexopt" /usr/local/bin/teslalinux-dexopt
install -m 0644 "$REPO/tools/atl-vm/mk-android-tzdata.py" /usr/local/lib/teslalinux/mk-android-tzdata.py
echo 'tesla ALL=(root) NOPASSWD: /usr/local/bin/teslalinux-apply-display, /usr/local/bin/teslalinux-restart-app, /usr/local/bin/teslalinux-set-locale, /usr/local/bin/teslalinux-set-keyboard, /usr/local/bin/teslalinux-network, /usr/local/bin/teslalinux-system-update' > /etc/sudoers.d/teslalinux
chmod 0440 /etc/sudoers.d/teslalinux
# tacomp is written against wlroots 0.20; Debian trixie ships 0.18 (see docs/CAR-STACK-READINESS.md,
# "Pi OS blockers"): a binary built for this system is installed when one exists
# (the ATL line's installer, tools/pios/install.sh, ships it in the dist with its own wlroots 0.20)
if [[ -x "$REPO/services/display/build/tacomp" ]]; then
  install -D -m 0755 "$REPO/services/display/build/tacomp" /opt/teslalinux/tacomp/bin/tacomp
else
  log "  tacomp not built yet (services/display); tacomp.service stays inactive until /opt/teslalinux/tacomp/bin/tacomp exists"
fi
systemctl daemon-reload
systemctl enable teslalinux-netconfig.service teslalinux-tls-fetch.timer \
  teslalinux-pipewire.service teslalinux-wireplumber.service teslalinux-pipewire-pulse.service \
  teslalinux-dbus.service tacomp.service teslalinux-atl.service teslalinux-api.service teslalinux-gps.service \
  teslalinux-audio.service teslalinux-uplink.service

# ---------------------------------------------------------------------------------------------
log "6/9 audio: PipeWire for tesla in /run/tesla with a null sink the app plays into"
install -d /etc/pipewire/pipewire.conf.d
cat > /etc/pipewire/pipewire.conf.d/teslalinux-sink.conf <<'EOF'
context.objects = [
  { factory = adapter
    args = { factory.name = support.null-audio-sink node.name = "android" media.class = Audio/Sink
             object.linger = true audio.position = [ FL FR ] audio.rate = 48000
             priority.session = 3000 priority.driver = 3000 }
  }
]
EOF
# the null sink is the only sink: no ALSA/Bluetooth monitors, so the Pi's own outputs never win
install -d /etc/wireplumber/wireplumber.conf.d
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
# ALSA "default" -> PipeWire: ATL's AudioTrack (snd_pcm_open "default") reaches the null sink
cat > /etc/asound.conf <<'EOF'
# teslalinux: every ALSA client plays through PipeWire
pcm.!default { type pipewire }
ctl.!default { type pipewire }
EOF
# PipeWire, WirePlumber and pipewire-pulse run as system units for tesla in /run/tesla
# (teslalinux-pipewire*.service), next to tacomp's Wayland socket: no user session, no linger. An
# earlier provisioning enabled the per-user units; switch them off.
loginctl disable-linger tesla 2>/dev/null || true

# ---------------------------------------------------------------------------------------------
log "7/9 LTE: ModemManager off the critical path, usb_modeswitch quirks from upstream"
cp "$REPO/host/phase1-pios/etc/usb_modeswitch.d/"* /etc/usb_modeswitch.d/ 2>/dev/null || true
mkdir -p /etc/systemd/system/ModemManager.service.d
printf '[Unit]\nAfter=hostapd.service nginx.service\n' > /etc/systemd/system/ModemManager.service.d/late.conf
systemctl enable ModemManager NetworkManager
# NetworkManager: any new ethernet-like modem (cdc_ether/ncm/rndis) gets DHCP automatically. Nothing to do.

# ---------------------------------------------------------------------------------------------
if [[ $TRIM -eq 1 ]]; then
log "8/9 trim: remove everything from the boot critical path that the car doesn't need"
systemctl disable --now \
  NetworkManager-wait-online.service systemd-networkd-wait-online.service \
  apt-daily.timer apt-daily-upgrade.timer man-db.timer fstrim.timer e2scrub_all.timer \
  dphys-swapfile.service rpi-eeprom-update.service raspi-config.service \
  ModemManager.service avahi-daemon.service avahi-daemon.socket bluetooth.service hciuart.service \
  triggerhappy.service triggerhappy.socket cups.service cups.socket \
  wpa_supplicant.service 2>/dev/null || true
# (disabled, not masked: NetworkManager still starts wpa_supplicant through D-Bus activation when the
# backup Wi-Fi card joins a network; teslalinux-uplink, Settings > Wi-Fi & LTE)
# keep NTP: systemd-timesyncd sets the clock once an uplink exists (the RTC covers the time before)
systemctl enable systemd-timesyncd.service 2>/dev/null || true
# ModemManager still starts, but only pulled in late by our ordering drop-in:
systemctl enable ModemManager.service
# Serial getty + the SSH keygen oneshot are slow; keep ssh itself.
systemctl disable serial-getty@ttyS0.service getty@tty1.service 2>/dev/null || true
systemctl mask systemd-random-seed.service
# journald: volatile, small. Saves ~0.5 s of fs sync at boot and SD wear.
mkdir -p /etc/systemd/journald.conf.d
printf '[Journal]\nStorage=volatile\nRuntimeMaxUse=16M\n' > /etc/systemd/journald.conf.d/teslalinux.conf
# udev settle is a common 1-3 s sink; don't wait for it.
mkdir -p /etc/systemd/system/systemd-udev-settle.service.d
printf '[Service]\nExecStart=\nExecStart=/bin/true\n' > /etc/systemd/system/systemd-udev-settle.service.d/noop.conf
# Faster fsck/mount: noatime, commit=30
sed -i 's|\(PARTUUID=[^ ]* */ *ext4 *\)defaults,noatime|\1noatime,commit=30|' /etc/fstab || true
# CPU governor: performance (we're not on battery; schedutil ramp costs boot time)
cat > /etc/systemd/system/teslalinux-cpufreq.service <<'EOF'
[Unit]
Description=teslalinux: performance governor
DefaultDependencies=no
[Service]
Type=oneshot
ExecStart=/bin/sh -c 'for g in /sys/devices/system/cpu/cpufreq/policy*/scaling_governor; do echo performance > $g; done'
[Install]
WantedBy=sysinit.target
EOF
systemctl enable teslalinux-cpufreq.service
fi

# ---------------------------------------------------------------------------------------------
log "9/9 RTC + time: use the DS3231 if present so TLS works before LTE is up"
if i2cdetect -y 1 2>/dev/null | grep -q ' 68 '; then
  cat > /etc/udev/rules.d/85-hwclock.rules <<'EOF'
KERNEL=="rtc0", RUN+="/sbin/hwclock --hctosys --utc"
EOF
  log "  DS3231 found on i2c-1"
else
  log "  no RTC found: the kernel will boot with a stale clock. Add a DS3231 or accept TLS errors until LTE sets the time."
  sed -i '/dtoverlay=i2c-rtc,ds3231/d' "$BOOT/config.txt"
fi

systemctl daemon-reload
log "done. Reboot. Waze starts once ATL is in /opt/teslalinux and the app is installed (tools/apps/install-app.sh)"
log "measure with: systemd-analyze && systemd-analyze critical-chain hostapd.service"
