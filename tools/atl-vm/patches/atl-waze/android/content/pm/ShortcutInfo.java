/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.content.pm;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.LocusId;
import android.graphics.drawable.Icon;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.PersistableBundle;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.ArraySet;

import java.util.Set;

/**
 * Represents a shortcut that can be published via {@link ShortcutManager} (AOSP).
 *
 * <p>teslalinux: the fields, flags, builder checks and parcel form of AOSP's ShortcutInfo. Left
 * out: android.app.Person (not in ATL), capability bindings and AppSearch documents (system
 * APIs), and starting themes.
 */
public final class ShortcutInfo implements Parcelable {
    static final String RES_TYPE_STRING = "string";

    private static final String ANDROID_PACKAGE_NAME = "android";

    private static final int IMPLICIT_RANK_MASK = 0x7fffffff;

    /** @hide */
    public static final int RANK_CHANGED_BIT = ~IMPLICIT_RANK_MASK;
    /** @hide */
    public static final int RANK_NOT_SET = Integer.MAX_VALUE;

    /** @hide */ public static final int FLAG_DYNAMIC = 1 << 0;
    /** @hide */ public static final int FLAG_PINNED = 1 << 1;
    /** @hide */ public static final int FLAG_HAS_ICON_RES = 1 << 2;
    /** @hide */ public static final int FLAG_HAS_ICON_FILE = 1 << 3;
    /** @hide */ public static final int FLAG_KEY_FIELDS_ONLY = 1 << 4;
    /** @hide */ public static final int FLAG_MANIFEST = 1 << 5;
    /** @hide */ public static final int FLAG_DISABLED = 1 << 6;
    /** @hide */ public static final int FLAG_STRINGS_RESOLVED = 1 << 7;
    /** @hide */ public static final int FLAG_IMMUTABLE = 1 << 8;
    /** @hide */ public static final int FLAG_ADAPTIVE_BITMAP = 1 << 9;
    /** @hide */ public static final int FLAG_RETURNED_BY_SERVICE = 1 << 10;
    /** @hide */ public static final int FLAG_ICON_FILE_PENDING_SAVE = 1 << 11;
    /** @hide */ public static final int FLAG_SHADOW = 1 << 12;
    /** @hide */ public static final int FLAG_LONG_LIVED = 1 << 13;
    /** @hide */ public static final int FLAG_CACHED_NOTIFICATIONS = 1 << 14;
    /** @hide */ public static final int FLAG_HAS_ICON_URI = 1 << 15;
    /** @hide */ public static final int FLAG_CACHED_PEOPLE_TILE = 1 << 29;
    /** @hide */ public static final int FLAG_CACHED_BUBBLES = 1 << 30;
    /** @hide */ public static final int FLAG_CACHED_ALL =
            FLAG_CACHED_NOTIFICATIONS | FLAG_CACHED_BUBBLES | FLAG_CACHED_PEOPLE_TILE;

    /** @hide */ public static final int CLONE_REMOVE_ICON = 1;
    /** @hide */ public static final int CLONE_REMOVE_INTENT = 1 << 1;
    /** @hide */ public static final int CLONE_REMOVE_NON_KEY_INFO = 1 << 2;
    /** @hide */ public static final int CLONE_REMOVE_RES_NAMES = 1 << 3;
    /** @hide */ public static final int CLONE_REMOVE_PERSON = 1 << 4;
    /** @hide */ public static final int CLONE_REMOVE_FOR_CREATOR = CLONE_REMOVE_ICON | CLONE_REMOVE_RES_NAMES;

    public static final int DISABLED_REASON_NOT_DISABLED = 0;
    public static final int DISABLED_REASON_BY_APP = 1;
    public static final int DISABLED_REASON_APP_CHANGED = 2;
    public static final int DISABLED_REASON_UNKNOWN = 3;
    public static final int DISABLED_REASON_VERSION_LOWER = 100;
    public static final int DISABLED_REASON_BACKUP_NOT_SUPPORTED = 101;
    public static final int DISABLED_REASON_SIGNATURE_MISMATCH = 102;
    public static final int DISABLED_REASON_OTHER_RESTORE_ISSUE = 103;

