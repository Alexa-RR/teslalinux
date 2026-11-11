/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.content;

import static android.content.ClipDescription.MIMETYPE_TEXT_HTML;
import static android.content.ClipDescription.MIMETYPE_TEXT_INTENT;
import static android.content.ClipDescription.MIMETYPE_TEXT_PLAIN;
import static android.content.ClipDescription.MIMETYPE_TEXT_URILIST;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.Html;
import android.text.Spanned;
import android.text.TextUtils;
import android.util.Log;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;

/**
 * Representation of a clipped data on the clipboard.
 *
 * teslalinux: AOSP, minus the multi-user URI rewriting (prepareToLeaveProcess / fixUris), the
 * ContentProvider stream coercion of content:// URIs beyond openInputStream, text-classification
 * links (TextLinks) and the proto dump; Item.coerceToText reads text/* URIs through
 * ContentResolver.openInputStream as AOSP does.
 */
public class ClipData implements Parcelable {
	static final String[] MIMETYPES_TEXT_PLAIN = new String[] { MIMETYPE_TEXT_PLAIN };
	static final String[] MIMETYPES_TEXT_HTML = new String[] { MIMETYPE_TEXT_HTML };
	static final String[] MIMETYPES_TEXT_URILIST = new String[] { MIMETYPE_TEXT_URILIST };
	static final String[] MIMETYPES_TEXT_INTENT = new String[] { MIMETYPE_TEXT_INTENT };

	final ClipDescription mClipDescription;

	final android.graphics.Bitmap mIcon;

	final ArrayList<Item> mItems;

	/** Description of a single item in a ClipData. */
	public static class Item {
		final CharSequence mText;
		final String mHtmlText;
		final Intent mIntent;
		Uri mUri;

		/** A builder for a ClipData Item. */
		public static final class Builder {
			private CharSequence mText;
			private String mHtmlText;
			private Intent mIntent;
			private Uri mUri;

			@NonNull
			public Builder setText(@Nullable CharSequence text) {
				mText = text;
				return this;
			}

			@NonNull
			public Builder setHtmlText(@Nullable String htmlText) {
				mHtmlText = htmlText;
				return this;
			}

			@NonNull
			public Builder setIntent(@Nullable Intent intent) {
				mIntent = intent;
				return this;
			}

			@NonNull
			public Builder setUri(@Nullable Uri uri) {
				mUri = uri;
				return this;
			}

			@NonNull
			public Item build() {
				return new Item(mText, mHtmlText, mIntent, mUri);
			}
		}

		/** @hide */
		public Item(Item other) {
			mText = other.mText;
			mHtmlText = other.mHtmlText;
			mIntent = other.mIntent;
			mUri = other.mUri;
		}

		/** Create an Item consisting of a single block of (possibly styled) text. */
		public Item(CharSequence text) {
			mText = text;
			mHtmlText = null;
			mIntent = null;
			mUri = null;
		}

		/** Create an Item consisting of a single block of (possibly styled) text, with an alternative HTML formatted representation. */
		public Item(CharSequence text, String htmlText) {
			mText = text;
			mHtmlText = htmlText;
			mIntent = null;
			mUri = null;
		}

		/** Create an Item consisting of an arbitrary Intent. */
		public Item(Intent intent) {
			mText = null;
			mHtmlText = null;
			mIntent = intent;
			mUri = null;
		}

		/** Create an Item consisting of an arbitrary URI. */
		public Item(Uri uri) {
			mText = null;
			mHtmlText = null;
			mIntent = null;
			mUri = uri;
		}

		/** Create a complex Item, containing multiple representations of text, Intent, and/or URI. */
		public Item(CharSequence text, Intent intent, Uri uri) {
			mText = text;
			mHtmlText = null;
			mIntent = intent;
			mUri = uri;
		}

		/** Create a complex Item, containing multiple representations of text, HTML text, Intent, and/or URI. */
		public Item(CharSequence text, String htmlText, Intent intent, Uri uri) {
			if (htmlText != null && text == null) {
				throw new IllegalArgumentException("Plain text must be supplied if HTML text is supplied");
			}
			mText = text;
			mHtmlText = htmlText;
			mIntent = intent;
			mUri = uri;
		}

