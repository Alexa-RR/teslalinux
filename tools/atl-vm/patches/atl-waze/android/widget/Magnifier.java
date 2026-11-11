package android.widget;

import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;

/**
 * Android magnifier widget: shows a zoomed-in copy of a region of a view in a small window
 * floating over it (text handles, Compose text selection). AOSP API and defaults.
 *
 * teslalinux: AOSP copies the source pixels with PixelCopy into a dedicated surface. Here the
 * window is a PopupWindow whose ImageView shows the source view's live GTK rendering (a
 * GtkWidgetPaintable, native-text-textview.sh), scaled by the zoom and offset so the source
 * point is in the middle; GTK keeps it current as the view redraws. The overlay drawable is drawn
 * over the content; rounded corners are not clipped (this runtime's views have no outline
 * clipping), the elevation goes to the popup.
 */
public final class Magnifier {
	private static native long native_widgetPaintable(long widget);
	private static native void native_unref(long paintable);

	private static final float DEFAULT_ZOOM = 1.25f;

	private final View mView;
	private final int mWindowWidth;
	private final int mWindowHeight;
	private float mZoom;
	private int mSourceWidth;
	private int mSourceHeight;
	private final float mWindowElevation;
	private final float mWindowCornerRadius;
	private final Drawable mOverlay;
	private final int mDefaultHorizontalSourceToMagnifierOffset;
	private final int mDefaultVerticalSourceToMagnifierOffset;
	private final boolean mClippingEnabled;
	private final int mLeftBound, mTopBound, mRightBound, mBottomBound;

	private PopupWindow mWindow;
	private ImageView mContent;
	private FrameLayout mFrame;
	private long mPaintable;
	private final Point mWindowCoords = new Point();
	private final Point mClampedCenterZoomCoords = new Point();
	private float mPrevShowSourceX = Float.NaN, mPrevShowSourceY = Float.NaN;
	private float mPrevShowWindowX = Float.NaN, mPrevShowWindowY = Float.NaN;

	/** @deprecated AOSP: use {@link Builder} */
	@Deprecated
	public Magnifier(View view) {
		this(createBuilderWithOldMagnifierDefaults(view));
	}

	static Builder createBuilderWithOldMagnifierDefaults(final View view) {
		final Builder params = new Builder(view);
		final float density = view.getResources().getDisplayMetrics().density;
		params.mWidth = Math.round(100 * density);
		params.mHeight = Math.round(48 * density);
		params.mElevation = 4 * density;
		params.mCornerRadius = 2 * density;
		params.mZoom = DEFAULT_ZOOM;
		params.mHorizontalDefaultSourceToMagnifierOffset = 0;
		params.mVerticalDefaultSourceToMagnifierOffset = Math.round(-42 * density);
		return params;
	}

	private Magnifier(Builder params) {
		mView = params.mView;
		mWindowWidth = params.mWidth;
		mWindowHeight = params.mHeight;
		mZoom = params.mZoom;
		mSourceWidth = Math.round(mWindowWidth / mZoom);
		mSourceHeight = Math.round(mWindowHeight / mZoom);
		mWindowElevation = params.mElevation;
		mWindowCornerRadius = params.mCornerRadius;
		mOverlay = params.mOverlay;
		mDefaultHorizontalSourceToMagnifierOffset = params.mHorizontalDefaultSourceToMagnifierOffset;
		mDefaultVerticalSourceToMagnifierOffset = params.mVerticalDefaultSourceToMagnifierOffset;
		mClippingEnabled = params.mClippingEnabled;
		mLeftBound = params.mLeftContentBound;
		mTopBound = params.mTopContentBound;
		mRightBound = params.mRightContentBound;
		mBottomBound = params.mBottomContentBound;
	}

	public void show(float sourceCenterX, float sourceCenterY) {
		show(sourceCenterX, sourceCenterY,
			sourceCenterX + mDefaultHorizontalSourceToMagnifierOffset,
			sourceCenterY + mDefaultVerticalSourceToMagnifierOffset);
	}