    /** @hide */
    public static final int MAX_ID_LENGTH = 1000;

    public static final int SURFACE_LAUNCHER = 1 << 0;

    public static final String SHORTCUT_CATEGORY_CONVERSATION = "android.shortcut.conversation";

    private final String mId;
    private final String mPackageName;
    private ComponentName mActivity;
    private Icon mIcon;
    private int mTitleResId;
    private CharSequence mTitle;
    private int mTextResId;
    private CharSequence mText;
    private int mDisabledMessageResId;
    private CharSequence mDisabledMessage;
    private ArraySet<String> mCategories;
    private Intent[] mIntents;
    private PersistableBundle[] mIntentPersistableExtrases;
    private int mRank;
    private int mImplicitRank;
    private PersistableBundle mExtras;
    private long mLastChangedTimestamp;
    private int mFlags;
    private int mIconResId;
    private final int mUserId;
    private int mDisabledReason;
    private LocusId mLocusId;
    private int mExcludedSurfacesFlags;

    private ShortcutInfo(Builder b) {
        mUserId = UserHandle.myUserId();
        mId = b.mId;
        mPackageName = b.mContext.getPackageName();
        mActivity = b.mActivity;
        mIcon = b.mIcon;
        mTitle = b.mTitle;
        mTitleResId = b.mTitleResId;
        mText = b.mText;
        mTextResId = b.mTextResId;
        mDisabledMessage = b.mDisabledMessage;
        mDisabledMessageResId = b.mDisabledMessageResId;
        mCategories = cloneCategories(b.mCategories);
        mIntents = cloneIntents(b.mIntents);
        fixUpIntentExtras();
        mRank = b.mRank;
        mExtras = b.mExtras;
        mLocusId = b.mLocusId;
        mExcludedSurfacesFlags = b.mExcludedSurfacesFlags;
        if (b.mIsLongLived) {
            mFlags |= FLAG_LONG_LIVED;
        }
        updateTimestamp();
    }

    /** the intents' extras are kept as PersistableBundles (AOSP), so they must be persistable */
    private void fixUpIntentExtras() {
        if (mIntents == null) {
            mIntentPersistableExtrases = null;
            return;
        }
        mIntentPersistableExtrases = new PersistableBundle[mIntents.length];
        for (int i = 0; i < mIntents.length; i++) {
            final Intent intent = mIntents[i];
            final android.os.Bundle extras = intent.getExtras();
            if (extras == null || extras.isEmpty()) {
                mIntentPersistableExtrases[i] = null;
            } else {
                mIntentPersistableExtrases[i] = new PersistableBundle(extras);
                intent.replaceExtras((android.os.Bundle) null);
            }
        }
    }

    private static ArraySet<String> cloneCategories(Set<String> source) {
        if (source == null) {
            return null;
        }
        final ArraySet<String> ret = new ArraySet<>(source.size());
        for (CharSequence s : source) {
            if (!TextUtils.isEmpty(s)) {
                ret.add(s.toString().intern());
            }
        }
        return ret;
    }

    private static Intent[] cloneIntents(Intent[] intents) {
        if (intents == null) {
            return null;
        }
        final Intent[] ret = new Intent[intents.length];
        for (int i = 0; i < ret.length; i++) {
            if (intents[i] != null) {
                ret[i] = new Intent(intents[i]);
            }
        }
        return ret;
    }

    private static PersistableBundle[] clonePersistableBundle(PersistableBundle[] bundle) {
        if (bundle == null) {
            return null;
        }
        final PersistableBundle[] ret = new PersistableBundle[bundle.length];
        for (int i = 0; i < ret.length; i++) {
            if (bundle[i] != null) {
                ret[i] = new PersistableBundle(bundle[i]);
            }
        }
        return ret;
    }

