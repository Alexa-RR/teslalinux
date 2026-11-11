#!/bin/sh
# teslalinux-tls acceptance tests: a throwaway CA, the device key/CSR made by `teslalinux-tls init`,
# and certificates that must be accepted or rejected. Runs anywhere with OpenSSL >= 3.4 (for
# x509 -not_before/-not_after) and a POSIX sh: on the Mac (Homebrew openssl), on Alpine, on Pi OS.
#   sh host/tls/test/run.sh        exit 0 = every case behaved (TLS_TEST_SH=dash: run the tool under dash)
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
TOOL="$HERE/../teslalinux-tls"
W=$(mktemp -d "${TMPDIR:-/tmp}/tls-test.XXXXXX")
trap 'rm -rf "$W"' EXIT
cd "$W" || exit 1
HOST=car.example.test
pass=0; fail=0
ok() { pass=$((pass + 1)); printf 'ok   %s\n' "$1"; }
bad() { fail=$((fail + 1)); printf 'FAIL %s\n' "$1"; [ -n "${2:-}" ] && printf '     %s\n' "$2"; }
ts() { # offset seconds -> YYYYMMDDHHMMSSZ (UTC)
	t=$(( $(date +%s) + $1 ))
	date -u -r "$t" +%Y%m%d%H%M%SZ 2>/dev/null || date -u -d "@$t" +%Y%m%d%H%M%SZ
}

cat >env <<EOF
DEVICE_HOST=$HOST
TLS_CERT=$W/tls/fullchain.pem
TLS_KEY=$W/tls/privkey.pem
EOF
export TESLALINUX_ENV="$W/env" TLS_PUBLIC_DIR="$W/public" TLS_STATE="$W/state/tls.json" TLS_CAFILE="$W/ca/root.pem" \
	TLS_BUILD_TIME_FILE="$W/none" TLS_BAKED_CHAIN="$W/none" TLS_NO_RELOAD=1 TLS_PIDFILE="$W/pid"
tool() { ${TLS_TEST_SH:-sh} "$TOOL" "$@"; }

# ---- a CA: root -> intermediate (x509_strict-clean extensions)
mkdir -p ca
cat >ca/ext.cnf <<'EOF'
[root]
basicConstraints = critical, CA:TRUE
keyUsage = critical, keyCertSign, cRLSign
subjectKeyIdentifier = hash
[inter]
basicConstraints = critical, CA:TRUE, pathlen:0
keyUsage = critical, keyCertSign, cRLSign
subjectKeyIdentifier = hash
authorityKeyIdentifier = keyid:always
[leaf]
basicConstraints = critical, CA:FALSE
keyUsage = critical, digitalSignature
extendedKeyUsage = serverAuth
subjectKeyIdentifier = hash
authorityKeyIdentifier = keyid:always
subjectAltName = @sans
[leaf_client]
basicConstraints = critical, CA:FALSE
keyUsage = critical, digitalSignature
extendedKeyUsage = clientAuth
authorityKeyIdentifier = keyid:always
subjectAltName = @sans
[leaf_badsan]
basicConstraints = critical, CA:FALSE
keyUsage = critical, digitalSignature
extendedKeyUsage = serverAuth
authorityKeyIdentifier = keyid:always
subjectAltName = DNS:car.example.test, DNS:www.car.example.test, DNS:fullscreen.car.example.test, DNS:evil.example.test
[sans]
DNS.1 = car.example.test
DNS.2 = www.car.example.test
DNS.3 = fullscreen.car.example.test
DNS.4 = www.fullscreen.car.example.test
EOF
mkca() { # name
	openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out "ca/$1-root.key" 2>/dev/null
	openssl req -new -key "ca/$1-root.key" -subj "/CN=$1 test root" -out "ca/$1-root.csr" 2>/dev/null
	openssl x509 -req -in "ca/$1-root.csr" -signkey "ca/$1-root.key" -days 3650 \
		-extfile ca/ext.cnf -extensions root -out "ca/$1-root.pem" 2>/dev/null
	openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out "ca/$1-inter.key" 2>/dev/null
	openssl req -new -key "ca/$1-inter.key" -subj "/CN=$1 test intermediate" -out "ca/$1-inter.csr" 2>/dev/null
	openssl x509 -req -in "ca/$1-inter.csr" -CA "ca/$1-root.pem" -CAkey "ca/$1-root.key" -days 3000 \
		-extfile ca/ext.cnf -extensions inter -out "ca/$1-inter.pem" 2>/dev/null
}
mkca good; mkca evil
cp ca/good-root.pem ca/root.pem

