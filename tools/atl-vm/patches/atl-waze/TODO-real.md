# Shortcuts that must become real implementations

Rule for this patch set: a method either does what AOSP does, or does what AOSP does on a device that
lacks the feature (telephony, Bluetooth, biometrics, keystore hardware). Returning a dummy value just
to get past a call is not acceptable. The items below were taken as shortcuts early on and are to be
replaced; each is removed from this list only when the real behavior is in.

| Where | Shortcut | Real behavior needed |
|---|---|---|
| `android/location/LocationProvider.java`, `LocationManager` additions | GNSS measurements report not supported, as a receiver without them | Nothing more unless a host receiver with raw measurements appears |

## Open after the Nov 23 integration (fork tracks A to G)
- Account and login (signed in) is the settings page `settings_main.account.account_and_login`;
  signed out it is `TempUserProfileActivity`, whose ScrollView scrolls, and atl-tests/scroll
  (ScrollView with EditText rows) scrolls. Check it with a signed-in Waze.
- The IME window inset is the built-in on-screen keyboard's (WindowInsets.Type.ime(), adjustResize,
  WindowInsetsAnimation). A keyboard of the car page or a device keyboard has no inset: nothing
  tells ATL its height (Wayland text-input-v3 carries none).

## Deferred (not needed by Waze yet)
Real work for later, not dropped. Each is a gap no Waze code path reaches today.
- OpenGL ES 1 contexts: Debian's Mesa is built without GLES1 (`eglCreateContext` fails with
  `EGL_BAD_ALLOC`, also without ATL); GLES10/GLES11/GL10 apps (a GLSurfaceView renderer that never
  sets a client version) need an ES 1 on ES 2 layer. Not started.