    private ShortcutInfo(ShortcutInfo source, int cloneFlags) {
        mUserId = source.mUserId;
        mId = source.mId;
        mPackageName = source.mPackageName;
        mActivity = source.mActivity;
        mFlags = source.mFlags;
        mLastChangedTimestamp = source.mLastChangedTimestamp;
        mDisabledReason = source.mDisabledReason;
        mLocusId = source.mLocusId;
        mIconResId = source.mIconResId;
        if ((cloneFlags & CLONE_REMOVE_NON_KEY_INFO) == 0) {
            if ((cloneFlags & CLONE_REMOVE_ICON) == 0) {
                mIcon = source.mIcon;
            }
            mTitle = source.mTitle;
            mTitleResId = source.mTitleResId;
            mText = source.mText;
            mTextResId = source.mTextResId;
            mDisabledMessage = source.mDisabledMessage;
            mDisabledMessageResId = source.mDisabledMessageResId;
            mCategories = cloneCategories(source.mCategories);
            if ((cloneFlags & CLONE_REMOVE_INTENT) == 0) {
                mIntents = cloneIntents(source.mIntents);
                mIntentPersistableExtrases = clonePersistableBundle(source.mIntentPersistableExtrases);
            }
            mRank = source.mRank;
            mExtras = source.mExtras;
            mExcludedSurfacesFlags = source.mExcludedSurfacesFlags;
        } else {
            // Set this bit.
            mFlags |= FLAG_KEY_FIELDS_ONLY;
        }
    }

    /** @hide a copy, without what cloneFlags removes */
    public ShortcutInfo clone(int cloneFlags) {
        return new ShortcutInfo(this, cloneFlags);
    }

    /** @hide the mandatory fields (AOSP enforceMandatoryFields) */
    public void enforceMandatoryFields(boolean forPinned) {
        if (TextUtils.isEmpty(mId))
            throw new IllegalArgumentException("Shortcut ID must be provided");
        if (!forPinned && mActivity == null)
            throw new NullPointerException("Activity must be provided");
        if (mTitle == null && mTitleResId == 0)
            throw new IllegalArgumentException("Short label must be provided");
        if (mIntents == null || mIntents.length == 0)
            throw new NullPointerException("Shortcut Intent must be provided");
    }

    /** @hide AOSP copyNonNullFieldsFrom: an update keeps fields the source leaves unset */
    public void copyNonNullFieldsFrom(ShortcutInfo source) {
        if (!mId.equals(source.mId))
            throw new IllegalArgumentException("ID must match");
        if (source.mActivity != null) mActivity = source.mActivity;
        if (source.mIcon != null) mIcon = source.mIcon;
        if (source.mTitle != null || source.mTitleResId != 0) {
            mTitle = source.mTitle;
            mTitleResId = source.mTitleResId;
        }
        if (source.mText != null || source.mTextResId != 0) {
            mText = source.mText;
            mTextResId = source.mTextResId;
        }
        if (source.mDisabledMessage != null || source.mDisabledMessageResId != 0) {
            mDisabledMessage = source.mDisabledMessage;
            mDisabledMessageResId = source.mDisabledMessageResId;
        }
        if (source.mCategories != null) mCategories = cloneCategories(source.mCategories);
        if (source.mLocusId != null) mLocusId = source.mLocusId;
        if (source.mIntents != null) {
            mIntents = cloneIntents(source.mIntents);
            mIntentPersistableExtrases = clonePersistableBundle(source.mIntentPersistableExtrases);
        }
        if (source.mRank != RANK_NOT_SET) mRank = source.mRank;
        if (source.mExtras != null) mExtras = source.mExtras;
        mExcludedSurfacesFlags = source.mExcludedSurfacesFlags;
    }

    /** Builder class for {@link ShortcutInfo} objects. */
    public static class Builder {
        private final Context mContext;
        private String mId;
        private ComponentName mActivity;
        private Icon mIcon;
        private int mTitleResId;
        private CharSequence mTitle;
        private int mTextResId;
        private CharSequence mText;
        private int mDisabledMessageResId;
        private CharSequence mDisabledMessage;
        private Set<String> mCategories;
        private Intent[] mIntents;
        private boolean mIsLongLived;
        private int mRank = RANK_NOT_SET;
        private PersistableBundle mExtras;
        private LocusId mLocusId;
        private int mExcludedSurfacesFlags;

