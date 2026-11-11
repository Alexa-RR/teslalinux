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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A font family class can be used for creating Typeface.
 *
 * teslalinux: AOSP public API; the fonts are kept as Java objects (see {@link Font}).
 */
public final class FontFamily {
	private static final String TAG = "FontFamily";

	/** A builder class for creating new FontFamily. */
	public static final class Builder {
		private final ArrayList<Font> mFonts = new ArrayList<>();
		private final ArrayList<Long> mStyleHashes = new ArrayList<>();

		public Builder(@NonNull Font font) {
			Objects.requireNonNull(font, "font can not be null");
			mFonts.add(font);
			mStyleHashes.add(styleHash(font));
		}

		/** Adds a font to this family; AOSP rejects two fonts with the same weight and slant. */
		public @NonNull Builder addFont(@NonNull Font font) {
			Objects.requireNonNull(font, "font can not be null");
			long hash = styleHash(font);
			if (mStyleHashes.contains(hash)) {
				throw new IllegalArgumentException("Font with same style is already added: " + font);
			}
			mFonts.add(font);
			mStyleHashes.add(hash);
			return this;
		}

		public @NonNull FontFamily build() {
			return build("", 0 /* FontConfig.FontFamily.VARIANT_DEFAULT */, true /* isCustomFallback */, false /* isDefaultFallback */);
		}

		/** @hide */
		public @NonNull FontFamily build(@NonNull String langTags, int variant, boolean isCustomFallback, boolean isDefaultFallback) {
			return new FontFamily(new ArrayList<>(mFonts), langTags, variant);
		}

		private static long styleHash(Font font) {
			return (long) font.getStyle().getWeight() << 32 | font.getStyle().getSlant();
		}
	}

	private final List<Font> mFonts;
	private final String mLangTags;
	private final int mVariant;

	private FontFamily(List<Font> fonts, String langTags, int variant) {
		mFonts = fonts;
		mLangTags = langTags;
		mVariant = variant;
	}

	/** @hide BCP-47 language tags this family is for, or null */
	public @Nullable String getLangTags() {
		return mLangTags == null || mLangTags.isEmpty() ? null : mLangTags;
	}

	/** @hide */
	public int getVariant() {
		return mVariant;
	}

	public @NonNull Font getFont(int index) {
		return mFonts.get(index);
	}

	public int getSize() {
		return mFonts.size();
	}

	/** @hide the font whose weight/slant is closest to the requested style, AOSP's matching rule */
	public @NonNull Font getClosestFont(@NonNull FontStyle style) {
		Font best = mFonts.get(0);
		int bestScore = Integer.MAX_VALUE;
		for (Font f : mFonts) {
			int score = Math.abs(f.getStyle().getWeight() - style.getWeight()) / 100 + (f.getStyle().getSlant() == style.getSlant() ? 0 : 2);
			if (score < bestScore) {
				bestScore = score;
				best = f;
			}
		}
		return best;
	}
}
