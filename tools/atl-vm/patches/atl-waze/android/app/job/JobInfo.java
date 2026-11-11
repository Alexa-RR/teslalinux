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
import android.content.ComponentName;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.Bundle;
import android.os.PersistableBundle;
import java.util.ArrayList;
import java.util.Objects;

/**
 * teslalinux: AOSP's JobInfo (android14-release): every Builder setting is kept, validated as in
 * AOSP's build(), and read back by the getters; JobScheduler runs the job by these constraints.
 */
public class JobInfo {
	public static final int NETWORK_TYPE_NONE = 0;
	public static final int NETWORK_TYPE_ANY = 1;
	public static final int NETWORK_TYPE_UNMETERED = 2;
	public static final int NETWORK_TYPE_NOT_ROAMING = 3;
	public static final int NETWORK_TYPE_CELLULAR = 4;
	@Deprecated public static final int NETWORK_TYPE_METERED = NETWORK_TYPE_CELLULAR;
	public static final int NETWORK_BYTES_UNKNOWN = -1;
	public static final long DEFAULT_INITIAL_BACKOFF_MILLIS = 30000L;
	public static final long MAX_BACKOFF_DELAY_MILLIS = 5 * 60 * 60 * 1000;
	public static final int BACKOFF_POLICY_LINEAR = 0;
	public static final int BACKOFF_POLICY_EXPONENTIAL = 1;
	private static final long MIN_PERIOD_MILLIS = 15 * 60 * 1000L;
	private static final long MIN_FLEX_MILLIS = 5 * 60 * 1000L;
	private static final long MIN_BACKOFF_MILLIS = 10 * 1000L;
	public static final int PRIORITY_MIN = 100;
	public static final int PRIORITY_LOW = 200;
	public static final int PRIORITY_DEFAULT = 300;
	public static final int PRIORITY_HIGH = 400;
	public static final int PRIORITY_MAX = 500;
	public static final int CONSTRAINT_FLAG_CHARGING = 1 << 0;
	public static final int CONSTRAINT_FLAG_BATTERY_NOT_LOW = 1 << 1;
	public static final int CONSTRAINT_FLAG_DEVICE_IDLE = 1 << 2;
	public static final int CONSTRAINT_FLAG_STORAGE_NOT_LOW = 1 << 3;
	public static final int FLAG_EXPEDITED = 1 << 4;
	public static final int FLAG_IMPORTANT_WHILE_FOREGROUND = 1 << 1;
	public static final int FLAG_PREFETCH = 1 << 2;
	public static final int FLAG_USER_INITIATED = 1 << 5;

	public static long getMinPeriodMillis() {
		return MIN_PERIOD_MILLIS;
	}

	public static long getMinFlexMillis() {
		return MIN_FLEX_MILLIS;
	}

	public static final long getMinBackoffMillis() {
		return MIN_BACKOFF_MILLIS;
	}

	private int jobId;
	private PersistableBundle extras;
	private Bundle transientExtras;
	private ClipData clipData;
	private int clipGrantFlags;
	private ComponentName service;
	private int constraintFlags;
	private TriggerContentUri[] triggerContentUris;
	private long triggerContentUpdateDelay = -1;
	private long triggerContentMaxDelay = -1;
	private boolean hasEarlyConstraint;
	private boolean hasLateConstraint;
	private NetworkRequest networkRequest;
	private int networkType = NETWORK_TYPE_NONE;
	private long networkDownloadBytes = NETWORK_BYTES_UNKNOWN;
	private long networkUploadBytes = NETWORK_BYTES_UNKNOWN;
	private long minimumNetworkChunkBytes = NETWORK_BYTES_UNKNOWN;
	private long minLatencyMillis;
	private long maxExecutionDelayMillis;
	private boolean isPeriodic;
	private boolean isPersisted;
	private long intervalMillis;
	private long flexMillis;
	private long initialBackoffMillis = DEFAULT_INITIAL_BACKOFF_MILLIS;
	private int backoffPolicy = BACKOFF_POLICY_EXPONENTIAL;
	private int mPriority = PRIORITY_DEFAULT;
	private int flags;
	private String mTraceTag;

	private JobInfo() {
	}