        /** Constructor. */
        public Builder(Context context, String id) {
            mContext = context;
            if (TextUtils.isEmpty(id))
                throw new IllegalArgumentException("id cannot be empty");
            mId = id;
        }

        /** @hide */
        public Builder setId(String id) {
            if (TextUtils.isEmpty(id))
                throw new IllegalArgumentException("id cannot be empty");
            mId = id;
            return this;
        }

        /** Sets the locus id of the shortcut. */
        public Builder setLocusId(LocusId locusId) {
            if (locusId == null)
                throw new NullPointerException("locusId cannot be null");
            mLocusId = locusId;
            return this;
        }

        /** Sets the target activity. */
        public Builder setActivity(ComponentName activity) {
            if (activity == null)
                throw new NullPointerException("activity cannot be null");
            mActivity = activity;
            return this;
        }

        /** Sets an icon of a shortcut. */
        public Builder setIcon(Icon icon) {
            mIcon = icon;
            return this;
        }

        /** Sets the short title of a shortcut. */
        public Builder setShortLabel(CharSequence shortLabel) {
            if (mTitleResId != 0)
                throw new IllegalArgumentException("shortLabelResId already set");
            if (TextUtils.isEmpty(shortLabel))
                throw new IllegalArgumentException("shortLabel cannot be empty");
            mTitle = shortLabel;
            return this;
        }

        /** @hide */
        public Builder setShortLabelResId(int shortLabelResId) {
            if (mTitle != null)
                throw new IllegalArgumentException("shortLabel already set");
            mTitleResId = shortLabelResId;
            return this;
        }

        /** Sets the text of a shortcut. */
        public Builder setLongLabel(CharSequence longLabel) {
            if (mTextResId != 0)
                throw new IllegalArgumentException("longLabelResId already set");
            if (TextUtils.isEmpty(longLabel))
                throw new IllegalArgumentException("longLabel cannot be empty");
            mText = longLabel;
            return this;
        }

        /** @hide */
        public Builder setLongLabelResId(int longLabelResId) {
            if (mText != null)
                throw new IllegalArgumentException("longLabel already set");
            mTextResId = longLabelResId;
            return this;
        }

        /** Sets the message that should be shown when the user attempts to start a shortcut that is disabled. */
        public Builder setDisabledMessage(CharSequence disabledMessage) {
            if (mDisabledMessageResId != 0)
                throw new IllegalArgumentException("disabledMessageResId already set");
            if (TextUtils.isEmpty(disabledMessage))
                throw new IllegalArgumentException("disabledMessage cannot be empty");
            mDisabledMessage = disabledMessage;
            return this;
        }

        /** @hide */
        public Builder setDisabledMessageResId(int disabledMessageResId) {
            if (mDisabledMessage != null)
                throw new IllegalArgumentException("disabledMessage already set");
            mDisabledMessageResId = disabledMessageResId;
            return this;
        }

        /** Sets categories for a shortcut. */
        public Builder setCategories(Set<String> categories) {
            mCategories = categories;
            return this;
        }

        /** Sets the intent of a shortcut. */
        public Builder setIntent(Intent intent) {
            return setIntents(new Intent[] {intent});
        }

        /** Sets multiple intents instead of a single intent (the last one is launched, the others form the back stack). */
        public Builder setIntents(Intent[] intents) {
            if (intents == null)
                throw new NullPointerException("intents cannot be null");
            if (intents.length == 0)
                throw new IllegalArgumentException("intents cannot be empty");
            for (Intent intent : intents) {
                if (intent == null)
                    throw new NullPointerException("intents cannot contain null");
                if (intent.getAction() == null)
                    throw new NullPointerException("intent's action must be set");
            }
            // Make sure always clone incoming intents.
            mIntents = cloneIntents(intents);
            return this;
        }

