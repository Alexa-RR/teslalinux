#!/usr/bin/env bash
# teslalinux: install the car stack on a Raspberry Pi 4 (4 B, 400, CM4) running Raspberry Pi OS Lite
# 64-bit (Debian 13 trixie). One command, as root, on the Pi:
#
#   curl -fsSL https://github.com/OWNER/REPO/releases/latest/download/install.sh | sudo bash -s -- [OPTIONS]
#   sudo ./install.sh [install] [OPTIONS]
#
#   --dist latest|vX.Y.Z|URL|FILE  the runtime built by the release workflow (tools/debvm/build-dist.sh +
#                        pack-dist.sh): the latest release (default), a release tag, a URL or a local
#                        teslalinux-dist-*.tar.zst
#   --sha256 HEX         the tarball's expected sha256 (default: the release's SHA256SUMS; a URL needs
#                        one or a SHA256SUMS next to it)
#   --config FILE        settings as KEY=VALUE lines: the keys of /etc/teslalinux/env (AP_SSID, AP_PSK,
#                        AP_COUNTRY, DEVICE_HOST, ...) plus LTE_APN, LTE_USER, LTE_PASSWORD, LTE_ROAMING
#   --set KEY=VALUE      one setting (repeatable; wins over --config)
#   --apk PATH           install Waze from this split-APK bundle (.apkm/.xapk/.apks/zip) or directory
#   --waze apkpure|none  apkpure: download Waze now (verified against Waze's signing certificate; it
#                        installs at the reboot); none: leave it to the car page's upload screen
#   --payload DIR        take configs and scripts from this repo checkout instead of the dist's copy
#   --no-trim            leave the boot-time trimming out
#   --no-prewarm         do not build ART's boot image now (the first app start does it)
#   --force              redo every step, changed or not
#   --yes                no questions (needed when this SSH session runs over the hotspot's Wi-Fi)
#   --reboot | --no-reboot   what to do at the end (default: ask)
#
#   teslalinux doctor [--static]   health check (this script, installed as /usr/local/sbin/teslalinux)
#   teslalinux status              installed dist, previous dist, steps, backups
#   teslalinux rollback [--yes]    the previous dist and the system files as they were before the
#                                  last install run that changed them
#   teslalinux uninstall [--keep-data] [--yes]
#                                  every file and unit setting back to before the first install;
#                                  --keep-data keeps the app, its data, settings and certificates
#
# Idempotent: every step records a hash of its inputs (and of its own code) in
# /var/lib/teslalinux/install.state and is skipped while they are unchanged. Every system file it
# replaces is copied first to /var/lib/teslalinux/backup/<time>/ (MANIFEST lists replaced and created
# files and the previous state of every unit it enables, disables or masks). Log:
# /var/log/teslalinux/install-<time>.log.
set -euo pipefail

# The GitHub repository whose releases carry the dist and this script (the one place it is named)
TL_RELEASE_REPO="${TL_RELEASE_REPO:-Alexa-RR/teslalinux}"
# Waze's signing certificate (SHA-256), the reference for a first install from a download or a file
WAZE_SIGNER=03637f6c5d8f604e6fdb79a6ffbfa578de4e318f8da22fc6106665247f8807d7

PREFIX=/opt/teslalinux
DISTS=/opt/teslalinux.d
STATE_DIR=/var/lib/teslalinux
STATE="$STATE_DIR/install.state"
BACKUP_ROOT="$STATE_DIR/backup"
CACHE=/var/cache/teslalinux
LOG_DIR=/var/log/teslalinux
ENVF=/etc/teslalinux/env
LIB=/usr/local/lib/teslalinux
APPS=/var/lib/teslalinux/apps
TESLA_HOME=/var/lib/tesla
SELF=/usr/local/sbin/teslalinux
REBOOT_FLAG=/run/teslalinux/reboot-required
# directories that belong to teslalinux as a whole (not tracked file by file)
OWNED_DIRS=(/usr/share/teslalinux /usr/local/lib/teslalinux /usr/local/share/teslalinux /usr/local/share/doc/teslalinux
  /var/www/teslalinux /var/www/teslalinux-redirect /var/cache/teslalinux)
# the car stack's units (host/phase1-pios/etc/systemd), without the Waydroid session
UNITS=(teslalinux-netconfig.service teslalinux-dbus.service teslalinux-uplink.service tacomp.service
  teslalinux-api.service teslalinux-gps.service teslalinux-audio.service teslalinux-pipewire.service
  teslalinux-wireplumber.service teslalinux-pipewire-pulse.service teslalinux-atl.service)
APP_UNITS=(teslalinux-atl.service teslalinux-audio.service teslalinux-gps.service teslalinux-api.service
  teslalinux-pipewire-pulse.service teslalinux-wireplumber.service teslalinux-pipewire.service tacomp.service teslalinux-dbus.service)
# runtime packages next to the dist's MANIFEST list (tools/debian/carstack.sh, host/phase1-pios/provision.sh)
RUNTIME_PKGS=(nginx python3 python3-websockets openssl curl ca-certificates rsync ffmpeg dbus
  pipewire pipewire-pulse pipewire-alsa wireplumber libasound2-plugins seatd
  hostapd dnsmasq nftables network-manager modemmanager usb-modeswitch usb-modeswitch-data iw rfkill iproute2
  libgl1-mesa-dri libegl-mesa0 libgbm1 libgles2 v4l-utils
  fonts-roboto fonts-noto-core i2c-tools util-linux-extra unzip sudo zstd xz-utils)

# ---------------------------------------------------------------- output
TTY_OK=0
{ : < /dev/tty; } 2>/dev/null && TTY_OK=1
log() { printf '\033[1;36m[teslalinux]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[teslalinux] WARNING:\033[0m %s\n' "$*"; }
die() { printf '\033[1;31m[teslalinux] ERROR:\033[0m %s\n' "$*"; exit 1; }
ask() { # QUESTION: true on yes; --yes answers yes, no terminal answers no
  [[ ${YES:-0} == 1 ]] && return 0
  [[ $TTY_OK == 1 ]] || return 1
  local a; printf '%s [y/N] ' "$1" > /dev/tty; read -r a < /dev/tty || return 1
  [[ "$a" == y || "$a" == Y || "$a" == yes ]]
}
sha256() { sha256sum "$1" | cut -d' ' -f1; }
# hash of the contents of every file under the given paths (missing ones count as absent)
hash_paths() {
  local p
  for p in "$@"; do
    if [[ -d "$p" ]]; then find "$p" -type f -print0 | sort -z | xargs -0 -r sha256sum
    elif [[ -f "$p" ]]; then sha256sum "$p"
    else echo "absent $p"; fi
  done | sha256sum | cut -c1-32
}
sh_quote() { local s=$1 q="'"; s=${s//$q/$q\\$q$q}; printf "'%s'" "$s"; }

# ---------------------------------------------------------------- backups (track before changing)
RUN_TS=$(date +%Y%m%d-%H%M%S)
BK="$BACKUP_ROOT/$RUN_TS"
declare -A TRACKED=() UNIT_TRACKED=()
ensure_bk() {
  [[ -d "$BK" ]] && return 0
  mkdir -p "$BK/files"
  printf '%s\n' "${RUN_DIST_BEFORE:-}" > "$BK/dist.before"
  printf '# teslalinux install %s: replaced|created PATH, unit NAME PREVIOUS-STATE\n' "$RUN_TS" > "$BK/MANIFEST"
}
track() { # PATH...: keep what is there before this run changes it (once per path and run)
  local p
  for p in "$@"; do
    [[ -n "${TRACKED[$p]:-}" ]] && continue
    TRACKED[$p]=1
    ensure_bk
    # the set on disk is the truth (a subshell's track leaves no trace in TRACKED)
    if [[ -e "$BK/files$p" || -L "$BK/files$p" ]] || grep -qxF "$(printf 'created\t%s' "$p")" "$BK/MANIFEST"; then continue; fi
    if [[ -e "$p" || -L "$p" ]]; then
      mkdir -p "$BK/files$(dirname "$p")"
      cp -a "$p" "$BK/files$p"
      printf 'replaced\t%s\n' "$p" >> "$BK/MANIFEST"
    else
      printf 'created\t%s\n' "$p" >> "$BK/MANIFEST"
    fi
  done
}
put_file() { # SRC DEST MODE [OWNER:GROUP]: install unless DEST already is exactly that
  local src=$1 dst=$2 mode=$3 own=${4:-root:root}
  if [[ -f "$dst" && ! -L "$dst" ]] && cmp -s "$src" "$dst" \
     && [[ "$(stat -c '%a %U:%G' "$dst")" == "$(printf '%o' "$((8#$mode))") $own" ]]; then
    return 0
  fi
  track "$dst"
  install -D -m "$mode" -o "${own%%:*}" -g "${own##*:}" "$src" "$dst"
}
put_text() { # DEST MODE [OWNER:GROUP] < content (a here-document: never at the end of a pipe)
  local t; t=$(mktemp)
  cat > "$t"
  put_file "$t" "$1" "$2" "${3:-root:root}"
  rm -f "$t"
}
put_str() { # DEST MODE CONTENT [OWNER:GROUP]: CONTENT as printf %b reads it (\n for newlines)
  local t; t=$(mktemp)
  printf '%b' "$3" > "$t"
  put_file "$t" "$1" "$2" "${4:-root:root}"
  rm -f "$t"
}
unit_state() { systemctl is-enabled "$1" 2>/dev/null || true; }
unit_set() { # enable|disable|mask|unmask UNIT...: records each unit's previous state once per run
  local action=$1 u st; shift
  for u in "$@"; do
    if [[ -z "${UNIT_TRACKED[$u]:-}" ]]; then
      UNIT_TRACKED[$u]=1
      st=$(unit_state "$u"); st=${st%%$'\n'*}
      ensure_bk
      printf 'unit\t%s\t%s\n' "$u" "${st:-not-found}" >> "$BK/MANIFEST"
    fi
    systemctl "$action" "$u" >/dev/null 2>&1 || warn "systemctl $action $u failed"
  done
}

# ---------------------------------------------------------------- step state
state_get() { [[ -f "$STATE" ]] && awk -v k="$1" '$1 == k { print $2 }' "$STATE" || true; }
state_set() {
  mkdir -p "$STATE_DIR"
  { [[ -f "$STATE" ]] && awk -v k="$1" '$1 != k' "$STATE"; printf '%s %s\n' "$1" "$2"; } > "$STATE.tmp"
  mv "$STATE.tmp" "$STATE"
}
step_hash() { printf '%s\n%s\n' "$(declare -f "step_$1")" "$(inputs "$1")" | sha256sum | cut -c1-32; }
STEP_N=0
run_step() { # NAME: skipped while its code and inputs are unchanged
  local name=$1
  STEP_N=$((STEP_N + 1))
  if [[ $FORCE == 0 && -n "$(state_get "$name")" && "$(state_get "$name")" == "$(step_hash "$name")" ]]; then
    log "[$STEP_N] $name: unchanged, skipped"; return 0
  fi
  log "[$STEP_N] $name"
  "step_$name"
  # recomputed afterwards: a step may change what its inputs look at (config writes the env file)
  state_set "$name" "$(step_hash "$name")"
}
# what each step depends on besides its own code
inputs() {
  case "$1" in
    dist) echo "$DIST_SHA $DIST_DIR" ;;
    packages) echo "$(manifest_get packages) ${RUNTIME_PKGS[*]}" ;;
    user) echo "-" ;;
    files) hash_paths "$PAYLOAD/host" "$PAYLOAD/services" "$PAYLOAD/tools" ;;
    config) echo "$(hash_paths "$PAYLOAD/host/phase1-pios/env" "$ENVF" "${CONFIG:-/nonexistent}") ${SETS[*]:-}" ;;
    network) hash_paths "$ENVF" "$PAYLOAD/host/phase1-pios/etc" "$PAYLOAD/host/teslalinux-render" ;;
    tls) hash_paths "$ENVF" "$PAYLOAD/tls" /usr/local/bin/teslalinux-tls ;;
    web) hash_paths "$ENVF" "$PAYLOAD/web" "$PAYLOAD/host/phase1-pios/etc" /etc/teslalinux/tls ;;
    audio) echo "-" ;;
    display) model ;;
    appupdate) echo "$(hash_paths "$PAYLOAD/tools/appupdate" "$PAYLOAD/tools/apps" "$PREFIX/bin/tlplay") $WAZE_MODE" ;;
    waze) echo "$WAZE_MODE ${APK:+$(hash_paths "$APK")} $(hash_paths "$APPS/com.waze/installed.json")" ;;
    prewarm) echo "$PREWARM $(readlink -f "$PREFIX") $(ls "$TESLA_HOME"/.cache/art/*/*boot*.art 2>/dev/null | head -1)" ;;
    trim) echo "-" ;;
    boot) echo "$(model) $(rtc_detect) $(hash_paths "$PAYLOAD/host/rpi4")" ;;
    enable) echo "$(hash_paths /etc/systemd/system) $(tls_base_url) $TRIM" ;;
  esac
}

