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

package android.app;

import android.R;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.DialogInterface;
import android.content.res.TypedArray;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;
import android.window.OnBackInvokedDispatcher;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * teslalinux: AOSP's Dialog (android14-release) on ATL's dialog layers.
 *
 * <p>AOSP adds the dialog's decor to the WindowManager of the context it was created with, so the
 * dialog window belongs to that activity's token: it is shown while the activity is visible
 * (between onStart and onStop), hidden while it is stopped, and removed ("leaked window") when the
 * activity is destroyed. ATL shows a dialog as a layer of the main window's GtkOverlay
 * (android_app_Dialog.c, tl_dialog_overlay), above every activity page, so the token rule is applied
 * here: {@link Activity} reports its visibility and destruction to {@link #tlTokenVisibility} and
 * {@link #tlTokenDestroyed}. Without it a dialog of an activity that started another one stayed on
 * top of the new activity, and dialogs of finished activities piled up.
 *
 * <p>As in AOSP, the dialog's context is a ContextThemeWrapper with the dialog theme
 * (android:dialogTheme of the caller's theme when no theme is given), and the cancel, dismiss and
 * show listeners run from messages posted to the main thread after the window change.
 */
public class Dialog implements DialogInterface, Window.Callback, KeyEvent.Callback {
	private static final String TAG = "Dialog";

	/* ---- ATL native dialog layer (android_app_Dialog.c) ---- */
	protected long nativePtr;

	protected native long nativeInit();
	private native void nativeSetTitle(long ptr, String title);
	private native void nativeSetContentView(long ptr, long widget);
	private native void nativeShow(long ptr);
	private native void nativeClose(long ptr);
	private native boolean nativeIsShowing(long ptr);

	private Activity mOwnerActivity;

	final Context mContext;
	final Window mWindow;

	View mDecor;

	protected boolean mCancelable = true;

	private String mCancelAndDismissTaken;
	private Message mCancelMessage;
	private Message mDismissMessage;
	private Message mShowMessage;

	private OnKeyListener mOnKeyListener;

	private boolean mCreated = false;
	private boolean mShowing = false;
	private boolean mCanceled = false;

	private final Handler mHandler = new Handler(Looper.getMainLooper());

	private static final int DISMISS = 0x43;
	private static final int CANCEL = 0x44;
	private static final int SHOW = 0x45;

	private final Handler mListenersHandler;

	private final Runnable mDismissAction = this::dismissDialog;

	/* AOSP Window.mCloseOnTouchOutside: set by setCanceledOnTouchOutside, else from the theme's
	 * android:windowCloseOnTouchOutside when the window is shown (PhoneWindow.generateLayout) */
	private boolean mCloseOnTouchOutside;
	private boolean mCloseOnTouchOutsideSet;

	private final KeyEvent.DispatcherState mKeyDispatchState = new KeyEvent.DispatcherState();

	/* the token rule (see the class comment): hidden while the token activity is stopped, the layer
	 * removed for good once it is destroyed */
	private boolean tlTokenHidden;
	private boolean tlWindowLeaked;
	/* Dialog.hide(): the decor is GONE and the layer (with its dim) is taken off the window */
	private boolean tlHiddenByApp;

	private TlBackDispatcher mBackDispatcher;

	public Dialog(Context context) {
		this(context, 0, true);
	}

	public Dialog(Context context, int themeResId) {
		this(context, themeResId, true);
	}

	Dialog(Context context, int themeResId, boolean createContextThemeWrapper) {
		if (createContextThemeWrapper) {
			if (themeResId == 0) {
				final TypedValue outValue = new TypedValue();
				context.getTheme().resolveAttribute(R.attr.dialogTheme, outValue, true);
				themeResId = outValue.resourceId;
			}
			mContext = themeResId != 0 ? new ContextThemeWrapper(context, themeResId) : context;
		} else {
			mContext = context;
		}

		mWindow = new Window(mContext, this);
		nativePtr = nativeInit();
		mWindow.set_native_window(nativePtr);

		mListenersHandler = new ListenersHandler(this);
	}

	protected Dialog(Context context, boolean cancelable, OnCancelListener cancelListener) {
		this(context);
		mCancelable = cancelable;
		setOnCancelListener(cancelListener);
	}

	public final Context getContext() {
		return mContext;
	}

	/** AOSP: the action bar of a window with FEATURE_ACTION_BAR; ATL's dialog windows have none. */
	public ActionBar getActionBar() {
		return null;
	}

	public final void setOwnerActivity(Activity activity) {
		mOwnerActivity = activity;
	}

	public final Activity getOwnerActivity() {
		return mOwnerActivity;
	}

	/** AOSP: the decor is in the window and VISIBLE (hide() makes it GONE). */
	public boolean isShowing() {
		return mDecor == null ? false : mDecor.getVisibility() == View.VISIBLE;
	}

	public void create() {
		if (!mCreated) {
			dispatchOnCreate(null);
		}
	}

	public void show() {
		if (System.getenv("ATL_DEBUG_DIALOG_STACK") != null)
			new Throwable("Dialog.show() called from").printStackTrace(System.out);
		/* AOSP requires the UI thread; ATL posts a show from another thread there */
		if (Looper.myLooper() != Looper.getMainLooper()) {
			mHandler.post(this::show);
			return;
		}

		if (mShowing) {
			if (mDecor != null) {
				mDecor.setVisibility(View.VISIBLE);
				tlHiddenByApp = false;
				tlUpdateLayer();
			}
			return;
		}

		Activity token = tlTokenActivity();
		if (token != null && token.isDestroyed()) {
			/* AOSP: WindowManagerGlobal.addView with a dead token */
			throw new WindowManager.BadTokenException("Unable to add window -- token for " + token + " is not valid; is your activity running?");
		}

		mCanceled = false;

		if (!mCreated) {
			dispatchOnCreate(null);
		} else {
			// Fill the DecorView in on any configuration changes that
			// may have occured while it was removed from the WindowManager.
			mWindow.getDecorView().dispatchConfigurationChanged(mContext.getResources().getConfiguration());
		}

		onStart();
		mDecor = mWindow.getDecorView();

		// Read the size of the main window. Floating dialogs should be smaller as specified in the windowMinWidth* attributes.
		// Non-floating are typically constructed with MATCH_PARENT layout params and thus get the exact size of the main window.
		// Most non-floating dialogs are technically dialogs, but are expected to behave more like full size activities.
		Rect displayFrame = new Rect();
		mDecor.getWindowVisibleDisplayFrame(displayFrame);

		TypedArray a = mContext.obtainStyledAttributes(R.styleable.Window);
		float windowWidthFraction = 1;
		if (a.getBoolean(R.styleable.Window_windowIsFloating, false)) {
			if (displayFrame.width() > displayFrame.height())
				windowWidthFraction = a.getFraction(R.styleable.Window_windowMinWidthMajor, 1, 1, 1);
			else
				windowWidthFraction = a.getFraction(R.styleable.Window_windowMinWidthMinor, 1, 1, 1);
		}
		if (!mCloseOnTouchOutsideSet)
			mCloseOnTouchOutside = a.getBoolean(R.styleable.Window_windowCloseOnTouchOutside, false);
		a.recycle();

		LayoutParams lp = mWindow.getAttributes();
		int widthMeasureSpec = View.MeasureSpec.makeMeasureSpec(lp.width >= 0 ? lp.width : (int)(displayFrame.width() * windowWidthFraction),
		                                                        lp.width == LayoutParams.WRAP_CONTENT ? View.MeasureSpec.AT_MOST : View.MeasureSpec.EXACTLY);
		int heightMeasureSpec = View.MeasureSpec.makeMeasureSpec(lp.height >= 0 ? lp.height : (int)(displayFrame.height()),
		                                                         lp.height == LayoutParams.WRAP_CONTENT ? View.MeasureSpec.AT_MOST : View.MeasureSpec.EXACTLY);
		mDecor.internalSetDefaultMeasureSpec(widthMeasureSpec, heightMeasureSpec);

		/* AOSP WindowManager.addView: the window is added now, visible while its token is */
		tlTokenHidden = token != null && !token.tlTokenVisible;
		tlHiddenByApp = false;
		tlWindowLeaked = false;
		mDecor.setVisibility(View.VISIBLE);
		tlUpdateLayer();

		mShowing = true;
		tlRegisterShowing(true);
		if (!tlTokenHidden)
			tlDialogWindowFocus(true);

		sendShowMessage();
	}

	/** AOSP: hides the dialog without dismissing it (the decor becomes GONE). */
	public void hide() {
		if (mDecor != null) {
			mDecor.setVisibility(View.GONE);
			tlHiddenByApp = true;
			tlUpdateLayer();
		}
	}

	@Override
	public void dismiss() {
		if (Looper.myLooper() == mHandler.getLooper()) {
			dismissDialog();
		} else {
			mHandler.post(mDismissAction);
		}
	}

	void dismissDialog() {
		if (mDecor == null || !mShowing) {
			return;
		}

		if (tlWindowLeaked) {
			/* AOSP: removeViewImmediate on a window that WindowManagerGlobal.closeAll already removed
			 * throws IllegalArgumentException after this cleanup; ATL logs it instead of throwing so
			 * a late dismiss() from an app callback does not end the process. */
			Log.e(TAG, "dismiss(): " + mDecor + " not attached to window manager (its activity was destroyed)");
		}

		try {
			nativeClose(nativePtr);
			android.view.inputmethod.InputMethodManager.tlWindowRemoved(mDecor);
		} finally {
			boolean hadFocus = !tlTokenHidden && !tlWindowLeaked;
			mDecor = null;
			onStop();
			mShowing = false;
			tlRegisterShowing(false);
			if (hadFocus)
				tlDialogWindowFocus(false);

			sendDismissMessage();
		}
	}

	private void sendDismissMessage() {
		if (mDismissMessage != null) {
			// Obtain a new message so this dialog can be re-used
			Message.obtain(mDismissMessage).sendToTarget();
		}
	}

	private void sendShowMessage() {
		if (mShowMessage != null) {
			// Obtain a new message so this dialog can be re-used
			Message.obtain(mShowMessage).sendToTarget();
		}
	}

	void dispatchOnCreate(Bundle savedInstanceState) {
		if (!mCreated) {
			onCreate(savedInstanceState);
			mCreated = true;
		}
	}

	protected void onCreate(Bundle savedInstanceState) {
	}

	/** AOSP: only turns off the action bar's show/hide animation; ATL's dialogs have no action bar. */
	protected void onStart() {
	}

	/** AOSP: only re-enables the action bar's show/hide animation; ATL's dialogs have no action bar. */
	protected void onStop() {
	}

	private static final String DIALOG_SHOWING_TAG = "android:dialogShowing";
	private static final String DIALOG_HIERARCHY_TAG = "android:dialogHierarchy";

	/**
	 * AOSP saves the showing flag and the window's view hierarchy state; ATL's Window has no
	 * saveHierarchyState, so only the views' own state is not carried over.
	 */
	public Bundle onSaveInstanceState() {
		Bundle bundle = new Bundle();
		bundle.putBoolean(DIALOG_SHOWING_TAG, mShowing);
		return bundle;
	}

	public void onRestoreInstanceState(Bundle savedInstanceState) {
		if (savedInstanceState.getBoolean(DIALOG_SHOWING_TAG)) {
			show();
		}
	}

	public Window getWindow() {
		return mWindow;
	}

	/** AOSP: the window's focused view. */
	public View getCurrentFocus() {
		View decor = mWindow != null ? mWindow.getDecorView() : null;
		return decor != null ? decor.findFocus() : null;
	}

	public <T extends View> T findViewById(int id) {
		return (T) mWindow.findViewById(id);
	}

	public final <T extends View> T requireViewById(int id) {
		T view = findViewById(id);
		if (view == null) {
			throw new IllegalArgumentException("ID does not reference a View inside this Dialog");
		}
		return view;
	}

	public void setContentView(int layoutResID) {
		setContentView(LayoutInflater.from(mContext).inflate(layoutResID, null));
	}

	public void setContentView(View view) {
		mWindow.setContentView(view);
	}

	public void setContentView(View view, ViewGroup.LayoutParams params) {
		view.setLayoutParams(params);
		mWindow.setContentView(view);
	}

	public void addContentView(View view, ViewGroup.LayoutParams params) {
		view.setLayoutParams(params);
		((ViewGroup) mWindow.getDecorView()).addView(view);
	}

	public void setTitle(CharSequence title) {
		mWindow.getAttributes().setTitle(title);
		nativeSetTitle(nativePtr, String.valueOf(title));
	}

	public void setTitle(int titleId) {
		setTitle(mContext.getText(titleId));
	}

	public boolean onKeyDown(int keyCode, KeyEvent event) {
		if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
			event.startTracking();
			return true;
		}
		return false;
	}

	public boolean onKeyLongPress(int keyCode, KeyEvent event) {
		return false;
	}

	public boolean onKeyUp(int keyCode, KeyEvent event) {
		if ((keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE)
		    && event.isTracking() && !event.isCanceled()) {
			/* AOSP: with OnBackInvokedCallback enabled the window's top callback gets BACK */
			android.window.OnBackInvokedCallback callback = mBackDispatcher != null ? mBackDispatcher.getTopCallback() : null;
			if (callback != null)
				callback.onBackInvoked();
			else
				onBackPressed();
			return true;
		}
		return false;
	}

	public boolean onKeyMultiple(int keyCode, int repeatCount, KeyEvent event) {
		return false;
	}

	public void onBackPressed() {
		if (mCancelable) {
			cancel();
		}
	}

	public boolean onKeyShortcut(int keyCode, KeyEvent event) {
		return false;
	}

	/** AOSP: a touch outside the window cancels a cancelable dialog that closes on touch outside. */
	public boolean onTouchEvent(MotionEvent event) {
		if (mCancelable && mShowing && shouldCloseOnTouch(event)) {
			cancel();
			return true;
		}
		return false;
	}

	/* AOSP Window.shouldCloseOnTouch / isOutOfBounds */
	private boolean shouldCloseOnTouch(MotionEvent event) {
		View decor = mDecor;
		if (!mCloseOnTouchOutside || decor == null)
			return false;
		final int action = event.getAction();
		if (action == MotionEvent.ACTION_OUTSIDE)
			return true;
		if (action != MotionEvent.ACTION_UP)
			return false;
		final int x = (int) event.getX();
		final int y = (int) event.getY();
		final int slop = ViewConfiguration.get(mContext).getScaledWindowTouchSlop();
		return (x < -slop) || (y < -slop) || (x > (decor.getWidth() + slop)) || (y > (decor.getHeight() + slop));
	}

	public boolean onTrackballEvent(MotionEvent event) {
		return false;
	}

	public boolean onGenericMotionEvent(MotionEvent event) {
		return false;
	}

	@Override
	public void onWindowAttributesChanged(WindowManager.LayoutParams params) {
	}

	@Override
	public void onContentChanged() {
	}

	@Override
	public void onWindowFocusChanged(boolean hasFocus) {
	}

	@Override
	public void onAttachedToWindow() {
	}

	@Override
	public void onDetachedFromWindow() {
	}

	/** AOSP: the key listener, then the window's views (GTK already gave the key to the focused view), then this dialog's callbacks. */
	@Override
	public boolean dispatchKeyEvent(KeyEvent event) {
		if ((mOnKeyListener != null) && (mOnKeyListener.onKey(this, event.getKeyCode(), event))) {
			return true;
		}
		if (mWindow.superDispatchKeyEvent(event)) {
			return true;
		}
		return event.dispatch(this, mDecor != null ? mKeyDispatchState : null, this);
	}

	@Override
	public boolean dispatchKeyShortcutEvent(KeyEvent event) {
		if (mWindow.superDispatchKeyShortcutEvent(event)) {
			return true;
		}
		return onKeyShortcut(event.getKeyCode(), event);
	}

	@Override
	public boolean dispatchTouchEvent(MotionEvent ev) {
		if (mWindow.superDispatchTouchEvent(ev)) {
			return true;
		}
		return onTouchEvent(ev);
	}

	@Override
	public boolean dispatchTrackballEvent(MotionEvent ev) {
		if (mWindow.superDispatchTrackballEvent(ev)) {
			return true;
		}
		return onTrackballEvent(ev);
	}

	@Override
	public boolean dispatchGenericMotionEvent(MotionEvent ev) {
		if (mWindow.superDispatchGenericMotionEvent(ev)) {
			return true;
		}
		return onGenericMotionEvent(ev);
	}

	@Override
	public View onCreatePanelView(int featureId) {
		return null;
	}

	@Override
	public boolean onCreatePanelMenu(int featureId, Menu menu) {
		if (featureId == Window.FEATURE_OPTIONS_PANEL) {
			return onCreateOptionsMenu(menu);
		}
		return false;
	}

	@Override
	public boolean onPreparePanel(int featureId, View view, Menu menu) {
		if (featureId == Window.FEATURE_OPTIONS_PANEL && menu != null) {
			return onPrepareOptionsMenu(menu) && menu.hasVisibleItems();
		}
		return true;
	}

	@Override
	public boolean onMenuOpened(int featureId, Menu menu) {
		return true;
	}

	@Override
	public boolean onMenuItemSelected(int featureId, MenuItem item) {
		return false;
	}

	@Override
	public void onPanelClosed(int featureId, Menu menu) {
	}

	public boolean onCreateOptionsMenu(Menu menu) {
		return true;
	}

	public boolean onPrepareOptionsMenu(Menu menu) {
		return true;
	}

	public boolean onOptionsItemSelected(MenuItem item) {
		return false;
	}

	public void onOptionsMenuClosed(Menu menu) {
	}

	/** AOSP: SearchManager is asked to start a search; this platform has no search service (no SearchManager UI), so no search is started. */
	@Override
	public boolean onSearchRequested() {
		return false;
	}

	public final boolean requestWindowFeature(int featureId) {
		return mWindow.requestFeature(featureId);
	}

	public void takeKeyEvents(boolean get) {
		View decor = mWindow.getDecorView();
		if (decor != null)
			decor.setFocusable(get);
	}

	public void setCancelable(boolean flag) {
		mCancelable = flag;
	}

	public void setCanceledOnTouchOutside(boolean cancel) {
		if (cancel && !mCancelable) {
			mCancelable = true;
		}
		mCloseOnTouchOutside = cancel;
		mCloseOnTouchOutsideSet = true;
	}

	@Override
	public void cancel() {
		if (!mCanceled && mCancelMessage != null) {
			mCanceled = true;
			// Obtain a new message so this dialog can be re-used
			Message.obtain(mCancelMessage).sendToTarget();
		}
		dismiss();
	}

	public void setOnCancelListener(OnCancelListener listener) {
		if (mCancelAndDismissTaken != null) {
			throw new IllegalStateException(
			    "OnCancelListener is already taken by "
			    + mCancelAndDismissTaken + " and can not be replaced.");
		}
		if (listener != null) {
			mCancelMessage = mListenersHandler.obtainMessage(CANCEL, listener);
		} else {
			mCancelMessage = null;
		}
	}

	public void setCancelMessage(Message msg) {
		mCancelMessage = msg;
	}

	public void setOnDismissListener(OnDismissListener listener) {
		if (mCancelAndDismissTaken != null) {
			throw new IllegalStateException(
			    "OnDismissListener is already taken by "
			    + mCancelAndDismissTaken + " and can not be replaced.");
		}
		if (listener != null) {
			mDismissMessage = mListenersHandler.obtainMessage(DISMISS, listener);
		} else {
			mDismissMessage = null;
		}
	}

	public void setOnShowListener(OnShowListener listener) {
		if (listener != null) {
			mShowMessage = mListenersHandler.obtainMessage(SHOW, listener);
		} else {
			mShowMessage = null;
		}
	}

	public void setDismissMessage(Message msg) {
		mDismissMessage = msg;
	}

	/** @hide AOSP: lets a DialogFragment own the cancel and dismiss listeners. */
	public boolean takeCancelAndDismissListeners(String msg, OnCancelListener cancel, OnDismissListener dismiss) {
		if (mCancelAndDismissTaken != null) {
			mCancelAndDismissTaken = null;
		} else if (mCancelMessage != null || mDismissMessage != null) {
			return false;
		}
		setOnCancelListener(cancel);
		setOnDismissListener(dismiss);
		mCancelAndDismissTaken = msg;
		return true;
	}

	public void setOnKeyListener(OnKeyListener onKeyListener) {
		mOnKeyListener = onKeyListener;
	}

	/** AOSP: the dispatcher of this dialog's window (see TlBackDispatcher for when callbacks are used). */
	public OnBackInvokedDispatcher getOnBackInvokedDispatcher() {
		if (mBackDispatcher == null)
			mBackDispatcher = new TlBackDispatcher(mContext);
		return mBackDispatcher;
	}

	private static final class ListenersHandler extends Handler {
		private final WeakReference<DialogInterface> mDialog;

		public ListenersHandler(Dialog dialog) {
			super(Looper.getMainLooper());
			mDialog = new WeakReference<>(dialog);
		}

		@Override
		public void handleMessage(Message msg) {
			switch (msg.what) {
				case DISMISS:
					((OnDismissListener) msg.obj).onDismiss(mDialog.get());
					break;
				case CANCEL:
					((OnCancelListener) msg.obj).onCancel(mDialog.get());
					break;
				case SHOW:
					((OnShowListener) msg.obj).onShow(mDialog.get());
					break;
			}
		}
	}

	/* ---------------- teslalinux: ATL glue ---------------- */

	/** The dim behind the dialog was touched (native layer): AOSP's ACTION_OUTSIDE reaching onTouchEvent. */
	private void tlTouchOutside() {
		if (mCancelable && mShowing && mCloseOnTouchOutside) {
			cancel();
		}
	}

	/** The activity whose window token the dialog's window uses (AOSP: the context's WindowManager). */
	Activity tlTokenActivity() {
		Context c = mContext;
		while (c instanceof ContextWrapper && !(c instanceof Activity))
			c = ((ContextWrapper) c).getBaseContext();
		return c instanceof Activity ? (Activity) c : null;
	}

	/** The native layer is on the window while the dialog is added, not hidden by hide(), and its token is visible. */
	private void tlUpdateLayer() {
		if (mDecor != null && !tlHiddenByApp && !tlTokenHidden && !tlWindowLeaked)
			nativeShow(nativePtr);
		else
			nativeClose(nativePtr);
	}

	/* the dialogs currently showing (view dump / probes walk their decors like the activity's) */
	private static final List<Dialog> tlShowingDialogs = new ArrayList<>();

	public static List<Dialog> tlShowingDialogs() {
		synchronized (tlShowingDialogs) {
			return new ArrayList<>(tlShowingDialogs);
		}
	}

	private void tlRegisterShowing(boolean showing) {
		synchronized (tlShowingDialogs) {
			tlShowingDialogs.remove(this);
			if (showing)
				tlShowingDialogs.add(this);
		}
	}

	/** AOSP: the windows of an activity token are shown while the activity is visible (onStart..onStop). */
	static void tlTokenVisibility(Activity activity, boolean visible) {
		for (Dialog d : tlShowingDialogs()) {
			if (d.tlTokenActivity() != activity || d.tlWindowLeaked)
				continue;
			boolean wasHidden = d.tlTokenHidden;
			d.tlTokenHidden = !visible;
			d.tlUpdateLayer();
			if (wasHidden && visible && d.isShowing())
				d.tlDialogWindowFocus(true);
		}
	}

	/** AOSP ActivityThread.handleDestroyActivity: WindowManagerGlobal.closeAll removes the windows the activity leaked. */
	static void tlTokenDestroyed(Activity activity) {
		for (Dialog d : tlShowingDialogs()) {
			if (d.tlTokenActivity() != activity || d.tlWindowLeaked)
				continue;
			Log.e("WindowManager", "Activity " + activity.getClass().getName() + " has leaked window " + d.mDecor + " that was originally added here");
			d.tlWindowLeaked = true;
			d.nativeClose(d.nativePtr);
			android.view.inputmethod.InputMethodManager.tlWindowRemoved(d.mDecor);
		}
	}

	/** AOSP: a showing dialog's window has the focus; the activity's window loses it and gets it back on dismiss. */
	private void tlDialogWindowFocus(boolean showing) {
		View decor = mWindow != null ? mWindow.getDecorView() : null;
		if (decor != null)
			decor.dispatchWindowFocusChanged(showing);
		Activity a = tlTokenActivity();
		if (a != null && !a.isDestroyed())
			a.tlWindowFocusChanged(!showing);
	}
}
