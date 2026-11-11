#!/bin/sh
# teslalinux: ViewTreeObserver listener state is idempotent (tl_vto_idempotent). AOSP's
# ViewTreeObserver keeps listeners in a copy-on-write list; adding when already listening or removing
# the last listener twice is a no-op. ATL printed "invalid state" and called exit(1) when the Java
# side reported "no listeners" with no frame-clock handler connected (Waze's map view removes its
# global layout listener in onStop after the view already detached), which ended the whole process:
# every "crash" on the way to the trip overview was this exit, with libwaze's atexit destructors
# then faulting. The redundant transitions are no-ops now; a view without a frame clock (not realized)
# simply has nothing to connect. Run by build-atl.sh from ~/atl-src. Idempotent.
set -eu
V=src/api-impl-jni/views/android_view_ViewTreeObserver.c
if ! grep -q "tl_vto_idempotent" "$V"; then
python3 - "$V" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
old='''	gulong signal_handle = _GET_LONG_FIELD(this, "onGlobalLayout_signal_handle");
	GdkFrameClock *clock = gtk_widget_get_frame_clock(window);

	if (have_listeners && !signal_handle) {
		/* this adds our callback before the existing handler, which means we effectively execute before the paint phase (after layout) */
		signal_handle = g_signal_connect(G_OBJECT(clock), "paint", G_CALLBACK(on_global_layout_callback), _REF(this)); // FIXME: cleanup callback for _UNREF
		_SET_LONG_FIELD(this, "onGlobalLayout_signal_handle", signal_handle);
	} else if (!have_listeners && signal_handle) {
		g_signal_handler_disconnect(G_OBJECT(clock), signal_handle);
		_SET_LONG_FIELD(this, "onGlobalLayout_signal_handle", 0);
	} else {
'''
new='''	gulong signal_handle = _GET_LONG_FIELD(this, "onGlobalLayout_signal_handle");
	GdkFrameClock *clock = gtk_widget_get_frame_clock(window);
	if (!clock) {
		/* tl_vto_idempotent: not realized, nothing to connect; the handle is stale if any */
		_SET_LONG_FIELD(this, "onGlobalLayout_signal_handle", 0);
		return;
	}

	if (have_listeners && !signal_handle) {
		/* this adds our callback before the existing handler, which means we effectively execute before the paint phase (after layout) */
		signal_handle = g_signal_connect(G_OBJECT(clock), "paint", G_CALLBACK(on_global_layout_callback), _REF(this)); // FIXME: cleanup callback for _UNREF
		_SET_LONG_FIELD(this, "onGlobalLayout_signal_handle", signal_handle);
	} else if (!have_listeners && signal_handle) {
		if (g_signal_handler_is_connected(G_OBJECT(clock), signal_handle))
			g_signal_handler_disconnect(G_OBJECT(clock), signal_handle);
		_SET_LONG_FIELD(this, "onGlobalLayout_signal_handle", 0);
	} else if (getenv("ATL_DEBUG_LAYOUT")) {
		/* tl_vto_idempotent: AOSP's listener list makes this a no-op (a second removal, an add while listening) */
'''
assert s.count(old)==1, s.count(old)
s=s.replace(old,new,1)
old2='''		fprintf(stderr, "Java_android_view_ViewTreeObserver_native_1set_1have_1global_1layout_1listeners: invalid state: have_listeners: %d, signal_handle: 0x%016w64x\\n", have_listeners, signal_handle);
		exit(1);
	}
'''
new2='''		fprintf(stderr, "Java_android_view_ViewTreeObserver_native_1set_1have_1global_1layout_1listeners: redundant: have_listeners: %d, signal_handle: 0x%016w64x\\n", have_listeners, signal_handle);
	}
'''
assert s.count(old2)==1, s.count(old2)
s=s.replace(old2,new2,1)
open(p,'w').write(s)
PY
fi
grep -c "tl_vto_idempotent" "$V"
