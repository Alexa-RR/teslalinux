/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.graphics;

import android.content.ContentResolver;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.util.DisplayMetrics;
import android.util.Size;
import android.util.TypedValue;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.Callable;

/**
 * teslalinux: AOSP's ImageDecoder API (API 28) on the BitmapFactory decoders (gdk-pixbuf).
 *
 * The header is read first (size and mime type) and handed to the OnHeaderDecodedListener, which
 * may set the target size, crop, sample size, post processor and the other options; the image is
 * then decoded, sampled/scaled to the target size, cropped, post-processed and given the source's
 * density as AOSP does (resources are scaled to the display density). EXIF orientation of JPEGs is
 * applied, as AOSP's ImageDecoder does. Animated images (GIF, animated WebP/PNG) asked for with
 * decodeDrawable become an AnimatedImageDrawable when that class provides
 * {@code static Drawable tlDecode(InputStream, int srcDensity, int dstDensity)}; otherwise their
 * first frame is decoded.
 */
public final class ImageDecoder implements AutoCloseable {
	public static final int ALLOCATOR_DEFAULT = 0;
	public static final int ALLOCATOR_SOFTWARE = 1;
	public static final int ALLOCATOR_SHARED_MEMORY = 2;
	public static final int ALLOCATOR_HARDWARE = 3;
	public static final int MEMORY_POLICY_LOW_RAM = 0;
	public static final int MEMORY_POLICY_DEFAULT = 1;

	/** Source of encoded image data. */
	public static abstract class Source {
		Source() {}

		abstract InputStream tlOpen() throws IOException;

		/** The density the image was made for (DENSITY_DEFAULT for unknown sources). */
		int tlDensity() {
			return Bitmap.DENSITY_NONE;
		}

		Resources tlResources() {
			return null;
		}

		byte[] tlBytes() throws IOException {
			try (InputStream is = tlOpen()) {
				if (is == null)
					throw new IOException("unable to open the source");
				return BitmapFactory.tlReadAll(is);
			}
		}
	}

	private static final class StreamSource extends Source {
		private final Callable<InputStream> mOpen;
		private final Resources mResources;
		private final int mDensity;

		StreamSource(Callable<InputStream> open, Resources res, int density) {
			mOpen = open;
			mResources = res;
			mDensity = density;
		}

		@Override
		InputStream tlOpen() throws IOException {
			try {
				return mOpen.call();
			} catch (IOException e) {
				throw e;
			} catch (Exception e) {
				throw new IOException(e);
			}
		}

		@Override
		int tlDensity() {
			return mDensity;
		}

		@Override
		Resources tlResources() {
			return mResources;
		}
	}

	/** Information about an encoded image. */
	public static class ImageInfo {
		private final Size mSize;
		private final String mMimeType;
		private final boolean mAnimated;

		ImageInfo(Size size, String mimeType, boolean animated) {
			mSize = size;
			mMimeType = mimeType;
			mAnimated = animated;
		}

		/** Size of the image, without scaling or cropping. */
		public Size getSize() {
			return mSize;
		}

		/** The mimeType of the image. */
		public String getMimeType() {
			return mMimeType != null ? mMimeType : "";
		}

		/** Whether the image is animated. */
		public boolean isAnimated() {
			return mAnimated;
		}

		/** The colour space the image is decoded into: the decoders produce sRGB. */
		public ColorSpace getColorSpace() {
			return ColorSpace.get(ColorSpace.Named.SRGB);
		}
	}

	/** Thrown if the provided data is incomplete or not a supported image. */
	public static final class DecodeException extends IOException {
		public static final int SOURCE_EXCEPTION = 1;
		public static final int SOURCE_INCOMPLETE = 2;
		public static final int SOURCE_MALFORMED_DATA = 3;

		private final int mError;
		private final Source mSource;

		public DecodeException(int error, String msg, Throwable cause, Source source) {
			super(msg, cause);
			mError = error;
			mSource = source;
		}

		public int getError() {
			return mError;
		}

		public Source getSource() {
			return mSource;
		}
	}