- android.transition is not ported (ATL's Transition is an empty class): popups show and hide
  without their Material enter/exit transition, app-set transitions do not run. Not started.
- AssistStructure/ViewNode and the ContentCaptureSession ViewStructure methods
  (`newViewStructure`, `newVirtualViewStructure`, `notifyViewAppeared`, `notifyViewsAppeared`,
  `notifyViewsDisappeared`, `newAutofillId`): they need `ViewNode` (extends
  `AssistStructure.ViewNode`) and the full `AutofillId`; ATL ships a 4-line `AutofillId` and no
  `ViewStructure`. Port `AutofillId`, `AutofillValue`, `ViewStructure`, `AssistStructure`/`ViewNode`
  from AOSP. Not started.
- AndroidKeyStore Curve 25519 keys (x25519/ed25519), XDH KeyAgreement, Ed25519 Signature: started
  on fork branch `track-g-later` (key generation, import, agreement and RFC 8032 signing pass in one
  process). Left: an x25519/ed25519 key stored by the keystore does not load again in a new process
  ("Keystore public key unreadable"); the PlatformTest checks are in that branch as
  `test-notes/teslalinux-platformtest-curve25519.patch`.
- The AccessibilityNodeInfo connection (no accessibility service runs here), a per-window
  accessibility node query.

## Placeholders seen in the upstream ATL merge (added 2026-11-11, fix in the fork)
- Keystore backend: the fork's KeyStoreStorage is kept. Upstream's libsecret natives
  (`android_security_keystore_AndroidKeyStore.c`) are compiled but unused; using them is a decision
  for the user.

## Content capture / autofill (added 2026-10-08)
- The ContentCaptureSession ViewStructure methods: see "Deferred (not needed by Waze yet)".
- `ContentCaptureContext` carries only the app-side fields; the @SystemApi half needs the system
  service (absent here, as on devices without a content capture service).
- No `ContentCaptureManager`: `Context.CONTENT_CAPTURE_MANAGER_SERVICE` resolves to null, which is
  AOSP behaviour when the service is missing, so `View.getContentCaptureSession()` is null.
- Activity.setPictureInPictureParams / android.app.PictureInPictureParams(.Builder) /
  PictureInPictureUiState are not ported (no PiP window mode in ATL). Waze references them for
  the navigation PiP; add when PiP is designed for the car screen.

## Boot classpath (ART libcore) and lambdas
- The guest's core-oj/core-libart hostdex jars were dexed with dx (invoke-custom left in), and the
  shipped java.lang.invoke.LambdaMetafactory was a placeholder returning null: every lambda inside
  libcore threw BootstrapMethodError. patches/atl-waze/bootcp/java/lang/invoke/LambdaMetafactory.java
  (installed by patch-core-oj.sh) implements the metafactory with a Proxy per function object over
  MethodHandles (captures, method refs, default methods such as Function.andThen all work).
- Fixed by tools/debvm/patches/libcore-d8.py (D8-dexed core-oj/core-libart; atl-tests/lambda 7/7),
  run by build-dist.sh.
- Test harness: dalvikvm runs a dex directly in the guest (dalvikvm -cp /home/atl/x.dex Main);
  scratchpad/mhtest has the lambda/method-handle tests. A boot jar change costs one boot-image
  rebuild (~3 min) instead of a Waze relaunch (~10 min).

## Open after 2026-10-08 evening (Mac VM)
- Audit (scratchpad/apicheck3.txt): accessibility (176 members), content/pm (59), inputmethod (43),
  Notification, media/session, WebView, camera2 (no camera: report absence), GnssStatus.

## 2026-10-09 evening, open
- Navigation start: after "View routes" -> trip overview -> "Less chatty" dialog -> "No thanks",
  the screen stays black except the lane arrows. Map frames DO arrive (ATL_DEBUG_SURFACE shows
  eglSwapBuffers/set_texture/snapshot with a non-black centre pixel) and the GTK tree has the map
  SurfaceViewWidget mapped at 1280x720, so most likely another toplevel (a dialog GtkWindow that was
  never destroyed; cage keeps the newest toplevel on top) covers the main window. Next step: list
  gtk_window_get_toplevels() in atl_viewdump.c (visible/mapped/title) and check Dialog.nativeClose
  (gtk_window_close + close-request -> dismiss re-entry) actually hides/destroys the window.
- FIXED (Oct 10): dialogs are layers in the main window (native-dialog-overlay.sh: GtkOverlay, AOSP dim 0.6,
  outside-touch cancel, BACK to the topmost dialog). Still separate GTK windows: ATL's software keyboard
  (ATLKeyboardDialog) and NotificationManager popups; under cage they cover the screen.
- AccessibilityNodeInfo.setQueryFromAppProcessEnabled keeps no connection (no in-process node
  query service yet); AccessibilityManager/View accessibility event dispatch still ATL's minimal one.

- /S3_TTS/...mp3 is Waze's media3 cache key for a server TTS clip; a clip missing from downloads/voice_files falls through to FileDataSource. Open: why that clip was not downloaded (trace a drive).

- FIXED (Oct 10): drawer buttons unreachable: ATL's View.requestLayout only flipped flags up the chain, so Compose's AndroidViewsHandler never remeasured holders whose View went GONE and a stale 1280x72 node kept catching taps. requestLayout now calls the parent's virtual requestLayout (AOSP). One-off crash seen once right after Stop: 'Must run runDetachLifecycle() before markAsDetached()' (Compose detach ordering) - not reproduced since; watch for it.

## Oct 10: View state stubs still in ATL (found while porting the button/switch widgets)
- onLayout for leaf views reaches Java only when the class overrides onLayout/onSizeChanged
  (native-leaf-layout.sh); AOSP always calls it. Fine for performance, but a class that only reads
  getWidth()/getHeight() in onDraw after a size change is covered by the wrapper's allocation.
- isShown walks the parents (AOSP); a parentless view reports isAttachedToWindow.

## Oct 10: performance notes
- ART ran with -Xcheck:jni on every launch (native-checkjni-opt.sh keeps it behind ATL_CHECK_JNI=1).
- base.apk is AOT-compiled (289 MB oat in ~/.cache/art/arm64); api-impl.jar too. Idle CPU in the VM is
  llvmpipe (software GL for the map at ~25 fps) plus Waze's RenderThread; the Java main thread is ~15%.
- Launch to MainScreenVisible is ~3.5 s in the VM (process start to Waze's MainScreenVisible; Waze's
  own boot clock reports 2.2 s from its entry point, so ART/ATL/APK startup is ~1.3 s).
- GSK renderer: cairo in the VM (llvmpipe); the Pi should run the GL renderer so SurfaceView frames
  stay GL textures (egl.c builds them with gdk_gl_texture_builder; no download).

## Oct 10 late: pending verification (since verified, see the Oct 10 'verified end to end' section)
- native-osk.sh + InputMethodManager tlOsk*: built-in on-screen keyboard layer (Latin/symbols/Hebrew,
  Search/Go/Done action key, backspace via deleteSurroundingText). Built, never shown yet: Compose's
  search field was not focused in the test runs (tap positions depend on the sheet state) and the run
  crashed (below) before the keyboard could appear. Verify: tap the field, expect "[ime] showSoftInput"
  with ATL_DEBUG_IME=1, keyboard at the bottom, keys committing text, Search action.
- Crash after the dialog overlay change: a SurfaceView whose dialog layer is not in the window yet has
  no GTK native, egl.c derived the GL context from it (NULL -> SIGSEGV) and View.native_get_window
  read the NULL root. Fixed in native-dialog-overlay.sh (tl_surface_for_widget, nearest ancestor with
  a Window); rebuild and re-run the search/dialog flows to confirm.
- Hardware keys reach AndroidComposeView.dispatchKeyEvent (ATL_DEBUG_KEY=1 trace) but return false
  when no Compose text field is focused; the Wayland text-input path (GtkIMContext) only matters
  with a compositor keyboard.

## Host launcher (Pi) — written (host/phase1-pios/teslalinux-atl + teslalinux-atl.service), untested on hardware
services/ has gps/touch/audio/api daemons and the planned tacomp compositor; the ATL launcher is in host/phase1-pios.
When it is written, carry over what the VM runs prove: GSK_RENDERER=gl (V3D), no LIBGL_ALWAYS_SOFTWARE,
ATL_LOCATION_SOURCE=tesla:127.0.0.1:9997 (gps.py), ATL_FORCE_FULLSCREEN=1, ATL_DISABLE_WINDOW_DECORATIONS=1,
ATL_OSK unset (built-in keyboard), ATL_CHECK_JNI unset, ATL_DENSITY_DPI for the car screen, the
split APK set from tools/apps/install-app.sh, and the dalvik cache warmed (dex2oat speed) at image bake.

## Oct 10 late: keyboard and text input status
- Built-in on-screen keyboard works end to end on EditText (Waze's Home/Work address screen:
  focus-in -> showSoftInput, keys commit through the EditText InputConnection, Search action).
- Compose legacy BasicTextField (the search screen field): the "value never redraws" symptom of
  Oct 10 was the Choreographer vsync re-request (see below); since then a FOCUSED field draws its
  text. The field on the main map sheet is tap-to-open by design: it opens the search screen,
  whose own field must then be focused. That last step did not happen until Nov 9 (see below).
- Keyboard label for the language key uses C byte escapes (double-encoding fixed).

## Nov 9: search field focus (fixed)
- Symptom: tapping the search bar opened the search screen with no keyboard and no caret; typed
  keys reached AndroidComposeView but nothing took them. Only a second tap on the field itself
  focused it (then typing, suggestions and the Search action all worked).
- Cause: View.isInTouchMode() was a stub returning false, so Compose's InputModeManager was in
  Keyboard mode, where Focusability.SystemDefined makes every clickable focusable. Waze's
  FocusRequester sits on the clickable search-bar row; in touch mode (Android) it cannot take
  focus and the request enters the text field, here the row itself took it.
- Fix: AOSP touch mode (View.java.frag tlEnsureTouchMode, native-touch-mode.sh: pointer DOWN/SCROLL
  enters, navigation/typing keys leave, OnTouchModeChangeListeners of every window, enter/leave
  focus adjustment), with the focus state it needs made real: hasFocus/findFocus/clearFocus from
  the window's GTK focus (native-focus-state.sh), ViewGroup getFocusedChild/descendantFocusability
  (attr too)/clearFocus, Window registry + peekDecorView, KeyEvent.getUnicodeChar per Generic.kcm
  (no C0 controls; ENTER '\n'). Verified (out/search-2026-11-09-*.png): open -> focused field + OSK,
  OSK and hardware typing, live suggestions, Search/Return -> result preview, BACK chain, and the
  Home address EditText (OSK and hardware keys).
- Fallout fixed on the way: with a real hasFocus, AutoCompleteTextView shows its dropdown for the
  first time; ATL's ViewGroup.findViewById built a log line with getResources() on every call (a
  Waze cell overrides getResources and crashed in its constructor; now AOSP traversal), and
  ATL's PopupWindow grabbed the keyboard and the next outside tap (setOutsideTouchable mapped to
  autohide, setFocusable a no-op). PopupWindow now follows AOSP: not focusable by default
  (GtkPopover can-focus off, native-popup-window.sh), grabs only when focusable and touch modal,
  outside-touchable popups get ACTION_OUTSIDE from the main window's input hook and dismiss.
