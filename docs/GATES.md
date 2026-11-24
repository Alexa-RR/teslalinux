# PalGate gates

PalGate (Pal Systems) gates and barriers open from the car page, without the phone and without
the PalGate app on the device. The device becomes a *linked device* of the driver's PalGate
account, lists its gates and opens them through PalGate's cloud API, the way the PalGate app's own
linked devices do.

```
car page (js/gates, Settings > Gates) --/api/gates*--> api.py (8081) --> teslalinux-gates (127.0.0.1:8082) --> PalGate's API
browser geolocation --> gps.py --> feed 9997 --> teslalinux-gates: auto-open near a gate
```

## Using it

* **Link:** Settings > Gates > Link. The page shows a QR code for three minutes. On the phone, the
  PalGate app's menu > Device Linking > Link a Device scans it; the page then shows the account's
  gates. The account takes two linked devices; the device uses the slot PalGate gives it.
* **Open:** the gate button among the map's own buttons (top right, in both the Phone and the Car
  interface). With one gate shown it opens that gate at once; with more it opens a list, one tap
  per gate. What happens is shown on the button or the row and in a notice at the top: Opening,
  then opened, or why it did not open (PalGate's reason, no internet, a link PalGate no longer
  accepts). A second tap within 3 seconds is the same open.
* **Choose:** Settings > Gates lists the account's gates: shown on the map or not, the order (the
  arrows), and per gate (the options button): its name on the map, which output it opens when the
  gate has two (a pedestrian door, say), Open now. Gates that PalGate opens only over Bluetooth
  from the phone are listed but not shown on the map unless the driver shows them.
* **Auto-open (off by default, per gate):** in a gate's options, *Gate's position* takes the car's
  position now (stand at the gate; it needs Settings > Waze > Car's position on, and is refused
  when the fix is worse than 50 m). Then *Open when I arrive*. It opens the gate when the car
  enters the circle (15 to 300 m, 60 by default) from outside it, at a speed between the slowest
  (3 km/h) and the fastest (50 km/h), and with *Only when driving towards it* (on by default) only
  while the distance falls. After an open it does not open again until the car has left the circle
  by a margin (half the radius, at least 20 m) and the cooldown (10 minutes by default) has passed:
  a car parked inside never opens it twice, and a device that starts inside the circle does not
  open it. A fix less accurate than 1.5 times the radius (at least 50 m) is not used. The page
  shows a notice when it fires, and why when PalGate refused. The position comes from the car's
  browser, so auto-open works while the car page is open with the position on.
* **Unlink:** Settings > Gates > Unlink (tap twice). The device asks PalGate to drop the link and
  forgets the account and the gate list; the gates' settings stay for a next link of the same
  account. The
  PalGate app also lists linked devices and can remove this one.

## Privacy and safety

* The account's phone number and session token are in `/var/lib/teslalinux/gates/account.json`,
  mode 0600 in a 0700 directory of the `tesla` user (systemd `UMask=0077`, `ProtectSystem=strict`
  with only that directory writable). They are never logged, never sent to the page (it sees the
  last three digits of the phone number), never in the repository or a release, and never leave
  the device except to PalGate's API. Gate positions are in `settings.json`, also 0600.
* An open is a real-world action: the service only opens on the driver's tap, the driver's
  auto-open setting for that gate, or Grok when the driver asks it and has turned on Settings >
  Grok > Let Grok open gates (off by default; each open also needs `confirm: true`,
  `docs/GROK.md`). Tests run against a local mock of the API
  (`services/gates/test/mock_palgate.py`), never PalGate.

## The service (`services/gates/`)

* `gates.py`: the service (stdlib HTTP on loopback 8082, systemd `teslalinux-gates.service`,
  OpenRC `host/alpine/init.d/teslalinux-gates`). Its routes are listed in its docstring; api.py
  forwards every `/api/gates*` request to it unchanged (503 while it is not running).
  `GET /api/gatesEvents?after=N&wait=S` is a long poll (at most 25 s) the page uses for auto-open
  notices and for opens made from another page.
* `palgate.py`: the API client and the request token.
* `tlqr.py`: a small QR encoder (byte mode, level M, versions 1 to 10), so the device needs no QR
  package.
* Needs `python3-cryptography` (Alpine `py3-cryptography`) for AES.
* Tests: `python3 services/gates/test/test_gates.py` (the token against published vectors, the QR
  code's structure, the auto-open rules, and the service and api.py end to end against the mock:
  linking, the list, settings, opens and refusals, the events, auto-open through a GPS feed,
  file modes, unlinking). By hand: `services/gates/test/mock_palgate.py PORT` approves a link
  a few seconds after it is asked for, then run `gates.py` with `TESLALINUX_GATES_API` pointing at it.

## The protocol, as we use it

Written for this project from the descriptions in the open-source PalGate clients (pylgate,
GPL-3.0; homebridge-palgate, MIT; ha-palgate, Apache-2.0); no code of theirs is copied, and nothing
of PalGate's (code, apps, logos) is in this repository.

* Base `https://api1.pal-es.com/v1/bt/`, JSON answers `{status: "ok"|"failed", msg, err}`;
  `401` or `errId 7101` is a token PalGate does not accept.
* Linking: the QR code is the text `{"id": "<uuid4>"}`. The device asks
  `GET un/secondary/init/<uuid>` (no token) every 2 seconds until the answer carries
  `user.id` (the phone number), `user.token` (16 bytes, hex) and `secondary` (the token type, 1 or
  2).
* Every other request carries `X-Bt-Token`, derived for the current second:
  `K1 = FA D3 25 72 81 29 | phone (6 bytes, big-endian) | 3A B4 5A 65`,
  `K2 = AES-128-ECB-decrypt(K1, session token)`,
  `B = 00 0A 0A 00 00 00 00 00 00 00 | (unix time + 2, 4 bytes big-endian) | 00 00`,
  token = upper-case hex of `(type << 4 | 1) | phone (6 bytes) | AES-128-ECB-encrypt(K2, B)`. The
  device's clock must be right (NTP, or the RTC without internet).
* `GET devices/` lists the gates (`id`, `name1`/`customName1`, `output1`/`output2`, `localOnly`, ...);
  `GET device/<id>/open-gate?openBy=100&outputNum=<1|2>` opens one; `GET secondary/unlink?secondary=<type>`
  drops the link.
