package android.view.inputmethod;

import android.os.Bundle;
import android.os.IBinder;
import android.os.ResultReceiver;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewParent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * teslalinux: the AOSP InputMethodManager API on top of ATL's GTK input method context
 * (GtkIMContext). On Android the manager talks to the system's InputMethodManagerService and to
 * an input method application; here the "input method" is the GTK IM module of the session (which
 * may drive an on-screen keyboard through the compositor), so:
 * - a text input session is started for a view by asking it for an InputConnection
 *   ({@link View#onCreateInputConnection}) and binding the GTK IM context to the view's widget;
 *   committed text and surrounding-text queries of the IM module flow through that connection;
 * - there are no input method packages: the IME lists are empty, pickers and status icons do not
 *   exist and stylus handwriting is unavailable, which is how AOSP reports a device without them;
 * - cursor position reports are forwarded to the IM context so pop-ups and OSKs can follow the
 *   caret; extracted-text reports are kept for the session (no IME ever requests extraction).
 */
public final class InputMethodManager {
	private static final String TAG = "InputMethodManager";

	public static final int SHOW_IMPLICIT = 0x0001;
	public static final int SHOW_FORCED = 0x0002;
	public static final int RESULT_UNCHANGED_SHOWN = 0;
	public static final int RESULT_UNCHANGED_HIDDEN = 1;
	public static final int RESULT_SHOWN = 2;
	public static final int RESULT_HIDDEN = 3;
	public static final int HIDE_IMPLICIT_ONLY = 0x0001;
	public static final int HIDE_NOT_ALWAYS = 0x0002;

	private static final long sImContext = nativeInit();

	/* the text input session (AOSP: the "served view" and its connection) */
	private static View sServedView;
	private static InputConnection sServedConnection;
	private static EditorInfo sServedEditorInfo;
	private static boolean sInputShown;
	private static ExtractedText sExtractedText;
	private static int sExtractedTextToken;
	private static CursorAnchorInfo sCursorAnchorInfo;

	public InputMethodManager() {
	}

	/* ------------------------------------------------------------------ sessions */

	/**
	 * AOSP: begins a new input session for the view (the input method is told to restart with the
	 * view's current EditorInfo). The old connection is dropped and the view is asked for a new one.
	 */
	public void restartInput(View view) {
		if (view == null)
			return;
		startInputInner(view);
	}

	private static final boolean TL_DEBUG_IME = System.getenv("ATL_DEBUG_IME") != null;

	private boolean startInputInner(View view) {
		if (TL_DEBUG_IME)
			android.util.Log.i("InputMethodManager", "[ime] startInput view=" + view.getClass().getName() + " focused=" + view.isFocused());
		EditorInfo tba = new EditorInfo();
		tba.packageName = view.getContext() != null ? view.getContext().getPackageName() : null;
		tba.fieldId = view.getId();
		InputConnection ic = view.onCreateInputConnection(tba);
		if (sServedConnection != null && sServedConnection != ic)
			sServedConnection.closeConnection();
		sServedView = view;
		sServedConnection = ic;
		sServedEditorInfo = ic != null ? tba : null;
		sExtractedText = null;
		sCursorAnchorInfo = null;
		nativeStartInput(sImContext, view.widget, ic, ic != null ? tba.inputType : 0);
		return ic != null;
	}

	private static void endInput() {
		if (sServedConnection != null)
			sServedConnection.closeConnection();
		sServedView = null;
		sServedConnection = null;
		sServedEditorInfo = null;
		sExtractedText = null;
		sCursorAnchorInfo = null;
		nativeStartInput(sImContext, 0, null, 0);
		nativeOskHide();
		sInputShown = false;
	}

	/** AOSP: whether the view is the one the input method currently serves. */
	public boolean isActive(View view) {
		return sServedView == view && sServedConnection != null;
	}

	/** AOSP: whether any view is currently served. */
	public boolean isActive() {
		return sServedConnection != null;
	}

	/** AOSP: whether the served view accepts text (it provided an InputConnection). */
	public boolean isAcceptingText() {
		return sServedConnection != null;
	}

	/** AOSP: whether an input connection for the view is active (API 33). */
	public boolean hasActiveInputConnection(View view) {
		return isActive(view);
	}

	/**
	 * AOSP: the view's text changed outside the input method's knowledge; re-synchronise the session.
	 * On Android this takes a {@link TextSnapshot} from the connection; the GTK IM context pulls
	 * the surrounding text on demand, so resetting it and refreshing the surrounding text is the
	 * equivalent.
	 */
	public void invalidateInput(View view) {
		if (!isActive(view))
			return;
		nativeUpdateSurrounding(sImContext);
	}

	/** AOSP (hidden): the served view's context, for connections created without a target view. */
	public android.content.Context getFallbackContextFromServedView() {
		return sServedView != null ? sServedView.getContext() : null;
	}

	/* ------------------------------------------------------------------ soft input */

	public boolean showSoftInput(View view, int flags) {
		return showSoftInput(view, flags, null);
	}

	/**
	 * AOSP: asks the input method to show its soft input for the view. The view must be served (a
	 * session is started for it when it is not), and the result is reported to the receiver.
	 */
	public boolean showSoftInput(View view, int flags, ResultReceiver resultReceiver) {
		if (view == null)
			return false;
		if (!isActive(view) && !startInputInner(view)) {
			if (resultReceiver != null)
				resultReceiver.send(RESULT_UNCHANGED_HIDDEN, null);
			return false;
		}
		boolean wasShown = sInputShown;
		sInputShown = nativeActivate(sImContext);
		if (tlOskEnabled() && sServedEditorInfo != null) {
			nativeOskShow(sServedEditorInfo.imeOptions, sServedEditorInfo.inputType);
			sInputShown = true;
		}
		if (TL_DEBUG_IME)
			android.util.Log.i("InputMethodManager", "[ime] showSoftInput view=" + view.getClass().getName() + " shown=" + sInputShown + " ic=" + (sServedConnection != null ? sServedConnection.getClass().getName() : "null"));
		if (resultReceiver != null)
			resultReceiver.send(wasShown ? RESULT_UNCHANGED_SHOWN : RESULT_SHOWN, null);
		return sInputShown;
	}

	public boolean hideSoftInputFromWindow(IBinder windowToken, int flags) {
		return hideSoftInputFromWindow(windowToken, flags, null);
	}

	/** AOSP: hides the soft input; returns whether it was showing (HIDE_IMPLICIT_ONLY only hides an implicit request). */
	public boolean hideSoftInputFromWindow(IBinder windowToken, int flags, ResultReceiver resultReceiver) {
		boolean wasShown = sInputShown;
		if (TL_DEBUG_IME)
			android.util.Log.i("InputMethodManager", "[ime] hideSoftInput wasShown=" + wasShown);
		nativeDeactivate(sImContext);
		nativeOskHide();
		sInputShown = false;
		if (resultReceiver != null)
			resultReceiver.send(wasShown ? RESULT_HIDDEN : RESULT_UNCHANGED_HIDDEN, null);
		return wasShown;
	}

	/** AOSP: shows the soft input when hidden and hides it when shown. */
	public void toggleSoftInput(int showFlags, int hideFlags) {
		if (sInputShown)
			hideSoftInputFromWindow(null, hideFlags);
		else if (sServedView != null)
			showSoftInput(sServedView, showFlags);
	}

	public void toggleSoftInputFromWindow(IBinder windowToken, int showFlags, int hideFlags) {
		toggleSoftInput(showFlags, hideFlags);
	}

	/** AOSP: the input method never covers the whole screen here (GTK input methods do not have an extract UI). */
	public boolean isFullscreenMode() {
		return false;
	}

	/* ------------------------------------------------------------------ reports from the editor */

	/**
	 * AOSP: reports the selection and composing range; the GTK IM context pulls the surrounding
	 * text through the connection, so the session's surrounding text is refreshed.
	 */
	public void updateSelection(View view, int selStart, int selEnd, int candidatesStart, int candidatesEnd) {
		if (!isActive(view))
			return;
		nativeUpdateSurrounding(sImContext);
	}

	/**
	 * AOSP: reports the caret and text bounds. The insertion marker is passed to the IM context as
	 * the cursor location (in the served widget's coordinates) so pop-ups and OSKs follow it.
	 */
	public void updateCursorAnchorInfo(View view, CursorAnchorInfo cursorAnchorInfo) {
		if (!isActive(view) || cursorAnchorInfo == null)
			return;
		sCursorAnchorInfo = cursorAnchorInfo;
		float x = cursorAnchorInfo.getInsertionMarkerHorizontal();
		float top = cursorAnchorInfo.getInsertionMarkerTop();
		float bottom = cursorAnchorInfo.getInsertionMarkerBottom();
		if (Float.isNaN(x) || Float.isNaN(top) || Float.isNaN(bottom))
			return;
		float[] pts = { x, top, x, bottom };
		cursorAnchorInfo.getMatrix().mapPoints(pts);
		int[] loc = new int[2];
		view.getLocationOnScreen(loc);
		nativeSetCursorLocation(sImContext, Math.round(pts[0] - loc[0]), Math.round(pts[1] - loc[1]), 1, Math.max(1, Math.round(pts[3] - pts[1])));
	}

	/**
	 * AOSP: reports extracted text to an input method that requested it with the given token. No
	 * GTK input method requests extraction; the report is kept with the session.
	 */
	public void updateExtractedText(View view, int token, ExtractedText text) {
		if (!isActive(view))
			return;
		sExtractedText = text;
		sExtractedTextToken = token;
	}

	/** @deprecated AOSP: superseded by updateCursorAnchorInfo; on current Android it reports nothing. */
	@Deprecated
	public void updateCursor(View view, int left, int top, int right, int bottom) {
		if (!isActive(view))
			return;
		nativeSetCursorLocation(sImContext, left, top, Math.max(1, right - left), Math.max(1, bottom - top));
	}

	/** @deprecated AOSP: always false since API 21. */
	@Deprecated
	public boolean isWatchingCursor(View view) {
		return false;
	}

	/** AOSP: the user clicked the served view; GTK input methods have no click notification to receive. */
	public void viewClicked(View view) {
	}

	/** AOSP: completions for the input method's extract UI; GTK input methods have none to show them in. */
	public void displayCompletions(View view, CompletionInfo[] completions) {
	}

	/** AOSP: a private command for the input method application; there is no such application here. */
	public void sendAppPrivateCommand(View view, String action, Bundle data) {
	}

	/** AOSP: a key event the input method synthesised is delivered through the served view's window. */
	public void dispatchKeyEventFromInputMethod(View targetView, KeyEvent event) {
		View root = targetView != null ? targetView : sServedView;
		if (root == null)
			return;
		ViewParent parent = root.getParent();
		while (parent instanceof View) {
			root = (View) parent;
			parent = root.getParent();
		}
		root.dispatchKeyEvent(event);
	}

	/* ------------------------------------------------------------------ input method packages */

	/** AOSP: the installed input methods; the GTK IM module is not an Android input method package. */
	public List<InputMethodInfo> getInputMethodList() {
		return new ArrayList<InputMethodInfo>();
	}

	public List<InputMethodInfo> getEnabledInputMethodList() {
		return new ArrayList<InputMethodInfo>();
	}

	public List<InputMethodSubtype> getEnabledInputMethodSubtypeList(InputMethodInfo imi, boolean allowsImplicitlySelectedSubtypes) {
		return new ArrayList<InputMethodSubtype>();
	}

	public InputMethodInfo getCurrentInputMethodInfo() {
		return null;
	}

	public InputMethodSubtype getCurrentInputMethodSubtype() {
		return new InputMethodSubtype();
	}

	public InputMethodSubtype getLastInputMethodSubtype() {
		return null;
	}

	/** AOSP: selecting a subtype needs an input method package; none exists. */
	public boolean setCurrentInputMethodSubtype(InputMethodSubtype subtype) {
		return false;
	}

	public Map<InputMethodInfo, List<InputMethodSubtype>> getShortcutInputMethodsAndSubtypes() {
		return new HashMap<InputMethodInfo, List<InputMethodSubtype>>();
	}

	public void setAdditionalInputMethodSubtypes(String imiId, InputMethodSubtype[] subtypes) {
	}

	public boolean shouldOfferSwitchingToNextInputMethod(IBinder imeToken) {
		return false;
	}

	public boolean switchToNextInputMethod(IBinder imeToken, boolean onlyCurrentIme) {
		return false;
	}

	public boolean switchToLastInputMethod(IBinder imeToken) {
		return false;
	}

	/** AOSP: the system's input method picker; there is no system UI to show one in. */
	public void showInputMethodPicker() {
	}

	public void showInputMethodAndSubtypeEnabler(String imiId) {
	}

	/** AOSP: the input method's status-bar icon; there is no status bar. */
	public void showStatusIcon(IBinder imeToken, String packageName, int iconId) {
	}

	public void hideStatusIcon(IBinder imeToken) {
	}

	/* ------------------------------------------------------------------ stylus handwriting */

	/** AOSP: stylus handwriting needs an input method that supports it; none is installed. */
	public boolean isStylusHandwritingAvailable() {
		return false;
	}

	public void startStylusHandwriting(View view) {
		Log.w(TAG, "Stylus handwriting is not available: no input method supports it");
	}

	/**
	 * AOSP: tells the input method a delegator view may hand stylus handwriting to another view; with
	 * no handwriting-capable input method (isStylusHandwritingAvailable) there is nobody to prepare,
	 * and acceptStylusHandwritingDelegation reports false, as AOSP does on such a device.
	 */
	public void prepareStylusHandwritingDelegation(View delegatorView) {
	}

	public void prepareStylusHandwritingDelegation(View delegatorView, String delegatePackageName) {
	}

	public boolean acceptStylusHandwritingDelegation(View delegateView) {
		return false;
	}

	public boolean acceptStylusHandwritingDelegation(View delegateView, String delegatorPackageName) {
		return false;
	}

	/* ------------------------------------------------------------------ natives (GtkIMContext) */

	private static native long nativeInit();
	/** binds the IM context to the widget and the connection (widget 0 / connection null ends the session) */
	private static native void nativeStartInput(long imContext, long widget, InputConnection ic, int inputType);
	/** focuses the IM context and asks the compositor for its on-screen keyboard; true when accepted */
	private static native boolean nativeActivate(long imContext);
	private static native void nativeDeactivate(long imContext);
	private static native void nativeSetCursorLocation(long imContext, int x, int y, int width, int height);
	/** pushes the connection's surrounding text and selection to the IM context */
	private static native void nativeUpdateSurrounding(long imContext);

	/* ------------------------------------------------------------------ teslalinux: built-in on-screen keyboard */
	/*
	 * This platform's input method (native-osk.sh): a keyboard layer in the main window, the way the
	 * system IME window sits above the activity in AOSP. ATL_OSK=none leaves soft input to the
	 * Wayland compositor's own keyboard.
	 */
	private static native void nativeOskShow(int imeOptions, int inputType);
	private static native void nativeOskHide();
	private static native boolean nativeOskIsShown();

	private static boolean tlOskEnabled() {
		String v = System.getenv("ATL_OSK");
		return v == null || !v.equals("none");
	}

	/** A key of the on-screen keyboard: committed like an IME's commitText (main thread). */
	private static void tlOskText(String text) {
		InputConnection ic = sServedConnection;
		if (TL_DEBUG_IME)
			android.util.Log.i("InputMethodManager", "[ime] osk text '" + text + "' ic=" + (ic != null ? ic.getClass().getName() : "null"));
		if (ic == null)
			return;
		ic.beginBatchEdit();
		ic.commitText(text, 1);
		ic.endBatchEdit();
		if (TL_DEBUG_IME)
			android.util.Log.i("InputMethodManager", "[ime] text before cursor now '" + ic.getTextBeforeCursor(50, 0) + "'");
	}

	/** Backspace: the character before the cursor, or the selection (AOSP LatinIME's deleteSurroundingText path). */
	private static void tlOskBackspace() {
		InputConnection ic = sServedConnection;
		if (ic == null)
			return;
		ic.beginBatchEdit();
		CharSequence selected = ic.getSelectedText(0);
		if (selected != null && selected.length() > 0) {
			ic.commitText("", 1);
		} else {
			CharSequence before = ic.getTextBeforeCursor(2, 0);
			int n = 1;
			if (before != null && before.length() == 2 && Character.isSurrogatePair(before.charAt(0), before.charAt(1)))
				n = 2;
			ic.deleteSurroundingText(n, 0);
		}
		ic.endBatchEdit();
	}

	/** The action key: the editor's IME action when it has one, otherwise an ENTER key (AOSP LatinIME). */
	private static void tlOskAction() {
		InputConnection ic = sServedConnection;
		if (ic == null)
			return;
		int imeOptions = sServedEditorInfo != null ? sServedEditorInfo.imeOptions : 0;
		int action = imeOptions & EditorInfo.IME_MASK_ACTION;
		boolean noEnterAction = (imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0;
		if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED && !noEnterAction) {
			ic.performEditorAction(action);
		} else {
			long now = android.os.SystemClock.uptimeMillis();
			ic.sendKeyEvent(new android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER, 0));
			ic.sendKeyEvent(new android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_ENTER, 0));
		}
	}

	/** The on-screen keyboard consumes BACK while it shows (AOSP: the IME window handles it); true when it did. */
	private static boolean tlOskHandleBack() {
		if (!nativeOskIsShown())
			return false;
		nativeOskHide();
		sInputShown = false;
		return true;
	}

	/** A window (dialog decor) left the screen: its editor's input session ends and the soft input hides (AOSP). */
	public static void tlWindowRemoved(View decor) {
		View v = sServedView;
		while (v != null && v != decor)
			v = v.getParent() instanceof View ? (View) v.getParent() : null;
		if (v == decor && decor != null) {
			endInput();
		}
	}
}
