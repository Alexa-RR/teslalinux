	/* ---- teslalinux (gfx): path effects, mask filters, text skew, stroke miter (native/atl_gfx_canvas.c) ---- */
	private PathEffect mPathEffect;
	private MaskFilter mMaskFilter;
	private float mTextSkewX;
	private String mFontVariationSettings;
	private static native void native_set_font_variations(long paint, String pangoVariations);

	private static native void native_set_stroke_miter(long paint, float miter);
	private static native float native_get_stroke_miter(long paint);
	private static native void native_set_dash(long paint, float[] intervals, float phase);
	private static native void native_set_color_matrix(long paint, float[] matrix);

	/** AOSP's Paint copy covers every setting; the native clone holds colour, stroke and font, this the rest. */
	private void tlCopyJavaState(Paint src) {
		xfermode = src.xfermode;
		shader = src.shader;
		align = src.align;
		color_filter = src.color_filter;
		tlAntiAlias = src.tlAntiAlias;
		mTypeface = src.mTypeface;
		mLocales = src.mLocales;
		mFontFeatureSettings = src.mFontFeatureSettings;
		mFlags = src.mFlags;
		mHinting = src.mHinting;
		mLetterSpacing = src.mLetterSpacing;
		mTextScaleX = src.mTextScaleX;
		mShadowLayerRadius = src.mShadowLayerRadius;
		mShadowLayerDx = src.mShadowLayerDx;
		mShadowLayerDy = src.mShadowLayerDy;
		mShadowLayerColor = src.mShadowLayerColor;
		mElegantTextHeight = src.mElegantTextHeight;
		mHasCompatScaling = src.mHasCompatScaling;
		mCompatScaling = src.mCompatScaling;
		mInvCompatScaling = src.mInvCompatScaling;
		mPathEffect = src.mPathEffect;
		mMaskFilter = src.mMaskFilter;
		mTextSkewX = src.mTextSkewX;
		mFontVariationSettings = src.mFontVariationSettings;
	}

	private void tlResetGfx() {
		mPathEffect = null;
		mMaskFilter = null;
		mTextSkewX = 0;
		mTypeface = null; /* AOSP reset: the default typeface */
		tlApplyFace();
	}

	/**
	 * The transfer mode of the paint as an SkBlendMode value, or -1 for plain source-over drawing
	 * (no xfermode, or SRC_OVER).
	 */
	public int tlBlendModeValue() {
		if (xfermode instanceof PorterDuffXfermode) {
			int m = ((PorterDuffXfermode) xfermode).porterDuffMode;
			return m == PorterDuff.Mode.SRC_OVER.nativeInt ? -1 : m;
		}
		return -1;
	}
