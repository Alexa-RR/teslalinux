"""Cloudflare Access application tokens (the JWT in Cf-Access-Jwt-Assertion), checked on the origin.

Cloudflare Access signs the token it forwards to the origin with the Zero Trust organization's
keys, published at https://<team>.cloudflareaccess.com/cdn-cgi/access/certs (Cloudflare's
"Validate JWTs" page). A request is accepted only when the token:

  * is a JWS with three base64url parts and the RS256 algorithm (the only one Access uses; "none"
    and HMAC algorithms are refused, so a key can never be misused as a shared secret),
  * names a key id (kid) that the team's certificate endpoint publishes, and its signature verifies
    with that key (RSASSA-PKCS1-v1_5 with SHA-256),
  * has iss equal to the configured team domain, exactly,
  * has the configured application's AUD tag in aud (a string or a list),
  * has not expired (exp) and is already valid (nbf, iat), with a small clock leeway.

Keys come from the configured team domain only, never from a URL in the token. They are cached and
fetched again when a token names a key id the cache does not have (Access rotates its keys), at
most once a minute.
"""
import base64
import json
import threading
import time
import urllib.request

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import padding, rsa

LEEWAY_S = 60            # clock difference accepted for exp / nbf / iat
CACHE_S = 3600           # a fetched key set is used this long
REFETCH_MIN_S = 60       # an unknown kid fetches the keys again at most this often
MAX_TOKEN = 16384


class TokenError(Exception):
    """The request's token is missing or not valid for this application."""


def b64url_decode(s):
    if not isinstance(s, str) or not s or any(c not in
                                               "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_" for c in s):
        raise TokenError("not base64url")
    return base64.urlsafe_b64decode(s + "=" * (-len(s) % 4))


def _int_from_b64(s):
    return int.from_bytes(b64url_decode(s), "big")


def certs_url(team_domain):
    return team_domain.rstrip("/") + "/cdn-cgi/access/certs"


def public_keys(jwks):
    """{kid: RSA public key} from a JWK set (RSA keys for signatures only)."""
    keys = {}
    for k in (jwks or {}).get("keys", []):
        if not isinstance(k, dict) or k.get("kty") != "RSA" or not k.get("kid"):
            continue
        if k.get("use", "sig") != "sig" or k.get("alg", "RS256") != "RS256":
            continue
        try:
            keys[k["kid"]] = rsa.RSAPublicNumbers(_int_from_b64(k["e"]), _int_from_b64(k["n"])).public_key()
        except (KeyError, ValueError, TokenError):
            continue
    return keys


class KeySet:
    """The team's signing keys, fetched from its certificate endpoint and cached."""

    def __init__(self, fetch=None):
        self._fetch = fetch or self._http_fetch
        self._lock = threading.Lock()
        self._url = None
        self._keys = {}
        self._at = 0.0
        self._tried = 0.0

    @staticmethod
    def _http_fetch(url):
        req = urllib.request.Request(url, headers={"Accept": "application/json", "User-Agent": "teslalinux-grok"})
        with urllib.request.urlopen(req, timeout=10) as r:
            return json.loads(r.read(1 << 20).decode())

    def get(self, url, kid, now=None):
        now = time.time() if now is None else now
        with self._lock:
            if url != self._url:
                self._url, self._keys, self._at, self._tried = url, {}, 0.0, 0.0
            stale = now - self._at > CACHE_S
            if (kid not in self._keys or stale) and (stale or now - self._tried >= REFETCH_MIN_S):
                self._tried = now
                try:
                    keys = public_keys(self._fetch(url))
                except Exception as e:  # network, JSON: keep what we had
                    if not self._keys:
                        raise TokenError("the team's signing keys could not be read: %s" % e)
                    keys = None
                if keys is not None:
                    self._keys, self._at = keys, now
            return self._keys.get(kid)


def verify(token, team_domain, aud, keyset, now=None):
    """The token's claims when it is valid for this application; TokenError otherwise."""
    now = time.time() if now is None else now
    if not team_domain or not aud:
        raise TokenError("the Access team domain and AUD tag are not configured")
    if not token:
        raise TokenError("no Cf-Access-Jwt-Assertion header")
    if len(token) > MAX_TOKEN:
        raise TokenError("token too long")
    parts = token.split(".")
    if len(parts) != 3:
        raise TokenError("not a JWS")
    try:
        header = json.loads(b64url_decode(parts[0]))
    except (ValueError, UnicodeDecodeError) as e:
        raise TokenError("malformed token: %s" % e)
    if not isinstance(header, dict):
        raise TokenError("malformed token")
    # the algorithm first: a token that names another one is refused whatever else it holds
    if header.get("alg") != "RS256":
        raise TokenError("algorithm %r is not RS256" % header.get("alg"))
    try:
        claims = json.loads(b64url_decode(parts[1]))
        sig = b64url_decode(parts[2])
    except (ValueError, UnicodeDecodeError) as e:
        raise TokenError("malformed token: %s" % e)
    if not isinstance(claims, dict):
        raise TokenError("malformed token")
    if "crit" in header:
        raise TokenError("unsupported critical header")
    kid = header.get("kid")
    if not isinstance(kid, str) or not kid:
        raise TokenError("no key id")
    key = keyset.get(certs_url(team_domain), kid, now)
    if key is None:
        raise TokenError("unknown signing key")
    try:
        key.verify(sig, (parts[0] + "." + parts[1]).encode("ascii"), padding.PKCS1v15(), hashes.SHA256())
    except InvalidSignature:
        raise TokenError("bad signature")
    if claims.get("iss") != team_domain.rstrip("/"):
        raise TokenError("issuer %r is not the team domain" % claims.get("iss"))
    a = claims.get("aud")
    auds = a if isinstance(a, list) else [a]
    if aud not in auds:
        raise TokenError("token is for another application")
    for name in ("exp",):
        if not isinstance(claims.get(name), (int, float)):
            raise TokenError("no %s" % name)
    if now > claims["exp"] + LEEWAY_S:
        raise TokenError("token expired")
    for name in ("nbf", "iat"):
        v = claims.get(name)
        if v is not None and (not isinstance(v, (int, float)) or v > now + LEEWAY_S):
            raise TokenError("token not valid yet (%s)" % name)
    return claims
