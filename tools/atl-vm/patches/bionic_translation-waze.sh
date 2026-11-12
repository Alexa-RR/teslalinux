#!/bin/sh
# teslalinux: patch bionic_translation (checkout at ~/bionic_translation) with the libc/pthread shims
# Waze's native libraries import but the shims lack, then build + install into ~/atl-prefix.
# Idempotent. Symbols found with tools/atl-vm/symcheck.sh on libwaze.so and libcrashlytics*.so:
#   __register_atfork (libwaze + every other lib), __strncat_chk (libwaze),
#   __memchr_chk, __pread64_chk, __readlink_chk (crashlytics)
set -eu
SRC="${BIONIC_SRC:-$HOME/bionic_translation}"   # teslalinux Debian VM: tools/debvm/build-dist.sh sets both
PREFIX="${ATL_PREFIX:-$HOME/atl-prefix}"
cd "$SRC"
$(command -v sudo || command -v doas) apk add -q elfutils-dev libbsd-dev libunwind-dev mesa-dev 2>/dev/null || true
log() { printf '\033[1;33m[patch-bionic]\033[0m %s\n' "$*"; }

if ! grep -q 'bionic___register_atfork' pthread_wrapper/libpthread.c; then
log "adding __register_atfork"
cat >> pthread_wrapper/libpthread.c <<'EOF'

/* teslalinux: bionic's libc exports __register_atfork(prepare, parent, child, dso_handle); the
 * NDK's crt wraps pthread_atfork() with it for every library, so any app library imports it. */
int bionic___register_atfork(void (*prepare)(void), void (*parent)(void), void (*child)(void), void *dso_handle)
{
	(void)dso_handle;
	return pthread_atfork(prepare, parent, child);
}
EOF
fi

if ! grep -q 'bionic___strncat_chk' libc/libc-chk.c; then
log "adding fortify wrappers: __strncat_chk __memchr_chk __pread64_chk __readlink_chk"
cat >> libc/libc-chk.c <<'EOF'

/* teslalinux: additional _FORTIFY_SOURCE wrappers imported by Waze / Crashlytics natives. The
 * compiler-supplied destination sizes are ignored, same as the wrappers above. */
#include <unistd.h>
char *bionic___strncat_chk(char *dest, const char *src, size_t n, size_t dest_len)
{
	(void)dest_len;
	return strncat(dest, src, n);
}

void *bionic___memchr_chk(const void *s, int c, size_t n, size_t s_len)
{
	(void)s_len;
	return memchr(s, c, n);
}

ssize_t bionic___pread64_chk(int fd, void *buf, size_t count, off_t offset, size_t buf_size)
{
	(void)buf_size;
	return pread(fd, buf, count, offset);
}

ssize_t bionic___readlink_chk(const char *path, char *buf, size_t size, size_t buf_size)
{
	(void)buf_size;
	return readlink(path, buf, size);
}
EOF
fi

# teslalinux: keep the linker WARN/ERROR diagnostics (missing symbols) but drop the per-relocation
# and per-lookup trace lines; they were ~290k log lines per Waze start, which costs seconds under
# emulation and is pure noise on the car. Idempotent.
sed -i -e "s/^#define TRACE_DEBUG	    1/#define TRACE_DEBUG	    0/" -e "s/^#define DO_TRACE_LOOKUP	    1/#define DO_TRACE_LOOKUP	    0/" -e "s/^#define DO_TRACE_RELO	    1/#define DO_TRACE_RELO	    0/" linker/linker_debug.h
grep -n "TRACE_DEBUG\|DO_TRACE_" linker/linker_debug.h | head -3
# the packed-relocation path prints one unconditional stderr line per relocation (~290k for libwaze.so)
sed -i 's/fprintf(stderr, "calling apkenv_reloc_library with packed reloc/if (0) &/' linker/linker.c
grep -c 'if (0) fprintf(stderr, "calling apkenv_reloc_library' linker/linker.c

