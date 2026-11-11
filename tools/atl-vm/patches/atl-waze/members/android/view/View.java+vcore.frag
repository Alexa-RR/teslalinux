	// ---- teslalinux (vcore): View state AOSP keeps in mViewFlags/mPrivateFlags* and friends ----

	/** layout() / layoutInternal() frames on the stack: a layout pass is running (isInLayout) */
	private static int tlLayoutDepth;
	/** PFLAG3_IS_LAID_OUT: set by a layout, cleared when detached */
	private boolean tlLaidOut;
	private boolean tlWillNotDraw;
	private boolean tlSaveEnabled = true;
	private boolean tlSaveFromParentEnabled = true;
	private boolean tlHapticFeedbackEnabled = true;
	private boolean tlSoundEffectsEnabled = true;
	private boolean tlPaddingRelative;
	private boolean tlScrollContainer;
	private boolean tlTemporarilyDetached;
	private int tlTransientStateCount;
	private boolean tlHorizontalFadingEdge, tlVerticalFadingEdge;
	/** ScrollabilityCache.fadingEdgeLength: -1 until a fading edge is enabled or the length set */
	private int tlFadingEdgeLength = -1;
	private boolean tlHorizontalScrollBar, tlVerticalScrollBar;
	private int tlScrollBarStyle = SCROLLBARS_INSIDE_OVERLAY;
	private Rect tlClipBounds;
	private ViewOutlineProvider tlOutlineProvider = ViewOutlineProvider.BACKGROUND;
	private boolean tlClipToOutline;
	private int tlOutlineW = -1, tlOutlineH = -1;
	private boolean tlOutlineRebuildPosted;

	/** The View attributes AOSP's constructor reads that ATL's did not. */
	private void tlVcoreReadAttrs(android.content.res.TypedArray a) {
		if (a.hasValue(com.android.internal.R.styleable.View_saveEnabled))
			tlSaveEnabled = a.getBoolean(com.android.internal.R.styleable.View_saveEnabled, true);
		if (a.hasValue(com.android.internal.R.styleable.View_hapticFeedbackEnabled))
			tlHapticFeedbackEnabled = a.getBoolean(com.android.internal.R.styleable.View_hapticFeedbackEnabled, true);
		if (a.hasValue(com.android.internal.R.styleable.View_soundEffectsEnabled))
			tlSoundEffectsEnabled = a.getBoolean(com.android.internal.R.styleable.View_soundEffectsEnabled, true);
		if (a.hasValue(com.android.internal.R.styleable.View_isScrollContainer))
			tlScrollContainer = a.getBoolean(com.android.internal.R.styleable.View_isScrollContainer, false);
		if (a.hasValue(com.android.internal.R.styleable.View_scrollbars)) {
			final int scrollbars = a.getInt(com.android.internal.R.styleable.View_scrollbars, SCROLLBARS_NONE);
			tlHorizontalScrollBar = (scrollbars & SCROLLBARS_HORIZONTAL) != 0;
			tlVerticalScrollBar = (scrollbars & SCROLLBARS_VERTICAL) != 0;
		}
		if (a.hasValue(com.android.internal.R.styleable.View_scrollbarStyle))
			tlScrollBarStyle = a.getInt(com.android.internal.R.styleable.View_scrollbarStyle, SCROLLBARS_INSIDE_OVERLAY) & SCROLLBARS_STYLE_MASK;
		// requiresFadingEdge (and the old fadingEdge) select the fading edges, fadingEdgeLength their size
		int fading = FADING_EDGE_NONE;
		if (a.hasValue(com.android.internal.R.styleable.View_requiresFadingEdge))
			fading = a.getInt(com.android.internal.R.styleable.View_requiresFadingEdge, FADING_EDGE_NONE);
		else if (a.hasValue(com.android.internal.R.styleable.View_fadingEdge))
			fading = a.getInt(com.android.internal.R.styleable.View_fadingEdge, FADING_EDGE_NONE);
		tlHorizontalFadingEdge = (fading & FADING_EDGE_HORIZONTAL) != 0;
		tlVerticalFadingEdge = (fading & FADING_EDGE_VERTICAL) != 0;
		if (fading != FADING_EDGE_NONE || a.hasValue(com.android.internal.R.styleable.View_fadingEdgeLength)) {
			tlFadingEdgeLength = a.getDimensionPixelSize(com.android.internal.R.styleable.View_fadingEdgeLength,
				ViewConfiguration.get(getContext()).getScaledFadingEdgeLength());
		}
		if (a.hasValue(com.android.internal.R.styleable.View_scrollX) || a.hasValue(com.android.internal.R.styleable.View_scrollY)) {
			scrollTo(a.getDimensionPixelOffset(com.android.internal.R.styleable.View_scrollX, 0),
				 a.getDimensionPixelOffset(com.android.internal.R.styleable.View_scrollY, 0));
		}
		tlVcoreReadFocusAttrs(a);
	}

	/** AOSP setFrame: a new size rebuilds the outline */
	private void tlFrameMaybeChanged() {
		final int w = getWidth(), h = getHeight();
		if (w != tlOutlineW || h != tlOutlineH) {
			tlOutlineW = w;
			tlOutlineH = h;
			if (tlClipToOutline)
				tlRebuildOutline();
		}
	}

	private static boolean sTlClipNativeMissing;

	/** The clip bounds go to the native widget, which clips its whole drawing (native-vcore-view-clip.sh). */
	private void tlNativeClipBounds() {
		if (widget == 0 || sTlClipNativeMissing)
			return;
		try {
			if (tlClipBounds != null)
				nativeSetClipBounds(widget, true, tlClipBounds.left, tlClipBounds.top, tlClipBounds.right, tlClipBounds.bottom);
			else
				nativeSetClipBounds(widget, false, 0, 0, 0, 0);
		} catch (UnsatisfiedLinkError e) {
			sTlClipNativeMissing = true; // an api-impl.jar newer than the native library
		}
	}

	private static native void nativeSetClipBounds(long widget, boolean set, int left, int top, int right, int bottom);
	private static native void nativeSetOutlineClip(long widget, boolean set, int left, int top, int right, int bottom, float radius);

	/** rebuildOutline() on the next event loop pass (several changes in a row are folded) */
	private void tlPostRebuildOutline() {
		if (!tlClipToOutline || tlOutlineRebuildPosted)
			return;
		tlOutlineRebuildPosted = true;
		tlMainHandler().post(new Runnable() {
			@Override
			public void run() {
				tlOutlineRebuildPosted = false;
				tlRebuildOutline();
			}
		});
	}

	/**
	 * AOSP rebuildOutline(): the outline provider fills an Outline, which clips the view when
	 * clipToOutline is set. Round rects (rects) clip through the native widget; a path outline is not
	 * clipped (TODO-real). An empty outline would hide the view in AOSP; it is left unclipped here
	 * because ATL's Drawable.getOutline() still leaves most drawables' outlines empty.
	 */
	private void tlRebuildOutline() {
		if (widget == 0 || sTlClipNativeMissing)
			return;
		try {
			if (!tlClipToOutline || tlOutlineProvider == null) {
				nativeSetOutlineClip(widget, false, 0, 0, 0, 0, 0);
				return;
			}
			android.graphics.Outline outline = new android.graphics.Outline();
			outline.setAlpha(1.0f);
			tlOutlineProvider.getOutline(this, outline);
			if (outline.mMode == android.graphics.Outline.MODE_ROUND_RECT && !outline.mRect.isEmpty()) {
				final float radius = outline.mRadius > 0 ? outline.mRadius : 0;
				nativeSetOutlineClip(widget, true, outline.mRect.left, outline.mRect.top, outline.mRect.right, outline.mRect.bottom, radius);
			} else {
				nativeSetOutlineClip(widget, false, 0, 0, 0, 0, 0);
			}
		} catch (UnsatisfiedLinkError e) {
			sTlClipNativeMissing = true;
		}
	}

	public boolean isSaveEnabled() {
		return tlSaveEnabled;
	}

	public boolean isSaveFromParentEnabled() {
		return tlSaveFromParentEnabled;
	}

	public boolean isHapticFeedbackEnabled() {
		return tlHapticFeedbackEnabled;
	}

	/** AOSP: click/navigation sounds of this view (on by default) */
	public void setSoundEffectsEnabled(boolean soundEffectsEnabled) {
		tlSoundEffectsEnabled = soundEffectsEnabled;
	}

	public boolean isSoundEffectsEnabled() {
		return tlSoundEffectsEnabled;
	}

	public boolean isScrollContainer() {
		return tlScrollContainer;
	}

	/**
	 * The vibration of a haptic feedback constant, after AOSP's HapticFeedbackVibrationProvider
	 * defaults on a device without predefined effects: a tick for light feedback, a click for the
	 * rest, a heavier click for long presses.
	 */
	private static long tlHapticDuration(int feedbackConstant) {
		switch (feedbackConstant) {
			case 0:  // LONG_PRESS
			case 3:  // KEYBOARD_TAP (KEYBOARD_PRESS)
				return 30;
			case 4:  // CLOCK_TICK
			case 8:  // TEXT_HANDLE_MOVE
			case 9:  // ENTRY_BUMP
				return 5;
			default: // VIRTUAL_KEY, CONTEXT_CLICK, CONFIRM, REJECT, ...
				return 12;
		}
	}

	public boolean isTemporarilyDetached() {
		return tlTemporarilyDetached;
	}

	/** AOSP: called when the view is detached temporarily (a ListView recycles it); empty by default */
	public void onStartTemporaryDetach() {
	}

	/** AOSP: called when the temporary detach is over; empty by default */
	public void onFinishTemporaryDetach() {
	}

	/** AOSP: a ViewGroup passes the hotspot on to children that duplicate its state; View: nothing */
	public void dispatchDrawableHotspotChanged(float x, float y) {
	}

	public boolean isHorizontalFadingEdgeEnabled() {
		return tlHorizontalFadingEdge;
	}

	public boolean isVerticalFadingEdgeEnabled() {
		return tlVerticalFadingEdge;
	}

	public int getHorizontalFadingEdgeLength() {
		return tlHorizontalFadingEdge ? Math.max(tlFadingEdgeLength, 0) : 0;
	}

	/** AOSP: how strongly the top edge fades, 1 while the content is scrolled down */
	protected float getTopFadingEdgeStrength() {
		return computeVerticalScrollOffset() > 0 ? 1.0f : 0.0f;
	}

	/** AOSP: how strongly the bottom edge fades, 1 while there is more content below */
	protected float getBottomFadingEdgeStrength() {
		return computeVerticalScrollOffset() + computeVerticalScrollExtent() < computeVerticalScrollRange() ? 1.0f : 0.0f;
	}

	/** AOSP: how strongly the left edge fades, 1 while the content is scrolled right */
	protected float getLeftFadingEdgeStrength() {
		// AOSP: computeHorizontalScrollOffset() > 0; View's default offset is the scroll position
		return getScrollX() > 0 ? 1.0f : 0.0f;
	}

	/** AOSP: how strongly the right edge fades, 1 while there is more content to the right */
	protected float getRightFadingEdgeStrength() {
		return getScrollX() + computeHorizontalScrollExtent() < computeHorizontalScrollRange() ? 1.0f : 0.0f;
	}

	/** AOSP: the view's known solid background colour for fading edge drawing, 0 when unknown */
	public int getSolidColor() {
		return 0;
	}

	public boolean isHorizontalScrollBarEnabled() {
		return tlHorizontalScrollBar;
	}

	public boolean isVerticalScrollBarEnabled() {
		return tlVerticalScrollBar;
	}

	public int getScrollBarStyle() {
		return tlScrollBarStyle;
	}


	// ---- teslalinux (vcore): user-set focus order, as AOSP's View keeps it ----
	private int tlNextFocusLeftId = NO_ID, tlNextFocusRightId = NO_ID, tlNextFocusUpId = NO_ID, tlNextFocusDownId = NO_ID;
	private int tlNextFocusForwardId = NO_ID;
	private boolean tlKeyboardNavigationCluster;
	private boolean tlFocusedByDefault;

	public int getNextFocusLeftId() { return tlNextFocusLeftId; }
	public int getNextFocusRightId() { return tlNextFocusRightId; }
	public int getNextFocusUpId() { return tlNextFocusUpId; }
	public int getNextFocusDownId() { return tlNextFocusDownId; }
	/** AOSP: the view focused by FOCUS_FORWARD from this one (attribute nextFocusForward) */
	public int getNextFocusForwardId() { return tlNextFocusForwardId; }
	public void setNextFocusForwardId(int nextFocusForwardId) { tlNextFocusForwardId = nextFocusForwardId; }

	/** The focus attributes of AOSP's View constructor. */
	private void tlVcoreReadFocusAttrs(android.content.res.TypedArray a) {
		tlNextFocusLeftId = a.getResourceId(com.android.internal.R.styleable.View_nextFocusLeft, tlNextFocusLeftId);
		tlNextFocusRightId = a.getResourceId(com.android.internal.R.styleable.View_nextFocusRight, tlNextFocusRightId);
		tlNextFocusUpId = a.getResourceId(com.android.internal.R.styleable.View_nextFocusUp, tlNextFocusUpId);
		tlNextFocusDownId = a.getResourceId(com.android.internal.R.styleable.View_nextFocusDown, tlNextFocusDownId);
		tlNextFocusForwardId = a.getResourceId(com.android.internal.R.styleable.View_nextFocusForward, tlNextFocusForwardId);
		if (a.hasValue(com.android.internal.R.styleable.View_nextClusterForward))
			setNextClusterForwardId(a.getResourceId(com.android.internal.R.styleable.View_nextClusterForward, NO_ID));
		if (a.hasValue(com.android.internal.R.styleable.View_keyboardNavigationCluster))
			tlKeyboardNavigationCluster = a.getBoolean(com.android.internal.R.styleable.View_keyboardNavigationCluster, false);
		if (a.hasValue(com.android.internal.R.styleable.View_focusedByDefault))
			tlFocusedByDefault = a.getBoolean(com.android.internal.R.styleable.View_focusedByDefault, false);
	}

	/** AOSP: the view a focus move in this direction goes to by the user-set ids, null when none is set */
	View findUserSetNextFocus(View root, int direction) {
		switch (direction) {
			case FOCUS_LEFT:
				if (tlNextFocusLeftId == View.NO_ID)
					return null;
				return tlFindViewInsideOutShouldExist(root, this, tlNextFocusLeftId);
			case FOCUS_RIGHT:
				if (tlNextFocusRightId == View.NO_ID)
					return null;
				return tlFindViewInsideOutShouldExist(root, this, tlNextFocusRightId);
			case FOCUS_UP:
				if (tlNextFocusUpId == View.NO_ID)
					return null;
				return tlFindViewInsideOutShouldExist(root, this, tlNextFocusUpId);
			case FOCUS_DOWN:
				if (tlNextFocusDownId == View.NO_ID)
					return null;
				return tlFindViewInsideOutShouldExist(root, this, tlNextFocusDownId);
			case FOCUS_FORWARD:
				if (tlNextFocusForwardId == View.NO_ID)
					return null;
				return tlFindViewInsideOutShouldExist(root, this, tlNextFocusForwardId);
			case FOCUS_BACKWARD: {
				if (getId() == View.NO_ID)
					return null;
				final View rootView = root;
				final View startView = this;
				// Since we have forward links but no backward links, we need to find the view that
				// forward links to this view. We can't just find the view with the specified ID
				// because view IDs need not be unique throughout the tree.
				return root.findViewByPredicateInsideOut(startView, new java.util.function.Predicate<View>() {
					@Override
					public boolean test(View t) {
						return t.tlNextFocusForwardId != View.NO_ID && tlFindViewInsideOutShouldExist(rootView, t, t.tlNextFocusForwardId) == startView;
					}
				});
			}
		}
		return null;
	}

	/** AOSP: the next cluster by the user-set id (FOCUS_FORWARD) or the cluster that forward-links here */
	View findUserSetNextKeyboardNavigationCluster(View root, int direction) {
		switch (direction) {
			case FOCUS_FORWARD:
				if (getNextClusterForwardId() == View.NO_ID)
					return null;
				return tlFindViewInsideOutShouldExist(root, this, getNextClusterForwardId());
			case FOCUS_BACKWARD: {
				if (getId() == View.NO_ID)
					return null;
				final int id = getId();
				return root.findViewByPredicateInsideOut(this, new java.util.function.Predicate<View>() {
					@Override
					public boolean test(View t) {
						return t.getNextClusterForwardId() == id;
					}
				});
			}
		}
		return null;
	}

	private static View tlFindViewInsideOutShouldExist(View root, View start, final int id) {
		View result = root.findViewByPredicateInsideOut(start, new java.util.function.Predicate<View>() {
			@Override
			public boolean test(View v) {
				return v.getId() == id;
			}
		});
		if (result == null)
			android.util.Log.w("View", "couldn't find view with id " + id);
		return result;
	}

	/**
	 * AOSP: looks for a view matching the predicate, first in start's subtree, then in each ancestor's
	 * subtree (skipping the subtree already searched), up to this view.
	 */
	public final <T extends View> T findViewByPredicateInsideOut(View start, java.util.function.Predicate<View> predicate) {
		View childToSkip = null;
		for (;;) {
			T view = tlFindViewByPredicateTraversal(start, predicate, childToSkip);
			if (view != null || start == this) {
				return view;
			}
			ViewParent parent = start.getParent();
			if (parent == null || !(parent instanceof View)) {
				return null;
			}
			childToSkip = start;
			start = (View)parent;
		}
	}

	/** AOSP: the first view of this subtree (this view first) matching the predicate */
	public final <T extends View> T findViewByPredicate(java.util.function.Predicate<View> predicate) {
		return tlFindViewByPredicateTraversal(this, predicate, null);
	}

	/** AOSP View/ViewGroup.findViewByPredicateTraversal: the view, then its children in order */
	@SuppressWarnings("unchecked")
	private static <T extends View> T tlFindViewByPredicateTraversal(View v, java.util.function.Predicate<View> predicate, View childToSkip) {
		if (predicate.test(v))
			return (T)v;
		if (v instanceof ViewGroup) {
			ViewGroup g = (ViewGroup)v;
			final int n = g.getChildCount();
			for (int i = 0; i < n; i++) {
				View child = g.getChildAt(i);
				if (child == null || child == childToSkip)
					continue;
				T found = tlFindViewByPredicateTraversal(child, predicate, null);
				if (found != null)
					return found;
			}
		}
		return null;
	}

	// ---- teslalinux (vcore): plain View state AOSP keeps ----
	/* FOCUSABLE_AUTO, setFocusable(int) and getFocusable() are the focus model's (View.java.repl, tl_focus_model) */
	public static final int SCROLL_INDICATOR_TOP = 0x1;
	public static final int SCROLL_INDICATOR_BOTTOM = 0x2;
	public static final int SCROLL_INDICATOR_LEFT = 0x4;
	public static final int SCROLL_INDICATOR_RIGHT = 0x8;
	public static final int SCROLL_INDICATOR_START = 0x10;
	public static final int SCROLL_INDICATOR_END = 0x20;
	static final int SCROLL_INDICATORS_ALL = SCROLL_INDICATOR_TOP | SCROLL_INDICATOR_BOTTOM | SCROLL_INDICATOR_LEFT
						 | SCROLL_INDICATOR_RIGHT | SCROLL_INDICATOR_START | SCROLL_INDICATOR_END;
	private View.OnCreateContextMenuListener tlOnCreateContextMenuListener;
	private int tlAccessibilityLiveRegion;
	private int tlScrollIndicators;
	private String tlTransitionName;
	private boolean tlDefaultFocusHighlightEnabled = true;
	private CharSequence tlAccessibilityPaneTitle;
	private boolean tlAccessibilityHeading;
	private int tlLabelFor = NO_ID;
	private CharSequence tlStateDescription;
	private java.util.ArrayList<android.graphics.Rect> tlGestureExclusionRects;
	private java.util.ArrayList<android.graphics.Rect> tlKeepClearRects;
