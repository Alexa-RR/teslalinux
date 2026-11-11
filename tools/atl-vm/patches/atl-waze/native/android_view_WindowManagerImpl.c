/*
 * teslalinux: WindowManager windows added by the app (PopupWindow, Compose popups, panels) as layers
 * of the main window (tl_window_layers). Replaces ATL's android_view_WindowManagerImpl.c, which put
 * each view into a GtkPopover pointing at x/y and ignored the window's size, gravity and flags.
 *
 * Android gives each such window its own surface, sized and placed by the window manager from the
 * WindowManager.LayoutParams. Here a TlWindowLayer fills the main window's GtkOverlay above
 * everything added before it (activity content, dialogs, earlier windows) and holds the root view's
 * WrapperWidget at the window frame, which Java computes (WindowManagerImpl.tlLayoutWindow: the
 * ViewRootImpl measure and the window manager's gravity placement) on every allocation of the
 * layer; a requestLayout of the root view queues that allocation through GTK.
 * Input: a window with FLAG_NOT_TOUCHABLE takes no touches; a touch-modal window (focusable and no
 * FLAG_NOT_TOUCH_MODAL) also takes the touches outside its frame and passes them to its root view
 * in window coordinates (AOSP delivers them to the modal window); any other window only takes the
 * touches inside its frame, the rest reach the windows below. FLAG_DIM_BEHIND dims what is below.
 */
#include <gtk/gtk.h>

#include "../defines.h"
#include "../util.h"
#include "AndroidLayout.h"
#include "../widgets/WrapperWidget.h"

#include "../generated_headers/android_view_WindowManagerImpl.h"

#define FLAG_DIM_BEHIND       0x00000002
#define FLAG_NOT_FOCUSABLE    0x00000008
#define FLAG_NOT_TOUCHABLE    0x00000010
#define FLAG_NOT_TOUCH_MODAL  0x00000020
#define MOTION_ACTION_DOWN    0
#define MOTION_ACTION_UP      1
#define MOTION_ACTION_CANCEL  3

extern GtkWidget *tl_main_overlay;

G_DECLARE_FINAL_TYPE(TlWindowLayer, tl_window_layer, TL, WINDOW_LAYER, GtkWidget)

struct _TlWindowLayer {
	GtkWidget parent_instance;
	GtkWidget *child;  /* the root view's WrapperWidget */
	GtkWidget *view_widget;
	jobject view;      /* global reference to the root View */
	int flags;
	float dim_amount;
	GdkRectangle frame; /* the window frame in layer (= main window) coordinates */
	gboolean outside_down;
};

G_DEFINE_TYPE(TlWindowLayer, tl_window_layer, GTK_TYPE_WIDGET)

static jclass tl_wm_class(JNIEnv *env)
{
	static jclass klass = NULL;
	if (!klass)
		klass = _REF((*env)->FindClass(env, "android/view/WindowManagerImpl"));
	return klass;
}

static gboolean tl_window_layer_touch_modal(TlWindowLayer *self)
{
	/* AOSP: FLAG_NOT_FOCUSABLE implies FLAG_NOT_TOUCH_MODAL */
	return !(self->flags & FLAG_NOT_FOCUSABLE) && !(self->flags & FLAG_NOT_TOUCH_MODAL);
}

static void tl_window_layer_measure(GtkWidget *widget, GtkOrientation orientation, int for_size, int *minimum, int *natural, int *minimum_baseline, int *natural_baseline)
{
	/* a layer never asks the main window for space; it takes the size the overlay gives it */
	*minimum = *natural = 0;
	*minimum_baseline = *natural_baseline = -1;
}

static void tl_window_layer_size_allocate(GtkWidget *widget, int width, int height, int baseline)
{
	TlWindowLayer *self = TL_WINDOW_LAYER(widget);
	if (!self->child || !self->view)
		return;
	JNIEnv *env = get_jni_env();
	static jmethodID layout = NULL;
	if (!layout)
		layout = _STATIC_METHOD(tl_wm_class(env), "tlLayoutWindow", "(Landroid/view/View;II[I)V");
	jintArray out = (*env)->NewIntArray(env, 4);
	(*env)->CallStaticVoidMethod(env, tl_wm_class(env), layout, self->view, width, height, out);
	if ((*env)->ExceptionCheck(env)) {
		(*env)->ExceptionDescribe(env);
		(*env)->ExceptionClear(env);
		(*env)->DeleteLocalRef(env, out);
		return;
	}
	jint rect[4];
	(*env)->GetIntArrayRegion(env, out, 0, 4, rect);
	(*env)->DeleteLocalRef(env, out);
	self->frame = (GdkRectangle){ rect[0], rect[1], rect[2], rect[3] };
	/* the root view was measured for exactly the frame (ViewRootImpl); let GTK's measure of it ask for the same */
	GtkLayoutManager *lm = self->view_widget ? gtk_widget_get_layout_manager(self->view_widget) : NULL;
	if (lm && ATL_IS_ANDROID_LAYOUT(lm)) {
		ATL_ANDROID_LAYOUT(lm)->width = rect[2];
		ATL_ANDROID_LAYOUT(lm)->height = rect[3];
	}
	int min, nat;
	gtk_widget_measure(self->child, GTK_ORIENTATION_HORIZONTAL, -1, &min, &nat, NULL, NULL);
	gtk_widget_measure(self->child, GTK_ORIENTATION_VERTICAL, rect[2], &min, &nat, NULL, NULL);
	gtk_widget_size_allocate(self->child, &self->frame, -1);
}

