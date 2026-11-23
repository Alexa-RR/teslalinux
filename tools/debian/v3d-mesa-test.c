/* teslalinux: what the Mesa patches for the Pi's V3D (tools/debvm/patches/mesa-*.patch) must keep
 * true, checked against the GL's own results on a Pi's V3D, or any Mesa driver, surfaceless:
 *
 *   gcc -O1 -o v3d-mesa-test v3d-mesa-test.c -lEGL -lGLESv2
 *   ./v3d-mesa-test [ITERATIONS]          (LD_LIBRARY_PATH picks the Mesa to test)
 *
 * 1. Vertex shaders that read what the GPU just rendered get it (v3d's binner runs the coordinate
 *    shader before the job's render, so a bin job must wait for the rendering it reads:
 *    mesa-v3d-vs-texture-bin-sync.patch). Each iteration a pass renders the value i into texture T,
 *    with a fragment shader slow enough that the render takes a while (the race window); then a
 *    draw into F whose vertex shader reads T puts its column where that value says; F's pixels must
 *    show it there. Three ways T gets written: a render pass (a draw), a clear, and glGenerateMipmap
 *    (V3D's TFU) read at level 1; and one texture only uploaded from the CPU, which needs no wait.
 * 2. A blit that covers its whole destination gives exactly the blit's pixels, and one that covers
 *    part of it keeps the rest (mesa-v3d-blit-no-load.patch: the destination is not loaded first
 *    when the blit overwrites all of it), with the destination drawn into just before.
 * Prints PASS/FAIL per kind and "RESULT passed/total"; exits non-zero on a failure.
 */
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static int checks, failures;

static void expect(const char *what, int ok, int iteration, int got, int want)
{
	checks++;
	if (!ok) {
		failures++;
		if (failures <= 20)
			printf("FAIL %s (iteration %d: got %d, expected %d)\n", what, iteration, got, want);
	}
}

static GLuint shader(GLenum type, const char *src)
{
	GLuint s = glCreateShader(type);
	glShaderSource(s, 1, &src, NULL);
	glCompileShader(s);
	GLint ok = 0;
	glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
	if (!ok) {
		char log[2048];
		glGetShaderInfoLog(s, sizeof log, NULL, log);
		fprintf(stderr, "shader: %s\n", log);
		exit(2);
	}
	return s;
}

static GLuint program(const char *vs, const char *fs)
{
	GLuint p = glCreateProgram();
	glAttachShader(p, shader(GL_VERTEX_SHADER, vs));
	glAttachShader(p, shader(GL_FRAGMENT_SHADER, fs));
	glLinkProgram(p);
	GLint ok = 0;
	glGetProgramiv(p, GL_LINK_STATUS, &ok);
	if (!ok) {
		fprintf(stderr, "link failed\n");
		exit(2);
	}
	return p;
}

/* a full-screen triangle whose fragments write u_value / 255 after a slow loop */
static const char *slow_vs =
	"#version 300 es\n"
	"void main() { vec2 p = vec2(float((gl_VertexID & 1) << 2) - 1.0, float((gl_VertexID & 2) << 1) - 1.0);"
	" gl_Position = vec4(p, 0.0, 1.0); }\n";
static const char *slow_fs =
	"#version 300 es\nprecision highp float;\nuniform float u_value; uniform int u_loops; out vec4 c;\n"
	"void main() { float a = gl_FragCoord.x; for (int i = 0; i < u_loops; i++) a = fract(a * 1.0001 + 0.37);"
	" c = vec4(u_value / 255.0, a * 1e-6, 0.0, 1.0); }\n";
/* two triangles covering column (T's red * 255) of F: the vertex shader reads T at LEVEL */
static const char *read_vs =
	"#version 300 es\nuniform sampler2D u_tex; uniform int u_level;\nout float v_value;\n"
	"void main() { float v = texelFetch(u_tex, ivec2(0, 0), u_level).r * 255.0; v_value = v;"
	" float x0 = (floor(v + 0.5) + 0.2) / 64.0 - 1.0, x1 = (floor(v + 0.5) + 0.8) / 64.0 - 1.0;"
	" vec2 p[6] = vec2[6](vec2(x0, -1.0), vec2(x1, -1.0), vec2(x0, 1.0), vec2(x1, -1.0), vec2(x1, 1.0), vec2(x0, 1.0));"
	" gl_Position = vec4(p[gl_VertexID], 0.0, 1.0); }\n";
static const char *read_fs =
	"#version 300 es\nprecision highp float;\nin float v_value; out vec4 c;\n"
	"void main() { c = vec4(1.0, 1.0, 1.0, 1.0); }\n";

static GLuint texture(int size, int levels)
{
	GLuint t;
	glGenTextures(1, &t);
	glBindTexture(GL_TEXTURE_2D, t);
	glTexStorage2D(GL_TEXTURE_2D, levels, GL_RGBA8, size, size);
	glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, levels > 1 ? GL_NEAREST_MIPMAP_NEAREST : GL_NEAREST);
	glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
	return t;
}