		/** Retrieve the raw text contained in this Item. */
		public CharSequence getText() {
			return mText;
		}

		/** Retrieve the raw HTML text contained in this Item. */
		public String getHtmlText() {
			return mHtmlText;
		}

		/** Retrieve the raw Intent contained in this Item. */
		public Intent getIntent() {
			return mIntent;
		}

		/** Retrieve the raw URI contained in this Item. */
		public Uri getUri() {
			return mUri;
		}

		/**
		 * Turn this item into text, regardless of the type of data it actually contains: text, a
		 * text/* URI read through the content resolver, or an Intent / URI rendered as a string.
		 */
		public CharSequence coerceToText(Context context) {
			// If this Item has an explicit textual value, simply return that.
			CharSequence text = getText();
			if (text != null) {
				return text;
			}

			// If this Item has a URI value, try using that.
			Uri uri = getUri();
			if (uri != null) {
				// First see if the URI can be opened as a plain text stream (of any sub-type).
				InputStream stream = null;
				try {
					String type = context.getContentResolver().getType(uri);
					if (type != null && type.startsWith("text/")) {
						stream = context.getContentResolver().openInputStream(uri);
					}
				} catch (FileNotFoundException | SecurityException e) {
					// Unable to open content URI as text... not really an error, just something to ignore.
				}
				if (stream != null) {
					try {
						StringBuilder builder = new StringBuilder(128);
						InputStreamReader reader = new InputStreamReader(stream, "UTF-8");
						char[] buffer = new char[8192];
						int len;
						while ((len = reader.read(buffer)) > 0) {
							builder.append(buffer, 0, len);
						}
						return builder.toString();
					} catch (IOException e) {
						// Something bad has happened.
						Log.w("ClipData", "Failure loading text", e);
						return e.toString();
					} finally {
						try {
							stream.close();
						} catch (IOException e) {
							// ignore
						}
					}
				}

				// If we couldn't open the URI as a stream, use the URI itself as a textual representation.
				return uri.toString();
			}

			// Finally, if all we have is an Intent, then we can just turn that into text.
			Intent intent = getIntent();
			if (intent != null) {
				return intent.toUri(Intent.URI_INTENT_SCHEME);
			}

			// Shouldn't get here, but just in case...
			return "";
		}

		/** Like {@link #coerceToHtmlText(Context)}, but returns styled text. */
		public CharSequence coerceToStyledText(Context context) {
			CharSequence text = getText();
			if (text instanceof Spanned) {
				return text;
			}
			String htmlText = getHtmlText();
			if (htmlText != null) {
				try {
					CharSequence newText = Html.fromHtml(htmlText);
					if (newText != null) {
						return newText;
					}
				} catch (RuntimeException e) {
					// If anything bad happens, we'll fall back on the plain text.
				}
			}

			if (text != null) {
				return text;
			}
			return coerceToHtmlOrStyledText(context, true);
		}

		/** Turn this item into HTML text, regardless of the type of data it actually contains. */
		public String coerceToHtmlText(Context context) {
			// If the item has an explicit HTML value, simply return that.
			String htmlText = getHtmlText();
			if (htmlText != null) {
				return htmlText;
			}

			// If this Item has a plain text value, return it as HTML.
			CharSequence text = getText();
			if (text != null) {
				// ATL Html has no toHtml(Spanned): styled text is escaped as plain text (style tags are lost)
				return Html.escapeHtml(text);
			}

			text = coerceToHtmlOrStyledText(context, false);
			return text != null ? text.toString() : null;
		}

