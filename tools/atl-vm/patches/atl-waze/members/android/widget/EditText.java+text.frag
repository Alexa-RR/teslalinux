	/* teslalinux (+text): AOSP EditText behaviour around the GtkText */
	@Override
	public void append(CharSequence text, int start, int end) {
		final int len = native_getLength(widget);
		native_setSelection(widget, len, len);
		native_insertText(widget, text.subSequence(start, end).toString());
	}

	/** AOSP: the hint colour reaches GtkText's placeholder */
	@Override
	public void setHintTextColor(android.content.res.ColorStateList colors) {
		super.setHintTextColor(colors);
		native_setHintColor(widget, getCurrentHintTextColor());
	}

	/** AOSP: an EditText keeps its text across configuration changes */
	@Override
	public boolean getFreezesText() {
		return true;
	}

	/** AOSP: the GtkText cursor or selection moved (native-text-textview.sh, tl_edittext_selection) */
	private void tlGtkSelectionChanged() {
		onSelectionChanged(getSelectionStart(), getSelectionEnd());
	}

	/** AOSP: the platform text context menu items (select all, cut, copy, paste) on the GtkText */
	public boolean onTextContextMenuItem(int id) {
		final String text = native_getText(widget);
		final int min = Math.max(0, Math.min(getSelectionStart(), getSelectionEnd()));
		final int max = Math.min(text.length(), Math.max(getSelectionStart(), getSelectionEnd()));
		final android.content.ClipboardManager cm = (android.content.ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
		if (id == android.R.id.selectAll) {
			selectAll();
			return true;
		} else if (id == android.R.id.copy || id == android.R.id.cut) {
			if (min == max || cm == null)
				return false;
			cm.setPrimaryClip(android.content.ClipData.newPlainText(null, text.substring(min, max)));
			if (id == android.R.id.cut)
				native_deleteRange(widget, min, max);
			return true;
		} else if (id == android.R.id.paste) {
			if (cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip().getItemCount() == 0)
				return false;
			CharSequence paste = cm.getPrimaryClip().getItemAt(0).coerceToText(getContext());
			if (paste == null)
				return false;
			if (min != max)
				native_deleteRange(widget, min, max);
			native_setSelection(widget, min, min);
			native_insertText(widget, paste.toString());
			return true;
		}
		return false;
	}
