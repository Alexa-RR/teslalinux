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

package android.graphics.fonts;

import android.text.TextUtils;
import java.util.ArrayList;

/** AOSP FontVariationAxis: one OpenType variation axis ('wght' 700) and the settings string format. */
public final class FontVariationAxis {
	private final int mTag;
	private final String mTagString;
	private final float mStyleValue;

	/**
	 * Construct FontVariationAxis.
	 *
	 * @param tagString 4 letter ascii axis tag ('wght', 'wdth', ...)
	 * @param styleValue the value of the axis
	 */
	public FontVariationAxis(String tagString, float styleValue) {
		if (!isValidTag(tagString)) {
			throw new IllegalArgumentException("Illegal tag pattern: " + tagString);
		}
		mTag = makeTag(tagString);
		mTagString = tagString;
		mStyleValue = styleValue;
	}

	/** Returns the OpenType style tag value. */
	public int getOpenTypeTagValue() {
		return mTag;
	}

	/** Returns the variable font axis tag associated to this axis. */
	public String getTag() {
		return mTagString;
	}

	/** Returns the style value associated to the given axis for this font. */
	public float getStyleValue() {
		return mStyleValue;
	}

	/** Returns a valid font variation setting string for this object ("'wght' 700.0"). */
	@Override
	public String toString() {
		return "'" + mTagString + "' " + Float.toString(mStyleValue);
	}

	private static boolean isValidTag(String tagString) {
		if (tagString == null || tagString.length() != 4)
			return false;
		for (int i = 0; i < 4; i++) {
			char c = tagString.charAt(i);
			if (c < 0x20 || c > 0x7e)
				return false;
		}
		return true;
	}

	/** The axes of a font variation settings string ("'wdth' 150, 'wght' 700"); null or empty: none. */
	public static FontVariationAxis[] fromFontVariationSettings(String settings) {
		if (settings == null || settings.isEmpty())
			return null;
		ArrayList<FontVariationAxis> axes = new ArrayList<>();
		int length = settings.length();
		for (int i = 0; i < length; i++) {
			char c = settings.charAt(i);
			if (Character.isWhitespace(c))
				continue;
			if (!(c == '\'' || c == '"') || length < i + 6 || settings.charAt(i + 5) != c)
				throw new IllegalArgumentException("Tag should be wrapped with double or single quote: " + settings);
			String tagString = settings.substring(i + 1, i + 5);
			i += 6; // the tag and its quotes
			int endOfValueString = settings.indexOf(',', i);
			if (endOfValueString == -1)
				endOfValueString = length;
			float value;
			try {
				value = Float.parseFloat(settings.substring(i, endOfValueString).trim());
			} catch (NumberFormatException e) {
				throw new IllegalArgumentException("Failed to parse float string: " + e.getMessage());
			}
			axes.add(new FontVariationAxis(tagString, value));
			i = endOfValueString;
		}
		return axes.toArray(new FontVariationAxis[0]);
	}

	/** The settings string of the axes, separated by commas; empty for null or no axes. */
	public static String toFontVariationSettings(FontVariationAxis[] axes) {
		if (axes == null)
			return "";
		return TextUtils.join(",", axes);
	}

	private static int makeTag(String tag) {
		return (tag.charAt(0) << 24) | (tag.charAt(1) << 16) | (tag.charAt(2) << 8) | tag.charAt(3);
	}
}
