package android.text;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import org.xml.sax.ContentHandler;
import org.xml.sax.DTDHandler;
import org.xml.sax.EntityResolver;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXNotRecognizedException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.AttributesImpl;

/**
 * teslalinux: a tolerant HTML tokenizer feeding a SAX ContentHandler, standing in for the TagSoup
 * parser AOSP's Html.fromHtml uses (TagSoup is not part of this runtime). Like TagSoup it accepts
 * real-world HTML: case-insensitive names (reported in lower case), unquoted attributes, named and
 * numeric character references, void elements (br, img, hr, ...), self-closing tags, a new block
 * closing an open paragraph, a new li closing the previous one, end tags closing the elements opened
 * after the matching one, stray end tags ignored, and every element still open closed at the end.
 */
final class TlHtmlReader implements XMLReader {
	private ContentHandler mContentHandler;
	private EntityResolver mEntityResolver;
	private DTDHandler mDTDHandler;
	private ErrorHandler mErrorHandler;
	private final ArrayList<String> mStack = new ArrayList<String>();

	private static final java.util.Set<String> VOID = new java.util.HashSet<String>(java.util.Arrays.asList(
		"br", "img", "hr", "input", "meta", "link", "area", "base", "col", "embed", "param", "source", "track", "wbr"));
	private static final java.util.Set<String> CLOSES_P = new java.util.HashSet<String>(java.util.Arrays.asList(
		"p", "div", "ul", "ol", "li", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "table", "pre", "hr", "dl"));
	private static final HashMap<String, Integer> ENTITIES = new HashMap<String, Integer>();
	static {
		String[] names = {"amp", "lt", "gt", "quot", "apos", "nbsp", "copy", "reg", "trade", "mdash", "ndash", "hellip",
			"laquo", "raquo", "ldquo", "rdquo", "lsquo", "rsquo", "sbquo", "bdquo", "bull", "middot", "euro", "pound",
			"yen", "cent", "deg", "times", "divide", "shy", "sect", "para", "plusmn", "frac12", "frac14", "frac34",
			"iexcl", "iquest", "micro", "larr", "rarr", "uarr", "darr", "harr", "zwnj", "zwj", "lrm", "rlm", "ensp",
			"emsp", "thinsp", "dagger", "Dagger", "permil", "prime", "Prime", "oline", "sup1", "sup2", "sup3", "ordf",
			"ordm", "not", "macr", "acute", "cedil", "uml", "curren", "brvbar"};
		int[] cps = {'&', '<', '>', '"', '\'', 0xA0, 0xA9, 0xAE, 0x2122, 0x2014, 0x2013, 0x2026,
			0xAB, 0xBB, 0x201C, 0x201D, 0x2018, 0x2019, 0x201A, 0x201E, 0x2022, 0xB7, 0x20AC, 0xA3,
			0xA5, 0xA2, 0xB0, 0xD7, 0xF7, 0xAD, 0xA7, 0xB6, 0xB1, 0xBD, 0xBC, 0xBE,
			0xA1, 0xBF, 0xB5, 0x2190, 0x2192, 0x2191, 0x2193, 0x2194, 0x200C, 0x200D, 0x200E, 0x200F, 0x2002,
			0x2003, 0x2009, 0x2020, 0x2021, 0x2030, 0x2032, 0x2033, 0x203E, 0xB9, 0xB2, 0xB3, 0xAA,
			0xBA, 0xAC, 0xAF, 0xB4, 0xB8, 0xA8, 0xA4, 0xA6};
		for (int i = 0; i < names.length; i++)
			ENTITIES.put(names[i], cps[i]);
	}

	@Override
	public boolean getFeature(String name) throws SAXNotRecognizedException {
		throw new SAXNotRecognizedException(name);
	}

	@Override
	public void setFeature(String name, boolean value) throws SAXNotRecognizedException {
		throw new SAXNotRecognizedException(name);
	}

	@Override
	public Object getProperty(String name) throws SAXNotRecognizedException {
		throw new SAXNotRecognizedException(name);
	}

