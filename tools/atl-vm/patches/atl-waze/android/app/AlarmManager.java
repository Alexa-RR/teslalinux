/*
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.app;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.SystemClock;
import android.util.Slog;
import java.util.ArrayList;
import java.util.Iterator;

/**
 * teslalinux: AlarmManager after AOSP's AlarmManager / AlarmManagerService, in the app's process.
 * An alarm is identified by its PendingIntent (PendingIntent.equals) or its OnAlarmListener: setting
 * one again replaces it, cancel() removes it, repeating alarms re-arm until cancelled, and
 * setAlarmClock() alarms are reported by getNextAlarmClock(). The device does not sleep here, so
 * wakeup and non-wakeup alarms fire alike; inexact and windowed alarms fire at their earliest time.
 * Alarms live as long as the process, like AOSP's are cleared when the app is stopped.
 */
public class AlarmManager {
	private static final String TAG = "AlarmManager";

	public static final int RTC_WAKEUP = 0;
	public static final int RTC = 1;
	public static final int ELAPSED_REALTIME_WAKEUP = 2;
	public static final int ELAPSED_REALTIME = 3;

	public static final long INTERVAL_FIFTEEN_MINUTES = 15 * 60 * 1000;
	public static final long INTERVAL_HALF_HOUR = 2 * INTERVAL_FIFTEEN_MINUTES;
	public static final long INTERVAL_HOUR = 2 * INTERVAL_HALF_HOUR;
	public static final long INTERVAL_HALF_DAY = 12 * INTERVAL_HOUR;
	public static final long INTERVAL_DAY = 2 * INTERVAL_HALF_DAY;

	public static final String ACTION_NEXT_ALARM_CLOCK_CHANGED = "android.app.action.NEXT_ALARM_CLOCK_CHANGED";
	public static final String ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED = "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED";

	public static interface OnAlarmListener {
		void onAlarm();
	}

	/* one scheduled alarm */
	private static final class Alarm implements Runnable {
		final int type;
		long triggerAtMillis; /* in the alarm type's time base */
		final long interval;
		final PendingIntent operation;
		final OnAlarmListener listener;
		final Handler handler;
		final AlarmClockInfo alarmClock;
		java.util.concurrent.Executor executor;

		Alarm(int type, long triggerAtMillis, long interval, PendingIntent operation, OnAlarmListener listener, Handler handler, AlarmClockInfo alarmClock) {
			this.type = type;
			this.triggerAtMillis = triggerAtMillis;
			this.interval = interval;
			this.operation = operation;
			this.listener = listener;
			this.handler = handler;
			this.alarmClock = alarmClock;
		}

		boolean matches(PendingIntent pi, OnAlarmListener l) {
			return (pi != null && pi.equals(operation)) || (l != null && l == listener);
		}

		long delayMillis() {
			long now = (type == ELAPSED_REALTIME || type == ELAPSED_REALTIME_WAKEUP) ? SystemClock.elapsedRealtime() : System.currentTimeMillis();
			return Math.max(0, triggerAtMillis - now);
		}

		void arm() {
			sScheduler.postDelayed(this, delayMillis());
		}

		@Override
		public void run() {
			synchronized (sAlarms) {
				if (!sAlarms.contains(this))
					return;
				if (interval > 0) {
					/* AOSP: a repeating alarm's next time follows from the previous one, skipping missed ones */
					long now = (type == ELAPSED_REALTIME || type == ELAPSED_REALTIME_WAKEUP) ? SystemClock.elapsedRealtime() : System.currentTimeMillis();
					do {
						triggerAtMillis += interval;
					} while (triggerAtMillis <= now);
					arm();
				} else {
					sAlarms.remove(this);
				}
			}
			Slog.i(TAG, "delivering alarm: " + (operation != null ? operation : listener));
			if (listener != null) {
				Runnable deliver = new Runnable() {
					@Override
					public void run() {
						listener.onAlarm();
					}
				};
				if (executor != null)
					executor.execute(deliver);
				else
					(handler != null ? handler : sScheduler).post(deliver);
			} else if (operation != null) {
				Intent fill = new Intent();
				fill.putExtra("android.intent.extra.ALARM_COUNT", 1); /* Intent.EXTRA_ALARM_COUNT */
				try {
					operation.send(null, 0, fill);
				} catch (Exception e) {
					/* AOSP: a canceled PendingIntent's alarm is dropped */
					synchronized (sAlarms) {
						sAlarms.remove(this);
					}
					sScheduler.removeCallbacks(this);
				}
			}
		}
	}