- Also fixed: native-mediacodec.sh never applied on a fresh build (its "tl_mediacodec" guard
  matched the "tl_mediacodec_drop" marker of native-mediacodec-no-exit.sh, which sorts first), so
  MediaCodecList.nativeHasDecoder was missing and the first voice prompt killed Waze with an
  UnsatisfiedLinkError. The guard is now the full marker of its own block.
- Still open from this area:
  - (fixed Nov 9, see "Nov 9: app windows and popups") PopupWindow was a GtkPopover.

## Nov 9: app windows and popups (fixed)
- Symptom: the Home/Work address dropdown was an empty 40 px strip at the left edge that took the
  keyboard and the next tap; picking an address was impossible.
- Cause: ATL's WindowManagerImpl.addView put each window in a GtkPopover pointing at x/y (size,
  gravity and flags ignored), its PopupWindow was a GtkPopover shim (setFocusable a no-op,
  getMaxAvailableHeight 500, outside touch as an autohide grab), ListPopupWindow had every call
  into those commented out, and getWindowVisibleDisplayFrame returned the -w/-h launch size.
- Fix: WindowManagerImpl windows are layers of the main window (native-window-layers.sh,
  TlWindowLayer: ViewRootImpl measure, Gravity placement clamped unless FLAG_LAYOUT_NO_LIMITS,
  touch-modal vs pass-through, FLAG_NOT_TOUCHABLE/NOT_FOCUSABLE, FLAG_DIM_BEHIND, ACTION_OUTSIDE
  for FLAG_WATCH_OUTSIDE_TOUCH, keys to the topmost focusable app window, below the on-screen
  keyboard); AOSP PopupWindow, ListPopupWindow, DropDownListView, ForwardingListener,
  AutoScrollHelper, RoundedCorner and AccessibilityManager (no services installed) ported;
  WindowManager.LayoutParams got AOSP's constants, fields, default constructor, alpha 1.0 and
  copyFrom; real View.OnLayoutChangeListener dispatch, getRootWindowInsets for attached views,
  toGlobal/toLocalMotionEvent, isRootNamespace; the visible display frame is the allocated window
  minus the on-screen keyboard (native-window-frame.sh).
