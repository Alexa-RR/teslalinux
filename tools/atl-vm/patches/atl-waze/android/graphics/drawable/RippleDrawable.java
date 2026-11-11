/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.graphics.drawable;

import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import com.android.internal.R;

/**
 * Drawable that shows a ripple effect in response to state changes (AOSP API 21). The anchoring
 * position of the ripple for a given state may be specified by calling {@link #setHotspot}.
 *
 * Layout: the content layers are drawn first; if a layer has the id {@code android.R.id.mask} it
 * is not drawn but bounds the ripple. The ripple itself is a circle of the ripple colour (the
 * colour state list for the current state, at the AOSP touch-feedback alpha) centred on the
 * hotspot, with the radius given by {@link #setRadius} or, for {@link #RADIUS_AUTO}, the bounds'
 * half-diagonal. The expanding animation of the framework (RippleForeground) is not run: the
 * ripple is shown fully grown while the state is pressed and hidden otherwise, which is what the
 * end of the animation looks like.
 */
public class RippleDrawable extends LayerDrawable {
	/** Radius value that specifies the ripple radius as the bounds of the drawable. */
	public static final int RADIUS_AUTO = -1;

	private static final int[] PRESSED_STATE = { android.R.attr.state_pressed };

	private ColorStateList mColor;
	private int mRadius = RADIUS_AUTO;
	private int mEffectColor = Color.TRANSPARENT;
	private float mHotspotX = Float.NaN;
	private float mHotspotY = Float.NaN;
	private final Rect mHotspotBounds = new Rect();
	private boolean mHasHotspotBounds;
	private boolean mPressed;
	private boolean mHovered;
	private boolean mFocused;
	private final Paint mRipplePaint = new Paint();

	/**
	 * Creates a new ripple drawable with the specified ripple color and optional content and mask
	 * drawables.
	 */
	public RippleDrawable(ColorStateList color, Drawable content, Drawable mask) {
		super(layersOf(content, mask));
		if (color == null)
			throw new IllegalArgumentException("RippleDrawable requires a non-null color");
		mColor = color;
		if (mask != null)
			setId(content == null ? 0 : 1, R.id.mask); // the mask layer bounds the ripple and is not drawn
		mRipplePaint.setAntiAlias(true);
	}

	/** Content first, then the mask, so LayerDrawable sizes its per-layer padding arrays for both. */
	private static Drawable[] layersOf(Drawable content, Drawable mask) {
		int n = (content != null ? 1 : 0) + (mask != null ? 1 : 0);
		Drawable[] layers = new Drawable[n];
		int i = 0;
		if (content != null)
			layers[i++] = content;
		if (mask != null)
			layers[i] = mask;
		return layers;
	}

	RippleDrawable() {
		mColor = ColorStateList.valueOf(Color.MAGENTA);
		mRipplePaint.setAntiAlias(true);
	}

	/** Sets the ripple color. */
	/**
	 * AOSP: android:color (usually ?attr/colorControlHighlight, resolved through the theme) and
	 * android:radius, read before the layer list consumes the parser; the items (and the
	 * android:id/mask layer) inflate as in LayerDrawable. Without this an XML ripple kept the
	 * placeholder colour of the inflation constructor.
	 */
	@Override
	public void inflate(android.content.res.Resources r, org.xmlpull.v1.XmlPullParser parser, android.util.AttributeSet attrs,
	                    android.content.res.Resources.Theme theme) throws org.xmlpull.v1.XmlPullParserException, java.io.IOException {
		final android.content.res.TypedArray a = obtainAttributes(r, theme, attrs, R.styleable.RippleDrawable);
		final ColorStateList color = a.getColorStateList(R.styleable.RippleDrawable_color);
		if (color != null)
			mColor = color;
		mRadius = a.getDimensionPixelSize(R.styleable.RippleDrawable_radius, mRadius);
		a.recycle();
		super.inflate(r, parser, attrs, theme);
	}

	public void setColor(ColorStateList color) {
		if (color == null)
			throw new IllegalArgumentException("color cannot be null");
		mColor = color;
		invalidateSelf();
	}

