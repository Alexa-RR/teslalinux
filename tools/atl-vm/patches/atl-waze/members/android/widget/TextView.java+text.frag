import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.os.LocaleList;
import android.text.Selection;
import android.text.SpanWatcher;
import android.text.Spannable;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.text.method.PasswordTransformationMethod;
import android.text.method.SingleLineTransformationMethod;
import android.text.style.CharacterStyle;
import android.text.style.ClickableSpan;
import android.text.style.RelativeSizeSpan;
import android.text.util.Linkify;
import android.view.MotionEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;

	/* ==== teslalinux (+text): AOSP TextView behaviour on top of the GTK label ====
	 * The text is still drawn by a GtkLabel (an EditText's by GtkText), but everything AOSP derives
	 * from the paint and the text now reaches it as a Pango attribute list (native-text-textview.sh):
	 * pixel text size, typeface family/weight/style, letter and line spacing, underline/strike-through
	 * paint flags, every CharacterStyle span. Lines (singleLine, maxLines, minLines, lines, ellipsize),
	 * compound drawables (drawn here, kept clear of by the label's margins), min/max width and height,
	 * ems, autosizing, transformation methods, filters, text watchers, movement methods (clickable
	 * links), auto-link, text appearance and the AOSP constructor attributes follow AOSP TextView. */

	private native void native_setStyledText(long widget, String text, int[] attrs, String[] strings);
	private native void native_setLineConfig(long widget, int maxLines, int ellipsize, boolean singleLine);
	private native int native_getLinesHeight(long widget, int width, int lines);
	private native long native_getLayout(long widget);
	private native void native_setCompoundPadding4(long widget, int left, int top, int right, int bottom);
	private native void native_setJavaDraw(long widget, boolean enable);
	private native void native_setShadow(long widget, float radius, float dx, float dy, int color);
	protected native void native_setHintColor(long widget, int color);
	protected native void native_setCursorVisible(long widget, boolean visible);
	protected native void native_setMaxLength(long widget, int max);
	protected native void native_setTextVisibility(long widget, boolean visible);

	/* AOSP constants */
	public static final int AUTO_SIZE_TEXT_TYPE_NONE = 0;
	public static final int AUTO_SIZE_TEXT_TYPE_UNIFORM = 1;
	private static final int DEFAULT_AUTO_SIZE_MIN_TEXT_SIZE_IN_SP = 12;
	private static final int DEFAULT_AUTO_SIZE_MAX_TEXT_SIZE_IN_SP = 112;
	private static final int DEFAULT_AUTO_SIZE_GRANULARITY_IN_PX = 1;
	private static final float UNSET_AUTO_SIZE_UNIFORM_CONFIGURATION_VALUE = -1f;
	private static final int LINES = 1;
	private static final int EMS = LINES;
	private static final int PIXELS = 2;
	private static final InputFilter[] NO_FILTERS = new InputFilter[0];
	private static final android.text.Spanned EMPTY_SPANNED = new android.text.SpannedString("");
	private static final int SANS = 1, SERIF = 2, MONOSPACE = 3;

	/* line and size limits (AOSP mMaximum/mMaxMode, mMinimum/mMinMode, mMaxWidth/..., mMinWidth/...) */
	private int mMaximum = Integer.MAX_VALUE;
	private int mMaxMode = LINES;
	private int mMinimum = 0;
	private int mMinMode = LINES;
	private int mMaxWidth = Integer.MAX_VALUE;
	private int mMaxWidthMode = PIXELS;
	private int mMinWidth = 0;
	private int mMinWidthMode = PIXELS;
	private boolean mSingleLine;
	private boolean mHorizontallyScrolling;
	private TextUtils.TruncateAt mEllipsize;
	private int mMarqueeRepeatLimit = 3;

	/* text style */
	private float tlTextSize;          /* px; 0 while no size was ever set (GTK's default font) */
	private int mTextSizeUnit = -1;
	private float mSpacingMult = 1.0f;
	private float mSpacingAdd = 0.0f;
	private boolean mIncludePad = true;
	private float mShadowRadius, mShadowDx, mShadowDy;
	private int mShadowColor;
	private String tlFontFeatures;
	private String tlFontVariations;
	private LocaleList tlTextLocales;
	private boolean tlFakeBold;
	private float tlSkewX;
	private ColorStateList tlLinkTextColor;
	private int tlCurLinkColor;
	private boolean tlLinkColorResolved;

	/* text content */
	private CharSequence mTransformed = "";
	private CharSequence tlShown = "";
	private BufferType mBufferType = BufferType.NORMAL;
	private InputFilter[] mFilters = NO_FILTERS;
	private ArrayList<TextWatcher> mListeners;
	private TransformationMethod mTransformation;
	private MovementMethod mMovement;
	private KeyListener tlKeyListener;
	private Editable.Factory mEditableFactory = Editable.Factory.getInstance();
	private Spannable.Factory mSpannableFactory = Spannable.Factory.getInstance();
	private int mAutoLinkMask;
	private boolean mLinksClickable = true;
	private boolean mTextIsSelectable;
	private boolean mSelectAllOnFocus;
	private boolean mFreezesText;
	private boolean mCursorVisible = true;
	private boolean mShowSoftInputOnFocus = true;
	private ActionMode.Callback mCustomSelectionActionModeCallback;
	private ActionMode.Callback mCustomInsertionActionModeCallback;
	private CharSequence mError;
	private int mBatchEditNesting;
	private TlChangeWatcher mChangeWatcher;
	private int tlTextGen;
	private Layout tlLayout;
	private int tlLayoutGen = -1;
	private int tlLayoutWidth = -1;
	private boolean tlInInit;

	/* autosize (AOSP) */
	private int mAutoSizeTextType = AUTO_SIZE_TEXT_TYPE_NONE;
	private boolean mNeedsAutoSizeText;
	private float mAutoSizeStepGranularityInPx = UNSET_AUTO_SIZE_UNIFORM_CONFIGURATION_VALUE;
	private float mAutoSizeMinTextSizeInPx = UNSET_AUTO_SIZE_UNIFORM_CONFIGURATION_VALUE;
	private float mAutoSizeMaxTextSizeInPx = UNSET_AUTO_SIZE_UNIFORM_CONFIGURATION_VALUE;
	private int[] mAutoSizeTextSizesInPx = new int[0];
	private boolean mHasPresetAutoSizeValues;
	private TextPaint mTempTextPaint;

	/* compound drawables (AOSP TextView.Drawables) */
	private static final int DR_LEFT = 0, DR_TOP = 1, DR_RIGHT = 2, DR_BOTTOM = 3;
	private Drawable[] tlShowing = new Drawable[4]; /* null while View's constructor runs */
	private Drawable tlDrStart, tlDrEnd;
	private int tlDrSizeLeft, tlDrHeightLeft, tlDrSizeRight, tlDrHeightRight;
	private int tlDrSizeTop, tlDrWidthTop, tlDrSizeBottom, tlDrWidthBottom;
	private int tlDrawablePadding;
	private ColorStateList tlDrTintList;
	private BlendMode tlDrBlendMode;
	private boolean tlDrHasTint, tlDrHasTintMode;
	private boolean tlJavaDraw;
	private int tlCompoundTop, tlCompoundBottom;

	/* ---------------------------------------------------------------- construction (AOSP) */

	/** AOSP TextAppearanceAttributes */
	private static final class TlAppearance {
		int textColorHighlight = 0;
		ColorStateList textColor, textColorHint, textColorLink;
		int textSize = -1;
		int textSizeUnit = -1;
		LocaleList textLocales;
		String fontFamily;
		Typeface fontTypeface;
		boolean fontFamilyExplicit;
		int typefaceIndex = -1;
		int textStyle = 0;
		int fontWeight = -1;
		boolean allCaps, hasAllCaps;
		int shadowColor = 0;
		float shadowDx, shadowDy, shadowRadius;
		boolean hasElegant, elegant;
		boolean hasFallbackLineSpacing, fallbackLineSpacing;
		boolean hasLetterSpacing;
		float letterSpacing;
		String fontFeatureSettings, fontVariationSettings;
	}

	/** AOSP readTextAppearance: `tv` reads the TextView styleable, otherwise TextAppearance */
	private void tlReadTextAppearance(Context context, TypedArray a, TlAppearance at, boolean tv) {
		final int[] idx = tv ? new int[] {
			com.android.internal.R.styleable.TextView_textColorHighlight, com.android.internal.R.styleable.TextView_textColor,
			com.android.internal.R.styleable.TextView_textColorHint, com.android.internal.R.styleable.TextView_textColorLink,
			com.android.internal.R.styleable.TextView_textSize, com.android.internal.R.styleable.TextView_textLocale,
			com.android.internal.R.styleable.TextView_typeface, com.android.internal.R.styleable.TextView_fontFamily,
			com.android.internal.R.styleable.TextView_textStyle, com.android.internal.R.styleable.TextView_textFontWeight,
			com.android.internal.R.styleable.TextView_textAllCaps, com.android.internal.R.styleable.TextView_shadowColor,
			com.android.internal.R.styleable.TextView_shadowDx, com.android.internal.R.styleable.TextView_shadowDy,
			com.android.internal.R.styleable.TextView_shadowRadius, com.android.internal.R.styleable.TextView_elegantTextHeight,
			com.android.internal.R.styleable.TextView_fallbackLineSpacing, com.android.internal.R.styleable.TextView_letterSpacing,
			com.android.internal.R.styleable.TextView_fontFeatureSettings, com.android.internal.R.styleable.TextView_fontVariationSettings,
		} : new int[] {
			com.android.internal.R.styleable.TextAppearance_textColorHighlight, com.android.internal.R.styleable.TextAppearance_textColor,
			com.android.internal.R.styleable.TextAppearance_textColorHint, com.android.internal.R.styleable.TextAppearance_textColorLink,
			com.android.internal.R.styleable.TextAppearance_textSize, com.android.internal.R.styleable.TextAppearance_textLocale,
			com.android.internal.R.styleable.TextAppearance_typeface, com.android.internal.R.styleable.TextAppearance_fontFamily,
			com.android.internal.R.styleable.TextAppearance_textStyle, com.android.internal.R.styleable.TextAppearance_textFontWeight,
			com.android.internal.R.styleable.TextAppearance_textAllCaps, com.android.internal.R.styleable.TextAppearance_shadowColor,
			com.android.internal.R.styleable.TextAppearance_shadowDx, com.android.internal.R.styleable.TextAppearance_shadowDy,
			com.android.internal.R.styleable.TextAppearance_shadowRadius, com.android.internal.R.styleable.TextAppearance_elegantTextHeight,
			com.android.internal.R.styleable.TextAppearance_fallbackLineSpacing, com.android.internal.R.styleable.TextAppearance_letterSpacing,
			com.android.internal.R.styleable.TextAppearance_fontFeatureSettings, com.android.internal.R.styleable.TextAppearance_fontVariationSettings,
		};
		if (a.hasValue(idx[0]))
			at.textColorHighlight = a.getColor(idx[0], at.textColorHighlight);
		if (a.hasValue(idx[1]))
			at.textColor = a.getColorStateList(idx[1]);
		if (a.hasValue(idx[2]))
			at.textColorHint = a.getColorStateList(idx[2]);
		if (a.hasValue(idx[3]))
			at.textColorLink = a.getColorStateList(idx[3]);
		if (a.hasValue(idx[4])) {
			at.textSize = a.getDimensionPixelSize(idx[4], at.textSize);
			at.textSizeUnit = TypedValue.COMPLEX_UNIT_PX;
			TypedValue v = a.peekValue(idx[4]);
			if (v != null && v.type == TypedValue.TYPE_DIMENSION)
				at.textSizeUnit = (v.data >> TypedValue.COMPLEX_UNIT_SHIFT) & TypedValue.COMPLEX_UNIT_MASK;
		}
		if (a.hasValue(idx[5])) {
			final String localeString = a.getString(idx[5]);
			if (localeString != null) {
				final LocaleList localeList = LocaleList.forLanguageTags(localeString);
				if (!localeList.isEmpty())
					at.textLocales = localeList;
			}
		}
		if (a.hasValue(idx[6])) {
			at.typefaceIndex = a.getInt(idx[6], at.typefaceIndex);
			if (at.typefaceIndex != -1 && !at.fontFamilyExplicit)
				at.fontFamily = null;
		}
		if (a.hasValue(idx[7])) {
			try {
				at.fontTypeface = a.getFont(idx[7]);
			} catch (RuntimeException e) {
				/* AOSP: a string family name, not a font resource */
			}
			if (at.fontTypeface == null)
				at.fontFamily = a.getString(idx[7]);
			at.fontFamilyExplicit = true;
		}
		if (a.hasValue(idx[8]))
			at.textStyle = a.getInt(idx[8], at.textStyle);
		if (a.hasValue(idx[9]))
			at.fontWeight = a.getInt(idx[9], at.fontWeight);
		if (a.hasValue(idx[10])) {
			at.allCaps = a.getBoolean(idx[10], at.allCaps);
			at.hasAllCaps = true;
		}
		if (a.hasValue(idx[11]))
			at.shadowColor = a.getInt(idx[11], at.shadowColor);
		if (a.hasValue(idx[12]))
			at.shadowDx = a.getFloat(idx[12], at.shadowDx);
		if (a.hasValue(idx[13]))
			at.shadowDy = a.getFloat(idx[13], at.shadowDy);
		if (a.hasValue(idx[14]))
			at.shadowRadius = a.getFloat(idx[14], at.shadowRadius);
		if (a.hasValue(idx[15])) {
			at.hasElegant = true;
			at.elegant = a.getBoolean(idx[15], at.elegant);
		}
		if (a.hasValue(idx[16])) {
			at.hasFallbackLineSpacing = true;
			at.fallbackLineSpacing = a.getBoolean(idx[16], at.fallbackLineSpacing);
		}
		if (a.hasValue(idx[17])) {
			at.hasLetterSpacing = true;
			at.letterSpacing = a.getFloat(idx[17], at.letterSpacing);
		}
		if (a.hasValue(idx[18]))
			at.fontFeatureSettings = a.getString(idx[18]);
		if (a.hasValue(idx[19]))
			at.fontVariationSettings = a.getString(idx[19]);
	}

	/** AOSP applyTextAppearance */
	private void tlApplyTextAppearance(TlAppearance at) {
		if (at.textColor != null)
			setTextColor(at.textColor);
		if (at.textColorHint != null)
			setHintTextColor(at.textColorHint);
		if (at.textColorLink != null)
			setLinkTextColor(at.textColorLink);
		if (at.textColorHighlight != 0)
			setHighlightColor(at.textColorHighlight);
		if (at.textSize != -1) {
			mTextSizeUnit = at.textSizeUnit;
			setRawTextSize(at.textSize, true);
		}
		if (at.textLocales != null)
			setTextLocales(at.textLocales);
		if (at.typefaceIndex != -1 && !at.fontFamilyExplicit)
			at.fontFamily = null;
		setTypefaceFromAttrs(at.fontTypeface, at.fontFamily, at.typefaceIndex, at.textStyle, at.fontWeight);
		if (at.shadowColor != 0)
			setShadowLayer(at.shadowRadius, at.shadowDx, at.shadowDy, at.shadowColor);
		if (at.hasAllCaps && at.allCaps)
			setAllCaps(true);
		if (at.hasElegant)
			setElegantTextHeight(at.elegant);
		if (at.hasFallbackLineSpacing)
			setFallbackLineSpacing(at.fallbackLineSpacing);
		if (at.hasLetterSpacing)
			setLetterSpacing(at.letterSpacing);
		if (at.fontFeatureSettings != null)
			setFontFeatureSettings(at.fontFeatureSettings);
		if (at.fontVariationSettings != null)
			setFontVariationSettings(at.fontVariationSettings);
	}

	/** AOSP TextView(Context, AttributeSet, int, int): the attribute part */
	private void tlInit(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
		tlInInit = true;
		final android.content.res.Resources res = getResources();
		paint.density = res.getDisplayMetrics().density;
		tlApplyLineConfig();

		final TlAppearance at = new TlAppearance();
		TypedArray a = context.obtainStyledAttributes(attrs, com.android.internal.R.styleable.TextViewAppearance, defStyleAttr, defStyleRes);
		int ap = a.getResourceId(com.android.internal.R.styleable.TextViewAppearance_textAppearance, -1);
		a.recycle();
		if (ap != -1) {
			TypedArray appearance = context.obtainStyledAttributes(ap, com.android.internal.R.styleable.TextAppearance);
			tlReadTextAppearance(context, appearance, at, false);
			appearance.recycle();
		}

		a = context.obtainStyledAttributes(attrs, com.android.internal.R.styleable.TextView, defStyleAttr, defStyleRes);
		tlReadTextAppearance(context, a, at, true);

		CharSequence text = "";
		CharSequence hint = null;
		boolean password = false;
		int inputType = EditorInfo_TYPE_NULL;
		boolean singleLine = false;
		int ellipsize = -1;
		int maxlength = -1;
		int bufferTypeIndex = 0;
		boolean selectallonfocus = false;
		Drawable drawableLeft = null, drawableTop = null, drawableRight = null, drawableBottom = null, drawableStart = null, drawableEnd = null;
		ColorStateList drawableTint = null;
		BlendMode drawableTintMode = null;
		int drawablePadding = 0;
		boolean textIsSetFromXml = false;
		int firstBaselineToTopHeight = -1, lastBaselineToBottomHeight = -1;
		float lineHeight = -1f;
		int lineHeightUnit = -1;
		int autoSizeType = AUTO_SIZE_TEXT_TYPE_NONE;
		float autoSizeMin = UNSET_AUTO_SIZE_UNIFORM_CONFIGURATION_VALUE, autoSizeMax = UNSET_AUTO_SIZE_UNIFORM_CONFIGURATION_VALUE, autoSizeStep = UNSET_AUTO_SIZE_UNIFORM_CONFIGURATION_VALUE;

		final int n = a.getIndexCount();
		for (int i = 0; i < n; i++) {
			final int attr = a.getIndex(i);
			if (attr == com.android.internal.R.styleable.TextView_drawableLeft) {
				drawableLeft = a.getDrawable(attr);
			} else if (attr == com.android.internal.R.styleable.TextView_drawableTop) {
				drawableTop = a.getDrawable(attr);
			} else if (attr == com.android.internal.R.styleable.TextView_drawableRight) {
				drawableRight = a.getDrawable(attr);
			} else if (attr == com.android.internal.R.styleable.TextView_drawableBottom) {
				drawableBottom = a.getDrawable(attr);
			} else if (attr == com.android.internal.R.styleable.TextView_drawableStart) {
				drawableStart = a.getDrawable(attr);
			} else if (attr == com.android.internal.R.styleable.TextView_drawableEnd) {
				drawableEnd = a.getDrawable(attr);
			} else if (attr == com.android.internal.R.styleable.TextView_drawableTint) {
				drawableTint = a.getColorStateList(attr);
			} else if (attr == com.android.internal.R.styleable.TextView_drawableTintMode) {
				drawableTintMode = tlParseBlendMode(a.getInt(attr, -1));
			} else if (attr == com.android.internal.R.styleable.TextView_drawablePadding) {
				drawablePadding = a.getDimensionPixelSize(attr, drawablePadding);
			} else if (attr == com.android.internal.R.styleable.TextView_maxLines) {
				setMaxLines(a.getInt(attr, -1));
			} else if (attr == com.android.internal.R.styleable.TextView_maxHeight) {
				setMaxHeight(a.getDimensionPixelSize(attr, -1));
			} else if (attr == com.android.internal.R.styleable.TextView_lines) {
				setLines(a.getInt(attr, -1));
			} else if (attr == com.android.internal.R.styleable.TextView_height) {
				setHeight(a.getDimensionPixelSize(attr, -1));
			} else if (attr == com.android.internal.R.styleable.TextView_minLines) {
				setMinLines(a.getInt(attr, -1));
			} else if (attr == com.android.internal.R.styleable.TextView_minHeight) {
				setMinHeight(a.getDimensionPixelSize(attr, -1));
			} else if (attr == com.android.internal.R.styleable.TextView_maxEms) {
				setMaxEms(a.getInt(attr, -1));
			} else if (attr == com.android.internal.R.styleable.TextView_maxWidth) {
				setMaxWidth(a.getDimensionPixelSize(attr, -1));
			} else if (attr == com.android.internal.R.styleable.TextView_ems) {
				setEms(a.getInt(attr, -1));
			} else if (attr == com.android.internal.R.styleable.TextView_width) {
				setWidth(a.getDimensionPixelSize(attr, -1));
			} else if (attr == com.android.internal.R.styleable.TextView_minEms) {
				setMinEms(a.getInt(attr, -1));
			} else if (attr == com.android.internal.R.styleable.TextView_minWidth) {
				setMinWidth(a.getDimensionPixelSize(attr, -1));
			} else if (attr == com.android.internal.R.styleable.TextView_gravity) {
				setGravity(a.getInt(attr, -1));
			} else if (attr == com.android.internal.R.styleable.TextView_hint) {
				hint = a.getText(attr);
			} else if (attr == com.android.internal.R.styleable.TextView_text) {
				textIsSetFromXml = true;
				text = a.getText(attr);
			} else if (attr == com.android.internal.R.styleable.TextView_scrollHorizontally) {
				if (a.getBoolean(attr, false))
					setHorizontallyScrolling(true);
			} else if (attr == com.android.internal.R.styleable.TextView_singleLine) {
				singleLine = a.getBoolean(attr, singleLine);
			} else if (attr == com.android.internal.R.styleable.TextView_ellipsize) {
				ellipsize = a.getInt(attr, ellipsize);
			} else if (attr == com.android.internal.R.styleable.TextView_marqueeRepeatLimit) {
				setMarqueeRepeatLimit(a.getInt(attr, mMarqueeRepeatLimit));
			} else if (attr == com.android.internal.R.styleable.TextView_includeFontPadding) {
				if (!a.getBoolean(attr, true))
					setIncludeFontPadding(false);
			} else if (attr == com.android.internal.R.styleable.TextView_cursorVisible) {
				if (!a.getBoolean(attr, true))
					setCursorVisible(false);
			} else if (attr == com.android.internal.R.styleable.TextView_maxLength) {
				maxlength = a.getInt(attr, -1);
			} else if (attr == com.android.internal.R.styleable.TextView_textScaleX) {
				setTextScaleX(a.getFloat(attr, 1.0f));
			} else if (attr == com.android.internal.R.styleable.TextView_freezesText) {
				mFreezesText = a.getBoolean(attr, false);
			} else if (attr == com.android.internal.R.styleable.TextView_enabled) {
				setEnabled(a.getBoolean(attr, isEnabled()));
			} else if (attr == com.android.internal.R.styleable.TextView_password) {
				password = a.getBoolean(attr, password);
			} else if (attr == com.android.internal.R.styleable.TextView_lineSpacingExtra) {
				mSpacingAdd = a.getDimensionPixelSize(attr, (int) mSpacingAdd);
			} else if (attr == com.android.internal.R.styleable.TextView_lineSpacingMultiplier) {
				mSpacingMult = a.getFloat(attr, mSpacingMult);
			} else if (attr == com.android.internal.R.styleable.TextView_inputType) {
				inputType = a.getInt(attr, EditorInfo_TYPE_NULL);
			} else if (attr == com.android.internal.R.styleable.TextView_imeOptions) {
				setImeOptions(a.getInt(attr, tlImeOptions));
			} else if (attr == com.android.internal.R.styleable.TextView_selectAllOnFocus) {
				selectallonfocus = a.getBoolean(attr, selectallonfocus);
			} else if (attr == com.android.internal.R.styleable.TextView_autoLink) {
				mAutoLinkMask = a.getInt(attr, 0);
			} else if (attr == com.android.internal.R.styleable.TextView_linksClickable) {
				mLinksClickable = a.getBoolean(attr, true);
			} else if (attr == com.android.internal.R.styleable.TextView_bufferType) {
				bufferTypeIndex = a.getInt(attr, bufferTypeIndex);
			} else if (attr == com.android.internal.R.styleable.TextView_textIsSelectable) {
				setTextIsSelectable(a.getBoolean(attr, false));
			} else if (attr == com.android.internal.R.styleable.TextView_breakStrategy) {
				setBreakStrategy(a.getInt(attr, Layout.BREAK_STRATEGY_SIMPLE));
			} else if (attr == com.android.internal.R.styleable.TextView_hyphenationFrequency) {
				setHyphenationFrequency(a.getInt(attr, Layout.HYPHENATION_FREQUENCY_NONE));
			} else if (attr == com.android.internal.R.styleable.TextView_autoSizeTextType) {
				autoSizeType = a.getInt(attr, AUTO_SIZE_TEXT_TYPE_NONE);
			} else if (attr == com.android.internal.R.styleable.TextView_autoSizeStepGranularity) {
				autoSizeStep = a.getDimension(attr, UNSET_AUTO_SIZE_UNIFORM_CONFIGURATION_VALUE);
			} else if (attr == com.android.internal.R.styleable.TextView_autoSizeMinTextSize) {
				autoSizeMin = a.getDimension(attr, UNSET_AUTO_SIZE_UNIFORM_CONFIGURATION_VALUE);
			} else if (attr == com.android.internal.R.styleable.TextView_autoSizeMaxTextSize) {
				autoSizeMax = a.getDimension(attr, UNSET_AUTO_SIZE_UNIFORM_CONFIGURATION_VALUE);
			} else if (attr == com.android.internal.R.styleable.TextView_autoSizePresetSizes) {
				final int presetId = a.getResourceId(attr, 0);
				if (presetId > 0) {
					final TypedArray sizes = a.getResources().obtainTypedArray(presetId);
					final int len = sizes.length();
					final int[] parsed = new int[len];
					for (int k = 0; k < len; k++)
						parsed[k] = sizes.getDimensionPixelSize(k, -1);
					sizes.recycle();
					mAutoSizeTextSizesInPx = cleanupAutoSizePresetSizes(parsed);
					setupAutoSizeUniformPresetSizesConfiguration();
				}
			} else if (attr == com.android.internal.R.styleable.TextView_firstBaselineToTopHeight) {
				firstBaselineToTopHeight = a.getDimensionPixelSize(attr, -1);
			} else if (attr == com.android.internal.R.styleable.TextView_lastBaselineToBottomHeight) {
				lastBaselineToBottomHeight = a.getDimensionPixelSize(attr, -1);
			} else if (attr == com.android.internal.R.styleable.TextView_lineHeight) {
				TypedValue v = a.peekValue(attr);
				if (v != null && v.type == TypedValue.TYPE_DIMENSION) {
					lineHeightUnit = (v.data >> TypedValue.COMPLEX_UNIT_SHIFT) & TypedValue.COMPLEX_UNIT_MASK;
					lineHeight = TypedValue.complexToFloat(v.data);
				} else {
					lineHeight = a.getDimensionPixelSize(attr, -1);
				}
			} else if (attr == com.android.internal.R.styleable.TextView_fallbackLineSpacing) {
				setFallbackLineSpacing(a.getBoolean(attr, true));
			} else if (attr == com.android.internal.R.styleable.TextView_lineBreakStyle) {
				setLineBreakStyle(a.getInt(attr, getLineBreakStyle()));
			} else if (attr == com.android.internal.R.styleable.TextView_textCursorDrawable) {
				tlCursorDrawableRes = a.getResourceId(attr, 0);
			} else if (attr == com.android.internal.R.styleable.TextView_lineBreakWordStyle) {
				setLineBreakWordStyle(a.getInt(attr, getLineBreakWordStyle()));
			}
		}
		a.recycle();

		BufferType bufferType = bufferTypeIndex == 1 ? BufferType.SPANNABLE : bufferTypeIndex == 2 ? BufferType.EDITABLE : BufferType.NORMAL;

		if (inputType != EditorInfo_TYPE_NULL) {
			setInputType(inputType);
			singleLine = !isMultilineInputType(inputType);
		} else if (password) {
			setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
		}
		mSelectAllOnFocus = selectallonfocus;

		/* AOSP: a single-line view without a key listener ellipsizes at the end unless told otherwise */
		if (singleLine && getKeyListener() == null && ellipsize == -1 && !(this instanceof EditText))
			ellipsize = 3; /* END */
		if (singleLine)
			setSingleLine(true);
		switch (ellipsize) {
			case 1:
				setEllipsize(TextUtils.TruncateAt.START);
				break;
			case 2:
				setEllipsize(TextUtils.TruncateAt.MIDDLE);
				break;
			case 3:
				setEllipsize(TextUtils.TruncateAt.END);
				break;
			case 4:
				setEllipsize(TextUtils.TruncateAt.MARQUEE);
				break;
		}

		tlApplyTextAppearance(at);

		if (maxlength >= 0)
			setFilters(new InputFilter[] {new InputFilter.LengthFilter(maxlength)});

		if (drawableLeft != null || drawableTop != null || drawableRight != null || drawableBottom != null)
			setCompoundDrawablesWithIntrinsicBounds(drawableLeft, drawableTop, drawableRight, drawableBottom);
		if (drawableStart != null || drawableEnd != null) {
			setCompoundDrawablesRelativeWithIntrinsicBounds(drawableStart,
					drawableTop != null ? drawableTop : tlShowing[DR_TOP],
					drawableEnd,
					drawableBottom != null ? drawableBottom : tlShowing[DR_BOTTOM]);
		}
		if (drawableTint != null)
			setCompoundDrawableTintList(drawableTint);
		if (drawableTintMode != null)
			setCompoundDrawableTintBlendMode(drawableTintMode);
		setCompoundDrawablePadding(drawablePadding);

		if (text == null)
			text = "";
		if (textIsSetFromXml || !(this instanceof EditText))
			setText(text, bufferType);
		if (hint != null)
			setHint(hint);

		if (autoSizeType == AUTO_SIZE_TEXT_TYPE_UNIFORM && supportsAutoSizeText()) {
			if (!mHasPresetAutoSizeValues) {
				final android.util.DisplayMetrics dm = res.getDisplayMetrics();
				if (autoSizeStep == UNSET_AUTO_SIZE_UNIFORM_CONFIGURATION_VALUE)
					autoSizeStep = DEFAULT_AUTO_SIZE_GRANULARITY_IN_PX;
				if (autoSizeMin == UNSET_AUTO_SIZE_UNIFORM_CONFIGURATION_VALUE)
					autoSizeMin = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, DEFAULT_AUTO_SIZE_MIN_TEXT_SIZE_IN_SP, dm);
				if (autoSizeMax == UNSET_AUTO_SIZE_UNIFORM_CONFIGURATION_VALUE)
					autoSizeMax = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, DEFAULT_AUTO_SIZE_MAX_TEXT_SIZE_IN_SP, dm);
				validateAndSetAutoSizeTextTypeUniformConfiguration(autoSizeMin, autoSizeMax, autoSizeStep);
			}
			setupAutoSizeText();
		} else {
			mAutoSizeTextType = AUTO_SIZE_TEXT_TYPE_NONE;
		}

		if (firstBaselineToTopHeight >= 0)
			setFirstBaselineToTopHeight(firstBaselineToTopHeight);
		if (lastBaselineToBottomHeight >= 0)
			setLastBaselineToBottomHeight(lastBaselineToBottomHeight);
		if (lineHeight >= 0) {
			if (lineHeightUnit == -1)
				setLineHeight((int) lineHeight);
			else
				setLineHeight(lineHeightUnit, lineHeight);
		}
		tlInInit = false;
		tlRenderText();
	}

	private static final int EditorInfo_TYPE_NULL = 0;

	private static BlendMode tlParseBlendMode(int value) {
		switch (value) {
			case 3:
				return BlendMode.SRC_OVER;
			case 5:
				return BlendMode.SRC_IN;
			case 9:
				return BlendMode.SRC_ATOP;
			case 14:
				return BlendMode.MODULATE;
			case 15:
				return BlendMode.SCREEN;
			case 16:
				return BlendMode.PLUS;
			default:
				return null;
		}
	}

	/* ---------------------------------------------------------------- text */

	/** AOSP setText(CharSequence, BufferType, boolean notifyBefore, int oldlen) */
	private void tlSetText(CharSequence text, BufferType type) {
		if (text == null)
			text = "";
		for (InputFilter filter : mFilters) {
			CharSequence out = filter.filter(text, 0, text.length(), EMPTY_SPANNED, 0, 0);
			if (out != null)
				text = out;
		}
		final CharSequence oldText = this.text;
		final int oldlen = oldText == null ? 0 : oldText.length();
		sendBeforeTextChanged(oldText, 0, oldlen, text.length());

		final boolean needEditableForNotification = mListeners != null && mListeners.size() != 0;
		if (type == BufferType.EDITABLE || getKeyListener() != null || needEditableForNotification) {
			Editable t = mEditableFactory.newEditable(text);
			text = t;
			setFilters(t, mFilters);
		} else if (type == BufferType.SPANNABLE || mMovement != null) {
			text = mSpannableFactory.newSpannable(text);
		} else {
			text = TextUtils.stringOrSpannedString(text);
		}

		if (mAutoLinkMask != 0) {
			Spannable s2;
			if (type == BufferType.EDITABLE || text instanceof Spannable)
				s2 = (Spannable) text;
			else
				s2 = mSpannableFactory.newSpannable(text);
			if (Linkify.addLinks(s2, mAutoLinkMask)) {
				text = s2;
				type = (type == BufferType.EDITABLE) ? BufferType.EDITABLE : BufferType.SPANNABLE;
				this.text = text;
				if (mLinksClickable && !textCanBeSelected())
					setMovementMethod(LinkMovementMethod.getInstance());
			}
		}

		mBufferType = type;
		if (mChangeWatcher != null && this.text instanceof Spannable)
			((Spannable) this.text).removeSpan(mChangeWatcher);
		this.text = text;
		mTransformed = mTransformation == null ? text : mTransformation.getTransformation(text, this);
		if (mTransformed == null)
			mTransformed = "";

		final int textLength = text.length();
		if (text instanceof Spannable) {
			final Spannable sp = (Spannable) text;
			if (mChangeWatcher == null)
				mChangeWatcher = new TlChangeWatcher();
			sp.setSpan(mChangeWatcher, 0, textLength, Spanned.SPAN_INCLUSIVE_INCLUSIVE | (100 << Spanned.SPAN_PRIORITY_SHIFT));
			if (mTransformation instanceof TextWatcher)
				sp.setSpan(mTransformation, 0, textLength, Spanned.SPAN_INCLUSIVE_INCLUSIVE | (200 << Spanned.SPAN_PRIORITY_SHIFT));
			if (mMovement != null)
				mMovement.initialize(this, (Spannable) text);
		}

		tlShowTextOrHint();
		sendOnTextChanged(text, 0, oldlen, textLength);
		onTextChanged(text, 0, oldlen, textLength);
		if (needEditableForNotification)
			sendAfterTextChanged((Editable) text);
		if (!tlInInit) {
			requestLayout();
			invalidate();
		}
	}

	/** AOSP: a filter set on an Editable buffer applies to its own edits too */
	private void setFilters(Editable e, InputFilter[] filters) {
		e.setFilters(filters);
	}

	/** AOSP (hidden): the view's own text, re-shown after an edit of an Editable/Spannable buffer */
	private void tlTextChangedInPlace() {
		mTransformed = mTransformation == null ? text : mTransformation.getTransformation(text, this);
		if (mTransformed == null)
			mTransformed = "";
		tlShowTextOrHint();
		requestLayout();
		invalidate();
	}

	/** AOSP ChangeWatcher: edits of the view's Editable reach the watchers and the display */
	private final class TlChangeWatcher implements TextWatcher, SpanWatcher, android.text.NoCopySpan {
		@Override
		public void beforeTextChanged(CharSequence buffer, int start, int before, int after) {
			sendBeforeTextChanged(buffer, start, before, after);
		}

		@Override
		public void onTextChanged(CharSequence buffer, int start, int before, int after) {
			TextView.this.tlTextChangedInPlace();
			sendOnTextChanged(buffer, start, before, after);
			TextView.this.onTextChanged(buffer, start, before, after);
		}

		@Override
		public void afterTextChanged(Editable buffer) {
			sendAfterTextChanged(buffer);
		}

		@Override
		public void onSpanChanged(Spannable buf, Object what, int s, int e, int st, int en) {
			if (what instanceof CharacterStyle)
				TextView.this.tlRenderText();
		}

		@Override
		public void onSpanAdded(Spannable buf, Object what, int s, int e) {
			if (what instanceof CharacterStyle)
				TextView.this.tlRenderText();
		}

		@Override
		public void onSpanRemoved(Spannable buf, Object what, int s, int e) {
			if (what instanceof CharacterStyle)
				TextView.this.tlRenderText();
		}
	}

	private void sendBeforeTextChanged(CharSequence text, int start, int before, int after) {
		if (mListeners != null) {
			final ArrayList<TextWatcher> list = mListeners;
			final int count = list.size();
			for (int i = 0; i < count; i++)
				list.get(i).beforeTextChanged(text, start, before, after);
		}
	}

	private void sendOnTextChanged(CharSequence text, int start, int before, int after) {
		if (mListeners != null) {
			final ArrayList<TextWatcher> list = mListeners;
			final int count = list.size();
			for (int i = 0; i < count; i++)
				list.get(i).onTextChanged(text, start, before, after);
		}
	}

	private void sendAfterTextChanged(Editable text) {
		if (mListeners != null) {
			final ArrayList<TextWatcher> list = mListeners;
			final int count = list.size();
			for (int i = 0; i < count; i++)
				list.get(i).afterTextChanged(text);
		}
	}

	/** AOSP: called when the text changed; the default does nothing */
	protected void onTextChanged(CharSequence text, int start, int lengthBefore, int lengthAfter) {
	}

	/** AOSP: called when the selection changed; the default does nothing */
	protected void onSelectionChanged(int selStart, int selEnd) {
	}

	public void addTextChangedListener(TextWatcher watcher) {
		if (mListeners == null)
			mListeners = new ArrayList<TextWatcher>();
		mListeners.add(watcher);
	}

	public void removeTextChangedListener(TextWatcher watcher) {
		if (mListeners != null) {
			int i = mListeners.indexOf(watcher);
			if (i >= 0)
				mListeners.remove(i);
		}
	}

	public void setText(CharSequence text, BufferType type, boolean notifyBefore, int oldlen) {
		setText(text, type);
	}

	public final void setText(int resid, BufferType type) {
		setText(getContext().getResources().getText(resid), type);
	}

	public final void setTextKeepState(CharSequence text) {
		setTextKeepState(text, mBufferType);
	}

	public final void setTextKeepState(CharSequence text, BufferType type) {
		int start = getSelectionStart();
		int end = getSelectionEnd();
		int len = text.length();
		setText(text, type);
		if ((start >= 0 || end >= 0) && this.text instanceof Spannable)
			Selection.setSelection((Spannable) this.text, Math.max(0, Math.min(start, len)), Math.max(0, Math.min(end, len)));
	}

	public final void setEditableFactory(Editable.Factory factory) {
		mEditableFactory = factory;
		setText(text);
	}

	public final void setSpannableFactory(Spannable.Factory factory) {
		mSpannableFactory = factory;
		setText(text);
	}

	public Editable getEditableText() {
		return (text instanceof Editable) ? (Editable) text : null;
	}

	public CharSequence getTransformed() {
		return mTransformed;
	}

	/** AOSP: the text filters; the length filter of an EditText also bounds its GtkText */
	public void setFilters(InputFilter[] filters) {
		if (filters == null)
			throw new IllegalArgumentException();
		mFilters = filters;
		if (text instanceof Editable)
			setFilters((Editable) text, filters);
		int max = 0;
		for (InputFilter f : filters) {
			if (f instanceof InputFilter.LengthFilter)
				max = max == 0 ? ((InputFilter.LengthFilter) f).getMax() : Math.min(max, ((InputFilter.LengthFilter) f).getMax());
		}
		if (widget != 0)
			native_setMaxLength(widget, max);
	}

	public InputFilter[] getFilters() {
		return mFilters;
	}

	public final void setTransformationMethod(TransformationMethod method) {
		if (method == mTransformation)
			return;
		if (mTransformation != null && text instanceof Spannable)
			((Spannable) text).removeSpan(mTransformation);
		mTransformation = method;
		if (widget != 0)
			native_setTextVisibility(widget, !(method instanceof PasswordTransformationMethod));
		if (method instanceof android.text.method.TransformationMethod2)
			((android.text.method.TransformationMethod2) method).setLengthChangesAllowed(!(this instanceof EditText));
		setText(text);
	}

	public final TransformationMethod getTransformationMethod() {
		return mTransformation;
	}

	public void setKeyListener(KeyListener input) {
		tlKeyListener = input;
		fixFocusableAndClickableSettings();
	}

	public KeyListener getKeyListener() {
		return tlKeyListener;
	}

	public final void setMovementMethod(MovementMethod movement) {
		if (mMovement != movement) {
			mMovement = movement;
			if (movement != null && !(text instanceof Spannable))
				setText(text);
			fixFocusableAndClickableSettings();
		}
	}

	public final MovementMethod getMovementMethod() {
		return mMovement;
	}

	private void fixFocusableAndClickableSettings() {
		if (mMovement != null || tlKeyListener != null) {
			setFocusable(true);
			setClickable(true);
			setLongClickable(true);
		}
	}

	public final void setLinksClickable(boolean whether) {
		mLinksClickable = whether;
	}

	public final boolean getLinksClickable() {
		return mLinksClickable;
	}

	public final void setAutoLinkMask(int mask) {
		mAutoLinkMask = mask;
	}

	public final int getAutoLinkMask() {
		return mAutoLinkMask;
	}

	public URLSpan[] getUrls() {
		if (text instanceof Spanned)
			return ((Spanned) text).getSpans(0, text.length(), URLSpan.class);
		return new URLSpan[0];
	}

	public void setTextIsSelectable(boolean selectable) {
		if (mTextIsSelectable == selectable)
			return;
		mTextIsSelectable = selectable;
		setFocusable(selectable);
		setClickable(selectable);
		setLongClickable(selectable);
		setText(text, selectable ? BufferType.SPANNABLE : BufferType.NORMAL);
	}

	public boolean isTextSelectable() {
		return mTextIsSelectable;
	}

	boolean textCanBeSelected() {
		return mTextIsSelectable || this instanceof EditText;
	}

	public int getSelectionStart() {
		return Selection.getSelectionStart(getText());
	}

	public int getSelectionEnd() {
		return Selection.getSelectionEnd(getText());
	}

	public boolean hasSelection() {
		final int start = getSelectionStart();
		final int end = getSelectionEnd();
		return start >= 0 && end > 0 && start != end;
	}

	public void setSelection(int start, int stop) {
		if (text instanceof Spannable)
			Selection.setSelection((Spannable) text, start, stop);
	}

	public void setSelection(int index) {
		setSelection(index, index);
	}

	public void setSelectAllOnFocus(boolean selectAllOnFocus) {
		mSelectAllOnFocus = selectAllOnFocus;
		if (selectAllOnFocus && !(text instanceof Spannable))
			setText(text, BufferType.SPANNABLE);
	}

	public void setCursorVisible(boolean visible) {
		mCursorVisible = visible;
		if (widget != 0)
			native_setCursorVisible(widget, visible);
	}

	public boolean isCursorVisible() {
		return mCursorVisible;
	}

	public void setShowSoftInputOnFocus(boolean show) {
		mShowSoftInputOnFocus = show;
	}

	public final boolean getShowSoftInputOnFocus() {
		return mShowSoftInputOnFocus;
	}

	public void setFreezesText(boolean freezesText) {
		mFreezesText = freezesText;
	}

	public boolean getFreezesText() {
		return mFreezesText;
	}

	/** AOSP: the text's selection action mode callback. GTK's own text selection UI runs instead of
	 * AOSP's floating toolbar here, so the callback is kept and reported but never started. */
	public void setCustomSelectionActionModeCallback(ActionMode.Callback actionModeCallback) {
		mCustomSelectionActionModeCallback = actionModeCallback;
	}

	public ActionMode.Callback getCustomSelectionActionModeCallback() {
		return mCustomSelectionActionModeCallback;
	}

	public void setCustomInsertionActionModeCallback(ActionMode.Callback actionModeCallback) {
		mCustomInsertionActionModeCallback = actionModeCallback;
	}

	public ActionMode.Callback getCustomInsertionActionModeCallback() {
		return mCustomInsertionActionModeCallback;
	}

	/** AOSP: nested batch edits; the outermost begin/end reach onBeginBatchEdit/onEndBatchEdit */
	public void beginBatchEdit() {
		if (++mBatchEditNesting == 1)
			onBeginBatchEdit();
	}

	public void endBatchEdit() {
		if (mBatchEditNesting > 0 && --mBatchEditNesting == 0)
			onEndBatchEdit();
	}

	public void onBeginBatchEdit() {
	}

	public void onEndBatchEdit() {
	}

	/** AOSP: a TextView without an editor has no composing text to clear */
	public void clearComposingText() {
		if (text instanceof Spannable)
			android.view.inputmethod.BaseInputConnection.removeComposingSpans((Spannable) text);
	}

	private Bundle tlInputExtras;

	public Bundle getInputExtras(boolean create) {
		if (tlInputExtras == null && create)
			tlInputExtras = new Bundle();
		return tlInputExtras;
	}

	/** AOSP: the input type; a password variation selects the password transformation and a monospace face */
	public void setInputType(int type) {
		final boolean wasPassword = isPasswordInputType(getInputType());
		final boolean wasVisiblePassword = isVisiblePasswordInputType(getInputType());
		tlInputType = type;
		final boolean isPassword = isPasswordInputType(type);
		final boolean isVisiblePassword = isVisiblePasswordInputType(type);
		boolean forceUpdate = false;
		if (isPassword) {
			setTransformationMethod(PasswordTransformationMethod.getInstance());
			setTypefaceFromAttrs(null, null, MONOSPACE, Typeface.NORMAL, -1);
		} else if (isVisiblePassword) {
			if (mTransformation == PasswordTransformationMethod.getInstance())
				forceUpdate = true;
			setTypefaceFromAttrs(null, null, MONOSPACE, Typeface.NORMAL, -1);
		} else if (wasPassword || wasVisiblePassword) {
			setTypefaceFromAttrs(null, null, -1, Typeface.NORMAL, -1);
			if (mTransformation == PasswordTransformationMethod.getInstance())
				forceUpdate = true;
		}
		boolean singleLine = !isMultilineInputType(type);
		if (mSingleLine != singleLine || forceUpdate)
			applySingleLine(singleLine, !isPassword, true);
	}

	public void setRawInputType(int type) {
		tlInputType = type;
	}

	private static boolean isMultilineInputType(int type) {
		return (type & (android.text.InputType.TYPE_MASK_CLASS | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE))
			== (android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
	}

	private static boolean isPasswordInputType(int inputType) {
		final int variation = inputType & (android.text.InputType.TYPE_MASK_CLASS | android.text.InputType.TYPE_MASK_VARIATION);
		return variation == (android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD)
			|| variation == (android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
			|| variation == (android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
	}

	private static boolean isVisiblePasswordInputType(int inputType) {
		final int variation = inputType & (android.text.InputType.TYPE_MASK_CLASS | android.text.InputType.TYPE_MASK_VARIATION);
		return variation == (android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
	}

	/* ---------------------------------------------------------------- what the label shows */

	private final android.text.TlTextAttrs tlAttrs = new android.text.TlTextAttrs();


	/** AOSP: the text colour state list for links (ClickableSpan draws in it) */
	public final void setLinkTextColor(int color) {
		setLinkTextColor(ColorStateList.valueOf(color));
	}

	public final void setLinkTextColor(ColorStateList colors) {
		tlLinkTextColor = colors;
		tlLinkColorResolved = false;
		tlRenderText();
	}

	public final ColorStateList getLinkTextColors() {
		return tlLinkTextColor;
	}

	private int tlLinkColor() {
		if (!tlLinkColorResolved) {
			ColorStateList c = tlLinkTextColor;
			if (c == null) {
				try {
					final TypedArray ta = getContext().obtainStyledAttributes(new int[] {android.R.attr.textColorLink});
					c = ta.getColorStateList(0);
					ta.recycle();
				} catch (Exception e) {
				}
			}
			tlCurLinkColor = c != null ? c.getColorForState(getDrawableState(), c.getDefaultColor()) : 0;
			tlLinkColorResolved = true;
		}
		return tlCurLinkColor;
	}

	/** builds the label's text and Pango attributes from the paint and the spans */
	private void tlRenderText() {
		tlTextGen++;
		if (widget == 0 || tlInInit || tlAttrs == null)
			return;
		final android.text.TlTextAttrs at = tlAttrs;
		final boolean editor = this instanceof EditText;
		final CharSequence shown = editor ? null : tlShown;
		at.reset();
		final float size = tlTextSize > 0 ? tlTextSize : paint.getTextSize();
		if (tlTextSize > 0)
			at.addFloat(android.text.TlTextAttrs.A_SIZE, 0, -1, tlTextSize);
		final int flags = paint.getFlags();
		final android.text.TlTextAttrs.Face base = android.text.TlTextAttrs.Face.of(paint.getTypeface(), tlFakeBold || (flags & Paint.FAKE_BOLD_TEXT_FLAG) != 0, tlSkewX);
		at.face(base, null, 0, -1);
		final float ls = paint.getLetterSpacing();
		if (ls != 0 && size > 0)
			at.addFloat(android.text.TlTextAttrs.A_LETTERSP, 0, -1, ls * size);
		if ((flags & Paint.UNDERLINE_TEXT_FLAG) != 0)
			at.add(android.text.TlTextAttrs.A_UNDERLINE, 0, -1, 1);
		if ((flags & Paint.STRIKE_THRU_TEXT_FLAG) != 0)
			at.add(android.text.TlTextAttrs.A_STRIKE, 0, -1, 1);
		if (mSpacingMult != 1.0f || mSpacingAdd != 0.0f)
			at.addFloat(android.text.TlTextAttrs.A_LINEHEIGHT, 0, -1, Math.max(1, getLineHeight()));
		if (tlFontFeatures != null && !tlFontFeatures.isEmpty())
			at.addString(android.text.TlTextAttrs.A_FEATURES, 0, -1, tlFontFeatures);
		if (tlFontVariations != null && !tlFontVariations.isEmpty())
			at.addString(android.text.TlTextAttrs.A_VARIATIONS, 0, -1, tlFontVariations);
		if (tlTextLocales != null && !tlTextLocales.isEmpty())
			at.addString(android.text.TlTextAttrs.A_LANGUAGE, 0, -1, tlTextLocales.get(0).toLanguageTag());
		if (shown instanceof Spanned)
			at.spans((Spanned) shown, base, paint.getTypeface(), size, getCurrentTextColor(), tlLinkColor(),
				ls, paint.density, getDrawableState());
		if (shown instanceof Spanned)
			at.lineHeights((Spanned) shown, paint);
		native_setStyledText(widget, shown == null ? null : shown.toString(), at.attrs(), at.strings());
	}

	/* ---------------------------------------------------------------- text size and face */

	public void setTextSize(float size) {
		setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
	}

	public void setTextSize(int unit, float size) {
		if (!isAutoSizeEnabled())
			setTextSizeInternal(unit, size, true);
	}

	private void setTextSizeInternal(int unit, float size, boolean shouldRequestLayout) {
		final Context c = getContext();
		final android.content.res.Resources r = c == null ? android.content.res.Resources.getSystem() : c.getResources();
		mTextSizeUnit = unit;
		setRawTextSize(TypedValue.applyDimension(unit, size, r.getDisplayMetrics()), shouldRequestLayout);
	}

	private void setRawTextSize(float size, boolean shouldRequestLayout) {
		if (size != tlTextSize) {
			tlTextSize = size;
			paint.setTextSize(size);
			tlRenderText();
			if (shouldRequestLayout && !tlInInit)
				requestLayout();
			invalidate();
		}
	}

	public float getTextSize() {
		return tlTextSize > 0 ? tlTextSize : paint.getTextSize();
	}

	public int getTextSizeUnit() {
		return mTextSizeUnit;
	}

	public float getScaledTextSize() {
		return getTextSize() / paint.density;
	}

	public void setTypeface(Typeface tf) {
		if (paint.getTypeface() != tf) {
			paint.setTypeface(tf);
			tlRenderText();
			if (!tlInInit)
				requestLayout();
			invalidate();
		}
	}

	/** AOSP: a typeface in a style; what the face lacks is synthesized (fake bold, skew) */
	public void setTypeface(Typeface tf, int style) {
		if (style > 0) {
			if (tf == null)
				tf = Typeface.defaultFromStyle(style);
			else
				tf = Typeface.create(tf, style);
			final int typefaceStyle = tf != null ? tf.getStyle() : 0;
			final int need = style & ~typefaceStyle;
			tlFakeBold = (need & Typeface.BOLD) != 0;
			tlSkewX = (need & Typeface.ITALIC) != 0 ? -0.25f : 0;
			setTypeface(tf);
		} else {
			tlFakeBold = false;
			tlSkewX = 0;
			setTypeface(tf);
		}
		tlRenderText();
	}

	public Typeface getTypeface() {
		return paint.getTypeface();
	}

	private void setTypefaceFromAttrs(Typeface typeface, String familyName, int typefaceIndex, int style, int weight) {
		if (typeface == null && familyName != null) {
			resolveStyleAndSetTypeface(Typeface.create(familyName, Typeface.NORMAL), style, weight);
		} else if (typeface != null) {
			resolveStyleAndSetTypeface(typeface, style, weight);
		} else {
			switch (typefaceIndex) {
				case SANS:
					resolveStyleAndSetTypeface(Typeface.SANS_SERIF, style, weight);
					break;
				case SERIF:
					resolveStyleAndSetTypeface(Typeface.SERIF, style, weight);
					break;
				case MONOSPACE:
					resolveStyleAndSetTypeface(Typeface.MONOSPACE, style, weight);
					break;
				default:
					resolveStyleAndSetTypeface(null, style, weight);
					break;
			}
		}
	}

	private void resolveStyleAndSetTypeface(Typeface typeface, int style, int weight) {
		if (weight >= 0) {
			weight = Math.min(1000, weight);
			final boolean italic = (style & Typeface.ITALIC) != 0;
			setTypeface(Typeface.create(typeface, weight, italic));
		} else {
			setTypeface(typeface, style);
		}
	}

	public void setPaintFlags(int flags) {
		if (paint.getFlags() != flags) {
			paint.setFlags(flags);
			tlRenderText();
			if (!tlInInit)
				requestLayout();
			invalidate();
		}
	}

	public int getPaintFlags() {
		return paint.getFlags();
	}

	public void setLetterSpacing(float letterSpacing) {
		if (letterSpacing != paint.getLetterSpacing()) {
			paint.setLetterSpacing(letterSpacing);
			tlRenderText();
			if (!tlInInit)
				requestLayout();
			invalidate();
		}
	}

	public float getLetterSpacing() {
		return paint.getLetterSpacing();
	}

	public void setFontFeatureSettings(String fontFeatureSettings) {
		if (fontFeatureSettings != tlFontFeatures && (fontFeatureSettings == null || !fontFeatureSettings.equals(tlFontFeatures))) {
			tlFontFeatures = fontFeatureSettings;
			paint.setFontFeatureSettings(fontFeatureSettings);
			tlRenderText();
			if (!tlInInit)
				requestLayout();
			invalidate();
		}
	}

	public String getFontFeatureSettings() {
		return tlFontFeatures;
	}

	/** AOSP: OpenType variation settings ("'wght' 700, 'wdth' 80"), handed to Pango as "wght=700,wdth=80" */
	public boolean setFontVariationSettings(String fontVariationSettings) {
		String pango = null;
		if (fontVariationSettings != null && !fontVariationSettings.isEmpty()) {
			StringBuilder sb = new StringBuilder();
			for (String part : fontVariationSettings.split(",")) {
				String p = part.trim();
				if (p.isEmpty())
					continue;
				int q1 = p.indexOf('\'') >= 0 ? p.indexOf('\'') : p.indexOf('"');
				int q2 = q1 >= 0 ? p.indexOf(p.charAt(q1), q1 + 1) : -1;
				if (q1 < 0 || q2 != q1 + 5)
					throw new IllegalArgumentException("Invalid font variation settings: " + fontVariationSettings);
				String tag = p.substring(q1 + 1, q2);
				float value;
				try {
					value = Float.parseFloat(p.substring(q2 + 1).trim());
				} catch (NumberFormatException e) {
					throw new IllegalArgumentException("Invalid font variation settings: " + fontVariationSettings);
				}
				if (sb.length() > 0)
					sb.append(',');
				sb.append(tag).append('=').append(value);
			}
			pango = sb.toString();
		}
		final boolean changed = pango == null ? tlFontVariations != null : !pango.equals(tlFontVariations);
		tlFontVariations = pango;
		tlFontVariationSettingsRaw = fontVariationSettings;
		if (changed) {
			tlRenderText();
			if (!tlInInit)
				requestLayout();
			invalidate();
		}
		return true;
	}

	private String tlFontVariationSettingsRaw;

	public String getFontVariationSettings() {
		return tlFontVariationSettingsRaw;
	}

	public void setTextScaleX(float size) {
		if (size != paint.getTextScaleX()) {
			paint.setTextScaleX(size);
			if (!tlInInit)
				requestLayout();
			invalidate();
		}
	}

	public float getTextScaleX() {
		return paint.getTextScaleX();
	}

	public void setTextLocales(LocaleList locales) {
		if (locales == null || locales.isEmpty())
			throw new IllegalArgumentException("locales cannot be null or empty");
		tlTextLocales = locales;
		paint.setTextLocales(locales);
		tlRenderText();
		if (!tlInInit)
			requestLayout();
		invalidate();
	}

	public void setTextLocale(Locale locale) {
		setTextLocales(new LocaleList(locale));
	}

	public LocaleList getTextLocales() {
		return tlTextLocales != null ? tlTextLocales : LocaleList.getDefault();
	}

	public void setShadowLayer(float radius, float dx, float dy, int color) {
		paint.setShadowLayer(radius, dx, dy, color);
		mShadowRadius = radius;
		mShadowDx = dx;
		mShadowDy = dy;
		mShadowColor = color;
		if (widget != 0)
			native_setShadow(widget, radius, dx, dy, color);
		invalidate();
	}

	public float getShadowRadius() {
		return mShadowRadius;
	}

	public float getShadowDx() {
		return mShadowDx;
	}

	public float getShadowDy() {
		return mShadowDy;
	}

	public int getShadowColor() {
		return mShadowColor;
	}

	/* ---------------------------------------------------------------- appearance */

	/** AOSP (deprecated): setTextAppearance(int) */
	public void setTextAppearance(Context context, int resId) {
		setTextAppearance(resId);
	}

	public void setTextAppearance(int resId) {
		final TypedArray ta = getContext().obtainStyledAttributes(resId, com.android.internal.R.styleable.TextAppearance);
		final TlAppearance at = new TlAppearance();
		tlReadTextAppearance(getContext(), ta, at, false);
		ta.recycle();
		tlApplyTextAppearance(at);
	}

	/* ---------------------------------------------------------------- line spacing */

	public void setLineSpacing(float add, float mult) {
		if (mSpacingAdd != add || mSpacingMult != mult) {
			mSpacingAdd = add;
			mSpacingMult = mult;
			tlRenderText();
			if (!tlInInit)
				requestLayout();
			invalidate();
		}
	}

	public float getLineSpacingMultiplier() {
		return mSpacingMult;
	}

	public float getLineSpacingExtra() {
		return mSpacingAdd;
	}

	public int getLineHeight() {
		return Math.round(paint.getFontMetricsInt(null) * mSpacingMult + mSpacingAdd);
	}

	public void setLineHeight(int lineHeight) {
		if (lineHeight < 0)
			throw new IllegalArgumentException();
		final int fontHeight = getPaint().getFontMetricsInt(null);
		if (lineHeight != fontHeight)
			setLineSpacing(lineHeight - fontHeight, 1f);
	}

	public void setLineHeight(int unit, float lineHeight) {
		setLineHeight(Math.round(TypedValue.applyDimension(unit, lineHeight, getResources().getDisplayMetrics())));
	}

	public void setFirstBaselineToTopHeight(int firstBaselineToTopHeight) {
		if (firstBaselineToTopHeight < 0)
			throw new IllegalArgumentException();
		final Paint.FontMetricsInt fontMetrics = new Paint.FontMetricsInt();
		getPaint().getFontMetricsInt(fontMetrics);
		final int fontMetricsTop = getIncludeFontPadding() ? fontMetrics.top : fontMetrics.ascent;
		if (firstBaselineToTopHeight > Math.abs(fontMetricsTop)) {
			final int paddingTop = firstBaselineToTopHeight - (-fontMetricsTop);
			setPadding(getPaddingLeft(), paddingTop, getPaddingRight(), getPaddingBottom());
		}
	}

	public void setLastBaselineToBottomHeight(int lastBaselineToBottomHeight) {
		if (lastBaselineToBottomHeight < 0)
			throw new IllegalArgumentException();
		final Paint.FontMetricsInt fontMetrics = new Paint.FontMetricsInt();
		getPaint().getFontMetricsInt(fontMetrics);
		final int fontMetricsBottom = getIncludeFontPadding() ? fontMetrics.bottom : fontMetrics.descent;
		if (lastBaselineToBottomHeight > Math.abs(fontMetricsBottom)) {
			final int paddingBottom = lastBaselineToBottomHeight - fontMetricsBottom;
			setPadding(getPaddingLeft(), getPaddingTop(), getPaddingRight(), paddingBottom);
		}
	}

	public int getFirstBaselineToTopHeight() {
		final Paint.FontMetricsInt fm = new Paint.FontMetricsInt();
		getPaint().getFontMetricsInt(fm);
		return getPaddingTop() - fm.top;
	}

	public int getLastBaselineToBottomHeight() {
		final Paint.FontMetricsInt fm = new Paint.FontMetricsInt();
		getPaint().getFontMetricsInt(fm);
		return getPaddingBottom() + fm.bottom;
	}

	/** AOSP: the font's extra top/bottom padding. Pango sizes lines by ascent and descent of the
	 * fonts used, so the label has no such padding to add or leave out; the flag is kept and reported. */
	public void setIncludeFontPadding(boolean includepad) {
		if (mIncludePad != includepad) {
			mIncludePad = includepad;
			if (!tlInInit)
				requestLayout();
			invalidate();
		}
	}

	public boolean getIncludeFontPadding() {
		return mIncludePad;
	}

	/* ---------------------------------------------------------------- lines */

	public void setSingleLine() {
		setSingleLine(true);
	}

	public void setSingleLine(boolean singleLine) {
		setInputTypeSingleLine(singleLine);
		applySingleLine(singleLine, true, true);
	}

	private void setInputTypeSingleLine(boolean singleLine) {
		if ((tlInputType & android.text.InputType.TYPE_MASK_CLASS) == android.text.InputType.TYPE_CLASS_TEXT) {
			if (singleLine)
				tlInputType &= ~android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE;
			else
				tlInputType |= android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE;
		}
	}

	/** AOSP applySingleLine: one line, no wrapping, newlines shown as spaces */
	private void applySingleLine(boolean singleLine, boolean applyTransformation, boolean changeMaxLines) {
		mSingleLine = singleLine;
		if (singleLine) {
			setLines(1);
			setHorizontallyScrolling(true);
			if (applyTransformation && !(this instanceof EditText))
				setTransformationMethod(SingleLineTransformationMethod.getInstance());
		} else {
			if (changeMaxLines)
				setMaxLines(Integer.MAX_VALUE);
			setHorizontallyScrolling(false);
			if (applyTransformation && mTransformation instanceof SingleLineTransformationMethod)
				setTransformationMethod(null);
		}
		tlApplyLineConfig();
	}

	public boolean isSingleLine() {
		return mSingleLine;
	}

	public void setHorizontallyScrolling(boolean whether) {
		if (mHorizontallyScrolling != whether) {
			mHorizontallyScrolling = whether;
			tlApplyLineConfig();
			if (!tlInInit)
				requestLayout();
			invalidate();
		}
	}

	public final boolean isHorizontallyScrollable() {
		return mHorizontallyScrolling;
	}

	public boolean getHorizontallyScrolling() {
		return mHorizontallyScrolling;
	}

	public void setMinLines(int minLines) {
		mMinimum = minLines;
		mMinMode = LINES;
		if (!tlInInit)
			requestLayout();
		invalidate();
	}

	public int getMinLines() {
		return mMinMode == LINES ? mMinimum : -1;
	}

	public void setMinHeight(int minPixels) {
		mMinimum = minPixels;
		mMinMode = PIXELS;
		if (!tlInInit)
			requestLayout();
		invalidate();
	}

	public int getMinHeight() {
		return mMinMode == PIXELS ? mMinimum : -1;
	}

	public void setMaxLines(int maxLines) {
		mMaximum = maxLines;
		mMaxMode = LINES;
		tlApplyLineConfig();
		if (!tlInInit)
			requestLayout();
		invalidate();
	}

	public int getMaxLines() {
		return mMaxMode == LINES ? mMaximum : -1;
	}

	public void setMaxHeight(int maxPixels) {
		mMaximum = maxPixels;
		mMaxMode = PIXELS;
		tlApplyLineConfig();
		if (!tlInInit)
			requestLayout();
		invalidate();
	}

	public int getMaxHeight() {
		return mMaxMode == PIXELS ? mMaximum : -1;
	}

	public void setLines(int lines) {
		mMaximum = mMinimum = lines;
		mMaxMode = mMinMode = LINES;
		tlApplyLineConfig();
		if (!tlInInit)
			requestLayout();
		invalidate();
	}

	public void setHeight(int pixels) {
		mMaximum = mMinimum = pixels;
		mMaxMode = mMinMode = PIXELS;
		tlApplyLineConfig();
		if (!tlInInit)
			requestLayout();
		invalidate();
	}

	public void setMinEms(int minEms) {
		mMinWidth = minEms;
		mMinWidthMode = EMS;
		if (!tlInInit)
			requestLayout();
		invalidate();
	}

	public int getMinEms() {
		return mMinWidthMode == EMS ? mMinWidth : -1;
	}

	public void setMinWidth(int minPixels) {
		mMinWidth = minPixels;
		mMinWidthMode = PIXELS;
		if (!tlInInit)
			requestLayout();
		invalidate();
	}

	public int getMinWidth() {
		return mMinWidthMode == PIXELS ? mMinWidth : -1;
	}

	public void setMaxEms(int maxEms) {
		mMaxWidth = maxEms;
		mMaxWidthMode = EMS;
		if (!tlInInit)
			requestLayout();
		invalidate();
	}

	public int getMaxEms() {
		return mMaxWidthMode == EMS ? mMaxWidth : -1;
	}

	public void setMaxWidth(int maxPixels) {
		mMaxWidth = maxPixels;
		mMaxWidthMode = PIXELS;
		if (!tlInInit)
			requestLayout();
		invalidate();
	}

	public int getMaxWidth() {
		return mMaxWidthMode == PIXELS ? mMaxWidth : -1;
	}

	public void setEms(int ems) {
		mMaxWidth = mMinWidth = ems;
		mMaxWidthMode = mMinWidthMode = EMS;
		if (!tlInInit)
			requestLayout();
		invalidate();
	}

	public void setWidth(int pixels) {
		mMaxWidth = mMinWidth = pixels;
		mMaxWidthMode = mMinWidthMode = PIXELS;
		if (!tlInInit)
			requestLayout();
		invalidate();
	}

	public void setEllipsize(TextUtils.TruncateAt where) {
		if (mEllipsize != where) {
			mEllipsize = where;
			tlApplyLineConfig();
			if (!tlInInit)
				requestLayout();
			invalidate();
		}
	}

	public TextUtils.TruncateAt getEllipsize() {
		return mEllipsize;
	}

	/** AOSP: the number of marquee repetitions. A marquee shows as an end ellipsis here, as it does
	 * on AOSP phones (MARQUEE_FADE_SWITCH_SHOW_ELLIPSIS) whenever the marquee is not running. */
	public void setMarqueeRepeatLimit(int marqueeLimit) {
		mMarqueeRepeatLimit = marqueeLimit;
	}

	public int getMarqueeRepeatLimit() {
		return mMarqueeRepeatLimit;
	}

	/** the label's wrap/lines/ellipsis for the current single-line, max-lines and ellipsize state */
	private void tlApplyLineConfig() {
		if (widget == 0 || tlShowing == null)
			return;
		int pangoEllipsize = 0; /* PANGO_ELLIPSIZE_NONE */
		final boolean single = mSingleLine || mHorizontallyScrolling;
		final boolean linesLimited = single || (mMaxMode == LINES && mMaximum != Integer.MAX_VALUE && mMaximum > 0);
		if (mEllipsize != null && linesLimited) {
			final boolean oneLine = single || mMaximum == 1;
			switch (mEllipsize) {
				case START:
					pangoEllipsize = oneLine ? 1 : 0; /* AOSP: START and MIDDLE need a single line */
					break;
				case MIDDLE:
					pangoEllipsize = oneLine ? 2 : 0;
					break;
				default:
					pangoEllipsize = 3; /* END, END_SMALL, MARQUEE */
					break;
			}
		}
		native_setLineConfig(widget, mMaxMode == LINES ? mMaximum : -1, pangoEllipsize, single);
		tlTextGen++;
	}

	/* ---------------------------------------------------------------- layout and measuring */

	/** AOSP: the text's Layout; here the layout of what the label shows (its PangoLayout) */
	public Layout getLayout() {
		if (widget == 0)
			return null;
		final int w = getWidth();
		if (tlLayout != null && tlLayoutGen == tlTextGen && tlLayoutWidth == w)
			return tlLayout;
		final long pango = native_getLayout(widget);
		if (pango == 0)
			return null;
		tlLayout = Layout.tlWrapNative(pango, (this instanceof EditText) ? getText() : tlShown, paint, getLayoutAlignment(), mSpacingMult, mSpacingAdd);
		tlLayoutGen = tlTextGen;
		tlLayoutWidth = w;
		return tlLayout;
	}

	public final Layout getHintLayout() {
		return (tlShowingHintNow()) ? getLayout() : null;
	}

	private boolean tlShowingHintNow() {
		return !(this instanceof EditText) && (text == null || text.length() == 0) && getHint() != null && getHint().length() > 0;
	}

	public void nullLayouts() {
		tlLayout = null;
		tlTextGen++;
	}

	public int getLineCount() {
		final Layout l = getLayout();
		return l != null ? l.getLineCount() : 0;
	}

	public boolean getLineBounds(int line, Rect bounds) {
		final Layout l = getLayout();
		if (l == null) {
			if (bounds != null)
				bounds.set(0, 0, 0, 0);
			return false;
		}
		int baseline = l.getLineBaseline(line);
		if (bounds != null) {
			bounds.left = (int) l.getLineLeft(line);
			bounds.top = l.getLineTop(line);
			bounds.right = (int) l.getLineRight(line);
			bounds.bottom = l.getLineBottom(line);
			int voffset = getExtendedPaddingTop() + getVerticalOffset(true);
			bounds.offset(getCompoundPaddingLeft(), voffset);
		}
		return true;
	}

	@Override
	public int getBaseline() {
		final Layout l = getLayout();
		if (l == null || l.getLineCount() == 0)
			return super.getBaseline();
		int voffset = 0;
		if ((gravity & Gravity.VERTICAL_GRAVITY_MASK) != Gravity.TOP)
			voffset = getVerticalOffset(true);
		return getExtendedPaddingTop() + voffset + l.getLineBaseline(0);
	}

	public int getExtendedPaddingTop() {
		if (mMaxMode != LINES)
			return getCompoundPaddingTop();
		final Layout l = getLayout();
		if (l == null || l.getLineCount() <= mMaximum)
			return getCompoundPaddingTop();
		int top = getCompoundPaddingTop();
		int bottom = getCompoundPaddingBottom();
		int viewht = getHeight() - top - bottom;
		int layoutht = l.getLineTop(mMaximum);
		if (layoutht >= viewht)
			return top;
		final int g = gravity & Gravity.VERTICAL_GRAVITY_MASK;
		if (g == Gravity.TOP)
			return top;
		else if (g == Gravity.BOTTOM)
			return top + viewht - layoutht;
		else
			return top + (viewht - layoutht) / 2;
	}

	public int getExtendedPaddingBottom() {
		if (mMaxMode != LINES)
			return getCompoundPaddingBottom();
		final Layout l = getLayout();
		if (l == null || l.getLineCount() <= mMaximum)
			return getCompoundPaddingBottom();
		int top = getCompoundPaddingTop();
		int bottom = getCompoundPaddingBottom();
		int viewht = getHeight() - top - bottom;
		int layoutht = l.getLineTop(mMaximum);
		if (layoutht >= viewht)
			return bottom;
		final int g = gravity & Gravity.VERTICAL_GRAVITY_MASK;
		if (g == Gravity.TOP)
			return bottom + viewht - layoutht;
		else if (g == Gravity.BOTTOM)
			return bottom;
		else
			return bottom + (viewht - layoutht) / 2;
	}

	private int getVerticalOffset(boolean forceNormal) {
		int voffset = 0;
		final int g = gravity & Gravity.VERTICAL_GRAVITY_MASK;
		final Layout l = getLayout();
		if (l != null && g != Gravity.TOP) {
			int boxht = getMeasuredHeight() - getExtendedPaddingTop() - getExtendedPaddingBottom();
			int textht = l.getHeight();
			if (textht < boxht) {
				if (g == Gravity.BOTTOM)
					voffset = boxht - textht;
				else
					voffset = (boxht - textht) >> 1;
			}
		}
		return voffset;
	}

	public int getTotalPaddingLeft() {
		return getCompoundPaddingLeft();
	}

	public int getTotalPaddingRight() {
		return getCompoundPaddingRight();
	}

	public int getTotalPaddingStart() {
		return isLayoutRtl() ? getTotalPaddingRight() : getTotalPaddingLeft();
	}

	public int getTotalPaddingEnd() {
		return isLayoutRtl() ? getTotalPaddingLeft() : getTotalPaddingRight();
	}

	public int getTotalPaddingTop() {
		return getExtendedPaddingTop() + getVerticalOffset(true);
	}

	public int getTotalPaddingBottom() {
		return getExtendedPaddingBottom() + getBottomVerticalOffset(true);
	}

	private int getBottomVerticalOffset(boolean forceNormal) {
		int voffset = 0;
		final int g = gravity & Gravity.VERTICAL_GRAVITY_MASK;
		final Layout l = getLayout();
		if (l != null && g != Gravity.BOTTOM) {
			int boxht = getMeasuredHeight() - getExtendedPaddingTop() - getExtendedPaddingBottom();
			int textht = l.getHeight();
			if (textht < boxht) {
				if (g == Gravity.TOP)
					voffset = boxht - textht;
				else
					voffset = (boxht - textht) >> 1;
			}
		}
		return voffset;
	}

	/** AOSP: the character offset at a point in view coordinates */
	public int getOffsetForPosition(float x, float y) {
		final Layout l = getLayout();
		if (l == null)
			return -1;
		y -= getTotalPaddingTop();
		y = Math.max(0.0f, y);
		y = Math.min(getHeight() - getTotalPaddingBottom() - 1, y);
		y += getScrollY();
		final int line = l.getLineForVertical((int) y);
		x -= getTotalPaddingLeft();
		x = Math.max(0.0f, x);
		x = Math.min(getWidth() - getTotalPaddingRight() - 1, x);
		x += getScrollX();
		return l.getOffsetForHorizontal(line, x);
	}

	/** AOSP onMeasure on top of the native label measure: drawable sizes, min/max width (pixels or
	 * ems), min/max lines or height, suggested minimum size, then autosizing */
	private void tlMeasure(int widthMeasureSpec, int heightMeasureSpec) {
		if (tlShowing == null)
			return;
		final int widthMode = MeasureSpec.getMode(widthMeasureSpec);
		final int widthSize = MeasureSpec.getSize(widthMeasureSpec);
		final int heightMode = MeasureSpec.getMode(heightMeasureSpec);
		final int heightSize = MeasureSpec.getSize(heightMeasureSpec);
		int width = getMeasuredWidth();
		int height = getMeasuredHeight();
		final int padLR = getCompoundPaddingLeft() + getCompoundPaddingRight();
		final int padTB = getCompoundPaddingTop() + getCompoundPaddingBottom();

		if (widthMode != MeasureSpec.EXACTLY) {
			int w = width;
			w = Math.max(w, Math.max(tlDrWidthTop, tlDrWidthBottom) + padLR);
			if (mMaxWidthMode == EMS)
				w = Math.min(w, mMaxWidth * getLineHeight());
			else
				w = Math.min(w, mMaxWidth);
			if (mMinWidthMode == EMS)
				w = Math.max(w, mMinWidth * getLineHeight());
			else
				w = Math.max(w, mMinWidth);
			w = Math.max(w, getSuggestedMinimumWidth());
			if (widthMode == MeasureSpec.AT_MOST)
				w = Math.min(widthSize, w);
			if (w != width) {
				width = w;
				native_measure(widget, MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), heightMeasureSpec);
				height = getMeasuredHeight();
			}
		}

		if (heightMode != MeasureSpec.EXACTLY) {
			final int textW = Math.max(0, width - padLR);
			final boolean label = !(this instanceof EditText);
			int textH = Math.max(0, height - padTB);
			if (mMaxMode == LINES && mMaximum != Integer.MAX_VALUE && mMaximum > 0 && label) {
				final int lim = native_getLinesHeight(widget, textW, mMaximum);
				if (lim >= 0 && textH > lim)
					textH = lim;
			}
			if (mMinMode == LINES && mMinimum > 0) {
				final int lim = label ? native_getLinesHeight(widget, textW, mMinimum) : mMinimum * getLineHeight();
				if (lim > textH)
					textH = lim;
			}
			int desired = Math.max(textH, Math.max(tlDrHeightLeft, tlDrHeightRight));
			desired += padTB;
			if (mMaxMode != LINES)
				desired = Math.min(desired, mMaximum);
			if (mMinMode != LINES)
				desired = Math.max(desired, mMinimum);
			desired = Math.max(desired, getSuggestedMinimumHeight());
			if (heightMode == MeasureSpec.AT_MOST)
				desired = Math.min(desired, heightSize);
			height = desired;
		}
		setMeasuredDimension(width, height);
		if (isAutoSizeEnabled())
			autoSizeText();
	}

	/* ---------------------------------------------------------------- autosize (AOSP) */

	protected boolean supportsAutoSizeText() {
		return !(this instanceof EditText);
	}

	private boolean isAutoSizeEnabled() {
		return supportsAutoSizeText() && mAutoSizeTextType != AUTO_SIZE_TEXT_TYPE_NONE;
	}

	public void setAutoSizeTextTypeWithDefaults(int autoSizeTextType) {
		if (supportsAutoSizeText()) {
			switch (autoSizeTextType) {
				case AUTO_SIZE_TEXT_TYPE_NONE:
					clearAutoSizeConfiguration();
					break;
				case AUTO_SIZE_TEXT_TYPE_UNIFORM:
					final android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
					validateAndSetAutoSizeTextTypeUniformConfiguration(
						TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, DEFAULT_AUTO_SIZE_MIN_TEXT_SIZE_IN_SP, dm),
						TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, DEFAULT_AUTO_SIZE_MAX_TEXT_SIZE_IN_SP, dm),
						DEFAULT_AUTO_SIZE_GRANULARITY_IN_PX);
					if (setupAutoSizeText()) {
						autoSizeText();
						invalidate();
					}
					break;
				default:
					throw new IllegalArgumentException("Unknown auto-size text type: " + autoSizeTextType);
			}
		}
	}

	public void setAutoSizeTextTypeUniformWithConfiguration(int autoSizeMinTextSize, int autoSizeMaxTextSize, int autoSizeStepGranularity, int unit) {
		if (supportsAutoSizeText()) {
			final android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
			validateAndSetAutoSizeTextTypeUniformConfiguration(
				TypedValue.applyDimension(unit, autoSizeMinTextSize, dm),
				TypedValue.applyDimension(unit, autoSizeMaxTextSize, dm),
				TypedValue.applyDimension(unit, autoSizeStepGranularity, dm));
			if (setupAutoSizeText()) {
				autoSizeText();
				invalidate();
			}
		}
	}

	public void setAutoSizeTextTypeUniformWithPresetSizes(int[] presetSizes, int unit) {
		if (supportsAutoSizeText()) {
			final int presetSizesLength = presetSizes.length;
			if (presetSizesLength > 0) {
				int[] presetSizesInPx = new int[presetSizesLength];
				if (unit == TypedValue.COMPLEX_UNIT_PX) {
					presetSizesInPx = Arrays.copyOf(presetSizes, presetSizesLength);
				} else {
					final android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
					for (int i = 0; i < presetSizesLength; i++)
						presetSizesInPx[i] = Math.round(TypedValue.applyDimension(unit, presetSizes[i], dm));
				}
				mAutoSizeTextSizesInPx = cleanupAutoSizePresetSizes(presetSizesInPx);
				if (!setupAutoSizeUniformPresetSizesConfiguration())
					throw new IllegalArgumentException("None of the preset sizes is valid: " + Arrays.toString(presetSizes));
			} else {
				mHasPresetAutoSizeValues = false;
			}
			if (setupAutoSizeText()) {
				autoSizeText();
				invalidate();
			}
		}
	}

	public int getAutoSizeTextType() {
		return mAutoSizeTextType;
	}

	public int getAutoSizeStepGranularity() {
		return Math.round(mAutoSizeStepGranularityInPx);
	}

	public int getAutoSizeMinTextSize() {
		return Math.round(mAutoSizeMinTextSizeInPx);
	}

	public int getAutoSizeMaxTextSize() {
		return Math.round(mAutoSizeMaxTextSizeInPx);
	}

	public int[] getAutoSizeTextAvailableSizes() {
		return mAutoSizeTextSizesInPx;
	}

	private boolean setupAutoSizeUniformPresetSizesConfiguration() {
		final int sizesLength = mAutoSizeTextSizesInPx.length;
		mHasPresetAutoSizeValues = sizesLength > 0;
		if (mHasPresetAutoSizeValues) {
			mAutoSizeTextType = AUTO_SIZE_TEXT_TYPE_UNIFORM;
			mAutoSizeMinTextSizeInPx = mAutoSizeTextSizesInPx[0];
			mAutoSizeMaxTextSizeInPx = mAutoSizeTextSizesInPx[sizesLength - 1];
			mAutoSizeStepGranularityInPx = UNSET_AUTO_SIZE_UNIFORM_CONFIGURATION_VALUE;
		}
		return mHasPresetAutoSizeValues;
	}

	private void validateAndSetAutoSizeTextTypeUniformConfiguration(float autoSizeMinTextSizeInPx, float autoSizeMaxTextSizeInPx, float autoSizeStepGranularityInPx) {
		if (autoSizeMinTextSizeInPx <= 0)
			throw new IllegalArgumentException("Minimum auto-size text size (" + autoSizeMinTextSizeInPx + "px) is less or equal to (0px)");
		if (autoSizeMaxTextSizeInPx <= autoSizeMinTextSizeInPx)
			throw new IllegalArgumentException("Maximum auto-size text size (" + autoSizeMaxTextSizeInPx + "px) is less or equal to minimum auto-size text size (" + autoSizeMinTextSizeInPx + "px)");
		if (autoSizeStepGranularityInPx <= 0)
			throw new IllegalArgumentException("The auto-size step granularity (" + autoSizeStepGranularityInPx + "px) is less or equal to (0px)");
		mAutoSizeTextType = AUTO_SIZE_TEXT_TYPE_UNIFORM;
		mAutoSizeMinTextSizeInPx = autoSizeMinTextSizeInPx;
		mAutoSizeMaxTextSizeInPx = autoSizeMaxTextSizeInPx;
		mAutoSizeStepGranularityInPx = autoSizeStepGranularityInPx;
		mHasPresetAutoSizeValues = false;
	}

	private void clearAutoSizeConfiguration() {
		mAutoSizeTextType = AUTO_SIZE_TEXT_TYPE_NONE;
		mAutoSizeMinTextSizeInPx = UNSET_AUTO_SIZE_UNIFORM_CONFIGURATION_VALUE;
		mAutoSizeMaxTextSizeInPx = UNSET_AUTO_SIZE_UNIFORM_CONFIGURATION_VALUE;
		mAutoSizeStepGranularityInPx = UNSET_AUTO_SIZE_UNIFORM_CONFIGURATION_VALUE;
		mAutoSizeTextSizesInPx = new int[0];
		mNeedsAutoSizeText = false;
	}

	private int[] cleanupAutoSizePresetSizes(int[] presetValues) {
		final int presetValuesLength = presetValues.length;
		if (presetValuesLength == 0)
			return presetValues;
		Arrays.sort(presetValues);
		int[] unique = new int[presetValuesLength];
		int count = 0;
		for (int i = 0; i < presetValuesLength; i++) {
			final int v = presetValues[i];
			if (v > 0 && (count == 0 || unique[count - 1] != v))
				unique[count++] = v;
		}
		return count == presetValuesLength ? presetValues : Arrays.copyOf(unique, count);
	}

	private boolean setupAutoSizeText() {
		if (supportsAutoSizeText() && mAutoSizeTextType == AUTO_SIZE_TEXT_TYPE_UNIFORM) {
			if (!mHasPresetAutoSizeValues || mAutoSizeTextSizesInPx.length == 0) {
				final int len = ((int) Math.floor((mAutoSizeMaxTextSizeInPx - mAutoSizeMinTextSizeInPx) / mAutoSizeStepGranularityInPx)) + 1;
				final int[] sizes = new int[len];
				for (int i = 0; i < len; i++)
					sizes[i] = Math.round(mAutoSizeMinTextSizeInPx + (i * mAutoSizeStepGranularityInPx));
				mAutoSizeTextSizesInPx = cleanupAutoSizePresetSizes(sizes);
			}
			mNeedsAutoSizeText = true;
		} else {
			mNeedsAutoSizeText = false;
		}
		return mNeedsAutoSizeText;
	}

	/** AOSP autoSizeText: the largest size whose layout fits the measured box */
	private void autoSizeText() {
		if (!isAutoSizeEnabled())
			return;
		if (mNeedsAutoSizeText) {
			if (getMeasuredWidth() <= 0 || getMeasuredHeight() <= 0)
				return;
			final int availableWidth = mHorizontallyScrolling ? (1 << 20) : getMeasuredWidth() - getCompoundPaddingLeft() - getCompoundPaddingRight();
			final int availableHeight = getMeasuredHeight() - getCompoundPaddingBottom() - getCompoundPaddingTop();
			if (availableWidth <= 0 || availableHeight <= 0)
				return;
			final float optimalTextSize = findLargestTextSizeWhichFits(availableWidth, availableHeight);
			if (optimalTextSize != getTextSize()) {
				mTextSizeUnit = TypedValue.COMPLEX_UNIT_PX;
				setRawTextSize(optimalTextSize, false);
			}
		}
		mNeedsAutoSizeText = true;
	}

	private int findLargestTextSizeWhichFits(int availableWidth, int availableHeight) {
		final int sizesCount = mAutoSizeTextSizesInPx.length;
		if (sizesCount == 0)
			throw new IllegalStateException("No available text sizes to choose from.");
		int bestSizeIndex = 0;
		int lowIndex = bestSizeIndex + 1;
		int highIndex = sizesCount - 1;
		while (lowIndex <= highIndex) {
			final int sizeToTryIndex = (lowIndex + highIndex) / 2;
			if (suggestedSizeFitsInSpace(mAutoSizeTextSizesInPx[sizeToTryIndex], availableWidth, availableHeight)) {
				bestSizeIndex = lowIndex;
				lowIndex = sizeToTryIndex + 1;
			} else {
				highIndex = sizeToTryIndex - 1;
				bestSizeIndex = highIndex;
			}
		}
		return mAutoSizeTextSizesInPx[bestSizeIndex];
	}

	private boolean suggestedSizeFitsInSpace(int suggestedSizeInPx, int availableWidth, int availableHeight) {
		final CharSequence t = mTransformed != null ? mTransformed : getText();
		final int maxLines = getMaxLines();
		if (mTempTextPaint == null)
			mTempTextPaint = new TextPaint();
		mTempTextPaint.setTypeface(paint.getTypeface());
		mTempTextPaint.setLetterSpacing(paint.getLetterSpacing());
		mTempTextPaint.setTextSize(suggestedSizeInPx);
		final android.text.StaticLayout layout = android.text.StaticLayout.Builder.obtain(t, 0, t.length(), mTempTextPaint, availableWidth)
			.setAlignment(getLayoutAlignment())
			.setLineSpacing(getLineSpacingExtra(), getLineSpacingMultiplier())
			.setIncludePad(getIncludeFontPadding())
			.setBreakStrategy(getBreakStrategy())
			.setHyphenationFrequency(getHyphenationFrequency())
			.setMaxLines(mMaxMode == LINES ? mMaximum : Integer.MAX_VALUE)
			.setTextDirection(getTextDirectionHeuristic())
			.build();
		if (maxLines != -1 && layout.getLineCount() > maxLines)
			return false;
		if (layout.getHeight() > availableHeight)
			return false;
		return true;
	}

	/* ---------------------------------------------------------------- compound drawables (AOSP) */

	public void setCompoundDrawables(Drawable left, Drawable top, Drawable right, Drawable bottom) {
		/* switching to absolute drawables discards the relative ones */
		if (tlDrStart != null && tlDrStart != left && tlDrStart != right)
			tlDrStart.setCallback(null);
		if (tlDrEnd != null && tlDrEnd != left && tlDrEnd != right)
			tlDrEnd.setCallback(null);
		tlDrStart = tlDrEnd = null;
		tlSetShowing(left, top, right, bottom);
	}

	private void tlSetShowing(Drawable left, Drawable top, Drawable right, Drawable bottom) {
		final Drawable[] next = {left, top, right, bottom};
		for (int i = 0; i < 4; i++) {
			final Drawable old = tlShowing[i];
			if (old != null && old != next[0] && old != next[1] && old != next[2] && old != next[3])
				old.setCallback(null);
			tlShowing[i] = next[i];
		}
		final Rect r = new Rect();
		final int[] state = getDrawableState();
		if (left != null) {
			left.setState(state);
			left.copyBounds(r);
			left.setCallback(this);
			tlDrSizeLeft = r.width();
			tlDrHeightLeft = r.height();
		} else {
			tlDrSizeLeft = tlDrHeightLeft = 0;
		}
		if (right != null) {
			right.setState(state);
			right.copyBounds(r);
			right.setCallback(this);
			tlDrSizeRight = r.width();
			tlDrHeightRight = r.height();
		} else {
			tlDrSizeRight = tlDrHeightRight = 0;
		}
		if (top != null) {
			top.setState(state);
			top.copyBounds(r);
			top.setCallback(this);
			tlDrSizeTop = r.height();
			tlDrWidthTop = r.width();
		} else {
			tlDrSizeTop = tlDrWidthTop = 0;
		}
		if (bottom != null) {
			bottom.setState(state);
			bottom.copyBounds(r);
			bottom.setCallback(this);
			tlDrSizeBottom = r.height();
			tlDrWidthBottom = r.width();
		} else {
			tlDrSizeBottom = tlDrWidthBottom = 0;
		}
		applyCompoundDrawableTint();
		tlUpdateJavaDraw();
		if (!tlInInit)
			requestLayout();
		invalidate();
	}

	private void tlUpdateJavaDraw() {
		final boolean need = tlShowing[0] != null || tlShowing[1] != null || tlShowing[2] != null || tlShowing[3] != null;
		if (need != tlJavaDraw && widget != 0) {
			tlJavaDraw = need;
			native_setJavaDraw(widget, need);
		}
	}

	public void setCompoundDrawablesWithIntrinsicBounds(Drawable left, Drawable top, Drawable right, Drawable bottom) {
		if (left != null)
			left.setBounds(0, 0, left.getIntrinsicWidth(), left.getIntrinsicHeight());
		if (right != null)
			right.setBounds(0, 0, right.getIntrinsicWidth(), right.getIntrinsicHeight());
		if (top != null)
			top.setBounds(0, 0, top.getIntrinsicWidth(), top.getIntrinsicHeight());
		if (bottom != null)
			bottom.setBounds(0, 0, bottom.getIntrinsicWidth(), bottom.getIntrinsicHeight());
		setCompoundDrawables(left, top, right, bottom);
	}

	public void setCompoundDrawablesWithIntrinsicBounds(int left, int top, int right, int bottom) {
		final Context context = getContext();
		setCompoundDrawablesWithIntrinsicBounds(left != 0 ? context.getDrawable(left) : null,
			top != 0 ? context.getDrawable(top) : null,
			right != 0 ? context.getDrawable(right) : null,
			bottom != 0 ? context.getDrawable(bottom) : null);
	}

	public void setCompoundDrawablesRelative(Drawable start, Drawable top, Drawable end, Drawable bottom) {
		final boolean rtl = isLayoutRtl();
		tlSetShowing(rtl ? end : start, top, rtl ? start : end, bottom);
		tlDrStart = start;
		tlDrEnd = end;
	}

	public void setCompoundDrawablesRelativeWithIntrinsicBounds(Drawable start, Drawable top, Drawable end, Drawable bottom) {
		if (start != null)
			start.setBounds(0, 0, start.getIntrinsicWidth(), start.getIntrinsicHeight());
		if (end != null)
			end.setBounds(0, 0, end.getIntrinsicWidth(), end.getIntrinsicHeight());
		if (top != null)
			top.setBounds(0, 0, top.getIntrinsicWidth(), top.getIntrinsicHeight());
		if (bottom != null)
			bottom.setBounds(0, 0, bottom.getIntrinsicWidth(), bottom.getIntrinsicHeight());
		setCompoundDrawablesRelative(start, top, end, bottom);
	}

	public void setCompoundDrawablesRelativeWithIntrinsicBounds(int start, int top, int end, int bottom) {
		final Context context = getContext();
		setCompoundDrawablesRelativeWithIntrinsicBounds(start != 0 ? context.getDrawable(start) : null,
			top != 0 ? context.getDrawable(top) : null,
			end != 0 ? context.getDrawable(end) : null,
			bottom != 0 ? context.getDrawable(bottom) : null);
	}

	public Drawable[] getCompoundDrawables() {
		return new Drawable[] {tlShowing[DR_LEFT], tlShowing[DR_TOP], tlShowing[DR_RIGHT], tlShowing[DR_BOTTOM]};
	}

	public Drawable[] getCompoundDrawablesRelative() {
		final boolean rtl = isLayoutRtl();
		return new Drawable[] {
			tlDrStart != null ? tlDrStart : (rtl ? tlShowing[DR_RIGHT] : tlShowing[DR_LEFT]),
			tlShowing[DR_TOP],
			tlDrEnd != null ? tlDrEnd : (rtl ? tlShowing[DR_LEFT] : tlShowing[DR_RIGHT]),
			tlShowing[DR_BOTTOM]};
	}

	public void setCompoundDrawablePadding(int pad) {
		if (pad != tlDrawablePadding) {
			tlDrawablePadding = pad;
			if (!tlInInit)
				requestLayout();
			invalidate();
		}
	}

	public int getCompoundDrawablePadding() {
		return tlDrawablePadding;
	}

	public void setCompoundDrawableTintList(ColorStateList tint) {
		tlDrTintList = tint;
		tlDrHasTint = true;
		applyCompoundDrawableTint();
	}

	public ColorStateList getCompoundDrawableTintList() {
		return tlDrTintList;
	}

	public void setCompoundDrawableTintMode(PorterDuff.Mode tintMode) {
		setCompoundDrawableTintBlendMode(tintMode != null ? BlendMode.fromValue(tintMode.nativeInt) : null);
	}

	public void setCompoundDrawableTintBlendMode(BlendMode blendMode) {
		tlDrBlendMode = blendMode;
		tlDrHasTintMode = true;
		applyCompoundDrawableTint();
	}

	public PorterDuff.Mode getCompoundDrawableTintMode() {
		final BlendMode mode = getCompoundDrawableTintBlendMode();
		return mode != null ? BlendMode.blendModeToPorterDuffMode(mode) : null;
	}

	public BlendMode getCompoundDrawableTintBlendMode() {
		return tlDrBlendMode;
	}

	private void applyCompoundDrawableTint() {
		if (!tlDrHasTint && !tlDrHasTintMode)
			return;
		final int[] state = getDrawableState();
		for (Drawable d : tlShowing) {
			if (d == null)
				continue;
			d.mutate();
			if (tlDrHasTint)
				d.setTintList(tlDrTintList);
			if (tlDrHasTintMode)
				d.setTintBlendMode(tlDrBlendMode);
			if (d.isStateful())
				d.setState(state);
		}
	}

	public int getCompoundPaddingTop() {
		final Drawable d = tlShowing != null ? tlShowing[DR_TOP] : null;
		return d == null ? getPaddingTop() : getPaddingTop() + tlDrawablePadding + tlDrSizeTop;
	}

	public int getCompoundPaddingBottom() {
		final Drawable d = tlShowing != null ? tlShowing[DR_BOTTOM] : null;
		return d == null ? getPaddingBottom() : getPaddingBottom() + tlDrawablePadding + tlDrSizeBottom;
	}

	public int getCompoundPaddingLeft() {
		final Drawable d = tlShowing != null ? tlShowing[DR_LEFT] : null;
		return d == null ? getPaddingLeft() : getPaddingLeft() + tlDrawablePadding + tlDrSizeLeft;
	}

	public int getCompoundPaddingRight() {
		final Drawable d = tlShowing != null ? tlShowing[DR_RIGHT] : null;
		return d == null ? getPaddingRight() : getPaddingRight() + tlDrawablePadding + tlDrSizeRight;
	}

	public int getCompoundPaddingStart() {
		return isLayoutRtl() ? getCompoundPaddingRight() : getCompoundPaddingLeft();
	}

	public int getCompoundPaddingEnd() {
		return isLayoutRtl() ? getCompoundPaddingLeft() : getCompoundPaddingRight();
	}

	@Override
	protected boolean verifyDrawable(Drawable who) {
		if (super.verifyDrawable(who))
			return true;
		if (tlShowing == null)
			return false;
		for (Drawable d : tlShowing) {
			if (who == d)
				return true;
		}
		return false;
	}

	@Override
	public void invalidateDrawable(Drawable drawable) {
		if (tlShowing == null) {
			super.invalidateDrawable(drawable);
			return;
		}
		boolean mine = false;
		for (Drawable d : tlShowing) {
			if (drawable == d)
				mine = true;
		}
		if (mine) {
			/* AOSP: a size change of a compound drawable relayouts; a redraw just invalidates */
			final Rect b = drawable.getBounds();
			if ((drawable == tlShowing[DR_LEFT] && (b.width() != tlDrSizeLeft || b.height() != tlDrHeightLeft))
			    || (drawable == tlShowing[DR_RIGHT] && (b.width() != tlDrSizeRight || b.height() != tlDrHeightRight))
			    || (drawable == tlShowing[DR_TOP] && (b.height() != tlDrSizeTop || b.width() != tlDrWidthTop))
			    || (drawable == tlShowing[DR_BOTTOM] && (b.height() != tlDrSizeBottom || b.width() != tlDrWidthBottom))) {
				tlSetShowing(tlShowing[DR_LEFT], tlShowing[DR_TOP], tlShowing[DR_RIGHT], tlShowing[DR_BOTTOM]);
				return;
			}
			invalidate();
			return;
		}
		super.invalidateDrawable(drawable);
	}

	@Override
	public void jumpDrawablesToCurrentState() {
		super.jumpDrawablesToCurrentState();
		if (tlShowing == null)
			return;
		for (Drawable d : tlShowing) {
			if (d != null)
				d.jumpToCurrentState();
		}
	}

	/** AOSP onDraw: the compound drawables around the text (the label draws the text itself) */
	@Override
	public void onDraw(Canvas canvas) {
		super.onDraw(canvas);
		if (tlShowing == null || tlShowing[0] == null && tlShowing[1] == null && tlShowing[2] == null && tlShowing[3] == null)
			return;
		final int compoundPaddingLeft = getCompoundPaddingLeft();
		final int compoundPaddingTop = getCompoundPaddingTop();
		final int compoundPaddingRight = getCompoundPaddingRight();
		final int compoundPaddingBottom = getCompoundPaddingBottom();
		final int scrollX = getScrollX();
		final int scrollY = getScrollY();
		final int width = getWidth();
		final int height = getHeight();
		final boolean isLayoutRtl = isLayoutRtl();
		final int offset = getHorizontalOffsetForDrawables();
		final int leftOffset = isLayoutRtl ? 0 : offset;
		final int rightOffset = isLayoutRtl ? offset : 0;
		final int vspace = height - compoundPaddingBottom - compoundPaddingTop;
		final int hspace = width - compoundPaddingRight - compoundPaddingLeft;
		if (tlShowing[DR_LEFT] != null) {
			canvas.save();
			canvas.translate(scrollX + getPaddingLeft() + leftOffset, scrollY + compoundPaddingTop + (vspace - tlDrHeightLeft) / 2);
			tlShowing[DR_LEFT].draw(canvas);
			canvas.restore();
		}
		if (tlShowing[DR_RIGHT] != null) {
			canvas.save();
			canvas.translate(scrollX + width - getPaddingRight() - tlDrSizeRight - rightOffset, scrollY + compoundPaddingTop + (vspace - tlDrHeightRight) / 2);
			tlShowing[DR_RIGHT].draw(canvas);
			canvas.restore();
		}
		if (tlShowing[DR_TOP] != null) {
			canvas.save();
			canvas.translate(scrollX + compoundPaddingLeft + (hspace - tlDrWidthTop) / 2, scrollY + getPaddingTop());
			tlShowing[DR_TOP].draw(canvas);
			canvas.restore();
		}
		if (tlShowing[DR_BOTTOM] != null) {
			canvas.save();
			canvas.translate(scrollX + compoundPaddingLeft + (hspace - tlDrWidthBottom) / 2, scrollY + height - getPaddingBottom() - tlDrSizeBottom);
			tlShowing[DR_BOTTOM].draw(canvas);
			canvas.restore();
		}
	}

	/* ---------------------------------------------------------------- touch (movement methods) */

	/** AOSP: the movement method (LinkMovementMethod: clickable spans) sees the touches of a TextView
	 * whose text is Spannable; it is reached through setClickable (fixFocusableAndClickableSettings) */
	@Override
	public boolean onTouchEvent(MotionEvent event) {
		final boolean superResult = super.onTouchEvent(event);
		if (mMovement != null && isEnabled() && text instanceof Spannable && getLayout() != null) {
			if (mMovement.onTouchEvent(this, (Spannable) text, event))
				return true;
		}
		return superResult;
	}

	/* ---------------------------------------------------------------- error (AOSP Editor.setError) */

	private Drawable tlErrorDrawable;
	private Drawable tlErrorSavedEnd;
	private PopupWindow tlErrorPopup;

	/** AOSP: an error message with the error icon at the end of the view, its text in a popup under
	 * the view while the view has focus. The framework resources here have no indicator_input_error,
	 * so the icon is the public ic_dialog_alert. */
	public void setError(CharSequence error) {
		if (error == null) {
			setError(null, null);
		} else {
			Drawable dr = null;
			try {
				int id = getResources().getIdentifier("indicator_input_error", "drawable", "android");
				dr = getContext().getDrawable(id != 0 ? id : android.R.drawable.ic_dialog_alert);
			} catch (RuntimeException e) {
			}
			if (dr != null)
				dr.setBounds(0, 0, dr.getIntrinsicWidth(), dr.getIntrinsicHeight());
			setError(error, dr);
		}
	}

	public void setError(CharSequence error, Drawable icon) {
		mError = TextUtils.stringOrSpannedString(error);
		final Drawable[] rel = getCompoundDrawablesRelative();
		if (tlErrorDrawable != null && rel[2] == tlErrorDrawable) {
			/* restore the end drawable the error icon replaced */
			setCompoundDrawablesRelative(rel[0], rel[1], tlErrorSavedEnd, rel[3]);
			rel[2] = tlErrorSavedEnd;
		}
		tlErrorDrawable = null;
		if (mError != null && icon != null) {
			tlErrorSavedEnd = rel[2];
			tlErrorDrawable = icon;
			setCompoundDrawablesRelative(rel[0], rel[1], icon, rel[3]);
		}
		tlUpdateErrorPopup();
	}

	public CharSequence getError() {
		return mError;
	}

	/** AOSP Editor.showError/hideError: the popup is up while there is an error and the view is focused */
	private void tlUpdateErrorPopup() {
		final boolean show = mError != null && isFocused() && getWindowToken() != null;
		if (!show) {
			if (tlErrorPopup != null && tlErrorPopup.isShowing())
				tlErrorPopup.dismiss();
			return;
		}
		if (tlErrorPopup == null) {
			final TextView tv = new TextView(getContext());
			tv.setTextColor(0xff000000);
			tv.setBackgroundResource(com.android.internal.R.drawable.popup_inline_error_holo_light);
			tlErrorPopup = new PopupWindow(tv, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
			tlErrorPopup.setFocusable(false);
			tlErrorPopup.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
		}
		final TextView tv = (TextView) tlErrorPopup.getContentView();
		tv.setText(mError);
		tv.measure(MeasureSpec.makeMeasureSpec(Math.max(1, getWidth()), MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
		final int xoff = Math.max(0, getWidth() - tv.getMeasuredWidth());
		if (tlErrorPopup.isShowing())
			tlErrorPopup.update(this, xoff, 0, tv.getMeasuredWidth(), tv.getMeasuredHeight());
		else
			tlErrorPopup.showAsDropDown(this, xoff, 0);
	}

	@Override
	protected void onFocusChanged(boolean focused, int direction, Rect previouslyFocusedRect) {
		super.onFocusChanged(focused, direction, previouslyFocusedRect);
		if (tlShowing == null)
			return;
		if (focused && mSelectAllOnFocus && text instanceof Spannable && !(this instanceof EditText))
			Selection.selectAll((Spannable) text);
		tlUpdateErrorPopup();
	}

	/* ---------------------------------------------------------------- misc */

	/** AOSP: whether the view is an editor (an input method can attach) */
	@Override
	public boolean onCheckIsTextEditor() {
		return this instanceof EditText || tlKeyListener != null;
	}

	private android.text.method.WordIterator tlWordIterator;

	/** AOSP (hidden): the word iterator for the text's locale (word-wise deletion and movement) */
	public android.text.method.WordIterator getWordIterator() {
		if (tlWordIterator == null)
			tlWordIterator = new android.text.method.WordIterator(getTextLocale());
		return tlWordIterator;
	}

	/** AOSP (hidden, API 34): whether a transformation maps offsets (OffsetMapping); none of the
	 * transformation methods here do, as with AOSP's own password/single-line/all-caps ones */
	public boolean isOffsetMappingAvailable() {
		return false;
	}

	/** AOSP: the parameters a PrecomputedText for this view must be made with */
	public android.text.PrecomputedText.Params getTextMetricsParams() {
		return new android.text.PrecomputedText.Params(new TextPaint(paint),
			android.graphics.text.LineBreakConfig.getLineBreakConfig(getLineBreakStyle(), getLineBreakWordStyle()),
			getTextDirectionHeuristic(), getBreakStrategy(), getHyphenationFrequency());
	}

	/** AOSP: takes the measuring parameters of a PrecomputedText (paint face, size, spacing, strategies) */
	public void setTextMetricsParams(android.text.PrecomputedText.Params params) {
		final TextPaint p = params.getTextPaint();
		paint.set(p);
		paint.setTypeface(p.getTypeface());
		paint.setLetterSpacing(p.getLetterSpacing());
		paint.setTextScaleX(p.getTextScaleX());
		paint.setFlags(p.getFlags());
		if (p.getTextSize() != tlTextSize) {
			tlTextSize = p.getTextSize();
			paint.setTextSize(tlTextSize);
		}
		setFontFeatureSettings(p.getFontFeatureSettings());
		setBreakStrategy(params.getBreakStrategy());
		setHyphenationFrequency(params.getHyphenationFrequency());
		final android.graphics.text.LineBreakConfig lbc = params.getLineBreakConfig();
		setLineBreakStyle(lbc.getLineBreakStyle());
		setLineBreakWordStyle(lbc.getLineBreakWordStyle());
		tlRenderText();
		requestLayout();
		invalidate();
	}

	/* AOSP text cursor drawable. GtkText draws its own caret (in the text colour, or hidden by
	 * setCursorVisible); the drawable is kept and reported as AOSP does. */
	private int tlCursorDrawableRes;
	private Drawable tlCursorDrawable;

	public void setTextCursorDrawable(Drawable textCursorDrawable) {
		tlCursorDrawable = textCursorDrawable;
		tlCursorDrawableRes = 0;
	}

	public void setTextCursorDrawable(int textCursorDrawable) {
		setTextCursorDrawable(textCursorDrawable != 0 ? getContext().getDrawable(textCursorDrawable) : null);
		tlCursorDrawableRes = textCursorDrawable;
	}

	public Drawable getTextCursorDrawable() {
		if (tlCursorDrawable == null && tlCursorDrawableRes != 0)
			tlCursorDrawable = getContext().getDrawable(tlCursorDrawableRes);
		return tlCursorDrawable;
	}
