/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.graphics.fonts;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.os.ParcelFileDescriptor;
import android.util.TypedValue;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.Objects;

/**
 * A font class can be used for creating FontFamily.
 *
 * teslalinux: the AOSP public API with the font data held in Java (the font file bytes, style,
 * ttc index and variation axes). AOSP hands these to minikin through a native pointer; ATL shapes
 * text with Pango, so the first time a typeface lays out text with this font, its file is added to
 * Pango's font map as an application font (native/atl_fonts.c), and the typeface selects it by the
 * face's own family, weight and slant (see tlPangoFamily and TlTextAttrs.Face).
 */
public final class Font {
	private static final String TAG = "Font";

	/** Builder for a Font. */
	public static final class Builder {
		private @Nullable ByteBuffer mBuffer;
		private @Nullable File mFile;
		private @Nullable String mAssetPath;
		private @Nullable AssetManager mAssetManager;
		private @NonNull String mLocaleList = "";
		private @IntRangeWeight int mWeight = FontStyle.FONT_WEIGHT_UNSPECIFIED;
		private int mItalic = RESOLVE_BY_FONT_TABLE;
		private int mTtcIndex = 0;
		private @Nullable FontVariationAxis[] mAxes = null;
		private @Nullable IOException mException;

		private static final int RESOLVE_BY_FONT_TABLE = -1;

		@interface IntRangeWeight {}

		public Builder(@NonNull ByteBuffer buffer) {
			Objects.requireNonNull(buffer, "buffer can not be null");
			if (!buffer.isDirect()) {
				throw new IllegalArgumentException("Only direct buffer can be used as the source of font data.");
			}
			mBuffer = buffer;
		}

		public Builder(@NonNull ByteBuffer buffer, @NonNull File path, @NonNull String localeList) {
			this(buffer);
			mFile = path;
			mLocaleList = localeList;
		}

		public Builder(@NonNull File path, @NonNull String localeList) {
			this(path);
			mLocaleList = localeList;
		}

		public Builder(@NonNull File path) {
			Objects.requireNonNull(path, "path can not be null");
			mFile = path;
			try (FileInputStream fis = new FileInputStream(path)) {
				mBuffer = createBuffer(fis.getChannel(), 0, -1);
			} catch (IOException e) {
				mException = e;
			}
		}

		public Builder(@NonNull ParcelFileDescriptor fd) {
			this(fd, 0, -1);
		}

		public Builder(@NonNull ParcelFileDescriptor fd, long offset, long size) {
			try (FileInputStream fis = new FileInputStream(fd.getFileDescriptor())) {
				mBuffer = createBuffer(fis.getChannel(), offset, size);
			} catch (IOException e) {
				mException = e;
			}
		}

		public Builder(@NonNull AssetManager am, @NonNull String path) {
			this(am, path, true /* is asset */, 0 /* cookie */);
		}

		public Builder(@NonNull AssetManager am, @NonNull String path, boolean isAsset, int cookie) {
			mAssetManager = am;
			mAssetPath = path;
			try {
				mBuffer = createBuffer(am, path, isAsset, cookie);
			} catch (IOException e) {
				mException = e;
			}
		}

		public Builder(@NonNull Resources res, int resId) {
			final TypedValue value = new TypedValue();
			res.getValue(resId, value, true);
			if (value.string == null) {
				mException = new IOException("Resource " + Integer.toHexString(resId) + " is not a font.");
				return;
			}
			final String str = value.string.toString();
			if (str.toLowerCase().endsWith(".xml")) {
				mException = new IOException("Resource " + Integer.toHexString(resId) + " must be a font file, not an XML font family.");
				return;
			}
			mAssetManager = res.getAssets();
			mAssetPath = str;
			try {
				mBuffer = createBuffer(res.getAssets(), str, false, value.assetCookie);
			} catch (IOException e) {
				mException = e;
			}
		}

		public Builder(@NonNull Font font) {
			mBuffer = font.getBuffer();
			mFile = font.getFile();
			mAssetPath = font.mAssetPath;
			mAssetManager = font.mAssetManager;
			mWeight = font.getStyle().getWeight();
			mItalic = font.getStyle().getSlant();
			mTtcIndex = font.getTtcIndex();
			mAxes = font.getAxes();
			mLocaleList = font.getLocaleList();
		}

