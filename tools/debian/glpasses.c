/* teslalinux: what an app under ATL draws per frame, pass by pass (LD_PRELOAD shim, Pi or VM).
 *
 *   gcc -O2 -shared -fPIC -o glpasses.so glpasses.c -ldl
 *   LD_PRELOAD=/path/glpasses.so GLPASSES_TRIGGER=/var/lib/tesla/glpasses.go
 *   GLPASSES_OUT=/var/lib/tesla/glpasses.log  (in the app's environment; teslalinux-atl passes it on)
 *   touch the trigger file: the next GLPASSES_FRAMES (default 8) frames are written to the log and
 *   the trigger file is removed.
 *
 * bionic_translation resolves an app's symbol X to bionic_X when one is loaded (swaptime.c uses the
 * same), so this defines bionic_ versions of the GLES calls that start, fill and end a render pass on a
 * tiler (framebuffer binds and attachments, clears, draws, blits, invalidates, read-backs, flushes,
 * fences) and of eglSwapBuffers as the frame mark, and calls the next definition (libandroid's own
 * bionic_ where it has one, else the system's libGLESv2). Every pass is printed with the framebuffer,
 * its colour/depth attachments (texture or renderbuffer, size, internal format), the viewport, how it
 * was cleared, the number of draws and vertices/indices, the programs used, blend and depth state,
 * and how it ended (the next bind, a blit, a read-back, a flush). Only the thread that calls
 * eglSwapBuffers is followed (a GL context is per thread).
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

typedef unsigned int GLenum, GLuint, GLbitfield;
typedef int GLint, GLsizei;
typedef unsigned char GLboolean;
typedef float GLfloat;
typedef intptr_t GLintptr;
typedef intptr_t GLsizeiptr;
typedef unsigned int EGLBoolean;
typedef void *EGLDisplay, *EGLSurface, *GLsync;

#define GL_FRAMEBUFFER 0x8D40
#define GL_DRAW_FRAMEBUFFER 0x8CA9
#define GL_READ_FRAMEBUFFER 0x8CA8
#define GL_COLOR_ATTACHMENT0 0x8CE0
#define GL_DEPTH_ATTACHMENT 0x8D00
#define GL_STENCIL_ATTACHMENT 0x8D20
#define GL_DEPTH_STENCIL_ATTACHMENT 0x821A
#define GL_COLOR_BUFFER_BIT 0x4000
#define GL_DEPTH_BUFFER_BIT 0x0100
#define GL_STENCIL_BUFFER_BIT 0x0400
#define GL_BLEND 0x0BE2
#define GL_DEPTH_TEST 0x0B71
#define GL_SCISSOR_TEST 0x0C11
#define GL_TEXTURE_2D 0x0DE1

static void *gles;
static void *next(const char *bionic_name, const char *name)
{
	void *f = dlsym(RTLD_NEXT, bionic_name);
	if (f)
		return f;
	if (!gles)
		gles = dlopen("libGLESv2.so.2", RTLD_NOW | RTLD_GLOBAL);
	return gles ? dlsym(gles, name) : NULL;
}
#define REAL(ret, name, args)                                    \
	static ret(*real_##name) args;                               \
	static inline void load_##name(void)                         \
	{                                                            \
		if (!real_##name)                                        \
			real_##name = next("bionic_" #name, #name);          \
	}

/* ---- state: textures and renderbuffers (size, format), framebuffer attachments ---- */
#define MAXOBJ 65536
static struct { int w, h; GLenum fmt; } tex[MAXOBJ], rb[MAXOBJ];
static struct { GLuint color, depth; char ctype, dtype; } fbo[MAXOBJ];
static GLuint bound_tex, bound_rb, draw_fbo;

/* ---- the trace window ---- */
static FILE *out;
static int frames_left, frame_no;
static pthread_t swap_thread;
static int have_swap_thread;
static double t0;
static const char *trigger_path;

static double now_ms(void)
{
	struct timespec ts;
	clock_gettime(CLOCK_MONOTONIC, &ts);
	return ts.tv_sec * 1e3 + ts.tv_nsec / 1e6;
}