static GLuint framebuffer(GLuint tex)
{
	GLuint f;
	glGenFramebuffers(1, &f);
	glBindFramebuffer(GL_FRAMEBUFFER, f);
	glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex, 0);
	if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
		fprintf(stderr, "framebuffer incomplete\n");
		exit(2);
	}
	return f;
}

/* draw into F (128x8) the triangle T's value places, read F back: the column it covers, or -1 */
static int place_and_find(GLuint read_prog, GLuint fb, GLuint tex, int level)
{
	static unsigned char px[128 * 8 * 4];
	glBindFramebuffer(GL_FRAMEBUFFER, fb);
	glViewport(0, 0, 128, 8);
	glClearColor(0, 0, 0, 1);
	glClear(GL_COLOR_BUFFER_BIT);
	glUseProgram(read_prog);
	glActiveTexture(GL_TEXTURE0);
	glBindTexture(GL_TEXTURE_2D, tex);
	glUniform1i(glGetUniformLocation(read_prog, "u_tex"), 0);
	glUniform1i(glGetUniformLocation(read_prog, "u_level"), level);
	glDrawArrays(GL_TRIANGLES, 0, 6);
	glReadPixels(0, 0, 128, 8, GL_RGBA, GL_UNSIGNED_BYTE, px);
	for (int x = 0; x < 128; x++)
		if (px[(4 * 128 + x) * 4] > 128)
			return x;
	return -1;
}

/* 2: blits into a destination drawn into just before (value 9 everywhere), from a source holding
 * value v (flipped upside down, as an app's final copy into its window): a full one must leave v
 * everywhere, a partial one v inside its rectangle and 9 outside */
static void blits(GLuint slow, int iterations)
{
	const int W = 256, H = 160;
	GLuint src = framebuffer(texture(512, 1));
	/* the destination: a W x H renderbuffer, so the blit can cover all of it */
	GLuint rb, fdst;
	glGenRenderbuffers(1, &rb);
	glBindRenderbuffer(GL_RENDERBUFFER, rb);
	glRenderbufferStorage(GL_RENDERBUFFER, GL_RGBA8, W, H);
	glGenFramebuffers(1, &fdst);
	glBindFramebuffer(GL_FRAMEBUFFER, fdst);
	glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, rb);
	static unsigned char px[256 * 160 * 4];
	int wrong_full = 0, wrong_part = 0;
	for (int i = 0; i < iterations; i++) {
		int v = 20 + (i * 53) % 200;
		glBindFramebuffer(GL_FRAMEBUFFER, src);
		glViewport(0, 0, 512, 512);
		glUseProgram(slow);
		glUniform1f(glGetUniformLocation(slow, "u_value"), (float)v);
		glUniform1i(glGetUniformLocation(slow, "u_loops"), 20);
		glDrawArrays(GL_TRIANGLES, 0, 3);
		for (int full = 1; full >= 0; full--) {
			glBindFramebuffer(GL_FRAMEBUFFER, fdst);
			glViewport(0, 0, W, H);
			glUniform1f(glGetUniformLocation(slow, "u_value"), 9.0f);
			glDrawArrays(GL_TRIANGLES, 0, 3);
			glBindFramebuffer(GL_READ_FRAMEBUFFER, src);
			glBindFramebuffer(GL_DRAW_FRAMEBUFFER, fdst);
			if (full)
				glBlitFramebuffer(0, 0, W, H, 0, H, W, 0, GL_COLOR_BUFFER_BIT, GL_NEAREST);
			else
				glBlitFramebuffer(0, 0, 100, 50, 30, 20, 130, 70, GL_COLOR_BUFFER_BIT, GL_NEAREST);
			glBindFramebuffer(GL_READ_FRAMEBUFFER, fdst);
			glReadPixels(0, 0, W, H, GL_RGBA, GL_UNSIGNED_BYTE, px);
			int bad = 0;
			for (int y = 0; y < H && !bad; y++)
				for (int x = 0; x < W && !bad; x++) {
					int inside = full || (x >= 30 && x < 130 && y >= 20 && y < 70);
					int want = inside ? v : 9;
					int got = px[(y * W + x) * 4];
					if (got < want - 1 || got > want + 1) {
						bad = 1;
						expect(full ? "full blit" : "partial blit", 0, i, got, want);
					}
				}
			if (!bad)
				expect("blit", 1, i, 0, 0);
			if (bad && full)
				wrong_full++;
			if (bad && !full)
				wrong_part++;
		}
	}
	printf("%s a blit over the whole destination gives the blit's pixels (%d of %d wrong)\n", wrong_full ? "FAIL" : "PASS", wrong_full, iterations);
	printf("%s a blit over part of the destination keeps the rest (%d of %d wrong)\n", wrong_part ? "FAIL" : "PASS", wrong_part, iterations);
}