        /** Sets if a shortcut would be valid even if it has been unpublished/invisible by the app. */
        public Builder setLongLived(boolean longLived) {
            mIsLongLived = longLived;
            return this;
        }

        /** "Rank" of a shortcut, which is a non-negative value that's used by the launcher app to sort shortcuts. */
        public Builder setRank(int rank) {
            if (rank < 0)
                throw new IllegalArgumentException("Rank cannot be negative");
            mRank = rank;
            return this;
        }

        /** Extras that the app can set for any purpose. */
        public Builder setExtras(PersistableBundle extras) {
            mExtras = extras;
            return this;
        }

        /** Sets which surfaces the shortcut is excluded from. */
        public Builder setExcludedFromSurfaces(final int surfaces) {
            mExcludedSurfacesFlags = surfaces;
            return this;
        }

        /** Creates a {@link ShortcutInfo} instance. */
        public ShortcutInfo build() {
            return new ShortcutInfo(this);
        }
    }

    /** Returns the ID of a shortcut. */
    public String getId() {
        return mId;
    }

    /** Gets the LocusId associated with this shortcut. */
    public LocusId getLocusId() {
        return mLocusId;
    }

    /** Return the package name of the publisher app. */
    public String getPackage() {
        return mPackageName;
    }

    /** Return the target activity. */
    public ComponentName getActivity() {
        return mActivity;
    }

    /** @hide */
    public void setActivity(ComponentName activity) {
        mActivity = activity;
    }

    /** @hide the icon (removed from the shortcuts ShortcutManager returns) */
    public Icon getIcon() {
        return mIcon;
    }

    /** Return the short description of a shortcut. */
    public CharSequence getShortLabel() {
        return mTitle;
    }

    /** @hide */
    public int getShortLabelResourceId() {
        return mTitleResId;
    }

    /** Return the long description of a shortcut. */
    public CharSequence getLongLabel() {
        return mText;
    }

    /** @hide the long label if set, else the short one */
    public CharSequence getLabel() {
        CharSequence label = getLongLabel();
        if (TextUtils.isEmpty(label)) {
            label = getShortLabel();
        }
        return label;
    }

    /** @hide */
    public int getLongLabelResourceId() {
        return mTextResId;
    }

    /** Return the message that should be shown when the user attempts to start a shortcut that is disabled. */
    public CharSequence getDisabledMessage() {
        return mDisabledMessage;
    }

    /** @hide */
    public int getDisabledMessageResourceId() {
        return mDisabledMessageResId;
    }

    /** @hide */
    public void setDisabledReason(int reason) {
        mDisabledReason = reason;
    }

    /** Returns why a shortcut has been disabled. */
    public int getDisabledReason() {
        return mDisabledReason;
    }

    /** Return the shortcut's categories. */
    public Set<String> getCategories() {
        return mCategories;
    }

    /** Returns the intent that is executed when the user selects this shortcut (the last of getIntents()). */
    public Intent getIntent() {
        if (mIntents == null || mIntents.length == 0) {
            return null;
        }
        final int last = mIntents.length - 1;
        final Intent intent = new Intent(mIntents[last]);
        return setIntentExtras(intent, mIntentPersistableExtrases[last]);
    }

    /** Return the intent set with {@link Builder#setIntents(Intent[])}. */
    public Intent[] getIntents() {
        if (mIntents == null) {
            return null;
        }
        final Intent[] ret = new Intent[mIntents.length];
        for (int i = 0; i < ret.length; i++) {
            ret[i] = new Intent(mIntents[i]);
            setIntentExtras(ret[i], mIntentPersistableExtrases[i]);
        }
        return ret;
    }

    private static Intent setIntentExtras(Intent intent, PersistableBundle extras) {
        if (extras == null) {
            intent.replaceExtras((android.os.Bundle) null);
        } else {
            intent.replaceExtras(new android.os.Bundle(extras));
        }
        return intent;
    }

    /** "Rank" of a shortcut. */
    public int getRank() {
        return mRank;
    }

    /** @hide */
    public boolean hasRank() {
        return mRank != RANK_NOT_SET;
    }