# ---------------------------------------------------------------- facts
model() { tr -d '\0' < /proc/device-tree/model 2>/dev/null || echo unknown; }
env_get() { # FILE KEY: the value with shell semantics (the env file is a sourced sh file)
  [[ -r "$1" ]] || return 0
  env -i sh -c 'set -a; . "$1" >/dev/null 2>&1; eval "printf %s \"\${$2-}\""' sh "$1" "$2"
}
env_has() { [[ -r "$1" ]] && env -i sh -c 'set -a; . "$1" >/dev/null 2>&1; eval "[ -n \"\${$2+x}\" ]"' sh "$1" "$2"; }
env_keys() { [[ -r "$1" ]] && sed -n 's/^[[:space:]]*\(export[[:space:]]\{1,\}\)\{0,1\}\([A-Z_][A-Z0-9_]*\)=.*/\2/p' "$1" | awk '!seen[$0]++' || true; }
cfg() { env_get "$ENVF" "$1"; }
# where the shared car certificate is fetched from (empty: fetching is off)
tls_base_url() { cfg TLS_BASE_URL; }
manifest_get() { [[ -f "$PREFIX/MANIFEST" ]] && sed -n "s/^$1=//p" "$PREFIX/MANIFEST" | head -1 || true; }
boot_dir() { if [[ -f /boot/firmware/config.txt ]]; then echo /boot/firmware; else echo /boot; fi; }
rtc_detect() { # yes | no | unknown (i2c not enabled yet: known after the reboot)
  [[ -e /dev/i2c-1 ]] || { echo unknown; return 0; }
  command -v i2cdetect >/dev/null || { echo unknown; return 0; }
  local row; row=$(i2cdetect -y 1 2>/dev/null | awk '$1 == "60:" { print $10 }')
  case "$row" in 68|UU) echo yes ;; *) echo no ;; esac
}
ssh_over_iface() { # IFACE: true when an SSH connection to this machine arrives over IFACE
  local addrs l ip
  addrs=" $(ip -o addr show dev "$1" 2>/dev/null | awk '{ sub(/\/.*/, "", $4); printf "%s ", $4 }')"
  [[ "$addrs" == " " ]] && return 1
  if [[ -n "${SSH_CONNECTION:-}" ]]; then
    ip=$(awk '{ print $3 }' <<<"$SSH_CONNECTION")
    [[ "$addrs" == *" $ip "* ]] && return 0
  fi
  while read -r l; do
    ip=${l%:*}; ip=${ip#[}; ip=${ip%]}; ip=${ip#::ffff:}
    [[ "$addrs" == *" $ip "* ]] && return 0
  done < <(ss -Htn state established '( sport = :22 )' 2>/dev/null | awk '{ print $3 }')
  return 1
}

# ================================================================ install
usage() {
  local src="${BASH_SOURCE[0]:-}"
  [[ -f "$src" ]] || src=$SELF
  awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$src" 2>/dev/null || echo "see tools/pios/install.sh"
}

parse_install_args() {
  DIST=latest DIST_SHA_WANT="" CONFIG="" APK="" WAZE_MODE="" PAYLOAD_OVERRIDE=""
  TRIM=1 PREWARM=1 FORCE=0 YES=0 REBOOT=ask
  SETS=()
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --dist) DIST=${2:?--dist needs a value}; shift ;;
      --sha256) DIST_SHA_WANT=${2:?}; shift ;;
      --config) CONFIG=${2:?}; shift ;;
      --set) [[ "${2:-}" =~ ^[A-Z_][A-Z0-9_]*= ]] || die "--set KEY=VALUE"; SETS+=("$2"); shift ;;
      --apk) APK=${2:?}; WAZE_MODE=apk; shift ;;
      --waze) WAZE_MODE=${2:?}; shift; [[ $WAZE_MODE == apkpure || $WAZE_MODE == none ]] || die "--waze apkpure|none" ;;
      --payload) PAYLOAD_OVERRIDE=${2:?}; shift ;;
      --no-trim) TRIM=0 ;;
      --no-prewarm) PREWARM=0 ;;
      --force) FORCE=1 ;;
      --yes|-y) YES=1 ;;
      --reboot) REBOOT=yes ;;
      --no-reboot) REBOOT=no ;;
      -h|--help) usage; exit 0 ;;
      *) die "unknown option $1 (--help)" ;;
    esac
    shift
  done
  [[ -z "$APK" || -e "$APK" ]] || die "--apk: no such file or directory: $APK"
  [[ -z "$CONFIG" || -r "$CONFIG" ]] || die "--config: cannot read $CONFIG"
  [[ -z "$DIST_SHA_WANT" || "$DIST_SHA_WANT" =~ ^[0-9a-fA-F]{64}$ ]] || die "--sha256 needs 64 hex digits"
  DIST_SHA_WANT=${DIST_SHA_WANT,,}
  [[ -n "$APK" ]] && APK=$(readlink -f "$APK")
  [[ -n "$CONFIG" ]] && CONFIG=$(readlink -f "$CONFIG")
  return 0
}

# the value a setting will have: --set, then --config, then the installed env (from settings)
setting() {
  local k=$1 s
  for s in "${SETS[@]}"; do [[ "${s%%=*}" == "$k" ]] && { printf '%s' "${s#*=}"; return 0; }; done
  if [[ -n "$CONFIG" ]] && env_has "$CONFIG" "$k"; then env_get "$CONFIG" "$k"; return 0; fi
  env_get "$ENVF" "$k"
}

