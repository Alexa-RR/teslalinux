#!/bin/sh
# teslalinux: AOSP input batching for touch moves, and every pointer in the receiving view's space.
#
# AOSP never hands an app one MotionEvent per touch report: InputConsumer batches ACTION_MOVE samples
# and ViewRootImpl consumes the batch once per frame (Choreographer CALLBACK_INPUT), as one
# ACTION_MOVE whose current sample has every finger's newest position and whose history holds the
# earlier samples; a non-move event (a finger down or up) consumes the pending batch first, so order
# is kept. ATL dispatched each GDK touch update on its own. The car page sends one message per finger
# (Pointer Events), so in a pinch every MotionEvent moved only one finger while the other stood
# still; Waze's map (its native gesture recognizer reads getX(i)/getY(i) of each event) then never
# saw two fingers move apart and did not zoom, while one-finger pans worked.
#
# Here a capture controller on each toplevel holds GDK_TOUCH_UPDATE events (only sequences ATL
# tracks), records a snapshot of all tracked pointers per held update, and at the next frame
# (GdkFrameClock before-paint: after the frame's events, before the app's animation callbacks, as
# CALLBACK_INPUT runs before CALLBACK_ANIMATION) delivers the newest update through GTK once. That
# delivery carries the other fingers' newest positions, and the held samples become the event's
# history (MotionEvent(int, int, long[], int[], float[]), getHistoricalX & co.). A touch begin, end
# or cancel delivers the pending batch first. ATL_TOUCH_BATCH=0 turns batching off.
#
# Also: the other pointers of an event were left in the coordinates of whichever view last
# processed their own events; now every pointer is converted into the receiving view's coordinates
# from its window position, as AOSP transforms the whole event (dispatchTransformedTouchEvent).
#
# Run by build-atl.sh from ~/atl-src. Idempotent.
set -eu
F=src/api-impl-jni/views/android_view_View.c
if ! grep -q "tl_touch_batch_flush" "$F"; then
python3 - "$F" <<'PY'
import sys
p = sys.argv[1]
s = open(p).read()