	public void show(float sourceCenterX, float sourceCenterY, float magnifierCenterX, float magnifierCenterY) {
		if (mView.widget == 0)
			return;
		if (mWindow == null) {
			mPaintable = native_widgetPaintable(mView.widget);
			final Drawable live = new Drawable(mPaintable);
			native_unref(mPaintable); /* the Drawable holds its own reference */
			mContent = new ImageView(mView.getContext());
			mContent.setScaleType(ImageView.ScaleType.FIT_XY);
			mContent.setImageDrawable(live);
			mFrame = new FrameLayout(mView.getContext());
			mFrame.addView(mContent, new FrameLayout.LayoutParams(1, 1));
			if (mOverlay != null) {
				final ImageView overlay = new ImageView(mView.getContext());
				overlay.setScaleType(ImageView.ScaleType.FIT_XY);
				overlay.setImageDrawable(mOverlay);
				mFrame.addView(overlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
			}
			mWindow = new PopupWindow(mFrame, mWindowWidth, mWindowHeight);
			mWindow.setTouchable(false);
			mWindow.setFocusable(false);
			mWindow.setClippingEnabled(mClippingEnabled);
			mWindow.setElevation(mWindowElevation);
			mWindow.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
		}
		/* AOSP: the source rectangle stays inside the view (or the configured bounds) */
		final int viewW = mView.getWidth(), viewH = mView.getHeight();
		float sx = sourceCenterX, sy = sourceCenterY;
		final int minX = mLeftBound == SOURCE_BOUND_MAX_IN_SURFACE ? 0 : mView.getPaddingLeft();
		final int maxX = mRightBound == SOURCE_BOUND_MAX_IN_SURFACE ? viewW : viewW - mView.getPaddingRight();
		final int minY = mTopBound == SOURCE_BOUND_MAX_IN_SURFACE ? 0 : mView.getPaddingTop();
		final int maxY = mBottomBound == SOURCE_BOUND_MAX_IN_SURFACE ? viewH : viewH - mView.getPaddingBottom();
		sx = Math.max(minX + mSourceWidth / 2f, Math.min(maxX - mSourceWidth / 2f, sx));
		sy = Math.max(minY + mSourceHeight / 2f, Math.min(maxY - mSourceHeight / 2f, sy));
		mClampedCenterZoomCoords.x = Math.round(sx);
		mClampedCenterZoomCoords.y = Math.round(sy);

		/* the zoomed view, placed so the source centre is the window centre */
		final FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) mContent.getLayoutParams();
		final int zw = Math.max(1, Math.round(viewW * mZoom)), zh = Math.max(1, Math.round(viewH * mZoom));
		if (lp.width != zw || lp.height != zh) {
			lp.width = zw;
			lp.height = zh;
			mContent.setLayoutParams(lp);
		}
		mContent.setTranslationX(mWindowWidth / 2f - sx * mZoom);
		mContent.setTranslationY(mWindowHeight / 2f - sy * mZoom);

		final int[] loc = new int[2];
		mView.getLocationInWindow(loc);
		int left = Math.round(loc[0] + magnifierCenterX - mWindowWidth / 2f);
		int top = Math.round(loc[1] + magnifierCenterY - mWindowHeight / 2f);
		if (mClippingEnabled) {
			final View root = mView.getRootView();
			left = Math.max(0, Math.min(root.getWidth() - mWindowWidth, left));
			top = Math.max(0, Math.min(root.getHeight() - mWindowHeight, top));
		}
		mWindowCoords.x = left;
		mWindowCoords.y = top;
		if (mWindow.isShowing())
			mWindow.update(left, top, mWindowWidth, mWindowHeight);
		else
			mWindow.showAtLocation(mView, Gravity.NO_GRAVITY, left, top);
		mPrevShowSourceX = sourceCenterX;
		mPrevShowSourceY = sourceCenterY;
		mPrevShowWindowX = magnifierCenterX;
		mPrevShowWindowY = magnifierCenterY;
	}

	public void dismiss() {
		if (mWindow != null) {
			mWindow.dismiss();
			mWindow = null;
			mContent = null;
			mFrame = null;
		}
		mPrevShowSourceX = mPrevShowSourceY = mPrevShowWindowX = mPrevShowWindowY = Float.NaN;
	}

	/** AOSP: refreshes the content; the live paintable follows the view, so only the frame is redrawn */
	public void update() {
		if (mWindow != null && mContent != null)
			mContent.invalidate();
	}

	public int getWidth() {
		return mWindowWidth;
	}

	public int getHeight() {
		return mWindowHeight;
	}

	public int getSourceWidth() {
		return mSourceWidth;
	}

	public int getSourceHeight() {
		return mSourceHeight;
	}

	public void setZoom(float zoom) {
		if (zoom <= 0)
			throw new IllegalArgumentException("Zoom should be positive");
		mZoom = zoom;
		mSourceWidth = Math.round(mWindowWidth / mZoom);
		mSourceHeight = Math.round(mWindowHeight / mZoom);
		if (mWindow != null && !Float.isNaN(mPrevShowSourceX))
			show(mPrevShowSourceX, mPrevShowSourceY, mPrevShowWindowX, mPrevShowWindowY);
	}