preflight() {
  log "preflight"
  [[ $EUID -eq 0 ]] || die "run as root (sudo)"
  [[ -d /run/systemd/system ]] || die "needs systemd as init"
  if systemd-detect-virt -q --container 2>/dev/null; then die "this is a container; the installer needs the Pi itself"; fi
  [[ "$(uname -m)" == aarch64 ]] || die "needs a 64-bit kernel (uname -m: $(uname -m)); install Raspberry Pi OS Lite (64-bit)"
  [[ "$(dpkg --print-architecture)" == arm64 ]] || die "needs a 64-bit (arm64) userland, this is $(dpkg --print-architecture); install Raspberry Pi OS Lite (64-bit)"
  local id codename
  id=$(. /etc/os-release && echo "${ID:-}"); codename=$(. /etc/os-release && echo "${VERSION_CODENAME:-}")
  [[ "$id" == debian || "$id" == raspbian ]] && [[ "$codename" == trixie ]] || die "needs Raspberry Pi OS / Debian 13 trixie (found $id $codename)"
  [[ -f /etc/rpi-issue ]] || warn "no /etc/rpi-issue: not Raspberry Pi OS (plain Debian on the Pi works if its kernel has the Pi's V3D and codec drivers)"
  local m; m=$(model)
  case "$m" in
    "Raspberry Pi 4"*|"Raspberry Pi 400"*|"Raspberry Pi Compute Module 4"*) ;;
    "Raspberry Pi 5"*|"Raspberry Pi Compute Module 5"*|"Raspberry Pi 500"*)
      die "$m is not supported yet: the Pi 5 has no hardware video encoder (tacomp's M2M path), a different GPU (V3D 7) and Pi OS runs it with 16K pages, which this ART build is not made for. Use a Pi 4 for now." ;;
    *) die "unsupported board '$m' (needs a Raspberry Pi 4 / 400 / CM4)" ;;
  esac
  local mem; mem=$(awk '/^MemTotal:/ { print int($2 / 1024) }' /proc/meminfo)
  # a 2 GB board reports about 1.8 GiB (firmware and CMA take the rest)
  (( mem >= 1700 )) || die "needs a board with at least 2 GB of RAM (MemTotal ${mem} MiB)"
  local free; free=$(df -Pk / | awk 'NR == 2 { print int($4 / 1024) }')
  (( free >= 4096 )) || die "needs at least 4 GiB free on / (${free} MiB free)"
  [[ "$(getconf PAGESIZE)" == 4096 ]] || die "needs a 4K-page kernel (PAGESIZE $(getconf PAGESIZE)); kernel8.img is the Pi 4's"
  if [[ -e "$PREFIX" && ! -L "$PREFIX" ]]; then
    die "$PREFIX is a directory, not the installer's symlink (a build tree?): move it away (mv $PREFIX $PREFIX.local) and run again"
  fi
  local ap sub; ap=$(setting AP_IFACE); ap=${ap:-wlan0}; sub=$(setting AP_SUBNET); sub=${sub:-172.16.0}
  [[ -d "/sys/class/net/$ap/wireless" || -d "/sys/class/net/$ap/phy80211" ]] || warn "$ap is not a Wi-Fi interface here; the hotspot needs one (AP_IFACE)"
  if ssh_over_iface "$ap"; then
    warn "this SSH session runs over $ap. After the reboot $ap becomes the car hotspot and this connection is gone; join the hotspot and ssh to $sub.1, or use Ethernet."
    ask "Continue?" || die "stopped (--yes to go ahead)"
  elif [[ "$(nmcli -t -f DEVICE,STATE device 2>/dev/null | sed -n "s/^$ap://p")" == connected ]]; then
    warn "$ap is connected to a Wi-Fi network now; after the reboot it is the car hotspot and the Pi uses LTE, Ethernet or a second Wi-Fi card for internet"
  fi
  if [[ "$WAZE_MODE" == apk ]]; then
    [[ -e "$APK" ]] || die "--apk $APK does not exist"
  fi
}

# ---------------------------------------------------------------- dist: where it comes from
download() { # URL OUT
  curl -fL --retry 5 --retry-delay 3 --connect-timeout 20 -o "$2.part" "$1" && mv "$2.part" "$2"
}
resolve_dist() {
  mkdir -p "$CACHE/dist"
  local base="" sums name
  case "$DIST" in
    latest) base="https://github.com/$TL_RELEASE_REPO/releases/latest/download" ;;
    v[0-9]*) base="https://github.com/$TL_RELEASE_REPO/releases/download/$DIST" ;;
  esac
  if [[ -n "$base" ]]; then
    sums="$CACHE/dist/SHA256SUMS.$DIST"
    if ! download "$base/SHA256SUMS" "$sums"; then
      if [[ -L "$PREFIX" && -f "$PREFIX/MANIFEST" && -z "$DIST_SHA_WANT" ]]; then
        warn "cannot reach $base (offline?): keeping the installed dist $(manifest_get version)"
        DIST_FILE="" DIST_SHA=$(cat "$PREFIX/.dist-sha256" 2>/dev/null || echo installed) DIST_DIR=$(readlink -f "$PREFIX")
        return 0
      fi
      die "cannot download $base/SHA256SUMS (no release '$DIST' in $TL_RELEASE_REPO, or no network)"
    fi
    name=$(awk '$2 ~ /^teslalinux-dist-.*-arm64\.tar\.(zst|xz)$/ { print $2; exit }' "$sums")
    [[ -n "$name" ]] || die "the release's SHA256SUMS lists no teslalinux-dist tarball"
    DIST_SHA=$(awk -v n="$name" '$2 == n { print $1 }' "$sums")
    [[ -z "$DIST_SHA_WANT" || "$DIST_SHA_WANT" == "$DIST_SHA" ]] || die "--sha256 $DIST_SHA_WANT does not match the release's $DIST_SHA"
    DIST_FILE="$CACHE/dist/$name"
    if [[ ! -f "$DIST_FILE" || "$(sha256 "$DIST_FILE")" != "$DIST_SHA" ]]; then
      log "downloading $name"
      download "$base/$name" "$DIST_FILE" || die "download of $base/$name failed"
    fi
  elif [[ "$DIST" =~ ^https?:// ]]; then
    name=$(basename "${DIST%%\?*}")
    DIST_FILE="$CACHE/dist/$name"
    DIST_SHA=$DIST_SHA_WANT
    if [[ -z "$DIST_SHA" ]]; then
      sums="$CACHE/dist/SHA256SUMS.url"
      download "${DIST%/*}/SHA256SUMS" "$sums" 2>/dev/null && DIST_SHA=$(awk -v n="$name" '$2 == n { print $1 }' "$sums")
      [[ -n "$DIST_SHA" ]] || die "no checksum for $DIST: pass --sha256 HEX (or publish a SHA256SUMS next to it)"
    fi
    if [[ ! -f "$DIST_FILE" || "$(sha256 "$DIST_FILE")" != "$DIST_SHA" ]]; then
      log "downloading $DIST"
      download "$DIST" "$DIST_FILE" || die "download of $DIST failed"
    fi
  else
    [[ -f "$DIST" ]] || die "--dist: no such file: $DIST"
    DIST_FILE=$(readlink -f "$DIST")
    DIST_SHA=$DIST_SHA_WANT
    if [[ -z "$DIST_SHA" && -f "$(dirname "$DIST_FILE")/SHA256SUMS" ]]; then
      DIST_SHA=$(awk -v n="$(basename "$DIST_FILE")" '$2 == n { print $1 }' "$(dirname "$DIST_FILE")/SHA256SUMS")
    fi
    [[ -n "$DIST_SHA" ]] || { DIST_SHA=$(sha256 "$DIST_FILE"); warn "no checksum given for $DIST_FILE (a local file is trusted): sha256 $DIST_SHA"; }
  fi
  local got; got=$(sha256 "$DIST_FILE")
  [[ "$got" == "$DIST_SHA" ]] || die "$DIST_FILE: sha256 $got, expected $DIST_SHA"
  log "dist $(basename "$DIST_FILE") sha256 ok"
  # the version from the MANIFEST inside, and where it goes
  case "$DIST_FILE" in
    *.zst) command -v zstd >/dev/null || { apt-get install -y -q zstd >/dev/null || die "zstd is needed to unpack $DIST_FILE"; }; TAR_Z=--zstd ;;
    *.xz) TAR_Z=--xz ;;
    *) die "unknown dist format: $DIST_FILE" ;;
  esac
  local man; man=$(tar $TAR_Z -xOf "$DIST_FILE" --wildcards '*/MANIFEST' 2>/dev/null) || die "$DIST_FILE holds no MANIFEST"
  DIST_VERSION=$(sed -n 's/^version=//p' <<<"$man" | head -1)
  [[ "$DIST_VERSION" =~ ^[A-Za-z0-9._+-]+$ ]] || die "bad dist version '$DIST_VERSION'"
  [[ "$(sed -n 's/^arch=//p' <<<"$man" | head -1)" == arm64 ]] || die "the dist is not for arm64"
  DIST_DIR="$DISTS/$DIST_VERSION"
  if [[ -d "$DIST_DIR" && "$(cat "$DIST_DIR/.dist-sha256" 2>/dev/null)" != "$DIST_SHA" ]]; then
    DIST_DIR="$DISTS/$DIST_VERSION-${DIST_SHA:0:8}"
  fi
}

