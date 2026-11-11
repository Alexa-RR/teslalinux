#!/bin/sh
# teslalinux: install the app updater on the Pi (Raspberry Pi OS / Debian, systemd + NetworkManager), as root.
# Called by the installer (install.sh one-liner, docs/APP-UPDATES.md "Installer"); safe to run again.
#
#   tools/appupdate/install.sh [--tlplay PATH|none] [--no-enable]
#
# PATH is the arm64 tlplay binary (APKPure by default; Google Play only when enabled in the settings)
# (default: tools/appupdate/target/release/tlplay, i.e. built on the Pi or
# in the Debian arm64 VM with tools/appupdate/build.sh). Installs:
#   /usr/local/bin/tlplay, /usr/local/bin/teslalinux-appupdate
#   /usr/local/share/teslalinux/appupdate/device.properties (the Play device profile)
#   /usr/local/lib/teslalinux/apps/{install-app.sh,apk-info.py,apk_verify.py}
#   /etc/teslalinux/play/ (root 0700; the dispensed login is cached there, 0600)
#   /var/lib/teslalinux/appupdate/ (state, staging; 0755 so the app user can read the staged APKs)
#   units teslalinux-appupdate.{service,timer}, -boot.service, -health.service; NM hook 91-teslalinux-appupdate
#   /etc/sudoers.d/teslalinux-appupdate: the API (user tesla) may run `teslalinux-appupdate kick --force` and
#   `teslalinux-appupdate receive-upload` only
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
PIOS="$REPO/host/phase1-pios/etc"
TLPLAY_BIN="$HERE/target/release/tlplay"
ENABLE=1
while [ $# -gt 0 ]; do
	case "$1" in
	--tlplay) TLPLAY_BIN=$2; shift ;;
	--no-enable) ENABLE=0 ;;
	*) echo "unknown option $1" >&2; exit 2 ;;
	esac
	shift
done
[ "$(id -u)" = 0 ] || { echo "run as root" >&2; exit 1; }
# --tlplay none: no downloader (only uploads from the settings page work; the source should be "manual")
[ "$TLPLAY_BIN" = none ] || [ -x "$TLPLAY_BIN" ] || { echo "no tlplay binary at $TLPLAY_BIN (tools/appupdate/build.sh, or --tlplay PATH|none)" >&2; exit 1; }

[ "$TLPLAY_BIN" = none ] || install -m 0755 "$TLPLAY_BIN" /usr/local/bin/tlplay
install -m 0755 "$HERE/teslalinux-appupdate" /usr/local/bin/teslalinux-appupdate
install -d -m 0755 /usr/local/share/teslalinux/appupdate /usr/local/lib/teslalinux/apps /usr/local/share/doc/teslalinux
install -m 0644 "$HERE/device.properties" /usr/local/share/teslalinux/appupdate/device.properties
install -m 0755 "$REPO/tools/apps/install-app.sh" "$REPO/tools/apps/apk-info.py" /usr/local/lib/teslalinux/apps/
install -m 0644 "$REPO/tools/apps/apk_verify.py" /usr/local/lib/teslalinux/apps/
install -m 0644 "$REPO/docs/APP-UPDATES.md" /usr/local/share/doc/teslalinux/APP-UPDATES.md
install -d -m 0700 -o root -g root /etc/teslalinux/play
install -d -m 0755 /var/lib/teslalinux/appupdate /var/log/teslalinux
for u in teslalinux-appupdate.service teslalinux-appupdate.timer teslalinux-appupdate-boot.service teslalinux-appupdate-health.service teslalinux-atl.service; do
	install -m 0644 "$PIOS/systemd/$u" /etc/systemd/system/
done
install -D -m 0755 "$PIOS/NetworkManager/91-teslalinux-appupdate" /etc/NetworkManager/dispatcher.d/91-teslalinux-appupdate
# the API runs as tesla: it may start a check and hand over an uploaded bundle (streamed on stdin into
# the root-only upload directory), nothing else
printf 'tesla ALL=(root) NOPASSWD: /usr/local/bin/teslalinux-appupdate kick --force, /usr/local/bin/teslalinux-appupdate receive-upload --size * --name *\n' > /etc/sudoers.d/teslalinux-appupdate
chmod 0440 /etc/sudoers.d/teslalinux-appupdate
visudo -cq -f /etc/sudoers.d/teslalinux-appupdate
systemctl daemon-reload
if [ "$ENABLE" = 1 ]; then
	systemctl enable teslalinux-appupdate.timer teslalinux-appupdate-boot.service teslalinux-appupdate-health.service
	systemctl start teslalinux-appupdate.timer
fi
echo "teslalinux-appupdate installed; state: teslalinux-appupdate status; log: /var/log/teslalinux/appupdate.log"
