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

import android.content.Context;
import android.content.LocusId;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.session.MediaSession;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.SystemClock;
import android.text.TextUtils;
import android.widget.RemoteViews;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * teslalinux: AOSP's Notification (android14-release) as a data model: the public fields, the
 * extras under AOSP's keys, Builder, Action, the styles, BubbleMetadata. What it does not do is
 * render: AOSP's Builder builds RemoteViews from the platform notification templates for SystemUI,
 * and this platform has no notification shade (see NotificationManager), so no content views are
 * generated; custom ones an app sets are kept. Bitmaps, Icons and Person icons stay in the
 * notification's fields: ATL's Bitmap and Icon are not Parcelable, so a Bundle cannot hold them
 * (AOSP also puts them in the extras).
 */
public class Notification implements Parcelable {
	public static final String EXTRA_CHANNEL_ID = "android.intent.extra.CHANNEL_ID";
	public static final String EXTRA_CHANNEL_GROUP_ID = "android.intent.extra.CHANNEL_GROUP_ID";
	public static final String EXTRA_NOTIFICATION_TAG = "android.intent.extra.NOTIFICATION_TAG";
	public static final String EXTRA_NOTIFICATION_ID = "android.intent.extra.NOTIFICATION_ID";
	public static final int DEFAULT_ALL = ~0;
	public static final int DEFAULT_SOUND = 1;
	public static final int DEFAULT_VIBRATE = 2;
	public static final int DEFAULT_LIGHTS = 4;
	public static final String EXTRA_REMOTE_INPUT_DRAFT = "android.remoteInputDraft";
	public static final int STREAM_DEFAULT = -1;
	public static final AudioAttributes AUDIO_ATTRIBUTES_DEFAULT = new AudioAttributes.Builder()
	    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
	    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
	    .build();

	public static final int FLAG_SHOW_LIGHTS = 0x00000001;
	public static final int FLAG_ONGOING_EVENT = 0x00000002;
	public static final int FLAG_INSISTENT = 0x00000004;
	public static final int FLAG_ONLY_ALERT_ONCE = 0x00000008;
	public static final int FLAG_AUTO_CANCEL = 0x00000010;
	public static final int FLAG_NO_CLEAR = 0x00000020;
	public static final int FLAG_FOREGROUND_SERVICE = 0x00000040;
	@Deprecated public static final int FLAG_HIGH_PRIORITY = 0x00000080;
	public static final int FLAG_LOCAL_ONLY = 0x00000100;
	public static final int FLAG_GROUP_SUMMARY = 0x00000200;
	public static final int FLAG_AUTOGROUP_SUMMARY = 0x00000400;
	public static final int FLAG_CAN_COLORIZE = 0x00000800;
	public static final int FLAG_BUBBLE = 0x00001000;
	public static final int FLAG_NO_DISMISS = 0x00002000;

	public static final int PRIORITY_DEFAULT = 0;
	public static final int PRIORITY_LOW = -1;
	public static final int PRIORITY_MIN = -2;
	public static final int PRIORITY_HIGH = 1;
	public static final int PRIORITY_MAX = 2;
	public static final int COLOR_DEFAULT = 0;
	public static final int VISIBILITY_PUBLIC = 1;
	public static final int VISIBILITY_PRIVATE = 0;
	public static final int VISIBILITY_SECRET = -1;

	public static final String CATEGORY_CALL = "call";
	public static final String CATEGORY_NAVIGATION = "navigation";
	public static final String CATEGORY_MESSAGE = "msg";
	public static final String CATEGORY_EMAIL = "email";
	public static final String CATEGORY_EVENT = "event";
	public static final String CATEGORY_PROMO = "promo";
	public static final String CATEGORY_ALARM = "alarm";
	public static final String CATEGORY_PROGRESS = "progress";
	public static final String CATEGORY_SOCIAL = "social";
	public static final String CATEGORY_ERROR = "err";
	public static final String CATEGORY_TRANSPORT = "transport";
	public static final String CATEGORY_SYSTEM = "sys";
	public static final String CATEGORY_SERVICE = "service";
	public static final String CATEGORY_RECOMMENDATION = "recommendation";
	public static final String CATEGORY_STATUS = "status";
	public static final String CATEGORY_REMINDER = "reminder";
	public static final String CATEGORY_CAR_EMERGENCY = "car_emergency";
	public static final String CATEGORY_CAR_WARNING = "car_warning";
	public static final String CATEGORY_CAR_INFORMATION = "car_information";
	public static final String CATEGORY_WORKOUT = "workout";
	public static final String CATEGORY_LOCATION_SHARING = "location_sharing";
	public static final String CATEGORY_STOPWATCH = "stopwatch";
	public static final String CATEGORY_MISSED_CALL = "missed_call";

	public static final String EXTRA_TITLE = "android.title";
	public static final String EXTRA_TITLE_BIG = EXTRA_TITLE + ".big";
	public static final String EXTRA_TEXT = "android.text";
	public static final String EXTRA_SUB_TEXT = "android.subText";
	public static final String EXTRA_REMOTE_INPUT_HISTORY = "android.remoteInputHistory";
	public static final String EXTRA_INFO_TEXT = "android.infoText";
	public static final String EXTRA_SUMMARY_TEXT = "android.summaryText";
	public static final String EXTRA_BIG_TEXT = "android.bigText";
	public static final String EXTRA_SMALL_ICON = "android.icon";
	public static final String EXTRA_LARGE_ICON = "android.largeIcon";
	public static final String EXTRA_LARGE_ICON_BIG = EXTRA_LARGE_ICON + ".big";
	public static final String EXTRA_PROGRESS = "android.progress";
	public static final String EXTRA_PROGRESS_MAX = "android.progressMax";
	public static final String EXTRA_PROGRESS_INDETERMINATE = "android.progressIndeterminate";
	public static final String EXTRA_SHOW_CHRONOMETER = "android.showChronometer";
	public static final String EXTRA_CHRONOMETER_COUNT_DOWN = "android.chronometerCountDown";
	public static final String EXTRA_SHOW_WHEN = "android.showWhen";
	public static final String EXTRA_PICTURE = "android.picture";
	public static final String EXTRA_PICTURE_ICON = "android.pictureIcon";
	public static final String EXTRA_PICTURE_CONTENT_DESCRIPTION = "android.pictureContentDescription";
	public static final String EXTRA_SHOW_BIG_PICTURE_WHEN_COLLAPSED = "android.showBigPictureWhenCollapsed";
	public static final String EXTRA_TEXT_LINES = "android.textLines";
	public static final String EXTRA_TEMPLATE = "android.template";
	@Deprecated public static final String EXTRA_PEOPLE = "android.people";
	public static final String EXTRA_PEOPLE_LIST = "android.people.list";
	public static final String EXTRA_BACKGROUND_IMAGE_URI = "android.backgroundImageUri";
	public static final String EXTRA_MEDIA_SESSION = "android.mediaSession";
	public static final String EXTRA_MEDIA_REMOTE_DEVICE = "android.mediaRemoteDevice";
	public static final String EXTRA_MEDIA_REMOTE_ICON = "android.mediaRemoteIcon";
	public static final String EXTRA_MEDIA_REMOTE_INTENT = "android.mediaRemoteIntent";
	public static final String EXTRA_COMPACT_ACTIONS = "android.compactActions";
	public static final String EXTRA_SELF_DISPLAY_NAME = "android.selfDisplayName";
	public static final String EXTRA_MESSAGING_PERSON = "android.messagingUser";
	public static final String EXTRA_CONVERSATION_TITLE = "android.conversationTitle";
	public static final String EXTRA_MESSAGES = "android.messages";
	public static final String EXTRA_HISTORIC_MESSAGES = "android.messages.historic";
	public static final String EXTRA_IS_GROUP_CONVERSATION = "android.isGroupConversation";
	public static final String EXTRA_CALL_TYPE = "android.callType";
	public static final String EXTRA_CALL_IS_VIDEO = "android.callIsVideo";
	public static final String EXTRA_CALL_PERSON = "android.callPerson";
	public static final String EXTRA_VERIFICATION_ICON = "android.verificationIcon";
	public static final String EXTRA_VERIFICATION_TEXT = "android.verificationText";
	public static final String EXTRA_ANSWER_INTENT = "android.answerIntent";
	public static final String EXTRA_DECLINE_INTENT = "android.declineIntent";
	public static final String EXTRA_HANG_UP_INTENT = "android.hangUpIntent";
	public static final String EXTRA_ANSWER_COLOR = "android.answerColor";
	public static final String EXTRA_DECLINE_COLOR = "android.declineColor";
	public static final String EXTRA_COLORIZED = "android.colorized";
	public static final String EXTRA_CONTAINS_CUSTOM_VIEW = "android.contains.customView";

	public static final int GROUP_ALERT_ALL = 0;
	public static final int GROUP_ALERT_SUMMARY = 1;
	public static final int GROUP_ALERT_CHILDREN = 2;
	public static final int BADGE_ICON_NONE = 0;
	public static final int BADGE_ICON_SMALL = 1;
	public static final int BADGE_ICON_LARGE = 2;
	public static final int FOREGROUND_SERVICE_DEFAULT = 0;
	public static final int FOREGROUND_SERVICE_IMMEDIATE = 1;
	public static final int FOREGROUND_SERVICE_DEFERRED = 2;

	private static final int MAX_CHARSEQUENCE_LENGTH = 1024;
	public static final int MAX_ACTION_BUTTONS = 3;

