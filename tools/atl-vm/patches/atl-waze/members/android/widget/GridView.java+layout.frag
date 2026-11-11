	/* ---- teslalinux (layout): AOSP GridView getters, keys and layout animation parameters ----
	 * Copyright (C) 2006 The Android Open Source Project, Apache License 2.0 (frameworks/base
	 * core/java/android/widget/GridView.java, android14-release). */

	/** AOSP: the number of columns, or AUTO_FIT before the first layout of an auto-fit grid */
	public int getNumColumns() {
		return mNumColumns;
	}

	/** AOSP: the requested column width (setColumnWidth / android:columnWidth) */
	public int getRequestedColumnWidth() {
		return mRequestedColumnWidth;
	}

	/** AOSP: the column width the last layout used */
	public int getColumnWidth() {
		return mColumnWidth;
	}

	/** AOSP: the horizontal spacing the last layout used (stretching may widen the requested one) */
	public int getHorizontalSpacing() {
		return mHorizontalSpacing;
	}

	public int getRequestedHorizontalSpacing() {
		return mRequestedHorizontalSpacing;
	}

	public int getVerticalSpacing() {
		return mVerticalSpacing;
	}

	public int getGravity() {
		return mGravity;
	}

	@Override
	public boolean onKeyDown(int keyCode, KeyEvent event) {
		return tlCommonKey(keyCode, 1, event);
	}

	public boolean onKeyMultiple(int keyCode, int repeatCount, KeyEvent event) {
		return tlCommonKey(keyCode, repeatCount, event);
	}

	@Override
	public boolean onKeyUp(int keyCode, KeyEvent event) {
		return tlCommonKey(keyCode, 1, event);
	}

	/** AOSP GridView.commonKey: arrows move the selection by cell, row or page */
	private boolean tlCommonKey(int keyCode, int count, KeyEvent event) {
		if (mAdapter == null) {
			return false;
		}
		if (mDataChanged) {
			layoutChildren();
		}
		boolean handled = false;
		int action = event.getAction();
		if (!handled && action != KeyEvent.ACTION_UP) {
			switch (keyCode) {
				case KeyEvent.KEYCODE_DPAD_LEFT:
					if (event.hasNoModifiers())
						handled = resurrectSelectionIfNeeded() || arrowScroll(FOCUS_LEFT);
					break;
				case KeyEvent.KEYCODE_DPAD_RIGHT:
					if (event.hasNoModifiers())
						handled = resurrectSelectionIfNeeded() || arrowScroll(FOCUS_RIGHT);
					break;
				case KeyEvent.KEYCODE_DPAD_UP:
					if (event.hasNoModifiers())
						handled = resurrectSelectionIfNeeded() || arrowScroll(FOCUS_UP);
					else if (event.hasModifiers(KeyEvent.META_ALT_ON))
						handled = resurrectSelectionIfNeeded() || fullScroll(FOCUS_UP);
					break;
				case KeyEvent.KEYCODE_DPAD_DOWN:
					if (event.hasNoModifiers())
						handled = resurrectSelectionIfNeeded() || arrowScroll(FOCUS_DOWN);
					else if (event.hasModifiers(KeyEvent.META_ALT_ON))
						handled = resurrectSelectionIfNeeded() || fullScroll(FOCUS_DOWN);
					break;
				case KeyEvent.KEYCODE_PAGE_UP:
					if (event.hasNoModifiers())
						handled = resurrectSelectionIfNeeded() || pageScroll(FOCUS_UP);
					else if (event.hasModifiers(KeyEvent.META_ALT_ON))
						handled = resurrectSelectionIfNeeded() || fullScroll(FOCUS_UP);
					break;
				case KeyEvent.KEYCODE_PAGE_DOWN:
					if (event.hasNoModifiers())
						handled = resurrectSelectionIfNeeded() || pageScroll(FOCUS_DOWN);
					else if (event.hasModifiers(KeyEvent.META_ALT_ON))
						handled = resurrectSelectionIfNeeded() || fullScroll(FOCUS_DOWN);
					break;
				case KeyEvent.KEYCODE_MOVE_HOME:
					if (event.hasNoModifiers())
						handled = resurrectSelectionIfNeeded() || fullScroll(FOCUS_UP);
					break;
				case KeyEvent.KEYCODE_MOVE_END:
					if (event.hasNoModifiers())
						handled = resurrectSelectionIfNeeded() || fullScroll(FOCUS_DOWN);
					break;
				case KeyEvent.KEYCODE_TAB:
					if (event.hasNoModifiers())
						handled = resurrectSelectionIfNeeded() || tlSequenceScroll(FOCUS_FORWARD);
					else if (event.hasModifiers(KeyEvent.META_SHIFT_ON))
						handled = resurrectSelectionIfNeeded() || tlSequenceScroll(FOCUS_BACKWARD);
					break;
			}
		}
		if (handled) {
			return true;
		}
		if (sendToTextFilter(keyCode, count, event)) {
			return true;
		}
		switch (action) {
			case KeyEvent.ACTION_DOWN:
				return super.onKeyDown(keyCode, event);
			case KeyEvent.ACTION_UP:
				return super.onKeyUp(keyCode, event);
			default:
				return false;
		}
	}

	/** AOSP sequenceScroll: TAB walks the items in adapter order */
	private boolean tlSequenceScroll(int direction) {
		int selectedPosition = mSelectedPosition;
		int count = mItemCount;
		boolean moved = false;
		switch (direction) {
			case FOCUS_FORWARD:
				if (selectedPosition < count - 1) {
					mLayoutMode = LAYOUT_MOVE_SELECTION;
					setSelectionInt(selectedPosition + 1);
					moved = true;
				}
				break;
			case FOCUS_BACKWARD:
				if (selectedPosition > 0) {
					mLayoutMode = LAYOUT_MOVE_SELECTION;
					setSelectionInt(selectedPosition - 1);
					moved = true;
				}
				break;
		}
		if (moved) {
			invokeOnItemScrollListener();
		}
		return moved;
	}

	/** AOSP: a grid layout animation gets the row and column of each child */
	@Override
	protected void attachLayoutAnimationParameters(View child, ViewGroup.LayoutParams params, int index, int count) {
		android.view.animation.GridLayoutAnimationController.AnimationParameters animationParams = (android.view.animation.GridLayoutAnimationController.AnimationParameters) params.layoutAnimationParameters;
		if (animationParams == null) {
			animationParams = new android.view.animation.GridLayoutAnimationController.AnimationParameters();
			params.layoutAnimationParameters = animationParams;
		}
		animationParams.count = count;
		animationParams.index = index;
		animationParams.columnsCount = mNumColumns;
		animationParams.rowsCount = count / mNumColumns;
		if (!mStackFromBottom) {
			animationParams.column = index % mNumColumns;
			animationParams.row = index / mNumColumns;
		} else {
			final int invertedIndex = count - 1 - index;
			animationParams.column = mNumColumns - 1 - (invertedIndex % mNumColumns);
			animationParams.row = animationParams.rowsCount - 1 - invertedIndex / mNumColumns;
		}
	}
