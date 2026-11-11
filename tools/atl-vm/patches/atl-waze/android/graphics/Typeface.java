/*
 * Copyright (C) 2006 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.res.AssetManager;
import android.content.res.FontResourcesParser.FamilyResourceEntry;
import android.content.res.FontResourcesParser.FontFamilyFilesResourceEntry;
import android.content.res.FontResourcesParser.FontFileResourceEntry;
import android.content.res.FontResourcesParser.ProviderResourceEntry;
import android.graphics.fonts.Font;
import android.graphics.fonts.FontFamily;
import android.graphics.fonts.FontStyle;
import android.graphics.fonts.FontVariationAxis;
import android.util.Log;
import android.util.LruCache;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The Typeface class specifies the typeface and intrinsic style of a font.
 *
 * teslalinux: AOSP API and semantics for everything that lives in Java (styles, weights, builders,
 * resource/asset/file loading through {@link Font}, caches). Rendering: ATL draws text with Pango.
 * A system typeface is a fontconfig family (AOSP's fonts.xml aliases mapped in TlTextAttrs.Face);
 * a typeface of font files names, per family, the Pango face of the font minikin would pick, whose
 * file is registered with Pango's font map on first use (Font.tlPangoFamily, native/atl_fonts.c).
 */
public class Typeface {
	private static final String TAG = "Typeface";

	/** The default NORMAL typeface object */
	public static final Typeface DEFAULT;
	/** The default BOLD typeface object. */
	public static final Typeface DEFAULT_BOLD;
	/** The NORMAL style of the default sans serif typeface. */
	public static final Typeface SANS_SERIF;
	/** The NORMAL style of the default serif typeface. */
	public static final Typeface SERIF;
	/** The NORMAL style of the default monospace typeface. */
	public static final Typeface MONOSPACE;

	// Style
	public static final int NORMAL = 0;
	public static final int BOLD = 1;
	public static final int ITALIC = 2;
	public static final int BOLD_ITALIC = 3;
	/** @hide */
	public static final int STYLE_MASK = 0x03;

	/** @hide */
	public static final int RESOLVE_BY_FONT_TABLE = -1;
	/** @hide */
	public static final String DEFAULT_FAMILY = "sans-serif";

	public long native_instance = 0; // directly accessed by androidx

	private static final LruCache<String, Typeface> sDynamicTypefaceCache = new LruCache<>(16);
	private static final Object sStyledCacheLock = new Object();
	private static final LruCache<String, Typeface> sStyledTypefaceCache = new LruCache<>(16);

	private final @Nullable String mFamilyName;
	private final @NonNull List<FontFamily> mFamilies;
	private final int mStyle;
	private final int mWeight;
	private final boolean mItalic;

	static {
		DEFAULT = new Typeface(null, new ArrayList<FontFamily>(), NORMAL, FontStyle.FONT_WEIGHT_NORMAL, false);
		DEFAULT_BOLD = new Typeface(null, new ArrayList<FontFamily>(), BOLD, FontStyle.FONT_WEIGHT_BOLD, false);
		SANS_SERIF = new Typeface("sans-serif", new ArrayList<FontFamily>(), NORMAL, FontStyle.FONT_WEIGHT_NORMAL, false);
		SERIF = new Typeface("serif", new ArrayList<FontFamily>(), NORMAL, FontStyle.FONT_WEIGHT_NORMAL, false);
		MONOSPACE = new Typeface("monospace", new ArrayList<FontFamily>(), NORMAL, FontStyle.FONT_WEIGHT_NORMAL, false);
	}

	private Typeface(@Nullable String familyName, @NonNull List<FontFamily> families, int style, int weight, boolean italic) {
		mFamilyName = familyName;
		mFamilies = families;
		mStyle = style;
		mWeight = weight;
		mItalic = italic;
	}

	/** Returns the typeface's intrinsic style attributes */
	public int getStyle() {
		return mStyle;
	}

	public final boolean isBold() {
		return (mStyle & BOLD) != 0;
	}

	public final boolean isItalic() {
		return (mStyle & ITALIC) != 0;
	}

	/** Returns the weight (1..1000) of this typeface. */
	public int getWeight() {
		return mWeight;
	}

