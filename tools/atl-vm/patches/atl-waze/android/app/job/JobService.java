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

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/**
 * teslalinux: AOSP's JobService. JobScheduler (in this process) starts it and calls onStartJob /
 * onStopJob; jobFinished hands the job back for rescheduling (backoff or the next period).
 */
public abstract class JobService extends Service {
	public static final String PERMISSION_BIND = "android.permission.BIND_JOB_SERVICE";
	public static final int JOB_END_NOTIFICATION_POLICY_DETACH = 0;
	public static final int JOB_END_NOTIFICATION_POLICY_REMOVE = 1;

	public abstract boolean onStartJob(JobParameters params);

	public abstract boolean onStopJob(JobParameters params);

	/** AOSP: the system binds the service to run jobs; here JobScheduler calls it directly, so there is no binder. */
	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

	public final void jobFinished(JobParameters params, boolean wantsReschedule) {
		JobScheduler.jobFinished(params.mRecord, wantsReschedule);
	}

	/** AOSP (API 34): the job's network changed while it runs; the default does nothing. */
	public void onNetworkChanged(JobParameters params) {
	}
}