# leaf CSR -> chain; args: csr out ext not_before_offset not_after_offset [ca]
sign() {
	c=${6:-good}
	openssl x509 -req -in "$1" -CA "ca/$c-inter.pem" -CAkey "ca/$c-inter.key" -extfile ca/ext.cnf -extensions "$3" \
		-not_before "$(ts "$4")" -not_after "$(ts "$5")" -out "$2.leaf" 2>/dev/null || return 1
	cat "$2.leaf" "ca/$c-inter.pem" >"$2"
}
expect() { # name want(accept|reject|defer) file
	out=$(tool verify "$3" 2>/dev/null)
	case "$out" in "$2"*) ok "$1: $out" ;; *) bad "$1: wanted $2" "$out" ;; esac
}

# ---- init: key, CSR, placeholder
tool init 2>/dev/null || bad "init"
[ -s tls/privkey.pem ] && [ -s tls/device.csr.pem ] && [ -s tls/fullchain.pem ] && ok "init made key, CSR, placeholder" || bad "init outputs"
perm=$(ls -l tls/privkey.pem | cut -c1-10); [ "$perm" = "-rw-------" ] && ok "key mode 0600" || bad "key mode $perm"
dperm=$(ls -ld tls | cut -c1-10); [ "$dperm" = "drwx------" ] && ok "key dir mode 0700" || bad "key dir mode $dperm"
sans=$(openssl req -in tls/device.csr.pem -noout -text | grep -A1 "Subject Alternative Name" | tail -1 | tr -d ' ')
[ "$sans" = "DNS:$HOST,DNS:www.$HOST,DNS:fullscreen.$HOST,DNS:www.fullscreen.$HOST" ] && ok "CSR SANs exact" || bad "CSR SANs" "$sans"
openssl req -in tls/device.csr.pem -noout -text | grep -q "prime256v1\|P-256" && ok "CSR key is P-256" || bad "CSR key type"
cmp -s public/device.csr.pem tls/device.csr.pem && ok "CSR published read-only copy" || bad "public CSR"
[ "$(tool export-csr 2>/dev/null)" = "$(cat tls/device.csr.pem)" ] && ok "export-csr prints the CSR" || bad "export-csr"
grep -q '"selfSigned": true' state/tls.json && ok "state: placeholder is self-signed" || bad "state after init" "$(cat state/tls.json)"

# ---- candidates
sign tls/device.csr.pem good.pem leaf -3600 $((90 * 86400))
expect "matching key, trusted, exact SANs" accept good.pem

openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out other.key 2>/dev/null
openssl req -new -key other.key -subj "/CN=$HOST" -addext "subjectAltName=DNS:$HOST,DNS:www.$HOST,DNS:fullscreen.$HOST,DNS:www.fullscreen.$HOST" -out other.csr 2>/dev/null
sign other.csr wrongkey.pem leaf -3600 $((90 * 86400))
expect "wrong key" reject wrongkey.pem

sign tls/device.csr.pem badsan.pem leaf_badsan -3600 $((90 * 86400))
expect "extra SAN" reject badsan.pem

sign tls/device.csr.pem expired.pem leaf -$((200 * 86400)) -$((110 * 86400))
expect "expired" reject expired.pem

sign tls/device.csr.pem future.pem leaf $((2 * 86400)) $((92 * 86400))
expect "not yet valid" reject future.pem

sign tls/device.csr.pem untrusted.pem leaf -3600 $((90 * 86400)) evil
expect "untrusted chain" reject untrusted.pem

sign tls/device.csr.pem client.pem leaf_client -3600 $((90 * 86400))
expect "EKU clientAuth only" reject client.pem

{ cat good.pem; cat other.key; } >withkey.pem
expect "private key block inside" reject withkey.pem

head -c 100 good.pem >trunc.pem
expect "truncated" reject trunc.pem

: >empty.pem
expect "empty" reject empty.pem

# ---- clock not set (before the image build time): defer, keep the current certificate
echo $(( $(date +%s) + 86400 )) >"$W/buildtime"
out=$(TLS_BUILD_TIME_FILE="$W/buildtime" tool verify good.pem 2>/dev/null)
case "$out" in defer*) ok "clock before build time: $out" ;; *) bad "clock check" "$out" ;; esac

