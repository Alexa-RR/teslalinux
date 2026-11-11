# host/alpine: the car image's service layer

OpenRC scripts for the Alpine car image (`tools/image/build-alpine-pi.sh`), the Alpine counterpart
of `host/phase1-pios/etc/systemd`. All services run as the unprivileged `tesla` user under
`supervise-daemon` (restart within a second or two), log to `/var/log/teslalinux/*.log`, and share
`/run/tesla` as the runtime directory (the compositor's Wayland socket `tesla-0`, the pulse socket).

| Service | What | Depends on |
|---|---|---|
| `teslalinux-firstboot` | grow the root filesystem; the bundled car certificate pairs (`teslalinux-tls init`) | localmount |
| `teslalinux-netconfig` | re-render hostapd/dnsmasq/nftables/nginx/display env when env or saved settings changed; ensure a served cert + key | localmount; before hostapd, dnsmasq, nftables, nginx, networking, tacomp |
| `teslalinux-tls` | check the shared car certificate's DNS version pointer, download only the slots and `active.json` that changed (everything every 6 h when DNS gives no answer), check them, serve the active slot + reload nginx (hourly, backoff, SIGUSR1 from `dhcpcd.exit-hook`) | after net, nginx; never blocks boot |
| `tacomp` | display compositor: frames to the car browser, touch back; WebSockets on 127.0.0.1 only | localmount, uses seatd |
| `teslalinux-atl` | Waze under ATL on tacomp's socket (`host/phase1-pios/teslalinux-atl`) | tacomp; uses gps, pipewire, wireplumber |
| `teslalinux-gps` | browser geolocation -> location feed (loopback) | after net |
| `teslalinux-audio` | the app's audio to the browser | pipewire-pulse |
| `teslalinux-api` | the config API the frontend polls (upstream configuration-manager contract) | after net |
| `teslalinux-pipewire`, `teslalinux-wireplumber`, `teslalinux-pipewire-pulse` | PipeWire for `tesla`, each supervised | pipewire first |

Alpine's own scripts cover `hostapd`, `dnsmasq`, `nftables` (`/etc/nftables.nft`), `nginx`
(`/etc/nginx/http.d/teslalinux.conf` + `/etc/nginx/teslalinux-*.inc`), `chronyd`, `dbus`, `seatd`,
`udev` (sysinit, with udev-trigger and udev-settle; no mdev), `networking` (static wlan0), `dhcpcd`
(uplinks; `denyinterfaces wlan0`).
Settings live in `/etc/teslalinux/env` (copied from `host/phase1-pios/env` at build time), the
frontend's saved settings in `/var/lib/teslalinux/state/props.json` (written by the API as
`tesla`), and `/etc/teslalinux/display.env` (size, encoder, quality, density for tacomp and ATL).
`teslalinux-apply-display` is the one command the API may run as root (sudoers).