# ================================================================ steps
step_dist() {
  if [[ -z "${DIST_FILE:-}" ]]; then log "  keeping $DIST_DIR"; return 0; fi
  if [[ ! -d "$DIST_DIR" ]]; then
    log "  unpacking into $DIST_DIR"
    local tmp="$DIST_DIR.tmp-$$"
    rm -rf "$tmp"; mkdir -p "$tmp"
    tar $TAR_Z -xf "$DIST_FILE" -C "$tmp" --strip-components=1 --no-same-owner
    printf '%s\n' "$DIST_SHA" > "$tmp/.dist-sha256"
    mv "$tmp" "$DIST_DIR"
  fi
  local cur; cur=$(readlink -f "$PREFIX" 2>/dev/null || true)
  if [[ "$cur" != "$DIST_DIR" ]]; then
    log "  $PREFIX -> $DIST_DIR (was ${cur:-none})"
    ln -sfn "$DIST_DIR" "$PREFIX.new"
    mv -T "$PREFIX.new" "$PREFIX"
    # ART's cached boot image and app code belong to the old jars
    rm -rf "$TESLA_HOME/.cache/art"
  fi
  # the ATL stack's libraries (lib, lib/art) for the loader and dex2oat; tacomp uses its RUNPATH
  put_str /etc/ld.so.conf.d/teslalinux.conf 0644 "$PREFIX/lib\n$PREFIX/lib/art\n"
  ldconfig
  # keep the current dist and the one the latest backup set returns to; drop older ones
  local keep_prev="" d
  keep_prev=$(cat "$(ls -1d "$BACKUP_ROOT"/2* 2>/dev/null | tail -1)/dist.before" 2>/dev/null || true)
  for d in "$DISTS"/*; do
    [[ -d "$d" ]] || continue
    case "$d" in "$DIST_DIR"|"$keep_prev"|"${RUN_DIST_BEFORE:-/none}") continue ;; esac
    log "  removing the old dist $d"; rm -rf "$d"
  done
}

step_packages() {
  export DEBIAN_FRONTEND=noninteractive
  log "  apt-get update"
  apt-get update -q >/dev/null
  local want=() avail=() missing=() p
  read -r -a want <<<"$(manifest_get packages) ${RUNTIME_PKGS[*]}"
  for p in "${want[@]}"; do
    # never build tools or desktop compositors on the car
    case "$p" in *-dev|gcc|gcc-*|g++|g++-*|cpp|openjdk-*|default-jdk*|cage|weston) warn "  not installing build/desktop package $p"; continue ;; esac
    if apt-cache show "$p" >/dev/null 2>&1; then avail+=("$p"); else missing+=("$p"); fi
  done
  if [[ ${#missing[@]} -gt 0 ]]; then
    die "packages not available from this system's apt sources: ${missing[*]} (Raspberry Pi OS trixie arm64 has them; check /etc/apt/sources.list.d)"
  fi
  log "  installing ${#avail[@]} packages"
  apt-get install -y -q --no-install-recommends "${avail[@]}"
  # Debian starts these with their default configs at install; ours are written below and they
  # start at the next boot (the hotspot takes the Wi-Fi interface only then)
  systemctl stop dnsmasq 2>/dev/null || true
  systemctl stop nginx 2>/dev/null || true
}

step_user() {
  id tesla >/dev/null 2>&1 || useradd -r -m -d "$TESLA_HOME" -s /usr/sbin/nologin tesla
  usermod -aG video,render,input,audio tesla
  put_str /etc/tmpfiles.d/tesla.conf 0644 'd /run/tesla 0700 tesla tesla -\n'
  systemd-tmpfiles --create /etc/tmpfiles.d/tesla.conf
  # Android apps open fonts by their Android path (uninstall removes /system/fonts)
  install -d /system/fonts
  local f
  for f in /usr/share/fonts/truetype/roboto/unhinted/RobotoTTF/*.ttf /usr/share/fonts/truetype/noto/*.ttf; do
    [[ -f "$f" ]] && ln -sf "$f" /system/fonts/
  done
  return 0
}

step_files() {
  local P=$PAYLOAD f
  install -d -m 0755 /etc/teslalinux "$LIB" "$LIB/apps" "$LIB/check" /usr/share/teslalinux "$STATE_DIR" "$LOG_DIR"
  install -d -m 0750 -o tesla -g tesla "$STATE_DIR/state"
  install -d -m 0755 -o tesla -g tesla "$APPS"
  # the templates teslalinux-render reads (at boot too, teslalinux-netconfig.service)
  rm -rf /usr/share/teslalinux/templates; install -d /usr/share/teslalinux/templates
  cp -r "$P/host/phase1-pios/etc/." /usr/share/teslalinux/templates/
  install -m 0644 "$P/host/phase1-pios/env" /usr/share/teslalinux/env.template
  for f in teslalinux-render teslalinux-restart-app teslalinux-set-locale teslalinux-set-keyboard teslalinux-network \
           teslalinux-system-update teslalinux-uplink teslalinux-dexopt; do
    put_file "$P/host/$f" "/usr/local/bin/$f" 0755
  done
  put_file "$P/host/phase1-pios/teslalinux-apply-display" /usr/local/bin/teslalinux-apply-display 0755
  put_file "$P/host/phase1-pios/teslalinux-atl" /usr/local/bin/teslalinux-atl 0755
  put_file "$P/host/tls/teslalinux-tls" /usr/local/bin/teslalinux-tls 0755
  install -m 0644 "$P/services/gps/gps.py" "$P/services/audio/audio.py" "$P/services/api/api.py" "$P/services/api/tlprops.py" "$LIB/"
  install -m 0644 "$P/tools/atl-vm/mk-android-tzdata.py" "$LIB/mk-android-tzdata.py"
  install -m 0755 "$P/tools/apps/install-app.sh" "$P/tools/apps/apk-info.py" "$LIB/apps/"
  install -m 0644 "$P/tools/apps/apk_verify.py" "$LIB/apps/"
  install -m 0644 "$P/host/local/check.py" "$P/services/display/test/ws-client.py" "$LIB/check/"
  put_str /etc/sudoers.d/teslalinux 0440 'tesla ALL=(root) NOPASSWD: /usr/local/bin/teslalinux-apply-display, /usr/local/bin/teslalinux-restart-app, /usr/local/bin/teslalinux-set-locale, /usr/local/bin/teslalinux-set-keyboard, /usr/local/bin/teslalinux-network, /usr/local/bin/teslalinux-system-update\n'
  visudo -cq -f /etc/sudoers.d/teslalinux || die "sudoers check failed for /etc/sudoers.d/teslalinux"
  for f in "${UNITS[@]}" teslalinux-tls-fetch.service teslalinux-tls-fetch.timer; do
    put_file "$P/host/phase1-pios/etc/systemd/$f" "/etc/systemd/system/$f" 0644
  done
  put_file "$P/host/phase1-pios/etc/NetworkManager/90-teslalinux-tls" /etc/NetworkManager/dispatcher.d/90-teslalinux-tls 0755
  for f in "$P"/host/phase1-pios/etc/usb_modeswitch.d/*; do
    put_file "$f" "/etc/usb_modeswitch.d/$(basename "$f")" 0644
  done
  # this script, for doctor / status / rollback / uninstall
  put_file "$P/tools/pios/install.sh" "$SELF" 0755
  [[ -f /etc/teslalinux/build-time ]] || date +%s > /etc/teslalinux/build-time
  systemctl daemon-reload
}

step_config() {
  local tpl="$PAYLOAD/host/phase1-pios/env" k v line out tv
  declare -A val=()
  local keys=() extra=()
  mapfile -t keys < <(env_keys "$tpl")
  # every key: --set > --config > the installed env > the template
  local all=(); mapfile -t all < <({ env_keys "$tpl"; env_keys "$ENVF"; [[ -n "$CONFIG" ]] && env_keys "$CONFIG"; for v in "${SETS[@]}"; do echo "${v%%=*}"; done; } | awk 'NF && !seen[$0]++')
  for k in "${all[@]}"; do
    case "$k" in LTE_*) continue ;; esac
    if env_has "$tpl" "$k"; then val[$k]=$(env_get "$tpl" "$k"); fi
    if env_has "$ENVF" "$k"; then val[$k]=$(env_get "$ENVF" "$k"); fi
    if [[ -n "$CONFIG" ]] && env_has "$CONFIG" "$k"; then val[$k]=$(env_get "$CONFIG" "$k"); fi
    for v in "${SETS[@]}"; do [[ "${v%%=*}" == "$k" ]] && val[$k]=${v#*=}; done
  done
  # the hotspot password: never the template's placeholder; a random one when none was given
  if [[ -z "${val[AP_PSK]:-}" || "${val[AP_PSK]}" == changeit ]]; then
    val[AP_PSK]=$(openssl rand -base64 24 | tr -dc 'A-Za-z0-9' | cut -c1-16)
    GENERATED_PSK=${val[AP_PSK]}
  fi
  [[ "${val[AP_PSK]}" =~ ^[[:print:]]{8,63}$ ]] || die "AP_PSK must be 8 to 63 printable ASCII characters"
  [[ -n "${val[AP_SSID]:-}" && ${#val[AP_SSID]} -le 32 ]] || die "AP_SSID must be 1 to 32 characters"
  # the Wi-Fi country: the one set for this Pi (raspi-config / Imager put it on the kernel command
  # line) unless one was given or is already installed
  if ! env_has "$ENVF" AP_COUNTRY && { [[ -z "$CONFIG" ]] || ! env_has "$CONFIG" AP_COUNTRY; } && [[ " ${SETS[*]:-} " != *" AP_COUNTRY="* ]]; then
    local rd; rd=$(sed -n 's/.*cfg80211\.ieee80211_regdom=\([A-Z][A-Z]\).*/\1/p' /proc/cmdline)
    [[ -n "$rd" ]] && val[AP_COUNTRY]=$rd
  fi
  [[ "${val[AP_COUNTRY]:-}" =~ ^[A-Z]{2}$ ]] || die "AP_COUNTRY must be a two-letter country code"
  [[ "${val[DEVICE_HOST]:-}" =~ ^[A-Za-z0-9]([A-Za-z0-9.-]{0,251}[A-Za-z0-9])?$ ]] || die "DEVICE_HOST is not a host name"
  [[ "${val[AP_SUBNET]:-}" =~ ^[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}$ ]] || die "AP_SUBNET must be the first three octets (172.16.0)"
  # the template's text, with the lines whose value differs replaced; keys it lacks appended
  out=$(mktemp)
  while IFS= read -r line || [[ -n "$line" ]]; do
    if [[ "$line" =~ ^[[:space:]]*(export[[:space:]]+)?([A-Z_][A-Z0-9_]*)= ]]; then
      k=${BASH_REMATCH[2]}
      tv=$(env_get "$tpl" "$k")
      if [[ -n "${val[$k]+x}" && "${val[$k]}" != "$tv" ]]; then line="$k=$(sh_quote "${val[$k]}")"; fi
    fi
    printf '%s\n' "$line"
  done < "$tpl" > "$out"
  declare -A in_tpl=()
  for k in "${keys[@]}"; do in_tpl[$k]=1; done
  for k in "${all[@]}"; do
    case "$k" in LTE_*) continue ;; esac
    [[ -n "${in_tpl[$k]:-}" ]] || extra+=("$k")
  done
  if [[ ${#extra[@]} -gt 0 ]]; then
    printf '\n# settings kept from the installed env, --config and --set\n' >> "$out"
    for k in "${extra[@]}"; do printf '%s=%s\n' "$k" "$(sh_quote "${val[$k]}")" >> "$out"; done
  fi
  # the hotspot password is in it: root and the API (user tesla) only
  put_file "$out" "$ENVF" 0640 root:tesla
  rm -f "$out"
  # LTE: the APN and its credentials, through the same tool as the settings page
  local apn user pass roam
  apn=$(setting LTE_APN); user=$(setting LTE_USER); pass=$(setting LTE_PASSWORD); roam=$(setting LTE_ROAMING)
  if [[ -n "$apn$user$pass$roam" ]]; then
    track /etc/teslalinux/lte.env
    LTE_APN=$apn LTE_USER=$user LTE_PASSWORD=$pass LTE_ROAMING=$roam python3 -c '
import json, os
e = os.environ
lte = {"apn": e["LTE_APN"], "user": e["LTE_USER"], "enabled": True,
       "roaming": e["LTE_ROAMING"].lower() in ("1", "true", "yes", "on")}
if e["LTE_PASSWORD"]:
    lte["password"] = e["LTE_PASSWORD"]
print(json.dumps({"lte": lte}))' | /usr/local/bin/teslalinux-network apply boot || warn "the LTE settings were not applied (teslalinux-network)"
  fi
}

step_network() {
  local ap sub
  ap=$(cfg AP_IFACE); sub=$(cfg AP_SUBNET)
  track /etc/hostapd/hostapd.conf /etc/dnsmasq.conf /etc/nftables.conf
  /usr/local/bin/teslalinux-render --root / --layout pios --env "$ENVF" --only net
  put_str /etc/NetworkManager/conf.d/teslalinux-unmanaged.conf 0644 "[keyfile]\nunmanaged-devices=interface-name:$ap\n"
  put_text /etc/systemd/system/teslalinux-ap-addr.service 0644 <<EOF
[Unit]
Description=teslalinux: static address on $ap
Before=hostapd.service dnsmasq.service
DefaultDependencies=no
After=sys-subsystem-net-devices-$ap.device
[Service]
Type=oneshot
RemainAfterExit=yes
ExecStart=/usr/sbin/rfkill unblock wlan
ExecStart=/usr/sbin/ip addr replace $sub.1/24 dev $ap
ExecStart=/usr/sbin/ip link set $ap up
[Install]
WantedBy=sysinit.target
EOF
  put_str /etc/sysctl.d/90-teslalinux.conf 0644 'net.ipv4.ip_forward=1\nnet.core.rmem_max=8388608\nnet.core.wmem_max=8388608\n'
  if [[ -e /etc/sysctl.d/91-teslalinux-vm.conf ]]; then track /etc/sysctl.d/91-teslalinux-vm.conf; rm -f /etc/sysctl.d/91-teslalinux-vm.conf; fi
  put_str /etc/systemd/system/dnsmasq.service.d/teslalinux.conf 0644 '[Unit]\nAfter=teslalinux-ap-addr.service\nWants=teslalinux-ap-addr.service\n'
  put_str /etc/systemd/system/hostapd.service.d/teslalinux.conf 0644 '[Unit]\nAfter=teslalinux-ap-addr.service\nWants=teslalinux-ap-addr.service\n[Service]\nRestart=always\nRestartSec=1\n'
  # LTE: ModemManager off the critical path
  put_str /etc/systemd/system/ModemManager.service.d/teslalinux.conf 0644 '[Unit]\nAfter=hostapd.service nginx.service\n'
  systemctl daemon-reload
}

step_tls() {
  local b=/usr/share/teslalinux/tls
  install -d -m 0755 "$STATE_DIR/tls-state"
  # the shared car certificate as published when this release was built (pack-dist.sh), so the car
  # page works before the Pi's first uplink; teslalinux-tls init installs a pair only if it passes
  # every check and is newer than the one installed, and teslalinux-tls-fetch.timer keeps it current
  rm -rf "$b"
  if [[ -s "$PAYLOAD/tls/current/fullchain.pem" || -s "$PAYLOAD/tls/standby/fullchain.pem" ]]; then
    install -d -m 0700 "$b"
    cp -r "$PAYLOAD/tls/." "$b/"
    chmod -R go-rwx "$b"
  else
    warn "this release carries no certificate pairs: the Pi fetches them on its first uplink (self-signed until then)"
  fi
  # no nginx test/reload here: step_web renders the site, tests it and restarts nginx next
  TLS_NO_RELOAD=1 /usr/local/bin/teslalinux-tls init
}

step_web() {
  local f
  install -d /var/www/teslalinux
  rsync -a --delete "$PAYLOAD/web/" /var/www/teslalinux/
  track /etc/nginx/sites-available/teslalinux /etc/nginx/sites-enabled/teslalinux /etc/nginx/sites-enabled/default /etc/teslalinux/display.env
  for f in teslalinux-tls.inc teslalinux-mime.inc teslalinux-static.inc teslalinux-app.inc teslalinux-nocache.inc teslalinux-api.inc teslalinux-ws.inc; do
    track "/etc/nginx/$f"
  done
  /usr/local/bin/teslalinux-render --root / --layout pios --env "$ENVF" --only web
  /usr/local/bin/teslalinux-render --root / --layout pios --env "$ENVF" --only display
  # everything once more, which also writes the stamp teslalinux-netconfig.service compares at boot
  /usr/local/bin/teslalinux-render --root / --layout pios --env "$ENVF" >/dev/null
  ln -sfn /etc/nginx/sites-available/teslalinux /etc/nginx/sites-enabled/teslalinux
  rm -f /etc/nginx/sites-enabled/default
  nginx -t 2>&1 | sed 's/^/  /'
  nginx -t >/dev/null 2>&1 || die "nginx -t failed"
  systemctl restart nginx
}

step_audio() {
  put_text /etc/pipewire/pipewire.conf.d/teslalinux-sink.conf 0644 <<'EOF'
context.objects = [
  { factory = adapter
    args = { factory.name = support.null-audio-sink node.name = "android" media.class = Audio/Sink
             object.linger = true audio.position = [ FL FR ] audio.rate = 48000
             priority.session = 3000 priority.driver = 3000 }
  }
]
EOF
  # the null sink is the only sink: no ALSA/Bluetooth monitors, so the Pi's own outputs never win
  put_text /etc/wireplumber/wireplumber.conf.d/teslalinux.conf 0644 <<'EOF'
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
  put_str /etc/asound.conf 0644 '# teslalinux: every ALSA client plays through PipeWire\npcm.!default { type pipewire }\nctl.!default { type pipewire }\n'
  # PipeWire runs as system units for tesla in /run/tesla (teslalinux-pipewire*.service), no user session
  loginctl disable-linger tesla 2>/dev/null || true
}

step_display() {
  local m; m=$(model)
  # Pi 4: tacomp renders with GLES on V3D (/dev/dri/renderD128) and encodes on the VPU's V4L2 M2M
  # encoder (/dev/video11; TACOMP_ENCODER comes from display.env, m2m-jpeg by default); the app's GTK
  # draws with its GL renderer ("ngl": GTK 4.18 removed "gl")
  log "  $m: WLR_RENDERER=gles2, GSK_RENDERER=ngl"
  put_str /etc/systemd/system/tacomp.service.d/20-model.conf 0644 "# teslalinux install: $m\n[Service]\nEnvironment=WLR_RENDERER=gles2\n"
  put_str /etc/systemd/system/teslalinux-atl.service.d/20-model.conf 0644 "# teslalinux install: $m\n[Service]\nEnvironment=GSK_RENDERER=ngl\n"
  # the test VM's software-rendering drop-ins (tools/debian/carstack.sh --vm) never belong on a Pi
  local f
  for f in /etc/systemd/system/tacomp.service.d/50-vm.conf /etc/systemd/system/teslalinux-atl.service.d/50-vm.conf; do
    if [[ -e "$f" ]]; then track "$f"; rm -f "$f"; fi
  done
  systemctl daemon-reload
}

step_appupdate() {
  local tl=none f
  [[ -x "$PREFIX/bin/tlplay" ]] && tl="$PREFIX/bin/tlplay"
  for f in /usr/local/bin/tlplay /usr/local/bin/teslalinux-appupdate /etc/systemd/system/teslalinux-appupdate.service \
           /etc/systemd/system/teslalinux-appupdate.timer /etc/systemd/system/teslalinux-appupdate-boot.service \
           /etc/systemd/system/teslalinux-appupdate-health.service /etc/NetworkManager/dispatcher.d/91-teslalinux-appupdate \
           /etc/sudoers.d/teslalinux-appupdate; do
    track "$f"
  done
  sh "$PAYLOAD/tools/appupdate/install.sh" --tlplay "$tl" --no-enable
  # a first install has no installed app to compare the signer with: Waze's is pinned
  put_str /etc/teslalinux/appupdate.env 0644 "# teslalinux install: the signing certificate a first Waze install must have\nAPPUPDATE_FIRST_SIGNER=$WAZE_SIGNER\n"
  # the update source (the settings page changes it later): APKPure when asked for or when there is
  # a downloader, uploads only with --waze none or without tlplay
  local settings="$STATE_DIR/state/appupdate.json" src=""
  case "$WAZE_MODE" in
    apkpure) src=apkpure ;;
    none) src=manual ;;
    *) if [[ ! -f "$settings" && $tl == none ]]; then src=manual; fi ;;
  esac
  if [[ $tl == none && $src == apkpure ]]; then src=manual; fi
  if [[ -n "$src" ]]; then
    SRC=$src python3 -c '
