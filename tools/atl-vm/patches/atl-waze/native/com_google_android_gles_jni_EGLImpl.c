/*
 * teslalinux: JNI for com.google.android.gles_jni.EGLImpl (javax.microedition.khronos.egl.EGL10).
 *
 * Semantics follow AOSP's core/jni/com_google_android_gles_jni_EGLImpl.cpp; the Java side passes the
 * native handles as longs. Entry points that ATL wraps for its GTK/Wayland surface model (display
 * lookup, config choice, pbuffer/window surfaces, make-current, swap, surface queries, current
 * surface) go through the bionic_egl* wrappers in libandroid/egl.c, the same ones the
 * android.opengl.EGL14 path uses, so both APIs share one surface bookkeeping. Everything else is
 * the EGL implementation of the host.
 *
 * ATL_DEBUG_EGL=1 (also read by the Java side) traces context creation here.
 */
#include <EGL/egl.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>

#include "../defines.h"
#include "../util.h"

#include "../../libandroid/native_window.h"

/* wrappers defined in libandroid/egl.c that native_window.h does not declare */
EGLBoolean bionic_eglChooseConfig(EGLDisplay display, EGLint *attrib_list, EGLConfig *configs, EGLint config_size, EGLint *num_config);
EGLSurface bionic_eglCreatePbufferSurface(EGLDisplay display, EGLConfig config, EGLint const *attrib_list);
EGLBoolean bionic_eglQuerySurface(EGLDisplay display, EGLSurface surface, EGLint attribute, EGLint *value);
EGLSurface bionic_eglGetCurrentSurface(EGLint readdraw);

static bool egl_debug(void)
{
	static int debug = -1;
	if (debug < 0)
		debug = getenv("ATL_DEBUG_EGL") != NULL;
	return debug;
}

/* ---- array helpers (AOSP uses GetPrimitiveArrayCritical for the attribute lists too) ------------ */

static jint *get_int_array_crit(JNIEnv *env, jintArray array)
{
	if (array != NULL)
		return (jint *)(*env)->GetPrimitiveArrayCritical(env, array, (jboolean *)0);
	return NULL; /* a NULL attrib_list means "defaults" to EGL */
}

static void release_int_array_crit(JNIEnv *env, jintArray array, jint *base, jint mode)
{
	if (array != NULL)
		(*env)->ReleasePrimitiveArrayCritical(env, array, base, mode);
}

static jlong *get_long_array_crit(JNIEnv *env, jlongArray array)
{
	if (array != NULL)
		return (jlong *)(*env)->GetPrimitiveArrayCritical(env, array, (jboolean *)0);
	return NULL;
}

static void release_long_array_crit(JNIEnv *env, jlongArray array, jlong *base, jint mode)
{
	if (array != NULL)
		(*env)->ReleasePrimitiveArrayCritical(env, array, base, mode);
}

/* EGLConfig is a pointer; the Java side carries configs as long[] */
static EGLConfig *alloc_config_array(jint config_size)
{
	return config_size > 0 ? calloc(config_size, sizeof(EGLConfig)) : NULL;
}

static void store_configs(jlong *out, EGLConfig *configs, jint n)
{
	for (jint i = 0; i < n; i++)
		out[i] = _INTPTR(configs[i]);
}

/* ---- display ------------------------------------------------------------------------------------ */

JNIEXPORT jlong JNICALL Java_com_google_android_gles_1jni_EGLImpl_native_1eglGetDisplay(JNIEnv *env, jobject this, jobject display)
{
	/* EGL_DEFAULT_DISPLAY is the only native display an Android app can name; ATL resolves it to the
	 * display the toolkit window lives on (Wayland or X11), see libandroid/egl.c */
	return _INTPTR(bionic_eglGetDisplay(0));
}

JNIEXPORT jboolean JNICALL Java_com_google_android_gles_1jni_EGLImpl_native_1eglInitialize(JNIEnv *env, jobject this, jlong display, jintArray _major_minor)
{
	EGLint major = 0, minor = 0;
	EGLBoolean ret = eglInitialize(_PTR(display), &major, &minor);
	if (_major_minor) {
		jint *major_minor = get_int_array_crit(env, _major_minor);
		if ((*env)->GetArrayLength(env, _major_minor) >= 2) {
			major_minor[0] = major;
			major_minor[1] = minor;
		}
		release_int_array_crit(env, _major_minor, major_minor, 0);
	}
	return ret;
}

