	/* teslalinux: Bitmap additions */
	private boolean tlMipMap;
	public int getPixel(int x, int y) { int[] p = new int[1]; getPixels(p, 0, 1, x, y, 1, 1); return p[0]; }
	public void setPixel(int x, int y, int color) { setPixels(new int[] { color }, 0, 1, x, y, 1, 1); }
	public boolean hasMipMap() { return tlMipMap; }
	public void setHasMipMap(boolean hasMipMap) { tlMipMap = hasMipMap; }
	public Bitmap asShared() { return this; }

	/** API 28: a bitmap with the contents of a Picture at its recorded size. */
	public static Bitmap createBitmap(Picture source) {
		return createBitmap(source, source.getWidth(), source.getHeight(), Config.ARGB_8888);
	}

	public static Bitmap createBitmap(Picture source, int width, int height, Config config) {
		if (width <= 0 || height <= 0)
			throw new IllegalArgumentException("width & height must be > 0");
		if (config == null)
			throw new IllegalArgumentException("Config must not be null");
		Bitmap bitmap = createBitmap(width, height, config);
		Canvas canvas = new Canvas(bitmap);
		if (source.getWidth() > 0 && source.getHeight() > 0)
			canvas.scale(width / (float) source.getWidth(), height / (float) source.getHeight());
		source.draw(canvas);
		return bitmap;
	}

	/** API 26: with an explicit alpha flag and colour space (sRGB is the only space the pixels are kept in). */
	public static Bitmap createBitmap(int width, int height, Config config, boolean hasAlpha, ColorSpace colorSpace) {
		return createBitmap(width, height, config, hasAlpha);
	}

	public static Bitmap createBitmap(int width, int height, Config config, boolean hasAlpha) {
		Bitmap b = createBitmap(width, height, config);
		if (!hasAlpha)
			b.eraseColor(0xff000000);
		return b;
	}
	/**
	 * AOSP: indicates that the bitmap was created for an unknown pixel density; no density scaling is applied.
	 */
	public static final int DENSITY_NONE = 0;
