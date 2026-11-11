
	/*
	 * teslalinux (native-font-files.sh): the face of the typeface (family, weight, slant, width, as
	 * TextView and Layout resolve it, see TlTextAttrs.Face) and the fake-bold flag on the native
	 * Pango font description. Text skew is not part of it: GskCanvas shears the text itself.
	 */
	private void tlApplyFace() {
		final android.text.TlTextAttrs.Face face = android.text.TlTextAttrs.Face.of(mTypeface, (mFlags & FAKE_BOLD_TEXT_FLAG) != 0, 0);
		native_set_face(paint, face.family, face.weight, face.italic, face.stretch);
	}

	private static native void native_set_face(long paint, String family, int weight, boolean italic, int stretch);