static int active(void)
{
	return out && frames_left > 0 && have_swap_thread && pthread_equal(pthread_self(), swap_thread);
}

/* the current pass */
static struct {
	int open;
	GLuint fbo;
	int vx, vy, vw, vh;
	GLbitfield clear;
	int draws, verts, blend_draws, depth_draws, programs;
	GLuint last_prog;
	double start;
	GLuint sampled[8]; /* render-target textures (attached to some framebuffer) bound while drawing */
	int nsampled;
} pass;
static unsigned char is_target[MAXOBJ]; /* textures attached to a framebuffer */
static GLuint cur_prog;
static int blend_on, depth_on, scissor_on;

static const char *fmtname(GLenum f)
{
	static char buf[16];
	switch (f) {
	case 0x8058: return "RGBA8";
	case 0x8051: return "RGB8";
	case 0x1908: return "RGBA";
	case 0x1907: return "RGB";
	case 0x881A: return "RGBA16F";
	case 0x8C3A: return "R11G11B10F";
	case 0x8D62: return "RGB565";
	case 0x81A5: return "DEPTH16";
	case 0x81A6: return "DEPTH24";
	case 0x8CAC: return "DEPTH32F";
	case 0x88F0: return "D24S8";
	case 0x8CAD: return "D32FS8";
	case 0x1902: return "DEPTH";
	case 0x84F9: return "DEPTH_STENCIL";
	case 0x8229: return "R8";
	case 0x822D: return "R16F";
	case 0x822E: return "R32F";
	case 0x8C43: return "SRGB8A8";
	}
	snprintf(buf, sizeof buf, "0x%x", f);
	return buf;
}

static void describe_fbo(GLuint f, char *buf, size_t n)
{
	if (f == 0 || f >= MAXOBJ) {
		snprintf(buf, n, "fbo %u (window)", f);
		return;
	}
	char c[64] = "-", d[64] = "-";
	if (fbo[f].color) {
		int w = fbo[f].ctype == 't' ? tex[fbo[f].color % MAXOBJ].w : rb[fbo[f].color % MAXOBJ].w;
		int h = fbo[f].ctype == 't' ? tex[fbo[f].color % MAXOBJ].h : rb[fbo[f].color % MAXOBJ].h;
		GLenum fm = fbo[f].ctype == 't' ? tex[fbo[f].color % MAXOBJ].fmt : rb[fbo[f].color % MAXOBJ].fmt;
		snprintf(c, sizeof c, "%c%u %dx%d %s", fbo[f].ctype, fbo[f].color, w, h, fmtname(fm));
	}
	if (fbo[f].depth) {
		int w = fbo[f].dtype == 't' ? tex[fbo[f].depth % MAXOBJ].w : rb[fbo[f].depth % MAXOBJ].w;
		int h = fbo[f].dtype == 't' ? tex[fbo[f].depth % MAXOBJ].h : rb[fbo[f].depth % MAXOBJ].h;
		GLenum fm = fbo[f].dtype == 't' ? tex[fbo[f].depth % MAXOBJ].fmt : rb[fbo[f].depth % MAXOBJ].fmt;
		snprintf(d, sizeof d, "%c%u %dx%d %s", fbo[f].dtype, fbo[f].depth, w, h, fmtname(fm));
	}
	snprintf(buf, n, "fbo %u [colour %s | depth %s]", f, c, d);
}

