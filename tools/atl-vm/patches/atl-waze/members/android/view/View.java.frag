	/* teslalinux: View additions used by Compose / AppCompat at first layout */
	public static final int LAYER_TYPE_NONE = 0, LAYER_TYPE_SOFTWARE = 1, LAYER_TYPE_HARDWARE = 2;
	private int tlLayerType = LAYER_TYPE_NONE;
	public WindowInsets dispatchApplyWindowInsets(WindowInsets insets) { return onApplyWindowInsets(insets); }
	public WindowInsets onApplyWindowInsets(WindowInsets insets) { return insets; }
	public int getLayerType() { return tlLayerType; }
	public boolean isOpaque() { return false; }
	public boolean hasOverlappingRendering() { return true; }
	public long getUniqueDrawingId() { return System.identityHashCode(this); }
	public void resetPivot() {}
	public void addOnUnhandledKeyEventListener(OnUnhandledKeyEventListener listener) {}
	public void removeOnUnhandledKeyEventListener(OnUnhandledKeyEventListener listener) {}
	public android.graphics.Rect getClipBounds() { return null; }
	public boolean getClipBounds(android.graphics.Rect outRect) { return false; }
	public int getMeasuredHeightAndState() { return getMeasuredHeight(); }
	public boolean isImportantForAccessibility() { return true; }
	public boolean isImportantForAutofill() { return false; }
	public boolean isImportantForContentCapture() { return false; }
	public int getImportantForContentCapture() { return 0; }
	public void setImportantForContentCapture(int mode) {}
	public CharSequence getAccessibilityClassName() { return View.class.getName(); }
	public CharSequence getAccessibilityPaneTitle() { return null; }
	public CharSequence getStateDescription() { return null; }
	public void setStateDescription(CharSequence stateDescription) {}
	public android.window.OnBackInvokedDispatcher findOnBackInvokedDispatcher() { return null; }
	public boolean hasExplicitFocusable() { return isFocusable(); }
	public boolean isFocusedByDefault() { return false; }
	public void setFocusedByDefault(boolean isFocusedByDefault) {}
	public boolean isAccessibilityHeading() { return false; }
	public boolean isAccessibilityFocused() { return false; }
	public java.util.List<android.graphics.Rect> getSystemGestureExclusionRects() { return new java.util.ArrayList<>(); }
	public void setSystemGestureExclusionRects(java.util.List<android.graphics.Rect> rects) {}
	public java.util.List<android.graphics.Rect> getPreferKeepClearRects() { return new java.util.ArrayList<>(); }
	public void setPreferKeepClearRects(java.util.List<android.graphics.Rect> rects) {}
	public int getScrollIndicators() { return 0; }
	public void setScrollIndicators(int indicators) {}
	public int getLabelFor() { return -1; }
	public void dispatchStartTemporaryDetach() {}
	public void dispatchFinishTemporaryDetach() {}
	public void dispatchConfigurationChanged(android.content.res.Configuration newConfig) {}
	public void drawableHotspotChanged(float x, float y) {}
	public boolean restoreDefaultFocus() { return requestFocus(); }
	public void cancelDragAndDrop() {}
	public void addKeyboardNavigationClusters(java.util.Collection<View> views, int direction) {}
	public void addTouchables(java.util.ArrayList<View> views) {}
	public View keyboardNavigationClusterSearch(View currentCluster, int direction) { return null; }
	public ViewParent getParentForAccessibility() { return getParent(); }
	public void setAllowClickWhenDisabled(boolean clickableWhenDisabled) {}
	public boolean isPivotSet() { return false; }
	public android.view.accessibility.AccessibilityNodeProvider getAccessibilityNodeProvider() { return null; }

	/* ---- content capture (AOSP View.java, API 29). The content capture system service does not
	 * exist in this runtime, so the context has no ContentCaptureManager and the default session is
	 * null, exactly as on a device where content capture is unavailable. ---- */
	private android.view.contentcapture.ContentCaptureSession mContentCaptureSession;
	private boolean mContentCaptureSessionCached;

	public void setContentCaptureSession(android.view.contentcapture.ContentCaptureSession contentCaptureSession) {
		mContentCaptureSession = contentCaptureSession;
	}

	public final android.view.contentcapture.ContentCaptureSession getContentCaptureSession() {
		if (mContentCaptureSessionCached) {
			return mContentCaptureSession;
		}
		mContentCaptureSession = getAndCacheContentCaptureSession();
		mContentCaptureSessionCached = true;
		return mContentCaptureSession;
	}

	private android.view.contentcapture.ContentCaptureSession getAndCacheContentCaptureSession() {
		// First try the session explicitly set by setContentCaptureSession()
		if (mContentCaptureSession != null) {
			return mContentCaptureSession;
		}
		// Then the session explicitly set in an ancestor
		android.view.contentcapture.ContentCaptureSession session = null;
		if (parent instanceof View) {
			session = ((View) parent).getContentCaptureSession();
		}
		// Finally, if no session was explicitly set, use the context default session (none here:
		// Context.CONTENT_CAPTURE_MANAGER_SERVICE resolves to no service).
		if (session == null) {
			Object ccm = getContext().getSystemService(android.content.Context.CONTENT_CAPTURE_MANAGER_SERVICE);
			return ccm == null ? null : session;
		}
		return session;
	}

	/* ---- teslalinux: View members from AOSP that ATL lacked (focus, keys, nested scrolling, state,
	 * transitions, drawable state, pointer icon, autofill ids, misc property setters) ---- */
	private boolean mNestedScrollingEnabled;
	private ViewParent mNestedScrollingParent;
	private int[] mTempNestedScrollConsumed;
	private OnKeyListener mOnKeyListener;
	private OnFocusChangeListener mOnFocusChangeListener;
	private KeyEvent.DispatcherState mKeyDispatchState;
	private android.graphics.Matrix mAnimationMatrix;
	private String[] mAutofillHints;
	private android.view.autofill.AutofillId mAutofillId;
	private int mContentSensitivity;
	private float mFrameContentVelocity = -1f;
	private android.graphics.Paint mLayerPaint;
	private int mNextClusterForwardId = NO_ID;
	private int mOutlineAmbientShadowColor = 0xFF000000;
	private int mOutlineSpotShadowColor = 0xFF000000;
	private android.graphics.RenderEffect mRenderEffect;
	private float mRequestedFrameRate = REQUESTED_FRAME_RATE_CATEGORY_DEFAULT;
	private boolean mScreenReaderFocusable;
	private float mTransitionAlpha = 1f;
	private float mTranslationZ;
	private PointerIcon mPointerIcon;
	private OnReceiveContentListener mReceiveContentListener;
	private String[] mReceiveContentMimeTypes;

	public static final float REQUESTED_FRAME_RATE_CATEGORY_DEFAULT = Float.NaN;
	public static final float REQUESTED_FRAME_RATE_CATEGORY_NO_PREFERENCE = -1;
	public static final float REQUESTED_FRAME_RATE_CATEGORY_LOW = -2;
	public static final float REQUESTED_FRAME_RATE_CATEGORY_NORMAL = -3;
	public static final float REQUESTED_FRAME_RATE_CATEGORY_HIGH = -4;
	public static final int AUTOFILL_TYPE_NONE = 0;
	public static final int AUTOFILL_TYPE_TEXT = 1;
	public static final int AUTOFILL_TYPE_TOGGLE = 2;
	public static final int AUTOFILL_TYPE_LIST = 3;
	public static final int AUTOFILL_TYPE_DATE = 4;
	public static final int IMPORTANT_FOR_AUTOFILL_AUTO = 0x0;
	public static final int IMPORTANT_FOR_AUTOFILL_YES = 0x1;
	public static final int IMPORTANT_FOR_AUTOFILL_NO = 0x2;
	public static final int IMPORTANT_FOR_AUTOFILL_YES_EXCLUDE_DESCENDANTS = 0x4;
	public static final int IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS = 0x8;
	public static final int LAST_APP_AUTOFILL_ID = Integer.MAX_VALUE / 2;
	public static final int CONTENT_SENSITIVITY_AUTO = 0;
	public static final int CONTENT_SENSITIVITY_SENSITIVE = 1;
	public static final int CONTENT_SENSITIVITY_NOT_SENSITIVE = 2;

	// --- nested scrolling child protocol (AOSP) ---

	public boolean startNestedScroll(int axes) {
		if (hasNestedScrollingParent()) {
			// Already in progress
			return true;
		}
		if (isNestedScrollingEnabled()) {
			ViewParent p = getParent();
			View child = this;
			while (p != null) {
				if (p.onStartNestedScroll(child, this, axes)) {
					mNestedScrollingParent = p;
					p.onNestedScrollAccepted(child, this, axes);
					return true;
				}
				if (p instanceof View) {
					child = (View) p;
				}
				p = p.getParent();
			}
		}
		return false;
	}

	public boolean hasNestedScrollingParent() {
		return mNestedScrollingParent != null;
	}

	public boolean dispatchNestedPreScroll(int dx, int dy, int[] consumed, int[] offsetInWindow) {
		if (isNestedScrollingEnabled() && mNestedScrollingParent != null) {
			if (dx != 0 || dy != 0) {
				int startX = 0;
				int startY = 0;
				if (offsetInWindow != null) {
					getLocationInWindow(offsetInWindow);
					startX = offsetInWindow[0];
					startY = offsetInWindow[1];
				}
				if (consumed == null) {
					if (mTempNestedScrollConsumed == null) {
						mTempNestedScrollConsumed = new int[2];
					}
					consumed = mTempNestedScrollConsumed;
				}
				consumed[0] = 0;
				consumed[1] = 0;
				mNestedScrollingParent.onNestedPreScroll(this, dx, dy, consumed);
				if (offsetInWindow != null) {
					getLocationInWindow(offsetInWindow);
					offsetInWindow[0] -= startX;
					offsetInWindow[1] -= startY;
				}
				return consumed[0] != 0 || consumed[1] != 0;
			} else if (offsetInWindow != null) {
				offsetInWindow[0] = 0;
				offsetInWindow[1] = 0;
			}
		}
		return false;
	}

	public boolean dispatchNestedScroll(int dxConsumed, int dyConsumed, int dxUnconsumed, int dyUnconsumed, int[] offsetInWindow) {
		if (isNestedScrollingEnabled() && mNestedScrollingParent != null) {
			if (dxConsumed != 0 || dyConsumed != 0 || dxUnconsumed != 0 || dyUnconsumed != 0) {
				int startX = 0;
				int startY = 0;
				if (offsetInWindow != null) {
					getLocationInWindow(offsetInWindow);
					startX = offsetInWindow[0];
					startY = offsetInWindow[1];
				}
				mNestedScrollingParent.onNestedScroll(this, dxConsumed, dyConsumed, dxUnconsumed, dyUnconsumed);
				if (offsetInWindow != null) {
					getLocationInWindow(offsetInWindow);
					offsetInWindow[0] -= startX;
					offsetInWindow[1] -= startY;
				}
				return true;
			} else if (offsetInWindow != null) {
				offsetInWindow[0] = 0;
				offsetInWindow[1] = 0;
			}
		}
		return false;
	}

	public boolean dispatchNestedFling(float velocityX, float velocityY, boolean consumed) {
		if (isNestedScrollingEnabled() && mNestedScrollingParent != null) {
			return mNestedScrollingParent.onNestedFling(this, velocityX, velocityY, consumed);
		}
		return false;
	}

	public boolean dispatchNestedPreFling(float velocityX, float velocityY) {
		if (isNestedScrollingEnabled() && mNestedScrollingParent != null) {
			return mNestedScrollingParent.onNestedPreFling(this, velocityX, velocityY);
		}
		return false;
	}

	// --- keys ---
	public boolean onKeyUp(int keyCode, KeyEvent event) {
		if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER || keyCode == KeyEvent.KEYCODE_SPACE) {
			if (!isClickable()) {
				return false;
			}
			if (isPressed()) {
				setPressed(false);
				performClick();
				return true;
			}
		}
		return false;
	}

	public boolean onKeyMultiple(int keyCode, int repeatCount, KeyEvent event) {
		return false;
	}

	public boolean onKeyPreIme(int keyCode, KeyEvent event) {
		return false;
	}

	public boolean dispatchKeyEventPreIme(KeyEvent event) {
		return onKeyPreIme(event.getKeyCode(), event);
	}

	/** AOSP keeps one DispatcherState per window (attach info); here it lives on the root view. */
	public KeyEvent.DispatcherState getKeyDispatcherState() {
		View root = getRootView();
		if (root == null) {
			return null;
		}
		if (root.mKeyDispatchState == null) {
			root.mKeyDispatchState = new KeyEvent.DispatcherState();
		}
		return root.mKeyDispatchState;
	}

	// --- focus ---
	public void addFocusables(java.util.ArrayList<View> views, int direction) {
		addFocusables(views, direction, isInTouchMode() ? FOCUSABLES_TOUCH_MODE : FOCUSABLES_ALL);
	}

	public void addFocusables(java.util.ArrayList<View> views, int direction, int focusableMode) {
		if (views == null) {
			return;
		}
		if (!isFocusable() || getVisibility() != VISIBLE || !isEnabled()) {
			return;
		}
		if ((focusableMode & FOCUSABLES_TOUCH_MODE) == FOCUSABLES_TOUCH_MODE && !isFocusableInTouchMode()) {
			return;
		}
		views.add(this);
	}

	/** AOSP: whether this view can take focus while the display is in touch mode. */
	public boolean isFocusableInTouchMode() {
		return tlFocusableInTouchMode && isFocusable();
	}

	/** Called by the view system when the focus state of this view changes (AOSP: notifies the listener and drops the pressed state). */
	protected void onFocusChanged(boolean gainFocus, int direction, android.graphics.Rect previouslyFocusedRect) {
		if (!gainFocus) {
			if (isPressed()) {
				setPressed(false);
			}
		}
		if (mOnFocusChangeListener != null) {
			mOnFocusChangeListener.onFocusChange(this, gainFocus);
		}
		refreshDrawableState();
	}

	// --- hover / pointer ---
	public boolean onHoverEvent(MotionEvent event) {
		// AOSP: hoverable views (clickable or long-clickable) consume hover events to track the hovered state
		if (isClickable() || isLongClickable()) {
			return true;
		}
		return false;
	}

	public PointerIcon onResolvePointerIcon(MotionEvent event, int pointerIndex) {
		return mPointerIcon;
	}

	// --- saved state (AOSP) ---
	protected android.os.Parcelable onSaveInstanceState() {
		return BaseSavedState.EMPTY_STATE;
	}

	protected void onRestoreInstanceState(android.os.Parcelable state) {
		if (state != null && !(state instanceof AbsSavedState)) {
			throw new IllegalArgumentException("Wrong state class, expecting View State but received " + state.getClass().toString()
					+ " instead. This usually happens when two views of different type have the same id in the same hierarchy."
					+ " The id of this view is " + getId() + ". Make sure other views do not use the same id.");
		}
	}

	// --- layout direction / window state callbacks (AOSP defaults) ---
	public void onRtlPropertiesChanged(int layoutDirection) {
	}

	public void onWindowSystemUiVisibilityChanged(int visible) {
	}

	protected void onWindowVisibilityChanged(int visibility) {
	}

	// --- drawable state (AOSP) ---
	protected boolean verifyDrawable(android.graphics.drawable.Drawable who) {
		return who == getBackground() || who == getForeground();
	}

	protected void drawableStateChanged() {
		final int[] state = getDrawableState();
		final android.graphics.drawable.Drawable bg = getBackground();
		if (bg != null && bg.isStateful() && bg.setState(state)) {
			invalidateDrawable(bg);
		}
		final android.graphics.drawable.Drawable fg = getForeground();
		if (fg != null && fg.isStateful() && fg.setState(state)) {
			invalidateDrawable(fg);
		}
	}


	// --- invalidation / animation matrix ---
	public void postInvalidateOnAnimation(int left, int top, int right, int bottom) {
		postInvalidate(left, top, right, bottom);
	}

	public void setAnimationMatrix(android.graphics.Matrix matrix) {
		mAnimationMatrix = matrix == null ? null : new android.graphics.Matrix(matrix);
		invalidate();
	}

	public android.graphics.Matrix getAnimationMatrix() {
		return mAnimationMatrix;
	}

	// --- autofill ids / hints ---
	public void setAutofillHints(String... autofillHints) {
		if (autofillHints == null || autofillHints.length == 0) {
			mAutofillHints = null;
		} else {
			mAutofillHints = autofillHints;
		}
	}

	public String[] getAutofillHints() {
		return mAutofillHints;
	}

	/** AOSP: autofill ids are unique per process; the counter wraps at LAST_APP_AUTOFILL_ID like ContextImpl.getNextAutofillId. */
	private static final java.util.concurrent.atomic.AtomicInteger sNextAutofillId = new java.util.concurrent.atomic.AtomicInteger(NO_ID);
	private int mAutofillViewId = NO_ID;
	private boolean mAutofillIdExplicitlySet;

	public int getAutofillViewId() {
		if (mAutofillViewId == NO_ID) {
			int id = sNextAutofillId.incrementAndGet();
			if (id >= LAST_APP_AUTOFILL_ID) {
				sNextAutofillId.set(NO_ID);
				id = sNextAutofillId.incrementAndGet();
			}
			mAutofillViewId = id;
		}
		return mAutofillViewId;
	}

	public void setAutofillId(android.view.autofill.AutofillId id) {
		if (isAttachedToWindow()) {
			throw new IllegalStateException("Cannot set autofill id when view is attached");
		}
		if (id != null && !id.isNonVirtual()) {
			throw new IllegalStateException("Cannot set autofill id assigned to virtual views");
		}
		if (id == null && !mAutofillIdExplicitlySet) {
			return;
		}
		mAutofillId = id;
		if (id != null) {
			mAutofillViewId = id.getViewId();
			mAutofillIdExplicitlySet = true;
		} else {
			mAutofillViewId = NO_ID;
			mAutofillIdExplicitlySet = false;
		}
	}


	// --- API 35 content sensitivity / frame rate hints (recorded; no screen-capture protection or frame pacing here) ---
	public void setContentSensitivity(int mode) {
		mContentSensitivity = mode;
	}

	public int getContentSensitivity() {
		return mContentSensitivity;
	}

	public void setFrameContentVelocity(float pixelsPerSecond) {
		mFrameContentVelocity = Math.abs(pixelsPerSecond);
	}

	public float getFrameContentVelocity() {
		return mFrameContentVelocity;
	}

	public void setRequestedFrameRate(float frameRate) {
		mRequestedFrameRate = frameRate;
	}

	public float getRequestedFrameRate() {
		return mRequestedFrameRate;
	}

	// --- layers / outline / render effect ---
	public void setLayerPaint(android.graphics.Paint paint) {
		if (getLayerType() != LAYER_TYPE_NONE) {
			mLayerPaint = paint;
			invalidate();
		}
	}

	public android.graphics.Paint getLayerPaint() {
		return mLayerPaint;
	}

	public void setOutlineAmbientShadowColor(int color) {
		mOutlineAmbientShadowColor = color;
		invalidate();
	}

	public int getOutlineAmbientShadowColor() {
		return mOutlineAmbientShadowColor;
	}

	public void setOutlineSpotShadowColor(int color) {
		mOutlineSpotShadowColor = color;
		invalidate();
	}

	public int getOutlineSpotShadowColor() {
		return mOutlineSpotShadowColor;
	}

	/** The effect is kept on the view; the GSK renderer does not apply it yet (TODO-real.md). */
	public void setRenderEffect(android.graphics.RenderEffect renderEffect) {
		mRenderEffect = renderEffect;
		invalidate();
	}

	public android.graphics.RenderEffect getRenderEffect() {
		return mRenderEffect;
	}

	// --- geometry (AOSP setFrame through the individual edge setters) ---
	public final void setLeftTopRightBottom(int left, int top, int right, int bottom) {
		setLeft(left);
		setTop(top);
		setRight(right);
		setBottom(bottom);
	}

	public void setNextClusterForwardId(int nextClusterForwardId) {
		mNextClusterForwardId = nextClusterForwardId;
	}

	public int getNextClusterForwardId() {
		return mNextClusterForwardId;
	}

	public void setScreenReaderFocusable(boolean screenReaderFocusable) {
		mScreenReaderFocusable = screenReaderFocusable;
	}

	public boolean isScreenReaderFocusable() {
		return mScreenReaderFocusable;
	}

	/** Transition alpha multiplies the view alpha while a Transition runs (AOSP); here it is applied to the GTK opacity. */
	public void setTransitionAlpha(float alpha) {
		mTransitionAlpha = alpha;
		native_setVisibility(widget, visibility, getAlpha() * mTransitionAlpha, atl_enabled);
	}


	/** Changes the visibility without the usual change notifications (used by Transitions). */
	public void setTransitionVisibility(int visibility) {
		setVisibility(visibility);
	}

	public void setZ(float z) {
		setTranslationZ(z - getElevation());
	}


	// --- drag and drop: no drag session service in this runtime; AOSP returns false when a drag cannot start ---
	public final boolean startDragAndDrop(android.content.ClipData data, DragShadowBuilder shadowBuilder, Object myLocalState, int flags) {
		android.util.Log.w("View", "startDragAndDrop: drag and drop is not available in this runtime");
		return false;
	}

	public final boolean updateDragShadow(DragShadowBuilder shadowBuilder) {
		return false;
	}

	// --- receive content (AOSP: the listener, else the default handling which inserts nothing for a plain View) ---
	public void setOnReceiveContentListener(String[] mimeTypes, OnReceiveContentListener listener) {
		if (listener != null) {
			if (mimeTypes == null || mimeTypes.length == 0) {
				throw new IllegalArgumentException("When the listener is set, MIME types must also be set");
			}
		}
		if (mimeTypes != null) {
			for (String mimeType : mimeTypes) {
				if (mimeType.startsWith("*/")) {
					throw new IllegalArgumentException("A MIME type set here must not start with */: " + mimeType);
				}
			}
		}
		mReceiveContentMimeTypes = mimeTypes != null && mimeTypes.length > 0 ? mimeTypes.clone() : null;
		mReceiveContentListener = listener;
	}

	public String[] getReceiveContentMimeTypes() {
		return mReceiveContentMimeTypes;
	}

	public ContentInfo performReceiveContent(ContentInfo payload) {
		if (mReceiveContentListener != null) {
			return mReceiveContentListener.onReceiveContent(this, payload);
		}
		return onReceiveContent(payload);
	}

	public ContentInfo onReceiveContent(ContentInfo payload) {
		return payload;
	}

	// --- matrix transforms (AOSP hidden API used by transition and tooltip code) ---

	public void transformMatrixToLocal(android.graphics.Matrix matrix) {
		final ViewParent parent = getParent();
		if (parent instanceof View) {
			final View vp = (View) parent;
			vp.transformMatrixToLocal(matrix);
			matrix.postTranslate(vp.getScrollX(), vp.getScrollY());
		}
		matrix.postTranslate(-getLeft(), -getTop());
		android.graphics.Matrix inverse = new android.graphics.Matrix();
		if (getMatrix().invert(inverse)) {
			matrix.postConcat(inverse);
		}
	}

	/* ---- teslalinux: AOSP touch-to-click state (View.onTouchEvent) ---- */
	private boolean mClickable;
	private boolean mLongClickable;
	private boolean mPrePressed;
	private boolean mHasPerformedLongPress;
	private boolean mIgnoreNextUpEvent;
	private Runnable mPendingCheckForTap;
	private Runnable mPendingCheckForLongPress;
	private Runnable mUnsetPressedState;
	private float tlTapX, tlTapY;
	/** The event being handled was dispatched by Java code rather than by a GTK controller. */
	private boolean tlDispatchingSynthetic;
	private TouchDelegate mTouchDelegate;

	/* AOSP: context clickable (stylus button / mouse right button), set by setContextClickable or a context click listener */
	private boolean tlContextClickable;

	public boolean isContextClickable() {
		return tlContextClickable;
	}

	/** AOSP: pressed with a hotspot for the background drawable. */
	private void setPressed(boolean pressed, float x, float y) {
		if (pressed && background != null)
			background.setHotspot(x, y);
		setPressed(pressed);
	}

	/** AOSP: the click, through the listener. */
	private boolean performClickInternal() {
		return performClick();
	}

	/** AOSP: whether an ancestor delays the pressed state (scrolling containers). */
	public boolean isInScrollingContainer() {
		ViewParent p = getParent();
		while (p != null && p instanceof ViewGroup) {
			if (((ViewGroup) p).shouldDelayChildPressedState())
				return true;
			p = p.getParent();
		}
		return false;
	}

	/** AOSP: whether the point (local coordinates) is inside the view, grown by slop. */
	public boolean pointInView(float localX, float localY, float slop) {
		return localX >= -slop && localY >= -slop && localX < ((getWidth()) + slop) && localY < ((getHeight()) + slop);
	}

	private void checkForLongClick(long delay, final float x, final float y) {
		if (isLongClickable()) {
			mHasPerformedLongPress = false;
			if (mPendingCheckForLongPress == null)
				mPendingCheckForLongPress = new Runnable() {
					@Override
					public void run() {
						if (pressed && getParent() != null) {
							if (performLongClick(x, y))
								mHasPerformedLongPress = true;
						}
					}
				};
			postDelayed(mPendingCheckForLongPress, delay);
		}
	}

	private void removeLongPressCallback() {
		if (mPendingCheckForLongPress != null)
			removeCallbacks(mPendingCheckForLongPress);
	}

	private void removeTapCallback() {
		if (mPendingCheckForTap != null) {
			mPrePressed = false;
			removeCallbacks(mPendingCheckForTap);
		}
	}

	/** The main-thread handler every View shares for post/postDelayed/removeCallbacks (ViewRootImpl's in AOSP). */
	private static Handler sTlMainHandler;
	private static Handler tlMainHandler() {
		if (sTlMainHandler == null)
			sTlMainHandler = new Handler(Looper.getMainLooper());
		return sTlMainHandler;
	}
	/** Set by the native dispatcher before calling a custom dispatchTouchEvent: the event is synthetic (not from GTK). */
	static boolean tlNativeDispatchSynthetic;

	/** AOSP: forces the view's layer to be created and drawn. There is no cached layer here (GTK renders
	 * the widget tree directly), so only the precondition remains. */
	public void buildLayer() {
		if (getLayerType() == LAYER_TYPE_NONE)
			throw new IllegalStateException("Cannot build a layer if the layer type is set to LAYER_TYPE_NONE");
	}
	/* AOSP: accessibility live regions */
	public static final int ACCESSIBILITY_LIVE_REGION_NONE = 0x00000000;
	public static final int ACCESSIBILITY_LIVE_REGION_POLITE = 0x00000001;
	public static final int ACCESSIBILITY_LIVE_REGION_ASSERTIVE = 0x00000002;
	/* AOSP (API 34): whether accessibility services may see this view's data */
	public static final int ACCESSIBILITY_DATA_SENSITIVE_AUTO = 0x00000000;
	public static final int ACCESSIBILITY_DATA_SENSITIVE_YES = 0x00000001;
	public static final int ACCESSIBILITY_DATA_SENSITIVE_NO = 0x00000002;
	private int mAccessibilityDataSensitive = ACCESSIBILITY_DATA_SENSITIVE_AUTO;
	/* AOSP: touches are discarded while another window obscures this one; the compositor never
	 * reports MotionEvent.FLAG_WINDOW_IS_OBSCURED to ATL, so the flag only records the request */
	private boolean mFilterTouchesWhenObscured;

	public void setFilterTouchesWhenObscured(boolean enabled) {
		mFilterTouchesWhenObscured = enabled;
	}

	public boolean getFilterTouchesWhenObscured() {
		return mFilterTouchesWhenObscured;
	}
	private static int sNextAccessibilityViewId = 1;
	private int mAccessibilityViewId = NO_ID;

	/** AOSP (hidden): the id accessibility uses for this view, assigned on first use. */
	public int getAccessibilityViewId() {
		if (mAccessibilityViewId == NO_ID)
			mAccessibilityViewId = sNextAccessibilityViewId++;
		return mAccessibilityViewId;
	}

	/**
	 * AOSP (hidden): the accessibility id of the window; the accessibility manager service assigns
	 * one when a service is running, otherwise it stays undefined, which is the case here.
	 */
	public int getAccessibilityWindowId() {
		return android.view.accessibility.AccessibilityWindowInfo.UNDEFINED_WINDOW_ID;
	}

	public void setAccessibilityDataSensitive(int accessibilityDataSensitive) {
		mAccessibilityDataSensitive = accessibilityDataSensitive;
	}

	public int getAccessibilityDataSensitive() {
		return mAccessibilityDataSensitive;
	}

	/** AOSP: AUTO resolves to whether touches are filtered when the view is obscured. */
	public boolean isAccessibilityDataSensitive() {
		if (mAccessibilityDataSensitive == ACCESSIBILITY_DATA_SENSITIVE_AUTO)
			return getFilterTouchesWhenObscured();
		return mAccessibilityDataSensitive == ACCESSIBILITY_DATA_SENSITIVE_YES;
	}
	/** AOSP: a resolved layout direction, LAYOUT_DIRECTION_LTR or LAYOUT_DIRECTION_RTL. */
	@android.annotation.IntDef({LAYOUT_DIRECTION_LTR, LAYOUT_DIRECTION_RTL})
	@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.SOURCE)
	public @interface ResolvedLayoutDir {}
	/* teslalinux debugging: the specs of the last measure pass (android.atl.ViewDump) */
	public int tlLastWidthMeasureSpec() {
		return oldWidthMeasureSpec;
	}

	public int tlLastHeightMeasureSpec() {
		return oldHeightMeasureSpec;
	}
	/** AOSP: the drawable state of a checked view (CompoundButton, Checkable items). */
	protected static final int[] CHECKED_STATE_SET = new int[] {com.android.internal.R.attr.state_checked};
	/** AOSP: the autofill type of a view (TOGGLE for compound buttons, TEXT for editors); plain views have none. */
	@android.annotation.IntDef(prefix = {"AUTOFILL_TYPE_"}, value = {AUTOFILL_TYPE_NONE, AUTOFILL_TYPE_TEXT, AUTOFILL_TYPE_TOGGLE, AUTOFILL_TYPE_LIST, AUTOFILL_TYPE_DATE})
	@java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.SOURCE)
	public @interface AutofillType {}

	public @AutofillType int getAutofillType() {
		return AUTOFILL_TYPE_NONE;
	}

	public android.view.autofill.AutofillValue getAutofillValue() {
		return null;
	}

	/** AOSP: a view that is not autofillable ignores the value. */
	public void autofill(android.view.autofill.AutofillValue value) {
	}

	/** AOSP: resolved layout direction is RTL. */
	public boolean isLayoutRtl() {
		return getLayoutDirection() == LAYOUT_DIRECTION_RTL;
	}

	/** AOSP: drawables get the resolved layout direction here; the base view has none to resolve. */
	public void onResolveDrawables(int layoutDirection) {
	}

	/* AOSP accessibility population: the public methods delegate to the internal ones so a delegate can wrap them */
	public void onInitializeAccessibilityEvent(android.view.accessibility.AccessibilityEvent event) {
		onInitializeAccessibilityEventInternal(event);
	}

	public void onInitializeAccessibilityEventInternal(android.view.accessibility.AccessibilityEvent event) {
		event.setSource(this);
		event.setClassName(getAccessibilityClassName());
		event.setPackageName(getContext().getPackageName());
		event.setEnabled(isEnabled());
		event.setContentDescription(getContentDescription());
		event.setScrollX(getScrollX());
		event.setScrollY(getScrollY());
	}

	public void onPopulateAccessibilityEvent(android.view.accessibility.AccessibilityEvent event) {
		onPopulateAccessibilityEventInternal(event);
	}

	public void onPopulateAccessibilityEventInternal(android.view.accessibility.AccessibilityEvent event) {
	}

	public void onInitializeAccessibilityNodeInfo(android.view.accessibility.AccessibilityNodeInfo info) {
		onInitializeAccessibilityNodeInfoInternal(info);
	}

	public void onInitializeAccessibilityNodeInfoInternal(android.view.accessibility.AccessibilityNodeInfo info) {
		android.graphics.Rect bounds = new android.graphics.Rect(0, 0, getWidth(), getHeight());
		info.setBoundsInParent(bounds);
		int[] location = new int[2];
		getLocationOnScreen(location);
		bounds.offset(location[0], location[1]);
		info.setBoundsInScreen(bounds);
		info.setVisibleToUser(isShown());
		info.setPackageName(getContext().getPackageName());
		info.setClassName(getAccessibilityClassName());
		info.setContentDescription(getContentDescription());
		info.setEnabled(isEnabled());
		info.setClickable(isClickable());
		info.setFocusable(isFocusable());
		info.setFocused(isFocused());
		info.setSelected(isSelected());
		info.setLongClickable(isLongClickable());
	}
	/* AOSP: accessibility importance modes */
	public static final int IMPORTANT_FOR_ACCESSIBILITY_AUTO = 0x00000000;
	public static final int IMPORTANT_FOR_ACCESSIBILITY_YES = 0x00000001;
	public static final int IMPORTANT_FOR_ACCESSIBILITY_NO = 0x00000002;
	public static final int IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS = 0x00000004;

	/**
	 * AOSP: whether the layout direction can be resolved (INHERIT with no parent cannot). ATL resolves
	 * every view's direction (getLayoutDirection/isLayoutDirectionResolved), so it always can.
	 */
	public boolean canResolveLayoutDirection() {
		return true;
	}

	/* AOSP aggregated visibility: a view learns whether it is actually visible (own visibility, ancestors, window) */
	private boolean tlAggregatedVisible;

	/**
	 * AOSP: called when the user-visibility of this View has changed, whether through its own
	 * visibility, an ancestor's, or the window's. The background drawable follows it; ProgressBar
	 * starts and stops its indeterminate animation here.
	 */
	public void onVisibilityAggregated(boolean isVisible) {
		tlAggregatedVisible = isVisible;
		final android.graphics.drawable.Drawable dr = getBackground();
		if (dr != null && isVisible != dr.isVisible()) {
			dr.setVisible(isVisible, false);
		}
		final android.graphics.drawable.Drawable fg = getForeground();
		if (fg != null && isVisible != fg.isVisible()) {
			fg.setVisible(isVisible, false);
		}
	}

	/** AOSP: delivers the aggregated visibility to this view (ViewGroup continues to its children). */
	public boolean dispatchVisibilityAggregated(boolean isVisible) {
		final boolean thisVisible = getVisibility() == VISIBLE;
		if (thisVisible || !isVisible) {
			onVisibilityAggregated(isVisible);
		}
		return thisVisible && isVisible;
	}

	/** teslalinux: the last aggregated visibility this view was told about. */
	boolean tlIsAggregatedVisible() {
		return tlAggregatedVisible;
	}

	/**
	 * AOSP: performs an accessibility action on this view; the standard actions map to the view's
	 * own click/focus/selection behaviour. Accessibility focus needs an accessibility service, which
	 * this platform has none of, so those actions report failure the way AOSP does without one.
	 */
	public boolean performAccessibilityActionInternal(int action, android.os.Bundle arguments) {
		switch (action) {
			case android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK: {
				if (isClickable()) {
					performClickInternal();
					return true;
				}
			} break;
			case android.view.accessibility.AccessibilityNodeInfo.ACTION_LONG_CLICK: {
				if (isLongClickable()) {
					performLongClick();
					return true;
				}
			} break;
			case android.view.accessibility.AccessibilityNodeInfo.ACTION_FOCUS: {
				if (!hasFocus()) {
					// Get out of touch mode since accessibility
					// wants to move focus around.
					return requestFocus();
				}
			} break;
			case android.view.accessibility.AccessibilityNodeInfo.ACTION_CLEAR_FOCUS: {
				if (hasFocus()) {
					clearFocus();
					return !isFocused();
				}
			} break;
			case android.view.accessibility.AccessibilityNodeInfo.ACTION_SELECT: {
				if (!isSelected()) {
					setSelected(true);
					return isSelected();
				}
			} break;
			case android.view.accessibility.AccessibilityNodeInfo.ACTION_CLEAR_SELECTION: {
				if (isSelected()) {
					setSelected(false);
					return !isSelected();
				}
			} break;
			case android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS:
			case android.view.accessibility.AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS:
				return false;
			case com.android.internal.R.id.accessibilityActionShowOnScreen: {
				if (isAttachedToWindow()) {
					final android.graphics.Rect r = new android.graphics.Rect();
					getDrawingRect(r);
					return requestRectangleOnScreen(r, true);
				}
			} break;
			case com.android.internal.R.id.accessibilityActionContextClick: {
				if (isContextClickable()) {
					performContextClick();
					return true;
				}
			} break;
		}
		return false;
	}
	/** AOSP: a long click not tied to a touch position (the context menu anchors to the view). */
	public boolean performLongClick() {
		return performLongClick(Float.NaN, Float.NaN);
	}
	/* AOSP: a context click (stylus button, mouse right button) goes to the context click listener */
	private OnContextClickListener tlOnContextClickListener;

	public void setOnContextClickListener(OnContextClickListener l) {
		if (!isContextClickable()) {
			setContextClickable(true);
		}
		tlOnContextClickListener = l;
	}

	public boolean performContextClick() {
		boolean handled = false;
		if (tlOnContextClickListener != null) {
			handled = tlOnContextClickListener.onContextClick(this);
		}
		return handled;
	}
	/** AOSP: interface definition for a callback to be invoked when a view is context clicked. */
	public interface OnContextClickListener {
		boolean onContextClick(View v);
	}
	public void setContextClickable(boolean contextClickable) {
		tlContextClickable = contextClickable;
	}
	/**
	 * AOSP: invoked when the alpha changes; a subclass that applies the alpha itself returns true
	 * (ImageButton/ImageView apply it to their drawable), otherwise the view is drawn through an alpha layer.
	 */
	protected boolean onSetAlpha(int alpha) {
		return false;
	}
	/* AOSP window focus: the root view of a window tree records it; every view can ask hasWindowFocus() */
	private boolean tlWindowFocus;

	/** AOSP: called by the window when it gains or loses focus; propagates to onWindowFocusChanged (and a ViewGroup's children). */
	public void dispatchWindowFocusChanged(boolean hasFocus) {
		tlWindowFocus = hasFocus;
		onWindowFocusChanged(hasFocus);
	}

	/* ---- teslalinux: view focus state and touch mode (tl_touch_mode) ----
	 * Focus: the focused view of a window is the view whose widget holds the window's GTK focus
	 * (gtk_root_get_focus; dialogs are layers of the main GtkWindow and share it). hasFocus,
	 * findFocus and clearFocus read and change that state (native-focus-state.sh) with AOSP's
	 * meaning: a view "has focus" when it or one of its descendants is the focused view.
	 * Touch mode: AOSP keeps it per display (WindowManagerService, Android 13+) and every window of
	 * the display reads it from AttachInfo.mInTouchMode. This platform has one display, so the state
	 * is process-wide. It starts at config_defaultInTouchMode. The main window's GTK controllers
	 * call in before an event reaches any view (native-touch-mode.sh): a pointer DOWN or SCROLL
	 * enters touch mode (ViewRootImpl.EarlyPostImeInputStage.processPointerEvent), a navigation or
	 * typing key leaves it (ViewRootImpl.checkForLeavingTouchModeAndConsume). A change reaches the
	 * OnTouchModeChangeListeners of every window and then adjusts that window's focus
	 * (ViewRootImpl.enterTouchMode / leaveTouchMode). Compose derives its InputModeManager from it,
	 * which decides whether clickables can take focus (Focusability.SystemDefined). */
	private static final boolean TL_DEBUG_TOUCH_MODE = System.getenv("ATL_DEBUG_FOCUS") != null;
	/** 1 in touch mode, 0 not, -1 before the display default was read */
	private static int tlTouchMode = -1;

	private static boolean tlTouchModeState() {
		if (tlTouchMode < 0)
			tlTouchMode = android.content.res.Resources.getSystem().getBoolean(com.android.internal.R.bool.config_defaultInTouchMode) ? 1 : 0;
		return tlTouchMode == 1;
	}

	/** AOSP ViewRootImpl.ensureTouchMode for every window of the display; true when focus changed as a result. */
	static boolean tlEnsureTouchMode(boolean inTouchMode) {
		if (tlTouchModeState() == inTouchMode)
			return false;
		tlTouchMode = inTouchMode ? 1 : 0;
		if (TL_DEBUG_TOUCH_MODE)
			android.util.Log.i("View", "[focus] touch mode -> " + inTouchMode);
		boolean focusChanged = false;
		for (Window window : Window.tlLiveWindows()) {
			View root = window.getDecorView();
			if (root == null || !root.isAttachedToWindow())
				continue;
			/* AOSP ensureTouchModeLocally: the window's listeners first, then its focus */
			if (window.view_tree_observer != null)
				window.view_tree_observer.dispatchOnTouchModeChanged(inTouchMode);
			focusChanged |= inTouchMode ? tlEnterTouchMode(root) : tlLeaveTouchMode(root);
		}
		return focusChanged;
	}

	/** AOSP ViewRootImpl.enterTouchMode: a focused view that cannot keep focus in touch mode gives it up. */
	private static boolean tlEnterTouchMode(View root) {
		if (root.hasFocus()) {
			final View focused = root.findFocus();
			if (focused != null && !focused.isFocusableInTouchMode()) {
				final ViewGroup ancestorToTakeFocus = tlFindAncestorToTakeFocusInTouchMode(focused);
				if (ancestorToTakeFocus != null)
					return ancestorToTakeFocus.requestFocus();
				focused.tlClearFocusInternal(true, false);
				return true;
			}
		}
		return false;
	}

	/** AOSP ViewRootImpl.findAncestorToTakeFocusInTouchMode (the walk ends at the window's root view). */
	private static ViewGroup tlFindAncestorToTakeFocusInTouchMode(View focused) {
		ViewParent parent = focused.getParent();
		while (parent instanceof ViewGroup) {
			final ViewGroup vgParent = (ViewGroup) parent;
			if (vgParent.getDescendantFocusability() == ViewGroup.FOCUS_AFTER_DESCENDANTS && vgParent.isFocusableInTouchMode())
				return vgParent;
			parent = vgParent.getParent();
		}
		return null;
	}

	/** AOSP ViewRootImpl.leaveTouchMode: without touch, some view should hold focus for the keys. */
	private static boolean tlLeaveTouchMode(View root) {
		if (root.hasFocus()) {
			View focusedView = root.findFocus();
			if (!(focusedView instanceof ViewGroup))
				return false; /* some view has focus, let it keep it */
			else if (((ViewGroup) focusedView).getDescendantFocusability() != ViewGroup.FOCUS_AFTER_DESCENDANTS)
				return false; /* a group has focus and does not prefer its children over itself */
		}
		return root.restoreDefaultFocus();
	}

	private static boolean tlIsNavigationKey(KeyEvent keyEvent) {
		switch (keyEvent.getKeyCode()) {
			case KeyEvent.KEYCODE_DPAD_LEFT:
			case KeyEvent.KEYCODE_DPAD_RIGHT:
			case KeyEvent.KEYCODE_DPAD_UP:
			case KeyEvent.KEYCODE_DPAD_DOWN:
			case KeyEvent.KEYCODE_DPAD_CENTER:
			case KeyEvent.KEYCODE_PAGE_UP:
			case KeyEvent.KEYCODE_PAGE_DOWN:
			case KeyEvent.KEYCODE_MOVE_HOME:
			case KeyEvent.KEYCODE_MOVE_END:
			case KeyEvent.KEYCODE_TAB:
			case KeyEvent.KEYCODE_SPACE:
			case KeyEvent.KEYCODE_ENTER:
				return true;
		}
		return false;
	}

	/**
	 * AOSP ViewRootImpl.checkForLeavingTouchModeAndConsume, called by the main window's key controller
	 * before any view sees the key; true when the key only served to leave touch mode (consumed).
	 */
	static boolean tlCheckForLeavingTouchModeAndConsume(KeyEvent event) {
		if (!tlTouchModeState())
			return false;
		final int action = event.getAction();
		if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_MULTIPLE)
			return false;
		if ((event.getFlags() & KeyEvent.FLAG_KEEP_TOUCH_MODE) != 0)
			return false;
		if (event.hasNoModifiers() && tlIsNavigationKey(event))
			return tlEnsureTouchMode(false);
		if (event.getUnicodeChar() > 0) {
			tlEnsureTouchMode(false); /* typing keys always reach the view */
			return false;
		}
		return false;
	}

	/**
	 * A pointer DOWN or SCROLL from any device at (x, y) in the main window, before it reaches a view
	 * (AOSP processPointerEvent). App windows that watch outside touches get theirs (ACTION_OUTSIDE).
	 */
	static void tlPointerDown(float x, float y) {
		tlEnsureTouchMode(true);
		WindowManagerImpl.tlDispatchOutsideTouch(x, y);
	}

	/* ---- teslalinux: the AOSP focus model (tl_focus_model) ----
	 * Focus is view state, per window: a focused view has tlFocused (AOSP PFLAG_FOCUSED) and every
	 * group on its path to the window's root has it as tlFocusedChild (mFocused). GTK has one focus
	 * per toplevel and every window here (activity, dialogs, app windows) is a layer of one GtkWindow,
	 * so GTK's focus is a projection: it shows the focused view of the focused window (the topmost
	 * dialog or focusable app window, else the activity's). Focus that GTK moves on its own (a tap
	 * into an EditText's GtkText) comes back through tlGtkFocusChanged. */
	boolean tlFocused;

	/** AOSP canTakeFocus */
	private boolean tlCanTakeFocus() {
		return visibility == VISIBLE && isFocusable() && isEnabled();
	}

	/** AOSP hasAncestorThatBlocksDescendantFocus */
	private boolean tlHasAncestorThatBlocksDescendantFocus() {
		ViewParent ancestor = parent;
		while (ancestor instanceof ViewGroup) {
			final ViewGroup vgAncestor = (ViewGroup) ancestor;
			if (vgAncestor.getDescendantFocusability() == ViewGroup.FOCUS_BLOCK_DESCENDANTS)
				return true;
			ancestor = vgAncestor.getParent();
		}
		return false;
	}

	/** AOSP requestFocusNoSearch */
	boolean tlRequestFocusNoSearch(int direction, Rect previouslyFocusedRect) {
		if (!tlCanTakeFocus())
			return false;
		if (isInTouchMode() && !isFocusableInTouchMode())
			return false;
		if (tlHasAncestorThatBlocksDescendantFocus())
			return false;
		tlHandleFocusGainInternal(direction, previouslyFocusedRect);
		return true;
	}

	/** AOSP handleFocusGainInternal: this view becomes its window's focused view. */
	void tlHandleFocusGainInternal(int direction, Rect previouslyFocusedRect) {
		if (tlFocused)
			return;
		tlFocused = true;
		final View oldFocus = getRootView().findFocus();
		if (parent instanceof ViewGroup)
			((ViewGroup) parent).requestChildFocus(this, this);
		if (isAttachedToWindow())
			getViewTreeObserver().dispatchOnGlobalFocusChange(oldFocus, this);
		onFocusChanged(true, direction, previouslyFocusedRect);
		refreshDrawableState();
		tlProjectFocus(direction);
	}

	/** AOSP unFocus: loses focus without telling the parents (they move it themselves). */
	void tlUnFocus(View focused) {
		tlClearFocusInternal(focused, false, false);
	}

	/**
	 * AOSP clearFocusInternal: the view gives up focus, is told so (onFocusChanged), and with
	 * refocus the window's root view picks the next focused view; otherwise the window's global
	 * focus listeners learn that no view has focus.
	 */
	void tlClearFocusInternal(View focused, boolean propagate, boolean refocus) {
		if (!tlFocused)
			return;
		tlFocused = false;
		if (propagate && parent instanceof ViewGroup)
			((ViewGroup) parent).clearChildFocus(this);
		onFocusChanged(false, 0, null);
		refreshDrawableState();
		if (widget != 0)
			nativeClearFocus(widget); /* GTK's focus leaves the widget when it had it */
		if (propagate && (!refocus || !tlRootViewRequestFocus()) && isAttachedToWindow())
			getViewTreeObserver().dispatchOnGlobalFocusChange(this, null);
	}

	void tlClearFocusInternal(boolean propagate, boolean refocus) {
		tlClearFocusInternal(null, propagate, refocus);
	}

	/* ---- the focused window and GTK's focus ---- */
	/** windows above the activity that take focus (showing dialogs, focusable app windows), topmost last */
	private static final java.util.ArrayList<View> tlFocusWindows = new java.util.ArrayList<>();

	private static View tlFocusedWindowRoot() {
		return tlFocusWindows.isEmpty() ? null : tlFocusWindows.get(tlFocusWindows.size() - 1);
	}

	/** whether this view's window is the focused one (an activity window is, when nothing is above it) */
	boolean tlInFocusedWindow() {
		View top = tlFocusedWindowRoot();
		return top == null ? !tlFocusWindows.contains(getRootView()) : getRootView() == top;
	}

	/** GTK's focus shows this view when its window is the focused window */
	private void tlProjectFocus(int direction) {
		if (tlFocusWindows.isEmpty())
			tlActivityRoot = new java.lang.ref.WeakReference<>(getRootView());
		if (widget != 0 && !tlFocusFromGtk && tlInFocusedWindow())
			nativeRequestFocus(widget, direction);
	}
	/** the root of the activity window, whose focused view gets GTK's focus back when the windows above go */
	private static java.lang.ref.WeakReference<View> tlActivityRoot;
	private static boolean tlFocusFromGtk;

	/**
	 * A window that takes focus was shown (shown=true) or went away: the focused window changes, and
	 * GTK's focus shows its focused view, or none (keys then reach the window itself, as on AOSP when
	 * the focused window has no focused view).
	 */
	public static void tlFocusWindowChanged(View root, boolean shown) {
		if (root == null)
			return;
		tlFocusWindows.remove(root);
		if (shown)
			tlFocusWindows.add(root);
		View top = tlFocusedWindowRoot();
		if (top == null)
			top = tlActivityRoot != null ? tlActivityRoot.get() : null;
		View focused = top != null ? top.findFocus() : null;
		if (focused != null && focused.widget != 0)
			focused.nativeRequestFocus(focused.widget, FOCUS_DOWN);
		else
			nativeDropWindowFocus();
	}
	/**
	 * GTK moved its focus to a widget of this view (a tap into a GtkText, GTK's own focus
	 * navigation): the view becomes its window's focused view, as if it had requested it.
	 */
	static void tlGtkFocusChanged(View view) {
		if (view == null || view.tlFocused)
			return;
		tlFocusFromGtk = true;
		try {
			view.tlHandleFocusGainInternal(FOCUS_DOWN, null);
		} finally {
			tlFocusFromGtk = false;
		}
	}


	/** AOSP rootViewRequestFocus */
	boolean tlRootViewRequestFocus() {
		final View root = getRootView();
		return root != null && root.requestFocus();
	}

	/** AOSP sAlwaysAssignFocus: apps targeting API < 28 expect a view to be focused again after clearFocus. */
	boolean tlAlwaysAssignFocus() {
		android.content.pm.ApplicationInfo ai = getContext() != null ? getContext().getApplicationInfo() : null;
		return ai != null && ai.targetSdkVersion < android.os.Build.VERSION_CODES.P;
	}

	/** drops GTK's focus (and a deferred focus request) when this view's widget has it; true if it had */
	private static native boolean nativeClearFocus(long widget);
	/** GTK's focus goes to no widget: keys reach the window-level dispatch */
	private static native void nativeDropWindowFocus();

	/* ---- teslalinux: layout change listeners, root namespace, window frames (tl_layout_change) ---- */
	private java.util.ArrayList<OnLayoutChangeListener> tlLayoutChangeListeners;
	private int tlLaidOutLeft, tlLaidOutTop, tlLaidOutRight, tlLaidOutBottom;

	/** AOSP View.layout: after onLayout, the layout change listeners get the new and the previous bounds. */
	private void tlDispatchLayoutChange() {
		final int oldL = tlLaidOutLeft, oldT = tlLaidOutTop, oldR = tlLaidOutRight, oldB = tlLaidOutBottom;
		tlLaidOutLeft = left;
		tlLaidOutTop = top;
		tlLaidOutRight = right;
		tlLaidOutBottom = bottom;
		if (tlLayoutChangeListeners == null || tlLayoutChangeListeners.isEmpty())
			return;
		java.util.ArrayList<OnLayoutChangeListener> listeners = new java.util.ArrayList<OnLayoutChangeListener>(tlLayoutChangeListeners);
		for (OnLayoutChangeListener l : listeners)
			l.onLayoutChange(this, left, top, right, bottom, oldL, oldT, oldR, oldB);
	}

	private int tlRawLayoutDirection = LAYOUT_DIRECTION_INHERIT;

	/** AOSP (hidden): the layout direction as set, before resolution. */
	public int getRawLayoutDirection() {
		return tlRawLayoutDirection;
	}

	private boolean tlIsRootNamespace;

	/** AOSP (hidden): marks the root of a namespace for findViewById and focus searches (window roots). */
	public void setIsRootNamespace(boolean isRoot) {
		tlIsRootNamespace = isRoot;
	}

	public boolean isRootNamespace() {
		return tlIsRootNamespace;
	}

	/** AOSP (hidden): the view's location on screen as a new array. */
	public int[] getLocationOnScreen() {
		int[] location = new int[2];
		getLocationOnScreen(location);
		return location;
	}

	/**
	 * AOSP (hidden): the frame of the display area the window is laid out in. There are no system
	 * bars here, so it is the window's visible display frame.
	 */
	public void getWindowDisplayFrame(android.graphics.Rect outRect) {
		getWindowVisibleDisplayFrame(outRect);
	}

	/**
	 * AOSP (hidden): converts an event in this view's coordinates to screen coordinates (through the
	 * parents, then the window's position on screen, which ViewRootImpl adds on Android); false when
	 * the view is not attached. Used to forward touches between windows (ForwardingListener).
	 */
	public boolean toGlobalMotionEvent(MotionEvent ev) {
		if (!isAttachedToWindow())
			return false;
		final android.graphics.Matrix m = new android.graphics.Matrix();
		transformMatrixToGlobal(m);
		final int[] window = getRootView().getLocationOnScreen();
		m.postTranslate(window[0], window[1]);
		ev.transform(m);
		return true;
	}

	/** AOSP (hidden): converts an event in screen coordinates to this view's coordinates. */
	public boolean toLocalMotionEvent(MotionEvent ev) {
		if (!isAttachedToWindow())
			return false;
		final int[] window = getRootView().getLocationOnScreen();
		final android.graphics.Matrix m = new android.graphics.Matrix();
		m.setTranslate(-window[0], -window[1]);
		transformMatrixToLocal(m);
		ev.transform(m);
		return true;
	}