	public int getId() { return jobId; }
	public PersistableBundle getExtras() { return extras; }
	public Bundle getTransientExtras() { return transientExtras; }
	public ClipData getClipData() { return clipData; }
	public int getClipGrantFlags() { return clipGrantFlags; }
	public ComponentName getService() { return service; }
	public int getPriority() { return mPriority; }
	/** @hide */ public int getFlags() { return flags; }
	public boolean isExpedited() { return (flags & FLAG_EXPEDITED) != 0; }
	public boolean isUserInitiated() { return (flags & FLAG_USER_INITIATED) != 0; }
	@Deprecated public boolean isImportantWhileForeground() { return (flags & FLAG_IMPORTANT_WHILE_FOREGROUND) != 0; }
	public boolean isPrefetch() { return (flags & FLAG_PREFETCH) != 0; }
	public boolean isRequireCharging() { return (constraintFlags & CONSTRAINT_FLAG_CHARGING) != 0; }
	public boolean isRequireBatteryNotLow() { return (constraintFlags & CONSTRAINT_FLAG_BATTERY_NOT_LOW) != 0; }
	public boolean isRequireDeviceIdle() { return (constraintFlags & CONSTRAINT_FLAG_DEVICE_IDLE) != 0; }
	public boolean isRequireStorageNotLow() { return (constraintFlags & CONSTRAINT_FLAG_STORAGE_NOT_LOW) != 0; }
	/** @hide */ public int getConstraintFlags() { return constraintFlags; }
	public TriggerContentUri[] getTriggerContentUris() { return triggerContentUris; }
	public long getTriggerContentUpdateDelay() { return triggerContentUpdateDelay; }
	public long getTriggerContentMaxDelay() { return triggerContentMaxDelay; }
	@Deprecated public int getNetworkType() { return networkType; }
	public NetworkRequest getRequiredNetwork() { return networkRequest; }
	public long getEstimatedNetworkDownloadBytes() { return networkDownloadBytes; }
	public long getEstimatedNetworkUploadBytes() { return networkUploadBytes; }
	public long getMinimumNetworkChunkBytes() { return minimumNetworkChunkBytes; }
	public long getMinLatencyMillis() { return minLatencyMillis; }
	public long getMaxExecutionDelayMillis() { return maxExecutionDelayMillis; }
	public boolean isPeriodic() { return isPeriodic; }
	public boolean isPersisted() { return isPersisted; }
	public long getIntervalMillis() { return intervalMillis; }
	public long getFlexMillis() { return flexMillis; }
	public long getInitialBackoffMillis() { return initialBackoffMillis; }
	public int getBackoffPolicy() { return backoffPolicy; }
	public String getTraceTag() { return mTraceTag; }
	/** @hide */ public boolean hasEarlyConstraint() { return hasEarlyConstraint; }
	/** @hide */ public boolean hasLateConstraint() { return hasLateConstraint; }

	@Override
	public boolean equals(Object o) {
		if (!(o instanceof JobInfo))
			return false;
		JobInfo j = (JobInfo) o;
		return jobId == j.jobId && Objects.equals(service, j.service) && constraintFlags == j.constraintFlags
		    && networkType == j.networkType && Objects.equals(networkRequest, j.networkRequest)
		    && minLatencyMillis == j.minLatencyMillis && maxExecutionDelayMillis == j.maxExecutionDelayMillis
		    && isPeriodic == j.isPeriodic && isPersisted == j.isPersisted && intervalMillis == j.intervalMillis
		    && flexMillis == j.flexMillis && initialBackoffMillis == j.initialBackoffMillis
		    && backoffPolicy == j.backoffPolicy && mPriority == j.mPriority && flags == j.flags
		    && triggerContentUpdateDelay == j.triggerContentUpdateDelay && triggerContentMaxDelay == j.triggerContentMaxDelay
		    && java.util.Arrays.equals(triggerContentUris, j.triggerContentUris);
	}

	@Override
	public int hashCode() {
		return Objects.hash(jobId, service, constraintFlags, networkType, minLatencyMillis, maxExecutionDelayMillis,
		                    isPeriodic, intervalMillis, flexMillis, flags, mPriority);
	}

	@Override
	public String toString() {
		return "(job:" + jobId + "/" + (service != null ? service.flattenToShortString() : null) + ")";
	}