if ! grep -q 'tl_thread_trace' pthread_wrapper/libpthread.c; then
log "adding ATL_DEBUG_THREADS thread trace (creation site, start routine, exit path)"
python3 - pthread_wrapper/libpthread.c <<'PY2'
import sys
p=sys.argv[1]; s=open(p).read()
old = """int bionic_pthread_create(bionic_pthread_t *thread, const bionic_attr_t *attr, void* (*start)(void*), void *arg)
{
	assert(thread && (!attr || IS_MAPPED(attr)));
	return pthread_create((pthread_t*)thread, (attr ? attr->glibc : NULL), start, arg);
}
"""
new = """/* teslalinux: ATL_DEBUG_THREADS traces every thread the app creates (tl_thread_trace) */
#include <stdio.h>
#include <stdlib.h>
#include <sys/syscall.h>
#include <signal.h>
struct tl_thread_start {
	void *(*start)(void *);
	void *arg;
	void *creator;
};

static int tl_thread_trace(void)
{
	static int enabled = -1;
	if (enabled < 0)
		enabled = getenv("ATL_DEBUG_THREADS") != NULL;
	return enabled;
}

static void tl_thread_name_addr(void *addr, char *buf, size_t len)
{
	FILE *f = fopen("/proc/self/maps", "r");
	char line[512];
	unsigned long a = (unsigned long)addr;
	buf[0] = 0;
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

static void tl_thread_exit_log(void *p)
{
	struct tl_thread_start *s = p;
	char a[320];
	static int dumped = 0;
	tl_thread_name_addr((void *)s->start, a, sizeof a);
	fprintf(stderr, "[thread] exit tid=%ld start=%s%c", (long)syscall(SYS_gettid), a, 10);
	free(s);
	/* ATL_DEBUG_THREADS_DUMP: ask the runtime for a thread dump (SIGQUIT) when the first app thread
	 * exits and give its signal catcher time to write it before this thread goes on exiting */
	if (getenv("ATL_DEBUG_THREADS_DUMP")) {
		if (!dumped) {
			dumped = 1;
			fprintf(stderr, "[thread] requesting thread dump%c", 10);
			FILE *t = fopen("/tmp/atl-dumpstacks", "w");
			if (t)
				fclose(t);
			kill(getpid(), SIGQUIT);
		}
		sleep(8); /* every exiting thread waits so the dump sees the process alive */
	}
}

static void *tl_thread_trampoline(void *p)
{
	struct tl_thread_start *s = p;
	char a[320], c[320];
	void *ret;
	tl_thread_name_addr((void *)s->start, a, sizeof a);
	tl_thread_name_addr(s->creator, c, sizeof c);
	fprintf(stderr, "[thread] start tid=%ld start=%s created-from %s%c", (long)syscall(SYS_gettid), a, c, 10);
	pthread_cleanup_push(tl_thread_exit_log, s);
	ret = s->start(s->arg);
	pthread_cleanup_pop(1);
	return ret;
}

int bionic_pthread_create(bionic_pthread_t *thread, const bionic_attr_t *attr, void* (*start)(void*), void *arg)
{
	assert(thread && (!attr || IS_MAPPED(attr)));
	if (tl_thread_trace()) {
		struct tl_thread_start *s = malloc(sizeof *s);
		s->start = start;
		s->arg = arg;
		s->creator = __builtin_return_address(0);
		return pthread_create((pthread_t*)thread, (attr ? attr->glibc : NULL), tl_thread_trampoline, s);
	}
	return pthread_create((pthread_t*)thread, (attr ? attr->glibc : NULL), start, arg);
}

void bionic_pthread_exit(void *retval)
{
	if (tl_thread_trace()) {
		char c[320];
		tl_thread_name_addr(__builtin_return_address(0), c, sizeof c);
		fprintf(stderr, "[thread] pthread_exit tid=%ld from %s%c", (long)syscall(SYS_gettid), c, 10);
	}
	pthread_exit(retval);
}
"""
assert s.count(old) == 1, s.count(old)
open(p, 'w').write(s.replace(old, new, 1))
PY2
fi
log "building"
# teslalinux: a mutex whose bytes match none of the three static initializers (tl_mutex_state).
# bionic only reads the type bits of the state word ((state >> 14) & 3) and ignores the rest of the
# struct, so a mutex that sits in memory with stale bytes (Waze's routing code starts with such
# ones) locks fine on Android; the shim aborted on it ("no such default initializer???"). The
# shim now initialises it from the type bits the way bionic would. Idempotent.
if ! grep -q 'tl_mutex_state' pthread_wrapper/libpthread.c; then
python3 - pthread_wrapper/libpthread.c <<'PY3'
import sys
p=sys.argv[1]; s=open(p).read()
old='''	// We might have been mapped by another thread in the meantime, otherwise fail
	assert(IS_MAPPED(mutex) && "no such default initializer???");
}
'''
new='''	// We might have been mapped by another thread in the meantime
	if (IS_MAPPED(mutex))
		return;

	/* tl_mutex_state: bionic reads only the type bits of the state word */
	int type = (mutex->__private[0] >> 14) & 3;
	pthread_mutexattr_t attr;
	pthread_mutexattr_init(&attr);
	pthread_mutexattr_settype(&attr, type == PTHREAD_MUTEX_RECURSIVE ? PTHREAD_MUTEX_RECURSIVE
	                                 : type == PTHREAD_MUTEX_ERRORCHECK ? PTHREAD_MUTEX_ERRORCHECK : PTHREAD_MUTEX_NORMAL);
	mutex->glibc = mmap(NULL, sizeof(*mutex->glibc), PROT_READ | PROT_WRITE, MAP_ANONYMOUS | MAP_PRIVATE, -1, 0);
	pthread_mutex_init(mutex->glibc, &attr);
	pthread_mutexattr_destroy(&attr);
}
'''
assert s.count(old)==1, s.count(old)
open(p,'w').write(s.replace(old,new,1))
PY3
fi