	public interface OnHeaderDecodedListener {
		void onHeaderDecoded(ImageDecoder decoder, ImageInfo info, Source source);
	}

	public interface OnPartialImageListener {
		boolean onPartialImage(DecodeException exception);
	}

	private final Source mSource;
	private final ImageInfo mInfo;
	private final byte[] mData;
	private int mDesiredWidth;
	private int mDesiredHeight;
	private int mAllocator = ALLOCATOR_DEFAULT;
	private boolean mUnpremultipliedRequired;
	private boolean mMutable;
	private boolean mDecodeAsAlphaMask;
	private int mMemoryPolicy = MEMORY_POLICY_DEFAULT;
	private Rect mCropRect;
	private PostProcessor mPostProcessor;
	private OnPartialImageListener mOnPartialImageListener;
	private ColorSpace mDesiredColorSpace;
	private boolean mClosed;

	private ImageDecoder(Source source, byte[] data, ImageInfo info) {
		mSource = source;
		mData = data;
		mInfo = info;
		mDesiredWidth = info.getSize().getWidth();
		mDesiredHeight = info.getSize().getHeight();
	}

	// ---- sources ----------------------------------------------------------------------------

	public static Source createSource(final ContentResolver cr, final Uri uri) {
		return new StreamSource(() -> cr.openInputStream(uri), null, Bitmap.DENSITY_NONE);
	}

	public static Source createSource(ContentResolver cr, Uri uri, Resources res) {
		final ContentResolver fcr = cr;
		final Uri furi = uri;
		return new StreamSource(() -> fcr.openInputStream(furi), res, Bitmap.DENSITY_NONE);
	}

	public static Source createSource(final AssetManager assets, final String fileName) {
		return new StreamSource(() -> assets.open(fileName), null, DisplayMetrics.DENSITY_DEFAULT);
	}

	/** A resource: decoded for its density and scaled to the display's, as AOSP does. */
	public static Source createSource(final Resources res, final int resId) {
		final TypedValue value = new TypedValue();
		int density;
		try {
			res.getValue(resId, value, true);
			density = value.density == TypedValue.DENSITY_DEFAULT ? DisplayMetrics.DENSITY_DEFAULT
			        : value.density == TypedValue.DENSITY_NONE ? Bitmap.DENSITY_NONE : value.density;
		} catch (RuntimeException e) {
			density = DisplayMetrics.DENSITY_DEFAULT;
		}
		return new StreamSource(() -> res.openRawResource(resId), res, density);
	}

	/** The bytes from the buffer's position to its limit (the buffer is not moved). */
	public static Source createSource(ByteBuffer buffer) {
		final ByteBuffer b = buffer.duplicate();
		final byte[] data = new byte[b.remaining()];
		b.get(data);
		return new StreamSource(() -> new ByteArrayInputStream(data), null, Bitmap.DENSITY_NONE);
	}

	public static Source createSource(byte[] data) {
		return createSource(data, 0, data.length);
	}

	public static Source createSource(final byte[] data, final int offset, final int length) {
		if (data == null)
			throw new NullPointerException("null byte[] in createSource!");
		if (offset < 0 || length < 0 || offset >= data.length || offset + length > data.length)
			throw new ArrayIndexOutOfBoundsException("invalid offset/length!");
		return new StreamSource(() -> new ByteArrayInputStream(data, offset, length), null, Bitmap.DENSITY_NONE);
	}

	public static Source createSource(final File file) {
		return new StreamSource(() -> new FileInputStream(file), null, Bitmap.DENSITY_NONE);
	}

	public static Source createSource(final Callable<AssetFileDescriptor> callable) {
		return new StreamSource(() -> callable.call().createInputStream(), null, Bitmap.DENSITY_NONE);
	}

	/** True for the formats the decoders read. */
	public static boolean isMimeTypeSupported(String mimeType) {
		if (mimeType == null)
			throw new NullPointerException("mimeType must not be null");
		switch (mimeType.toLowerCase(java.util.Locale.US)) {
			case "image/png":
			case "image/jpeg":
			case "image/webp":
			case "image/gif":
			case "image/bmp":
			case "image/x-bmp":
			case "image/x-ico":
			case "image/vnd.wap.wbmp":
				return true;
			default:
				return false;
		}
	}

