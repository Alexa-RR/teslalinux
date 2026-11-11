#!/bin/sh
# Install or update an Android app for the ATL runtime from a split-APK bundle (.apkm / .xapk / .apks,
# all plain zips of APKs) or from a directory of APKs.
#
#   install-app.sh <bundle-or-dir> [--apps DIR] [--abi arm64_v8a] [--density xhdpi] [--lang en]
#                  [--allow-downgrade] [--no-restart]
#
# Layout: $APPS/<package>/ holds base.apk plus the split_config.*.apk files this device needs
# (ATL is started with all of them, base first). Keeping the original signed APKs instead of a
# merged, re-signed one preserves the APK Signing Block that Google APIs fingerprint.
#
# Like the Android package manager, an update is only accepted when it is the same package, signed
# with the same certificate (signer SHA-256) and not a lower versionCode. The previous set is kept
# as <package>.old for rollback (`mv`), the switch itself is a rename, so a crash or power loss in
# the middle leaves either the old or the new set complete.
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
INFO="$HERE/apk-info.py"
APPS=${APPS_DIR:-/var/lib/teslalinux/apps}
ABI=""; DENSITY=${APP_DENSITY:-xhdpi}; LANG_SPLIT=${APP_LANG:-en}; ALLOW_DOWNGRADE=0; RESTART=1
SRC=""
while [ $# -gt 0 ]; do
  case "$1" in
    --apps) APPS=$2; shift ;;
    --abi) ABI=$2; shift ;;
    --density) DENSITY=$2; shift ;;
    --lang) LANG_SPLIT=$2; shift ;;
    --allow-downgrade) ALLOW_DOWNGRADE=1 ;;
    --no-restart) RESTART=0 ;;
    -*) echo "unknown option $1" >&2; exit 2 ;;
    *) SRC=$1 ;;
  esac
  shift
done
[ -n "$SRC" ] || { sed -n 2,16p "$0"; exit 2; }
if [ -z "$ABI" ]; then
  case "$(uname -m)" in aarch64|arm64) ABI=arm64_v8a ;; armv7l|armv8l) ABI=armeabi_v7a ;; x86_64) ABI=x86_64 ;; *) ABI=$(uname -m) ;; esac
fi

log() { printf '[install-app] %s\n' "$*"; }
die() { printf '[install-app] error: %s\n' "$*" >&2; exit 1; }

STAGE=$(mktemp -d "${TMPDIR:-/tmp}/install-app.XXXXXX")
trap 'rm -rf "$STAGE"' EXIT

# 1. unpack / collect the APKs
if [ -d "$SRC" ]; then
  cp "$SRC"/*.apk "$STAGE"/
else
  unzip -q -o "$SRC" '*.apk' -d "$STAGE" || die "could not unpack $SRC"
fi
[ -f "$STAGE/base.apk" ] || die "bundle has no base.apk"

# 2. choose the splits this device needs (bundletool naming: split_config.<qualifier>.apk)
SET="$STAGE/base.apk"
for q in "$ABI" "$DENSITY" "$LANG_SPLIT"; do
  f="$STAGE/split_config.$q.apk"
  if [ -f "$f" ]; then SET="$SET $f"; else log "no split for '$q' in bundle (fine if base.apk carries it)"; fi
done
BASE_ABIS=$(python3 "$INFO" "$STAGE/base.apk" | python3 -c 'import json,sys; print(" ".join(json.load(sys.stdin)["abis"]))')
case " $SET " in *"split_config.$ABI.apk"*) ;; *)
  case " $BASE_ABIS " in *" $(echo "$ABI" | tr _ -) "*) ;; *) die "no native code for $ABI: neither split_config.$ABI.apk nor lib/ in base.apk" ;; esac ;;
esac

# 3. identity of the new set
NEW_JSON=$(python3 "$INFO" "$STAGE/base.apk")
PKG=$(printf '%s' "$NEW_JSON" | python3 -c 'import json,sys; print(json.load(sys.stdin)["package"])')
NEW_VC=$(printf '%s' "$NEW_JSON" | python3 -c 'import json,sys; print(json.load(sys.stdin)["versionCode"])')
NEW_VN=$(printf '%s' "$NEW_JSON" | python3 -c 'import json,sys; print(json.load(sys.stdin)["versionName"])')
NEW_SIG=$(printf '%s' "$NEW_JSON" | python3 -c 'import json,sys; print(json.load(sys.stdin)["signer"])')
[ -n "$PKG" ] && [ "$PKG" != "None" ] || die "base.apk has no package name"
[ "$NEW_SIG" != "None" ] || die "base.apk has no APK Signing Block (v2/v3 signature)"
for f in $SET; do
  s=$(python3 "$INFO" "$f" | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d["package"], d["signer"])')
  [ "$s" = "$PKG $NEW_SIG" ] || die "$(basename "$f") is not part of $PKG signed by $NEW_SIG ($s)"
done
log "$PKG versionCode=$NEW_VC ($NEW_VN) signer=$NEW_SIG abi=$ABI"

# 4. compare with what is installed
CUR="$APPS/$PKG"
if [ -f "$CUR/base.apk" ]; then
  CUR_JSON=$(python3 "$INFO" "$CUR/base.apk")
  CUR_VC=$(printf '%s' "$CUR_JSON" | python3 -c 'import json,sys; print(json.load(sys.stdin)["versionCode"])')
  CUR_SIG=$(printf '%s' "$CUR_JSON" | python3 -c 'import json,sys; print(json.load(sys.stdin)["signer"])')
  [ "$CUR_SIG" = "$NEW_SIG" ] || die "signing certificate differs from the installed $PKG (installed $CUR_SIG, new $NEW_SIG); refusing, as Android would"
  if [ "$NEW_VC" -lt "$CUR_VC" ] && [ "$ALLOW_DOWNGRADE" = 0 ]; then
    die "versionCode $NEW_VC is lower than the installed $CUR_VC (use --allow-downgrade)"
  fi
  log "updating from versionCode $CUR_VC"
else
  log "fresh install"
fi

# 5. stage next to the target and switch by rename
mkdir -p "$APPS"
NEXT="$APPS/$PKG.new"
rm -rf "$NEXT"; mkdir "$NEXT"
for f in $SET; do cp "$f" "$NEXT/"; done
printf '{"package":"%s","versionCode":%s,"versionName":"%s","signer":"%s","abi":"%s","apks":"%s"}\n' \
  "$PKG" "$NEW_VC" "$NEW_VN" "$NEW_SIG" "$ABI" "$(for f in $SET; do basename "$f"; done | tr '\n' ' ' | sed 's/ $//')" > "$NEXT/installed.json"
sync
rm -rf "$CUR.old"
[ -d "$CUR" ] && mv "$CUR" "$CUR.old"
mv "$NEXT" "$CUR"
sync
log "installed into $CUR: $(ls "$CUR" | tr '\n' ' ')"

# 6. restart the running instance so it picks up the new code (the supervisor/runner restarts it)
if [ "$RESTART" = 1 ] && pgrep -f "android-translation-layer $CUR/base.apk" >/dev/null 2>&1; then
  log "restarting the running app"
  pkill -f "android-translation-layer $CUR/base.apk" || true
fi
