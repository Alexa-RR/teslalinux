#!/bin/sh
# teslalinux: build the Alpine car image for the Raspberry Pi (aarch64).
#
#   build-alpine-pi.sh --atl-prefix atl-prefix.tar.gz --apps apps.tar.gz [--env env] [--out teslalinux.img]
#                      [--size 6G] [--rootfs-only DIR]
#   env TLS_BUNDLE_SRC=DIR bakes the shared car certificate into the image (/usr/share/teslalinux/tls):
#   DIR holds active.json and current/ and standby/ with fullchain.pem + privkey.pem each, as published
#   at TLS_BASE_URL (or as pack-dist.sh bundles them). First boot installs a pair only if it passes the
#   same checks as a fetched one (docs/CAR-STACK-READINESS.md "Certificate"); without it the Pi serves a
#   self-signed fallback until its first fetch. No DNS/ACME credential ever goes into the image.
#
# Runs as root on an Alpine aarch64 host (the tools/atl-vm VM is one: `doas sh build-alpine-pi.sh`).
# Steps: a rootfs with apk --root (packages.txt), the repo's services, web, configs rendered from
# the env file, the ATL prefix tarball (tools/image/pack-atl-prefix.sh) into /opt/atl, the apps
# tarball into /var/lib/teslalinux/apps, OpenRC services enabled, the Pi boot files, then a two
# partition image (FAT boot + ext4 root). See docs/ALPINE-IMAGE.md.
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
ATL_TAR=""; APPS_TAR=""; ENVFILE="$REPO/host/phase1-pios/env"; OUT="$PWD/teslalinux.img"; SIZE=6G; ROOTFS_ONLY=""
MIRROR=${ALPINE_MIRROR:-https://dl-cdn.alpinelinux.org/alpine}
while [ $# -gt 0 ]; do
	case "$1" in
		--atl-prefix) ATL_TAR=$2; shift ;;
		--apps) APPS_TAR=$2; shift ;;
		--env) ENVFILE=$2; shift ;;
		--out) OUT=$2; shift ;;
		--size) SIZE=$2; shift ;;
		--rootfs-only) ROOTFS_ONLY=$2; shift ;;
		*) echo "unknown argument $1" >&2; exit 2 ;;
	esac
	shift
done
[ "$(id -u)" = 0 ] || { echo "run as root" >&2; exit 1; }
[ "$(uname -m)" = aarch64 ] || { echo "build on aarch64 (the apk --root rootfs runs the target's binaries)" >&2; exit 1; }
log() { printf '\033[1;36m[image]\033[0m %s\n' "$*"; }
. "$ENVFILE"
TLS_CERT=${TLS_CERT:-/etc/teslalinux/tls/fullchain.pem}
TLS_KEY=${TLS_KEY:-/etc/teslalinux/tls/privkey.pem}

ROOT=${ROOTFS_ONLY:-$(mktemp -d /tmp/teslalinux-rootfs.XXXXXX)}
mkdir -p "$ROOT"

# every host config comes from the same renderer the Pi runs at boot (host/teslalinux-render)
render() { python3 "$REPO/host/teslalinux-render" --root "$ROOT" --layout alpine --templates "$REPO/host/phase1-pios/etc" --env "$ENVFILE" --state /nonexistent "$@"; }