	private static final ArrayList<Alarm> sAlarms = new ArrayList<>();
	private static final Handler sScheduler = new Handler(Looper.getMainLooper());

	private void setImpl(int type, long triggerAtMillis, long intervalMillis, PendingIntent operation,
	                     OnAlarmListener listener, Handler targetHandler, AlarmClockInfo alarmClock) {
		if (operation == null && listener == null)
			throw new NullPointerException("operation or listener must be non-null");
		if (type < RTC_WAKEUP || type > ELAPSED_REALTIME)
			throw new IllegalArgumentException("Invalid alarm type " + type);
		if (triggerAtMillis < 0)
			triggerAtMillis = 0;
		Alarm alarm = new Alarm(type, triggerAtMillis, intervalMillis, operation, listener, targetHandler, alarmClock);
		synchronized (sAlarms) {
			removeLocked(operation, listener);
			sAlarms.add(alarm);
			alarm.arm();
		}
	}

	private static void removeLocked(PendingIntent operation, OnAlarmListener listener) {
		for (Iterator<Alarm> it = sAlarms.iterator(); it.hasNext();) {
			Alarm a = it.next();
			if (a.matches(operation, listener)) {
				it.remove();
				sScheduler.removeCallbacks(a);
			}
		}
	}

	public void set(int type, long triggerAtMillis, PendingIntent operation) {
		setImpl(type, triggerAtMillis, 0, operation, null, null, null);
	}

	public void set(int type, long triggerAtMillis, String tag, OnAlarmListener listener, Handler targetHandler) {
		setImpl(type, triggerAtMillis, 0, null, listener, targetHandler, null);
	}

	public void setRepeating(int type, long triggerAtMillis, long intervalMillis, PendingIntent operation) {
		setImpl(type, triggerAtMillis, intervalMillis, operation, null, null, null);
	}

	public void setInexactRepeating(int type, long triggerAtMillis, long intervalMillis, PendingIntent operation) {
		setImpl(type, triggerAtMillis, intervalMillis, operation, null, null, null);
	}

	public void setWindow(int type, long windowStartMillis, long windowLengthMillis, PendingIntent operation) {
		setImpl(type, windowStartMillis, 0, operation, null, null, null);
	}

	public void setWindow(int type, long windowStartMillis, long windowLengthMillis, String tag, OnAlarmListener listener, Handler targetHandler) {
		setImpl(type, windowStartMillis, 0, null, listener, targetHandler, null);
	}

	public void setExact(int type, long triggerAtMillis, PendingIntent operation) {
		setImpl(type, triggerAtMillis, 0, operation, null, null, null);
	}

	public void setExact(int type, long triggerAtMillis, String tag, OnAlarmListener listener, Handler targetHandler) {
		setImpl(type, triggerAtMillis, 0, null, listener, targetHandler, null);
	}

	public void setAndAllowWhileIdle(int type, long triggerAtMillis, PendingIntent operation) {
		setImpl(type, triggerAtMillis, 0, operation, null, null, null);
	}

	public void setExactAndAllowWhileIdle(int type, long triggerAtMillis, PendingIntent operation) {
		setImpl(type, triggerAtMillis, 0, operation, null, null, null);
	}

	public void setExactAndAllowWhileIdle(int type, long triggerAtMillis, String tag, final java.util.concurrent.Executor executor, final OnAlarmListener listener) {
		/* the listener stays the alarm's identity (cancel(listener)); delivery goes through the executor */
		setImpl(type, triggerAtMillis, 0, null, listener, null, null);
		synchronized (sAlarms) {
			for (Alarm a : sAlarms)
				if (a.listener == listener)
					a.executor = executor;
		}
	}

