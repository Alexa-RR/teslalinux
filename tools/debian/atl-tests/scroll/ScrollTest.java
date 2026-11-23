package org.teslalinux.scrolltest;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * A ScrollView whose rows alternate between a TextView and an EditText (the layout of Waze's
 * signed-in "Account and login" settings page: description texts and login fields in a scrolling
 * settings panel). run.sh swipes over the rows on the car display and reads the scroll position from
 * the log ("SCROLL y=N" on every change, "SCROLLTEST ready" once laid out, "ROW i top bottom kind"
 * per row in window coordinates).
 */
public class ScrollTest extends Activity {
	static final String TAG = "ScrollTest";

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		final ScrollView scroll = new ScrollView(this);
		final LinearLayout col = new LinearLayout(this);
		col.setOrientation(LinearLayout.VERTICAL);
		final View[] rows = new View[16];
		for (int i = 0; i < rows.length; i++) {
			View row;
			if (i % 2 == 0) {
				TextView t = new TextView(this);
				t.setText("Description text of row " + i + ": what this setting does, over two lines of text");
				t.setGravity(Gravity.CENTER_VERTICAL);
				t.setTextSize(20);
				row = t;
			} else {
				EditText e = new EditText(this);
				e.setText("field " + i);
				e.setTextSize(22);
				row = e;
			}
			row.setBackgroundColor(i % 2 == 0 ? Color.rgb(240, 240, 240) : Color.WHITE);
			col.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 140));
			rows[i] = row;
		}
		scroll.addView(col, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
		scroll.setOnScrollChangeListener((v, x, y, ox, oy) -> Log.w(TAG, "SCROLL y=" + y));
		setContentView(scroll);
		scroll.postDelayed(() -> {
			int[] loc = new int[2];
			for (int i = 0; i < rows.length; i++) {
				rows[i].getLocationInWindow(loc);
				Log.w(TAG, "ROW " + i + " " + loc[1] + " " + (loc[1] + rows[i].getHeight()) + " " + (i % 2 == 0 ? "text" : "edit"));
			}
			Log.w(TAG, "SCROLLTEST ready range=" + (col.getHeight() - scroll.getHeight()));
		}, 1500);
	}
}