	/** @hide system font family name this typeface resolves to, or null for the default family */
	public @Nullable String getSystemFontFamilyName() {
		return mFamilyName;
	}

	/** @hide the custom font families this typeface carries (empty for system typefaces) */
	public @NonNull List<FontFamily> getFontFamilies() {
		return mFamilies;
	}

	/** @hide the custom font file best matching this typeface's style, or null for system typefaces */
	public @Nullable Font getPrimaryFont() {
		if (mFamilies.isEmpty())
			return null;
		return mFamilies.get(0).getClosestFont(new FontStyle(mWeight, mItalic ? FontStyle.FONT_SLANT_ITALIC : FontStyle.FONT_SLANT_UPRIGHT));
	}

	private static int styleOf(int weight, boolean italic) {
		return (weight >= 600 ? BOLD : NORMAL) | (italic ? ITALIC : NORMAL);
	}

	// ---- creation -------------------------------------------------------------------------------

	/** Create a typeface object given a family name, and option style information. */
	public static Typeface create(String familyName, int style) {
		return create(getSystemDefaultTypeface(familyName), style);
	}

	private static Typeface getSystemDefaultTypeface(@Nullable String familyName) {
		if (familyName == null || familyName.isEmpty())
			return DEFAULT;
		switch (familyName) {
			case "sans-serif": return SANS_SERIF;
			case "serif": return SERIF;
			case "monospace": return MONOSPACE;
			default:
				/* AOSP looks the family up in the system font map; names it does not know fall back to
				 * the default. Pango does its own matching by name, so the name is kept. */
				return new Typeface(familyName, new ArrayList<FontFamily>(), NORMAL, FontStyle.FONT_WEIGHT_NORMAL, false);
		}
	}

	/** Create a typeface object that best matches the specified existing typeface and the specified Style. */
	public static Typeface create(Typeface family, int style) {
		if ((style & ~STYLE_MASK) != 0) {
			style = NORMAL;
		}
		if (family == null) {
			family = DEFAULT;
		}
		if (family.mStyle == style) {
			return family;
		}
		final String key = cacheKey(family, style);
		synchronized (sStyledCacheLock) {
			Typeface typeface = sStyledTypefaceCache.get(key);
			if (typeface == null) {
				int weight = (style & BOLD) != 0 ? FontStyle.FONT_WEIGHT_BOLD : FontStyle.FONT_WEIGHT_NORMAL;
				typeface = new Typeface(family.mFamilyName, family.mFamilies, style, weight, (style & ITALIC) != 0);
				sStyledTypefaceCache.put(key, typeface);
			}
			return typeface;
		}
	}

	/** Creates a typeface object that best matches the specified existing typeface and the specified weight and italic style. */
	public static @NonNull Typeface create(@Nullable Typeface family, int weight, boolean italic) {
		if (weight < 1 || weight > 1000) {
			throw new IllegalArgumentException("weight must be in [1, 1000] but was " + weight);
		}
		if (family == null) {
			family = DEFAULT;
		}
		if (family.mWeight == weight && family.mItalic == italic) {
			return family;
		}
		final String key = cacheKey(family, weight * 2 + (italic ? 1 : 0) + 100);
		synchronized (sStyledCacheLock) {
			Typeface typeface = sStyledTypefaceCache.get(key);
			if (typeface == null) {
				typeface = new Typeface(family.mFamilyName, family.mFamilies, styleOf(weight, italic), weight, italic);
				sStyledTypefaceCache.put(key, typeface);
			}
			return typeface;
		}
	}

	private static String cacheKey(Typeface family, int style) {
		return System.identityHashCode(family) + ":" + style;
	}

	/** Returns one of the default typeface objects, based on the specified style */
	public static Typeface defaultFromStyle(int style) {
		return create(DEFAULT, style);
	}

	/** Create a new typeface from the specified font data. */
	public static Typeface createFromAsset(AssetManager mgr, String path) {
		Objects.requireNonNull(path);
		Objects.requireNonNull(mgr);
		Typeface typeface = new Builder(mgr, path).build();
		if (typeface != null)
			return typeface;
		// AOSP throws RuntimeException("Font asset not found " + path) when the asset is missing
		throw new RuntimeException("Font asset not found " + path);
	}

