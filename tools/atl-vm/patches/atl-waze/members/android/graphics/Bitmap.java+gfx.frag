	/* ---- teslalinux (gfx) ---- */

	/**
	 * The bitmap's content becomes what snapshot s records (GskCanvas composes a transfer-mode draw
	 * with the bitmap's previous content into a fresh snapshot). The snapshot is owned by the bitmap.
	 */
	public synchronized void tlSetSnapshot(long s) {
		native_recycle(texture, snapshot);
		texture = 0;
		snapshot = s;
	}

	/** AOSP: the density the bitmap was made for (the display density by default). */
	private int tlDensity = -1;
	private boolean tlPremultiplied = true;
	private int tlAllocation;
	private int tlGeneration;
	private ColorSpace tlColorSpace;
	private Gainmap tlGainmap;

	public int getDensity() {
		if (tlDensity < 0)
			tlDensity = android.content.res.Resources.getSystem().getDisplayMetrics().densityDpi;
		return tlDensity;
	}

	/** AOSP: changes whenever the pixels change. */
	public int getGenerationId() {
		return tlGeneration;
	}

	/** AOSP: same size, config and pixels. */
	public boolean sameAs(Bitmap other) {
		if (other == this)
			return true;
		if (other == null || other.width != width || other.height != height || other.config != config)
			return false;
		int[] a = new int[width * height], b = new int[width * height];
		getPixels(a, 0, width, 0, 0, width, height);
		other.getPixels(b, 0, width, 0, 0, width, height);
		return java.util.Arrays.equals(a, b);
	}

	/**
	 * AOSP: the colour space of the pixels: sRGB for the 8-bit configs, extended linear sRGB for
	 * RGBA_F16, none for ALPHA_8, unless one was set with setColorSpace.
	 */
	public ColorSpace getColorSpace() {
		if (config == Config.ALPHA_8)
			return null;
		if (tlColorSpace != null)
			return tlColorSpace;
		return ColorSpace.get(config == Config.RGBA_F16 ? ColorSpace.Named.LINEAR_EXTENDED_SRGB : ColorSpace.Named.SRGB);
	}

	/**
	 * AOSP: changes how the pixels are interpreted (no conversion). AOSP accepts RGB spaces with an
	 * ICC parametric curve only; the GSK texture keeps its sRGB interpretation when drawn.
	 */
	public void setColorSpace(ColorSpace colorSpace) {
		if (colorSpace == null)
			throw new IllegalArgumentException("The colorSpace cannot be set to null");
		if (config == Config.ALPHA_8)
			throw new IllegalArgumentException("Cannot set a ColorSpace on ALPHA_8");
		if (colorSpace.getModel() != ColorSpace.Model.RGB)
			throw new IllegalArgumentException("Unsupported color model: " + colorSpace.getModel());
		tlColorSpace = colorSpace;
	}

	/** AOSP API 34: an HDR gain map attached to the bitmap (none unless set). */
	public boolean hasGainmap() {
		return tlGainmap != null;
	}

	public Gainmap getGainmap() {
		return tlGainmap;
	}

	/**
	 * AOSP API 34. The gain map is kept with the bitmap; as on Android with an SDR display, the base
	 * image is what is drawn.
	 */
	public void setGainmap(Gainmap gainmap) {
		tlGainmap = gainmap;
	}

	private static native byte[] native_compress(long texture, String format, int quality, boolean lossless);

	/** RGB_565 / ARGB_4444 pixels as little-endian 16-bit words (premultiplied 4444, as Skia stores it). */
	private byte[] tlPack16() {
		int[] px = new int[width * height];
		getPixels(px, 0, width, 0, 0, width, height);
		byte[] out = new byte[stride * height];
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				int c = px[y * width + x];
				int a = c >>> 24, r = (c >> 16) & 0xff, g = (c >> 8) & 0xff, b = c & 0xff;
				int v;
				if (config == Config.RGB_565) {
					v = ((r >> 3) << 11) | ((g >> 2) << 5) | (b >> 3);
				} else {
					if (tlPremultiplied) {
						r = r * a / 255;
						g = g * a / 255;
						b = b * a / 255;
					}
					v = ((r >> 4) << 12) | ((g >> 4) << 8) | ((b >> 4) << 4) | (a >> 4);
				}
				int o = y * stride + x * 2;
				out[o] = (byte) v;
				out[o + 1] = (byte) (v >> 8);
			}
		}
		return out;
	}

	private static void tlPut(Buffer buffer, byte[] data) {
		if (buffer instanceof java.nio.ByteBuffer) {
			((java.nio.ByteBuffer) buffer).put(data);
		} else {
			java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.nativeOrder());
			if (buffer instanceof java.nio.ShortBuffer)
				((java.nio.ShortBuffer) buffer).put(bb.asShortBuffer());
			else
				((java.nio.IntBuffer) buffer).put(bb.asIntBuffer());
		}
	}

	/** AOSP: replaces the pixels from the buffer, in the bitmap's memory layout; advances its position. */
	public void copyPixelsFromBuffer(Buffer src) {
		if (!isMutable())
			throw new IllegalStateException("Bitmap is immutable");
		int shift = src instanceof java.nio.ByteBuffer ? 0 : src instanceof java.nio.ShortBuffer ? 1 : src instanceof java.nio.IntBuffer ? 2 : -1;
		if (shift < 0)
			throw new RuntimeException("unsupported Buffer subclass");
		int needed = getAllocationByteCount();
		if (((long) src.remaining() << shift) < needed)
			throw new RuntimeException("Buffer not large enough for pixels");
		byte[] raw = new byte[needed];
		if (src instanceof java.nio.ByteBuffer) {
			((java.nio.ByteBuffer) src).get(raw);
		} else {
			java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.nativeOrder());
			if (src instanceof java.nio.ShortBuffer) {
				java.nio.ShortBuffer s = (java.nio.ShortBuffer) src;
				short[] tmp = new short[needed >> 1];
				s.get(tmp);
				bb.asShortBuffer().put(tmp);
			} else {
				java.nio.IntBuffer s = (java.nio.IntBuffer) src;
				int[] tmp = new int[needed >> 2];
				s.get(tmp);
				bb.asIntBuffer().put(tmp);
			}
		}
		int[] px = new int[width * height];
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				int c;
				if (config == Config.RGB_565 || config == Config.ARGB_4444) {
					int o = y * stride + x * 2;
					int v = (raw[o] & 0xff) | ((raw[o + 1] & 0xff) << 8);
					if (config == Config.RGB_565) {
						int r = (v >> 11) & 0x1f, g = (v >> 5) & 0x3f, b = v & 0x1f;
						c = 0xff000000 | ((r << 3 | r >> 2) << 16) | ((g << 2 | g >> 4) << 8) | (b << 3 | b >> 2);
					} else {
						int r = (v >> 12) & 0xf, g = (v >> 8) & 0xf, b = (v >> 4) & 0xf, a = v & 0xf;
						c = Color.argb(a * 17, r * 17, g * 17, b * 17);
						c = tlUnpremul(c);
					}
				} else if (config == Config.ALPHA_8) {
					c = (raw[y * stride + x] & 0xff) << 24;
				} else if (config == Config.RGBA_F16) {
					int o = y * stride + x * 8;
					java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(raw, o, 8).order(java.nio.ByteOrder.LITTLE_ENDIAN);
					float r = android.util.Half.toFloat(bb.getShort()), g = android.util.Half.toFloat(bb.getShort());
					float b = android.util.Half.toFloat(bb.getShort()), a = android.util.Half.toFloat(bb.getShort());
					c = Color.argb(tlClamp(a), tlClamp(a > 0 && tlPremultiplied ? r / a : r), tlClamp(a > 0 && tlPremultiplied ? g / a : g), tlClamp(a > 0 && tlPremultiplied ? b / a : b));
				} else {
					int o = y * stride + x * 4;
					c = Color.argb(raw[o + 3] & 0xff, raw[o] & 0xff, raw[o + 1] & 0xff, raw[o + 2] & 0xff);
					if (tlPremultiplied && hasAlpha)
						c = tlUnpremul(c);
				}
				px[y * width + x] = c;
			}
		}
		native_recycle(texture, snapshot);
		texture = 0;
		snapshot = native_erase_color(0, width, height);
		setPixels(px, 0, width, 0, 0, width, height);
	}

	private static int tlClamp(float v) {
		return Math.max(0, Math.min(255, Math.round(v * 255f)));
	}

	private static int tlUnpremul(int c) {
		int a = c >>> 24;
		if (a == 0 || a == 255)
			return a == 0 ? 0 : c;
		int r = Math.min(255, ((c >> 16) & 0xff) * 255 / a), g = Math.min(255, ((c >> 8) & 0xff) * 255 / a), b = Math.min(255, (c & 0xff) * 255 / a);
		return Color.argb(a, r, g, b);
	}

	/** AOSP (hidden): a size scaled from one density to another, rounded (no scaling with DENSITY_NONE). */
	public static int scaleFromDensity(int size, int sdensity, int tdensity) {
		return tlScale(size, sdensity, tdensity);
	}
