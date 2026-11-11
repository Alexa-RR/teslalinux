/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.text.util;

import android.text.Spannable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.method.MovementMethod;
import android.text.style.URLSpan;
import android.util.Patterns;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Linkify take a piece of text and a regular expression and turns all of the regex matches in
 * the text into clickable links (AOSP android.text.util.Linkify).
 *
 * teslalinux: AOSP finds phone numbers with libphonenumber and map addresses with
 * WebView.findAddress; neither exists in this runtime, so phone numbers are found the way AOSP did
 * before libphonenumber (Patterns.PHONE with at least 5 digits, linked as tel: digits and '+'),
 * and MAP_ADDRESSES finds nothing, as on an AOSP build without the WebView address finder.
 */
public class Linkify {
	public static final int WEB_URLS = 0x01;
	public static final int EMAIL_ADDRESSES = 0x02;
	public static final int PHONE_NUMBERS = 0x04;
	@Deprecated
	public static final int MAP_ADDRESSES = 0x08;
	@Deprecated
	public static final int ALL = WEB_URLS | EMAIL_ADDRESSES | PHONE_NUMBERS | MAP_ADDRESSES;

	private static final int PHONE_NUMBER_MINIMUM_DIGITS = 5;

	/** AOSP: a URL match is only a link when it is not preceded by '@' (part of an email address) */
	public static final MatchFilter sUrlMatchFilter = new MatchFilter() {
		public final boolean acceptMatch(CharSequence s, int start, int end) {
			if (start == 0)
				return true;
			if (s.charAt(start - 1) == '@')
				return false;
			return true;
		}
	};

	/** AOSP: a phone number match needs at least PHONE_NUMBER_MINIMUM_DIGITS digits */
	public static final MatchFilter sPhoneNumberMatchFilter = new MatchFilter() {
		public final boolean acceptMatch(CharSequence s, int start, int end) {
			int digitCount = 0;
			for (int i = start; i < end; i++) {
				if (Character.isDigit(s.charAt(i))) {
					digitCount++;
					if (digitCount >= PHONE_NUMBER_MINIMUM_DIGITS)
						return true;
				}
			}
			return false;
		}
	};

	/** AOSP: a phone number link keeps only the digits and '+' */
	public static final TransformFilter sPhoneNumberTransformFilter = new TransformFilter() {
		public final String transformUrl(final Matcher match, String url) {
			return Patterns.digitsAndPlusOnly(match);
		}
	};

	public interface MatchFilter {
		boolean acceptMatch(CharSequence s, int start, int end);
	}

	public interface TransformFilter {
		String transformUrl(final Matcher match, String url);
	}

	public static final boolean addLinks(Spannable text, int mask) {
		return addLinks(text, mask, null);
	}

	public static final boolean addLinks(Spannable text, int mask, Function<String, URLSpan> urlSpanFactory) {
		if (text != null && containsUnsupportedCharacters(text.toString()))
			return false;
		if (mask == 0)
			return false;
		final URLSpan[] old = text.getSpans(0, text.length(), URLSpan.class);
		for (int i = old.length - 1; i >= 0; i--)
			text.removeSpan(old[i]);
		final ArrayList<LinkSpec> links = new ArrayList<LinkSpec>();
		if ((mask & WEB_URLS) != 0)
			gatherLinks(links, text, Patterns.AUTOLINK_WEB_URL, new String[] {"http://", "https://", "rtsp://", "ftp://"}, sUrlMatchFilter, null);
		if ((mask & EMAIL_ADDRESSES) != 0)
			gatherLinks(links, text, Patterns.AUTOLINK_EMAIL_ADDRESS, new String[] {"mailto:"}, null, null);
		if ((mask & PHONE_NUMBERS) != 0)
			gatherLinks(links, text, Patterns.PHONE, new String[] {"tel:"}, sPhoneNumberMatchFilter, sPhoneNumberTransformFilter);
		pruneOverlaps(links);
		if (links.size() == 0)
			return false;
		for (LinkSpec link : links)
			applyLink(link.url, link.start, link.end, text, urlSpanFactory);
		return true;
	}

	/** AOSP: bidi override characters make link text spoofable; such text is not linkified */
	private static boolean containsUnsupportedCharacters(String text) {
		return text.contains("‬") || text.contains("‭") || text.contains("‮");
	}

	public static final boolean addLinks(TextView text, int mask) {
		if (mask == 0)
			return false;
		final CharSequence t = text.getText();
		if (t instanceof Spannable) {
			if (addLinks((Spannable) t, mask)) {
				addLinkMovementMethod(text);
				return true;
			}
			return false;
		} else {
			SpannableString s = SpannableString.valueOf(t);
			if (addLinks(s, mask)) {
				addLinkMovementMethod(text);
				text.setText(s);
				return true;
			}
			return false;
		}
	}

	private static final void addLinkMovementMethod(TextView t) {
		MovementMethod m = t.getMovementMethod();
		if ((m == null) || !(m instanceof LinkMovementMethod)) {
			if (t.getLinksClickable())
				t.setMovementMethod(LinkMovementMethod.getInstance());
		}
	}

	public static final void addLinks(TextView text, Pattern pattern, String scheme) {
		addLinks(text, pattern, scheme, null, null, null);
	}

	public static final void addLinks(TextView text, Pattern pattern, String scheme, MatchFilter matchFilter, TransformFilter transformFilter) {
		addLinks(text, pattern, scheme, null, matchFilter, transformFilter);
	}

