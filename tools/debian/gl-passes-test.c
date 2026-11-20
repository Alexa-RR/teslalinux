/* teslalinux: checks of ATL's gl_passes.c (deferred fences, redundant clears left out) against the
 * GL's own results, on any Mesa driver (the Debian VM's llvmpipe, a Pi's V3D) with a surfaceless
 * EGL context. Every case reads the pixels back: a clear left out must never leave the wrong value.
 *
 *   gcc -O1 -o gl-passes-test gl-passes-test.c -L$ATL/lib -Wl,-rpath,$ATL/lib -Wl,--allow-shlib-undefined -rdynamic -landroid -lEGL -lGLESv2
 *   ATL_DEBUG_GL_PASSES=1 ./gl-passes-test         (exit 0: all passed)
 *
 * The app's calls go through libandroid's bionic_ wrappers, as bionic_translation resolves them for
 * an app; the test calls those by name. */
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

EGLContext bionic_eglCreateContext(EGLDisplay, EGLConfig, EGLContext, const EGLint *);
EGLBoolean bionic_eglMakeCurrent(EGLDisplay, EGLSurface, EGLSurface, EGLContext);
EGLBoolean bionic_eglDestroyContext(EGLDisplay, EGLContext);
void (*bionic_eglGetProcAddress(const char *))(void);
void bionic_glClear(GLbitfield);
void bionic_glClearBufferfv(GLenum, GLint, const GLfloat *);
void bionic_glDrawArrays(GLenum, GLint, GLsizei);
void bionic_glFramebufferTexture2D(GLenum, GLenum, GLenum, GLuint, GLint);
void bionic_glFramebufferRenderbuffer(GLenum, GLenum, GLenum, GLuint);
void bionic_glTexImage2D(GLenum, GLint, GLint, GLsizei, GLsizei, GLint, GLenum, GLenum, const void *);
void bionic_glTexSubImage2D(GLenum, GLint, GLint, GLint, GLsizei, GLsizei, GLenum, GLenum, const void *);
void bionic_glBlitFramebuffer(GLint, GLint, GLint, GLint, GLint, GLint, GLint, GLint, GLbitfield, GLenum);
void bionic_glInvalidateFramebuffer(GLenum, GLsizei, const GLenum *);
void bionic_glDeleteTextures(GLsizei, const GLuint *);
void bionic_glCopyTexSubImage2D(GLenum, GLint, GLint, GLint, GLint, GLint, GLsizei, GLsizei);
GLsync bionic_glFenceSync(GLenum, GLbitfield);
GLenum bionic_glClientWaitSync(GLsync, GLbitfield, GLuint64);
void bionic_glGetSynciv(GLsync, GLenum, GLsizei, GLsizei *, GLint *);
void bionic_glDeleteSync(GLsync);
GLboolean bionic_glIsSync(GLsync);
void bionic_glFlush(void);
void bionic_glFinish(void);

/* data libandroid takes from ATL's main library (libtranslation_layer_main.so); unused here */
void *main_thread_looper;
unsigned long main_thread_id;
char handle_cache[1 << 16];
void *window; /* the main GtkWindow (android-translation-layer) */

static int failures, checks;
#define W 64
#define H 64

static void expect_pixel(const char *what, int x, int y, unsigned r, unsigned g, unsigned b, unsigned a)
{
	unsigned char p[4] = { 0 };
	glReadPixels(x, y, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, p);
	checks++;
	if (abs((int)p[0] - (int)r) > 2 || abs((int)p[1] - (int)g) > 2 || abs((int)p[2] - (int)b) > 2 || abs((int)p[3] - (int)a) > 2) {
		failures++;
		printf("FAIL %s: pixel (%d,%d) is %u,%u,%u,%u, expected %u,%u,%u,%u\n", what, x, y, p[0], p[1], p[2], p[3], r, g, b, a);
	}
}

static void expect(const char *what, int ok)
{
	checks++;
	if (!ok) {
		failures++;
		printf("FAIL %s\n", what);
	}
}

static GLuint prog, vbo;
/* a triangle over the left half, in the colour of uniform c */
static void draw_left_half(float r, float g, float b, float a)
{
	glUseProgram(prog);
	glUniform4f(glGetUniformLocation(prog, "c"), r, g, b, a);
	glBindBuffer(GL_ARRAY_BUFFER, vbo);
	glEnableVertexAttribArray(0);
	glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 0, 0);
	bionic_glDrawArrays(GL_TRIANGLES, 0, 6);
}

