#!/usr/bin/env bash
# teslalinux: pack the native Pi OS runtime that build-dist.sh built into /opt/teslalinux as the dist
# tarball tools/pios/install.sh installs on the Pi (Raspberry Pi OS / Debian trixie arm64).
#
#   pack-dist.sh [OPTIONS] [OUTDIR]        (default OUTDIR: ~/dist-out)
#     --prefix DIR     the built prefix (default /opt/teslalinux)
#     --repo DIR       the repo checkout the payload comes from (default: the checkout this script is
#                      in; in the VM, where it is not in one, a copy of the repo must be given)
#     --version VER    the dist version (default <YYYYMMDD>-<first 8 of ATL_COMMIT>)
#     --release TAG    the release tag this dist is published under (recorded in MANIFEST)
#     --release-repo OWNER/REPO  the GitHub repository whose releases install.sh downloads from
#                      (its TL_RELEASE_REPO default, in the payload copy and the release asset)
#     --versions FILE  build-dist.sh's pins (default: versions.env next to this script, else ~/versions.env)
#     --no-strip       keep the debug info (default: strip --strip-debug, symbol tables stay)
#     --cert-url URL   where the shared car certificate is published (default: $TLS_BASE_URL, else
#                      https://certs.alexahub.dev/car); '' = bundle none. Both slots' pairs and
#                      active.json are bundled (share/teslalinux/tls/) when they pass teslalinux-tls
#                      verify, so a fresh install serves a trusted certificate before its first
#                      uplink; a failed download only warns (the Pi fetches them itself)
#
# Output in OUTDIR:
#   teslalinux-dist-<ver>-arm64.tar.zst   (.tar.xz when zstd is missing) one top directory
#                                          teslalinux-dist-<ver>/ = the runtime part of the prefix
#                                          + MANIFEST + share/teslalinux/ (the payload: the repo's host
#                                          configs, units, services, web frontend, app tools, installer)
#   MANIFEST     the same file as inside: version, build facts, every pin of versions.env, and
#                packages= (the Debian runtime packages the prefix's ELF files need: ldd of each one,
#                mapped through dpkg -S, minus what the prefix provides itself)
#   SHA256SUMS   of the tarball, MANIFEST and install.sh
#   install.sh   tools/pios/install.sh (the release's one-line installer)
# Left out of the prefix: headers, static and libtool archives, pkg-config/cmake files, docs,
# build-only tools (dx, wolfssl-config, wayland-scanner) and the Java class jars javac/dx use.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
log() { printf '\033[1;32m[pack-dist]\033[0m %s\n' "$*"; }
die() { log "ERROR: $*"; exit 1; }

PREFIX=/opt/teslalinux
REPO=""
VERSION=""
RELEASE=""
RELEASE_REPO=""
VERSIONS=""
STRIP=1
OUT=""
CERT_URL=${TLS_BASE_URL-https://certs.alexahub.dev/car}
while [[ $# -gt 0 ]]; do
  case "$1" in
    --prefix) PREFIX=${2:?}; shift ;;
    --repo) REPO=${2:?}; shift ;;
    --version) VERSION=${2:?}; shift ;;
    --release) RELEASE=${2-}; shift ;;
    --release-repo) RELEASE_REPO=${2:?}; shift ;;
    --versions) VERSIONS=${2:?}; shift ;;
    --no-strip) STRIP=0 ;;
    --cert-url) CERT_URL=${2-}; shift ;;
    -h|--help) awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"; exit 0 ;;
    -*) die "unknown option $1" ;;
    *) OUT=$1 ;;
  esac
  shift
done
OUT="${OUT:-$HOME/dist-out}"
if [[ -z "$REPO" && -f "$HERE/../../tools/pios/install.sh" ]]; then REPO="$(cd "$HERE/../.." && pwd)"; fi
[[ -n "$REPO" && -f "$REPO/tools/pios/install.sh" ]] || die "no repo checkout for the payload (--repo DIR)"
if [[ -z "$VERSIONS" ]]; then
  if [[ -f "$HERE/versions.env" ]]; then VERSIONS="$HERE/versions.env"; else VERSIONS="$HOME/versions.env"; fi
