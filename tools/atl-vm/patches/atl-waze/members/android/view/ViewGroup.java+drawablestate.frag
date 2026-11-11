	/* ---- teslalinux: AOSP ViewGroup drawable state (selected/activated/pressed passed to children,
	 * addStatesFromChildren) ---- */
	private boolean tlAddStatesFromChildren;

	public boolean addStatesFromChildren() {
		return tlAddStatesFromChildren;
	}

	/** AOSP: a child's state changed; a group that shows its children's states rebuilds its own */
	public void childDrawableStateChanged(View child) {
		if (tlAddStatesFromChildren)
			refreshDrawableState();
	}

	@Override
	protected void dispatchSetSelected(boolean selected) {
		for (int i = 0; i < getChildCount(); i++)
			getChildAt(i).setSelected(selected);
	}

	@Override
	protected void dispatchSetActivated(boolean activated) {
		for (int i = 0; i < getChildCount(); i++)
			getChildAt(i).setActivated(activated);
	}

	/** AOSP: the union of the children's states when addStatesFromChildren is set */
	@Override
	protected int[] onCreateDrawableState(int extraSpace) {
		if (!tlAddStatesFromChildren)
			return super.onCreateDrawableState(extraSpace);
		int need = 0;
		for (int i = 0; i < getChildCount(); i++)
			need += getChildAt(i).getDrawableState().length;
		int[] state = super.onCreateDrawableState(extraSpace + need);
		for (int i = 0; i < getChildCount(); i++) {
			final int[] childState = getChildAt(i).getDrawableState();
			if (childState != null)
				state = mergeDrawableStates(state, childState);
		}
		return state;
	}
