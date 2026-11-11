package android.atl;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.io.File;

/**
 * teslalinux debugging aid: ATL_DEBUG_DUMPVIEWS=1 makes every resumed Activity poll (once a second)
 * for the trigger file /tmp/atl-dumpviews; when it appears the file is removed and the window's view
 * hierarchy is written to the log (tag ViewDump), one line per view: class, id, visibility, alpha,
 * position and size, attachment state and, for text views, the text. tap.sh dump creates the trigger.
 */
public final class ViewDump {
	private static final String TAG = "ViewDump";
	private static final File TRIGGER = new File("/tmp/atl-dumpviews");
	private static final boolean ENABLED = System.getenv("ATL_DEBUG_DUMPVIEWS") != null;

	private ViewDump() {
	}

	private static Thread sStackWatcher;

	/** Starts the trigger poller for an activity (no-op unless ATL_DEBUG_DUMPVIEWS is set). */
	/* every activity that installed a poller; a plain dump walks all live ones (the trigger is
	 * consumed by whichever poller sees it first, which used to dump only that activity) */
	private static final java.util.Set<Activity> sActivities =
		java.util.Collections.newSetFromMap(new java.util.WeakHashMap<Activity, Boolean>());

	public static void installPoller(final Activity activity) {
		if (!ENABLED)
			return;
		synchronized (sActivities) {
			sActivities.add(activity);
		}
		installStackWatcher();
		installMainStackDumper();
		final Handler handler = new Handler(Looper.getMainLooper());
		handler.postDelayed(new Runnable() {
			@Override
			public void run() {
				if (activity.isDestroyed())
					return;
				if (TRIGGER.exists()) {
					String command = readTrigger();
					// "@Name command": only an activity whose class name contains Name answers (every activity polls)
					if (command.startsWith("@")) {
						int sp = command.indexOf(' ');
						String want = sp > 0 ? command.substring(1, sp) : command.substring(1);
						if (!activity.getClass().getName().contains(want)) {
							handler.postDelayed(this, 200);
							return;
						}
						command = sp > 0 ? command.substring(sp + 1) : "";
					}
					TRIGGER.delete();
					View decor = activity.getWindow() != null ? activity.getWindow().getDecorView() : null;
					if (command.startsWith("config")) {
						// "config": the Configuration and DisplayMetrics the app sees
						android.content.res.Resources res = activity.getResources();
						Log.i(TAG, "config " + res.getConfiguration());
						Log.i(TAG, "metrics " + res.getDisplayMetrics());
						Log.i(TAG, "locale default=" + java.util.Locale.getDefault() + " user.locale=" + System.getProperty("user.locale")
						           + " user.language=" + System.getProperty("user.language") + " user.region=" + System.getProperty("user.region")
						           + " LocaleList=" + android.os.LocaleList.getDefault() + " tz=" + java.util.TimeZone.getDefault().getID()
						           + " sys_config=" + android.content.Context.sys_config);
						android.view.Display disp = activity.getWindowManager().getDefaultDisplay();
						android.graphics.Point p = new android.graphics.Point();
						disp.getSize(p);
						Log.i(TAG, "display size " + p.x + "x" + p.y + " rotation=" + disp.getRotation());
					} else if (command.startsWith("windows")) {
						// "windows": every live activity's window and decor (attach state, root insets, visible rect)
						synchronized (sActivities) {
							for (Activity a : sActivities) {
								android.view.Window w = a.getWindow();
								View d = w != null ? w.getDecorView() : null;
								android.graphics.Rect r = new android.graphics.Rect();
								boolean vis = d != null && d.getGlobalVisibleRect(r);
								String life = "-";
								try {
									Object lc = a.getClass().getMethod("getLifecycle").invoke(a);
									life = String.valueOf(lc.getClass().getMethod("getCurrentState").invoke(lc));
								} catch (Throwable t) {
									// not a LifecycleOwner
								}
								Log.i(TAG, "windows: " + a.getClass().getName() + " lifecycle=" + life + " destroyed=" + a.isDestroyed() + " finishing=" + a.isFinishing()
								           + " window=" + w + " decor=" + (d != null ? d.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(d)) : null)
								           + " attached=" + (d != null && d.isAttachedToWindow()) + " rootInsets=" + (d != null ? d.getRootWindowInsets() : null)
								           + " visible=" + vis + " " + r + " size=" + (d != null ? d.getWidth() + "x" + d.getHeight() : "-"));
							}
						}
					} else if (command.startsWith("vecprobe")) {
						// "vecprobe": Compose's vector cache sequence on a bitmap canvas, pixels read back
						vecProbe();
					} else if (command.startsWith("textprobe")) {
						// "textprobe [size]": log what the text measurement API reports for a few strings
						String[] parts = command.trim().split(" ");
						float size = parts.length > 1 ? Float.parseFloat(parts[1]) : 44f;
						textProbe(size);
					} else if (command.startsWith("fragments")) {
						// "fragments": the activity's support FragmentManager dump (states, child managers, back stack)
						try {
							Object fm = activity.getClass().getMethod("getSupportFragmentManager").invoke(activity);
							java.io.StringWriter sw = new java.io.StringWriter();
							fm.getClass().getMethod("dump", String.class, java.io.FileDescriptor.class, java.io.PrintWriter.class, String[].class)
							  .invoke(fm, "", null, new java.io.PrintWriter(sw), null);
							for (String l : sw.toString().split("\n"))
								Log.i(TAG, "fragments: " + l);
						} catch (Exception e) {
							Log.i(TAG, "fragments: " + e);
						}
					} else if (command.startsWith("choreo")) {
						// "choreo": does a Choreographer frame callback fire? plus the scheduler's internal state
						try {
							final android.view.Choreographer ch = android.view.Choreographer.getInstance();
							StringBuilder sb = new StringBuilder("choreo: ");
							for (String fn : new String[] { "mFrameScheduled", "mCallbacksRunning", "mLastFrameTimeNanos", "mFrameIntervalNanos", "USE_VSYNC", "mVsyncScheduled" }) {
								try { java.lang.reflect.Field f = android.view.Choreographer.class.getDeclaredField(fn); f.setAccessible(true); sb.append(fn).append('=').append(f.get(ch)).append(' '); } catch (Throwable ignored) { }
							}
							Log.i(TAG, sb.toString());
							final long t0 = android.os.SystemClock.uptimeMillis();
							ch.postFrameCallback(new android.view.Choreographer.FrameCallback() {
								@Override public void doFrame(long frameTimeNanos) {
									Log.i(TAG, "choreo: frame callback fired after " + (android.os.SystemClock.uptimeMillis() - t0) + " ms");
								}
							});
							new Handler(Looper.getMainLooper()).postDelayed(new Runnable() { @Override public void run() { Log.i(TAG, "choreo: handler post fired after " + (android.os.SystemClock.uptimeMillis() - t0) + " ms"); } }, 1);
						} catch (Throwable e) {
							Log.i(TAG, "choreo: " + e);
						}
					} else if (command.startsWith("composestate")) {
						// "composestate": lifecycle owner state of each compose owner view and the running recomposers
						try {
							java.util.List<View> owners = new java.util.ArrayList<>();
							collectComposeViews(decor, owners);
							for (View o : owners) {
								StringBuilder sb = new StringBuilder("composestate: owner ").append(o.getWidth()).append('x').append(o.getHeight());
								try {
									ClassLoader cl = o.getClass().getClassLoader();
									Class<?> vtlo = Class.forName("androidx.lifecycle.ViewTreeLifecycleOwner", true, cl);
									Object lo = vtlo.getMethod("get", View.class).invoke(null, o);
									sb.append(" lifecycleOwner=").append(lo == null ? "null" : lo.getClass().getName());
									if (lo != null) {
										Object lc = lo.getClass().getMethod("getLifecycle").invoke(lo);
										sb.append(" state=").append(lc.getClass().getMethod("getCurrentState").invoke(lc));
									}
								} catch (Throwable t) { sb.append(" lifecycle? ").append(t); }
								try {
									// the window recomposer this owner composes under (AOSP: found on the window's root view)
									Class<?> wr = Class.forName("androidx.compose.ui.platform.WindowRecomposer_androidKt", true, o.getClass().getClassLoader());
									Object ctx = wr.getMethod("findViewTreeCompositionContext", View.class).invoke(null, o);
									sb.append(" recomposer=").append(ctx == null ? "null" : ctx.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(ctx)));
									if (ctx != null) {
										for (java.lang.reflect.Method m : ctx.getClass().getMethods()) {
											if (m.getName().equals("getHasPendingWork") || m.getName().equals("getChangeCount"))
												sb.append(' ').append(m.getName()).append('=').append(m.invoke(ctx));
											if (m.getName().equals("getState") || m.getName().equals("getCurrentState")) {
												Object st = m.invoke(ctx);
												Object val = st;
												// the app shades kotlinx.coroutines: find the StateFlow.value getter by its result type
												Class<?> stateCls = Class.forName("androidx.compose.runtime.Recomposer$State", true, ctx.getClass().getClassLoader());
												for (java.lang.reflect.Method vm : st.getClass().getMethods()) {
													if (vm.getParameterCount() != 0 || vm.getReturnType() == void.class || vm.getDeclaringClass() == Object.class) continue;
													try { Object r = vm.invoke(st); if (stateCls.isInstance(r)) { val = r; break; } } catch (Throwable ignored) { }
												}
												sb.append(' ').append(m.getName()).append('=').append(val);
											}
										}
									}
									View root = o.getRootView();
									sb.append(" rootView=").append(root == null ? "null" : root.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(root)));
									int depth = 0; android.view.ViewParent pp = o.getParent();
									while (pp != null) { depth++; pp = pp.getParent(); }
									sb.append(" parentDepth=").append(depth).append(" decor=").append(decor == null ? "null" : decor.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(decor)));
								} catch (Throwable t) { sb.append(" recomposer? ").append(t); }
								try {
									Object cc = o.getClass().getMethod("getCompositionContext").invoke(o);
									sb.append(" compositionContext=").append(cc == null ? "null" : cc.getClass().getName());
									if (cc != null) {
										for (java.lang.reflect.Method m : cc.getClass().getMethods()) {
											if (m.getName().equals("getHasPendingWork") || m.getName().equals("getCurrentState")) {
												sb.append(' ').append(m.getName()).append('=').append(m.invoke(cc));
											}
										}
									}
								} catch (Throwable t) { sb.append(" cc? ").append(t); }
								Log.i(TAG, sb.toString());
							}
							ClassLoader cl = owners.isEmpty() ? ViewDump.class.getClassLoader() : owners.get(0).getClass().getClassLoader();
							try {
								Class<?> snap = Class.forName("androidx.compose.runtime.snapshots.Snapshot", true, cl);
								Object sc = snap.getField("Companion").get(null);
								Object cur = sc.getClass().getMethod("getCurrent").invoke(sc);
								Log.i(TAG, "composestate: main-thread snapshot " + cur.getClass().getName() + " id=" + snap.getMethod("getId").invoke(cur) + " readOnly=" + snap.getMethod("getReadOnly").invoke(cur));
							} catch (Throwable t) { Log.i(TAG, "composestate: snapshot? " + t); }
							Class<?> rc = Class.forName("androidx.compose.runtime.Recomposer", true, cl);
							Object companion = rc.getField("Companion").get(null);
							Object flow = companion.getClass().getMethod("getRunningRecomposers").invoke(companion);
							Object set = Class.forName("kotlinx.coroutines.flow.StateFlow", true, cl).getMethod("getValue").invoke(flow);
							for (Object info : (Iterable<?>) set) {
								StringBuilder sb = new StringBuilder("composestate: recomposer ");
								for (java.lang.reflect.Method m : info.getClass().getMethods()) {
									if (m.getName().equals("getHasPendingWork") || m.getName().equals("getChangeCount")) {
										sb.append(m.getName()).append('=').append(m.invoke(info)).append(' ');
									}
									if (m.getName().equals("getState")) {
										Object sf = m.invoke(info);
										sb.append("state=").append(Class.forName("kotlinx.coroutines.flow.StateFlow", true, cl).getMethod("getValue").invoke(sf)).append(' ');
									}
								}
								Log.i(TAG, sb.toString());
							}
						} catch (Throwable e) {
							Log.i(TAG, "composestate: " + e);
						}
					} else if (command.startsWith("composeapply")) {
						// "composeapply": Snapshot.sendApplyNotifications() by hand (does a pending state change then recompose?)
						try {
							java.util.List<View> owners = new java.util.ArrayList<>();
							collectComposeViews(decor, owners);
							ClassLoader cl = owners.isEmpty() ? ViewDump.class.getClassLoader() : owners.get(0).getClass().getClassLoader();
							Class<?> snap = Class.forName("androidx.compose.runtime.snapshots.Snapshot", true, cl);
							Object companion = snap.getField("Companion").get(null);
							companion.getClass().getMethod("sendApplyNotifications").invoke(companion);
							Log.i(TAG, "composeapply: sent");
						} catch (Exception e) {
							Log.i(TAG, "composeapply: " + e);
						}
					} else if (command.startsWith("focusstate")) {
						// "focusstate": per compose owner, the View focus flags, the FocusOwner's fields and every focus target's state
						composeFocusState(decor);
					} else if (command.startsWith("textfields")) {
						// "textfields": every Compose text field core node (size, layout result, text) in every compose root
						composeTextFields(decor);
					} else if (command.startsWith("fields ")) {
						// "fields NAME [DEPTH]": every view whose class name is NAME (full or simple): all
						// its declared fields up to android.*, with values; objects one level deeper
						String[] parts = command.trim().split(" ");
						int depth = parts.length > 2 ? Integer.parseInt(parts[2]) : 1;
						java.util.List<View> found = new java.util.ArrayList<View>();
						for (Activity a : snapshotActivities()) {
							View d = a.getWindow() != null ? a.getWindow().getDecorView() : null;
							if (d != null)
								collectByClassName(d, parts[1], found);
						}
						for (View v : found)
							dumpFields("fields", v, depth, new java.util.IdentityHashMap<Object, Boolean>());
						Log.i(TAG, "fields: " + found.size() + " view(s) named " + parts[1]);
					} else if (command.startsWith("layoutprobe ")) {
						// "layoutprobe WIDTH SIZE TEXT": a StaticLayout of TEXT at WIDTH px and SIZE px, as
						// Compose builds one: each line's range, width and left/right
						String[] parts = command.trim().split(" ", 4);
						android.text.TextPaint tp = new android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG);
						tp.setTextSize(Float.parseFloat(parts[2]));
						String probeText = parts[3];
						int probeMaxLines = Integer.MAX_VALUE;
						if (probeText.startsWith("maxlines=")) {
							int sp = probeText.indexOf(' ');
							probeMaxLines = Integer.parseInt(probeText.substring(9, sp));
							probeText = probeText.substring(sp + 1);
						}
						parts[3] = probeText;
						android.text.StaticLayout.Builder pb = android.text.StaticLayout.Builder.obtain(probeText, 0, probeText.length(), tp,
						    Integer.parseInt(parts[1]));
						if (probeMaxLines != Integer.MAX_VALUE)
							pb.setMaxLines(probeMaxLines).setEllipsize(android.text.TextUtils.TruncateAt.END);
						android.text.StaticLayout sl = pb.build();
						Log.i(TAG, "layoutprobe: width=" + sl.getWidth() + " lines=" + sl.getLineCount() + " desired="
						      + android.text.Layout.getDesiredWidth(parts[3], tp) + " measureText=" + tp.measureText(parts[3]));
						for (int i = 0; i < sl.getLineCount(); i++)
							Log.i(TAG, "layoutprobe: line " + i + " [" + sl.getLineStart(i) + "," + sl.getLineEnd(i) + ") width="
							      + sl.getLineWidth(i) + " left=" + sl.getLineLeft(i) + " right=" + sl.getLineRight(i)
							      + " text=\"" + parts[3].substring(sl.getLineStart(i), sl.getLineEnd(i)) + "\"");
					} else if (command.startsWith("tvinfo ")) {
						// "tvinfo TEXT": every TextView showing TEXT: its size, typeface, measured and laid-out
						// widths next to Paint.measureText and the text layout's own width
						String want = command.trim().substring(7);
						for (Activity a : snapshotActivities()) {
							View d = a.getWindow() != null ? a.getWindow().getDecorView() : null;
							if (d != null)
								tvInfo(d, want);
						}
					} else if (command.startsWith("invalidate ")) {
						// "invalidate NAME": View.invalidate() on every view whose class name is NAME (full or
						// simple), as the app would; shows whether a view that stopped redrawing comes back
						String[] parts = command.trim().split(" ");
						java.util.List<View> found = new java.util.ArrayList<View>();
						for (Activity a : snapshotActivities()) {
							View d = a.getWindow() != null ? a.getWindow().getDecorView() : null;
							if (d != null)
								collectByClassName(d, parts[1], found);
						}
						for (View v : found) {
							v.invalidate();
							Log.i(TAG, "invalidate: " + v + " " + v.getWidth() + "x" + v.getHeight() + " shown=" + v.isShown());
						}
					} else if (command.startsWith("objects ")) {
						// "objects ACTIVITY CLASS [DEPTH]": walk the fields of every live activity whose class
						// name contains ACTIVITY (app objects, collections, maps, up to DEPTH levels, default 6)
						// and print every object whose class name contains CLASS with all its fields
						String[] parts = command.trim().split(" ");
						int depth = parts.length > 3 ? Integer.parseInt(parts[3]) : 6;
						int[] found = new int[1];
						for (Activity a : snapshotActivities()) {
							if (a.getClass().getName().contains(parts[1]))
								findObjects(a, parts[2], depth, new java.util.IdentityHashMap<Object, Boolean>(), a.getClass().getSimpleName(), found);
						}
						Log.i(TAG, "objects: " + found[0] + " object(s) of " + parts[2]);
					} else if (command.startsWith("ctree ")) {
						// "ctree X Y W H": every Compose LayoutNode whose bounds meet the window rect: bounds,
						// placed state, measure policy, modifier nodes and any text they hold
						String[] parts = command.trim().split(" ");
						composeTree(decor, Float.parseFloat(parts[1]), Float.parseFloat(parts[2]), Float.parseFloat(parts[3]), Float.parseFloat(parts[4]));
					} else if (command.startsWith("hit ")) {
						// "hit X Y": what Compose's own hit test returns at a window position (per compose root)
						String[] parts = command.trim().split(" ");
						composeHitTest(decor, Float.parseFloat(parts[1]), Float.parseFloat(parts[2]));
					} else if (command.startsWith("setvis ")) {
						// "setvis <id hex> <0|4|8>": change a view's visibility (debugging the stacking of views)
						String[] parts = command.trim().split(" ");
						int id = (int) Long.parseLong(parts[1].replace("0x", ""), 16);
						View target = decor != null ? decor.findViewById(id) : null;
						Log.i(TAG, "setvis " + parts[1] + " -> " + (target == null ? "not found" : target.getClass().getName()));
						if (target != null)
							target.setVisibility(Integer.parseInt(parts[2]));
					} else {
						java.util.List<Activity> all = new java.util.ArrayList<Activity>();
						synchronized (sActivities) {
							all.addAll(sActivities);
						}
						if (!all.contains(activity))
							all.add(activity);
						for (Activity a : all) {
							if (a.isDestroyed())
								continue;
							View d = a.getWindow() != null ? a.getWindow().getDecorView() : null;
							Log.i(TAG, "=== view hierarchy of " + a.getClass().getName() + " ===");
							if (d != null) {
								dump(d, 0);
								nativeDumpGtk(d.widget);
							}
							Log.i(TAG, "=== end ===");
						}
					}
				}
				handler.postDelayed(this, 1000);
			}
		}, 1000);
	}

	/**
	 * What androidx.compose.ui.graphics.vector.DrawCache does for a VectorPainter, step by step on a
	 * 24x24 ARGB bitmap: clear with BlendMode.CLEAR, root scale, a nested group (save + concat of a
	 * translation), an even-odd ring path filled black, restore. Logs the alpha of a few pixels after
	 * each variant: the ring must be opaque, its hole and the corners transparent.
	 */
	private static void vecProbe() {
		String[] names = { "plain", "evenodd", "scale", "scale+concat", "clear+scale+concat", "concat-matrix-values" };
		for (int v = 0; v < names.length; v++) {
			try {
				android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(24, 24, android.graphics.Bitmap.Config.ARGB_8888);
				android.graphics.Canvas c = new android.graphics.Canvas(b);
				android.graphics.Paint clear = new android.graphics.Paint();
				if (v >= 4) {
					clear.setBlendMode(android.graphics.BlendMode.CLEAR);
					c.drawRect(0, 0, 24, 24, clear);
				}
				android.graphics.Path ring = new android.graphics.Path();
				ring.addCircle(12, 12, 8, android.graphics.Path.Direction.CW);
				ring.addCircle(12, 12, 4, android.graphics.Path.Direction.CW);
				if (v >= 1)
					ring.setFillType(android.graphics.Path.FillType.EVEN_ODD);
				android.graphics.Paint black = new android.graphics.Paint();
				black.setColor(0xff000000);
				black.setAntiAlias(true);
				int sc = c.save();
				if (v >= 2)
					c.scale(0.5f, 0.5f);
				if (v >= 3) {
					c.save();
					android.graphics.Matrix m = new android.graphics.Matrix();
					if (v == 5)
						m.setValues(new float[] { 1, 0, 3, 0, 1, 0, 0, 0, 1 });
					else
						m.setTranslate(3, 0);
					c.concat(m);
				}
				c.drawPath(ring, black);
				c.restoreToCount(sc);
				StringBuilder sb = new StringBuilder("vecprobe ").append(names[v]).append(':');
				int[][] pts = { { 0, 0 }, { 23, 23 }, { 12, 12 }, { 5, 12 }, { 4, 6 }, { 7, 6 }, { 2, 6 }, { 6, 2 } };
				for (int[] pt : pts)
					sb.append(' ').append(pt[0]).append(',').append(pt[1]).append('=').append(Integer.toHexString(b.getPixel(pt[0], pt[1]) >>> 24));
				Log.i(TAG, sb.toString());
			} catch (Throwable t) {
				Log.i(TAG, "vecprobe " + names[v] + ": " + t);
			}
		}
	}

	/** Logs Paint/StaticLayout measurements (tag ViewDump) for "H", "Hey Wazer!" and the empty string. */
	private static void textProbe(float size) {
		android.text.TextPaint paint = new android.text.TextPaint();
		paint.setTextSize(size);
		android.graphics.Paint.FontMetricsInt fmi = paint.getFontMetricsInt();
		android.graphics.Paint.FontMetrics fm = paint.getFontMetrics();
		Log.i(TAG, "textprobe size=" + size + " ascent=" + paint.ascent() + " descent=" + paint.descent()
				+ " fmi top/asc/desc/bottom/lead=" + fmi.top + "/" + fmi.ascent + "/" + fmi.descent + "/" + fmi.bottom + "/" + fmi.leading
				+ " fm=" + fm.top + "/" + fm.ascent + "/" + fm.descent + "/" + fm.bottom + " spacing=" + paint.getFontSpacing()
				+ " letterSpacing=" + paint.getLetterSpacing() + " scaleX=" + paint.getTextScaleX());
		for (String t : new String[] { "H", "Hey Wazer!", "" }) {
			android.graphics.Rect r = new android.graphics.Rect();
			paint.getTextBounds(t, 0, t.length(), r);
			float[] widths = new float[Math.max(1, t.length())];
			int n = paint.getTextWidths(t, widths);
			StringBuilder w = new StringBuilder();
			for (int i = 0; i < n; i++)
				w.append(i > 0 ? "," : "").append(widths[i]);
			android.text.StaticLayout l = android.text.StaticLayout.Builder.obtain(t, 0, t.length(), paint, 100000).setIncludePad(false).build();
			Log.i(TAG, "textprobe \"" + t + "\": measureText=" + paint.measureText(t) + " bounds=" + r + " widths=[" + w + "]"
					+ " layout h=" + l.getHeight() + " lines=" + l.getLineCount() + " top0=" + l.getLineTop(0) + " bottom0=" + l.getLineBottom(0)
					+ " asc0=" + l.getLineAscent(0) + " desc0=" + l.getLineDescent(0) + " base0=" + l.getLineBaseline(0) + " w0=" + l.getLineWidth(0)
					+ " desired=" + android.text.Layout.getDesiredWidth(t, paint) + " runAdvance=" + paint.getRunAdvance(t, 0, t.length(), 0, t.length(), false, t.length()));
		}
	}

	/**
	 * A daemon thread that logs the stack of every Java thread when /tmp/atl-dumpstacks appears
	 * (created by native debugging hooks, e.g. the thread-exit trace), independent of the main thread.
	 */
	private static synchronized void installStackWatcher() {
		if (sStackWatcher != null)
			return;
		sStackWatcher = new Thread("atl-stack-watcher") {
			@Override
			public void run() {
				final File trigger = new File("/tmp/atl-dumpstacks");
				while (true) {
					try {
						Thread.sleep(150);
					} catch (InterruptedException e) {
						return;
					}
					if (!trigger.exists())
						continue;
					trigger.delete();
					Log.i(TAG, "=== java thread stacks ===");
					for (java.util.Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
						Thread t = e.getKey();
						Log.i(TAG, "\"" + t.getName() + "\" id=" + t.getId() + " state=" + t.getState());
						for (StackTraceElement f : e.getValue())
							Log.i(TAG, "    at " + f);
					}
					Log.i(TAG, "=== end stacks ===");
				}
			}
		};
		sStackWatcher.setDaemon(true);
		sStackWatcher.start();
	}

	private static String readTrigger() {
		try {
			java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader(TRIGGER));
			String line = r.readLine();
			r.close();
			return line == null ? "" : line;
		} catch (java.io.IOException e) {
			return "";
		}
	}

	private static void tvInfo(View v, String want) {
		if (v instanceof android.widget.TextView) {
			android.widget.TextView tv = (android.widget.TextView) v;
			CharSequence t = tv.getText();
			if (t != null && t.toString().equals(want)) {
				android.text.Layout l = tv.getLayout();
				Log.i(TAG, "tvinfo: " + v.getClass().getName() + " \"" + t + "\" textSize=" + tv.getTextSize()
				      + " typeface=" + tv.getTypeface() + " style=" + (tv.getTypeface() != null ? tv.getTypeface().getStyle() : -1)
				      + " measured=" + v.getMeasuredWidth() + "x" + v.getMeasuredHeight() + " size=" + v.getWidth() + "x" + v.getHeight()
				      + " paint.measureText=" + tv.getPaint().measureText(t.toString()) + " paintSize=" + tv.getPaint().getTextSize()
				      + " pad=" + tv.getCompoundPaddingLeft() + "," + tv.getCompoundPaddingRight()
				      + " layoutWidth=" + (l != null ? l.getWidth() + " lines=" + l.getLineCount() + " line0=" + l.getLineWidth(0) : "null"));
			}
		}
		if (v instanceof ViewGroup) {
			ViewGroup g = (ViewGroup) v;
			for (int i = 0; i < g.getChildCount(); i++)
				tvInfo(g.getChildAt(i), want);
		}
	}

	/** Writes the GTK widget tree containing the widget to stderr (allocations, mapping, child order). */
	private static native void nativeDumpGtk(long widget);

	/**
	 * Walks a Compose owner view's LayoutNode tree through reflection (the names carry the
	 * androidx_compose_ui_ui visibility suffix of the Kotlin internals): size, placement and the
	 * modifier classes of every node, so Compose content shows up next to the View tree.
	 */
	private static void dumpComposeTree(View composeView, int depth) {
		try {
			Object root = composeView.getClass().getMethod("getRoot").invoke(composeView);
			dumpLayoutNode(root, depth, 0);
		} catch (Throwable t) {
			Log.i(TAG, indent(depth) + "(compose tree unavailable: " + t + ")");
		}
	}

	private static String indent(int depth) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < depth; i++)
			sb.append("  ");
		return sb.toString();
	}

	private static Object call(Object o, String... names) throws Exception {
		for (String n : names) {
			try {
				java.lang.reflect.Method m = o.getClass().getMethod(n);
				m.setAccessible(true);
				return m.invoke(o);
			} catch (NoSuchMethodException e) {
				// try the next name
			}
		}
		throw new NoSuchMethodException(names[0]);
	}

	/**
	 * The vector tree under a Compose VectorPainter (VectorComponent -> GroupComponent -> PathComponent):
	 * group transforms and clip paths, and per path its fill/stroke brush, fill type, alpha and the
	 * number of path nodes, found by field type so obfuscated field names do not matter.
	 */
	private static String describeVector(Object o, int depth) {
		if (o == null || depth > 6)
			return "";
		StringBuilder sb = new StringBuilder();
		String cn = o.getClass().getName();
		String sn = o.getClass().getSimpleName();
		if (cn.endsWith("PathComponent")) {
			sb.append("path(");
			for (java.lang.reflect.Field f : o.getClass().getDeclaredFields()) {
				try {
					if (java.lang.reflect.Modifier.isStatic(f.getModifiers()))
						continue;
					f.setAccessible(true);
					Object v = f.get(o);
					if (v == null)
						continue;
					String vn = v.getClass().getName();
					if (v instanceof java.util.List)
						sb.append(f.getName()).append("=").append(((java.util.List<?>) v).size()).append("nodes ");
					else if (vn.contains("Brush") || vn.contains("SolidColor") || vn.contains("Gradient") || vn.contains("Shader"))
						sb.append(f.getName()).append("=").append(v).append(' ');
					else if (v instanceof Float || v instanceof Integer)
						sb.append(f.getName()).append("=").append(v).append(' ');
				} catch (Throwable e) {
				}
			}
			return sb.append(") ").toString();
		}
		if (cn.endsWith("GroupComponent") || cn.endsWith("VectorComponent") || cn.endsWith("VectorPainter")) {
			sb.append(sn).append("(");
			for (java.lang.reflect.Field f : o.getClass().getDeclaredFields()) {
				try {
					if (java.lang.reflect.Modifier.isStatic(f.getModifiers()))
						continue;
					f.setAccessible(true);
					Object v = f.get(o);
					if (v == null)
						continue;
					String vn = v.getClass().getName();
					if (vn.startsWith("androidx.compose.ui.graphics.vector") && !vn.endsWith("Kt"))
						sb.append(describeVector(v, depth + 1));
					else if (v instanceof java.util.List) {
						java.util.List<?> l = (java.util.List<?>) v;
						boolean comps = !l.isEmpty() && l.get(0) != null && l.get(0).getClass().getName().startsWith("androidx.compose.ui.graphics.vector")
						                && l.get(0).getClass().getName().endsWith("Component");
						if (comps) {
							for (Object c : l)
								sb.append(describeVector(c, depth + 1));
						} else if (cn.endsWith("GroupComponent") && !l.isEmpty()) {
							sb.append("clip=").append(l.size()).append("nodes ");
						}
					} else if ((v instanceof Float && ((Float) v) != 0f && ((Float) v) != 1f))
						sb.append(f.getName()).append("=").append(v).append(' ');
				} catch (Throwable e) {
				}
			}
			return sb.append(") ").toString();
		}
		return "";
	}

	private static void dumpLayoutNode(Object node, int depth, int count) throws Exception {
		if (count > 400)
			return;
		StringBuilder sb = new StringBuilder(indent(depth)).append("~node");
		try {
			sb.append(' ').append(call(node, "getWidth")).append('x').append(call(node, "getHeight"));
		} catch (Exception e) {
		}
		try {
			Object placed = call(node, "isPlaced");
			if (Boolean.FALSE.equals(placed))
				sb.append(" NOT-PLACED");
		} catch (Exception e) {
		}
		try {
			java.util.List<?> infos = (java.util.List<?>) call(node, "getModifierInfo");
			StringBuilder mods = new StringBuilder();
			for (Object info : infos) {
				Object mod = call(info, "getModifier");
				String n = mod.getClass().getSimpleName();
				if (n.isEmpty())
					n = mod.getClass().getName();
				if (mods.length() < 4000)
					mods.append(n).append(',');
				if (n.equals("PainterElement")) {
					// the painter (VectorPainter/BitmapPainter/...), its size and the tint colour filter
					for (java.lang.reflect.Field f : mod.getClass().getDeclaredFields()) {
						try {
							if (java.lang.reflect.Modifier.isStatic(f.getModifiers()))
								continue;
							f.setAccessible(true);
							Object val = f.get(mod);
							if (val == null || val instanceof Boolean || val instanceof Float)
								continue;
							String cn = val.getClass().getName();
							if (cn.contains("Painter") || cn.contains("ColorFilter") || cn.contains("painter")) {
								mods.append(f.getName()).append('=').append(val.getClass().getSimpleName().isEmpty() ? cn : val.getClass().getSimpleName())
								    .append(cn.contains("ColorFilter") ? "(" + val + ")" : "").append(',');
								if (cn.endsWith("VectorPainter"))
									mods.append(" vector{").append(describeVector(val, 0)).append("} ");
							}
						} catch (Throwable e) {
						}
					}
				}
				if (n.equals("BackgroundElement")) {
					try {
						java.lang.reflect.Field f = mod.getClass().getDeclaredField("color");
						f.setAccessible(true);
						long color = f.getLong(mod);
						mods.append("#").append(Long.toHexString(color >>> 32)).append(',');
					} catch (Exception e) {
					}
				}
			}
			if (mods.length() > 0)
				sb.append(" [").append(mods).append(']');
		} catch (Exception e) {
		}
		Log.i(TAG, sb.toString());
		java.util.List<?> children = (java.util.List<?>) call(node, "getChildren$androidx_compose_ui_ui", "getChildren$ui_release", "getChildren");
		for (Object c : children)
			dumpLayoutNode(c, depth + 1, count + 1);
	}

	public static void dump(View v, int depth) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < depth; i++)
			sb.append("  ");
		sb.append(v.getClass().getName());
		if (v.getId() != View.NO_ID)
			sb.append(" id=0x").append(Integer.toHexString(v.getId()));
		sb.append(" vis=").append(v.getVisibility() == View.VISIBLE ? "V" : v.getVisibility() == View.INVISIBLE ? "I" : "G");
		sb.append(" alpha=").append(v.getAlpha());
		sb.append(" at ").append(v.getLeft()).append(',').append(v.getTop());
		sb.append(" size ").append(v.getWidth()).append('x').append(v.getHeight());
		sb.append(lpString(v.getLayoutParams()));
		sb.append(" ms=").append(specString(v.tlLastWidthMeasureSpec())).append('x').append(specString(v.tlLastHeightMeasureSpec()));
		sb.append(" md=").append(v.getMeasuredWidth()).append('x').append(v.getMeasuredHeight());
		android.graphics.drawable.Drawable bg = v.getBackground();
		if (bg != null) {
			sb.append(" bg=");
			if (bg instanceof android.graphics.drawable.ColorDrawable)
				sb.append("#").append(Integer.toHexString(((android.graphics.drawable.ColorDrawable) bg).getColor()));
			else
				sb.append(bg.getClass().getSimpleName());
		}
		if (v.getBackgroundTintList() != null)
			sb.append(" bgtint=#").append(Integer.toHexString(v.getBackgroundTintList().getColorForState(v.getDrawableState(), 0)));
		if (v.getId() != android.view.View.NO_ID) {
			try {
				sb.append(" @").append(v.getResources().getResourceEntryName(v.getId()));
			} catch (Exception e) {
			}
		}
		if (v instanceof android.widget.ImageView) {
			android.widget.ImageView iv = (android.widget.ImageView) v;
			android.graphics.drawable.Drawable d = iv.getDrawable();
			sb.append(" img=").append(d == null ? "null" : d.getClass().getSimpleName() + "(" + d.getIntrinsicWidth() + "x" + d.getIntrinsicHeight() + ")");
			if (iv.getImageTintList() != null)
				sb.append(" imgtint=#").append(Integer.toHexString(iv.getImageTintList().getColorForState(iv.getDrawableState(), 0)));
			if (iv.getColorFilter() != null)
				sb.append(" cf=").append(iv.getColorFilter().getClass().getSimpleName());
			if (iv.getScaleType() != android.widget.ImageView.ScaleType.FIT_CENTER)
				sb.append(" st=").append(iv.getScaleType());
			if (!iv.getImageMatrix().isIdentity())
				sb.append(" mx=").append(iv.getImageMatrix().toShortString());
		}
		if (v instanceof android.widget.TextView) {
			android.widget.TextView tv = (android.widget.TextView) v;
			sb.append(" tg=0x").append(Integer.toHexString(tv.getGravity()));
			sb.append(" color=#").append(Integer.toHexString(tv.getCurrentTextColor()));
			CharSequence h = tv.getHint();
			if (h != null && h.length() > 0)
				sb.append(" hint=\"").append(h).append('"');
		}
		if (v instanceof android.widget.Checkable)
			sb.append(" checked=").append(((android.widget.Checkable) v).isChecked());
		for (Class<?> c = v.getClass(); c != null; c = c.getSuperclass()) {
			if (c.getSimpleName().equals("SwitchCompat") || c.getSimpleName().equals("Switch")) {
				// obfuscated builds rename the fields: list every drawable, int and boolean field of the class
				for (java.lang.reflect.Field f : c.getDeclaredFields()) {
					try {
						if (java.lang.reflect.Modifier.isStatic(f.getModifiers()))
							continue;
						Class<?> t = f.getType();
						if (t != int.class && t != boolean.class && t != float.class && !android.graphics.drawable.Drawable.class.isAssignableFrom(t))
							continue;
						f.setAccessible(true);
						Object val = f.get(v);
						if (val instanceof android.graphics.drawable.Drawable) {
							android.graphics.drawable.Drawable d = (android.graphics.drawable.Drawable) val;
							val = d.getClass().getSimpleName() + "(" + d.getIntrinsicWidth() + "x" + d.getIntrinsicHeight() + " b=" + d.getBounds().toShortString() + ")";
						} else if (val == null) {
							continue;
						}
						sb.append(' ').append(f.getName()).append('=').append(val);
					} catch (Exception e) {
					}
				}
				break;
			}
		}
		if (v.getClass().getName().endsWith("AndroidComposeView")) {
			android.content.res.Resources r = v.getContext().getResources();
			sb.append(" res=").append(Integer.toHexString(System.identityHashCode(r))).append("/ui=0x").append(Integer.toHexString(r.getConfiguration().uiMode))
			  .append(" ctx=").append(v.getContext().getClass().getSimpleName());
		}
		if (v.getTranslationX() != 0 || v.getTranslationY() != 0)
			sb.append(" tr ").append(v.getTranslationX()).append(',').append(v.getTranslationY());
		sb.append(v.isAttachedToWindow() ? " attached" : " DETACHED");
		if (v instanceof TextView) {
			CharSequence t = ((TextView) v).getText();
			if (t != null && t.length() > 0)
				sb.append(" text=\"").append(t.length() > 40 ? t.subSequence(0, 40) + "..." : t).append('"');
		}
		Log.i(TAG, sb.toString());
		if (v.getClass().getName().equals("androidx.compose.ui.platform.AndroidComposeView"))
			dumpComposeTree(v, depth + 1);
		if (v instanceof ViewGroup) {
			ViewGroup g = (ViewGroup) v;
			for (int i = 0; i < g.getChildCount(); i++)
				dump(g.getChildAt(i), depth + 1);
		}
	}

	/** " lp=WxH[ w=weight][ g=gravity][ m=l,t,r,b]" with MATCH_PARENT/WRAP_CONTENT spelled out */
	static String lpString(android.view.ViewGroup.LayoutParams lp) {
		if (lp == null)
			return " lp=null";
		StringBuilder sb = new StringBuilder(" lp=").append(lpDim(lp.width)).append('x').append(lpDim(lp.height));
		if (lp instanceof android.widget.LinearLayout.LayoutParams) {
			android.widget.LinearLayout.LayoutParams l = (android.widget.LinearLayout.LayoutParams) lp;
			if (l.weight != 0)
				sb.append(" w=").append(l.weight);
			if (l.gravity != -1)
				sb.append(" g=0x").append(Integer.toHexString(l.gravity));
		} else if (lp instanceof android.widget.FrameLayout.LayoutParams) {
			sb.append(" g=0x").append(Integer.toHexString(((android.widget.FrameLayout.LayoutParams) lp).gravity));
		}
		if (lp instanceof android.view.ViewGroup.MarginLayoutParams) {
			android.view.ViewGroup.MarginLayoutParams m = (android.view.ViewGroup.MarginLayoutParams) lp;
			if (m.leftMargin != 0 || m.topMargin != 0 || m.rightMargin != 0 || m.bottomMargin != 0)
				sb.append(" m=").append(m.leftMargin).append(',').append(m.topMargin).append(',').append(m.rightMargin).append(',').append(m.bottomMargin);
		}
		return sb.toString();
	}

	static String lpDim(int d) {
		return d == android.view.ViewGroup.LayoutParams.MATCH_PARENT ? "MP" : d == android.view.ViewGroup.LayoutParams.WRAP_CONTENT ? "WC" : Integer.toString(d);
	}

	static String specString(int spec) {
		int mode = android.view.View.MeasureSpec.getMode(spec);
		return (mode == android.view.View.MeasureSpec.EXACTLY ? "E" : mode == android.view.View.MeasureSpec.AT_MOST ? "A" : "U")
				+ android.view.View.MeasureSpec.getSize(spec);
	}

	/** Runs LayoutNode.hitTest of every attached compose root at a window position and logs the hit nodes. */
	/** A Compose text field core node: focus, layout result size, paragraph size, text and style. */
	private static void appendTextFieldInfo(StringBuilder sb, Object node) {
	/* the text field's layout: size, text and focus (a zero size means nothing can be drawn) */
	try {
		Object tls = null, focused = null, legacy = null;
		for (Class<?> c = node.getClass(); c != null; c = c.getSuperclass()) {
			for (java.lang.reflect.Field f : c.getDeclaredFields()) {
				f.setAccessible(true);
				if (f.getName().equals("textLayoutState")) tls = f.get(node);
				if (f.getName().equals("isFocused")) focused = f.get(node);
				if (f.getName().equals("state") || f.getName().equals("legacyTextFieldState")) legacy = f.get(node);
			}
		}
		if (legacy != null) {
			tls = legacy;
			try { focused = call(legacy, "getHasFocus"); } catch (Exception ignored) { }
			try { sb.append(" legacyText='").append(call(call(legacy, "getTextDelegate"), "getText")).append("'"); } catch (Exception ignored) { }
			// the BasicTextField(String) wrapper's captured objects: its local TextFieldValue state and the app callback
			try {
				java.lang.reflect.Field of = legacy.getClass().getDeclaredField("onValueChangeOriginal");
				of.setAccessible(true);
				Object orig = of.get(legacy);
				if (orig != null) {
					for (java.lang.reflect.Field f : orig.getClass().getDeclaredFields()) {
						f.setAccessible(true);
						Object v = f.get(orig);
						String desc = v == null ? "null" : v.getClass().getName();
						try {
							if (v != null && Class.forName("androidx.compose.runtime.State", true, v.getClass().getClassLoader()).isInstance(v))
								desc += "{" + call(v, "getValue") + "}";
						} catch (Throwable ignored) { }
						sb.append(" wrapper.").append(f.getName()).append('=').append(desc);
						// the app callback's own captures (two levels): names the screen's handler / view model
						if (v != null && !desc.startsWith("androidx.")) {
							for (java.lang.reflect.Field g : v.getClass().getDeclaredFields()) {
								try {
									g.setAccessible(true);
									Object w = g.get(v);
									sb.append(" cb.").append(g.getName()).append('=').append(w == null ? "null" : w.getClass().getName());
									if (w != null && !(w instanceof Number) && !(w instanceof String)) {
										for (java.lang.reflect.Field h : w.getClass().getDeclaredFields()) {
											try {
												h.setAccessible(true);
												Object x = h.get(w);
												sb.append(" cb2.").append(h.getName()).append('=').append(x == null ? "null" : x.getClass().getName());
											} catch (Throwable ignored) { }
										}
									}
								} catch (Throwable ignored) { }
							}
						}
					}
				}
				java.lang.reflect.Field pf = legacy.getClass().getDeclaredField("processor");
				pf.setAccessible(true);
				Object proc = pf.get(legacy);
				sb.append(" buffer='").append(call(call(proc, "toTextFieldValue"), "getText")).append("'");
			} catch (Throwable t) { sb.append(" wrapper? ").append(t); }
			// the node's own flags (enabled, readOnly, ...): a disabled field must not be focusable
			for (java.lang.reflect.Field f : node.getClass().getDeclaredFields()) {
				try {
					if (f.getType() == boolean.class) {
						f.setAccessible(true);
						sb.append(' ').append(f.getName()).append('=').append(f.getBoolean(node));
					}
				} catch (Exception ignored) { }
			}
			for (java.lang.reflect.Field f : legacy.getClass().getDeclaredFields()) {
				try {
					if (f.getName().equals("isLayoutResultStale") || f.getName().equals("onValueChange") || f.getName().equals("onValueChangeOriginal") || f.getName().equals("inputSession")) {
						f.setAccessible(true);
						Object v = f.get(legacy);
						sb.append(' ').append(f.getName()).append('=').append(v == null ? "null" : (v instanceof Boolean ? v : v.getClass().getName()));
					}
				} catch (Exception ignored) { }
			}
		}
		sb.append(" focused=").append(focused);
		Object lr = tls != null ? call(tls, "getLayoutResult") : null;
		if (lr != null && lr.getClass().getSimpleName().contains("Proxy"))
			lr = call(lr, "getValue");
		if (lr != null) {
			Object size = call(lr, "getSize-YbymL2g", "getSize");
			long sz = size instanceof Long ? (Long) size : 0L;
			sb.append(" layout=").append((int) (sz >> 32)).append('x').append((int) (sz & 0xffffffffL));
			Object mp = call(lr, "getMultiParagraph");
			if (mp != null)
				sb.append(" para=").append(call(mp, "getWidth")).append('x').append(call(mp, "getHeight")).append(" lines=").append(call(mp, "getLineCount"));
			Object li = call(lr, "getLayoutInput");
			if (li != null)
				sb.append(" text='").append(call(li, "getText")).append("' style=").append(String.valueOf(call(li, "getStyle")).replaceAll("[\\s]+", " "));
		} else {
			sb.append(" layout=null");
		}
	} catch (Exception e) {
		sb.append(" field?").append(e.getClass().getSimpleName()).append(':').append(e.getMessage());
	}
	}

	/** Walks every compose root's layout tree and logs each text field core node. */
	private static void composeTextFields(View root) {
		java.util.List<View> owners = new java.util.ArrayList<>();
		collectComposeViews(root, owners);
		for (android.app.Dialog dlg : android.app.Dialog.tlShowingDialogs()) {
			View dd = dlg.getWindow() != null ? dlg.getWindow().getDecorView() : null;
			if (dd != null)
				collectComposeViews(dd, owners);
		}
		for (View cv : owners) {
			try {
				int[] loc = new int[2];
				cv.getLocationOnScreen(loc);
				Object ln = call(cv, "getRoot");
				StringBuilder sb = new StringBuilder();
				try {
					Object wi = call(cv, "getWindowInfo");
					sb.append("owner ").append(cv.getWidth()).append('x').append(cv.getHeight()).append(" composeWindowFocused=").append(call(wi, "isWindowFocused"))
					  .append(" viewFocused=").append(cv.isFocused()).append(" hasWindowFocus=").append(cv.hasWindowFocus()).append(" attached=").append(cv.isAttachedToWindow())
					  .append(" shown=").append(cv.isShown()).append(" | ");
				} catch (Exception e) {
					sb.append("owner? ").append(e).append(" | ");
				}
				walkTextFields(ln, 0, sb, cv.getWidth() + "x" + cv.getHeight() + "@" + loc[0] + "," + loc[1]);
				Log.i(TAG, "textfields: " + sb);
			} catch (Exception e) {
				Log.i(TAG, "textfields: " + e);
			}
		}
	}

	private static void composeFocusState(View root) {
		java.util.List<View> owners = new java.util.ArrayList<>();
		collectComposeViews(root, owners);
		View rootFocus = root.findFocus();
		Log.i(TAG, "focusstate: decor findFocus=" + (rootFocus == null ? "null" : rootFocus.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(rootFocus))));
		for (View cv : owners) {
			StringBuilder sb = new StringBuilder("focusstate: owner ").append(cv.getWidth()).append('x').append(cv.getHeight())
			  .append('@').append(Integer.toHexString(System.identityHashCode(cv)))
			  .append(" isFocused=").append(cv.isFocused()).append(" hasFocus=").append(cv.hasFocus()).append(" shown=").append(cv.isShown());
			try {
				Object fo = call(cv, "getFocusOwner");
				for (Class<?> c = fo.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
					for (java.lang.reflect.Field f : c.getDeclaredFields()) {
						if (java.lang.reflect.Modifier.isStatic(f.getModifiers()))
							continue;
						f.setAccessible(true);
						Object v = f.get(fo);
						String vs = v == null ? "null" : v.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(v));
						if (v instanceof Boolean || v instanceof Number)
							vs = String.valueOf(v);
						sb.append(' ').append(f.getName()).append('=').append(vs);
					}
				}
				sb.append(" rootState=").append(call(fo, "getRootState"));
			} catch (Throwable t) {
				sb.append(" focusOwner? ").append(t);
			}
			Log.i(TAG, sb.toString());
			try {
				walkFocusTargets(call(cv, "getRoot"), 0);
			} catch (Throwable t) {
				Log.i(TAG, "focusstate: walk? " + t);
			}
		}
	}

	/** a modifier node and, for a DelegatingNode, its delegate chain (focus targets are often delegates) */
	private static void logFocusTarget(Object n, Object owner, Object ln, int depth, int level) throws Exception {
		if (n == null || level > 6)
			return;
		if (n.getClass().getSimpleName().equals("FocusTargetNode")) {
			String st;
			try { st = String.valueOf(call(n, "getFocusState")); } catch (Exception e) { st = "?" + e.getClass().getSimpleName(); }
			String props = "";
			try {
				Object fp = call(n, "fetchFocusProperties$androidx_compose_ui_ui", "fetchFocusProperties$ui_release", "fetchFocusProperties");
				props = " canFocus=" + call(fp, "getCanFocus");
			} catch (Exception e) { }
			Log.i(TAG, "focusstate:   " + indent(depth) + "FocusTargetNode@" + Integer.toHexString(System.identityHashCode(n)) + " state=" + st + props
			        + (owner != null ? " in " + owner.getClass().getName() : "") + " layout=" + call(ln, "getWidth") + "x" + call(ln, "getHeight") + " depth=" + depth);
		}
		Object delegate = null;
		try { delegate = call(n, "getDelegate$androidx_compose_ui_ui", "getDelegate$ui_release"); } catch (Exception e) { }
		for (int k = 0; delegate != null && k < 16; k++) {
			logFocusTarget(delegate, n, ln, depth, level + 1);
			delegate = call(delegate, "getChild$androidx_compose_ui_ui", "getChild$ui_release", "getChild");
		}
	}

	private static void walkFocusTargets(Object ln, int depth) throws Exception {
		if (ln == null || depth > 80)
			return;
		Object nodes = call(ln, "getNodes$androidx_compose_ui_ui", "getNodes$ui_release", "getNodes");
		Object n = nodes != null ? call(nodes, "getHead$androidx_compose_ui_ui", "getHead$ui_release", "getHead") : null;
		for (int i = 0; n != null && i < 64; i++) {
			logFocusTarget(n, null, ln, depth, 0);
			n = call(n, "getChild$androidx_compose_ui_ui", "getChild$ui_release", "getChild");
		}
		Object children = call(ln, "getChildren$androidx_compose_ui_ui", "getChildren$ui_release", "getChildren", "get_children$androidx_compose_ui_ui", "get_children$ui_release");
		if (children instanceof java.util.List) {
			for (Object c : (java.util.List<?>) children)
				walkFocusTargets(c, depth + 1);
		}
	}

	private static void collectComposeViews(View v, java.util.List<View> out) {
		if (v.getClass().getName().endsWith("AndroidComposeView"))
			out.add(v);
		if (v instanceof ViewGroup) {
			ViewGroup g = (ViewGroup) v;
			for (int i = 0; i < g.getChildCount(); i++)
				collectComposeViews(g.getChildAt(i), out);
		}
	}

	private static void walkTextFields(Object ln, int depth, StringBuilder sb, String owner) throws Exception {
		if (ln == null || depth > 80)
			return;
		Object nodes = call(ln, "getNodes$androidx_compose_ui_ui", "getNodes$ui_release", "getNodes");
		Object n = nodes != null ? call(nodes, "getHead$androidx_compose_ui_ui", "getHead$ui_release", "getHead") : null;
		for (int i = 0; n != null && i < 64; i++) {
			String cn = n.getClass().getSimpleName();
			if (cn.contains("TextField") && !cn.contains("Semantics") || cn.startsWith("CoreTextFieldSemanticsModifierNode")) {
				sb.append("textfield in ").append(owner).append(' ').append(cn).append(" node ").append(call(ln, "getWidth")).append('x').append(call(ln, "getHeight"))
				  .append(" depth=").append(depth).append(" placed=").append(call(ln, "isPlaced", "isPlaced$androidx_compose_ui_ui"));
				try {
					Object coord = call(ln, "getCoordinates");
					Class<?> lc = Class.forName("androidx.compose.ui.layout.LayoutCoordinates", true, ln.getClass().getClassLoader());
					Object b = Class.forName("androidx.compose.ui.layout.LayoutCoordinatesKt", true, ln.getClass().getClassLoader()).getMethod("boundsInRoot", lc).invoke(null, coord);
					sb.append(" bounds=").append(b);
				} catch (Exception e) {
					sb.append(" bounds?").append(e.getClass().getSimpleName());
				}
				appendTextFieldInfo(sb, n);
				sb.append(" | ");
			}
			n = call(n, "getChild$androidx_compose_ui_ui", "getChild$ui_release", "getChild");
		}
		Object children = call(ln, "getChildren$androidx_compose_ui_ui", "getChildren$ui_release", "getChildren", "get_children$androidx_compose_ui_ui", "get_children$ui_release");
		if (children instanceof java.util.List) {
			for (Object c : (java.util.List<?>) children)
				walkTextFields(c, depth + 1, sb, owner);
		}
	}

	static void composeTree(View root, float x, float y, float w, float h) {
		java.util.ArrayList<View> composeViews = new java.util.ArrayList<>();
		collectByName(root, "AndroidComposeView", composeViews);
		for (View cv : composeViews) {
			if (!cv.isAttachedToWindow() || cv.getWidth() == 0)
				continue;
			int[] loc = new int[2];
			cv.getLocationInWindow(loc);
			Log.i(TAG, "ctree " + cv.getWidth() + "x" + cv.getHeight() + " @" + loc[0] + "," + loc[1]);
			try {
				ClassLoader cl = cv.getClass().getClassLoader();
				Class<?> lc = Class.forName("androidx.compose.ui.layout.LayoutCoordinates", true, cl);
				java.lang.reflect.Method boundsInRoot = Class.forName("androidx.compose.ui.layout.LayoutCoordinatesKt", true, cl).getMethod("boundsInRoot", lc);
				composeTreeNode(call(cv, "getRoot"), 0, x - loc[0], y - loc[1], w, h, boundsInRoot, new int[] {0});
			} catch (Throwable e) {
				Log.i(TAG, "ctree: " + e);
			}
		}
	}

	private static void composeTreeNode(Object ln, int depth, float x, float y, float w, float h, java.lang.reflect.Method boundsInRoot, int[] count) throws Exception {
		if (ln == null || count[0] > 400)
			return;
		Object coords = call(ln, "getCoordinates");
		Object rect = boundsInRoot.invoke(null, coords);
		float l = (Float) call(rect, "getLeft"), t = (Float) call(rect, "getTop"), r = (Float) call(rect, "getRight"), b = (Float) call(rect, "getBottom");
		boolean meets = r >= x && l <= x + w && b >= y && t <= y + h;
		if (meets) {
			count[0]++;
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < depth && i < 40; i++)
				sb.append(' ');
			sb.append('#').append(call(ln, "getSemanticsId")).append(' ').append((int) l).append(',').append((int) t).append('-').append((int) r).append(',').append((int) b);
			try {
				sb.append(" w=").append(call(ln, "getWidth")).append(" h=").append(call(ln, "getHeight"));
			} catch (Exception ignored) {
			}
			try {
				if (!(Boolean) call(ln, "isPlaced"))
					sb.append(" NOTPLACED");
			} catch (Exception ignored) {
			}
			try {
				Object mp = call(ln, "getMeasurePolicy");
				sb.append(" mp=").append(mp.getClass().getName());
			} catch (Exception ignored) {
			}
			try {
				Object chain = call(ln, "getNodes$ui_release", "getNodes$androidx_compose_ui_ui", "getNodes");
				Object node = call(chain, "getHead$ui_release", "getHead$androidx_compose_ui_ui", "getHead");
				sb.append(" [");
				for (int i = 0; node != null && i < 30; i++) {
					String sn = node.getClass().getSimpleName();
					if (!sn.equals("TailModifierNode") && !sn.contains("SentinelHead"))
						sb.append(sn.length() <= 2 ? node.getClass().getName() : sn).append(' ');
					for (Class<?> c = node.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
						for (java.lang.reflect.Field f : c.getDeclaredFields()) {
							if (java.lang.reflect.Modifier.isStatic(f.getModifiers()))
								continue;
							Class<?> ft = f.getType();
							if (ft == String.class || CharSequence.class.isAssignableFrom(ft)) {
								f.setAccessible(true);
								Object v = f.get(node);
								if (v != null && v.toString().length() > 0)
									sb.append(f.getName()).append("=\"").append(v.toString().replace('\n', ' ')).append("\" ");
							} else if (ft.getName().contains("WindowInsets") || ft.getName().contains("PaddingValues")) {
								f.setAccessible(true);
								Object v = f.get(node);
								if (v != null)
									sb.append(f.getName()).append('=').append(v).append(' ');
							} else if (sn.contains("Padding") && (ft == float.class || ft == boolean.class)) {
								f.setAccessible(true);
								sb.append(f.getName()).append('=').append(f.get(node)).append(' ');
							} else if (sn.startsWith("Draw") || sn.contains("Background") || sn.contains("Graphics")) {
								if (ft == float.class && f.getName().toLowerCase().contains("alpha")) {
									f.setAccessible(true);
									sb.append(f.getName()).append('=').append(f.getFloat(node)).append(' ');
								}
							}
						}
					}
					node = call(node, "getChild$ui_release", "getChild$androidx_compose_ui_ui", "getChild");
				}
				sb.append(']');
			} catch (Exception e) {
				sb.append(" nodes?").append(e.getClass().getSimpleName());
			}
			Log.i(TAG, "ctree " + sb);
		}
		java.util.List<?> children = (java.util.List<?>) call(ln, "getChildren$ui_release", "getChildren$androidx_compose_ui_ui", "getChildren");
		for (Object c : children)
			composeTreeNode(c, depth + 1, x, y, w, h, boundsInRoot, count);
	}

	static void composeHitTest(View root, float x, float y) {
		java.util.ArrayList<View> composeViews = new java.util.ArrayList<>();
		collectByName(root, "AndroidComposeView", composeViews);
		for (View cv : composeViews) {
			if (!cv.isAttachedToWindow() || cv.getWidth() == 0)
				continue;
			int[] loc = new int[2];
			cv.getLocationInWindow(loc);
			float lx = x - loc[0], ly = y - loc[1];
			try {
				Object layoutNode = call(cv, "getRoot");
				Class<?> hitResultClass = Class.forName("androidx.compose.ui.node.HitTestResult", true, cv.getClass().getClassLoader());
				Object result = hitResultClass.getDeclaredConstructor().newInstance();
				java.lang.reflect.Method hit = null;
				for (java.lang.reflect.Method m : layoutNode.getClass().getMethods()) {
					if (m.getName().startsWith("hitTest") && !m.getName().contains("Semantics") && m.getParameterCount() == 4
							&& m.getParameterTypes()[0] == long.class && m.getParameterTypes()[1] == hitResultClass) {
						hit = m;
					}
				}
				if (hit == null) {
					Log.i(TAG, "hit: no LayoutNode.hitTest on " + layoutNode.getClass());
					continue;
				}
				long packed = (((long) Float.floatToRawIntBits(lx)) << 32) | (Float.floatToRawIntBits(ly) & 0xffffffffL);
				Object[] args = new Object[4];
				args[0] = packed;
				args[1] = result;
				for (int i = 2; i < 4; i++) // Compose 1.7: (isTouchEvent, isInLayer); 1.8: (pointerType=Touch, isInLayer)
					args[i] = hit.getParameterTypes()[i] == int.class ? (Object) 1 : (Object) Boolean.TRUE;
				hit.invoke(layoutNode, args);
				StringBuilder sb = new StringBuilder("hit " + x + "," + y + " in " + cv.getWidth() + "x" + cv.getHeight() + " @" + loc[0] + "," + loc[1] + ": ");
				int n = 0;
				for (Object node : (java.util.List<?>) result) {
					if (n++ > 24)
						break;
					String nn = node.getClass().getSimpleName().length() <= 2 ? node.getClass().getName() : node.getClass().getSimpleName();
					sb.append(nn);
					try {
						java.lang.reflect.Field ef = node.getClass().getDeclaredField("element");
						ef.setAccessible(true);
						Object el = ef.get(node);
						if (el != null) {
							sb.append('(').append(el.getClass().getSimpleName());
							// an interop filter wraps a lambda that captured the AndroidViewHolder: show that view
							try {
								for (java.lang.reflect.Field lf : el.getClass().getDeclaredFields()) {
									lf.setAccessible(true);
									Object lv = lf.get(el);
									if (lv == null) continue;
									for (java.lang.reflect.Field cf : lv.getClass().getDeclaredFields()) {
										cf.setAccessible(true);
										Object cv2 = cf.get(lv);
										if (cv2 instanceof View) {
											View hv = (View) cv2;
											sb.append(" view=").append(hv.getClass().getSimpleName()).append(' ').append(hv.getWidth()).append('x').append(hv.getHeight())
											  .append(" md=").append(hv.getMeasuredWidth()).append('x').append(hv.getMeasuredHeight()).append(" vis=").append(hv.getVisibility());
											if (hv instanceof android.view.ViewGroup && ((android.view.ViewGroup) hv).getChildCount() > 0) {
												View c0 = ((android.view.ViewGroup) hv).getChildAt(0);
												sb.append(" child=").append(c0.getClass().getSimpleName()).append(" vis=").append(c0.getVisibility()).append(' ').append(c0.getWidth()).append('x').append(c0.getHeight());
											}
										}
									}
								}
							} catch (Exception e) {
							}
							sb.append(')');
						}
					} catch (Exception e) {
					}
					try {
						java.lang.reflect.Field cf = null;
						for (Class<?> c = node.getClass(); c != null && cf == null; c = c.getSuperclass()) {
							try {
								cf = c.getDeclaredField("coordinator");
							} catch (NoSuchFieldException ignored) {
							}
						}
						cf.setAccessible(true);
						Object coord = cf.get(node);
						Class<?> lc = Class.forName("androidx.compose.ui.layout.LayoutCoordinates", true, cv.getClass().getClassLoader());
						Object rect = Class.forName("androidx.compose.ui.layout.LayoutCoordinatesKt", true, cv.getClass().getClassLoader())
								.getMethod("boundsInRoot", lc).invoke(null, coord);
						sb.append('@').append(rect);
						try {
							java.lang.reflect.Field lf = null;
							for (Class<?> c = coord.getClass(); c != null && lf == null; c = c.getSuperclass()) {
								try {
									lf = c.getDeclaredField("layoutNode");
								} catch (NoSuchFieldException ignored) {
								}
							}
							lf.setAccessible(true);
							Object ln = lf.get(coord);
							StringBuilder chain = new StringBuilder();
							Object n2 = ln;
							for (int d = 0; d < 6 && n2 != null; d++) {
								chain.append(d == 0 ? "#" : "<").append(call(n2, "getSemanticsId"));
								n2 = call(n2, "getParent$androidx_compose_ui_ui", "getParent$ui_release", "getParent");
							}
							sb.append(" depth=").append(call(ln, "getDepth$androidx_compose_ui_ui", "getDepth$ui_release", "getDepth")).append(' ').append(chain);
						} catch (Exception e) {
							sb.append(" tree?").append(e.getClass().getSimpleName());
						}
					} catch (Exception e) {
						sb.append("@?").append(e.getClass().getSimpleName());
					}
					if (node.getClass().getSimpleName().startsWith("TextFieldCoreModifierNode"))
						appendTextFieldInfo(sb, node);
					if (node.getClass().getSimpleName().equals("AnchoredDraggableNode")) {
						try {
							java.lang.reflect.Field sf = null;
							for (Class<?> c = node.getClass(); c != null && sf == null; c = c.getSuperclass()) {
								try {
									sf = c.getDeclaredField("state");
								} catch (NoSuchFieldException ignored) {
								}
							}
							sf.setAccessible(true);
							Object st = sf.get(node);
							sb.append(" state{cur=").append(call(st, "getCurrentValue")).append(" target=").append(call(st, "getTargetValue"))
							  .append(" settled=").append(call(st, "getSettledValue")).append(" offset=").append(call(st, "getOffset"))
							  .append(" animating=").append(call(st, "isAnimationRunning")).append('}');
						} catch (Exception e) {
							sb.append(" state?").append(e);
						}
					}
					sb.append(' ');
				}
				if (n == 0)
					sb.append("(nothing)");
				Log.i(TAG, sb.toString());
			} catch (Exception e) {
				Log.i(TAG, "hit failed: " + e);
			}
		}
	}

	private static java.util.List<Activity> snapshotActivities() {
		synchronized (sActivities) {
			return new java.util.ArrayList<Activity>(sActivities);
		}
	}

	/** objects reachable from o (app classes, arrays, collections, maps) whose class name contains want */
	private static void findObjects(Object o, String want, int depth, java.util.IdentityHashMap<Object, Boolean> seen, String path, int[] found) {
		if (o == null || depth < 0 || seen.containsKey(o) || seen.size() > 200000)
			return;
		seen.put(o, Boolean.TRUE);
		Class<?> oc = o.getClass();
		if (oc.getName().contains(want)) {
			found[0]++;
			Log.i(TAG, "objects: " + path + " -> " + oc.getName() + "@" + Integer.toHexString(System.identityHashCode(o)));
			dumpFields("objects   ", o, 0, new java.util.IdentityHashMap<Object, Boolean>());
		}
		if (oc.isArray()) {
			if (!oc.getComponentType().isPrimitive()) {
				Object[] arr = (Object[]) o;
				for (int i = 0; i < arr.length && i < 500; i++)
					findObjects(arr[i], want, depth - 1, seen, path + "[" + i + "]", found);
			}
			return;
		}
		if (o instanceof java.util.Collection) {
			int i = 0;
			try {
				for (Object e : (java.util.Collection<?>) o) {
					if (i++ >= 500)
						break;
					findObjects(e, want, depth - 1, seen, path + "[" + (i - 1) + "]", found);
				}
			} catch (Throwable t) {
				// concurrent modification while walking: skip
			}
			return;
		}
		if (o instanceof java.util.Map) {
			try {
				for (java.util.Map.Entry<?, ?> e : ((java.util.Map<?, ?>) o).entrySet())
					findObjects(e.getValue(), want, depth - 1, seen, path + "{" + e.getKey() + "}", found);
			} catch (Throwable t) {
				// skip
			}
			return;
		}
		for (Class<?> c = oc; c != null && c != Object.class; c = c.getSuperclass()) {
			String cn = c.getName();
			boolean app = !(cn.startsWith("java.") || cn.startsWith("android.") || cn.startsWith("libcore.") || cn.startsWith("dalvik.") || cn.startsWith("sun."));
			if (!app && !(o instanceof Activity) && !cn.startsWith("androidx.lifecycle"))
				continue;
			for (java.lang.reflect.Field f : c.getDeclaredFields()) {
				if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive())
					continue;
				try {
					f.setAccessible(true);
					findObjects(f.get(o), want, depth - 1, seen, path + "." + f.getName(), found);
				} catch (Throwable t) {
					// inaccessible: skip
				}
			}
		}
	}

	static void collectByClassName(View v, String name, java.util.List<View> out) {
		if (v.getClass().getName().equals(name) || v.getClass().getSimpleName().equals(name))
			out.add(v);
		if (v instanceof android.view.ViewGroup) {
			android.view.ViewGroup g = (android.view.ViewGroup) v;
			for (int i = 0; i < g.getChildCount(); i++)
				collectByClassName(g.getChildAt(i), name, out);
		}
	}

	/** the declared fields of o's class and its app superclasses (stops at android.* / java.*), with values */
	private static void dumpFields(String prefix, Object o, int depth, java.util.IdentityHashMap<Object, Boolean> seen) {
		if (o == null || seen.containsKey(o))
			return;
		seen.put(o, Boolean.TRUE);
		// an androidx object (a Compose interop holder, say) shows its androidx fields too
		final boolean androidxObject = o.getClass().getName().startsWith("androidx.");
		for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
			String cn = c.getName();
			if (cn.startsWith("android.") || cn.startsWith("java.") || (cn.startsWith("androidx.") && !androidxObject))
				break;
			for (java.lang.reflect.Field f : c.getDeclaredFields()) {
				if (java.lang.reflect.Modifier.isStatic(f.getModifiers()))
					continue;
				Object val;
				try {
					f.setAccessible(true);
					val = f.get(o);
				} catch (Throwable t) {
					val = "<" + t + ">";
				}
				String vs;
				try {
					vs = String.valueOf(val);
				} catch (Throwable t) {
					vs = "<toString: " + t + ">";
				}
				if (vs.length() > 3000)
					vs = vs.substring(0, 3000) + "...";
				Log.i(TAG, prefix + " " + cn + "." + f.getName() + " (" + f.getType().getSimpleName() + ") = " + vs);
				if (depth > 0 && val != null && !f.getType().isPrimitive()) {
					String vcn = val.getClass().getName();
					if (vcn.startsWith("com.waze."))
						dumpFields(prefix + "  " + f.getName() + ":", val, depth - 1, seen);
				}
			}
		}
	}

	static void collectByName(View v, String simpleName, java.util.List<View> out) {
		if (v.getClass().getSimpleName().equals(simpleName))
			out.add(v);
		if (v instanceof android.view.ViewGroup) {
			android.view.ViewGroup g = (android.view.ViewGroup) v;
			for (int i = 0; i < g.getChildCount(); i++)
				collectByName(g.getChildAt(i), simpleName, out);
		}
	}

	/** A daemon thread that logs the main thread's Java stack when /tmp/atl-stack appears: works while the main thread is busy. */
	private static boolean sStackDumper;
	private static synchronized void installMainStackDumper() {
		if (sStackDumper)
			return;
		sStackDumper = true;
		Thread t = new Thread("ViewDump-stack") {
			@Override public void run() {
				java.io.File trigger = new java.io.File("/tmp/atl-stack");
				java.io.File triggerAll = new java.io.File("/tmp/atl-stack-all");
				while (true) {
					try { Thread.sleep(300); } catch (InterruptedException e) { return; }
					if (triggerAll.exists()) {
						// "/tmp/atl-stack-all": every thread's top frames (who waits for whom)
						triggerAll.delete();
						for (java.util.Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
							Thread th = e.getKey();
							StackTraceElement[] st = e.getValue();
							if (st.length == 0)
								continue;
							Log.i(TAG, "allstack: \"" + th.getName() + "\" state=" + th.getState() + " frames=" + st.length);
							for (int i = 0; i < Math.min(st.length, 10); i++)
								Log.i(TAG, "allstack:     at " + st[i]);
						}
					}
					if (!trigger.exists())
						continue;
					trigger.delete();
					Thread main = Looper.getMainLooper().getThread();
					StackTraceElement[] st = main.getStackTrace();
					Log.i(TAG, "mainstack: state=" + main.getState() + " frames=" + st.length);
					for (int i = 0; i < Math.min(st.length, 48); i++)
						Log.i(TAG, "mainstack:   at " + st[i]);
				}
			}
		};
		t.setDaemon(true);
		t.start();
	}
}
