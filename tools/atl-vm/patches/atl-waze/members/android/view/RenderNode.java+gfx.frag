	/* ---- teslalinux (gfx): layer type and paint, overlapping, shadow colours, inverse matrix ---- */
	private int tlLayerType;
	private boolean tlForceLayer;
	private boolean tlOverlapping = true;
	private Paint tlLayerPaint;
	private int tlSpotShadowColor = 0xff000000;
	private int tlAmbientShadowColor = 0xff000000;

	/** graphics.RenderNode.setUseCompositingLayer: a layer even with LAYER_TYPE_NONE. */
	protected void tlSetForceLayer(boolean force, Paint paint) {
		tlForceLayer = force;
		setLayerPaint(paint);
	}

	/** The content wrapped in the layer paint's colour filter and alpha, or 0 when there is nothing to apply. */
	private long tlLayerKeep(long content) {
		if ((tlLayerType == 0 && !tlForceLayer) || tlLayerPaint == null || content == 0)
			return 0;
		android.graphics.ColorFilter f = tlLayerPaint.getColorFilter();
		boolean filter = f instanceof android.graphics.PorterDuffColorFilter || f instanceof android.graphics.BlendModeColorFilter
		                 || f instanceof android.graphics.ColorMatrixColorFilter || f instanceof android.graphics.LightingColorFilter;
		float a = tlLayerPaint.getAlpha() / 255f;
		if (!filter && a >= 1f)
			return 0;
		return nativeLayerPaintKeep(content, filter ? tlLayerPaint.paint : 0, a);
	}

	private static native long nativeLayerPaintKeep(long node, long paint, float alpha);

	public boolean setSpotShadowColor(int color) {
		boolean changed = tlSpotShadowColor != color;
		tlSpotShadowColor = color;
		return changed;
	}

	public int getSpotShadowColor() {
		return tlSpotShadowColor;
	}

	public boolean setAmbientShadowColor(int color) {
		boolean changed = tlAmbientShadowColor != color;
		tlAmbientShadowColor = color;
		return changed;
	}

	public int getAmbientShadowColor() {
		return tlAmbientShadowColor;
	}

	/** AOSP: the inverse of getMatrix (identity when it cannot be inverted). */
	public void getInverseMatrix(Matrix outMatrix) {
		Matrix m = new Matrix();
		getMatrix(m);
		if (!m.invert(outMatrix))
			outMatrix.reset();
	}

	public boolean getClipToBounds() {
		return clipToBounds;
	}

	/** AOSP (hidden, used by Compose): frees the recorded display list. */
	public void destroyDisplayListData() {
		if (isValid())
			discardDisplayList();
	}
