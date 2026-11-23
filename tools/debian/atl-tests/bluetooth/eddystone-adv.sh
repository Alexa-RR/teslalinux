#!/bin/sh
# eddystone-adv.sh start [NAMESPACE [INSTANCE [HCI]]] | stop [HCI]
#
# Makes a BlueZ host (a spare Pi, a laptop with Linux) advertise Eddystone frames in the format
# Waze's tunnel beacons use, for testing the beacon scan of another device: an Eddystone-UID frame
# (service data on UUID 0xFEAA, frame type 0x00: TX power at 0 m, 10-byte namespace, 6-byte
# instance) and an Eddystone-TLM frame (type 0x20: battery voltage, temperature, advertisement and
# uptime counters), each with the 16-bit service UUID list 0xFEAA, as two advertising instances the
# kernel alternates every second. Non-connectable, general discoverable; legacy advertising on 1M.
#
#   NAMESPACE  20 hex digits; default EDE5A7B1986E2BE4CA5A, the namespace Waze 5.27 expects (the
#              default of its "Beacons"/"Prefix" setting; Waze ignores UID frames of other namespaces)
#   INSTANCE   12 hex digits; default 544C54455354 ("TLTEST"). Waze only places a beacon it knows
#              from its map tiles; any other instance is heard and reported but not located.
#   HCI        the controller index (default 0: hci0)
#
# Needs root (btmgmt talks to the kernel's management interface). Check with
# `btmgmt --index 0 advinfo` and, from another host, `bluetoothctl scan le`.
set -eu
cmd=${1:-start}
case "$cmd" in
	start)
		ns=${2:-EDE5A7B1986E2BE4CA5A}
		inst=${3:-544C54455354}
		hci=${4:-0}
		if [ ${#ns} -ne 20 ] || [ ${#inst} -ne 12 ]; then
			echo "NAMESPACE must be 20 hex digits and INSTANCE 12" >&2
			exit 2
		fi
		# UID: type 00, TX power at 0 m 0xEE (-18 dBm), namespace, instance, 2 reserved bytes
		uid="0303AAFE""1716AAFE""00EE${ns}${inst}0000"
		# TLM: type 20, version 0, 3000 mV, 25.0 C, 16 advertisements, 3.2 s since power on
		tlm="0303AAFE""1116AAFE""20000BB819000000001000000020"
		btmgmt --index "$hci" power on >/dev/null
		btmgmt --index "$hci" clr-adv >/dev/null 2>&1 || true # fails when nothing is advertised
		btmgmt --index "$hci" add-adv -g -D 1 -d "$uid" 1
		btmgmt --index "$hci" add-adv -g -D 1 -d "$tlm" 2
		;;
	stop)
		btmgmt --index "${2:-0}" clr-adv
		;;
	*)
		echo "usage: $0 start [NAMESPACE [INSTANCE [HCI]]] | stop [HCI]" >&2
		exit 2
		;;
esac
