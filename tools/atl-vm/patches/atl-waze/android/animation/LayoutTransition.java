/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.animation;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * teslalinux: AOSP's LayoutTransition (automatic animations of a ViewGroup's layout changes): the
 * default animators, durations, delays, staggers and interpolators of AOSP; APPEARING fades a new
 * child in, DISAPPEARING fades a removed one out, CHANGE_APPEARING / CHANGE_DISAPPEARING / CHANGING
 * animate the other children (and, with animateParentHierarchy, the parents) from their old to
 * their new bounds when the next layout moves them.
 *
 * The ViewGroup drives it as on Android: addView -> addChild, removeView -> removeChild (the child
 * stays drawn through startViewTransition/endViewTransition while it fades), setVisibility ->
 * showChild/hideChild, layout -> layoutChange.
 */
public class LayoutTransition {

	/** The other children move because a child appears. */
	public static final int CHANGE_APPEARING = 0;
	/** The other children move because a child disappears. */
	public static final int CHANGE_DISAPPEARING = 1;
	/** A child appears. */
	public static final int APPEARING = 2;
	/** A child disappears. */
	public static final int DISAPPEARING = 3;
	/** Children change for a reason other than a child appearing or disappearing (off by default). */
	public static final int CHANGING = 4;

	private static final int FLAG_APPEARING = 0x01;
	private static final int FLAG_DISAPPEARING = 0x02;
	private static final int FLAG_CHANGE_APPEARING = 0x04;
	private static final int FLAG_CHANGE_DISAPPEARING = 0x08;
	private static final int FLAG_CHANGING = 0x10;

	private static long DEFAULT_DURATION = 300;

	private static ObjectAnimator defaultChange;
	private static ObjectAnimator defaultChangeIn;
	private static ObjectAnimator defaultChangeOut;
	private static ObjectAnimator defaultFadeIn;
	private static ObjectAnimator defaultFadeOut;

	private Animator mDisappearingAnim = null;
	private Animator mAppearingAnim = null;
	private Animator mChangingAppearingAnim = null;
	private Animator mChangingDisappearingAnim = null;
	private Animator mChangingAnim = null;

	private long mChangingAppearingDuration = DEFAULT_DURATION;
	private long mChangingDisappearingDuration = DEFAULT_DURATION;
	private long mChangingDuration = DEFAULT_DURATION;
	private long mAppearingDuration = DEFAULT_DURATION;
	private long mDisappearingDuration = DEFAULT_DURATION;

	private long mAppearingDelay = DEFAULT_DURATION;
	private long mDisappearingDelay = 0;
	private long mChangingAppearingDelay = 0;
	private long mChangingDisappearingDelay = DEFAULT_DURATION;
	private long mChangingDelay = 0;

	private long mChangingAppearingStagger = 0;
	private long mChangingDisappearingStagger = 0;
	private long mChangingStagger = 0;

	private static TimeInterpolator ACCEL_DECEL_INTERPOLATOR = new AccelerateDecelerateInterpolator();
	private static TimeInterpolator DECEL_INTERPOLATOR = new DecelerateInterpolator();
	private static TimeInterpolator sAppearingInterpolator = ACCEL_DECEL_INTERPOLATOR;
	private static TimeInterpolator sDisappearingInterpolator = ACCEL_DECEL_INTERPOLATOR;
	private static TimeInterpolator sChangingAppearingInterpolator = DECEL_INTERPOLATOR;
	private static TimeInterpolator sChangingDisappearingInterpolator = DECEL_INTERPOLATOR;
	private static TimeInterpolator sChangingInterpolator = DECEL_INTERPOLATOR;

	private TimeInterpolator mAppearingInterpolator = sAppearingInterpolator;
	private TimeInterpolator mDisappearingInterpolator = sDisappearingInterpolator;
	private TimeInterpolator mChangingAppearingInterpolator = sChangingAppearingInterpolator;
	private TimeInterpolator mChangingDisappearingInterpolator = sChangingDisappearingInterpolator;
	private TimeInterpolator mChangingInterpolator = sChangingInterpolator;