- Verified: Home and Work address screens show suggestions under the field, typing filters them,
  picking one saves it ("Work saved" snackbar), BACK leaves.
- Still open from this area:
  - android.transition: see "Deferred (not needed by Waze yet)".
  - The window layer covers only the main window; app windows of other toplevels (none today) and
    the ATLKeyboardDialog / notification GtkWindows are not managed.

## Oct 10: Compose went static after a while (root cause found)
- Symptom: typed text never drew in the search field, result lists stayed empty, Compose UIs only
  showed their first composition. Recomposer state PendingWork with hasPendingWork=true forever;
  Choreographer mFrameScheduled=true with mLastFrameTimeNanos frozen; a posted frame callback never
  fired; the native vsync request was not pending.
- Cause: ATL's Choreographer.doFrame returns on a frame time that steps backwards without
  re-requesting the vsync (AOSP calls scheduleVsyncLocked there; ATL had commented it out). With
  the GTK frame-clock vsync the frame time occasionally steps back relative to the jitter-adjusted
  System.nanoTime base, after which no frame is ever scheduled again.
- Fix: Choreographer.java.repl restores the re-request. native-choreographer-vsync.sh has an
  ATL_DEBUG_VSYNC watchdog that reports a request the frame clock never served.
- ViewDump commands added for this: composestate (lifecycle owner, recomposer state per owner,
  main-thread snapshot), choreo (scheduler fields + does a frame callback fire), composeapply.

## Oct 10 late: search -> result -> routes
- Works: typing, live suggestions, Search action, results list, tapping a result (location
  preview), View routes launches TripOverviewActivity without crashing.