static void end_pass(const char *why)
{
	if (!pass.open)
		return;
	if (pass.draws || pass.clear) {
		char d[200];
		describe_fbo(pass.fbo, d, sizeof d);
		fprintf(out, "  %8.2f %-58s vp %dx%d%s clear%s%s%s draws %4d verts %7d blend %4d depth %4d progs %3d cpu %.2f ms | ends: %s\n",
		        pass.start - t0, d, pass.vw, pass.vh, scissor_on ? " scissor" : "",
		        pass.clear & GL_COLOR_BUFFER_BIT ? " C" : "", pass.clear & GL_DEPTH_BUFFER_BIT ? " D" : "",
		        pass.clear & GL_STENCIL_BUFFER_BIT ? " S" : "", pass.draws, pass.verts, pass.blend_draws, pass.depth_draws,
		        pass.programs, now_ms() - pass.start, why);
		for (int i = 0; i < pass.nsampled; i++)
			fprintf(out, "              samples render target t%u (%dx%d %s)\n", pass.sampled[i], tex[pass.sampled[i]].w, tex[pass.sampled[i]].h,
			        fmtname(tex[pass.sampled[i]].fmt));
	}
	pass.open = 0;
}

static void begin_pass(void)
{
	if (pass.open)
		return;
	memset(&pass, 0, sizeof pass);
	pass.open = 1;
	pass.fbo = draw_fbo;
	pass.start = now_ms();
}

static int vpw, vph;

static void note_draw(int count)
{
	if (!active())
		return;
	begin_pass();
	pass.vw = vpw;
	pass.vh = vph;
	pass.draws++;
	pass.verts += count;
	if (blend_on)
		pass.blend_draws++;
	if (depth_on)
		pass.depth_draws++;
	if (cur_prog != pass.last_prog) {
		pass.programs++;
		pass.last_prog = cur_prog;
	}
}

/* ---- wrapped calls ---- */
REAL(void, glBindFramebuffer, (GLenum, GLuint))
void bionic_glBindFramebuffer(GLenum target, GLuint f)
{
	load_glBindFramebuffer();
	if (target == GL_FRAMEBUFFER || target == GL_DRAW_FRAMEBUFFER) {
		if (active() && f != draw_fbo) {
			char why[32];
			snprintf(why, sizeof why, "bind fbo %u", f);
			end_pass(why);
		}
		draw_fbo = f;
	}
	real_glBindFramebuffer(target, f);
}

REAL(void, glBindTexture, (GLenum, GLuint))
void bionic_glBindTexture(GLenum t, GLuint id)
{
	load_glBindTexture();
	if (t == GL_TEXTURE_2D)
		bound_tex = id;
	if (active() && id < MAXOBJ && is_target[id] && pass.nsampled < 8) {
		int seen = 0;
		for (int i = 0; i < pass.nsampled; i++)
			seen |= pass.sampled[i] == id;
		if (!seen) {
			begin_pass();
			pass.sampled[pass.nsampled++] = id;
		}
	}
	real_glBindTexture(t, id);
}

REAL(void, glTexImage2D, (GLenum, GLint, GLint, GLsizei, GLsizei, GLint, GLenum, GLenum, const void *))
void bionic_glTexImage2D(GLenum t, GLint level, GLint ifmt, GLsizei w, GLsizei h, GLint b, GLenum f, GLenum ty, const void *p)
{
	load_glTexImage2D();
	if (t == GL_TEXTURE_2D && level == 0 && bound_tex < MAXOBJ) {
		tex[bound_tex].w = w;
		tex[bound_tex].h = h;
		tex[bound_tex].fmt = ifmt;
	}
	real_glTexImage2D(t, level, ifmt, w, h, b, f, ty, p);
}

REAL(void, glTexStorage2D, (GLenum, GLsizei, GLenum, GLsizei, GLsizei))
void bionic_glTexStorage2D(GLenum t, GLsizei levels, GLenum ifmt, GLsizei w, GLsizei h)
{
	load_glTexStorage2D();
	if (t == GL_TEXTURE_2D && bound_tex < MAXOBJ) {
		tex[bound_tex].w = w;
		tex[bound_tex].h = h;
		tex[bound_tex].fmt = ifmt;
	}
	real_glTexStorage2D(t, levels, ifmt, w, h);
}

REAL(void, glBindRenderbuffer, (GLenum, GLuint))
void bionic_glBindRenderbuffer(GLenum t, GLuint id)
{
	load_glBindRenderbuffer();
	bound_rb = id;
	real_glBindRenderbuffer(t, id);
}

