package org.teslalinux.gltest;

import android.app.Activity;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES10;
import android.opengl.GLES10Ext;
import android.opengl.GLES11;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLES30;
import android.opengl.GLES31;
import android.opengl.GLES31Ext;
import android.opengl.GLES32;
import android.os.Bundle;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.microedition.khronos.opengles.GL10;
import javax.microedition.khronos.opengles.GL11;

/**
 * ATL runtime test of the GL bindings: android.opengl.GLES10/10Ext/11/11Ext (on an OpenGL ES 1.1
 * context), GLES20/30/31/31Ext/32 (on an OpenGL ES 3.2 context) and the GL10/GL11 GLImpl, on
 * pbuffers of the host's EGL (llvmpipe in the VM). Checks results the GL defines (state set and
 * read back, uniforms, pixels drawn and read, buffer mapping, fences, the fixed-function matrix
 * stack) and AOSP's argument checks (the IllegalArgumentExceptions of the generated bindings), then
 * calls every native method of every class once with neutral arguments: none may be missing
 * (UnsatisfiedLinkError) and the context must still work afterwards.
 * GLES_TRACE=1 prints each call of that sweep first. Prints "GLTEST PASS/FAIL ..." and
 * "GLTEST RESULT passed/total", then exits.
 */
public class GlesTest extends Activity {
	static int passed, total;
	static final boolean TRACE = "1".equals(System.getenv("GLES_TRACE"));

	static void check(String name, boolean ok, String detail) {
		total++;
		if (ok)
			passed++;
		System.out.println("GLTEST " + (ok ? "PASS " : "FAIL ") + name + (ok || detail == null ? "" : " (" + detail + ")"));
		System.out.flush();
	}

	static void log(String s) {
		System.out.println("GLTEST " + s);
		System.out.flush();
	}

	interface Call {
		void run() throws Throwable;
	}

	/* the call must throw an IllegalArgumentException with AOSP's message */
	static void expectIAE(String name, String message, Call c) {
		try {
			c.run();
			check(name, false, "no exception");
		} catch (IllegalArgumentException e) {
			check(name, message == null || message.equals(e.getMessage()), e.getMessage());
		} catch (Throwable t) {
			check(name, false, t.toString());
		}
	}

	static EGLDisplay dpy;
	static String eglFailure;

	static EGLSurface makeCurrent(int renderableBit, int[] contextAttrs) {
		int[] attrs = {EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
		               EGL14.EGL_DEPTH_SIZE, 16, EGL14.EGL_STENCIL_SIZE, 8,
		               EGL14.EGL_RENDERABLE_TYPE, renderableBit, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE};
		EGLConfig[] c = new EGLConfig[1];
		int[] n = new int[1];
		if (!EGL14.eglChooseConfig(dpy, attrs, 0, c, 0, 1, n, 0) || n[0] < 1) {
			eglFailure = "eglChooseConfig: no config (0x" + Integer.toHexString(EGL14.eglGetError()) + ")";
			return null;
		}
		EGLContext ctx = EGL14.eglCreateContext(dpy, c[0], EGL14.EGL_NO_CONTEXT, contextAttrs, 0);
		if (ctx == null || ctx.equals(EGL14.EGL_NO_CONTEXT)) {
			eglFailure = "eglCreateContext failed (0x" + Integer.toHexString(EGL14.eglGetError()) + ")";
			return null;
		}
		EGLSurface s = EGL14.eglCreatePbufferSurface(dpy, c[0], new int[] {EGL14.EGL_WIDTH, 16, EGL14.EGL_HEIGHT, 16, EGL14.EGL_NONE}, 0);
		if (s == null || s.equals(EGL14.EGL_NO_SURFACE)) {
			eglFailure = "eglCreatePbufferSurface failed (0x" + Integer.toHexString(EGL14.eglGetError()) + ")";
			return null;
		}
		if (!EGL14.eglMakeCurrent(dpy, s, s, ctx)) {
			eglFailure = "eglMakeCurrent failed (0x" + Integer.toHexString(EGL14.eglGetError()) + ")";
			return null;
		}
		return s;
	}

	static void drainErrors() {
		for (int i = 0; i < 64 && GLES20.glGetError() != 0; i++)
			;
	}

	static ByteBuffer direct(int bytes) {
		return ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
	}

	/* the colour at (0,0) of the current framebuffer */
	static int[] pixel() {
		ByteBuffer px = direct(4);
		GLES20.glReadPixels(0, 0, 1, 1, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, px);
		return new int[] {px.get(0) & 0xff, px.get(1) & 0xff, px.get(2) & 0xff, px.get(3) & 0xff};
	}

	static boolean near(int[] got, int... want) {
		for (int i = 0; i < want.length; i++)
			if (Math.abs(got[i] - want[i]) > 2)
				return false;
		return true;
	}

	static int shader(int type, String src) {
		int s = GLES20.glCreateShader(type);
		GLES20.glShaderSource(s, src);
		GLES20.glCompileShader(s);
		int[] ok = new int[1];
		GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
		if (ok[0] == 0)
			log("shader: " + GLES20.glGetShaderInfoLog(s));
		return s;
	}

	/* ---- OpenGL ES 2.0 to 3.2 ---- */

