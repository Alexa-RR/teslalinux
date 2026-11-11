#!/bin/sh
# teslalinux-tls and renew.sh tests, offline: a throwaway CA (root -> intermediate), certificates and
# keys for both slots that must be accepted or rejected, installs and fetches against a local HTTPS
# server (python3), active.json switching and fallback, atomic install rollback, the revocation
# decision from a CRL, and the issuer's slot decisions. Runs anywhere with OpenSSL >= 3.4 (for
# x509 -not_before/-not_after) and a POSIX sh: on the Mac (Homebrew openssl), on Alpine, on Pi OS.
#   sh host/tls/test/run.sh        exit 0 = every case behaved (TLS_TEST_SH=dash: run the tool under dash)
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
TOOL="$HERE/../teslalinux-tls"
RENEW="$HERE/../renew.sh"
W=$(mktemp -d "${TMPDIR:-/tmp}/tls-test.XXXXXX")
SERVER=""
trap '[ -n "$SERVER" ] && { kill "$SERVER"; wait "$SERVER"; } 2>/dev/null; rm -rf "$W"' EXIT
cd "$W" || exit 1
HOST=car.example.test
pass=0; fail=0; skip=0
ok() { pass=$((pass + 1)); printf 'ok   %s\n' "$1"; }
bad() { fail=$((fail + 1)); printf 'FAIL %s\n' "$1"; [ -n "${2:-}" ] && printf '     %s\n' "$2"; }
skipped() { skip=$((skip + 1)); printf 'skip %s\n' "$1"; }
ts() { # offset seconds -> YYYYMMDDHHMMSSZ (UTC)
	t=$(( $(date +%s) + $1 ))
	date -u -r "$t" +%Y%m%d%H%M%SZ 2>/dev/null || date -u -d "@$t" +%Y%m%d%H%M%SZ
}
same() { cmp -s "$1" "$2"; }

export TLS_CAFILE="$W/ca/root.pem" TLS_BUILD_TIME_FILE="$W/none" TLS_BUNDLE_DIR="$W/none" TLS_NO_RELOAD=1 \
	TLS_PIDFILE="$W/pid" TLS_BASE_URL=""
# one Pi = one directory with its env file, TLS files and state
mkpi() {
	mkdir -p "$1"
	printf 'DEVICE_HOST=%s\nTLS_CERT=%s\nTLS_KEY=%s\n' "$HOST" "$1/tls/fullchain.pem" "$1/tls/privkey.pem" >"$1/env"
}
pi() { d=$1; shift; TESLALINUX_ENV="$d/env" TLS_STATE="$d/state.json" ${TLS_TEST_SH:-sh} "$TOOL" "$@"; }
mkpi pi0

# ---- a CA: root -> intermediate (x509_strict-clean extensions), CRL distribution point in the leaves
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
[current]
basicConstraints = critical, CA:FALSE
keyUsage = critical, digitalSignature
extendedKeyUsage = serverAuth
subjectKeyIdentifier = hash
authorityKeyIdentifier = keyid:always
crlDistributionPoints = URI:http://crl.example.test/inter.crl
subjectAltName = DNS:car.example.test, DNS:www.car.example.test, DNS:fullscreen.car.example.test, DNS:www.fullscreen.car.example.test
[standby]
basicConstraints = critical, CA:FALSE
keyUsage = critical, digitalSignature
extendedKeyUsage = serverAuth
subjectKeyIdentifier = hash
authorityKeyIdentifier = keyid:always
crlDistributionPoints = URI:http://crl.example.test/inter.crl
subjectAltName = DNS:car.example.test, DNS:www.car.example.test, DNS:fullscreen.car.example.test, DNS:www.fullscreen.car.example.test, DNS:standby.car.example.test
[client]
basicConstraints = critical, CA:FALSE
keyUsage = critical, digitalSignature
extendedKeyUsage = clientAuth
authorityKeyIdentifier = keyid:always
subjectAltName = DNS:car.example.test, DNS:www.car.example.test, DNS:fullscreen.car.example.test, DNS:www.fullscreen.car.example.test
[badsan]
basicConstraints = critical, CA:FALSE
keyUsage = critical, digitalSignature
extendedKeyUsage = serverAuth
authorityKeyIdentifier = keyid:always
subjectAltName = DNS:car.example.test, DNS:www.car.example.test, DNS:fullscreen.car.example.test, DNS:evil.example.test
[server]
basicConstraints = critical, CA:FALSE
keyUsage = critical, digitalSignature
extendedKeyUsage = serverAuth
authorityKeyIdentifier = keyid:always
subjectAltName = DNS:localhost, IP:127.0.0.1
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