	/* ---- public fields (AOSP) ---- */
	public long when;
	/** @hide */ public long creationTime;
	@Deprecated public int icon;
	public int iconLevel;
	@Deprecated public int number;
	public PendingIntent contentIntent;
	public PendingIntent deleteIntent;
	public PendingIntent fullScreenIntent;
	public CharSequence tickerText;
	@Deprecated public RemoteViews tickerView;
	@Deprecated public RemoteViews contentView;
	@Deprecated public RemoteViews bigContentView;
	@Deprecated public RemoteViews headsUpContentView;
	@Deprecated public Bitmap largeIcon;
	@Deprecated public Uri sound;
	@Deprecated public int audioStreamType = STREAM_DEFAULT;
	@Deprecated public AudioAttributes audioAttributes = AUDIO_ATTRIBUTES_DEFAULT;
	@Deprecated public long[] vibrate;
	@Deprecated public int ledARGB;
	@Deprecated public int ledOnMS;
	@Deprecated public int ledOffMS;
	@Deprecated public int defaults;
	public int flags;
	@Deprecated public int priority;
	public int color = COLOR_DEFAULT;
	public int visibility;
	public Notification publicVersion;
	public String category;
	public Bundle extras = new Bundle();
	public Action[] actions;

	/* ---- AOSP private state ---- */
	private String mGroupKey;
	private String mSortKey;
	private Icon mSmallIcon;
	private Icon mLargeIcon;
	private String mChannelId;
	private long mTimeout;
	private String mShortcutId;
	private LocusId mLocusId;
	private CharSequence mSettingsText;
	private int mGroupAlertBehavior = GROUP_ALERT_ALL;
	private int mBadgeIcon = BADGE_ICON_NONE;
	private BubbleMetadata mBubbleMetadata;
	private boolean mAllowSystemGeneratedContextualActions = true;
	private int mFgsDeferBehavior = FOREGROUND_SERVICE_DEFAULT;
	/* in-memory parts AOSP keeps in the extras (see the class comment) */
	Style mStyle;
	ArrayList<Person> mPersonList;
	Bitmap mPicture;
	Icon mPictureIcon;
	Icon mBigLargeIcon;

	public Notification() {
		this.when = System.currentTimeMillis();
		this.creationTime = System.currentTimeMillis();
		this.priority = PRIORITY_DEFAULT;
	}

	@Deprecated
	public Notification(int icon, CharSequence tickerText, long when) {
		this.icon = icon;
		this.tickerText = tickerText;
		this.when = when;
		this.creationTime = System.currentTimeMillis();
	}

	public Notification(Parcel parcel) {
		this();
		when = parcel.readLong();
		creationTime = parcel.readLong();
		icon = parcel.readInt();
		number = parcel.readInt();
		tickerText = parcel.readCharSequence();
		flags = parcel.readInt();
		defaults = parcel.readInt();
		priority = parcel.readInt();
		color = parcel.readInt();
		visibility = parcel.readInt();
		category = parcel.readString();
		mGroupKey = parcel.readString();
		mSortKey = parcel.readString();
		mChannelId = parcel.readString();
		mTimeout = parcel.readLong();
		mShortcutId = parcel.readString();
		mGroupAlertBehavior = parcel.readInt();
		mBadgeIcon = parcel.readInt();
		mAllowSystemGeneratedContextualActions = parcel.readBoolean();
		mFgsDeferBehavior = parcel.readInt();
		extras = parcel.readBundle();
	}

	/** AOSP: a deep copy, extras included. */
	@Override
	public Notification clone() {
		Notification that = new Notification();
		cloneInto(that, true);
		return that;
	}

	/** @hide */
	public void cloneInto(Notification that, boolean heavy) {
		that.when = this.when;
		that.creationTime = this.creationTime;
		that.mSmallIcon = this.mSmallIcon;
		that.number = this.number;
		that.contentIntent = this.contentIntent;
		that.deleteIntent = this.deleteIntent;
		that.fullScreenIntent = this.fullScreenIntent;
		if (this.tickerText != null)
			that.tickerText = this.tickerText.toString();
		if (heavy) {
			that.tickerView = this.tickerView;
			that.contentView = this.contentView;
			that.bigContentView = this.bigContentView;
			that.headsUpContentView = this.headsUpContentView;
			that.largeIcon = this.largeIcon;
			that.mLargeIcon = this.mLargeIcon;
			that.mPicture = this.mPicture;
			that.mPictureIcon = this.mPictureIcon;
			that.mBigLargeIcon = this.mBigLargeIcon;
		}
		that.iconLevel = this.iconLevel;
		that.sound = this.sound;
		that.audioStreamType = this.audioStreamType;
		if (this.audioAttributes != null)
			that.audioAttributes = this.audioAttributes;
		final long[] vibrate = this.vibrate;
		if (vibrate != null)
			that.vibrate = Arrays.copyOf(vibrate, vibrate.length);
		that.ledARGB = this.ledARGB;
		that.ledOnMS = this.ledOnMS;
		that.ledOffMS = this.ledOffMS;
		that.defaults = this.defaults;
		that.flags = this.flags;
		that.priority = this.priority;
		that.category = this.category;
		that.mGroupKey = this.mGroupKey;
		that.mSortKey = this.mSortKey;
		if (this.extras != null)
			that.extras = new Bundle(this.extras);
		if (this.actions != null) {
			that.actions = new Action[this.actions.length];
			for (int i = 0; i < this.actions.length; i++) {
				if (this.actions[i] != null)
					that.actions[i] = this.actions[i].clone();
			}
		}
		if (heavy && this.publicVersion != null) {
			that.publicVersion = new Notification();
			this.publicVersion.cloneInto(that.publicVersion, heavy);
		}
		that.color = this.color;
		that.visibility = this.visibility;
		that.mChannelId = this.mChannelId;
		that.mTimeout = this.mTimeout;
		that.mShortcutId = this.mShortcutId;
		that.mLocusId = this.mLocusId;
		that.mBadgeIcon = this.mBadgeIcon;
		that.mSettingsText = this.mSettingsText;
		that.mGroupAlertBehavior = this.mGroupAlertBehavior;
		that.mFgsDeferBehavior = this.mFgsDeferBehavior;
		that.mBubbleMetadata = this.mBubbleMetadata;
		that.mAllowSystemGeneratedContextualActions = this.mAllowSystemGeneratedContextualActions;
		that.mStyle = this.mStyle;
		that.mPersonList = this.mPersonList != null ? new ArrayList<>(this.mPersonList) : null;
	}

	@Override
	public int describeContents() {
		return 0;
	}

	@Override
	public void writeToParcel(Parcel parcel, int flags) {
		parcel.writeLong(when);
		parcel.writeLong(creationTime);
		parcel.writeInt(icon);
		parcel.writeInt(number);
		parcel.writeCharSequence(tickerText);
		parcel.writeInt(this.flags);
		parcel.writeInt(defaults);
		parcel.writeInt(priority);
		parcel.writeInt(color);
		parcel.writeInt(visibility);
		parcel.writeString(category);
		parcel.writeString(mGroupKey);
		parcel.writeString(mSortKey);
		parcel.writeString(mChannelId);
		parcel.writeLong(mTimeout);
		parcel.writeString(mShortcutId);
		parcel.writeInt(mGroupAlertBehavior);
		parcel.writeInt(mBadgeIcon);
		parcel.writeBoolean(mAllowSystemGeneratedContextualActions);
		parcel.writeInt(mFgsDeferBehavior);
		parcel.writeBundle(extras);
	}

	public static final Parcelable.Creator<Notification> CREATOR = new Parcelable.Creator<Notification>() {
		public Notification createFromParcel(Parcel parcel) {
			return new Notification(parcel);
		}

		public Notification[] newArray(int size) {
			return new Notification[size];
		}
	};

	/** AOSP: shortens CharSequences that would make a notification too large. */
	public static CharSequence safeCharSequence(CharSequence cs) {
		if (cs == null)
			return cs;
		if (cs.length() > MAX_CHARSEQUENCE_LENGTH) {
			cs = cs.subSequence(0, MAX_CHARSEQUENCE_LENGTH);
		}
		return cs;
	}

	public String getGroup() {
		return mGroupKey;
	}

	public String getSortKey() {
		return mSortKey;
	}

	public String getChannelId() {
		return mChannelId;
	}

	@Deprecated
	public long getTimeout() {
		return mTimeout;
	}

	public long getTimeoutAfter() {
		return mTimeout;
	}

	public int getBadgeIconType() {
		return mBadgeIcon;
	}

	public String getShortcutId() {
		return mShortcutId;
	}

	public LocusId getLocusId() {
		return mLocusId;
	}

	public CharSequence getSettingsText() {
		return mSettingsText;
	}

	public int getGroupAlertBehavior() {
		return mGroupAlertBehavior;
	}

	public BubbleMetadata getBubbleMetadata() {
		return mBubbleMetadata;
	}

	/** @hide */
	public void setBubbleMetadata(BubbleMetadata data) {
		mBubbleMetadata = data;
	}

	public boolean getAllowSystemGeneratedContextualActions() {
		return mAllowSystemGeneratedContextualActions;
	}

	public Icon getSmallIcon() {
		return mSmallIcon;
	}

	/** @hide */
	public void setSmallIcon(Icon icon) {
		mSmallIcon = icon;
	}

	public Icon getLargeIcon() {
		return mLargeIcon;
	}

	/** @hide */
	public int getForegroundServiceBehavior() {
		return mFgsDeferBehavior;
	}

	/** @hide */
	public boolean isGroupSummary() {
		return mGroupKey != null && (flags & FLAG_GROUP_SUMMARY) != 0;
	}