	static void gles2Tests() {
		String version = GLES20.glGetString(GLES20.GL_VERSION);
		check("GLES20.glGetString(GL_VERSION) is OpenGL ES 3.x", version != null && version.startsWith("OpenGL ES 3"), version);

		GLES20.glScissor(1, 2, 3, 4);
		int[] box = new int[6];
		GLES20.glGetIntegerv(GLES20.GL_SCISSOR_BOX, box, 2);
		check("glScissor / glGetIntegerv(GL_SCISSOR_BOX, array, offset)", box[2] == 1 && box[3] == 2 && box[4] == 3 && box[5] == 4, Arrays.toString(box));
		IntBuffer ib = direct(16).asIntBuffer();
		GLES20.glGetIntegerv(GLES20.GL_SCISSOR_BOX, ib);
		check("glGetIntegerv(GL_SCISSOR_BOX, IntBuffer)", ib.get(0) == 1 && ib.get(3) == 4, null);
		GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
		check("glIsEnabled(GL_SCISSOR_TEST)", GLES20.glIsEnabled(GLES20.GL_SCISSOR_TEST), null);
		GLES20.glDisable(GLES20.GL_SCISSOR_TEST);

		GLES20.glFrontFace(GLES20.GL_CW);
		int[] v = new int[1];
		GLES20.glGetIntegerv(GLES20.GL_FRONT_FACE, v, 0);
		check("glFrontFace(GL_CW) sets GL_FRONT_FACE", v[0] == GLES20.GL_CW, "0x" + Integer.toHexString(v[0]));
		GLES20.glFrontFace(GLES20.GL_CCW);

		GLES20.glStencilFunc(GLES20.GL_EQUAL, 5, 0x3f);
		GLES20.glStencilOpSeparate(GLES20.GL_BACK, GLES20.GL_KEEP, GLES20.GL_INCR, GLES20.GL_DECR);
		GLES20.glStencilMaskSeparate(GLES20.GL_BACK, 0x0f);
		GLES20.glClearStencil(7);
		int[] st = new int[6];
		GLES20.glGetIntegerv(GLES20.GL_STENCIL_FUNC, st, 0);
		GLES20.glGetIntegerv(GLES20.GL_STENCIL_REF, st, 1);
		GLES20.glGetIntegerv(GLES20.GL_STENCIL_VALUE_MASK, st, 2);
		GLES20.glGetIntegerv(GLES20.GL_STENCIL_BACK_PASS_DEPTH_FAIL, st, 3);
		GLES20.glGetIntegerv(GLES20.GL_STENCIL_BACK_WRITEMASK, st, 4);
		GLES20.glGetIntegerv(GLES20.GL_STENCIL_CLEAR_VALUE, st, 5);
		check("glStencilFunc/OpSeparate/MaskSeparate/glClearStencil state",
		      st[0] == GLES20.GL_EQUAL && st[1] == 5 && st[2] == 0x3f && st[3] == GLES20.GL_INCR && st[4] == 0x0f && st[5] == 7, Arrays.toString(st));
		GLES20.glStencilFunc(GLES20.GL_ALWAYS, 0, 0xff);
		GLES20.glStencilMask(0xff);

		GLES20.glLineWidth(1f);
		GLES20.glDepthRangef(0.25f, 0.75f);
		float[] dr = new float[2];
		GLES20.glGetFloatv(GLES20.GL_DEPTH_RANGE, dr, 0);
		check("glDepthRangef / glGetFloatv(GL_DEPTH_RANGE)", dr[0] == 0.25f && dr[1] == 0.75f, Arrays.toString(dr));
		GLES20.glDepthRangef(0f, 1f);
		boolean[] mask = new boolean[4];
		GLES20.glColorMask(true, false, true, false);
		GLES20.glGetBooleanv(GLES20.GL_COLOR_WRITEMASK, mask, 0);
		check("glColorMask / glGetBooleanv", mask[0] && !mask[1] && mask[2] && !mask[3], Arrays.toString(mask));
		GLES20.glColorMask(true, true, true, true);

		int[] tex = new int[3];
		GLES20.glGenTextures(1, tex, 2);
		GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[2]);
		check("glGenTextures(offset) + glBindTexture: glIsTexture", tex[2] != 0 && GLES20.glIsTexture(tex[2]), "tex " + tex[2]);
		GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
		int[] tp = new int[1];
		GLES20.glGetTexParameteriv(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, tp, 0);
		check("glTexParameteri / glGetTexParameteriv", tp[0] == GLES20.GL_NEAREST, null);
		GLES20.glDeleteTextures(1, tex, 2);
		check("glDeleteTextures: glIsTexture false", !GLES20.glIsTexture(tex[2]), null);
		IntBuffer tb = direct(8).asIntBuffer();
		GLES20.glGenTextures(2, tb);
		check("glGenTextures(IntBuffer)", tb.get(0) != 0 && tb.get(1) != 0 && tb.get(0) != tb.get(1), null);
		GLES20.glDeleteTextures(2, tb);