# a fresh key + certificate: NAME.key, NAME.chain (leaf + intermediate), NAME.leaf
# args: name ext not_before_offset not_after_offset [ca]
mkpair() {
	c=${5:-good}
	openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out "$1.key" 2>/dev/null
	openssl req -new -key "$1.key" -subj "/CN=$HOST" -out "$1.csr" 2>/dev/null
	openssl x509 -req -in "$1.csr" -CA "ca/$c-inter.pem" -CAkey "ca/$c-inter.key" -extfile ca/ext.cnf -extensions "$2" \
		-not_before "$(ts "$3")" -not_after "$(ts "$4")" -out "$1.leaf" 2>/dev/null || return 1
	cat "$1.leaf" "ca/$c-inter.pem" >"$1.chain"
}
verify_expect() { # name want(accept|reject|defer) slot chain key [pi dir]
	out=$(pi "${6:-pi0}" verify "$3" "$4" "$5" 2>/dev/null)
	case "$out" in "$2"*) ok "$1: $out" ;; *) bad "$1: wanted $2" "$out" ;; esac
}
DAY=86400
mkpair cur1 current -3600 $((90 * DAY))
mkpair sb1 standby -3600 $((90 * DAY))
mkpair badsan badsan -3600 $((90 * DAY))
mkpair expired current -$((200 * DAY)) -$((110 * DAY))
mkpair future current $((2 * DAY)) $((92 * DAY))
mkpair untrusted current -3600 $((90 * DAY)) evil
mkpair client client -3600 $((90 * DAY))

# ---- acceptance of a pair per slot
verify_expect "current pair, trusted, exact names" accept current cur1.chain cur1.key
verify_expect "standby pair, trusted, exact names + standby." accept standby sb1.chain sb1.key
verify_expect "current pair offered as standby (names)" reject standby cur1.chain cur1.key
verify_expect "standby pair offered as current (names)" reject current sb1.chain sb1.key
verify_expect "key does not match the leaf" reject current cur1.chain sb1.key
verify_expect "extra SAN" reject current badsan.chain badsan.key
verify_expect "expired" reject current expired.chain expired.key
verify_expect "not yet valid" reject current future.chain future.key
verify_expect "untrusted chain" reject current untrusted.chain untrusted.key
verify_expect "EKU clientAuth only" reject current client.chain client.key
{ cat cur1.chain; cat cur1.key; } >withkey.chain
verify_expect "private key block inside the chain" reject current withkey.chain cur1.key
{ cat cur1.key; cat cur1.leaf; } >withcert.key
verify_expect "certificate block inside the key file" reject current cur1.chain withcert.key
head -c 100 cur1.chain >trunc.chain
verify_expect "truncated chain" reject current trunc.chain cur1.key
: >empty.pem
verify_expect "empty key" reject current cur1.chain empty.pem
verify_expect "unknown slot" reject other cur1.chain cur1.key
echo $(( $(date +%s) + DAY )) >"$W/buildtime"
out=$(TLS_BUILD_TIME_FILE="$W/buildtime" pi pi0 verify current cur1.chain cur1.key 2>/dev/null)
case "$out" in defer*) ok "clock before the build time: $out" ;; *) bad "clock check" "$out" ;; esac

# ---- init without anything trusted: the self-signed fallback
pi pi0 init 2>/dev/null || bad "init"
[ "$(cat pi0/tls/served 2>/dev/null)" = selfsigned ] && [ -s pi0/tls/fullchain.pem ] && [ -s pi0/tls/privkey.pem ] \
	&& ok "init with nothing trusted: self-signed served" || bad "init fallback" "$(cat pi0/tls/served 2>/dev/null)"
perm=$(ls -l pi0/tls/privkey.pem | cut -c1-10); [ "$perm" = "-rw-------" ] && ok "served key mode 0600" || bad "served key mode $perm"
dperm=$(ls -ld pi0/tls | cut -c1-10); [ "$dperm" = "drwx------" ] && ok "TLS dir mode 0700" || bad "TLS dir mode $dperm"
grep -q '"selfSigned": true' pi0/state.json && ok "state: self-signed" || bad "state after init" "$(cat pi0/state.json)"

