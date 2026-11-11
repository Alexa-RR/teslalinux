package org.teslalinux.embedtest;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Parcel;
import android.util.Log;
import android.view.SurfaceControlViewHost;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * ATL runtime test of embedded windows: a SurfaceView fills the activity and draws red; a view
 * hierarchy (translucent blue, a label and a button) is given to a SurfaceControlViewHost, whose
 * SurfacePackage goes through a Parcel (as between two components) into
 * SurfaceView.setChildSurfacePackage. run.sh checks the log ("EMBED ..." lines, tag EmbedTest) and
 * a screenshot, and taps the button.
 */
public class EmbedActivity extends Activity {
	static final String TAG = "EmbedTest";
	SurfaceView surfaceView;
	SurfaceControlViewHost host;
	Button button;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		FrameLayout root = new FrameLayout(this);
		surfaceView = new SurfaceView(this);
		root.addView(surfaceView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
		setContentView(root);
		surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
			public void surfaceCreated(SurfaceHolder holder) {
				Canvas c = holder.lockCanvas();
				if (c != null) {
					c.drawColor(Color.RED);
					holder.unlockCanvasAndPost(c);
				}
			}

			public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
				Log.w(TAG, "EMBED surfaceChanged " + width + "x" + height);
				if (host == null && width > 0 && height > 0)
					embed(width, height);
			}

			public void surfaceDestroyed(SurfaceHolder holder) {
			}
		});
	}

	void embed(int width, int height) {
		host = new SurfaceControlViewHost(this, getWindowManager().getDefaultDisplay(), surfaceView.getHostToken());
		LinearLayout content = new LinearLayout(this);
		content.setOrientation(LinearLayout.VERTICAL);
		content.setBackgroundColor(0x800000ff);
		TextView label = new TextView(this);
		label.setText("embedded window");
		label.setTextSize(32);
		label.setTextColor(Color.WHITE);
		content.addView(label);
		button = new Button(this);
		button.setText("Tap me");
		button.setTextSize(32);
		button.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				Log.w(TAG, "EMBED click");
				button.setText("Tapped");
			}
		});
		content.addView(button, new LinearLayout.LayoutParams(400, 160));
		host.setView(content, width / 2, height / 2);

		SurfaceControlViewHost.SurfacePackage pkg = host.getSurfacePackage();
		Parcel p = Parcel.obtain();
		pkg.writeToParcel(p, 0);
		p.setDataPosition(0);
		SurfaceControlViewHost.SurfacePackage remote = SurfaceControlViewHost.SurfacePackage.CREATOR.createFromParcel(p);
		p.recycle();
		pkg.release();
		surfaceView.setChildSurfacePackage(remote);
		Log.w(TAG, "EMBED attached hostToken=" + (surfaceView.getHostToken() != null) + " rootAttached=" + content.isAttachedToWindow());
		surfaceView.postDelayed(new Runnable() {
			public void run() {
				int[] loc = new int[2];
				button.getLocationOnScreen(loc);
				Log.w(TAG, "EMBED layout root=" + content.getWidth() + "x" + content.getHeight() + " button=" + loc[0] + "," + loc[1] + " "
				               + button.getWidth() + "x" + button.getHeight() + " shown=" + content.isShown() + " attached=" + content.isAttachedToWindow());
			}
		}, 2000);
	}
}
