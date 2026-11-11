	/**
	 * AOSP (API 34): the inverse of applyDimension: a pixel value in the given unit. SP uses the
	 * linear scaledDensity, as this applyDimension does (no non-linear font scale converter).
	 */
	public static float deriveDimension(int unitToConvertTo, float pixelValue, DisplayMetrics metrics) {
		switch (unitToConvertTo) {
			case COMPLEX_UNIT_PX:
				return pixelValue;
			case COMPLEX_UNIT_DIP:
				if (metrics.density == 0)
					return 0;
				return pixelValue / metrics.density;
			case COMPLEX_UNIT_SP:
				if (metrics.scaledDensity == 0)
					return 0;
				return pixelValue / metrics.scaledDensity;
			case COMPLEX_UNIT_PT:
				if (metrics.xdpi == 0)
					return 0;
				return pixelValue / metrics.xdpi / (1f / 72);
			case COMPLEX_UNIT_IN:
				if (metrics.xdpi == 0)
					return 0;
				return pixelValue / metrics.xdpi;
			case COMPLEX_UNIT_MM:
				if (metrics.xdpi == 0)
					return 0;
				return pixelValue / metrics.xdpi / (1f / 25.4f);
			default:
				throw new IllegalArgumentException("Invalid unitToConvertTo " + unitToConvertTo);
		}
	}

	/** AOSP (API 34): converts a pixel value to the given unit (the unit of a TypedValue). */
	public static float convertPixelsToDimension(int unitToConvertTo, float pixelValue, DisplayMetrics metrics) {
		return deriveDimension(unitToConvertTo, pixelValue, metrics);
	}

	/** AOSP (API 34): applyDimension under its API 34 name. */
	public static float convertDimensionToPixels(int unitToConvertFrom, float value, DisplayMetrics metrics) {
		return applyDimension(unitToConvertFrom, value, metrics);
	}
