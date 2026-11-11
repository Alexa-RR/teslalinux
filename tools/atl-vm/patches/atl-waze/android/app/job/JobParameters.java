/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.app.job;

import android.content.ClipData;
import android.net.Network;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.PersistableBundle;

/**
 * teslalinux: AOSP's JobParameters (android14-release): what a running job is given. Work items
 * (dequeueWork/completeWork) come from JobScheduler's queue for the job.
 */
public class JobParameters implements Parcelable {
	public static final int STOP_REASON_UNDEFINED = 0;
	public static final int STOP_REASON_CANCELLED_BY_APP = 1;
	public static final int STOP_REASON_PREEMPT = 2;
	public static final int STOP_REASON_TIMEOUT = 3;
	public static final int STOP_REASON_DEVICE_STATE = 4;
	public static final int STOP_REASON_CONSTRAINT_BATTERY_NOT_LOW = 5;
	public static final int STOP_REASON_CONSTRAINT_CHARGING = 6;
	public static final int STOP_REASON_CONSTRAINT_CONNECTIVITY = 7;
	public static final int STOP_REASON_CONSTRAINT_DEVICE_IDLE = 8;
	public static final int STOP_REASON_CONSTRAINT_STORAGE_NOT_LOW = 9;
	public static final int STOP_REASON_QUOTA = 10;
	public static final int STOP_REASON_BACKGROUND_RESTRICTION = 11;
	public static final int STOP_REASON_APP_STANDBY = 12;
	public static final int STOP_REASON_USER = 13;
	public static final int STOP_REASON_SYSTEM_PROCESSING = 14;
	public static final int STOP_REASON_ESTIMATED_APP_LAUNCH_TIME_CHANGED = 15;

	public static final Creator<JobParameters> CREATOR = new Creator<JobParameters>() {
		@Override
		public JobParameters createFromParcel(Parcel in) {
			throw new UnsupportedOperationException("JobParameters are created by JobScheduler, not parcelled in this runtime");
		}

		@Override
		public JobParameters[] newArray(int size) {
			return new JobParameters[size];
		}
	};

	final JobInfo jobInfo;
	final JobScheduler.JobRecord mRecord;
	private final Uri[] mTriggeredContentUris;
	private final String[] mTriggeredContentAuthorities;
	private final Network mNetwork;
	private final boolean mOverrideDeadlineExpired;
	int mStopReason = STOP_REASON_UNDEFINED;

	JobParameters(JobScheduler.JobRecord record, Uri[] triggeredUris, String[] triggeredAuthorities, Network network, boolean deadlineExpired) {
		this.jobInfo = record.job;
		this.mRecord = record;
		this.mTriggeredContentUris = triggeredUris;
		this.mTriggeredContentAuthorities = triggeredAuthorities;
		this.mNetwork = network;
		this.mOverrideDeadlineExpired = deadlineExpired;
	}

	public int getJobId() {
		return jobInfo.getId();
	}

	public String getJobNamespace() {
		return mRecord.namespace;
	}

	public int getStopReason() {
		return mStopReason;
	}

	public PersistableBundle getExtras() {
		return jobInfo.getExtras();
	}

	public Bundle getTransientExtras() {
		return jobInfo.getTransientExtras();
	}

	public ClipData getClipData() {
		return jobInfo.getClipData();
	}

	public int getClipGrantFlags() {
		return jobInfo.getClipGrantFlags();
	}

	public boolean isExpeditedJob() {
		return jobInfo.isExpedited();
	}

	public boolean isUserInitiatedJob() {
		return jobInfo.isUserInitiated();
	}

	public boolean isOverrideDeadlineExpired() {
		return mOverrideDeadlineExpired;
	}

	/** AOSP: the content URIs whose change triggered the job, or null when it was not a content trigger. */
	public Uri[] getTriggeredContentUris() {
		return mTriggeredContentUris;
	}

	public String[] getTriggeredContentAuthorities() {
		return mTriggeredContentAuthorities;
	}

	/** AOSP: the network the job's network constraint was satisfied with, or null. */
	public Network getNetwork() {
		return mNetwork;
	}

	/** AOSP: the next queued work item (enqueue), or null when the queue is empty. */
	public JobWorkItem dequeueWork() {
		return mRecord.dequeueWork();
	}

	/** AOSP: the item is done; when no work is left the job finishes. */
	public void completeWork(JobWorkItem work) {
		if (!mRecord.completeWork(work))
			throw new IllegalArgumentException("Given work is not active: " + work);
	}

	@Override
	public int describeContents() {
		return 0;
	}

	@Override
	public void writeToParcel(Parcel dest, int flags) {
		dest.writeInt(getJobId());
		dest.writeInt(mStopReason);
	}
}
