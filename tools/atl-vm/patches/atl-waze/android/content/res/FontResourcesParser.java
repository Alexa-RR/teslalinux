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
package android.content.res;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Parser for xml type font resources.
 *
 * teslalinux: AOSP, with the com.android.internal.R.styleable.FontFamily / FontFamilyFont index
 * tables replaced by the android attribute ids themselves (ATL has no internal styleable arrays).
 */
public class FontResourcesParser {
	private static final String TAG = "FontResourcesParser";

	/* android.R.attr ids (API 26-28), values from android.jar */
	private static final int ATTR_FONT_PROVIDER_AUTHORITY = 0x01010550;
	private static final int ATTR_FONT_PROVIDER_PACKAGE = 0x01010557;
	private static final int ATTR_FONT_PROVIDER_QUERY = 0x01010551;
	private static final int ATTR_FONT_PROVIDER_CERTS = 0x0101055d;
	private static final int ATTR_FONT_PROVIDER_SYSTEM_FONT_FAMILY = 0x01010622;
	private static final int ATTR_FONT = 0x01010532;
	private static final int ATTR_FONT_WEIGHT = 0x01010533;
	private static final int ATTR_FONT_STYLE = 0x0101053f;
	private static final int ATTR_FONT_VARIATION_SETTINGS = 0x01010570;
	private static final int ATTR_TTC_INDEX = 0x0101056f;

	private static final int[] FONT_FAMILY = { ATTR_FONT_PROVIDER_AUTHORITY, ATTR_FONT_PROVIDER_PACKAGE, ATTR_FONT_PROVIDER_QUERY, ATTR_FONT_PROVIDER_CERTS, ATTR_FONT_PROVIDER_SYSTEM_FONT_FAMILY };
	private static final int FONT_FAMILY_AUTHORITY = 0, FONT_FAMILY_PACKAGE = 1, FONT_FAMILY_QUERY = 2, FONT_FAMILY_CERTS = 3, FONT_FAMILY_SYSTEM_FONT_FAMILY = 4;
	private static final int[] FONT_FAMILY_FONT = { ATTR_FONT, ATTR_FONT_WEIGHT, ATTR_FONT_STYLE, ATTR_FONT_VARIATION_SETTINGS, ATTR_TTC_INDEX };
	private static final int FONT_FAMILY_FONT_FONT = 0, FONT_FAMILY_FONT_WEIGHT = 1, FONT_FAMILY_FONT_STYLE = 2, FONT_FAMILY_FONT_VARIATION_SETTINGS = 3, FONT_FAMILY_FONT_TTC_INDEX = 4;

	// A class represents single entry of font-family in xml file.
	public interface FamilyResourceEntry {}

	// A class represents font provider based font-family element in xml file.
	public static final class ProviderResourceEntry implements FamilyResourceEntry {
		private final @NonNull String mProviderAuthority;
		private final @NonNull String mProviderPackage;
		private final @NonNull String mQuery;
		private final @Nullable String mSystemFontFamilyName;
		private final @Nullable List<List<String>> mCerts;

		public ProviderResourceEntry(@NonNull String authority, @NonNull String pkg, @NonNull String query, @Nullable List<List<String>> certs, @Nullable String systemFontFamilyName) {
			mProviderAuthority = authority;
			mProviderPackage = pkg;
			mQuery = query;
			mCerts = certs;
			mSystemFontFamilyName = systemFontFamilyName;
		}

		public @NonNull String getAuthority() {
			return mProviderAuthority;
		}

		public @NonNull String getPackage() {
			return mProviderPackage;
		}

		public @NonNull String getQuery() {
			return mQuery;
		}

		public @Nullable String getSystemFontFamilyName() {
			return mSystemFontFamilyName;
		}

		public @Nullable List<List<String>> getCerts() {
			return mCerts;
		}
	}

	// A class represents font element in xml file which points a file in resource.
	public static final class FontFileResourceEntry {
		public static final int RESOLVE_BY_FONT_TABLE = Typeface.RESOLVE_BY_FONT_TABLE;
		public static final int UPRIGHT = 0;
		public static final int ITALIC = 1;

		private final @NonNull String mFileName;
		private int mWeight;
		private int mItalic;
		private int mTtcIndex;
		private String mVariationSettings;
		private int mResourceId;

		public FontFileResourceEntry(@NonNull String fileName, int weight, int italic, @Nullable String variationSettings, int ttcIndex) {
			mFileName = fileName;
			mWeight = weight;
			mItalic = italic;
			mVariationSettings = variationSettings;
			mTtcIndex = ttcIndex;
		}

		public @NonNull String getFileName() {
			return mFileName;
		}

		public int getWeight() {
			return mWeight;
		}

		public int getItalic() {
			return mItalic;
		}

		public @Nullable String getVariationSettings() {
			return mVariationSettings;
		}

		public int getTtcIndex() {
			return mTtcIndex;
		}
	}

	// A class represents file based font-family element in xml file.
	public static final class FontFamilyFilesResourceEntry implements FamilyResourceEntry {
		private final @NonNull FontFileResourceEntry[] mEntries;

