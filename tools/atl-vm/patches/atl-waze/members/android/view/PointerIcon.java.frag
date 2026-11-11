	private android.graphics.Bitmap mBitmap;
	private float mHotSpotX, mHotSpotY;

	/** A custom pointer icon from a bitmap and a hot spot inside it (AOSP validation). */
	public static PointerIcon create(android.graphics.Bitmap bitmap, float hotSpotX, float hotSpotY) {
		if (bitmap == null)
			throw new IllegalArgumentException("bitmap must not be null");
		if (hotSpotX < 0 || hotSpotX >= bitmap.getWidth())
			throw new IllegalArgumentException("x hotspot lies outside of the bitmap area");
		if (hotSpotY < 0 || hotSpotY >= bitmap.getHeight())
			throw new IllegalArgumentException("y hotspot lies outside of the bitmap area");
		PointerIcon icon = new PointerIcon();
		icon.mBitmap = bitmap;
		icon.mHotSpotX = hotSpotX;
		icon.mHotSpotY = hotSpotY;
		return icon;
	}
	/* AOSP: the system pointer icon types */
	public static final int TYPE_CUSTOM = -1;
	public static final int TYPE_NULL = 0;
	public static final int TYPE_NOT_SPECIFIED = 1;
	public static final int TYPE_ARROW = 1000;
	public static final int TYPE_CONTEXT_MENU = 1001;
	public static final int TYPE_HAND = 1002;
	public static final int TYPE_HELP = 1003;
	public static final int TYPE_WAIT = 1004;
	public static final int TYPE_CELL = 1006;
	public static final int TYPE_CROSSHAIR = 1007;
	public static final int TYPE_TEXT = 1008;
	public static final int TYPE_VERTICAL_TEXT = 1009;
	public static final int TYPE_ALIAS = 1010;
	public static final int TYPE_COPY = 1011;
	public static final int TYPE_NO_DROP = 1012;
	public static final int TYPE_ALL_SCROLL = 1013;
	public static final int TYPE_HORIZONTAL_DOUBLE_ARROW = 1014;
	public static final int TYPE_VERTICAL_DOUBLE_ARROW = 1015;
	public static final int TYPE_TOP_RIGHT_DIAGONAL_DOUBLE_ARROW = 1016;
	public static final int TYPE_TOP_LEFT_DIAGONAL_DOUBLE_ARROW = 1017;
	public static final int TYPE_ZOOM_IN = 1018;
	public static final int TYPE_ZOOM_OUT = 1019;
	public static final int TYPE_GRAB = 1020;
	public static final int TYPE_GRABBING = 1021;
	public static final int TYPE_HANDWRITING = 1022;
	public static final int TYPE_DEFAULT = TYPE_ARROW;
