#!/bin/sh
# teslalinux: android.os.Process.setThreadPriority(tid, prio) and setThreadPriority(prio) as AOSP's
# android_util_Process.cpp: setpriority(PRIO_PROCESS, tid, prio) on the Linux thread, errors as
# IllegalArgumentException (no such thread) / SecurityException (not permitted). The Java side
# declares both natives (replace/android/os/Process.java+os.repl). Android lets an app raise its
# threads up to its RLIMIT_NICE; a raise beyond that limit is clamped to the best allowed level
# instead of failing, so audio/display threads that ask for -16/-19 get as close as the host allows
# (give the app's user LimitNICE=-20 / rlimit nice in the image for the full range).
# Run by build-atl.sh from ~/atl-src. Idempotent.
set -eu
T=src/api-impl-jni/os/android_os_Process.c
if ! grep -q "tl_set_thread_priority" "$T"; then
python3 - "$T" <<'PY'
import sys
p = sys.argv[1]
s = open(p).read()
if "#include <errno.h>" not in s:
    s = s.replace("#include <sys/resource.h>\n", "#include <sys/resource.h>\n#include <errno.h>\n#include <stdio.h>\n#include <unistd.h>\n#include <sys/syscall.h>\n", 1)
s += r'''
/* tl_set_thread_priority: AOSP android_os_Process_setThreadPriority */
static void tl_set_thread_priority(JNIEnv *env, jint tid, jint pri)
{
	if (pri < -20)
		pri = -20;
	if (pri > 19)
		pri = 19;
	if (setpriority(PRIO_PROCESS, tid, pri) == 0)
		return;
	int err = errno;
	if ((err == EACCES || err == EPERM) && pri < getpriority(PRIO_PROCESS, tid)) {
		/* raising beyond RLIMIT_NICE: take the best level the limit allows (20 - rlim_cur) */
		struct rlimit rl;
		if (getrlimit(RLIMIT_NICE, &rl) == 0) {
			int best = 20 - (int)rl.rlim_cur;
			if (best > 19)
				best = 19;
			if (best < pri)
				best = pri;
			errno = 0;
			int cur = getpriority(PRIO_PROCESS, tid);
			if (errno == 0 && best < cur && setpriority(PRIO_PROCESS, tid, best) == 0)
				return;
			if (errno == 0 && best >= cur)
				return; /* already as high as allowed */
			err = errno;
		}
	}
	char buf[128];
	if (err == ESRCH || err == EINVAL) {
		snprintf(buf, sizeof(buf), "Given thread %d does not exist", tid);
		(*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/IllegalArgumentException"), buf);
	} else if (err == EPERM || err == EACCES) {
		snprintf(buf, sizeof(buf), "No permission to modify given thread %d", tid);
		(*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/SecurityException"), buf);
	} else {
		snprintf(buf, sizeof(buf), "Unknown error %d", err);
		(*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/RuntimeException"), buf);
	}
}

JNIEXPORT void JNICALL Java_android_os_Process_setThreadPriority__II(JNIEnv *env, jclass clazz, jint tid, jint pri)
{
	tl_set_thread_priority(env, tid, pri);
}

JNIEXPORT void JNICALL Java_android_os_Process_setThreadPriority__I(JNIEnv *env, jclass clazz, jint pri)
{
	tl_set_thread_priority(env, (jint)syscall(SYS_gettid), pri);
}
'''
open(p, "w").write(s)
PY
fi
grep -c "tl_set_thread_priority" "$T"
