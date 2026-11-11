/* teslalinux: the host side of android.net.ConnectivityManager (tl_connectivity).
 * GLib's GNetworkMonitor knows whether a default route exists, whether the connection is metered
 * and whether it reaches the internet (full), a captive portal or only the local network. The
 * Java side turns that into Network/NetworkCapabilities/NetworkInfo and delivers callbacks;
 * "network-changed" (emitted on the GLib main loop) feeds ConnectivityManager.tlOnNetworkChanged. */
#include <gio/gio.h>

#include "../defines.h"
#include "../util.h"

#include "../generated_headers/android_net_ConnectivityManager.h"

static jclass tl_cm_class;
static jmethodID tl_cm_changed;

JNIEXPORT jboolean JNICALL Java_android_net_ConnectivityManager_nativeGetNetworkAvailable(JNIEnv *env, jclass this)
{
	return g_network_monitor_get_network_available(g_network_monitor_get_default());
}

JNIEXPORT jboolean JNICALL Java_android_net_ConnectivityManager_nativeIsMetered(JNIEnv *env, jclass this)
{
	return g_network_monitor_get_network_metered(g_network_monitor_get_default());
}

JNIEXPORT jint JNICALL Java_android_net_ConnectivityManager_nativeGetConnectivity(JNIEnv *env, jclass this)
{
	return (jint)g_network_monitor_get_connectivity(g_network_monitor_get_default());
}

static void on_network_changed(GNetworkMonitor *monitor, gboolean network_available, gpointer user_data)
{
	JNIEnv *env = get_jni_env();
	(*env)->CallStaticVoidMethod(env, tl_cm_class, tl_cm_changed, (jboolean)network_available);
	if ((*env)->ExceptionCheck(env))
		(*env)->ExceptionDescribe(env);
}

JNIEXPORT void JNICALL Java_android_net_ConnectivityManager_nativeStartMonitor(JNIEnv *env, jclass this)
{
	if (tl_cm_class)
		return;
	tl_cm_class = _REF(this);
	tl_cm_changed = (*env)->GetStaticMethodID(env, this, "tlOnNetworkChanged", "(Z)V");
	g_signal_connect(g_network_monitor_get_default(), "network-changed", G_CALLBACK(on_network_changed), NULL);
}
