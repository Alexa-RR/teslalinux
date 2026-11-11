/*
 * teslalinux: JNI for android.opengl.EGL14 / EGL15 / EGLExt.
 *
 * Semantics follow AOSP's core/jni/android_opengl_EGL14.cpp (handle objects created through the
 * private (long) constructors, EGL_NO_* statics installed from _nativeClassInit, EGLConfig[] outputs
 * filled with fresh handle objects, offset/length checks on array parameters). Entry points that ATL
 * wraps for its GTK/Wayland surface model (display lookup, config choice, pbuffer/window surfaces,
 * make-current, swap, surface queries) go through the same bionic_egl* wrappers the javax EGL10 path
 * uses, so both APIs share one surface bookkeeping.
 */
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <stdbool.h>
#include <stdlib.h>
#include <string.h>

#include "../defines.h"
#include "../util.h"
#include "../../libandroid/native_window.h"

/* wrappers defined in libandroid/egl.c that native_window.h does not declare */
EGLBoolean bionic_eglChooseConfig(EGLDisplay display, EGLint *attrib_list, EGLConfig *configs, EGLint config_size, EGLint *num_config);
EGLSurface bionic_eglCreatePbufferSurface(EGLDisplay display, EGLConfig config, EGLint const *attrib_list);
EGLBoolean bionic_eglQuerySurface(EGLDisplay display, EGLSurface surface, EGLint attribute, EGLint *value);
EGLSurface bionic_eglGetCurrentSurface(EGLint readdraw);
EGLBoolean bionic_eglPresentationTimeANDROID(EGLDisplay dpy, EGLSurface surface, EGLnsecsANDROID time);

/* ---- handle classes --------------------------------------------------------------------------- */

static jclass eglconfigClass, eglcontextClass, egldisplayClass, eglsurfaceClass, eglsyncClass, eglimageClass;
static jmethodID eglconfigGetHandleID, eglcontextGetHandleID, egldisplayGetHandleID, eglsurfaceGetHandleID, eglsyncGetHandleID, eglimageGetHandleID;
static jmethodID eglconfigConstructor, eglcontextConstructor, egldisplayConstructor, eglsurfaceConstructor, eglsyncConstructor, eglimageConstructor;
static jobject eglNoContextObject, eglNoDisplayObject, eglNoSurfaceObject, eglNoSyncObject, eglNoImageObject;
static bool handles_initialized = false;

static void throw_exc(JNIEnv *env, const char *cls, const char *msg)
{
	jclass c = (*env)->FindClass(env, cls);
	if (c)
		(*env)->ThrowNew(env, c, msg);
}

static void init_handle_class(JNIEnv *env, const char *name, jclass *cls, jmethodID *get_handle, jmethodID *ctor)
{
	jclass local = (*env)->FindClass(env, name);
	*cls = (jclass)(*env)->NewGlobalRef(env, local);
	*get_handle = (*env)->GetMethodID(env, *cls, "getNativeHandle", "()J");
	*ctor = (*env)->GetMethodID(env, *cls, "<init>", "(J)V");
}

static void init_handles(JNIEnv *env)
{
	if (handles_initialized)
		return;
	init_handle_class(env, "android/opengl/EGLConfig", &eglconfigClass, &eglconfigGetHandleID, &eglconfigConstructor);
	init_handle_class(env, "android/opengl/EGLContext", &eglcontextClass, &eglcontextGetHandleID, &eglcontextConstructor);
	init_handle_class(env, "android/opengl/EGLDisplay", &egldisplayClass, &egldisplayGetHandleID, &egldisplayConstructor);
	init_handle_class(env, "android/opengl/EGLSurface", &eglsurfaceClass, &eglsurfaceGetHandleID, &eglsurfaceConstructor);
	init_handle_class(env, "android/opengl/EGLSync", &eglsyncClass, &eglsyncGetHandleID, &eglsyncConstructor);
	init_handle_class(env, "android/opengl/EGLImage", &eglimageClass, &eglimageGetHandleID, &eglimageConstructor);

	eglNoContextObject = (*env)->NewGlobalRef(env, (*env)->NewObject(env, eglcontextClass, eglcontextConstructor, (jlong)(intptr_t)EGL_NO_CONTEXT));
	eglNoDisplayObject = (*env)->NewGlobalRef(env, (*env)->NewObject(env, egldisplayClass, egldisplayConstructor, (jlong)(intptr_t)EGL_NO_DISPLAY));
	eglNoSurfaceObject = (*env)->NewGlobalRef(env, (*env)->NewObject(env, eglsurfaceClass, eglsurfaceConstructor, (jlong)(intptr_t)EGL_NO_SURFACE));
	eglNoSyncObject = (*env)->NewGlobalRef(env, (*env)->NewObject(env, eglsyncClass, eglsyncConstructor, (jlong)(intptr_t)EGL_NO_SYNC));
	eglNoImageObject = (*env)->NewGlobalRef(env, (*env)->NewObject(env, eglimageClass, eglimageConstructor, (jlong)(intptr_t)EGL_NO_IMAGE));
	handles_initialized = true;
}

static void *fromEGLHandle(JNIEnv *env, jmethodID mid, jobject obj)
{
	if (obj == NULL) {
		throw_exc(env, "java/lang/IllegalArgumentException", "Object is set to null.");
		return NULL;
	}
	return (void *)(intptr_t)(*env)->CallLongMethod(env, obj, mid);
}