import json, os, sys
p = sys.argv[1]
try:
    s = json.load(open(p))
except (OSError, ValueError):
    s = {}
s["source"] = os.environ["SRC"]
json.dump(s, open(p, "w"), indent=1, sort_keys=True)' "$settings"
    chown tesla:tesla "$settings"; chmod 0644 "$settings"
    log "  update source: $src"
  fi
}

apk_signer() { # APK: the verified signer (APK Signature Scheme v3/v2), or nothing
  python3 -c 'import sys; sys.path.insert(0, "/usr/local/lib/teslalinux/apps"); import apk_verify; print(apk_verify.verify(sys.argv[1], 32)[1])' "$1" 2>/dev/null || true
}
step_waze() {
  local installed=""
  [[ -f "$APPS/com.waze/base.apk" ]] && installed=1
  case "$WAZE_MODE" in
    apk)
      local t; t=$(mktemp -d); chmod 0755 "$t"
      if [[ -d "$APK" ]]; then cp "$APK"/*.apk "$t/"; else cp "$APK" "$t/"; fi
      chmod -R a+rX "$t"
      local base="$t/base.apk"
      if [[ ! -d "$APK" ]]; then unzip -p "$APK" base.apk > "$t/.base-check.apk" 2>/dev/null && base="$t/.base-check.apk"; fi
      if [[ -f "$base" ]]; then
        local sg; sg=$(apk_signer "$base")
        if [[ "$sg" != "$WAZE_SIGNER" ]]; then
          rm -rf "$t"; die "--apk: base.apk is not Waze signed by Waze's certificate (signer ${sg:-unverifiable})"
        fi
      fi
      rm -f "$t/.base-check.apk"
      local src="$t"; [[ -d "$APK" ]] || src="$t/$(basename "$APK")"
      runuser -u tesla -- env APPS_DIR="$APPS" sh "$LIB/apps/install-app.sh" "$src" --no-restart
      rm -rf "$t" "$TESLA_HOME"/.cache/art/*/*base* 2>/dev/null || true
      WAZE_STATUS="installed from $(basename "$APK")"
      ;;
    apkpure)
      if [[ ! -x /usr/local/bin/tlplay ]]; then
        warn "tlplay is not in this dist: no download. Install Waze with --apk PATH, or upload it on the car page (Settings)."
        WAZE_STATUS="not installed (no downloader in this dist; use --apk or the car page's upload)"
        return 0
      fi
      log "  APKPure: looking up Waze (verified against Waze's signer, staged for the next start)"
      local out rc=0
      out=$(set -a; . /etc/teslalinux/appupdate.env; set +a; /usr/local/bin/teslalinux-appupdate stage --force 2>&1) || rc=$?
      printf '%s\n' "$out" | tail -5 | sed 's/^/  /'
      if [[ $rc != 0 && -z "$installed" ]]; then
        warn "Waze was not downloaded now; the updater retries hourly (teslalinux-appupdate status), or use --apk / the car page's upload"
        WAZE_STATUS="pending download (teslalinux-appupdate status)"
      else
        WAZE_STATUS=$(printf '%s\n' "$out" | tail -1)
      fi
      ;;
    *)
      if [[ -n "$installed" ]]; then WAZE_STATUS="installed"
      else WAZE_STATUS="not installed: upload it on the car page (Settings), or run again with --apk PATH or --waze apkpure"; fi
      ;;
  esac
}