# teslalinux: the glibc objects behind bionic's mutexes, condition variables, rwlocks, semaphores and
# attributes live in one reserved arena (tl_obj_arena), so "is this one of ours?" is a range check
# instead of a mincore() system call. Every lock, unlock, wait and signal called is_mapped(), i.e.
# sysconf() plus mincore(), which also takes the kernel's mmap lock and so waits behind any other
# thread's mmap/munmap: ~15% of Waze's map render thread (Godot locks constantly) went into it, and
# more on the Pi's slower cores. Each object also had a whole page of its own (mmap per mutex).
# The check stays exact for what it is for: bionic's static initializers (pointer field 0, 0x4000,
# 0x8000) and stale bytes (tl_mutex_state) are never a slot-aligned address inside the arena, so they
# still get their lazy initialisation, which now runs under one lock and re-checks the field there,
# so two threads initialising the same static mutex at once agree on one object instead of each
# locking its own (no atomic instructions on the field: bionic's objects are only 4-byte aligned,
# and an exclusive load of the 8-byte pointer inside one faults with SIGBUS). Slots are reused
# through a free list and the arena is never unmapped. Idempotent.
if ! grep -q 'tl_obj_arena' pthread_wrapper/libpthread.c; then
python3 - pthread_wrapper/libpthread.c <<'PY4'
import re, sys
p = sys.argv[1]; s = open(p).read()

def rep(old, new):
    global s
    assert s.count(old) == 1, (old[:70], s.count(old))
    s = s.replace(old, new, 1)

def replace_function(head, body):
    """replace the whole function starting with `head` (up to its closing brace at column 0)"""
    global s
    i = s.index(head)
    j = s.index("\n}\n", i) + 3
    s = s[:i] + body + s[j:]

