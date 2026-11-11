	/* ---- teslalinux: font resources (AOSP Resources.getFont / ResourcesImpl.loadFont) ---- */

	/**
	 * Return a font resource (a font file or an XML font family). The parsed typeface is cached per
	 * resource path, as in AOSP; a value that is not a font throws NotFoundException.
	 */
	public android.graphics.Typeface getFont(int id) throws NotFoundException {
		final TypedValue value = new TypedValue();
		getValue(id, value, true);
		android.graphics.Typeface typeface = getFont(value, id);
		if (typeface != null) {
			return typeface;
		}
		throw new NotFoundException("Font resource ID #0x" + Integer.toHexString(id));
	}

	/** @hide AOSP: loads the typeface a (resolved) TypedValue points at. */
	public android.graphics.Typeface getFont(TypedValue value, int id) throws NotFoundException {
		if (value.string == null) {
			throw new NotFoundException("Resource \"" + getResourceName(id) + "\" (" + Integer.toHexString(id) + ") is not a Font: " + value);
		}
		final String file = value.string.toString();
		if (!file.startsWith("res/")) {
			return null;
		}
		android.graphics.Typeface cached = android.graphics.Typeface.findFromCache(mAssets, file);
		if (cached != null) {
			return cached;
		}
		try {
			if (file.endsWith("xml")) {
				final XmlResourceParser rp = loadXmlResourceParser(file, id, value.assetCookie, "font");
				final FontResourcesParser.FamilyResourceEntry familyEntry = FontResourcesParser.parse(rp, this);
				if (familyEntry == null) {
					return null;
				}
				return android.graphics.Typeface.createFromResources(familyEntry, mAssets, file);
			}
			return new android.graphics.Typeface.Builder(mAssets, file, false /* isAsset */, value.assetCookie).build();
		} catch (org.xmlpull.v1.XmlPullParserException e) {
			android.util.Log.e("Resources", "Failed to parse xml resource " + file, e);
		} catch (java.io.IOException e) {
			android.util.Log.e("Resources", "Failed to read xml resource " + file, e);
		}
		return null;
	}

	/** AOSP: a float resource (TYPE_FLOAT), e.g. {@code <item type="dimen" format="float">}. */
	public float getFloat(int id) {
		final TypedValue value = new TypedValue();
		getValue(id, value, true);
		if (value.type == TypedValue.TYPE_FLOAT) {
			return value.getFloat();
		}
		throw new NotFoundException("Resource ID #0x" + Integer.toHexString(id) + " type #0x" + Integer.toHexString(value.type) + " is not valid");
	}

	/**
	 * AOSP: the drawable for a specific density. ATL keeps one density per process, so this is the
	 * drawable for the current configuration.
	 */
	public android.graphics.drawable.Drawable getDrawableForDensity(int id, int density, Theme theme) {
		return getDrawable(id, theme);
	}
