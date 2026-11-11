/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.view;

import android.graphics.Rect;

/**
 * Helper class to handle situations where you want a view to have a larger touch area than its
 * actual view bounds. The view whose touch area is changed is called the delegate view. This class
 * should be used by an ancestor of the delegate. To use a TouchDelegate, first create an instance
 * that specifies the bounds that should be mapped to the delegate and the delegate view itself.
 * (AOSP, without the accessibility node info bridge.)
 */
public class TouchDelegate {
	/** View that should receive forwarded touch events */
	private View mDelegateView;
	/** Bounds in local coordinates of the containing view that should be mapped to the delegate view. */
	private Rect mBounds;
	/** mBounds inflated to include some slop. This rect is to track whether the motion events should be considered to be within the delegate view. */
	private Rect mSlopBounds;
	/** True if the delegate had been targeted on a down event (intersected mBounds). */
	private boolean mDelegateTargeted;
	private int mSlop;

	public static final int ABOVE = 1;
	public static final int BELOW = 2;
	public static final int TO_LEFT = 4;
	public static final int TO_RIGHT = 8;

	/**
	 * Constructor
	 *
	 * @param bounds Bounds in local coordinates of the containing view that should be mapped to
	 *        the delegate view
	 * @param delegateView The view that should receive motion events
	 */
	public TouchDelegate(Rect bounds, View delegateView) {
		mBounds = bounds;
		mSlop = ViewConfiguration.get(delegateView.getContext()).getScaledTouchSlop();
		mSlopBounds = new Rect(bounds);
		mSlopBounds.inset(-mSlop, -mSlop);
		mDelegateView = delegateView;
	}

	/**
	 * Forward touch events to the delegate view if the event is within the bounds specified in the
	 * constructor.
	 *
	 * @param event The touch event to forward
	 * @return True if the event was consumed by the delegate, false otherwise.
	 */
	public boolean onTouchEvent(MotionEvent event) {
		int x = (int) event.getX();
		int y = (int) event.getY();
		boolean sendToDelegate = false;
		boolean hit = true;
		boolean handled = false;

		switch (event.getActionMasked()) {
			case MotionEvent.ACTION_DOWN:
				mDelegateTargeted = mBounds.contains(x, y);
				sendToDelegate = mDelegateTargeted;
				break;
			case MotionEvent.ACTION_POINTER_DOWN:
			case MotionEvent.ACTION_POINTER_UP:
			case MotionEvent.ACTION_UP:
			case MotionEvent.ACTION_MOVE:
				sendToDelegate = mDelegateTargeted;
				if (sendToDelegate) {
					Rect slopBounds = mSlopBounds;
					if (!slopBounds.contains(x, y)) {
						hit = false;
					}
				}
				break;
			case MotionEvent.ACTION_CANCEL:
				sendToDelegate = mDelegateTargeted;
				mDelegateTargeted = false;
				break;
		}
		if (sendToDelegate) {
			final View delegateView = mDelegateView;
			if (hit) {
				// Offset event coordinates to be inside the target view
				event.setLocation(delegateView.getWidth() / 2, delegateView.getHeight() / 2);
			} else {
				// Offset event coordinates to be outside the target view (in case it does something like tracking pressed state)
				int slop = mSlop;
				event.setLocation(-(slop * 2), -(slop * 2));
			}
			handled = delegateView.dispatchTouchEvent(event);
		}
		return handled;
	}

	/**
	 * Forward hover events to the delegate view if the event is within the bounds specified in the
	 * constructor and touch exploration is enabled; there is no touch exploration service here.
	 */
	public boolean onTouchExplorationHoverEvent(MotionEvent event) {
		return false;
	}
}
