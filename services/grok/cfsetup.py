"""The Cloudflare side of Grok remote control, set up with the driver's own API token.

Given the driver's zone (a domain on their Cloudflare account), the hostname to use in it, the
email address allowed in, and an API token, this creates or updates, through the Cloudflare API
(https://api.cloudflare.com/client/v4), what docs/GROK.md otherwise has the driver click together:

  1. the zone and its account                       GET  /zones?name=ZONE
  2. the Zero Trust organization (its team domain)   GET  /accounts/A/access/organizations
     and its login methods                          GET  /accounts/A/access/identity_providers
  3. a remotely managed tunnel and its token         GET/POST /accounts/A/cfd_tunnel, GET .../token
  4. the Access application for the hostname:       GET/POST/PUT /accounts/A/access/apps
     self-hosted, Managed OAuth on (dynamic client registration for Grok's redirect URIs, short
     access tokens, a two-week grant), one Allow policy for the email address
  5. the tunnel's route: HOSTNAME -> http://127.0.0.1:8083 (this device's MCP endpoint), with
     cloudflared checking the Access token too (originRequest.access)
                                                    PUT  /accounts/A/cfd_tunnel/T/configurations
  6. the DNS record HOSTNAME CNAME T.cfargotunnel.com, proxied
                                                    GET/POST/PATCH /zones/Z/dns_records

Running it again finds and updates what it made before (by the tunnel's name, the application's
hostname, the record's name), so it is safe to repeat. The API token is used for these calls only:
it is never written anywhere and never logged. The token needs exactly these permissions (a
custom token, the account and the one zone as its resources):

  Account  Cloudflare Tunnel                                       Edit
  Account  Access: Apps and Policies                               Edit
  Account  Access: Organizations, Identity Providers, and Groups   Read
  Zone     Zone                                                    Read
  Zone     DNS                                                     Edit
"""
import json
import re
import urllib.error
import urllib.request

API = "https://api.cloudflare.com/client/v4"
ORIGIN = "http://127.0.0.1:8083"
APP_NAME = "teslalinux Grok remote control"
# Grok's OAuth redirect URIs (Managed OAuth registers clients dynamically; Access accepts only
# redirect URIs on this list). More can be given to setup() or added in the Access application.
DEFAULT_REDIRECTS = ["https://grok.com/*"]
TOKEN_PERMISSIONS = [
    ("Account", "Cloudflare Tunnel", "Edit"),
    ("Account", "Access: Apps and Policies", "Edit"),
    ("Account", "Access: Organizations, Identity Providers, and Groups", "Read"),
    ("Zone", "Zone", "Read"),
    ("Zone", "DNS", "Edit"),
]
HOST_RE = re.compile(r"^(?=.{1,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}$")
EMAIL_RE = re.compile(r"^[^@\s]{1,64}@[^@\s]{1,255}$")


class SetupError(Exception):
    """A step failed; the message says which and what to do."""


class Client:
    def __init__(self, token, base=API, timeout=20):
        if not token or not isinstance(token, str) or any(c.isspace() for c in token.strip()) or len(token) > 400:
            raise SetupError("The API token is missing or not a token.")
        self.token = token.strip()
        self.base = base.rstrip("/")
        self.timeout = timeout

    def call(self, method, path, body=None, what=""):
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(self.base + path, data=data, method=method, headers={
            "Authorization": "Bearer " + self.token,
            "Content-Type": "application/json",
            "Accept": "application/json",
            "User-Agent": "teslalinux-grok-setup",
        })
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as r:
                out = json.loads(r.read().decode() or "{}")
        except urllib.error.HTTPError as e:
            try:
                out = json.loads(e.read().decode() or "{}")
            except ValueError:
                out = {}
            raise SetupError("%s: Cloudflare answered HTTP %d%s" % (what or path, e.code, _errors(out)))
        except (urllib.error.URLError, OSError) as e:
            raise SetupError("%s: Cloudflare's API is not reachable (%s)" % (what or path, getattr(e, "reason", e)))
        except ValueError:
            raise SetupError("%s: Cloudflare's answer is not JSON" % (what or path))
        if not out.get("success", False):
            raise SetupError("%s: Cloudflare refused%s" % (what or path, _errors(out)))
        return out.get("result")


