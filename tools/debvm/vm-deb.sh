#!/usr/bin/env bash
# teslalinux Debian trixie arm64 build/test VM on the Mac: the build machine for the native Raspberry
# Pi OS packages (out/pios-plan/PLAN.md, sections 1-3). The same QEMU/HVF guest as the Alpine ATL VM,
# run by the same tools/atl-vm/vm-mac.sh with its state in ~/teslalinux-debvm and ssh on :2223.
#
#   vm-deb.sh init         download + verify the Debian cloud image, make the overlay, UEFI vars,
#                          guest key and cloud-init seed (idempotent; never touches an existing overlay)
#   vm-deb.sh up           start + wait for ssh (refused while the Alpine VM runs: 8 GB Mac, one VM)
#   vm-deb.sh provision    push guest-provision.sh + build-dist.sh + versions.env and install build deps
#   vm-deb.sh push-patches push the repo's ATL native patches + bionic patch into the guest
#   vm-deb.sh push-tacomp  push services/display into ~/tacomp
#   vm-deb.sh build [STEP...]  push everything, run build-dist.sh detached (log ~/build/build.out)
#   vm-deb.sh start | stop | status | wait | ssh [cmd] | shell | push L R | pull R L | console
#   vm-deb.sh ports        the Mac ports forwarded to the guest's nginx (443 and 80), see car_fwd_env
#   vm-deb.sh switch-from-alpine   stop the Alpine VM (asks first unless --yes), then `up`
#
# The guest is user `atl` (passwordless sudo), key ~/teslalinux-debvm/atlvm. The base image stays
# read-only as the overlay's backing file, like the Alpine VM's.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
export PATH="/opt/homebrew/bin:$PATH"
export ATL_VM_DIR="${DEB_VM_DIR:-$HOME/teslalinux-debvm}"
export ATL_VM_SSH_PORT="${DEB_VM_SSH_PORT:-2223}"
export ATL_VM_CPUS="${DEB_VM_CPUS:-4}"
export ATL_VM_MEM="${DEB_VM_MEM:-4G}"
VM="$ATL_VM_DIR"
ALPINE_VM="${ATL_VM_ALPINE_DIR:-$HOME/teslalinux-vm}"
VMMAC="$REPO/tools/atl-vm/vm-mac.sh"
DISK_SIZE="${DEB_VM_DISK:-48G}"
IMG_URL="${DEB_VM_IMG_URL:-https://cloud.debian.org/images/cloud/trixie/latest}"
IMG="debian-13-genericcloud-arm64.qcow2"
QEMU="$(command -v qemu-system-aarch64 || echo /opt/homebrew/bin/qemu-system-aarch64)"
SHARE="$(dirname "$(dirname "$QEMU")")/share/qemu"
log() { printf '\033[1;35m[vm-deb]\033[0m %s\n' "$*"; }
die() { log "$*"; exit 1; }
alive() { [[ -f "$1/qemu.pid" ]] && kill -0 "$(cat "$1/qemu.pid")" 2>/dev/null; }

# The car stack's web ports, forwarded at start (loopback only): Mac 127.0.0.1:8443 -> guest 443 and
# 127.0.0.1:8080 -> guest 80, where the Debian VM's system nginx listens as on the Pi
# (tools/debian/carstack.sh). DEB_VM_FWD_CAR=0 turns them off; DEB_VM_HTTPS_PORT/DEB_VM_HTTP_PORT move
# the Mac side. QEMU refuses to start when a forwarded port is taken (host/local/web-local.sh's nginx
# also uses 8443), so a busy port falls back to the next free one from +1000 and says so.
car_fwd_env() {
  [[ "${DEB_VM_FWD_CAR:-1}" == 1 ]] || return 0
  local https="${DEB_VM_HTTPS_PORT:-8443}" http="${DEB_VM_HTTP_PORT:-8080}"
  busy() { lsof -nP -iTCP:"$1" -sTCP:LISTEN >/dev/null 2>&1; }
  local p
  for p in https http; do
    local port=${!p} want=${!p}
    while busy "$port"; do port=$((port + 1000)); done
    [[ $port == "$want" ]] || log "Mac port $want is taken ($(lsof -nP -iTCP:"$want" -sTCP:LISTEN | awk 'NR==2{print $1" pid "$2}')): forwarding $p on $port instead"
    printf -v "$p" '%s' "$port"
  done
  export ATL_VM_FWD_CAR=1 ATL_VM_FWD_CAR_HTTPS="$https" ATL_VM_FWD_CAR_HTTP="$http" \
    ATL_VM_FWD_CAR_GUEST_HTTPS=443 ATL_VM_FWD_CAR_GUEST_HTTP=80
  echo "https=$https http=$http" > "$VM/car-ports"
}

