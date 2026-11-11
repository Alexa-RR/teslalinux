# diag/ — crash and UI probes for the ATL test VM

Copied to `~` on the VM with `vm.sh push`; the probe scripts run detached (`nohup bash ~/probe-x.sh &`)
and write `/tmp/<name>-result.txt`, because an ssh session that stays silent for ~30 s or runs
longer than a few minutes returns nothing.

- `tl_segv.c`: `gcc -shared -fPIC -O1 -o ~/libtlsegv.so tl_segv.c -lpthread -ldl`, then
  `LD_PRELOAD=~/libtlsegv.so` on the ATL launch. Prints the fault pc/lr and a frame-pointer chain
  mapped to library offsets (`[segv]`), and the caller chain of `exit`/`_exit`/`abort` (`[exit]`).
  ART's own reporter dies in its unwinder and gdb cannot run in the VM (memory), so this is the
  one that worked: it showed ATL calling exit(1) from the ViewTreeObserver native.
- `probe-search.sh`: open the search sheet, type "te" by the field's reported bounds (view dump).
- `probe-spin.sh`: search -> result -> View routes, then Java stacks of a busy main thread
  (`/tmp/atl-stack`, `/tmp/atl-stack-all` handled by `android.atl.ViewDump`'s side thread) and a
  forced crash dump with the maps saved beforehand.
- `probe-flows.sh`: Hebrew typing, the Work address flow, navigation and BACK.
- `probe-tripmap.sh`: recent destination -> trip overview surface state.
- `probe-choreo.sh`, `probe-results.sh`: Choreographer frame delivery; results screen pick trace.

View dump commands (`echo <cmd> > /tmp/atl-dumpviews`, `@Name` prefix targets one activity):
`dump`, `textfields`, `composestate`, `choreo`, `composeapply`, `hit X Y`, `fragments`, `config`.