JNIEXPORT jstring JNICALL Java_com_google_android_gles_1jni_EGLImpl_native_1eglQueryString(JNIEnv *env, jobject this, jlong display, jint name)
{
	const char *chars = eglQueryString(_PTR(display), name);
	if (chars == NULL)
		return NULL;
	return (*env)->NewStringUTF(env, chars);
}

JNIEXPORT jint JNICALL Java_com_google_android_gles_1jni_EGLImpl_native_1eglGetError(JNIEnv *env, jobject this)
{
	return eglGetError();
}

/* ---- configs ------------------------------------------------------------------------------------ */

JNIEXPORT jboolean JNICALL Java_com_google_android_gles_1jni_EGLImpl_native_1eglChooseConfig(JNIEnv *env, jobject this, jlong egl_display, jintArray attrib_list, jlongArray egl_configs, jint config_size, jintArray num_config)
{
	EGLConfig *configs = egl_configs ? alloc_config_array(config_size) : NULL;
	EGLint num = 0;

	jint *attrib_base = get_int_array_crit(env, attrib_list);
	EGLBoolean ret = bionic_eglChooseConfig(_PTR(egl_display), attrib_base, configs, config_size, &num);
	release_int_array_crit(env, attrib_list, attrib_base, JNI_ABORT);

	if (ret && configs) {
		jlong *configs_base = get_long_array_crit(env, egl_configs);
		store_configs(configs_base, configs, num < config_size ? num : config_size);
		release_long_array_crit(env, egl_configs, configs_base, 0);
	}
	free(configs);

	jint *num_config_base = get_int_array_crit(env, num_config);
	if (num_config_base)
		num_config_base[0] = num;
	release_int_array_crit(env, num_config, num_config_base, 0);
	return ret;
}

JNIEXPORT jboolean JNICALL Java_com_google_android_gles_1jni_EGLImpl_native_1eglGetConfigs(JNIEnv *env, jobject this, jlong egl_display, jlongArray egl_configs, jint config_size, jintArray num_config)
{
	EGLConfig *configs = egl_configs ? alloc_config_array(config_size) : NULL;
	EGLint num = 0;

	EGLBoolean ret = eglGetConfigs(_PTR(egl_display), configs, config_size, &num);

	if (ret && configs) {
		jlong *configs_base = get_long_array_crit(env, egl_configs);
		store_configs(configs_base, configs, num < config_size ? num : config_size);
		release_long_array_crit(env, egl_configs, configs_base, 0);
	}
	free(configs);

	jint *num_config_base = get_int_array_crit(env, num_config);
	if (num_config_base)
		num_config_base[0] = num;
	release_int_array_crit(env, num_config, num_config_base, 0);
	return ret;
}

JNIEXPORT jboolean JNICALL Java_com_google_android_gles_1jni_EGLImpl_native_1eglGetConfigAttrib(JNIEnv *env, jobject this, jlong display, jlong config, jint attribute, jintArray _value)
{
	EGLint v = 0;
	EGLBoolean ret = eglGetConfigAttrib(_PTR(display), _PTR(config), attribute, &v);
	jint *value = get_int_array_crit(env, _value);
	if (value)
		value[0] = v;
	release_int_array_crit(env, _value, value, 0);
	return ret;
}

/* ---- contexts ----------------------------------------------------------------------------------- */

JNIEXPORT jlong JNICALL Java_com_google_android_gles_1jni_EGLImpl_native_1eglCreateContext(JNIEnv *env, jobject this, jlong egl_display, jlong egl_config, jlong share_context, jintArray attrib_list)
{
	jint *attrib_base = get_int_array_crit(env, attrib_list);
	EGLContext egl_context = eglCreateContext(_PTR(egl_display), _PTR(egl_config), _PTR(share_context), attrib_base);
	release_int_array_crit(env, attrib_list, attrib_base, JNI_ABORT);
	if (egl_debug())
		fprintf(stderr, "[egl] eglCreateContext(display %p, config %p, share %p) = %p\n", _PTR(egl_display), _PTR(egl_config), _PTR(share_context), egl_context);
	return _INTPTR(egl_context);
}

JNIEXPORT jboolean JNICALL Java_com_google_android_gles_1jni_EGLImpl_native_1eglDestroyContext(JNIEnv *env, jobject this, jlong display, jlong context)
{
	return eglDestroyContext(_PTR(display), _PTR(context));
}

JNIEXPORT jboolean JNICALL Java_com_google_android_gles_1jni_EGLImpl_native_1eglQueryContext(JNIEnv *env, jobject this, jlong display, jlong context, jint attribute, jintArray _value)
{
	EGLint v = 0;
	EGLBoolean ret = eglQueryContext(_PTR(display), _PTR(context), attribute, &v);
	jint *value = get_int_array_crit(env, _value);
	if (value)
		value[0] = v;
	release_int_array_crit(env, _value, value, 0);
	return ret;
}

