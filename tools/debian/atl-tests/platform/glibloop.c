/* glibloop.c: runs GLib's default main context on a thread of its own, for PlatformTest's network
 * phase on dalvikvm. In android-translation-layer the app's main thread iterates it (GTK's main
 * loop) and is attached to the VM, whose JavaVM pointer the executable gives api-impl's natives
 * (util.c's `jvm`); a bare dalvikvm has neither, so this sets `jvm` and attaches the loop thread,
 * and the D-Bus signals and idle callbacks ATL's ConnectivityManager uses get dispatched there.
 * Built and loaded by run.sh; never part of the runtime. */
#include <dlfcn.h>
#include <glib.h>
#include <jni.h>
#include <stdio.h>

static JavaVM *vm;

static gpointer loop_thread(gpointer data)
{
	JNIEnv *env;
	(*vm)->AttachCurrentThread(vm, (void **)&env, NULL);
	g_main_loop_run(g_main_loop_new(NULL, FALSE));
	return NULL;
}

JNIEXPORT void JNICALL Java_org_teslalinux_platformtest_PlatformTest_runGlibLoop(JNIEnv *env, jclass cls)
{
	static gboolean started;
	if (started)
		return;
	started = TRUE;
	(*env)->GetJavaVM(env, &vm);
	void *lib = dlopen("libtranslation_layer_main.so", RTLD_NOW | RTLD_NOLOAD);
	JavaVM **jvm = lib ? dlsym(lib, "jvm") : NULL;
	if (jvm && !*jvm)
		*jvm = vm;
	else if (!jvm)
		fprintf(stderr, "glibloop: no jvm symbol in libtranslation_layer_main.so: %s\n", dlerror());
	g_thread_new("glib-loop", loop_thread, NULL);
}