static void tl_window_layer_snapshot(GtkWidget *widget, GtkSnapshot *snapshot)
{
	TlWindowLayer *self = TL_WINDOW_LAYER(widget);
	if ((self->flags & FLAG_DIM_BEHIND) && self->dim_amount > 0) {
		GdkRGBA dim = { 0, 0, 0, self->dim_amount };
		gtk_snapshot_append_color(snapshot, &dim, &GRAPHENE_RECT_INIT(0, 0, gtk_widget_get_width(widget), gtk_widget_get_height(widget)));
	}
	if (self->child)
		gtk_widget_snapshot_child(widget, self->child, snapshot);
}

/* which touches the layer takes (GTK picks the topmost widget that contains the point) */
static gboolean tl_window_layer_contains(GtkWidget *widget, double x, double y)
{
	TlWindowLayer *self = TL_WINDOW_LAYER(widget);
	if (self->flags & FLAG_NOT_TOUCHABLE)
		return FALSE;
	if (tl_window_layer_touch_modal(self))
		return TRUE;
	return x >= self->frame.x && y >= self->frame.y && x < self->frame.x + self->frame.width && y < self->frame.y + self->frame.height;
}

static gboolean tl_window_layer_outside(TlWindowLayer *self, double x, double y)
{
	return !(x >= self->frame.x && y >= self->frame.y && x < self->frame.x + self->frame.width && y < self->frame.y + self->frame.height);
}

/* a touch-modal window gets the touches outside its frame, in its own coordinates */
static void tl_window_layer_modal_touch(TlWindowLayer *self, int action, double x, double y)
{
	JNIEnv *env = get_jni_env();
	static jmethodID touch = NULL;
	if (!touch)
		touch = _STATIC_METHOD(tl_wm_class(env), "tlModalOutsideTouch", "(Landroid/view/View;IFF)V");
	(*env)->CallStaticVoidMethod(env, tl_wm_class(env), touch, self->view, action, (jfloat)(x - self->frame.x), (jfloat)(y - self->frame.y));
	if ((*env)->ExceptionCheck(env))
		(*env)->ExceptionDescribe(env);
}

static void on_layer_pressed(GtkGestureClick *gesture, int n_press, double x, double y, TlWindowLayer *self)
{
	if (!tl_window_layer_touch_modal(self) || !tl_window_layer_outside(self, x, y))
		return; /* inside the frame: the window's own views handle it */
	gtk_gesture_set_state(GTK_GESTURE(gesture), GTK_EVENT_SEQUENCE_CLAIMED);
	self->outside_down = TRUE;
	tl_window_layer_modal_touch(self, MOTION_ACTION_DOWN, x, y);
}

static void on_layer_released(GtkGestureClick *gesture, int n_press, double x, double y, TlWindowLayer *self)
{
	if (!self->outside_down)
		return;
	self->outside_down = FALSE;
	tl_window_layer_modal_touch(self, MOTION_ACTION_UP, x, y);
}

static void on_layer_cancel(GtkGesture *gesture, GdkEventSequence *sequence, TlWindowLayer *self)
{
	if (!self->outside_down)
		return;
	self->outside_down = FALSE;
	tl_window_layer_modal_touch(self, MOTION_ACTION_CANCEL, self->frame.x - 1, self->frame.y - 1);
}

static void tl_window_layer_dispose(GObject *object)
{
	TlWindowLayer *self = TL_WINDOW_LAYER(object);
	if (self->child) {
		gtk_widget_unparent(self->child);
		g_object_unref(self->child);
		self->child = NULL;
	}
	if (self->view) {
		JNIEnv *env = get_jni_env();
		_UNREF(self->view);
		self->view = NULL;
	}
	G_OBJECT_CLASS(tl_window_layer_parent_class)->dispose(object);
}