def _errors(out):
    errs = out.get("errors") if isinstance(out, dict) else None
    if not errs:
        return ""
    msgs = []
    for e in errs[:3]:
        if isinstance(e, dict):
            msgs.append("%s%s" % (e.get("message", ""), " (%s)" % e["code"] if e.get("code") else ""))
    return ": " + "; ".join(m for m in msgs if m) if msgs else ""


def tunnel_name(hostname):
    return "teslalinux-" + hostname


def _q(s):
    return urllib.request.quote(s, safe="")


def check_inputs(zone, hostname, email):
    zone = (zone or "").strip().lower().rstrip(".")
    hostname = (hostname or "").strip().lower().rstrip(".")
    email = (email or "").strip()
    if not HOST_RE.match(zone):
        raise SetupError("The domain %r is not a domain name (for example example.com)." % zone)
    if not hostname:
        hostname = "mcp." + zone
    if not HOST_RE.match(hostname) or not (hostname == zone or hostname.endswith("." + zone)):
        raise SetupError("The hostname must be in %s (for example mcp.%s)." % (zone, zone))
    if not EMAIL_RE.match(email):
        raise SetupError("The email address %r is not an email address." % email)
    return zone, hostname, email


def oauth_configuration(redirects):
    return {
        "enabled": True,
        "dynamic_client_registration": {
            "enabled": True,
            "allow_any_on_localhost": False,
            "allow_any_on_loopback": False,
            "allowed_uris": list(redirects),
        },
        # Cloudflare's recommendation for agents: short access tokens, a long grant (refreshed
        # silently, the policy checked again at every refresh)
        "grant": {"access_token_lifetime": "15m", "session_duration": "336h"},
    }


def app_body(hostname, email, redirects, existing=None):
    body = dict(existing or {})
    for k in ("id", "uid", "aud", "created_at", "updated_at", "policies"):
        body.pop(k, None)
    body.update({
        "type": "self_hosted",
        "name": body.get("name") or APP_NAME,
        "domain": hostname,
        "destinations": [{"type": "public", "uri": hostname}],
        "app_launcher_visible": False,
        "session_duration": body.get("session_duration") or "24h",
        "oauth_configuration": oauth_configuration(redirects),
        "policies": [{
            "name": "Allow " + email,
            "decision": "allow",
            "include": [{"email": {"email": email}}],
            "precedence": 1,
        }],
    })
    return body


