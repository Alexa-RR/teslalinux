#!/usr/bin/env bash
# teslalinux shared car certificate issuer: runs in .github/workflows/tls-certs.yml (GitHub-hosted
# ubuntu-24.04 runner), never on a Pi. Every car serves the same names with the same certificate and
# key, so the key is generated here, fresh for every issuance, and published next to its chain.
#
# Two independent slots (the Pi serves the one active.json names, or the other when that one is bad):
#   current   DEVICE_HOST, www., fullscreen., www.fullscreen.
#   standby   the same + standby.DEVICE_HOST (another name set: Let's Encrypt's duplicate-certificate
#             limit counts it separately, and revoking one leaves the other)
# Every run, per slot: re-issue when the slot is missing or invalid (key/names/chain/expired), revoked
# (the leaf's serial in its CRL), or due (less than 1/3 of the lifetime left, or the CA's ARI window
# has opened, which is also how a CA asks for an early renewal). When `current` is unusable, active.json
# moves to `standby` first, `current` is re-issued, then active.json moves back.
#
# Published (R2, served at PUBLIC_BASE_URL):
#   <CERT_PATH_PREFIX>/<slot>/{fullchain.pem,privkey.pem,meta.json}   what the Pis fetch
#   <CERT_PATH_PREFIX>/<slot>-previous/{...}                         the pair before
#   <CERT_PATH_PREFIX>/active.json   {"slot": "current"|"standby", "reason": ..., "updated": ...}
# Before publishing, the new pair passes the Pi's own acceptance checks (host/tls/teslalinux-tls verify).
#
# Environment (workflow env, from the `tls` GitHub Environment):
#   ACTION    check (issue only what is due) | issue-current | issue-standby | issue-both (issue now)
#   STAGING   true: Let's Encrypt staging; decisions as usual, nothing is published (a dry run)
#   secrets:  ACME_ACCOUNT_KEY (PEM), CF_DNS_API_TOKEN, R2_ACCESS_KEY_ID, R2_SECRET_ACCESS_KEY
#   vars:     DEVICE_HOST, CF_ZONE_ID, R2_ACCOUNT_ID, R2_BUCKET, CERT_PATH_PREFIX, PUBLIC_BASE_URL,
#             CF_ACCOUNT_ID, CHALLENGE_ALIAS (the validation zone _acme-challenge.* is CNAMEd to),
#             ACME_EMAIL (all three optional)
# Sourced with TLS_RENEW_LIB=1 it only defines its functions (host/tls/test/run.sh tests the decisions).
set -euo pipefail
umask 077
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# ---- pinned ACME client: acme.sh 3.1.6, by commit, plus the sha256 of the two files we run
ACME_REPO=https://github.com/acmesh-official/acme.sh
ACME_COMMIT=807da6498377ee5e0cf43a78091f46f12dc59a89   # tag 3.1.6
ACME_SH_SHA256=c7d68b021cfd6380ea83a82962abde5b484779fee0b97d38681dfa1396bbc8d7
DNS_CF_SHA256=9628ee8238cb3f9cfa1b1a985c0e9593436a3e4f8a9d65a6f775b981be9e76c8
ACME_PROD=https://acme-v02.api.letsencrypt.org/directory
ACME_STAGING=https://acme-staging-v02.api.letsencrypt.org/directory
SYSTEM_CA=${SYSTEM_CA:-/etc/ssl/certs/ca-certificates.crt}

