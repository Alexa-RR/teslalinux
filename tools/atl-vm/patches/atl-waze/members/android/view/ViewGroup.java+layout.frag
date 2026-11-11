	/* ---- teslalinux (layout): AOSP ViewGroup clipping, custom drawing order, view transitions,
	 * layout animations, motion-event splitting, focus propagation and window insets dispatch ----
	 * Copyright (C) 2006 The Android Open Source Project, Apache License 2.0 (logic ported from
	 * frameworks/base core/java/android/view/ViewGroup.java, android14-release). */

	/** AOSP initViewGroup: FLAG_CLIP_CHILDREN | FLAG_CLIP_TO_PADDING */
	private boolean tlClipChildren = true;
	private boolean tlClipToPadding = true;
	/** AOSP FLAG_USE_CHILD_DRAWING_ORDER */
	private boolean tlChildrenDrawingOrder;
	/** AOSP FLAG_SPLIT_MOTION_EVENTS: on for every app targeting Honeycomb or later */
	private boolean tlSplitMotionEvents = true;
	/** AOSP FLAG_TOUCHSCREEN_BLOCKS_FOCUS */
	private boolean tlTouchscreenBlocksFocus;
	/** AOSP mTransitioningViews: children whose removal is deferred (startViewTransition) */
	private ArrayList<View> tlTransitioningViews;
	/** AOSP mDisappearingChildren: removed children still drawn until their transition ends */
	private ArrayList<View> tlDisappearingChildren;
	/** the group still drawing a disappearing child */
	private static final java.util.WeakHashMap<View, ViewGroup> tlDisappearingParents = new java.util.WeakHashMap<View, ViewGroup>();
	/** AOSP mLayoutAnimationController / FLAG_RUN_ANIMATION / mAnimationListener */
	private android.view.animation.LayoutAnimationController tlLayoutAnimation;
	private boolean tlRunLayoutAnimation;
	private boolean tlLayoutAnimationPending;
	private android.view.animation.Animation.AnimationListener tlLayoutAnimationListener;
	/** the drawing order last applied to the children's widgets (custom drawing order) */
	private View[] tlAppliedOrder;

	/** AOSP RenderNode.setClipToBounds of a child, from this group's clipChildren (tl_layout_clip) */
	private static native void native_setChildClipsToBounds(long child, boolean clip);
	/** AOSP FLAG_CLIP_TO_PADDING: the children are clipped to the padding box when drawn (tl_layout_clip) */
	private static native void native_setPaddingClip(long widget, boolean enabled);

	/** AOSP ViewGroup.initFromAttributes, the attributes ATL did not read */
	private void tlInitFromAttributes(TypedArray a) {
		final int n = a.getIndexCount();
		for (int i = 0; i < n; i++) {
			final int attr = a.getIndex(i);
			if (attr == com.android.internal.R.styleable.ViewGroup_clipChildren) {
				tlClipChildren = a.getBoolean(attr, true);
			} else if (attr == com.android.internal.R.styleable.ViewGroup_clipToPadding) {
				tlClipToPadding = a.getBoolean(attr, true);
			} else if (attr == com.android.internal.R.styleable.ViewGroup_persistentDrawingCache) {
				setPersistentDrawingCache(a.getInt(attr, PERSISTENT_SCROLLING_CACHE));
			} else if (attr == com.android.internal.R.styleable.ViewGroup_addStatesFromChildren) {
				setAddStatesFromChildren(a.getBoolean(attr, false));
			} else if (attr == com.android.internal.R.styleable.ViewGroup_layoutAnimation) {
				final int id = a.getResourceId(attr, -1);
				if (id > 0)
					setLayoutAnimation(android.view.animation.AnimationUtils.loadLayoutAnimation(getContext(), id));
			} else if (attr == com.android.internal.R.styleable.ViewGroup_splitMotionEvents) {
				tlSplitMotionEvents = a.getBoolean(attr, false);
			} else if (attr == com.android.internal.R.styleable.ViewGroup_animateLayoutChanges) {
				if (a.getBoolean(attr, false))
					setLayoutTransition(new LayoutTransition());
			} else if (attr == com.android.internal.R.styleable.ViewGroup_layoutMode) {
				setLayoutMode(a.getInt(attr, LAYOUT_MODE_UNDEFINED));
			} else if (attr == com.android.internal.R.styleable.ViewGroup_transitionGroup) {
				setTransitionGroup(a.getBoolean(attr, false));
			} else if (attr == com.android.internal.R.styleable.ViewGroup_touchscreenBlocksFocus) {
				tlTouchscreenBlocksFocus = a.getBoolean(attr, false);
			}
		}
	}

	/** end of the constructor: the padding clip of this group follows clipToPadding */
	private void tlInitViewGroup() {
		native_setPaddingClip(widget, tlClipToPadding);
	}

	// ---- clipChildren / clipToPadding ----

	/**
	 * AOSP: whether the children are clipped to their bounds (each child's RenderNode clips to its
	 * bounds while its parent has clipChildren). Off lets a child, and the child's own descendants,
	 * draw outside the child (still inside this group unless its own parent lets it overflow).
	 */
	public void setClipChildren(boolean clipChildren) {
		if (clipChildren == tlClipChildren)
			return;
		tlClipChildren = clipChildren;
		for (int i = 0; i < children.size(); i++)
			native_setChildClipsToBounds(children.get(i).widget, clipChildren);
		invalidate();
	}

	public boolean getClipChildren() {
		return tlClipChildren;
	}

	/** AOSP: whether the children are clipped to this group's padding box when drawn (default on) */
	public void setClipToPadding(boolean clipToPadding) {
		if (clipToPadding == tlClipToPadding)
			return;
		tlClipToPadding = clipToPadding;
		native_setPaddingClip(widget, clipToPadding);
		invalidate();
	}

	public boolean getClipToPadding() {
		return tlClipToPadding;
	}

	/** a child joins: its drawing clips to its bounds as this group's clipChildren says */
	private void tlChildAdded(View child) {
		native_setChildClipsToBounds(child.widget, tlClipChildren);
	}

	/**
	 * before a child's widget joins this group: a child added back while it was disappearing (still
	 * drawn by the group it was removed from) stops disappearing, as AOSP addViewInner does; GTK
	 * gives a widget one parent, so another group lets go of the widget first
	 */
	private void tlBeforeChildAdded(View child) {
		final ViewGroup old = tlDisappearingParents.remove(child);
		if (old != null) {
			if (old.tlDisappearingChildren != null)
				old.tlDisappearingChildren.remove(child);
			if (old != this) {
				old.native_removeView(old.widget, child.widget);
				old.invalidate();
			}
		}
	}

	// ---- custom drawing order (AOSP getAndVerifyPreorderedIndex) ----

	/**
	 * AOSP: when enabled, getChildDrawingOrder(childCount, i) decides which child is drawn i-th
	 * (and, in reverse, which one gets touches first); Z still sorts on top of that order.
	 */
	protected void setChildrenDrawingOrderEnabled(boolean enabled) {
		if (enabled == tlChildrenDrawingOrder)
			return;
		tlChildrenDrawingOrder = enabled;
		// usually called from a constructor: the order is asked for once the group is set up
		tlScheduleDrawingOrder();
	}

	protected boolean isChildrenDrawingOrderEnabled() {
		return tlChildrenDrawingOrder;
	}

	/** a re-evaluation of the custom drawing order is posted */
	private boolean tlDrawingOrderPending;

	/**
	 * AOSP getAndVerifyPreorderedIndex for every drawing position: the custom drawing order, or
	 * null for the child order. AOSP asks getChildDrawingOrder while drawing, when the group's own
	 * bookkeeping is complete; here the order is asked for when children come and go and at layout,
	 * where a ViewPager may still be adding pages. An order that cannot be had yet (an exception, an
	 * index out of range or twice) stacks the children in child order for now and is asked for
	 * again on the next main-loop pass.
	 */
	private int[] tlCustomDrawingOrder(int childrenCount) {
		if (!tlChildrenDrawingOrder)
			return null;
		final int[] order = new int[childrenCount];
		final boolean[] seen = new boolean[childrenCount];
		try {
			for (int i = 0; i < childrenCount; i++) {
				final int childIndex = getChildDrawingOrder(childrenCount, i);
				if (childIndex < 0 || childIndex >= childrenCount || seen[childIndex])
					throw new IndexOutOfBoundsException("getChildDrawingOrder() returned invalid index " + childIndex + " (child count is " + childrenCount + ")");
				seen[childIndex] = true;
				order[i] = childIndex;
			}
		} catch (RuntimeException e) {
			tlScheduleDrawingOrder();
			return null;
		}
		return order;
	}

	private void tlScheduleDrawingOrder() {
		if (tlDrawingOrderPending)
			return;
		tlDrawingOrderPending = true;
		post(new Runnable() {
			@Override
			public void run() {
				tlDrawingOrderPending = false;
				tlApplyDrawingOrder();
			}
		});
	}

	/**
	 * Re-stacks the children's widgets when the drawing order (custom order and Z) differs from the
	 * order last applied. AOSP evaluates the order on every draw; here the widget order is the
	 * drawing order, so it is re-evaluated whenever it can have changed: children added, removed or
	 * brought to front, the custom order switched, and every layout of this group (ViewPager and
	 * SwipeRefreshLayout compute their order while measuring and laying out).
	 */
	private void tlApplyDrawingOrder() {
		if (!tlChildrenDrawingOrder && tlAppliedOrder == null)
			return; // the plain child (or Z) order, kept by the add/Z paths
		final ArrayList<View> ordered = tlOrderedChildren();
		final int n = ordered.size();
		boolean same = tlAppliedOrder != null && tlAppliedOrder.length == n;
		for (int i = 0; same && i < n; i++)
			same = tlAppliedOrder[i] == ordered.get(i);
		if (same)
			return;
		tlAppliedOrder = tlChildrenDrawingOrder ? ordered.toArray(new View[n]) : null;
		tlRestackChildren();
	}

	@Override
	public void layout(int l, int t, int r, int b) {
		super.layout(l, t, r, b);
		if (tlChildrenDrawingOrder)
			tlApplyDrawingOrder();
		if (tlRunLayoutAnimation && tlLayoutAnimation != null && !tlLayoutAnimationPending) {
			// AOSP runs the layout animation from the first dispatchDraw after the layout: the
			// children are bound to it once this layout pass has allocated them
			tlLayoutAnimationPending = true;
			post(new Runnable() {
				@Override
				public void run() {
					tlLayoutAnimationPending = false;
					tlBindLayoutAnimation();
				}
			});
		}
	}

	// ---- view transitions (AOSP startViewTransition / endViewTransition) ----

	/**
	 * AOSP: a child that is removed while in a transition (a fragment's exit animation) stays drawn
	 * until endViewTransition; its widget stays in the group, it just leaves the child list.
	 */
	public void startViewTransition(View view) {
		if (view.parent == this) {
			if (tlTransitioningViews == null)
				tlTransitioningViews = new ArrayList<View>();
			tlTransitioningViews.add(view);
		}
	}

	public void endViewTransition(View view) {
		if (tlTransitioningViews != null) {
			tlTransitioningViews.remove(view);
			final ArrayList<View> disappearing = tlDisappearingChildren;
			if (disappearing != null && disappearing.remove(view)) {
				tlDisappearingParents.remove(view);
				native_removeView(widget, view.widget);
				invalidate();
			}
		}
	}

	/** AOSP removeViewInternal -> addDisappearingView: whether the removal keeps the widget for now */
	private boolean tlKeepDisappearing(View child) {
		if (tlTransitioningViews == null || !tlTransitioningViews.contains(child))
			return false;
		if (tlDisappearingChildren == null)
			tlDisappearingChildren = new ArrayList<View>();
		if (!tlDisappearingChildren.contains(child))
			tlDisappearingChildren.add(child);
		tlDisappearingParents.put(child, this);
		return true;
	}

	/** removes the child's widget, or keeps it drawn while the child is in a view transition */
	private void tlRemoveChildWidget(View child) {
		if (!tlKeepDisappearing(child))
			native_removeView(widget, child.widget);
	}

	// ---- layout animation (AOSP setLayoutAnimation & co.) ----

	/** AOSP: the animation run on the children at the next layout of this group */
	public void setLayoutAnimation(android.view.animation.LayoutAnimationController controller) {
		tlLayoutAnimation = controller;
		if (controller != null)
			tlRunLayoutAnimation = true;
	}

	public android.view.animation.LayoutAnimationController getLayoutAnimation() {
		return tlLayoutAnimation;
	}

	/** AOSP: runs the layout animation at the next layout, and asks for that layout */
	public void startLayoutAnimation() {
		if (tlLayoutAnimation != null) {
			tlRunLayoutAnimation = true;
			requestLayout();
		}
	}

	/** AOSP: the layout animation runs after the next layout pass */
	public void scheduleLayoutAnimation() {
		if (tlLayoutAnimation != null)
			tlRunLayoutAnimation = true;
	}

	public void setLayoutAnimationListener(android.view.animation.Animation.AnimationListener animationListener) {
		tlLayoutAnimationListener = animationListener;
	}

	public android.view.animation.Animation.AnimationListener getLayoutAnimationListener() {
		return tlLayoutAnimationListener;
	}

	/** AOSP: whether the group can run its layout animation */
	protected boolean canAnimate() {
		return tlLayoutAnimation != null;
	}

	/** AOSP: hands the child its index and the child count, for the controller's delays */
	protected void attachLayoutAnimationParameters(View child, LayoutParams params, int index, int count) {
		android.view.animation.LayoutAnimationController.AnimationParameters animationParams = params.layoutAnimationParameters;
		if (animationParams == null) {
			animationParams = new android.view.animation.LayoutAnimationController.AnimationParameters();
			params.layoutAnimationParameters = animationParams;
		}
		animationParams.count = count;
		animationParams.index = index;
	}

	/** AOSP dispatchDraw, FLAG_RUN_ANIMATION: every visible child gets its delayed animation */
	private void tlBindLayoutAnimation() {
		if (!tlRunLayoutAnimation || !canAnimate())
			return;
		final android.view.animation.LayoutAnimationController controller = tlLayoutAnimation;
		final int count = children.size();
		final ArrayList<View> animated = new ArrayList<View>();
		for (int i = 0; i < count; i++) {
			final View child = children.get(i);
			if (child.getVisibility() == VISIBLE) {
				final LayoutParams params = child.getLayoutParams();
				attachLayoutAnimationParameters(child, params, i, count);
				final android.view.animation.Animation animation = controller.getAnimationForView(child);
				child.setAnimation(animation);
				animated.add(child);
			}
		}
		controller.start();
		tlRunLayoutAnimation = false;
		if (tlLayoutAnimationListener != null)
			tlLayoutAnimationListener.onAnimationStart(controller.getAnimation());
		// AOSP notifies the end once the controller is done and no child animates any more
		post(new Runnable() {
			@Override
			public void run() {
				boolean more = false;
				for (int i = 0; i < animated.size() && !more; i++) {
					final android.view.animation.Animation a = animated.get(i).getAnimation();
					more = a != null && !a.hasEnded();
				}
				if (more || !controller.isDone()) {
					postDelayed(this, 16);
					return;
				}
				if (tlLayoutAnimationListener != null)
					tlLayoutAnimationListener.onAnimationEnd(controller.getAnimation());
			}
		});
	}

	// ---- touch: motion event splitting ----

	/**
	 * AOSP: whether a second pointer that goes down on another child is sent to that child instead
	 * of joining the gesture of the first one. ATL's touch path picks the target of every event
	 * from the primary pointer (native_dispatchTouchEvent); the flag is kept for the app and its
	 * own dispatch logic.
	 */
	public void setMotionEventSplittingEnabled(boolean split) {
		tlSplitMotionEvents = split;
	}

	public boolean isMotionEventSplittingEnabled() {
		return tlSplitMotionEvents;
	}

	// ---- layout params ----

	/** AOSP: any non-null params fit a plain ViewGroup; subclasses check their own type */
	protected boolean checkLayoutParams(LayoutParams p) {
		return p != null;
	}

	/** AOSP: the child skips the forced layout it asked for (PFLAG_FORCE_LAYOUT is cleared) */
	protected void cleanupLayoutState(View child) {
		child.tlCleanupLayoutState();
	}

	/**
	 * AOSP: true, a plain ViewGroup is assumed to scroll, so its children delay their pressed state
	 * by the tap timeout (FrameLayout, LinearLayout, RelativeLayout and other layouts say false).
	 */
	public boolean shouldDelayChildPressedState() {
		return true;
	}

	/** AOSP: moves every child up or down (their tops and bottoms) */
	public void offsetChildrenTopAndBottom(int offset) {
		for (int i = 0; i < children.size(); i++)
			children.get(i).offsetTopAndBottom(offset);
		invalidate();
	}

	/** AOSP: offsets a rectangle in the descendant's coordinates into this group's coordinates */
	public final void offsetDescendantRectToMyCoords(View descendant, Rect rect) {
		View v = descendant;
		while (v != this) {
			if (v == null)
				throw new IllegalArgumentException("parameter must be a descendant of this view");
			rect.offset(v.getLeft() - v.getScrollX(), v.getTop() - v.getScrollY());
			v = (v.getParent() instanceof View) ? (View) v.getParent() : null;
		}
	}

	// ---- focus ----

	/* requestChildFocus is the focus model's (ViewGroup.java.repl, tl_focus_model) */

	/**
	 * AOSP: a descendant became focusable; the report travels up to the window root, which gives
	 * it focus when nothing has focus and the window is not in touch mode (ViewRootImpl).
	 */
	public void focusableViewAvailable(View v) {
		if (getDescendantFocusability() == FOCUS_BLOCK_DESCENDANTS || getVisibility() != VISIBLE)
			return;
		if (!isFocusableInTouchMode() && tlShouldBlockFocusForTouchscreen())
			return;
		if (isFocused() && getDescendantFocusability() != FOCUS_AFTER_DESCENDANTS)
			return;
		if (parent instanceof ViewGroup) {
			((ViewGroup) parent).focusableViewAvailable(v);
		} else if (!(parent instanceof View) && isAttachedToWindow()) {
			// AOSP ViewRootImpl.focusableViewAvailable, this view being the window's root
			if (!hasFocus()) {
				if (!isInTouchMode())
					v.requestFocus();
			} else {
				final View focused = findFocus();
				if (focused instanceof ViewGroup && ((ViewGroup) focused).getDescendantFocusability() == FOCUS_AFTER_DESCENDANTS && tlIsDescendantOf(v, focused))
					v.requestFocus();
			}
		}
	}

	private static boolean tlIsDescendantOf(View child, View ancestor) {
		for (ViewParent p = child.getParent(); p != null; p = p.getParent()) {
			if (p == ancestor)
				return true;
		}
		return false;
	}

	/** AOSP: on a touchscreen, a group that blocks focus keeps its descendants from taking focus */
	public void setTouchscreenBlocksFocus(boolean touchscreenBlocksFocus) {
		tlTouchscreenBlocksFocus = touchscreenBlocksFocus;
	}

	public boolean getTouchscreenBlocksFocus() {
		return tlTouchscreenBlocksFocus;
	}

	/**
	 * AOSP shouldBlockFocusForTouchscreen: the car screen is a touchscreen (FEATURE_TOUCHSCREEN), so
	 * the flag blocks, except for a keyboard navigation cluster that holds the focus.
	 */
	private boolean tlShouldBlockFocusForTouchscreen() {
		return tlTouchscreenBlocksFocus && !(isKeyboardNavigationCluster() && hasFocus());
	}

	// ---- window insets (AOSP dispatchApplyWindowInsets, the non-broken dispatch of API 30+) ----

	/** AOSP: the group applies the insets itself, then every child gets them, unless consumed */
	@Override
	public WindowInsets dispatchApplyWindowInsets(WindowInsets insets) {
		insets = super.dispatchApplyWindowInsets(insets);
		if (insets.isConsumed())
			return insets;
		final int count = getChildCount();
		for (int i = 0; i < count; i++)
			getChildAt(i).dispatchApplyWindowInsets(insets);
		return insets;
	}

	/** AOSP: the group and every descendant use the framework's optional fitsSystemWindows */
	@Override
	public void makeOptionalFitsSystemWindows() {
		super.makeOptionalFitsSystemWindows();
		final int count = getChildCount();
		for (int i = 0; i < count; i++)
			getChildAt(i).makeOptionalFitsSystemWindows();
	}

	// ---- key shortcuts and trackball events (AOSP: the focused view, or the child holding it) ----

	@Override
	public boolean dispatchKeyShortcutEvent(KeyEvent event) {
		if (isFocused())
			return super.dispatchKeyShortcutEvent(event);
		final View focused = getFocusedChild();
		return focused != null && focused.dispatchKeyShortcutEvent(event);
	}

	@Override
	public boolean dispatchTrackballEvent(MotionEvent event) {
		if (isFocused())
			return super.dispatchTrackballEvent(event);
		final View focused = getFocusedChild();
		return focused != null && focused.dispatchTrackballEvent(event);
	}

	// ---- drawing caches (AOSP, deprecated since API 28: with hardware rendering they are flags) ----
	private boolean tlAlwaysDrawnWithCache = true;
	private boolean tlChildrenDrawnWithCache;
	private boolean tlAnimationCache = true;

	/** AOSP: deprecated; the flag is kept, as AOSP keeps it with hardware rendering */
	public void setAlwaysDrawnWithCacheEnabled(boolean always) {
		tlAlwaysDrawnWithCache = always;
	}

	public boolean isAlwaysDrawnWithCacheEnabled() {
		return tlAlwaysDrawnWithCache;
	}

	protected void setChildrenDrawnWithCacheEnabled(boolean enabled) {
		tlChildrenDrawnWithCache = enabled;
	}

	protected boolean isChildrenDrawnWithCacheEnabled() {
		return tlChildrenDrawnWithCache;
	}

	/** AOSP: deprecated; the flag is kept */
	public void setAnimationCacheEnabled(boolean enabled) {
		tlAnimationCache = enabled;
	}

	public boolean isAnimationCacheEnabled() {
		return tlAnimationCache;
	}

	/** AOSP: every child's drawing cache follows, unless all caches are to be kept */
	protected void setChildrenDrawingCacheEnabled(boolean enabled) {
		if (enabled || (getPersistentDrawingCache() & PERSISTENT_ALL_CACHES) != PERSISTENT_ALL_CACHES) {
			for (int i = 0; i < children.size(); i++)
				children.get(i).setDrawingCacheEnabled(enabled);
		}
	}