	/** API 31: the effect (highlight) colour drawn on hover/focus; TRANSPARENT means the default. */
	public void setEffectColor(ColorStateList color) {
		mEffectColor = color == null ? Color.TRANSPARENT : color.getColorForState(getState(), color.getDefaultColor());
		invalidateSelf();
	}

	public ColorStateList getEffectColor() {
		return ColorStateList.valueOf(mEffectColor);
	}

	/** Sets the radius in pixels of the fully expanded ripple, or RADIUS_AUTO for the bounds. */
	public void setRadius(int radius) {
		mRadius = radius;
		invalidateSelf();
	}

	public int getRadius() {
		return mRadius;
	}

	@Override
	public void setHotspot(float x, float y) {
		mHotspotX = x;
		mHotspotY = y;
		if (mPressed)
			invalidateSelf();
	}

	@Override
	public void setHotspotBounds(int left, int top, int right, int bottom) {
		mHotspotBounds.set(left, top, right, bottom);
		mHasHotspotBounds = true;
	}

	/** The hotspot bounds: explicit ones when set, else the drawable bounds (AOSP). */
	public void getHotspotBounds(Rect outRect) {
		if (mHasHotspotBounds)
			outRect.set(mHotspotBounds);
		else
			outRect.set(getBounds());
	}

	@Override
	public boolean isStateful() {
		return true;
	}

	/** AOSP API 31 (Drawable has no base here). */
	public boolean hasFocusStateSpecified() {
		return true;
	}

	@Override
	protected boolean onStateChange(int[] stateSet) {
		boolean changed = super.onStateChange(stateSet);
		boolean pressed = false, hovered = false, focused = false, enabled = false;
		if (stateSet != null) {
			for (int s : stateSet) {
				if (s == android.R.attr.state_pressed)
					pressed = true;
				else if (s == android.R.attr.state_hovered)
					hovered = true;
				else if (s == android.R.attr.state_focused)
					focused = true;
				else if (s == android.R.attr.state_enabled)
					enabled = true;
			}
		}
		pressed &= enabled;
		if (pressed != mPressed || hovered != mHovered || focused != mFocused) {
			mPressed = pressed;
			mHovered = hovered;
			mFocused = focused;
			invalidateSelf();
			return true;
		}
		return changed;
	}

	/** Content layers, then the ripple when pressed (bounded by the mask layer or the bounds). */
	@Override
	public void draw(Canvas canvas) {
		final ChildDrawable[] array = mLayerState.mChildren;
		final int N = mLayerState.mNum;
		for (int i = 0; i < N; i++) {
			if (array[i].mId != R.id.mask)
				array[i].mDrawable.draw(canvas);
		}
		if (!mPressed && !mHovered && !mFocused)
			return;
		Rect bounds = getBounds();
		if (bounds.isEmpty())
			return;
		int color = mColor.getColorForState(getState(), mColor.getDefaultColor());
		if (!mPressed) {
			// hover/focus: the AOSP background highlight is the ripple colour at a lower alpha
			color = (color & 0x00FFFFFF) | ((Color.alpha(color) / 2) << 24);
		}
		mRipplePaint.setColor(color);
		float cx = Float.isNaN(mHotspotX) ? bounds.exactCenterX() : mHotspotX;
		float cy = Float.isNaN(mHotspotY) ? bounds.exactCenterY() : mHotspotY;
		float radius;
		if (mRadius != RADIUS_AUTO) {
			radius = mRadius;
		} else {
			float halfW = bounds.width() / 2f, halfH = bounds.height() / 2f;
			radius = (float) Math.sqrt(halfW * halfW + halfH * halfH);
		}
		int saved = canvas.save();
		canvas.clipRect(bounds);
		if (mPressed)
			canvas.drawCircle(cx, cy, radius, mRipplePaint);
		else
			canvas.drawRect(bounds, mRipplePaint);
		canvas.restoreToCount(saved);
	}

	@Override
	public int getOpacity() {
		return android.graphics.PixelFormat.TRANSLUCENT;
	}

	@Override
	public boolean setVisible(boolean visible, boolean restart) {
		boolean changed = super.setVisible(visible, restart);
		if (!visible) {
			mPressed = false;
			mHovered = false;
			mFocused = false;
		}
		return changed;
	}
}
