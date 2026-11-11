/*
 * Copyright (C) 2006 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.gles_jni;

import android.graphics.SurfaceTexture;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.util.HashMap;
import java.util.Map;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.egl.EGLSurface;

/**
 * The javax.microedition.khronos.egl.EGL10 implementation (AOSP opengl/java/com/google/android/gles_jni/EGLImpl.java,
 * semantics of core/jni/com_google_android_gles_jni_EGLImpl.cpp). Handle objects carry the native EGL handle in a
 * long; the natives take those longs (ATL convention) instead of the handle objects AOSP's JNI unpacks itself.
 *
 * Platform notes (where the device behaviour differs by construction, not by omission):
 *  - The EGLDisplay handed out is the one GTK renders the window with (libandroid/egl.c), so eglTerminate() keeps
 *    AOSP's reference-counted contract towards the app (the count eglInitialize() builds up) but never tears the
 *    display down underneath the toolkit.
 *  - There are no native pixmaps on this platform. eglCreatePixmapSurface() throws UnsupportedOperationException
 *    exactly like AOSP's JNI does, and eglCopyBuffers() returns EGL_FALSE as AOSP's JNI does.
 *  - ATL_DEBUG_EGL=1 traces every entry point to logcat.
 */
public class EGLImpl implements EGL10 {
	private static final String TAG = "EGLImpl";
	private static final boolean DEBUG = System.getenv("ATL_DEBUG_EGL") != null;

	private EGLContext mContext = new EGLContext(-1);
	private EGLDisplay mDisplay = new EGLDisplay(-1);
	private EGLSurfaceImpl mSurface = new EGLSurfaceImpl(-1);

	/** eglInitialize/eglTerminate reference counts per display (AOSP egl_display_t::initialize/terminate). */
	private static final Map<Long, Integer> sInitCounts = new HashMap<>();

	public EGLImpl() {
	}

	private static void trace(String what) {
		if (DEBUG)
			android.util.Log.i(TAG, "[" + Thread.currentThread().getName() + "] " + what);
	}

	private static long handle(EGLDisplay display) {
		if (display == null)
			throw new IllegalArgumentException("display == null");
		return display.native_egl_display;
	}

	private static long handle(EGLContext context) {
		if (context == null)
			throw new IllegalArgumentException("context == null");
		return context.native_egl_context;
	}

	private static long handle(EGLSurface surface) {
		if (surface == null)
			throw new IllegalArgumentException("surface == null");
		if (!(surface instanceof EGLSurfaceImpl))
			throw new IllegalArgumentException("surface is not an EGLSurfaceImpl: " + surface.getClass().getName());
		return ((EGLSurfaceImpl) surface).mEGLSurface;
	}

	private static long handle(EGLConfig config) {
		if (config == null)
			throw new IllegalArgumentException("config == null");
		return config.native_egl_config;
	}

	// ---- initialisation -------------------------------------------------------------------------------------

	public boolean eglInitialize(EGLDisplay display, int[] major_minor) {
		long dpy = handle(display);
		int[] mm = (major_minor != null && major_minor.length >= 2) ? major_minor : new int[2];
		boolean ok = native_eglInitialize(dpy, mm);
		if (ok) {
			synchronized (sInitCounts) {
				Integer n = sInitCounts.get(dpy);
				sInitCounts.put(dpy, n == null ? 1 : n + 1);
			}
		}
		trace("eglInitialize(" + Long.toHexString(dpy) + ") = " + ok + " version " + mm[0] + "." + mm[1]);
		return ok;
	}

	public boolean eglTerminate(EGLDisplay display) {
		long dpy = handle(display);
		synchronized (sInitCounts) {
			Integer n = sInitCounts.get(dpy);
			if (n != null && n > 0)
				sInitCounts.put(dpy, n - 1);
		}
		trace("eglTerminate(" + Long.toHexString(dpy) + ")");
		// the display is shared with the toolkit's renderer: the app's view of it is released, the EGL display lives on
		return dpy != 0;
	}

	/** @hide */
	public static int getInitCount(EGLDisplay display) {
		long dpy = handle(display);
		synchronized (sInitCounts) {
			Integer n = sInitCounts.get(dpy);
			return n == null ? 0 : n;
		}
	}

