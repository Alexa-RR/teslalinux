	/* ---- teslalinux (gfx): BitmapFactory.Options as AOSP applies them ---- */

	/** The stream's bytes (the decoders need the header twice: once for the size, once to decode). */
	static byte[] tlReadAll(InputStream is) throws java.io.IOException {
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		byte[] buf = new byte[16384];
		int n;
		while ((n = is.read(buf)) > 0)
			out.write(buf, 0, n);
		return out.toByteArray();
	}

	/** The image's mime type from its signature, or null. */
	static String tlSniffMime(byte[] d) {
		if (d.length >= 8 && (d[0] & 0xff) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G')
			return "image/png";
		if (d.length >= 3 && (d[0] & 0xff) == 0xff && (d[1] & 0xff) == 0xd8 && (d[2] & 0xff) == 0xff)
			return "image/jpeg";
		if (d.length >= 6 && d[0] == 'G' && d[1] == 'I' && d[2] == 'F' && d[3] == '8')
			return "image/gif";
		if (d.length >= 12 && d[0] == 'R' && d[1] == 'I' && d[2] == 'F' && d[3] == 'F' && d[8] == 'W' && d[9] == 'E' && d[10] == 'B' && d[11] == 'P')
			return "image/webp";
		if (d.length >= 2 && d[0] == 'B' && d[1] == 'M')
			return "image/bmp";
		if (d.length >= 4 && d[0] == 0 && d[1] == 0 && d[2] == 1 && d[3] == 0)
			return "image/x-ico";
		if (d.length >= 12 && d[4] == 'f' && d[5] == 't' && d[6] == 'y' && d[7] == 'p') {
			String brand = new String(d, 8, 4, java.nio.charset.StandardCharsets.US_ASCII);
			if (brand.startsWith("avi"))
				return "image/avif";
			return "image/heif";
		}
		return null;
	}

	private static int tlBe16(byte[] d, int o) {
		return ((d[o] & 0xff) << 8) | (d[o + 1] & 0xff);
	}

	private static int tlLe16(byte[] d, int o) {
		return (d[o] & 0xff) | ((d[o + 1] & 0xff) << 8);
	}

	private static int tlLe24(byte[] d, int o) {
		return (d[o] & 0xff) | ((d[o + 1] & 0xff) << 8) | ((d[o + 2] & 0xff) << 16);
	}

	/** {width, height} from the header, or null when the format's header is not parsed here. */
	static int[] tlHeaderSize(byte[] d, String mime) {
		try {
			if ("image/png".equals(mime) && d.length >= 24)
				return new int[] { java.nio.ByteBuffer.wrap(d, 16, 4).getInt(), java.nio.ByteBuffer.wrap(d, 20, 4).getInt() };
			if ("image/gif".equals(mime) && d.length >= 10)
				return new int[] { tlLe16(d, 6), tlLe16(d, 8) };
			if ("image/bmp".equals(mime) && d.length >= 26) {
				java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(d).order(java.nio.ByteOrder.LITTLE_ENDIAN);
				return new int[] { Math.abs(b.getInt(18)), Math.abs(b.getInt(22)) };
			}
			if ("image/webp".equals(mime) && d.length >= 30) {
				String chunk = new String(d, 12, 4, java.nio.charset.StandardCharsets.US_ASCII);
				if (chunk.equals("VP8 "))
					return new int[] { tlLe16(d, 26) & 0x3fff, tlLe16(d, 28) & 0x3fff };
				if (chunk.equals("VP8L")) {
					int b0 = d[21] & 0xff, b1 = d[22] & 0xff, b2 = d[23] & 0xff, b3 = d[24] & 0xff;
					return new int[] { 1 + (((b1 & 0x3f) << 8) | b0), 1 + (((b3 & 0xf) << 10) | (b2 << 2) | ((b1 & 0xc0) >> 6)) };
				}
				if (chunk.equals("VP8X"))
					return new int[] { 1 + tlLe24(d, 24), 1 + tlLe24(d, 27) };
			}
			if ("image/jpeg".equals(mime)) {
				int o = 2;
				while (o + 9 < d.length) {
					if ((d[o] & 0xff) != 0xff) {
						o++;
						continue;
					}
					int m = d[o + 1] & 0xff;
					if (m == 0xff) {
						o++;
						continue;
					}
					if (m == 0xd8 || (m >= 0xd0 && m <= 0xd7) || m == 0x01) {
						o += 2;
						continue;
					}
					if (m >= 0xc0 && m <= 0xcf && m != 0xc4 && m != 0xc8 && m != 0xcc)
						return new int[] { tlBe16(d, o + 7), tlBe16(d, o + 5) };
					o += 2 + tlBe16(d, o + 2);
				}
			}
		} catch (RuntimeException e) {
			// a truncated header: fall back to decoding
		}
		return null;
	}

	/** AOSP: the sample size is rounded down to a power of two. */
	private static int tlSample(int inSampleSize) {
		int s = 1;
		while (s * 2 <= inSampleSize)
			s *= 2;
		return s;
	}

	/** A PNG with Android's nine-patch chunk (its chunk coordinates are not rescaled here). */
	private static boolean tlIsNinePatch(byte[] d) {
		for (int i = 8; i + 8 <= d.length; ) {
			int len = java.nio.ByteBuffer.wrap(d, i, 4).getInt();
			if (d[i + 4] == 'n' && d[i + 5] == 'p' && d[i + 6] == 'T' && d[i + 7] == 'c')
				return true;
			if (d[i + 4] == 'I' && d[i + 5] == 'D' && d[i + 6] == 'A' && d[i + 7] == 'T')
				return false;
			if (len < 0)
				return false;
			i += 12 + len;
		}
		return false;
	}

	private static Bitmap tlDecodeWithOptions(InputStream is, Rect outPadding, Options opts) {
		byte[] data;
		try {
			data = tlReadAll(is);
		} catch (java.io.IOException e) {
			return null;
		}
		String mime = tlSniffMime(data);
		opts.outMimeType = mime;
		int sample = tlSample(opts.inSampleSize);
		boolean ninePatch = "image/png".equals(mime) && tlIsNinePatch(data);
		// AOSP: with inScaled, a bitmap decoded for inDensity is scaled to inTargetDensity
		float scale = 1f;
		if (opts.inScaled && opts.inDensity != 0 && opts.inTargetDensity != 0 && opts.inDensity != opts.inTargetDensity
		    && opts.inDensity != opts.inScreenDensity && !ninePatch)
			scale = opts.inTargetDensity / (float) opts.inDensity;
		if (opts.inJustDecodeBounds) {
			int[] size = tlHeaderSize(data, mime);
			if (size == null) {
				Bitmap full = decodeStreamInternal(new ByteArrayInputStream(data), null, null);
				if (full == null) {
					opts.outWidth = opts.outHeight = -1;
					return null;
				}
				size = new int[] { full.getWidth(), full.getHeight() };
				full.recycle();
			}
			opts.outWidth = (int) (Math.max(1, size[0] / sample) * scale + 0.5f);
			opts.outHeight = (int) (Math.max(1, size[1] / sample) * scale + 0.5f);
			opts.outConfig = Bitmap.Config.ARGB_8888;
			return null;
		}
		Bitmap bm = decodeStreamInternal(new ByteArrayInputStream(data), outPadding, opts);
		if (bm == null) {
			opts.outWidth = opts.outHeight = -1;
			if (opts.inBitmap != null)
				throw new IllegalArgumentException("Problem decoding into existing bitmap");
			return null;
		}
		int w = Math.max(1, bm.getWidth() / sample), h = Math.max(1, bm.getHeight() / sample);
		int sw = (int) (w * scale + 0.5f), sh = (int) (h * scale + 0.5f);
		if (sw != bm.getWidth() || sh != bm.getHeight()) {
			Bitmap scaled = Bitmap.createScaledBitmap(bm, Math.max(1, sw), Math.max(1, sh), true);
			bm.recycle();
			bm = scaled;
		}
		// AOSP setDensityFromOptions (nine-patches keep the display density: their chunk is not rescaled)
		if (opts.inDensity != 0 && !ninePatch) {
			bm.setDensity(opts.inDensity);
			if (opts.inTargetDensity != 0 && opts.inDensity != opts.inTargetDensity && opts.inDensity != opts.inScreenDensity && opts.inScaled)
				bm.setDensity(opts.inTargetDensity);
		}
		bm.mutable = opts.inMutable;
		opts.outWidth = bm.getWidth();
		opts.outHeight = bm.getHeight();
		opts.outConfig = Bitmap.Config.ARGB_8888;
		return bm;
	}
