package org.teslalinux.lifecycletest;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.widget.TextView;

/**
 * ATL runtime test of activities started for a result that finish at once, the way apps ask for
 * permissions through a small activity of their own (Waze's RequestPermissionActivity):
 *   1. NowActivity finishes in onCreate (the permission is already granted, nothing to ask);
 *   2. AskActivity asks with requestPermissions and finishes in onRequestPermissionsResult;
 *   3. TranslucentActivity (Theme.Translucent.NoTitleBar, as Waze's permission and calendar
 *      activities are translucent and floating) shows over MainActivity and finishes after a while:
 *      MainActivity must stay started and visible under it (paused, not stopped: AOSP keeps the
 *      activity below a translucent one visible) and get only onResume afterwards.
 * Each returns a result with a "granted" extra. MainActivity must get both results and be resumed
 * and shown again; the number of started activities (what AndroidX's ProcessLifecycleOwner and apps'
 * own foreground trackers count) must never drop to 0, which an app reads as "went to the
 * background". Every lifecycle event is logged as "LIFE ..." (tag LifecycleTest); run.sh checks them.
 */
public class LifecycleTest {
	static final String TAG = "LifecycleTest";
	static int started = 0;
	static boolean wentBackground = false;
	static boolean underTranslucent = false;
	static boolean mainStoppedUnderTranslucent = false;
	static boolean mainRestartedUnderTranslucent = false;

	static void log(String s) {
		Log.w(TAG, "LIFE " + s + " started=" + started);
	}

	static void track(Application app) {
		app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
			public void onActivityCreated(Activity a, Bundle b) { log(name(a) + " created"); }
			public void onActivityStarted(Activity a) {
				started++;
				log(name(a) + " started");
				if (underTranslucent && a instanceof MainActivity)
					mainRestartedUnderTranslucent = true;
			}
			public void onActivityResumed(Activity a) { log(name(a) + " resumed"); }
			public void onActivityPaused(Activity a) { log(name(a) + " paused"); }
			public void onActivityStopped(Activity a) {
				started--;
				log(name(a) + " stopped");
				if (underTranslucent && a instanceof MainActivity)
					mainStoppedUnderTranslucent = true;
				if (started == 0) {
					wentBackground = true;
					log("BACKGROUND");
				}
			}
			public void onActivitySaveInstanceState(Activity a, Bundle b) {}
			public void onActivityDestroyed(Activity a) { log(name(a) + " destroyed"); }
		});
	}

	static String name(Activity a) {
		return a.getClass().getSimpleName();
	}

	public static class MainActivity extends Activity {
		TextView text;
		boolean resumed;
		int results;

		@Override
		protected void onCreate(Bundle b) {
			track(getApplication());
			super.onCreate(b);
			text = new TextView(this);
			text.setGravity(Gravity.CENTER);
			text.setTextSize(40);
			text.setText("waiting");
			text.setBackgroundColor(Color.rgb(200, 0, 0));
			setContentView(text);
			log("MainActivity onCreate done");
			new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
				public void run() {
					log("starting NowActivity");
					startActivityForResult(new Intent(MainActivity.this, NowActivity.class), 1);
				}
			}, 2000);
		}

		@Override
		protected void onResume() {
			super.onResume();
			resumed = true;
		}

		@Override
		protected void onPause() {
			super.onPause();
			resumed = false;
		}

		@Override
		protected void onActivityResult(int requestCode, int resultCode, Intent data) {
			super.onActivityResult(requestCode, resultCode, data);
			boolean granted = data != null && data.getBooleanExtra("granted", false);
			log("RESULT request=" + requestCode + " code=" + resultCode + " granted=" + granted);
			results++;
			if (requestCode == 1) {
				text.setText("result 1");
				text.setBackgroundColor(Color.rgb(200, 200, 0));
				new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
					public void run() {
						log("starting AskActivity");
						startActivityForResult(new Intent(MainActivity.this, AskActivity.class), 2);
					}
				}, 1500);
			} else if (requestCode == 2) {
				text.setText("result 2");
				text.setBackgroundColor(Color.rgb(0, 160, 0));
				new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
					public void run() {
						log("starting TranslucentActivity");
						underTranslucent = true;
						startActivityForResult(new Intent(MainActivity.this, TranslucentActivity.class), 3);
					}
				}, 1500);
			} else {
				underTranslucent = false;
				text.setText("result 3");
				text.setBackgroundColor(Color.rgb(0, 0, 200));
				new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
					public void run() {
						log("FINAL results=" + results + " mainResumed=" + resumed + " background=" + wentBackground
							+ " stoppedUnderTranslucent=" + mainStoppedUnderTranslucent
							+ " restartedUnderTranslucent=" + mainRestartedUnderTranslucent
							+ " finishing=" + isFinishing() + " destroyed=" + isDestroyed());
					}
				}, 1500);
			}
		}
	}

	/** finishes in onCreate with its result, like a permission activity whose permission is already granted */
	public static class NowActivity extends Activity {
		@Override
		protected void onCreate(Bundle b) {
			super.onCreate(b);
			setResult(RESULT_OK, new Intent().putExtra("granted", true));
			finish();
		}
	}

	/** a translucent activity over MainActivity: a half-transparent band; finishes after 6 s (time for a screenshot) with a result */
	public static class TranslucentActivity extends Activity {
		@Override
		protected void onCreate(Bundle b) {
			super.onCreate(b);
			TextView t = new TextView(this);
			t.setText("translucent");
			t.setTextSize(40);
			t.setGravity(Gravity.CENTER);
			t.setBackgroundColor(Color.argb(120, 255, 255, 255));
			setContentView(t);
			log("TranslucentActivity shown");
			new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
				public void run() {
					log("TranslucentActivity finishing");
					setResult(RESULT_OK, new Intent().putExtra("granted", true));
					finish();
				}
			}, 6000);
		}
	}

	/** asks for a permission and finishes with the answer */
	public static class AskActivity extends Activity {
		@Override
		protected void onCreate(Bundle b) {
			super.onCreate(b);
			TextView t = new TextView(this);
			t.setText("asking");
			setContentView(t);
			requestPermissions(new String[] {"android.permission.READ_CALENDAR"}, 7);
		}

		@Override
		public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
			boolean granted = grantResults.length > 0 && grantResults[0] == 0;
			log("AskActivity permission result granted=" + granted);
			setResult(RESULT_OK, new Intent().putExtra("granted", granted));
			finish();
		}
	}
}
