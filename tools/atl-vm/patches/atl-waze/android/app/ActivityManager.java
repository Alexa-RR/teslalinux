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

import android.atl.ATLLoadedApp;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ConfigurationInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.Process;
import android.os.SystemProperties;
import android.util.DisplayMetrics;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * teslalinux: ActivityManager after AOSP (android14-release) for a runtime that is one app process
 * with one task. Process, memory and task queries answer from this process (/proc for memory, the
 * activity bookkeeping in Activity for the task) the way AOSP answers an app about itself.
 */
public class ActivityManager {
	private static final String TAG = "ActivityManager";

	/* ---------------- processes ---------------- */

	public static class RunningAppProcessInfo implements Parcelable {
		public String processName;
		public int pid;
		public int uid;
		public String pkgList[];
		public int flags;
		public int lastTrimLevel;
		public int importance;
		public int lru;
		public int importanceReasonCode;
		public int importanceReasonPid;
		public ComponentName importanceReasonComponent;
		public int importanceReasonImportance;
		public int processState;
		public boolean isFocused;
		public long lastActivityTime;

		public static final int IMPORTANCE_FOREGROUND = 100;
		public static final int IMPORTANCE_FOREGROUND_SERVICE = 125;
		public static final int IMPORTANCE_TOP_SLEEPING_PRE_28 = 150;
		public static final int IMPORTANCE_VISIBLE = 200;
		public static final int IMPORTANCE_PERCEPTIBLE_PRE_26 = 130;
		public static final int IMPORTANCE_PERCEPTIBLE = 230;
		public static final int IMPORTANCE_CANT_SAVE_STATE_PRE_26 = 170;
		public static final int IMPORTANCE_SERVICE = 300;
		public static final int IMPORTANCE_TOP_SLEEPING = 325;
		public static final int IMPORTANCE_CANT_SAVE_STATE = 350;
		public static final int IMPORTANCE_CACHED = 400;
		public static final int IMPORTANCE_BACKGROUND = IMPORTANCE_CACHED;
		public static final int IMPORTANCE_EMPTY = 500;
		public static final int IMPORTANCE_GONE = 1000;
		public static final int REASON_UNKNOWN = 0;
		public static final int REASON_PROVIDER_IN_USE = 1;
		public static final int REASON_SERVICE_IN_USE = 2;

		public RunningAppProcessInfo() {
			importance = IMPORTANCE_FOREGROUND;
			importanceReasonCode = REASON_UNKNOWN;
			processState = 2; /* PROCESS_STATE_IMPORTANT_FOREGROUND */
			isFocused = false;
			lastActivityTime = 0;
		}

		public RunningAppProcessInfo(String pProcessName, int pPid, String pArr[]) {
			processName = pProcessName;
			pid = pPid;
			pkgList = pArr;
			isFocused = false;
			lastActivityTime = 0;
		}

		public int describeContents() {
			return 0;
		}

		public void writeToParcel(Parcel dest, int flags) {
			dest.writeString(processName);
			dest.writeInt(pid);
			dest.writeInt(uid);
			dest.writeStringArray(pkgList);
			dest.writeInt(this.flags);
			dest.writeInt(lastTrimLevel);
			dest.writeInt(importance);
			dest.writeInt(lru);
			dest.writeInt(importanceReasonCode);
			dest.writeInt(importanceReasonPid);
			dest.writeInt(importanceReasonImportance);
			dest.writeInt(processState);
			dest.writeBoolean(isFocused);
			dest.writeLong(lastActivityTime);
		}

		@Override
		public String toString() {
			return "RunningAppProcessInfo{" + processName + " pid=" + pid + " uid=" + uid + " importance=" + importance + "}";
		}
	}

	/** The importance of this process: foreground while an activity is visible, else foreground-service or cached (AOSP OomAdjuster in short). */
	static void tlFillMyProcess(RunningAppProcessInfo outState) {
		ATLLoadedApp app = ATLLoadedApp.getPrimaryApplication();
		outState.pid = Process.myPid();
		outState.uid = Process.myUid();
		outState.processName = app != null && app.pkg != null ? app.pkg.applicationInfo.processName != null ? app.pkg.applicationInfo.processName : app.pkg.packageName : null;
		outState.pkgList = app != null && app.pkg != null ? new String[] {app.pkg.packageName} : null;
		outState.lastTrimLevel = 0;
		int started;
		synchronized (Activity.tlLiveActivities) {
			started = Activity.tlStartedCount;
		}
		if (started > 0) {
			outState.importance = RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
			outState.processState = 2; /* PROCESS_STATE_TOP */
			outState.isFocused = true;
		} else if (Service.tlForegroundCount() > 0) {
			outState.importance = RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE;
			outState.processState = 4; /* PROCESS_STATE_FOREGROUND_SERVICE */
		} else {
			outState.importance = RunningAppProcessInfo.IMPORTANCE_CACHED;
			outState.processState = 16; /* PROCESS_STATE_CACHED_ACTIVITY */
		}
		outState.importanceReasonCode = RunningAppProcessInfo.REASON_UNKNOWN;
		outState.lastActivityTime = Activity.tlTaskLastActiveTime;
	}

