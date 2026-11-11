	/* ---- teslalinux: AOSP ViewParent members ATL lacked ---- */
	/** A child's drawing became invalid (AOSP). */
	public void invalidateChild(View child, android.graphics.Rect r);
	/** Software invalidation path (AOSP): returns the next parent to notify, or null. */
	public ViewParent invalidateChildInParent(int[] location, android.graphics.Rect r);