	/** AOSP: a content URI whose changes trigger the job. */
	public static final class TriggerContentUri {
		public static final int FLAG_NOTIFY_FOR_DESCENDANTS = 1 << 0;
		private final Uri mUri;
		private final int mFlags;

		public TriggerContentUri(Uri uri, int flags) {
			mUri = Objects.requireNonNull(uri);
			mFlags = flags;
		}

		public Uri getUri() {
			return mUri;
		}

		public int getFlags() {
			return mFlags;
		}

		@Override
		public boolean equals(Object o) {
			if (!(o instanceof TriggerContentUri))
				return false;
			TriggerContentUri t = (TriggerContentUri) o;
			return Objects.equals(t.mUri, mUri) && t.mFlags == mFlags;
		}

		@Override
		public int hashCode() {
			return (mUri == null ? 0 : mUri.hashCode()) ^ mFlags;
		}
	}

	public static final class Builder {
		private final int mJobId;
		private final ComponentName mJobService;
		private PersistableBundle mExtras = new PersistableBundle();
		private Bundle mTransientExtras = Bundle.EMPTY;
		private ClipData mClipData;
		private int mClipGrantFlags;
		private int mPriority = PRIORITY_DEFAULT;
		private int mFlags;
		private int mConstraintFlags;
		private NetworkRequest mNetworkRequest;
		private long mNetworkDownloadBytes = NETWORK_BYTES_UNKNOWN;
		private long mNetworkUploadBytes = NETWORK_BYTES_UNKNOWN;
		private long mMinimumNetworkChunkBytes = NETWORK_BYTES_UNKNOWN;
		private ArrayList<TriggerContentUri> mTriggerContentUris;
		private long mTriggerContentUpdateDelay = -1;
		private long mTriggerContentMaxDelay = -1;
		private boolean mIsPersisted;
		private long mMinLatencyMillis;
		private long mMaxExecutionDelayMillis;
		private boolean mIsPeriodic;
		private boolean mHasEarlyConstraint;
		private boolean mHasLateConstraint;
		private long mIntervalMillis;
		private long mFlexMillis;
		private long mInitialBackoffMillis = DEFAULT_INITIAL_BACKOFF_MILLIS;
		private int mBackoffPolicy = BACKOFF_POLICY_EXPONENTIAL;
		private boolean mBackoffPolicySet = false;
		private int mNetworkType = NETWORK_TYPE_NONE;
		private String mTraceTag;

		public Builder(int jobId, ComponentName jobService) {
			mJobService = jobService;
			mJobId = jobId;
		}

		public Builder setPriority(int priority) {
			mPriority = priority;
			return this;
		}

		public Builder setExtras(PersistableBundle extras) {
			mExtras = extras;
			return this;
		}

		public Builder setTransientExtras(Bundle extras) {
			mTransientExtras = extras;
			return this;
		}

		public Builder setClipData(ClipData clip, int grantFlags) {
			mClipData = clip;
			mClipGrantFlags = grantFlags;
			return this;
		}

		public Builder setRequiredNetwork(NetworkRequest networkRequest) {
			mNetworkRequest = networkRequest;
			mNetworkType = networkRequest == null ? NETWORK_TYPE_NONE : NETWORK_TYPE_ANY;
			return this;
		}

		public Builder setRequiredNetworkType(int networkType) {
			mNetworkType = networkType;
			if (networkType == NETWORK_TYPE_NONE) {
				mNetworkRequest = null;
			} else {
				NetworkRequest.Builder builder = new NetworkRequest.Builder();
				if (networkType == NETWORK_TYPE_UNMETERED) {
					builder.addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
				} else if (networkType == NETWORK_TYPE_NOT_ROAMING) {
					builder.addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING);
				} else if (networkType == NETWORK_TYPE_CELLULAR) {
					builder.addTransportType(android.net.NetworkCapabilities.TRANSPORT_CELLULAR);
				}
				mNetworkRequest = builder.build();
			}
			return this;
		}

		public Builder setEstimatedNetworkBytes(long downloadBytes, long uploadBytes) {
			mNetworkDownloadBytes = downloadBytes;
			mNetworkUploadBytes = uploadBytes;
			return this;
		}

