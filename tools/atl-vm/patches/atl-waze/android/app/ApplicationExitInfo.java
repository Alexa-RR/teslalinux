/*
 * Copyright (C) 2019 The Android Open Source Project
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

import android.os.UserHandle;
import java.io.IOException;
import java.io.InputStream;

/**
 * teslalinux: AOSP's ApplicationExitInfo (android14-release, the public API): the record of why
 * a process of the app ended. On Android the system's ProcessList keeps these records; this runtime
 * has no such system service, so ActivityManager.getHistoricalProcessExitReasons has no records to
 * return (see there) and no instance is created by the platform.
 */
public final class ApplicationExitInfo {
	public static final int REASON_UNKNOWN = 0;
	public static final int REASON_EXIT_SELF = 1;
	public static final int REASON_SIGNALED = 2;
	public static final int REASON_LOW_MEMORY = 3;
	public static final int REASON_CRASH = 4;
	public static final int REASON_CRASH_NATIVE = 5;
	public static final int REASON_ANR = 6;
	public static final int REASON_INITIALIZATION_FAILURE = 7;
	public static final int REASON_PERMISSION_CHANGE = 8;
	public static final int REASON_EXCESSIVE_RESOURCE_USAGE = 9;
	public static final int REASON_USER_REQUESTED = 10;
	public static final int REASON_USER_STOPPED = 11;
	public static final int REASON_DEPENDENCY_DIED = 12;
	public static final int REASON_OTHER = 13;
	public static final int REASON_FREEZER = 14;
	public static final int REASON_PACKAGE_STATE_CHANGE = 15;
	public static final int REASON_PACKAGE_UPDATED = 16;

	private int mPid;
	private int mRealUid;
	private int mPackageUid;
	private int mDefiningUid;
	private String mProcessName;
	private int mReason;
	private int mStatus;
	private int mImportance;
	private long mPss;
	private long mRss;
	private long mTimestamp;
	private String mDescription;
	private byte[] mState;
	private byte[] mTrace;

	/** @hide */
	public ApplicationExitInfo() {
	}

	public int getPid() { return mPid; }
	public int getRealUid() { return mRealUid; }
	public int getPackageUid() { return mPackageUid; }
	public int getDefiningUid() { return mDefiningUid; }
	public String getProcessName() { return mProcessName; }
	public int getReason() { return mReason; }
	public int getStatus() { return mStatus; }
	public int getImportance() { return mImportance; }
	public long getPss() { return mPss; }
	public long getRss() { return mRss; }
	public long getTimestamp() { return mTimestamp; }
	public String getDescription() { return mDescription; }
	public UserHandle getUserHandle() { return new UserHandle(mRealUid / UserHandle.PER_USER_RANGE); }
	public byte[] getProcessStateSummary() { return mState; }

	/** AOSP: the ANR traces / native tombstone of the exit, when the system kept one. */
	public InputStream getTraceInputStream() throws IOException {
		return mTrace != null ? new java.io.ByteArrayInputStream(mTrace) : null;
	}

	/** @hide */ public void setPid(int pid) { mPid = pid; }
	/** @hide */ public void setRealUid(int uid) { mRealUid = uid; }
	/** @hide */ public void setPackageUid(int uid) { mPackageUid = uid; }
	/** @hide */ public void setDefiningUid(int uid) { mDefiningUid = uid; }
	/** @hide */ public void setProcessName(String processName) { mProcessName = processName; }
	/** @hide */ public void setReason(int reason) { mReason = reason; }
	/** @hide */ public void setStatus(int status) { mStatus = status; }
	/** @hide */ public void setImportance(int importance) { mImportance = importance; }
	/** @hide */ public void setPss(long pss) { mPss = pss; }
	/** @hide */ public void setRss(long rss) { mRss = rss; }
	/** @hide */ public void setTimestamp(long timestamp) { mTimestamp = timestamp; }
	/** @hide */ public void setDescription(String description) { mDescription = description; }
	/** @hide */ public void setProcessStateSummary(byte[] state) { mState = state; }
	/** @hide */ public void setTrace(byte[] trace) { mTrace = trace; }

	@Override
	public String toString() {
		return "ApplicationExitInfo(timestamp=" + mTimestamp + " pid=" + mPid + " realUid=" + mRealUid
		    + " packageUid=" + mPackageUid + " definingUid=" + mDefiningUid + " user=" + (mRealUid / 100000)
		    + " process=" + mProcessName + " reason=" + mReason + " status=" + mStatus
		    + " importance=" + mImportance + " pss=" + mPss + "KB rss=" + mRss + "KB description=" + mDescription + ")";
	}
}