		/** Creates a buffer containing font data using the assetManager and other parameters. */
		public static ByteBuffer createBuffer(@NonNull AssetManager am, @NonNull String path, boolean isAsset, int cookie) throws IOException {
			Objects.requireNonNull(am, "assetManager can not be null");
			Objects.requireNonNull(path, "path can not be null");
			try (InputStream is = isAsset ? am.open(path) : am.openNonAsset(path)) {
				return readAll(is);
			}
		}

		private static ByteBuffer createBuffer(FileChannel channel, long offset, long size) throws IOException {
			final long len = size == -1 ? channel.size() - offset : size;
			if (len < 0 || len > Integer.MAX_VALUE) {
				throw new IOException("font file too large: " + len);
			}
			ByteBuffer buffer = ByteBuffer.allocateDirect((int) len);
			channel.position(offset);
			while (buffer.hasRemaining()) {
				if (channel.read(buffer) < 0)
					break;
			}
			buffer.flip();
			return buffer;
		}

		private static ByteBuffer readAll(InputStream is) throws IOException {
			byte[] data = new byte[16384];
			int total = 0;
			int n;
			while ((n = is.read(data, total, data.length - total)) > 0) {
				total += n;
				if (total == data.length)
					data = Arrays.copyOf(data, data.length * 2);
			}
			ByteBuffer buffer = ByteBuffer.allocateDirect(total);
			buffer.put(data, 0, total);
			buffer.flip();
			return buffer;
		}

		public @NonNull Builder setWeight(int weight) {
			if (weight < FontStyle.FONT_WEIGHT_MIN || weight > FontStyle.FONT_WEIGHT_MAX) {
				throw new IllegalArgumentException("weight must be in [" + FontStyle.FONT_WEIGHT_MIN + ", " + FontStyle.FONT_WEIGHT_MAX + "]");
			}
			mWeight = weight;
			return this;
		}

		public @NonNull Builder setSlant(int slant) {
			mItalic = slant == FontStyle.FONT_SLANT_UPRIGHT ? 0 : 1;
			return this;
		}

		public @NonNull Builder setTtcIndex(int ttcIndex) {
			mTtcIndex = ttcIndex;
			return this;
		}

		public @NonNull Builder setFontVariationSettings(@Nullable String variationSettings) {
			mAxes = FontVariationAxis.fromFontVariationSettings(variationSettings);
			return this;
		}

		public @NonNull Builder setFontVariationSettings(@Nullable FontVariationAxis[] axes) {
			mAxes = axes == null ? null : axes.clone();
			return this;
		}

		public @NonNull Font build() throws IOException {
			if (mException != null) {
				throw new IOException("Failed to read font contents", mException);
			}
			/* AOSP resolves unspecified weight/slant from the OS/2 table of the font file;
			 * without a font parser the sensible defaults are regular upright. */
			if (mWeight == FontStyle.FONT_WEIGHT_UNSPECIFIED) {
				mWeight = FontStyle.FONT_WEIGHT_NORMAL;
			}
			if (mItalic == RESOLVE_BY_FONT_TABLE) {
				mItalic = 0;
			}
			final int slant = mItalic == 1 ? FontStyle.FONT_SLANT_ITALIC : FontStyle.FONT_SLANT_UPRIGHT;
			return new Font(mBuffer, mFile, mAssetManager, mAssetPath, new FontStyle(mWeight, slant), mTtcIndex, mAxes, mLocaleList);
		}
	}

	private final @NonNull ByteBuffer mBuffer;
	private final @Nullable File mFile;
	private final @Nullable AssetManager mAssetManager;
	private final @Nullable String mAssetPath;
	private final @NonNull FontStyle mFontStyle;
	private final int mTtcIndex;
	private final @Nullable FontVariationAxis[] mAxes;
	private final @NonNull String mLocaleList;

	private Font(@NonNull ByteBuffer buffer, @Nullable File file, @Nullable AssetManager am, @Nullable String assetPath,
	             @NonNull FontStyle fontStyle, int ttcIndex, @Nullable FontVariationAxis[] axes, @NonNull String localeList) {
		mBuffer = buffer;
		mFile = file;
		mAssetManager = am;
		mAssetPath = assetPath;
		mFontStyle = fontStyle;
		mTtcIndex = ttcIndex;
		mAxes = axes;
		mLocaleList = localeList;
	}