def setup(token, zone, hostname, email, redirects=None, base=API, log=None, origin=ORIGIN):
    """Creates or updates everything; returns {teamDomain, aud, hostname, tunnelId, tunnelToken,
    appId, loginMethods, steps}. SetupError says which step failed."""
    zone, hostname, email = check_inputs(zone, hostname, email)
    redirects = [r for r in (redirects or DEFAULT_REDIRECTS) if isinstance(r, str) and r.strip()]
    if not redirects:
        redirects = list(DEFAULT_REDIRECTS)
    for r in redirects:
        if not re.match(r"^(https://[^\s/]+(/\S*)?|[a-z][a-z0-9+.-]*:/\S*)$", r):
            raise SetupError("%r is not a redirect URI." % r)
    steps = []

    def step(msg):
        steps.append(msg)
        if log:
            log(msg)

    c = Client(token, base)
    zones = c.call("GET", "/zones?name=%s" % _q(zone), what="Finding the domain")
    if not zones:
        raise SetupError("The domain %s is not in this token's Cloudflare zones. Give the token Zone: Zone Read for it." % zone)
    z = zones[0]
    zone_id, account_id = z["id"], (z.get("account") or {}).get("id")
    if not account_id:
        raise SetupError("Cloudflare did not say which account %s belongs to." % zone)
    step("Domain %s found." % zone)

    try:
        org = c.call("GET", "/accounts/%s/access/organizations" % account_id, what="Reading the Zero Trust organization")
    except SetupError as e:
        raise SetupError("%s. Set up Zero Trust once in the Cloudflare dashboard (Zero Trust, choose a team name, Free plan), "
                         "and give the token Access: Organizations, Identity Providers, and Groups Read." % e)
    auth_domain = (org or {}).get("auth_domain")
    if not auth_domain:
        raise SetupError("This account has no Zero Trust organization yet. Open Zero Trust in the Cloudflare dashboard once and choose a team name (the Free plan is enough).")
    team_domain = "https://" + auth_domain
    idps = c.call("GET", "/accounts/%s/access/identity_providers" % account_id, what="Reading the login methods") or []
    methods = sorted({i.get("type") for i in idps if isinstance(i, dict) and i.get("type")})
    step("Zero Trust team %s." % auth_domain)

    name = tunnel_name(hostname)
    tunnels = c.call("GET", "/accounts/%s/cfd_tunnel?name=%s&is_deleted=false" % (account_id, _q(name)), what="Looking for the tunnel") or []
    tunnels = [t for t in tunnels if t.get("name") == name and not t.get("deleted_at")]
    if tunnels:
        tunnel_id = tunnels[0]["id"]
        step("Tunnel %s already there." % name)
    else:
        t = c.call("POST", "/accounts/%s/cfd_tunnel" % account_id, {"name": name, "config_src": "cloudflare"}, what="Creating the tunnel")
        tunnel_id = t["id"]
        step("Tunnel %s created." % name)
    tunnel_token = c.call("GET", "/accounts/%s/cfd_tunnel/%s/token" % (account_id, tunnel_id), what="Reading the tunnel's token")
    if not isinstance(tunnel_token, str) or not tunnel_token:
        raise SetupError("Cloudflare gave no tunnel token.")

    apps = c.call("GET", "/accounts/%s/access/apps" % account_id, what="Looking for the Access application") or []
    mine = [a for a in apps if isinstance(a, dict) and (a.get("domain") or "").lower().rstrip("/") == hostname]
    if mine:
        app = c.call("PUT", "/accounts/%s/access/apps/%s" % (account_id, mine[0]["id"]),
                     app_body(hostname, email, redirects, mine[0]), what="Updating the Access application")
        step("Access application for %s updated." % hostname)
    else:
        app = c.call("POST", "/accounts/%s/access/apps" % account_id, app_body(hostname, email, redirects),
                     what="Creating the Access application")
        step("Access application for %s created (Managed OAuth on, only %s allowed)." % (hostname, email))
    aud = (app or {}).get("aud")
    if not aud:
        raise SetupError("Cloudflare gave the Access application no AUD tag.")

    team_name = auth_domain.split(".")[0]
    c.call("PUT", "/accounts/%s/cfd_tunnel/%s/configurations" % (account_id, tunnel_id), {"config": {"ingress": [
        {"hostname": hostname, "service": origin,
         "originRequest": {"access": {"required": True, "teamName": team_name, "audTag": [aud]}}},
        {"service": "http_status:404"},
    ]}}, what="Routing the tunnel")
    step("Tunnel routes %s to this device." % hostname)

    target = "%s.cfargotunnel.com" % tunnel_id
    recs = c.call("GET", "/zones/%s/dns_records?name=%s" % (zone_id, _q(hostname)), what="Looking for the DNS record") or []
    recs = [r for r in recs if (r.get("name") or "").lower() == hostname]
    rec = {"type": "CNAME", "name": hostname, "content": target, "proxied": True, "comment": APP_NAME}
    if not recs:
        c.call("POST", "/zones/%s/dns_records" % zone_id, rec, what="Creating the DNS record")
        step("DNS record %s created." % hostname)
    else:
        r = recs[0]
        if r.get("type") != "CNAME" or not (r.get("content") or "").endswith(".cfargotunnel.com"):
            raise SetupError("%s already has a DNS record (%s %s) that is not a tunnel's. Choose another hostname, or delete that record first."
                             % (hostname, r.get("type"), r.get("content")))
        if r.get("content") != target or not r.get("proxied"):
            c.call("PATCH", "/zones/%s/dns_records/%s" % (zone_id, r["id"]), rec, what="Updating the DNS record")
            step("DNS record %s now points at the tunnel." % hostname)
        else:
            step("DNS record %s already points at the tunnel." % hostname)

    return {"teamDomain": team_domain, "aud": aud, "hostname": hostname, "tunnelId": tunnel_id,
            "tunnelToken": tunnel_token, "appId": app.get("id"), "loginMethods": methods, "steps": steps}