	/** AOSP: the app's own processes (an app only sees itself): here, this one. */
	public List<RunningAppProcessInfo> getRunningAppProcesses() {
		RunningAppProcessInfo info = new RunningAppProcessInfo();
		tlFillMyProcess(info);
		List<RunningAppProcessInfo> list = new ArrayList<>();
		list.add(info);
		return list;
	}

	public static void getMyMemoryState(RunningAppProcessInfo outState) {
		tlFillMyProcess(outState);
	}

	/* ---------------- memory ---------------- */

	/** AOSP: ro.config.low_ram, the device's low-RAM configuration. */
	public boolean isLowRamDevice() {
		return isLowRamDeviceStatic();
	}

	/** @hide */
	public static boolean isLowRamDeviceStatic() {
		return "true".equals(SystemProperties.get("ro.config.low_ram", "false"));
	}

	public static class MemoryInfo implements Parcelable {
		public long advertisedMem;
		public long availMem;
		public long totalMem;
		public long threshold;
		public boolean lowMemory;
		/** @hide */ public long hiddenAppThreshold;
		/** @hide */ public long secondaryServerThreshold;
		/** @hide */ public long visibleAppThreshold;
		/** @hide */ public long foregroundAppThreshold;

		public MemoryInfo() {
		}

		public int describeContents() {
			return 0;
		}

		public void writeToParcel(Parcel dest, int flags) {
			dest.writeLong(advertisedMem);
			dest.writeLong(availMem);
			dest.writeLong(totalMem);
			dest.writeLong(threshold);
			dest.writeBoolean(lowMemory);
			dest.writeLong(hiddenAppThreshold);
			dest.writeLong(secondaryServerThreshold);
			dest.writeLong(visibleAppThreshold);
			dest.writeLong(foregroundAppThreshold);
		}
	}

	/**
	 * AOSP: totalMem and availMem from the kernel (/proc/meminfo MemTotal and MemAvailable), the
	 * threshold the low memory killer starts killing cached processes at, lowMemory when the free
	 * memory is under it. The thresholds are AOSP ProcessList's minfree levels for a device of this
	 * memory size and a 64-bit kernel (ProcessList.updateOomLevels).
	 */
	public void getMemoryInfo(MemoryInfo outInfo) {
		long total = 0, available = -1, free = 0, cached = 0;
		try (BufferedReader r = new BufferedReader(new FileReader("/proc/meminfo"))) {
			String line;
			while ((line = r.readLine()) != null) {
				if (line.startsWith("MemTotal:"))
					total = tlMeminfoKb(line);
				else if (line.startsWith("MemAvailable:"))
					available = tlMeminfoKb(line);
				else if (line.startsWith("MemFree:"))
					free = tlMeminfoKb(line);
				else if (line.startsWith("Cached:"))
					cached = tlMeminfoKb(line);
			}
		} catch (IOException e) {
			android.util.Log.w(TAG, "getMemoryInfo: /proc/meminfo: " + e);
		}
		if (available < 0)
			available = free + cached;
		long[] minfreeKb = tlOomMinFreeKb(total * 1024);
		outInfo.totalMem = total * 1024;
		outInfo.advertisedMem = outInfo.totalMem;
		outInfo.availMem = available * 1024;
		outInfo.foregroundAppThreshold = minfreeKb[0] * 1024;
		outInfo.visibleAppThreshold = minfreeKb[1] * 1024;
		outInfo.secondaryServerThreshold = minfreeKb[2] * 1024;
		outInfo.hiddenAppThreshold = minfreeKb[5] * 1024;
		outInfo.threshold = outInfo.hiddenAppThreshold;
		outInfo.lowMemory = outInfo.availMem < outInfo.threshold;
	}