REAL(void, glRenderbufferStorage, (GLenum, GLenum, GLsizei, GLsizei))
void bionic_glRenderbufferStorage(GLenum t, GLenum ifmt, GLsizei w, GLsizei h)
{
	load_glRenderbufferStorage();
	if (bound_rb < MAXOBJ) {
		rb[bound_rb].w = w;
		rb[bound_rb].h = h;
		rb[bound_rb].fmt = ifmt;
	}
	real_glRenderbufferStorage(t, ifmt, w, h);
}

REAL(void, glRenderbufferStorageMultisample, (GLenum, GLsizei, GLenum, GLsizei, GLsizei))
void bionic_glRenderbufferStorageMultisample(GLenum t, GLsizei s, GLenum ifmt, GLsizei w, GLsizei h)
{
	load_glRenderbufferStorageMultisample();
	if (bound_rb < MAXOBJ) {
		rb[bound_rb].w = w;
		rb[bound_rb].h = h;
		rb[bound_rb].fmt = ifmt;
	}
	real_glRenderbufferStorageMultisample(t, s, ifmt, w, h);
}

static void attach(GLenum att, GLuint id, char type)
{
	if (draw_fbo >= MAXOBJ)
		return;
	if (type == 't' && id < MAXOBJ)
		is_target[id] = 1;
	if (att == GL_COLOR_ATTACHMENT0) {
		fbo[draw_fbo].color = id;
		fbo[draw_fbo].ctype = type;
	} else if (att == GL_DEPTH_ATTACHMENT || att == GL_DEPTH_STENCIL_ATTACHMENT) {
		fbo[draw_fbo].depth = id;
		fbo[draw_fbo].dtype = type;
	}
}

REAL(void, glFramebufferTexture2D, (GLenum, GLenum, GLenum, GLuint, GLint))
void bionic_glFramebufferTexture2D(GLenum t, GLenum att, GLenum tt, GLuint id, GLint level)
{
	load_glFramebufferTexture2D();
	attach(att, id, 't');
	real_glFramebufferTexture2D(t, att, tt, id, level);
}

REAL(void, glFramebufferTextureLayer, (GLenum, GLenum, GLuint, GLint, GLint))
void bionic_glFramebufferTextureLayer(GLenum t, GLenum att, GLuint id, GLint level, GLint layer)
{
	load_glFramebufferTextureLayer();
	attach(att, id, 'l');
	real_glFramebufferTextureLayer(t, att, id, level, layer);
}

REAL(void, glFramebufferRenderbuffer, (GLenum, GLenum, GLenum, GLuint))
void bionic_glFramebufferRenderbuffer(GLenum t, GLenum att, GLenum rt, GLuint id)
{
	load_glFramebufferRenderbuffer();
	attach(att, id, 'r');
	real_glFramebufferRenderbuffer(t, att, rt, id);
}

REAL(void, glViewport, (GLint, GLint, GLsizei, GLsizei))
void bionic_glViewport(GLint x, GLint y, GLsizei w, GLsizei h)
{
	load_glViewport();
	vpw = w;
	vph = h;
	real_glViewport(x, y, w, h);
}

REAL(void, glUseProgram, (GLuint))
void bionic_glUseProgram(GLuint p)
{
	load_glUseProgram();
	cur_prog = p;
	real_glUseProgram(p);
}

static void cap(GLenum c, int on)
{
	if (c == GL_BLEND)
		blend_on = on;
	else if (c == GL_DEPTH_TEST)
		depth_on = on;
	else if (c == GL_SCISSOR_TEST)
		scissor_on = on;
}
REAL(void, glEnable, (GLenum))
void bionic_glEnable(GLenum c)
{
	load_glEnable();
	cap(c, 1);
	real_glEnable(c);
}
REAL(void, glDisable, (GLenum))
void bionic_glDisable(GLenum c)
{
	load_glDisable();
	cap(c, 0);
	real_glDisable(c);
}

