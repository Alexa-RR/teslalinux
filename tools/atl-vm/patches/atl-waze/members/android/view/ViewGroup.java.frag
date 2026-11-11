	/* ---- teslalinux: ViewGroup members from AOSP that ATL lacked ---- */
	/** AOSP group flags (FLAG_IS_TRANSITION_GROUP* are already defined by ATL). */
	private int mGroupFlags;

	public static final int PERSISTENT_NO_CACHE = 0x0;
	public static final int PERSISTENT_ANIMATION_CACHE = 0x1;
	public static final int PERSISTENT_SCROLLING_CACHE = 0x2;
	public static final int PERSISTENT_ALL_CACHES = 0x3;
	private int mPersistentDrawingCache = PERSISTENT_SCROLLING_CACHE;

	public static final int LAYOUT_MODE_UNDEFINED = -1;
	public static final int LAYOUT_MODE_CLIP_BOUNDS = 0;
	public static final int LAYOUT_MODE_OPTICAL_BOUNDS = 1;
	public static final int LAYOUT_MODE_DEFAULT = LAYOUT_MODE_CLIP_BOUNDS;
	private int mLayoutMode = LAYOUT_MODE_UNDEFINED;

	private boolean mSuppressLayout;
	private boolean mLayoutCalledWhileSuppressed;
	private int mNestedScrollAxes;

	/** Deprecated drawing-cache policy; Android itself ignores it since API 28 (hardware rendering), only the value is kept. */
	public void setPersistentDrawingCache(int drawingCacheToKeep) {
		mPersistentDrawingCache = drawingCacheToKeep & PERSISTENT_ALL_CACHES;
	}

	public int getPersistentDrawingCache() {
		return mPersistentDrawingCache;
	}

	public void setLayoutMode(int layoutMode) {
		if (mLayoutMode != layoutMode) {
			mLayoutMode = layoutMode;
			requestLayout();
		}
	}

	public int getLayoutMode() {
		if (mLayoutMode == LAYOUT_MODE_UNDEFINED) {
			mLayoutMode = (getParent() instanceof ViewGroup) ? ((ViewGroup) getParent()).getLayoutMode() : LAYOUT_MODE_DEFAULT;
		}
		return mLayoutMode;
	}

	/** Tells this ViewGroup to suppress all layout() calls until layout suppression is disabled with a later call. */
	public void suppressLayout(boolean suppress) {
		mSuppressLayout = suppress;
		if (!suppress) {
			if (mLayoutCalledWhileSuppressed) {
				requestLayout();
				mLayoutCalledWhileSuppressed = false;
			}
		}
	}

	public boolean isLayoutSuppressed() {
		return mSuppressLayout;
	}

	public void setTransitionGroup(boolean isTransitionGroup) {
		mGroupFlags |= FLAG_IS_TRANSITION_GROUP_SET;
		if (isTransitionGroup) {
			mGroupFlags |= FLAG_IS_TRANSITION_GROUP;
		} else {
			mGroupFlags &= ~FLAG_IS_TRANSITION_GROUP;
		}
	}

	public int getNestedScrollAxes() {
		return mNestedScrollAxes;
	}

	/** Returns the index of the child to draw for this iteration; the default order is the child index. */
	protected int getChildDrawingOrder(int childCount, int drawingPosition) {
		return drawingPosition;
	}

	public final int getChildDrawingOrder(int drawingPosition) {
		return getChildDrawingOrder(getChildCount(), drawingPosition);
	}

	/** Removes a range of views during layout (no requestLayout / invalidate, as in AOSP). */
	public void removeViewsInLayout(int start, int count) {
		removeViews(start, count);
	}

	public void removeViews(int start, int count) {
		if (start < 0 || count < 0 || start + count > getChildCount()) {
			throw new IndexOutOfBoundsException();
		}
		java.util.ArrayList<View> toRemove = new java.util.ArrayList<View>();
		for (int i = start; i < start + count; i++) {
			toRemove.add(getChildAt(i));
		}
		for (View v : toRemove) {
			removeViewInternal(v);
		}
	}

	/** Offset a rectangle that is in our coordinate space into an ancestor descendant's coordinate space (AOSP offsetRectBetweenParentAndChild, inverse direction). */
	public final void offsetRectIntoDescendantCoords(View descendant, android.graphics.Rect rect) {
		View v = descendant;
		while (v != this) {
			if (v == null) {
				throw new IllegalArgumentException("parameter must be a descendant of this view");
			}
			rect.offset(v.getScrollX() - v.getLeft(), v.getScrollY() - v.getTop());
			v = (v.getParent() instanceof View) ? (View) v.getParent() : null;
		}
	}

	/** Called when a child wants a rectangle on screen; a plain ViewGroup cannot scroll, so false (AOSP default). */
	public boolean requestChildRectangleOnScreen(View child, android.graphics.Rect rectangle, boolean immediate) {
		return false;
	}

	/** Look for a descendant to call requestFocus on, in the direction of traversal (AOSP). */
	protected boolean onRequestFocusInDescendants(int direction, android.graphics.Rect previouslyFocusedRect) {
		int index;
		int increment;
		int end;
		int count = getChildCount();
		if ((direction & FOCUS_FORWARD) != 0) {
			index = 0;
			increment = 1;
			end = count;
		} else {
			index = count - 1;
			increment = -1;
			end = -1;
		}
		for (int i = index; i != end; i += increment) {
			View child = getChildAt(i);
			if (child.getVisibility() == VISIBLE) {
				if (child.requestFocus(direction, previouslyFocusedRect)) {
					return true;
				}
			}
		}
		return false;
	}

	/** Find the nearest view in the specified direction that wants to take focus (AOSP: delegated to the parent chain). */
	public View focusSearch(View focused, int direction) {
		if (getParent() instanceof ViewGroup) {
			return ((ViewGroup) getParent()).focusSearch(focused, direction);
		}
		return null;
	}

	@Override
	public boolean dispatchKeyEventPreIme(KeyEvent event) {
		View focused = getFocusedChild();
		if (focused != null) {
			return focused.dispatchKeyEventPreIme(event);
		}
		return super.dispatchKeyEventPreIme(event);
	}

	/** AOSP: a child became dirty; the dirty region is handled by the GTK widget tree here, the parent chain just propagates. */
	public ViewParent invalidateChildInParent(final int[] location, final android.graphics.Rect dirty) {
		invalidate();
		return getParent();
	}

	/** Adds the focusable views of this group and its children in traversal order (AOSP, honouring descendant focusability). */
	@Override
	public void addFocusables(java.util.ArrayList<View> views, int direction, int focusableMode) {
		final int focusableCount = views.size();
		final int descendantFocusability = getDescendantFocusability();
		final boolean blockFocusForTouchscreen = false;
		if (descendantFocusability == FOCUS_BLOCK_DESCENDANTS || blockFocusForTouchscreen) {
			if (isFocusable()) {
				super.addFocusables(views, direction, focusableMode);
			}
			return;
		}
		if (descendantFocusability == FOCUS_BEFORE_DESCENDANTS) {
			super.addFocusables(views, direction, focusableMode);
		}
		final int count = getChildCount();
		for (int i = 0; i < count; i++) {
			View child = getChildAt(i);
			if (child.getVisibility() == View.VISIBLE) {
				child.addFocusables(views, direction, focusableMode);
			}
		}
		// When set to FOCUS_AFTER_DESCENDANTS, we only add ourselves if there are no focusable descendants.
		if (descendantFocusability == FOCUS_AFTER_DESCENDANTS && focusableCount == views.size()) {
			super.addFocusables(views, direction, focusableMode);
		}
	}

	/** AOSP ViewGroup.invalidateChild (hardware-accelerated path): the dirty child is reported upwards. */
	public final void invalidateChild(View child, final android.graphics.Rect dirty) {
		onDescendantInvalidated(child, child);
	}

	private static int sDescendantTrace;
	/** AOSP: the aggregated visibility continues to the visible children. */
	@Override
	public boolean dispatchVisibilityAggregated(boolean isVisible) {
		isVisible = super.dispatchVisibilityAggregated(isVisible);
		final int count = getChildCount();
		for (int i = 0; i < count; i++) {
			View child = getChildAt(i);
			if (child.getVisibility() == VISIBLE) {
				child.dispatchVisibilityAggregated(isVisible);
			}
		}
		return isVisible;
	}
	/** AOSP: window focus reaches every child. */
	@Override
	public void dispatchWindowFocusChanged(boolean hasFocus) {
		super.dispatchWindowFocusChanged(hasFocus);
		final int count = getChildCount();
		for (int i = 0; i < count; i++) {
			getChildAt(i).dispatchWindowFocusChanged(hasFocus);
		}
	}

	/* ---- teslalinux: descendant focusability and focus clearing (tl_touch_mode) ---- */
	private static final int[] TL_DESCENDANT_FOCUSABILITY_FLAGS = {FOCUS_BEFORE_DESCENDANTS, FOCUS_AFTER_DESCENDANTS, FOCUS_BLOCK_DESCENDANTS};
	/** AOSP initViewGroup: FOCUS_BEFORE_DESCENDANTS */
	private int tlDescendantFocusability = FOCUS_BEFORE_DESCENDANTS;

	/** AOSP: sets how this group and its descendants share focus. */
	@Override
	public void setDescendantFocusability(int focusability) {
		switch (focusability) {
			case FOCUS_BEFORE_DESCENDANTS:
			case FOCUS_AFTER_DESCENDANTS:
			case FOCUS_BLOCK_DESCENDANTS:
				break;
			default:
				throw new IllegalArgumentException("must be one of FOCUS_BEFORE_DESCENDANTS, FOCUS_AFTER_DESCENDANTS, FOCUS_BLOCK_DESCENDANTS");
		}
		tlDescendantFocusability = focusability;
	}

	/** AOSP: a group whose child holds focus clears the focus there; otherwise the group itself gives it up. */
	@Override
	public void clearFocus() {
		View focused = getFocusedChild();
		if (focused == null)
			super.clearFocus();
		else
			focused.clearFocus();
	}

	/* ---- teslalinux: the AOSP focus model for groups (tl_focus_model, see View.java.frag) ---- */
	/** AOSP mFocused: the child that is or contains the focused view */
	View tlFocusedChild;

	/** AOSP: this group or a descendant is the focused view of its window. */
	@Override
	public boolean hasFocus() {
		return tlFocused || tlFocusedChild != null;
	}

	/** AOSP: this group when focused, else the focused view in its subtree. */
	@Override
	public View findFocus() {
		if (tlFocused)
			return this;
		return tlFocusedChild != null ? tlFocusedChild.findFocus() : null;
	}

	/** AOSP: the focused descendant gave up focus; so does the path from here up. */
	public void clearChildFocus(View child) {
		tlFocusedChild = null;
		if (getParent() instanceof ViewGroup)
			((ViewGroup) getParent()).clearChildFocus(this);
	}

	/** AOSP unFocus: the focused view below (or this group) loses focus. */
	@Override
	void tlUnFocus(View focused) {
		if (tlFocusedChild == null) {
			super.tlUnFocus(focused);
		} else {
			tlFocusedChild.tlUnFocus(focused);
			tlFocusedChild = null;
		}
	}

	/** AOSP: the group itself takes focus; a focused descendant gives it up first. */
	@Override
	void tlHandleFocusGainInternal(int direction, android.graphics.Rect previouslyFocusedRect) {
		if (tlFocusedChild != null) {
			tlFocusedChild.tlUnFocus(this);
			tlFocusedChild = null;
		}
		super.tlHandleFocusGainInternal(direction, previouslyFocusedRect);
	}

	/** AOSP: the group, its descendants or both may take focus, in the order descendantFocusability says. */
	@Override
	public boolean requestFocus(int direction, android.graphics.Rect previouslyFocusedRect) {
		switch (getDescendantFocusability()) {
			case FOCUS_BLOCK_DESCENDANTS:
				return super.requestFocus(direction, previouslyFocusedRect);
			case FOCUS_BEFORE_DESCENDANTS: {
				final boolean took = super.requestFocus(direction, previouslyFocusedRect);
				return took ? took : onRequestFocusInDescendants(direction, previouslyFocusedRect);
			}
			case FOCUS_AFTER_DESCENDANTS: {
				final boolean took = onRequestFocusInDescendants(direction, previouslyFocusedRect);
				return took ? took : super.requestFocus(direction, previouslyFocusedRect);
			}
			default:
				throw new IllegalStateException("descendant focusability must be one of FOCUS_BEFORE_DESCENDANTS, FOCUS_AFTER_DESCENDANTS, FOCUS_BLOCK_DESCENDANTS but is " + getDescendantFocusability());
		}
	}