static void tl_window_layer_init(TlWindowLayer *self)
{
	GtkGesture *click = gtk_gesture_click_new();
	gtk_gesture_single_set_touch_only(GTK_GESTURE_SINGLE(click), FALSE);
	gtk_event_controller_set_propagation_phase(GTK_EVENT_CONTROLLER(click), GTK_PHASE_BUBBLE);
	g_signal_connect(click, "pressed", G_CALLBACK(on_layer_pressed), self);
	g_signal_connect(click, "released", G_CALLBACK(on_layer_released), self);
	g_signal_connect(click, "cancel", G_CALLBACK(on_layer_cancel), self);
	gtk_widget_add_controller(GTK_WIDGET(self), GTK_EVENT_CONTROLLER(click));
}

static void tl_window_layer_class_init(TlWindowLayerClass *klass)
{
	GtkWidgetClass *widget_class = GTK_WIDGET_CLASS(klass);
	G_OBJECT_CLASS(klass)->dispose = tl_window_layer_dispose;
	widget_class->measure = tl_window_layer_measure;
	widget_class->size_allocate = tl_window_layer_size_allocate;
	widget_class->snapshot = tl_window_layer_snapshot;
	widget_class->contains = tl_window_layer_contains;
}

static void tl_window_layer_apply_flags(TlWindowLayer *self, int flags, float dim_amount)
{
	self->flags = flags;
	self->dim_amount = dim_amount;
	/* AOSP FLAG_NOT_FOCUSABLE: nothing in the window takes key focus */
	gtk_widget_set_can_focus(GTK_WIDGET(self), !(flags & FLAG_NOT_FOCUSABLE));
	gtk_widget_set_can_target(GTK_WIDGET(self), !(flags & FLAG_NOT_TOUCHABLE));
	gtk_widget_queue_allocate(GTK_WIDGET(self));
	gtk_widget_queue_draw(GTK_WIDGET(self));
}

JNIEXPORT jlong JNICALL Java_android_view_WindowManagerImpl_native_1addWindow(JNIEnv *env, jclass clazz, jobject view, jlong widget_ptr, jint flags, jfloat dim_amount)
{
	GtkWidget *view_widget = GTK_WIDGET(_PTR(widget_ptr));
	GtkWidget *wrapper = gtk_widget_get_parent(view_widget);
	TlWindowLayer *self = g_object_new(tl_window_layer_get_type(), NULL);
	g_object_ref_sink(self);
	self->view = _REF(view);
	self->view_widget = view_widget;
	self->child = g_object_ref(wrapper);
	if (gtk_widget_get_parent(wrapper))
		gtk_widget_unparent(wrapper);
	gtk_widget_set_parent(wrapper, GTK_WIDGET(self));
	tl_window_layer_apply_flags(self, flags, dim_amount);
	gtk_overlay_add_overlay(GTK_OVERLAY(tl_main_overlay), GTK_WIDGET(self));
	gtk_overlay_set_measure_overlay(GTK_OVERLAY(tl_main_overlay), GTK_WIDGET(self), FALSE);
	gtk_overlay_set_clip_overlay(GTK_OVERLAY(tl_main_overlay), GTK_WIDGET(self), TRUE);
	/* AOSP stacks the IME window above application windows: stay below the on-screen keyboard
	 * layer (native-osk.sh) when it is in the overlay */
	for (GtkWidget *c = gtk_widget_get_first_child(tl_main_overlay); c; c = gtk_widget_get_next_sibling(c)) {
		if (gtk_widget_has_css_class(c, "tl-osk")) {
			gtk_widget_insert_before(GTK_WIDGET(self), tl_main_overlay, c);
			break;
		}
	}
	return _INTPTR(self);
}

JNIEXPORT void JNICALL Java_android_view_WindowManagerImpl_native_1updateWindow(JNIEnv *env, jclass clazz, jlong layer_ptr, jint flags, jfloat dim_amount)
{
	if (!layer_ptr)
		return;
	tl_window_layer_apply_flags(TL_WINDOW_LAYER(_PTR(layer_ptr)), flags, dim_amount);
}

JNIEXPORT void JNICALL Java_android_view_WindowManagerImpl_native_1removeWindow(JNIEnv *env, jclass clazz, jlong layer_ptr)
{
	if (!layer_ptr)
		return;
	TlWindowLayer *self = TL_WINDOW_LAYER(_PTR(layer_ptr));
	if (gtk_widget_get_parent(GTK_WIDGET(self)) == tl_main_overlay)
		gtk_overlay_remove_overlay(GTK_OVERLAY(tl_main_overlay), GTK_WIDGET(self));
	g_object_run_dispose(G_OBJECT(self));
	g_object_unref(self);
}