	/** Change animations set up and waiting for the layout that starts them. */
	private final HashMap<View, Animator> pendingAnimations = new HashMap<View, Animator>();
	private final LinkedHashMap<View, Animator> currentChangingAnimations = new LinkedHashMap<View, Animator>();
	private final LinkedHashMap<View, Animator> currentAppearingAnimations = new LinkedHashMap<View, Animator>();
	private final LinkedHashMap<View, Animator> currentDisappearingAnimations = new LinkedHashMap<View, Animator>();
	private final HashMap<View, View.OnLayoutChangeListener> layoutChangeListenerMap = new HashMap<View, View.OnLayoutChangeListener>();

	private int mTransitionTypes = FLAG_CHANGE_APPEARING | FLAG_CHANGE_DISAPPEARING | FLAG_APPEARING | FLAG_DISAPPEARING;

	private long staggerDelay;
	private ArrayList<TransitionListener> mListeners;
	private boolean mAnimateParentHierarchy = true;

	/** Constructs a LayoutTransition object with the default animators. */
	public LayoutTransition() {
		if (defaultChangeIn == null) {
			// "left" is just a placeholder; we'll put real properties/values in when needed
			PropertyValuesHolder pvhLeft = PropertyValuesHolder.ofInt("left", 0, 1);
			PropertyValuesHolder pvhTop = PropertyValuesHolder.ofInt("top", 0, 1);
			PropertyValuesHolder pvhRight = PropertyValuesHolder.ofInt("right", 0, 1);
			PropertyValuesHolder pvhBottom = PropertyValuesHolder.ofInt("bottom", 0, 1);
			PropertyValuesHolder pvhScrollX = PropertyValuesHolder.ofInt("scrollX", 0, 1);
			PropertyValuesHolder pvhScrollY = PropertyValuesHolder.ofInt("scrollY", 0, 1);
			defaultChangeIn = ObjectAnimator.ofPropertyValuesHolder((Object) null, pvhLeft, pvhTop, pvhRight, pvhBottom, pvhScrollX, pvhScrollY);
			defaultChangeIn.setDuration(DEFAULT_DURATION);
			defaultChangeIn.setStartDelay(0);
			defaultChangeIn.setInterpolator(sChangingAppearingInterpolator);
			defaultChangeOut = defaultChangeIn.clone();
			defaultChangeOut.setStartDelay(DEFAULT_DURATION);
			defaultChangeOut.setInterpolator(sChangingDisappearingInterpolator);
			defaultChange = defaultChangeIn.clone();
			defaultChange.setStartDelay(0);
			defaultChange.setInterpolator(sChangingInterpolator);

			defaultFadeIn = ObjectAnimator.ofFloat(null, "alpha", 0f, 1f);
			defaultFadeIn.setDuration(DEFAULT_DURATION);
			defaultFadeIn.setStartDelay(DEFAULT_DURATION);
			defaultFadeIn.setInterpolator(sAppearingInterpolator);
			defaultFadeOut = ObjectAnimator.ofFloat(null, "alpha", 1f, 0f);
			defaultFadeOut.setDuration(DEFAULT_DURATION);
			defaultFadeOut.setStartDelay(0);
			defaultFadeOut.setInterpolator(sDisappearingInterpolator);
		}
		mChangingAppearingAnim = defaultChangeIn;
		mChangingDisappearingAnim = defaultChangeOut;
		mChangingAnim = defaultChange;
		mAppearingAnim = defaultFadeIn;
		mDisappearingAnim = defaultFadeOut;
	}

	/** Sets the duration of all the transition animations. */
	public void setDuration(long duration) {
		mChangingAppearingDuration = duration;
		mChangingDisappearingDuration = duration;
		mChangingDuration = duration;
		mAppearingDuration = duration;
		mDisappearingDuration = duration;
	}

	/** Enables the given transition type for subsequent changes. */
	public void enableTransitionType(int transitionType) {
		switch (transitionType) {
			case APPEARING: mTransitionTypes |= FLAG_APPEARING; break;
			case DISAPPEARING: mTransitionTypes |= FLAG_DISAPPEARING; break;
			case CHANGE_APPEARING: mTransitionTypes |= FLAG_CHANGE_APPEARING; break;
			case CHANGE_DISAPPEARING: mTransitionTypes |= FLAG_CHANGE_DISAPPEARING; break;
			case CHANGING: mTransitionTypes |= FLAG_CHANGING; break;
		}
	}

