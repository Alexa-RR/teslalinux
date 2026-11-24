"""Connect Cloudflare: the Grok setup's sign-in with the driver's Cloudflare account (OAuth 2.0).

Cloudflare's self-managed OAuth clients (developers.cloudflare.com/fundamentals/oauth): the
project's client is public in the OAuth sense (Authorization Code with PKCE S256, token endpoint
auth "none", no secret), so its id can be in the repository. The flow, all on the device:

  1. start(): a PKCE verifier and a state, kept in memory for STATE_TTL_S; the car page (or a phone
     on the hotspot, through the short link /api/grok/oauth/go?s=STATE) opens Cloudflare's consent
     page: https://dash.cloudflare.com/oauth2/auth?response_type=code&client_id=..&redirect_uri=
     https://<DEVICE_HOST>/api/grok/oauth/callback&scope=..&state=..&code_challenge=..
  2. callback(): Cloudflare sends the browser back with code and state (or error=access_denied);
     the state must be a pending one, the code is exchanged (with the verifier) for an access token
  3. the driver's email from the API's /user, the zones the grant can see; the domain
     is the one the driver named, else the only zone, else the driver picks one (the token stays
     in memory for that, at most STATE_TTL_S)
  4. cfsetup.setup() with the token (tunnel, DNS record, Access application with Managed OAuth and
     the email policy), then the token is revoked (/oauth2/revoke) whatever happened: nothing but
     what the tunnel needs is kept.

Scopes: exactly the project client's (Cloudflare's OAuth scope ids, from GET /client/v4/oauth/scopes,
for the permissions each call needs):
  teams-connectors.write  Cloudflare One Connectors Write: the tunnel (list, create, token, routes)
  access.write            Access: Apps and Policies Write: the Access application (list, create, update)
  access-acct.read        Access: Organizations, Identity Providers, and Groups Read: the team domain,
                          the login methods
  zone.read               Zone Read: the domains and their account
  dns.write               DNS Write: the hostname's record (list, create, update)
  account-settings.read   Account Settings Read: the accounts the driver allowed (GET /accounts)
  user-details.read       User Details Read: the driver's email address (GET /user), for the Access policy
"""
import base64
import hashlib
import json
import os
import secrets
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

import cfsetup

# the project's OAuth client (docs/GROK.md, "For the maintainer"); a fork sets its own
DEFAULT_CLIENT_ID = "a3ec02d7b50d47e2ec23d7772fa51e31"
OAUTH_BASE = "https://dash.cloudflare.com/oauth2"
SCOPES = ["dns.write", "zone.read", "access.write", "access-acct.read", "teams-connectors.write",
          "account-settings.read", "user-details.read"]
STATE_TTL_S = 600
CALLBACK_PATH = "/api/grok/oauth/callback"


class OAuthError(Exception):
    """The sign-in did not work; the message says why in plain words."""


def b64u(b):
    return base64.urlsafe_b64encode(b).decode().rstrip("=")


def pkce():
    """(verifier, S256 challenge); the challenge starts with a letter or digit (Cloudflare's note:
    a leading - or _ breaks the authorization URL's parsing)."""
    while True:
        v = b64u(secrets.token_bytes(48))
        c = b64u(hashlib.sha256(v.encode()).digest())
        if c[0].isalnum():
            return v, c


