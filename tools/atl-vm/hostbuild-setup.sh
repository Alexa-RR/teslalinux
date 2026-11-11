#!/usr/bin/env bash
# teslalinux: (re)create hostbuild.sh's work dir inputs from the running ATL guest (through vm.sh, so
# it works on the Mac, under Docker on Windows, and with ATL_VM_REMOTE).
#
#   hostbuild-setup.sh [all|src|jars]     default all
#
# Work dir: $ATL_WORK, default <repo>/out/atl-work. It gets:
#   atl-guest-src.tgz   tar of the guest's ~/atl-src/src/api-impl with entries src/api-impl/..., the
#                       layout hostbuild.sh --reset extracts into atl-guest/ (atl-guest/ is deleted
#                       here, so the next hostbuild.sh re-extracts it)
#   atl-host/jars/core-all_classes.jar, core-junit_classes.jar, junit-runner_classes.jar
#                       ART libcore class jars, javac's -bootclasspath. ATL's meson.build takes them
#                       from art-standalone's pkg-config libdir + /java; looked up there first, then
#                       by name under the guest's ATL build dirs, prefix and /usr.
#   atl-host/jars/r8.jar  D8. Taken from the guest if it has one, otherwise the pinned R8 release
#                       below from Google Maven, checked against the pinned digest.
#   atl-host/jars/dx.jar  dx, used only by patch-core-oj.sh (dexes the libcore additions with
#                       --core-library). Optional: a warning if the guest has none.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=hostenv.sh
. "$HERE/hostenv.sh"
log() { printf '\033[1;32m[hostbuild-setup]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[hostbuild-setup]\033[0m %s\n' "$*" >&2; }
vm() { bash "$HERE/vm.sh" "$@"; }
# pull_to GUEST_PATH LOCAL: copy through a .part file so a failed pull never leaves a truncated input
pull_to() { rm -f "$2.part"; vm pull "$1" "$2.part" && [[ -s "$2.part" ]] || hostenv_die "could not pull guest:$1"; mv -f "$2.part" "$2"; }

# R8 from Google Maven: com.android.tools:r8 (the published jar is self-contained, D8 included).
# R8 9.x needs Java 17+ to run (Homebrew openjdk@17 on the Mac).
R8_VERSION=9.5.29
R8_URL="https://dl.google.com/android/maven2/com/android/tools/r8/$R8_VERSION/r8-$R8_VERSION.jar"
# SHA-1 as published next to the jar on Google Maven (r8-9.5.29.jar.sha1), pinned here on 2026-11-09.
R8_SHA1=0de8ece61264f7c5021b80375237f3c3904693e9
# SHA-256 of the same file (not published by Google Maven): computed from the first download on
# 2026-11-09, whose SHA-1 matched the pin above. When set, it is the check; empty falls back to SHA-1.
R8_SHA256=f8c762ff84640b10083b934a47278758a7665977e618fab0c927fff00521dea3

WHAT="${1:-all}"
case "$WHAT" in all|src|jars) ;; *) hostenv_die "usage: hostbuild-setup.sh [all|src|jars]" ;; esac

digest() {  # digest sha1|sha256 FILE
  "$PY" -I -c 'import hashlib,sys; h=hashlib.new(sys.argv[1]); f=open(sys.argv[2],"rb")
for b in iter(lambda: f.read(1<<20), b""): h.update(b)
print(h.hexdigest())' "$1" "$(topath "$2")"
}

vm ssh true >/dev/null 2>&1 || hostenv_die "the guest does not answer 'vm.sh ssh true'; start it first (vm.sh start && vm.sh wait)"
mkdir -p "$JARS"

# 1. the api-impl source tree
if [[ "$WHAT" == all || "$WHAT" == src ]]; then
  vm ssh 'test -d ~/atl-src/src/api-impl' || hostenv_die "the guest has no ~/atl-src/src/api-impl (run ~/build-atl.sh there first)"
  log "packing the guest's ~/atl-src/src/api-impl"
  vm ssh 'rm -f /tmp/atl-guest-src.tgz && tar -czf /tmp/atl-guest-src.tgz -C ~/atl-src src/api-impl && ls -la /tmp/atl-guest-src.tgz' | sed 's/^/  /'
  rm -f "$WORK/atl-guest-src.tgz.part"
  vm pull /tmp/atl-guest-src.tgz "$WORK/atl-guest-src.tgz.part" || hostenv_die "could not pull guest:/tmp/atl-guest-src.tgz"
  vm ssh 'rm -f /tmp/atl-guest-src.tgz' || true
  tar -tzf "$WORK/atl-guest-src.tgz.part" | grep -q '^src/api-impl/android/content/Context.java$' \
    || hostenv_die "pulled archive lacks src/api-impl/android/content/Context.java ($WORK/atl-guest-src.tgz.part)"
  mv -f "$WORK/atl-guest-src.tgz.part" "$WORK/atl-guest-src.tgz"
  rm -rf "$WORK/atl-guest"
  log "atl-guest-src.tgz updated; the next hostbuild.sh extracts it (implicit --reset)"
fi

[[ "$WHAT" == all || "$WHAT" == jars ]] || exit 0