	/** Disables the given transition type for subsequent changes. */
	public void disableTransitionType(int transitionType) {
		switch (transitionType) {
			case APPEARING: mTransitionTypes &= ~FLAG_APPEARING; break;
			case DISAPPEARING: mTransitionTypes &= ~FLAG_DISAPPEARING; break;
			case CHANGE_APPEARING: mTransitionTypes &= ~FLAG_CHANGE_APPEARING; break;
			case CHANGE_DISAPPEARING: mTransitionTypes &= ~FLAG_CHANGE_DISAPPEARING; break;
			case CHANGING: mTransitionTypes &= ~FLAG_CHANGING; break;
		}
	}

	/** Whether the given transition type is enabled. */
	public boolean isTransitionTypeEnabled(int transitionType) {
		switch (transitionType) {
			case APPEARING: return (mTransitionTypes & FLAG_APPEARING) == FLAG_APPEARING;
			case DISAPPEARING: return (mTransitionTypes & FLAG_DISAPPEARING) == FLAG_DISAPPEARING;
			case CHANGE_APPEARING: return (mTransitionTypes & FLAG_CHANGE_APPEARING) == FLAG_CHANGE_APPEARING;
			case CHANGE_DISAPPEARING: return (mTransitionTypes & FLAG_CHANGE_DISAPPEARING) == FLAG_CHANGE_DISAPPEARING;
			case CHANGING: return (mTransitionTypes & FLAG_CHANGING) == FLAG_CHANGING;
		}
		return false;
	}

	public void setStartDelay(int transitionType, long delay) {
		switch (transitionType) {
			case CHANGE_APPEARING: mChangingAppearingDelay = delay; break;
			case CHANGE_DISAPPEARING: mChangingDisappearingDelay = delay; break;
			case CHANGING: mChangingDelay = delay; break;
			case APPEARING: mAppearingDelay = delay; break;
			case DISAPPEARING: mDisappearingDelay = delay; break;
		}
	}

	public long getStartDelay(int transitionType) {
		switch (transitionType) {
			case CHANGE_APPEARING: return mChangingAppearingDelay;
			case CHANGE_DISAPPEARING: return mChangingDisappearingDelay;
			case CHANGING: return mChangingDelay;
			case APPEARING: return mAppearingDelay;
			case DISAPPEARING: return mDisappearingDelay;
		}
		return 0;
	}

	public void setDuration(int transitionType, long duration) {
		switch (transitionType) {
			case CHANGE_APPEARING: mChangingAppearingDuration = duration; break;
			case CHANGE_DISAPPEARING: mChangingDisappearingDuration = duration; break;
			case CHANGING: mChangingDuration = duration; break;
			case APPEARING: mAppearingDuration = duration; break;
			case DISAPPEARING: mDisappearingDuration = duration; break;
		}
	}

	public long getDuration(int transitionType) {
		switch (transitionType) {
			case CHANGE_APPEARING: return mChangingAppearingDuration;
			case CHANGE_DISAPPEARING: return mChangingDisappearingDuration;
			case CHANGING: return mChangingDuration;
			case APPEARING: return mAppearingDuration;
			case DISAPPEARING: return mDisappearingDuration;
		}
		return DEFAULT_DURATION;
	}

	public void setStagger(int transitionType, long duration) {
		switch (transitionType) {
			case CHANGE_APPEARING: mChangingAppearingStagger = duration; break;
			case CHANGE_DISAPPEARING: mChangingDisappearingStagger = duration; break;
			case CHANGING: mChangingStagger = duration; break;
		}
	}

	public long getStagger(int transitionType) {
		switch (transitionType) {
			case CHANGE_APPEARING: return mChangingAppearingStagger;
			case CHANGE_DISAPPEARING: return mChangingDisappearingStagger;
			case CHANGING: return mChangingStagger;
		}
		return 0;
	}

	public void setInterpolator(int transitionType, TimeInterpolator interpolator) {
		switch (transitionType) {
			case CHANGE_APPEARING: mChangingAppearingInterpolator = interpolator; break;
			case CHANGE_DISAPPEARING: mChangingDisappearingInterpolator = interpolator; break;
			case CHANGING: mChangingInterpolator = interpolator; break;
			case APPEARING: mAppearingInterpolator = interpolator; break;
			case DISAPPEARING: mDisappearingInterpolator = interpolator; break;
		}
	}

