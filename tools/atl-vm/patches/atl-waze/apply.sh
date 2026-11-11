#!/bin/sh
# teslalinux: apply the Waze stub batch to the ATL checkout at ~/atl-src and rebuild into ~/atl-prefix.
# Files under this directory mirror src/api-impl/. Idempotent.
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="$HOME/atl-src"
API="$SRC/src/api-impl"
MESON="$API/meson.build"
log() { printf '\033[1;33m[atl-waze]\033[0m %s\n' "$*"; }

cd "$HERE"
for f in $(find android com javax -name '*.java' 2>/dev/null | sort); do
  mkdir -p "$API/$(dirname "$f")"
  cp "$f" "$API/$f"
  if ! grep -q "'$f'," "$MESON"; then
    # insert into the srcs list right after its opening line (order does not matter to meson); busybox sed
    awk -v f="$f" '!done && /^srcs = \[/ { print; printf "\t\x27%s\x27,\n", f; done=1; next } { print }' "$MESON" > "$MESON.tmp" && mv "$MESON.tmp" "$MESON"
    log "registered $f"
  fi
done

# Context.getAttributionSource() (API 31), next to the existing getAttributionTag().
CTX="$API/android/content/Context.java"
if ! grep -q "getAttributionSource" "$CTX"; then
  sed -i "s|^\tpublic String getAttributionTag() {|\tpublic android.content.AttributionSource getAttributionSource() {\n\t\treturn new android.content.AttributionSource(android.os.Process.myUid(), getPackageName(), getAttributionTag());\n\t}\n\n\tpublic String getAttributionTag() {|" "$CTX"
  log "patched Context.getAttributionSource()"
fi

# StorageManager.UUID_DEFAULT is the argument apps pass to StorageStatsManager.
SM="$API/android/os/storage/StorageManager.java"
if ! grep -q "UUID_DEFAULT" "$SM"; then
  # same text as hostbuild.sh inserts
  sed -i "s|^public class StorageManager {|public class StorageManager {\n\t/* teslalinux: this platform has a single volume, internal storage, so every path is on\n\t * UUID_DEFAULT (what AOSP returns for /data paths on a device without adoptable or portable\n\t * storage). There is no StorageManagerService to reserve or free cache space (allocateBytes),\n\t * so allocation is reported unsupported, AOSP's answer for an fd it cannot allocate on. */\n\tpublic static final java.util.UUID UUID_DEFAULT = java.util.UUID.fromString(\"41217664-9172-527a-b3d5-edabb50a7d69\");\n\tpublic java.util.UUID getUuidForPath(java.io.File path) { return UUID_DEFAULT; }\n\tpublic boolean isAllocationSupported(java.io.FileDescriptor fd) { return false; }|" "$SM"
  log "patched StorageManager.UUID_DEFAULT"
fi

# Member fragments and textual replacements for classes ATL already has.
command -v python3 >/dev/null 2>&1 || "$(command -v sudo || command -v doas)" apk add -q python3
python3 "$HERE/inject.py" "$API" "$HERE"

cd "$SRC" && git add -A && git -c user.name=teslalinux -c user.email=dev@teslalinux commit -q -m "waze: stub batch ($(date +%F))" || true
log "rebuilding ATL"
"$HOME/build-atl.sh" rebuild 2>&1 | tail -20
