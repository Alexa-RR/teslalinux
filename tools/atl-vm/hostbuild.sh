#!/usr/bin/env bash
# teslalinux: build ATL's api-impl.jar on the HOST (native javac + D8) instead of inside the guest,
# and push it into the guest's atl-prefix. Pure-Java changes only (JNI natives are untouched).
# About 20 s instead of a guest rebuild. Runs on macOS, Linux and Windows Git Bash.
#
#   hostbuild.sh            apply patches/atl-waze to the host tree, build, push jar, clear ART cache
#   hostbuild.sh --no-push  build only (the compile check)
#   hostbuild.sh --reset    re-extract the host tree from atl-guest-src.tgz first
#
# Work dir: $ATL_WORK, default <repo>/out/atl-work (git-ignored). hostbuild-setup.sh fills it from
# the guest. It must contain:
#   atl-guest-src.tgz                   tar of the guest's ~/atl-src/src/api-impl, entries src/api-impl/...
#   atl-guest/src/api-impl/             that tree extracted (done here on --reset or when missing)
#   atl-host/jars/core-all_classes.jar  ART libcore class jars: javac's -bootclasspath, as in ATL's
#   atl-host/jars/core-junit_classes.jar  meson build (art-standalone's libdir/java)
#   atl-host/jars/junit-runner_classes.jar
#   atl-host/jars/r8.jar                D8 (com.android.tools.r8.D8)
# Also needs a JDK (javac/java/jar; on macOS Homebrew openjdk@17 is found by itself) and python3.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=hostenv.sh
. "$HERE/hostenv.sh"
API="$WORK/atl-guest/src/api-impl"
OUT="$WORK/atl-host/build"
PATCH="$HERE/patches/atl-waze"
export MSYS_NO_PATHCONV=1
log() { printf '\033[1;32m[hostbuild]\033[0m %s\n' "$*"; }
PUSH=1; RESET=0
for a in "$@"; do case "$a" in --no-push) PUSH=0 ;; --reset) RESET=1 ;; *) hostenv_die "unknown option $a (--no-push, --reset)" ;; esac; done

# inputs present? (a missing extracted tree is a first run: extract it, i.e. an implicit --reset)
missing=""
for j in core-all_classes.jar core-junit_classes.jar junit-runner_classes.jar r8.jar; do
  [[ -s "$JARS/$j" ]] || missing="$missing atl-host/jars/$j"
done
if [[ ! -s "$WORK/atl-guest-src.tgz" ]]; then
  if [[ $RESET -eq 1 || ! -d "$API" ]]; then missing="$missing atl-guest-src.tgz"; fi
elif [[ ! -d "$API" ]]; then
  RESET=1
fi
[[ -z "$missing" ]] || hostenv_die "work dir $WORK is missing inputs:$missing -- run tools/atl-vm/hostbuild-setup.sh (guest running), or point ATL_WORK at a filled work dir"
hostenv_use_jdk

# 0. --reset: start again from the tree pulled out of the guest (atl-guest-src.tgz) and drop the
#    old hash-tagged fragment blocks that earlier injector versions appended (they ran to the end
#    of the class), so the keyed BEGIN/END fragments below are the only injected code.
if [[ $RESET -eq 1 ]]; then
  log "resetting host tree from $WORK/atl-guest-src.tgz"
  rm -rf "$WORK/atl-guest"; mkdir -p "$WORK/atl-guest"; tar -xzf "$WORK/atl-guest-src.tgz" -C "$WORK/atl-guest"
  [[ -d "$API" ]] || hostenv_die "atl-guest-src.tgz has no src/api-impl/ (re-run hostbuild-setup.sh src)"
  "$PY" "$(topath "$HERE/strip-old-frags.py")" "$(topath "$API")"
fi

# 1. apply the patch set to the host tree (new files + member fragments + replacements)
cd "$PATCH"
for f in $(find android com javax -name '*.java' 2>/dev/null | sort); do mkdir -p "$API/$(dirname "$f")"; cp "$f" "$API/$f"; done
# Two insertions into ATL classes, same text as apply.sh (the guest-side path). Each is guarded by a
# grep for the member it adds, so re-running leaves the file alone; a missing anchor is an error.
"$PY" -I - "$(topath "$API/android/content/Context.java")" "$(topath "$API/android/os/storage/StorageManager.java")" <<'PYEOF'
import io, re, sys
ctx, sm = sys.argv[1], sys.argv[2]

def insert(path, guard, anchor, text, what):
    s = io.open(path, encoding="utf-8").read()
    if guard in s:
        return
    s, n = re.subn(anchor, lambda m: text, s, flags=re.M)
    if n == 0:
        sys.exit("hostbuild: anchor for %s not found in %s" % (what, path))
    io.open(path, "w", encoding="utf-8", newline="\n").write(s)
    print("  patched " + what)

