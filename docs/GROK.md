# Grok remote control

Grok, the assistant in the car, can drive Waze on the device: start navigation, search places with
Waze's own search, say when you will arrive and what the next turn is, end the drive, and send Waze
reports (police, a crash, a hazard, traffic, ...) when you ask for one. It does
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

| Tool | Car mode | Phone mode |
|---|---|---|
| `navigate_to` (an address or place name, coordinates, or a search result) | yes: Waze's car session gets the destination as Android Auto hands one over, and Waze starts driving | text and coordinates: Waze's deep link (`waze://?q=...&navigate=yes`) |
| `search_places` (Waze's own search, around the car) | yes: name, address, distance and a `resultId` for `navigate_to` | no |
| `trip_status` (destination, arrival time, distance and time left, next turn) | yes: what Waze reports to the car | no |
| `stop_navigation` | yes | no: Waze offers other apps no way to end a drive |
| `report` (a Waze report at the car's position: category and kind) | yes: through Waze's own Report button and screens | no: Waze's report screens are not reachable from outside the app |
| `report_types` (the categories and kinds Waze offers now) | yes | no |

A tool that cannot do its job says why, in plain words, so Grok can tell you. Searching works on
Waze's search screens out of sight: the car screen keeps its panels (the turn card holds still for
the few seconds it takes), but Waze's map may move to the results and back. Waze's car search
looks around the car's position only and does not give coordinates. Every request is logged on
the device (Settings > Grok, "What Grok asked for").

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

Not offered to Grok: PalGate gates (`docs/GATES.md`). They could become a separate opt-in later.

## What you need

- A domain on your Cloudflare account (its DNS on Cloudflare). Any plan.
- Zero Trust turned on in that account once: in the Cloudflare dashboard open **Zero Trust**,
  choose a team name and the Free plan. New organizations sign in with your Cloudflare account;
  to get a one-time PIN by email instead, add **One-time PIN** in Zero Trust > Integrations >
  Identity providers.
- The device online (LTE or Wi-Fi) and a Grok account.

## Set it up automatically

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
2. On the car page: **Settings > Grok > Set up**. Enter your domain, a hostname in it (empty:
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
- Grok can only use the six tools above.

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
| Waze | ATL's car app channel `127.0.0.1:9995` (Car mode) and intent channel `127.0.0.1:9993` (Phone mode) |

The MCP server speaks Streamable HTTP, statelessly: the current revision (2026-07-28: no
handshake, `server/discover`, the version in every request) and the earlier ones (2025-03-26 to
2025-11-25: `initialize`, no session id issued). Tests: `python3 services/grok/test/test_grok.py`
(local stand-ins for Cloudflare's API, Access's keys and Waze's car host; needs
`python3-cryptography`). Raspberry Pi OS only: the Alpine image has no units for it yet.