	public synchronized EGLDisplay eglGetDisplay(Object native_display) {
		long value = native_eglGetDisplay(native_display);
		trace("eglGetDisplay(" + native_display + ") = " + Long.toHexString(value));
		if (value == 0) {
			return EGL10.EGL_NO_DISPLAY;
		}
		if (mDisplay.native_egl_display != value)
			mDisplay = new EGLDisplay(value);
		return mDisplay;
	}

	public String eglQueryString(EGLDisplay display, int name) {
		String s = native_eglQueryString(handle(display), name);
		trace("eglQueryString(" + name + ") = " + s);
		return s;
	}

	public int eglGetError() {
		return native_eglGetError();
	}

	// ---- configs ----------------------------------------------------------------------------------------------

	public boolean eglChooseConfig(EGLDisplay display, int[] attrib_list, EGLConfig[] configs, int config_size, int[] num_config) {
		long dpy = handle(display);
		if (num_config == null || num_config.length < 1)
			throw new IllegalArgumentException("num_config must hold at least one element");
		long[] native_configs = null;
		if (config_size != 0) {
			if (configs == null)
				throw new IllegalArgumentException("app error: eglChooseConfig called with non-zero 'config_size' (" + config_size + "), but with null 'configs' array");
			if (configs.length < config_size)
				throw new IllegalArgumentException("configs.length < config_size");
			native_configs = new long[config_size];
		}
		boolean ok = native_eglChooseConfig(dpy, attrib_list, native_configs, config_size, num_config);
		if (ok && configs != null) {
			int n = Math.min(num_config[0], config_size);
			for (int i = 0; i < n; i++)
				configs[i] = new EGLConfig(native_configs[i]);
		}
		trace("eglChooseConfig(config_size=" + config_size + ") = " + ok + " num_config=" + num_config[0]);
		return ok;
	}

	public boolean eglGetConfigs(EGLDisplay display, EGLConfig[] configs, int config_size, int[] num_config) {
		long dpy = handle(display);
		if (num_config == null || num_config.length < 1)
			throw new IllegalArgumentException("num_config must hold at least one element");
		long[] native_configs = null;
		if (configs != null) {
			if (configs.length < config_size)
				throw new IllegalArgumentException("configs.length < config_size");
			native_configs = new long[Math.max(config_size, 0)];
		}
		boolean ok = native_eglGetConfigs(dpy, native_configs, config_size, num_config);
		if (ok && configs != null) {
			int n = Math.min(num_config[0], config_size);
			for (int i = 0; i < n; i++)
				configs[i] = new EGLConfig(native_configs[i]);
		}
		trace("eglGetConfigs(config_size=" + config_size + ") = " + ok + " num_config=" + num_config[0]);
		return ok;
	}

	public boolean eglGetConfigAttrib(EGLDisplay display, EGLConfig config, int attribute, int[] value) {
		if (value == null || value.length < 1)
			throw new IllegalArgumentException("value must hold at least one element");
		return native_eglGetConfigAttrib(handle(display), handle(config), attribute, value);
	}

	// ---- contexts ---------------------------------------------------------------------------------------------

	public EGLContext eglCreateContext(EGLDisplay display, EGLConfig config, EGLContext share_context, int[] attrib_list) {
		long dpy = handle(display);
		long cfg = handle(config);
		long share = (share_context != null) ? share_context.native_egl_context : 0;
		long ctx = native_eglCreateContext(dpy, cfg, share, attrib_list);
		trace("eglCreateContext(share=" + Long.toHexString(share) + ") = " + Long.toHexString(ctx) + (ctx == 0 ? " error " + eglGetError() : ""));
		if (ctx == 0) {
			return EGL10.EGL_NO_CONTEXT;
		}
		return new EGLContext(ctx);
	}

	public boolean eglDestroyContext(EGLDisplay display, EGLContext context) {
		boolean ok = native_eglDestroyContext(handle(display), handle(context));
		trace("eglDestroyContext(" + Long.toHexString(context.native_egl_context) + ") = " + ok);
		return ok;
	}