# Context.getAttributionSource() (API 31), next to the existing getAttributionTag().
insert(ctx, "getAttributionSource", r"^\tpublic String getAttributionTag\(\) \{",
       "\tpublic android.content.AttributionSource getAttributionSource() {\n"
       "\t\treturn new android.content.AttributionSource(android.os.Process.myUid(), getPackageName(), getAttributionTag());\n"
       "\t}\n"
       "\n"
       "\tpublic String getAttributionTag() {",
       "Context.getAttributionSource()")

# StorageManager.UUID_DEFAULT is the argument apps pass to StorageStatsManager.
insert(sm, "UUID_DEFAULT", r"^public class StorageManager \{",
       "public class StorageManager {\n"
       "\t/* teslalinux: this platform has a single volume, internal storage, so every path is on\n"
       "\t * UUID_DEFAULT (what AOSP returns for /data paths on a device without adoptable or portable\n"
       "\t * storage). There is no StorageManagerService to reserve or free cache space (allocateBytes),\n"
       "\t * so allocation is reported unsupported, AOSP's answer for an fd it cannot allocate on. */\n"
       "\tpublic static final java.util.UUID UUID_DEFAULT = java.util.UUID.fromString(\"41217664-9172-527a-b3d5-edabb50a7d69\");\n"
       "\tpublic java.util.UUID getUuidForPath(java.io.File path) { return UUID_DEFAULT; }\n"
       "\tpublic boolean isAllocationSupported(java.io.FileDescriptor fd) { return false; }",
       "StorageManager.UUID_DEFAULT")
PYEOF
"$PY" "$(topath "$PATCH/inject.py")" "$(topath "$API")" "$(topath "$PATCH")" | sed "s/^/  /"

# 2. javac (same flags as ATL's meson rule) -> hax.jar -> D8 -> api-impl.jar
rm -rf "$OUT"; mkdir -p "$OUT/classes"
find "$API" -name '*.java' | topath_list > "$OUT/sources.txt"
BCP="$(topath "$JARS/core-all_classes.jar")$CPSEP$(topath "$JARS/core-junit_classes.jar")$CPSEP$(topath "$JARS/junit-runner_classes.jar")"
log "javac $(wc -l < "$OUT/sources.txt" | tr -d ' ') files"
javac -g -nowarn -bootclasspath "$BCP" -source 1.8 -target 1.8 -encoding UTF-8 -Xlint:-options       -d "$(topath "$OUT/classes")" "@$(topath "$OUT/sources.txt")" > "$OUT/javac.log" 2>&1 || true
grep -vE "^Note:|warning:|^\s+\^|^\s*$" "$OUT/javac.log" | grep -B1 -A3 "error:" | head -60 || true
grep -q "error:" "$OUT/javac.log" && { echo "javac reported errors ($(grep -c 'error:' "$OUT/javac.log"))" >&2; exit 1; }
[[ -f "$OUT/classes/android/content/Context.class" ]] || { echo "javac failed:" >&2; tail -20 "$OUT/javac.log" >&2; exit 1; }
( cd "$OUT/classes" && jar cf "$(topath "$OUT/hax.jar")" . )
log "d8"
# D8 (r8.jar) instead of dx: dx emits invoke-custom for lambdas, which the guest ART cannot bootstrap
# (BootstrapMethodError); D8 desugars lambdas into classes and keeps default/static interface methods (API 24).
rm -rf "$OUT/dexout"; mkdir -p "$OUT/dexout"
java -cp "$(topath "$JARS/r8.jar")" com.android.tools.r8.D8 --min-api 24 --output "$(topath "$OUT/dexout")" "$(topath "$OUT/hax.jar")" 2>&1 | grep -v "^Warning" | head -5 || true
[[ -f "$OUT/dexout/classes.dex" ]] || { echo "D8 produced no classes.dex" >&2; exit 1; }
rm -f "$OUT/api-impl.jar"; (cd "$OUT/dexout" && jar cf "$(topath "$OUT/api-impl.jar")" classes.dex)
ls -la "$OUT/api-impl.jar" | awk '{print "  api-impl.jar " $5 " bytes"}'
"$PY" -I -c "import zipfile,sys; sys.exit(0 if 'classes.dex' in zipfile.ZipFile(sys.argv[1]).namelist() else 1)" "$(topath "$OUT/api-impl.jar")" || { echo "api-impl.jar has no classes.dex" >&2; exit 1; }

[[ $PUSH -eq 0 ]] && exit 0
# 3. push into the guest + drop ART's cached copy
bash "$HERE/vm.sh" push "$OUT/api-impl.jar" /home/atl/atl-prefix/lib/java/dex/android_translation_layer/api-impl.jar
bash "$HERE/vm.sh" ssh 'cp ~/atl-prefix/lib/java/dex/android_translation_layer/api-impl.jar ~/api-impl-host.jar; rm -f ~/.cache/art/arm64/*api-impl*; ls -la ~/atl-prefix/lib/java/dex/android_translation_layer/api-impl.jar | cut -c25-'
log "pushed"