static jobject toEGLHandle(JNIEnv *env, jclass cls, jmethodID con, void *handle)
{
	if (cls == eglcontextClass && handle == EGL_NO_CONTEXT)
		return eglNoContextObject;
	if (cls == egldisplayClass && handle == EGL_NO_DISPLAY)
		return eglNoDisplayObject;
	if (cls == eglsurfaceClass && handle == EGL_NO_SURFACE)
		return eglNoSurfaceObject;
	if (cls == eglsyncClass && handle == EGL_NO_SYNC)
		return eglNoSyncObject;
	if (cls == eglimageClass && handle == EGL_NO_IMAGE)
		return eglNoImageObject;
	return (*env)->NewObject(env, cls, con, (jlong)(intptr_t)handle);
}

/* AOSP validates array arguments like this: null array -> IllegalArgumentException("<name> == null"),
 * negative offset -> "<name>Offset < 0", too few remaining elements -> "length - offset < needed" */
static bool check_array(JNIEnv *env, jarray array, jint offset, jint needed, const char *name, const char *offset_name, const char *needed_msg)
{
	if (array == NULL) {
		char msg[128];
		snprintf(msg, sizeof(msg), "%s == null", name);
		throw_exc(env, "java/lang/IllegalArgumentException", msg);
		return false;
	}
	if (offset < 0) {
		char msg[128];
		snprintf(msg, sizeof(msg), "%s < 0", offset_name);
		throw_exc(env, "java/lang/IllegalArgumentException", msg);
		return false;
	}
	if ((*env)->GetArrayLength(env, array) - offset < needed) {
		throw_exc(env, "java/lang/IllegalArgumentException", needed_msg);
		return false;
	}
	return true;
}

/* attrib lists may be null (EGL treats NULL as "no attributes") */
static EGLint *get_int_attribs(JNIEnv *env, jintArray array, jint offset, jint **base)
{
	*base = NULL;
	if (array == NULL)
		return NULL;
	*base = (*env)->GetIntArrayElements(env, array, NULL);
	return (EGLint *)(*base + offset);
}

static void release_int_attribs(JNIEnv *env, jintArray array, jint *base)
{
	if (base)
		(*env)->ReleaseIntArrayElements(env, array, base, JNI_ABORT);
}

static EGLAttrib *get_long_attribs(JNIEnv *env, jlongArray array, jint offset, jlong **base)
{
	*base = NULL;
	if (array == NULL)
		return NULL;
	*base = (*env)->GetLongArrayElements(env, array, NULL);
	return (EGLAttrib *)(*base + offset);
}

static void release_long_attribs(JNIEnv *env, jlongArray array, jlong *base)
{
	if (base)
		(*env)->ReleaseLongArrayElements(env, array, base, JNI_ABORT);
}

/* ================================== EGL14 ===================================================== */

JNIEXPORT void JNICALL Java_android_opengl_EGL14__1nativeClassInit(JNIEnv *env, jclass eglClass)
{
	init_handles(env);
	(*env)->SetStaticObjectField(env, eglClass, (*env)->GetStaticFieldID(env, eglClass, "EGL_NO_CONTEXT", "Landroid/opengl/EGLContext;"), eglNoContextObject);
	(*env)->SetStaticObjectField(env, eglClass, (*env)->GetStaticFieldID(env, eglClass, "EGL_NO_DISPLAY", "Landroid/opengl/EGLDisplay;"), eglNoDisplayObject);
	(*env)->SetStaticObjectField(env, eglClass, (*env)->GetStaticFieldID(env, eglClass, "EGL_NO_SURFACE", "Landroid/opengl/EGLSurface;"), eglNoSurfaceObject);
}

JNIEXPORT jint JNICALL Java_android_opengl_EGL14_eglGetError(JNIEnv *env, jclass this)
{
	return eglGetError();
}

JNIEXPORT jobject JNICALL Java_android_opengl_EGL14_eglGetDisplay__I(JNIEnv *env, jclass this, jint display_id)
{
	/* ATL maps every display id onto the GTK display the app window lives on */
	return toEGLHandle(env, egldisplayClass, egldisplayConstructor, bionic_eglGetDisplay((EGLNativeDisplayType)(intptr_t)display_id));
}

JNIEXPORT jobject JNICALL Java_android_opengl_EGL14_eglGetDisplay__J(JNIEnv *env, jclass this, jlong display_id)
{
	return toEGLHandle(env, egldisplayClass, egldisplayConstructor, bionic_eglGetDisplay((EGLNativeDisplayType)(intptr_t)display_id));
}

JNIEXPORT jboolean JNICALL Java_android_opengl_EGL14_eglInitialize(JNIEnv *env, jclass this, jobject dpy, jintArray major_ref, jint majorOffset, jintArray minor_ref, jint minorOffset)
{
	EGLDisplay dpy_native = (EGLDisplay)fromEGLHandle(env, egldisplayGetHandleID, dpy);
	if ((*env)->ExceptionCheck(env))
		return JNI_FALSE;
	if (!check_array(env, major_ref, majorOffset, 1, "major", "majorOffset", "length - majorOffset < 1 < needed"))
		return JNI_FALSE;
	if (!check_array(env, minor_ref, minorOffset, 1, "minor", "minorOffset", "length - minorOffset < 1 < needed"))
		return JNI_FALSE;
	jint *major_base = (*env)->GetIntArrayElements(env, major_ref, NULL);
	jint *minor_base = (*env)->GetIntArrayElements(env, minor_ref, NULL);
	EGLBoolean ret = eglInitialize(dpy_native, (EGLint *)(major_base + majorOffset), (EGLint *)(minor_base + minorOffset));
	(*env)->ReleaseIntArrayElements(env, major_ref, major_base, 0);
	(*env)->ReleaseIntArrayElements(env, minor_ref, minor_base, 0);
	return ret;
}