- Fixed on the way: bionic shim aborted on a mutex with stale bytes (now typed from the state word
  like bionic); view intents with the app's own scheme resolve to the app's activity with
  singleTask onNewIntent (ATL opened them externally); hover callbacks with no current event;
  surfaceDestroyed of a covered activity now follows the replacing window's surfaceCreated (AOSP
  order; the inversion crashed Waze's native map on the trip overview).
- Resolved: the spin after View routes was the main thread waiting in the map view's onStop for a
  GL thread blocked in eglMakeCurrent's unbounded buffer wait (fixed, bounded + hidden-surface
  consumption); the crashes were ATL's exit(1) in the ViewTreeObserver native (fixed).

## Oct 10: process exits that looked like native crashes
- ATL's ViewTreeObserver native called exit(1) on a redundant listener transition (Waze's map view
  removes its global layout listener in onStop after the view detached). Every "crash" on the way to
  the trip overview was this exit; libwaze's atexit destructors then faulted inside exit(), which
  is what the signal reports showed. Fixed (native-viewtreeobserver-idempotent.sh: AOSP no-op).
- Bitmap.getPixels of a sub-rectangle called exit(1): implemented (native-bitmap-getpixels-rect.sh).
- Diagnostics that worked: LD_PRELOAD=~/libtlsegv.so (scratchpad tl_segv.c) prints the fault pc, a
  frame-pointer chain mapped to library offsets, and the caller chain of exit/_exit/abort; the
  view dump's /tmp/atl-stack and /tmp/atl-stack-all give Java stacks from a side thread while the
  main thread is busy. gdb cannot run in the VM (killed by its memory limit).

## Oct 10: verified end to end on the VM
Search typing (Latin and Hebrew, live suggestions; on Oct 10 only after a second tap on the field,
since Nov 9 the opened screen focuses it), results list, result tap (location preview),
View routes -> trip overview -> guidance (route, turn banner, ETA), BACK during guidance ("Still
driving to?" prompt), a recent destination -> guidance, the Home/Work address screen typing.
Explained: the 199 mph reading is gps-sim.py restarting its scripted drive on every new connection
(a reconnect teleports the position ~800 m and Waze's motion estimate spikes); the real feed does
not jump. The imperial units once shown are Waze's own choice; the route chooser's map pane
was blank in its first seconds once (it then went straight to guidance).

## The Pi runtime for ATL (decision needed before the image bake)
The VM stack is Alpine's: android-translation-layer, art_standalone, bionic_translation and
libandroidfw are Alpine packages (musl), patched and rebuilt in place (build-atl.sh,
bionic_translation-waze.sh). Pi OS (Debian) has none of them, and host ART from source is a very
large build. Options, in order of least risk:
1. Alpine container on Pi OS: an aarch64 Alpine rootfs under /opt/atl-root with the same packages
   and the same patch/build scripts, entered by teslalinux-atl via systemd-nspawn or bwrap with
   /dev/dri, /dev/video*, the tacomp Wayland socket (/run/tesla) and PipeWire bind-mounted. Reuses
   everything the VM proved, Mesa v3d inside the container. Recommended.
2. Alpine as the whole Pi image (Alpine supports the Pi 4/5): simplest runtime, but tacomp,
   nginx, hostapd and provision.sh are written for Pi OS.
3. Build the ART/bionic stack from source on Debian: hours of build, upstream-divergent.
The launcher (host/phase1-pios/teslalinux-atl) already takes ATL_PREFIX; option 1 wraps its exec
in the container entry. Not started: no Pi here to test a bake.

## Oct 10: CPU profile in the VM (perf, cpu-clock, main thread at the main screen)
pixman 34 %, kernel 18 % (page faults from the cairo renderer copying each map frame to CPU
memory), GTK 14 %, cairo 12 %, musl memcpy/malloc 9 %; ATL itself about 2 % (libart 3.2 %,
api-impl 1.2 %, libtranslation_layer_main 0.7 %). The process is dominated by the llvmpipe threads
drawing the map. None of this exists on the Pi (GSK_RENDERER=gl, V3D, the SurfaceView texture stays
on the GPU), so tuning continues on hardware: measure frame time there before changing ATL.
Tooling: ~/probe-perf.sh on the VM; `apk add perf`, perf_event_paranoid=-1.