static GLuint make_program(void)
{
	const char *vs = "#version 300 es\nlayout(location=0) in vec2 p; void main(){ gl_Position = vec4(p, 0.5, 1.0); }";
	const char *fs = "#version 300 es\nprecision mediump float; uniform vec4 c; out vec4 o; void main(){ o = c; }";
	GLuint v = glCreateShader(GL_VERTEX_SHADER), f = glCreateShader(GL_FRAGMENT_SHADER), p = glCreateProgram();
	glShaderSource(v, 1, &vs, NULL);
	glCompileShader(v);
	glShaderSource(f, 1, &fs, NULL);
	glCompileShader(f);
	glAttachShader(p, v);
	glAttachShader(p, f);
	glLinkProgram(p);
	GLint ok = 0;
	glGetProgramiv(p, GL_LINK_STATUS, &ok);
	if (!ok) {
		printf("program link failed\n");
		exit(2);
	}
	return p;
}

static GLuint make_texture(void)
{
	GLuint t;
	glGenTextures(1, &t);
	glBindTexture(GL_TEXTURE_2D, t);
	bionic_glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, W, H, 0, GL_RGBA, GL_UNSIGNED_BYTE, NULL);
	return t;
}

int main(void)
{
	PFNEGLGETPLATFORMDISPLAYEXTPROC get_platform_display = (PFNEGLGETPLATFORMDISPLAYEXTPROC)eglGetProcAddress("eglGetPlatformDisplayEXT");
	EGLDisplay dpy = get_platform_display ? get_platform_display(EGL_PLATFORM_SURFACELESS_MESA, EGL_DEFAULT_DISPLAY, NULL) : EGL_NO_DISPLAY;
	if (dpy == EGL_NO_DISPLAY || !eglInitialize(dpy, NULL, NULL)) {
		printf("no surfaceless EGL display\n");
		return 2;
	}
	eglBindAPI(EGL_OPENGL_ES_API);
	const EGLint cattr[] = { EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE };
	EGLContext ctx = bionic_eglCreateContext(dpy, EGL_NO_CONFIG_KHR, EGL_NO_CONTEXT, cattr);
	if (ctx == EGL_NO_CONTEXT || !bionic_eglMakeCurrent(dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, ctx)) {
		printf("no GLES 3 context\n");
		return 2;
	}
	printf("%s / %s\n", glGetString(GL_RENDERER), glGetString(GL_VERSION));

	prog = make_program();
	const float tri[] = { -1, -1, 0, -1, 0, 1, -1, -1, 0, 1, -1, 1 };
	glGenBuffers(1, &vbo);
	glBindBuffer(GL_ARRAY_BUFFER, vbo);
	glBufferData(GL_ARRAY_BUFFER, sizeof tri, tri, GL_STATIC_DRAW);

	GLuint tex = make_texture(), fbo;
	glGenFramebuffers(1, &fbo);
	glBindFramebuffer(GL_FRAMEBUFFER, fbo);
	bionic_glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex, 0);
	GLuint depth;
	glGenRenderbuffers(1, &depth);
	glBindRenderbuffer(GL_RENDERBUFFER, depth);
	glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, W, H);
	bionic_glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, depth);
	expect("framebuffer complete", glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE);
	glViewport(0, 0, W, H);

	/* 0. a redundant clear is left out: a write that goes around the wrappers (the system's
	 * glTexSubImage2D itself, which no app reaches under ATL) survives it. Skipped with
	 * ATL_GL_SKIP_CLEARS=0, where the clear has to happen */
	const char *skip = getenv("ATL_GL_SKIP_CLEARS");
	if (!skip || atoi(skip)) {
		unsigned char blue[4 * 4] = { 0, 0, 255, 255, 0, 0, 255, 255, 0, 0, 255, 255, 0, 0, 255, 255 };
		glClearColor(1, 0, 0, 1);
		bionic_glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
		glBindTexture(GL_TEXTURE_2D, tex);
		glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 2, 2, GL_RGBA, GL_UNSIGNED_BYTE, blue); /* not seen */
		bionic_glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
		expect_pixel("redundant clear left out", 0, 0, 0, 0, 255, 255);
		draw_left_half(0, 1, 0, 1); /* covers (0,0) */
	}

	/* 1. a clear, a draw, the same clear: the draw must be cleared away */
	glClearColor(1, 0, 0, 1);
	bionic_glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
	bionic_glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT); /* redundant: may be left out */
	expect_pixel("clear red", 10, 10, 255, 0, 0, 255);
	draw_left_half(0, 1, 0, 1);
	expect_pixel("draw green", 10, 10, 0, 255, 0, 255);
	bionic_glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
	expect_pixel("clear after draw", 10, 10, 255, 0, 0, 255);

	/* 2. the same clear with another colour must happen */
	glClearColor(0, 0, 1, 1);
	bionic_glClear(GL_COLOR_BUFFER_BIT);
	expect_pixel("clear to another colour", 50, 50, 0, 0, 255, 255);

	/* 3. a scissored clear writes part of the image: a full clear after it must happen */
	glClearColor(1, 1, 0, 1);
	glEnable(GL_SCISSOR_TEST);
	glScissor(0, 0, 8, 8);
	bionic_glClear(GL_COLOR_BUFFER_BIT);
	glDisable(GL_SCISSOR_TEST);
	expect_pixel("scissored clear inside", 2, 2, 255, 255, 0, 255);
	expect_pixel("scissored clear outside", 50, 50, 0, 0, 255, 255);
	glClearColor(0, 0, 1, 1);
	bionic_glClear(GL_COLOR_BUFFER_BIT);
	expect_pixel("full clear after a scissored one", 2, 2, 0, 0, 255, 255);

	/* 4. a masked clear does not make the buffer clean */
	glClearColor(1, 1, 1, 1);
	glColorMask(GL_TRUE, GL_FALSE, GL_FALSE, GL_TRUE);
	bionic_glClear(GL_COLOR_BUFFER_BIT);
	glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
	expect_pixel("masked clear", 50, 50, 255, 0, 255, 255);
	bionic_glClear(GL_COLOR_BUFFER_BIT);
	expect_pixel("full clear after a masked one", 50, 50, 255, 255, 255, 255);

	/* 5. an upload into the attached texture */
	unsigned char black[4 * 16] = { 0 };
	for (int i = 3; i < (int)sizeof black; i += 4)
		black[i] = 255;
	glBindTexture(GL_TEXTURE_2D, tex);
	bionic_glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 4, 4, GL_RGBA, GL_UNSIGNED_BYTE, black);
	expect_pixel("upload", 1, 1, 0, 0, 0, 255);
	bionic_glClear(GL_COLOR_BUFFER_BIT);
	expect_pixel("clear after an upload", 1, 1, 255, 255, 255, 255);

	/* 6. a blit into it */
	GLuint tex2 = make_texture(), fbo2;
	glGenFramebuffers(1, &fbo2);
	glBindFramebuffer(GL_FRAMEBUFFER, fbo2);
	bionic_glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex2, 0);
	glClearColor(0, 1, 0, 1);
	bionic_glClear(GL_COLOR_BUFFER_BIT);
	glBindFramebuffer(GL_READ_FRAMEBUFFER, fbo2);
	glBindFramebuffer(GL_DRAW_FRAMEBUFFER, fbo);
	bionic_glBlitFramebuffer(0, 0, W, H, 0, 0, W, H, GL_COLOR_BUFFER_BIT, GL_NEAREST);
	glBindFramebuffer(GL_FRAMEBUFFER, fbo);
	expect_pixel("blit", 30, 30, 0, 255, 0, 255);
	glClearColor(1, 1, 1, 1);
	bionic_glClear(GL_COLOR_BUFFER_BIT);
	expect_pixel("clear after a blit", 30, 30, 255, 255, 255, 255);

	/* 7. a copy into it */
	glBindFramebuffer(GL_READ_FRAMEBUFFER, fbo2);
	glBindTexture(GL_TEXTURE_2D, tex);
	bionic_glCopyTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 0, 0, W, H);
	glBindFramebuffer(GL_FRAMEBUFFER, fbo);
	expect_pixel("copy", 30, 30, 0, 255, 0, 255);
	bionic_glClear(GL_COLOR_BUFFER_BIT);
	expect_pixel("clear after a copy", 30, 30, 255, 255, 255, 255);

	/* 8. another texture attached in its place: it holds something else */
	bionic_glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex2, 0);
	bionic_glClear(GL_COLOR_BUFFER_BIT);
	expect_pixel("clear after attaching another texture", 30, 30, 255, 255, 255, 255);
	bionic_glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex, 0);

	/* 9. invalidated: the next clear must happen */
	GLenum att = GL_COLOR_ATTACHMENT0;
	glClearColor(1, 0, 1, 1);
	bionic_glClear(GL_COLOR_BUFFER_BIT);
	bionic_glInvalidateFramebuffer(GL_FRAMEBUFFER, 1, &att);
	bionic_glClear(GL_COLOR_BUFFER_BIT);
	expect_pixel("clear after invalidate", 30, 30, 255, 0, 255, 255);

	/* 10. a deleted texture's name reused: a new texture, not the old clean one */
	bionic_glDeleteTextures(1, &tex);
	GLuint tex3 = make_texture();
	bionic_glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex3, 0);
	bionic_glClear(GL_COLOR_BUFFER_BIT);
	expect_pixel("clear of a new texture with a reused name", 30, 30, 255, 0, 255, 255);

	/* 11. glClearBufferfv on colour, then a draw, then the same glClearBufferfv */
	const float cyan[4] = { 0, 1, 1, 1 };
	bionic_glClearBufferfv(GL_COLOR, 0, cyan);
	bionic_glClearBufferfv(GL_COLOR, 0, cyan);
	expect_pixel("glClearBufferfv", 50, 50, 0, 255, 255, 255);
	glDisable(GL_DEPTH_TEST);
	draw_left_half(1, 0, 0, 1);
	bionic_glClearBufferfv(GL_COLOR, 0, cyan);
	expect_pixel("glClearBufferfv after a draw", 10, 10, 0, 255, 255, 255);

	/* 12. the depth buffer: cleared to 1, drawn at 0.5 with depth writes, cleared to 1 again; a
	 * draw at 0.75 with GL_LESS must then pass */
	glEnable(GL_DEPTH_TEST);
	glDepthFunc(GL_LESS);
	glClearDepthf(1.0f);
	bionic_glClear(GL_DEPTH_BUFFER_BIT);
	draw_left_half(0, 0, 0, 1); /* z = 0.5 written */
	bionic_glClear(GL_DEPTH_BUFFER_BIT | GL_COLOR_BUFFER_BIT);
	glUniform4f(glGetUniformLocation(prog, "c"), 1, 1, 0, 1);
	draw_left_half(1, 1, 0, 1); /* passes only if depth was cleared back to 1 */
	expect_pixel("depth cleared after a draw", 10, 10, 255, 255, 0, 255);
	glDisable(GL_DEPTH_TEST);

	/* fences: deferred, never reported signalled before their commands are flushed and done */
	GLsync s = bionic_glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
	expect("fence is a sync", s && bionic_glIsSync(s));
	GLint status = 0, len = 0;
	const char *defer = getenv("ATL_GL_DEFER_FENCES");
	int deferred = !defer || atoi(defer);
	bionic_glGetSynciv(s, GL_SYNC_STATUS, 1, &len, &status);
	expect("fence status has one value", len == 1);
	if (deferred) /* a real fence can have signalled already */
		expect("fresh fence unsignalled", status == GL_UNSIGNALED);
	GLint type = 0;
	bionic_glGetSynciv(s, GL_OBJECT_TYPE, 1, NULL, &type);
	expect("fence object type", type == GL_SYNC_FENCE);
	GLenum r = bionic_glClientWaitSync(s, 0, 0);
	if (deferred)
		expect("poll of an unflushed fence times out", r == GL_TIMEOUT_EXPIRED);
	r = bionic_glClientWaitSync(s, GL_SYNC_FLUSH_COMMANDS_BIT, 2000000000ull);
	expect("wait with flush signals", r == GL_CONDITION_SATISFIED || r == GL_ALREADY_SIGNALED);
	bionic_glDeleteSync(s);
	expect("deleted fence is not a sync", !bionic_glIsSync(s));
	s = bionic_glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
	GLsync s2 = bionic_glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
	bionic_glFlush();
	bionic_glFinish();
	r = bionic_glClientWaitSync(s, 0, 0);
	expect("fence after glFlush + glFinish signalled", r == GL_ALREADY_SIGNALED || r == GL_CONDITION_SATISFIED);
	bionic_glGetSynciv(s2, GL_SYNC_STATUS, 1, NULL, &status);
	expect("second fence signalled", status == GL_SIGNALED);
	bionic_glDeleteSync(s);
	bionic_glDeleteSync(s2);
	s = bionic_glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
	bionic_eglMakeCurrent(dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT); /* puts it in */
	bionic_eglMakeCurrent(dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, ctx);
	r = bionic_glClientWaitSync(s, 0, 2000000000ull);
	expect("fence put in by eglMakeCurrent signals", r == GL_ALREADY_SIGNALED || r == GL_CONDITION_SATISFIED);
	bionic_glDeleteSync(s);
	bionic_glDeleteSync(0);
	expect("no GL error", glGetError() == GL_NO_ERROR);

	/* eglGetProcAddress hands out the wrappers */
	expect("eglGetProcAddress(glClear) is the wrapper", (void *)bionic_eglGetProcAddress("glClear") == (void *)bionic_glClear);

	bionic_eglMakeCurrent(dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
	bionic_eglDestroyContext(dpy, ctx);
	printf("%d checks, %d failed\n", checks, failures);
	return failures ? 1 : 0;
}