	public TimeInterpolator getInterpolator(int transitionType) {
		switch (transitionType) {
			case CHANGE_APPEARING: return mChangingAppearingInterpolator;
			case CHANGE_DISAPPEARING: return mChangingDisappearingInterpolator;
			case CHANGING: return mChangingInterpolator;
			case APPEARING: return mAppearingInterpolator;
			case DISAPPEARING: return mDisappearingInterpolator;
		}
		return null;
	}

	public void setAnimator(int transitionType, Animator animator) {
		switch (transitionType) {
			case CHANGE_APPEARING: mChangingAppearingAnim = animator; break;
			case CHANGE_DISAPPEARING: mChangingDisappearingAnim = animator; break;
			case CHANGING: mChangingAnim = animator; break;
			case APPEARING: mAppearingAnim = animator; break;
			case DISAPPEARING: mDisappearingAnim = animator; break;
		}
	}

	public Animator getAnimator(int transitionType) {
		switch (transitionType) {
			case CHANGE_APPEARING: return mChangingAppearingAnim;
			case CHANGE_DISAPPEARING: return mChangingDisappearingAnim;
			case CHANGING: return mChangingAnim;
			case APPEARING: return mAppearingAnim;
			case DISAPPEARING: return mDisappearingAnim;
		}
		return null;
	}

	/** Whether the parents of the container animate too when the container changes size. */
	public void setAnimateParentHierarchy(boolean animateParentHierarchy) {
		mAnimateParentHierarchy = animateParentHierarchy;
	}

	// ---- change animations --------------------------------------------------------------------

	private void runChangeTransition(final ViewGroup parent, View newView, final int changeReason) {
		Animator baseAnimator = null;
		Animator parentAnimator = null;
		final long duration;
		switch (changeReason) {
			case APPEARING:
				baseAnimator = mChangingAppearingAnim;
				duration = mChangingAppearingDuration;
				parentAnimator = defaultChangeIn;
				break;
			case DISAPPEARING:
				baseAnimator = mChangingDisappearingAnim;
				duration = mChangingDisappearingDuration;
				parentAnimator = defaultChangeOut;
				break;
			case CHANGING:
				baseAnimator = mChangingAnim;
				duration = mChangingDuration;
				parentAnimator = defaultChange;
				break;
			default:
				duration = 0;
				break;
		}
		if (baseAnimator == null)
			return;

		staggerDelay = 0;
		final ViewTreeObserver observer = parent.getViewTreeObserver();
		if (!observer.isAlive())
			return;
		int numChildren = parent.getChildCount();
		for (int i = 0; i < numChildren; ++i) {
			final View child = parent.getChildAt(i);
			if (child != newView)
				setupChangeAnimation(parent, changeReason, baseAnimator, duration, child);
		}
		if (mAnimateParentHierarchy) {
			ViewGroup tempParent = parent;
			while (tempParent != null) {
				ViewParent parentParent = tempParent.getParent();
				if (parentParent instanceof ViewGroup) {
					setupChangeAnimation((ViewGroup) parentParent, changeReason, parentAnimator, duration, tempParent);
					tempParent = (ViewGroup) parentParent;
				} else {
					tempParent = null;
				}
			}
		}

		// The listeners of views whose layout did not change are removed after the next layout
		final CleanupCallback callback = new CleanupCallback(layoutChangeListenerMap, parent);
		observer.addOnPreDrawListener(callback);
		parent.addOnAttachStateChangeListener(callback);
	}

	/** This flag tells the parents to animate too (used by ViewGroup for nested containers). */
	public boolean isChangingLayout() {
		return currentChangingAnimations.size() > 0;
	}