class Flow:
    def __init__(self, client_id=None, oauth_base=None, api_base=None, device_host=None, now=time.time):
        self.client_id = client_id if client_id is not None else DEFAULT_CLIENT_ID
        self.oauth_base = (oauth_base or OAUTH_BASE).rstrip("/")
        self.api_base = api_base or cfsetup.API
        self.device_host = device_host or "car.alexahub.dev"
        self.now = now
        self.lock = threading.Lock()
        self.pending = {}      # state -> {verifier, created, domain, hostname, token?, email?, zones?}
        self.last = None       # the last finished setup: {ok, message, steps, note, at}

    @property
    def redirect_uri(self):
        return "https://%s%s" % (self.device_host, CALLBACK_PATH)

    def available(self):
        return bool(self.client_id)

    def _expire(self):
        t = self.now()
        for s in [s for s, p in self.pending.items() if t - p["created"] > STATE_TTL_S]:
            p = self.pending.pop(s)
            if p.get("token"):
                self.revoke(p["token"])

    def start(self, domain=None, hostname=None):
        """A new sign-in: (state, the authorize URL). One at a time: a new one replaces the others."""
        if not self.available():
            raise OAuthError("This device has no Cloudflare OAuth client id. Use Advanced (an API token) instead.")
        verifier, challenge = pkce()
        state = b64u(secrets.token_bytes(18))
        with self.lock:
            self._expire()
            for p in self.pending.values():
                if p.get("token"):
                    self.revoke(p["token"])
            self.pending = {state: {"verifier": verifier, "created": self.now(), "domain": (domain or "").strip().lower() or None,
                                    "hostname": (hostname or "").strip().lower() or None}}
        q = urllib.parse.urlencode({"response_type": "code", "client_id": self.client_id, "redirect_uri": self.redirect_uri,
                                    "scope": " ".join(SCOPES), "state": state, "code_challenge": challenge,
                                    "code_challenge_method": "S256"}, quote_via=urllib.parse.quote)
        url = "%s/auth?%s" % (self.oauth_base, q)
        with self.lock:
            self.pending[state]["url"] = url
        return state, url

    def authorize_url(self, state):
        """The consent URL of a pending sign-in again (the short link a phone opens)."""
        with self.lock:
            self._expire()
            p = self.pending.get(state)
            if not p or p.get("token"):
                raise OAuthError("This sign-in link has expired. Start again in Settings > Grok.")
            return p["url"]

    def _post_form(self, url, form):
        data = urllib.parse.urlencode(form).encode()
        req = urllib.request.Request(url, data=data, method="POST", headers={
            "Content-Type": "application/x-www-form-urlencoded", "Accept": "application/json", "User-Agent": "teslalinux-grok-setup"})
        try:
            with urllib.request.urlopen(req, timeout=20) as r:
                body = r.read().decode() or "{}"
                return r.status, (json.loads(body) if body.strip().startswith("{") else {})
        except urllib.error.HTTPError as e:
            try:
                return e.code, json.loads(e.read().decode() or "{}")
            except ValueError:
                return e.code, {}
        except (urllib.error.URLError, OSError) as e:
            raise OAuthError("Cloudflare's sign-in service is not reachable (%s)." % getattr(e, "reason", e))

    def exchange(self, code, verifier):
        status, out = self._post_form(self.oauth_base + "/token", {
            "grant_type": "authorization_code", "code": code, "client_id": self.client_id,
            "redirect_uri": self.redirect_uri, "code_verifier": verifier})
        tok = out.get("access_token") if isinstance(out, dict) else None
        if status != 200 or not tok:
            raise OAuthError("Cloudflare did not accept the sign-in (%s). Start again." % (
                (out or {}).get("error_description") or (out or {}).get("error") or "HTTP %d" % status))
        return tok

    def revoke(self, token):
        """RFC 7009 revocation; best effort (the token also expires by itself)."""
        try:
            self._post_form(self.oauth_base + "/revoke", {"token": token, "token_type_hint": "access_token", "client_id": self.client_id})
            return True
        except OAuthError:
            return False

    def user(self, token):
        """The login's user details (GET /user, User Details Read): its email address."""
        try:
            return cfsetup.Client(token, self.api_base).call("GET", "/user", what="Reading who signed in") or {}
        except cfsetup.SetupError as e:
            raise OAuthError(str(e))

    def accounts(self, token):
        """The accounts the driver allowed on the consent page (GET /accounts, Account Settings Read)."""
        try:
            acc = cfsetup.Client(token, self.api_base).call("GET", "/accounts?per_page=50", what="Listing your accounts") or []
        except cfsetup.SetupError as e:
            raise OAuthError(str(e))
        return [a.get("name") or a.get("id") for a in acc if isinstance(a, dict)]

    def zones(self, token):
        c = cfsetup.Client(token, self.api_base)
        zs = c.call("GET", "/zones?per_page=50&status=active", what="Listing your domains") or []
        return sorted({z["name"] for z in zs if isinstance(z, dict) and z.get("name")})

    # ------------------------------------------------------------------ the way back

    def callback(self, params, store):
        """Cloudflare sent the browser back. Returns {"done": result} or {"choose": [zones], "state": s}.
        store(result) keeps what the tunnel needs (grok.py: teslalinux-set-grok). OAuthError otherwise."""
        state = (params.get("state") or "").strip()
        with self.lock:
            self._expire()
            p = self.pending.get(state) if state else None
            if p is None or p.get("token"):
                raise OAuthError("This sign-in has expired or was already used (they last %d minutes). "
                                 "Start again in Settings > Grok." % (STATE_TTL_S // 60))
            if params.get("error"):
                self.pending.pop(state, None)
        err = params.get("error")
        if err:
            if err == "access_denied":
                raise OAuthError("You did not allow access on Cloudflare's page, so nothing was set up.")
            raise OAuthError("Cloudflare's sign-in ended with an error: %s." % (params.get("error_description") or err))
        code = (params.get("code") or "").strip()
        if not code:
            raise OAuthError("Cloudflare sent no authorization code. Start again.")
        token = self.exchange(code, p["verifier"])
        try:
            info = self.user(token)
            email = (info.get("email") or "").strip() if isinstance(info, dict) else ""
            if not email:
                raise OAuthError("Cloudflare gave no email address for this login, so Access cannot be "
                                 "limited to you. Use Advanced (an API token) instead.")
            accounts = self.accounts(token)
            zones = self.zones(token)
            if not zones:
                raise OAuthError("This Cloudflare login sees no domain%s. Sign in with the account that has your "
                                 "domain, and allow that account on Cloudflare's page." % (
                                     " in %s" % ", ".join(accounts) if accounts else ""))
            want = p["domain"]
            if want and want not in zones:
                raise OAuthError("%s is not among the domains this login allowed (%s). Allow that domain's account "
                                 "on Cloudflare's page, or pick another domain." % (want, ", ".join(zones)))
            if not want and len(zones) > 1:
                with self.lock:
                    p.update(token=token, email=email, zones=zones, created=self.now())
                token = None  # kept for the choice, revoked after it or when it expires
                return {"choose": zones, "state": state, "email": email}
            return {"done": self._setup(state, token, want or zones[0], p["hostname"], email, store)}
        finally:
            if token:
                self.revoke(token)
                with self.lock:
                    self.pending.pop(state, None)

    def choose(self, state, zone, store):
        """The driver picked the domain after the sign-in."""
        with self.lock:
            self._expire()
            p = self.pending.get(state)
            if not p or not p.get("token"):
                raise OAuthError("This sign-in has expired. Start again in Settings > Grok.")
            self.pending.pop(state, None)
        try:
            if zone not in p["zones"]:
                raise OAuthError("%s is not one of the domains this login allowed." % zone)
            return {"done": self._setup(state, p["token"], zone, p["hostname"], p["email"], store)}
        finally:
            self.revoke(p["token"])

    def _setup(self, state, token, zone, hostname, email, store):
        try:
            r = cfsetup.setup(token, zone, hostname, email, base=self.api_base)
        except cfsetup.SetupError as e:
            raise OAuthError(str(e))
        ok, msg = store(r, email)
        if not ok:
            raise OAuthError("Cloudflare is set up, but the device did not store it: %s" % msg)
        note = None
        if "onetimepin" not in r["loginMethods"]:
            note = ("Your Zero Trust organization has no One-time PIN login: sign in with %s (or add One-time PIN in "
                    "Zero Trust > Integrations > Identity providers)." % (", ".join(r["loginMethods"]) or "its login method"))
        result = {"ok": True, "message": "Set up. Add https://%s/mcp as a custom connector on grok.com." % r["hostname"],
                  "steps": r["steps"] + ["The sign-in was revoked; the device keeps only the tunnel's token."],
                  "note": note, "at": self.now(), "hostname": r["hostname"], "email": email}
        self.last = result
        return result

    def fail(self, message):
        self.last = {"ok": False, "message": message, "at": self.now()}