REAL(void, glClear, (GLbitfield))
void bionic_glClear(GLbitfield m)
{
	load_glClear();
	if (active()) {
		begin_pass();
		if (pass.draws)
			fprintf(out, "  %8.2f     (clear %x after %d draws in the same pass)\n", now_ms() - t0, m, pass.draws);
		pass.clear |= m;
	}
	real_glClear(m);
}

REAL(void, glClearBufferfv, (GLenum, GLint, const GLfloat *))
void bionic_glClearBufferfv(GLenum b, GLint i, const GLfloat *v)
{
	load_glClearBufferfv();
	if (active()) {
		begin_pass();
		pass.clear |= b == 0x1800 ? GL_COLOR_BUFFER_BIT : GL_DEPTH_BUFFER_BIT;
	}
	real_glClearBufferfv(b, i, v);
}

REAL(void, glDrawArrays, (GLenum, GLint, GLsizei))
void bionic_glDrawArrays(GLenum m, GLint f, GLsizei c)
{
	load_glDrawArrays();
	note_draw(c);
	real_glDrawArrays(m, f, c);
}
REAL(void, glDrawElements, (GLenum, GLsizei, GLenum, const void *))
void bionic_glDrawElements(GLenum m, GLsizei c, GLenum t, const void *i)
{
	load_glDrawElements();
	note_draw(c);
	real_glDrawElements(m, c, t, i);
}
REAL(void, glDrawArraysInstanced, (GLenum, GLint, GLsizei, GLsizei))
void bionic_glDrawArraysInstanced(GLenum m, GLint f, GLsizei c, GLsizei n)
{
	load_glDrawArraysInstanced();
	note_draw(c * n);
	real_glDrawArraysInstanced(m, f, c, n);
}
REAL(void, glDrawElementsInstanced, (GLenum, GLsizei, GLenum, const void *, GLsizei))
void bionic_glDrawElementsInstanced(GLenum m, GLsizei c, GLenum t, const void *i, GLsizei n)
{
	load_glDrawElementsInstanced();
	note_draw(c * n);
	real_glDrawElementsInstanced(m, c, t, i, n);
}
REAL(void, glDrawRangeElements, (GLenum, GLuint, GLuint, GLsizei, GLenum, const void *))
void bionic_glDrawRangeElements(GLenum m, GLuint s, GLuint e, GLsizei c, GLenum t, const void *i)
{
	load_glDrawRangeElements();
	note_draw(c);
	real_glDrawRangeElements(m, s, e, c, t, i);
}

REAL(void, glBlitFramebuffer, (GLint, GLint, GLint, GLint, GLint, GLint, GLint, GLint, GLbitfield, GLenum))
void bionic_glBlitFramebuffer(GLint a, GLint b, GLint c, GLint d, GLint e, GLint f, GLint g, GLint h, GLbitfield m, GLenum fl)
{
	load_glBlitFramebuffer();
	if (active()) {
		end_pass("blit");
		fprintf(out, "  %8.2f   blit %dx%d -> %dx%d into fbo %u mask %x\n", now_ms() - t0, c - a, d - b, g - e, h - f, draw_fbo, m);
	}
	real_glBlitFramebuffer(a, b, c, d, e, f, g, h, m, fl);
}

REAL(void, glInvalidateFramebuffer, (GLenum, GLsizei, const GLenum *))
void bionic_glInvalidateFramebuffer(GLenum t, GLsizei n, const GLenum *a)
{
	load_glInvalidateFramebuffer();
	if (active())
		fprintf(out, "  %8.2f     invalidate fbo %u (%d attachments, first %x)\n", now_ms() - t0, draw_fbo, n, n ? a[0] : 0);
	real_glInvalidateFramebuffer(t, n, a);
}

REAL(void, glReadPixels, (GLint, GLint, GLsizei, GLsizei, GLenum, GLenum, void *))
void bionic_glReadPixels(GLint x, GLint y, GLsizei w, GLsizei h, GLenum f, GLenum t, void *p)
{
	load_glReadPixels();
	if (active()) {
		end_pass("read-back");
		fprintf(out, "  %8.2f   glReadPixels %dx%d\n", now_ms() - t0, w, h);
	}
	real_glReadPixels(x, y, w, h, f, t, p);
}

