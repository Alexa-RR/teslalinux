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
		setContentView(root);
		try {
			stateChecks();
		} catch (Throwable t) {
			check("state checks ran", false, t.toString());
		}
		rtlRow.postDelayed(() -> {
			check("an RTL LinearLayout lays its first child out on the right", rowA.getLeft() > rowB.getLeft() && rowA.getRight() == rtlRow.getWidth() - 30,
			      "a " + rowA.getLeft() + "-" + rowA.getRight() + " b " + rowB.getLeft() + " row " + rtlRow.getWidth());
			check("onRtlPropertiesChanged told the child RTL", rtlChild.rtlCalls > 0 && rtlChild.lastDirection == View.LAYOUT_DIRECTION_RTL, rtlChild.rtlCalls + " " + rtlChild.lastDirection);
			check("the window's root resolves LTR from an English configuration", root.getLayoutDirection() == View.LAYOUT_DIRECTION_LTR && root.isLayoutDirectionResolved(), "" + root.getLayoutDirection());
			Log.w(TAG, "GFXCHECK done");
		}, 1000);
	}
}