	@Override
	public void setProperty(String name, Object value) throws SAXNotRecognizedException {
		throw new SAXNotRecognizedException(name);
	}

	@Override
	public void setEntityResolver(EntityResolver resolver) {
		mEntityResolver = resolver;
	}

	@Override
	public EntityResolver getEntityResolver() {
		return mEntityResolver;
	}

	@Override
	public void setDTDHandler(DTDHandler handler) {
		mDTDHandler = handler;
	}

	@Override
	public DTDHandler getDTDHandler() {
		return mDTDHandler;
	}

	@Override
	public void setContentHandler(ContentHandler handler) {
		mContentHandler = handler;
	}

	@Override
	public ContentHandler getContentHandler() {
		return mContentHandler;
	}

	@Override
	public void setErrorHandler(ErrorHandler handler) {
		mErrorHandler = handler;
	}

	@Override
	public ErrorHandler getErrorHandler() {
		return mErrorHandler;
	}

	@Override
	public void parse(String systemId) throws IOException, SAXException {
		parse(new InputSource(systemId));
	}

	@Override
	public void parse(InputSource input) throws IOException, SAXException {
		Reader r = input.getCharacterStream();
		if (r == null)
			throw new IOException("TlHtmlReader reads character streams only");
		StringBuilder sb = new StringBuilder();
		char[] buf = new char[4096];
		int n;
		while ((n = r.read(buf)) > 0)
			sb.append(buf, 0, n);
		run(sb.toString());
	}

	private void run(String s) throws SAXException {
		final ContentHandler h = mContentHandler;
		mStack.clear();
		h.startDocument();
		final int len = s.length();
		int i = 0;
		StringBuilder text = new StringBuilder();
		while (i < len) {
			char c = s.charAt(i);
			if (c == '<' && i + 1 < len) {
				char d = s.charAt(i + 1);
				if (s.startsWith("<!--", i)) {
					flush(text);
					int e = s.indexOf("-->", i + 4);
					i = e < 0 ? len : e + 3;
					continue;
				}
				if (d == '!' || d == '?') {
					flush(text);
					int e = s.indexOf('>', i + 2);
					i = e < 0 ? len : e + 1;
					continue;
				}
				if (d == '/' || Character.isLetter(d)) {
					flush(text);
					i = tag(s, i);
					continue;
				}
			}
			if (c == '&') {
				int[] out = new int[2];
				if (entity(s, i, out)) {
					text.appendCodePoint(out[0]);
					i = out[1];
					continue;
				}
			}
			text.append(c);
			i++;
		}
		flush(text);
		for (int k = mStack.size() - 1; k >= 0; k--)
			h.endElement("", mStack.get(k), mStack.get(k));
		mStack.clear();
		h.endDocument();
	}

	private void flush(StringBuilder text) throws SAXException {
		if (text.length() > 0) {
			char[] ch = text.toString().toCharArray();
			mContentHandler.characters(ch, 0, ch.length);
			text.setLength(0);
		}
	}

	/** a character reference at s[i] ('&'); out = {code point, index after it} */
	private static boolean entity(String s, int i, int[] out) {
		int len = s.length();
		int j = i + 1;
		if (j < len && s.charAt(j) == '#') {
			j++;
			int radix = 10;
			if (j < len && (s.charAt(j) == 'x' || s.charAt(j) == 'X')) {
				radix = 16;
				j++;
			}
			int start = j;
			while (j < len && Character.digit(s.charAt(j), radix) >= 0)
				j++;
			if (j == start)
				return false;
			try {
				int cp = Integer.parseInt(s.substring(start, j), radix);
				if (cp <= 0 || cp > 0x10FFFF)
					cp = 0xFFFD;
				out[0] = cp;
			} catch (NumberFormatException e) {
				return false;
			}
			if (j < len && s.charAt(j) == ';')
				j++;
			out[1] = j;
			return true;
		}
		int start = j;
		while (j < len && Character.isLetterOrDigit(s.charAt(j)))
			j++;
		if (j == start)
			return false;
		Integer cp = ENTITIES.get(s.substring(start, j));
		if (cp == null)
			return false;
		if (j < len && s.charAt(j) == ';')
			j++;
		out[0] = cp;
		out[1] = j;
		return true;
	}