JNIEXPORT jboolean JNICALL Java_android_opengl_EGL14_eglTerminate(JNIEnv *env, jclass this, jobject dpy)
{
	EGLDisplay dpy_native = (EGLDisplay)fromEGLHandle(env, egldisplayGetHandleID, dpy);
	if ((*env)->ExceptionCheck(env))
		return JNI_FALSE;
	return eglTerminate(dpy_native);
}

JNIEXPORT jstring JNICALL Java_android_opengl_EGL14_eglQueryString(JNIEnv *env, jclass this, jobject dpy, jint name)
{
	EGLDisplay dpy_native = (EGLDisplay)fromEGLHandle(env, egldisplayGetHandleID, dpy);
	if ((*env)->ExceptionCheck(env))
		return NULL;
	const char *chars = eglQueryString(dpy_native, name);
	return chars ? (*env)->NewStringUTF(env, chars) : NULL;
}

/* shared by eglGetConfigs / eglChooseConfig: fill the EGLConfig[] with handle objects */
static void store_configs(JNIEnv *env, jobjectArray configs_ref, jint configsOffset, EGLConfig *configs, jint count)
{
	for (jint i = 0; i < count; i++) {
		jobject config_obj = toEGLHandle(env, eglconfigClass, eglconfigConstructor, configs[i]);
		(*env)->SetObjectArrayElement(env, configs_ref, configsOffset + i, config_obj);
		(*env)->DeleteLocalRef(env, config_obj);
	}
}

JNIEXPORT jboolean JNICALL Java_android_opengl_EGL14_eglGetConfigs(JNIEnv *env, jclass this, jobject dpy, jobjectArray configs_ref, jint configsOffset, jint config_size, jintArray num_config_ref, jint num_configOffset)
{
	EGLDisplay dpy_native = (EGLDisplay)fromEGLHandle(env, egldisplayGetHandleID, dpy);
	if ((*env)->ExceptionCheck(env))
		return JNI_FALSE;
	if (configs_ref != NULL && !check_array(env, (jarray)configs_ref, configsOffset, config_size, "configs", "configsOffset", "length - configsOffset < config_size < needed"))
		return JNI_FALSE;
	if (!check_array(env, num_config_ref, num_configOffset, 1, "num_config", "num_configOffset", "length - num_configOffset < 1 < needed"))
		return JNI_FALSE;
	EGLConfig *configs = configs_ref ? calloc(config_size > 0 ? config_size : 1, sizeof(EGLConfig)) : NULL;
	jint *num_config_base = (*env)->GetIntArrayElements(env, num_config_ref, NULL);
	EGLBoolean ret = eglGetConfigs(dpy_native, configs, config_size, (EGLint *)(num_config_base + num_configOffset));
	jint count = num_config_base[num_configOffset];
	(*env)->ReleaseIntArrayElements(env, num_config_ref, num_config_base, 0);
	if (ret && configs)
		store_configs(env, configs_ref, configsOffset, configs, count < config_size ? count : config_size);
	free(configs);
	return ret;
}

JNIEXPORT jboolean JNICALL Java_android_opengl_EGL14_eglChooseConfig(JNIEnv *env, jclass this, jobject dpy, jintArray attrib_list_ref, jint attrib_listOffset, jobjectArray configs_ref, jint configsOffset, jint config_size, jintArray num_config_ref, jint num_configOffset)
{
	EGLDisplay dpy_native = (EGLDisplay)fromEGLHandle(env, egldisplayGetHandleID, dpy);
	if ((*env)->ExceptionCheck(env))
		return JNI_FALSE;
	if (!check_array(env, attrib_list_ref, attrib_listOffset, 0, "attrib_list", "attrib_listOffset", "attrib_list too short"))
		return JNI_FALSE;
	if (configs_ref != NULL && !check_array(env, (jarray)configs_ref, configsOffset, config_size, "configs", "configsOffset", "length - configsOffset < config_size < needed"))
		return JNI_FALSE;
	if (!check_array(env, num_config_ref, num_configOffset, 1, "num_config", "num_configOffset", "length - num_configOffset < 1 < needed"))
		return JNI_FALSE;
	jint *attrib_base;
	EGLint *attrib_list = get_int_attribs(env, attrib_list_ref, attrib_listOffset, &attrib_base);
	EGLConfig *configs = configs_ref ? calloc(config_size > 0 ? config_size : 1, sizeof(EGLConfig)) : NULL;
	jint *num_config_base = (*env)->GetIntArrayElements(env, num_config_ref, NULL);
	EGLBoolean ret = bionic_eglChooseConfig(dpy_native, attrib_list, configs, config_size, (EGLint *)(num_config_base + num_configOffset));
	jint count = num_config_base[num_configOffset];
	(*env)->ReleaseIntArrayElements(env, num_config_ref, num_config_base, 0);
	release_int_attribs(env, attrib_list_ref, attrib_base);
	if (ret && configs)
		store_configs(env, configs_ref, configsOffset, configs, count < config_size ? count : config_size);
	free(configs);
	return ret;
}

