#!/usr/bin/env bash
# teslalinux ATL test VM: Windows/Linux host wrapper. Needs Docker. All state lives in the docker
# volume "atlvm-data"; the QEMU process runs inside the long-lived container "atlvm-host".
#
#   vm.sh up                 build the host image, start the container, prepare + boot the VM, wait for ssh
#   vm.sh ssh [cmd...]       shell / command in the Alpine guest
#   vm.sh push LOCAL REMOTE  copy a file into the guest (via the container)
#   vm.sh pull REMOTE LOCAL  copy a file out of the guest
#   vm.sh provision          run guest-provision.sh in the guest (ATL + compositor + toolchain)
#   vm.sh stop | start | status | destroy
# On macOS (and ATL_VM_REMOTE unset) every subcommand goes to vm-mac.sh next to this script: the
# guest runs locally under QEMU/HVF there, so callers (hostbuild.sh, cycle.sh, ...) use vm.sh everywhere.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [[ "$(uname -s)" == Darwin && -z "${ATL_VM_REMOTE:-}" ]]; then
  exec bash "$HERE/vm-mac.sh" "$@"
fi
C=atlvm-host
# Host paths for docker (Windows form under Git Bash); container paths stay POSIX.
HERE_W="$(cygpath -m "$HERE" 2>/dev/null || echo "$HERE")"
export MSYS_NO_PATHCONV=1
# ATL_VM_REMOTE=user@mac: the guest runs on an Apple Silicon Mac under vm-mac.sh (HVF, no emulation);
# every command below is forwarded there over ssh. Unset: the local Docker/QEMU-TCG setup.
REMOTE="${ATL_VM_REMOTE:-}"
RMAC="${ATL_VM_REMOTE_SCRIPT:-\$HOME/teslalinux-vm/vm-mac.sh}"
rq() { printf '%q ' "$@"; }   # quote args for the remote shell
if [[ -n "$REMOTE" ]]; then
  case "${1:-}" in
    up|start) ssh -o BatchMode=yes "$REMOTE" "$RMAC start && $RMAC wait" ;;
    stop|status|wait|console|provision) ssh -o BatchMode=yes "$REMOTE" "$RMAC $(rq "$@")" ;;
    ssh)   shift; ssh -o BatchMode=yes "$REMOTE" "$RMAC ssh $(rq "$@")" ;;
    shell) ssh -t "$REMOTE" "$RMAC ssh" ;;
    push)  scp -q -o BatchMode=yes "$(cygpath -m "$2" 2>/dev/null || echo "$2")" "$REMOTE:/tmp/atlvm-push.bin" && ssh -o BatchMode=yes "$REMOTE" "$RMAC push /tmp/atlvm-push.bin $(rq "$3")" ;;
    pull)  ssh -o BatchMode=yes "$REMOTE" "$RMAC pull $(rq "$2") /tmp/atlvm-pull.bin" && scp -q -o BatchMode=yes "$REMOTE:/tmp/atlvm-pull.bin" "$(cygpath -m "$3" 2>/dev/null || echo "$3")" ;;
    *) echo "remote mode supports: up|start|stop|status|wait|ssh|shell|push|pull|provision|console" >&2; exit 2 ;;
  esac
  exit $?
fi

inner() { docker exec -i "$C" vm-inner "$@"; }
ensure_container() {
  if ! docker ps --format '{{.Names}}' | grep -qx "$C"; then
    docker image inspect atlvm-host:latest >/dev/null 2>&1 || docker build -q -t atlvm-host:latest "$HERE_W/docker"
    docker rm -f "$C" >/dev/null 2>&1 || true
    docker run -d --name "$C" --cpus "${ATL_VM_CPUS:-8}" --memory 12g \
      -e ATL_VM_CPUS="${ATL_VM_CPUS:-8}" -e ATL_VM_MEM="${ATL_VM_MEM:-8G}" \
      -v atlvm-data:/vm -v "$HERE_W/seed:/seed:ro" -p 127.0.0.1:2222:2222 \
      atlvm-host:latest >/dev/null
  fi
}

case "${1:-}" in
  up)
    ensure_container
    inner prepare
    inner start
    inner wait 120
    ;;
  start)  ensure_container; inner start ;;
  stop)   inner stop ;;
  status) inner status "${2:-5}" ;;
  wait)   inner wait "${2:-120}" ;;
  ssh)    shift; docker exec -i "$C" vm-inner ssh "$@" ;;
  shell)  docker exec -it "$C" vm-inner ssh ;;
  push)   docker cp "$(cygpath -m "$2" 2>/dev/null || echo "$2")" "$C:/tmp/push.bin" && inner scp-to /tmp/push.bin "$3" ;;
  pull)   inner scp-from "$2" /tmp/pull.bin && docker cp "$C:/tmp/pull.bin" "$(cygpath -m "$3" 2>/dev/null || echo "$3")" ;;
  provision)
    docker cp "$HERE_W/guest-provision.sh" "$C:/tmp/guest-provision.sh"
    inner scp-to /tmp/guest-provision.sh /home/atl/guest-provision.sh
    docker exec -i "$C" vm-inner ssh 'chmod +x ~/guest-provision.sh && ~/guest-provision.sh'
    ;;
  console) docker exec -i "$C" tail -n "${2:-50}" /vm/console.log ;;
  destroy) docker rm -f "$C" >/dev/null 2>&1 || true; docker volume rm atlvm-data >/dev/null 2>&1 || true; echo destroyed ;;
  *) echo "usage: $0 up|start|stop|status|wait|ssh|shell|push|pull|provision|console|destroy"; exit 2 ;;
esac