		private CharSequence coerceToHtmlOrStyledText(Context context, boolean styled) {
			// If this Item has a URI value, try using that.
			if (mUri != null) {
				// Check to see what data representations the content provider supports; text/html is used as-is.
				String type = null;
				try {
					type = context.getContentResolver().getType(mUri);
				} catch (SecurityException e) {
					// ignore
				}
				if (type != null && (type.equals(MIMETYPE_TEXT_HTML) || type.startsWith("text/"))) {
					InputStream stream = null;
					try {
						stream = context.getContentResolver().openInputStream(mUri);
					} catch (FileNotFoundException | SecurityException e) {
						// Unable to open content URI as text... not really an error, just something to ignore.
					}
					if (stream != null) {
						try {
							StringBuilder builder = new StringBuilder(128);
							InputStreamReader reader = new InputStreamReader(stream, "UTF-8");
							char[] buffer = new char[8192];
							int len;
							while ((len = reader.read(buffer)) > 0) {
								builder.append(buffer, 0, len);
							}
							String text = builder.toString();
							if (type.equals(MIMETYPE_TEXT_HTML)) {
								if (styled) {
									try {
										CharSequence newText = Html.fromHtml(text);
										return newText != null ? newText : text;
									} catch (RuntimeException e) {
										return text;
									}
								} else {
									return text;
								}
							}
							return styled ? text : Html.escapeHtml(text);
						} catch (IOException e) {
							Log.w("ClipData", "Failure loading text", e);
							return Html.escapeHtml(e.toString());
						} finally {
							try {
								stream.close();
							} catch (IOException e) {
								// ignore
							}
						}
					}
				}

				// If we couldn't open the URI as a stream, use the URI itself as a textual representation.
				if (styled) {
					return uriToStyledText(mUri);
				} else {
					return uriToHtml(mUri);
				}
			}

			// Finally, if all we have is an Intent, then we can just turn that into text.
			if (mIntent != null) {
				if (styled) {
					return uriToStyledText(mIntent.toUri(Intent.URI_INTENT_SCHEME));
				} else {
					return uriToHtml(mIntent.toUri(Intent.URI_INTENT_SCHEME));
				}
			}

			// Shouldn't get here, but just in case...
			return "";
		}

		private String uriToHtml(Uri uri) {
			return uriToHtml(uri.toString());
		}

		private String uriToHtml(String uri) {
			StringBuilder builder = new StringBuilder(256);
			builder.append("<a href=\"");
			builder.append(Html.escapeHtml(uri));
			builder.append("\">");
			builder.append(Html.escapeHtml(uri));
			builder.append("</a>");
			return builder.toString();
		}

		private CharSequence uriToStyledText(Uri uri) {
			return uriToStyledText(uri.toString());
		}

		private CharSequence uriToStyledText(String uri) {
			android.text.SpannableStringBuilder builder = new android.text.SpannableStringBuilder();
			builder.append(uri);
			builder.setSpan(new android.text.style.URLSpan(uri), 0, builder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
			return builder;
		}

		@Override
		public String toString() {
			StringBuilder b = new StringBuilder(128);

			b.append("ClipData.Item { ");
			toShortString(b, true);
			b.append(" }");

			return b.toString();
		}

		/** @hide */
		public void toShortString(StringBuilder b, boolean redactContent) {
			boolean first = true;
			if (mHtmlText != null) {
				first = false;
				if (redactContent) {
					b.append("H(").append(mHtmlText.length()).append(')');
				} else {
					b.append("H:").append(mHtmlText);
				}
			}
			if (mText != null) {
				if (!first) {
					b.append(' ');
				}
				first = false;
				if (redactContent) {
					b.append("T(").append(mText.length()).append(')');
				} else {
					b.append("T:").append(mText);
				}
			}
			if (mUri != null) {
				if (!first) {
					b.append(' ');
				}
				first = false;
				if (redactContent) {
					b.append("U(").append(mUri.getScheme()).append(')');
				} else {
					b.append("U:").append(mUri);
				}
			}
			if (mIntent != null) {
				if (!first) {
					b.append(' ');
				}
				first = false;
				b.append("I:");
				b.append(mIntent.toString());
			}
		}

		/** @hide */
		public void toShortSummaryString(StringBuilder b) {
			if (mHtmlText != null) {
				b.append("HTML");
			} else if (mText != null) {
				b.append("TEXT");
			} else if (mUri != null) {
				b.append("U:");
				b.append(mUri);
			} else if (mIntent != null) {
				b.append("I:");
				b.append(mIntent.toString());
			}
		}
	}