	private void setupChangeAnimation(final ViewGroup parent, final int changeReason, Animator baseAnimator,
	                                  final long duration, final View child) {
		// If we already have a listener for this child, then we've already set up the changing
		// animation we need. Multiple calls for a child may occur when several add/remove
		// operations are run at once on a container; each one will trigger multiple calls for the
		// existing children in the container.
		if (layoutChangeListenerMap.get(child) != null)
			return;
		// Don't animate items up from size(0,0); this is likely because the objects were offscreen
		// or the container was a new container
		if (child.getWidth() == 0 && child.getHeight() == 0)
			return;

		final Animator anim = baseAnimator.clone();
		anim.setTarget(child);
		anim.setupStartValues();

		Animator currentAnimation = pendingAnimations.get(child);
		if (currentAnimation != null) {
			currentAnimation.cancel();
			pendingAnimations.remove(child);
		}
		pendingAnimations.put(child, anim);

		// The pending animation is dropped if no layout change starts it within its duration
		ValueAnimator pendingAnimRemover = ValueAnimator.ofFloat(0f, 1f).setDuration(duration + 100);
		pendingAnimRemover.addListener(new AnimatorListenerAdapter() {
			@Override
			public void onAnimationEnd(Animator animation) {
				pendingAnimations.remove(child);
			}
		});
		pendingAnimRemover.start();

		final View.OnLayoutChangeListener listener = new View.OnLayoutChangeListener() {
			public void onLayoutChange(View v, int left, int top, int right, int bottom,
			                           int oldLeft, int oldTop, int oldRight, int oldBottom) {
				// Tell the animation to extract end values from the changed object
				anim.setupEndValues();
				if (anim instanceof ValueAnimator) {
					boolean valuesDiffer = false;
					PropertyValuesHolder[] oldValues = ((ValueAnimator) anim).getValues();
					if (oldValues != null) {
						for (PropertyValuesHolder pvh : oldValues) {
							if (pvh.tlStartDiffersFromEnd()) {
								valuesDiffer = true;
								break;
							}
						}
					}
					if (!valuesDiffer)
						return;
				}

				long startDelay = 0;
				switch (changeReason) {
					case APPEARING:
						startDelay = mChangingAppearingDelay + staggerDelay;
						staggerDelay += mChangingAppearingStagger;
						if (mChangingAppearingInterpolator != sChangingAppearingInterpolator)
							anim.setInterpolator(mChangingAppearingInterpolator);
						break;
					case DISAPPEARING:
						startDelay = mChangingDisappearingDelay + staggerDelay;
						staggerDelay += mChangingDisappearingStagger;
						if (mChangingDisappearingInterpolator != sChangingDisappearingInterpolator)
							anim.setInterpolator(mChangingDisappearingInterpolator);
						break;
					case CHANGING:
						startDelay = mChangingDelay + staggerDelay;
						staggerDelay += mChangingStagger;
						if (mChangingInterpolator != sChangingInterpolator)
							anim.setInterpolator(mChangingInterpolator);
						break;
				}
				anim.setStartDelay(startDelay);
				anim.setDuration(duration);

				Animator prevAnimation = currentChangingAnimations.get(child);
				if (prevAnimation != null)
					prevAnimation.cancel();
				Animator pendingAnimation = pendingAnimations.get(child);
				if (pendingAnimation != null)
					pendingAnimations.remove(child);
				// Cache the animation in case we need to cancel it later
				currentChangingAnimations.put(child, anim);

				// AOSP queues the start with the view root until the next traversal; the layout
				// that called this listener is that traversal, so the animation starts now
				anim.start();

				// this only removes listeners whose views changed - must clear the
				// other listeners later
				child.removeOnLayoutChangeListener(this);
				layoutChangeListenerMap.remove(child);
			}
		};
		anim.addListener(new AnimatorListenerAdapter() {
			@Override
			public void onAnimationStart(Animator animator) {
				if (hasListeners()) {
					ArrayList<TransitionListener> listeners = new ArrayList<TransitionListener>(mListeners);
					for (TransitionListener l : listeners)
						l.startTransition(LayoutTransition.this, parent, child,
						                  changeReason == APPEARING ? CHANGE_APPEARING : changeReason == DISAPPEARING ? CHANGE_DISAPPEARING : CHANGING);
				}
			}

			@Override
			public void onAnimationCancel(Animator animator) {
				child.removeOnLayoutChangeListener(listener);
				layoutChangeListenerMap.remove(child);
			}

			@Override
			public void onAnimationEnd(Animator animator) {
				currentChangingAnimations.remove(child);
				if (hasListeners()) {
					ArrayList<TransitionListener> listeners = new ArrayList<TransitionListener>(mListeners);
					for (TransitionListener l : listeners)
						l.endTransition(LayoutTransition.this, parent, child,
						                changeReason == APPEARING ? CHANGE_APPEARING : changeReason == DISAPPEARING ? CHANGE_DISAPPEARING : CHANGING);
				}
			}
		});

		child.addOnLayoutChangeListener(listener);
		// cache the listener for later removal
		layoutChangeListenerMap.put(child, listener);
	}