	// ---- options ----------------------------------------------------------------------------

	public void setTargetSize(int width, int height) {
		if (width <= 0 || height <= 0)
			throw new IllegalArgumentException("Dimensions must be positive! provided (" + width + ", " + height + ")");
		mDesiredWidth = width;
		mDesiredHeight = height;
	}

	/** AOSP: the target size from a sample size (the decoder's power-of-two sampling rounding). */
	public void setTargetSampleSize(int sampleSize) {
		if (sampleSize <= 0)
			throw new IllegalArgumentException("sampleSize must be positive! provided " + sampleSize);
		Size size = mInfo.getSize();
		setTargetSize(Math.max(1, (size.getWidth() + sampleSize - 1) / sampleSize), Math.max(1, (size.getHeight() + sampleSize - 1) / sampleSize));
	}

	public void setAllocator(int allocator) {
		if (allocator < ALLOCATOR_DEFAULT || allocator > ALLOCATOR_HARDWARE)
			throw new IllegalArgumentException("invalid allocator " + allocator);
		mAllocator = allocator;
	}

	public int getAllocator() {
		return mAllocator;
	}

	public void setUnpremultipliedRequired(boolean unpremultipliedRequired) {
		mUnpremultipliedRequired = unpremultipliedRequired;
	}

	public boolean isUnpremultipliedRequired() {
		return mUnpremultipliedRequired;
	}

	public void setPostProcessor(PostProcessor postProcessor) {
		mPostProcessor = postProcessor;
	}

	public PostProcessor getPostProcessor() {
		return mPostProcessor;
	}

	public void setOnPartialImageListener(OnPartialImageListener listener) {
		mOnPartialImageListener = listener;
	}

	public OnPartialImageListener getOnPartialImageListener() {
		return mOnPartialImageListener;
	}

	public void setCrop(Rect subset) {
		mCropRect = subset;
	}

	public Rect getCrop() {
		return mCropRect;
	}

	public void setMutableRequired(boolean mutable) {
		mMutable = mutable;
	}

	public boolean isMutableRequired() {
		return mMutable;
	}

	public void setMemorySizePolicy(int policy) {
		mMemoryPolicy = policy;
	}

	public int getMemorySizePolicy() {
		return mMemoryPolicy;
	}

	public void setDecodeAsAlphaMaskEnabled(boolean enabled) {
		mDecodeAsAlphaMask = enabled;
	}

	public boolean isDecodeAsAlphaMaskEnabled() {
		return mDecodeAsAlphaMask;
	}

	/** AOSP: the colour space to decode into; the pixels are kept in sRGB and the bitmap reports the space. */
	public void setTargetColorSpace(ColorSpace colorSpace) {
		mDesiredColorSpace = colorSpace;
	}

	@Override
	public void close() {
		mClosed = true;
	}

	// ---- decoding ---------------------------------------------------------------------------

	private static boolean tlIsAnimated(byte[] d, String mime) {
		if ("image/gif".equals(mime)) {
			int frames = 0;
			for (int i = 13; i + 2 < d.length; i++) {
				// graphic control extensions, one per frame of an animation
				if ((d[i] & 0xff) == 0x21 && (d[i + 1] & 0xff) == 0xf9 && (d[i + 2] & 0xff) == 0x04 && ++frames > 1)
					return true;
			}
			return false;
		}
		if ("image/webp".equals(mime))
			return indexOf(d, "ANIM") >= 0;
		if ("image/png".equals(mime))
			return indexOf(d, "acTL") >= 0 && indexOf(d, "acTL") < Math.max(0, indexOf(d, "IDAT"));
		return false;
	}

	private static int indexOf(byte[] d, String s) {
		byte[] p = s.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
		outer:
		for (int i = 0; i + p.length <= d.length; i++) {
			for (int k = 0; k < p.length; k++)
				if (d[i + k] != p[k])
					continue outer;
			return i;
		}
		return -1;
	}

