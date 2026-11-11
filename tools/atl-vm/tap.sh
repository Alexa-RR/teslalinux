#!/bin/sh
# teslalinux: drive the app under headless cage (pushed to ~/tap.sh).
#   tap.sh tap X Y          click at absolute output coordinates
#   tap.sh type TEXT        type text (wtype)
#   tap.sh key KEYNAME      press a key (xkb name, e.g. Return, BackSpace, Tab)
#   tap.sh shot FILE.png    screenshot (grim)
#   tap.sh swipe X1 Y1 X2 Y2 [steps]
# The wlr virtual pointer only moves relatively, so every tap first parks the pointer at 0,0.
export XDG_RUNTIME_DIR=${XDG_RUNTIME_DIR:-/tmp/xdg-atl}
export WAYLAND_DISPLAY=${WAYLAND_DISPLAY:-wayland-0}
park() { wlrctl pointer move -9000 -9000 >/dev/null 2>&1; }
case "$1" in
  # A persistent virtual pointer (vpointer, see vpointer.c) when its FIFO exists: wlrctl creates and
  # destroys a device per call, which makes GTK re-create its wl_pointer and lose the button events.
  tap)   if [ -p "$HOME/vpointer.fifo" ]; then echo "click $2 $3" > "$HOME/vpointer.fifo"; else park; wlrctl pointer move "$2" "$3"; sleep 0.15; wlrctl pointer click left; fi; echo "tap $2,$3" ;;
  move)  if [ -p "$HOME/vpointer.fifo" ]; then echo "move $2 $3" > "$HOME/vpointer.fifo"; else park; wlrctl pointer move "$2" "$3"; fi; echo "move $2,$3" ;;
  down)  echo "down" > "$HOME/vpointer.fifo"; echo down ;;
  up)    echo "up" > "$HOME/vpointer.fifo"; echo up ;;
  type)  shift; wtype -d 30 "$*"; echo "typed" ;;
  key)   wtype -k "$2"; echo "key $2" ;;
  shot)  grim "$2" && echo "shot $2" ;;
  setvis) echo "setvis $2 $3" > /tmp/atl-dumpviews; sleep 1.5; d=$(ls -d "$HOME"/waze/run-* | tail -1); grep -a "I/ViewDump.*setvis" "$d/atl.log" | tail -1 ;;
  dump)  touch /tmp/atl-dumpviews; sleep 1.6; d=$(ls -d "$HOME"/waze/run-* | tail -1); grep -a "I/ViewDump" "$d/atl.log" | tail -n ${2:-400} | sed -E "s/^I\/ViewDump *\([0-9]+\): //" ;;
  swipe) steps=${6:-10}; dx=$(( ($4 - $2) / steps )); dy=$(( ($5 - $3) / steps )); x=$2; y=$3
         { echo "move $x $y"; echo "sleep 50"; echo "down"; i=0; while [ $i -lt $steps ]; do x=$((x+dx)); y=$((y+dy)); echo "move $x $y"; echo "sleep 30"; i=$((i+1)); done; echo "up"; } > "$HOME/vpointer.fifo"; echo "swipe" ;;
  *) echo "usage: tap.sh tap X Y | move X Y | down | up | type TEXT | key NAME | shot FILE | swipe X1 Y1 X2 Y2 [steps]" ;;
esac