	/** Create a new clip. */
	public ClipData(CharSequence label, String[] mimeTypes, Item item) {
		mClipDescription = new ClipDescription(label, mimeTypes);
		if (item == null) {
			throw new NullPointerException("item is null");
		}
		mIcon = null;
		mItems = new ArrayList<Item>();
		mItems.add(item);
		mClipDescription.setIsStyledText(isStyledText());
	}

	/** Create a new clip. */
	public ClipData(ClipDescription description, Item item) {
		mClipDescription = description;
		if (item == null) {
			throw new NullPointerException("item is null");
		}
		mIcon = null;
		mItems = new ArrayList<Item>();
		mItems.add(item);
		mClipDescription.setIsStyledText(isStyledText());
	}

	/** Create a new clip. */
	public ClipData(ClipDescription description, ArrayList<Item> items) {
		mClipDescription = description;
		if (items == null) {
			throw new NullPointerException("item is null");
		}
		mIcon = null;
		mItems = items;
	}

	/** Create a new clip that is a copy of another clip. This does a deep-copy of all items in the clip. */
	public ClipData(ClipData other) {
		mClipDescription = other.mClipDescription;
		mIcon = other.mIcon;
		mItems = new ArrayList<Item>(other.mItems);
	}

	/** Returns a copy of the ClipData with a limited number of items. @hide */
	public ClipData copyForTransferWithActivityInfo() {
		return new ClipData(this);
	}

	/** Create a new ClipData holding data of the type {@link ClipDescription#MIMETYPE_TEXT_PLAIN}. */
	public static ClipData newPlainText(CharSequence label, CharSequence text) {
		Item item = new Item(text);
		return new ClipData(label, MIMETYPES_TEXT_PLAIN, item);
	}

	/** Create a new ClipData holding data of the type {@link ClipDescription#MIMETYPE_TEXT_HTML}. */
	public static ClipData newHtmlText(CharSequence label, CharSequence text, String htmlText) {
		Item item = new Item(text, htmlText);
		return new ClipData(label, MIMETYPES_TEXT_HTML, item);
	}

	/** Create a new ClipData holding an Intent with MIME type {@link ClipDescription#MIMETYPE_TEXT_INTENT}. */
	public static ClipData newIntent(CharSequence label, Intent intent) {
		Item item = new Item(intent);
		return new ClipData(label, MIMETYPES_TEXT_INTENT, item);
	}

	/**
	 * Create a new ClipData holding a URI. The MIME types are the types the content resolver
	 * reports for the URI plus text/uri-list, as in AOSP.
	 */
	public static ClipData newUri(ContentResolver resolver, CharSequence label, Uri uri) {
		Item item = new Item(uri);
		String[] mimeTypes = getMimeTypes(resolver, uri);
		return new ClipData(label, mimeTypes, item);
	}

	/** Finds all applicable MIME types for a given URI. */
	private static String[] getMimeTypes(ContentResolver resolver, Uri uri) {
		String[] mimeTypes = null;
		if (uri != null && ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
			String realType = resolver.getType(uri);
			if (realType != null) {
				mimeTypes = new String[] { realType, MIMETYPE_TEXT_URILIST };
			}
		}
		if (mimeTypes == null) {
			mimeTypes = MIMETYPES_TEXT_URILIST;
		}
		return mimeTypes;
	}

	/** Create a new ClipData holding an URI with MIME type {@link ClipDescription#MIMETYPE_TEXT_URILIST}. */
	public static ClipData newRawUri(CharSequence label, Uri uri) {
		Item item = new Item(uri);
		return new ClipData(label, MIMETYPES_TEXT_URILIST, item);
	}

	/** Return the {@link ClipDescription} associated with this data, describing what it contains. */
	public ClipDescription getDescription() {
		return mClipDescription;
	}

	/** Add a new Item to the overall ClipData container. */
	public void addItem(Item item) {
		if (item == null) {
			throw new NullPointerException("item is null");
		}
		mItems.add(item);
		if (mItems.size() == 1) {
			mClipDescription.setIsStyledText(isStyledText());
		}
	}