arena = '''/* teslalinux (tl_obj_arena): the glibc objects behind the bionic ones, in one reserved arena */
#include <stdlib.h>
#define TL_OBJ_SLOT 256
#define TL_OBJ_ARENA ((size_t)1 << 30) /* reserved address space; pages are committed when used */
static uintptr_t tl_obj_base; /* 0 until the first allocation */
static size_t tl_obj_used;
static void *tl_obj_free_list;
static pthread_mutex_t tl_obj_lock = PTHREAD_MUTEX_INITIALIZER;

static inline bool tl_obj_is_ours(const void *p)
{
	const uintptr_t base = __atomic_load_n(&tl_obj_base, __ATOMIC_ACQUIRE);
	const uintptr_t off = (uintptr_t)p - base;
	return base && off < TL_OBJ_ARENA && !(off & (TL_OBJ_SLOT - 1));
}

static void *tl_obj_alloc(size_t size)
{
	assert(size <= TL_OBJ_SLOT);
	pthread_mutex_lock(&tl_obj_lock);
	if (!tl_obj_base) {
		void *arena = mmap(NULL, TL_OBJ_ARENA, PROT_READ | PROT_WRITE, MAP_ANONYMOUS | MAP_PRIVATE | MAP_NORESERVE, -1, 0);
		if (arena == MAP_FAILED)
			abort();
		__atomic_store_n(&tl_obj_base, (uintptr_t)arena, __ATOMIC_RELEASE);
	}
	void *obj = tl_obj_free_list;
	if (obj) {
		tl_obj_free_list = *(void **)obj;
	} else {
		if (tl_obj_used + TL_OBJ_SLOT > TL_OBJ_ARENA)
			abort();
		obj = (void *)(tl_obj_base + tl_obj_used);
		tl_obj_used += TL_OBJ_SLOT;
	}
	pthread_mutex_unlock(&tl_obj_lock);
	memset(obj, 0, TL_OBJ_SLOT);
	return obj;
}

static void tl_obj_free(void *obj)
{
	if (!tl_obj_is_ours(obj))
		return;
	pthread_mutex_lock(&tl_obj_lock);
	*(void **)obj = tl_obj_free_list;
	tl_obj_free_list = obj;
	pthread_mutex_unlock(&tl_obj_lock);
}
#define TL_OBJ_FREE(x) do { tl_obj_free(x); (x) = NULL; } while (0)

/* lazy initialisation of a bionic object found uninitialised: one at a time, re-checked under the
 * lock (the pointer fields are read and written with plain, alignment-tolerant accesses) */
static pthread_mutex_t tl_obj_init_lock = PTHREAD_MUTEX_INITIALIZER;

'''
i = s.index("static bool is_mapped(void *mem, const size_t sz)")
j = s.index("\n}\n", i) + 3
s = s[:i] + arena + s[j:]
rep("#define IS_MAPPED(x) is_mapped(x->glibc, sizeof(*x))", "#define IS_MAPPED(x) tl_obj_is_ours((x)->glibc)")

# every glibc object comes from the arena and goes back to it
s, n = re.subn(r"mmap\(NULL, sizeof\(\*([a-z]+->glibc)\), PROT_READ \| PROT_WRITE, MAP_ANONYMOUS \| MAP_PRIVATE, -1, 0\)", r"tl_obj_alloc(sizeof(*\1))", s)
assert n >= 12, n
s, n = re.subn(r"munmap\(([a-z]+->glibc), sizeof\(\*[a-z]+->glibc\)\);", r"TL_OBJ_FREE(\1);", s)
assert n >= 7, n

