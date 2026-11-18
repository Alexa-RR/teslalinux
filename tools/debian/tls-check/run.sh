#!/bin/bash
# run.sh [--online HOST]: check ART's TLS provider (Conscrypt) under the installed dist's dalvikvm
# with TlsCheck.java: provider registration, ALPN h2 and session tickets set the way OkHttp 5 sets
# them, TLS 1.3 resumption (sequential and from parallel connections, every session complete),
# a blocked read woken when another thread closes the socket, the JCA algorithms, and api-impl's
# X509TrustManagerExtensions. Two loopback test servers (tls-server.py, with a throwaway CA) are
# started and stopped here. --online HOST also checks the default trust store
# (CONSCRYPT_SYSTEM_CACERTS, teslalinux-android-cacerts) and h2 against HOST:443.
# Prints PASS/FAIL per check and "RESULT passed/total"; exits non-zero on a failure.
#
#   ATL_PREFIX     the installed dist (default /opt/teslalinux)
#   TLSCHECK_DEX   a prebuilt TlsCheck dex (the Pi has no JDK: build it in the VM, copy it over);
#                  otherwise built here with javac and D8
#   R8_JAR         D8 for dexing (default ~/build/r8-9.5.29.jar)
#   DALVIKVM_OPTS  extra dalvikvm options, e.g. -Ximage:/opt/teslalinux/lib/art/../java/dex/art/oat/boot.art
#                  to use the boot image the installer built (tools/pios/install.sh, prewarm)
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PREFIX="${ATL_PREFIX:-/opt/teslalinux}"
R8="${R8_JAR:-$HOME/build/r8-9.5.29.jar}"
ONLINE=()
[[ "${1:-}" == --online ]] && ONLINE=(--online "${2:?--online HOST}")

work="$(mktemp -d /tmp/tls-check.XXXXXX)"
pids=()
cleanup() { for p in "${pids[@]}"; do kill "$p" 2>/dev/null || true; done; rm -rf "$work"; }
trap cleanup EXIT

if [[ -n "${TLSCHECK_DEX:-}" ]]; then
  cp "$TLSCHECK_DEX" "$work/test.dex"
else
  mkdir -p "$work/classes"
  javac -nowarn -source 8 -target 8 -Xlint:-options -bootclasspath "$PREFIX/lib/java/core-all_classes.jar" \
    -d "$work/classes" "$HERE/TlsCheck.java"
  java -cp "$R8" com.android.tools.r8.D8 --min-api 24 --lib "$PREFIX/lib/java/core-all_classes.jar" \
    --output "$work" "$work"/classes/*.class
  mv "$work/classes.dex" "$work/test.dex"
fi

# a throwaway CA and a server certificate for 127.0.0.1 (ECDSA P-256, like most of the web)
openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -keyout "$work/ca.key" -out "$work/ca.pem" \
  -days 2 -subj "/CN=tls-check CA" -addext basicConstraints=critical,CA:TRUE -addext keyUsage=critical,keyCertSign 2>/dev/null
openssl req -new -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -keyout "$work/server.key" -out "$work/server.csr" \
  -subj "/CN=localhost" 2>/dev/null
printf 'subjectAltName=DNS:localhost,IP:127.0.0.1\nbasicConstraints=CA:FALSE\nextendedKeyUsage=serverAuth\n' > "$work/ext.cnf"
openssl x509 -req -in "$work/server.csr" -CA "$work/ca.pem" -CAkey "$work/ca.key" -CAcreateserial -days 2 \
  -out "$work/server.pem" -extfile "$work/ext.cnf" 2>/dev/null
cat "$work/server.pem" "$work/ca.pem" > "$work/chain.pem"

free_port() { python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1])'; }
P1=$(free_port); P2=$(free_port)
python3 -I "$HERE/tls-server.py" "$P1" "$work/chain.pem" "$work/server.key" > "$work/s1.log" 2>&1 &
pids+=($!)
python3 -I "$HERE/tls-server.py" "$P2" "$work/chain.pem" "$work/server.key" h2,http/1.1 > "$work/s2.log" 2>&1 &
pids+=($!)
for _ in $(seq 50); do [[ $(cat "$work/s1.log" "$work/s2.log" 2>/dev/null | grep -c listening) == 2 ]] && break; sleep 0.1; done

CP="$work/test.dex"
API_IMPL="$PREFIX/lib/java/dex/android_translation_layer/api-impl.jar"
[[ -f "$API_IMPL" ]] && CP="$CP:$API_IMPL"
mkdir -p "$work/android-data"
# shellcheck disable=SC2086
env ANDROID_DATA="$work/android-data" ANDROID_LOG_TAGS='*:e' LD_LIBRARY_PATH="$PREFIX/lib" PATH="$PREFIX/bin:/usr/bin:/bin" \
  "$PREFIX/bin/dalvikvm" ${DALVIKVM_OPTS:-} -cp "$CP" TlsCheck "$work/ca.pem" 127.0.0.1 "$P1" "$P2" "${ONLINE[@]}"