	/** @hide */
	public boolean isGroupChild() {
		return mGroupKey != null && (flags & FLAG_GROUP_SUMMARY) == 0;
	}

	/** @hide */
	public boolean isForegroundService() {
		return (flags & FLAG_FOREGROUND_SERVICE) != 0;
	}

	/** @hide */
	public boolean isColorized() {
		return extras.getBoolean(EXTRA_COLORIZED) && (hasColorizedPermission() || isForegroundService());
	}

	private boolean hasColorizedPermission() {
		return (flags & FLAG_CAN_COLORIZE) != 0;
	}

	/** @hide */
	public boolean hasTitle() {
		return extras != null && (!TextUtils.isEmpty(extras.getCharSequence(EXTRA_TITLE))
		                          || !TextUtils.isEmpty(extras.getCharSequence(EXTRA_TITLE_BIG)));
	}

	/** @hide */
	public boolean containsCustomViews() {
		return contentView != null || bigContentView != null || headsUpContentView != null
		    || (publicVersion != null && (publicVersion.contentView != null
		                                  || publicVersion.bigContentView != null
		                                  || publicVersion.headsUpContentView != null));
	}

	/**
	 * AOSP (API 36): whether the notification qualifies as a promoted ongoing ("live update")
	 * notification: ongoing, with a title, no custom views, not a group summary, and a standard
	 * style (none, big text, call or progress).
	 */
	public boolean hasPromotableCharacteristics() {
		if ((flags & FLAG_ONGOING_EVENT) == 0 || !hasTitle() || containsCustomViews() || isGroupSummary())
			return false;
		return mStyle == null || mStyle instanceof BigTextStyle || mStyle instanceof CallStyle;
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();
		sb.append("Notification(channel=");
		sb.append(getChannelId());
		sb.append(" shortcut=");
		sb.append(getShortcutId());
		sb.append(" contentView=");
		sb.append(contentView != null ? contentView.toString() : "null");
		sb.append(" vibrate=");
		sb.append(vibrate != null ? Arrays.toString(vibrate) : "null");
		sb.append(" sound=");
		sb.append(sound);
		sb.append(" defaults=0x");
		sb.append(Integer.toHexString(this.defaults));
		sb.append(" flags=0x");
		sb.append(Integer.toHexString(this.flags));
		sb.append(String.format(" color=0x%08x", this.color));
		if (this.category != null) {
			sb.append(" category=");
			sb.append(this.category);
		}
		if (this.mGroupKey != null) {
			sb.append(" groupKey=");
			sb.append(this.mGroupKey);
		}
		if (this.mSortKey != null) {
			sb.append(" sortKey=");
			sb.append(this.mSortKey);
		}
		if (actions != null) {
			sb.append(" actions=");
			sb.append(actions.length);
		}
		sb.append(" vis=");
		sb.append(this.visibility);
		if (this.publicVersion != null) {
			sb.append(" publicVersion=");
			sb.append(publicVersion.toString());
		}
		if (mLocusId != null) {
			sb.append(" locusId=");
			sb.append(mLocusId);
		}
		sb.append(" title=").append(extras.getCharSequence(EXTRA_TITLE));
		sb.append(" text=").append(extras.getCharSequence(EXTRA_TEXT));
		sb.append(")");
		return sb.toString();
	}

	/* ================================================================ Action */

	public static class Action implements Parcelable {
		public static final int SEMANTIC_ACTION_NONE = 0;
		public static final int SEMANTIC_ACTION_REPLY = 1;
		public static final int SEMANTIC_ACTION_MARK_AS_READ = 2;
		public static final int SEMANTIC_ACTION_MARK_AS_UNREAD = 3;
		public static final int SEMANTIC_ACTION_DELETE = 4;
		public static final int SEMANTIC_ACTION_ARCHIVE = 5;
		public static final int SEMANTIC_ACTION_MUTE = 6;
		public static final int SEMANTIC_ACTION_UNMUTE = 7;
		public static final int SEMANTIC_ACTION_THUMBS_UP = 8;
		public static final int SEMANTIC_ACTION_THUMBS_DOWN = 9;
		public static final int SEMANTIC_ACTION_CALL = 10;
		public static final int SEMANTIC_ACTION_MARK_CONVERSATION_AS_PRIORITY = 11;
		public static final int SEMANTIC_ACTION_CONVERSATION_IS_PHISHING = 12;

		private final Bundle mExtras;
		private Icon mIcon;
		private final RemoteInput[] mRemoteInputs;
		private boolean mAllowGeneratedReplies = true;
		private final int mSemanticAction;
		private final boolean mIsContextual;
		private boolean mAuthenticationRequired;

		@Deprecated public int icon;
		public CharSequence title;
		public PendingIntent actionIntent;

		/** @hide */
		public Action() {
			this(null, (CharSequence) null, null, new Bundle(), null, true, SEMANTIC_ACTION_NONE, false, false);
		}

		@Deprecated
		public Action(int icon, CharSequence title, PendingIntent intent) {
			this(icon != 0 ? Icon.createWithResource("", icon) : null, title, intent, new Bundle(), null, true, SEMANTIC_ACTION_NONE, false, false);
			this.icon = icon;
		}

		private Action(Icon icon, CharSequence title, PendingIntent intent, Bundle extras,
		               RemoteInput[] remoteInputs, boolean allowGeneratedReplies, int semanticAction,
		               boolean isContextual, boolean requireAuth) {
			this.mIcon = icon;
			this.title = safeCharSequence(title);
			this.actionIntent = intent;
			this.mExtras = extras != null ? extras : new Bundle();
			this.mRemoteInputs = remoteInputs;
			this.mAllowGeneratedReplies = allowGeneratedReplies;
			this.mSemanticAction = semanticAction;
			this.mIsContextual = isContextual;
			this.mAuthenticationRequired = requireAuth;
		}

		public Icon getIcon() {
			return mIcon;
		}

		public Bundle getExtras() {
			return mExtras;
		}

		public boolean getAllowGeneratedReplies() {
			return mAllowGeneratedReplies;
		}

		public RemoteInput[] getRemoteInputs() {
			return mRemoteInputs;
		}

		public int getSemanticAction() {
			return mSemanticAction;
		}

		public boolean isContextual() {
			return mIsContextual;
		}

		public boolean isAuthenticationRequired() {
			return mAuthenticationRequired;
		}

		/** @hide */
		public RemoteInput[] getDataOnlyRemoteInputs() {
			if (mRemoteInputs == null)
				return null;
			ArrayList<RemoteInput> list = new ArrayList<>();
			for (RemoteInput r : mRemoteInputs)
				if (r.isDataOnly())
					list.add(r);
			return list.isEmpty() ? null : list.toArray(new RemoteInput[0]);
		}

		@Override
		public Action clone() {
			return new Action(getIcon(), title, actionIntent, mExtras == null ? new Bundle() : new Bundle(mExtras),
			                  mRemoteInputs, getAllowGeneratedReplies(), getSemanticAction(), isContextual(),
			                  isAuthenticationRequired());
		}

		@Override
		public int describeContents() {
			return 0;
		}

		@Override
		public void writeToParcel(Parcel out, int flags) {
			out.writeInt(icon);
			out.writeCharSequence(title);
			out.writeParcelable(actionIntent, flags);
			out.writeBundle(mExtras);
			out.writeBoolean(mAllowGeneratedReplies);
			out.writeInt(mSemanticAction);
			out.writeBoolean(mIsContextual);
			out.writeBoolean(mAuthenticationRequired);
		}

		@Override
		public String toString() {
			return "Action [" + icon + ", " + title + ", " + actionIntent + "]";
		}

		public static final class Builder {
			private final Icon mIcon;
			private final CharSequence mTitle;
			private final PendingIntent mIntent;
			private boolean mAllowGeneratedReplies = true;
			private final Bundle mExtras;
			private ArrayList<RemoteInput> mRemoteInputs;
			private int mSemanticAction;
			private boolean mIsContextual;
			private boolean mAuthenticationRequired;
			private final int mIconRes;

			@Deprecated
			public Builder(int icon, CharSequence title, PendingIntent intent) {
				this(icon != 0 ? Icon.createWithResource("", icon) : null, title, intent, new Bundle(), null, true, SEMANTIC_ACTION_NONE, false, icon);
			}

			public Builder(Icon icon, CharSequence title, PendingIntent intent) {
				this(icon, title, intent, new Bundle(), null, true, SEMANTIC_ACTION_NONE, false, 0);
			}

			public Builder(Action action) {
				this(action.getIcon(), action.title, action.actionIntent, new Bundle(action.mExtras),
				     action.getRemoteInputs(), action.getAllowGeneratedReplies(), action.getSemanticAction(),
				     action.isContextual(), action.icon);
				mAuthenticationRequired = action.mAuthenticationRequired;
			}

			private Builder(Icon icon, CharSequence title, PendingIntent intent, Bundle extras,
			                RemoteInput[] remoteInputs, boolean allowGeneratedReplies, int semanticAction,
			                boolean isContextual, int iconRes) {
				mIcon = icon;
				mTitle = title;
				mIntent = intent;
				mExtras = extras;
				if (remoteInputs != null) {
					mRemoteInputs = new ArrayList<>(remoteInputs.length);
					Collections.addAll(mRemoteInputs, remoteInputs);
				}
				mAllowGeneratedReplies = allowGeneratedReplies;
				mSemanticAction = semanticAction;
				mIsContextual = isContextual;
				mIconRes = iconRes;
			}

