#!/bin/sh
# teslalinux: guest-side log inspection helpers (pushed to ~/inspect.sh). Usage: ./inspect.sh CMD [args]
d=$(ls -d ~/waze/run-* | tail -1)
noise='bionic_linker|FirebaseInstanceId|WazeSource|method_verifier|GC |^\s*$|dex2oat|Firebase-Installations|MessengerIpcClient|Rpc     '
case "$1" in
  dir) echo "$d"; ls "$d" ;;
  perf) grep -n "WazePerf\|boot event" "$d/atl.log" | cut -c1-160 ;;
  after) # lines after first match of $2, noise filtered, up to $3 lines (default 80)
    n=$(grep -n "$2" "$d/atl.log" | head -1 | cut -d: -f1); [ -z "$n" ] && { echo "no match for $2"; exit 0; }
    tail -n +"$((n+1))" "$d/atl.log" | grep -vE "$noise" | cut -c1-${4:-170} | head -"${3:-80}" ;;
  grep) grep -nE "$2" "$d/atl.log" | grep -vE "$noise" | cut -c1-${3:-170} | head -"${4:-60}" ;;
  tail) tail -n "${2:-40}" "$d/atl.log" | grep -vE "$noise" | cut -c1-${3:-170} ;;
  tags) sed -E 's/\([0-9]+\)//; s/^[A-Z]\///' "$d/atl.log" | grep -vE "$noise" | cut -d: -f1 | sort | uniq -c | sort -rn | head -"${2:-40}" ;;
  shots) ls -la "$d"/*.png "$d"/shots/ 2>/dev/null; md5sum "$d"/shots/*.png "$d"/*.png 2>/dev/null | cut -c1-10,33- ;;
  *) echo "usage: inspect.sh dir|perf|after PATTERN [N] [W]|grep PATTERN [W] [N]|tail [N]|tags [N]|shots" ;;
esac
