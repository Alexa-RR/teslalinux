/*
 * Copyright (C) 2012 The Android Open Source Project
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
import android.content.pm.ActivityInfo;
import android.content.pm.PackageParser;
import android.os.Bundle;
import android.util.Log;
import java.util.ArrayList;

/**
 * teslalinux: AOSP's TaskStackBuilder (android14-release): a synthetic back stack built from the
 * manifest's android:parentActivityName chain. The parent metadata comes from the app's parsed
 * manifest (the app's own activities are the only ones a task here can hold).
 */
public class TaskStackBuilder {
	private static final String TAG = "TaskStackBuilder";

	private final ArrayList<Intent> mIntents = new ArrayList<Intent>();
	private final Context mSourceContext;

	private TaskStackBuilder(Context a) {
		mSourceContext = a;
	}

	public static TaskStackBuilder create(Context context) {
		return new TaskStackBuilder(context);
	}

	public TaskStackBuilder addNextIntent(Intent nextIntent) {
		mIntents.add(nextIntent);
		return this;
	}

	public TaskStackBuilder addNextIntentWithParentStack(Intent nextIntent) {
		ComponentName target = nextIntent.getComponent();
		if (target == null) {
			target = nextIntent.resolveActivity(mSourceContext.getPackageManager());
		}
		if (target != null) {
			addParentStack(target);
		}
		addNextIntent(nextIntent);
		return this;
	}

	public TaskStackBuilder addParentStack(Activity sourceActivity) {
		final Intent parent = sourceActivity.getParentActivityIntent();
		if (parent != null) {
			// We have the actual parent intent, build the rest from static metadata
			// then add the direct parent intent to the end.
			ComponentName target = parent.getComponent();
			if (target == null) {
				target = parent.resolveActivity(mSourceContext.getPackageManager());
			}
			addParentStack(target);
			addNextIntent(parent);
		}
		return this;
	}

	public TaskStackBuilder addParentStack(Class<?> sourceActivityClass) {
		return addParentStack(new ComponentName(mSourceContext, sourceActivityClass));
	}

	public TaskStackBuilder addParentStack(ComponentName sourceActivityName) {
		final int insertAt = mIntents.size();
		ActivityInfo info = activityInfo(sourceActivityName.getClassName());
		if (info == null) {
			Log.e(TAG, "Bad ComponentName while traversing activity parent metadata");
			throw new IllegalArgumentException("No activity " + sourceActivityName);
		}
		String parentActivity = info.parentActivityName;
		while (parentActivity != null) {
			final ComponentName target = new ComponentName(mSourceContext.getPackageName(), parentActivity);
			info = activityInfo(parentActivity);
			if (info == null) {
				Log.e(TAG, "Bad ComponentName while traversing activity parent metadata");
				throw new IllegalArgumentException("No activity " + target);
			}
			parentActivity = info.parentActivityName;
			final Intent parent = parentActivity == null && insertAt == 0
			    ? Intent.makeMainActivity(target)
			    : new Intent().setComponent(target);
			mIntents.add(insertAt, parent);
		}
		return this;
	}

	private ActivityInfo activityInfo(String className) {
		ATLLoadedApp app = ATLLoadedApp.getPrimaryApplication();
		if (app == null || app.pkg == null || className == null)
			return null;
		if (className.startsWith("."))
			className = app.pkg.packageName + className;
		for (PackageParser.Activity a : app.pkg.activities) {
			if (className.equals(a.className) || className.equals(a.info.name))
				return a.info;
		}
		return null;
	}

	public int getIntentCount() {
		return mIntents.size();
	}

	public Intent editIntentAt(int index) {
		return mIntents.get(index);
	}

	public void startActivities() {
		startActivities(null);
	}

	public void startActivities(Bundle options) {
		if (mIntents.isEmpty()) {
			throw new IllegalStateException("No intents added to TaskStackBuilder; cannot startActivities");
		}
		mSourceContext.startActivities(getIntents(), options);
	}

	public PendingIntent getPendingIntent(int requestCode, int flags) {
		return getPendingIntent(requestCode, flags, null);
	}

	public PendingIntent getPendingIntent(int requestCode, int flags, Bundle options) {
		if (mIntents.isEmpty()) {
			throw new IllegalStateException("No intents added to TaskStackBuilder; cannot getPendingIntent");
		}
		return PendingIntent.getActivities(mSourceContext, requestCode, getIntents(), flags, options);
	}

	public Intent[] getIntents() {
		Intent[] intents = new Intent[mIntents.size()];
		if (intents.length == 0)
			return intents;
		intents[0] = new Intent(mIntents.get(0)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | 0x00008000 /* FLAG_ACTIVITY_CLEAR_TASK */ | 0x00004000 /* FLAG_ACTIVITY_TASK_ON_HOME */);
		for (int i = 1; i < intents.length; i++) {
			intents[i] = new Intent(mIntents.get(i));
		}
		return intents;
	}
}
