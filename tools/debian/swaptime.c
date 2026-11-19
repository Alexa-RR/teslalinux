/* teslalinux: frame timing of an app under ATL, as an LD_PRELOAD shim (no uprobes on the Pi's kernel).
 *
 *   gcc -O2 -shared -fPIC -o swaptime.so swaptime.c -ldl
 *   LD_PRELOAD=/path/swaptime.so (in the app's environment; teslalinux-atl passes it on)
 *
 * Defines bionic_eglSwapBuffers, which bionic_translation resolves for the app's eglSwapBuffers before
 * libandroid's own (the preload comes first), times it and calls libandroid's. Every SWAPTIME_FRAMES
 * swaps (default 300) one line goes to stderr: frames per second over the window, and for the work
 * (the end of one swap to the start of the next: the app drawing its frame) and the swap itself (the
 * time it blocks: ATL's pacing) the median, 90th percentile and maximum in milliseconds, plus a
 * histogram of the frame period (start to start) in 8.33 ms bins (half a 60 Hz tick).
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

typedef unsigned int EGLBoolean;
typedef void *EGLDisplay;
typedef void *EGLSurface;

#define MAX_FRAMES 2000
static double work_ms[MAX_FRAMES], swap_ms[MAX_FRAMES], period_ms[MAX_FRAMES];
static int count, window = -1;
static double last_start, last_end, window_start;

static double now_ms(void)
{
	struct timespec ts;
	clock_gettime(CLOCK_MONOTONIC, &ts);
	return ts.tv_sec * 1e3 + ts.tv_nsec / 1e6;
}

static int cmp(const void *a, const void *b)
{
	double x = *(const double *)a, y = *(const double *)b;
	return x < y ? -1 : x > y;
}

static void stats(const char *name, double *v, int n, char *out, size_t len)
{
	qsort(v, n, sizeof(double), cmp);
	snprintf(out, len, "%s med %.1f p90 %.1f max %.1f", name, v[n / 2], v[n * 9 / 10], v[n - 1]);
}

static void report(double t)
{
	char w[96], s[96], hist[256] = "";
	int bins[8] = {0};
	for (int i = 0; i < count; i++) {
		int b = (int)(period_ms[i] / 8.333);
		bins[b > 7 ? 7 : b]++;
	}
	for (int b = 0; b < 8; b++) {
		char one[32];
		snprintf(one, sizeof(one), b < 7 ? " <%.0f:%d" : " >=%.0f:%d", b < 7 ? (b + 1) * 8.333 : 7 * 8.333, bins[b]);
		strncat(hist, one, sizeof(hist) - strlen(hist) - 1);
	}
	stats("work", work_ms, count, w, sizeof(w));
	stats("swap", swap_ms, count, s, sizeof(s));
	fprintf(stderr, "[swaptime] %d frames %.1f/s | %s | %s | period ms%s\n", count, count * 1000.0 / (t - window_start), w, s, hist);
}

EGLBoolean bionic_eglSwapBuffers(EGLDisplay display, EGLSurface surface)
{
	static EGLBoolean (*real)(EGLDisplay, EGLSurface);
	static int frames_per_report;
	if (!real) {
		real = (EGLBoolean (*)(EGLDisplay, EGLSurface))dlsym(RTLD_NEXT, "bionic_eglSwapBuffers");
		const char *e = getenv("SWAPTIME_FRAMES");
		frames_per_report = e ? atoi(e) : 300;
		if (frames_per_report < 10 || frames_per_report > MAX_FRAMES)
			frames_per_report = 300;
	}
	double start = now_ms();
	EGLBoolean ret = real(display, surface);
	double end = now_ms();
	if (window >= 0 && count < MAX_FRAMES) {
		work_ms[count] = start - last_end;
		swap_ms[count] = end - start;
		period_ms[count] = start - last_start;
		count++;
	}
	if (window < 0 || count >= frames_per_report) {
		if (count)
			report(start);
		window = window < 0 ? 0 : window + 1;
		count = 0;
		window_start = start;
	}
	last_start = start;
	last_end = end;
	return ret;
}