	/** Create a new typeface from the specified font file. */
	public static Typeface createFromFile(@Nullable File file) {
		Typeface typeface = new Builder(file).build();
		if (typeface != null)
			return typeface;
		throw new RuntimeException(file + " could not be made into a typeface");
	}

	/** Create a new typeface from the specified font file. */
	public static Typeface createFromFile(@Nullable String path) {
		Objects.requireNonNull(path);
		return createFromFile(new File(path));
	}

	/** @hide used by ATL's own code paths (android.graphics.FontFamily is the pre-API 29 hidden class) */
	public static Typeface createFromFamiliesWithDefault(android.graphics.FontFamily[] families) {
		return DEFAULT;
	}

	/** @hide */
	public static Typeface createFromFamiliesWithDefault(android.graphics.FontFamily[] families, int weight, int italic) {
		return createFromFamiliesWithDefault(families);
	}

	/** @hide typeface cache keyed by resource/asset path, as in AOSP ResourcesImpl.loadFont */
	public static Typeface findFromCache(AssetManager mgr, String path) {
		synchronized (sDynamicTypefaceCache) {
			return sDynamicTypefaceCache.get(Builder.createAssetUid(mgr, path, 0, null, RESOLVE_BY_FONT_TABLE, RESOLVE_BY_FONT_TABLE, DEFAULT_FAMILY));
		}
	}

	/** @hide builds a typeface from a parsed font XML family (AOSP Typeface.createFromResources) */
	public static Typeface createFromResources(FamilyResourceEntry entry, AssetManager mgr, String path) {
		if (entry instanceof ProviderResourceEntry) {
			final ProviderResourceEntry providerEntry = (ProviderResourceEntry) entry;
			String systemFontFamilyName = providerEntry.getSystemFontFamilyName();
			if (systemFontFamilyName != null) {
				return Typeface.create(systemFontFamilyName, NORMAL);
			}
			/* Downloadable fonts come from the font provider app (Google Play services), which is
			 * not installed here; AOSP returns DEFAULT when the request cannot be served. */
			Log.w(TAG, "Downloadable font family " + providerEntry.getQuery() + " from " + providerEntry.getAuthority() + ": no font provider available, using the default typeface");
			return DEFAULT;
		}

		Typeface typeface = findFromCache(mgr, path);
		if (typeface != null) return typeface;

		final FontFamilyFilesResourceEntry filesEntry = (FontFamilyFilesResourceEntry) entry;
		try {
			FontFamily.Builder familyBuilder = null;
			for (final FontFileResourceEntry fontFile : filesEntry.getEntries()) {
				final Font.Builder fontBuilder = new Font.Builder(mgr, fontFile.getFileName(), false /* isAsset */, 0)
						.setTtcIndex(fontFile.getTtcIndex())
						.setFontVariationSettings(fontFile.getVariationSettings());
				if (fontFile.getWeight() != RESOLVE_BY_FONT_TABLE) {
					fontBuilder.setWeight(fontFile.getWeight());
				}
				if (fontFile.getItalic() != RESOLVE_BY_FONT_TABLE) {
					fontBuilder.setSlant(fontFile.getItalic() == FontFileResourceEntry.ITALIC ? FontStyle.FONT_SLANT_ITALIC : FontStyle.FONT_SLANT_UPRIGHT);
				}
				if (familyBuilder == null) {
					familyBuilder = new FontFamily.Builder(fontBuilder.build());
				} else {
					familyBuilder.addFont(fontBuilder.build());
				}
			}
			if (familyBuilder == null) {
				return DEFAULT;
			}
			final FontFamily family = familyBuilder.build();
			final Font bestFont = family.getClosestFont(new FontStyle(FontStyle.FONT_WEIGHT_NORMAL, FontStyle.FONT_SLANT_UPRIGHT));
			final FontStyle normal = bestFont.getStyle();
			typeface = new CustomFallbackBuilder(family).setStyle(normal).build();

			final String key = Builder.createAssetUid(mgr, path, 0, null, RESOLVE_BY_FONT_TABLE, RESOLVE_BY_FONT_TABLE, DEFAULT_FAMILY);
			synchronized (sDynamicTypefaceCache) {
				sDynamicTypefaceCache.put(key, typeface);
			}
			return typeface;
		} catch (IllegalArgumentException e) {
			// invalid ttc index or variation settings
			return null;
		} catch (IOException e) {
			return null;
		}
	}