	private static String decode(String v) {
		if (v.indexOf('&') < 0)
			return v;
		StringBuilder sb = new StringBuilder();
		int[] out = new int[2];
		for (int i = 0; i < v.length();) {
			if (v.charAt(i) == '&' && entity(v, i, out)) {
				sb.appendCodePoint(out[0]);
				i = out[1];
			} else {
				sb.append(v.charAt(i++));
			}
		}
		return sb.toString();
	}

	/** a start or end tag at s[i] ('<'); returns the index after it */
	private int tag(String s, int i) throws SAXException {
		final int len = s.length();
		boolean end = s.charAt(i + 1) == '/';
		int j = i + (end ? 2 : 1);
		int start = j;
		while (j < len && !Character.isWhitespace(s.charAt(j)) && s.charAt(j) != '>' && s.charAt(j) != '/')
			j++;
		String name = s.substring(start, j).toLowerCase(Locale.ROOT);
		AttributesImpl attrs = new AttributesImpl();
		boolean selfClosing = false;
		while (j < len && s.charAt(j) != '>') {
			char c = s.charAt(j);
			if (Character.isWhitespace(c)) {
				j++;
				continue;
			}
			if (c == '/') {
				selfClosing = true;
				j++;
				continue;
			}
			int as = j;
			while (j < len && !Character.isWhitespace(s.charAt(j)) && s.charAt(j) != '=' && s.charAt(j) != '>' && s.charAt(j) != '/')
				j++;
			String an = s.substring(as, j).toLowerCase(Locale.ROOT);
			while (j < len && Character.isWhitespace(s.charAt(j)))
				j++;
			String av = an;
			if (j < len && s.charAt(j) == '=') {
				j++;
				while (j < len && Character.isWhitespace(s.charAt(j)))
					j++;
				if (j < len && (s.charAt(j) == '"' || s.charAt(j) == '\'')) {
					char q = s.charAt(j);
					int e = s.indexOf(q, j + 1);
					if (e < 0)
						e = len;
					av = s.substring(j + 1, e);
					j = Math.min(len, e + 1);
				} else {
					int vs = j;
					while (j < len && !Character.isWhitespace(s.charAt(j)) && s.charAt(j) != '>')
						j++;
					av = s.substring(vs, j);
				}
				av = decode(av);
			}
			if (!an.isEmpty() && attrs.getIndex(an) < 0)
				attrs.addAttribute("", an, an, "CDATA", av);
			selfClosing = false;
		}
		j = Math.min(len, j + 1);
		if (name.isEmpty())
			return j;
		final ContentHandler h = mContentHandler;
		if (end) {
			int k = mStack.lastIndexOf(name);
			if (k >= 0) {
				for (int m = mStack.size() - 1; m >= k; m--)
					h.endElement("", mStack.get(m), mStack.get(m));
				while (mStack.size() > k)
					mStack.remove(mStack.size() - 1);
			}
			return j;
		}
		if (CLOSES_P.contains(name) && mStack.contains("p"))
			closeThrough("p");
		if (name.equals("li") && mStack.contains("li")) {
			int li = mStack.lastIndexOf("li");
			int ul = Math.max(mStack.lastIndexOf("ul"), mStack.lastIndexOf("ol"));
			if (li > ul)
				closeThrough("li");
		}
		h.startElement("", name, name, attrs);
		if (VOID.contains(name) || selfClosing)
			h.endElement("", name, name);
		else
			mStack.add(name);
		return j;
	}

	private void closeThrough(String name) throws SAXException {
		int k = mStack.lastIndexOf(name);
		for (int m = mStack.size() - 1; m >= k; m--)
			mContentHandler.endElement("", mStack.get(m), mStack.get(m));
		while (mStack.size() > k)
			mStack.remove(mStack.size() - 1);
	}
}
