#!/usr/bin/env bash
# teslalinux: a stand-in for tools/pios/install.sh in host/test/test_release_update.py. It does what
# the real installer does to the parts the release updater looks at, under the test's directories
# (the updater's own RELEASE_UPDATE_* variables): unpacks the dist into DISTS/<version>, records a
# backup set with dist.before, links PREFIX to the new dist; rollback goes back to the latest set's
# dist.before and renames the set as the real one does.
#   install --dist FILE --yes --no-reboot | prepare --dist FILE --yes | rollback --yes
#   FAKE_PREPARE_FAIL=1: prepare exits 1 (the release is then installed the slow way)
#   FAKE_INSTALL_FAIL=after-switch: exits 1 once the link has moved (a run that died half way)
#   FAKE_INSTALL_NO_SET=1: changes no file but the link (a run that leaves no backup set)
set -euo pipefail
PREFIX=$RELEASE_UPDATE_PREFIX DISTS=$RELEASE_UPDATE_DISTS BACKUP_ROOT=$RELEASE_UPDATE_BACKUP_ROOT
echo "fake-install $*" >> "$FAKE_INSTALL_LOG"
case "${1:-}" in
  prepare)
    [[ "$2" == --dist && "$4" == --yes ]] || { echo "bad arguments: $*" >&2; exit 2; }
    [[ -f "$3" ]] || exit 1
    [[ -n "${FAKE_PREPARE_FAIL:-}" ]] && { echo "unshare: mount failed" >&2; exit 1; }
    exit 0 ;;
  install)
    [[ "$2" == --dist && "$4" == --yes && "$5" == --no-reboot ]] || { echo "bad arguments: $*" >&2; exit 2; }
    file=$3
    sums="$(dirname "$file")/SHA256SUMS"
    want=$(awk -v n="$(basename "$file")" '$2 == n { print $1 }' "$sums")
    got=$(sha256sum "$file" | cut -d' ' -f1)
    [[ -n "$want" && "$want" == "$got" ]] || { echo "sha256 mismatch" >&2; exit 1; }
    # teslalinux-dist-<version>-arm64.tar.xz holds teslalinux-dist-<version>/
    ver=$(basename "$file"); ver=${ver#teslalinux-dist-}; ver=${ver%-arm64.tar.xz}
    before=$(readlink "$PREFIX" || true)
    mkdir -p "$DISTS/$ver.tmp" && tar -xJf "$file" -C "$DISTS/$ver.tmp" --strip-components=1
    rm -rf "${DISTS:?}/$ver"; mv "$DISTS/$ver.tmp" "$DISTS/$ver"
    if [[ -z "${FAKE_INSTALL_NO_SET:-}" ]]; then
      # the real names are run times; a sequence number keeps them in order within one second here
      mkdir -p "$BACKUP_ROOT"
      set_dir="$BACKUP_ROOT/20261124-$(printf '%06d' "$(ls -1 "$BACKUP_ROOT" | wc -l)")"
      mkdir -p "$set_dir"; printf '%s\n' "$before" > "$set_dir/dist.before"; printf '# fake\n' > "$set_dir/MANIFEST"
    fi
    rm -f "$PREFIX"; ln -s "$DISTS/$ver" "$PREFIX"
    [[ "${FAKE_INSTALL_FAIL:-}" == after-switch ]] && { echo "apt-get update failed" >&2; exit 1; }
    exit 0 ;;
  rollback)
    [[ "${2:-}" == --yes ]] || exit 2
    set_dir=$(ls -1d "$BACKUP_ROOT"/2* 2>/dev/null | tail -1)
    [[ -n "$set_dir" ]] || { echo "no backup set" >&2; exit 1; }
    before=$(cat "$set_dir/dist.before")
    [[ -d "$before" ]] || exit 1
    rm -f "$PREFIX"; ln -s "$before" "$PREFIX"
    mv "$set_dir" "$BACKUP_ROOT/rolled-back-$(basename "$set_dir")"
    exit 0 ;;
esac
exit 2
