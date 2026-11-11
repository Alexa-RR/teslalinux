#!/bin/sh
# teslalinux: Choreographer vsync from the GdkFrameClock (tl_vsync). AOSP paces animation and
# traversal callbacks with the display's vsync (DisplayEventReceiver); ATL's Choreographer ran a
# free 10 ms timer, so Compose animations ticked at up to 100 Hz, unsynchronised with GTK's frames.
# nativeScheduleVsync requests the UPDATE phase of the toplevel's frame clock and the update
# handler calls Choreographer.tlOnVsync with the frame time (CLOCK_MONOTONIC ns, System.nanoTime's
# base). Appended to android_view_ViewTreeObserver.c, which already has the frame clock plumbing.
# ATL_DEBUG_VSYNC=1 prints the vsync rate. Run by build-atl.sh from ~/atl-src. Idempotent.
set -eu
V=src/api-impl-jni/views/android_view_ViewTreeObserver.c
if ! grep -q "tl_vsync" "$V"; then
cat >> "$V" <<'EOF'

/* ---- teslalinux: Choreographer vsync from the GdkFrameClock (tl_vsync) ---- */
static jobject tl_vsync_choreographer = NULL; /* global ref of the main looper's Choreographer */
static jmethodID tl_vsync_method = NULL;
static gboolean tl_vsync_pending = FALSE;
static GdkFrameClock *tl_vsync_clock = NULL;
static gulong tl_vsync_handler = 0;

static void tl_on_frame_update(GdkFrameClock *clock, gpointer data)
{
	if (!tl_vsync_pending || !tl_vsync_choreographer)
		return;
	tl_vsync_pending = FALSE;
	JNIEnv *env = get_jni_env();
	jlong frame_time_ns = (jlong)gdk_frame_clock_get_frame_time(clock) * 1000;
	if (getenv("ATL_DEBUG_VSYNC")) {
		static int n = 0;
		static gint64 t0 = 0;
		if (n == 0)
			t0 = g_get_monotonic_time();
		if (++n % 120 == 0) {
			gint64 t = g_get_monotonic_time();
			fprintf(stderr, "[vsync] %d frames, %.1f Hz over the last 120\n", n, 120.0 * 1000000.0 / (double)(t - t0));
			t0 = t;
		}
	}
	(*env)->CallVoidMethod(env, tl_vsync_choreographer, tl_vsync_method, frame_time_ns);
	if ((*env)->ExceptionCheck(env))
		(*env)->ExceptionDescribe(env);
}

static gint64 tl_vsync_requested_at = 0; /* when the pending request was made */
static gint64 tl_vsync_requested_counter = 0;
/* watchdog: a request the frame clock has not served for 500 ms (the clock is frozen until the
 * compositor's first frame callback right after map) is requested again, so the Choreographer can
 * never wait forever for a vsync; ATL_DEBUG_VSYNC reports it */
static gboolean tl_vsync_watchdog(gpointer data)
{
	if (tl_vsync_pending && tl_vsync_clock) {
		gint64 waited = g_get_monotonic_time() - tl_vsync_requested_at;
		if (waited > 500000) {
			if (getenv("ATL_DEBUG_VSYNC"))
				fprintf(stderr, "[vsync] STALLED: request pending for %.0f ms, frame counter %" G_GINT64_FORMAT " (was %" G_GINT64_FORMAT "), window mapped=%d visible=%d\n",
				        waited / 1000.0, gdk_frame_clock_get_frame_counter(tl_vsync_clock), tl_vsync_requested_counter,
				        window ? gtk_widget_get_mapped(window) : -1, window ? gtk_widget_get_visible(window) : -1);
			gdk_frame_clock_request_phase(tl_vsync_clock, GDK_FRAME_CLOCK_PHASE_UPDATE);
		}
	}
	return G_SOURCE_CONTINUE;
}
JNIEXPORT jboolean JNICALL Java_android_view_Choreographer_nativeScheduleVsync(JNIEnv *env, jobject this)
{
	static int calls = 0;
	static guint watchdog = 0;
	GdkFrameClock *clock = window ? gtk_widget_get_frame_clock(window) : NULL;
	if (getenv("ATL_DEBUG_VSYNC") && ++calls <= 3)
		fprintf(stderr, "[vsync] scheduleVsync #%d window=%p clock=%p\n", calls, (void *)window, (void *)clock);
	if (!watchdog)
		watchdog = g_timeout_add(1000, tl_vsync_watchdog, NULL);
	if (!clock)
		return JNI_FALSE;
	tl_vsync_requested_at = g_get_monotonic_time();
	tl_vsync_requested_counter = gdk_frame_clock_get_frame_counter(clock);
	if (tl_vsync_clock != clock) {
		if (tl_vsync_clock) {
			g_signal_handler_disconnect(tl_vsync_clock, tl_vsync_handler);
			g_object_unref(tl_vsync_clock);
		}
		tl_vsync_clock = g_object_ref(clock);
		tl_vsync_handler = g_signal_connect(clock, "update", G_CALLBACK(tl_on_frame_update), NULL);
	}
	if (!tl_vsync_choreographer || !(*env)->IsSameObject(env, tl_vsync_choreographer, this)) {
		if (tl_vsync_choreographer)
			(*env)->DeleteGlobalRef(env, tl_vsync_choreographer);
		tl_vsync_choreographer = (*env)->NewGlobalRef(env, this);
		tl_vsync_method = _METHOD(_CLASS(this), "tlOnVsync", "(J)V");
	}
	tl_vsync_pending = TRUE;
	gdk_frame_clock_request_phase(clock, GDK_FRAME_CLOCK_PHASE_UPDATE);
	return JNI_TRUE;
}
EOF
fi
grep -c "tl_vsync" "$V"