    /** @hide */
    public void setRank(int rank) {
        mRank = rank;
    }

    /** @hide */
    public void setImplicitRank(int rank) {
        // Make sure to keep RANK_CHANGED_BIT.
        mImplicitRank = (mImplicitRank & RANK_CHANGED_BIT) | (rank & IMPLICIT_RANK_MASK);
    }

    /** @hide */
    public int getImplicitRank() {
        return mImplicitRank & IMPLICIT_RANK_MASK;
    }

    /** Extras that the app can set for any purpose. */
    public PersistableBundle getExtras() {
        return mExtras;
    }

    /** @hide */
    public int getUserId() {
        return mUserId;
    }

    /** {@link UserHandle} on which the publisher created this shortcut. */
    public UserHandle getUserHandle() {
        return UserHandle.of(mUserId);
    }

    /** Last time when any of the fields was updated. */
    public long getLastChangedTimestamp() {
        return mLastChangedTimestamp;
    }

    /** @hide */
    public int getFlags() {
        return mFlags;
    }

    /** @hide */
    public void replaceFlags(int flags) {
        mFlags = flags;
    }

    /** @hide */
    public void addFlags(int flags) {
        mFlags |= flags;
    }

    /** @hide */
    public void clearFlags(int flags) {
        mFlags &= ~flags;
    }

    /** @hide */
    public boolean hasFlags(int flags) {
        return (mFlags & flags) == flags;
    }

    /** @hide */
    public void updateTimestamp() {
        mLastChangedTimestamp = System.currentTimeMillis();
    }

    /** @hide */
    public void setTimestamp(long value) {
        mLastChangedTimestamp = value;
    }

    /** Return whether a shortcut is dynamic. */
    public boolean isDynamic() {
        return hasFlags(FLAG_DYNAMIC);
    }

    /** Return whether a shortcut is pinned. */
    public boolean isPinned() {
        return hasFlags(FLAG_PINNED);
    }

    /** Return whether a shortcut is static; that is, whether a shortcut is published from AndroidManifest.xml. */
    public boolean isDeclaredInManifest() {
        return hasFlags(FLAG_MANIFEST);
    }

    /** @hide */
    @Deprecated
    public boolean isManifestShortcut() {
        return isDeclaredInManifest();
    }

    /** Return if a shortcut is a long-lived shortcut. */
    public boolean isLongLived() {
        return hasFlags(FLAG_LONG_LIVED);
    }

    /** Return whether a shortcut is cached. */
    public boolean isCached() {
        return (getFlags() & FLAG_CACHED_ALL) != 0;
    }

    /** @hide */
    public boolean isFloating() {
        return (isPinned() || isCached()) && !(isDynamic() || isManifestShortcut());
    }

    /** @hide */
    public boolean isOriginallyFromManifest() {
        return hasFlags(FLAG_IMMUTABLE);
    }

    /** Return if a shortcut is immutable, in which case it cannot be modified with any of {@link ShortcutManager} APIs. */
    public boolean isImmutable() {
        return hasFlags(FLAG_IMMUTABLE);
    }

    /** Returns false if a shortcut is disabled with {@link ShortcutManager#disableShortcuts}. */
    public boolean isEnabled() {
        return !hasFlags(FLAG_DISABLED);
    }

    /** @hide */
    public boolean isAlive() {
        return hasFlags(FLAG_PINNED) || hasFlags(FLAG_DYNAMIC) || hasFlags(FLAG_MANIFEST) || isCached();
    }

    /** @hide */
    public boolean usesQuota() {
        return hasFlags(FLAG_DYNAMIC) || hasFlags(FLAG_MANIFEST);
    }

    /** Return whether a shortcut only contains "key" information only or not. */
    public boolean hasKeyFieldsOnly() {
        return hasFlags(FLAG_KEY_FIELDS_ONLY);
    }

    /** Return true if the shortcut is excluded from specified surface. */
    public boolean isExcludedFromSurfaces(int surface) {
        return (mExcludedSurfacesFlags & surface) != 0;
    }

