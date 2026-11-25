# The car's own link (Settings > Tesla)

The device can read the Tesla it sits in over Bluetooth, as a **read-only key** of the car. With
it, Waze follows the route the driver starts on the car's own map, and Waze can use the car's own GPS
as its position instead of the car browser's.

## What it reads, and what it can do

- **The key's role is `vehicle_monitor`.** The car lets it read the car's state; it cannot unlock,
  open, start or drive the car, or change any setting. The car lists it on its Controls > Locks
  screen, where the driver can remove it at any time.
- **What is read:** the drive state (gear, speed, the active route: destination name, its
  coordinates, minutes and distance to it, traffic delay, energy at arrival) every 2 s, and the
  location state (the car's GPS position and heading) every second while the car's position is used
  for Waze, every 5 s otherwise.
- **Nothing goes over the internet.** The link is Bluetooth LE between the car and the device;
  Tesla's servers and the driver's Tesla account are not involved.

## Setting it up

1. Settings > Tesla > Car's VIN: the 17 characters from the car's Controls > Software (Additional
   Vehicle Information) or the bottom of the windshield. The device makes its key the first time.
2. Sit in the car with it on, and press **Pair**. The car shows its add-key prompt: tap the key
   card on the reader behind the cupholders and confirm on the car's screen (the prompt waits about
   30 seconds). The link shows **Connected over Bluetooth** a few seconds later.
3. **Waze follows the car's route** is on by default. **Use the car's GPS** is off by default.

4. On the car, Controls > Locks lists the new key without a name ("Unknown key"): an add-key
   request carries the key, its role and a form factor, nothing more. Tap the pencil next to it and
   name it Teslalinux.

**Forget the car** removes the VIN and the key from the device. The car keeps listing the key
until it is removed on its Locks screen.

## How it works

| Piece | What |
|---|---|
| `services/carkey/` (Go, root, loopback 8086) | `teslalinux-carkey`: Tesla's [vehicle-command](https://github.com/teslamotors/vehicle-command) library (the car's signed protocol, sessions, the add-key request) over a connector of its own, `bluez.go`, which reaches the car through BlueZ over D-Bus. vehicle-command's own BLE connector takes the Bluetooth controller over a raw HCI channel and holds it alone, which would stop BlueZ and with it Waze's tunnel-beacon scanning (docs/BEACONS.md); through bluetoothd both share it. One LE connection and one infotainment session stay open while the car answers; a dropped link is looked for again (10 s, doubling to a minute). The VIN, the key (P-256, SEC1 PEM) and the car's Bluetooth address live in `/var/lib/teslalinux/carkey`, 0600 in a 0700 directory of root's. |
| `services/carsync/carsync.py` (Python, `tesla`) | `teslalinux-carsync`: reads carkey's state every second. A destination counts once two of the car's reads agree; it goes to Waze with Grok remote control's code, the way Grok sends an address (`services/grok/waze.py` `navigate_geo`). Car mode: the destination's name as text for Waze's own search near the car (`geo:0,0?q=TEXT` in Android Auto's navigate intent), so Waze drives to its own place under its own name, and Go now is pressed on Waze's route preview. The car's name has no town ("Herzl St 10"), so Waze's distance left must match the car's (within 35 % or 3 km); a search that finds another place, or starts no drive, is replaced by the car's coordinates (which Waze lists as "Received location"). Phone mode opens Waze's `ll=` deep link with the coordinates (its trip cannot be read back to check a search). When the car's route ends, Waze's drive ends too (Car mode). A refused destination is offered again every 30 s, six times. With **Use the car's GPS**, each new fix goes to the GPS bridge (`services/gps/gps.py`) marked `"source": "car"`; the bridge then drops the browser's fixes until the car's stop for 3 s. The car's accuracy field reads 9999.99 even with a valid fix, so accuracy goes as unknown (the app's reader assumes 10 m). |
| `services/api/api.py` | `GET/POST /api/carLink`: carkey's state, the settings (`/var/lib/teslalinux/state/carsync.json`: `follow`, `carGps`) and carsync's last action; the VIN, Pair and Forget go to carkey. |
| `web/js/settings/settings-tesla.js` | Settings > Tesla. |

The car advertises as `S` + the first 8 bytes of SHA-1(VIN) in hex + `C`. Once connected, BlueZ
shows the car under its own name (the one its driver gave it), so the address it had is kept and
matched too.

## Limits

- **Connection slots:** a Tesla takes a few Bluetooth LE connections at once (phone keys among
  them). This device holds one for as long as the car answers.
- **A sleeping car** does not answer the infotainment session: the link says so and tries again.
- **Phone mode** cannot end Waze's drive from outside the app, so there the car's route ending
  leaves Waze's drive as it is.
- A drive the driver starts in Waze itself is left alone; only a new route on the car's screen
  changes Waze's destination.
