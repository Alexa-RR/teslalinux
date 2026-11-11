/*
 * teslalinux: the natives of android.os.FileObserver$ObserverThread, after AOSP's
 * frameworks/base/core/jni/android_util_FileObserver.cpp (Apache-2.0, Copyright (C) 2006 The
 * Android Open Source Project): one inotify descriptor, a blocking read loop on the observer thread
 * that calls ObserverThread.onEvent(wd, mask, name) for each event, and add/remove of watches.
 */

#include <errno.h>
#include <jni.h>
#include <stdio.h>
#include <sys/inotify.h>
#include <unistd.h>

JNIEXPORT jint JNICALL Java_android_os_FileObserver_00024ObserverThread_init(JNIEnv *env, jobject object)
{
	return (jint)inotify_init1(IN_CLOEXEC);
}

JNIEXPORT void JNICALL Java_android_os_FileObserver_00024ObserverThread_observe(JNIEnv *env, jobject object, jint fd)
{
	/* aligned for struct inotify_event; room for several events with names up to NAME_MAX */
	char event_buf[4096] __attribute__((aligned(__alignof__(struct inotify_event))));
	jclass cls = (*env)->GetObjectClass(env, object);
	jmethodID on_event = (*env)->GetMethodID(env, cls, "onEvent", "(IILjava/lang/String;)V");
	if (!on_event)
		return;

	while (1) {
		int event_pos = 0;
		ssize_t num_bytes = read(fd, event_buf, sizeof(event_buf));
		if (num_bytes < (ssize_t)sizeof(struct inotify_event)) {
			if (num_bytes < 0 && errno == EINTR)
				continue;
			fprintf(stderr, "FileObserver: observe() got a short event (%zd)\n", num_bytes);
			return;
		}
		while (num_bytes >= (ssize_t)sizeof(struct inotify_event)) {
			struct inotify_event *event = (struct inotify_event *)(event_buf + event_pos);
			jstring path = NULL;
			if (event->len > 0)
				path = (*env)->NewStringUTF(env, event->name);
			(*env)->CallVoidMethod(env, object, on_event, (jint)event->wd, (jint)event->mask, path);
			if ((*env)->ExceptionCheck(env)) {
				(*env)->ExceptionDescribe(env);
				(*env)->ExceptionClear(env);
			}
			if (path)
				(*env)->DeleteLocalRef(env, path);
			int event_size = sizeof(struct inotify_event) + event->len;
			num_bytes -= event_size;
			event_pos += event_size;
		}
	}
}

JNIEXPORT void JNICALL Java_android_os_FileObserver_00024ObserverThread_startWatching(JNIEnv *env, jobject object, jint fd, jobjectArray paths, jint mask, jintArray wfd_array)
{
	jsize count = (*env)->GetArrayLength(env, paths);
	jint *wfds = (*env)->GetIntArrayElements(env, wfd_array, NULL);
	if (!wfds)
		return;
	for (jsize i = 0; i < count; i++) {
		jstring path_string = (*env)->GetObjectArrayElement(env, paths, i);
		if (!path_string)
			continue;
		const char *path = (*env)->GetStringUTFChars(env, path_string, NULL);
		if (path) {
			wfds[i] = inotify_add_watch(fd, path, (uint32_t)mask);
			(*env)->ReleaseStringUTFChars(env, path_string, path);
		}
		(*env)->DeleteLocalRef(env, path_string);
	}
	(*env)->ReleaseIntArrayElements(env, wfd_array, wfds, 0);
}

JNIEXPORT void JNICALL Java_android_os_FileObserver_00024ObserverThread_stopWatching(JNIEnv *env, jobject object, jint fd, jintArray wfd_array)
{
	jsize count = (*env)->GetArrayLength(env, wfd_array);
	jint *wfds = (*env)->GetIntArrayElements(env, wfd_array, NULL);
	if (!wfds)
		return;
	for (jsize i = 0; i < count; i++) {
		if (wfds[i] >= 0)
			inotify_rm_watch(fd, wfds[i]);
	}
	(*env)->ReleaseIntArrayElements(env, wfd_array, wfds, JNI_ABORT);
}