	// ---- Builder --------------------------------------------------------------------------------

	/** A builder class for creating new Typeface instance. */
	public static final class Builder {
		public static final int NORMAL_WEIGHT = 400;
		public static final int BOLD_WEIGHT = 700;

		private final AssetManager mAssetManager;
		private final String mPath;
		private final @Nullable Font.Builder mFontBuilder;
		private String mFallbackFamilyName;
		private int mWeight = RESOLVE_BY_FONT_TABLE;
		private int mItalic = RESOLVE_BY_FONT_TABLE;
		private int mTtcIndex;
		private FontVariationAxis[] mAxes;

		public Builder(@NonNull File path) {
			mFontBuilder = new Font.Builder(path);
			mAssetManager = null;
			mPath = null;
		}

		public Builder(@NonNull FileDescriptor fd) {
			Font.Builder builder;
			try {
				builder = new Font.Builder(android.os.ParcelFileDescriptor.dup(fd));
			} catch (IOException e) {
				// AOSP: falls back to the system font silently when the fd cannot be duplicated
				builder = null;
			}
			mFontBuilder = builder;
			mAssetManager = null;
			mPath = null;
		}

		public Builder(@NonNull String path) {
			this(new File(path));
		}

		public Builder(@NonNull AssetManager assetManager, @NonNull String path) {
			this(assetManager, path, true /* is asset */, 0 /* cookie */);
		}

		public Builder(@NonNull AssetManager assetManager, @NonNull String path, boolean isAsset, int cookie) {
			mFontBuilder = new Font.Builder(assetManager, path, isAsset, cookie);
			mAssetManager = assetManager;
			mPath = path;
		}

		public Builder setWeight(int weight) {
			mWeight = weight;
			mFontBuilder.setWeight(weight);
			return this;
		}

		public Builder setItalic(boolean italic) {
			mItalic = italic ? FontStyle.FONT_SLANT_ITALIC : FontStyle.FONT_SLANT_UPRIGHT;
			mFontBuilder.setSlant(mItalic);
			return this;
		}

		public Builder setTtcIndex(int ttcIndex) {
			mTtcIndex = ttcIndex;
			mFontBuilder.setTtcIndex(ttcIndex);
			return this;
		}

		public Builder setFontVariationSettings(@Nullable String variationSettings) {
			mAxes = FontVariationAxis.fromFontVariationSettings(variationSettings);
			mFontBuilder.setFontVariationSettings(variationSettings);
			return this;
		}

		public Builder setFontVariationSettings(@Nullable FontVariationAxis[] axes) {
			mAxes = axes == null ? null : axes.clone();
			mFontBuilder.setFontVariationSettings(axes);
			return this;
		}

		/** Sets a fallback family name used when the font file cannot be loaded. */
		public Builder setFallback(@Nullable String familyName) {
			mFallbackFamilyName = familyName;
			return this;
		}

		/** @hide cache key for typefaces built from assets/resources (AOSP createAssetUid) */
		public static String createAssetUid(final AssetManager mgr, String path, int ttcIndex, @Nullable FontVariationAxis[] axes, int weight, int italic, String fallback) {
			final StringBuilder builder = new StringBuilder();
			builder.append("asset:").append(System.identityHashCode(mgr)).append(':').append(path)
				.append("-").append(ttcIndex).append("-").append(weight).append("-").append(italic)
				.append("--").append(fallback).append("--");
			if (axes != null) {
				for (FontVariationAxis axis : axes) {
					builder.append(axis.getTag()).append("-").append(axis.getStyleValue());
				}
			}
			return builder.toString();
		}

		private Typeface resolveFallbackTypeface() {
			if (mFallbackFamilyName == null) {
				return null;
			}
			final Typeface base = getSystemDefaultTypeface(mFallbackFamilyName);
			if (mWeight == RESOLVE_BY_FONT_TABLE && mItalic == RESOLVE_BY_FONT_TABLE) {
				return base;
			}
			final int weight = (mWeight == RESOLVE_BY_FONT_TABLE) ? base.mWeight : mWeight;
			final boolean italic = (mItalic == RESOLVE_BY_FONT_TABLE) ? base.mItalic : mItalic == 1;
			return create(base, weight, italic);
		}

