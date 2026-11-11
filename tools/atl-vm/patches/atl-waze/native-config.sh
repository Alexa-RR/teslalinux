#!/bin/sh
# teslalinux: AOSP Configuration/Display from the real window. ATL set only screenWidthDp/HeightDp
# (from the monitor) and left orientation, smallestScreenWidthDp, screenLayout, touchscreen,
# keyboard and navigation undefined, and the Display size came from the -w/-h arguments even after
# the compositor resized the window. Waze picks its navigation layout from orientation == LANDSCAPE.
# The configuration now follows the toplevel's size, is recomputed on every surface layout and
# changes are delivered through Activity.onConfigurationChanged (AOSP). Run by build-atl.sh from
# ~/atl-src. Idempotent.
set -eu
C=src/api-impl-jni/content/android_content_Context.c
A=src/api-impl-jni/app/android_app_Activity.c
if ! grep -q "activity_configuration_changed" "$A"; then
cat >> "$A" <<'EOF'

/* teslalinux: AOSP delivers configuration changes to the resumed activity (native-config.sh) */
void activity_configuration_changed(JNIEnv *env, jobject config)
{
	if (!activity_current)
		return;
	jmethodID method = _METHOD(_CLASS(activity_current), "performConfigurationChanged", "(Landroid/content/res/Configuration;)V");
	(*env)->CallVoidMethod(env, activity_current, method, config);
	if ((*env)->ExceptionCheck(env))
		(*env)->ExceptionDescribe(env);
}
EOF
fi
if ! grep -q "tl_apply_window_config" "$C"; then
python3 - "$C" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
NL=chr(10)
helper='''
/* ---- teslalinux: configuration from the real window (native-config.sh) ---- */
extern GtkWindow *window; /* the app window (main.c), same declaration as below in this file */
void activity_configuration_changed(JNIEnv *env, jobject config);
static int tl_config_width, tl_config_height;

/* AOSP Configuration.screenLayout size buckets from the smallest and longest side in dp */
static int tl_screen_layout_size(int sw, int lw)
{
	if (sw >= 720 && lw >= 960)
		return 4; /* SCREENLAYOUT_SIZE_XLARGE */
	if (sw >= 480 && lw >= 640)
		return 3; /* SCREENLAYOUT_SIZE_LARGE */
	if (sw >= 320 && lw >= 470)
		return 2; /* SCREENLAYOUT_SIZE_NORMAL */
	return 1;     /* SCREENLAYOUT_SIZE_SMALL */
}

/* fills the size-dependent and device fields of a Configuration for a window of width x height px */
static void tl_apply_window_config(JNIEnv *env, jobject config, int width, int height)
{
	int density_dpi = _GET_INT_FIELD(config, "densityDpi");
	if (density_dpi <= 0) {
		/* the device density: ATL_DENSITY_DPI (ro.sf.lcd_density, as DisplayMetrics.DENSITY_DEVICE and
		 * the asset configuration read it), mdpi without it */
		const char *dpi_env = g_getenv("ATL_DENSITY_DPI");
		density_dpi = dpi_env ? (int)g_ascii_strtoll(dpi_env, NULL, 10) : 0;
		if (density_dpi <= 0)
			density_dpi = 160;
		_SET_INT_FIELD(config, "densityDpi", density_dpi);
	}
	int width_dp = width * 160 / density_dpi;
	int height_dp = height * 160 / density_dpi;
	int sw = width_dp < height_dp ? width_dp : height_dp;
	int lw = width_dp < height_dp ? height_dp : width_dp;
	_SET_INT_FIELD(config, "screenWidthDp", width_dp);
	_SET_INT_FIELD(config, "screenHeightDp", height_dp);
	_SET_INT_FIELD(config, "smallestScreenWidthDp", sw);
	_SET_INT_FIELD(config, "orientation", width_dp >= height_dp ? 2 /* ORIENTATION_LANDSCAPE */ : 1 /* ORIENTATION_PORTRAIT */);
	int layout = tl_screen_layout_size(sw, lw);
	/* SCREENLAYOUT_LONG_YES when the aspect ratio is at least 5:3 (AOSP: long side * 3 / 5 >= short side) */
	layout |= (lw * 3 / 5 >= sw - 1) ? 0x20 : 0x10;
	layout |= 0x40; /* SCREENLAYOUT_LAYOUTDIR_LTR */
	layout |= 0x100; /* SCREENLAYOUT_ROUND_NO */
	_SET_INT_FIELD(config, "screenLayout", layout);
	/* a touch screen without hardware keys or a d-pad, like a phone (AOSP values) */
	_SET_INT_FIELD(config, "touchscreen", 3);        /* TOUCHSCREEN_FINGER */
	_SET_INT_FIELD(config, "keyboard", 1);           /* KEYBOARD_NOKEYS */
	_SET_INT_FIELD(config, "keyboardHidden", 2);     /* KEYBOARDHIDDEN_YES */
	_SET_INT_FIELD(config, "hardKeyboardHidden", 2); /* HARDKEYBOARDHIDDEN_YES */
	_SET_INT_FIELD(config, "navigation", 1);         /* NAVIGATION_NONAV */
	_SET_INT_FIELD(config, "navigationHidden", 2);   /* NAVIGATIONHIDDEN_YES */
	int ui_mode = _GET_INT_FIELD(config, "uiMode");
	if ((ui_mode & 0x0f) == 0)
		_SET_INT_FIELD(config, "uiMode", ui_mode | 1); /* UI_MODE_TYPE_NORMAL, night bits kept */
	/* the Display reports the same size (DisplayMetrics.setToDefaults reads these) */
	jclass display_class = (*env)->FindClass(env, "android/view/Display");
	(*env)->SetStaticIntField(env, display_class, (*env)->GetStaticFieldID(env, display_class, "window_width", "I"), width);
	(*env)->SetStaticIntField(env, display_class, (*env)->GetStaticFieldID(env, display_class, "window_height", "I"), height);
	tl_config_width = width;
	tl_config_height = height;
}

static void tl_window_size(int *width, int *height)
{
	*width = 0;
	*height = 0;
	if (window && gtk_widget_get_realized(GTK_WIDGET(window))) {
		*width = gtk_widget_get_width(GTK_WIDGET(window));
		*height = gtk_widget_get_height(GTK_WIDGET(window));
	}
	if (*width <= 0 || *height <= 0) {
		GdkDisplay *display = gdk_display_get_default();
		GdkMonitor *monitor = g_list_model_get_item(gdk_display_get_monitors(display), 0);
		GdkRectangle geometry;
		gdk_monitor_get_geometry(monitor, &geometry);
		*width = geometry.width;
		*height = geometry.height;
	}
}

/* the toplevel's surface was laid out: a size change is a configuration change */
static void tl_surface_layout_cb(GdkSurface *surface, int width, int height, gpointer user_data)
{
	int w = gtk_widget_get_width(GTK_WIDGET(window)), h = gtk_widget_get_height(GTK_WIDGET(window));
	if (w <= 0 || h <= 0 || (w == tl_config_width && h == tl_config_height))
		return;
	JNIEnv *env = get_jni_env();
	jobject config = _GET_STATIC_OBJ_FIELD(handle_cache.context.class, "sys_config", "Landroid/content/res/Configuration;");
	if (!config)
		return;
	tl_apply_window_config(env, config, w, h);
	jmethodID refresh = (*env)->GetStaticMethodID(env, handle_cache.context.class, "tlOnConfigurationChanged", "()V");
	(*env)->CallStaticVoidMethod(env, handle_cache.context.class, refresh);
	if ((*env)->ExceptionCheck(env))
		(*env)->ExceptionDescribe(env);
	activity_configuration_changed(env, config);
}

static void tl_connect_surface_layout(GtkWidget *widget, gpointer user_data)
{
	GdkSurface *surface = gtk_native_get_surface(GTK_NATIVE(widget));
	if (surface && !g_object_get_data(G_OBJECT(surface), "tl-config-connected")) {
		g_signal_connect(surface, "layout", G_CALLBACK(tl_surface_layout_cb), NULL);
		g_object_set_data(G_OBJECT(surface), "tl-config-connected", GINT_TO_POINTER(1));
	}
}
'''
old='''JNIEXPORT void JNICALL Java_android_content_Context_native_1updateConfig(JNIEnv *env, jclass this, jobject config)
{
	GdkDisplay *display = gdk_display_get_default();
	GdkMonitor *monitor = g_list_model_get_item(gdk_display_get_monitors(display), 0);
	GdkRectangle geometry;
	gdk_monitor_get_geometry(monitor, &geometry);

	_SET_INT_FIELD(config, "screenWidthDp", geometry.width);
	_SET_INT_FIELD(config, "screenHeightDp", geometry.height);
'''
new='''JNIEXPORT void JNICALL Java_android_content_Context_native_1updateConfig(JNIEnv *env, jclass this, jobject config)
{
	int width, height;
	tl_window_size(&width, &height);
	tl_apply_window_config(env, config, width, height);
	if (window) {
		if (gtk_widget_get_realized(GTK_WIDGET(window)))
			tl_connect_surface_layout(GTK_WIDGET(window), NULL);
		else
			g_signal_connect_after(window, "realize", G_CALLBACK(tl_connect_surface_layout), NULL);
	}
'''
assert s.count(old)==1, s.count(old)
s=s.replace(old, helper+old.replace(old,new),1)
open(p,'w').write(s)
PY
fi
grep -c "tl_apply_window_config" "$C"; grep -c "activity_configuration_changed" "$A"