	/** AOSP: an RTC_WAKEUP alarm that is also the user's next alarm clock (getNextAlarmClock). */
	public void setAlarmClock(AlarmClockInfo info, PendingIntent operation) {
		setImpl(RTC_WAKEUP, info.getTriggerTime(), 0, operation, null, null, info);
	}

	public void cancel(PendingIntent operation) {
		if (operation == null) {
			throw new NullPointerException("cancel() called with a null PendingIntent");
		}
		synchronized (sAlarms) {
			removeLocked(operation, null);
		}
	}

	public void cancel(OnAlarmListener listener) {
		if (listener == null) {
			throw new NullPointerException("cancel() called with a null OnAlarmListener");
		}
		synchronized (sAlarms) {
			removeLocked(null, listener);
		}
	}

	/** AOSP (API 34): cancels every alarm of the app. */
	public void cancelAll() {
		synchronized (sAlarms) {
			for (Alarm a : sAlarms)
				sScheduler.removeCallbacks(a);
			sAlarms.clear();
		}
	}

	/** AOSP: the earliest pending setAlarmClock() alarm (of this app: the only one here). */
	public AlarmClockInfo getNextAlarmClock() {
		AlarmClockInfo next = null;
		synchronized (sAlarms) {
			for (Alarm a : sAlarms) {
				if (a.alarmClock != null && (next == null || a.alarmClock.getTriggerTime() < next.getTriggerTime()))
					next = a.alarmClock;
			}
		}
		return next;
	}

	/**
	 * AOSP (API 31): an app targeting S or later needs SCHEDULE_EXACT_ALARM or USE_EXACT_ALARM for
	 * exact alarms; earlier targets always may.
	 */
	public boolean canScheduleExactAlarms() {
		Context context = android.atl.ATLLoadedApp.getPrimaryApplication().getApplication();
		if (context.getApplicationInfo().targetSdkVersion < Build.VERSION_CODES.S)
			return true;
		return context.checkSelfPermission("android.permission.SCHEDULE_EXACT_ALARM") == PackageManager.PERMISSION_GRANTED
		    || context.checkSelfPermission("android.permission.USE_EXACT_ALARM") == PackageManager.PERMISSION_GRANTED;
	}

	/** AOSP: needs the system's SET_TIME permission, which an app does not get. */
	public void setTime(long millis) {
		throw new SecurityException("setTime: Neither user " + android.os.Process.myUid() + " nor current process has android.permission.SET_TIME.");
	}

	/** AOSP: needs SET_TIME_ZONE, which an app does not get. */
	public void setTimeZone(String timeZone) {
		throw new SecurityException("setTimeZone: Neither user " + android.os.Process.myUid() + " nor current process has android.permission.SET_TIME_ZONE.");
	}

	public static final class AlarmClockInfo implements Parcelable {
		private final long mTriggerTime;
		private final PendingIntent mShowIntent;

		public AlarmClockInfo(long triggerTime, PendingIntent showIntent) {
			mTriggerTime = triggerTime;
			mShowIntent = showIntent;
		}

		AlarmClockInfo(Parcel in) {
			mTriggerTime = in.readLong();
			PendingIntent pi;
			try {
				pi = (PendingIntent) in.readParcelable(PendingIntent.class.getClassLoader());
			} catch (ReflectiveOperationException e) {
				pi = null;
			}
			mShowIntent = pi;
		}

		public long getTriggerTime() {
			return mTriggerTime;
		}

		public PendingIntent getShowIntent() {
			return mShowIntent;
		}

		@Override
		public int describeContents() {
			return 0;
		}

		@Override
		public void writeToParcel(Parcel dest, int flags) {
			dest.writeLong(mTriggerTime);
			dest.writeParcelable(mShowIntent, flags);
		}

		public static final Creator<AlarmClockInfo> CREATOR = new Creator<AlarmClockInfo>() {
			@Override
			public AlarmClockInfo createFromParcel(Parcel in) {
				return new AlarmClockInfo(in);
			}

			@Override
			public AlarmClockInfo[] newArray(int size) {
				return new AlarmClockInfo[size];
			}
		};
	}
}