			public Builder addExtras(Bundle extras) {
				if (extras != null) {
					mExtras.putAll(extras);
				}
				return this;
			}

			public Bundle getExtras() {
				return mExtras;
			}

			public Builder addRemoteInput(RemoteInput remoteInput) {
				if (mRemoteInputs == null) {
					mRemoteInputs = new ArrayList<RemoteInput>();
				}
				mRemoteInputs.add(remoteInput);
				return this;
			}

			public Builder setAllowGeneratedReplies(boolean allowGeneratedReplies) {
				mAllowGeneratedReplies = allowGeneratedReplies;
				return this;
			}

			public Builder setSemanticAction(int semanticAction) {
				mSemanticAction = semanticAction;
				return this;
			}

			public Builder setContextual(boolean isContextual) {
				mIsContextual = isContextual;
				return this;
			}

			public Builder setAuthenticationRequired(boolean authenticationRequired) {
				mAuthenticationRequired = authenticationRequired;
				return this;
			}

			public Builder extend(Extender extender) {
				extender.extend(this);
				return this;
			}

			private void checkContextualActionNullFields() {
				if (!mIsContextual)
					return;
				if (mIcon == null) {
					throw new NullPointerException("Contextual Actions must contain a valid icon");
				}
				if (mIntent == null) {
					throw new NullPointerException("Contextual Actions must contain a valid PendingIntent");
				}
			}

			public Action build() {
				checkContextualActionNullFields();
				ArrayList<RemoteInput> dataOnlyInputs = new ArrayList<>();
				RemoteInput[] previousDataInputs = null;
				if (mRemoteInputs != null) {
					RemoteInput[] all = mRemoteInputs.toArray(new RemoteInput[0]);
					mRemoteInputs = new ArrayList<>();
					for (RemoteInput input : all) {
						if (input.isDataOnly())
							dataOnlyInputs.add(input);
						else
							mRemoteInputs.add(input);
					}
				}
				RemoteInput[] textInputsArr = mRemoteInputs == null ? null : mRemoteInputs.toArray(new RemoteInput[mRemoteInputs.size()]);
				RemoteInput[] combined = textInputsArr;
				if (!dataOnlyInputs.isEmpty()) {
					combined = new RemoteInput[(textInputsArr != null ? textInputsArr.length : 0) + dataOnlyInputs.size()];
					int i = 0;
					if (textInputsArr != null)
						for (RemoteInput r : textInputsArr)
							combined[i++] = r;
					for (RemoteInput r : dataOnlyInputs)
						combined[i++] = r;
				}
				Action a = new Action(mIcon, mTitle, mIntent, mExtras, combined, mAllowGeneratedReplies,
				                      mSemanticAction, mIsContextual, mAuthenticationRequired);
				a.icon = mIconRes;
				return a;
			}
		}

		/** AOSP: extends an Action.Builder with extra data (WearableExtender etc.). */
		public interface Extender {
			public Builder extend(Builder builder);
		}

		public static final Parcelable.Creator<Action> CREATOR = new Parcelable.Creator<Action>() {
			public Action createFromParcel(Parcel in) {
				int icon = in.readInt();
				CharSequence title = in.readCharSequence();
				PendingIntent intent;
				try {
					intent = (PendingIntent) in.readParcelable(Action.class.getClassLoader());
				} catch (ReflectiveOperationException e) {
					intent = null;
				}
				Bundle extras = in.readBundle();
				boolean allowGenerated = in.readBoolean();
				int semantic = in.readInt();
				boolean contextual = in.readBoolean();
				boolean auth = in.readBoolean();
				Action a = new Action(icon != 0 ? Icon.createWithResource("", icon) : null, title, intent, extras, null, allowGenerated, semantic, contextual, auth);
				a.icon = icon;
				return a;
			}

			public Action[] newArray(int size) {
				return new Action[size];
			}
		};
	}

	/* ================================================================ Builder */

	public static class Builder {
		private final Context mContext;
		private Notification mN;
		private Bundle mUserExtras = new Bundle();
		private Style mStyle;
		private ArrayList<Action> mActions = new ArrayList<>(MAX_ACTION_BUTTONS);
		private ArrayList<Person> mPersonList = new ArrayList<>();

		public Builder(Context context, String channelId) {
			this(context, (Notification) null);
			mN.mChannelId = channelId;
		}

		@Deprecated
		public Builder(Context context) {
			this(context, (Notification) null);
		}

		/** @hide AOSP: a builder that continues from an existing notification. */
		public Builder(Context context, Notification toAdopt) {
			mContext = context;
			if (toAdopt == null) {
				mN = new Notification();
				if (context != null && context.getApplicationInfo() != null
				    && context.getApplicationInfo().targetSdkVersion < android.os.Build.VERSION_CODES.N) {
					mN.extras.putBoolean(EXTRA_SHOW_WHEN, true);
				}
				mN.priority = PRIORITY_DEFAULT;
				mN.visibility = VISIBILITY_PRIVATE;
			} else {
				mN = toAdopt;
				if (mN.actions != null)
					Collections.addAll(mActions, mN.actions);
				if (mN.mPersonList != null)
					mPersonList.addAll(mN.mPersonList);
				if (mN.getSmallIcon() == null && mN.icon != 0)
					setSmallIcon(mN.icon);
				if (mN.getLargeIcon() == null && mN.largeIcon != null)
					setLargeIcon(mN.largeIcon);
				if (mN.mStyle != null)
					setStyle(mN.mStyle);
				mUserExtras = new Bundle(mN.extras);
			}
		}

		/** AOSP: a Builder that rebuilds the given notification. */
		public static Notification.Builder recoverBuilder(Context context, Notification n) {
			return new Builder(context, n);
		}

		public Builder setShortcutId(String shortcutId) {
			mN.mShortcutId = shortcutId;
			return this;
		}

		public Builder setLocusId(LocusId locusId) {
			mN.mLocusId = locusId;
			return this;
		}

		public Builder setBadgeIconType(int icon) {
			mN.mBadgeIcon = icon;
			return this;
		}

		public Builder setGroupAlertBehavior(int groupAlertBehavior) {
			mN.mGroupAlertBehavior = groupAlertBehavior;
			return this;
		}

		public Builder setBubbleMetadata(BubbleMetadata data) {
			mN.mBubbleMetadata = data;
			return this;
		}

		public Builder setChannelId(String channelId) {
			mN.mChannelId = channelId;
			return this;
		}

		@Deprecated
		public Builder setChannel(String channelId) {
			return setChannelId(channelId);
		}

		public Builder setTimeoutAfter(long durationMs) {
			mN.mTimeout = durationMs;
			return this;
		}

		@Deprecated
		public Builder setTimeout(long durationMs) {
			return setTimeoutAfter(durationMs);
		}

		public Builder setWhen(long when) {
			mN.when = when;
			return this;
		}

		public Builder setShowWhen(boolean show) {
			mN.extras.putBoolean(EXTRA_SHOW_WHEN, show);
			return this;
		}

		public Builder setUsesChronometer(boolean b) {
			mN.extras.putBoolean(EXTRA_SHOW_CHRONOMETER, b);
			return this;
		}

		public Builder setChronometerCountDown(boolean countDown) {
			mN.extras.putBoolean(EXTRA_CHRONOMETER_COUNT_DOWN, countDown);
			return this;
		}

		public Builder setSmallIcon(int icon) {
			return setSmallIcon(icon != 0 ? Icon.createWithResource(mContext != null ? mContext.getPackageName() : "", icon) : null, icon);
		}

		public Builder setSmallIcon(int icon, int level) {
			mN.iconLevel = level;
			return setSmallIcon(icon);
		}

		public Builder setSmallIcon(Icon icon) {
			return setSmallIcon(icon, 0);
		}

		private Builder setSmallIcon(Icon icon, int res) {
			mN.setSmallIcon(icon);
			mN.icon = res;
			return this;
		}

		public Builder setContentTitle(CharSequence title) {
			mN.extras.putCharSequence(EXTRA_TITLE, safeCharSequence(title));
			return this;
		}

		public Builder setContentText(CharSequence text) {
			mN.extras.putCharSequence(EXTRA_TEXT, safeCharSequence(text));
			return this;
		}

		public Builder setSubText(CharSequence text) {
			mN.extras.putCharSequence(EXTRA_SUB_TEXT, safeCharSequence(text));
			return this;
		}

		public Builder setSettingsText(CharSequence text) {
			mN.mSettingsText = safeCharSequence(text);
			return this;
		}

		public Builder setRemoteInputHistory(CharSequence[] text) {
			if (text == null) {
				mN.extras.putCharSequenceArray(EXTRA_REMOTE_INPUT_HISTORY, null);
			} else {
				final int itemCount = Math.min(5, text.length);
				CharSequence[] safe = new CharSequence[itemCount];
				for (int i = 0; i < itemCount; i++) {
					safe[i] = safeCharSequence(text[i]);
				}
				mN.extras.putCharSequenceArray(EXTRA_REMOTE_INPUT_HISTORY, safe);
			}
			return this;
		}

		@Deprecated
		public Builder setNumber(int number) {
			mN.number = number;
			return this;
		}

		@Deprecated
		public Builder setContentInfo(CharSequence info) {
			mN.extras.putCharSequence(EXTRA_INFO_TEXT, safeCharSequence(info));
			return this;
		}

		public Builder setProgress(int max, int progress, boolean indeterminate) {
			mN.extras.putInt(EXTRA_PROGRESS, progress);
			mN.extras.putInt(EXTRA_PROGRESS_MAX, max);
			mN.extras.putBoolean(EXTRA_PROGRESS_INDETERMINATE, indeterminate);
			return this;
		}

