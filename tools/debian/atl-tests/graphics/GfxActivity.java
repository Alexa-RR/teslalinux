package org.teslalinux.gfxtest;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * ATL on-screen test (screen.sh runs it under a private headless compositor with GSK's cairo or GL
 * renderer): the GfxCases drawn by a View through the window's renderer, one cell each, with their
 * probe pixels logged in window coordinates ("GFXPROBE x y rrggbb tolerance name", the expected
 * colour composited over the cell background) for check-shot.py to compare with a screenshot; and
 * View state checks (selection, duplicateParentState, layout direction resolution, relative
 * padding, text alignment), logged as "GFXCHECK PASS|FAIL name".
 */
public class GfxActivity extends Activity {
	static final String TAG = "GfxTest";
	static final int GAP = 4, BG = 0xFF808080;
	List<GfxCases.Case> cases;
	CasesView casesView;

	class CasesView extends View {
		boolean logged;

		CasesView(Context c) {
			super(c);
		}

		int cols() {
			return Math.max(1, getWidth() / (GfxCases.CELL + GAP));
		}

		@Override
		public void onDraw(Canvas canvas) { /* public: ATL's View declares it so (AOSP: protected) */
			final int cols = cols();
			for (int i = 0; i < cases.size(); i++) {
				canvas.save();
				canvas.translate((i % cols) * (GfxCases.CELL + GAP), (i / cols) * (GfxCases.CELL + GAP));
				canvas.clipRect(0, 0, GfxCases.CELL, GfxCases.CELL);
				canvas.drawColor(BG);
				try {
					cases.get(i).draw(canvas);
				} catch (Throwable t) {
					Log.w(TAG, "GFXCASE " + cases.get(i).name + " threw " + t);
				}
				canvas.restore();
			}
			if (!logged) {
				logged = true;
				postDelayed(GfxActivity.this::logProbes, 1500);
			}
		}
	}

	void logProbes() {
		int[] loc = new int[2];
		casesView.getLocationInWindow(loc);
		final int cols = casesView.cols();
		final float br = ((BG >> 16) & 0xff) / 255f, bgc = ((BG >> 8) & 0xff) / 255f, bb = (BG & 0xff) / 255f;
		for (int i = 0; i < cases.size(); i++) {
			GfxCases.Case k = cases.get(i);
			int ox = loc[0] + (i % cols) * (GfxCases.CELL + GAP), oy = loc[1] + (i / cols) * (GfxCases.CELL + GAP);
			for (GfxCases.Probe p : k.probes) {
				float a = p.premul[3];
				int r = Math.round(255 * Math.min(1, p.premul[0] + br * (1 - a)));
				int g = Math.round(255 * Math.min(1, p.premul[1] + bgc * (1 - a)));
				int b = Math.round(255 * Math.min(1, p.premul[2] + bb * (1 - a)));
				Log.w(TAG, String.format("GFXPROBE %d %d %02x%02x%02x %d %s", ox + p.x, oy + p.y, r, g, b, Math.round(p.tol * 255) + 2, k.name));
			}
		}
		Log.w(TAG, "GFX ready");
	}

	static void check(String name, boolean ok, String detail) {
		Log.w(TAG, "GFXCHECK " + (ok ? "PASS " : "FAIL ") + name + (ok || detail == null ? "" : "  (" + detail + ")"));
	}

	static boolean has(int[] states, int s) {
		for (int x : states)
			if (x == s)
				return true;
		return false;
	}

	static class RtlView extends View {
		int rtlCalls = 0, lastDirection = -1;

		RtlView(Context c) {
			super(c);
		}

		@Override
		public void onRtlPropertiesChanged(int layoutDirection) {
			rtlCalls++;
			lastDirection = layoutDirection;
		}
	}

	RtlView rtlChild;
	LinearLayout rtlRow;
	View rowA, rowB, padded;
	/* text alignment rows: {view, the side its text must be drawn on} */
	final java.util.ArrayList<Object[]> alignRows = new java.util.ArrayList<>();