anchor = '''// TODO: find a way to reconcile this with libandroid/input.c?
static gboolean on_pointer_event('''
assert s.count(anchor) == 1, "on_pointer_event anchor"
batch = r'''/* ---- tl_touch_batch: AOSP input batching (see tools/atl-vm/patches/atl-waze/native-touch-batch.sh) */
#define TL_BATCH_MAX_SAMPLES 32
#define TL_BATCH_POINTERS 16

struct tl_batch_sample {
	guint32 time;
	int n;
	int ids[TL_BATCH_POINTERS];
	float raw_x[TL_BATCH_POINTERS];
	float raw_y[TL_BATCH_POINTERS];
};

static struct tl_batch_sample tl_batch_samples[TL_BATCH_MAX_SAMPLES];
static int tl_batch_count;
static GdkEvent *tl_batch_event;  /* the newest held touch update */
static GdkEvent *tl_batch_replay; /* the update being delivered: the capture controller lets it pass */
/* while the batch is delivered: the samples before the current one, for the event's history */
static const struct tl_batch_sample *tl_batch_history;
static int tl_batch_history_len;
static GdkFrameClock *tl_batch_clock;
static gulong tl_batch_clock_handler;

static bool tl_touch_batch_enabled(void)
{
	static int enabled = -1;
	if (enabled < 0) {
		const char *v = getenv("ATL_TOUCH_BATCH");
		enabled = !(v && v[0] == '0');
	}
	return enabled;
}

/* the window position of pointer `id` in a sample, or false if the sample does not have it */
static bool tl_batch_sample_raw(const struct tl_batch_sample *s, int id, float *x, float *y)
{
	for (int i = 0; i < s->n; i++) {
		if (s->ids[i] == id) {
			*x = s->raw_x[i];
			*y = s->raw_y[i];
			return true;
		}
	}
	return false;
}

/* deliver the held batch through GTK now (from the frame clock, or before a non-move event) */
static void tl_touch_batch_flush(void)
{
	if (!tl_batch_event)
		return;
	GdkEvent *event = tl_batch_event;
	tl_batch_event = NULL;
	const struct tl_batch_sample *last = &tl_batch_samples[tl_batch_count - 1];
	/* every other finger at its newest position; the event's own one is set by on_pointer_event */
	for (int i = 0; i < last->n; i++) {
		int id = last->ids[i];
		if (id > 0 && id < MAX_POINTERS && pointers[id].id) {
			pointers[id].raw_x = last->raw_x[i];
			pointers[id].raw_y = last->raw_y[i];
		}
	}
	tl_batch_history = tl_batch_samples;
	tl_batch_history_len = tl_batch_count - 1;
	tl_batch_replay = event;
	gboolean handled = FALSE;
	GdkSurface *surface = gdk_event_get_surface(event);
	if (surface && !gdk_surface_is_destroyed(surface))
		g_signal_emit_by_name(surface, "event", event, &handled);
	tl_batch_replay = NULL;
	tl_batch_history = NULL;
	tl_batch_history_len = 0;
	tl_batch_count = 0;
	gdk_event_unref(event);
}

static void tl_touch_batch_on_frame(GdkFrameClock *clock, gpointer data)
{
	tl_touch_batch_flush();
}

static gboolean tl_touch_batch_capture(GtkEventControllerLegacy *controller, GdkEvent *event, gpointer data)
{
	if (event == tl_batch_replay)
		return FALSE;
	GdkEventType type = gdk_event_get_event_type(event);
	if (type == GDK_TOUCH_BEGIN || type == GDK_TOUCH_END || type == GDK_TOUCH_CANCEL) {
		/* AOSP: a non-move event consumes the pending batch first, so the app sees them in order */
		tl_touch_batch_flush();
		return FALSE;
	}
	if (type != GDK_TOUCH_UPDATE || !tl_touch_batch_enabled() || !pointer_indices)
		return FALSE;
	uintptr_t id = (uintptr_t)gdk_event_get_event_sequence(event);
	if (id == 0 || id >= MAX_POINTERS || !pointers[id].id)
		return FALSE; /* a sequence ATL does not track: GTK handles it as it would */
	if (tl_batch_count == TL_BATCH_MAX_SAMPLES)
		tl_touch_batch_flush(); /* a frame that never comes: keep the batch bounded */
	double x;
	double y;
	gdk_event_get_position(event, &x, &y);
	struct tl_batch_sample *sample = &tl_batch_samples[tl_batch_count];
	const struct tl_batch_sample *prev = tl_batch_count > 0 ? sample - 1 : NULL;
	sample->time = gdk_event_get_time(event);
	sample->n = 0;
	for (guint i = 0; i < pointer_indices->len && sample->n < TL_BATCH_POINTERS; i++) {
		struct pointer *pointer = (struct pointer *)g_ptr_array_index(pointer_indices, i);
		float rx = pointer->raw_x;
		float ry = pointer->raw_y;
		if ((uintptr_t)pointer->id == id) {
			rx = x;
			ry = y;
		} else if (prev) {
			tl_batch_sample_raw(prev, pointer->id, &rx, &ry);
		}
		sample->ids[sample->n] = pointer->id;
		sample->raw_x[sample->n] = rx;
		sample->raw_y[sample->n] = ry;
		sample->n++;
	}
	tl_batch_count++;
	if (tl_batch_event)
		gdk_event_unref(tl_batch_event);
	tl_batch_event = gdk_event_ref(event);

	GdkSurface *surface = gdk_event_get_surface(event);
	GdkFrameClock *clock = surface ? gdk_surface_get_frame_clock(surface) : NULL;
	if (!clock) {
		tl_touch_batch_flush();
		return TRUE;
	}
	if (clock != tl_batch_clock) {
		if (tl_batch_clock) {
			g_signal_handler_disconnect(tl_batch_clock, tl_batch_clock_handler);
			g_object_unref(tl_batch_clock);
		}
		tl_batch_clock = g_object_ref(clock);
		tl_batch_clock_handler = g_signal_connect(clock, "before-paint", G_CALLBACK(tl_touch_batch_on_frame), NULL);
	}
	gdk_frame_clock_request_phase(clock, GDK_FRAME_CLOCK_PHASE_BEFORE_PAINT);
	return TRUE;
}

/* the batching controller on the widget's toplevel (each toplevel once), ahead of every view */
static void tl_touch_batch_install(GtkWidget *widget)
{
	GtkRoot *root = gtk_widget_get_root(widget);
	if (!root || g_object_get_data(G_OBJECT(root), "tl-touch-batch"))
		return;
	GtkEventController *controller = GTK_EVENT_CONTROLLER(gtk_event_controller_legacy_new());
	gtk_event_controller_set_propagation_phase(controller, GTK_PHASE_CAPTURE);
	g_signal_connect(controller, "event", G_CALLBACK(tl_touch_batch_capture), NULL);
	gtk_widget_add_controller(GTK_WIDGET(root), controller);
	g_object_set_data(G_OBJECT(root), "tl-touch-batch", controller);
}

/* a batched ACTION_MOVE: the held samples as history, then the current one (`coords`, in the view's
 * coordinates), through MotionEvent(int source, int action, long[] eventTimes, int[] ids, float[] coords) */
static jobject tl_touch_batch_motion_event(JNIEnv *env, GtkWidget *widget, int action, jintArray ids, jfloatArray coords,
                                           int num_pointers, guint32 timestamp)
{
	static jmethodID batched;
	if (!batched)
		batched = (*env)->GetMethodID(env, handle_cache.motion_event.class, "<init>", "(II[J[I[F)V");
	int samples = tl_batch_history_len + 1;
	jlongArray times = (*env)->NewLongArray(env, samples);
	jfloatArray all = (*env)->NewFloatArray(env, samples * num_pointers * 4);
	float *cur = (*env)->GetFloatArrayElements(env, coords, NULL);
	for (int s = 0; s < tl_batch_history_len; s++) {
		const struct tl_batch_sample *sample = &tl_batch_history[s];
		jlong t = sample->time;
		(*env)->SetLongArrayRegion(env, times, s, 1, &t);
		for (int i = 0; i < num_pointers; i++) {
			struct pointer *pointer = (struct pointer *)g_ptr_array_index(pointer_indices, i);
			float v[4] = {cur[4 * i], cur[4 * i + 1], cur[4 * i + 2], cur[4 * i + 3]};
			float rx;
			float ry;
			if (tl_batch_sample_raw(sample, pointer->id, &rx, &ry)) {
				double wx = rx;
				double wy = ry;
				transform_coords_to_widget_relative(widget, &wx, &wy);
				v[0] = wx;
				v[1] = wy;
				v[2] = rx;
				v[3] = ry;
			}
			(*env)->SetFloatArrayRegion(env, all, (s * num_pointers + i) * 4, 4, v);
		}
	}
	jlong t = timestamp;
	(*env)->SetLongArrayRegion(env, times, samples - 1, 1, &t);
	(*env)->SetFloatArrayRegion(env, all, (samples - 1) * num_pointers * 4, num_pointers * 4, cur);
	(*env)->ReleaseFloatArrayElements(env, coords, cur, JNI_ABORT);
	jobject motion_event = (*env)->NewObject(env, handle_cache.motion_event.class, batched, SOURCE_TOUCHSCREEN, action, times, ids, all);
	(*env)->DeleteLocalRef(env, times);
	(*env)->DeleteLocalRef(env, all);
	return motion_event;
}

'''
s = s.replace(anchor, batch + anchor, 1)