		@Deprecated
		public Builder setContent(RemoteViews views) {
			return setCustomContentView(views);
		}

		public Builder setCustomContentView(RemoteViews contentView) {
			mN.contentView = contentView;
			return this;
		}

		public Builder setCustomBigContentView(RemoteViews contentView) {
			mN.bigContentView = contentView;
			return this;
		}

		public Builder setCustomHeadsUpContentView(RemoteViews contentView) {
			mN.headsUpContentView = contentView;
			return this;
		}

		public Builder setContentIntent(PendingIntent intent) {
			mN.contentIntent = intent;
			return this;
		}

		public Builder setDeleteIntent(PendingIntent intent) {
			mN.deleteIntent = intent;
			return this;
		}

		public Builder setFullScreenIntent(PendingIntent intent, boolean highPriority) {
			mN.fullScreenIntent = intent;
			setFlag(FLAG_HIGH_PRIORITY, highPriority);
			return this;
		}

		public Builder setTicker(CharSequence tickerText) {
			mN.tickerText = safeCharSequence(tickerText);
			return this;
		}

		@Deprecated
		public Builder setTicker(CharSequence tickerText, RemoteViews views) {
			setTicker(tickerText);
			return this;
		}

		public Builder setLargeIcon(Bitmap b) {
			mN.largeIcon = b;
			return setLargeIcon(b != null ? Icon.createWithBitmap(b) : null);
		}

		public Builder setLargeIcon(Icon icon) {
			mN.mLargeIcon = icon;
			return this;
		}

		@Deprecated
		public Builder setSound(Uri sound) {
			mN.sound = sound;
			mN.audioAttributes = AUDIO_ATTRIBUTES_DEFAULT;
			return this;
		}

		@Deprecated
		public Builder setSound(Uri sound, int streamType) {
			mN.sound = sound;
			mN.audioStreamType = streamType;
			return this;
		}

		@Deprecated
		public Builder setSound(Uri sound, AudioAttributes audioAttributes) {
			mN.sound = sound;
			mN.audioAttributes = audioAttributes;
			return this;
		}

		@Deprecated
		public Builder setVibrate(long[] pattern) {
			mN.vibrate = pattern;
			return this;
		}

		@Deprecated
		public Builder setLights(int argb, int onMs, int offMs) {
			mN.ledARGB = argb;
			mN.ledOnMS = onMs;
			mN.ledOffMS = offMs;
			if (onMs != 0 || offMs != 0) {
				mN.flags |= FLAG_SHOW_LIGHTS;
			}
			return this;
		}

		public Builder setOngoing(boolean ongoing) {
			setFlag(FLAG_ONGOING_EVENT, ongoing);
			return this;
		}

		public Builder setColorized(boolean colorize) {
			mN.extras.putBoolean(EXTRA_COLORIZED, colorize);
			return this;
		}

		public Builder setOnlyAlertOnce(boolean onlyAlertOnce) {
			setFlag(FLAG_ONLY_ALERT_ONCE, onlyAlertOnce);
			return this;
		}

		public Builder setForegroundServiceBehavior(int behavior) {
			mN.mFgsDeferBehavior = behavior;
			return this;
		}

		public Builder setAutoCancel(boolean autoCancel) {
			setFlag(FLAG_AUTO_CANCEL, autoCancel);
			return this;
		}

		public Builder setLocalOnly(boolean localOnly) {
			setFlag(FLAG_LOCAL_ONLY, localOnly);
			return this;
		}

		@Deprecated
		public Builder setDefaults(int defaults) {
			mN.defaults = defaults;
			return this;
		}

		@Deprecated
		public Builder setPriority(int pri) {
			mN.priority = pri;
			return this;
		}

		public Builder setCategory(String category) {
			mN.category = category;
			return this;
		}

		@Deprecated
		public Builder addPerson(String uri) {
			addPerson(new Person.Builder().setUri(uri).build());
			return this;
		}

		public Builder addPerson(Person person) {
			mPersonList.add(person);
			return this;
		}

		public Builder setGroup(String groupKey) {
			mN.mGroupKey = groupKey;
			return this;
		}

		public Builder setGroupSummary(boolean isGroupSummary) {
			setFlag(FLAG_GROUP_SUMMARY, isGroupSummary);
			return this;
		}

		public Builder setSortKey(String sortKey) {
			mN.mSortKey = sortKey;
			return this;
		}

		public Builder addExtras(Bundle extras) {
			if (extras != null) {
				mUserExtras.putAll(extras);
			}
			return this;
		}

		public Builder setExtras(Bundle extras) {
			if (extras != null) {
				mUserExtras = extras;
			}
			return this;
		}

		public Bundle getExtras() {
			return mUserExtras;
		}

		@Deprecated
		public Builder addAction(int icon, CharSequence title, PendingIntent intent) {
			mActions.add(new Action(icon, safeCharSequence(title), intent));
			return this;
		}

		public Builder addAction(Action action) {
			if (action != null) {
				mActions.add(action);
			}
			return this;
		}

		public Builder setActions(Action... actions) {
			mActions.clear();
			for (int i = 0; i < actions.length; i++) {
				if (actions[i] != null) {
					mActions.add(actions[i]);
				}
			}
			return this;
		}

		public Builder setStyle(Style style) {
			if (mStyle != style) {
				mStyle = style;
				if (mStyle != null) {
					mStyle.setBuilder(this);
					mN.extras.putString(EXTRA_TEMPLATE, style.getClass().getName());
				} else {
					mN.extras.remove(EXTRA_TEMPLATE);
				}
			}
			return this;
		}

		public Style getStyle() {
			return mStyle;
		}

		public Builder setVisibility(int visibility) {
			mN.visibility = visibility;
			return this;
		}

		public Builder setPublicVersion(Notification n) {
			if (n != null) {
				mN.publicVersion = new Notification();
				n.cloneInto(mN.publicVersion, true);
			} else {
				mN.publicVersion = null;
			}
			return this;
		}

		public Builder extend(Extender extender) {
			extender.extend(this);
			return this;
		}

		public Builder setColor(int argb) {
			mN.color = argb;
			return this;
		}

		public Builder setAllowSystemGeneratedContextualActions(boolean allowed) {
			mN.mAllowSystemGeneratedContextualActions = allowed;
			return this;
		}

		public Builder setFlag(int mask, boolean value) {
			if (value) {
				mN.flags |= mask;
			} else {
				mN.flags &= ~mask;
			}
			return this;
		}

		/** @hide */
		public Context getContext() {
			return mContext;
		}

		/**
		 * AOSP builds the collapsed, expanded and heads-up RemoteViews from the platform's
		 * notification templates (for SystemUI). This platform has no notification UI and no
		 * template layouts, so only views the app supplied itself exist.
		 */
		public RemoteViews createContentView() {
			return mN.contentView;
		}

		public RemoteViews createBigContentView() {
			return mN.bigContentView;
		}

		public RemoteViews createHeadsUpContentView() {
			return mN.headsUpContentView;
		}

		/** AOSP: the notification, with the user extras, actions, people and style folded in. */
		public Notification build() {
			mN.extras.putAll(mUserExtras);
			mN.creationTime = System.currentTimeMillis();
			if (!mActions.isEmpty()) {
				mN.actions = new Action[mActions.size()];
				mActions.toArray(mN.actions);
			} else {
				mN.actions = null;
			}
			if (!mPersonList.isEmpty()) {
				mN.mPersonList = new ArrayList<>(mPersonList);
				mN.extras.putParcelableArrayList(EXTRA_PEOPLE_LIST, new ArrayList<>(mPersonList));
			}
			if (mStyle != null) {
				mStyle.purgeResources();
				mStyle.validate(mContext);
				mStyle.buildStyled(mN);
			}
			mN.mStyle = mStyle;
			if (mN.contentView != null || mN.bigContentView != null || mN.headsUpContentView != null)
				mN.extras.putBoolean(EXTRA_CONTAINS_CUSTOM_VIEW, true);
			if ((mN.defaults & DEFAULT_LIGHTS) != 0) {
				mN.flags |= FLAG_SHOW_LIGHTS;
			}
			return mN;
		}

		@Deprecated
		public Notification getNotification() {
			return build();
		}
	}

	/** AOSP: extends a Notification.Builder (WearableExtender, CarExtender, ...). */
	public interface Extender {
		public Builder extend(Builder builder);
	}

	/* ================================================================ styles */

	/** AOSP: the base class of the rich notification styles. */
	public static abstract class Style {
		private CharSequence mBigContentTitle;
		protected CharSequence mSummaryText = null;
		protected boolean mSummaryTextSet = false;
		protected Builder mBuilder;

		protected void internalSetBigContentTitle(CharSequence title) {
			mBigContentTitle = title;
		}

		protected void internalSetSummaryText(CharSequence cs) {
			mSummaryText = cs;
			mSummaryTextSet = true;
		}

		public void setBuilder(Builder builder) {
			if (mBuilder != builder) {
				mBuilder = builder;
				if (mBuilder != null) {
					mBuilder.setStyle(this);
				}
			}
		}

		protected void checkBuilder() {
			if (mBuilder == null) {
				throw new IllegalArgumentException("Style requires a valid Builder object");
			}
		}

		/** @hide */
		public void addExtras(Bundle extras) {
			if (mSummaryTextSet) {
				extras.putCharSequence(EXTRA_SUMMARY_TEXT, mSummaryText);
			}
			if (mBigContentTitle != null) {
				extras.putCharSequence(EXTRA_TITLE_BIG, mBigContentTitle);
			}
			extras.putString(EXTRA_TEMPLATE, this.getClass().getName());
		}