# lazy initialisation: build the object, then install it atomically
replace_function("static void default_sem_init(bionic_sem_t *sem)\n{", '''static void default_sem_init(bionic_sem_t *sem)
{
	// Apparently some android apps (hearthstone) do not call sem_init()
	assert(sem);
	pthread_mutex_lock(&tl_obj_init_lock);
	if (!tl_obj_is_ours(sem->glibc))
		sem->glibc = tl_obj_alloc(sizeof(*sem->glibc)); /* zeroed: a semaphore with the value 0 */
	pthread_mutex_unlock(&tl_obj_init_lock);
}
''')
replace_function("static void default_rwlock_init(bionic_rwlock_t *rwlock)\n{", '''static void default_rwlock_init(bionic_rwlock_t *rwlock)
{
	// Apparently some android apps/libs (Qt5) do not call pthread_rwlock_init()
	assert(rwlock);
	pthread_mutex_lock(&tl_obj_init_lock);
	if (!tl_obj_is_ours(rwlock->glibc))
		rwlock->glibc = tl_obj_alloc(sizeof(*rwlock->glibc)); /* zeroed: PTHREAD_RWLOCK_INITIALIZER */
	pthread_mutex_unlock(&tl_obj_init_lock);
}
''')
replace_function("static void default_pthread_cond_init(bionic_cond_t *cond)\n{", '''static void default_pthread_cond_init(bionic_cond_t *cond)
{
	assert(cond);
	pthread_mutex_lock(&tl_obj_init_lock);
	if (!tl_obj_is_ours(cond->glibc))
		cond->glibc = tl_obj_alloc(sizeof(*cond->glibc)); /* zeroed: PTHREAD_COND_INITIALIZER */
	pthread_mutex_unlock(&tl_obj_init_lock);
}
''')
replace_function("static void default_pthread_mutex_init(bionic_mutex_t *mutex)\n{", '''static void default_pthread_mutex_init(bionic_mutex_t *mutex)
{
	assert(mutex);
	pthread_mutex_lock(&tl_obj_init_lock);
	if (tl_obj_is_ours(mutex->glibc)) {
		pthread_mutex_unlock(&tl_obj_init_lock);
		return;
	}
	bionic_mutex_t seen;
	memcpy(&seen, mutex, sizeof(seen));
	pthread_mutex_t *obj = tl_obj_alloc(sizeof(*obj));
	bool initialised = false;
	for (size_t i = 0; i < ARRAY_SIZE(bionic_mutex_init_map); i++) {
		if (!memcmp(&bionic_mutex_init_map[i].bionic, &seen, sizeof(seen))) {
			memcpy(obj, &bionic_mutex_init_map[i].glibc, sizeof(bionic_mutex_init_map[i].glibc));
			initialised = true;
			break;
		}
	}
	if (!initialised) {
		/* tl_mutex_state: bionic reads only the type bits of the state word */
		int type = (seen.__private[0] >> 14) & 3;
		pthread_mutexattr_t attr;
		pthread_mutexattr_init(&attr);
		pthread_mutexattr_settype(&attr, type == PTHREAD_MUTEX_RECURSIVE ? PTHREAD_MUTEX_RECURSIVE
		                                 : type == PTHREAD_MUTEX_ERRORCHECK ? PTHREAD_MUTEX_ERRORCHECK : PTHREAD_MUTEX_NORMAL);
		pthread_mutex_init(obj, &attr);
		pthread_mutexattr_destroy(&attr);
	}
	mutex->glibc = obj;
	pthread_mutex_unlock(&tl_obj_init_lock);
}
''')
assert "is_mapped(" not in s, "is_mapped still used"
open(p, 'w').write(s)
PY4
fi

# teslalinux: an open overlay hook (libc_bio_set_open_overlay_func). The path override hook only sees
# a path; the host's file overlays (ATL_FILE_OVERLAYS) must also know whether an open only reads, so
# they never stand in for an open that writes. Read-only open()/open64()/fopen()/fopen64()/freopen()
# and the fortified __open_2() ask the hook first; a descriptor it returns is used instead of opening
# the path, -1 opens the path as before. __open_2() and freopen() now also take the path overrides,
# like open() and fopen(). Idempotent.
if ! grep -q 'libc_bio_set_open_overlay_func' libc/libc-open-overrides.c; then
log "open overlay hook (open, fopen, freopen, __open_2)"
python3 - libc/libc-open-overrides.c libc/libc-musl.c libc/libc-chk.c <<'PY5'
import sys
ovr, musl, chk = sys.argv[1:4]

def rep(path, old, new):
    s = open(path).read()
    assert s.count(old) == 1, (path, old[:60], s.count(old))
    open(path, 'w').write(s.replace(old, new, 1))