		/**
		 * Generates new Typeface from specified configuration; null if the font could not be
		 * read and no fallback family was set (AOSP semantics).
		 */
		public Typeface build() {
			if (mFontBuilder == null) {
				return resolveFallbackTypeface();
			}
			try {
				final Font font = mFontBuilder.build();
				final String key = mAssetManager == null ? null
						: createAssetUid(mAssetManager, mPath, font.getTtcIndex(), font.getAxes(), mWeight, mItalic, mFallbackFamilyName == null ? DEFAULT_FAMILY : mFallbackFamilyName);
				if (key != null) {
					synchronized (sDynamicTypefaceCache) {
						final Typeface typeface = sDynamicTypefaceCache.get(key);
						if (typeface != null) {
							return typeface;
						}
					}
				}
				final FontFamily family = new FontFamily.Builder(font).build();
				final int weight = mWeight == RESOLVE_BY_FONT_TABLE ? font.getStyle().getWeight() : mWeight;
				final int slant = mItalic == RESOLVE_BY_FONT_TABLE ? font.getStyle().getSlant() : mItalic;
				final CustomFallbackBuilder builder = new CustomFallbackBuilder(family).setStyle(new FontStyle(weight, slant));
				if (mFallbackFamilyName != null) {
					builder.setSystemFallback(mFallbackFamilyName);
				}
				final Typeface typeface = builder.build();
				if (key != null) {
					synchronized (sDynamicTypefaceCache) {
						sDynamicTypefaceCache.put(key, typeface);
					}
				}
				return typeface;
			} catch (IOException | IllegalArgumentException e) {
				return resolveFallbackTypeface();
			}
		}
	}

	// ---- CustomFallbackBuilder ------------------------------------------------------------------

	/** A builder class for creating new Typeface instance from custom font families. */
	public static final class CustomFallbackBuilder {
		private static final int MAX_CUSTOM_FALLBACK = 64;
		private final ArrayList<FontFamily> mFamilies = new ArrayList<>();
		private String mFallbackName = null;
		private @Nullable FontStyle mStyle;

		public static int getMaxCustomFallbackCount() {
			return MAX_CUSTOM_FALLBACK;
		}

		public CustomFallbackBuilder(@NonNull FontFamily family) {
			Objects.requireNonNull(family);
			mFamilies.add(family);
		}

		public @NonNull CustomFallbackBuilder setSystemFallback(@NonNull String familyName) {
			Objects.requireNonNull(familyName);
			mFallbackName = familyName;
			return this;
		}

		public @NonNull CustomFallbackBuilder setStyle(@NonNull FontStyle style) {
			mStyle = style;
			return this;
		}

		public @NonNull CustomFallbackBuilder addCustomFallback(@NonNull FontFamily family) {
			Objects.requireNonNull(family);
			if (mFamilies.size() >= getMaxCustomFallbackCount()) {
				throw new IllegalArgumentException("Custom fallback limit exceeded(" + getMaxCustomFallbackCount() + ")");
			}
			mFamilies.add(family);
			return this;
		}

		public @NonNull Typeface build() {
			final FontStyle style = mStyle != null ? mStyle : mFamilies.get(0).getFont(0).getStyle();
			final boolean italic = style.getSlant() == FontStyle.FONT_SLANT_ITALIC;
			return new Typeface(mFallbackName, new ArrayList<>(mFamilies), styleOf(style.getWeight(), italic), style.getWeight(), italic);
		}
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		Typeface typeface = (Typeface) o;
		return mStyle == typeface.mStyle && mWeight == typeface.mWeight && mItalic == typeface.mItalic
				&& Objects.equals(mFamilyName, typeface.mFamilyName) && mFamilies.equals(typeface.mFamilies);
	}

	@Override
	public int hashCode() {
		return Objects.hash(mFamilyName, mFamilies, mStyle, mWeight, mItalic);
	}

	@Override
	public String toString() {
		return "Typeface{family=" + mFamilyName + ", style=" + mStyle + ", weight=" + mWeight + ", italic=" + mItalic + ", customFamilies=" + mFamilies.size() + "}";
	}
}