fi
[[ -f "$VERSIONS" ]] || die "no versions.env ($VERSIONS)"
# shellcheck source=versions.env
. "$VERSIONS"
[[ -x "$PREFIX/bin/android-translation-layer" ]] || die "$PREFIX has no ATL (tools/debvm/build-dist.sh)"
[[ -x "$PREFIX/tacomp/bin/tacomp" ]] || die "$PREFIX has no tacomp (build-dist.sh tacomp)"
for t in ldd dpkg strip sha256sum tar; do command -v "$t" >/dev/null || die "missing tool: $t"; done
VERSION="${VERSION:-$(date -u +%Y%m%d)-${ATL_COMMIT:0:8}}"
[[ "$VERSION" =~ ^[A-Za-z0-9._+-]+$ ]] || die "bad version string '$VERSION'"
ARCH=$(dpkg --print-architecture)
TOP="teslalinux-dist-$VERSION"
if command -v zstd >/dev/null; then EXT=tar.zst; else EXT=tar.xz; fi
TARBALL="$TOP-$ARCH.$EXT"

mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)"
STAGE="$OUT/.stage-$$"
trap 'rm -rf "$STAGE"' EXIT
D="$STAGE/$TOP"
mkdir -p "$D"

# ---------------------------------------------------------------- 1. the runtime part of the prefix
log "1/5 prefix $PREFIX -> $TOP/"
EXCLUDES=(
  --exclude=/include --exclude=/tacomp/include --exclude='*.a' --exclude='*.la' --exclude='*.orig'
  --exclude=pkgconfig --exclude=/lib/cmake --exclude=/share/doc --exclude=/share/man
  --exclude=/share/aclocal --exclude=/tacomp/share
  --exclude=/bin/dx --exclude=/bin/wolfssl-config --exclude=/tacomp/bin/wayland-scanner
  # class jars for javac/dx (the runtime reads lib/java/dex/**)
  --exclude='/lib/java/*.jar'
)
rsync -a "${EXCLUDES[@]}" "$PREFIX/" "$D/"

is_elf() { [[ -f "$1" && ! -L "$1" && "$(head -c 4 "$1" | od -An -tx1 | tr -d ' \n')" == 7f454c46 ]]; }
mapfile -t ELFS < <(find "$D" -type f | sort | while read -r f; do is_elf "$f" && printf '%s\n' "$f"; done)
log "  ${#ELFS[@]} ELF files"
if [[ $STRIP == 1 ]]; then
  # tacomp's RUNPATH was set by patchelf, which strip can corrupt; it is small anyway
  for f in "${ELFS[@]}"; do
    [[ "$f" == "$D/tacomp/bin/tacomp" ]] && continue
    chmod u+w "$f"; strip --strip-debug "$f"
  done
fi

# ---------------------------------------------------------------- 2. the payload (repo files the installer uses)
log "2/5 payload from $REPO -> $TOP/share/teslalinux/"
PAYLOAD=(
  host/phase1-pios host/rpi4 host/tls/teslalinux-tls host/local/check.py
  host/teslalinux-render host/teslalinux-restart-app host/teslalinux-set-locale host/teslalinux-set-keyboard
  host/teslalinux-network host/teslalinux-system-update host/teslalinux-uplink host/teslalinux-dexopt
  services/gps/gps.py services/audio/audio.py services/api/api.py services/api/tlprops.py
  services/display/test/ws-client.py
  web
  tools/apps/install-app.sh tools/apps/apk-info.py tools/apps/apk_verify.py
  tools/appupdate/install.sh tools/appupdate/teslalinux-appupdate tools/appupdate/device.properties
  tools/pios/install.sh tools/atl-vm/mk-android-tzdata.py docs/APP-UPDATES.md
)
[[ -f "$REPO/LICENSE" ]] && PAYLOAD+=(LICENSE)
for p in "${PAYLOAD[@]}"; do [[ -e "$REPO/$p" ]] || die "payload path missing in the repo: $p"; done
mkdir -p "$D/share/teslalinux"
tar -C "$REPO" --exclude=__pycache__ --exclude='*.pyc' --exclude=.DS_Store -cf - "${PAYLOAD[@]}" | tar -C "$D/share/teslalinux" -xf -
if [[ -n "$RELEASE_REPO" ]]; then
  [[ "$RELEASE_REPO" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]] || die "--release-repo OWNER/REPO"
  sed -i "s|^TL_RELEASE_REPO=\"\${TL_RELEASE_REPO:-[^}]*}\"|TL_RELEASE_REPO=\"\${TL_RELEASE_REPO:-$RELEASE_REPO}\"|" "$D/share/teslalinux/tools/pios/install.sh"
  grep -qxF "TL_RELEASE_REPO=\"\${TL_RELEASE_REPO:-$RELEASE_REPO}\"" "$D/share/teslalinux/tools/pios/install.sh" || die "could not set the release repository in install.sh"
