/*
 * Copyright (C) 2011 The Android Open Source Project
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

package android.os;

import java.util.Arrays;
import java.util.Objects;

/**
 * Describes the source of some work that may be done by someone else (AOSP). Holds the uids (and
 * optional package names) the work is attributed to, kept sorted by uid then name as AOSP does.
 * teslalinux: the attribution chains (WorkChain, a system API) are not carried.
 */
public class WorkSource implements Parcelable {
    int mNum;
    int[] mUids = new int[0];
    String[] mNames;

    /** Create an empty work source. */
    public WorkSource() {
        mNum = 0;
    }

    /** Create a new WorkSource that is a copy of an existing one. */
    public WorkSource(WorkSource orig) {
        if (orig == null) {
            mNum = 0;
            return;
        }
        mNum = orig.mNum;
        mUids = orig.mUids.clone();
        mNames = orig.mNames != null ? orig.mNames.clone() : null;
    }

    /** @hide a work source of one uid. */
    public WorkSource(int uid) {
        mNum = 1;
        mUids = new int[] {uid, 0};
        mNames = null;
    }

    /** @hide a work source of one uid and package name. */
    public WorkSource(int uid, String packageName) {
        if (packageName == null)
            throw new NullPointerException("packageName can't be null");
        mNum = 1;
        mUids = new int[] {uid, 0};
        mNames = new String[] {packageName, null};
    }

    WorkSource(Parcel in) {
        mNum = in.readInt();
        mUids = in.createIntArray();
        mNames = in.createStringArray();
        if (mUids == null)
            mUids = new int[0];
    }

    /** @hide */
    public int size() {
        return mNum;
    }

    /** @hide */
    public int getUid(int index) {
        return mUids[index];
    }

    /** @hide the package name at index, or null. */
    public String getPackageName(int index) {
        return mNames != null ? mNames[index] : null;
    }

    /** @hide */
    public String getName(int index) {
        return getPackageName(index);
    }

    /** Clear this WorkSource to be empty. */
    public void clear() {
        mNum = 0;
    }

    /** @hide whether it attributes the work to nobody. */
    public boolean isEmpty() {
        return mNum == 0;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof WorkSource))
            return false;
        WorkSource other = (WorkSource) o;
        return !diff(other);
    }

    @Override
    public int hashCode() {
        int result = 0;
        for (int i = 0; i < mNum; i++) {
            result = ((result << 4) | (result >>> 28)) ^ mUids[i];
        }
        if (mNames != null) {
            for (int i = 0; i < mNum; i++) {
                result = ((result << 4) | (result >>> 28)) ^ Objects.hashCode(mNames[i]);
            }
        }
        return result;
    }

    /** Compare this WorkSource with another; true when they differ. */
    public boolean diff(WorkSource other) {
        int N = mNum;
        if (N != other.mNum) {
            return true;
        }
        final int[] uids1 = mUids;
        final int[] uids2 = other.mUids;
        final String[] names1 = mNames;
        final String[] names2 = other.mNames;
        for (int i = 0; i < N; i++) {
            if (uids1[i] != uids2[i]) {
                return true;
            }
            if (names1 != null && names2 != null && !Objects.equals(names1[i], names2[i])) {
                return true;
            }
        }
        return false;
    }

    /** Replace the current contents of this work source with the given work source. */
    public void set(WorkSource other) {
        if (other == null) {
            mNum = 0;
            return;
        }
        mNum = other.mNum;
        mUids = other.mUids.clone();
        mNames = other.mNames != null ? other.mNames.clone() : null;
    }

    /** @hide */
    public void set(int uid) {
        mNum = 1;
        mUids = new int[] {uid, 0};
        mNames = null;
    }

    /** Merge the contents of other WorkSource in to this one; true when this one changed. */
    public boolean add(WorkSource other) {
        boolean changed = false;
        for (int i = 0; i < other.mNum; i++) {
            changed |= insert(other.mUids[i], other.mNames != null ? other.mNames[i] : null);
        }
        return changed;
    }

    /** @hide */
    public boolean add(int uid) {
        return insert(uid, null);
    }

    /** @hide */
    public boolean add(int uid, String name) {
        return insert(uid, name);
    }

    /** Remove the uids (and names) of other from this one; true when this one changed. */
    public boolean remove(WorkSource other) {
        if (isEmpty() || other.isEmpty()) {
            return false;
        }
        boolean changed = false;
        for (int j = 0; j < other.mNum; j++) {
            int uid = other.mUids[j];
            String name = other.mNames != null ? other.mNames[j] : null;
            for (int i = 0; i < mNum; i++) {
                if (mUids[i] == uid && (name == null || mNames == null || Objects.equals(mNames[i], name))) {
                    System.arraycopy(mUids, i + 1, mUids, i, mNum - i - 1);
                    if (mNames != null) {
                        System.arraycopy(mNames, i + 1, mNames, i, mNum - i - 1);
                    }
                    mNum--;
                    changed = true;
                    break;
                }
            }
        }
        return changed;
    }

    private boolean insert(int uid, String name) {
        if (name != null && mNames == null) {
            mNames = new String[mUids.length];
        }
        int i = 0;
        for (; i < mNum; i++) {
            if (mUids[i] > uid) {
                break;
            }
            if (mUids[i] == uid) {
                String n = mNames != null ? mNames[i] : null;
                if (Objects.equals(n, name)) {
                    return false;
                }
                if (name != null && n != null && n.compareTo(name) > 0) {
                    break;
                }
            }
        }
        if (mNum >= mUids.length) {
            mUids = Arrays.copyOf(mUids, mNum + 10);
            if (mNames != null) {
                mNames = Arrays.copyOf(mNames, mNum + 10);
            }
        }
        System.arraycopy(mUids, i, mUids, i + 1, mNum - i);
        mUids[i] = uid;
        if (mNames != null) {
            System.arraycopy(mNames, i, mNames, i + 1, mNum - i);
            mNames[i] = name;
        }
        mNum++;
        return true;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mNum);
        dest.writeIntArray(mUids);
        dest.writeStringArray(mNames);
    }

    @Override
    public String toString() {
        StringBuilder result = new StringBuilder();
        result.append("WorkSource{");
        for (int i = 0; i < mNum; i++) {
            if (i != 0) {
                result.append(", ");
            }
            result.append(mUids[i]);
            if (mNames != null) {
                result.append(" ");
                result.append(mNames[i]);
            }
        }
        result.append("}");
        return result.toString();
    }

    public static final Parcelable.Creator<WorkSource> CREATOR = new Parcelable.Creator<WorkSource>() {
        public WorkSource createFromParcel(Parcel in) {
            return new WorkSource(in);
        }

        public WorkSource[] newArray(int size) {
            return new WorkSource[size];
        }
    };
}
