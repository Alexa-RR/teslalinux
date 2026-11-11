package android.view;

import android.graphics.Rect;
import java.util.ArrayList;

/**
 * teslalinux: the window manager for the windows an app adds itself (PopupWindow, Compose popups,
 * toasts and other panels; activities and dialogs have their own paths). On Android each one is a
 * window with its own ViewRootImpl and surface, sized and placed by the window manager from its
 * WindowManager.LayoutParams. Here each window is a layer of the main GtkWindow (native
 * TlWindowLayer, android_view_WindowManagerImpl.c), stacked above everything added before it, the
 * way native-dialog-overlay.sh stacks dialogs:
 * - size: the root view is measured like ViewRootImpl.measureHierarchy (MATCH_PARENT exactly the
 *   display frame, WRAP_CONTENT at most the display frame, a pixel size exactly that size);
 * - position: Gravity.apply with x/y (and the margins) in the display frame, then clamped to the
 *   display unless FLAG_LAYOUT_NO_LIMITS (WindowState.computeFrame / Gravity.applyDisplay); a frame
 *   smaller than the measured size measures the view again at the frame size (ViewRootImpl relayout);
 * - input: FLAG_NOT_TOUCHABLE windows let every touch through; a touch-modal window (focusable and
 *   no FLAG_NOT_TOUCH_MODAL) takes the touches outside its frame too; FLAG_WATCH_OUTSIDE_TOUCH
 *   windows get ACTION_OUTSIDE for a touch elsewhere; keys go to the topmost focusable window;
 * - FLAG_DIM_BEHIND dims everything below by dimAmount.
 * The layout runs on GTK's allocation of the layer, so a requestLayout of the root view (which
 * queues a GTK resize) relayouts the window, like ViewRootImpl.requestLayout.
 */
public class WindowManagerImpl implements WindowManager, ViewManager {

	private static final String TAG = "WindowManagerImpl";
	private static final boolean DEBUG = System.getenv("ATL_DEBUG_WINDOWS") != null;

	/**
	 * The parent of a window's root view: what ViewRootImpl is on Android. Layout, drawing and
	 * traversals are the GTK widget tree's job here, so the root parent only has to give the AOSP
	 * answers at the top of the chain (nested scrolling stops at the root, invalidation needs no
	 * further scheduling because every widget below already queued its own redraw).
	 */
	private static class WindowViewParent implements ViewParent {

		@Override
		public android.view.ViewParent getParent() {
			return null;
		}

		/** ViewRootImpl: whether a traversal with layout is pending; GTK lays out on its own frame clock. */
		@Override
		public boolean isLayoutRequested() {
			return false;
		}

		/** ViewRootImpl: nothing to intercept above the root. */
		@Override
		public void requestDisallowInterceptTouchEvent(boolean disallowIntercept) {
		}

		/** ViewRootImpl: the root never takes part in nested scrolling. */
		@Override
		public boolean onStartNestedScroll(View child, View target, int nestedScrollAxes) {
			return false;
		}

		@Override
		public boolean onNestedPreFling(View target, float velocityX, float velocityY) {
			return false;
		}

		@Override
		public boolean onNestedFling(View target, float velocityX, float velocityY, boolean consumed) {
			return false;
		}

		@Override
		public void onNestedScrollAccepted(View child, View target, int nestedScrollAxes) {
		}

		@Override
		public void onNestedPreScroll(View target, int dx, int dy, int[] consumed) {
		}

		@Override
		public void onNestedScroll(View target, int dxConsumed, int dyConsumed, int dxUnconsumed, int dyUnconsumed) {
		}

		@Override
		public void onStopNestedScroll(View target) {
		}

		/** ViewRootImpl.onDescendantInvalidated: invalidate(), the next frame redraws the window. A
		 * View.invalidate() has queued its widget already; Compose's layer moves and property changes
		 * (RenderNode position, translation, alpha) report only this, so the target's widget is queued
		 * here, or its new layer state would show only when something else redraws. */
		@Override
		public void onDescendantInvalidated(View child, View target) {
			if (target != null)
				target.tlQueueWindowRedraw();
			else if (child != null)
				child.tlQueueWindowRedraw();
		}

		/** ViewRootImpl.invalidateChild: ends in invalidateChildInParent. */
		@Override
		public void invalidateChild(View child, android.graphics.Rect dirty) {
			invalidateChildInParent(null, dirty);
		}

		/** ViewRootImpl.invalidateChildInParent: schedules the frame (GTK already did) and ends the chain. */
		@Override
		public ViewParent invalidateChildInParent(int[] location, android.graphics.Rect dirty) {
			return null;
		}
	}

	/** One added window: its root view, its attributes and where the last layout put it. */
	private static final class AddedWindow {
		final View view;
		final WindowManager.LayoutParams params = new WindowManager.LayoutParams();
		final Rect frame = new Rect();
		long layer;