# ---- install: accept_chain + install_chain, the path `fetch` takes after its download
run_install() {
	sed '/^case "${1:-}" in$/,$d' "$TOOL" >"$W/lib.sh"
	${TLS_TEST_SH:-sh} -c '. "$1"; accept_chain "$2"; rc=$?; [ $rc -eq 0 ] || { echo "rc=$rc $REASON"; exit 1; }
		install_chain "$2" && write_state installed test && echo installed' sh "$W/lib.sh" "$1" 2>/dev/null
}
r=$(run_install good.pem); [ "$r" = installed ] && ok "install good chain" || bad "install good chain" "$r"
cmp -s tls/fullchain.pem good.pem && ok "installed file is the chain" || bad "installed file"
[ -s tls/fullchain.pem.prev ] && ok "previous (placeholder) kept as .prev" || bad "prev kept"
grep -q '"trusted": true' state/tls.json && grep -q '"lastResult": "installed"' state/tls.json && ok "state: trusted, installed" || bad "state after install" "$(cat state/tls.json)"
expect "same chain again" "accept (unchanged)" good.pem

sign tls/device.csr.pem older.pem leaf -3600 $((60 * 86400))
expect "older than the installed one" reject older.pem
sign tls/device.csr.pem newer.pem leaf -60 $((120 * 86400))
expect "newer than the installed one" accept newer.pem

# ---- nginx -t fails after the swap: the previous chain must come back
mkdir -p fakebin
printf '#!/bin/sh\ncase "$*" in *-t*) exit 1 ;; esac\nexit 0\n' >fakebin/nginx; chmod +x fakebin/nginx
before=$(openssl dgst -sha256 -r tls/fullchain.pem)
r=$(PATH="$W/fakebin:$PATH" TLS_NO_RELOAD=0 run_install newer.pem)
after=$(openssl dgst -sha256 -r tls/fullchain.pem)
[ "$r" != installed ] && [ "$before" = "$after" ] && ok "nginx -t failure: previous chain restored" || bad "nginx -t failure restore" "$r"

# ---- the cloud renewer's mode: no key, only the CSR's public key (TLS_EXPECTED_SPKI)
csr_spki=$(openssl req -in tls/device.csr.pem -noout -pubkey | openssl pkey -pubin -outform DER | openssl dgst -sha256 -r | cut -d' ' -f1)
out=$(TLS_EXPECTED_SPKI=$csr_spki TLS_KEY=/nonexistent TLS_CERT=/nonexistent tool verify newer.pem 2>/dev/null)
case "$out" in "accept (key: spki:"*) ok "CI mode: CSR public key accepted: $out" ;; *) bad "CI mode accept" "$out" ;; esac
out=$(TLS_EXPECTED_SPKI=$csr_spki TLS_KEY=/nonexistent TLS_CERT=/nonexistent tool verify wrongkey.pem 2>/dev/null)
case "$out" in reject*) ok "CI mode: other key rejected" ;; *) bad "CI mode reject" "$out" ;; esac
out=$(TLS_EXPECTED_SPKI=$csr_spki TLS_KEY=/nonexistent TLS_CERT="$W/tls/fullchain.pem" tool verify older.pem 2>/dev/null)
case "$out" in reject*"not newer"*) ok "CI mode: older than the published chain rejected" ;; *) bad "CI mode older" "$out" ;; esac

# ---- rotation: the old pair serves until the new key's chain arrives
old_spki=$(openssl pkey -in tls/privkey.pem -pubout -outform DER | openssl dgst -sha256 -r)
tool rotate >rotate.out 2>/dev/null
[ -s tls/privkey.next.pem ] && cmp -s tls/fullchain.pem good.pem && ok "rotate: next key made, current chain still served" || bad "rotate"
[ "$(tool export-csr 2>/dev/null)" = "$(cat tls/device.next.csr.pem)" ] && ok "export-csr prints the new CSR during rotation" || bad "export-csr rotation"
sign tls/device.next.csr.pem rotated.pem leaf -60 $((130 * 86400))
expect "chain for the rotation key" accept rotated.pem
r=$(run_install rotated.pem); [ "$r" = installed ] && ok "install rotated chain" || bad "install rotated chain" "$r"
new_spki=$(openssl pkey -in tls/privkey.pem -pubout -outform DER | openssl dgst -sha256 -r)
[ "$new_spki" != "$old_spki" ] && [ ! -e tls/privkey.next.pem ] && ok "rotation complete: new key in place" || bad "rotation promote"
expect "old key's chain after rotation" reject newer.pem

echo "--- $pass passed, $fail failed"
[ "$fail" -eq 0 ]