		int[] rb = new int[1];
		GLES20.glGenRenderbuffers(1, rb, 0);
		GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, rb[0]);
		GLES20.glRenderbufferStorage(GLES20.GL_RENDERBUFFER, GLES20.GL_RGBA4, 8, 4);
		int[] rbw = new int[2];
		GLES20.glGetRenderbufferParameteriv(GLES20.GL_RENDERBUFFER, GLES20.GL_RENDERBUFFER_WIDTH, rbw, 0);
		GLES20.glGetRenderbufferParameteriv(GLES20.GL_RENDERBUFFER, GLES20.GL_RENDERBUFFER_HEIGHT, rbw, 1);
		check("glRenderbufferStorage / glGetRenderbufferParameteriv; glIsRenderbuffer",
		      rbw[0] == 8 && rbw[1] == 4 && GLES20.glIsRenderbuffer(rb[0]), Arrays.toString(rbw));
		int[] fb = new int[1];
		GLES20.glGenFramebuffers(1, fb, 0);
		GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fb[0]);
		GLES20.glFramebufferRenderbuffer(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_RENDERBUFFER, rb[0]);
		check("glFramebufferRenderbuffer: complete", GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE
		      && GLES20.glIsFramebuffer(fb[0]), null);
		GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
		GLES20.glDeleteFramebuffers(1, fb, 0);
		GLES20.glDeleteRenderbuffers(1, rb, 0);
		check("glDeleteFramebuffers / glDeleteRenderbuffers", !GLES20.glIsFramebuffer(fb[0]) && !GLES20.glIsRenderbuffer(rb[0]), null);

		GLES20.glViewport(0, 0, 16, 16);
		GLES20.glClearColor(0.25f, 0.5f, 0.75f, 1f);
		GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
		GLES20.glFinish();
		int[] px = pixel();
		check("glClearColor + glClear + glFinish + glReadPixels(Buffer)", near(px, 64, 128, 191, 255), Arrays.toString(px));

		/* a program with uniforms of every kind the test sets */
		/* every uniform used (a factor GL cannot fold away), so that all four are active */
		int vs = shader(GLES20.GL_VERTEX_SHADER, "#version 300 es\nuniform vec4 u4; uniform ivec2 i2; uniform uvec2 ui; uniform mat4 m; in vec4 p;"
		    + " flat out vec4 c; void main() { c = u4 + vec4(vec2(i2) + vec2(ui), 0, 0) * 1e-9; gl_Position = m * p; }");
		int fs = shader(GLES20.GL_FRAGMENT_SHADER, "#version 300 es\nprecision mediump float; flat in vec4 c; out vec4 o; void main() { o = c; }");
		int prog = GLES20.glCreateProgram();
		GLES20.glAttachShader(prog, vs);
		GLES20.glAttachShader(prog, fs);
		GLES20.glBindAttribLocation(prog, 3, "p");
		GLES20.glLinkProgram(prog);
		int[] linked = new int[1];
		GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linked, 0);
		check("glLinkProgram (" + GLES20.glGetProgramInfoLog(prog).trim() + ")", linked[0] == 1, null);
		check("glGetAttribLocation after glBindAttribLocation", GLES20.glGetAttribLocation(prog, "p") == 3, null);
		int[] shaders = new int[2], count = new int[1];
		GLES20.glGetAttachedShaders(prog, 2, count, 0, shaders, 0);
		check("glGetAttachedShaders", count[0] == 2 && (shaders[0] == vs || shaders[1] == vs), Arrays.toString(shaders));
		GLES20.glUseProgram(prog);
		int lu4 = GLES20.glGetUniformLocation(prog, "u4"), li2 = GLES20.glGetUniformLocation(prog, "i2");
		int lui = GLES20.glGetUniformLocation(prog, "ui"), lm = GLES20.glGetUniformLocation(prog, "m");
		check("glGetUniformLocation", lu4 >= 0 && li2 >= 0 && lui >= 0 && lm >= 0, lu4 + " " + li2 + " " + lui + " " + lm);
		float[] f = new float[5];
		GLES20.glUniform4f(lu4, 1, 2, 3, 4);
		GLES20.glGetUniformfv(prog, lu4, f, 1);
		check("glUniform4f / glGetUniformfv(offset)", f[1] == 1 && f[2] == 2 && f[3] == 3 && f[4] == 4, Arrays.toString(f));
		GLES20.glUniform4fv(lu4, 1, new float[] {0, 5, 6, 7, 8}, 1);
		GLES20.glGetUniformfv(prog, lu4, f, 0);
		check("glUniform4fv(array, offset)", f[0] == 5 && f[3] == 8, Arrays.toString(f));
		FloatBuffer ufb = direct(16).asFloatBuffer();
		ufb.put(new float[] {9, 10, 11, 12}).position(0);
		GLES20.glUniform4fv(lu4, 1, ufb);
		FloatBuffer back = direct(16).asFloatBuffer();
		GLES20.glGetUniformfv(prog, lu4, back);
		check("glUniform4fv(FloatBuffer) / glGetUniformfv(FloatBuffer)", back.get(0) == 9 && back.get(3) == 12, null);
		GLES20.glUniform4f(lu4, 1, 0, 0, 1);
		int[] iv = new int[3];
		GLES20.glUniform2i(li2, 3, -4);
		GLES20.glGetUniformiv(prog, li2, iv, 1);
		check("glUniform2i / glGetUniformiv", iv[1] == 3 && iv[2] == -4, Arrays.toString(iv));
		GLES20.glUniform2iv(li2, 1, new int[] {9, 7, 8}, 1);
		GLES20.glGetUniformiv(prog, li2, iv, 0);
		check("glUniform2iv(array, offset)", iv[0] == 7 && iv[1] == 8, Arrays.toString(iv));
		GLES30.glUniform2ui(lui, 5, 6);
		int[] uv = new int[2];
		GLES30.glGetUniformuiv(prog, lui, uv, 0);
		check("GLES30.glUniform2ui / glGetUniformuiv", uv[0] == 5 && uv[1] == 6, Arrays.toString(uv));
		float[] ident = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
		float[] mat = {2, 0, 0, 0, 0, 3, 0, 0, 0, 0, 4, 0, 5, 6, 7, 1};
		GLES20.glUniformMatrix4fv(lm, 1, false, mat, 0);
		float[] mb = new float[16];
		GLES20.glGetUniformfv(prog, lm, mb, 0);
		check("glUniformMatrix4fv / glGetUniformfv", Arrays.equals(mat, mb), Arrays.toString(mb));
		GLES20.glUniformMatrix4fv(lm, 1, false, ident, 0);
		int[] len = new int[1], size = new int[1], type = new int[1];
		String uname = GLES20.glGetActiveUniform(prog, 0, size, 0, type, 0);
		check("glGetActiveUniform", uname != null && Arrays.asList("u4", "i2", "ui", "m").contains(uname), uname);
		GLES20.glValidateProgram(prog);

		/* a full-viewport quad from a client-side array: glVertexAttribPointer(Buffer) + glDrawArrays */
		FloatBuffer quad = direct(32).asFloatBuffer();
		quad.put(new float[] {-1, -1, 1, -1, -1, 1, 1, 1}).position(0);
		GLES20.glEnableVertexAttribArray(3);
		GLES20.glVertexAttribPointer(3, 2, GLES20.GL_FLOAT, false, 0, quad);
		GLES20.glClearColor(0, 0, 0, 1);
		GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
		GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
		px = pixel();
		check("glVertexAttribPointer(Buffer) + glDrawArrays draws the uniform's colour", near(px, 255, 0, 0, 255), Arrays.toString(px));
		ShortBuffer idx = direct(8).asShortBuffer();
		idx.put(new short[] {0, 1, 2, 3}).position(0);
		GLES20.glUniform4f(lu4, 0, 1, 0, 1);
		GLES20.glDrawElements(GLES20.GL_TRIANGLE_STRIP, 4, GLES20.GL_UNSIGNED_SHORT, idx);
		px = pixel();
		check("glDrawElements(Buffer)", near(px, 0, 255, 0, 255), Arrays.toString(px));
		GLES20.glDisableVertexAttribArray(3);
		float[] va = new float[4];
		GLES20.glVertexAttrib4f(3, 0.5f, 0.25f, 1, 1);
		GLES20.glGetVertexAttribfv(3, GLES20.GL_CURRENT_VERTEX_ATTRIB, va, 0);
		check("glVertexAttrib4f / glGetVertexAttribfv", va[0] == 0.5f && va[1] == 0.25f, Arrays.toString(va));

		/* buffers: data, sub data, mapping (GLES30), pointer */
		int[] buf = new int[1];
		GLES20.glGenBuffers(1, buf, 0);
		GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, buf[0]);
		IntBuffer data = direct(16).asIntBuffer();
		data.put(new int[] {1, 2, 3, 4}).position(0);
		GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, 16, data, GLES20.GL_STATIC_DRAW);
		IntBuffer nine = direct(4).asIntBuffer();
		nine.put(0, 9);
		GLES20.glBufferSubData(GLES20.GL_ARRAY_BUFFER, 4, 4, nine);
		int[] bsz = new int[1];
		GLES20.glGetBufferParameteriv(GLES20.GL_ARRAY_BUFFER, GLES20.GL_BUFFER_SIZE, bsz, 0);
		check("glBufferData / glGetBufferParameteriv(GL_BUFFER_SIZE); glIsBuffer", bsz[0] == 16 && GLES20.glIsBuffer(buf[0]), null);
		Buffer mapped = GLES30.glMapBufferRange(GLES20.GL_ARRAY_BUFFER, 0, 16, GLES30.GL_MAP_READ_BIT);
		boolean mapOk = mapped instanceof ByteBuffer && ((ByteBuffer) mapped).capacity() == 16;
		if (mapOk) {
			IntBuffer mi = ((ByteBuffer) mapped).order(ByteOrder.nativeOrder()).asIntBuffer();
			mapOk = mi.get(0) == 1 && mi.get(1) == 9 && mi.get(2) == 3 && mi.get(3) == 4;
		}
		check("glBufferSubData + GLES30.glMapBufferRange reads the data", mapOk, String.valueOf(mapped));
		Buffer ptr = GLES30.glGetBufferPointerv(GLES20.GL_ARRAY_BUFFER, GLES30.GL_BUFFER_MAP_POINTER);
		check("GLES30.glGetBufferPointerv of the mapping", ptr instanceof ByteBuffer && ((ByteBuffer) ptr).capacity() == 16, String.valueOf(ptr));
		check("GLES30.glUnmapBuffer", GLES30.glUnmapBuffer(GLES20.GL_ARRAY_BUFFER), null);
		GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
		GLES20.glDeleteBuffers(1, buf, 0);

		/* GLES30 */
		String ext0 = GLES30.glGetStringi(GLES20.GL_EXTENSIONS, 0);
		check("GLES30.glGetStringi(GL_EXTENSIONS, 0)", ext0 != null && ext0.startsWith("GL_"), ext0);
		long sync = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
		check("GLES30.glFenceSync / glIsSync", sync != 0 && GLES30.glIsSync(sync), null);
		int w = GLES30.glClientWaitSync(sync, GLES30.GL_SYNC_FLUSH_COMMANDS_BIT, 1000000000L);
		check("GLES30.glClientWaitSync signals", w == GLES30.GL_ALREADY_SIGNALED || w == GLES30.GL_CONDITION_SATISFIED, "0x" + Integer.toHexString(w));
		int[] syncStatus = new int[1], syncLen = new int[1];
		GLES30.glGetSynciv(sync, GLES30.GL_SYNC_STATUS, 1, syncLen, 0, syncStatus, 0);
		check("GLES30.glGetSynciv(GL_SYNC_STATUS) signaled", syncStatus[0] == GLES30.GL_SIGNALED, "0x" + Integer.toHexString(syncStatus[0]));
		GLES30.glDeleteSync(sync);
		check("GLES30.glDeleteSync", !GLES30.glIsSync(sync), null);
		int[] vao = new int[1];
		GLES30.glGenVertexArrays(1, vao, 0);
		GLES30.glBindVertexArray(vao[0]);
		check("GLES30.glGenVertexArrays / glIsVertexArray", GLES30.glIsVertexArray(vao[0]), null);
		GLES30.glBindVertexArray(0);
		GLES30.glDeleteVertexArrays(1, vao, 0);
		long[] l64 = new long[1];
		GLES30.glGetInteger64v(GLES30.GL_MAX_ELEMENT_INDEX, l64, 0);
		check("GLES30.glGetInteger64v(GL_MAX_ELEMENT_INDEX)", l64[0] >= (1L << 24) - 1, String.valueOf(l64[0]));
		int[] q = new int[1];
		GLES30.glGenQueries(1, q, 0);
		GLES30.glBeginQuery(GLES30.GL_ANY_SAMPLES_PASSED, q[0]);
		GLES30.glEndQuery(GLES30.GL_ANY_SAMPLES_PASSED);
		check("GLES30.glGenQueries / glIsQuery", GLES30.glIsQuery(q[0]), null);
		GLES30.glDeleteQueries(1, q, 0);

		/* GLES31 and GLES32 */
		int[] wg = new int[1];
		GLES31.glGetIntegeri_v(GLES31.GL_MAX_COMPUTE_WORK_GROUP_COUNT, 0, wg, 0);
		check("GLES31.glGetIntegeri_v(GL_MAX_COMPUTE_WORK_GROUP_COUNT, 0)", wg[0] >= 65535, String.valueOf(wg[0]));
		int cs = shader(GLES31.GL_COMPUTE_SHADER, "#version 310 es\nlayout(local_size_x = 4) in; layout(std430, binding = 0) buffer B { uint v[]; };"
		    + " void main() { v[gl_GlobalInvocationID.x] = gl_GlobalInvocationID.x * 3u + 1u; }");
		int cprog = GLES20.glCreateProgram();
		GLES20.glAttachShader(cprog, cs);
		GLES20.glLinkProgram(cprog);
		int[] ssbo = new int[1];
		GLES20.glGenBuffers(1, ssbo, 0);
		GLES20.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, ssbo[0]);
		GLES20.glBufferData(GLES31.GL_SHADER_STORAGE_BUFFER, 32, null, GLES20.GL_DYNAMIC_DRAW);
		GLES30.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, 0, ssbo[0]);
		GLES20.glUseProgram(cprog);
		GLES31.glDispatchCompute(2, 1, 1);
		GLES31.glMemoryBarrier(GLES31.GL_BUFFER_UPDATE_BARRIER_BIT);
		Buffer res = GLES30.glMapBufferRange(GLES31.GL_SHADER_STORAGE_BUFFER, 0, 32, GLES30.GL_MAP_READ_BIT);
		String got = "";
		boolean csOk = res instanceof ByteBuffer;
		if (csOk) {
			IntBuffer r = ((ByteBuffer) res).order(ByteOrder.nativeOrder()).asIntBuffer();
			for (int i = 0; i < 8; i++) {
				got += r.get(i) + " ";
				csOk &= r.get(i) == i * 3 + 1;
			}
		}
		check("GLES31.glDispatchCompute writes a storage buffer", csOk, got);
		GLES30.glUnmapBuffer(GLES31.GL_SHADER_STORAGE_BUFFER);
		GLES20.glDeleteBuffers(1, ssbo, 0);
		GLES20.glUseProgram(prog);
		int[] iface = new int[1];
		GLES31.glGetProgramInterfaceiv(prog, GLES31.GL_UNIFORM, GLES31.GL_ACTIVE_RESOURCES, iface, 0);
		check("GLES31.glGetProgramInterfaceiv(GL_UNIFORM, GL_ACTIVE_RESOURCES)", iface[0] == 4, String.valueOf(iface[0]));
		check("GLES32.glGetGraphicsResetStatus", GLES32.glGetGraphicsResetStatus() == GLES20.GL_NO_ERROR, null);
		GLES32.glEnablei(GLES20.GL_BLEND, 0);
		boolean bi = GLES32.glIsEnabledi(GLES20.GL_BLEND, 0);
		GLES32.glDisablei(GLES20.GL_BLEND, 0);
		check("GLES32.glEnablei / glIsEnabledi / glDisablei", bi && !GLES32.glIsEnabledi(GLES20.GL_BLEND, 0), null);
		GLES32.glObjectLabel(GLES32.GL_PROGRAM, prog, -1, "the test program");
		check("GLES32.glObjectLabel / glGetObjectLabel", "the test program".equals(GLES32.glGetObjectLabel(GLES32.GL_PROGRAM, prog)),
		      GLES32.glGetObjectLabel(GLES32.GL_PROGRAM, prog));
		check("GLES31Ext.glGetObjectLabelKHR", "the test program".equals(GLES31Ext.glGetObjectLabelKHR(GLES32.GL_PROGRAM, prog)), null);
		String rname = GLES31.glGetProgramResourceName(prog, GLES31.GL_UNIFORM, 0);
		check("GLES31.glGetProgramResourceName(GL_UNIFORM, 0)", Arrays.asList("u4", "i2", "ui", "m").contains(rname), rname);
		debugTests();
		check("no GL error after the ES 2/3 checks", GLES20.glGetError() == GLES20.GL_NO_ERROR, null);

		/* AOSP's argument checks */
		expectIAE("glGenTextures(null array): IllegalArgumentException", "textures == null", () -> GLES20.glGenTextures(1, null, 0));
		expectIAE("glGenTextures(offset < 0)", "offset < 0", () -> GLES20.glGenTextures(1, new int[1], -1));
		expectIAE("glGenTextures(n beyond the array)", "length - offset < n < needed", () -> GLES20.glGenTextures(2, new int[2], 1));
		expectIAE("glUniform4fv(count beyond the array)", "length - offset < count*4 < needed", () -> GLES20.glUniform4fv(lu4, 2, new float[4], 0));
		expectIAE("glGetIntegerv(empty array)", "length - offset < needed", () -> GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, new int[0], 0));
		expectIAE("glVertexAttribPointer(heap Buffer)", "Must use a native order direct Buffer",
		          () -> GLES20.glVertexAttribPointer(3, 2, GLES20.GL_FLOAT, false, 0, FloatBuffer.allocate(8)));
		expectIAE("glGenTextures(null IntBuffer)", "textures == null", () -> GLES20.glGenTextures(1, (IntBuffer) null));
		drainErrors();
	}

	/* ---- KHR_debug: the message callback, the message log, pointer labels ---- */

	static void insert(int id, String message) {
		GLES32.glDebugMessageInsert(GLES32.GL_DEBUG_SOURCE_APPLICATION, GLES32.GL_DEBUG_TYPE_MARKER, id, GLES32.GL_DEBUG_SEVERITY_NOTIFICATION, -1, message);
	}

	static void debugTests() {
		GLES20.glEnable(GLES32.GL_DEBUG_OUTPUT);
		GLES20.glEnable(GLES32.GL_DEBUG_OUTPUT_SYNCHRONOUS);
		final List<String> heard = new ArrayList<>();
		final Thread glThread = Thread.currentThread();
		final boolean[] sameThread = {true};
		GLES32.DebugProc proc = (source, type, id, severity, message) -> {
			heard.add(source + "/" + type + "/" + id + "/" + severity + " " + message);
			sameThread[0] &= Thread.currentThread() == glThread;
		};
		GLES32.glDebugMessageCallback(proc);
		insert(42, "hello callback");
		String want = GLES32.GL_DEBUG_SOURCE_APPLICATION + "/" + GLES32.GL_DEBUG_TYPE_MARKER + "/42/" + GLES32.GL_DEBUG_SEVERITY_NOTIFICATION + " hello callback";
		check("GLES32.glDebugMessageCallback: the DebugProc hears glDebugMessageInsert's message", heard.contains(want) && sameThread[0], heard.toString());
		check("GLES32.glGetPointerv(GL_DEBUG_CALLBACK_FUNCTION) is set", GLES32.glGetPointerv(GLES32.GL_DEBUG_CALLBACK_FUNCTION) != 0, null);
		check("GLES31Ext.glGetDebugMessageCallbackKHR: a DebugProc that is no DebugProcKHR is not returned", GLES31Ext.glGetDebugMessageCallbackKHR() == null, null);

		final List<String> khrHeard = new ArrayList<>();
		GLES31Ext.DebugProcKHR khr = (source, type, id, severity, message) -> khrHeard.add(message);
		GLES31Ext.glDebugMessageCallbackKHR(khr);
		check("GLES31Ext.glDebugMessageCallbackKHR / glGetDebugMessageCallbackKHR", GLES31Ext.glGetDebugMessageCallbackKHR() == khr, null);
		insert(43, "to the KHR callback");
		check("the DebugProcKHR replaces the DebugProc", khrHeard.contains("to the KHR callback") && heard.size() == 1, khrHeard + " " + heard);

		GLES31Ext.glDebugMessageCallbackKHR((source, type, id, severity, message) -> {
			throw new IllegalStateException("thrown by the callback: " + message);
		});
		String thrown = null;
		try {
			insert(44, "throw");
		} catch (IllegalStateException e) {
			thrown = e.getMessage();
		}
		check("a callback's exception is thrown by the GL call that emitted the message", "thrown by the callback: throw".equals(thrown), thrown);

		/* no callback: messages go to the log */
		GLES32.glDebugMessageCallback(null);
		check("glDebugMessageCallback(null) clears it", GLES31Ext.glGetDebugMessageCallbackKHR() == null && GLES32.glGetPointerv(GLES32.GL_DEBUG_CALLBACK_FUNCTION) == 0, null);
		GLES32.glGetDebugMessageLog(64, null, 0, null, 0, null, 0, null, 0); /* empty the log */
		insert(7, "logged one");
		insert(8, "logged two");
		insert(9, "logged three");
		int[] logged = new int[1];
		GLES20.glGetIntegerv(GLES32.GL_DEBUG_LOGGED_MESSAGES, logged, 0);
		check("messages are logged without a callback", logged[0] == 3, String.valueOf(logged[0]));
		int[] ids = new int[2], sev = new int[1];
		String[] first = GLES32.glGetDebugMessageLog(1, null, 0, null, 0, ids, 1, sev, 0);
		check("GLES32.glGetDebugMessageLog (String[] form, arrays) returns the oldest message",
		      first != null && first.length == 1 && "logged one".equals(first[0]) && ids[1] == 7 && sev[0] == GLES32.GL_DEBUG_SEVERITY_NOTIFICATION,
		      first == null ? "null" : Arrays.toString(first) + " ids " + Arrays.toString(ids));
		int[] src = new int[1], lens = new int[1];
		byte[] text = new byte[80];
		int n = GLES32.glGetDebugMessageLog(1, 64, src, 0, null, 0, null, 0, null, 0, lens, 0, text, 16);
		String second = lens[0] > 0 ? new String(text, 16, lens[0] - 1) : "";
		check("GLES32.glGetDebugMessageLog (arrays and a byte[] log)", n == 1 && "logged two".equals(second) && src[0] == GLES32.GL_DEBUG_SOURCE_APPLICATION,
		      n + " " + second);
		IntBuffer hidBuf = IntBuffer.allocate(1); /* array backed */
		ByteBuffer hlog = ByteBuffer.allocate(64);
		IntBuffer hlen = direct(4).asIntBuffer();
		n = GLES31Ext.glGetDebugMessageLogKHR(1, null, null, hidBuf, null, hlen, hlog);
		String third = hlen.get(0) > 0 ? new String(hlog.array(), 0, hlen.get(0) - 1) : "";
		check("GLES31Ext.glGetDebugMessageLogKHR (Buffer form, array-backed buffers)", n == 1 && "logged three".equals(third) && hidBuf.get(0) == 9, n + " " + third + " " + hidBuf.get(0));
		String[] none = GLES31Ext.glGetDebugMessageLogKHR(4, null, null, null, null);
		check("the log is empty after reading it all", none != null && none.length == 0, none == null ? "null" : Arrays.toString(none));
		expectIAE("glGetDebugMessageLog(ids shorter than count)", "length - idsOffset < count < needed",
		          () -> GLES32.glGetDebugMessageLog(2, null, 0, null, 0, new int[1], 0, null, 0));
		GLES20.glDisable(GLES32.GL_DEBUG_OUTPUT_SYNCHRONOUS);

		/* pointer labels: a sync object */
		long sync = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
		GLES32.glObjectPtrLabel(sync, "a fence");
		check("GLES32.glObjectPtrLabel / glGetObjectPtrLabel", "a fence".equals(GLES32.glGetObjectPtrLabel(sync)), GLES32.glGetObjectPtrLabel(sync));
		GLES31Ext.glObjectPtrLabelKHR(sync, "the KHR label");
		check("GLES31Ext.glObjectPtrLabelKHR / glGetObjectPtrLabelKHR", "the KHR label".equals(GLES31Ext.glGetObjectPtrLabelKHR(sync)), null);
		GLES32.glObjectPtrLabel(sync, null);
		check("glObjectPtrLabel(null) removes the label", "".equals(GLES32.glGetObjectPtrLabel(sync)), GLES32.glGetObjectPtrLabel(sync));
		GLES30.glDeleteSync(sync);
		check("no GL error after the KHR_debug checks", GLES20.glGetError() == GLES20.GL_NO_ERROR, null);
	}

	/* ---- OpenGL ES 1.1 ---- */

	static void gles1Tests() {
		String version = GLES10.glGetString(GLES10.GL_VERSION);
		check("GLES10.glGetString(GL_VERSION) is OpenGL ES-CM 1.x", version != null && version.startsWith("OpenGL ES-CM 1"), version);
		GLES10.glMatrixMode(GLES10.GL_MODELVIEW);
		GLES10.glLoadIdentity();
		GLES10.glTranslatef(1, 2, 3);
		GLES10.glScalef(2, 2, 2);
		float[] mv = new float[16];
		GLES11.glGetFloatv(GLES11.GL_MODELVIEW_MATRIX, mv, 0);
		check("GLES10 matrix stack: glTranslatef + glScalef, GLES11.glGetFloatv(GL_MODELVIEW_MATRIX)",
		      mv[0] == 2 && mv[12] == 1 && mv[13] == 2 && mv[14] == 3, Arrays.toString(mv));
		GLES10.glPushMatrix();
		GLES10.glLoadIdentity();
		int[] depth = new int[1];
		GLES10.glGetIntegerv(GLES11.GL_MODELVIEW_STACK_DEPTH, depth, 0);
		GLES10.glPopMatrix();
		check("GLES10.glPushMatrix / glGetIntegerv(GL_MODELVIEW_STACK_DEPTH)", depth[0] == 2, String.valueOf(depth[0]));
		GLES10.glLoadIdentity();

		GLES10.glViewport(0, 0, 16, 16);
		GLES10.glClearColor(0, 0, 0, 1);
		GLES10.glClear(GLES10.GL_COLOR_BUFFER_BIT);
		FloatBuffer quad = direct(32).asFloatBuffer();
		quad.put(new float[] {-1, -1, 1, -1, -1, 1, 1, 1}).position(0);
		GLES10.glEnableClientState(GLES10.GL_VERTEX_ARRAY);
		GLES10.glVertexPointer(2, GLES10.GL_FLOAT, 0, quad);
		GLES10.glColor4f(0, 0, 1, 1);
		GLES10.glDrawArrays(GLES10.GL_TRIANGLE_STRIP, 0, 4);
		ByteBuffer px = direct(4);
		GLES10.glReadPixels(0, 0, 1, 1, GLES10.GL_RGBA, GLES10.GL_UNSIGNED_BYTE, px);
		int[] p = {px.get(0) & 0xff, px.get(1) & 0xff, px.get(2) & 0xff, px.get(3) & 0xff};
		check("GLES10.glVertexPointer(Buffer) + glColor4f + glDrawArrays", near(p, 0, 0, 255, 255), Arrays.toString(p));
		GLES10.glDisableClientState(GLES10.GL_VERTEX_ARRAY);
		float[] col = new float[4];
		GLES11.glGetFloatv(GLES11.GL_CURRENT_COLOR, col, 0);
		check("GLES11.glGetFloatv(GL_CURRENT_COLOR)", col[2] == 1 && col[0] == 0, Arrays.toString(col));
		int[] buf = new int[1];
		GLES11.glGenBuffers(1, buf, 0);
		GLES11.glBindBuffer(GLES11.GL_ARRAY_BUFFER, buf[0]);
		GLES11.glBufferData(GLES11.GL_ARRAY_BUFFER, 8, null, GLES11.GL_STATIC_DRAW);
		int[] bsz = new int[1];
		GLES11.glGetBufferParameteriv(GLES11.GL_ARRAY_BUFFER, GLES11.GL_BUFFER_SIZE, bsz, 0);
		check("GLES11.glGenBuffers / glBufferData / glGetBufferParameteriv; glIsBuffer", bsz[0] == 8 && GLES11.glIsBuffer(buf[0]), null);
		GLES11.glBindBuffer(GLES11.GL_ARRAY_BUFFER, 0);
		GLES11.glDeleteBuffers(1, buf, 0);
		GLES11.glTexEnvi(GLES11.GL_TEXTURE_ENV, GLES11.GL_TEXTURE_ENV_MODE, GLES11.GL_REPLACE);
		int[] env = new int[1];
		GLES11.glGetTexEnviv(GLES11.GL_TEXTURE_ENV, GLES11.GL_TEXTURE_ENV_MODE, env, 0);
		check("GLES11.glTexEnvi / glGetTexEnviv", env[0] == GLES11.GL_REPLACE, null);
		GLES10.glLightfv(GLES10.GL_LIGHT0, GLES10.GL_DIFFUSE, new float[] {0.5f, 0.25f, 1, 1}, 0);
		float[] lv = new float[4];
		GLES11.glGetLightfv(GLES10.GL_LIGHT0, GLES10.GL_DIFFUSE, lv, 0);
		check("GLES10.glLightfv / GLES11.glGetLightfv", lv[0] == 0.5f && lv[1] == 0.25f, Arrays.toString(lv));
		int[] mant = new int[16], exp = new int[16];
		int qm = GLES10Ext.glQueryMatrixxOES(mant, 0, exp, 0);
		check("GLES10Ext.glQueryMatrixxOES of the identity", qm == 0 && mant[0] != 0 && mant[1] == 0, "status " + qm + " " + mant[0]);
		String ext = GLES10.glGetString(GLES10.GL_EXTENSIONS);
		if (ext != null && ext.contains("GL_OES_framebuffer_object")) {
			int[] fb = new int[1];
			GLES11Ext.glGenFramebuffersOES(1, fb, 0);
			GLES11Ext.glBindFramebufferOES(GLES11Ext.GL_FRAMEBUFFER_OES, fb[0]);
			check("GLES11Ext.glGenFramebuffersOES / glIsFramebufferOES", GLES11Ext.glIsFramebufferOES(fb[0]), null);
			GLES11Ext.glBindFramebufferOES(GLES11Ext.GL_FRAMEBUFFER_OES, 0);
			GLES11Ext.glDeleteFramebuffersOES(1, fb, 0);
		}
		check("no GL error after the ES 1 checks", GLES10.glGetError() == GLES10.GL_NO_ERROR, null);
		expectIAE("GLES10.glLightfv(params shorter than GL_DIFFUSE needs)", "length - offset < needed",
		          () -> GLES10.glLightfv(GLES10.GL_LIGHT0, GLES10.GL_DIFFUSE, new float[2], 0));
		expectIAE("GLES11.glGetFloatv(GL_MODELVIEW_MATRIX into 4 floats)", "length - offset < needed",
		          () -> GLES11.glGetFloatv(GLES11.GL_MODELVIEW_MATRIX, new float[4], 0));

		/* GL10/GL11 as GLSurfaceView renderers get it */
		GL11 gl = new com.google.android.gles_jni.GLImpl();
		gl.glClearColor(1, 1, 0, 1);
		gl.glClear(GL10.GL_COLOR_BUFFER_BIT);
		px.clear();
		gl.glReadPixels(0, 0, 1, 1, GL10.GL_RGBA, GL10.GL_UNSIGNED_BYTE, px);
		p = new int[] {px.get(0) & 0xff, px.get(1) & 0xff, px.get(2) & 0xff, px.get(3) & 0xff};
		check("GLImpl (GL10): glClearColor + glClear + glReadPixels", near(p, 255, 255, 0, 255), Arrays.toString(p));
		gl.glMatrixMode(GL10.GL_PROJECTION);
		int[] mm = new int[1];
		gl.glGetIntegerv(GL11.GL_MATRIX_MODE, mm, 0);
		check("GLImpl (GL11): glMatrixMode / glGetIntegerv(GL_MATRIX_MODE)", mm[0] == GL10.GL_PROJECTION, null);
		gl.glMatrixMode(GL10.GL_MODELVIEW);
		check("GLImpl: glGetString(GL_RENDERER)", gl.glGetString(GL10.GL_RENDERER) != null, null);
		drainErrors();
	}

	/* ---- every native once ---- */

	static ByteBuffer scratch = direct(4096);

	static Object neutral(Class<?> t) {
		if (t == int.class)
			return 0;
		if (t == long.class)
			return 0L;
		if (t == float.class)
			return 0f;
		if (t == double.class)
			return 0d;
		if (t == boolean.class)
			return false;
		if (t == short.class)
			return (short)0;
		if (t == byte.class)
			return (byte)0;
		if (t == char.class)
			return (char)0;
		if (t == int[].class)
			return new int[64];
		if (t == float[].class)
			return new float[64];
		if (t == long[].class)
			return new long[64];
		if (t == short[].class)
			return new short[64];
		if (t == byte[].class)
			return new byte[256];
		if (t == boolean[].class)
			return new boolean[64];
		if (t == char[].class)
			return new char[64];
		if (t == double[].class)
			return new double[64];
		if (t == String.class)
			return "";
		if (t == String[].class)
			return new String[] {""};
		scratch.clear();
		for (int i = 0; i < scratch.capacity(); i++)
			scratch.put(i, (byte)0);
		if (t == IntBuffer.class)
			return scratch.asIntBuffer();
		if (t == FloatBuffer.class)
			return scratch.asFloatBuffer();
		if (t == ShortBuffer.class)
			return scratch.asShortBuffer();
		if (t == LongBuffer.class)
			return scratch.asLongBuffer();
		if (t == CharBuffer.class)
			return scratch.asCharBuffer();
		if (t == DoubleBuffer.class)
			return scratch.asDoubleBuffer();
		if (t == ByteBuffer.class || t == Buffer.class)
			return scratch;
		return null; /* the debug callback interfaces */
	}

	/* AOSP's glGetBufferPointerv reads GL_BUFFER_MAP_LENGTH/POINTER uninitialized on a GL error (an
	 * invalid target), on Android too: it is called on a mapped buffer in gles2Tests instead */
	static final List<String> SKIP = Arrays.asList("GLES30.glGetBufferPointerv");

	static void sweep(Class<?> cls, Object instance) {
		int calls = 0, other = 0;
		List<String> missing = new ArrayList<>();
		Method[] methods = cls.getDeclaredMethods();
		Arrays.sort(methods, (a, b) -> a.toString().compareTo(b.toString()));
		for (Method m : methods) {
			int mod = m.getModifiers();
			if (!Modifier.isNative(mod) || !Modifier.isPublic(mod) || Modifier.isStatic(mod) != (instance == null))
				continue;
			String name = cls.getSimpleName() + "." + m.getName();
			if (SKIP.contains(name))
				continue;
			Class<?>[] types = m.getParameterTypes();
			Object[] args = new Object[types.length];
			for (int i = 0; i < types.length; i++)
				args[i] = neutral(types[i]);
			if (TRACE)
				log("call " + m);
			calls++;
			try {
				m.invoke(instance, args);
			} catch (InvocationTargetException e) {
				Throwable c = e.getCause();
				if (c instanceof UnsatisfiedLinkError || c instanceof LinkageError)
					missing.add(m.getName() + Arrays.toString(types) + ": " + c);
				else
					other++; /* AOSP's own exceptions for the neutral arguments */
			} catch (Throwable t) {
				missing.add(m.getName() + ": " + t);
			}
		}
		drainErrors();
		check(cls.getSimpleName() + ": every native method is implemented (" + calls + " called, " + other + " threw AOSP's checks)",
		      calls > 0 && missing.isEmpty(), missing.size() + " missing: " + missing);
	}

	static void run() {
		dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
		int[] ver = new int[2];
		check("eglInitialize", EGL14.eglInitialize(dpy, ver, 0, ver, 1), null);

		/* OpenGL ES 3.2 (EGL_OPENGL_ES3_BIT_KHR, client version 3.2) */
		EGLSurface s3 = makeCurrent(0x40, new int[] {EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, 0x30FB /* EGL_CONTEXT_MINOR_VERSION_KHR */, 2, EGL14.EGL_NONE});
		check("an OpenGL ES 3.2 context on a pbuffer", s3 != null, eglFailure);
		if (s3 != null) {
			gles2Tests();
			for (Class<?> c : new Class<?>[] {GLES20.class, GLES30.class, GLES31.class, GLES31Ext.class, GLES32.class})
				sweep(c, null);
			/* what the sweep's zero arguments changed: glReadBuffer(0) and glDrawBuffers(0, ...)
			 * select no buffer, glBeginTransformFeedback / glBeginQuery may be active */
			GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
			GLES30.glReadBuffer(GLES20.GL_BACK);
			GLES30.glDrawBuffers(1, new int[] {GLES20.GL_BACK}, 0);
			GLES20.glUseProgram(0);
			GLES30.glBindVertexArray(0);
			GLES20.glDisable(GLES30.GL_RASTERIZER_DISCARD);
			GLES20.glViewport(0, 0, 16, 16);
			GLES20.glDisable(GLES20.GL_SCISSOR_TEST);
			GLES20.glColorMask(true, true, true, true);
			drainErrors();
			GLES20.glClearColor(0, 1, 1, 1);
			GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
			int[] px = pixel();
			check("the ES 3.2 context still renders after the sweep", near(px, 0, 255, 255, 255), Arrays.toString(px));
		}
		EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);

		/* OpenGL ES 1.1 (EGL_OPENGL_ES_BIT) */
		EGLSurface s1 = makeCurrent(EGL14.EGL_OPENGL_ES_BIT, new int[] {EGL14.EGL_CONTEXT_CLIENT_VERSION, 1, EGL14.EGL_NONE});
		if (s1 == null && "eglCreateContext failed (0x3003)".equals(eglFailure)) {
			/* the host's Mesa is built without OpenGL ES 1 (Debian's is): it makes no ES 1 context
			 * (EGL_BAD_ALLOC). The ES 1 results cannot be checked here; the ES 1 classes' natives
			 * are still all called, on an ES 3.2 context (where GL refuses the fixed-function calls
			 * with GL errors): none may be missing */
			log("NOTE no OpenGL ES 1 context on this host (Mesa without GLES1): the ES 1.1 result checks are left out");
			EGLSurface s = makeCurrent(0x40, new int[] {EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE});
			check("an OpenGL ES 3 context for the ES 1 classes' sweep", s != null, eglFailure);
			if (s != null) {
				for (Class<?> c : new Class<?>[] {GLES10.class, GLES10Ext.class, GLES11.class, GLES11Ext.class})
					sweep(c, null);
				sweep(com.google.android.gles_jni.GLImpl.class, new com.google.android.gles_jni.GLImpl());
			}
		} else {
			check("an OpenGL ES 1.1 context on a pbuffer", s1 != null, eglFailure);
		}
		if (s1 != null) {
			gles1Tests();
			for (Class<?> c : new Class<?>[] {GLES10.class, GLES10Ext.class, GLES11.class, GLES11Ext.class})
				sweep(c, null);
			sweep(com.google.android.gles_jni.GLImpl.class, new com.google.android.gles_jni.GLImpl());
			GLES10.glBindTexture(GLES10.GL_TEXTURE_2D, 0);
			GLES10.glViewport(0, 0, 16, 16);
			GLES10.glDisable(GLES10.GL_SCISSOR_TEST);
			GLES10.glColorMask(true, true, true, true);
			GLES10.glClearColor(1, 0, 1, 1);
			GLES10.glClear(GLES10.GL_COLOR_BUFFER_BIT);
			int[] px = pixel();
			check("the ES 1.1 context still renders after the sweep", near(px, 255, 0, 255, 255), Arrays.toString(px));
		}
		EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
	}

	@Override
	protected void onCreate(Bundle b) {
		super.onCreate(b);
		new Thread(() -> {
			try {
				run();
			} catch (Throwable t) {
				check("the GL binding tests ran to the end", false, t.toString());
				t.printStackTrace();
			}
			System.out.println("GLTEST RESULT " + passed + "/" + total);
			System.out.flush();
			System.exit(passed == total ? 0 : 1);
		}, "gles-test").start();
	}
}