		AddedWindow(View view) {
			this.view = view;
		}
	}

	/** every window added through any WindowManagerImpl of the process, oldest (lowest) first */
	private static final ArrayList<AddedWindow> sWindows = new ArrayList<>();

	private static AddedWindow findWindow(View view) {
		for (AddedWindow w : sWindows) {
			if (w.view == view)
				return w;
		}
		return null;
	}

	public android.view.Display getDefaultDisplay() {
		return new android.view.Display();
	}

	/** AOSP WindowManagerGlobal.addView: the view becomes the root of a new window. */
	@Override
	public void addView(View view, android.view.ViewGroup.LayoutParams params) {
		if (view == null)
			throw new IllegalArgumentException("view must not be null");
		if (!(params instanceof WindowManager.LayoutParams))
			throw new IllegalArgumentException("Params must be WindowManager.LayoutParams");
		if (findWindow(view) != null)
			throw new IllegalStateException("View " + view + " has already been added to the window manager.");
		AddedWindow w = new AddedWindow(view);
		w.params.copyFrom((WindowManager.LayoutParams) params);
		if (DEBUG)
			android.util.Log.i(TAG, "addView " + view.getClass().getName() + " " + describe(w.params));
		view.setLayoutParams(params);
		view.parent = new WindowViewParent();
		sWindows.add(w);
		w.layer = native_addWindow(view, view.widget, w.params.flags, w.params.dimAmount);
		view.onAttachedToWindow();
	}

	/** AOSP: new attributes for an added window; applied at its next layout (ViewRootImpl.setLayoutParams). */
	@Override
	public void updateViewLayout(View view, android.view.ViewGroup.LayoutParams params) {
		if (!(params instanceof WindowManager.LayoutParams))
			throw new IllegalArgumentException("Params must be WindowManager.LayoutParams");
		AddedWindow w = findWindow(view);
		if (w == null)
			throw new IllegalArgumentException("View=" + view + " not attached to window manager");
		w.params.copyFrom((WindowManager.LayoutParams) params);
		if (DEBUG)
			android.util.Log.i(TAG, "updateViewLayout " + view.getClass().getName() + " " + describe(w.params));
		view.setLayoutParams(params);
		native_updateWindow(w.layer, w.params.flags, w.params.dimAmount);
	}

	@Override
	public void removeView(View view) {
		AddedWindow w = findWindow(view);
		if (w == null)
			throw new IllegalArgumentException("View=" + view + " not attached to window manager");
		if (DEBUG)
			android.util.Log.i(TAG, "removeView " + view.getClass().getName());
		sWindows.remove(w);
		view.onDetachedFromWindow();
		native_removeWindow(w.layer);
		w.layer = 0;
		view.parent = null;
	}

	@Override
	public void removeViewImmediate(View view) {
		removeView(view);
	}

	private static String describe(WindowManager.LayoutParams p) {
		return "type=" + p.type + " " + p.width + "x" + p.height + " at " + p.x + "," + p.y + " gravity=0x" + Integer.toHexString(p.gravity)
		     + " flags=0x" + Integer.toHexString(p.flags);
	}

	/** ViewRootImpl.getRootMeasureSpec */
	private static int getRootMeasureSpec(int windowSize, int rootDimension) {
		switch (rootDimension) {
			case ViewGroup.LayoutParams.MATCH_PARENT:
				return View.MeasureSpec.makeMeasureSpec(windowSize, View.MeasureSpec.EXACTLY);
			case ViewGroup.LayoutParams.WRAP_CONTENT:
				return View.MeasureSpec.makeMeasureSpec(windowSize, View.MeasureSpec.AT_MOST);
			default:
				return View.MeasureSpec.makeMeasureSpec(rootDimension, View.MeasureSpec.EXACTLY);
		}
	}

	/**
	 * Called by the layer's GTK allocation with the display frame (the main window's size): measures
	 * the root view and returns the window frame in out[0..3] (x, y, width, height).
	 */
	private static void tlLayoutWindow(View view, int displayWidth, int displayHeight, int[] out) {
		AddedWindow w = findWindow(view);
		WindowManager.LayoutParams p = w != null ? w.params : (WindowManager.LayoutParams) view.getLayoutParams();
		view.measure(getRootMeasureSpec(displayWidth, p.width), getRootMeasureSpec(displayHeight, p.height));
		int width = p.width >= 0 ? p.width : (p.width == ViewGroup.LayoutParams.MATCH_PARENT ? displayWidth : view.getMeasuredWidth());
		int height = p.height >= 0 ? p.height : (p.height == ViewGroup.LayoutParams.MATCH_PARENT ? displayHeight : view.getMeasuredHeight());
		Rect display = new Rect(0, 0, displayWidth, displayHeight);
		Rect frame = new Rect();
		Gravity.apply(p.gravity, width, height, display,
		              (int) (p.x + p.horizontalMargin * displayWidth), (int) (p.y + p.verticalMargin * displayHeight), frame);
		if ((p.flags & WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS) == 0)
			Gravity.applyDisplay(p.gravity, display, frame);
		if (frame.width() != view.getMeasuredWidth() || frame.height() != view.getMeasuredHeight())
			view.measure(View.MeasureSpec.makeMeasureSpec(frame.width(), View.MeasureSpec.EXACTLY),
			             View.MeasureSpec.makeMeasureSpec(frame.height(), View.MeasureSpec.EXACTLY));
		if (w != null)
			w.frame.set(frame);
		out[0] = frame.left;
		out[1] = frame.top;
		out[2] = frame.width();
		out[3] = frame.height();
	}