	/** Returns a font file buffer (read-only view). */
	public @NonNull ByteBuffer getBuffer() {
		return mBuffer.asReadOnlyBuffer();
	}

	/** Returns a file path of this font, or null if the font was not created from a file. */
	public @Nullable File getFile() {
		return mFile;
	}

	/** @hide the asset / resource path this font was built from, if any */
	public @Nullable String getAssetPath() {
		return mAssetPath;
	}

	public @NonNull FontStyle getStyle() {
		return mFontStyle;
	}

	public int getTtcIndex() {
		return mTtcIndex;
	}

	public @Nullable FontVariationAxis[] getAxes() {
		return mAxes == null ? null : mAxes.clone();
	}

	/** @hide (AOSP returns a LocaleList; the BCP-47 string form is kept here) */
	public @NonNull String getLocaleList() {
		return mLocaleList;
	}

	/** AOSP: identifier for the source this font was loaded from; files hash their path. */
	public int getSourceIdentifier() {
		return mFile != null ? mFile.getAbsolutePath().hashCode() : mAssetPath != null ? mAssetPath.hashCode() : System.identityHashCode(mBuffer);
	}

	/* teslalinux: the face Pango knows this font by, once registered (native/atl_fonts.c) */
	private boolean mTlRegistered;
	private @Nullable String mTlFamily;
	private int mTlWeight = FontStyle.FONT_WEIGHT_NORMAL;
	private boolean mTlItalic;
	/** set when a font was registered off the main thread and waits to be added to the UI font map */
	private static volatile boolean sTlPending;

	/**
	 * @hide The family name of this font's face in Pango's font map, registering the font file on
	 * first use; null when the data is not a font fontconfig can read (the text then falls back
	 * to the system fallback family, as minikin falls back when a font fails to load).
	 */
	public synchronized @Nullable String tlPangoFamily() {
		if (!mTlRegistered) {
			mTlRegistered = true;
			final int[] out = new int[3];
			mTlFamily = native_register(mBuffer, mFile != null ? mFile.getAbsolutePath() : null, mTtcIndex, out);
			if (mTlFamily != null) {
				mTlWeight = out[0];
				mTlItalic = out[1] != 0;
				if (out[2] != 0)
					sTlPending = true;
			}
		}
		return mTlFamily;
	}

	/** @hide the weight (1..1000) of the face in the font file, as Pango matches it */
	public synchronized int tlPangoWeight() {
		tlPangoFamily();
		return mTlWeight;
	}

	/** @hide whether the face in the font file is italic or oblique */
	public synchronized boolean tlPangoItalic() {
		tlPangoFamily();
		return mTlItalic;
	}

	/** @hide on the main thread: adds the fonts that other threads registered to the UI font map */
	public static void tlSyncPending() {
		if (sTlPending && android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
			sTlPending = false;
			native_sync_pending();
		}
	}

	private static native String native_register(ByteBuffer buffer, String path, int ttcIndex, int[] out);
	private static native void native_sync_pending();

	/** @hide */
	public boolean paramEquals(@NonNull Font f) {
		return f.getStyle().equals(getStyle()) && f.mTtcIndex == mTtcIndex && Arrays.equals(f.mAxes, mAxes)
				&& Objects.equals(f.mLocaleList, mLocaleList) && Objects.equals(f.mFile, mFile) && Objects.equals(f.mAssetPath, mAssetPath);
	}

	@Override
	public boolean equals(@Nullable Object o) {
		if (o == this) return true;
		if (o == null || !(o instanceof Font)) return false;
		Font f = (Font) o;
		return paramEquals(f) && f.mBuffer.equals(mBuffer);
	}

	@Override
	public int hashCode() {
		return Objects.hash(mFontStyle, mTtcIndex, Arrays.hashCode(mAxes), mLocaleList, mFile, mAssetPath);
	}

	@Override
	public String toString() {
		return "Font {path=" + (mFile != null ? mFile : mAssetPath) + ", style=" + mFontStyle + ", ttcIndex=" + mTtcIndex
				+ ", axes=" + java.util.Arrays.toString(mAxes) + ", localeList=" + mLocaleList + "}";
	}
}
