	/* ---- teslalinux: children drawn in Z order (AOSP ViewGroup.buildOrderedChildList) ----
	 * AOSP draws (and dispatches touches to, in reverse) the children sorted by Z, stable, so equal
	 * Z keeps child order; the child list itself (getChildAt) stays in insertion order. Here the
	 * order of the children's GTK widgets is the drawing order (native_drawChildren snapshots them in
	 * sibling order, and GTK picks the topmost), so it is kept equal to the ordered child list: after
	 * an add and whenever a child's Z changes. With every Z at 0 that order is the child order. */

	/** AOSP buildOrderedChildList: stable insertion by Z */
	private ArrayList<View> tlOrderedChildren() {
		final int n = children.size();
		final ArrayList<View> list = new ArrayList<View>(n);
		for (int i = 0; i < n; i++) {
			final View child = children.get(i);
			final float z = child.getZ();
			int insert = list.size();
			while (insert > 0 && list.get(insert - 1).getZ() > z)
				insert--;
			list.add(insert, child);
		}
		return list;
	}

	/** re-stacks the children's widgets in drawing order (last drawn on top) */
	void tlRestackChildren() {
		final ArrayList<View> ordered = tlOrderedChildren();
		long next = 0;
		for (int i = ordered.size() - 1; i >= 0; i--) {
			final View child = ordered.get(i);
			native_restackChild(widget, child.widget, next);
			next = child.widget;
		}
		invalidate();
	}

	/** whether any child has a Z (only then can the drawing order differ from the child order) */
	private boolean tlAnyChildZ() {
		for (int i = 0; i < children.size(); i++) {
			if (children.get(i).getZ() != 0f)
				return true;
		}
		return false;
	}

	/** moves child's widget right before next's (to the end when next is 0), unless already there */
	private native void native_restackChild(long widget, long child, long next);
