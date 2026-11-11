/*
 * Copyright 2018 The Android Open Source Project
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

package android.webkit;

import android.os.Process;
import android.os.SystemClock;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import org.json.JSONObject;

/**
 * Manages tracing of WebViews. In particular provides functionality for the app
 * to enable/disable tracing of parts of code and to collect tracing data.
 * This is useful for profiling performance issues, debugging and memory usage
 * analysis in production and real life scenarios.
 * <p>
 * The resulting trace data is sent back as a byte sequence in json format. This
 * file can be loaded in "chrome://tracing" for further analysis.
 */
public abstract class TracingController {
	/**
	 * Returns the default TracingController instance. At present there is
	 * only one TracingController instance for all WebView instances,
	 * however this restriction may be relaxed in a future Android release.
	 */
	public static TracingController getInstance() {
		return Recorder.INSTANCE;
	}

	/**
	 * Starts tracing all webviews. Depending on the trace mode in traceConfig
	 * specifies how the trace events are recorded.
	 *
	 * @throws IllegalStateException If the system is already tracing.
	 * @throws IllegalArgumentException If the configuration is invalid (e.g.
	 *         invalid category pattern or invalid tracing mode).
	 */
	public abstract void start(TracingConfig tracingConfig);

	/**
	 * Stops tracing and flushes tracing data to the specified outputStream.
	 *
	 * The data is sent to the specified output stream in json format typically
	 * in chunks by invoking {@link java.io.OutputStream#write(byte[])}. On completion
	 * the {@link java.io.OutputStream#close()} method is called.
	 *
	 * @return {@code false} if the WebView framework was not tracing at the time of the call,
	 * {@code true} otherwise.
	 */
	public abstract boolean stop(OutputStream outputStream, Executor executor);

	/**
	 * Returns whether the WebView framework is tracing.
	 */
	public abstract boolean isTracing();

	/**
	 * teslalinux: the WebView trace recorder. The engine (WebKitGTK) exposes no tracing to the
	 * embedder, so the trace holds the events the WebView layer itself sees, in Chromium's JSON trace
	 * event format: navigations (category "navigation"), script evaluation and JavaScript interface
	 * calls ("android_webview"), renderer process events ("android_webview"). Predefined category sets
	 * select the categories Chromium puts in them; custom patterns may end in '*'.
	 */
	static final class Recorder extends TracingController {
		static final Recorder INSTANCE = new Recorder();
		private static final int BUFFER_EVENTS = 50000;

		private volatile boolean mTracing;
		private TracingConfig mConfig;
		private final ArrayDeque<JSONObject> mEvents = new ArrayDeque<JSONObject>();
		private boolean mFull;

		@Override
		public synchronized void start(TracingConfig tracingConfig) {
			if (tracingConfig == null)
				throw new IllegalArgumentException("tracingConfig cannot be null");
			if (mTracing)
				throw new IllegalStateException("cannot start tracing: tracing is already enabled");
			int mode = tracingConfig.getTracingMode();
			if (mode != TracingConfig.RECORD_UNTIL_FULL && mode != TracingConfig.RECORD_CONTINUOUSLY)
				throw new IllegalArgumentException("invalid tracing mode");
			for (String pattern : tracingConfig.getCustomIncludedCategories()) {
				if (pattern == null || pattern.isEmpty() || pattern.indexOf('*') >= 0 && pattern.indexOf('*') != pattern.length() - 1 || pattern.indexOf(',') >= 0)
					throw new IllegalArgumentException("invalid category pattern: " + pattern);
			}
			mConfig = tracingConfig;
			mEvents.clear();
			mFull = false;
			mTracing = true;
		}

		@Override
		public boolean stop(final OutputStream outputStream, Executor executor) {
			final List<JSONObject> events;
			synchronized (this) {
				if (!mTracing)
					return false;
				mTracing = false;
				events = new ArrayList<JSONObject>(mEvents);
				mEvents.clear();
			}
			if (outputStream == null)
				return true;
			Runnable writer = new Runnable() {
				public void run() {
					try {
						outputStream.write("{\"traceEvents\":[".getBytes(StandardCharsets.UTF_8));
						for (int i = 0; i < events.size(); i++) {
							String e = (i > 0 ? "," : "") + events.get(i).toString();
							outputStream.write(e.getBytes(StandardCharsets.UTF_8));
						}
						outputStream.write("]}".getBytes(StandardCharsets.UTF_8));
					} catch (IOException e) {
						// the app's stream failed: nothing more can be delivered
					} finally {
						try {
							outputStream.close();
						} catch (IOException e) {
						}
					}
				}
			};
			if (executor != null)
				executor.execute(writer);
			else
				writer.run();
			return true;
		}

		@Override
		public boolean isTracing() {
			return mTracing;
		}

		private boolean enabled(String category) {
			TracingConfig c = mConfig;
			int sets = c.getPredefinedCategories();
			if ((sets & TracingConfig.CATEGORIES_ALL) != 0)
				return true;
			boolean webview = category.equals("android_webview");
			boolean blink = category.equals("navigation") || category.equals("blink");
			if ((sets & TracingConfig.CATEGORIES_ANDROID_WEBVIEW) != 0 && webview)
				return true;
			if ((sets & (TracingConfig.CATEGORIES_WEB_DEVELOPER | TracingConfig.CATEGORIES_RENDERING | TracingConfig.CATEGORIES_JAVASCRIPT_AND_RENDERING | TracingConfig.CATEGORIES_FRAME_VIEWER)) != 0 && blink)
				return true;
			for (String p : c.getCustomIncludedCategories()) {
				if (p.endsWith("*") ? category.startsWith(p.substring(0, p.length() - 1)) : category.equals(p))
					return true;
			}
			return false;
		}

		/** records an instant event (ph "i") or, with durUs >= 0, a complete event (ph "X") */
		void record(String category, String name, long startUs, long durUs, Map<String, Object> args) {
			if (!mTracing)
				return;
			synchronized (this) {
				if (!mTracing || !enabled(category))
					return;
				try {
					JSONObject e = new JSONObject();
					e.put("name", name);
					e.put("cat", category);
					e.put("ph", durUs >= 0 ? "X" : "i");
					e.put("ts", startUs);
					if (durUs >= 0)
						e.put("dur", durUs);
					else
						e.put("s", "t");
					e.put("pid", Process.myPid());
					e.put("tid", Process.myTid());
					if (args != null)
						e.put("args", new JSONObject(args));
					if (mEvents.size() >= BUFFER_EVENTS) {
						if (mConfig.getTracingMode() == TracingConfig.RECORD_UNTIL_FULL) {
							mFull = true;
							return;
						}
						mEvents.removeFirst();
					}
					mEvents.addLast(e);
				} catch (org.json.JSONException e) {
				}
			}
		}

		static long nowUs() {
			return SystemClock.elapsedRealtimeNanos() / 1000;
		}
	}
}