	public boolean eglQueryContext(EGLDisplay display, EGLContext context, int attribute, int[] value) {
		if (value == null || value.length < 1)
			throw new IllegalArgumentException("value must hold at least one element");
		return native_eglQueryContext(handle(display), handle(context), attribute, value);
	}

	public boolean eglMakeCurrent(EGLDisplay display, EGLSurface draw, EGLSurface read, EGLContext context) {
		long dpy = handle(display);
		long d = handle(draw), r = handle(read), c = handle(context);
		boolean ok = native_eglMakeCurrent(dpy, d, r, c);
		trace("eglMakeCurrent(draw=" + Long.toHexString(d) + " read=" + Long.toHexString(r) + " ctx=" + Long.toHexString(c) + ") = " + ok + (ok ? "" : " error " + eglGetError()));
		return ok;
	}

	public synchronized EGLContext eglGetCurrentContext() {
		long value = native_eglGetCurrentContext();
		if (value == 0) {
			return EGL10.EGL_NO_CONTEXT;
		}
		if (mContext.native_egl_context != value)
			mContext = new EGLContext(value);
		return mContext;
	}

	public synchronized EGLDisplay eglGetCurrentDisplay() {
		long value = native_eglGetCurrentDisplay();
		if (value == 0) {
			return EGL10.EGL_NO_DISPLAY;
		}
		if (mDisplay.native_egl_display != value)
			mDisplay = new EGLDisplay(value);
		return mDisplay;
	}

	public synchronized EGLSurface eglGetCurrentSurface(int readdraw) {
		if (readdraw != EGL_READ && readdraw != EGL_DRAW)
			throw new IllegalArgumentException("readdraw must be EGL_READ or EGL_DRAW");
		long value = native_eglGetCurrentSurface(readdraw);
		if (value == 0) {
			return EGL10.EGL_NO_SURFACE;
		}
		if (mSurface.mEGLSurface != value)
			mSurface = new EGLSurfaceImpl(value);
		return mSurface;
	}

	/** @hide */
	public boolean eglReleaseThread() {
		boolean ok = native_eglReleaseThread();
		trace("eglReleaseThread() = " + ok);
		return ok;
	}

	// ---- surfaces ---------------------------------------------------------------------------------------------

	public EGLSurface eglCreatePbufferSurface(EGLDisplay display, EGLConfig config, int[] attrib_list) {
		long sur = native_eglCreatePbufferSurface(handle(display), handle(config), attrib_list);
		trace("eglCreatePbufferSurface() = " + Long.toHexString(sur) + (sur == 0 ? " error " + eglGetError() : ""));
		if (sur == 0) {
			return EGL10.EGL_NO_SURFACE;
		}
		return new EGLSurfaceImpl(sur);
	}

	public EGLSurface eglCreatePixmapSurface(EGLDisplay display, EGLConfig config, Object native_pixmap, int[] attrib_list) {
		handle(display);
		handle(config);
		// AOSP's JNI (jni_eglCreatePixmapSurface) throws this: Android has no EGL native pixmaps, and neither has this platform
		throw new UnsupportedOperationException("eglCreatePixmapSurface");
	}

	public EGLSurface eglCreateWindowSurface(EGLDisplay display, EGLConfig config, Object native_window, int[] attrib_list) {
		long dpy = handle(display);
		long cfg = handle(config);
		Surface sur = null;
		if (native_window instanceof SurfaceView) {
			SurfaceView surfaceView = (SurfaceView) native_window;
			sur = surfaceView.getHolder().getSurface();
		} else if (native_window instanceof SurfaceHolder) {
			SurfaceHolder holder = (SurfaceHolder) native_window;
			sur = holder.getSurface();
		} else if (native_window instanceof Surface) {
			sur = (Surface) native_window;
		}

		long eglSurfaceId;
		if (sur != null) {
			eglSurfaceId = native_eglCreateWindowSurface(dpy, cfg, sur, attrib_list);
		} else if (native_window instanceof SurfaceTexture) {
			// AOSP takes the SurfaceTexture's buffer producer (SurfaceTexture_getProducer) and fails this way
			// when there is none. ATL's SurfaceTexture has no producer side yet (see TODO-real.md).
			throw new IllegalArgumentException("Make sure the SurfaceTexture is valid");
		} else {
			throw new java.lang.UnsupportedOperationException(
			    "eglCreateWindowSurface() can only be called with an instance of "
			    + "Surface, SurfaceView, SurfaceHolder or SurfaceTexture at the moment.");
		}
		trace("eglCreateWindowSurface(" + native_window + ") = " + Long.toHexString(eglSurfaceId) + (eglSurfaceId == 0 ? " error " + eglGetError() : ""));

		if (eglSurfaceId == 0) {
			return EGL10.EGL_NO_SURFACE;
		}
		return new EGLSurfaceImpl(eglSurfaceId);
	}

