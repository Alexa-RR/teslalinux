	/* ---- teslalinux: the decor view answers for ViewRootImpl at the top of the chain ----
	 * AOSP's decor view has the window's ViewRootImpl as parent, and ViewRootImpl answers
	 * onDescendantInvalidated with invalidate(): the next frame redraws the window. Here an
	 * activity's decor is the root of a GTK window with no ViewParent above it (a window added
	 * through WindowManagerImpl gets that class's root parent instead), so the chain ended at the
	 * decor and nothing was redrawn. Compose moves and fades its layers (RenderNode position,
	 * translation, alpha: an AnimatedVisibility slide, a drawer) and reports only
	 * onDescendantInvalidated(ownerView, ownerView), so those changes showed only when something
	 * else redrew: Waze's menu flashed open at its end position and vanished at once on close. */
	private static final class TlDecorView extends FrameLayout {
		TlDecorView(Context context) {
			super(context);
		}

		@Override
		public void onDescendantInvalidated(View child, View target) {
			if (getParent() != null) {
				super.onDescendantInvalidated(child, target);
				return;
			}
			// ViewRootImpl.invalidate(): the target's widget is drawn again on the next frame
			if (target != null)
				target.tlQueueWindowRedraw();
			else if (child != null)
				child.tlQueueWindowRedraw();
		}
	}