guard_one_vm() {
  # Both guests take 4 GB; the 8 GB Mac swaps hard with two. Never stop the other one implicitly:
  # another session may be in the middle of a Waze run there.
  if alive "$ALPINE_VM"; then
    die "the Alpine ATL VM is running (pid $(cat "$ALPINE_VM/qemu.pid")); only one VM at a time. Stop it with tools/atl-vm/vm-mac.sh stop when nobody uses it, or: vm-deb.sh switch-from-alpine"
  fi
}

case "${1:-}" in
  init)
    mkdir -p "$VM"
    cd "$VM"
    if [[ ! -f "$IMG" ]]; then
      log "downloading $IMG_URL/$IMG (about 330 MB)"
      curl -fL --retry 3 -o SHA512SUMS "$IMG_URL/SHA512SUMS"
      curl -fL --retry 3 -o "$IMG.part" "$IMG_URL/$IMG"
      want="$(awk -v f="$IMG" '$2 == f {print $1}' SHA512SUMS)"
      [[ -n "$want" ]] || die "SHA512SUMS has no line for $IMG"
      got="$(shasum -a 512 "$IMG.part" | awk '{print $1}')"
      [[ "$want" == "$got" ]] || die "checksum mismatch for $IMG (kept as $IMG.part)"
      mv "$IMG.part" "$IMG"
      chmod a-w "$IMG"
      log "image verified (sha512 $got)"
    fi
    if [[ ! -f disk.qcow2 ]]; then
      qemu-img create -q -f qcow2 -F qcow2 -b "$VM/$IMG" disk.qcow2 "$DISK_SIZE"
      log "overlay disk.qcow2 ($DISK_SIZE virtual) on $IMG"
    else
      log "disk.qcow2 exists: left alone"
    fi
    [[ -f efivars.fd ]] || { cp "$SHARE/edk2-arm-vars.fd" efivars.fd; chmod u+w efivars.fd; log "efivars.fd from edk2-arm-vars.fd"; }
    [[ -f atlvm ]] || { ssh-keygen -q -t ed25519 -N '' -C atl-debvm -f atlvm; log "guest key atlvm"; }
    if [[ ! -f seed.iso || "$HERE/seed/user-data.tmpl" -nt seed.iso ]]; then
      rm -rf seedfs seed.iso; mkdir seedfs
      sed "s|@PUBKEY@|$(cat atlvm.pub)|" "$HERE/seed/user-data.tmpl" > seedfs/user-data
      cp "$HERE/seed/meta-data" seedfs/meta-data
      # NoCloud datasource: an ISO9660 volume labelled cidata with user-data + meta-data
      hdiutil makehybrid -quiet -iso -joliet -default-volume-name cidata -o seed.iso seedfs
      log "seed.iso (cloud-init NoCloud)"
    fi
    log "init done: $VM"
    ;;
  up)
    guard_one_vm
    [[ -f "$VM/disk.qcow2" ]] || die "no VM in $VM: run vm-deb.sh init first"
    alive "$VM" || car_fwd_env
    bash "$VMMAC" start
    exec bash "$VMMAC" wait "${2:-90}"
    ;;
  start)
    guard_one_vm
    alive "$VM" || car_fwd_env
    exec bash "$VMMAC" start
    ;;
  switch-from-alpine)
    if alive "$ALPINE_VM"; then
      if [[ "${2:-}" != "--yes" ]]; then
        read -r -p "Stop the Alpine ATL VM (pid $(cat "$ALPINE_VM/qemu.pid"))? Anyone using it loses their run. [y/N] " a
        [[ "$a" == y || "$a" == Y ]] || die "not stopped"
      fi
      ATL_VM_DIR="$ALPINE_VM" ATL_VM_SSH_PORT=2222 bash "$VMMAC" stop
    fi
    exec bash "$0" up
    ;;
  provision)
    for f in guest-provision.sh build-dist.sh versions.env; do
      bash "$VMMAC" push "$HERE/$f" "/home/atl/$f"
    done
    exec bash "$VMMAC" ssh 'chmod +x ~/guest-provision.sh ~/build-dist.sh && ~/guest-provision.sh'
    ;;
  provision-async)
    # long apt runs go silent over ssh (the session comes back with no output): detach and poll ~/provision.out
    for f in guest-provision.sh build-dist.sh versions.env; do
      bash "$VMMAC" push "$HERE/$f" "/home/atl/$f"
    done
    exec bash "$VMMAC" ssh 'chmod +x ~/guest-provision.sh ~/build-dist.sh && setsid sh -c "~/guest-provision.sh > ~/provision.out 2>&1; echo exit \$? >> ~/provision.out" < /dev/null > /dev/null 2>&1 &'
    ;;
  push-patches)
    # the repo's ATL native patches -> ~/atl-native-patches/{*.sh,files/*} (replaced as a whole, so a
    # script deleted in the repo is gone in the guest too), the bionic_translation patch -> ~/patch-bionic.sh,
    # tools/debvm/patches -> ~/debvm-patches
    P="$REPO/tools/atl-vm/patches"
    tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
    mkdir -p "$tmp/atl-native-patches/files"
    cp "$P"/atl-waze/native-*.sh "$tmp/atl-native-patches/"
    cp "$P"/atl-waze/native/*.c "$tmp/atl-native-patches/files/"
    cp "$P/bionic_translation-waze.sh" "$tmp/patch-bionic.sh"
    cp "$P/bionic_translation-sysconf.py" "$tmp/patch-bionic-sysconf.py"
    # this VM's own source fixes for the Debian toolchain (GCC 14 etc.) -> ~/debvm-patches
    mkdir -p "$tmp/debvm-patches"; cp "$HERE"/patches/* "$tmp/debvm-patches/"
    # build-dist.sh's coreoj step (libcore boot classpath sources) and tlplay step (when cargo exists)
    mkdir -p "$tmp/debvm-bootcp" "$tmp/appupdate"; cp -R "$P/atl-waze/bootcp/." "$tmp/debvm-bootcp/"
    cp -R "$REPO/tools/appupdate/src" "$REPO/tools/appupdate/Cargo.toml" "$REPO/tools/appupdate/Cargo.lock" "$REPO/tools/appupdate/.cargo" "$tmp/appupdate/"
    COPYFILE_DISABLE=1 tar --no-mac-metadata --no-xattrs -C "$tmp" -cf - . | bash "$VMMAC" ssh 'rm -rf ~/atl-native-patches ~/debvm-patches ~/debvm-bootcp ~/appupdate && tar -xf - -C ~ && ls ~/atl-native-patches | wc -l'
    log "pushed $(ls "$P"/atl-waze/native-*.sh | wc -l | tr -d ' ') native patch scripts + the bionic patch"
    ;;
  push-tacomp)
    COPYFILE_DISABLE=1 tar --no-mac-metadata --no-xattrs -C "$REPO/services/display" --exclude build -cf - . | bash "$VMMAC" ssh 'rm -rf ~/tacomp && mkdir ~/tacomp && tar -xf - -C ~/tacomp'
    log "pushed services/display to ~/tacomp"
    ;;
  build)
    # the whole build detached (long steps go silent over ssh); poll with: vm-deb.sh ssh tail ~/build/build.out
    shift
    for f in build-dist.sh versions.env; do bash "$VMMAC" push "$HERE/$f" "/home/atl/$f"; done
    bash "$0" push-patches; bash "$0" push-tacomp
    exec bash "$VMMAC" ssh "chmod +x ~/build-dist.sh && mkdir -p ~/build && setsid sh -c '~/build-dist.sh ${*:-all} > ~/build/build.out 2>&1; echo exit \$? >> ~/build/build.out' < /dev/null > /dev/null 2>&1 &"
    ;;
  ports)
    # the Mac ports the running VM forwards (written at start)
    if alive "$VM" && [[ -f "$VM/car-ports" ]]; then cat "$VM/car-ports"; else echo "no car port forwards (VM not started by vm-deb.sh with DEB_VM_FWD_CAR=1)"; fi
    ;;
  stop|status|wait|ssh|shell|push|pull|console)
    exec bash "$VMMAC" "$@"
    ;;
  *) echo "usage: vm-deb.sh init|up|start|stop|status|wait|ssh|shell|push|pull|console|ports|provision|provision-async|push-patches|push-tacomp|build|switch-from-alpine"; exit 2 ;;
esac
