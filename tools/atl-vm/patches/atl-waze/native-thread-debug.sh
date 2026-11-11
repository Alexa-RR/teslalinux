#!/bin/sh
# teslalinux: the JavaVM invocation table is wrapped right after JNI_CreateJavaVM.
# 1. Native threads that attach themselves (AttachCurrentThread/AsDaemon) get a thread-specific
#    key whose destructor detaches them if they are still attached when the thread exits. ART aborts
#    the whole process when such a thread exits attached ("Native thread exited without calling
#    DetachCurrentThread"), and its own warning suggests exactly this kind of destructor; Waze ends
#    its native daemon threads this way when a second map view is created.
# 2. ATL_DEBUG_THREADS=1 additionally logs every attach/detach with tid, name and calling code.
# Run by build-atl.sh from ~/atl-src. Idempotent.
set -eu
M=src/main-executable/main.c
if ! grep -q "tl_vm_table" "$M"; then
python3 - "$M" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
NL=chr(10)
helper = '''
/* ---- teslalinux: JavaVM invocation table wrapper (native-thread-debug.sh) ---- */
#include <sys/syscall.h>
#include <unistd.h>
static struct JNIInvokeInterface_ tl_vm_table;
static const struct JNIInvokeInterface_ *tl_vm_orig;
static JavaVM *tl_vm;
static pthread_key_t tl_attach_key;
static int tl_thread_trace;

static void tl_name_addr(void *addr, char *buf, size_t len)
{
	Dl_info info;
	buf[0] = 0;
	if (dladdr(addr, &info) && info.dli_fname && info.dli_fname[0]) {
		snprintf(buf, len, "%s%s%s", info.dli_fname, info.dli_sname ? ":" : "", info.dli_sname ? info.dli_sname : "");
		return;
	}
	FILE *f = fopen("/proc/self/maps", "r");
	char line[512];
	unsigned long a = (unsigned long)addr;
	while (f && fgets(line, sizeof line, f)) {
		unsigned long lo, hi;
		char path[256];
		path[0] = 0;
		if (sscanf(line, "%lx-%lx %*s %*s %*s %*s %255s", &lo, &hi, path) >= 2 && a >= lo && a < hi) {
			snprintf(buf, len, "%s+0x%lx", path[0] ? path : "?", a - lo);
			break;
		}
	}
	if (f)
		fclose(f);
	if (!buf[0])
		snprintf(buf, len, "%p", addr);
}

/* runs at the exit of a thread that attached itself and never detached */
static void tl_auto_detach(void *value)
{
	void *env = NULL;
	if (tl_vm_orig->GetEnv(tl_vm, &env, JNI_VERSION_1_6) != JNI_OK)
		return; /* already detached */
	if (tl_thread_trace)
		fprintf(stderr, "[thread] auto-detach tid=%ld (thread exited while attached)%c", (long)syscall(SYS_gettid), 10);
	tl_vm_orig->DetachCurrentThread(tl_vm);
}

static void tl_mark_attached(jint ret)
{
	if (ret == JNI_OK && pthread_getspecific(tl_attach_key) == NULL)
		pthread_setspecific(tl_attach_key, (void *)1);
}

static void tl_trace_attach(const char *kind, void *args, jint r, void *caller)
{
	if (!tl_thread_trace)
		return;
	char who[320];
	tl_name_addr(caller, who, sizeof who);
	const char *name = args ? ((JavaVMAttachArgs *)args)->name : NULL;
	fprintf(stderr, "[thread] %s tid=%ld name=%s ret=%d from %s%c", kind, (long)syscall(SYS_gettid), name ? name : "(null)", (int)r, who, 10);
}

static jint JNICALL tl_vm_attach(JavaVM *vm, void **env, void *args)
{
	jint r = tl_vm_orig->AttachCurrentThread(vm, env, args);
	tl_mark_attached(r);
	tl_trace_attach("attach", args, r, __builtin_return_address(0));
	return r;
}

static jint JNICALL tl_vm_attach_daemon(JavaVM *vm, void **env, void *args)
{
	jint r = tl_vm_orig->AttachCurrentThreadAsDaemon(vm, env, args);
	tl_mark_attached(r);
	tl_trace_attach("attach-daemon", args, r, __builtin_return_address(0));
	return r;
}

static jint JNICALL tl_vm_detach(JavaVM *vm)
{
	if (tl_thread_trace) {
		char who[320];
		tl_name_addr(__builtin_return_address(0), who, sizeof who);
		fprintf(stderr, "[thread] detach tid=%ld from %s%c", (long)syscall(SYS_gettid), who, 10);
	}
	pthread_setspecific(tl_attach_key, NULL);
	return tl_vm_orig->DetachCurrentThread(vm);
}

static void tl_install_vm_wrapper(JavaVM *vm)
{
	tl_thread_trace = getenv("ATL_DEBUG_THREADS") != NULL;
	tl_vm = vm;
	tl_vm_orig = *vm;
	tl_vm_table = *tl_vm_orig;
	tl_vm_table.AttachCurrentThread = tl_vm_attach;
	tl_vm_table.AttachCurrentThreadAsDaemon = tl_vm_attach_daemon;
	tl_vm_table.DetachCurrentThread = tl_vm_detach;
	pthread_key_create(&tl_attach_key, tl_auto_detach);
	*(const struct JNIInvokeInterface_ **)vm = &tl_vm_table;
	if (tl_thread_trace)
		fprintf(stderr, "[thread] JavaVM invocation table wrapped%c", 10);
}
'''
old = '''		fprintf(stderr, "JVM launched successfully\\n");
	}
'''
assert s.count(old) == 1, s.count(old)
s = s.replace(old, old + "	tl_install_vm_wrapper(jvm);" + NL, 1)
idx = s.index("int ret = JNI_CreateJavaVM(&jvm")
end = s.rfind(NL + "}" + NL, 0, idx) + 3
s = s[:end] + helper + s[end:]
if "#include <pthread.h>" not in s:
    # after main.c's `#define _GNU_SOURCE` (it is there for dladdr/Dl_info): on glibc the first system
    # header fixes the feature set, so a header above the define hides dladdr; musl did not care
    gnu = "#define _GNU_SOURCE" + NL
    if gnu in s:
        s = s.replace(gnu, gnu + "#include <pthread.h>" + NL, 1)
    else:
        s = "#include <pthread.h>" + NL + s
open(p, 'w').write(s)
PY
fi
grep -c "tl_vm_table" "$M"
