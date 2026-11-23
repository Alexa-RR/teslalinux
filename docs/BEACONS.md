# Waze Beacons (positioning in tunnels)

Waze Beacons are small Bluetooth Low Energy transmitters that road operators mount along tunnels
(Boston's I-93 tunnels, New York's Queens-Midtown, Brooklyn-Battery, Lincoln and Holland tunnels,
Chicago's Lower Wacker Drive, tunnels in Pittsburgh, Rio, Paris, Oslo, Haifa, Australia and
others). GPS does not reach into a tunnel; Waze hears the beacons it passes, knows where each one
is mounted from its map data, and keeps the car's position moving underground. teslalinux gives
Waze what it needs for that: the Pi's Bluetooth controller and Android's Bluetooth LE scanning API.

## What Waze (5.27.5) does

From reading the app (decompiled for this analysis only; nothing of it is in this repository):

- **When it scans:** its native core looks for beacons in the map tiles around the car (the
  "Beacons" configuration: active, search distance, prefix). Only when tiles near the car hold
  beacons does it ask the Java side for the Bluetooth state and start a scan, and it stops again
  after it has left them (with a grace period). Away from beaconed tunnels it never touches
  Bluetooth.
- **What it checks first:** `BluetoothManager` from the context, its adapter (none: "no
  Bluetooth"; off: Waze asks to turn it on with `ACTION_REQUEST_ENABLE`), and, on Android 12 or
  later, that both `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT` are granted; without them it logs
  "Bluetooth permissions missing" and does not scan. Its manifest requests both (and
  `BLUETOOTH`/`BLUETOOTH_ADMIN` up to SDK 30) and does not mark `BLUETOOTH_SCAN` as
  `neverForLocation`, so on Android the scan results also need fine location and location on.
- **The scan:** `BluetoothAdapter.getBluetoothLeScanner().startScan(filters, settings, callback)`
  with `SCAN_MODE_LOW_LATENCY` and, when its "Android scan filter enabled" setting is on (it is,
  from Waze's servers), one `ScanFilter` on the service UUID `0xFEAA`; `stopScan` later. No legacy
  `startLeScan`, no connections, no GATT.
- **The frames:** Eddystone. For each `ScanResult` Waze reads `getScanRecord().getServiceData(FEAA)`,
  `getRssi()`, `getTimestampNanos()` (elapsed-realtime clock) and the device address:
  - frame type `0x00`, **Eddystone-UID**: TX power at 0 m, a 10-byte namespace and a 6-byte
    instance. Waze turns namespace + instance into 32 hex digits and only keeps those starting
    with its prefix (default namespace `EDE5A7B1986E2BE4CA5A`);
  - frame type `0x20`, **Eddystone-TLM**: battery voltage, temperature, advertisement and uptime
    counters (beacon health it reports back).
  Results are batched for 250 ms and handed to the native core, which places the beacon from its
  tile data, weighs RSSI against TX power and feeds a "beacon" location into its road snapper
  (it may ignore GPS updates while beacons are heard).

## How teslalinux provides it

- **ATL** (fork `track-bt`): `android.bluetooth` is backed by BlueZ over the D-Bus system bus
  (`TlBluez`, `android_bluetooth_TlBluez.c`). The adapter is BlueZ's first `Adapter1` (or
  `ATL_BLUETOOTH_ADAPTER=hci1` for a USB adapter); its `Powered` state is `STATE_ON`/`STATE_OFF`
  with AOSP's `ACTION_STATE_CHANGED` broadcasts as it comes and goes. A scan runs one BlueZ
  discovery session (`SetDiscoveryFilter` Transport le + DuplicateData, plus the scans' service
  UUIDs when every scan filters on one, so BlueZ only reports Eddystone devices while Waze scans);
  every advertisement becomes a `ScanResult` whose record is rebuilt from BlueZ's device
  properties and parsed by AOSP's `ScanRecord.parseFromBytes`. Filters, callback types, batching,
  AOSP's error and location rules are as on Android (the class comments list them). Without BlueZ,
  or with `ATL_BLUETOOTH=off` (`/etc/teslalinux/atl.env`), the device has no Bluetooth at all, as a
  phone without it. `ACTION_REQUEST_ENABLE` answers RESULT_OK when the adapter is on, RESULT_CANCELED
  otherwise (nobody to ask in the car).
- **The Pi** (`tools/pios/install.sh`): Bluetooth is no longer disabled (`dtoverlay=disable-bt` is
  gone from the boot config), `bluez` is installed and `bluetooth.service` enabled, a saved rfkill
  soft block is lifted, and `/etc/dbus-1/system.d/teslalinux-bluetooth.conf` lets the app user
  `tesla` only read BlueZ's objects and run discovery (no powering, pairing or connecting).
- **Boot cost** (Pi 4, Nov 23 2026, `teslalinux-boottime`): Bluetooth off, three boots: hotspot up
  7.81-7.96 s, car page 8.36-8.59 s; Bluetooth on, three boots: 7.40-7.56 s and 8.16-8.27 s. The
  controller's firmware (BCM4345C0) loads in the kernel in about 0.8 s, off the critical path;
  `bluetooth.service` starts in parallel (0.4-0.7 s). No measurable cost.
- **Radio sharing:** the Pi 4's Bluetooth and Wi-Fi are one chip (CYW43455); while Waze scans
  (only near beaconed tunnels) the chip time-shares the antenna with the car's hotspot. A USB
  Bluetooth adapter (`ATL_BLUETOOTH_ADAPTER`) takes the scanning off the Wi-Fi chip if that ever
  matters.

## Tests

- **VM** (`tools/debian/atl-tests/bluetooth/run.sh`, no Bluetooth hardware): a mock bluetoothd
  (`mock-bluez.c`) on a private system bus plays devices, Eddystone and iBeacon advertisements,
  power cycles, adapter removal and bluetoothd restarts; BluetoothTest checks the adapter, the
  discovery session and its filter, filters, callback types, batches, the legacy API, AOSP's
  errors and location rules, remote devices and `ATL_BLUETOOTH=off` (79 checks, Nov 23: all pass).
- **Real radio:** `tools/debian/atl-tests/bluetooth/eddystone-adv.sh start` on another BlueZ host
  (the spare Pi 5) advertises Waze-format UID frames (Waze's namespace, instance `TLTEST`) and TLM
  frames. `run.sh --live DIR SECONDS` on the Pi 4 (a DIR built with `run.sh --build` in the VM)
  scans with Waze's filter and settings and prints every frame. Nov 23: 17 frames in 10 s, UID and
  TLM byte for byte, with RSSI around -80 dBm at a few metres. (On the Pi's current runtime the
  test process can crash in ART's JIT after the scan; with `-Xint` it passes; not Bluetooth code.)
- **Waze itself:** a simulated drive through the I-93 tunnel (`tools/atl-vm/gps-sim.py` on a spare
  port, `ATL_LOCATION_SOURCE=tesla:127.0.0.1:PORT`) did not make Waze start its scan: its beacon
  check depends on Waze's server-side tile data and, likely, on a route through the tunnel. The
  first real proof is a drive.

## In a real tunnel

1. Before the drive, on the Pi: `bluetoothctl show` says `Powered: yes`. For a debug drive, put
   `TESLALINUX_ATL_DEBUG=1` and `ATL_DEBUG_BLUETOOTH=1` in `/etc/teslalinux/atl.env` and restart
   the app.
2. Drive into a tunnel listed above, ideally navigating through it.
3. Afterwards: `journalctl -u teslalinux-atl | grep '\[bluetooth\]'` shows `StartDiscovery: ok`
   when Waze started its beacon scan near the tunnel (and the adapter line at its start). With
   `ATL_DEBUG_BLUETOOTH=2` each advertisement handed to Waze is logged as well.
4. On the car screen: inside the tunnel the car's arrow keeps moving along the tunnel instead of
   freezing at the portal or jumping at the exit; the ETA keeps counting down.
5. If Waze never starts a scan near a beaconed tunnel, check that `BLUETOOTH_SCAN` and
   `BLUETOOTH_CONNECT` are granted (they are, as runtime permissions of an installed app) and that
   the car's location reaches Waze.