step_prewarm() {
  [[ $PREWARM == 1 ]] || { log "  skipped (--no-prewarm)"; return 0; }
  # ART builds its boot image (the core libraries, AOT) on the first start of any VM; dalvikvm
  # creates the VM before it looks for a class, so a run without one leaves the image in the cache
  # the app's later starts use (ATL uses the same default boot class path). A few minutes on a Pi 4.
  # This step runs when the dist changed or no image exists: whatever is cached is stale.
  rm -rf "$TESLA_HOME/.cache/art"
  install -d -o tesla -g tesla "$TESLA_HOME/.cache" "$TESLA_HOME/android-data"
  log "  building ART's boot image as tesla"
  runuser -u tesla -- env -i HOME="$TESLA_HOME" PATH="$PREFIX/bin:/usr/bin:/bin" LD_LIBRARY_PATH="$PREFIX/lib" \
    ANDROID_DATA="$TESLA_HOME/android-data" timeout 1800 "$PREFIX/bin/dalvikvm" >/dev/null 2>&1 || true
  if ls "$TESLA_HOME"/.cache/art/*/*boot*.art >/dev/null 2>&1; then
    log "  boot image ready ($(du -sh "$TESLA_HOME/.cache/art" | cut -f1))"
  else
    warn "no boot image was built now; the app's first start builds it"
  fi
}

step_trim() {
  # off the boot path; disabled, not stopped (this session may depend on some of them until the reboot)
  local u units=()
  for u in NetworkManager-wait-online.service systemd-networkd-wait-online.service apt-daily.timer apt-daily-upgrade.timer \
           man-db.timer e2scrub_all.timer rpi-eeprom-update.service raspi-config.service avahi-daemon.service avahi-daemon.socket \
           bluetooth.service hciuart.service triggerhappy.service triggerhappy.socket cups.service cups.socket serial-getty@ttyS0.service; do
    [[ "$(unit_state "$u")" == enabled ]] && units+=("$u")
  done
  [[ ${#units[@]} -eq 0 ]] || unit_set disable "${units[@]}"
  # cloud-init (Raspberry Pi Imager's first-boot settings) has done its work by now
  if [[ -d /etc/cloud && ! -e /etc/cloud/cloud-init.disabled ]]; then track /etc/cloud/cloud-init.disabled; touch /etc/cloud/cloud-init.disabled; fi
  # journald in RAM, small: no fs sync at boot, less SD wear
  put_str /etc/systemd/journald.conf.d/teslalinux.conf 0644 '[Journal]\nStorage=volatile\nRuntimeMaxUse=16M\n'
  # udev settle is a common 1-3 s sink; nothing here waits for it
  put_str /etc/systemd/system/systemd-udev-settle.service.d/teslalinux.conf 0644 '[Service]\nExecStart=\nExecStart=/bin/true\n'
  unit_set mask systemd-random-seed.service
  # noatime, commit=30 on the root file system
  if grep -qE '^[^#].*[[:space:]]/[[:space:]]+ext4[[:space:]]+defaults,noatime[[:space:]]' /etc/fstab; then
    track /etc/fstab
    sed -i 's|^\([^#][^[:space:]]*[[:space:]]\{1,\}/[[:space:]]\{1,\}ext4[[:space:]]\{1,\}\)defaults,noatime|\1noatime,commit=30|' /etc/fstab
  fi
  # the performance governor (mains-powered; schedutil's ramp costs boot time)
  put_text /etc/systemd/system/teslalinux-cpufreq.service 0644 <<'EOF'
[Unit]
Description=teslalinux: performance governor
DefaultDependencies=no
[Service]
Type=oneshot
ExecStart=/bin/sh -c 'for g in /sys/devices/system/cpu/cpufreq/policy*/scaling_governor; do echo performance > $g; done'
[Install]
WantedBy=sysinit.target
EOF
  systemctl daemon-reload
}

# the boot partition is FAT: no owners or modes to set, the file is rewritten in place
put_boot() { # SRC DEST
  cmp -s "$1" "$2" && return 0
  track "$2"
  cat "$1" > "$2"
  sync "$2" 2>/dev/null || sync
}
step_boot() {
  local bd cfgf cmdf rtc block tmp
  bd=$(boot_dir); cfgf="$bd/config.txt"; cmdf="$bd/cmdline.txt"
  [[ -f "$cfgf" && -f "$cmdf" ]] || die "no $cfgf / $cmdf"
  rtc=$(rtc_detect)
  # the fast-boot and codec settings of host/rpi4/config.txt for the Pi 4, as one marked block at the
  # end (later lines win); no hdmi_* (nothing is ever attached), the RTC overlay only when one answers
  block="# >>> teslalinux (tools/pios/install.sh rewrites this block) >>>
[pi4]
boot_delay=0
disable_splash=1
initial_turbo=60
arm_boost=1"
  if ! grep -qE '^[[:space:]]*dtoverlay=vc4-kms-v3d' "$cfgf"; then block+=$'\ndtoverlay=vc4-kms-v3d'; fi
  block+="
# the VPU's V4L2 M2M encoder (/dev/video11) and Mesa V3D share CMA; 256 MB covers 1088x832
dtoverlay=cma,cma-256
gpu_mem=128
camera_auto_detect=0
display_auto_detect=0
dtparam=audio=off
dtoverlay=disable-bt
dtparam=i2c_arm=on
enable_uart=0"
  if [[ $rtc == yes ]]; then block+=$'\n# DS3231 RTC on I2C-1 (found at 0x68): TLS works before the uplink sets the clock\ndtoverlay=i2c-rtc,ds3231'; fi
  block+=$'\n[all]\n# <<< teslalinux <<<'
  tmp=$(mktemp)
  awk '/^# >>> teslalinux/ { skip = 1; next } /^# <<< teslalinux <<</ { skip = 0; next } !skip' "$cfgf" > "$tmp"
  printf '%s\n' "$block" >> "$tmp"
  put_boot "$tmp" "$cfgf"
  # the kernel command line: only quiet + loglevel=3 (one line; everything else stays)
  local line; line=$(head -1 "$cmdf")
  local new=$line t
  for t in quiet loglevel=3; do [[ " $new " == *" $t "* ]] || new="$new $t"; done
  printf '%s\n' "$new" > "$tmp"
  put_boot "$tmp" "$cmdf"
  rm -f "$tmp"
  # the RTC sets the system clock at boot
  if [[ $rtc == yes ]]; then
    put_str /etc/udev/rules.d/85-teslalinux-hwclock.rules 0644 'KERNEL=="rtc0", RUN+="/usr/sbin/hwclock --hctosys --utc"\n'
    [[ "$(unit_state fake-hwclock.service)" == enabled ]] && unit_set disable fake-hwclock.service
    log "  DS3231 RTC found on i2c-1"
  elif [[ $rtc == unknown ]]; then
    log "  RTC: I2C comes up after the reboot; run the installer again then to detect a DS3231"
  else
    log "  no RTC on i2c-1: the clock is set once an uplink exists (TLS errors until then)"
  fi
}