JNIEXPORT jboolean JNICALL Java_android_opengl_EGL14_eglGetConfigAttrib(JNIEnv *env, jclass this, jobject dpy, jobject config, jint attribute, jintArray value_ref, jint offset)
{
	EGLDisplay dpy_native = (EGLDisplay)fromEGLHandle(env, egldisplayGetHandleID, dpy);
	EGLConfig config_native = (EGLConfig)fromEGLHandle(env, eglconfigGetHandleID, config);
	if ((*env)->ExceptionCheck(env))
		return JNI_FALSE;
	if (!check_array(env, value_ref, offset, 1, "value", "offset", "length - offset < 1 < needed"))
		return JNI_FALSE;
	jint *value_base = (*env)->GetIntArrayElements(env, value_ref, NULL);
	EGLBoolean ret = eglGetConfigAttrib(dpy_native, config_native, attribute, (EGLint *)(value_base + offset));
	(*env)->ReleaseIntArrayElements(env, value_ref, value_base, 0);
	return ret;
}

JNIEXPORT jobject JNICALL Java_android_opengl_EGL14__1eglCreateWindowSurface(JNIEnv *env, jclass this, jobject dpy, jobject config, jobject win, jintArray attrib_list_ref, jint offset)
{
	EGLDisplay dpy_native = (EGLDisplay)fromEGLHandle(env, egldisplayGetHandleID, dpy);
	EGLConfig config_native = (EGLConfig)fromEGLHandle(env, eglconfigGetHandleID, config);
	if ((*env)->ExceptionCheck(env))
		return NULL;
	if (!check_array(env, attrib_list_ref, offset, 0, "attrib_list", "offset", "attrib_list too short"))
		return NULL;
	if (win == NULL) {
		throw_exc(env, "java/lang/IllegalArgumentException", "Make sure the SurfaceView or associated SurfaceHolder has a valid Surface");
		return NULL;
	}
	struct ANativeWindow *window = ANativeWindow_fromSurface(env, win);
	if (window == NULL) {
		throw_exc(env, "java/lang/IllegalArgumentException", "Make sure the SurfaceView or associated SurfaceHolder has a valid Surface");
		return NULL;
	}
	jint *attrib_base;
	EGLint *attrib_list = get_int_attribs(env, attrib_list_ref, offset, &attrib_base);
	EGLSurface ret = bionic_eglCreateWindowSurface(dpy_native, config_native, window, attrib_list);
	release_int_attribs(env, attrib_list_ref, attrib_base);
	ANativeWindow_release(window); /* the wrapper holds its own reference for the surface's lifetime */
	return toEGLHandle(env, eglsurfaceClass, eglsurfaceConstructor, ret);
}

JNIEXPORT jobject JNICALL Java_android_opengl_EGL14__1eglCreateWindowSurfaceTexture(JNIEnv *env, jclass this, jobject dpy, jobject config, jobject win, jintArray attrib_list_ref, jint offset)
{
	/* On Android a SurfaceTexture is a GL consumer the producer side can render into; ATL has no
	 * SurfaceTexture streaming implementation, so this cannot produce a usable surface. */
	throw_exc(env, "java/lang/UnsupportedOperationException", "eglCreateWindowSurface(SurfaceTexture): SurfaceTexture is not implemented in this runtime");
	return NULL;
}

JNIEXPORT jobject JNICALL Java_android_opengl_EGL14_eglCreatePbufferSurface(JNIEnv *env, jclass this, jobject dpy, jobject config, jintArray attrib_list_ref, jint offset)
{
	EGLDisplay dpy_native = (EGLDisplay)fromEGLHandle(env, egldisplayGetHandleID, dpy);
	EGLConfig config_native = (EGLConfig)fromEGLHandle(env, eglconfigGetHandleID, config);
	if ((*env)->ExceptionCheck(env))
		return NULL;
	if (!check_array(env, attrib_list_ref, offset, 0, "attrib_list", "offset", "attrib_list too short"))
		return NULL;
	jint *attrib_base;
	EGLint *attrib_list = get_int_attribs(env, attrib_list_ref, offset, &attrib_base);
	EGLSurface ret = bionic_eglCreatePbufferSurface(dpy_native, config_native, attrib_list);
	release_int_attribs(env, attrib_list_ref, attrib_base);
	return toEGLHandle(env, eglsurfaceClass, eglsurfaceConstructor, ret);
}

JNIEXPORT jobject JNICALL Java_android_opengl_EGL14_eglCreatePixmapSurface(JNIEnv *env, jclass this, jobject dpy, jobject config, jint pixmap, jintArray attrib_list_ref, jint offset)
{
	/* same as AOSP: native pixmaps are not a thing on Android */
	throw_exc(env, "java/lang/UnsupportedOperationException", "eglCreatePixmapSurface");
	return NULL;
}

JNIEXPORT jboolean JNICALL Java_android_opengl_EGL14_eglDestroySurface(JNIEnv *env, jclass this, jobject dpy, jobject surface)
{
	EGLDisplay dpy_native = (EGLDisplay)fromEGLHandle(env, egldisplayGetHandleID, dpy);
	EGLSurface surface_native = (EGLSurface)fromEGLHandle(env, eglsurfaceGetHandleID, surface);
	if ((*env)->ExceptionCheck(env))
		return JNI_FALSE;
	return bionic_eglDestroySurface(dpy_native, surface_native);
}