    /** Returns a bitmask of all surfaces this shortcut is excluded from. */
    public int getExcludedFromSurfaces() {
        return mExcludedSurfacesFlags;
    }

    /** @hide */
    public boolean hasIconResource() {
        return hasFlags(FLAG_HAS_ICON_RES);
    }

    /** @hide */
    public int getIconResourceId() {
        return mIconResId;
    }

    private ShortcutInfo(Parcel source) {
        final ClassLoader cl = getClass().getClassLoader();

        mUserId = source.readInt();
        mId = source.readString8();
        mPackageName = source.readString8();
        mActivity = source.readParcelable(cl, ComponentName.class);
        mFlags = source.readInt();
        mIconResId = source.readInt();
        mLastChangedTimestamp = source.readLong();
        mDisabledReason = source.readInt();

        if (source.readInt() == 0) {
            return; // key information only.
        }

        mIcon = source.readParcelable(cl, Icon.class);
        mTitle = source.readCharSequence();
        mTitleResId = source.readInt();
        mText = source.readCharSequence();
        mTextResId = source.readInt();
        mDisabledMessage = source.readCharSequence();
        mDisabledMessageResId = source.readInt();
        mIntents = source.readParcelableArray(cl, Intent.class);
        mIntentPersistableExtrases = source.readParcelableArray(cl, PersistableBundle.class);
        mRank = source.readInt();
        mExtras = source.readParcelable(cl, PersistableBundle.class);

        int N = source.readInt();
        if (N == 0) {
            mCategories = null;
        } else {
            mCategories = new ArraySet<>(N);
            for (int i = 0; i < N; i++) {
                mCategories.add(source.readString8().intern());
            }
        }
        mLocusId = source.readParcelable(cl, LocusId.class);
        mExcludedSurfacesFlags = source.readInt();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mUserId);
        dest.writeString8(mId);
        dest.writeString8(mPackageName);
        dest.writeParcelable(mActivity, flags);
        dest.writeInt(mFlags);
        dest.writeInt(mIconResId);
        dest.writeLong(mLastChangedTimestamp);
        dest.writeInt(mDisabledReason);

        if (hasKeyFieldsOnly()) {
            dest.writeInt(0);
            return;
        }
        dest.writeInt(1);

        // ATL's Icon may not be Parcelable; then the icon is not carried (as ShortcutManager's
        // returned shortcuts do not carry one either)
        Object icon = mIcon;
        dest.writeParcelable(icon instanceof Parcelable ? (Parcelable) icon : null, flags);
        dest.writeCharSequence(mTitle);
        dest.writeInt(mTitleResId);
        dest.writeCharSequence(mText);
        dest.writeInt(mTextResId);
        dest.writeCharSequence(mDisabledMessage);
        dest.writeInt(mDisabledMessageResId);

        dest.writeParcelableArray(mIntents, flags);
        dest.writeParcelableArray(mIntentPersistableExtrases, flags);
        dest.writeInt(mRank);
        dest.writeParcelable(mExtras, flags);

        if (mCategories != null) {
            final int N = mCategories.size();
            dest.writeInt(N);
            for (int i = 0; i < N; i++) {
                dest.writeString8(mCategories.valueAt(i));
            }
        } else {
            dest.writeInt(0);
        }

        dest.writeParcelable(mLocusId, flags);
        dest.writeInt(mExcludedSurfacesFlags);
    }

    public static final Parcelable.Creator<ShortcutInfo> CREATOR = new Parcelable.Creator<ShortcutInfo>() {
        public ShortcutInfo createFromParcel(Parcel source) {
            return new ShortcutInfo(source);
        }

        public ShortcutInfo[] newArray(int size) {
            return new ShortcutInfo[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public String toString() {
        return "ShortcutInfo {id=" + mId + ", flags=0x" + Integer.toHexString(mFlags)
                + ", packageName=" + mPackageName + ", activity=" + mActivity
                + ", shortLabel=" + mTitle + ", rank=" + mRank + "}";
    }
}