		public Builder setMinimumNetworkChunkBytes(long chunkSizeBytes) {
			if (chunkSizeBytes != NETWORK_BYTES_UNKNOWN && chunkSizeBytes <= 0) {
				throw new IllegalArgumentException("Minimum chunk size must be positive");
			}
			mMinimumNetworkChunkBytes = chunkSizeBytes;
			return this;
		}

		public Builder setRequiresCharging(boolean requiresCharging) {
			mConstraintFlags = (mConstraintFlags & ~CONSTRAINT_FLAG_CHARGING) | (requiresCharging ? CONSTRAINT_FLAG_CHARGING : 0);
			return this;
		}

		public Builder setRequiresBatteryNotLow(boolean batteryNotLow) {
			mConstraintFlags = (mConstraintFlags & ~CONSTRAINT_FLAG_BATTERY_NOT_LOW) | (batteryNotLow ? CONSTRAINT_FLAG_BATTERY_NOT_LOW : 0);
			return this;
		}

		public Builder setRequiresDeviceIdle(boolean requiresDeviceIdle) {
			mConstraintFlags = (mConstraintFlags & ~CONSTRAINT_FLAG_DEVICE_IDLE) | (requiresDeviceIdle ? CONSTRAINT_FLAG_DEVICE_IDLE : 0);
			return this;
		}

		public Builder setRequiresStorageNotLow(boolean storageNotLow) {
			mConstraintFlags = (mConstraintFlags & ~CONSTRAINT_FLAG_STORAGE_NOT_LOW) | (storageNotLow ? CONSTRAINT_FLAG_STORAGE_NOT_LOW : 0);
			return this;
		}

		public Builder addTriggerContentUri(TriggerContentUri uri) {
			if (mTriggerContentUris == null) {
				mTriggerContentUris = new ArrayList<>();
			}
			mTriggerContentUris.add(uri);
			return this;
		}

		public Builder setTriggerContentUpdateDelay(long durationMs) {
			mTriggerContentUpdateDelay = durationMs;
			return this;
		}

		public Builder setTriggerContentMaxDelay(long durationMs) {
			mTriggerContentMaxDelay = durationMs;
			return this;
		}

		public Builder setPeriodic(long intervalMillis) {
			return setPeriodic(intervalMillis, intervalMillis);
		}

		public Builder setPeriodic(long intervalMillis, long flexMillis) {
			final long minPeriod = getMinPeriodMillis();
			if (intervalMillis < minPeriod)
				intervalMillis = minPeriod;
			final long percentClamp = 5 * intervalMillis / 100;
			final long minFlex = Math.max(percentClamp, getMinFlexMillis());
			if (flexMillis < minFlex)
				flexMillis = minFlex;
			mIsPeriodic = true;
			mIntervalMillis = intervalMillis;
			mFlexMillis = flexMillis;
			mHasEarlyConstraint = mHasLateConstraint = true;
			return this;
		}

		public Builder setMinimumLatency(long minLatencyMillis) {
			mMinLatencyMillis = minLatencyMillis;
			mHasEarlyConstraint = true;
			return this;
		}

		public Builder setOverrideDeadline(long maxExecutionDelayMillis) {
			mMaxExecutionDelayMillis = maxExecutionDelayMillis;
			mHasLateConstraint = true;
			return this;
		}

		public Builder setBackoffCriteria(long initialBackoffMillis, int backoffPolicy) {
			final long minBackoff = getMinBackoffMillis();
			if (initialBackoffMillis < minBackoff)
				initialBackoffMillis = minBackoff;
			mBackoffPolicySet = true;
			mInitialBackoffMillis = initialBackoffMillis;
			mBackoffPolicy = backoffPolicy;
			return this;
		}

		public Builder setExpedited(boolean expedited) {
			mFlags = expedited ? (mFlags | FLAG_EXPEDITED) : (mFlags & ~FLAG_EXPEDITED);
			if (expedited)
				mPriority = PRIORITY_MAX;
			else if (mPriority == PRIORITY_MAX)
				mPriority = PRIORITY_DEFAULT;
			return this;
		}

		public Builder setUserInitiated(boolean userInitiated) {
			mFlags = userInitiated ? (mFlags | FLAG_USER_INITIATED) : (mFlags & ~FLAG_USER_INITIATED);
			return this;
		}

