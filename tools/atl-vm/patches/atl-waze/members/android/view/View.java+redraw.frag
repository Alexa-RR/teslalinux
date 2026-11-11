	/* ---- teslalinux: the window's redraw for a descendant (AOSP ViewRootImpl.onDescendantInvalidated) ----
	 * AOSP's ViewRootImpl answers onDescendantInvalidated with invalidate(): the next frame redraws
	 * the window. Compose moves and fades its layers (RenderNode position, translation, alpha) and
	 * then reports only onDescendantInvalidated(ownerView, ownerView), without View.invalidate(); the
	 * window root (WindowManagerImpl) queues the redraw of that view's widget through this. */

	/** The view's widget is drawn again on the next frame (its display list re-evaluated), without
	 * telling the parent chain: the caller is the end of that chain. */
	void tlQueueWindowRedraw() {
		if (widget != 0)
			nativeInvalidate(widget);
	}
