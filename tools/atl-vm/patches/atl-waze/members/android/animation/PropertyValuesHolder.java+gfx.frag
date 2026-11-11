	/* ---- teslalinux (gfx): start/end values from the target (AOSP setupStartValue/setupEndValue) ---- */

	private void tlSetupValue(Object target, boolean start) {
		Object v;
		try {
			if (property != null) {
				v = property.get(target);
			} else {
				String capitalized = property_name.substring(0, 1).toUpperCase() + property_name.substring(1);
				Method getter = target.getClass().getMethod("get" + capitalized);
				getter.setAccessible(true);
				v = getter.invoke(target);
			}
		} catch (ReflectiveOperationException | RuntimeException e) {
			Log.e("PropertyValuesHolder", "no getter for " + property_name + " on " + target, e);
			return;
		}
		if (values_int != null && v instanceof Number) {
			values_int = values_int.clone();
			values_int[start ? 0 : values_int.length - 1] = ((Number) v).intValue();
		} else if (values_float != null && v instanceof Number) {
			values_float = values_float.clone();
			values_float[start ? 0 : values_float.length - 1] = ((Number) v).floatValue();
		} else if (values_object != null) {
			values_object = values_object.clone();
			values_object[start ? 0 : values_object.length - 1] = v;
		} else if (values_keyframe != null) {
			values_keyframe = values_keyframe.clone();
			int i = start ? 0 : values_keyframe.length - 1;
			Keyframe k = values_keyframe[i].clone();
			k.setValue(v);
			values_keyframe[i] = k;
		}
	}

	/** True when the first and last values differ (LayoutTransition skips unchanged layouts). */
	boolean tlStartDiffersFromEnd() {
		if (values_int != null)
			return values_int.length > 1 && values_int[0] != values_int[values_int.length - 1];
		if (values_float != null)
			return values_float.length > 1 && values_float[0] != values_float[values_float.length - 1];
		if (values_object != null)
			return values_object.length > 1 && !java.util.Objects.equals(values_object[0], values_object[values_object.length - 1]);
		if (values_keyframe != null)
			return values_keyframe.length > 1 && !java.util.Objects.equals(values_keyframe[0].getValue(), values_keyframe[values_keyframe.length - 1].getValue());
		return false;
	}