# ---- init with the release's bundled pairs
mkdir -p bundle/current bundle/standby
cp cur1.chain bundle/current/fullchain.pem; cp cur1.key bundle/current/privkey.pem
cp sb1.chain bundle/standby/fullchain.pem; cp sb1.key bundle/standby/privkey.pem
printf '{"slot": "current", "reason": "test", "updated": "x"}\n' >bundle/active.json
(TLS_BUNDLE_DIR="$W/bundle"; export TLS_BUNDLE_DIR; pi pi0 init 2>/dev/null) || bad "init with bundle"
same pi0/tls/fullchain.pem cur1.chain && same pi0/tls/privkey.pem cur1.key && [ "$(cat pi0/tls/served)" = current ] \
	&& ok "bundle: current pair installed and served" || bad "bundle current served" "$(cat pi0/tls/served)"
same pi0/tls/slots/standby/fullchain.pem sb1.chain && ok "bundle: standby pair installed" || bad "bundle standby"
perm=$(ls -l pi0/tls/slots/current/privkey.pem | cut -c1-10); [ "$perm" = "-rw-------" ] && ok "slot key mode 0600" || bad "slot key mode $perm"

# ---- the newer-than rule against the installed slot (also the issuer's pre-publish check)
mkpair cur0 current -7200 $((95 * DAY))
verify_expect "older notBefore than the installed current" reject current cur0.chain cur0.key
out=$(pi pi0 verify current cur0.chain cur0.key 2>/dev/null); case "$out" in *"not newer"*) ok "  ... reason: not newer" ;; *) bad "not-newer reason" "$out" ;; esac
verify_expect "the installed pair again" "accept (unchanged)" current cur1.chain cur1.key
mkpair cur2 current -60 $((90 * DAY))
verify_expect "newer current" accept current cur2.chain cur2.key

# ---- served-slot selection: active.json and the fallback when the active slot is unusable
printf '{"slot": "standby"}\n' >pi0/tls/active.json
pi pi0 select 2>/dev/null
same pi0/tls/fullchain.pem sb1.chain && [ "$(cat pi0/tls/served)" = standby ] && ok "active.json standby: standby served" || bad "switch to standby"
cp expired.chain pi0/tls/slots/standby/fullchain.pem; cp expired.key pi0/tls/slots/standby/privkey.pem
pi pi0 select 2>/dev/null
same pi0/tls/fullchain.pem cur1.chain && [ "$(cat pi0/tls/served)" = current ] && ok "active standby expired here: current served" || bad "fallback to current"
cp sb1.key pi0/tls/slots/standby/privkey.pem; cp sb1.chain pi0/tls/slots/standby/fullchain.pem
cp cur0.key pi0/tls/slots/current/privkey.pem
printf '{"slot": "current"}\n' >pi0/tls/active.json
pi pi0 select 2>/dev/null
same pi0/tls/fullchain.pem sb1.chain && ok "active current with a mismatched key: standby served" || bad "fallback to standby" "$(cat pi0/tls/served)"
cp cur1.key pi0/tls/slots/current/privkey.pem
pi pi0 select 2>/dev/null
same pi0/tls/fullchain.pem cur1.chain && ok "current repaired: current served again" || bad "back to current"
grep -q '"slot": "current"' pi0/state.json && grep -q '"standby": {"notAfter": [1-9][0-9]*, "usable": true}' pi0/state.json \
	&& ok "state: served slot and per-slot status" || bad "state slots" "$(cat pi0/state.json)"

# ---- an install from the per-device design: a good served pair becomes the current slot
mkpi pi1; mkdir -p pi1/tls; cp cur1.chain pi1/tls/fullchain.pem; cp cur1.key pi1/tls/privkey.pem
pi pi1 init 2>/dev/null
same pi1/tls/slots/current/fullchain.pem cur1.chain && same pi1/tls/fullchain.pem cur1.chain && ok "per-device install: served pair kept as current" || bad "adopt served pair"
mkpi pi2; mkdir -p pi2/tls; cp untrusted.chain pi2/tls/fullchain.pem; cp untrusted.key pi2/tls/privkey.pem
pi pi2 init 2>/dev/null
[ ! -e pi2/tls/slots/current/fullchain.pem ] && [ "$(cat pi2/tls/served)" = selfsigned ] && ok "untrusted served pair replaced by the self-signed fallback" || bad "untrusted served pair"

# ---- fetch from a local HTTPS server
if command -v python3 >/dev/null 2>&1 && command -v curl >/dev/null 2>&1; then
	mkpair srv server -3600 $((30 * DAY))
	mkdir -p www/car/current www/car/standby
	publish() { # slot name
		cp "$2.chain" "www/car/$1/fullchain.pem"; cp "$2.key" "www/car/$1/privkey.pem"
	}
	set_active() { printf '{"slot": "%s", "reason": "test", "updated": "x"}\n' "$1" >www/car/active.json; }
	python3 -c '