fi
# the shared car certificate as published now (.github/workflows/tls-certs.yml): checked with the
# Pi's own acceptance checks; anything missing or failing is left out with a warning
CERT_BUNDLE=""
bundle_certs() {
  local dst="$D/share/teslalinux/tls" t s v host
  [[ -n "$CERT_URL" ]] || { log "  certificate bundle: off"; return 0; }
  [[ "$CERT_URL" == https://* ]] || { log "  WARNING: --cert-url must be https; no certificate bundle"; return 0; }
  for t in curl openssl; do
    command -v "$t" >/dev/null || { log "  WARNING: no $t here: no certificate bundle (the Pi fetches it on its first uplink)"; return 0; }
  done
  host=$(env -i sh -c '. "$1" >/dev/null 2>&1; printf %s "${DEVICE_HOST:-}"' sh "$REPO/host/phase1-pios/env")
  t=$(mktemp -d)
  for s in current standby; do
    if ! curl -fsS --proto '=https' --max-time 60 --max-filesize 65536 -o "$t/$s.chain" "$CERT_URL/$s/fullchain.pem" \
       || ! curl -fsS --proto '=https' --max-time 60 --max-filesize 65536 -o "$t/$s.key" "$CERT_URL/$s/privkey.pem"; then
      log "  WARNING: could not download the $s pair from $CERT_URL; not bundled"
      continue
    fi
    if v=$(TESLALINUX_ENV=/nonexistent DEVICE_HOST="$host" TLS_SLOTS_DIR=/nonexistent TLS_BUILD_TIME_FILE=/nonexistent \
           TLS_STATE=/nonexistent/tls.json TLS_BASE_URL="" sh "$REPO/host/tls/teslalinux-tls" verify "$s" "$t/$s.chain" "$t/$s.key" 2>/dev/null); then
      install -d -m 0700 "$dst/$s"
      install -m 0644 "$t/$s.chain" "$dst/$s/fullchain.pem"
      install -m 0600 "$t/$s.key" "$dst/$s/privkey.pem"
      CERT_BUNDLE="$CERT_BUNDLE $s:$(openssl x509 -in "$t/$s.chain" -noout -enddate | cut -d= -f2- | tr ' ' '_')"
      log "  certificate bundle: $s for $host ($(openssl x509 -in "$t/$s.chain" -noout -enddate))"
    else
      log "  WARNING: the published $s pair fails the checks, not bundled: $v"
    fi
  done
  if [[ -n "$CERT_BUNDLE" ]] && curl -fsS --proto '=https' --max-time 60 --max-filesize 4096 -o "$t/active.json" "$CERT_URL/active.json"; then
    install -m 0644 "$t/active.json" "$dst/active.json"
  fi
  rm -rf "$t"
  CERT_BUNDLE=${CERT_BUNDLE# }
}
bundle_certs
REPO_COMMIT=""
if command -v git >/dev/null && git -C "$REPO" rev-parse --git-dir >/dev/null 2>&1; then
  REPO_COMMIT=$(git -C "$REPO" rev-parse HEAD)
  git -C "$REPO" diff --quiet HEAD -- "${PAYLOAD[@]}" 2>/dev/null || REPO_COMMIT="$REPO_COMMIT-dirty"
fi

# ---------------------------------------------------------------- 3. the runtime packages (ldd -> dpkg -S)
log "3/5 runtime packages of the ELF files"
declare -A SYSLIB=() UNRES=()
for f in "${ELFS[@]}"; do
  # the copy in $PREFIX: its dependencies resolve exactly as at run time (ld.so.conf, RUNPATH)
  rel="${f#"$D"}"
  src="$PREFIX$rel"
  while read -r line; do
    case "$line" in
      *"=> not found"*) UNRES["${line%% *}"]=1 ;;
      *"=> /"*) p="${line#*=> }"; SYSLIB["${p%% (*}"]=1 ;;
      /*) SYSLIB["${line%% (*}"]=1 ;;
    esac
  done < <(ldd "$src" 2>/dev/null || true)
done
# drop what the prefix provides itself (resolved into it, or a missing name that exists in it)
REAL_PREFIX="$(readlink -f "$PREFIX")"
for p in "${!SYSLIB[@]}"; do
  case "$(readlink -f "$p")/" in "$REAL_PREFIX"/*) unset 'SYSLIB[$p]' ;; esac
  case "$p" in "$PREFIX"/*) unset 'SYSLIB[$p]' ;; esac
done
for n in "${!UNRES[@]}"; do
  [[ -n "$(find "$D" -name "$n" -print -quit)" ]] && unset 'UNRES[$n]'
done
# dpkg's database names a file by the path its package ships; with the merged /usr a library may be
# reached as /lib/... while shipped as /usr/lib/... (or the other way), and through a symlink
alt() { case "$1" in /usr/*) printf '%s\n' "${1#/usr}" ;; /*) printf '/usr%s\n' "$1" ;; esac; }
CANDS=()
for p in "${!SYSLIB[@]}"; do
  r="$(readlink -f "$p")"
  CANDS+=("$p" "$(alt "$p")" "$r" "$(alt "$r")")
done
declare -A OWNER=()
if [[ ${#CANDS[@]} -gt 0 ]]; then
  while IFS= read -r line; do
    [[ "$line" == *": /"* && "$line" != diversion* ]] || continue
    pkgs="${line%%: /*}"; path="/${line#*: /}"
    pkg="${pkgs%%,*}"; pkg="${pkg%%:*}"
    OWNER["$path"]="$pkg"
  done < <(printf '%s\n' "${CANDS[@]}" | sort -u | xargs dpkg -S 2>/dev/null || true)
fi
declare -A PKGS=()
NOPKG=()
for p in "${!SYSLIB[@]}"; do
  r="$(readlink -f "$p")" hit=""
  for c in "$p" "$(alt "$p")" "$r" "$(alt "$r")"; do
    if [[ -n "${OWNER[$c]:-}" ]]; then hit="${OWNER[$c]}"; break; fi
  done
  if [[ -n "$hit" ]]; then PKGS["$hit"]=1; else NOPKG+=("$p"); fi
done
PKGLIST="$(printf '%s\n' "${!PKGS[@]}" | sort | tr '\n' ' ' | sed 's/ $//')"
UNRESLIST="$(printf '%s\n' "${!UNRES[@]}" "${NOPKG[@]}" | sed '/^$/d' | sort | tr '\n' ' ' | sed 's/ $//')"
log "  ${#SYSLIB[@]} system libraries from ${#PKGS[@]} packages"
[[ -z "$UNRESLIST" ]] || log "  WARNING: not resolved to a package: $UNRESLIST"

# ---------------------------------------------------------------- 4. MANIFEST
log "4/5 MANIFEST"
M="$D/MANIFEST"
{
  echo "# teslalinux dist (tools/debvm/pack-dist.sh): what tools/pios/install.sh reads"
  echo "format=1"
  echo "version=$VERSION"
  echo "release=$RELEASE"
  echo "arch=$ARCH"
  echo "prefix=$PREFIX"
  echo "tarball=$TARBALL"
  echo "built=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "build_os=$( . /etc/os-release && echo "$PRETTY_NAME")"
  echo "build_cc=$(cc --version 2>/dev/null | head -1)"
  echo "build_cpu_cflags=${TL_CPU_CFLAGS:-}"
  echo "build_machine=$(uname -m)"
  echo "repo_commit=$REPO_COMMIT"
  echo "stripped=$STRIP"
  echo "cert_url=$CERT_URL"
  echo "cert_bundle=$CERT_BUNDLE"
  # every pin of versions.env, as build-dist.sh used them
  grep -oE '^[A-Z][A-Z0-9_]*=' "$VERSIONS" | tr -d '=' | sort -u | while read -r k; do
    printf 'pin.%s=%s\n' "$k" "${!k-}"
  done
  echo "packages=$PKGLIST"
  echo "unresolved=$UNRESLIST"
} > "$M"
cp "$M" "$OUT/MANIFEST"

# ---------------------------------------------------------------- 5. tarball + sums
log "5/5 $TARBALL"
rm -f "$OUT/$TARBALL"
if [[ $EXT == tar.zst ]]; then
  tar -C "$STAGE" --sort=name --owner=0 --group=0 --numeric-owner -cf - "$TOP" | zstd -q -19 -T0 -o "$OUT/$TARBALL"
else
  tar -C "$STAGE" --sort=name --owner=0 --group=0 --numeric-owner -cf - "$TOP" | xz -T0 -6 > "$OUT/$TARBALL"
fi
install -m 0755 "$D/share/teslalinux/tools/pios/install.sh" "$OUT/install.sh"
(cd "$OUT" && sha256sum "$TARBALL" MANIFEST install.sh > SHA256SUMS)
log "done: $OUT/$TARBALL ($(du -h "$OUT/$TARBALL" | cut -f1); unpacked $(du -sh "$D" | cut -f1)), ${#PKGS[@]} packages"