	/** Starts the change animations that are waiting (AOSP calls this from the view root). */
	public void startChangingAnimations() {
		LinkedHashMap<View, Animator> currentAnimCopy = new LinkedHashMap<View, Animator>(currentChangingAnimations);
		for (Animator anim : currentAnimCopy.values()) {
			if (anim instanceof ObjectAnimator)
				((ObjectAnimator) anim).setCurrentPlayTime(0);
			if (!anim.isStarted())
				anim.start();
		}
	}

	/** Ends the change animations (the views jump to their end bounds). */
	public void endChangingAnimations() {
		LinkedHashMap<View, Animator> currentAnimCopy = new LinkedHashMap<View, Animator>(currentChangingAnimations);
		for (Animator anim : currentAnimCopy.values()) {
			anim.start();
			anim.end();
		}
		// listeners should clean up the currentChangingAnimations list, but just in case...
		currentChangingAnimations.clear();
	}

	/** Whether any change animation is running. */
	public boolean isChangingLayoutOrRunning() {
		return isChangingLayout() || isRunning();
	}

	/** Whether any of the transition animations is running. */
	public boolean isRunning() {
		return (currentChangingAnimations.size() > 0 || currentAppearingAnimations.size() > 0
		        || currentDisappearingAnimations.size() > 0);
	}

	/** Cancels the running animations. */
	public void cancel() {
		if (currentChangingAnimations.size() > 0) {
			LinkedHashMap<View, Animator> copy = new LinkedHashMap<View, Animator>(currentChangingAnimations);
			for (Animator anim : copy.values())
				anim.cancel();
			currentChangingAnimations.clear();
		}
		if (currentAppearingAnimations.size() > 0) {
			LinkedHashMap<View, Animator> copy = new LinkedHashMap<View, Animator>(currentAppearingAnimations);
			for (Animator anim : copy.values())
				anim.end();
			currentAppearingAnimations.clear();
		}
		if (currentDisappearingAnimations.size() > 0) {
			LinkedHashMap<View, Animator> copy = new LinkedHashMap<View, Animator>(currentDisappearingAnimations);
			for (Animator anim : copy.values())
				anim.end();
			currentDisappearingAnimations.clear();
		}
	}

	/** Cancels the running animations of one transition type. */
	public void cancel(int transitionType) {
		switch (transitionType) {
			case CHANGE_APPEARING:
			case CHANGE_DISAPPEARING:
			case CHANGING:
				if (currentChangingAnimations.size() > 0) {
					LinkedHashMap<View, Animator> copy = new LinkedHashMap<View, Animator>(currentChangingAnimations);
					for (Animator anim : copy.values())
						anim.cancel();
					currentChangingAnimations.clear();
				}
				break;
			case APPEARING:
				if (currentAppearingAnimations.size() > 0) {
					LinkedHashMap<View, Animator> copy = new LinkedHashMap<View, Animator>(currentAppearingAnimations);
					for (Animator anim : copy.values())
						anim.end();
					currentAppearingAnimations.clear();
				}
				break;
			case DISAPPEARING:
				if (currentDisappearingAnimations.size() > 0) {
					LinkedHashMap<View, Animator> copy = new LinkedHashMap<View, Animator>(currentDisappearingAnimations);
					for (Animator anim : copy.values())
						anim.end();
					currentDisappearingAnimations.clear();
				}
				break;
		}
	}

	// ---- appearing and disappearing ----------------------------------------------------------

	private void runAppearingTransition(final ViewGroup parent, final View child) {
		Animator currentAnimation = currentDisappearingAnimations.get(child);
		if (currentAnimation != null)
			currentAnimation.cancel();
		if (mAppearingAnim == null) {
			if (hasListeners()) {
				ArrayList<TransitionListener> listeners = new ArrayList<TransitionListener>(mListeners);
				for (TransitionListener listener : listeners)
					listener.endTransition(LayoutTransition.this, parent, child, APPEARING);
			}
			return;
		}
		Animator anim = mAppearingAnim.clone();
		anim.setTarget(child);
		anim.setStartDelay(mAppearingDelay);
		anim.setDuration(mAppearingDuration);
		if (mAppearingInterpolator != sAppearingInterpolator)
			anim.setInterpolator(mAppearingInterpolator);
		if (anim instanceof ObjectAnimator)
			((ObjectAnimator) anim).setCurrentPlayTime(0);
		anim.addListener(new AnimatorListenerAdapter() {
			@Override
			public void onAnimationEnd(Animator anim) {
				currentAppearingAnimations.remove(child);
				if (hasListeners()) {
					ArrayList<TransitionListener> listeners = new ArrayList<TransitionListener>(mListeners);
					for (TransitionListener listener : listeners)
						listener.endTransition(LayoutTransition.this, parent, child, APPEARING);
				}
			}
		});
		currentAppearingAnimations.put(child, anim);
		anim.start();
	}

