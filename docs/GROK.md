# Grok remote control

Grok, the assistant in the car, can drive Waze on the device: start navigation to a place given in
any form (an address, a map link, coordinates, ...), add a stop, choose a route, search places with
Waze's own search, change Waze's sound and map, say when you will arrive and what the next turn is,
end the drive, send Waze reports when you ask for one, and, if you allow it, open your PalGate
gates. It does
this through a remote MCP server (Model Context Protocol) that runs on the device and is reached
through a Cloudflare Tunnel on **your own domain**, behind Cloudflare Access. Nothing of the
teslalinux project is in the path, and the device is not reachable from the internet until you
turn it on.

```
Grok (grok.com connector) --HTTPS--> mcp.<your-domain>   Cloudflare Access: you sign in once (OAuth)
                                         |                   only your email address is allowed
                                         v
                                  Cloudflare Tunnel  <--outbound--  cloudflared on the device
                                                                       |
                                       services/grok 127.0.0.1:8083/mcp (checks Access's JWT)
                                                                       |
                                       Waze: ATL's car app channel (Car mode) or intent channel (Phone mode)
```

## What Grok can do

One tool per thing Waze's car screen does or shows; no tool does "anything". Car mode is Settings >
Waze interface: Car. In Phone mode only `navigate_to` (Waze's deep link), `car_position`,
`device_status` and the gates work; the others say they need Car mode.

**Driving**

| Tool | What it does |
|---|---|
| `navigate_to` | Starts a drive. The place in any form (below), or `destinations`: places in visiting order, Waze drives to the last and the ones before go in as stops as far as Waze allows. Answers with the trip, or "Waze is waiting on the route preview" (Go now, Routes, Avoid), or Waze's own refusal ("Something went wrong - try again") |
| `start_drive` | Presses the button Waze waits on: Go now on its route preview, Go now / Resume drive on its "Driving to ...?" card (never an error's OK) |
| `stop_navigation` | Ends the drive (Waze's end-drive callback) |
| `add_stop` | During a drive, adds a stop before the destination the way the car screen does: the place's card, **Add a stop** |
| `remove_stop` | Says what to do instead: Waze's car interface has no way to remove a stop |
| `route_options` | The routes Waze offers: on its route preview, or during a drive its "Alternate routes": time, distance, via, Waze's labels (tolls, traffic, ...) and which is selected |
| `choose_route` | Drives route N of `route_options` (Waze's list, the route, Go) |
| `set_avoid_tolls`, `set_avoid_ferries`, `set_avoid_freeways` | Waze's three Route options toggles (from its route preview or during a drive) |

**Places**

| Tool | What it does |
|---|---|
| `search_places` | Waze's own search around the car: name, distance, Waze's info line and a `resultId`; `addresses: true` also reads each place's card |
| `place_details` | Everything Waze's card shows for a result: address, hours, rating, prices, its buttons |
| `recent_places`, `saved_places` | Waze's own lists from its search screen, with `resultId`s (saved places only when the Waze account has some) |

**Waze's controls**

| Tool | What it does |
|---|---|
| `set_sound`, `mute_voice`, `unmute_voice` | Waze's Sound modes (Normal, Less chatty, Only alerts, Off); from the map during a drive, from Waze's settings otherwise |
| `zoom_in`, `zoom_out`, `recenter` | Waze's map buttons (`steps` presses) and center-on-car; the driver sees these, as intended |
| `answer_alert` | Presses a button of the alert Waze shows ("Still there" / "Not there" for a report ahead) |
| `report`, `report_types` | Waze reports (below) |

**Reading** (answered from what Waze last sent the car screen, without touching Waze; at once, even while another tool works)

| Tool | What it returns | Not known when |
|---|---|---|
| `trip_status` | Navigating or not, destination, arrival, time and distance left, the stops Waze counts ("1 stop", the estimates are then to the stop), the stop added by `add_stop`, the next turn | Phone mode; before Waze's car session starts |
| `next_turn` | The next turn and the one after: instruction, maneuver, road, distance, roundabout exit, lanes when Waze gives them, the current road | Waze gives no junction pictures |
| `current_speed` | Speed and the road's limit as Waze's speedometer shows them | the device reads Waze's speedometer only for the Waze versions it knows |
| `waze_alerts` | Waze's alerts and messages of the last minutes (title, subtitle, buttons) | |
| `current_screen` | What Waze's car screen shows: kind, titles, items, what it waits for, its controls, day or night, driving restrictions | |
| `car_position` | The car's GPS fix: latitude, longitude, heading, speed, accuracy, age | no fix in the last 10 s (Settings > Waze > Car's position) |
| `device_status` | The connection: LTE / Wi-Fi / Ethernet, LTE operator and signal, Wi-Fi network and signal, internet and its latency, the car hotspot's clients, CPU temperature; battery: not applicable | the settings service does not answer |

A tool that cannot do its job says why, in plain words, so Grok can tell you. Every request, reads
included, is logged on the device (Settings > Grok, "What Grok asked for"). Nothing is sent to
anyone else: Waze's car interface has no way to share a drive, and calling a place needs a phone.

### Places in any form

`navigate_to` (`destination` or `link`) and `add_stop` (`place` or `link`) take a place however it is
written, and the answer says what it was read as and how (`understood`):

- An address or a place name in any language (Hebrew too): Waze's own search near the car.
- Map links, read on the device: Waze (`waze.com/ul/h<geohash>`, `?ll=`, `?q=`, `waze://`),
  Google Maps (`/@lat,lon`, the place's `!3d…!4d…`, `?q=`, `?query=`, `?destination=`,
  `/maps/dir/…`, `/maps/search/…`, a place name), Apple Maps (`?ll=`, `?coordinate=`, `?daddr=`,
  `?q=`, `?address=`, `?sll=`), OpenStreetMap (`?mlat=&mlon=`, `#map=z/lat/lon`), Bing (`sp=point.`,
  `where1=`, `cp=lat~lon`), HERE (`/l/lat,lon`), `geo:` URIs. A link that names a place without
  coordinates goes to Waze's search with that name. Short links (`maps.app.goo.gl`, `goo.gl/maps`)
  are followed: HEAD requests only, redirects only, only to those hosts, 5 s, at most 5 hops, no
  cookies; nothing else is fetched.
- Coordinates: decimal degrees with signs or N/S/E/W, degrees and minutes, degrees minutes seconds,
  `lat lon` or `lat, lon` (longitude first only when the numbers or the letters leave no doubt).
- Plus Codes (full; a short one from the car's position), geohashes, Israeli grid (ITM, the New
  Israeli Grid) `E N` or `N E` pairs (converted on the device, within about 3 m of PROJ).
- Home, Work (on Waze's search screen once set), a saved place by name (`saved: …`), "my last
  destination" (Waze's recent places).
- what3words is not supported: it needs what3words' paid online service.

### Stops

Waze's car interface takes **one stop per drive**: its place card offers **Add a stop** during a
drive, and while a stop is set its navigation screen hides search. It has no list of stops and no
way to remove one. With a stop, Waze's trip keeps the final destination's name and its estimate is
to the stop; the car page shows Waze's "1 stop" with the destination's name after it, in both looks.

### Speed

The service keeps one connection to Waze's car host open (`services/grok/carlink.py`): the reader
keeps Waze's latest screen, trip, alerts and speedometer, so the reading tools answer in a few
milliseconds, and every tap waits on the exact screen it causes (no fixed pauses; a press Waze drops
while it changes screens is made again with the screen's current callback). What a tool learns
about Waze's screens (the report categories and kinds, the "Add a stop" button) is kept per Waze
version (`flows.json`); `report_types` answers from it at once and is read again when Waze's menu
changes.

### PalGate gates

`list_gates` lists the gates the driver shows on the map (Settings > Gates): id, name, the outputs of
a two-output gate, auto-open, and the distance when the car's and the gate's positions are known.
`open_gate` opens one (by id or name, `output` for a two-output gate) through the gates service the
car page uses (`docs/GATES.md`) and returns PalGate's answer. Opening is a physical action: it needs
Settings > Grok > **Let Grok open gates** (off by default) and `confirm: true` on every call, and
every call is in the log. Tested against the gates service's PalGate mock only.

### Reports

`report` files a report the way the driver would on Waze's car screen: it taps Waze's map
**Report** button, then the category, then the kind, all out of sight like a search. Waze sends the
report at the car's position and answers with its own notice. **Thanks!** shows on the car screen
too, and the tool returns it. A failure comes back as Waze's own error.

- The categories and kinds are read from Waze's screens (`report_types`), never from a fixed list.
  With Waze 5.27 they are Traffic (Heavy, Standstill), Police (Police, Mobile camera, Other side),
  Hazard (Car on shoulder, Construction, Object, Pothole, Broken traffic light), Crash (Crash,
  Pile-up, Other side), Blocked lane (Left, Right, Center lane), Bad weather (Icy road, Fog, Flood,
  Unplowed road, Slippery road), Animal (Animal, In lane, On shoulder), Map Issue (General map
  issue, Wrong turn, Wrong address, Wrong route, Missing roundabout, Missing road), and, while
  driving, Closure. Waze's Debug entry (it sends Waze's logs) is not offered.
- Each has an id from Waze's icon, the same in every language (`police_mobile_camera`,
  `hazard_pothole`); titles work too. The kind can be left out where it is the category itself
  (Police, Crash, Animal).
- When Waze asks one more question, its confirmation is taken from the request itself (a closure:
  "Report a closure on your route", Report). A question with several answers needs `option`;
  without it nothing is sent and the question goes back to Grok.
- Waze offers its Report button on the map while the map follows the car. If the map was moved,
  the tool taps Waze's own center-on-me first. Over another of Waze's screens or questions (such
  as "Still driving to ...?") there is no Report button, and the tool says so.
- Reports go to Waze's live service and other drivers see them, so Grok is told to report only what
  the driver asks for.

## What you need

- A domain on your Cloudflare account (its DNS on Cloudflare). Any plan.
- Zero Trust turned on in that account once: in the Cloudflare dashboard open **Zero Trust**,
  choose a team name and the Free plan. New organizations sign in with your Cloudflare account;
  to get a one-time PIN by email instead, add **One-time PIN** in Zero Trust > Integrations >
  Identity providers.
- The device online (LTE or Wi-Fi) and a Grok account.

## Set it up with Connect Cloudflare

1. On the car page: **Settings > Grok > Connect Cloudflare**. Optionally type the domain to use (empty:
   the only domain you allow, or you pick one afterwards), then **Continue with Cloudflare**. Cloudflare's
   page opens in the browser. In the car you can scan the QR code with a phone on the car's Wi-Fi
   and sign in there instead. The link works for 10 minutes.
2. Sign in to Cloudflare. On the consent page, check that the app is **Teslalinux**, choose the account
   with your domain, and allow it. It asks for exactly what the setup needs: DNS Write, Zone Read,
   Access: Apps and Policies Write, Access: Organizations, Identity Providers, and Groups Read,
   Cloudflare One Connector: cloudflared Write (the tunnel), Account Settings Read (which accounts you allowed)
   and User Details Read (your email address, for the Access rule).
3. Cloudflare sends you back to the device (`https://car.alexahub.dev/api/grok/oauth/callback`). The
   device creates (or, run again, updates) a tunnel named `teslalinux-<hostname>`, the DNS record for
   `mcp.<your-domain>`, and an Access application for it with Managed OAuth and one rule: the email
   address of the Cloudflare login. It then revokes the sign-in at once and keeps only the tunnel's
   token (root-only). If the login allows several domains, the page asks which one first.
4. Connect Grok (below).

If it fails, the page and Settings > Grok say why. Typical reasons: you declined on Cloudflare's page,
the 10 minutes ran out, the login has no domain (choose the right account on the consent page), or
the domain you typed is not in the accounts you allowed. You can also revoke the app yourself at any
time in Cloudflare under **My Profile > Access Management > Connected Applications**. The device
no longer needs it after the setup.

## Advanced: set it up with an API token

Use this when Connect Cloudflare is not available (a build without the project's OAuth client) or
you prefer a token.

1. In the Cloudflare dashboard: **My Profile > API Tokens > Create Token > Create Custom Token**.
   Give it exactly these permissions, with your account under Account Resources and your domain
   under Zone Resources:

   | | | |
   |---|---|---|
   | Account | Cloudflare Tunnel | Edit |
   | Account | Access: Apps and Policies | Edit |
   | Account | Access: Organizations, Identity Providers, and Groups | Read |
   | Zone | Zone | Read |
   | Zone | DNS | Edit |

   A short TTL (an hour) is fine: the token is needed for this step only.
2. On the car page: **Settings > Grok > Advanced: with an API token > Set up**. Enter your domain, a hostname in it (empty:
   `mcp.<your-domain>`), your email address and the token, then **Set up**.
   The device creates (or, run again, updates) a tunnel named `teslalinux-<hostname>`, the DNS
   record for the hostname, and an Access application for it with Managed OAuth and one rule:
   your email address. It stores the tunnel's token root-only, forgets the API token, and turns
   remote control on.
3. You can delete the API token in Cloudflare now.
4. Connect Grok (below).

## Set it up by hand

1. **Tunnel.** Cloudflare dashboard > **Networking > Tunnels > Create a tunnel** (cloudflared).
   Name it, then copy the token from the install command it shows (the long `eyJ...` text). Do
   not run that command: the device runs cloudflared itself.
2. **Route.** In the tunnel, add a published application route: hostname `mcp.<your-domain>`,
   service **HTTP** `127.0.0.1:8083`. (Under the route's Access settings, **Protect with Access**
   with your team name and the application's AUD tag from step 3 adds a second check in
   cloudflared; the device checks the token either way.)
3. **Access.** **Zero Trust > Access controls > Applications > Add an application >
   Self-hosted.** Domain `mcp.<your-domain>`. Add a policy: Action **Allow**, Include **Emails**
   your address. Under **Advanced settings** turn on **Managed OAuth**, and in its settings:
   - Allowed redirect URIs: `https://grok.com/*`
   - Access token lifetime: 15m; Grant session duration: 336h (two weeks)

   Save, then copy the application's **Application Audience (AUD) Tag**.
4. **Device.** Car page: **Settings > Grok > By hand**: the tunnel token, the team domain
   (`https://<team>.cloudflareaccess.com`, or just the team name), the AUD tag and the hostname.
   Then turn on **Let Grok control Waze**.

## Connect Grok

1. On grok.com: **Connectors** (grok.com/connectors) > **New Connector** > **Custom**.
2. URL: `https://mcp.<your-domain>/mcp` (Settings > Grok shows it as "Connector address").
3. Grok opens Cloudflare's sign-in: sign in with your Cloudflare account or the one-time PIN sent
   to your email. Grok then lists the six tools.
4. Use the same Grok account in the car, and ask, for example, "Navigate to the nearest gas
   station" or "When do we arrive?".

Grok gets an OAuth token from Cloudflare Access (Managed OAuth registers Grok as a client by
itself). Access renews it quietly every 15 minutes, checking your policy each time, and asks you
to sign in again after two weeks.

## Checking it

- Settings > Grok: **Tunnel: Connected (4)** means cloudflared holds its connections to
  Cloudflare; **Last request** shows Grok's last call and who made it.
- From any computer: `curl -i https://mcp.<your-domain>/mcp` answers **401** with a
  `WWW-Authenticate` header from Cloudflare Access: Access stands in front, as it should.

## Troubleshooting

- **Grok's sign-in fails with a redirect URI error:** add the redirect URI it names to the
  Access application's Managed OAuth **Allowed redirect URIs** (the automatic setup allows
  `https://grok.com/*`).
- **No one-time PIN on the sign-in page:** add One-time PIN as an identity provider (above), or
  sign in with your Cloudflare account.
- **Tunnel: Not running / Connecting:** the device has no internet, or the token is wrong (set it
  again). `journalctl -u teslalinux-cloudflared` on the device says which.
- **Grok says remote control is off:** turn it on in Settings > Grok.
- **"Cloudflare Access token refused":** the team domain or AUD tag on the device do not match the
  Access application (set up again, or copy them again by hand).

## How it is protected

- Off by default. Off means cloudflared is stopped and disabled, and the MCP endpoint refuses
  every request.
- The device makes only an outbound connection (the tunnel); no port is opened.
- Cloudflare Access lets only your email address in, through the login method you chose.
- The MCP endpoint (`services/grok/grok.py`, 127.0.0.1:8083) checks every request's
  `Cf-Access-Jwt-Assertion` itself: RS256 signature against your team's keys
  (`https://<team>.cloudflareaccess.com/cdn-cgi/access/certs`, never a key named by the token), the
  issuer, the AUD tag, expiry. A request without a valid token is refused even from the device.
  Browser `Origin` headers other than Grok's are refused.
- The hotspot cannot reach the MCP endpoint: nginx routes only the settings (`/api/grok*`, port
  8084) and never 8083.
- The tunnel token lives in `/etc/teslalinux/grok/tunnel.env` (root, 0600); systemd hands it to
  cloudflared, which runs as a user of its own. The API token of the automatic setup is never
  written anywhere.
- Grok can only use the tools above. Opening a gate also needs its own switch and `confirm: true`.

## On the device

| What | Where |
|---|---|
| The service | `teslalinux-grok.service`, `/usr/local/lib/teslalinux/grok/` (`services/grok/`) |
| MCP endpoint | `127.0.0.1:8083/mcp` (only cloudflared sends requests there) |
| Settings API | `127.0.0.1:8084`, nginx `/api/grok*` (`docs/UPSTREAM-NOTES.md`) |
| Settings | `/etc/teslalinux/grok.env` (no secrets), written by `teslalinux-set-grok` (root, sudo rule) |
| Tunnel token | `/etc/teslalinux/grok/tunnel.env` (root, 0600) |
| Tunnel | `teslalinux-cloudflared.service`, `/opt/teslalinux/bin/cloudflared` (pinned in `tools/debvm/versions.env`), metrics on `127.0.0.1:20241` |
| Action log | `/var/lib/teslalinux/grok/actions.jsonl` (the service user's, 0600) |
| What it learned of Waze's screens | `/var/lib/teslalinux/grok/flows.json`, per Waze versionCode (from the app's `installed.json`) |
| Waze | ATL's car app channel `127.0.0.1:9995` (Car mode, one connection kept open) and intent channel `127.0.0.1:9993` (Phone mode) |
| Reads | the GPS feed `127.0.0.1:9997`, the settings API's `/api/status` (`127.0.0.1:8081`), the gates service `127.0.0.1:8082` |

The MCP server speaks Streamable HTTP, statelessly: the current revision (2026-07-28: no
handshake, `server/discover`, the version in every request) and the earlier ones (2025-03-26 to
2025-11-25: `initialize`, no session id issued). Tests: `python3 services/grok/test/test_grok.py`
(local stand-ins for Cloudflare's API, Access's keys and Waze's car host; needs
`python3-cryptography`). Raspberry Pi OS only: the Alpine image has no units for it yet.

## For the maintainer: the Cloudflare OAuth client

Connect Cloudflare uses one OAuth client for the whole project, a Cloudflare self-managed OAuth
client (developers.cloudflare.com/fundamentals/oauth). It is a public client in the OAuth sense:
Authorization Code with PKCE (S256), token endpoint authentication `none`, no secret. So its id can
live in the repository: `DEFAULT_CLIENT_ID` in `services/grok/cfoauth.py`. When it is empty,
Settings > Grok shows Connect Cloudflare as unavailable and the API-token path works as before.

The project's client was made in the dashboard (**Manage Account > OAuth clients**; the `cf` CLI's
own login cannot be granted OAuth Client Write):

| Field | Value |
|---|---|
| Client ID | `a3ec02d7b50d47e2ec23d7772fa51e31` |
| Client name | Teslalinux |
| Redirect URI | `https://car.alexahub.dev/api/grok/oauth/callback` |
| Grant type | Authorization code (no refresh token: the device never keeps the sign-in) |
| Token endpoint authentication | None (public client, PKCE) |
| Visibility | Private |

Its scopes, all required, and what each is for. The device requests exactly these ids, from
`GET https://api.cloudflare.com/client/v4/oauth/scopes` (`cf iam oauth-scopes list`):

| Dashboard name | Scope id | Used for |
|---|---|---|
| DNS Write | `dns.write` | the hostname's DNS record (list, create, update) |
| Zone Read | `zone.read` | the domains the login allows, and each one's account |
| Access: Apps and Policies Write | `access.write` | the Access application with Managed OAuth and its email rule |
| Access: Organizations, Identity Providers, and Groups Read | `access-acct.read` | the team domain and the login methods |
| Cloudflare One Connector: cloudflared Write | `teams-connector-cloudflared.write` | the tunnel: list, create, its token, its route (the narrowest permission all four accept) |
| Account Settings Read | `account-settings.read` | the accounts the driver allowed (named when a login has no domain) |
| User Details Read | `user-details.read` | the driver's email address (`GET /user`) for the Access rule |

Until v0.6.1 the tunnel scope was the wider Cloudflare One Connectors Write (`teams-connectors.write`);
the client in the dashboard must carry the narrower `teams-connector-cloudflared.write` now, or a
consent asks for a scope the client does not have.

`Access: Apps and Policies Write` has an account-level id (`access.write`) and a zone-level one
(`zone-access.write`); the setup uses account-level Access applications, so the client must carry
the account-level one. `services/grok/test/test_grok.py` checks every API call against these.

A **private** client can be authorized only by members of the account that owns it, which is enough
to try it. To let every Teslalinux driver use it, make it **public**. That is permanent: it cannot be
made private again.

1. Give it a client URL on a domain the project can verify (not `github.com`), and verify that
   domain: Cloudflare shows a TXT record to add to the domain's DNS. The record's value belongs to
   that account; it never goes into this repository.
2. Give it a logo (a URL to the project's logo) and keep the name, client URL and scopes filled in.
3. **Manage Account > OAuth clients**, the client's menu, **Change Visibility** to public.

A fork that serves its car page under another `DEVICE_HOST` needs its own client with the redirect URI
`https://<DEVICE_HOST>/api/grok/oauth/callback`, and sets `TESLALINUX_GROK_CF_CLIENT_ID` for
teslalinux-grok.service (a systemd drop-in) or changes `DEFAULT_CLIENT_ID`.
