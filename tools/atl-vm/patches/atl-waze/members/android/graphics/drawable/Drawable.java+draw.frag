	/* ---- teslalinux (draw): AOSP base Drawable state ---- */
	/** AOSP mLevel: 0..10000, see setLevel. */
	private int mLevel = 0;
	/** AOSP mChangingConfigurations. */
	private int mChangingConfigurations = 0;
	/** AOSP mLayoutDirection (View.LAYOUT_DIRECTION_LTR = 0). */
	private int mLayoutDirection = LayoutDirection.LTR;
	/** The alpha of ATL's plain paintable drawable (AOSP keeps alpha in the subclasses' paints). */
	private int mTlAlpha = 0xFF;
	private PorterDuffColorFilter mTlAlphaFilter;
	/** AOSP (package): the density XML children are looked up at, 0 for the resources' density. */
	int mSrcDensityOverride = 0;
	/** AOSP: re-entrancy guards between setTintMode and setTintBlendMode. */
	private boolean mSetTintModeInvoked;
	private boolean mSetBlendModeInvoked;

	/**
	 * AOSP: the BitmapFactory.Options variant (deprecated in AOSP). ATL's decoder takes no options
	 * here (the drawable is decoded at the asset's own size, as the null-options path does).
	 */
	public static Drawable createFromResourceStream(Resources res, TypedValue value, InputStream is, String srcName, BitmapFactory.Options opts) {
		return createFromResourceStream(res, value, is, srcName, (Object) opts);
	}

	/** AOSP (hidden): XML inflation with a density override; ATL inflates at the resource density. */
	public static Drawable createFromXmlForDensity(Resources r, XmlPullParser parser, int density, Theme theme) throws XmlPullParserException, IOException {
		AttributeSet attrs = android.util.Xml.asAttributeSet(parser);
		int type;
		while ((type = parser.next()) != XmlPullParser.START_TAG && type != XmlPullParser.END_DOCUMENT)
			;
		if (type != XmlPullParser.START_TAG)
			throw new XmlPullParserException("No start tag found");
		Drawable drawable = createFromXmlInnerForDensity(r, parser, attrs, density, theme);
		if (drawable == null)
			throw new RuntimeException("Unknown initial tag: " + parser.getName());
		return drawable;
	}

	/** AOSP (hidden): createFromXmlInner with a density override (0: the resources' density). */
	static Drawable createFromXmlInnerForDensity(Resources r, XmlPullParser parser, AttributeSet attrs, int density, Theme theme) throws XmlPullParserException, IOException {
		return createFromXmlInner(r, parser, attrs, theme);
	}

	/**
	 * Skia's saveLayer(bounds, paint) for a drawable that composites several draws before applying
	 * one alpha and one colour filter (GradientDrawable with a translucent stroke over a fill): on
	 * ATL's GSK canvas a group with a DST_IN alpha (outer) and the colour filter as a colour matrix
	 * (inner) gives the same result. Returns the number of groups pushed (pop them with
	 * tlPopLayer), or -1 when the canvas or the filter cannot be expressed that way (the caller
	 * then filters each draw itself).
	 */
	static int tlPushLayer(Canvas canvas, int alpha, ColorFilter filter) {
		final GskCanvas gsk = canvas.tlGskCanvas();
		if (gsk == null)
			return -1;
		PorterDuffColorFilter pd = null;
		if (filter instanceof PorterDuffColorFilter) {
			pd = (PorterDuffColorFilter) filter;
		} else if (filter instanceof BlendModeColorFilter) {
			BlendModeColorFilter blend = (BlendModeColorFilter) filter;
			PorterDuff.Mode mode = blend.getMode() == BlendMode.MODULATE ? PorterDuff.Mode.MULTIPLY : BlendMode.blendModeToPorterDuffMode(blend.getMode());
			if (mode == null)
				return -1;
			pd = new PorterDuffColorFilter(blend.getColor(), mode);
		} else if (filter != null) {
			return -1;
		}
		int pushed = 0;
		alpha &= 0xFF;
		if (alpha != 0xFF && gsk.tlPushColorFilter(new PorterDuffColorFilter(alpha << 24, PorterDuff.Mode.DST_IN)))
			pushed++;
		if (pd != null && gsk.tlPushColorFilter(pd))
			pushed++;
		return pushed;
	}

	/** Closes the groups tlPushLayer opened. */
	static void tlPopLayer(Canvas canvas, int pushed) {
		for (int i = 0; i < pushed; i++)
			canvas.tlGskCanvas().tlPopGroup();
	}