log() { printf '[tls-certs] %s\n' "$*"; }
die() { printf '::error::%s\n' "$*"; summary "- **error**: $*"; exit 1; }
need() { for v in "$@"; do [[ -n "${!v:-}" ]] || die "$v is not set (see docs/CAR-STACK-READINESS.md, Certificate)"; done; }
summary() { printf '%s\n' "$*" >>"${GITHUB_STEP_SUMMARY:-/dev/null}"; }
note() { log "$*"; summary "- $*"; }
sha256() { sha256sum | cut -d' ' -f1; }
epoch() { date -u -d "$1" +%s; }
iso() { date -u -d "@$1" +%FT%TZ; }
leaf_of() { awk '/-----BEGIN CERTIFICATE-----/{p=1} p{print} /-----END CERTIFICATE-----/{if(p) exit}' "$1"; }
slot_names() { # SLOT: the exact name set
  local n="$DEVICE_HOST www.$DEVICE_HOST fullscreen.$DEVICE_HOST www.fullscreen.$DEVICE_HOST"
  if [[ $1 == standby ]]; then n="$n standby.$DEVICE_HOST"; fi
  echo "$n"
}
# the Pi's tool, in its check-only modes (no env file, no state, the runner's trust store)
pitool() {
  TESLALINUX_ENV=/nonexistent DEVICE_HOST="$DEVICE_HOST" TLS_CAFILE="$SYSTEM_CA" TLS_BUILD_TIME_FILE=/nonexistent \
    TLS_CERT=/nonexistent/fullchain.pem TLS_KEY=/nonexistent/privkey.pem TLS_STATE=/nonexistent/tls.json \
    TLS_NO_RELOAD=1 TLS_SLOTS_DIR="${PI_SLOTS_DIR:-/nonexistent}" TLS_BASE_URL="" sh "$HERE/teslalinux-tls" "$@"
}

# ---- decisions (pure; slot states are valid | due | invalid)
# issue_order CURRENT_STATE STANDBY_STATE: the slots to issue, the unusable ones first, current first
issue_order() {
  local out="" st
  for st in invalid due; do
    if [[ $1 == "$st" ]]; then out="$out current"; fi
    if [[ $2 == "$st" ]]; then out="$out standby"; fi
  done
  echo "${out# }"
}
# switch_first CURRENT_STATE STANDBY_STATE PUBLISHED_ACTIVE: "standby" when active.json must move to
# standby before anything is issued (current unusable, standby usable), else nothing
switch_first() {
  if [[ $1 == invalid && $2 != invalid && $3 != standby ]]; then echo standby; fi
}
# final_active CURRENT_STATE STANDBY_STATE: current whenever it is usable, else a usable standby
final_active() {
  if [[ $1 != invalid ]]; then echo current
  elif [[ $2 != invalid ]]; then echo standby
  else echo current; fi
}

# ---- published objects: R2 (authoritative) when its credentials are here, else the public URL
obj_get() { # KEY OUT -> 0 found, 1 missing, 2 error
  local err="$W/obj.err" code
  if [[ -n "${R2_ACCESS_KEY_ID:-}" && -n "${R2_SECRET_ACCESS_KEY:-}" && -n "${R2_BUCKET:-}" && -n "${R2_ACCOUNT_ID:-}" ]]; then
    if aws s3api get-object --endpoint-url "$ENDPOINT" --bucket "$R2_BUCKET" --key "$CERT_PATH_PREFIX/$1" "$2" >/dev/null 2>"$err"; then return 0; fi
    if grep -qE 'NoSuchKey|\(404\)|Not Found' "$err"; then rm -f "$2"; return 1; fi
    log "R2 read of $1 failed: $(head -c 300 "$err")"; return 2
  fi
  code=$(curl -sS --proto '=https' --max-time 30 --max-filesize 65536 -o "$2" -w '%{http_code}' "$PUBLIC_BASE_URL/$1?t=$(date +%s)" || true)
  case "$code" in 200) return 0 ;; 404) rm -f "$2"; return 1 ;; *) log "GET $PUBLIC_BASE_URL/$1: HTTP $code"; return 2 ;; esac
}
put() { # FILE KEY CONTENT_TYPE MAX_AGE
  aws s3 cp --only-show-errors --endpoint-url "$ENDPOINT" --content-type "$3" --cache-control "public, max-age=$4" \
    "$1" "s3://$R2_BUCKET/$CERT_PATH_PREFIX/$2"
}

# ---- the state of one published slot: sets STATE_<slot>, WHY_<slot>
set_state() { printf -v "STATE_$1" '%s' "$2"; printf -v "WHY_$1" '%s' "$3"; }
state_of() { local v="STATE_$1"; echo "${!v}"; }
why_of() { local v="WHY_$1"; echo "${!v}"; }