JNIEXPORT jboolean JNICALL Java_android_opengl_EGL14_eglQuerySurface(JNIEnv *env, jclass this, jobject dpy, jobject surface, jint attribute, jintArray value_ref, jint offset)
{
	EGLDisplay dpy_native = (EGLDisplay)fromEGLHandle(env, egldisplayGetHandleID, dpy);
	EGLSurface surface_native = (EGLSurface)fromEGLHandle(env, eglsurfaceGetHandleID, surface);
	if ((*env)->ExceptionCheck(env))
		return JNI_FALSE;
	if (!check_array(env, value_ref, offset, 1, "value", "offset", "length - offset < 1 < needed"))
		return JNI_FALSE;
	jint *value_base = (*env)->GetIntArrayElements(env, value_ref, NULL);
	EGLBoolean ret = bionic_eglQuerySurface(dpy_native, surface_native, attribute, (EGLint *)(value_base + offset));
	(*env)->ReleaseIntArrayElements(env, value_ref, value_base, 0);
	return ret;
}

JNIEXPORT jboolean JNICALL Java_android_opengl_EGL14_eglBindAPI(JNIEnv *env, jclass this, jint api)
{
	return eglBindAPI(api);
}

JNIEXPORT jint JNICALL Java_android_opengl_EGL14_eglQueryAPI(JNIEnv *env, jclass this)
{
	return eglQueryAPI();
}

JNIEXPORT jboolean JNICALL Java_android_opengl_EGL14_eglWaitClient(JNIEnv *env, jclass this)
{
	return eglWaitClient();
}

JNIEXPORT jboolean JNICALL Java_android_opengl_EGL14_eglReleaseThread(JNIEnv *env, jclass this)
{
	return eglReleaseThread();
}

static jobject create_pbuffer_from_client_buffer(JNIEnv *env, jobject dpy, jint buftype, EGLClientBuffer buffer, jobject config, jintArray attrib_list_ref, jint offset)
{
	EGLDisplay dpy_native = (EGLDisplay)fromEGLHandle(env, egldisplayGetHandleID, dpy);
	EGLConfig config_native = (EGLConfig)fromEGLHandle(env, eglconfigGetHandleID, config);
	if ((*env)->ExceptionCheck(env))
		return NULL;
	if (!check_array(env, attrib_list_ref, offset, 0, "attrib_list", "offset", "attrib_list too short"))
		return NULL;
	jint *attrib_base;
	EGLint *attrib_list = get_int_attribs(env, attrib_list_ref, offset, &attrib_base);
	EGLSurface ret = eglCreatePbufferFromClientBuffer(dpy_native, buftype, buffer, config_native, attrib_list);
	release_int_attribs(env, attrib_list_ref, attrib_base);
	return toEGLHandle(env, eglsurfaceClass, eglsurfaceConstructor, ret);
}

JNIEXPORT jobject JNICALL Java_android_opengl_EGL14_eglCreatePbufferFromClientBuffer__Landroid_opengl_EGLDisplay_2IILandroid_opengl_EGLConfig_2_3II(JNIEnv *env, jclass this, jobject dpy, jint buftype, jint buffer, jobject config, jintArray attrib_list_ref, jint offset)
{
	return create_pbuffer_from_client_buffer(env, dpy, buftype, (EGLClientBuffer)(intptr_t)buffer, config, attrib_list_ref, offset);
}

JNIEXPORT jobject JNICALL Java_android_opengl_EGL14_eglCreatePbufferFromClientBuffer__Landroid_opengl_EGLDisplay_2IJLandroid_opengl_EGLConfig_2_3II(JNIEnv *env, jclass this, jobject dpy, jint buftype, jlong buffer, jobject config, jintArray attrib_list_ref, jint offset)
{
	return create_pbuffer_from_client_buffer(env, dpy, buftype, (EGLClientBuffer)(intptr_t)buffer, config, attrib_list_ref, offset);
}

JNIEXPORT jboolean JNICALL Java_android_opengl_EGL14_eglSurfaceAttrib(JNIEnv *env, jclass this, jobject dpy, jobject surface, jint attribute, jint value)
{
	EGLDisplay dpy_native = (EGLDisplay)fromEGLHandle(env, egldisplayGetHandleID, dpy);
	EGLSurface surface_native = (EGLSurface)fromEGLHandle(env, eglsurfaceGetHandleID, surface);
	if ((*env)->ExceptionCheck(env))
		return JNI_FALSE;
	return eglSurfaceAttrib(dpy_native, surface_native, attribute, value);
}

JNIEXPORT jboolean JNICALL Java_android_opengl_EGL14_eglBindTexImage(JNIEnv *env, jclass this, jobject dpy, jobject surface, jint buffer)
{
	EGLDisplay dpy_native = (EGLDisplay)fromEGLHandle(env, egldisplayGetHandleID, dpy);
	EGLSurface surface_native = (EGLSurface)fromEGLHandle(env, eglsurfaceGetHandleID, surface);
	if ((*env)->ExceptionCheck(env))
		return JNI_FALSE;
	return eglBindTexImage(dpy_native, surface_native, buffer);
}

JNIEXPORT jboolean JNICALL Java_android_opengl_EGL14_eglReleaseTexImage(JNIEnv *env, jclass this, jobject dpy, jobject surface, jint buffer)
{
	EGLDisplay dpy_native = (EGLDisplay)fromEGLHandle(env, egldisplayGetHandleID, dpy);
	EGLSurface surface_native = (EGLSurface)fromEGLHandle(env, eglsurfaceGetHandleID, surface);
	if ((*env)->ExceptionCheck(env))
		return JNI_FALSE;
	return eglReleaseTexImage(dpy_native, surface_native, buffer);
}

