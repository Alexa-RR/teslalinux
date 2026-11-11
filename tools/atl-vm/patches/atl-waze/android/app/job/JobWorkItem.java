/*
 * Copyright (C) 2017 The Android Open Source Project
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

import android.content.Intent;
import android.os.PersistableBundle;

/** teslalinux: AOSP's JobWorkItem (android14-release): a unit of work queued with JobScheduler.enqueue. */
public final class JobWorkItem {
	private final Intent mIntent;
	private final PersistableBundle mExtras;
	private final long mNetworkDownloadBytes;
	private final long mNetworkUploadBytes;
	private final long mMinimumChunkBytes;
	int mDeliveryCount;
	int mWorkId;

	public JobWorkItem(Intent intent) {
		this(intent, JobInfo.NETWORK_BYTES_UNKNOWN, JobInfo.NETWORK_BYTES_UNKNOWN, JobInfo.NETWORK_BYTES_UNKNOWN);
	}

	public JobWorkItem(Intent intent, long downloadBytes, long uploadBytes) {
		this(intent, downloadBytes, uploadBytes, JobInfo.NETWORK_BYTES_UNKNOWN);
	}

	public JobWorkItem(Intent intent, long downloadBytes, long uploadBytes, long minimumNetworkChunkBytes) {
		mIntent = intent;
		mExtras = new PersistableBundle();
		mNetworkDownloadBytes = downloadBytes;
		mNetworkUploadBytes = uploadBytes;
		mMinimumChunkBytes = minimumNetworkChunkBytes;
	}

	public Intent getIntent() {
		return mIntent;
	}

	public PersistableBundle getExtras() {
		return mExtras;
	}

	public long getEstimatedNetworkDownloadBytes() {
		return mNetworkDownloadBytes;
	}

	public long getEstimatedNetworkUploadBytes() {
		return mNetworkUploadBytes;
	}

	public long getMinimumNetworkChunkBytes() {
		return mMinimumChunkBytes;
	}

	/** AOSP: how many times this item was handed to the job (re-delivered after the job stopped without completing it). */
	public int getDeliveryCount() {
		return mDeliveryCount;
	}

	/** @hide */
	public int getWorkId() {
		return mWorkId;
	}

	@Override
	public String toString() {
		return "JobWorkItem{id=" + mWorkId + " intent=" + mIntent + " dcount=" + mDeliveryCount + "}";
	}
}