step_enable() {
  systemctl daemon-reload
  systemctl unmask hostapd >/dev/null 2>&1 || true
  local en=(teslalinux-ap-addr.service hostapd.service dnsmasq.service nftables.service NetworkManager.service
    ModemManager.service nginx.service systemd-timesyncd.service "${UNITS[@]}"
    teslalinux-appupdate.timer teslalinux-appupdate-boot.service teslalinux-appupdate-health.service)
  [[ $TRIM == 1 ]] && en+=(teslalinux-cpufreq.service)
  unit_set enable "${en[@]}"
  if [[ -n "$(tls_base_url)" ]]; then unit_set enable teslalinux-tls-fetch.timer; fi
  # an earlier provisioning's certificate daemon unit; the timer replaces it
  if [[ -f /etc/systemd/system/teslalinux-tls.service ]]; then
    unit_set disable teslalinux-tls.service; track /etc/systemd/system/teslalinux-tls.service; rm -f /etc/systemd/system/teslalinux-tls.service
  fi
  systemctl daemon-reload
}

summary() {
  local host ssid psk sub
  host=$(cfg DEVICE_HOST); ssid=$(cfg AP_SSID); sub=$(cfg AP_SUBNET)
  echo
  log "installed: dist $(manifest_get version)${DIST_RELEASE:+ ($DIST_RELEASE)} in $(readlink -f "$PREFIX")"
  log "hotspot: SSID '$ssid' on $(cfg AP_IFACE), country $(cfg AP_COUNTRY), Pi at $sub.1"
  if [[ -n "${GENERATED_PSK:-}" ]]; then
    log "hotspot password (generated, write it down): $GENERATED_PSK"
  else
    log "hotspot password: unchanged (AP_PSK in $ENVF)"
  fi
  log "car page: https://$host/ (in the car's browser, on the hotspot)"
  log "Waze: ${WAZE_STATUS:-unchanged}"
  local served; served=$(cat /etc/teslalinux/tls/served 2>/dev/null || echo none)
  if [[ "$served" == current || "$served" == standby ]]; then
    log "certificate: the shared $host certificate ($served slot), kept current from $(tls_base_url)"
  else
    log "certificate: self-signed until the Pi fetches the shared one from $(tls_base_url) (first uplink)"
  fi
  log "log: $LOG"
  log "after the reboot: sudo teslalinux doctor"
}

cmd_install() {
  parse_install_args "$@"
  [[ $EUID -eq 0 ]] || die "run as root (sudo)"
  mkdir -p "$LOG_DIR"
  LOG="$LOG_DIR/install-$RUN_TS.log"
  # root only: the summary at the end holds the hotspot password
  install -m 0600 /dev/null "$LOG"
  exec > >(tee -a "$LOG") 2>&1
  log "teslalinux install $RUN_TS ($(model))"
  preflight
  RUN_DIST_BEFORE=$(readlink -f "$PREFIX" 2>/dev/null || true)
  resolve_dist
  run_step dist
  if [[ -n "$PAYLOAD_OVERRIDE" ]]; then PAYLOAD=$(readlink -f "$PAYLOAD_OVERRIDE"); else PAYLOAD="$PREFIX/share/teslalinux"; fi
  [[ -f "$PAYLOAD/tools/pios/install.sh" && -d "$PAYLOAD/host/phase1-pios" ]] || die "no payload in $PAYLOAD (an incomplete dist?)"
  DIST_RELEASE=$(manifest_get release)
  run_step packages
  run_step user
  run_step files
  run_step config
  run_step network
  run_step tls
  run_step web
  run_step audio
  run_step display
  run_step appupdate
  run_step waze
  run_step prewarm
  if [[ $TRIM == 1 ]]; then run_step trim; fi
  run_step boot
  run_step enable
  mkdir -p "$(dirname "$REBOOT_FLAG")"; touch "$REBOOT_FLAG"
  log "static health check"
  doctor_static || true
  summary
  case "$REBOOT" in
    yes) log "rebooting"; systemctl reboot ;;
    no) log "reboot to start the car stack: sudo reboot" ;;
    ask) if ask "Reboot now to start the car stack?"; then log "rebooting"; systemctl reboot; else log "reboot to start the car stack: sudo reboot"; fi ;;
  esac
}

# ================================================================ doctor
DOC_FAIL=0 DOC_WARN=0
ok() { printf '  \033[32mOK\033[0m    %s\n' "$*"; }
bad() { printf '  \033[31mFAIL\033[0m  %s\n' "$*"; DOC_FAIL=$((DOC_FAIL + 1)); }
meh() { printf '  \033[33mWARN\033[0m  %s\n' "$*"; DOC_WARN=$((DOC_WARN + 1)); }
info() { printf '  INFO  %s\n' "$*"; }