		@Deprecated
		public Builder setImportantWhileForeground(boolean importantWhileForeground) {
			mFlags = importantWhileForeground ? (mFlags | FLAG_IMPORTANT_WHILE_FOREGROUND) : (mFlags & ~FLAG_IMPORTANT_WHILE_FOREGROUND);
			return this;
		}

		public Builder setPrefetch(boolean prefetch) {
			mFlags = prefetch ? (mFlags | FLAG_PREFETCH) : (mFlags & ~FLAG_PREFETCH);
			return this;
		}

		public Builder setPersisted(boolean isPersisted) {
			mIsPersisted = isPersisted;
			return this;
		}

		public Builder setTraceTag(String traceTag) {
			mTraceTag = traceTag;
			return this;
		}

		/** AOSP: builds the job after the same validity checks (JobInfo.enforceValidity). */
		public JobInfo build() {
			if (mJobService == null)
				throw new IllegalArgumentException("You're trying to build a job without a service");
			if ((mFlags & FLAG_EXPEDITED) != 0) {
				if (mHasEarlyConstraint || mHasLateConstraint)
					throw new IllegalArgumentException("An expedited job cannot have a time delay or deadline");
				if (mIsPeriodic)
					throw new IllegalArgumentException("An expedited job cannot be periodic");
				if ((mConstraintFlags & ~CONSTRAINT_FLAG_STORAGE_NOT_LOW) != 0 || mTriggerContentUris != null)
					throw new IllegalArgumentException("An expedited job can only have network and storage-not-low constraints");
				if (mBackoffPolicySet && mBackoffPolicy != BACKOFF_POLICY_EXPONENTIAL)
					throw new IllegalArgumentException("An expedited job must use exponential backoff");
			}
			if (mIsPeriodic) {
				if (mMaxExecutionDelayMillis != 0L)
					throw new IllegalArgumentException("Can't call setOverrideDeadline() on a periodic job.");
				if (mMinLatencyMillis != 0L)
					throw new IllegalArgumentException("Can't call setMinimumLatency() on a periodic job");
				if (mTriggerContentUris != null)
					throw new IllegalArgumentException("Can't call addTriggerContentUri() on a periodic job");
			}
			if (mIsPersisted && mTriggerContentUris != null)
				throw new IllegalArgumentException("Can't call addTriggerContentUri() on a persisted job");
			if ((mConstraintFlags & CONSTRAINT_FLAG_DEVICE_IDLE) != 0 && mBackoffPolicySet)
				throw new IllegalArgumentException("An idle mode job will not respect any back-off policy, so calling setBackoffCriteria with setRequiresDeviceIdle is an error.");
			JobInfo job = new JobInfo();
			job.jobId = mJobId;
			job.extras = mExtras != null ? mExtras : new PersistableBundle();
			job.transientExtras = mTransientExtras;
			job.clipData = mClipData;
			job.clipGrantFlags = mClipGrantFlags;
			job.service = mJobService;
			job.constraintFlags = mConstraintFlags;
			job.triggerContentUris = mTriggerContentUris != null ? mTriggerContentUris.toArray(new TriggerContentUri[0]) : null;
			job.triggerContentUpdateDelay = mTriggerContentUpdateDelay;
			job.triggerContentMaxDelay = mTriggerContentMaxDelay;
			job.networkRequest = mNetworkRequest;
			job.networkType = mNetworkType;
			job.networkDownloadBytes = mNetworkDownloadBytes;
			job.networkUploadBytes = mNetworkUploadBytes;
			job.minimumNetworkChunkBytes = mMinimumNetworkChunkBytes;
			job.minLatencyMillis = mMinLatencyMillis;
			job.maxExecutionDelayMillis = mMaxExecutionDelayMillis;
			job.isPeriodic = mIsPeriodic;
			job.isPersisted = mIsPersisted;
			job.intervalMillis = mIntervalMillis;
			job.flexMillis = mFlexMillis;
			job.initialBackoffMillis = mInitialBackoffMillis;
			job.backoffPolicy = mBackoffPolicy;
			job.mPriority = mPriority;
			job.flags = mFlags;
			job.hasEarlyConstraint = mHasEarlyConstraint;
			job.hasLateConstraint = mHasLateConstraint;
			job.mTraceTag = mTraceTag;
			return job;
		}
	}
}