	public float getZoom() {
		return mZoom;
	}

	public float getElevation() {
		return mWindowElevation;
	}

	public float getCornerRadius() {
		return mWindowCornerRadius;
	}

	public int getDefaultHorizontalSourceToMagnifierOffset() {
		return mDefaultHorizontalSourceToMagnifierOffset;
	}

	public int getDefaultVerticalSourceToMagnifierOffset() {
		return mDefaultVerticalSourceToMagnifierOffset;
	}

	public Drawable getOverlay() {
		return mOverlay;
	}

	public boolean isClippingEnabled() {
		return mClippingEnabled;
	}

	/** AOSP: the top-left of the magnifier window, relative to the view's window (null while hidden) */
	public Point getPosition() {
		if (mWindow == null)
			return null;
		return new Point(mWindowCoords);
	}

	/** AOSP: the top-left of the magnified source region, relative to the view's window (null while hidden) */
	public Point getSourcePosition() {
		if (mWindow == null)
			return null;
		final int[] loc = new int[2];
		mView.getLocationInWindow(loc);
		return new Point(loc[0] + mClampedCenterZoomCoords.x - mSourceWidth / 2, loc[1] + mClampedCenterZoomCoords.y - mSourceHeight / 2);
	}

	/** AOSP: the default magnifier size, in dp */
	public static PointF getMagnifierDefaultSize() {
		return new PointF(100, 48);
	}

	public static final int SOURCE_BOUND_MAX_IN_SURFACE = 0;
	public static final int SOURCE_BOUND_MAX_VISIBLE = 1;

	public static final class Builder {
		private View mView;
		private int mWidth;
		private int mHeight;
		private float mZoom;
		private float mElevation;
		private float mCornerRadius;
		private Drawable mOverlay;
		private int mHorizontalDefaultSourceToMagnifierOffset;
		private int mVerticalDefaultSourceToMagnifierOffset;
		private boolean mClippingEnabled;
		private int mLeftContentBound;
		private int mTopContentBound;
		private int mRightContentBound;
		private int mBottomContentBound;

		public Builder(View view) {
			if (view == null)
				throw new NullPointerException();
			mView = view;
			final float density = view.getResources().getDisplayMetrics().density;
			mWidth = Math.round(100 * density);
			mHeight = Math.round(48 * density);
			mElevation = 4 * density;
			mCornerRadius = 2 * density;
			mZoom = DEFAULT_ZOOM;
			mHorizontalDefaultSourceToMagnifierOffset = 0;
			mVerticalDefaultSourceToMagnifierOffset = Math.round(-42 * density);
			mClippingEnabled = true;
			mLeftContentBound = SOURCE_BOUND_MAX_VISIBLE;
			mTopContentBound = SOURCE_BOUND_MAX_VISIBLE;
			mRightContentBound = SOURCE_BOUND_MAX_VISIBLE;
			mBottomContentBound = SOURCE_BOUND_MAX_VISIBLE;
		}

		public Builder setSize(int width, int height) {
			if (width <= 0 || height <= 0)
				throw new IllegalArgumentException("Magnifier size should be positive");
			mWidth = width;
			mHeight = height;
			return this;
		}

		public Builder setInitialZoom(float zoom) {
			if (zoom <= 0)
				throw new IllegalArgumentException("Zoom should be positive");
			mZoom = zoom;
			return this;
		}

		public Builder setElevation(float elevation) {
			if (elevation < 0)
				throw new IllegalArgumentException("Elevation should be non-negative");
			mElevation = elevation;
			return this;
		}

		public Builder setCornerRadius(float cornerRadius) {
			if (cornerRadius < 0)
				throw new IllegalArgumentException("Corner radius should be non-negative");
			mCornerRadius = cornerRadius;
			return this;
		}

		public Builder setOverlay(Drawable overlay) {
			mOverlay = overlay;
			return this;
		}

		public Builder setDefaultSourceToMagnifierOffset(int horizontalOffset, int verticalOffset) {
			mHorizontalDefaultSourceToMagnifierOffset = horizontalOffset;
			mVerticalDefaultSourceToMagnifierOffset = verticalOffset;
			return this;
		}

		public Builder setClippingEnabled(boolean clip) {
			mClippingEnabled = clip;
			return this;
		}

		public Builder setSourceBounds(int left, int top, int right, int bottom) {
			mLeftContentBound = left;
			mTopContentBound = top;
			mRightContentBound = right;
			mBottomContentBound = bottom;
			return this;
		}

		public Magnifier build() {
			return new Magnifier(this);
		}
	}
}
