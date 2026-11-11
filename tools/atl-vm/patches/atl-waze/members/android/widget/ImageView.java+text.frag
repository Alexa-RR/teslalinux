	/* ==== teslalinux (+text): AOSP ImageView measuring, URI/level images, baseline ==== */
	private boolean mAdjustViewBounds;
	private int mMaxWidth = Integer.MAX_VALUE;
	private int mMaxHeight = Integer.MAX_VALUE;
	private int mBaseline = -1;
	private boolean mBaselineAlignBottom;
	private boolean mCropToPaddingFlag;
	private int mLevel;
	private android.net.Uri mUri;
	private final Matrix mTlMatrix = new Matrix();

	public boolean getAdjustViewBounds() {
		return mAdjustViewBounds;
	}

	public int getMaxWidth() {
		return mMaxWidth;
	}

	public int getMaxHeight() {
		return mMaxHeight;
	}

	/** AOSP onMeasure: the drawable's intrinsic size plus padding (adjustViewBounds, maxWidth and
	 * maxHeight applied). Always in Java: GtkPicture measures its paintable, and paintables without an
	 * intrinsic size (NinePatchPaintable) measured as anything but the drawable's size (Waze's alert
	 * card drew its 8 px route track as a 69 px black block). */
	@Override
	protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
		int w, h;
		float desiredAspect = 0.0f;
		boolean resizeWidth = false, resizeHeight = false;
		final int widthSpecMode = MeasureSpec.getMode(widthMeasureSpec);
		final int heightSpecMode = MeasureSpec.getMode(heightMeasureSpec);
		final Drawable d = getDrawable();
		if (d == null) {
			w = h = 0;
		} else {
			w = d.getIntrinsicWidth();
			h = d.getIntrinsicHeight();
			if (w <= 0)
				w = 1;
			if (h <= 0)
				h = 1;
			if (mAdjustViewBounds) {
				resizeWidth = widthSpecMode != MeasureSpec.EXACTLY;
				resizeHeight = heightSpecMode != MeasureSpec.EXACTLY;
				desiredAspect = (float) w / (float) h;
			}
		}
		final int pleft = getPaddingLeft(), pright = getPaddingRight(), ptop = getPaddingTop(), pbottom = getPaddingBottom();
		int widthSize, heightSize;
		if (resizeWidth || resizeHeight) {
			widthSize = resolveAdjustedSize(w + pleft + pright, mMaxWidth, widthMeasureSpec);
			heightSize = resolveAdjustedSize(h + ptop + pbottom, mMaxHeight, heightMeasureSpec);
			if (desiredAspect != 0.0f) {
				final float actualAspect = (float) (widthSize - pleft - pright) / (heightSize - ptop - pbottom);
				if (Math.abs(actualAspect - desiredAspect) > 0.0000001) {
					boolean done = false;
					if (resizeWidth) {
						int newWidth = (int) (desiredAspect * (heightSize - ptop - pbottom)) + pleft + pright;
						if (!resizeHeight)
							widthSize = resolveAdjustedSize(newWidth, mMaxWidth, widthMeasureSpec);
						if (newWidth <= widthSize) {
							widthSize = newWidth;
							done = true;
						}
					}
					if (!done && resizeHeight) {
						int newHeight = (int) ((widthSize - pleft - pright) / desiredAspect) + ptop + pbottom;
						if (!resizeWidth)
							heightSize = resolveAdjustedSize(newHeight, mMaxHeight, heightMeasureSpec);
						if (newHeight <= heightSize)
							heightSize = newHeight;
					}
				}
			}
		} else {
			w += pleft + pright;
			h += ptop + pbottom;
			w = Math.max(w, getSuggestedMinimumWidth());
			h = Math.max(h, getSuggestedMinimumHeight());
			widthSize = resolveSizeAndState(w, widthMeasureSpec, 0);
			heightSize = resolveSizeAndState(h, heightMeasureSpec, 0);
		}
		setMeasuredDimension(widthSize, heightSize);
	}

	private int resolveAdjustedSize(int desiredSize, int maxSize, int measureSpec) {
		int result = desiredSize;
		final int specMode = MeasureSpec.getMode(measureSpec);
		final int specSize = MeasureSpec.getSize(measureSpec);
		switch (specMode) {
			case MeasureSpec.UNSPECIFIED:
				result = Math.min(desiredSize, maxSize);
				break;
			case MeasureSpec.AT_MOST:
				result = Math.min(Math.min(desiredSize, specSize), maxSize);
				break;
			case MeasureSpec.EXACTLY:
				result = specSize;
				break;
		}
		return result;
	}

	/** AOSP: an image from a URI (android.resource, content, file), read through the ContentResolver */
	public void setImageURI(android.net.Uri uri) {
		mUri = uri;
		if (uri == null) {
			setImageDrawable(null);
			return;
		}
		Drawable d = null;
		final String scheme = uri.getScheme();
		try {
			if ("android.resource".equals(scheme)) {
				final java.util.List<String> segs = uri.getPathSegments();
				final android.content.res.Resources res = getContext().getResources();
				int id = 0;
				if (segs.size() == 1) {
					id = Integer.parseInt(segs.get(0));
				} else if (segs.size() == 2) {
					id = res.getIdentifier(segs.get(1), segs.get(0), uri.getAuthority());
				}
				if (id != 0)
					d = getContext().getDrawable(id);
			} else {
				java.io.InputStream is = (scheme == null || "file".equals(scheme))
					? new java.io.FileInputStream(uri.getPath())
					: getContext().getContentResolver().openInputStream(uri);
				try {
					d = Drawable.createFromResourceStream(getResources(), null, is, uri.toString(), null);
				} finally {
					is.close();
				}
			}
		} catch (Exception e) {
			android.util.Log.w("ImageView", "Unable to open content: " + uri, e);
		}
		setImageDrawable(d);
	}

	/** AOSP: the image drawable's level (LevelListDrawable, ClipDrawable, ...) */
	public void setImageLevel(int level) {
		mLevel = level;
		final Drawable d = getDrawable();
		if (d != null) {
			d.setLevel(level);
			requestLayout();
			invalidate();
		}
	}

	/** AOSP: the matrix the image is drawn with (configureBounds' mDrawMatrix), identity when none */
	public Matrix getImageMatrix() {
		final Matrix m = tlDrawMatrix();
		return m != null ? m : new Matrix(Matrix.IDENTITY_MATRIX);
	}

	/** set by animateTransform until the next configureBounds (a new drawable, scale type, image matrix or size) */
	private Matrix mTlAnimMatrix;
	private boolean mTlAnimActive;
	private int mTlAnimW, mTlAnimH;

	/**
	 * AOSP ImageView.configureBounds: the drawable at its intrinsic size through this matrix (null:
	 * the drawable fills the content box). The native image paintable draws the same placement
	 * (native-imageview-scaletype.sh).
	 */
	private Matrix tlDrawMatrix() {
		final android.graphics.drawable.Drawable d = getDrawable();
		if (d == null)
			return null;
		final int vwidth = getWidth() - getPaddingLeft() - getPaddingRight();
		final int vheight = getHeight() - getPaddingTop() - getPaddingBottom();
		if (mTlAnimActive) {
			if (mTlAnimW == vwidth && mTlAnimH == vheight)
				return mTlAnimMatrix;
			mTlAnimActive = false;
		}
		final int dwidth = d.getIntrinsicWidth();
		final int dheight = d.getIntrinsicHeight();
		final ScaleType st = getScaleType();
		if (dwidth <= 0 || dheight <= 0 || st == ScaleType.FIT_XY)
			return null;
		if (st == ScaleType.MATRIX)
			return mTlMatrix.isIdentity() ? null : mTlMatrix;
		if (vwidth == dwidth && vheight == dheight)
			return null;
		final Matrix m = new Matrix();
		if (st == ScaleType.CENTER) {
			m.setTranslate(Math.round((vwidth - dwidth) * 0.5f), Math.round((vheight - dheight) * 0.5f));
		} else if (st == ScaleType.CENTER_CROP) {
			float scale, dx = 0, dy = 0;
			if (dwidth * vheight > vwidth * dheight) {
				scale = (float) vheight / (float) dheight;
				dx = (vwidth - dwidth * scale) * 0.5f;
			} else {
				scale = (float) vwidth / (float) dwidth;
				dy = (vheight - dheight * scale) * 0.5f;
			}
			m.setScale(scale, scale);
			m.postTranslate(Math.round(dx), Math.round(dy));
		} else if (st == ScaleType.CENTER_INSIDE) {
			float scale;
			if (dwidth <= vwidth && dheight <= vheight)
				scale = 1.0f;
			else
				scale = Math.min((float) vwidth / (float) dwidth, (float) vheight / (float) dheight);
			m.setScale(scale, scale);
			m.postTranslate(Math.round((vwidth - dwidth * scale) * 0.5f), Math.round((vheight - dheight * scale) * 0.5f));
		} else {
			final Matrix.ScaleToFit fit = st == ScaleType.FIT_START ? Matrix.ScaleToFit.START
			                              : st == ScaleType.FIT_END ? Matrix.ScaleToFit.END : Matrix.ScaleToFit.CENTER;
			m.setRectToRect(new android.graphics.RectF(0, 0, dwidth, dheight), new android.graphics.RectF(0, 0, vwidth, vheight), fit);
		}
		return m;
	}

	/** AOSP (hidden, androidx.transition ImageViewUtils): the draw matrix during a transition, until
	 * the next configureBounds; null fills the content box with the drawable */
	public void animateTransform(Matrix matrix) {
		if (getDrawable() == null)
			return;
		float[] values = null;
		if (matrix != null) {
			values = new float[9];
			matrix.getValues(values);
		}
		mTlAnimMatrix = matrix != null ? new Matrix(matrix) : null;
		mTlAnimActive = true;
		mTlAnimW = getWidth() - getPaddingLeft() - getPaddingRight();
		mTlAnimH = getHeight() - getPaddingTop() - getPaddingBottom();
		native_animateTransform(widget, values);
		invalidate();
	}

	protected native void native_setImageMatrix(long widget, float[] values);
	protected native void native_setCropToPadding(long widget, boolean crop);
	protected native void native_animateTransform(long widget, float[] values);

	public boolean getCropToPadding() {
		return mCropToPaddingFlag;
	}

	public void setBaseline(int baseline) {
		if (mBaseline != baseline) {
			mBaseline = baseline;
			requestLayout();
		}
	}

	public void setBaselineAlignBottom(boolean aligned) {
		if (mBaselineAlignBottom != aligned) {
			mBaselineAlignBottom = aligned;
			requestLayout();
		}
	}

	public boolean getBaselineAlignBottom() {
		return mBaselineAlignBottom;
	}

	@Override
	public int getBaseline() {
		if (mBaselineAlignBottom)
			return getMeasuredHeight();
		return mBaseline;
	}