	public static final void addLinks(TextView text, Pattern pattern, String defaultScheme, String[] schemes, MatchFilter matchFilter, TransformFilter transformFilter) {
		SpannableString spannable = SpannableString.valueOf(text.getText());
		boolean linksAdded = addLinks(spannable, pattern, defaultScheme, schemes, matchFilter, transformFilter);
		if (linksAdded) {
			text.setText(spannable);
			addLinkMovementMethod(text);
		}
	}

	public static final boolean addLinks(Spannable text, Pattern pattern, String scheme) {
		return addLinks(text, pattern, scheme, null, null, null);
	}

	public static final boolean addLinks(Spannable spannable, Pattern pattern, String scheme, MatchFilter matchFilter, TransformFilter transformFilter) {
		return addLinks(spannable, pattern, scheme, null, matchFilter, transformFilter);
	}

	public static final boolean addLinks(Spannable spannable, Pattern pattern, String defaultScheme, String[] schemes, MatchFilter matchFilter, TransformFilter transformFilter) {
		return addLinks(spannable, pattern, defaultScheme, schemes, matchFilter, transformFilter, null);
	}

	public static final boolean addLinks(Spannable spannable, Pattern pattern, String defaultScheme, String[] schemes, MatchFilter matchFilter, TransformFilter transformFilter, Function<String, URLSpan> urlSpanFactory) {
		if (spannable != null && containsUnsupportedCharacters(spannable.toString()))
			return false;
		final String[] schemesCopy;
		if (defaultScheme == null)
			defaultScheme = "";
		if (schemes == null || schemes.length < 1)
			schemes = new String[0];
		schemesCopy = new String[schemes.length + 1];
		schemesCopy[0] = defaultScheme.toLowerCase(Locale.ROOT);
		for (int index = 0; index < schemes.length; index++) {
			String scheme = schemes[index];
			schemesCopy[index + 1] = (scheme == null) ? "" : scheme.toLowerCase(Locale.ROOT);
		}
		boolean hasMatches = false;
		Matcher m = pattern.matcher(spannable);
		while (m.find()) {
			int start = m.start();
			int end = m.end();
			boolean allowed = true;
			if (matchFilter != null)
				allowed = matchFilter.acceptMatch(spannable, start, end);
			if (allowed) {
				String url = makeUrl(m.group(0), schemesCopy, m, transformFilter);
				applyLink(url, start, end, spannable, urlSpanFactory);
				hasMatches = true;
			}
		}
		return hasMatches;
	}

	private static void applyLink(String url, int start, int end, Spannable text, Function<String, URLSpan> urlSpanFactory) {
		if (urlSpanFactory == null)
			urlSpanFactory = DEFAULT_SPAN_FACTORY;
		final URLSpan span = urlSpanFactory.apply(url);
		text.setSpan(span, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
	}

	private static final Function<String, URLSpan> DEFAULT_SPAN_FACTORY = new Function<String, URLSpan>() {
		@Override
		public URLSpan apply(String string) {
			return new URLSpan(string);
		}
	};

	private static final String makeUrl(String url, String[] prefixes, Matcher matcher, TransformFilter filter) {
		if (filter != null)
			url = filter.transformUrl(matcher, url);
		boolean hasPrefix = false;
		for (int i = 0; i < prefixes.length; i++) {
			if (url.regionMatches(true, 0, prefixes[i], 0, prefixes[i].length())) {
				hasPrefix = true;
				// Fix capitalization if necessary
				if (!url.regionMatches(false, 0, prefixes[i], 0, prefixes[i].length()))
					url = prefixes[i] + url.substring(prefixes[i].length());
				break;
			}
		}
		if (!hasPrefix && prefixes.length > 0)
			url = prefixes[0] + url;
		return url;
	}

	private static final void gatherLinks(ArrayList<LinkSpec> links, Spannable s, Pattern pattern, String[] schemes, MatchFilter matchFilter, TransformFilter transformFilter) {
		Matcher m = pattern.matcher(s);
		while (m.find()) {
			int start = m.start();
			int end = m.end();
			if (matchFilter == null || matchFilter.acceptMatch(s, start, end)) {
				LinkSpec spec = new LinkSpec();
				spec.url = makeUrl(m.group(0), schemes, m, transformFilter);
				spec.start = start;
				spec.end = end;
				links.add(spec);
			}
		}
	}

	private static final void pruneOverlaps(ArrayList<LinkSpec> links) {
		Comparator<LinkSpec> c = new Comparator<LinkSpec>() {
			public final int compare(LinkSpec a, LinkSpec b) {
				if (a.start < b.start)
					return -1;
				if (a.start > b.start)
					return 1;
				if (a.end < b.end)
					return 1;
				if (a.end > b.end)
					return -1;
				return 0;
			}
		};
		Collections.sort(links, c);
		int len = links.size();
		int i = 0;
		while (i < len - 1) {
			LinkSpec a = links.get(i);
			LinkSpec b = links.get(i + 1);
			int remove = -1;
			if ((a.start <= b.start) && (a.end > b.start)) {
				if (b.end <= a.end)
					remove = i + 1;
				else if ((a.end - a.start) > (b.end - b.start))
					remove = i + 1;
				else if ((a.end - a.start) < (b.end - b.start))
					remove = i;
				if (remove != -1) {
					links.remove(remove);
					len--;
					continue;
				}
			}
			i++;
		}
	}

	private static class LinkSpec {
		String url;
		int start;
		int end;
	}
}
