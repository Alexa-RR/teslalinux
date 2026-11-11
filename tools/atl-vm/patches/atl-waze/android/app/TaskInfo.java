/*
 * Copyright (C) 2018 The Android Open Source Project
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

import android.content.ComponentName;
import android.content.Intent;
import android.content.LocusId;

/**
 * teslalinux: AOSP's TaskInfo (android14-release, the public part): what ActivityManager reports
 * about a task. This runtime has one task, holding the app's activities
 * (ActivityManager.AppTask.getTaskInfo fills it from Activity's task bookkeeping).
 */
public class TaskInfo {
	public int userId;
	public int taskId;
	public boolean isRunning;
	public Intent baseIntent;
	public ComponentName baseActivity;
	public ComponentName topActivity;
	public ComponentName origActivity;
	public ComponentName realActivity;
	public int numActivities;
	public long lastActiveTime;
	public int displayId;
	public ActivityManager.TaskDescription taskDescription;
	public boolean isFocused;
	public boolean isVisible;
	public boolean isSleeping;
	public LocusId mTopActivityLocusId;

	TaskInfo() {
	}

	public boolean isVisible() {
		return isVisible;
	}

	public boolean isFocused() {
		return isFocused;
	}

	public boolean isRunning() {
		return isRunning;
	}

	public int getTaskId() {
		return taskId;
	}

	public int getUserId() {
		return userId;
	}

	public LocusId getTopActivityLocusId() {
		return mTopActivityLocusId;
	}

	public ActivityManager.TaskDescription getTaskDescription() {
		return taskDescription;
	}

	@Override
	public String toString() {
		return "TaskInfo{userId=" + userId + " taskId=" + taskId + " displayId=" + displayId
		    + " isFocused=" + isFocused + " isVisible=" + isVisible + " isRunning=" + isRunning
		    + " baseIntent=" + baseIntent + " baseActivity=" + baseActivity
		    + " topActivity=" + topActivity + " origActivity=" + origActivity
		    + " realActivity=" + realActivity + " numActivities=" + numActivities
		    + " lastActiveTime=" + lastActiveTime + " taskDescription=" + taskDescription + "}";
	}
}