	private void runDisappearingTransition(final ViewGroup parent, final View child) {
		Animator currentAnimation = currentAppearingAnimations.get(child);
		if (currentAnimation != null)
			currentAnimation.cancel();
		if (mDisappearingAnim == null) {
			if (hasListeners()) {
				ArrayList<TransitionListener> listeners = new ArrayList<TransitionListener>(mListeners);
				for (TransitionListener listener : listeners)
					listener.endTransition(LayoutTransition.this, parent, child, DISAPPEARING);
			}
			return;
		}
		Animator anim = mDisappearingAnim.clone();
		anim.setStartDelay(mDisappearingDelay);
		anim.setDuration(mDisappearingDuration);
		if (mDisappearingInterpolator != sDisappearingInterpolator)
			anim.setInterpolator(mDisappearingInterpolator);
		anim.setTarget(child);
		final float preAnimAlpha = child.getAlpha();
		anim.addListener(new AnimatorListenerAdapter() {
			@Override
			public void onAnimationEnd(Animator anim) {
				currentDisappearingAnimations.remove(child);
				child.setAlpha(preAnimAlpha);
				if (hasListeners()) {
					ArrayList<TransitionListener> listeners = new ArrayList<TransitionListener>(mListeners);
					for (TransitionListener listener : listeners)
						listener.endTransition(LayoutTransition.this, parent, child, DISAPPEARING);
				}
			}
		});
		if (anim instanceof ObjectAnimator)
			((ObjectAnimator) anim).setCurrentPlayTime(0);
		currentDisappearingAnimations.put(child, anim);
		anim.start();
	}

	private void addChild(ViewGroup parent, View child, boolean changesLayout) {
		if (parent.getWindowVisibility() != View.VISIBLE)
			return;
		if ((mTransitionTypes & FLAG_APPEARING) == FLAG_APPEARING) {
			// Want disappearing animations to finish up before proceeding
			cancel(DISAPPEARING);
		}
		if (changesLayout && (mTransitionTypes & FLAG_CHANGE_APPEARING) == FLAG_CHANGE_APPEARING) {
			// Also, cancel changing animations so that we start fresh ones from current locations
			cancel(CHANGE_APPEARING);
			cancel(CHANGING);
		}
		if (hasListeners() && (mTransitionTypes & FLAG_APPEARING) == FLAG_APPEARING) {
			ArrayList<TransitionListener> listeners = new ArrayList<TransitionListener>(mListeners);
			for (TransitionListener listener : listeners)
				listener.startTransition(this, parent, child, APPEARING);
		}
		if (changesLayout && (mTransitionTypes & FLAG_CHANGE_APPEARING) == FLAG_CHANGE_APPEARING)
			runChangeTransition(parent, child, APPEARING);
		if ((mTransitionTypes & FLAG_APPEARING) == FLAG_APPEARING)
			runAppearingTransition(parent, child);
	}

	private boolean hasListeners() {
		return mListeners != null && mListeners.size() > 0;
	}

	/** Called by ViewGroup.layout() for the CHANGING transition. */
	public void layoutChange(ViewGroup parent) {
		if (parent.getWindowVisibility() != View.VISIBLE)
			return;
		if ((mTransitionTypes & FLAG_CHANGING) == FLAG_CHANGING && !isRunning()) {
			// This method is called for all calls to layout() in the container, including those
			// caused by add/remove/hide/show events, which will already have set up transition
			// animations. Avoid setting up CHANGING animations in this case.
			runChangeTransition(parent, null, CHANGING);
		}
	}

	/** Called by ViewGroup when a child is added (it runs APPEARING and CHANGE_APPEARING). */
	public void addChild(ViewGroup parent, View child) {
		addChild(parent, child, true);
	}

