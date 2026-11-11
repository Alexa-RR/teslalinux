#!/usr/bin/env bash
# teslalinux: one Waze-under-ATL iteration: host-build api-impl, push, run in the guest, wait for the
# process to end (or MAXSECS), then print the failure summary.   cycle.sh [MAXSECS]
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MAX="${1:-150}"
export MSYS_NO_PATHCONV=1
bash "$HERE/hostbuild.sh" || exit 1
bash "$HERE/vm.sh" ssh "pkill -9 -f \"[a]ndroid-translation-layer /home\" 2>/dev/null; (COMPOSITOR=${COMPOSITOR:-weston} ATL_DENSITY_DPI=${ATL_DENSITY_DPI:-} ATL_LOCATION_SOURCE=${ATL_LOCATION_SOURCE:-} GDB_AT=${GDB_AT:-} JDWP=${JDWP:-} JDB_AT=${JDB_AT:-} STRACE=${STRACE:-} DUMP_AT=${DUMP_AT:-} nohup ./run-waze.sh $MAX > ~/waze/cycle.out 2>&1 &); sleep 8; ls -d ~/waze/run-* | tail -1" | tail -1 | tr -d '\r' > /tmp/atl-rundir
RUNDIR="$(cat /tmp/atl-rundir)"
echo "[cycle] run dir $RUNDIR"
# wait for the process to exit (atl.log gets "exit=") or the runner to give up
bash "$HERE/vm.sh" ssh "for i in \$(seq 1 $((MAX/5+20))); do grep -q \"^exit=\" $RUNDIR/atl.log 2>/dev/null && break; sleep 5; done; echo waited \$((i*5))s"
bash "$HERE/vm.sh" ssh "
d=$RUNDIR
echo \"== jar checksum: \$(grep -o \"api-impl.jar\\*[0-9]*\" \$d/atl.log | head -1)   lines: \$(wc -l < \$d/atl.log)   shots: \$(ls \$d | grep -c shot)\"
echo \"== exit: \$(grep -m1 \"^exit=\" \$d/atl.log)\"
echo \"== E/ lines:\"; grep -E \"^E/\" \$d/atl.log | grep -vE \"FirebaseMessaging|no certificates|hasSystemFeature|Firebase-Installations|fingerprint hash|AsynchronousCloseMonitor\" | cut -c1-200 | sort | uniq -c | sort -rn | head -12
echo \"== first fatal/uncaught:\"; grep -nE \"FATAL|JNI DETECTED|SIGSEGV|SIGABRT|FATAL EXCEPTION|AndroidRuntime\" \$d/atl.log | head -3 | cut -c1-260
n=\$(grep -nE \"NoClassDefFoundError|NoSuchMethodError|NoSuchFieldError|UnsatisfiedLinkError|ClassNotFoundException|AbstractMethodError|IncompatibleClassChangeError\" \$d/atl.log | grep -v \"W/System.err\" | head -1 | cut -d: -f1)
if [ -n \"\$n\" ]; then echo \"== first linkage error at line \$n:\"; sed -n \"\$((n)),\$((n+14))p\" \$d/atl.log | cut -c1-220; fi
m=\$(grep -nE \"JNI DETECTED|FATAL EXCEPTION\" \$d/atl.log | head -1 | cut -d: -f1)
if [ -n \"\$m\" ]; then echo \"== stack before the abort (line \$m):\"; sed -n \"\$(( m>50 ? m-50 : 1 )),\$((m))p\" \$d/atl.log | grep -E \"^\s+at |Exception|Caused|Error\" | grep -v \"W/e\" | cut -c1-180 | tail -28; fi
echo \"== last 5 non-GC lines:\"; grep -v heap.cc \$d/atl.log | tail -5 | cut -c1-200
"