REAL(void, glCopyTexSubImage2D, (GLenum, GLint, GLint, GLint, GLint, GLint, GLsizei, GLsizei))
void bionic_glCopyTexSubImage2D(GLenum t, GLint l, GLint xo, GLint yo, GLint x, GLint y, GLsizei w, GLsizei h)
{
	load_glCopyTexSubImage2D();
	if (active()) {
		end_pass("copy-tex");
		fprintf(out, "  %8.2f   glCopyTexSubImage2D %dx%d\n", now_ms() - t0, w, h);
	}
	real_glCopyTexSubImage2D(t, l, xo, yo, x, y, w, h);
}

REAL(void, glGenerateMipmap, (GLenum))
void bionic_glGenerateMipmap(GLenum t)
{
	load_glGenerateMipmap();
	if (active())
		fprintf(out, "  %8.2f   glGenerateMipmap tex %u (%dx%d)\n", now_ms() - t0, bound_tex, tex[bound_tex % MAXOBJ].w, tex[bound_tex % MAXOBJ].h);
	real_glGenerateMipmap(t);
}

REAL(void, glFlush, (void))
void bionic_glFlush(void)
{
	load_glFlush();
	if (active())
		fprintf(out, "  %8.2f   glFlush\n", now_ms() - t0);
	real_glFlush();
}
REAL(void, glFinish, (void))
void bionic_glFinish(void)
{
	load_glFinish();
	if (active())
		fprintf(out, "  %8.2f   glFinish\n", now_ms() - t0);
	real_glFinish();
}
REAL(GLsync, glFenceSync, (GLenum, GLbitfield))
GLsync bionic_glFenceSync(GLenum c, GLbitfield f)
{
	load_glFenceSync();
	if (active())
		fprintf(out, "  %8.2f   glFenceSync\n", now_ms() - t0);
	return real_glFenceSync(c, f);
}
REAL(GLenum, glClientWaitSync, (GLsync, GLbitfield, uint64_t))
GLenum bionic_glClientWaitSync(GLsync s, GLbitfield f, uint64_t t)
{
	load_glClientWaitSync();
	double a = now_ms();
	GLenum r = real_glClientWaitSync(s, f, t);
	if (active())
		fprintf(out, "  %8.2f   glClientWaitSync %.2f ms\n", a - t0, now_ms() - a);
	return r;
}

/* the frame mark */
REAL(EGLBoolean, eglSwapBuffers, (EGLDisplay, EGLSurface))
EGLBoolean bionic_eglSwapBuffers(EGLDisplay d, EGLSurface s)
{
	load_eglSwapBuffers();
	if (!have_swap_thread) {
		swap_thread = pthread_self();
		have_swap_thread = 1;
		trigger_path = getenv("GLPASSES_TRIGGER");
	}
	if (pthread_equal(pthread_self(), swap_thread)) {
		if (frames_left > 0) {
			end_pass("eglSwapBuffers");
			fprintf(out, "  %8.2f eglSwapBuffers (frame %d)\n", now_ms() - t0, frame_no);
			if (--frames_left == 0) {
				fclose(out);
				out = NULL;
			}
		}
		static int n;
		if (!out && trigger_path && (++n % 30) == 0 && access(trigger_path, F_OK) == 0) {
			unlink(trigger_path);
			const char *o = getenv("GLPASSES_OUT");
			out = fopen(o ? o : "/tmp/glpasses.log", "a");
			const char *fr = getenv("GLPASSES_FRAMES");
			frames_left = fr ? atoi(fr) : 8;
			frame_no = 0;
			t0 = now_ms();
			if (out)
				fprintf(out, "== trace of %d frames\n", frames_left);
		}
		if (out) {
			frame_no++;
			fprintf(out, "-- frame %d at %.2f\n", frame_no, now_ms() - t0);
			memset(&pass, 0, sizeof pass);
		}
	}
	return real_eglSwapBuffers(d, s);
}