int main(int argc, char **argv)
{
	int iterations = argc > 1 ? atoi(argv[1]) : 120;
	PFNEGLGETPLATFORMDISPLAYEXTPROC get_platform_display = (PFNEGLGETPLATFORMDISPLAYEXTPROC)eglGetProcAddress("eglGetPlatformDisplayEXT");
	EGLDisplay dpy = get_platform_display ? get_platform_display(EGL_PLATFORM_SURFACELESS_MESA, EGL_DEFAULT_DISPLAY, NULL) : EGL_NO_DISPLAY;
	if (dpy == EGL_NO_DISPLAY || !eglInitialize(dpy, NULL, NULL) || !eglBindAPI(EGL_OPENGL_ES_API)) {
		fprintf(stderr, "no surfaceless EGL display\n");
		return 2;
	}
	const EGLint cattr[] = { EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE };
	EGLContext ctx = eglCreateContext(dpy, EGL_NO_CONFIG_KHR, EGL_NO_CONTEXT, cattr);
	if (ctx == EGL_NO_CONTEXT || !eglMakeCurrent(dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, ctx)) {
		fprintf(stderr, "no GLES 3 context\n");
		return 2;
	}
	printf("GL_RENDERER %s, GL_VERSION %s\n", glGetString(GL_RENDERER), glGetString(GL_VERSION));
	GLuint slow = program(slow_vs, slow_fs), reader = program(read_vs, read_fs);
	GLuint vao;
	glGenVertexArrays(1, &vao);
	glBindVertexArray(vao);
	GLuint t_draw = texture(512, 1), t_clear = texture(512, 1), t_mip = texture(512, 2), t_cpu = texture(4, 1);
	GLuint f_draw = framebuffer(t_draw), f_clear = framebuffer(t_clear), f_mip = framebuffer(t_mip);
	GLuint f_out = framebuffer(texture(128, 1));
	glBindTexture(GL_TEXTURE_2D, 0);
	int fail_before[4] = { 0 };
	const char *names[4] = { "a vertex shader reads what a draw rendered", "a vertex shader reads what a clear wrote",
	                         "a vertex shader reads the mipmap level glGenerateMipmap made", "a vertex shader reads a CPU upload" };
	for (int i = 0; i < iterations; i++) {
		int v = 1 + (i * 37) % 120;
		/* 1: a slow draw writes v */
		glBindFramebuffer(GL_FRAMEBUFFER, f_draw);
		glViewport(0, 0, 512, 512);
		glUseProgram(slow);
		glUniform1f(glGetUniformLocation(slow, "u_value"), (float)v);
		glUniform1i(glGetUniformLocation(slow, "u_loops"), 200);
		glDrawArrays(GL_TRIANGLES, 0, 3);
		int got = place_and_find(reader, f_out, t_draw, 0);
		int before = failures;
		expect(names[0], got == v, i, got, v);
		fail_before[0] += failures - before;
		/* 2: a slow draw elsewhere keeps the GPU busy, then a clear writes v */
		glBindFramebuffer(GL_FRAMEBUFFER, f_draw);
		glUseProgram(slow);
		glUniform1f(glGetUniformLocation(slow, "u_value"), 0.0f);
		glDrawArrays(GL_TRIANGLES, 0, 3);
		glBindFramebuffer(GL_FRAMEBUFFER, f_clear);
		glClearColor(v / 255.0f, 0, 0, 1);
		glClear(GL_COLOR_BUFFER_BIT);
		got = place_and_find(reader, f_out, t_clear, 0);
		before = failures;
		expect(names[1], got == v, i, got, v);
		fail_before[1] += failures - before;
		/* 3: a slow draw writes v into level 0, the TFU makes level 1 from it */
		glBindFramebuffer(GL_FRAMEBUFFER, f_mip);
		glViewport(0, 0, 512, 512);
		glUseProgram(slow);
		glUniform1f(glGetUniformLocation(slow, "u_value"), (float)v);
		glDrawArrays(GL_TRIANGLES, 0, 3);
		glBindTexture(GL_TEXTURE_2D, t_mip);
		glGenerateMipmap(GL_TEXTURE_2D);
		got = place_and_find(reader, f_out, t_mip, 1);
		before = failures;
		expect(names[2], got == v, i, got, v);
		fail_before[2] += failures - before;
		/* 4: a CPU upload of v */
		unsigned char texel[4 * 4 * 4];
		for (int k = 0; k < 16; k++)
			texel[4 * k] = (unsigned char)v, texel[4 * k + 1] = texel[4 * k + 2] = 0, texel[4 * k + 3] = 255;
		glBindTexture(GL_TEXTURE_2D, t_cpu);
		glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 4, 4, GL_RGBA, GL_UNSIGNED_BYTE, texel);
		got = place_and_find(reader, f_out, t_cpu, 0);
		before = failures;
		expect(names[3], got == v, i, got, v);
		fail_before[3] += failures - before;
	}
	for (int k = 0; k < 4; k++)
		printf("%s %s (%d of %d iterations wrong)\n", fail_before[k] ? "FAIL" : "PASS", names[k], fail_before[k], iterations);
	blits(slow, iterations);
	printf("RESULT %d/%d\n", checks - failures, checks);
	return failures ? 1 : 0;
}