# every pointer in the receiving view's coordinates; the batching controller on the toplevel
old = '''	pointers[id].coord_x = x;
	pointers[id].coord_y = y;
	pointers[id].raw_x = raw_x;
	pointers[id].raw_y = raw_y;
'''
assert s.count(old) == 1, "pointer update anchor"
new = old + '''	/* tl_touch_batch: the other pointers too, from their window positions (AOSP transforms the whole
	 * event into the receiving view's space) */
	for (guint i = 0; i < pointer_indices->len; i++) {
		struct pointer *other = (struct pointer *)g_ptr_array_index(pointer_indices, i);
		if (other == &pointers[id])
			continue;
		double ox = other->raw_x;
		double oy = other->raw_y;
		transform_coords_to_widget_relative(widget, &ox, &oy);
		other->coord_x = ox;
		other->coord_y = oy;
	}
	tl_touch_batch_install(widget);
'''
s = s.replace(old, new, 1)

# the batched event with its history
old = '''	jobject motion_event = (*env)->NewObject(env, handle_cache.motion_event.class, handle_cache.motion_event.constructor, SOURCE_TOUCHSCREEN, action, timestamp, ids, coords);
'''
assert s.count(old) == 1, "call_ontouch_callback constructor anchor"
new = '''	jobject motion_event;
	if (action == ACTION_MOVE && tl_batch_history_len > 0)
		motion_event = tl_touch_batch_motion_event(env, GTK_WIDGET(wrapper), action, ids, coords, num_pointers, timestamp);
	else
		motion_event = (*env)->NewObject(env, handle_cache.motion_event.class, handle_cache.motion_event.constructor, SOURCE_TOUCHSCREEN, action, timestamp, ids, coords);
'''
s = s.replace(old, new, 1)

# call_ontouch_callback comes before the batching block: declare what it uses
old = '''static bool call_ontouch_callback(WrapperWidget *wrapper, int action, struct pointer pointers[MAX_POINTERS], GPtrArray *pointer_indices, GtkPropagationPhase phase, guint32 timestamp, GdkEvent *event)
{'''
assert s.count(old) == 1, "call_ontouch_callback anchor"
new = '''static int tl_batch_history_len;
static jobject tl_touch_batch_motion_event(JNIEnv *env, GtkWidget *widget, int action, jintArray ids, jfloatArray coords,
                                           int num_pointers, guint32 timestamp);
''' + old
s = s.replace(old, new, 1)
open(p, "w").write(s)
PY
fi
grep -c "tl_touch_batch" "$F"