import http.server, ssl, sys
class Q(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *a, **k): super().__init__(*a, directory=sys.argv[1], **k)
    def log_message(self, *a): pass
s = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Q)
c = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER); c.load_cert_chain(sys.argv[2], sys.argv[3])
s.socket = c.wrap_socket(s.socket, server_side=True)
open(sys.argv[4] + ".tmp", "w").write(str(s.server_address[1]))
import os; os.rename(sys.argv[4] + ".tmp", sys.argv[4])
s.serve_forever()' "$W/www" "$W/srv.chain" "$W/srv.key" "$W/port" 2>/dev/null &
	SERVER=$!
	i=0; while [ ! -s port ] && [ $i -lt 50 ]; do sleep 0.1; i=$((i + 1)); done
	BASE="https://127.0.0.1:$(cat port)/car"
	# (a variable assignment in front of a shell function call outlives the call in POSIX sh)
	fetch() { (TLS_BASE_URL=$BASE; export TLS_BASE_URL; pi "$1" fetch 2>/dev/null); }
	mkpi pi3
	publish current cur1; publish standby sb1; set_active current
	fetch pi3; rc=$?
	[ $rc -eq 0 ] && same pi3/tls/fullchain.pem cur1.chain && same pi3/tls/slots/standby/privkey.pem sb1.key \
		&& grep -q '"lastResult": "installed"' pi3/state.json && ok "fetch: both slots installed, current served" || bad "fetch install" "rc=$rc $(cat pi3/state.json 2>/dev/null)"
	fetch pi3; grep -q '"lastResult": "unchanged"' pi3/state.json && ok "fetch again: unchanged" || bad "fetch unchanged" "$(cat pi3/state.json)"
	set_active standby; fetch pi3
	same pi3/tls/fullchain.pem sb1.chain && grep -q '"slot": "standby"' pi3/state.json && ok "fetch: active.json -> standby served" || bad "fetch switch" "$(cat pi3/state.json)"
	set_active current; publish current cur0; fetch pi3
	same pi3/tls/slots/current/fullchain.pem cur1.chain && same pi3/tls/fullchain.pem cur1.chain && ok "fetch: older current rejected, installed one kept and served" || bad "fetch older"
	grep -q 'current: rejected' pi3/state.json && ok "  ... logged in the state" || bad "fetch older state" "$(cat pi3/state.json)"
	cp sb1.key www/car/current/privkey.pem; cp cur2.chain www/car/current/fullchain.pem
	fetch pi3; same pi3/tls/slots/current/fullchain.pem cur1.chain && ok "fetch: published chain with the wrong key rejected" || bad "fetch wrong key"
	publish current cur2; fetch pi3
	same pi3/tls/fullchain.pem cur2.chain && same pi3/tls/privkey.pem cur2.key && ok "fetch: newer current installed and served" || bad "fetch newer"
	# nginx -t fails after the swap: the served pair and the slot must both come back
	mkpair cur3 current -30 $((90 * DAY))
	publish current cur3
	mkdir -p fakebin
	printf '#!/bin/sh\ncase "$*" in *-t*) exit 1 ;; esac\nexit 0\n' >fakebin/nginx; chmod +x fakebin/nginx
	(PATH="$W/fakebin:$PATH"; TLS_NO_RELOAD=0; export PATH TLS_NO_RELOAD; fetch pi3); rc=$?
	[ $rc -ne 0 ] && same pi3/tls/fullchain.pem cur2.chain && same pi3/tls/privkey.pem cur2.key && same pi3/tls/slots/current/fullchain.pem cur2.chain \
		&& ok "nginx -t failure: served pair and slot rolled back" || bad "nginx -t rollback" "rc=$rc"
	grep -q '"lastResult": "failed"' pi3/state.json && ok "  ... state: failed" || bad "rollback state" "$(cat pi3/state.json)"
	fetch pi3; same pi3/tls/fullchain.pem cur3.chain && ok "next fetch with a working nginx installs it" || bad "fetch after rollback"
	# active slot published unusable (key mismatch), never installed here: the other one is served
	mkpi pi4; publish current cur1; cp cur1.key www/car/standby/privkey.pem; set_active standby
	fetch pi4
	same pi4/tls/fullchain.pem cur1.chain && [ ! -e pi4/tls/slots/standby/fullchain.pem ] && ok "fresh Pi, active standby broken: current served" || bad "fresh fallback" "$(cat pi4/state.json)"
	# nothing reachable: the installed pair keeps serving
	(TLS_BASE_URL="https://127.0.0.1:1/car"; export TLS_BASE_URL; pi pi3 fetch 2>/dev/null); rc=$?
	[ $rc -ne 0 ] && same pi3/tls/fullchain.pem cur3.chain && grep -q '"lastResult": "failed"' pi3/state.json && ok "download failure: installed pair kept, state failed" || bad "download failure"
	# http:// is refused
	(TLS_BASE_URL="http://127.0.0.1/car"; export TLS_BASE_URL; pi pi3 fetch 2>/dev/null) && bad "http base accepted" || ok "http base URL refused"