	/** a full-width row of black text on white, whose text must end up on SIDE ("left"/"right") */
	android.widget.TextView alignRow(LinearLayout parent, String text, String side) {
		android.widget.TextView t = new android.widget.TextView(this);
		t.setText(text);
		t.setTextColor(0xFF000000);
		t.setBackgroundColor(0xFFFFFFFF);
		t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 18);
		parent.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 26));
		alignRows.add(new Object[] {t, side});
		return t;
	}

	/* the built-in on-screen keyboard as the IME inset (WindowInsets.Type.ime()), adjustResize, and
	 * the WindowInsetsAnimation callbacks; then "GFXCHECK done" (the keyboard is hidden again) */
	final StringBuilder imeCalls = new StringBuilder();
	int imeProgress;
	float imeLastFraction;

	void imeChecks(final LinearLayout root) {
		final android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
		final android.widget.EditText et = new android.widget.EditText(this);
		root.addView(et, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 40));
		getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
		final View decor = getWindow().getDecorView();
		/* the animations reach the app's views when the decor does not fit: setDecorFitsSystemWindows(false)
		 * before the first animation (AOSP: the fitting content view stops their dispatch for good) */
		getWindow().setDecorFitsSystemWindows(false);
		root.post(() -> {
			check("before: no IME inset", decor.getRootWindowInsets() != null && decor.getRootWindowInsets().getInsets(android.view.WindowInsets.Type.ime()).bottom == 0
			      && !decor.getRootWindowInsets().isVisible(android.view.WindowInsets.Type.ime()), "");
			et.requestFocus();
			root.setWindowInsetsAnimationCallback(new android.view.WindowInsetsAnimation.Callback(android.view.WindowInsetsAnimation.Callback.DISPATCH_MODE_STOP) {
				@Override
				public void onPrepare(android.view.WindowInsetsAnimation animation) {
					imeCalls.append("prepare:").append(animation.getTypeMask() == android.view.WindowInsets.Type.ime()).append(' ');
				}

				@Override
				public android.view.WindowInsetsAnimation.Bounds onStart(android.view.WindowInsetsAnimation animation, android.view.WindowInsetsAnimation.Bounds bounds) {
					imeCalls.append("start:").append(bounds.getUpperBound().bottom > 0).append(' ');
					return bounds;
				}

				@Override
				public android.view.WindowInsets onProgress(android.view.WindowInsets insets, java.util.List<android.view.WindowInsetsAnimation> running) {
					imeProgress++;
					imeLastFraction = running.get(0).getFraction();
					return insets;
				}

				@Override
				public void onEnd(android.view.WindowInsetsAnimation animation) {
					imeCalls.append("end ");
				}
			});
			root.postDelayed(() -> {
				imm.showSoftInput(et, 0);
				android.view.WindowInsets in = decor.getRootWindowInsets();
				final int imeBottom = in.getInsets(android.view.WindowInsets.Type.ime()).bottom;
				check("the on-screen keyboard is the IME inset, visible", imeBottom > 100 && in.isVisible(android.view.WindowInsets.Type.ime()), "" + imeBottom);
				check("decorFitsSystemWindows(false): no resize", decor.getPaddingBottom() == 0, "" + decor.getPaddingBottom());
				root.postDelayed(() -> {
					check("WindowInsetsAnimation: prepare, start (ime, bounds), frames to fraction 1, end", imeCalls.toString().startsWith("prepare:true start:true ")
					      && imeCalls.toString().contains("end") && imeProgress > 2 && imeLastFraction == 1f, imeCalls + " frames " + imeProgress + " last " + imeLastFraction);
					imm.hideSoftInputFromWindow(et.getWindowToken(), 0);
					check("hidden: no IME inset", decor.getRootWindowInsets().getInsets(android.view.WindowInsets.Type.ime()).bottom == 0, "");
					root.setWindowInsetsAnimationCallback(null);
					/* adjustResize with the decor fitting: the content shrinks above the keyboard */
					getWindow().setDecorFitsSystemWindows(true);
					root.postDelayed(() -> {
						imm.showSoftInput(et, 0);
						android.view.WindowInsets r = decor.getRootWindowInsets();
						int b = r.getInsets(android.view.WindowInsets.Type.ime()).bottom;
						check("adjustResize: the system window insets include it", r.getSystemWindowInsetBottom() == b && b > 0, r.getSystemWindowInsetBottom() + " vs " + b);
						check("adjustResize: the decor fits the content above the keyboard", decor.getPaddingBottom() == b, decor.getPaddingBottom() + " vs " + b);
						imm.hideSoftInputFromWindow(et.getWindowToken(), 0);
						check("hidden: the padding goes", decor.getPaddingBottom() == 0, "" + decor.getPaddingBottom());
						root.postDelayed(() -> dialogFocusChecks(root, et), 500);
					}, 200);
				}, 700);
			}, 200);
		});
	}

	/* each window keeps its own focused view (AOSP: focus is per window; the focused window is the
	 * topmost that takes focus) */
	void dialogFocusChecks(final LinearLayout root, final android.widget.EditText et) {
		et.requestFocus();
		final android.app.Dialog d = new android.app.Dialog(this);
		final android.widget.EditText det = new android.widget.EditText(this);
		d.setContentView(det);
		d.show();
		root.postDelayed(() -> {
			boolean took = det.requestFocus();
			check("a dialog's view takes focus in its window; the activity's keeps its own", det.isFocused() && et.isFocused() && d.getWindow().getDecorView().findFocus() == det
			      && getWindow().getDecorView().findFocus() == et, det.isFocused() + " " + et.isFocused() + " took " + took + " attached " + det.isAttachedToWindow()
			      + " focusable " + det.isFocusable() + "/" + det.isFocusableInTouchMode() + " touch " + det.isInTouchMode() + " et " + et.isFocusable() + "/" + et.isFocusableInTouchMode()
			      + " shown " + det.isShown());
			check("the dialog's window has the window focus, the activity's not", det.hasWindowFocus() && !et.hasWindowFocus(), det.hasWindowFocus() + " " + et.hasWindowFocus());
			d.dismiss();
			root.postDelayed(() -> {
				check("after the dialog: the activity's window has the focus again, with its focused view", et.hasWindowFocus() && et.isFocused(), et.hasWindowFocus() + " " + et.isFocused());
				root.removeView(et);
				Log.w(TAG, "GFXCHECK done");
			}, 300);
		}, 300);
	}

	/** GFXINK x0 y0 x1 y1 side name: the dark (text) pixels of the rectangle are on that half */
	void logInk() {
		for (Object[] row : alignRows) {
			android.widget.TextView t = (android.widget.TextView)row[0];
			int[] loc = new int[2];
			t.getLocationInWindow(loc);
			Log.w(TAG, String.format("GFXINK %d %d %d %d %s text alignment: \"%s\" gravity 0x%x textAlignment %d textDirection %d", loc[0], loc[1] + 2,
						 loc[0] + t.getWidth() - 1, loc[1] + t.getHeight() - 3, row[1], t.getText(), t.getGravity(), t.getTextAlignment(), t.getTextDirection()));
		}
	}

	void stateChecks() {
		/* selection: the flag, the drawable state, the children of a group */
		FrameLayout group = new FrameLayout(this);
		View child = new View(this);
		group.addView(child);
		group.setSelected(true);
		check("setSelected: isSelected", group.isSelected(), null);
		check("setSelected: state_selected in the drawable state", has(group.getDrawableState(), android.R.attr.state_selected), Arrays.toString(group.getDrawableState()));
		check("setSelected on a group selects its children", child.isSelected() && has(child.getDrawableState(), android.R.attr.state_selected), null);
		group.setSelected(false);
		check("setSelected(false) clears both", !group.isSelected() && !child.isSelected() && !has(child.getDrawableState(), android.R.attr.state_selected), null);

		/* duplicateParentState: the child takes the parent's state, also when it changes later (the
		 * enabled state: unlike pressed, selected and activated a group does not pass it on itself) */
		FrameLayout parent = new FrameLayout(this);
		View dup = new View(this);
		StateListDrawable sld = new StateListDrawable();
		sld.addState(new int[] { -android.R.attr.state_enabled }, new ColorDrawable(0xFFFF0000));
		sld.addState(new int[0], new ColorDrawable(0xFF00FF00));
		dup.setBackground(sld);
		dup.setDuplicateParentStateEnabled(true);
		parent.addView(dup);
		boolean before = has(dup.getDrawableState(), android.R.attr.state_enabled);
		parent.setEnabled(false);
		check("duplicateParentState: the parent's new state reaches the child's drawable state", before && !has(dup.getDrawableState(), android.R.attr.state_enabled), Arrays.toString(dup.getDrawableState()));
		check("duplicateParentState: and the child's background state", !has(sld.getState(), android.R.attr.state_enabled), Arrays.toString(sld.getState()));
		parent.setEnabled(true);
		check("duplicateParentState: and back", has(dup.getDrawableState(), android.R.attr.state_enabled) && has(sld.getState(), android.R.attr.state_enabled), null);

		/* text alignment: GRAVITY by default, INHERIT resolves to the parent's */
		View t = new View(this);
		check("getTextAlignment defaults to GRAVITY", t.getTextAlignment() == View.TEXT_ALIGNMENT_GRAVITY, "" + t.getTextAlignment());
		FrameLayout tp = new FrameLayout(this);
		tp.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
		View tc = new View(this);
		tc.setTextAlignment(View.TEXT_ALIGNMENT_INHERIT);
		tp.addView(tc);
		check("TEXT_ALIGNMENT_INHERIT takes the parent's", tc.getTextAlignment() == View.TEXT_ALIGNMENT_CENTER, "" + tc.getTextAlignment());

		/* layout direction */
		View detached = new View(this);
		check("a detached INHERIT view cannot resolve its direction", !detached.canResolveLayoutDirection(), null);
		FrameLayout rtl = new FrameLayout(this);
		rtl.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
		View inh = new View(this);
		rtl.addView(inh);
		check("INHERIT under an RTL parent resolves to RTL", inh.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL && inh.isLayoutRtl(), "" + inh.getLayoutDirection());
		rtl.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
		check("and follows the parent back to LTR", inh.getLayoutDirection() == View.LAYOUT_DIRECTION_LTR, "" + inh.getLayoutDirection());
		Locale saved = Locale.getDefault();
		Locale.setDefault(new Locale("he", "IL"));
		View loc = new View(this);
		loc.setLayoutDirection(View.LAYOUT_DIRECTION_LOCALE);
		check("LOCALE resolves from the default locale (Hebrew: RTL)", loc.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL, "" + loc.getLayoutDirection());
		Locale.setDefault(saved);
		View pad = new View(this);
		pad.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
		pad.setPaddingRelative(10, 1, 20, 2);
		check("setPaddingRelative in RTL: start is right", pad.getPaddingLeft() == 20 && pad.getPaddingRight() == 10 && pad.getPaddingStart() == 10 && pad.getPaddingEnd() == 20,
		      pad.getPaddingLeft() + "," + pad.getPaddingRight() + " start " + pad.getPaddingStart());
	}

	/* accessibility: the delegate gets every call first; with no accessibility service (accessibility
	 * off, as on a device without one) no event is created */
	void a11yChecks() {
		final StringBuilder calls = new StringBuilder();
		final android.view.accessibility.AccessibilityNodeProvider provider = new android.view.accessibility.AccessibilityNodeProvider() {};
		View.AccessibilityDelegate d = new View.AccessibilityDelegate() {
			@Override
			public void sendAccessibilityEvent(View host, int eventType) {
				calls.append("send:").append(eventType).append(' ');
				super.sendAccessibilityEvent(host, eventType);
			}

			@Override
			public void sendAccessibilityEventUnchecked(View host, android.view.accessibility.AccessibilityEvent event) {
				calls.append("unchecked ");
				super.sendAccessibilityEventUnchecked(host, event);
			}

			@Override
			public boolean performAccessibilityAction(View host, int action, Bundle args) {
				calls.append("action:").append(action).append(' ');
				return super.performAccessibilityAction(host, action, args);
			}

			@Override
			public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
				calls.append("nodeinfo ");
				super.onInitializeAccessibilityNodeInfo(host, info);
				info.setContentDescription("from the delegate");
			}

			@Override
			public android.view.accessibility.AccessibilityNodeProvider getAccessibilityNodeProvider(View host) {
				return host.getTag() != null ? provider : null;
			}
		};
		View v = new View(this);
		final boolean[] clicked = { false };
		v.setOnClickListener(x -> clicked[0] = true);
		v.setAccessibilityDelegate(d);
		check("setAccessibilityDelegate: getAccessibilityDelegate returns it", v.getAccessibilityDelegate() == d, null);
		v.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED);
		check("sendAccessibilityEvent goes to the delegate", calls.toString().contains("send:1 "), calls.toString());
		android.view.accessibility.AccessibilityManager am = (android.view.accessibility.AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
		check("no accessibility service: accessibility is off, no event is sent", !am.isEnabled() && !calls.toString().contains("unchecked"), am.isEnabled() + " " + calls);
		check("the accessibility manager is one per process", am == getSystemService(ACCESSIBILITY_SERVICE), null);
		boolean acted = v.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK, null);
		check("performAccessibilityAction goes through the delegate and clicks", acted && clicked[0] && calls.toString().contains("action:16 "), acted + " " + clicked[0] + " " + calls);
		android.view.accessibility.AccessibilityNodeInfo info = v.createAccessibilityNodeInfo();
		check("createAccessibilityNodeInfo: the delegate initialises the node", calls.toString().contains("nodeinfo") && "from the delegate".contentEquals(info.getContentDescription()),
		      calls + " " + info.getContentDescription());
		check("no provider from the delegate: none", v.getAccessibilityNodeProvider() == null, null);
		v.setTag("p");
		check("the delegate's provider is the view's", v.getAccessibilityNodeProvider() == provider, null);
		v.setAccessibilityDelegate(null);
		check("without a delegate: the view's own (no provider)", v.getAccessibilityNodeProvider() == null && v.getAccessibilityDelegate() == null, null);

		/* importance */
		View plain = new View(this);
		check("a plain view is not important for accessibility", !plain.isImportantForAccessibility(), null);
		plain.setClickable(true);
		check("a clickable view is", plain.isImportantForAccessibility(), null);
		plain.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
		check("IMPORTANT_FOR_ACCESSIBILITY_NO: not", !plain.isImportantForAccessibility(), null);
		FrameLayout hide = new FrameLayout(this);
		hide.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
		View under = new View(this);
		under.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
		hide.addView(under);
		check("under NO_HIDE_DESCENDANTS even YES is not", !under.isImportantForAccessibility(), null);

		/* a group's delegate can veto an event of its child */
		FrameLayout g = new FrameLayout(this);
		final View[] asked = { null };
		g.setAccessibilityDelegate(new View.AccessibilityDelegate() {
			@Override
			public boolean onRequestSendAccessibilityEvent(ViewGroup host, View child, android.view.accessibility.AccessibilityEvent event) {
				asked[0] = child;
				return false;
			}
		});
		View gc = new View(this);
		g.addView(gc);
		boolean sent = g.requestSendAccessibilityEvent(gc, android.view.accessibility.AccessibilityEvent.obtain(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED));
		check("ViewGroup.requestSendAccessibilityEvent: a parentless group sends nothing", !sent, null);
		FrameLayout top = new FrameLayout(this);
		top.addView(g);
		sent = g.requestSendAccessibilityEvent(gc, android.view.accessibility.AccessibilityEvent.obtain(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED));
		check("the group's delegate is asked and its veto stops the event", !sent && asked[0] == gc, sent + " " + asked[0]);
	}

	int sysUiSeen = -1;

	/* the listener setters kept nothing; hover/generic motion/drag/unhandled-key dispatch (AOSP) */
	void listenerChecks(View attached) {
		View v = new View(this);
		final int[] hovered = { 0 }, generic = { 0 };
		v.setOnHoverListener((x, e) -> { hovered[0]++; return true; });
		v.setOnGenericMotionListener((x, e) -> { generic[0]++; return true; });
		android.view.MotionEvent hover = android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_HOVER_MOVE, 1, 1, 0);
		hover.setSource(android.view.InputDevice.SOURCE_MOUSE);
		boolean h = v.dispatchGenericMotionEvent(hover);
		check("a hover event goes to the OnHoverListener", h && hovered[0] == 1 && generic[0] == 0, hovered[0] + " " + generic[0]);
		android.view.MotionEvent scroll = android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_SCROLL, 1, 1, 0);
		scroll.setSource(android.view.InputDevice.SOURCE_MOUSE);
		boolean g = v.dispatchGenericMotionEvent(scroll);
		check("a scroll event goes to the OnGenericMotionListener", g && generic[0] == 1, "" + generic[0]);
		v.setEnabled(false);
		check("a disabled view skips the hover listener", !v.dispatchGenericMotionEvent(hover) && hovered[0] == 1, "" + hovered[0]);
		final int[] dragged = { 0 };
		View d = new View(this);
		d.setOnDragListener((x, e) -> { dragged[0]++; return true; });
		check("dispatchDragEvent goes to the OnDragListener", d.dispatchDragEvent(android.view.DragEvent.obtain(android.view.DragEvent.ACTION_DRAG_STARTED, 1, 1, 0, 0, null, null, null, null, null, false)) && dragged[0] == 1, "" + dragged[0]);
		check("getDisplay of a detached view is null", new View(this).getDisplay() == null && attached.getDisplay() != null, null);
		final View[] got = { null };
		attached.addOnUnhandledKeyEventListener((x, e) -> { got[0] = x; return true; });
		View root = attached.getRootView();
		boolean taken = root.tlDispatchUnhandledKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_F5));
		check("an unhandled key reaches the view's OnUnhandledKeyEventListener", taken && got[0] == attached, String.valueOf(got[0]));
		attached.setOnSystemUiVisibilityChangeListener(vis -> sysUiSeen = vis);
		attached.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LOW_PROFILE);
	}

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		cases = GfxCases.all();
		LinearLayout root = new LinearLayout(this);
		root.setOrientation(LinearLayout.VERTICAL);
		casesView = new CasesView(this);
		final int cols = 1280 / (GfxCases.CELL + GAP);
		final int rows = (cases.size() + cols - 1) / cols;
		root.addView(casesView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rows * (GfxCases.CELL + GAP)));
		/* an RTL row: its first child goes to the right, its padding start is on the right */
		rtlRow = new LinearLayout(this);
		rtlRow.setOrientation(LinearLayout.HORIZONTAL);
		rtlRow.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
		rtlRow.setPaddingRelative(30, 0, 0, 0);
		rowA = new View(this);
		rowA.setBackgroundColor(0xFFFF0000);
		rowB = new View(this);
		rowB.setBackgroundColor(0xFF0000FF);
		rtlRow.addView(rowA, new LinearLayout.LayoutParams(100, 40));
		rtlRow.addView(rowB, new LinearLayout.LayoutParams(100, 40));
		rtlChild = new RtlView(this);
		rtlRow.addView(rtlChild, new LinearLayout.LayoutParams(20, 40));
		root.addView(rtlRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 40));
		/* AOSP's text alignment in an LTR window: gravity START is the paragraph's start (the text
		 * direction heuristic, FIRST_STRONG by default: a Hebrew paragraph is RTL) */
		alignRow(root, "שלום עולם", "right");
		alignRow(root, "Hello world", "left");
		alignRow(root, "שלום עולם", "left").setTextDirection(View.TEXT_DIRECTION_LTR);
		alignRow(root, "שלום עולם", "left").setGravity(android.view.Gravity.LEFT);
		alignRow(root, "שלום עולם", "left").setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
		alignRow(root, "שלום עולם", "left").setGravity(android.view.Gravity.END);
		alignRow(root, "Hello world", "right").setGravity(android.view.Gravity.END);
		alignRow(root, "123 שלום", "right");
		setContentView(root);
		try {
			stateChecks();
		} catch (Throwable t) {
			check("state checks ran", false, t.toString());
		}
		try {
			a11yChecks();
		} catch (Throwable t) {
			check("accessibility checks ran", false, t.toString());
		}
		rtlRow.postDelayed(() -> {
			try {
				listenerChecks(rowA);
			} catch (Throwable t) {
				check("listener checks ran", false, t.toString());
			}
		}, 300);
		rtlRow.postDelayed(() -> {
			check("setSystemUiVisibility tells the window's OnSystemUiVisibilityChangeListeners", sysUiSeen == View.SYSTEM_UI_FLAG_LOW_PROFILE
			      && rowA.getWindowSystemUiVisibility() == View.SYSTEM_UI_FLAG_LOW_PROFILE, sysUiSeen + " " + rowA.getWindowSystemUiVisibility());
			check("an RTL LinearLayout lays its first child out on the right", rowA.getLeft() > rowB.getLeft() && rowA.getRight() == rtlRow.getWidth() - 30,
			      "a " + rowA.getLeft() + "-" + rowA.getRight() + " b " + rowB.getLeft() + " row " + rtlRow.getWidth());
			check("onRtlPropertiesChanged told the child RTL", rtlChild.rtlCalls > 0 && rtlChild.lastDirection == View.LAYOUT_DIRECTION_RTL, rtlChild.rtlCalls + " " + rtlChild.lastDirection);
			check("the window's root resolves LTR from an English configuration", root.getLayoutDirection() == View.LAYOUT_DIRECTION_LTR && root.isLayoutDirectionResolved(), "" + root.getLayoutDirection());
			android.widget.TextView he = (android.widget.TextView)alignRows.get(0)[0];
			check("a Hebrew TextView's text direction heuristic finds RTL", he.getTextDirectionHeuristic().isRtl(he.getText(), 0, he.getText().length()), null);
			logInk();
			imeChecks(root);
		}, 1000);
	}
}