	public boolean eglDestroySurface(EGLDisplay display, EGLSurface surface) {
		long sur = handle(surface);
		boolean ok = native_eglDestroySurface(handle(display), sur);
		trace("eglDestroySurface(" + Long.toHexString(sur) + ") = " + ok);
		return ok;
	}

	public boolean eglQuerySurface(EGLDisplay display, EGLSurface surface, int attribute, int[] value) {
		if (value == null || value.length < 1)
			throw new IllegalArgumentException("value must hold at least one element");
		return native_eglQuerySurface(handle(display), handle(surface), attribute, value);
	}

	public boolean eglSwapBuffers(EGLDisplay display, EGLSurface surface) {
		return native_eglSwapBuffers(handle(display), handle(surface));
	}

	public boolean eglCopyBuffers(EGLDisplay display, EGLSurface surface, Object native_pixmap) {
		handle(display);
		handle(surface);
		if (native_pixmap == null)
			throw new IllegalArgumentException("native_pixmap == null");
		// AOSP's JNI returns EGL_FALSE here (no native pixmap type to copy into); same situation on this platform
		return false;
	}

	// ---- synchronisation ---------------------------------------------------------------------------------------

	public boolean eglWaitGL() {
		return native_eglWaitGL();
	}

	public boolean eglWaitNative(int engine, Object bindTarget) {
		// AOSP ignores bindTarget: EGL_CORE_NATIVE_ENGINE is the only engine and it needs no binding object
		return native_eglWaitNative(engine);
	}

	// ---- natives (com_google_android_gles_jni_EGLImpl.c) --------------------------------------------------------

	private native long native_eglCreateContext(long egl_display, long egl_config, long share_context, int[] attrib_list);
	private native boolean native_eglChooseConfig(long egl_display, int[] attrib_list, long[] egl_configs, int config_size, int[] num_config);
	private native boolean native_eglGetConfigs(long egl_display, long[] egl_configs, int config_size, int[] num_config);
	private native long native_eglCreateWindowSurface(long native_egl_display, long native_egl_config, Surface surface, int[] attrib_list);
	private native long native_eglGetDisplay(Object native_display);
	private native boolean native_eglInitialize(long native_egl_display, int[] major_minor);
	private native boolean native_eglGetConfigAttrib(long native_egl_display, long native_egl_config, int attribute, int[] value);
	private native boolean native_eglMakeCurrent(long native_egl_display, long native_draw_surface, long native_read_surface, long native_context);
	private native boolean native_eglSwapBuffers(long native_egl_display, long native_surface);
	private native boolean native_eglDestroySurface(long native_egl_display, long native_surface);
	private native boolean native_eglDestroyContext(long native_egl_display, long native_context);
	private native long native_eglCreatePbufferSurface(long native_egl_display, long native_egl_config, int[] attrib_list);
	private native long native_eglGetCurrentContext();
	private native long native_eglGetCurrentDisplay();
	private native long native_eglGetCurrentSurface(int readdraw);
	private native int native_eglGetError();
	private native String native_eglQueryString(long native_egl_display, int name);
	private native boolean native_eglQueryContext(long native_egl_display, long native_context, int attribute, int[] value);
	private native boolean native_eglQuerySurface(long native_egl_display, long native_surface, int attribute, int[] value);
	private native boolean native_eglReleaseThread();
	private native boolean native_eglWaitGL();
	private native boolean native_eglWaitNative(int engine);
}
