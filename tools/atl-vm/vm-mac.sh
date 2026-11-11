#!/usr/bin/env bash
# teslalinux ATL test VM on an Apple Silicon Mac: the same Alpine aarch64 guest as vm.sh, but run
# natively under QEMU's HVF accelerator (no Docker, no CPU emulation). State lives in ~/teslalinux-vm:
#   disk.qcow2 (+ its backing image), efivars.fd, seed.iso, atlvm (the guest ssh key).
#   vm-mac.sh start | stop | status | wait [tries] | ssh [cmd...] | push LOCAL REMOTE | pull REMOTE LOCAL
#   vm-mac.sh up               start + wait for ssh (the VM state in ~/teslalinux-vm must exist)
#   vm-mac.sh shell            interactive guest shell
#   vm-mac.sh provision        copy guest-provision.sh into the guest and run it
#   vm-mac.sh console [lines]  tail the serial console log
# ATL_VM_FWD_CAR=1 (opt-in, read at `start`): also forward the car stack's web ports for
# tools/atl-vm/carstack.sh: 127.0.0.1:${ATL_VM_FWD_CAR_HTTPS:-9443} -> guest 8443 (https) and
# 127.0.0.1:${ATL_VM_FWD_CAR_HTTP:-9880} -> guest 8880 (http). Unset: the VM starts exactly as before.
# ATL_VM_FWD_CAR_GUEST_HTTPS/_HTTP change the guest ports (the Debian VM runs nginx on 443/80, as the Pi).
# A running VM keeps its forwards; stop and start it to change them.
# vm.sh hands every subcommand to this script on macOS. "destroy" is refused here: the overlay holds
# the whole patched ATL build, the Waze install and the test scripts.
set -euo pipefail
# the repo directory, also when run through the ~/teslalinux-vm/vm-mac.sh symlink
SRC="${BASH_SOURCE[0]}"
while [[ -L "$SRC" ]]; do
  T="$(readlink "$SRC")"; [[ "$T" == /* ]] && SRC="$T" || SRC="$(dirname "$SRC")/$T"
done
HERE="$(cd "$(dirname "$SRC")" && pwd)"
export PATH="/opt/homebrew/bin:$PATH" # non-interactive ssh shells lack Homebrew (qemu-img)
VM="${ATL_VM_DIR:-$HOME/teslalinux-vm}"
CPUS="${ATL_VM_CPUS:-4}"
# 4G: the 8 GB M1 swapped a 6 GB guest out completely once Waze + a core dump filled it
MEM="${ATL_VM_MEM:-4G}"
SSH_PORT="${ATL_VM_SSH_PORT:-2222}"
PIDFILE="$VM/qemu.pid"
KEY="$VM/atlvm"
QEMU="${QEMU:-$(command -v qemu-system-aarch64 || echo /opt/homebrew/bin/qemu-system-aarch64)}"
FW="${ATL_VM_FW:-$(dirname "$(dirname "$QEMU")")/share/qemu/edk2-aarch64-code.fd}"
SSH_OPTS=(-i "$KEY" -p "$SSH_PORT" -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR -o ConnectTimeout=5)
log() { printf '\033[1;36m[vm-mac]\033[0m %s\n' "$*"; }
running() { [[ -f "$PIDFILE" ]] && kill -0 "$(cat "$PIDFILE")" 2>/dev/null; }

case "${1:-}" in
  start)
    running && { log "already running (pid $(cat "$PIDFILE"))"; exit 0; }
    # one guest at a time on the 8 GB Mac: the Alpine ATL VM (~/teslalinux-vm) and the Debian build
    # VM (~/teslalinux-debvm, tools/debvm/vm-deb.sh) take 4 GB each
    for other in "$HOME/teslalinux-vm" "$HOME/teslalinux-debvm"; do
      [[ "$other" == "$VM" || "${ATL_VM_ALLOW_TWO:-0}" == 1 ]] && continue
      if [[ -f "$other/qemu.pid" ]] && kill -0 "$(cat "$other/qemu.pid")" 2>/dev/null; then
        log "another VM runs from $other (pid $(cat "$other/qemu.pid")); only one at a time (ATL_VM_ALLOW_TWO=1 overrides)"
        exit 1
      fi
    done
    CAR_FWD=""
    if [[ "${ATL_VM_FWD_CAR:-0}" == 1 ]]; then
      gh="${ATL_VM_FWD_CAR_GUEST_HTTPS:-8443}" gp="${ATL_VM_FWD_CAR_GUEST_HTTP:-8880}"
      CAR_FWD=",hostfwd=tcp:127.0.0.1:${ATL_VM_FWD_CAR_HTTPS:-9443}-:$gh,hostfwd=tcp:127.0.0.1:${ATL_VM_FWD_CAR_HTTP:-9880}-:$gp"
      log "car stack ports forwarded: https 127.0.0.1:${ATL_VM_FWD_CAR_HTTPS:-9443} -> guest $gh, http 127.0.0.1:${ATL_VM_FWD_CAR_HTTP:-9880} -> guest $gp"
    fi
    # the disk was created in the Docker VM with an absolute /vm/... backing path; point it at the local copy
    base="$(qemu-img info "$VM/disk.qcow2" | sed -n 's/^backing file: \([^ ]*\).*/\1/p')"
    if [[ -n "$base" && ! -f "$base" ]]; then
      qemu-img rebase -u -F qcow2 -b "$VM/$(basename "$base")" "$VM/disk.qcow2"
      log "rebased disk.qcow2 onto $VM/$(basename "$base")"
    fi
    "$QEMU" \
      -M virt,gic-version=3,highmem=on -accel hvf -cpu host -smp "$CPUS" -m "$MEM" \
      -drive if=pflash,format=raw,readonly=on,file="$FW" \
      -drive if=pflash,format=raw,file="$VM/efivars.fd" \
      -drive if=virtio,format=qcow2,discard=unmap,file="$VM/disk.qcow2" \
      -drive if=virtio,format=raw,readonly=on,file="$VM/seed.iso" \
      -device virtio-rng-pci \
      -netdev "user,id=n0,hostfwd=tcp:127.0.0.1:$SSH_PORT-:22$CAR_FWD" -device virtio-net-pci,netdev=n0,romfile= \
      -display none -serial file:"$VM/console.log" -monitor none \
      -pidfile "$PIDFILE" -daemonize >"$VM/qemu.log" 2>&1
    log "started (pid $(cat "$PIDFILE")); console: $VM/console.log"
    ;;
  stop)
    running || { log "not running"; exit 0; }
    ssh "${SSH_OPTS[@]}" atl@127.0.0.1 'sudo poweroff' 2>/dev/null || true
    for _ in $(seq 1 40); do running || break; sleep 1; done
    running && kill "$(cat "$PIDFILE")" || true
    rm -f "$PIDFILE"; log "stopped"
    ;;
  status)
    running && echo "running (pid $(cat "$PIDFILE"))" || echo "stopped"
    tail -n "${2:-5}" "$VM/console.log" 2>/dev/null || true
    ;;
  wait)
    for i in $(seq 1 "${2:-60}"); do
      ssh "${SSH_OPTS[@]}" atl@127.0.0.1 true 2>/dev/null && { log "ssh up after $((i*2))s"; exit 0; }
      sleep 2
    done
    log "ssh never came up; console tail:"; tail -30 "$VM/console.log"; exit 1
    ;;
  up)
    [[ -f "$VM/disk.qcow2" && -f "$VM/efivars.fd" && -f "$VM/seed.iso" && -f "$KEY" ]] \
      || { log "VM state incomplete in $VM (needs disk.qcow2, efivars.fd, seed.iso, atlvm)"; exit 1; }
    bash "$HERE/vm-mac.sh" start
    exec bash "$HERE/vm-mac.sh" wait "${2:-60}"
    ;;
  ssh)  shift; exec ssh "${SSH_OPTS[@]}" atl@127.0.0.1 "$@" ;;
  shell) exec ssh -t "${SSH_OPTS[@]}" atl@127.0.0.1 ;;
  push) exec scp -i "$KEY" -P "$SSH_PORT" -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR "$2" "atl@127.0.0.1:$3" ;;
  pull) exec scp -i "$KEY" -P "$SSH_PORT" -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR "atl@127.0.0.1:$2" "$3" ;;
  provision)
    scp -i "$KEY" -P "$SSH_PORT" -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR \
      "$HERE/guest-provision.sh" atl@127.0.0.1:/home/atl/guest-provision.sh
    exec ssh "${SSH_OPTS[@]}" atl@127.0.0.1 'chmod +x ~/guest-provision.sh && ~/guest-provision.sh'
    ;;
  console) tail -n "${2:-50}" "$VM/console.log" ;;
  destroy)
    log "refusing to destroy $VM: disk.qcow2 holds the patched ATL build, the Waze install and the test scripts."
    log "to really start over: vm-mac.sh stop, then delete $VM/disk.qcow2 and $VM/efivars.fd by hand"
    exit 1
    ;;
  *) echo "usage: vm-mac.sh start|stop|status|wait|up|ssh|shell|push|pull|provision|console"; exit 2 ;;
esac