doctor_static() {
  local f out
  [[ "$(getconf PAGESIZE)" == 4096 ]] && ok "page size 4096" || bad "page size $(getconf PAGESIZE)"
  if [[ -L "$PREFIX" && -x "$PREFIX/bin/android-translation-layer" ]]; then ok "dist $(manifest_get version) at $(readlink -f "$PREFIX")"
  else bad "no dist at $PREFIX"; fi
  [[ -x "$PREFIX/tacomp/bin/tacomp" ]] && ok "tacomp" || bad "no $PREFIX/tacomp/bin/tacomp"
  for f in "$PREFIX/bin/android-translation-layer" "$PREFIX/bin/dalvikvm" "$PREFIX/tacomp/bin/tacomp" "$PREFIX"/lib/art/*.so "$PREFIX"/lib/java/dex/*/natives/*.so; do
    [[ -f "$f" ]] || continue
    out=$(ldd "$f" 2>/dev/null | grep 'not found' || true)
    [[ -z "$out" ]] || bad "$(basename "$f") misses libraries: $(echo "$out" | awk '{ print $1 }' | tr '\n' ' ')"
  done
  [[ -e /dev/dri/renderD128 ]] && ok "GPU render node /dev/dri/renderD128" || meh "no /dev/dri/renderD128 (vc4-kms-v3d; after the reboot?)"
  [[ -e /dev/video11 ]] && ok "V4L2 M2M encoder /dev/video11" || meh "no /dev/video11 (bcm2835-codec; tacomp falls back to CPU JPEG)"
  if nginx -t >/dev/null 2>&1; then ok "nginx -t"; else bad "nginx -t: $(nginx -t 2>&1 | tail -1)"; fi
  python3 -c 'import websockets' 2>/dev/null && ok "python3-websockets" || bad "python3 cannot import websockets"
  if [[ -f "$ENVF" ]]; then
    [[ "$(stat -c '%a %U:%G' "$ENVF")" == "640 root:tesla" ]] && ok "$ENVF mode 0640 root:tesla" || meh "$ENVF is $(stat -c '%a %U:%G' "$ENVF") (0640 root:tesla expected)"
    [[ "$(cfg AP_PSK)" != changeit ]] && ok "hotspot password set" || bad "AP_PSK is the placeholder 'changeit'"
  else bad "no $ENVF"; fi
  local u st
  for u in "${UNITS[@]}" hostapd.service dnsmasq.service nftables.service nginx.service teslalinux-ap-addr.service teslalinux-appupdate.timer; do
    st=$(unit_state "$u")
    [[ "$st" == enabled ]] || bad "$u is ${st:-missing}, not enabled"
  done
  ok "units checked"
  if [[ -f "$APPS/com.waze/base.apk" ]]; then ok "Waze $(sed -n 's/.*"versionName":"\([^"]*\)".*/\1/p' "$APPS/com.waze/installed.json" 2>/dev/null) installed"
  elif [[ -f "$STATE_DIR/appupdate/pending/base.apk" ]]; then ok "Waze staged; installs at the next start"
  else meh "Waze is not installed (car page upload, --apk or --waze apkpure)"; fi
  if ls "$TESLA_HOME"/.cache/art/*/*boot*.art >/dev/null 2>&1; then ok "ART boot image"; else info "no ART boot image yet (the first app start builds it)"; fi
  grep -q '^# >>> teslalinux' "$(boot_dir)/config.txt" && ok "boot config block" || meh "no teslalinux block in $(boot_dir)/config.txt"
}

doctor_live() {
  local u failed
  failed=$(systemctl --failed --no-legend --plain 2>/dev/null | awk '{ print $1 }' | tr '\n' ' ')
  [[ -z "${failed// }" ]] && ok "no failed units" || bad "failed units: $failed"
  for u in teslalinux-ap-addr hostapd dnsmasq nftables nginx tacomp teslalinux-api teslalinux-gps teslalinux-audio teslalinux-pipewire teslalinux-wireplumber teslalinux-dbus; do
    [[ "$(systemctl is-active "$u" 2>/dev/null)" == active ]] || bad "$u is $(systemctl is-active "$u" 2>/dev/null)"
  done
  ok "core units checked"
  local ap; ap=$(cfg AP_IFACE)
  local hs; hs=$(hostapd_cli -i "$ap" status 2>/dev/null | sed -n 's/^state=//p')
  [[ "$hs" == ENABLED ]] && ok "hotspot on $ap ($(hostapd_cli -i "$ap" status 2>/dev/null | sed -n 's/^ssid\[0\]=//p'))" || bad "hostapd on $ap: ${hs:-no answer}"
  info "network: $(nmcli -t -f DEVICE,TYPE,STATE device 2>/dev/null | grep -v ':unmanaged$' | tr '\n' ' ')"
  info "certificate: $(/usr/local/bin/teslalinux-tls status 2>/dev/null | tr -d '\n' | cut -c1-200)"
  info "app updates: $(/usr/local/bin/teslalinux-appupdate status 2>/dev/null | python3 -c 'import json,sys; d=json.load(sys.stdin); s=d.get("state",{}); print(d.get("source"), s.get("lastResult"), "-", s.get("lastMessage"))' 2>/dev/null || echo '?')"
  local co; co=$(mktemp)
  if python3 "$LIB/check/check.py" --addr 127.0.0.1 --https-port 443 --http-port 80 --env "$ENVF" --expect-display >"$co" 2>&1; then
    ok "web stack (check.py)"
  else
    bad "web stack (check.py): $(grep -iE 'fail|error' "$co" | head -3 | tr '\n' ' ')"
  fi
  rm -f "$co"
  local fr; fr=$(mktemp -d)
  if timeout 30 python3 "$LIB/check/ws-client.py" --out "$fr" --frames 1 --wait 3 >/dev/null 2>&1 && [[ -n "$(ls "$fr" 2>/dev/null)" ]]; then
    ok "a frame from tacomp ($(ls "$fr" | head -1))"
  else
    bad "no frame from tacomp's display socket"
  fi
  rm -rf "$fr"
  if [[ -f "$APPS/com.waze/base.apk" ]]; then
    local st mark
    st=$(systemctl is-active teslalinux-atl 2>/dev/null || true)
    mark=$(journalctl -b -u teslalinux-atl -o cat --no-pager -q -g MainScreenVisible 2>/dev/null | tail -1 || true)
    if [[ -n "$mark" ]]; then
      ok "Waze reached its main screen this boot"
    elif [[ "$st" == active ]]; then
      info "Waze is running (restarts: $(systemctl show -p NRestarts --value teslalinux-atl)); its main-screen marker is only logged at debug level"
    else
      bad "teslalinux-atl is $st"
    fi
  fi
}

cmd_doctor() {
  local static=0
  [[ "${1:-}" == --static ]] && static=1
  [[ $EUID -eq 0 ]] || die "run as root (sudo teslalinux doctor)"
  log "doctor: $(model)"
  doctor_static
  if [[ $static == 1 ]]; then :
  elif [[ -e "$REBOOT_FLAG" ]]; then info "installed but not rebooted yet: the running checks follow the reboot"
  else doctor_live; fi
  log "doctor: $DOC_FAIL failed, $DOC_WARN warnings"
  [[ $DOC_FAIL == 0 ]]
}

# ================================================================ status / rollback / uninstall
cmd_status() {
  echo "dist:      $(readlink -f "$PREFIX" 2>/dev/null || echo none) ($(manifest_get version))"
  echo "dists:     $(ls -1 "$DISTS" 2>/dev/null | tr '\n' ' ')"
  echo "backups:   $(ls -1 "$BACKUP_ROOT" 2>/dev/null | tr '\n' ' ')"
  echo "steps:"
  [[ -f "$STATE" ]] && sed 's/^/  /' "$STATE" || echo "  (none)"
}

# restore one backup set: replaced files back, created files removed, units to their recorded state
restore_set() { # DIR [first-only-map-name]
  local set=$1 kind p st
  while IFS=$'\t' read -r kind p st; do
    case "$kind" in
      replaced)
        [[ -n "${DONE_PATH[$p]:-}" ]] && continue; DONE_PATH[$p]=1
        mkdir -p "$(dirname "$p")"
        if [[ -L "$set/files$p" || -L "$p" ]]; then rm -f "$p"; cp -a "$set/files$p" "$p"
        # in place (the boot partition is FAT: no owners to restore there)
        else cp -a "$set/files$p" "$p" 2>/dev/null || cp "$set/files$p" "$p"; fi ;;
      created)
        [[ -n "${DONE_PATH[$p]:-}" ]] && continue; DONE_PATH[$p]=1
        rm -f "$p" ;;
      unit)
        [[ -n "${DONE_UNIT[$p]:-}" ]] && continue; DONE_UNIT[$p]=1
        case "$st" in
          masked) systemctl mask "$p" >/dev/null 2>&1 || true ;;
          enabled) systemctl unmask "$p" >/dev/null 2>&1; systemctl enable "$p" >/dev/null 2>&1 || true ;;
          disabled|indirect) systemctl unmask "$p" >/dev/null 2>&1; systemctl disable "$p" >/dev/null 2>&1 || true ;;
          *) systemctl unmask "$p" >/dev/null 2>&1; systemctl disable "$p" >/dev/null 2>&1 || true ;;
        esac ;;
    esac
  done < <(grep -v '^#' "$set/MANIFEST")
}

stop_stack() {
  systemctl stop teslalinux-appupdate.timer teslalinux-appupdate.service 2>/dev/null || true
  systemctl stop "${APP_UNITS[@]}" 2>/dev/null || true
}

cmd_rollback() {
  YES=0
  [[ "${1:-}" == --yes || "${1:-}" == -y ]] && YES=1
  [[ $EUID -eq 0 ]] || die "run as root"
  local set; set=$(ls -1d "$BACKUP_ROOT"/2* 2>/dev/null | tail -1)
  [[ -n "$set" ]] || die "no backup set to roll back to"
  local before; before=$(cat "$set/dist.before" 2>/dev/null || true)
  log "roll back the install run of $(basename "$set"): its file changes, and the dist to ${before:-(unchanged)}"
  if [[ -z "$before" ]]; then
    warn "that run was the first install: rolling it back is the same as uninstall"
    die "use: sudo teslalinux uninstall"
  fi
  [[ -d "$before" ]] || die "the previous dist $before is gone"
  ask "Roll back?" || die "stopped"
  mkdir -p "$LOG_DIR"; LOG="$LOG_DIR/rollback-$RUN_TS.log"; exec > >(tee -a "$LOG") 2>&1
  stop_stack
  declare -gA DONE_PATH=() DONE_UNIT=()
  restore_set "$set"
  ln -sfn "$before" "$PREFIX.new"; mv -T "$PREFIX.new" "$PREFIX"
  ldconfig
  rm -rf "$TESLA_HOME/.cache/art"
  mv "$set" "$BACKUP_ROOT/rolled-back-$(basename "$set")"
  # every step runs again on the next install
  rm -f "$STATE"
  systemctl daemon-reload
  log "rolled back to $(readlink -f "$PREFIX"); reboot to restart the stack (sudo reboot)"
}

cmd_uninstall() {
  local keep=0
  YES=0
  while [[ $# -gt 0 ]]; do
    case "$1" in --keep-data) keep=1 ;; --yes|-y) YES=1 ;; *) die "unknown option $1" ;; esac; shift
  done
  [[ $EUID -eq 0 ]] || die "run as root"
  if [[ $keep == 1 ]]; then
    log "uninstall: system files and units back to before the first install; the app, its data, the settings and the certificates stay"
  else
    log "uninstall: everything, including the app, its data, the settings, the certificates and the tesla user"
  fi
  ask "Uninstall teslalinux?" || die "stopped"
  local pk; pk=$(manifest_get packages)
  stop_stack
  systemctl stop teslalinux-tls-fetch.timer hostapd dnsmasq 2>/dev/null || true
  # every backup set, oldest first: the first record of a path or unit is its state before teslalinux
  declare -gA DONE_PATH=() DONE_UNIT=()
  local set d b sets=()
  # chronological, rolled-back sets at their own time
  mapfile -t sets < <(for d in "$BACKUP_ROOT"/2* "$BACKUP_ROOT"/rolled-back-*; do
    [[ -d "$d" ]] || continue; b=$(basename "$d"); printf '%s %s\n' "${b#rolled-back-}" "$d"; done | sort | awk '{ print $2 }')
  for set in "${sets[@]}"; do
    log "  restoring from $(basename "$set")"
    restore_set "$set"
  done
  # what is teslalinux's as a whole
  rm -rf "${OWNED_DIRS[@]}" "$SELF" "$PREFIX" "$DISTS" /etc/ld.so.conf.d/teslalinux.conf /system/fonts
  rmdir /system 2>/dev/null || true
  ldconfig
  if [[ $keep == 0 ]]; then
    rm -rf /etc/teslalinux "$STATE_DIR" "$LOG_DIR" "$TESLA_HOME"
    userdel tesla 2>/dev/null || true
  else
    rm -rf "$BACKUP_ROOT" "$STATE"
  fi
  systemctl daemon-reload
  log "done. The packages it installed stay (they may be used by other things); to remove them:"
  log "  sudo apt-get remove ${RUNTIME_PKGS[*]} $pk   (check the list first)"
  log "reboot to leave the hotspot configuration behind: sudo reboot"
}

main() {
  # this script may arrive through a pipe (curl | bash): nothing below may read the rest of it
  exec 0</dev/null
  local cmd=install
  # the installed copy (/usr/local/sbin/teslalinux) without arguments only explains itself
  if [[ $# -eq 0 && "$(basename "$0")" == teslalinux ]]; then usage; exit 0; fi
  case "${1:-}" in
    install|doctor|status|rollback|uninstall) cmd=$1; shift ;;
    -h|--help|help) usage; exit 0 ;;
  esac
  case "$cmd" in
    install) cmd_install "$@" ;;
    doctor) cmd_doctor "$@" ;;
    status) cmd_status ;;
    rollback) cmd_rollback "$@" ;;
    uninstall) cmd_uninstall "$@" ;;
  esac
}

main "$@"