	/**
	 * A pointer DOWN at (x, y) in the main window, before any view saw it: every window watching
	 * outside touches whose frame does not contain the point gets ACTION_OUTSIDE, in its own
	 * coordinates (the windows all belong to this app, so AOSP does not zero them).
	 */
	static void tlDispatchOutsideTouch(float x, float y) {
		ArrayList<AddedWindow> windows = new ArrayList<>(sWindows);
		for (AddedWindow w : windows) {
			if ((w.params.flags & WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH) == 0)
				continue;
			if (w.frame.contains((int) x, (int) y) || !sWindows.contains(w))
				continue;
			long now = android.os.SystemClock.uptimeMillis();
			MotionEvent ev = MotionEvent.obtain(now, now, MotionEvent.ACTION_OUTSIDE, x - w.frame.left, y - w.frame.top, 0);
			w.view.dispatchTouchEvent(ev);
			ev.recycle();
		}
	}

	/**
	 * A touch-modal window gets the touches outside its frame as well (AOSP: without
	 * FLAG_NOT_TOUCH_MODAL a focusable window consumes all pointer events); x/y are window-relative.
	 */
	private static void tlModalOutsideTouch(View view, int action, float x, float y) {
		long now = android.os.SystemClock.uptimeMillis();
		MotionEvent ev = MotionEvent.obtain(now, now, action, x, y, 0);
		view.dispatchTouchEvent(ev);
		ev.recycle();
	}

	/**
	 * A key, before any view saw it: AOSP delivers keys to the focused window, which is the topmost
	 * focusable one; an app-added focusable window above the activity takes them. True when such a
	 * window exists (its root got the key, handled or not, as ViewRootImpl does).
	 */
	static boolean tlDispatchKeyToFocusedWindow(KeyEvent event) {
		for (int i = sWindows.size() - 1; i >= 0; i--) {
			AddedWindow w = sWindows.get(i);
			if ((w.params.flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0)
				continue;
			w.view.dispatchKeyEvent(event);
			return true;
		}
		return false;
	}

	/* ---- cross-window blur (AOSP WindowManagerImpl / CrossWindowBlurListeners) ----
	 * AOSP: blur behind and between windows needs the compositor's background blur
	 * (ro.surface_flinger.supports_background_blur); without it isCrossWindowBlurEnabled() is
	 * false for good. The windows here are layers of one GTK window composited by GSK, which has
	 * no cross-window blur, so the state is false and never changes; a new listener is told the
	 * current state at once, on its executor, as CrossWindowBlurListeners.addListener does. */
	private static final java.util.HashMap<java.util.function.Consumer<Boolean>, java.util.concurrent.Executor> sBlurListeners = new java.util.HashMap<>();

	@Override
	public boolean isCrossWindowBlurEnabled() {
		return false;
	}

	@Override
	public void addCrossWindowBlurEnabledListener(java.util.function.Consumer<Boolean> listener) {
		final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
		addCrossWindowBlurEnabledListener(main::post, listener);
	}

	@Override
	public void addCrossWindowBlurEnabledListener(java.util.concurrent.Executor executor, java.util.function.Consumer<Boolean> listener) {
		if (executor == null)
			throw new IllegalArgumentException("executor cannot be null");
		if (listener == null)
			throw new IllegalArgumentException("listener cannot be null");
		synchronized (sBlurListeners) {
			if (sBlurListeners.containsKey(listener))
				return;
			sBlurListeners.put(listener, executor);
		}
		final boolean enabled = isCrossWindowBlurEnabled();
		executor.execute(() -> listener.accept(enabled));
	}

	@Override
	public void removeCrossWindowBlurEnabledListener(java.util.function.Consumer<Boolean> listener) {
		if (listener == null)
			throw new IllegalArgumentException("listener cannot be null");
		synchronized (sBlurListeners) {
			sBlurListeners.remove(listener);
		}
	}

	private static native long native_addWindow(View view, long widget, int flags, float dimAmount);
	private static native void native_updateWindow(long layer, int flags, float dimAmount);
	private static native void native_removeWindow(long layer);
}