# ---------------------------------------------------------------- 1. rootfs
log "1/6 rootfs in $ROOT"
mkdir -p "$ROOT/etc/apk/keys"
cp /etc/apk/keys/* "$ROOT/etc/apk/keys/" 2>/dev/null || true
cat > "$ROOT/etc/apk/repositories" <<EOF
$MIRROR/edge/main
$MIRROR/edge/community
$MIRROR/edge/testing
EOF
PKGS=$(grep -v '^#' "$HERE/packages.txt" | grep -v '^\s*$' | tr '\n' ' ')
# shellcheck disable=SC2086
apk --root "$ROOT" --arch aarch64 --initdb -U --allow-untrusted add $PKGS
log "   packages installed"

# ---------------------------------------------------------------- 2. system configuration
log "2/6 system configuration"
echo teslalinux > "$ROOT/etc/hostname"
printf '127.0.0.1\tlocalhost teslalinux\n' > "$ROOT/etc/hosts"
cat > "$ROOT/etc/fstab" <<EOF
/dev/mmcblk0p2	/	ext4	rw,noatime	0 1
/dev/mmcblk0p1	/boot	vfat	rw,noatime	0 2
tmpfs		/tmp	tmpfs	nosuid,nodev,mode=1777	0 0
EOF
# the service user; render (the DRM render node: tacomp's GLES2 renderer) is not in Alpine's
# baselayout, eudev's rules use it when it exists
chroot "$ROOT" /bin/sh -c 'getent group render >/dev/null || addgroup -S render; id tesla >/dev/null 2>&1 || adduser -D -h /var/lib/tesla -s /sbin/nologin tesla; for g in video render input audio seat; do addgroup tesla $g 2>/dev/null || true; done'
install -d -m 0750 "$ROOT/var/lib/tesla" "$ROOT/var/lib/tesla/android" "$ROOT/var/lib/tesla/.cache" "$ROOT/var/log/teslalinux"
install -d -m 0755 "$ROOT/var/lib/teslalinux" "$ROOT/var/lib/teslalinux/tls-state"
install -d -m 0750 "$ROOT/var/lib/teslalinux/state"
chroot "$ROOT" chown -R tesla:tesla /var/lib/tesla /var/log/teslalinux /var/lib/teslalinux/state
# the car's settings
install -d -m 0755 "$ROOT/etc/teslalinux"
cp "$ENVFILE" "$ROOT/etc/teslalinux/env"
# teslalinux-tls treats a clock before this as "not set yet" (no RTC, no NTP yet)
date +%s > "$ROOT/etc/teslalinux/build-time"
if [ -d "${TLS_BUNDLE_SRC:-/nonexistent}" ]; then
	install -d -m 0700 "$ROOT/usr/share/teslalinux/tls"
	for s in current standby; do
		[ -s "$TLS_BUNDLE_SRC/$s/fullchain.pem" ] && [ -s "$TLS_BUNDLE_SRC/$s/privkey.pem" ] || continue
		install -d -m 0700 "$ROOT/usr/share/teslalinux/tls/$s"
		install -m 0644 "$TLS_BUNDLE_SRC/$s/fullchain.pem" "$ROOT/usr/share/teslalinux/tls/$s/fullchain.pem"
		install -m 0600 "$TLS_BUNDLE_SRC/$s/privkey.pem" "$ROOT/usr/share/teslalinux/tls/$s/privkey.pem"
		log "   baked the $s pair of the shared car certificate (installed at first boot if it passes the checks)"
	done
	if [ -s "$TLS_BUNDLE_SRC/active.json" ]; then
		install -m 0644 "$TLS_BUNDLE_SRC/active.json" "$ROOT/usr/share/teslalinux/tls/active.json"
	fi
fi
# networking: the hotspot address on wlan0, DHCP on every uplink (LTE stick, Ethernet, tether) but not
# the hotspot, and the uplink-up hook for the certificate fetcher
install -d "$ROOT/etc/hostapd" "$ROOT/etc/usb_modeswitch.d" "$ROOT/etc/network"
cp "$REPO"/host/phase1-pios/etc/usb_modeswitch.d/* "$ROOT/etc/usb_modeswitch.d/" 2>/dev/null || true
cat > "$ROOT/etc/network/interfaces" <<EOF
auto lo
iface lo inet loopback
auto $AP_IFACE
iface $AP_IFACE inet static
	address $AP_SUBNET.1
	netmask 255.255.255.0
EOF
touch "$ROOT/etc/dhcpcd.conf"
grep -q '^# teslalinux' "$ROOT/etc/dhcpcd.conf" || printf '\n# teslalinux: the hotspot has its static address (networking); dhcpcd serves the uplinks\ndenyinterfaces %s waydroid0 lo\n# the backup Wi-Fi card is associated by teslalinux-uplink (wpa_supplicant), not by a dhcpcd hook\nnohook wpa_supplicant\n' "$AP_IFACE" >> "$ROOT/etc/dhcpcd.conf"
install -m 0755 "$REPO/host/alpine/dhcpcd.exit-hook" "$ROOT/etc/dhcpcd.exit-hook"
echo "net.ipv4.ip_forward = 1" > "$ROOT/etc/sysctl.d/10-teslalinux.conf"
# web: the frontend; nginx's site, includes and the youtu.be page are rendered below
install -d "$ROOT/var/www/teslalinux" "$ROOT/etc/nginx/http.d"
cp -r "$REPO/web/." "$ROOT/var/www/teslalinux/"
rm -f "$ROOT/etc/nginx/http.d/default.conf"
# services, the renderer and its templates (the boot-time re-render reads them from /usr/share)
install -d "$ROOT/usr/local/lib/teslalinux" "$ROOT/usr/local/bin" "$ROOT/usr/share/teslalinux/templates"
install -m 0644 "$REPO/services/gps/gps.py" "$REPO/services/audio/audio.py" "$REPO/services/api/api.py" \
	"$REPO/services/api/tlprops.py" "$ROOT/usr/local/lib/teslalinux/"
install -m 0755 "$REPO/host/teslalinux-render" "$ROOT/usr/local/bin/teslalinux-render"
cp -r "$REPO/host/phase1-pios/etc/." "$ROOT/usr/share/teslalinux/templates/"
rm -rf "$ROOT/usr/share/teslalinux/templates/systemd" "$ROOT/usr/share/teslalinux/templates/NetworkManager"
install -m 0755 "$REPO/host/phase1-pios/teslalinux-atl" "$ROOT/usr/local/bin/teslalinux-atl"
install -m 0755 "$REPO/host/alpine/teslalinux-firstboot" "$ROOT/usr/local/bin/teslalinux-firstboot"
install -m 0755 "$REPO/host/tls/teslalinux-tls" "$ROOT/usr/local/bin/teslalinux-tls"
install -m 0755 "$REPO/host/alpine/teslalinux-apply-display" "$ROOT/usr/local/bin/teslalinux-apply-display"
# the API restarts the display through this one command and nothing else
install -d -m 0750 "$ROOT/etc/sudoers.d"
install -m 0755 "$REPO/host/teslalinux-restart-app" "$ROOT/usr/local/bin/teslalinux-restart-app"
install -m 0755 "$REPO/host/teslalinux-set-locale" "$ROOT/usr/local/bin/teslalinux-set-locale"
install -m 0755 "$REPO/host/teslalinux-set-keyboard" "$ROOT/usr/local/bin/teslalinux-set-keyboard"
install -m 0755 "$REPO/host/teslalinux-network" "$ROOT/usr/local/bin/teslalinux-network"
install -m 0755 "$REPO/host/teslalinux-system-update" "$ROOT/usr/local/bin/teslalinux-system-update"
install -m 0755 "$REPO/host/teslalinux-uplink" "$ROOT/usr/local/bin/teslalinux-uplink"
install -m 0755 "$REPO/host/teslalinux-dexopt" "$ROOT/usr/local/bin/teslalinux-dexopt"
install -D -m 0644 "$REPO/tools/atl-vm/mk-android-tzdata.py" "$ROOT/usr/local/lib/teslalinux/mk-android-tzdata.py"
echo 'tesla ALL=(root) NOPASSWD: /usr/local/bin/teslalinux-apply-display, /usr/local/bin/teslalinux-restart-app, /usr/local/bin/teslalinux-set-locale, /usr/local/bin/teslalinux-set-keyboard, /usr/local/bin/teslalinux-network, /usr/local/bin/teslalinux-system-update' > "$ROOT/etc/sudoers.d/teslalinux"
chmod 0440 "$ROOT/etc/sudoers.d/teslalinux"
render
if [ -x "$HERE/../../services/display/build/tacomp" ]; then
	install -m 0755 "$HERE/../../services/display/build/tacomp" "$ROOT/usr/local/bin/tacomp"
elif [ -x "${TACOMP_BIN:-/nonexistent}" ]; then
	install -m 0755 "$TACOMP_BIN" "$ROOT/usr/local/bin/tacomp"
else
	log "   warning: no tacomp binary (build services/display on this host or pass TACOMP_BIN)"
fi
install -m 0755 "$REPO"/host/alpine/init.d/* "$ROOT/etc/init.d/"
install -d "$ROOT/etc/conf.d"
# size, encoder and quality come from /etc/teslalinux/display.env (rendered from the display state)
cat > "$ROOT/etc/conf.d/tacomp" <<EOF
TACOMP_RENDERER="gles2"
EOF
# the app's audio: a null sink "android" that is the default and the only sink (no ALSA/Bluetooth
# monitors, so the Pi's own outputs never take over); ALSA "default" goes to PipeWire, which is how
# ATL's AudioTrack (snd_pcm_open "default") reaches it; audio.py captures android.monitor
install -d -o 1000 -g 1000 "$ROOT/var/lib/tesla/.config/pipewire/pipewire.conf.d" "$ROOT/var/lib/tesla/.config/wireplumber/wireplumber.conf.d"
cat > "$ROOT/var/lib/tesla/.config/pipewire/pipewire.conf.d/teslalinux.conf" <<'EOF'
# teslalinux: the sink the app plays into; audio.py captures android.monitor
context.objects = [
  { factory = adapter
    args = { factory.name = support.null-audio-sink node.name = "android" node.description = "Android"
             media.class = "Audio/Sink" audio.position = [ FL FR ] audio.rate = 48000
             object.linger = true priority.session = 3000 priority.driver = 3000 } }
]
EOF
cat > "$ROOT/var/lib/tesla/.config/wireplumber/wireplumber.conf.d/teslalinux.conf" <<'EOF'
# teslalinux: no hardware monitors; the "android" null sink is the only and so the default sink
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
cat > "$ROOT/etc/asound.conf" <<'EOF'
# teslalinux: every ALSA client (ATL's AudioTrack opens "default") plays through PipeWire
pcm.!default { type pipewire }
ctl.!default { type pipewire }
EOF
chroot "$ROOT" chown -R tesla:tesla /var/lib/tesla/.config
# fonts where ATL looks for them
install -d "$ROOT/system/fonts"
for f in "$ROOT"/usr/share/fonts/roboto/*.ttf "$ROOT"/usr/share/fonts/noto/*.ttf "$ROOT"/usr/share/fonts/noto-cjk/*; do
	[ -f "$f" ] && ln -sf "${f#$ROOT}" "$ROOT/system/fonts/" || true
done

# ---------------------------------------------------------------- 3. the app runtime and the app
log "3/6 ATL prefix and apps"
if [ -n "$ATL_TAR" ]; then
	install -d "$ROOT/opt/atl"
	tar -xzf "$ATL_TAR" -C "$ROOT/opt/atl"
	# the prefix links the system ART and boot jars: point them at the image's packages
	rm -rf "$ROOT/opt/atl/lib/art"; install -d "$ROOT/opt/atl/lib/art" "$ROOT/opt/atl/lib/java/dex"
	for f in "$ROOT"/usr/lib/art/*; do ln -sfn "${f#$ROOT}" "$ROOT/opt/atl/lib/art/$(basename "$f")"; done
	ln -sfn /usr/lib/java/dex/art "$ROOT/opt/atl/lib/java/dex/art"
else
	log "   warning: no --atl-prefix: the stock (unpatched) ATL package will run"
	ln -sfn /usr "$ROOT/opt/atl"
fi
if [ -n "$APPS_TAR" ]; then
	install -d "$ROOT/var/lib/teslalinux/apps"
	tar -xzf "$APPS_TAR" -C "$ROOT/var/lib/teslalinux/apps"
	chroot "$ROOT" chown -R tesla:tesla /var/lib/teslalinux/apps
else
	log "   warning: no --apps: install Waze later with tools/apps/install-app.sh"
fi

# ---------------------------------------------------------------- 4. services
log "4/6 services"
# eudev handles devices (mdev would race it); udev-settle makes sure wlan0 exists before networking
# and hostapd start (brcmfmac is loaded by the coldplug trigger)
for svc in devfs dmesg udev udev-trigger udev-settle; do chroot "$ROOT" rc-update add $svc sysinit >/dev/null 2>&1 || true; done
for svc in hwclock modules sysctl hostname bootmisc syslog; do chroot "$ROOT" rc-update add $svc boot >/dev/null 2>&1 || true; done
for svc in mount-ro killprocs savecache; do chroot "$ROOT" rc-update add $svc shutdown >/dev/null 2>&1 || true; done
for svc in dbus seatd udev-postmount chronyd teslalinux-firstboot teslalinux-netconfig networking dhcpcd crond \
	hostapd dnsmasq nftables nginx teslalinux-tls teslalinux-pipewire teslalinux-wireplumber teslalinux-pipewire-pulse \
	teslalinux-gps teslalinux-api teslalinux-audio teslalinux-uplink tacomp teslalinux-atl; do
	chroot "$ROOT" rc-update add $svc default >/dev/null 2>&1 || log "   (service $svc not added)"
done

# ---------------------------------------------------------------- 5. boot files
log "5/6 boot files"
install -d "$ROOT/boot"
# host/rpi4/config.txt is written for Pi OS's kernel8.img; Alpine's linux-rpi installs vmlinuz-rpi
# and mkinitfs makes initramfs-rpi, so the firmware is pointed at those
awk '/^kernel=/ { print "kernel=vmlinuz-rpi"; print "initramfs initramfs-rpi followkernel"; next } { print }' \
	"$REPO/host/rpi4/config.txt" > "$ROOT/boot/config.txt"
grep -q '^kernel=vmlinuz-rpi' "$ROOT/boot/config.txt" || printf '[pi4]\nkernel=vmlinuz-rpi\ninitramfs initramfs-rpi followkernel\n' >> "$ROOT/boot/config.txt"
cat > "$ROOT/boot/cmdline.txt" <<EOF
modules=sd-mod,usb-storage,ext4 root=/dev/mmcblk0p2 rootfstype=ext4 rw rootwait console=tty1 quiet psi=1 coherent_pool=1M pcie_aspm=off
EOF
KVER=$(ls "$ROOT/lib/modules" | head -1)
if [ -n "$KVER" ]; then
	printf 'features="base mmc usb ext4 kms"\n' > "$ROOT/etc/mkinitfs/mkinitfs.conf"
	chroot "$ROOT" mkinitfs -o /boot/initramfs-rpi "$KVER" >/dev/null 2>&1 || log "   (mkinitfs failed; the kernel package's initramfs is used)"
fi
if [ -n "$ROOTFS_ONLY" ]; then
	log "rootfs only: $ROOT"
	exit 0
fi

# ---------------------------------------------------------------- 6. the image
log "6/6 image $OUT ($SIZE)"
rm -f "$OUT"
truncate -s "$SIZE" "$OUT"
sfdisk -q "$OUT" <<EOF
label: dos
unit: sectors
1 : start=2048, size=524288, type=c, bootable
2 : start=526336, type=83
EOF
LOOP=$(losetup -f --show -P "$OUT")
trap 'umount -R /mnt/teslalinux-root 2>/dev/null; losetup -d $LOOP 2>/dev/null' EXIT
mkfs.vfat -F 32 -n BOOT "${LOOP}p1" >/dev/null
mkfs.ext4 -q -L teslalinux "${LOOP}p2"
mkdir -p /mnt/teslalinux-root
mount "${LOOP}p2" /mnt/teslalinux-root
mkdir -p /mnt/teslalinux-root/boot
mount "${LOOP}p1" /mnt/teslalinux-root/boot
tar -C "$ROOT" -cf - . | tar -C /mnt/teslalinux-root -xf -
sync
umount -R /mnt/teslalinux-root
losetup -d "$LOOP"
trap - EXIT
log "done: $OUT"
