	/* teslalinux: Display additions */
	public static final int STATE_UNKNOWN = 0, STATE_OFF = 1, STATE_ON = 2, STATE_DOZE = 3, STATE_DOZE_SUSPEND = 4, STATE_VR = 5, STATE_ON_SUSPEND = 6;
	public static final class Mode {
		private final int id, w, h; private final float rate;
		public Mode(int id, int w, int h, float rate) { this.id = id; this.w = w; this.h = h; this.rate = rate; }
		public int getModeId() { return id; }
		public int getPhysicalWidth() { return w; }
		public int getPhysicalHeight() { return h; }
		public float getRefreshRate() { return rate; }
		public float[] getAlternativeRefreshRates() { return new float[0]; }
		public int[] getSupportedHdrTypes() { return new int[0]; }
		@Override public boolean equals(Object o) { return o instanceof Mode && ((Mode) o).id == id; }
		@Override public int hashCode() { return id; }
	}
	public static final class HdrCapabilities {
		public static final int HDR_TYPE_DOLBY_VISION = 1, HDR_TYPE_HDR10 = 2, HDR_TYPE_HLG = 3, HDR_TYPE_HDR10_PLUS = 4;
		public int[] getSupportedHdrTypes() { return new int[0]; }
		public float getDesiredMaxLuminance() { return 500f; }
		public float getDesiredMaxAverageLuminance() { return 300f; }
		public float getDesiredMinLuminance() { return 0.1f; }
	}
	public Mode getMode() { return new Mode(1, getWidth(), getHeight(), getRefreshRate()); }
	public Mode[] getSupportedModes() { return new Mode[] { getMode() }; }
	public int getState() { return STATE_ON; }
	public boolean isHdr() { return false; }
	public HdrCapabilities getHdrCapabilities() { return new HdrCapabilities(); }
	public boolean isWideColorGamut() { return false; }
	public boolean isMinimalPostProcessingSupported() { return false; }
	public float[] getSupportedRefreshRates() { return new float[] { getRefreshRate() }; }
	public int getFlags() { return 0; }
	public String getName() { return "atl"; }
	public boolean isValid() { return true; }
	/** Invalid display id (AOSP). */
	public static final int INVALID_DISPLAY = -1;

	/**
	 * AOSP: the display's rounded corner at a position (RoundedCorner.POSITION_*), or null when that
	 * corner is not rounded. The car screen is a browser viewport with square corners, so every
	 * position reports null, as AOSP does for a display without rounded corners.
	 */
	public RoundedCorner getRoundedCorner(int position) {
		if (position < RoundedCorner.POSITION_TOP_LEFT || position > RoundedCorner.POSITION_BOTTOM_LEFT)
			throw new IllegalArgumentException("unknown rounded corner position " + position);
		return null;
	}