		/** @hide */
		protected void restoreFromExtras(Bundle extras) {
			if (extras.containsKey(EXTRA_SUMMARY_TEXT)) {
				mSummaryText = extras.getCharSequence(EXTRA_SUMMARY_TEXT);
				mSummaryTextSet = true;
			}
			if (extras.containsKey(EXTRA_TITLE_BIG)) {
				mBigContentTitle = extras.getCharSequence(EXTRA_TITLE_BIG);
			}
		}

		/** @hide */
		public Notification buildStyled(Notification wip) {
			addExtras(wip.extras);
			return wip;
		}

		/** @hide */
		public void purgeResources() {
		}

		/** @hide */
		public void validate(Context context) {
		}

		/** @hide */
		public boolean hasSummaryInHeader() {
			return true;
		}

		/** @hide */
		public boolean displayCustomViewInline() {
			return false;
		}

		/** AOSP: the notification built by the Builder this style is attached to. */
		public Notification build() {
			checkBuilder();
			return mBuilder.build();
		}

		/** @hide */
		public CharSequence getBigContentTitle() {
			return mBigContentTitle;
		}

		/** @hide */
		public CharSequence getSummaryText() {
			return mSummaryText;
		}
	}

	public static class BigTextStyle extends Style {
		private CharSequence mBigText;

		public BigTextStyle() {
		}

		@Deprecated
		public BigTextStyle(Builder builder) {
			setBuilder(builder);
		}

		public BigTextStyle setBigContentTitle(CharSequence title) {
			internalSetBigContentTitle(safeCharSequence(title));
			return this;
		}

		public BigTextStyle setSummaryText(CharSequence cs) {
			internalSetSummaryText(safeCharSequence(cs));
			return this;
		}

		public BigTextStyle bigText(CharSequence cs) {
			mBigText = safeCharSequence(cs);
			return this;
		}

		/** @hide */
		public CharSequence getBigText() {
			return mBigText;
		}

		@Override
		public void addExtras(Bundle extras) {
			super.addExtras(extras);
			extras.putCharSequence(EXTRA_BIG_TEXT, mBigText);
		}

		@Override
		protected void restoreFromExtras(Bundle extras) {
			super.restoreFromExtras(extras);
			mBigText = extras.getCharSequence(EXTRA_BIG_TEXT);
		}
	}

	public static class BigPictureStyle extends Style {
		private Icon mPictureIcon;
		private Bitmap mPicture;
		private Icon mBigLargeIcon;
		private boolean mBigLargeIconSet = false;
		private CharSequence mPictureContentDescription;
		private boolean mShowBigPictureWhenCollapsed;

		public BigPictureStyle() {
		}

		@Deprecated
		public BigPictureStyle(Builder builder) {
			setBuilder(builder);
		}

		public BigPictureStyle setBigContentTitle(CharSequence title) {
			internalSetBigContentTitle(safeCharSequence(title));
			return this;
		}

		public BigPictureStyle setSummaryText(CharSequence cs) {
			internalSetSummaryText(safeCharSequence(cs));
			return this;
		}

		public BigPictureStyle setContentDescription(CharSequence contentDescription) {
			mPictureContentDescription = contentDescription;
			return this;
		}

		public BigPictureStyle bigPicture(Bitmap b) {
			mPicture = b;
			mPictureIcon = b != null ? Icon.createWithBitmap(b) : null;
			return this;
		}

		public BigPictureStyle bigPicture(Icon icon) {
			mPictureIcon = icon;
			mPicture = null;
			return this;
		}

		public BigPictureStyle showBigPictureWhenCollapsed(boolean show) {
			mShowBigPictureWhenCollapsed = show;
			return this;
		}

		public BigPictureStyle bigLargeIcon(Bitmap b) {
			return bigLargeIcon(b != null ? Icon.createWithBitmap(b) : null);
		}

		public BigPictureStyle bigLargeIcon(Icon icon) {
			mBigLargeIconSet = true;
			mBigLargeIcon = icon;
			return this;
		}

		/** @hide */
		public Icon getBigPicture() {
			return mPictureIcon;
		}

		@Override
		public void addExtras(Bundle extras) {
			super.addExtras(extras);
			if (mPictureContentDescription != null)
				extras.putCharSequence(EXTRA_PICTURE_CONTENT_DESCRIPTION, mPictureContentDescription);
			extras.putBoolean(EXTRA_SHOW_BIG_PICTURE_WHEN_COLLAPSED, mShowBigPictureWhenCollapsed);
		}

		@Override
		public Notification buildStyled(Notification wip) {
			super.buildStyled(wip);
			wip.mPicture = mPicture;
			wip.mPictureIcon = mPictureIcon;
			if (mBigLargeIconSet)
				wip.mBigLargeIcon = mBigLargeIcon;
			return wip;
		}
	}

	public static class InboxStyle extends Style {
		private static final int MAX_LINES = 5;
		private ArrayList<CharSequence> mTexts = new ArrayList<>(MAX_LINES);

		public InboxStyle() {
		}

		@Deprecated
		public InboxStyle(Builder builder) {
			setBuilder(builder);
		}

		public InboxStyle setBigContentTitle(CharSequence title) {
			internalSetBigContentTitle(safeCharSequence(title));
			return this;
		}

		public InboxStyle setSummaryText(CharSequence cs) {
			internalSetSummaryText(safeCharSequence(cs));
			return this;
		}

		public InboxStyle addLine(CharSequence cs) {
			mTexts.add(safeCharSequence(cs));
			return this;
		}

		/** @hide */
		public ArrayList<CharSequence> getLines() {
			return mTexts;
		}

		@Override
		public void addExtras(Bundle extras) {
			super.addExtras(extras);
			CharSequence[] a = new CharSequence[mTexts.size()];
			extras.putCharSequenceArray(EXTRA_TEXT_LINES, mTexts.toArray(a));
		}

		@Override
		protected void restoreFromExtras(Bundle extras) {
			super.restoreFromExtras(extras);
			mTexts.clear();
			CharSequence[] lines = extras.getCharSequenceArray(EXTRA_TEXT_LINES);
			if (lines != null)
				Collections.addAll(mTexts, lines);
		}
	}

	public static class MediaStyle extends Style {
		static final int MAX_MEDIA_BUTTONS_IN_COMPACT = 3;
		private int[] mActionsToShowInCompact = null;
		private MediaSession.Token mToken;
		private CharSequence mDeviceName;
		private int mDeviceIcon;
		private PendingIntent mDeviceIntent;

		public MediaStyle() {
		}

		@Deprecated
		public MediaStyle(Builder builder) {
			setBuilder(builder);
		}

		public MediaStyle setShowActionsInCompactView(int... actions) {
			mActionsToShowInCompact = actions;
			return this;
		}

		public MediaStyle setMediaSession(MediaSession.Token token) {
			mToken = token;
			return this;
		}

		/** AOSP (API 34): the remote device the media plays on, shown by the media controls. */
		public MediaStyle setRemotePlaybackInfo(CharSequence deviceName, int iconResource, PendingIntent chipIntent) {
			mDeviceName = deviceName;
			mDeviceIcon = iconResource;
			mDeviceIntent = chipIntent;
			return this;
		}

		/** @hide */
		public MediaSession.Token getMediaSession() {
			return mToken;
		}

		@Override
		public Notification buildStyled(Notification wip) {
			super.buildStyled(wip);
			if (wip.category == null) {
				wip.category = Notification.CATEGORY_TRANSPORT;
			}
			return wip;
		}

		@Override
		public void addExtras(Bundle extras) {
			super.addExtras(extras);
			if (mActionsToShowInCompact != null) {
				extras.putIntArray(EXTRA_COMPACT_ACTIONS, mActionsToShowInCompact);
			}
			if (mDeviceName != null) {
				extras.putCharSequence(EXTRA_MEDIA_REMOTE_DEVICE, mDeviceName);
			}
			if (mDeviceIcon > 0) {
				extras.putInt(EXTRA_MEDIA_REMOTE_ICON, mDeviceIcon);
			}
			if (mDeviceIntent != null) {
				extras.putParcelable(EXTRA_MEDIA_REMOTE_INTENT, mDeviceIntent);
			}
		}
	}

	/** AOSP: the custom content views go inside the standard notification decoration. */
	public static class DecoratedCustomViewStyle extends Style {
		public DecoratedCustomViewStyle() {
		}

		/** @hide */
		@Override
		public boolean displayCustomViewInline() {
			return true;
		}
	}

	/** AOSP: like DecoratedCustomViewStyle, for media notifications. */
	public static class DecoratedMediaCustomViewStyle extends MediaStyle {
		public DecoratedMediaCustomViewStyle() {
		}

		/** @hide */
		@Override
		public boolean displayCustomViewInline() {
			return true;
		}
	}

	public static class MessagingStyle extends Style {
		public static final int MAXIMUM_RETAINED_MESSAGES = 25;

		private Person mUser;
		private CharSequence mConversationTitle;
		private List<Message> mMessages = new ArrayList<>();
		private List<Message> mHistoricMessages = new ArrayList<>();
		private boolean mIsGroupConversation;
		private Icon mShortcutIcon;
		private int mUnreadMessageCount;

		@Deprecated
		public MessagingStyle(CharSequence userDisplayName) {
			this(new Person.Builder().setName(userDisplayName).build());
		}

		public MessagingStyle(Person user) {
			mUser = user;
		}