crl_check() { # SLOT CHAIN: good | revoked | unknown: ...
  local url
  url=$(openssl x509 -in "$2" -noout -ext crlDistributionPoints 2>/dev/null | sed -n 's/^[[:space:]]*URI:\(http[^[:space:]]*\).*/\1/p' | head -n1)
  if [[ -z "$url" ]]; then echo "unknown: no CRL distribution point"; return 0; fi
  if ! curl -fsS --max-time 60 --max-filesize 67108864 -o "$W/$1.crl" "$url" 2>"$W/crl.err"; then
    echo "unknown: CRL download from $url failed: $(head -c 200 "$W/crl.err")"; return 0
  fi
  pitool crl-status "$2" "$W/$1.crl"
}

ari_start() { # CHAIN: the start of the CA's suggested renewal window (RFC 9773), or nothing
  local ari leaf aki serial cert_id
  ari=$(curl -fsS --max-time 20 "$ACME_PROD" | jq -r '.renewalInfo // empty' || true)
  [[ -n "$ari" ]] || return 0
  leaf="$W/ari-leaf.pem"; leaf_of "$1" >"$leaf"
  aki=$(openssl x509 -in "$leaf" -noout -ext authorityKeyIdentifier | tail -n +2 | sed 's/keyid://I' | tr -dc '0-9A-Fa-f')
  serial=$(openssl x509 -in "$leaf" -noout -serial | cut -d= -f2)
  if (( ${#serial} % 2 )); then serial="0$serial"; fi
  # a DER INTEGER with the high bit set carries a leading zero byte
  if [[ "${serial:0:1}" =~ [89A-Fa-f] ]]; then serial="00$serial"; fi
  b64u() { xxd -r -p | base64 -w0 | tr '+/' '-_' | tr -d '='; }
  cert_id="$(printf '%s' "$aki" | b64u).$(printf '%s' "$serial" | b64u)"
  curl -fsS --max-time 20 "$ari/$cert_id" | jq -r '[.suggestedWindow.start // "", .explanationURL // ""] | join(" ")' || true
}

assess_slot() { # SLOT
  local s=$1 d="$W/pub/$1" verdict crl nb na now ari start expl
  if [[ ! -s "$d/fullchain.pem" || ! -s "$d/privkey.pem" ]]; then set_state "$s" invalid "not published"; return; fi
  if ! verdict=$(pitool verify "$s" "$d/fullchain.pem" "$d/privkey.pem" 2>/dev/null); then
    set_state "$s" invalid "fails the Pi's checks: ${verdict#reject: }"; return
  fi
  leaf_of "$d/fullchain.pem" >"$d/leaf.pem"
  crl=$(crl_check "$s" "$d/fullchain.pem")
  case "$crl" in
    revoked) set_state "$s" invalid "revoked (serial $(openssl x509 -in "$d/leaf.pem" -noout -serial | cut -d= -f2) is in its CRL)"; return ;;
    good) ;;
    *) note "$s: revocation status $crl" ;;
  esac
  nb=$(epoch "$(openssl x509 -in "$d/leaf.pem" -noout -startdate | cut -d= -f2)")
  na=$(epoch "$(openssl x509 -in "$d/leaf.pem" -noout -enddate | cut -d= -f2)")
  now=$(date +%s)
  if (( na - now < (na - nb) / 3 )); then set_state "$s" due "less than a third of the lifetime left (notAfter $(iso "$na"))"; return; fi
  ari=$(ari_start "$d/fullchain.pem"); start=${ari%% *}; expl=${ari#* }
  if [[ -n "$start" ]] && (( now >= $(epoch "$start") )); then
    set_state "$s" due "the CA's renewal window opened at $start${expl:+ ($expl)}"; return
  fi
  set_state "$s" valid "valid until $(iso "$na"), CRL ${crl%%:*}, ARI window from ${start:-unknown}"
}

# ---- acme.sh, pinned and verified, only the files we use (set up on the first issuance)
ACME_READY=0
acme_setup() {
  [[ $ACME_READY == 1 ]] && return 0
  git init -q "$W/acme-src"
  git -C "$W/acme-src" fetch -q --depth 1 "$ACME_REPO" "$ACME_COMMIT"
  git -C "$W/acme-src" checkout -q FETCH_HEAD
  [[ "$(git -C "$W/acme-src" rev-parse HEAD)" == "$ACME_COMMIT" ]] || die "acme.sh commit mismatch"
  echo "$ACME_SH_SHA256  $W/acme-src/acme.sh" | sha256sum -c --quiet || die "acme.sh sha256 mismatch"
  echo "$DNS_CF_SHA256  $W/acme-src/dnsapi/dns_cf.sh" | sha256sum -c --quiet || die "dns_cf.sh sha256 mismatch"
  install -d "$W/acme/dnsapi" "$W/home"
  install -m 0700 "$W/acme-src/acme.sh" "$W/acme/acme.sh"
  install -m 0600 "$W/acme-src/dnsapi/dns_cf.sh" "$W/acme/dnsapi/dns_cf.sh"
  rm -rf "$W/acme-src"
  CA_DIR="$W/home/ca/$(echo "$ACME_DIR" | cut -d/ -f3)/$(echo "$ACME_DIR" | cut -d/ -f4-)"
  install -d -m 0700 "$CA_DIR"
  printf '%s\n' "$ACME_ACCOUNT_KEY" >"$CA_DIR/account.key"
  openssl pkey -in "$CA_DIR/account.key" -noout 2>/dev/null || die "ACME_ACCOUNT_KEY is not a PEM private key"
  export LE_WORKING_DIR="$W/home" LE_CONFIG_HOME="$W/home" NO_TIMESTAMP=1
  export CF_Token="$CF_DNS_API_TOKEN" CF_Zone_ID="$CF_ZONE_ID"
  if [[ -n "${CF_ACCOUNT_ID:-}" ]]; then export CF_Account_ID="$CF_ACCOUNT_ID"; fi
  ACME_READY=1
}
acme() { "$W/acme/acme.sh" --home "$W/home" --config-home "$W/home" --server "$ACME_DIR" "$@"; }

# ---- one issuance: fresh key, CSR, DNS-01, the Pi's checks, publish
issue_slot() { # SLOT REASON
  local s=$1 d="$W/new-$1" cn san names nb na account
  names=$(slot_names "$s")
  note "$s: issuing ($2) for: $names"
  rm -rf "$d"; install -d -m 0700 "$d"
  # a new key every time: never reused, so a key reported compromised (it is public) never blocks us
  openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -pkeyopt ec_param_enc:named_curve -out "$d/privkey.pem" 2>/dev/null \
    || { note "$s: key generation failed"; return 1; }
  # a CN of its own per slot keeps acme.sh's per-domain state apart within this run
  cn=$DEVICE_HOST; if [[ $s == standby ]]; then cn="standby.$DEVICE_HOST"; fi
  san=$(for n in $names; do printf 'DNS:%s,' "$n"; done); san=${san%,}
  openssl req -new -key "$d/privkey.pem" -subj "/CN=$cn" -addext "subjectAltName=$san" -out "$d/csr.pem" 2>/dev/null \
    || { note "$s: CSR generation failed"; return 1; }
  acme_setup
  local args=(--signcsr --csr "$d/csr.pem" --dns dns_cf --fullchain-file "$d/fullchain.pem" --cert-file "$d/cert.pem" --force)
  if [[ -n "${CHALLENGE_ALIAS:-}" ]]; then args+=(--challenge-alias "$CHALLENGE_ALIAS"); fi
  if [[ -n "${ACME_EMAIL:-}" ]]; then args+=(--accountemail "$ACME_EMAIL"); fi
  if ! acme "${args[@]}" || [[ ! -s "$d/fullchain.pem" ]]; then note "$s: acme.sh could not issue"; return 1; fi
  account=$(sed -n "s/^ACCOUNT_URL='\(.*\)'$/\1/p" "$CA_DIR/ca.conf" 2>/dev/null | head -n1)
  log "ACME account: ${account:-unknown} (the accounturi of the CAA record)"
  ACME_ACCOUNT_URI=${account:-unknown}
  if [[ "$STAGING" == true ]]; then
    note "$s: staging certificate issued ($(openssl x509 -in "$d/fullchain.pem" -noout -enddate)); staging roots are untrusted, nothing is published"
    return 0
  fi
  # the Pi's own acceptance checks, including "newer than the published pair of this slot"
  local verdict
  if ! verdict=$(PI_SLOTS_DIR="$W/pub" pitool verify "$s" "$d/fullchain.pem" "$d/privkey.pem" 2>&1); then
    note "$s: the new pair fails the Pi's checks, not published: $verdict"; return 1
  fi
  log "$s: Pi acceptance checks: $verdict"
  leaf_of "$d/fullchain.pem" >"$d/leaf.pem"
  nb=$(epoch "$(openssl x509 -in "$d/leaf.pem" -noout -startdate | cut -d= -f2)")
  na=$(epoch "$(openssl x509 -in "$d/leaf.pem" -noout -enddate | cut -d= -f2)")
  jq -n --arg slot "$s" --arg names "$names" --argjson nb "$nb" --argjson na "$na" \
    --arg nbi "$(iso "$nb")" --arg nai "$(iso "$na")" \
    --arg serial "$(openssl x509 -in "$d/leaf.pem" -noout -serial | cut -d= -f2)" \
    --arg issuer "$(openssl x509 -in "$d/leaf.pem" -noout -issuer | cut -d= -f2-)" \
    --arg chain "$(sha256 <"$d/fullchain.pem")" \
    --arg spki "$(openssl x509 -in "$d/leaf.pem" -noout -pubkey | openssl pkey -pubin -outform DER | sha256)" \
    --arg issued "$(date -u +%FT%TZ)" \
    '{slot: $slot, names: ($names | split(" ")), serial: $serial, issuer: $issuer,
      notBefore: $nb, notAfter: $na, notBeforeIso: $nbi, notAfterIso: $nai,
      chainSha256: $chain, spkiSha256: $spki, issuedAt: $issued}' >"$d/meta.json"
  # previous <- the published pair, then the new one (key, chain, meta last)
  local p="$W/pub/$s"
  if [[ -s "$p/fullchain.pem" && -s "$p/privkey.pem" ]]; then
    put "$p/privkey.pem" "$s-previous/privkey.pem" application/x-pem-file 300 || { note "$s: R2 upload failed"; return 1; }
    put "$p/fullchain.pem" "$s-previous/fullchain.pem" application/pem-certificate-chain 300 || { note "$s: R2 upload failed"; return 1; }
    if [[ -s "$p/meta.json" ]]; then put "$p/meta.json" "$s-previous/meta.json" application/json 300 || true; fi
  fi
  put "$d/privkey.pem" "$s/privkey.pem" application/x-pem-file 300 \
    && put "$d/fullchain.pem" "$s/fullchain.pem" application/pem-certificate-chain 300 \
    && put "$d/meta.json" "$s/meta.json" application/json 300 \
    || { note "$s: R2 upload failed"; return 1; }
  install -d -m 0700 "$p"; cp "$d/privkey.pem" "$d/fullchain.pem" "$d/meta.json" "$p/"
  note "$s: published (notAfter $(iso "$na"), serial $(jq -r .serial "$d/meta.json"), chain sha256 \`$(jq -r .chainSha256 "$d/meta.json")\`)"
}