JNIEXPORT jboolean JNICALL Java_com_google_android_gles_1jni_EGLImpl_native_1eglMakeCurrent(JNIEnv *env, jobject this, jlong display, jlong draw_surface, jlong read_surface, jlong context)
{
	return bionic_eglMakeCurrent(_PTR(display), _PTR(draw_surface), _PTR(read_surface), _PTR(context));
}

JNIEXPORT jlong JNICALL Java_com_google_android_gles_1jni_EGLImpl_native_1eglGetCurrentContext(JNIEnv *env, jobject this)
{
	return _INTPTR(eglGetCurrentContext());
}

JNIEXPORT jlong JNICALL Java_com_google_android_gles_1jni_EGLImpl_native_1eglGetCurrentDisplay(JNIEnv *env, jobject this)
{
	return _INTPTR(eglGetCurrentDisplay());
}

JNIEXPORT jlong JNICALL Java_com_google_android_gles_1jni_EGLImpl_native_1eglGetCurrentSurface(JNIEnv *env, jobject this, jint readdraw)
{
	return _INTPTR(bionic_eglGetCurrentSurface(readdraw));
}

JNIEXPORT jboolean JNICALL Java_com_google_android_gles_1jni_EGLImpl_native_1eglReleaseThread(JNIEnv *env, jobject this)
{
	return eglReleaseThread();
}

/* ---- surfaces ----------------------------------------------------------------------------------- */

JNIEXPORT jlong JNICALL Java_com_google_android_gles_1jni_EGLImpl_native_1eglCreateWindowSurface(JNIEnv *env, jobject this, jlong display, jlong config, jobject surface, jintArray _attrib_list)
{
	struct ANativeWindow *native_window = ANativeWindow_fromSurface(env, surface);
	if (native_window == NULL)
		return 0;
	EGLint *attrib_list = get_int_array_crit(env, _attrib_list);
	EGLSurface ret = bionic_eglCreateWindowSurface(_PTR(display), _PTR(config), native_window, attrib_list);
	release_int_array_crit(env, _attrib_list, attrib_list, JNI_ABORT);
	ANativeWindow_release(native_window);
	return _INTPTR(ret);
}

JNIEXPORT jlong JNICALL Java_com_google_android_gles_1jni_EGLImpl_native_1eglCreatePbufferSurface(JNIEnv *env, jobject this, jlong display, jlong config, jintArray _attrib_list)
{
	EGLint *attrib_list = get_int_array_crit(env, _attrib_list);
	EGLSurface ret = bionic_eglCreatePbufferSurface(_PTR(display), _PTR(config), attrib_list);
	release_int_array_crit(env, _attrib_list, attrib_list, JNI_ABORT);
	return _INTPTR(ret);
}

JNIEXPORT jboolean JNICALL Java_com_google_android_gles_1jni_EGLImpl_native_1eglDestroySurface(JNIEnv *env, jobject this, jlong display, jlong surface)
{
	return bionic_eglDestroySurface(_PTR(display), _PTR(surface));
}

JNIEXPORT jboolean JNICALL Java_com_google_android_gles_1jni_EGLImpl_native_1eglQuerySurface(JNIEnv *env, jobject this, jlong display, jlong surface, jint attribute, jintArray _value)
{
	EGLint v = 0;
	EGLBoolean ret = bionic_eglQuerySurface(_PTR(display), _PTR(surface), attribute, &v);
	jint *value = get_int_array_crit(env, _value);
	if (value)
		value[0] = v;
	release_int_array_crit(env, _value, value, 0);
	return ret;
}

JNIEXPORT jboolean JNICALL Java_com_google_android_gles_1jni_EGLImpl_native_1eglSwapBuffers(JNIEnv *env, jobject this, jlong display, jlong surface)
{
	return bionic_eglSwapBuffers(_PTR(display), _PTR(surface));
}

/* ---- synchronisation ---------------------------------------------------------------------------- */

JNIEXPORT jboolean JNICALL Java_com_google_android_gles_1jni_EGLImpl_native_1eglWaitGL(JNIEnv *env, jobject this)
{
	return eglWaitGL();
}

JNIEXPORT jboolean JNICALL Java_com_google_android_gles_1jni_EGLImpl_native_1eglWaitNative(JNIEnv *env, jobject this, jint engine)
{
	return eglWaitNative(engine);
}