	/** @deprecated use showChild(ViewGroup, View, int) */
	@Deprecated
	public void showChild(ViewGroup parent, View child) {
		addChild(parent, child, true);
	}

	/** Called by ViewGroup when a child becomes visible. */
	public void showChild(ViewGroup parent, View child, int oldVisibility) {
		addChild(parent, child, oldVisibility == View.GONE);
	}

	private void removeChild(ViewGroup parent, View child, boolean changesLayout) {
		if (parent.getWindowVisibility() != View.VISIBLE)
			return;
		if ((mTransitionTypes & FLAG_DISAPPEARING) == FLAG_DISAPPEARING) {
			// Want appearing animations to finish up before proceeding
			cancel(APPEARING);
		}
		if (changesLayout && (mTransitionTypes & FLAG_CHANGE_DISAPPEARING) == FLAG_CHANGE_DISAPPEARING) {
			// Also, cancel changing animations so that we start fresh ones from current locations
			cancel(CHANGE_DISAPPEARING);
			cancel(CHANGING);
		}
		if (hasListeners() && (mTransitionTypes & FLAG_DISAPPEARING) == FLAG_DISAPPEARING) {
			ArrayList<TransitionListener> listeners = new ArrayList<TransitionListener>(mListeners);
			for (TransitionListener listener : listeners)
				listener.startTransition(this, parent, child, DISAPPEARING);
		}
		if (changesLayout && (mTransitionTypes & FLAG_CHANGE_DISAPPEARING) == FLAG_CHANGE_DISAPPEARING)
			runChangeTransition(parent, child, DISAPPEARING);
		if ((mTransitionTypes & FLAG_DISAPPEARING) == FLAG_DISAPPEARING)
			runDisappearingTransition(parent, child);
	}

	/** Called by ViewGroup when a child is removed (it runs DISAPPEARING and CHANGE_DISAPPEARING). */
	public void removeChild(ViewGroup parent, View child) {
		removeChild(parent, child, true);
	}

	/** @deprecated use hideChild(ViewGroup, View, int) */
	@Deprecated
	public void hideChild(ViewGroup parent, View child) {
		removeChild(parent, child, true);
	}

	/** Called by ViewGroup when a child becomes invisible or gone. */
	public void hideChild(ViewGroup parent, View child, int newVisibility) {
		removeChild(parent, child, newVisibility == View.GONE);
	}

	// ---- listeners ----------------------------------------------------------------------------

	public void addTransitionListener(TransitionListener listener) {
		if (mListeners == null)
			mListeners = new ArrayList<TransitionListener>();
		mListeners.add(listener);
	}

	public void removeTransitionListener(TransitionListener listener) {
		if (mListeners == null)
			return;
		mListeners.remove(listener);
	}

	public List<TransitionListener> getTransitionListeners() {
		return mListeners;
	}

	/** Listener for the start and end of each transition of a child. */
	public interface TransitionListener {
		void startTransition(LayoutTransition transition, ViewGroup container, View view, int transitionType);

		void endTransition(LayoutTransition transition, ViewGroup container, View view, int transitionType);
	}

	/** Removes the layout-change listeners that no layout change consumed, after the next layout. */
	private static final class CleanupCallback implements ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {
		final Map<View, View.OnLayoutChangeListener> layoutChangeListenerMap;
		final ViewGroup parent;

		CleanupCallback(Map<View, View.OnLayoutChangeListener> listenerMap, ViewGroup parent) {
			this.layoutChangeListenerMap = listenerMap;
			this.parent = parent;
		}

		private void cleanup() {
			parent.getViewTreeObserver().removeOnPreDrawListener(this);
			parent.removeOnAttachStateChangeListener(this);
			int count = layoutChangeListenerMap.size();
			if (count > 0) {
				Collection<View> views = new ArrayList<View>(layoutChangeListenerMap.keySet());
				for (View view : views) {
					View.OnLayoutChangeListener listener = layoutChangeListenerMap.get(view);
					view.removeOnLayoutChangeListener(listener);
				}
				layoutChangeListenerMap.clear();
			}
		}

		@Override
		public void onViewAttachedToWindow(View v) {}

		@Override
		public void onViewDetachedFromWindow(View v) {
			cleanup();
		}

		@Override
		public boolean onPreDraw() {
			cleanup();
			return true;
		}
	}
}