		/** @hide */
		public void validate(Context context) {
			if (mUser == null || mUser.getName() == null) {
				throw new RuntimeException("User must be valid and have a name.");
			}
		}

		@Deprecated
		public CharSequence getUserDisplayName() {
			return mUser.getName();
		}

		public Person getUser() {
			return mUser;
		}

		public MessagingStyle setConversationTitle(CharSequence conversationTitle) {
			mConversationTitle = conversationTitle;
			return this;
		}

		public CharSequence getConversationTitle() {
			return mConversationTitle;
		}

		public MessagingStyle setShortcutIcon(Icon conversationIcon) {
			mShortcutIcon = conversationIcon;
			return this;
		}

		public Icon getShortcutIcon() {
			return mShortcutIcon;
		}

		@Deprecated
		public MessagingStyle addMessage(CharSequence text, long timestamp, CharSequence sender) {
			return addMessage(text, timestamp, sender == null ? null : new Person.Builder().setName(sender).build());
		}

		public MessagingStyle addMessage(CharSequence text, long timestamp, Person sender) {
			return addMessage(new Message(text, timestamp, sender));
		}

		public MessagingStyle addMessage(Message message) {
			mMessages.add(message);
			if (mMessages.size() > MAXIMUM_RETAINED_MESSAGES) {
				mMessages.remove(0);
			}
			return this;
		}

		public MessagingStyle addHistoricMessage(Message message) {
			mHistoricMessages.add(message);
			if (mHistoricMessages.size() > MAXIMUM_RETAINED_MESSAGES) {
				mHistoricMessages.remove(0);
			}
			return this;
		}

		public List<Message> getMessages() {
			return mMessages;
		}

		public List<Message> getHistoricMessages() {
			return mHistoricMessages;
		}

		public MessagingStyle setGroupConversation(boolean isGroupConversation) {
			mIsGroupConversation = isGroupConversation;
			return this;
		}

		public boolean isGroupConversation() {
			if (mBuilder != null && mBuilder.mContext != null && mBuilder.mContext.getApplicationInfo().targetSdkVersion < android.os.Build.VERSION_CODES.P) {
				return mConversationTitle != null;
			}
			return mIsGroupConversation;
		}

		/** @hide */
		public void setUnreadMessageCount(int unreadMessageCount) {
			mUnreadMessageCount = unreadMessageCount;
		}

		@Override
		public void addExtras(Bundle extras) {
			super.addExtras(extras);
			if (mUser != null) {
				extras.putCharSequence(EXTRA_SELF_DISPLAY_NAME, mUser.getName());
				extras.putParcelable(EXTRA_MESSAGING_PERSON, mUser);
			}
			if (mConversationTitle != null) {
				extras.putCharSequence(EXTRA_CONVERSATION_TITLE, mConversationTitle);
			}
			if (!mMessages.isEmpty()) {
				extras.putParcelableArray(EXTRA_MESSAGES, Message.getBundleArrayForMessages(mMessages));
			}
			if (!mHistoricMessages.isEmpty()) {
				extras.putParcelableArray(EXTRA_HISTORIC_MESSAGES, Message.getBundleArrayForMessages(mHistoricMessages));
			}
			extras.putBoolean(EXTRA_IS_GROUP_CONVERSATION, mIsGroupConversation);
		}

		public static final class Message {
			static final String KEY_TEXT = "text";
			static final String KEY_TIMESTAMP = "time";
			static final String KEY_SENDER = "sender";
			static final String KEY_SENDER_PERSON = "sender_person";
			static final String KEY_DATA_MIME_TYPE = "type";
			static final String KEY_DATA_URI = "uri";
			static final String KEY_EXTRAS_BUNDLE = "extras";
			static final String KEY_REMOTE_INPUT_HISTORY = "remote_input_history";

			private CharSequence mText;
			private final long mTimestamp;
			private final Person mSender;
			private final boolean mRemoteInputHistory;
			private Bundle mExtras = new Bundle();
			private String mDataMimeType;
			private Uri mDataUri;

			@Deprecated
			public Message(CharSequence text, long timestamp, CharSequence sender) {
				this(text, timestamp, sender == null ? null : new Person.Builder().setName(sender).build());
			}

			public Message(CharSequence text, long timestamp, Person sender) {
				this(text, timestamp, sender, false);
			}

			/** @hide */
			public Message(CharSequence text, long timestamp, Person sender, boolean remoteInputHistory) {
				mText = safeCharSequence(text);
				mTimestamp = timestamp;
				mSender = sender;
				mRemoteInputHistory = remoteInputHistory;
			}

			public Message setData(String dataMimeType, Uri dataUri) {
				mDataMimeType = dataMimeType;
				mDataUri = dataUri;
				return this;
			}

			public CharSequence getText() {
				return mText;
			}

			public long getTimestamp() {
				return mTimestamp;
			}

			public Bundle getExtras() {
				return mExtras;
			}

			@Deprecated
			public CharSequence getSender() {
				return mSender == null ? null : mSender.getName();
			}

			public Person getSenderPerson() {
				return mSender;
			}

			public String getDataMimeType() {
				return mDataMimeType;
			}

			public Uri getDataUri() {
				return mDataUri;
			}

			/** @hide */
			public boolean isRemoteInputHistory() {
				return mRemoteInputHistory;
			}

			/** @hide */
			public Bundle toBundle() {
				Bundle bundle = new Bundle();
				if (mText != null) {
					bundle.putCharSequence(KEY_TEXT, mText);
				}
				bundle.putLong(KEY_TIMESTAMP, mTimestamp);
				if (mSender != null) {
					bundle.putCharSequence(KEY_SENDER, safeCharSequence(mSender.getName()));
					bundle.putParcelable(KEY_SENDER_PERSON, mSender);
				}
				if (mDataMimeType != null) {
					bundle.putString(KEY_DATA_MIME_TYPE, mDataMimeType);
				}
				if (mDataUri != null) {
					bundle.putString(KEY_DATA_URI, mDataUri.toString());
				}
				if (mExtras != null) {
					bundle.putBundle(KEY_EXTRAS_BUNDLE, mExtras);
				}
				if (mRemoteInputHistory) {
					bundle.putBoolean(KEY_REMOTE_INPUT_HISTORY, mRemoteInputHistory);
				}
				return bundle;
			}

			static Bundle[] getBundleArrayForMessages(List<Message> messages) {
				Bundle[] bundles = new Bundle[messages.size()];
				final int N = messages.size();
				for (int i = 0; i < N; i++) {
					bundles[i] = messages.get(i).toBundle();
				}
				return bundles;
			}
		}
	}

	/** AOSP (API 31): an incoming, ongoing or screening call. */
	public static class CallStyle extends Style {
		public static final int CALL_TYPE_UNKNOWN = 0;
		public static final int CALL_TYPE_INCOMING = 1;
		public static final int CALL_TYPE_ONGOING = 2;
		public static final int CALL_TYPE_SCREENING = 3;

		private int mCallType;
		private Person mPerson;
		private PendingIntent mAnswerIntent;
		private PendingIntent mDeclineIntent;
		private PendingIntent mHangUpIntent;
		private boolean mIsVideo;
		private Integer mAnswerButtonColor;
		private Integer mDeclineButtonColor;
		private Icon mVerificationIcon;
		private CharSequence mVerificationText;

		CallStyle() {
		}

		public static CallStyle forIncomingCall(Person person, PendingIntent declineIntent, PendingIntent answerIntent) {
			return new CallStyle(CALL_TYPE_INCOMING, person, null, requireNonNull(declineIntent, "declineIntent"), requireNonNull(answerIntent, "answerIntent"));
		}

		public static CallStyle forOngoingCall(Person person, PendingIntent hangUpIntent) {
			return new CallStyle(CALL_TYPE_ONGOING, person, requireNonNull(hangUpIntent, "hangUpIntent"), null, null);
		}

		public static CallStyle forScreeningCall(Person person, PendingIntent hangUpIntent, PendingIntent answerIntent) {
			return new CallStyle(CALL_TYPE_SCREENING, person, requireNonNull(hangUpIntent, "hangUpIntent"), null, requireNonNull(answerIntent, "answerIntent"));
		}

		private static <T> T requireNonNull(T o, String name) {
			if (o == null)
				throw new NullPointerException(name + " is required");
			return o;
		}

		private CallStyle(int callType, Person person, PendingIntent hangUpIntent, PendingIntent declineIntent, PendingIntent answerIntent) {
			if (person == null || TextUtils.isEmpty(person.getName())) {
				throw new IllegalArgumentException("person must have a non-empty a name");
			}
			mCallType = callType;
			mPerson = person;
			mAnswerIntent = answerIntent;
			mDeclineIntent = declineIntent;
			mHangUpIntent = hangUpIntent;
		}

		public CallStyle setIsVideo(boolean isVideo) {
			mIsVideo = isVideo;
			return this;
		}

		public CallStyle setVerificationIcon(Icon verificationIcon) {
			mVerificationIcon = verificationIcon;
			return this;
		}

		public CallStyle setVerificationText(CharSequence verificationText) {
			mVerificationText = safeCharSequence(verificationText);
			return this;
		}

		public CallStyle setAnswerButtonColorHint(int color) {
			mAnswerButtonColor = color;
			return this;
		}

		public CallStyle setDeclineButtonColorHint(int color) {
			mDeclineButtonColor = color;
			return this;
		}

		/** @hide */
		public Notification buildStyled(Notification wip) {
			super.buildStyled(wip);
			if (wip.category == null) {
				wip.category = Notification.CATEGORY_CALL;
			}
			return wip;
		}

