#!/bin/sh
# teslalinux: point the repo's Alpine-VM host scripts at the Debian build VM instead.
#   eval "$(tools/debvm/hostenv-deb.sh)"
#   tools/atl-vm/hostbuild-setup.sh all     # jars + api-impl tree from the Debian guest
#   tools/atl-vm/hostbuild.sh               # api-impl.jar -> /opt/teslalinux (via ~/atl-prefix)
#   tools/atl-vm/patch-core-oj.sh           # ALPN classes into /opt/teslalinux's core-oj-hostdex.jar
# A separate work dir keeps the Alpine VM's jars (out/atl-work) apart from the glibc build's.
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
cat <<EOF
export ATL_VM_DIR="\${DEB_VM_DIR:-\$HOME/teslalinux-debvm}"
export ATL_VM_SSH_PORT="\${DEB_VM_SSH_PORT:-2223}"
export ATL_WORK="$REPO/out/atl-work-deb"
export ATL_GUEST_CORE_OJ=/opt/teslalinux/lib/java/dex/art/core-oj-hostdex.jar
EOF
