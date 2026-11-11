#!/usr/bin/env bash
# teslalinux cloud certificate renewer: runs in .github/workflows/tls-renew.yml (GitHub-hosted
# ubuntu-24.04 runner), never on the Pi. Signs the Pi's CSR (host/tls/device.csr.pem, the key stays on
# the Pi) through Let's Encrypt with DNS-01 on Cloudflare and publishes only public material.
#
# Decides, issues, verifies with the Pi's own acceptance checks (host/tls/teslalinux-tls verify with
# TLS_EXPECTED_SPKI = the CSR's public key), publishes:
#   <R2_BUCKET>/<CERT_PATH_PREFIX>/current/{fullchain.pem,meta.json}   what the Pi fetches
#   <R2_BUCKET>/<CERT_PATH_PREFIX>/previous/{fullchain.pem,meta.json}  the one before, for rollback
#
# Environment (workflow env, from the `tls` GitHub Environment):
#   ACTION            renew (only when due) | issue (now) | revoke (revoke the published one, then issue)
#   REVOKE_REASON     RFC 5280 code for revoke: 4 superseded (default), 5 cessationOfOperation,
#                     0 unspecified, 3 affiliationChanged. keyCompromise (1) needs the certificate's
#                     own key, which only the Pi has; Let's Encrypt refuses it from the account key.
#   STAGING           true: Let's Encrypt staging, nothing is published (dry run of the whole flow)
#   secrets: ACME_ACCOUNT_KEY (PEM), CF_DNS_API_TOKEN, R2_ACCESS_KEY_ID, R2_SECRET_ACCESS_KEY
#   vars:    DEVICE_HOST, CF_ZONE_ID, R2_ACCOUNT_ID, R2_BUCKET, CERT_PATH_PREFIX, PUBLIC_BASE_URL,
#            CHALLENGE_ALIAS (optional: the validation zone _acme-challenge.* is CNAMEd to),
#            ACME_EMAIL (optional contact)
set -euo pipefail
umask 077
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# ---- pinned ACME client: acme.sh 3.1.6, by commit, plus the sha256 of the two files we run
ACME_REPO=https://github.com/acmesh-official/acme.sh
ACME_COMMIT=807da6498377ee5e0cf43a78091f46f12dc59a89   # tag 3.1.6
ACME_SH_SHA256=c7d68b021cfd6380ea83a82962abde5b484779fee0b97d38681dfa1396bbc8d7
DNS_CF_SHA256=9628ee8238cb3f9cfa1b1a985c0e9593436a3e4f8a9d65a6f775b981be9e76c8

log() { printf '[tls-renew] %s\n' "$*"; }
die() { printf '::error::%s\n' "$*"; exit 1; }
need() { for v in "$@"; do [[ -n "${!v:-}" ]] || die "$v is not set (see docs/CAR-STACK-READINESS.md, Certificate setup)"; done; }

ACTION=${ACTION:-renew}
STAGING=${STAGING:-false}
REVOKE_REASON=${REVOKE_REASON:-4}
case "$ACTION" in renew|issue|revoke) ;; *) die "ACTION must be renew, issue or revoke" ;; esac
case "$REVOKE_REASON" in 0|3|4|5) ;; *) die "REVOKE_REASON must be 0, 3, 4 or 5" ;; esac
need DEVICE_HOST ACME_ACCOUNT_KEY CF_DNS_API_TOKEN CF_ZONE_ID PUBLIC_BASE_URL
if [[ "$STAGING" != true ]]; then need R2_ACCOUNT_ID R2_BUCKET R2_ACCESS_KEY_ID R2_SECRET_ACCESS_KEY CERT_PATH_PREFIX; fi
case "$PUBLIC_BASE_URL" in https://*) ;; *) die "PUBLIC_BASE_URL must be https" ;; esac

# mask every line of the multi-line secret too (the runner masks whole values; be explicit)
while IFS= read -r line; do if [[ -n "$line" ]]; then echo "::add-mask::$line"; fi; done <<<"$ACME_ACCOUNT_KEY"

if [[ "$STAGING" == true ]]; then
  ACME_DIR=https://acme-staging-v02.api.letsencrypt.org/directory