# 2. locate the jars in the guest: prints NAME=PATH (empty PATH when not found) for each name
# shellcheck disable=SC2016  # expanded by the guest shell
LOCATE='
lib=$(pkg-config --variable=libdir art-standalone 2>/dev/null || true)
dxbin=$(command -v dx 2>/dev/null || true)
dxjar=""
if [ -n "$dxbin" ] && [ -f "$dxbin" ]; then
  dxjar=$(grep -o "[^ \"=:]*dx[^ \"/]*\.jar" "$dxbin" 2>/dev/null | head -1 || true)
  case "$dxjar" in /*) [ -f "$dxjar" ] || dxjar="" ;; *) dxjar="" ;; esac
fi
dirs=""
for d in "$HOME"/atl-src/build* "$HOME"/atl-prefix /usr/lib /usr/share /usr/local /opt; do [ -d "$d" ] && dirs="$dirs $d"; done
for n in core-all_classes.jar core-junit_classes.jar junit-runner_classes.jar r8.jar dx.jar; do
  p=""
  if [ -n "$lib" ] && [ -f "$lib/java/$n" ]; then p="$lib/java/$n"; fi
  if [ -z "$p" ] && [ "$n" = dx.jar ]; then p="$dxjar"; fi
  if [ -z "$p" ]; then
    case "$n" in
      r8.jar) p=$(find $dirs -type f \( -name r8.jar -o -name "r8-[0-9]*.jar" -o -name r8lib.jar -o -name d8.jar \) 2>/dev/null | head -1) ;;
      *) p=$(find $dirs -type f -name "$n" 2>/dev/null | head -1) ;;
    esac
  fi
  echo "$n=$p"
done'
log "locating the class jars in the guest"
FOUND="$(vm ssh "$LOCATE" | tr -d '\r')"
gpath() { printf '%s\n' "$FOUND" | sed -n "s|^$1=||p" | head -1; }

missing=""
for n in core-all_classes.jar core-junit_classes.jar junit-runner_classes.jar; do
  p="$(gpath "$n")"
  if [[ -z "$p" ]]; then missing="$missing $n"; continue; fi
  log "  $n <- guest:$p"
  pull_to "$p" "$JARS/$n"
done
[[ -z "$missing" ]] || hostenv_die "not found in the guest:$missing (looked in pkg-config art-standalone libdir/java, ~/atl-src/build*, ~/atl-prefix, /usr/lib, /usr/share, /usr/local, /opt); is art_standalone (-dev) installed there?"

p="$(gpath dx.jar)"
if [[ -n "$p" ]]; then
  log "  dx.jar <- guest:$p"
  pull_to "$p" "$JARS/dx.jar"
else
  warn "  dx.jar: none in the guest; only patch-core-oj.sh needs it"
fi

p="$(gpath r8.jar)"
if [[ -n "$p" ]]; then
  log "  r8.jar <- guest:$p"
  pull_to "$p" "$JARS/r8.jar"
else
  log "  r8.jar: none in the guest; downloading R8 $R8_VERSION from Google Maven"
  command -v curl >/dev/null 2>&1 || hostenv_die "curl is needed to download $R8_URL"
  dl="$(mktemp -d "${TMPDIR:-/tmp}/r8dl.XXXXXX")"
  curl -fsSL --retry 3 -o "$dl/r8.jar" "$R8_URL" || { rm -rf "$dl"; hostenv_die "download failed: $R8_URL"; }
  sha1="$(digest sha1 "$dl/r8.jar")"; sha256="$(digest sha256 "$dl/r8.jar")"
  if [[ -n "$R8_SHA256" ]]; then
    [[ "$sha256" == "$R8_SHA256" ]] || { rm -rf "$dl"; hostenv_die "r8-$R8_VERSION.jar sha256 $sha256 != pinned $R8_SHA256"; }
  else
    [[ "$sha1" == "$R8_SHA1" ]] || { rm -rf "$dl"; hostenv_die "r8-$R8_VERSION.jar sha1 $sha1 != pinned $R8_SHA1"; }
    warn "  r8-$R8_VERSION.jar matches the pinned SHA-1; record its SHA-256 in hostbuild-setup.sh: R8_SHA256=$sha256"
  fi
  mv -f "$dl/r8.jar" "$JARS/r8.jar"; rm -rf "$dl"
fi

# 3. sanity: every jar is a zip with the classes hostbuild.sh relies on
"$PY" -I - "$(topath "$JARS")" <<'PYEOF'
import sys, zipfile
d = sys.argv[1]
need = {
    "core-all_classes.jar": "java/lang/Object.class",
    "core-junit_classes.jar": None,
    "junit-runner_classes.jar": None,
    "r8.jar": "com/android/tools/r8/D8.class",
}
bad = []
for name, member in need.items():
    try:
        names = zipfile.ZipFile(d + "/" + name).namelist()
    except Exception as e:
        bad.append("%s (%s)" % (name, e)); continue
    if member and member not in names:
        bad.append("%s (no %s)" % (name, member))
if bad:
    sys.exit("hostbuild-setup: unusable jars: " + ", ".join(bad))
PYEOF
ls -la "$JARS" | sed 's/^/  /'
log "work dir ready: $WORK"