JNIEXPORT jboolean JNICALL Java_android_opengl_EGL14_eglSwapInterval(JNIEnv *env, jclass this, jobject dpy, jint interval)
{
	EGLDisplay dpy_native = (EGLDisplay)fromEGLHandle(env, egldisplayGetHandleID, dpy);
	if ((*env)->ExceptionCheck(env))
		return JNI_FALSE;
	return eglSwapInterval(dpy_native, interval);
}

JNIEXPORT jobject JNICALL Java_android_opengl_EGL14_eglCreateContext(JNIEnv *env, jclass this, jobject dpy, jobject config, jobject share_context, jintArray attrib_list_ref, jint offset)
{
	EGLDisplay dpy_native = (EGLDisplay)fromEGLHandle(env, egldisplayGetHandleID, dpy);
	EGLConfig config_native = (EGLConfig)fromEGLHandle(env, eglconfigGetHandleID, config);
	EGLContext share_context_native = (EGLContext)fromEGLHandle(env, eglcontextGetHandleID, share_context);
	if ((*env)->ExceptionCheck(env))
		return NULL;
	if (!check_array(env, attrib_list_ref, offset, 0, "attrib_list", "offset", "attrib_list too short"))
		return NULL;
	jint *attrib_base;
	EGLint *attrib_list = get_int_attribs(env, attrib_list_ref, offset, &attrib_base);
	EGLContext ret = eglCreateContext(dpy_native, config_native, share_context_native, attrib_list);
	release_int_attribs(env, attrib_list_ref, attrib_base);
	return toEGLHandle(env, eglcontextClass, eglcontextConstructor, ret);
}

JNIEXPORT jboolean JNICALL Java_android_opengl_EGL14_eglDestroyContext(JNIEnv *env, jclass this, jobject dpy, jobject ctx)
{
	EGLDisplay dpy_native = (EGLDisplay)fromEGLHandle(env, egldisplayGetHandleID, dpy);
	EGLContext ctx_native = (EGLContext)fromEGLHandle(env, eglcontextGetHandleID, ctx);
	if ((*env)->ExceptionCheck(env))
		return JNI_FALSE;
	return eglDestroyContext(dpy_native, ctx_native);
}

JNIEXPORT jboolean JNICALL Java_android_opengl_EGL14_eglMakeCurrent(JNIEnv *env, jclass this, jobject dpy, jobject draw, jobject read, jobject ctx)
{
	EGLDisplay dpy_native = (EGLDisplay)fromEGLHandle(env, egldisplayGetHandleID, dpy);
	EGLSurface draw_native = (EGLSurface)fromEGLHandle(env, eglsurfaceGetHandleID, draw);
	EGLSurface read_native = (EGLSurface)fromEGLHandle(env, eglsurfaceGetHandleID, read);
	EGLContext ctx_native = (EGLContext)fromEGLHandle(env, eglcontextGetHandleID, ctx);
	if ((*env)->ExceptionCheck(env))
		return JNI_FALSE;
	return bionic_eglMakeCurrent(dpy_native, draw_native, read_native, ctx_native);
}

JNIEXPORT jobject JNICALL Java_android_opengl_EGL14_eglGetCurrentContext(JNIEnv *env, jclass this)
{
	return toEGLHandle(env, eglcontextClass, eglcontextConstructor, eglGetCurrentContext());
}

JNIEXPORT jobject JNICALL Java_android_opengl_EGL14_eglGetCurrentSurface(JNIEnv *env, jclass this, jint readdraw)
{
	return toEGLHandle(env, eglsurfaceClass, eglsurfaceConstructor, bionic_eglGetCurrentSurface(readdraw));
}

JNIEXPORT jobject JNICALL Java_android_opengl_EGL14_eglGetCurrentDisplay(JNIEnv *env, jclass this)
{
	return toEGLHandle(env, egldisplayClass, egldisplayConstructor, eglGetCurrentDisplay());
}

JNIEXPORT jboolean JNICALL Java_android_opengl_EGL14_eglQueryContext(JNIEnv *env, jclass this, jobject dpy, jobject ctx, jint attribute, jintArray value_ref, jint offset)
{
	EGLDisplay dpy_native = (EGLDisplay)fromEGLHandle(env, egldisplayGetHandleID, dpy);
	EGLContext ctx_native = (EGLContext)fromEGLHandle(env, eglcontextGetHandleID, ctx);
	if ((*env)->ExceptionCheck(env))
		return JNI_FALSE;
	if (!check_array(env, value_ref, offset, 1, "value", "offset", "length - offset < 1 < needed"))
		return JNI_FALSE;
	jint *value_base = (*env)->GetIntArrayElements(env, value_ref, NULL);
	EGLBoolean ret = eglQueryContext(dpy_native, ctx_native, attribute, (EGLint *)(value_base + offset));
	(*env)->ReleaseIntArrayElements(env, value_ref, value_base, 0);
	return ret;
}

JNIEXPORT jboolean JNICALL Java_android_opengl_EGL14_eglWaitGL(JNIEnv *env, jclass this)
{
	return eglWaitGL();
}

JNIEXPORT jboolean JNICALL Java_android_opengl_EGL14_eglWaitNative(JNIEnv *env, jclass this, jint engine)
{
	return eglWaitNative(engine);
}