		public FontFamilyFilesResourceEntry(@NonNull FontFileResourceEntry[] entries) {
			mEntries = entries;
		}

		public @NonNull FontFileResourceEntry[] getEntries() {
			return mEntries;
		}
	}

	public static @Nullable FamilyResourceEntry parse(XmlPullParser parser, Resources resources) throws XmlPullParserException, IOException {
		int type;
		while ((type = parser.next()) != XmlPullParser.START_TAG && type != XmlPullParser.END_DOCUMENT) {
			// Empty loop.
		}

		if (type != XmlPullParser.START_TAG) {
			throw new XmlPullParserException("No start tag found");
		}
		return readFamilies(parser, resources);
	}

	private static @Nullable FamilyResourceEntry readFamilies(XmlPullParser parser, Resources resources) throws XmlPullParserException, IOException {
		parser.require(XmlPullParser.START_TAG, null, "font-family");
		String tag = parser.getName();
		if (tag.equals("font-family")) {
			return readFamily(parser, resources);
		} else {
			skip(parser);
			Log.e(TAG, "Failed to find font-family tag");
			return null;
		}
	}

	private static @Nullable FamilyResourceEntry readFamily(XmlPullParser parser, Resources resources) throws XmlPullParserException, IOException {
		AttributeSet attrs = Xml.asAttributeSet(parser);
		TypedArray array = resources.obtainAttributes(attrs, FONT_FAMILY);
		String authority = array.getString(FONT_FAMILY_AUTHORITY);
		String providerPackage = array.getString(FONT_FAMILY_PACKAGE);
		String query = array.getString(FONT_FAMILY_QUERY);
		int certsId = array.getResourceId(FONT_FAMILY_CERTS, 0);
		String systemFontFamilyName = array.getString(FONT_FAMILY_SYSTEM_FONT_FAMILY);
		array.recycle();
		if (authority != null && providerPackage != null && query != null) {
			while (parser.next() != XmlPullParser.END_TAG) {
				skip(parser);
			}
			List<List<String>> certs = null;
			if (certsId != 0) {
				TypedArray typedArray = resources.obtainTypedArray(certsId);
				if (typedArray.length() > 0) {
					certs = new ArrayList<>();
					boolean isArrayOfArrays = typedArray.getResourceId(0, 0) != 0;
					if (isArrayOfArrays) {
						for (int i = 0; i < typedArray.length(); i++) {
							int certId = typedArray.getResourceId(i, 0);
							String[] certsArray = resources.getStringArray(certId);
							List<String> certsList = Arrays.asList(certsArray);
							certs.add(certsList);
						}
					} else {
						String[] certsArray = resources.getStringArray(certsId);
						List<String> certsList = Arrays.asList(certsArray);
						certs.add(certsList);
					}
				}
				typedArray.recycle();
			}
			return new ProviderResourceEntry(authority, providerPackage, query, certs, systemFontFamilyName);
		}
		List<FontFileResourceEntry> fonts = new ArrayList<>();
		while (parser.next() != XmlPullParser.END_TAG) {
			if (parser.getEventType() != XmlPullParser.START_TAG) continue;
			String tag = parser.getName();
			if (tag.equals("font")) {
				final FontFileResourceEntry entry = readFont(parser, resources);
				if (entry != null) {
					fonts.add(entry);
				}
			} else {
				skip(parser);
			}
		}
		if (fonts.isEmpty()) {
			return null;
		}
		return new FontFamilyFilesResourceEntry(fonts.toArray(new FontFileResourceEntry[fonts.size()]));
	}

	private static FontFileResourceEntry readFont(XmlPullParser parser, Resources resources) throws XmlPullParserException, IOException {
		AttributeSet attrs = Xml.asAttributeSet(parser);
		TypedArray array = resources.obtainAttributes(attrs, FONT_FAMILY_FONT);
		int weight = array.getInt(FONT_FAMILY_FONT_WEIGHT, Typeface.RESOLVE_BY_FONT_TABLE);
		int italic = array.getInt(FONT_FAMILY_FONT_STYLE, FontFileResourceEntry.RESOLVE_BY_FONT_TABLE);
		String variationSettings = array.getString(FONT_FAMILY_FONT_VARIATION_SETTINGS);
		int ttcIndex = array.getInt(FONT_FAMILY_FONT_TTC_INDEX, 0);
		String filename = array.getString(FONT_FAMILY_FONT_FONT);
		array.recycle();
		while (parser.next() != XmlPullParser.END_TAG) {
			skip(parser);
		}
		if (filename == null) {
			return null;
		}
		return new FontFileResourceEntry(filename, weight, italic, variationSettings, ttcIndex);
	}

	private static void skip(XmlPullParser parser) throws XmlPullParserException, IOException {
		int depth = 1;
		while (depth > 0) {
			switch (parser.next()) {
				case XmlPullParser.START_TAG:
					depth++;
					break;
				case XmlPullParser.END_TAG:
					depth--;
					break;
			}
		}
	}
}