set_active() { # SLOT REASON
  jq -n --arg slot "$1" --arg reason "$2" --arg updated "$(date -u +%FT%TZ)" \
    '{slot: $slot, reason: $reason, updated: $updated}' >"$W/active.json"
  if [[ "$STAGING" == true ]]; then note "active.json: would be $1 ($2); staging publishes nothing"; ACTIVE=$1; return 0; fi
  put "$W/active.json" active.json application/json 60 || die "R2 upload of active.json failed"
  note "active.json: $1 ($2)"
  ACTIVE=$1
}

main() {
  ACTION=${ACTION:-check}
  STAGING=${STAGING:-false}
  case "$ACTION" in check|issue-current|issue-standby|issue-both) ;; *) die "ACTION must be check, issue-current, issue-standby or issue-both" ;; esac
  need DEVICE_HOST ACME_ACCOUNT_KEY CF_DNS_API_TOKEN CF_ZONE_ID PUBLIC_BASE_URL CERT_PATH_PREFIX
  if [[ "$STAGING" != true ]]; then need R2_ACCOUNT_ID R2_BUCKET R2_ACCESS_KEY_ID R2_SECRET_ACCESS_KEY; fi
  case "$PUBLIC_BASE_URL" in https://*) ;; *) die "PUBLIC_BASE_URL must be https" ;; esac
  # mask every line of the multi-line secret too (the runner masks whole values; be explicit)
  local line
  while IFS= read -r line; do if [[ -n "$line" ]]; then echo "::add-mask::$line"; fi; done <<<"$ACME_ACCOUNT_KEY"
  if [[ "$STAGING" == true ]]; then ACME_DIR=$ACME_STAGING; else ACME_DIR=$ACME_PROD; fi
  W=$(mktemp -d "${RUNNER_TEMP:-/tmp}/tls.XXXXXX")
  trap 'rm -rf "$W"' EXIT
  ENDPOINT="https://${R2_ACCOUNT_ID:-none}.r2.cloudflarestorage.com"
  export AWS_ACCESS_KEY_ID="${R2_ACCESS_KEY_ID:-}" AWS_SECRET_ACCESS_KEY="${R2_SECRET_ACCESS_KEY:-}" AWS_DEFAULT_REGION=auto \
    AWS_REQUEST_CHECKSUM_CALCULATION=when_required AWS_RESPONSE_CHECKSUM_VALIDATION=when_required AWS_PAGER=""
  summary "### Car certificates: $ACTION$([[ "$STAGING" == true ]] && echo ' (staging)')"

  # 1. what is published
  local s f rc
  for s in current standby; do
    install -d -m 0700 "$W/pub/$s"
    for f in fullchain.pem privkey.pem meta.json; do
      rc=0; obj_get "$s/$f" "$W/pub/$s/$f" || rc=$?
      if [[ $rc == 2 ]]; then die "could not read $s/$f; not deciding blindly"; fi
    done
  done
  ACTIVE=""
  rc=0; obj_get active.json "$W/active-published.json" || rc=$?
  case $rc in
    0) ACTIVE=$(jq -r '.slot // empty' "$W/active-published.json" 2>/dev/null || true) ;;
    1) ;;
    *) die "could not read active.json" ;;
  esac
  note "active.json: ${ACTIVE:-not published}"

  # 2. each slot's state
  for s in current standby; do
    assess_slot "$s"
    case "$ACTION" in
      "issue-$s"|issue-both) if [[ "$(state_of "$s")" == valid ]]; then set_state "$s" due "$ACTION requested"; fi ;;
    esac
    note "$s: $(state_of "$s"): $(why_of "$s")"
  done

  # 3. current unusable, standby usable: serve standby while current is re-issued
  if [[ -n "$(switch_first "$STATE_current" "$STATE_standby" "$ACTIVE")" ]]; then
    set_active standby "current: $WHY_current"
  fi

  # 4. issue, the unusable slots first
  local failed=0 order
  order=$(issue_order "$STATE_current" "$STATE_standby")
  if [[ -z "$order" ]]; then note "nothing to issue"; fi
  for s in $order; do
    if issue_slot "$s" "$(why_of "$s")"; then
      if [[ "$STAGING" != true ]]; then set_state "$s" valid "issued in this run"; fi
    else
      failed=1
    fi
  done

  # 5. active.json: current whenever it is usable
  local want reason
  want=$(final_active "$STATE_current" "$STATE_standby")
  if [[ $want == current ]]; then reason="current: $WHY_current"; else reason="current: $WHY_current; standby: $WHY_standby"; fi
  # staging publishes nothing, so the slots stay as they were: only a real run must end with one usable
  if [[ "$STAGING" != true && "$STATE_current" == invalid && "$STATE_standby" == invalid ]]; then note "**both slots are unusable**"; failed=1; fi
  if [[ "$want" != "$ACTIVE" ]]; then set_active "$want" "$reason"; else note "active.json stays $ACTIVE"; fi
  if [[ -n "${ACME_ACCOUNT_URI:-}" ]]; then summary "- ACME account: \`$ACME_ACCOUNT_URI\` (the accounturi of the CAA record)"; fi
  if [[ $failed == 1 ]]; then die "at least one issuance failed (see above)"; fi
  log "done"
}

if [[ -z "${TLS_RENEW_LIB:-}" ]]; then main "$@"; fi