JNIEXPORT jboolean JNICALL Java_android_opengl_EGL14_eglSwapBuffers(JNIEnv *env, jclass this, jobject dpy, jobject surface)
{
	EGLDisplay dpy_native = (EGLDisplay)fromEGLHandle(env, egldisplayGetHandleID, dpy);
	EGLSurface surface_native = (EGLSurface)fromEGLHandle(env, eglsurfaceGetHandleID, surface);
	if ((*env)->ExceptionCheck(env))
		return JNI_FALSE;
	return bionic_eglSwapBuffers(dpy_native, surface_native);
}

JNIEXPORT jboolean JNICALL Java_android_opengl_EGL14_eglCopyBuffers(JNIEnv *env, jclass this, jobject dpy, jobject surface, jint target)
{
	/* same as AOSP: no native pixmaps on Android */
	throw_exc(env, "java/lang/UnsupportedOperationException", "eglCopyBuffers");
	return JNI_FALSE;
}

/* ================================== EGL15 ===================================================== */

JNIEXPORT void JNICALL Java_android_opengl_EGL15__1nativeClassInit(JNIEnv *env, jclass eglClass)
{
	init_handles(env);
	(*env)->SetStaticObjectField(env, eglClass, (*env)->GetStaticFieldID(env, eglClass, "EGL_NO_IMAGE", "Landroid/opengl/EGLImage;"), eglNoImageObject);
	(*env)->SetStaticObjectField(env, eglClass, (*env)->GetStaticFieldID(env, eglClass, "EGL_NO_SYNC", "Landroid/opengl/EGLSync;"), eglNoSyncObject);
}

JNIEXPORT jobject JNICALL Java_android_opengl_EGL15_eglCreateSync(JNIEnv *env, jclass this, jobject dpy, jint type, jlongArray attrib_list_ref, jint offset)
{
	EGLDisplay dpy_native = (EGLDisplay)fromEGLHandle(env, egldisplayGetHandleID, dpy);
	if ((*env)->ExceptionCheck(env))
		return NULL;
	if (!check_array(env, attrib_list_ref, offset, 0, "attrib_list", "offset", "attrib_list too short"))
		return NULL;
	jlong *attrib_base;
	EGLAttrib *attrib_list = get_long_attribs(env, attrib_list_ref, offset, &attrib_base);
	EGLSync ret = eglCreateSync(dpy_native, type, attrib_list);
	release_long_attribs(env, attrib_list_ref, attrib_base);
	return toEGLHandle(env, eglsyncClass, eglsyncConstructor, ret);
}

JNIEXPORT jboolean JNICALL Java_android_opengl_EGL15_eglGetSyncAttrib(JNIEnv *env, jclass this, jobject dpy, jobject sync, jint attribute, jlongArray value_ref, jint offset)
{
	EGLDisplay dpy_native = (EGLDisplay)fromEGLHandle(env, egldisplayGetHandleID, dpy);
	EGLSync sync_native = (EGLSync)fromEGLHandle(env, eglsyncGetHandleID, sync);
	if ((*env)->ExceptionCheck(env))
		return JNI_FALSE;
	if (!check_array(env, value_ref, offset, 1, "value", "offset", "length - offset < 1 < needed"))
		return JNI_FALSE;
	jlong *value_base = (*env)->GetLongArrayElements(env, value_ref, NULL);
	EGLAttrib value;
	EGLBoolean ret = eglGetSyncAttrib(dpy_native, sync_native, attribute, &value);
	value_base[offset] = value;
	(*env)->ReleaseLongArrayElements(env, value_ref, value_base, 0);
	return ret;
}

JNIEXPORT jboolean JNICALL Java_android_opengl_EGL15_eglDestroySync(JNIEnv *env, jclass this, jobject dpy, jobject sync)
{
	EGLDisplay dpy_native = (EGLDisplay)fromEGLHandle(env, egldisplayGetHandleID, dpy);
	EGLSync sync_native = (EGLSync)fromEGLHandle(env, eglsyncGetHandleID, sync);
	if ((*env)->ExceptionCheck(env))
		return JNI_FALSE;
	return eglDestroySync(dpy_native, sync_native);
}

JNIEXPORT jint JNICALL Java_android_opengl_EGL15_eglClientWaitSync(JNIEnv *env, jclass this, jobject dpy, jobject sync, jint flags, jlong timeout)
{
	EGLDisplay dpy_native = (EGLDisplay)fromEGLHandle(env, egldisplayGetHandleID, dpy);
	EGLSync sync_native = (EGLSync)fromEGLHandle(env, eglsyncGetHandleID, sync);
	if ((*env)->ExceptionCheck(env))
		return 0;
	return eglClientWaitSync(dpy_native, sync_native, flags, (EGLTime)timeout);
}

JNIEXPORT jobject JNICALL Java_android_opengl_EGL15_eglGetPlatformDisplay(JNIEnv *env, jclass this, jint platform, jlong native_display, jlongArray attrib_list_ref, jint offset)
{
	if (!check_array(env, attrib_list_ref, offset, 0, "attrib_list", "offset", "attrib_list too short"))
		return NULL;
	jlong *attrib_base;
	EGLAttrib *attrib_list = get_long_attribs(env, attrib_list_ref, offset, &attrib_base);
	EGLDisplay ret = eglGetPlatformDisplay(platform, (void *)(intptr_t)native_display, attrib_list);
	release_long_attribs(env, attrib_list_ref, attrib_base);
	return toEGLHandle(env, egldisplayClass, egldisplayConstructor, ret);
}