else
  ACME_DIR=https://acme-v02.api.letsencrypt.org/directory
fi
W=$(mktemp -d "${RUNNER_TEMP:-/tmp}/tls.XXXXXX")
trap 'rm -rf "$W"' EXIT
CSR="$HERE/device.csr.pem"
EXPECTED="$DEVICE_HOST www.$DEVICE_HOST fullscreen.$DEVICE_HOST www.fullscreen.$DEVICE_HOST"
sha256() { sha256sum | cut -d' ' -f1; }
epoch() { date -u -d "$1" +%s; }

# ---- 1. the CSR: present, self-signed correctly, P-256, exactly the four names
[[ -s "$CSR" ]] || die "no $CSR: run 'sudo teslalinux-tls export-csr' on the Pi and commit it there"
openssl req -in "$CSR" -noout -verify 2>/dev/null || die "CSR signature does not verify"
openssl req -in "$CSR" -noout -text | grep -q 'ASN1 OID: prime256v1' || die "CSR key is not ECDSA P-256"
csr_sans=$(openssl req -in "$CSR" -noout -text | awk '/Subject Alternative Name/{getline; print; exit}' | tr -d ' ' | tr ',' '\n' | sort | tr '\n' ' ')
want_sans=$(for n in $EXPECTED; do echo "DNS:$n"; done | sort | tr '\n' ' ')
[[ "$csr_sans" == "$want_sans" ]] || die "CSR names '$csr_sans' are not '$want_sans'"
CSR_SPKI=$(openssl req -in "$CSR" -noout -pubkey | openssl pkey -pubin -outform DER | sha256)
log "CSR ok: $want_sans, spki $CSR_SPKI"

# ---- 2. what is published now
HAVE_CURRENT=0
code=$(curl -sS --proto '=https' --max-time 30 --max-filesize 65536 -o "$W/current.pem" -w '%{http_code}' "$PUBLIC_BASE_URL/current/fullchain.pem" || true)
case "$code" in
  200) HAVE_CURRENT=1 ;;
  404) log "nothing published yet" ;;
  *) die "could not read $PUBLIC_BASE_URL/current/fullchain.pem (HTTP $code); not issuing blindly" ;;
esac

leaf_of() { awk '/-----BEGIN CERTIFICATE-----/{p=1} p{print} /-----END CERTIFICATE-----/{if(p) exit}' "$1"; }
if [[ $HAVE_CURRENT == 1 ]]; then
  leaf_of "$W/current.pem" >"$W/current-leaf.pem"
  CUR_SPKI=$(openssl x509 -in "$W/current-leaf.pem" -noout -pubkey | openssl pkey -pubin -outform DER | sha256)
  CUR_NB=$(epoch "$(openssl x509 -in "$W/current-leaf.pem" -noout -startdate | cut -d= -f2)")
  CUR_NA=$(epoch "$(openssl x509 -in "$W/current-leaf.pem" -noout -enddate | cut -d= -f2)")
  log "published: notAfter $(date -u -d "@$CUR_NA" +%FT%TZ), spki $CUR_SPKI"
fi

# ---- 3. acme.sh, pinned and verified, only the files we use
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
acme() { "$W/acme/acme.sh" --home "$W/home" --config-home "$W/home" --server "$ACME_DIR" "$@"; }

# ---- 4. revoke (a stolen Pi: its key is gone; the replacement Pi has made a new key and CSR)
if [[ "$ACTION" == revoke ]]; then
  [[ $HAVE_CURRENT == 1 ]] || die "nothing published to revoke"
  [[ "$CSR_SPKI" != "$CUR_SPKI" ]] || die "the committed CSR uses the key of the certificate being revoked: run 'teslalinux-tls rotate' on the new Pi and commit its CSR first"
  D="$W/home/${DEVICE_HOST}_ecc"
  install -d -m 0700 "$D"
  printf "Le_Domain='%s'\nLe_API='%s'\nLe_Keylength='ec-256'\n" "$DEVICE_HOST" "$ACME_DIR" >"$D/$DEVICE_HOST.conf"
  cp "$W/current-leaf.pem" "$D/$DEVICE_HOST.cer"
  cp "$CSR" "$D/$DEVICE_HOST.csr"
  acme --revoke -d "$DEVICE_HOST" --ecc --revoke-reason "$REVOKE_REASON" || die "revocation failed"
  rm -rf "$D"
  log "revoked the published certificate (reason $REVOKE_REASON); issuing for the new CSR"
