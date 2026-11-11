	/* teslalinux: Drawable additions */
	/** AOSP returns 0xFF unless a subclass tracks its alpha; the plain drawable tracks it (setAlpha). */
	public int getAlpha() { return mTlAlpha; }
	public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
	public boolean isAutoMirrored() { return false; }
	public Rect getDirtyBounds() { return getBounds(); }
	/** AOSP: the default outline is the bounds rectangle with alpha 0 (it casts no shadow). */
	public void getOutline(android.graphics.Outline outline) {
		outline.setRect(getBounds());
		outline.setAlpha(0);
	}
	public android.graphics.Insets getOpticalInsets() { return android.graphics.Insets.NONE; }
	public void applyTheme(android.content.res.Resources.Theme t) {}
	public boolean canApplyTheme() { return false; }
	public android.graphics.Region getTransparentRegion() { return null; }
	protected boolean onLevelChange(int level) { return false; }

	/** Inflate this Drawable from an XML resource (AOSP: reads android:visible). */
	public void inflate(android.content.res.Resources r, org.xmlpull.v1.XmlPullParser parser, android.util.AttributeSet attrs) throws org.xmlpull.v1.XmlPullParserException, java.io.IOException {
		inflate(r, parser, attrs, null);
	}

	public void inflate(android.content.res.Resources r, org.xmlpull.v1.XmlPullParser parser, android.util.AttributeSet attrs, android.content.res.Resources.Theme theme) throws org.xmlpull.v1.XmlPullParserException, java.io.IOException {
		if (attrs != null) {
			boolean visible = attrs.getAttributeBooleanValue("http://schemas.android.com/apk/res/android", "visible", true);
			setVisible(visible, false);
		}
	}
	/** AOSP Drawable base: no bitmap filtering unless a subclass says so. */
	public boolean isFilterBitmap() { return false; }
	/** AOSP Drawable base: no colour filter unless a subclass keeps one. */
	/* ---- teslalinux: AOSP tint and colour filter state ---- */
	private android.content.res.ColorStateList mTint;
	private android.graphics.PorterDuff.Mode mTintMode = android.graphics.PorterDuff.Mode.SRC_IN;
	private android.graphics.PorterDuffColorFilter mTintFilter;
	private android.graphics.ColorFilter mColorFilter;
	public android.graphics.ColorFilter getColorFilter() { return mColorFilter; }
	/** The filter a subclass should paint with: the explicit colour filter, else the tint filter (AOSP). */
	protected android.graphics.ColorFilter tlEffectiveColorFilter() { return mColorFilter != null ? mColorFilter : mTintFilter; }
	public android.content.res.ColorStateList tlGetTintList() { return mTint; }
	public android.graphics.PorterDuff.Mode tlGetTintMode() { return mTintMode; }
	/** AOSP: the drawable currently showing (the drawable itself unless a container overrides). */
	public Drawable getCurrent() { return this; }
	/** AOSP (hidden): wraps a checked exception from a drawable inflation helper. */
	static void rethrowAsRuntimeException(Exception e) {
		throw new RuntimeException(e);
	}
	/* AOSP Drawable defaults (API 23-29) */
	/** AOSP: called when the resolved layout direction changes; true when the drawable changed because of it. */
	public boolean onLayoutDirectionChanged(int layoutDirection) {
		return false;
	}

	/** AOSP (API 29): a tint blend mode; the default maps it to the PorterDuff mode. */
	public void setTintBlendMode(android.graphics.BlendMode blendMode) {
		if (!mSetBlendModeInvoked) {
			mSetBlendModeInvoked = true;
			android.graphics.PorterDuff.Mode mode = android.graphics.BlendMode.blendModeToPorterDuffMode(blendMode);
			setTintMode(mode != null ? mode : DEFAULT_TINT_MODE);
			mSetBlendModeInvoked = false;
		}
	}

	/** AOSP (API 28): whether the drawable has a state for focus (so no focus highlight is needed). */
	public boolean hasFocusStateSpecified() {
		return false;
	}

	/** AOSP (hidden): an Xfermode for drawables that support one; ignored by the base class. */
	public void setXfermode(android.graphics.Xfermode mode) {
	}

	/** AOSP (hidden): clears the mutated state after a constant state swap. */
	public void clearMutated() {
	}
	/** AOSP: the hotspot bounds, the drawable bounds unless a subclass narrows them. */
	public void getHotspotBounds(Rect outRect) {
		outRect.set(getBounds());
	}

	/** AOSP: the more opaque of two opacities, used by containers. */
	public static int resolveOpacity(int op1, int op2) {
		if (op1 == op2) {
			return op1;
		}
		if (op1 == android.graphics.PixelFormat.UNKNOWN || op2 == android.graphics.PixelFormat.UNKNOWN) {
			return android.graphics.PixelFormat.UNKNOWN;
		}
		if (op1 == android.graphics.PixelFormat.TRANSLUCENT || op2 == android.graphics.PixelFormat.TRANSLUCENT) {
			return android.graphics.PixelFormat.TRANSLUCENT;
		}
		if (op1 == android.graphics.PixelFormat.TRANSPARENT || op2 == android.graphics.PixelFormat.TRANSPARENT) {
			return android.graphics.PixelFormat.TRANSPARENT;
		}
		return android.graphics.PixelFormat.OPAQUE;
	}

	/** AOSP (hidden): reads the "visible" attribute during inflation. */
	void inflateWithAttributes(Resources r, org.xmlpull.v1.XmlPullParser parser, android.content.res.TypedArray attrs, int visibleAttr)
			throws org.xmlpull.v1.XmlPullParserException, java.io.IOException {
		mVisible = attrs.getBoolean(visibleAttr, mVisible);
	}

	/* AOSP: visibility state (setVisible/isVisible); drawables start visible */
	private boolean mVisible = true;
	/**
	 * AOSP: scales a pixel value between densities; a size never rounds to 0.
	 * @hide
	 */
	public static int scaleFromDensity(int pixels, int sourceDensity, int targetDensity, boolean isSize) {
		if (pixels == 0 || sourceDensity == targetDensity) {
			return pixels;
		}

		final float result = pixels * targetDensity / (float) sourceDensity;
		if (!isSize) {
			return (int) result;
		}

		final int rounded = Math.round(result);
		if (rounded != 0) {
			return rounded;
		} else if (pixels > 0) {
			return 1;
		} else {
			return -1;
		}
	}

	/** @hide */
	public static float scaleFromDensity(float pixels, int sourceDensity, int targetDensity) {
		return pixels * targetDensity / sourceDensity;
	}
	/* AOSP DrawableInflater.inflateFromClass: constructors by class name, cached */
	private static final java.util.HashMap<String, java.lang.reflect.Constructor<? extends Drawable>> tlConstructorMap = new java.util.HashMap<>();

	private static Drawable tlInflateFromClass(android.content.res.Resources res, String className, org.xmlpull.v1.XmlPullParser parser) throws org.xmlpull.v1.XmlPullParserException {
		try {
			java.lang.reflect.Constructor<? extends Drawable> constructor;
			synchronized (tlConstructorMap) {
				constructor = tlConstructorMap.get(className);
				if (constructor == null) {
					ClassLoader loader = Drawable.class.getClassLoader();
					Class<?> clazz;
					try {
						clazz = loader.loadClass(className);
					} catch (ClassNotFoundException e) {
						/* an app-defined drawable class lives in the app's class loader */
						ClassLoader app = Thread.currentThread().getContextClassLoader();
						if (app == null)
							throw e;
						clazz = app.loadClass(className);
					}
					constructor = clazz.asSubclass(Drawable.class).getConstructor();
					tlConstructorMap.put(className, constructor);
				}
			}
			return constructor.newInstance();
		} catch (NoSuchMethodException e) {
			final android.view.InflateException ie = new android.view.InflateException(parser.getPositionDescription() + ": Error inflating class " + className);
			ie.initCause(e);
			throw ie;
		} catch (ClassCastException e) {
			// If loaded class is not a Drawable subclass.
			final android.view.InflateException ie = new android.view.InflateException(parser.getPositionDescription() + ": Class is not a Drawable " + className);
			ie.initCause(e);
			throw ie;
		} catch (ClassNotFoundException e) {
			/* AOSP: an unknown tag that is no class either is an invalid drawable tag */
			throw new org.xmlpull.v1.XmlPullParserException(parser.getPositionDescription() + ": invalid drawable tag " + className);
		} catch (Exception e) {
			final android.view.InflateException ie = new android.view.InflateException(parser.getPositionDescription() + ": Error inflating class " + className);
			ie.initCause(e);
			throw ie;
		}
	}