	/** EXIF orientation of a JPEG (1 when absent). */
	private static int tlExifOrientation(byte[] d) {
		try {
			int o = 2;
			while (o + 4 < d.length && (d[o] & 0xff) == 0xff) {
				int m = d[o + 1] & 0xff;
				int len = ((d[o + 2] & 0xff) << 8) | (d[o + 3] & 0xff);
				if (m == 0xe1 && d[o + 4] == 'E' && d[o + 5] == 'x' && d[o + 6] == 'i' && d[o + 7] == 'f') {
					int t = o + 10;
					java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(d);
					b.order(d[t] == 'I' ? java.nio.ByteOrder.LITTLE_ENDIAN : java.nio.ByteOrder.BIG_ENDIAN);
					int ifd = t + b.getInt(t + 4);
					int n = b.getShort(ifd) & 0xffff;
					for (int i = 0; i < n; i++) {
						int e = ifd + 2 + i * 12;
						if ((b.getShort(e) & 0xffff) == 0x0112)
							return b.getShort(e + 8) & 0xffff;
					}
					return 1;
				}
				if (m == 0xda)
					break;
				o += 2 + len;
			}
		} catch (RuntimeException e) {
			// malformed EXIF: no orientation
		}
		return 1;
	}

	private static ImageDecoder tlCreate(Source src) throws IOException {
		byte[] data;
		try {
			data = src.tlBytes();
		} catch (IOException e) {
			throw new DecodeException(DecodeException.SOURCE_EXCEPTION, "Failed to read the source", e, src);
		}
		String mime = BitmapFactory.tlSniffMime(data);
		int[] size = BitmapFactory.tlHeaderSize(data, mime);
		if (size == null) {
			Bitmap probe = BitmapFactory.decodeByteArray(data, 0, data.length);
			if (probe == null)
				throw new DecodeException(DecodeException.SOURCE_MALFORMED_DATA, "Failed to create image decoder with message 'unimplemented'Input contained an error.", null, src);
			size = new int[] { probe.getWidth(), probe.getHeight() };
			probe.recycle();
		}
		int orientation = "image/jpeg".equals(mime) ? tlExifOrientation(data) : 1;
		if (orientation >= 5 && orientation <= 8)
			size = new int[] { size[1], size[0] };
		// the size AOSP reports is already scaled from the source density to the display density
		int srcDensity = src.tlDensity();
		int dstDensity = src.tlResources() != null ? src.tlResources().getDisplayMetrics().densityDpi : Bitmap.DENSITY_NONE;
		if (srcDensity != Bitmap.DENSITY_NONE && dstDensity != Bitmap.DENSITY_NONE && srcDensity != dstDensity)
			size = new int[] { Bitmap.scaleFromDensity(size[0], srcDensity, dstDensity), Bitmap.scaleFromDensity(size[1], srcDensity, dstDensity) };
		ImageDecoder decoder = new ImageDecoder(src, data, new ImageInfo(new Size(size[0], size[1]), mime, tlIsAnimated(data, mime)));
		decoder.mOrientation = orientation;
		return decoder;
	}

	private int mOrientation = 1;