	private static long tlMeminfoKb(String line) {
		String[] parts = line.trim().split("\\s+");
		try {
			return parts.length >= 2 ? Long.parseLong(parts[1]) : 0;
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	/* AOSP ProcessList.updateOomLevels: minfree levels (KB) interpolated between the low and high
	 * tables by memory size (350..700 MB), raised for 64-bit cached levels. */
	private static long[] tlOomMinFreeKb(long totalBytes) {
		final int[] low = {12288, 18432, 24576, 36864, 43008, 49152};
		final int[] high = {73728, 92160, 110592, 129024, 147456, 184320};
		high[4] = 225000;
		high[5] = 325000;
		float scaleMem = ((float) (totalBytes / (1024 * 1024) - 350)) / (700 - 350);
		if (scaleMem < 0)
			scaleMem = 0;
		if (scaleMem > 1)
			scaleMem = 1;
		long[] out = new long[6];
		for (int i = 0; i < 6; i++)
			out[i] = (long) (low[i] + (high[i] - low[i]) * scaleMem);
		return out;
	}

	/**
	 * AOSP: dalvik.vm.heapgrowthlimit (the heap limit of an app without largeHeap), in MB. ART's
	 * runtime here is started with the limit that Runtime.maxMemory reports, which is used when the
	 * property is not set.
	 */
	public int getMemoryClass() {
		return staticGetMemoryClass();
	}

	/** @hide */
	public static int staticGetMemoryClass() {
		String vmHeapSize = SystemProperties.get("dalvik.vm.heapgrowthlimit", "");
		if (vmHeapSize != null && !"".equals(vmHeapSize)) {
			return Integer.parseInt(vmHeapSize.substring(0, vmHeapSize.length() - 1));
		}
		return staticGetLargeMemoryClass();
	}

	/** AOSP: dalvik.vm.heapsize (the largeHeap limit), in MB; else the running VM's maximum heap. */
	public int getLargeMemoryClass() {
		return staticGetLargeMemoryClass();
	}

	/** @hide */
	public static int staticGetLargeMemoryClass() {
		String vmHeapSize = SystemProperties.get("dalvik.vm.heapsize", "");
		if (vmHeapSize != null && !"".equals(vmHeapSize)) {
			return Integer.parseInt(vmHeapSize.substring(0, vmHeapSize.length() - 1));
		}
		return (int) (Runtime.getRuntime().maxMemory() / (1024 * 1024));
	}

	/** AOSP: the GL ES version and input requirements the device configuration offers. */
	public ConfigurationInfo getDeviceConfigurationInfo() {
		ConfigurationInfo info = new ConfigurationInfo();
		int gles = SystemProperties.getInt("ro.opengles.version", 0);
		if (gles != 0)
			info.reqGlEsVersion = gles;
		return info;
	}

	/**
	 * AOSP: the system wipes the app's data and kills it. This runtime has no package manager
	 * service to do that from outside the process, so the request is refused (false), as AOSP
	 * returns when the clear could not be started.
	 */
	public boolean clearApplicationUserData() {
		return false;
	}

	public static boolean isUserAMonkey() {
		return false;
	}

	/** AOSP (static, deprecated): whether the device runs under an instrumentation test harness (ro.test_harness). */
	public static boolean isRunningInTestHarness() {
		return SystemProperties.getInt("ro.test_harness", 0) == 1;
	}

	public static boolean isRunningInUserTestHarness() {
		return SystemProperties.getInt("persist.sys.test_harness", 0) == 1;
	}

	/** AOSP: true when the user restricted the app's background work; there is no such setting here. */
	public boolean isBackgroundRestricted() {
		return false;
	}

	/* ---------------- launcher icons (AOSP) ---------------- */

	public int getLauncherLargeIconSize() {
		return getLauncherLargeIconSizeInner(tlContext());
	}

	static int getLauncherLargeIconSizeInner(Context context) {
		final Resources res = context.getResources();
		final int size = res.getDimensionPixelSize(android.R.dimen.app_icon_size);
		final int sw = res.getConfiguration().smallestScreenWidthDp;
		if (sw < 600) {
			// Smaller than approx 7" tablets, use the regular icon size.
			return size;
		}
		final int density = res.getDisplayMetrics().densityDpi;
		switch (density) {
			case DisplayMetrics.DENSITY_LOW:
				return (size * DisplayMetrics.DENSITY_MEDIUM) / DisplayMetrics.DENSITY_LOW;
			case DisplayMetrics.DENSITY_MEDIUM:
				return (size * DisplayMetrics.DENSITY_HIGH) / DisplayMetrics.DENSITY_MEDIUM;
			case DisplayMetrics.DENSITY_TV:
				return (size * DisplayMetrics.DENSITY_XHIGH) / DisplayMetrics.DENSITY_HIGH;
			case DisplayMetrics.DENSITY_HIGH:
				return (size * DisplayMetrics.DENSITY_XHIGH) / DisplayMetrics.DENSITY_HIGH;
			case DisplayMetrics.DENSITY_XHIGH:
				return (size * DisplayMetrics.DENSITY_XXHIGH) / DisplayMetrics.DENSITY_XHIGH;
			case DisplayMetrics.DENSITY_XXHIGH:
				return (size * DisplayMetrics.DENSITY_XHIGH * 2) / DisplayMetrics.DENSITY_XXHIGH;
			default:
				// The density is some abnormal value.  Return some other
				// abnormal value that is a reasonable scaling of it.
				return (int) ((size * 1.5f) + .5f);
		}
	}

	public int getLauncherLargeIconDensity() {
		final Resources res = tlContext().getResources();
		final int density = res.getDisplayMetrics().densityDpi;
		final int sw = res.getConfiguration().smallestScreenWidthDp;
		if (sw < 600) {
			// Smaller than approx 7" tablets, use the regular icon size.
			return density;
		}
		switch (density) {
			case DisplayMetrics.DENSITY_LOW:
				return DisplayMetrics.DENSITY_MEDIUM;
			case DisplayMetrics.DENSITY_MEDIUM:
				return DisplayMetrics.DENSITY_HIGH;
			case DisplayMetrics.DENSITY_TV:
				return DisplayMetrics.DENSITY_XHIGH;
			case DisplayMetrics.DENSITY_HIGH:
				return DisplayMetrics.DENSITY_XHIGH;
			case DisplayMetrics.DENSITY_XHIGH:
				return DisplayMetrics.DENSITY_XXHIGH;
			case DisplayMetrics.DENSITY_XXHIGH:
				return DisplayMetrics.DENSITY_XHIGH * 2;
			default:
				// The density is some abnormal value.  Return some other
				// abnormal value that is a reasonable scaling of it.
				return (int) ((density * 1.5f) + .5f);
		}
	}

	private static Context tlContext() {
		return ATLLoadedApp.getPrimaryApplication().getApplication();
	}

	/* ---------------- tasks ---------------- */

	/** AOSP: the label, icon and colours of a task in recents. */
	public static class TaskDescription implements Parcelable {
		private String mLabel;
		private Bitmap mIcon;
		private int mIconRes;
		private int mColorPrimary;
		private int mColorBackground;
		private int mStatusBarColor;
		private int mNavigationBarColor;

		public TaskDescription(String label, Bitmap icon, int colorPrimary) {
			this(label, icon, 0, colorPrimary);
		}

		public TaskDescription(String label, int iconRes, int colorPrimary) {
			this(label, null, iconRes, colorPrimary);
			if ((colorPrimary != 0) && (android.graphics.Color.alpha(colorPrimary) != 255)) {
				throw new RuntimeException("A TaskDescription's primary color should be opaque");
			}
		}

		public TaskDescription(String label, int iconRes) {
			this(label, null, iconRes, 0);
		}

		public TaskDescription(String label, Bitmap icon) {
			this(label, icon, 0, 0);
		}

		public TaskDescription(String label) {
			this(label, null, 0, 0);
		}

		public TaskDescription() {
			this(null, null, 0, 0);
		}

		private TaskDescription(String label, Bitmap icon, int iconRes, int colorPrimary) {
			mLabel = label;
			mIcon = icon;
			mIconRes = iconRes;
			mColorPrimary = colorPrimary;
		}

		public TaskDescription(TaskDescription td) {
			copyFrom(td);
		}

		/** @hide */
		public void copyFrom(TaskDescription other) {
			mLabel = other.mLabel;
			mIcon = other.mIcon;
			mIconRes = other.mIconRes;
			mColorPrimary = other.mColorPrimary;
			mColorBackground = other.mColorBackground;
			mStatusBarColor = other.mStatusBarColor;
			mNavigationBarColor = other.mNavigationBarColor;
		}

		public String getLabel() {
			return mLabel;
		}

		public Bitmap getIcon() {
			return mIcon;
		}

		/** @hide */
		public int getIconResource() {
			return mIconRes;
		}

		public int getPrimaryColor() {
			return mColorPrimary;
		}

		public int getBackgroundColor() {
			return mColorBackground;
		}

		public int getStatusBarColor() {
			return mStatusBarColor;
		}

		public int getNavigationBarColor() {
			return mNavigationBarColor;
		}

		public int describeContents() {
			return 0;
		}

		public void writeToParcel(Parcel dest, int flags) {
			dest.writeString(mLabel);
			dest.writeInt(mIconRes);
			dest.writeInt(mColorPrimary);
			dest.writeInt(mColorBackground);
			dest.writeInt(mStatusBarColor);
			dest.writeInt(mNavigationBarColor);
		}

		@Override
		public String toString() {
			return "TaskDescription Label: " + mLabel + " Icon: " + mIcon + " IconRes: " + mIconRes
			    + " colorPrimary: " + mColorPrimary + " colorBackground: " + mColorBackground
			    + " statusBarColor: " + mStatusBarColor + " navigationBarColor: " + mNavigationBarColor;
		}

		/** AOSP (API 33) builder. */
		public static final class Builder {
			private String mLabel;
			private int mIconRes;
			private int mPrimaryColor;
			private int mBackgroundColor;
			private int mStatusBarColor;
			private int mNavigationBarColor;

			public Builder setLabel(String label) {
				mLabel = label;
				return this;
			}

			public Builder setIcon(int iconRes) {
				mIconRes = iconRes;
				return this;
			}

			public Builder setPrimaryColor(int color) {
				mPrimaryColor = color;
				return this;
			}

			public Builder setBackgroundColor(int color) {
				mBackgroundColor = color;
				return this;
			}

			public Builder setStatusBarColor(int color) {
				mStatusBarColor = color;
				return this;
			}

			public Builder setNavigationBarColor(int color) {
				mNavigationBarColor = color;
				return this;
			}

			public TaskDescription build() {
				TaskDescription td = new TaskDescription(mLabel, null, mIconRes, mPrimaryColor);
				td.mColorBackground = mBackgroundColor;
				td.mStatusBarColor = mStatusBarColor;
				td.mNavigationBarColor = mNavigationBarColor;
				return td;
			}
		}
	}

	/** AOSP: a task in the recents list. */
	public static class RecentTaskInfo extends TaskInfo {
		@Deprecated public int id;
		@Deprecated public int persistentId;
		public int affiliatedTaskId;
		public RecentTaskInfo() {
		}
	}

	/** AOSP: a running task. */
	public static class RunningTaskInfo extends TaskInfo {
		@Deprecated public int id;
		@Deprecated public CharSequence description;
		@Deprecated public int numRunning;
		public RunningTaskInfo() {
		}
	}

	/** The one task: the app's activities in launch order (Activity.tlLiveActivities). */
	static int tlTaskId() {
		ATLLoadedApp app = ATLLoadedApp.getPrimaryApplication();
		return System.identityHashCode(app != null ? app.getApplication() : null);
	}

	static void tlFillTaskInfo(TaskInfo info) {
		List<Activity> all = Activity.tlActivities();
		info.userId = Process.myUid() / android.os.UserHandle.PER_USER_RANGE;
		info.taskId = tlTaskId();
		info.isRunning = !all.isEmpty();
		info.numActivities = all.size();
		if (!all.isEmpty()) {
			Activity root = all.get(0);
			Activity top = all.get(all.size() - 1);
			info.baseIntent = root.getIntent() != null ? new Intent(root.getIntent()) : null;
			info.baseActivity = new ComponentName(root, root.getClass());
			info.realActivity = info.baseActivity;
			info.topActivity = new ComponentName(top, top.getClass());
			int started;
			synchronized (Activity.tlLiveActivities) {
				started = Activity.tlStartedCount;
			}
			info.isVisible = started > 0;
			info.isFocused = info.isVisible;
		}
		synchronized (Activity.tlLiveActivities) {
			info.lastActiveTime = Activity.tlTaskLastActiveTime;
			info.taskDescription = Activity.tlTaskDescription != null ? new TaskDescription(Activity.tlTaskDescription) : new TaskDescription();
		}
	}

	/** AOSP: a task of the calling app, which it can inspect and manage. */
	public static class AppTask {
		AppTask() {
		}

		/** AOSP: finishes all activities of the task and removes it from recents. */
		public void finishAndRemoveTask() {
			List<Activity> all = Activity.tlActivities();
			for (int i = all.size() - 1; i >= 0; i--)
				all.get(i).finish();
		}

		public RecentTaskInfo getTaskInfo() {
			RecentTaskInfo info = new RecentTaskInfo();
			tlFillTaskInfo(info);
			info.id = info.isRunning ? info.taskId : -1;
			info.persistentId = info.taskId;
			info.affiliatedTaskId = info.taskId;
			return info;
		}

		/** AOSP: brings the task to the front; it is always in front here, the only task on the display. */
		public void moveToFront() {
		}

		/** AOSP: starts an activity in this task. */
		public void startActivity(Context context, Intent intent, Bundle options) {
			context.startActivity(intent, options);
		}

		/**
		 * AOSP: hides the task from recents. There is no recents UI on this platform, so there is
		 * nothing for the flag to change.
		 */
		public void setExcludeFromRecents(boolean exclude) {
		}
	}

	/** AOSP: the app's own tasks; here the one task while it has activities. */
	public List<ActivityManager.AppTask> getAppTasks() {
		List<AppTask> tasks = new ArrayList<>();
		if (!Activity.tlActivities().isEmpty())
			tasks.add(new AppTask());
		return tasks;
	}

	/** AOSP (deprecated): only the caller's own tasks are returned since API 21. */
	@Deprecated
	public List<RunningTaskInfo> getRunningTasks(int maxNum) {
		List<RunningTaskInfo> list = new ArrayList<>();
		if (maxNum > 0 && !Activity.tlActivities().isEmpty()) {
			RunningTaskInfo info = new RunningTaskInfo();
			tlFillTaskInfo(info);
			info.id = info.taskId;
			info.numRunning = info.numActivities;
			list.add(info);
		}
		return list;
	}

	/** AOSP: the task is always the front one on this display. */
	public void moveTaskToFront(int taskId, int flags) {
		moveTaskToFront(taskId, flags, null);
	}

	public void moveTaskToFront(int taskId, int flags, Bundle options) {
	}

	/* ---------------- services ---------------- */

	public static class RunningServiceInfo implements Parcelable {
		public ComponentName service;
		public int pid;
		public int uid;
		public String process;
		public boolean foreground;
		public long activeSince;
		public boolean started;
		public int clientCount;
		public int crashCount;
		public long lastActivityTime;
		public long restarting;
		public int flags;
		public String clientPackage;
		public int clientLabel;

		public static final int FLAG_STARTED = 1 << 0;
		public static final int FLAG_FOREGROUND = 1 << 1;
		public static final int FLAG_SYSTEM_PROCESS = 1 << 2;
		public static final int FLAG_PERSISTENT_PROCESS = 1 << 3;

		public RunningServiceInfo() {
		}

		public int describeContents() {
			return 0;
		}

		public void writeToParcel(Parcel dest, int flags) {
			dest.writeInt(pid);
			dest.writeInt(uid);
			dest.writeString(process);
			dest.writeBoolean(foreground);
			dest.writeLong(activeSince);
			dest.writeBoolean(started);
			dest.writeInt(clientCount);
			dest.writeInt(crashCount);
			dest.writeLong(lastActivityTime);
			dest.writeLong(restarting);
			dest.writeInt(this.flags);
			dest.writeString(clientPackage);
			dest.writeInt(clientLabel);
		}

		public void readFromParcel(Parcel source) {
			pid = source.readInt();
			uid = source.readInt();
			process = source.readString();
			foreground = source.readBoolean();
			activeSince = source.readLong();
			started = source.readBoolean();
			clientCount = source.readInt();
			crashCount = source.readInt();
			lastActivityTime = source.readLong();
			restarting = source.readLong();
			flags = source.readInt();
			clientPackage = source.readString();
			clientLabel = source.readInt();
		}
	}

	/** AOSP (deprecated): only the caller's own services since API 26; Service keeps the running ones. */
	@Deprecated
	public List<RunningServiceInfo> getRunningServices(int maxNum) throws SecurityException {
		return Service.tlRunningServiceInfos(maxNum);
	}

	/**
	 * AOSP: the exit records ProcessList kept for the package. This runtime has no system process
	 * that outlives the app to record its exits, so there are none.
	 */
	public List<ApplicationExitInfo> getHistoricalProcessExitReasons(String packageName, int pid, int maxNum) {
		if (maxNum < 0 || pid < 0)
			throw new IllegalArgumentException("pid and maxNum must not be negative");
		return Collections.emptyList();
	}

	/** AOSP: a summary saved with the next exit record; nothing records exits here (see getHistoricalProcessExitReasons). */
	public void setProcessStateSummary(byte[] state) {
		if (state != null && state.length > 128)
			throw new IllegalArgumentException("Data size is too large");
	}
}