static jobject create_platform_surface(JNIEnv *env, jobject dpy, jobject config, jobject native_buffer, jlongArray attrib_list_ref, jint offset, bool window)
{
	EGLDisplay dpy_native = (EGLDisplay)fromEGLHandle(env, egldisplayGetHandleID, dpy);
	EGLConfig config_native = (EGLConfig)fromEGLHandle(env, eglconfigGetHandleID, config);
	if ((*env)->ExceptionCheck(env))
		return NULL;
	if (native_buffer == NULL) {
		throw_exc(env, "java/lang/IllegalArgumentException", window ? "native_window == null" : "native_pixmap == null");
		return NULL;
	}
	void *native = (*env)->GetDirectBufferAddress(env, native_buffer);
	if (native == NULL) {
		throw_exc(env, "java/lang/IllegalArgumentException", "Must use a native order direct Buffer");
		return NULL;
	}
	if (!check_array(env, attrib_list_ref, offset, 0, "attrib_list", "offset", "attrib_list too short"))
		return NULL;
	jlong *attrib_base;
	EGLAttrib *attrib_list = get_long_attribs(env, attrib_list_ref, offset, &attrib_base);
	EGLSurface ret = window ? eglCreatePlatformWindowSurface(dpy_native, config_native, native, attrib_list)
	                        : eglCreatePlatformPixmapSurface(dpy_native, config_native, native, attrib_list);
	release_long_attribs(env, attrib_list_ref, attrib_base);
	return toEGLHandle(env, eglsurfaceClass, eglsurfaceConstructor, ret);
}

JNIEXPORT jobject JNICALL Java_android_opengl_EGL15_eglCreatePlatformWindowSurface(JNIEnv *env, jclass this, jobject dpy, jobject config, jobject native_window, jlongArray attrib_list_ref, jint offset)
{
	return create_platform_surface(env, dpy, config, native_window, attrib_list_ref, offset, true);
}

JNIEXPORT jobject JNICALL Java_android_opengl_EGL15_eglCreatePlatformPixmapSurface(JNIEnv *env, jclass this, jobject dpy, jobject config, jobject native_pixmap, jlongArray attrib_list_ref, jint offset)
{
	return create_platform_surface(env, dpy, config, native_pixmap, attrib_list_ref, offset, false);
}

JNIEXPORT jboolean JNICALL Java_android_opengl_EGL15_eglWaitSync(JNIEnv *env, jclass this, jobject dpy, jobject sync, jint flags)
{
	EGLDisplay dpy_native = (EGLDisplay)fromEGLHandle(env, egldisplayGetHandleID, dpy);
	EGLSync sync_native = (EGLSync)fromEGLHandle(env, eglsyncGetHandleID, sync);
	if ((*env)->ExceptionCheck(env))
		return JNI_FALSE;
	return eglWaitSync(dpy_native, sync_native, flags);
}

JNIEXPORT jobject JNICALL Java_android_opengl_EGL15_eglCreateImage(JNIEnv *env, jclass this, jobject dpy, jobject context, jint target, jlong buffer, jlongArray attrib_list_ref, jint offset)
{
	EGLDisplay dpy_native = (EGLDisplay)fromEGLHandle(env, egldisplayGetHandleID, dpy);
	EGLContext context_native = (EGLContext)fromEGLHandle(env, eglcontextGetHandleID, context);
	if ((*env)->ExceptionCheck(env))
		return NULL;
	if (!check_array(env, attrib_list_ref, offset, 0, "attrib_list", "offset", "attrib_list too short"))
		return NULL;
	jlong *attrib_base;
	EGLAttrib *attrib_list = get_long_attribs(env, attrib_list_ref, offset, &attrib_base);
	EGLImage ret = eglCreateImage(dpy_native, context_native, target, (EGLClientBuffer)(intptr_t)buffer, attrib_list);
	release_long_attribs(env, attrib_list_ref, attrib_base);
	return toEGLHandle(env, eglimageClass, eglimageConstructor, ret);
}

JNIEXPORT jboolean JNICALL Java_android_opengl_EGL15_eglDestroyImage(JNIEnv *env, jclass this, jobject dpy, jobject image)
{
	EGLDisplay dpy_native = (EGLDisplay)fromEGLHandle(env, egldisplayGetHandleID, dpy);
	EGLImage image_native = (EGLImage)fromEGLHandle(env, eglimageGetHandleID, image);
	if ((*env)->ExceptionCheck(env))
		return JNI_FALSE;
	return eglDestroyImage(dpy_native, image_native);
}

/* ================================== EGLExt ==================================================== */

JNIEXPORT void JNICALL Java_android_opengl_EGLExt__1nativeClassInit(JNIEnv *env, jclass eglClass)
{
	init_handles(env);
}

JNIEXPORT jboolean JNICALL Java_android_opengl_EGLExt_eglPresentationTimeANDROID(JNIEnv *env, jclass this, jobject dpy, jobject sur, jlong time)
{
	EGLDisplay dpy_native = (EGLDisplay)fromEGLHandle(env, egldisplayGetHandleID, dpy);
	EGLSurface sur_native = (EGLSurface)fromEGLHandle(env, eglsurfaceGetHandleID, sur);
	if ((*env)->ExceptionCheck(env))
		return JNI_FALSE;
	return bionic_eglPresentationTimeANDROID(dpy_native, sur_native, (EGLnsecsANDROID)time);
}