else
	skipped "fetch tests (need python3 and curl)"
fi

# ---- revocation: the leaf's serial in a CRL signed by its issuer
mkdir -p crl/db; : >crl/db/index.txt; echo 1000 >crl/db/crlnumber
printf '[ca]\ndefault_ca = c\n[c]\ndatabase = %s\ncrlnumber = %s\ndefault_md = sha256\ndefault_crl_days = 7\n' "$W/crl/db/index.txt" "$W/crl/db/crlnumber" >crl/ca.cnf
openssl ca -config crl/ca.cnf -keyfile ca/good-inter.key -cert ca/good-inter.pem -revoke cur1.leaf >/dev/null 2>&1
openssl ca -config crl/ca.cnf -keyfile ca/good-inter.key -cert ca/good-inter.pem -gencrl -out crl/good.pem >/dev/null 2>&1
openssl crl -in crl/good.pem -outform DER -out crl/good.der 2>/dev/null
crl_expect() { # name want chain crl
	out=$(pi pi0 crl-status "$3" "$4" 2>/dev/null)
	case "$out" in "$2"*) ok "$1: $out" ;; *) bad "$1: wanted $2" "$out" ;; esac
}
if [ -s crl/good.pem ]; then
	crl_expect "CRL lists the serial" revoked cur1.chain crl/good.pem
	crl_expect "CRL (DER) lists the serial" revoked cur1.chain crl/good.der
	crl_expect "serial not in the CRL" good cur2.chain crl/good.pem
	mkdir -p crl/edb; : >crl/edb/index.txt; echo 1000 >crl/edb/crlnumber
	sed "s|$W/crl/db|$W/crl/edb|g" crl/ca.cnf >crl/evil.cnf
	openssl ca -config crl/evil.cnf -keyfile ca/evil-inter.key -cert ca/evil-inter.pem -gencrl -out crl/evil.pem >/dev/null 2>&1
	crl_expect "CRL from another issuer" unknown cur1.chain crl/evil.pem
	crl_expect "garbage instead of a CRL" unknown cur1.chain cur1.key
else
	bad "could not make a test CRL with openssl ca"
fi

# ---- the issuer's slot decisions (renew.sh, sourced as a library)
if command -v bash >/dev/null 2>&1; then
	dec() { bash -c 'TLS_RENEW_LIB=1 . "$1"; shift; "$@"' sh "$RENEW" "$@"; }
	dec_expect() { # name want fn args...
		n=$1 want=$2; shift 2
		got=$(dec "$@" 2>&1)
		[ "$got" = "$want" ] && ok "issuer: $n -> '${got}'" || bad "issuer: $n: wanted '$want'" "got '$got'"
	}
	dec_expect "both valid: issue nothing" "" issue_order valid valid
	dec_expect "current revoked: issue current" "current" issue_order invalid valid
	dec_expect "standby invalid, current due: standby first" "standby current" issue_order due invalid
	dec_expect "both invalid: current first" "current standby" issue_order invalid invalid
	dec_expect "both due: current first" "current standby" issue_order due due
	dec_expect "current revoked, standby valid: switch to standby first" "standby" switch_first invalid valid current
	dec_expect "current revoked, standby due: switch to standby first" "standby" switch_first invalid due current
	dec_expect "current revoked, already on standby: no switch" "" switch_first invalid valid standby
	dec_expect "both invalid: no switch" "" switch_first invalid invalid current
	dec_expect "current due: no switch" "" switch_first due valid current
	dec_expect "after re-issuing current: back to current" "current" final_active valid valid
	dec_expect "current re-issue failed: stay on standby" "standby" final_active invalid valid
	dec_expect "nothing usable: current" "current" final_active invalid invalid
	DEVICE_HOST=$HOST; export DEVICE_HOST
	dec_expect "standby names" "$HOST www.$HOST fullscreen.$HOST www.fullscreen.$HOST standby.$HOST" slot_names standby
else
	skipped "issuer decision tests (need bash)"
fi

echo "--- $pass passed, $fail failed, $skip skipped"
[ "$fail" -eq 0 ]