		@Override
		public void addExtras(Bundle extras) {
			super.addExtras(extras);
			extras.putInt(EXTRA_CALL_TYPE, mCallType);
			extras.putBoolean(EXTRA_CALL_IS_VIDEO, mIsVideo);
			extras.putParcelable(EXTRA_CALL_PERSON, mPerson);
			if (mVerificationText != null)
				extras.putCharSequence(EXTRA_VERIFICATION_TEXT, mVerificationText);
			if (mAnswerIntent != null)
				extras.putParcelable(EXTRA_ANSWER_INTENT, mAnswerIntent);
			if (mDeclineIntent != null)
				extras.putParcelable(EXTRA_DECLINE_INTENT, mDeclineIntent);
			if (mHangUpIntent != null)
				extras.putParcelable(EXTRA_HANG_UP_INTENT, mHangUpIntent);
			if (mAnswerButtonColor != null)
				extras.putInt(EXTRA_ANSWER_COLOR, mAnswerButtonColor);
			if (mDeclineButtonColor != null)
				extras.putInt(EXTRA_DECLINE_COLOR, mDeclineButtonColor);
		}

		/** @hide */
		public int getCallType() {
			return mCallType;
		}

		/** @hide */
		public Person getPerson() {
			return mPerson;
		}
	}

	/* ================================================================ BubbleMetadata */

	/**
	 * AOSP (API 29): how the notification shows as a bubble. This platform has no bubbles (no
	 * SystemUI), so the metadata is kept and returned but no bubble appears, as on a device with
	 * bubbles turned off.
	 */
	public static final class BubbleMetadata implements Parcelable {
		private PendingIntent mPendingIntent;
		private PendingIntent mDeleteIntent;
		private Icon mIcon;
		private int mDesiredHeight;
		private int mDesiredHeightResId;
		private int mFlags;
		private String mShortcutId;

		public static final int FLAG_AUTO_EXPAND_BUBBLE = 0x00000001;
		public static final int FLAG_SUPPRESS_NOTIFICATION = 0x00000002;
		public static final int FLAG_SUPPRESSABLE_BUBBLE = 0x00000004;
		public static final int FLAG_SUPPRESS_BUBBLE = 0x00000008;

		private BubbleMetadata(PendingIntent expandIntent, PendingIntent deleteIntent, Icon icon, int height, int heightResId, String shortcutId) {
			mPendingIntent = expandIntent;
			mIcon = icon;
			mDesiredHeight = height;
			mDesiredHeightResId = heightResId;
			mDeleteIntent = deleteIntent;
			mShortcutId = shortcutId;
		}

		public String getShortcutId() {
			return mShortcutId;
		}

		public PendingIntent getIntent() {
			return mPendingIntent;
		}

		/** @hide */
		public void setIntent(PendingIntent intent) {
			mPendingIntent = intent;
		}

		public PendingIntent getDeleteIntent() {
			return mDeleteIntent;
		}

		/** @hide */
		public void setDeleteIntent(PendingIntent deleteIntent) {
			mDeleteIntent = deleteIntent;
		}

		public Icon getIcon() {
			return mIcon;
		}

		public int getDesiredHeight() {
			return mDesiredHeight;
		}

		public int getDesiredHeightResId() {
			return mDesiredHeightResId;
		}

		public boolean getAutoExpandBubble() {
			return (mFlags & FLAG_AUTO_EXPAND_BUBBLE) != 0;
		}

		public boolean isNotificationSuppressed() {
			return (mFlags & FLAG_SUPPRESS_NOTIFICATION) != 0;
		}

		public boolean isBubbleSuppressable() {
			return (mFlags & FLAG_SUPPRESSABLE_BUBBLE) != 0;
		}

		public boolean isBubbleSuppressed() {
			return (mFlags & FLAG_SUPPRESS_BUBBLE) != 0;
		}

		/** @hide */
		public void setFlags(int flags) {
			mFlags = flags;
		}

		/** @hide */
		public int getFlags() {
			return mFlags;
		}

		@Override
		public int describeContents() {
			return 0;
		}

		@Override
		public void writeToParcel(Parcel out, int flags) {
			out.writeParcelable(mPendingIntent, flags);
			out.writeInt(mDesiredHeight);
			out.writeInt(mFlags);
			out.writeParcelable(mDeleteIntent, flags);
			out.writeInt(mDesiredHeightResId);
			out.writeString(mShortcutId);
		}

		public static final Creator<BubbleMetadata> CREATOR = new Creator<BubbleMetadata>() {
			@Override
			public BubbleMetadata createFromParcel(Parcel in) {
				try {
					ClassLoader cl = BubbleMetadata.class.getClassLoader();
					PendingIntent intent = (PendingIntent) in.readParcelable(cl);
					int height = in.readInt();
					int flags = in.readInt();
					PendingIntent delete = (PendingIntent) in.readParcelable(cl);
					int heightRes = in.readInt();
					String shortcut = in.readString();
					BubbleMetadata m = new BubbleMetadata(intent, delete, null, height, heightRes, shortcut);
					m.mFlags = flags;
					return m;
				} catch (ReflectiveOperationException e) {
					throw new RuntimeException(e);
				}
			}

			@Override
			public BubbleMetadata[] newArray(int size) {
				return new BubbleMetadata[size];
			}
		};

		public static final class Builder {
			private PendingIntent mPendingIntent;
			private Icon mIcon;
			private int mDesiredHeight;
			private int mDesiredHeightResId;
			private int mFlags;
			private PendingIntent mDeleteIntent;
			private String mShortcutId;

			@Deprecated
			public Builder() {
			}

			public Builder(String shortcutId) {
				if (TextUtils.isEmpty(shortcutId)) {
					throw new NullPointerException("Bubble requires a non-null shortcut id");
				}
				mShortcutId = shortcutId;
			}

			public Builder(PendingIntent intent, Icon icon) {
				if (intent == null) {
					throw new NullPointerException("Bubble requires non-null pending intent");
				}
				if (icon == null) {
					throw new NullPointerException("Bubbles require non-null icon");
				}
				mPendingIntent = intent;
				mIcon = icon;
			}

			@Deprecated
			public BubbleMetadata.Builder createShortcutBubble(String shortcutId) {
				if (!TextUtils.isEmpty(shortcutId)) {
					mPendingIntent = null;
					mIcon = null;
				}
				mShortcutId = shortcutId;
				return this;
			}

			@Deprecated
			public BubbleMetadata.Builder createIntentBubble(PendingIntent intent, Icon icon) {
				if (intent == null) {
					throw new IllegalArgumentException("Bubble requires non-null pending intent");
				}
				if (icon == null) {
					throw new IllegalArgumentException("Bubbles require non-null icon");
				}
				mShortcutId = null;
				mPendingIntent = intent;
				mIcon = icon;
				return this;
			}

			public BubbleMetadata.Builder setIntent(PendingIntent intent) {
				if (mShortcutId != null) {
					throw new IllegalStateException("Created as a shortcut bubble, cannot set a PendingIntent. Consider using BubbleMetadata.Builder(PendingIntent,Icon) instead.");
				}
				if (intent == null) {
					throw new IllegalArgumentException("Bubble requires non-null pending intent");
				}
				mPendingIntent = intent;
				return this;
			}

			public BubbleMetadata.Builder setIcon(Icon icon) {
				if (mShortcutId != null) {
					throw new IllegalStateException("Created as a shortcut bubble, cannot set an Icon. Consider using BubbleMetadata.Builder(PendingIntent,Icon) instead.");
				}
				if (icon == null) {
					throw new IllegalArgumentException("Bubbles require non-null icon");
				}
				mIcon = icon;
				return this;
			}

			public BubbleMetadata.Builder setDesiredHeight(int height) {
				mDesiredHeight = Math.max(height, 0);
				mDesiredHeightResId = 0;
				return this;
			}

			public BubbleMetadata.Builder setDesiredHeightResId(int heightResId) {
				mDesiredHeightResId = heightResId;
				mDesiredHeight = 0;
				return this;
			}

			public BubbleMetadata.Builder setAutoExpandBubble(boolean shouldExpand) {
				setFlag(FLAG_AUTO_EXPAND_BUBBLE, shouldExpand);
				return this;
			}

			public BubbleMetadata.Builder setSuppressNotification(boolean shouldSuppressNotif) {
				setFlag(FLAG_SUPPRESS_NOTIFICATION, shouldSuppressNotif);
				return this;
			}

			public BubbleMetadata.Builder setSuppressableBubble(boolean suppressBubble) {
				setFlag(FLAG_SUPPRESSABLE_BUBBLE, suppressBubble);
				return this;
			}

			public BubbleMetadata.Builder setDeleteIntent(PendingIntent deleteIntent) {
				mDeleteIntent = deleteIntent;
				return this;
			}

			public BubbleMetadata build() {
				if (mShortcutId == null && mPendingIntent == null) {
					throw new NullPointerException("Must supply pending intent or shortcut to bubble");
				}
				if (mShortcutId == null && mIcon == null) {
					throw new NullPointerException("Must supply an icon or shortcut for the bubble");
				}
				BubbleMetadata data = new BubbleMetadata(mPendingIntent, mDeleteIntent, mIcon, mDesiredHeight, mDesiredHeightResId, mShortcutId);
				data.setFlags(mFlags);
				return data;
			}

			public BubbleMetadata.Builder setFlag(int mask, boolean value) {
				if (value) {
					mFlags |= mask;
				} else {
					mFlags &= ~mask;
				}
				return this;
			}
		}
	}
}
