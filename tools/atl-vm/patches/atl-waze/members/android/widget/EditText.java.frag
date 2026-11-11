	/** Performs the editor action (IME action id); the listener gets it first, as with the IME. */
	public void onEditorAction(int actionCode) {
		if (tlEditorActionListener != null)
			tlEditorActionListener.onEditorAction(this, actionCode, null);
	}
	/* ---- teslalinux: the input method side of an EditText (AOSP TextView + EditableInputConnection) ---- */
	protected native void native_insertText(long widget, String text);
	protected native void native_deleteRange(long widget, int start, int end);
	protected native int native_getCursor(long widget);
	protected native void native_getSelection(long widget, int[] out);
	protected native void native_setSelection(long widget, int start, int end);
	protected native void native_activate(long widget);
	protected native int native_getLength(long widget);

	@Override
	public int getSelectionStart() {
		int[] sel = new int[2];
		native_getSelection(widget, sel);
		return sel[0];
	}

	@Override
	public int getSelectionEnd() {
		int[] sel = new int[2];
		native_getSelection(widget, sel);
		return sel[1];
	}

	@Override
	public void setSelection(int index) {
		native_setSelection(widget, index, index);
	}

	@Override
	public void setSelection(int start, int stop) {
		native_setSelection(widget, start, stop);
	}

	/** AOSP TextView.onCreateInputConnection: the editor's attributes and a connection the input method edits through. */
	@Override
	public android.view.inputmethod.InputConnection onCreateInputConnection(android.view.inputmethod.EditorInfo outAttrs) {
		outAttrs.inputType = tlInputType;
		outAttrs.imeOptions = tlImeOptions;
		if ((outAttrs.imeOptions & android.view.inputmethod.EditorInfo.IME_MASK_ACTION) == android.view.inputmethod.EditorInfo.IME_ACTION_UNSPECIFIED) {
			// AOSP: a single-line editor's default action is DONE, a multi-line one's is none
			if ((tlInputType & android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0)
				outAttrs.imeOptions |= android.view.inputmethod.EditorInfo.IME_ACTION_NONE;
			else
				outAttrs.imeOptions |= android.view.inputmethod.EditorInfo.IME_ACTION_DONE;
		}
		outAttrs.hintText = getHint();
		outAttrs.initialSelStart = getSelectionStart();
		outAttrs.initialSelEnd = getSelectionEnd();
		return new TlEditTextInputConnection(this);
	}

	/** GTK focus of the text widget: AOSP onFocusChanged; a focus gained by touch starts input and asks for the soft input. */
	private void tlGtkFocusChanged(boolean focused) {
		onFocusChanged(focused, FOCUS_DOWN, null);
		if (focused) {
			android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
			if (imm != null) {
				imm.restartInput(this);
				imm.showSoftInput(this, 0);
			}
		}
	}

	/** AOSP EditableInputConnection over the GtkText buffer (positions are character offsets). */
	private static final class TlEditTextInputConnection extends android.view.inputmethod.BaseInputConnection {
		private final EditText mView;
		private int mComposingStart = -1, mComposingEnd = -1;

		TlEditTextInputConnection(EditText view) {
			super(view, false);
			mView = view;
		}

		private String text() {
			return mView.native_getText(mView.widget);
		}

		@Override
		public boolean commitText(CharSequence text, int newCursorPosition) {
			if (mComposingStart >= 0) {
				mView.native_deleteRange(mView.widget, mComposingStart, mComposingEnd);
				mComposingStart = mComposingEnd = -1;
			}
			mView.native_insertText(mView.widget, text.toString());
			return true;
		}

		@Override
		public boolean setComposingText(CharSequence text, int newCursorPosition) {
			if (mComposingStart >= 0)
				mView.native_deleteRange(mView.widget, mComposingStart, mComposingEnd);
			int start = mView.native_getCursor(mView.widget);
			mView.native_insertText(mView.widget, text.toString());
			mComposingStart = start;
			mComposingEnd = start + text.length();
			return true;
		}

		@Override
		public boolean finishComposingText() {
			mComposingStart = mComposingEnd = -1;
			return true;
		}

		@Override
		public boolean deleteSurroundingText(int beforeLength, int afterLength) {
			int[] sel = new int[2];
			mView.native_getSelection(mView.widget, sel);
			int len = mView.native_getLength(mView.widget);
			int start = Math.max(0, sel[0] - beforeLength);
			int end = Math.min(len, sel[1] + afterLength);
			if (end > sel[1])
				mView.native_deleteRange(mView.widget, sel[1], end);
			if (sel[0] > start)
				mView.native_deleteRange(mView.widget, start, sel[0]);
			return true;
		}

		@Override
		public CharSequence getTextBeforeCursor(int n, int flags) {
			String t = text();
			int cursor = Math.min(mView.getSelectionStart(), t.length());
			return t.substring(Math.max(0, cursor - n), cursor);
		}

		@Override
		public CharSequence getTextAfterCursor(int n, int flags) {
			String t = text();
			int cursor = Math.min(mView.getSelectionEnd(), t.length());
			return t.substring(cursor, Math.min(t.length(), cursor + n));
		}

		@Override
		public CharSequence getSelectedText(int flags) {
			String t = text();
			int a = Math.min(mView.getSelectionStart(), t.length()), b = Math.min(mView.getSelectionEnd(), t.length());
			return a == b ? null : t.substring(a, b);
		}

		@Override
		public boolean setSelection(int start, int end) {
			mView.native_setSelection(mView.widget, start, end);
			return true;
		}

		@Override
		public boolean performEditorAction(int editorAction) {
			mView.native_activate(mView.widget);
			return true;
		}

		@Override
		public boolean sendKeyEvent(android.view.KeyEvent event) {
			if (event.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER) {
				if (event.getAction() == android.view.KeyEvent.ACTION_UP)
					mView.native_activate(mView.widget);
				return true;
			}
			if (event.getKeyCode() == android.view.KeyEvent.KEYCODE_DEL) {
				if (event.getAction() == android.view.KeyEvent.ACTION_DOWN)
					deleteSurroundingText(1, 0);
				return true;
			}
			return mView.dispatchKeyEvent(event);
		}
	}
