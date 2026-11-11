/* teslalinux diagnostics: LD_PRELOAD=libtlsegv.so prints the fault pc/lr and a frame-pointer chain
 * mapped to library offsets, then chains to the handler that was installed before (ART's). It is
 * registered at the kernel level 15 s after start so it runs before ART's reporter, which has been
 * dying in its unwinder before printing anything useful. aarch64 only. */
#define _GNU_SOURCE
#include <pthread.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/syscall.h>
#include <ucontext.h>
#include <unistd.h>

struct kernel_sigaction {
	void *handler;
	unsigned long flags;
	void *restorer;
	unsigned long mask;
};

static struct kernel_sigaction prev;

static void dump_map(unsigned long addr, const char *name)
{
	FILE *f = fopen("/proc/self/maps", "r");
	char line[640];
	int found = 0;
	while (f && fgets(line, sizeof line, f)) {
		unsigned long a, b, off;
		char perms[8], path[400];
		path[0] = 0;
		if (sscanf(line, "%lx-%lx %7s %lx %*s %*s %399s", &a, &b, perms, &off, path) >= 4 && a <= addr && addr < b) {
			fprintf(stderr, "[segv] %s=0x%lx -> %s +0x%lx\n", name, addr, path[0] ? path : "[anon]", addr - a + off);
			found = 1;
			break;
		}
	}
	if (f)
		fclose(f);
	if (!found)
		fprintf(stderr, "[segv] %s=0x%lx -> unmapped\n", name, addr);
}

static void handler(int sig, siginfo_t *si, void *uc_)
{
	ucontext_t *uc = uc_;
	unsigned long pc = uc->uc_mcontext.pc, lr = uc->uc_mcontext.regs[30], fp = uc->uc_mcontext.regs[29];
	fprintf(stderr, "[segv] signal %d addr %p pc 0x%lx lr 0x%lx x0 0x%llx x1 0x%llx x8 0x%llx x19 0x%llx x20 0x%llx\n", sig, si->si_addr, pc, lr,
	        (unsigned long long)uc->uc_mcontext.regs[0], (unsigned long long)uc->uc_mcontext.regs[1], (unsigned long long)uc->uc_mcontext.regs[8],
	        (unsigned long long)uc->uc_mcontext.regs[19], (unsigned long long)uc->uc_mcontext.regs[20]);
	dump_map(pc, "pc");
	dump_map(lr, "lr");
	for (int i = 0; i < 14 && fp; i++) {
		unsigned long *f = (unsigned long *)fp;
		if ((unsigned long)f < 4096)
			break;
		char name[8];
		snprintf(name, sizeof name, "f%d", i);
		dump_map(f[1], name);
		if (f[0] <= fp)
			break;
		fp = f[0];
	}
	fflush(stderr);
	if (prev.handler && prev.handler != (void *)SIG_DFL && prev.handler != (void *)SIG_IGN) {
		if (prev.flags & SA_SIGINFO)
			((void (*)(int, siginfo_t *, void *))prev.handler)(sig, si, uc_);
		else
			((void (*)(int))prev.handler)(sig);
		return;
	}
	signal(sig, SIG_DFL);
	raise(sig);
}

/* who ends the process: exit/_exit/abort with the caller chain */
#include <dlfcn.h>
static void dump_callers(const char *what, int code)
{
	fprintf(stderr, "[exit] %s(%d) called on thread %ld\n", what, code, (long)syscall(SYS_gettid));
	unsigned long fp = (unsigned long)__builtin_frame_address(0);
	for (int i = 0; i < 16 && fp; i++) {
		unsigned long *f = (unsigned long *)fp;
		if ((unsigned long)f < 4096)
			break;
		char name[8];
		snprintf(name, sizeof name, "c%d", i);
		dump_map(f[1], name);
		if (f[0] <= fp)
			break;
		fp = f[0];
	}
	fflush(stderr);
}
void exit(int code)
{
	dump_callers("exit", code);
	void (*real)(int) = dlsym(RTLD_NEXT, "exit");
	real(code);
	_Exit(code);
}
void _exit(int code)
{
	dump_callers("_exit", code);
	void (*real)(int) = dlsym(RTLD_NEXT, "_exit");
	real(code);
	for (;;) ;
}
void abort(void)
{
	dump_callers("abort", 0);
	void (*real)(void) = dlsym(RTLD_NEXT, "abort");
	real();
	for (;;) ;
}

static void *installer(void *arg)
{
	sleep(15);
	struct kernel_sigaction sa;
	memset(&sa, 0, sizeof sa);
	sa.handler = (void *)handler;
	sa.flags = SA_SIGINFO | SA_ONSTACK | SA_NODEFER;
	syscall(SYS_rt_sigaction, SIGSEGV, &sa, &prev, 8);
	fprintf(stderr, "[segv] handler installed (previous %p flags 0x%lx)\n", prev.handler, prev.flags);
	return NULL;
}

__attribute__((constructor)) static void init(void)
{
	pthread_t t;
	if (pthread_create(&t, NULL, installer, NULL) == 0)
		pthread_detach(t);
}