	/** Add a new Item to the overall ClipData container, updating the MIME types of the description from the resolver. */
	public void addItem(ContentResolver resolver, Item item) {
		addItem(item);

		if (item.getHtmlText() != null) {
			mClipDescription.addMimeTypes(MIMETYPES_TEXT_HTML);
		} else if (item.getText() != null) {
			mClipDescription.addMimeTypes(MIMETYPES_TEXT_PLAIN);
		}

		if (item.getIntent() != null) {
			mClipDescription.addMimeTypes(MIMETYPES_TEXT_INTENT);
		}

		if (item.getUri() != null) {
			mClipDescription.addMimeTypes(getMimeTypes(resolver, item.getUri()));
		}
	}

	/** @hide */
	public android.graphics.Bitmap getIcon() {
		return mIcon;
	}

	/** Return the number of items in the clip data. */
	public int getItemCount() {
		return mItems.size();
	}

	/** Return a single item inside of the clip data. */
	public Item getItemAt(int index) {
		return mItems.get(index);
	}

	/** @hide */
	public void setItemAt(int index, Item item) {
		mItems.set(index, item);
	}

	private boolean isStyledText() {
		if (mItems.isEmpty()) {
			return false;
		}
		final CharSequence text = mItems.get(0).getText();
		if (text instanceof Spanned) {
			Spanned spanned = (Spanned) text;
			Object[] spans = spanned.getSpans(0, spanned.length(), Object.class);
			for (Object span : spans) {
				if (span instanceof android.text.style.CharacterStyle || span instanceof android.text.style.ParagraphStyle) {
					return true;
				}
			}
		}
		return false;
	}

	@Override
	public String toString() {
		StringBuilder b = new StringBuilder(128);

		b.append("ClipData { ");
		toShortString(b, true);
		b.append(" }");

		return b.toString();
	}

	/** @hide */
	public void toShortString(StringBuilder b, boolean redactContent) {
		boolean first;
		if (mClipDescription != null) {
			first = !mClipDescription.toShortString(b, redactContent);
		} else {
			first = true;
		}
		if (mIcon != null) {
			if (!first) {
				b.append(' ');
			}
			first = false;
			b.append("I:");
			b.append(mIcon.getWidth());
			b.append('x');
			b.append(mIcon.getHeight());
		}
		if (mItems.size() != 1) {
			if (!first) {
				b.append(' ');
			}
			first = false;
			b.append(mItems.size()).append(" items:");
		}
		for (int i = 0; i < mItems.size(); i++) {
			if (!first) {
				b.append(' ');
			}
			first = false;
			b.append('{');
			mItems.get(i).toShortString(b, redactContent);
			b.append('}');
		}
	}

	@Override
	public int describeContents() {
		return 0;
	}

	@Override
	public void writeToParcel(Parcel dest, int flags) {
		mClipDescription.writeToParcel(dest, flags);
		dest.writeInt(0); // no icon
		final int N = mItems.size();
		dest.writeInt(N);
		for (int i = 0; i < N; i++) {
			Item item = mItems.get(i);
			dest.writeString(item.mText == null ? null : item.mText.toString()); // spans are not parcelled by ATL
			dest.writeString(item.mHtmlText);
			if (item.mIntent != null) {
				dest.writeInt(1);
				item.mIntent.writeToParcel(dest, flags);
			} else {
				dest.writeInt(0);
			}
			dest.writeString(item.mUri == null ? null : item.mUri.toString());
		}
	}

	ClipData(Parcel in) {
		mClipDescription = new ClipDescription(in);
		in.readInt(); // icon flag (icons are not parcelled here)
		mIcon = null;
		mItems = new ArrayList<Item>();
		final int N = in.readInt();
		for (int i = 0; i < N; i++) {
			CharSequence text = in.readString();
			String htmlText = in.readString();
			Intent intent = in.readInt() != 0 ? Intent.CREATOR.createFromParcel(in) : null;
			String uri = in.readString();
			mItems.add(new Item(text, htmlText, intent, uri == null ? null : Uri.parse(uri)));
		}
	}

	public static final @NonNull Parcelable.Creator<ClipData> CREATOR = new Parcelable.Creator<ClipData>() {
		@Override
		public ClipData createFromParcel(Parcel source) {
			return new ClipData(source);
		}

		@Override
		public ClipData[] newArray(int size) {
			return new ClipData[size];
		}
	};
}