	private Bitmap tlDecodeBitmap() throws IOException {
		Bitmap bm = BitmapFactory.decodeByteArray(mData, 0, mData.length);
		if (bm == null) {
			DecodeException e = new DecodeException(DecodeException.SOURCE_MALFORMED_DATA, "Input contained an error.", null, mSource);
			if (mOnPartialImageListener == null || !mOnPartialImageListener.onPartialImage(e))
				throw e;
			bm = Bitmap.createBitmap(mDesiredWidth, mDesiredHeight, Bitmap.Config.ARGB_8888);
		}
		Matrix m = new Matrix();
		switch (mOrientation) {
			case 2: m.setScale(-1, 1); break;
			case 3: m.setRotate(180); break;
			case 4: m.setScale(1, -1); break;
			case 5: m.setRotate(90); m.postScale(-1, 1); break;
			case 6: m.setRotate(90); break;
			case 7: m.setRotate(270); m.postScale(-1, 1); break;
			case 8: m.setRotate(270); break;
		}
		if (!m.isIdentity())
			bm = Bitmap.createBitmap(bm, 0, 0, bm.getWidth(), bm.getHeight(), m, true);
		if (bm.getWidth() != mDesiredWidth || bm.getHeight() != mDesiredHeight)
			bm = Bitmap.createScaledBitmap(bm, mDesiredWidth, mDesiredHeight, true);
		if (mCropRect != null) {
			Rect c = new Rect(mCropRect);
			if (!c.intersect(0, 0, bm.getWidth(), bm.getHeight()))
				throw new IllegalStateException("Subset " + mCropRect + " not contained by scaled image bounds: (" + bm.getWidth() + " x " + bm.getHeight() + ")");
			bm = Bitmap.createBitmap(bm, c.left, c.top, c.width(), c.height());
		}
		if (mPostProcessor != null || mMutable) {
			Bitmap mutable = bm.copy(Bitmap.Config.ARGB_8888, true);
			if (mPostProcessor != null) {
				int opacity = mPostProcessor.onPostProcess(new Canvas(mutable));
				if (opacity == PixelFormat.OPAQUE)
					mutable.setHasAlpha(false);
				else if (opacity == PixelFormat.TRANSLUCENT || opacity == PixelFormat.TRANSPARENT)
					mutable.setHasAlpha(true);
			}
			mutable.mutable = mMutable;
			bm = mutable;
		} else {
			bm.mutable = false;
		}
		if (mUnpremultipliedRequired)
			bm.setPremultiplied(false);
		if (mDesiredColorSpace != null && mDesiredColorSpace.getModel() == ColorSpace.Model.RGB)
			bm.setColorSpace(mDesiredColorSpace);
		int dstDensity = mSource.tlResources() != null ? mSource.tlResources().getDisplayMetrics().densityDpi : mSource.tlDensity();
		if (dstDensity != Bitmap.DENSITY_NONE)
			bm.setDensity(dstDensity);
		return bm;
	}

	private static ImageDecoder tlPrepare(Source src, OnHeaderDecodedListener listener) throws IOException {
		if (src == null)
			throw new IllegalArgumentException("source must not be null");
		ImageDecoder decoder = tlCreate(src);
		if (listener != null)
			listener.onHeaderDecoded(decoder, decoder.mInfo, src);
		return decoder;
	}

	/** Create a Bitmap from a Source. */
	public static Bitmap decodeBitmap(Source src, OnHeaderDecodedListener listener) throws IOException {
		try (ImageDecoder decoder = tlPrepare(src, listener)) {
			return decoder.tlDecodeBitmap();
		}
	}

	public static Bitmap decodeBitmap(Source src) throws IOException {
		return decodeBitmap(src, null);
	}

	/** Create a Drawable from a Source (an AnimatedImageDrawable for animated images, see the class comment). */
	public static Drawable decodeDrawable(Source src, OnHeaderDecodedListener listener) throws IOException {
		try (ImageDecoder decoder = tlPrepare(src, listener)) {
			Resources res = src.tlResources();
			if (decoder.mInfo.isAnimated()) {
				Drawable animated = tlAnimatedDrawable(decoder, res);
				if (animated != null)
					return animated;
			}
			return new BitmapDrawable(res, decoder.tlDecodeBitmap());
		}
	}

	public static Drawable decodeDrawable(Source src) throws IOException {
		return decodeDrawable(src, null);
	}

	/** AnimatedImageDrawable.tlDecode(InputStream, srcDensity, dstDensity) when the drawables provide it. */
	private static Drawable tlAnimatedDrawable(ImageDecoder decoder, Resources res) {
		try {
			Class<?> c = Class.forName("android.graphics.drawable.AnimatedImageDrawable");
			java.lang.reflect.Method m = c.getMethod("tlDecode", InputStream.class, int.class, int.class);
			int dst = res != null ? res.getDisplayMetrics().densityDpi : Bitmap.DENSITY_NONE;
			return (Drawable) m.invoke(null, new ByteArrayInputStream(decoder.mData), decoder.mSource.tlDensity(), dst);
		} catch (ReflectiveOperationException e) {
			return null;
		}
	}
}
