package org.teslalinux.gltest;

import android.app.Activity;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * ATL runtime test of GL streams: SurfaceTexture (an EGL producer on one thread and context, the
 * GL_TEXTURE_EXTERNAL_OES consumer on another: frames, timestamps, FIFO order, the transform
 * matrix, one producer at a time, Surface(SurfaceTexture), detach/attach, releaseTexImage,
 * release) and EGL_ANDROID_presentation_time on a SurfaceView (a frame is held until its time;
 * a due frame is dropped for a newer due one: run.sh reads ATL_DEBUG_SURFACE's latch lines).
 * Prints "GLTEST PASS/FAIL ..." and "GLTEST RESULT passed/total", then exits.
 */
public class GlTest extends Activity implements SurfaceHolder.Callback {
	static int passed, total;
	static final int GL_TEXTURE_EXTERNAL_OES = 0x8D65;

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

	static EGLDisplay dpy;
	static EGLConfig cfg;

	static void initEgl() {
		dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
		int[] v = new int[2];
		EGL14.eglInitialize(dpy, v, 0, v, 1);
		int[] attrs = {EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
		               EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT | EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE};
		EGLConfig[] c = new EGLConfig[1];
		int[] n = new int[1];
		EGL14.eglChooseConfig(dpy, attrs, 0, c, 0, 1, n, 0);
		cfg = c[0];
	}

	static EGLContext newContext() {
		return EGL14.eglCreateContext(dpy, cfg, EGL14.EGL_NO_CONTEXT, new int[] {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
	}

	static EGLSurface pbuffer() {
		return EGL14.eglCreatePbufferSurface(dpy, cfg, new int[] {EGL14.EGL_WIDTH, 16, EGL14.EGL_HEIGHT, 16, EGL14.EGL_NONE}, 0);
	}

	static boolean noSurface(EGLSurface s) {
		return s == null || s.equals(EGL14.EGL_NO_SURFACE) || s.getNativeHandle() == 0;
	}

	/* ---- the consumer's sampling: the external texture drawn into a framebuffer and read back ---- */

	static int program;
	static int fbo, fboTex;

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

	static void setupSampler() {
		program = GLES20.glCreateProgram();
		GLES20.glAttachShader(program, shader(GLES20.GL_VERTEX_SHADER,
		    "attribute vec2 p; uniform mat4 m; varying vec2 t; void main() { t = (m * vec4((p + 1.0) * 0.5, 0.0, 1.0)).xy; gl_Position = vec4(p, 0.0, 1.0); }"));
		GLES20.glAttachShader(program, shader(GLES20.GL_FRAGMENT_SHADER,
		    "#extension GL_OES_EGL_image_external : require\nprecision mediump float; uniform samplerExternalOES s; varying vec2 t; void main() { gl_FragColor = texture2D(s, t); }"));
		GLES20.glLinkProgram(program);
		int[] tex = new int[1];
		GLES20.glGenTextures(1, tex, 0);
		fboTex = tex[0];
		GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex);
		GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 64, 32, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
		int[] f = new int[1];
		GLES20.glGenFramebuffers(1, f, 0);
		fbo = f[0];
		GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
		GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fboTex, 0);
	}

	/** the RGBA at (x, y) of the external texture drawn with the SurfaceTexture's matrix */
	static int sample(int tex, float[] mtx, int x, int y) {
		GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
		GLES20.glViewport(0, 0, 64, 32);
		GLES20.glClearColor(0.5f, 0.5f, 0.5f, 0.5f);
		GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
		GLES20.glUseProgram(program);
		GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
		GLES20.glBindTexture(GL_TEXTURE_EXTERNAL_OES, tex);
		GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "s"), 0);
		GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "m"), 1, false, mtx, 0);
		FloatBuffer quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
		quad.put(new float[] {-1, -1, 1, -1, -1, 1, 1, 1}).position(0);
		int p = GLES20.glGetAttribLocation(program, "p");
		GLES20.glVertexAttribPointer(p, 2, GLES20.GL_FLOAT, false, 0, quad);
		GLES20.glEnableVertexAttribArray(p);
		GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
		ByteBuffer px = ByteBuffer.allocateDirect(4);
		GLES20.glReadPixels(x, y, 1, 1, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, px);
		return ((px.get(0) & 0xff) << 24) | ((px.get(1) & 0xff) << 16) | ((px.get(2) & 0xff) << 8) | (px.get(3) & 0xff);
	}

	/* ---- the producer: its own thread and context ---- */

	static final class Producer {
		final HandlerThread thread = new HandlerThread("gl-producer");
		final Handler handler;
		EGLContext context;
		EGLSurface surface;

		Producer() {
			thread.start();
			handler = new Handler(thread.getLooper());
		}

		Object run(final java.util.concurrent.Callable<Object> body) throws Exception {
			final Object[] out = new Object[1];
			final Exception[] err = new Exception[1];
			final Semaphore done = new Semaphore(0);
			handler.post(new Runnable() {
				public void run() {
					try {
						out[0] = body.call();
					} catch (Exception e) {
						err[0] = e;
					}
					done.release();
				}
			});
			if (!done.tryAcquire(10, TimeUnit.SECONDS))
				throw new RuntimeException("producer timed out");
			if (err[0] != null)
				throw err[0];
			return out[0];
		}

		EGLSurface connect(final Object window) throws Exception {
			return (EGLSurface) run(() -> {
				if (context == null)
					context = newContext();
				surface = EGL14.eglCreateWindowSurface(dpy, cfg, window, new int[] {EGL14.EGL_NONE}, 0);
				if (!noSurface(surface))
					EGL14.eglMakeCurrent(dpy, surface, surface, context);
				return surface;
			});
		}

		boolean frame(final float r, final float g, final float b, final long presentNs) throws Exception {
			return (Boolean) run(() -> {
				GLES20.glViewport(0, 0, 64, 32);
				GLES20.glClearColor(r, g, b, 1f);
				GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
				if (presentNs != 0)
					EGLExt.eglPresentationTimeANDROID(dpy, surface, presentNs);
				return EGL14.eglSwapBuffers(dpy, surface);
			});
		}

		/* the bottom half red, the top half blue (GL window coordinates), drawn by a shader */
		boolean splitFrame() throws Exception {
			return (Boolean) run(() -> {
				int prog = GLES20.glCreateProgram();
				GLES20.glAttachShader(prog, shader(GLES20.GL_VERTEX_SHADER, "attribute vec2 p; void main() { gl_Position = vec4(p, 0.0, 1.0); }"));
				GLES20.glAttachShader(prog, shader(GLES20.GL_FRAGMENT_SHADER,
				    "precision mediump float; void main() { gl_FragColor = gl_FragCoord.y < 16.0 ? vec4(1.0, 0.0, 0.0, 1.0) : vec4(0.0, 0.0, 1.0, 1.0); }"));
				GLES20.glLinkProgram(prog);
				GLES20.glViewport(0, 0, 64, 32);
				GLES20.glUseProgram(prog);
				FloatBuffer quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
				quad.put(new float[] {-1, -1, 1, -1, -1, 1, 1, 1}).position(0);
				int p = GLES20.glGetAttribLocation(prog, "p");
				GLES20.glVertexAttribPointer(p, 2, GLES20.GL_FLOAT, false, 0, quad);
				GLES20.glEnableVertexAttribArray(p);
				GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
				return EGL14.eglSwapBuffers(dpy, surface);
			});
		}

		void disconnect() throws Exception {
			run(() -> {
				EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
				EGL14.eglDestroySurface(dpy, surface);
				surface = null;
				return null;
			});
		}
	}

	static final int RED = 0xff0000ff, GREEN = 0x00ff00ff, BLUE = 0x0000ffff, YELLOW = 0xffff00ff;

	static String hex(int c) {
		return String.format("%08x", c);
	}

	static void surfaceTextureTests() throws Exception {
		initEgl();
		EGLContext consumer = newContext();
		EGLSurface pb = pbuffer();
		EGL14.eglMakeCurrent(dpy, pb, pb, consumer);
		setupSampler();
		int[] t = new int[2];
		GLES20.glGenTextures(2, t, 0);
		final SurfaceTexture st = new SurfaceTexture(t[0]);
		st.setDefaultBufferSize(64, 32);
		HandlerThread listenerThread = new HandlerThread("st-listener");
		listenerThread.start();
		final Semaphore frames = new Semaphore(0);
		final String[] listenerThreadName = new String[1];
		st.setOnFrameAvailableListener(new SurfaceTexture.OnFrameAvailableListener() {
			public void onFrameAvailable(SurfaceTexture s) {
				listenerThreadName[0] = Thread.currentThread().getName();
				frames.release();
			}
		}, new Handler(listenerThread.getLooper()));

		Producer producer = new Producer();
		EGLSurface ws = producer.connect(st);
		check("eglCreateWindowSurface(SurfaceTexture) makes a window surface", !noSurface(ws), "" + ws);
		int[] wh = new int[2];
		EGL14.eglQuerySurface(dpy, ws, EGL14.EGL_WIDTH, wh, 0);
		EGL14.eglQuerySurface(dpy, ws, EGL14.EGL_HEIGHT, wh, 1);
		check("of the default buffer size (64x32)", wh[0] == 64 && wh[1] == 32, wh[0] + "x" + wh[1]);
		check("a second producer cannot connect while one is", noSurface(EGL14.eglCreateWindowSurface(dpy, cfg, new Surface(st), new int[] {EGL14.EGL_NONE}, 0)), "");

		check("the producer's frame swaps", producer.frame(1, 0, 0, 123456789L), "");
		check("onFrameAvailable on the listener's Handler", frames.tryAcquire(5, TimeUnit.SECONDS) && "st-listener".equals(listenerThreadName[0]), "" + listenerThreadName[0]);
		st.updateTexImage();
		float[] m = new float[16];
		st.getTransformMatrix(m);
		boolean identity = true;
		for (int i = 0; i < 16; i++)
			identity &= m[i] == ((i % 5 == 0) ? 1f : 0f);
		check("the transform matrix (a GL-drawn frame needs none)", identity, java.util.Arrays.toString(m));
		int c = sample(t[0], m, 32, 16);
		check("updateTexImage: the frame in the external texture (red)", c == RED, hex(c));
		check("getTimestamp: the frame's presentation time", st.getTimestamp() == 123456789L, "" + st.getTimestamp());

		long before = System.nanoTime();
		producer.frame(0, 1, 0, 0);
		frames.tryAcquire(5, TimeUnit.SECONDS);
		st.updateTexImage();
		long ts = st.getTimestamp();
		c = sample(t[0], m, 32, 16);
		check("the next frame (green)", c == GREEN, hex(c));
		check("its timestamp is the time it was queued", ts >= before && ts <= System.nanoTime(), ts + " vs " + before);

		producer.frame(0, 0, 1, 0);
		producer.frame(1, 1, 0, 0);
		frames.tryAcquire(2, 5, TimeUnit.SECONDS);
		st.updateTexImage();
		int first = sample(t[0], m, 32, 16);
		st.updateTexImage();
		int second = sample(t[0], m, 32, 16);
		check("frames are latched in order (blue, then yellow)", first == BLUE && second == YELLOW, hex(first) + " " + hex(second));
		st.updateTexImage();
		check("with no new frame the current one stays", sample(t[0], m, 32, 16) == YELLOW, "");
		producer.splitFrame();
		frames.tryAcquire(5, TimeUnit.SECONDS);
		st.updateTexImage();
		st.getTransformMatrix(m);
		int top = sample(t[0], m, 32, 24), bottom = sample(t[0], m, 32, 8);
		check("with the matrix the image is upright (top blue, bottom red)", top == BLUE && bottom == RED, hex(top) + " " + hex(bottom));
		producer.frame(1, 1, 0, 0);
		frames.tryAcquire(5, TimeUnit.SECONDS);
		st.updateTexImage();

		/* updateTexImage in another context: AOSP refuses it */
		EGLContext other = newContext();
		EGLSurface pb2 = pbuffer();
		EGL14.eglMakeCurrent(dpy, pb2, pb2, other);
		boolean refused = false;
		try {
			st.updateTexImage();
		} catch (IllegalStateException e) {
			refused = true;
		}
		check("updateTexImage in another GL context throws IllegalStateException", refused, "");
		EGL14.eglMakeCurrent(dpy, pb, pb, consumer);

		/* detach and attach */
		st.detachFromGLContext();
		refused = false;
		try {
			st.updateTexImage();
		} catch (IllegalStateException e) {
			refused = true;
		}
		check("after detachFromGLContext updateTexImage throws (not attached)", refused, "");
		st.attachToGLContext(t[1]);
		check("attachToGLContext: the current frame in the new texture", sample(t[1], m, 32, 16) == YELLOW, hex(sample(t[1], m, 32, 16)));
		refused = false;
		try {
			st.attachToGLContext(t[1]);
		} catch (RuntimeException e) {
			refused = true;
		}
		check("attaching an attached SurfaceTexture throws", refused, "");

		/* the producer goes, another connects through Surface(SurfaceTexture) */
		producer.disconnect();
		Surface surface = new Surface(st);
		check("Surface(SurfaceTexture) is valid", surface.isValid(), "");
		EGLSurface ws2 = producer.connect(surface);
		check("after eglDestroySurface a producer connects through Surface(SurfaceTexture)", !noSurface(ws2), "");
		producer.frame(0, 1, 0, 0);
		frames.tryAcquire(5, TimeUnit.SECONDS);
		st.updateTexImage();
		check("and its frame arrives (green)", sample(t[1], m, 32, 16) == GREEN, hex(sample(t[1], m, 32, 16)));

		st.releaseTexImage();
		c = sample(t[1], m, 32, 16);
		check("releaseTexImage: the texture shows black", c == 0x00000000 || c == 0x000000ff, hex(c));

		st.release();
		check("release: isReleased", st.isReleased(), "");
		check("and the producer's next swap fails", !producer.frame(1, 0, 0, 0), "");
		producer.disconnect();
		surface.release();
		check("a released Surface is invalid", !surface.isValid(), "");
	}

	/* ---- EGL_ANDROID_presentation_time on a SurfaceView ---- */

	static void presentationTests(Surface viewSurface) throws Exception {
		Producer producer = new Producer();
		EGLSurface s = producer.connect(viewSurface);
		check("a window surface on the SurfaceView", !noSurface(s), "");
		producer.frame(0, 0, 1, 0);
		Thread.sleep(300);
		long due = System.nanoTime() + 400_000_000L;
		log("present-held due=" + due + " queued=" + System.nanoTime());
		producer.frame(1, 1, 0, due);
		Thread.sleep(800);
		long due2 = System.nanoTime() + 150_000_000L;
		log("present-drop due=" + due2);
		producer.frame(1, 0, 0, due2);
		producer.frame(0, 1, 0, due2 + 1);
		Thread.sleep(600);
		producer.disconnect();
	}

	SurfaceView view;

	@Override
	protected void onCreate(Bundle b) {
		super.onCreate(b);
		view = new SurfaceView(this);
		view.getHolder().addCallback(this);
		setContentView(view);
	}

	@Override
	public void surfaceCreated(final SurfaceHolder holder) {
		new Thread(new Runnable() {
			public void run() {
				try {
					surfaceTextureTests();
					presentationTests(holder.getSurface());
				} catch (Throwable t) {
					t.printStackTrace();
					check("tests ran to the end", false, t.toString());
				}
				System.out.println("GLTEST RESULT " + passed + "/" + total);
				System.out.flush();
				System.exit(passed == total ? 0 : 1);
			}
		}, "gl-test").start();
	}

	@Override
	public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}

	@Override
	public void surfaceDestroyed(SurfaceHolder holder) {}
}
