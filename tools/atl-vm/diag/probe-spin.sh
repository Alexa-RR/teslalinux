#!/bin/bash
# detached on the Mac VM: reproduce the spin after "View routes" and record where the main thread is
out=/tmp/spin-result.txt
: > $out
log() { echo "$(date +%H:%M:%S) $*" >> $out; }
pkill -9 -f "[a]ndroid-translation-layer /home"; pkill -f "[r]un-waze.sh 900"; sleep 1
cd ~ && (LD_PRELOAD=$HOME/libtlsegv.so COMPOSITOR=cage ATL_DEBUG_DUMPVIEWS=1 ATL_LOCATION_SOURCE=tesla:127.0.0.1:9997 nohup ./run-waze.sh 900 > ~/waze/interactive.out 2>&1 &)
sleep 10
d=$(ls -d ~/waze/run-* | tail -1); log "run $(basename $d)"
for i in $(seq 1 120); do grep -aq "MainScreenVisible\|^exit=" $d/atl.log && break; sleep 1; done
sleep 25
m=$(wc -l < $d/atl.log); echo "@MainActivity dump" > /tmp/atl-dumpviews; sleep 4
if tail -n +$m $d/atl.log | grep -aq "DialogWrapper\|Dialog"; then log "dialog: Close"; ~/tap.sh tap 450 472 >/dev/null; sleep 4; fi
rm -f /tmp/atl-dumpviews
bash ~/probe-search.sh | grep -a "legacyText\|no enabled" >> $out
~/tap.sh tap 1203 553 >/dev/null; sleep 4; ~/tap.sh tap 1172 679 >/dev/null; sleep 10; ~/tap.sh tap 170 222 >/dev/null; sleep 6
p=$(pgrep -f "[a]ndroid-translation-layer /home" | head -1)
sudo -n cat /proc/$p/maps > /tmp/maps-before.txt
~/tap.sh tap 404 680 >/dev/null; sleep 25
p=$(pgrep -f "[a]ndroid-translation-layer /home" | head -1)
if [ -z "$p" ]; then log "DEAD after View routes"; grep -a "Fatal signal" $d/atl.log | head -1 >> $out; grep -a "\[segv\]\|\[exit\]" $d/atl.log | grep -av "addr 0 pc\|wolfssl\|boot.vdex\|\[stack\]" | head -40 | cut -c1-200 >> $out; else
  a=$(awk '{print $14+$15}' /proc/$p/task/$p/stat); sleep 2; b=$(awk '{print $14+$15}' /proc/$p/task/$p/stat)
  log "alive; main cpu ticks/2s: $((b-a))"
  ~/tap.sh shot /tmp/q1.png >/dev/null
  grep -a "I/SurfaceView\|startActivityForResult\|I/Activity(.*): - on\(Destroy\|Stop\|Resume\)" $d/atl.log | sed -E "s/startActivityForResult\(Intent \[component=ComponentInfo\{com.waze\///; s/\}.*//; s/I\/SurfaceView\([0-9]+\): //; s/I\/Activity\([0-9]+\): //" | cut -c1-90 | tail -10 >> $out
  if [ $((b-a)) -le 100 ]; then log "no spin: leaving the app running"; log "done"; exit 0; fi
  sudo -n cat /proc/$p/maps > /tmp/maps-before.txt
  m=$(wc -l < $d/atl.log)
  for k in 1 2 3; do touch /tmp/atl-stack; sleep 3; done
  tail -n +$m $d/atl.log | grep -a "mainstack" | sed -E "s/^I.ViewDump\( *[0-9]+\): *//" | head -60 >> $out
  m=$(wc -l < $d/atl.log)
  touch /tmp/atl-stack-all; sleep 6
  tail -n +$m $d/atl.log | grep -a "allstack" | sed -E "s/^I.ViewDump\( *[0-9]+\): *//" > /tmp/allstack.txt
  m=$(wc -l < $d/atl.log)
  sudo -n python3 -c "
import ctypes,sys
libc=ctypes.CDLL(None, use_errno=True)
pid=int(sys.argv[1]); print('tgkill ->', libc.syscall(131, pid, pid, 11))
" $p >> $out
  sleep 10
  tail -n +$m $d/atl.log | grep -a "Fatal signal\|Thread:\| pc: \|x30: \|^exit=" | head -6 | cut -c1-120 >> $out
fi
python3 - >> $out <<'PY'
import re,glob
log=sorted(glob.glob("/home/atl/waze/run-*/atl.log"))[-1]
s=open(log,"rb").read().decode("utf-8","replace")
pcs=re.findall(r"pc: (0x[0-9a-f]+)", s); lrs=re.findall(r"x30: (0x[0-9a-f]+)", s)
maps=[]
for line in open("/tmp/maps-before.txt"):
    p=line.split(); a,b=p[0].split("-"); path=p[5] if len(p)>5 else "[anon]"
    maps.append((int(a,16),int(b,16),int(p[2],16),path))
for name,lst in (("pc",pcs),("lr",lrs)):
    if not lst: print(name, "none"); continue
    v=int(lst[-1],16)
    for a,b,off,path in maps:
        if a<=v<b: print(name, lst[-1], "->", path.split("/")[-1], "+0x%x" % (v-a+off)); break
    else: print(name, lst[-1], "-> unmapped")
PY
log "done"