fi

# ---- 5. is a new certificate due?
NEED=""
if [[ "$ACTION" != renew ]]; then NEED="$ACTION requested"
elif [[ $HAVE_CURRENT == 0 ]]; then NEED="nothing published"
elif [[ "$CUR_SPKI" != "$CSR_SPKI" ]]; then NEED="the CSR has a new key (rotation)"
else
  cur_sans=$(openssl x509 -in "$W/current-leaf.pem" -noout -ext subjectAltName | tail -n +2 | tr -d ' ' | tr ',' '\n' | sort | tr '\n' ' ')
  now=$(date +%s)
  if [[ "$cur_sans" != "$want_sans" ]]; then NEED="published names differ from the CSR"
  elif (( CUR_NA - now < (CUR_NA - CUR_NB) / 3 )); then NEED="less than a third of the lifetime left"
  else
    # ACME Renewal Information (RFC 9773): renew once the CA's suggested window has opened
    ari=$(curl -fsS --max-time 20 "$ACME_DIR" | jq -r '.renewalInfo // empty' || true)
    if [[ -n "$ari" ]]; then
      aki=$(openssl x509 -in "$W/current-leaf.pem" -noout -ext authorityKeyIdentifier | tail -n +2 | sed 's/keyid://I' | tr -dc '0-9A-Fa-f')
      serial=$(openssl x509 -in "$W/current-leaf.pem" -noout -serial | cut -d= -f2)
      if (( ${#serial} % 2 )); then serial="0$serial"; fi
      # a DER INTEGER with the high bit set carries a leading zero byte
      if [[ "${serial:0:1}" =~ [89A-Fa-f] ]]; then serial="00$serial"; fi
      b64u() { xxd -r -p | base64 -w0 | tr '+/' '-_' | tr -d '='; }
      cert_id="$(printf '%s' "$aki" | b64u).$(printf '%s' "$serial" | b64u)"
      start=$(curl -fsS --max-time 20 "$ari/$cert_id" | jq -r '.suggestedWindow.start // empty' || true)
      if [[ -n "$start" ]] && (( now >= $(epoch "$start") )); then NEED="ARI window opened at $start"; fi
      if [[ -z "$NEED" ]]; then log "ARI window starts ${start:-unknown}"; fi
    fi
  fi
fi
if [[ -z "$NEED" ]]; then
  log "not due: published certificate valid until $(date -u -d "@$CUR_NA" +%FT%TZ)"
  exit 0
fi
log "issuing: $NEED"

# ---- 6. issue: sign the CSR, DNS-01 on Cloudflare (optionally through the delegated validation zone)
args=(--signcsr --csr "$CSR" --dns dns_cf --fullchain-file "$W/fullchain.pem" --cert-file "$W/cert.pem" --force)
if [[ -n "${CHALLENGE_ALIAS:-}" ]]; then args+=(--challenge-alias "$CHALLENGE_ALIAS"); fi
if [[ -n "${ACME_EMAIL:-}" ]]; then args+=(--accountemail "$ACME_EMAIL"); fi
acme "${args[@]}" || die "acme.sh could not issue"
account=$(sed -n "s/^ACCOUNT_URL='\(.*\)'$/\1/p" "$CA_DIR/ca.conf" 2>/dev/null | head -n1)
log "ACME account: ${account:-unknown} (the accounturi of the CAA record)"
[[ -s "$W/fullchain.pem" ]] || die "acme.sh produced no chain"

# ---- 7. the Pi's own acceptance checks, with the CSR's public key instead of the private key
if [[ "$STAGING" == true ]]; then
  log "staging: chain issued ($(openssl x509 -in "$W/fullchain.pem" -noout -enddate)); staging roots are untrusted, nothing is published"
  exit 0
fi
cur_for_check=/nonexistent
if [[ $HAVE_CURRENT == 1 && "$ACTION" != revoke ]]; then cur_for_check="$W/current.pem"; fi
verdict=$(TESLALINUX_ENV=/nonexistent DEVICE_HOST="$DEVICE_HOST" TLS_EXPECTED_SPKI="$CSR_SPKI" TLS_KEY=/nonexistent \
  TLS_CERT="$cur_for_check" TLS_CAFILE=/etc/ssl/certs/ca-certificates.crt TLS_BUILD_TIME_FILE=/nonexistent \
  TLS_STATE="$W/state.json" TLS_PUBLIC_DIR="$W/public" sh "$HERE/teslalinux-tls" verify "$W/fullchain.pem" 2>&1) \
  || die "the new chain fails the Pi's checks: $verdict"
log "Pi acceptance checks: $verdict"

# ---- 8. publish: previous <- current, then the new current (chain first, meta last)
leaf_of "$W/fullchain.pem" >"$W/leaf.pem"
NB=$(epoch "$(openssl x509 -in "$W/leaf.pem" -noout -startdate | cut -d= -f2)")
NA=$(epoch "$(openssl x509 -in "$W/leaf.pem" -noout -enddate | cut -d= -f2)")
jq -n --argjson nb "$NB" --argjson na "$NA" \
  --arg nbi "$(date -u -d "@$NB" +%FT%TZ)" --arg nai "$(date -u -d "@$NA" +%FT%TZ)" \
  --arg chain "$(sha256 <"$W/fullchain.pem")" --arg spki "$CSR_SPKI" \
  --arg serial "$(openssl x509 -in "$W/leaf.pem" -noout -serial | cut -d= -f2)" \
  --arg issuer "$(openssl x509 -in "$W/leaf.pem" -noout -issuer | cut -d= -f2-)" \
  --arg sans "$want_sans" --arg published "$(date -u +%FT%TZ)" \
  '{notBefore: $nb, notAfter: $na, notBeforeIso: $nbi, notAfterIso: $nai, chainSha256: $chain,
    spkiSha256: $spki, serial: $serial, issuer: $issuer, sans: ($sans | split(" ") | map(select(. != ""))),
    publishedAt: $published}' >"$W/meta.json"

export AWS_ACCESS_KEY_ID="$R2_ACCESS_KEY_ID" AWS_SECRET_ACCESS_KEY="$R2_SECRET_ACCESS_KEY" AWS_DEFAULT_REGION=auto \
  AWS_REQUEST_CHECKSUM_CALCULATION=when_required AWS_RESPONSE_CHECKSUM_VALIDATION=when_required AWS_PAGER=""
S3="s3://$R2_BUCKET/$CERT_PATH_PREFIX"
ENDPOINT="https://$R2_ACCOUNT_ID.r2.cloudflarestorage.com"
put() { aws s3 cp --only-show-errors --endpoint-url "$ENDPOINT" --content-type "$3" --cache-control "public, max-age=300" "$1" "$2"; }
if [[ $HAVE_CURRENT == 1 ]]; then
  put "$W/current.pem" "$S3/previous/fullchain.pem" application/pem-certificate-chain
  if curl -fsS --max-time 20 --max-filesize 65536 -o "$W/current-meta.json" "$PUBLIC_BASE_URL/current/meta.json"; then
    put "$W/current-meta.json" "$S3/previous/meta.json" application/json
  fi
fi
put "$W/fullchain.pem" "$S3/current/fullchain.pem" application/pem-certificate-chain
put "$W/meta.json" "$S3/current/meta.json" application/json
log "published $PUBLIC_BASE_URL/current/fullchain.pem (notAfter $(date -u -d "@$NA" +%FT%TZ), chain sha256 $(sha256 <"$W/fullchain.pem"))"
{
  echo "### Car certificate"
  echo "- reason: $NEED"
  echo "- notAfter: $(date -u -d "@$NA" +%FT%TZ)"
  echo "- spki sha256: \`$CSR_SPKI\`"
  echo "- chain sha256: \`$(sha256 <"$W/fullchain.pem")\`"
  echo "- ACME account: \`${account:-unknown}\`"
} >>"${GITHUB_STEP_SUMMARY:-/dev/null}"
