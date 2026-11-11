#!/usr/bin/env bash
# Runs INSIDE the atlvm-host container. Manages the aarch64 Alpine VM in /vm (a docker volume).
#   vm-inner prepare   download image, make ssh key + cloud-init seed ISO, create disk
#   vm-inner start | stop | status | wait [tries]
#   vm-inner ssh [cmd...]      vm-inner scp-to LOCAL REMOTE      vm-inner scp-from REMOTE LOCAL
set -euo pipefail
VM=/vm
SEED_DIR=/seed            # bind-mounted from the repo (meta-data, user-data.tmpl)
CPUS="${ATL_VM_CPUS:-8}"
MEM="${ATL_VM_MEM:-8G}"
SSH_PORT=2222
PIDFILE="$VM/qemu.pid"
KEY="$VM/ssh/atlvm"
SSH_OPTS=(-i "$KEY" -p "$SSH_PORT" -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR -o ConnectTimeout=5)
log() { printf '\033[1;36m[vm]\033[0m %s\n' "$*"; }
running() { [[ -f "$PIDFILE" ]] && kill -0 "$(cat "$PIDFILE")" 2>/dev/null; }

BASE_URL=https://dl-cdn.alpinelinux.org/alpine/v3.22/releases/cloud
IMG=nocloud_alpine-3.22.4-aarch64-uefi-cloudinit-r0.qcow2

case "${1:-}" in
  prepare)
    mkdir -p "$VM/ssh"
    cd "$VM"
    if [[ ! -f "$IMG" ]]; then log "downloading $IMG"; curl -sS -L -o "$IMG" "$BASE_URL/$IMG"; fi
    curl -sS -L -o "$IMG.sha512" "$BASE_URL/$IMG.sha512"
    want="$(awk '{print $1}' "$IMG.sha512")"; have="$(sha512sum "$IMG" | awk '{print $1}')"
    [[ "$want" == "$have" ]] || { echo "checksum mismatch for $IMG" >&2; rm -f "$IMG"; exit 1; }
    log "image checksum ok"
    [[ -f "$KEY" ]] || ssh-keygen -q -t ed25519 -N '' -f "$KEY" -C atlvm
    PUB="$(cat "$KEY.pub")"
    mkdir -p "$VM/seedfs"
    cp "$SEED_DIR/meta-data" "$VM/seedfs/meta-data"
    sed "s|@PUBKEY@|$PUB|" "$SEED_DIR/user-data.tmpl" > "$VM/seedfs/user-data"
    genisoimage -quiet -output "$VM/seed.iso" -volid cidata -joliet -rock "$VM/seedfs/user-data" "$VM/seedfs/meta-data"
    if [[ ! -f "$VM/disk.qcow2" ]]; then
      qemu-img create -q -f qcow2 -F qcow2 -b "$VM/$IMG" "$VM/disk.qcow2" 32G; log "created disk.qcow2 (32G)"
    fi
    [[ -f "$VM/efivars.fd" ]] || cp /usr/share/AAVMF/AAVMF_VARS.fd "$VM/efivars.fd"
    log "prepared"
    ;;
  start)
    running && { log "already running (pid $(cat "$PIDFILE"))"; exit 0; }
    qemu-system-aarch64 \
      -M virt,gic-version=3 -cpu cortex-a72 -accel tcg,thread=multi -smp "$CPUS" -m "$MEM" \
      -drive if=pflash,format=raw,readonly=on,file=/usr/share/AAVMF/AAVMF_CODE.fd \
      -drive if=pflash,format=raw,file="$VM/efivars.fd" \
      -drive if=virtio,format=qcow2,file="$VM/disk.qcow2" \
      -drive if=virtio,format=raw,readonly=on,file="$VM/seed.iso" \
      -device virtio-rng-pci \
      -netdev user,id=n0,hostfwd=tcp:0.0.0.0:$SSH_PORT-:22 -device virtio-net-pci,netdev=n0,romfile= \
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
    for i in $(seq 1 "${2:-120}"); do
      ssh "${SSH_OPTS[@]}" atl@127.0.0.1 true 2>/dev/null && { log "ssh up after $((i*5))s"; exit 0; }
      sleep 5
    done
    log "ssh never came up; console tail:"; tail -30 "$VM/console.log"; exit 1
    ;;
  ssh) shift; exec ssh "${SSH_OPTS[@]}" atl@127.0.0.1 "$@" ;;
  scp-to) exec scp -i "$KEY" -P "$SSH_PORT" -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR "$2" "atl@127.0.0.1:$3" ;;
  scp-from) exec scp -i "$KEY" -P "$SSH_PORT" -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR "atl@127.0.0.1:$2" "$3" ;;
  *) echo "usage: vm-inner prepare|start|stop|status|wait|ssh|scp-to|scp-from"; exit 2 ;;
esac