rep(ovr, "/* open and fopen overrides */", '''/* teslalinux: the open overlay hook: given a path and open() flags of a read-only open, a descriptor
 * to use instead of opening the path, or -1 to open it */
typedef int (open_overlay_func_type)(const char *, int);
static open_overlay_func_type *open_overlay_func = NULL;

void libc_bio_set_open_overlay_func(open_overlay_func_type *func)
{
	open_overlay_func = func;
}

static int open_overlay(const char *path, int oflag)
{
	if (!open_overlay_func || (oflag & O_ACCMODE) != O_RDONLY || (oflag & (O_CREAT | O_TRUNC | O_TMPFILE)))
		return -1;
#ifdef O_PATH
	if (oflag & O_PATH)
		return -1;
#endif
	return open_overlay_func(path, oflag);
}

/* a stdio mode that only reads ("r", "rb", "re", ...), as open() flags */
static bool mode_only_reads(const char *mode, int *oflag)
{
	if (!mode || mode[0] != 'r' || strchr(mode, '+'))
		return false;
	*oflag = O_RDONLY | (strchr(mode, 'e') ? O_CLOEXEC : 0);
	return true;
}

/* freopen() with the path overrides and the open overlay; stream is glibc's FILE */
FILE *bionic_freopen_glibc(const char *filename, const char *mode, FILE *stream)
{
	if (!filename)
		return freopen(NULL, mode, stream);
	char *path = (char *)filename;
	bool free_path = (*apply_path_overrides_func)(&path);
	FILE *file;
	int oflag, fd;
	if (mode_only_reads(mode, &oflag) && (fd = open_overlay(path, oflag)) >= 0) {
		/* the stream is reopened on the overlay's descriptor, through its /proc/self/fd name */
		char proc[48];
		snprintf(proc, sizeof(proc), "/proc/self/fd/%d", fd);
		file = freopen(proc, mode, stream);
		close(fd);
	} else {
		file = freopen(path, mode, stream);
	}
	if (free_path)
		free(path);
	return file;
}

/* open and fopen overrides */''')
rep(ovr, '''	if(OPEN_NEEDS_MODE(oflag)) {
		va_list arg;
		va_start(arg, oflag);''', '''	fd = open_overlay(path, oflag);
	if (fd >= 0) {
		if (free_path)
			free(path);
		return fd;
	}

	if(OPEN_NEEDS_MODE(oflag)) {
		va_list arg;
		va_start(arg, oflag);''')
rep(ovr, '''	file = fopen(path, mode);
	if(free_path)''', '''	int oflag, fd;
	if (mode_only_reads(mode, &oflag) && (fd = open_overlay(path, oflag)) >= 0) {
		file = fdopen(fd, "r"); /* the descriptor already has the mode's O_CLOEXEC */
		if (!file)
			close(fd);
	} else {
		file = fopen(path, mode);
	}
	if(free_path)''')
rep(musl, '''FILE * bionic_freopen64(const char *filename, const char *opentype, FILE *stream)
{
	return freopen(filename, opentype, bionic_file_to_glibc_file(stream));
}''', '''FILE *bionic_freopen_glibc(const char *filename, const char *mode, FILE *stream);
FILE * bionic_freopen64(const char *filename, const char *opentype, FILE *stream)
{
	return bionic_freopen_glibc(filename, opentype, bionic_file_to_glibc_file(stream));
}''')
rep(musl, '''FILE * bionic_freopen(const char *filename, const char *opentype, FILE *stream)
{
	return freopen(filename, opentype, bionic_file_to_glibc_file(stream));
}''', '''FILE * bionic_freopen(const char *filename, const char *opentype, FILE *stream)
{
	return bionic_freopen_glibc(filename, opentype, bionic_file_to_glibc_file(stream));
}''')
rep(chk, '''int bionic___open_2(const char *pathname, int flags)
{
	flags |= O_LARGEFILE;
	return open(pathname, flags, 0);
}''', '''int bionic_open(char *path, int oflag, ...);
/* _FORTIFY_SOURCE's open() without O_CREAT: the same as open(), path overrides and overlay included */
int bionic___open_2(const char *pathname, int flags)
{
	return bionic_open((char *)pathname, flags | O_LARGEFILE);
}''')
PY5
fi

if ! grep -q "teslalinux: sysconf" libc/libc.c; then
log "sysconf: unknown names return -1/EINVAL, cache geometry 0 (glibc behaviour) instead of abort()"
python3 "${BIONIC_SYSCONF_PY:-$HOME/patch-bionic-sysconf.py}"
fi
if [ ! -f builddir/build.ninja ]; then rm -rf builddir
  meson setup builddir --prefix="$PREFIX" --libdir=lib -Dbuildtype=debugoptimized 2>&1 | tail -5
fi
ninja -C builddir 2>&1 | tail -8
meson install -C builddir --quiet
log "installed:"; ls -la "$PREFIX"/lib/lib*_bio.so* | head
nm -D "$PREFIX/lib/libpthread_bio.so" | grep -c bionic___register_atfork
nm -D "$PREFIX/lib/libc_bio.so" | grep -E "bionic___(strncat|memchr|pread64|readlink)_chk" | wc -l
log "done"
