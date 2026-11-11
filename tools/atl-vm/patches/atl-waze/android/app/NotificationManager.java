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

import android.atl.ATLLoadedApp;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * teslalinux: NotificationManager after AOSP's NotificationManager and NotificationManagerService,
 * for a device whose notifications are not shown: the car has no status bar or notification shade.
 * The whole API works for the app as on Android: channels and channel groups are stored and
 * returned (AOSP's create/update rules), notify() checks the channel the way the service does and
 * posts into the active notification list (getActiveNotifications), cancel()/cancelAll() remove
 * them (except foreground-service notifications, which only stopForeground removes),
 * areNotificationsEnabled() is true because the user has not blocked the app. Nothing draws them,
 * like on a device without SystemUI. The app's state lives in this process (one shared store,
 * whichever NotificationManager instance is used); there is no system service to keep channels
 * across restarts, and an app recreates its channels at start, as AOSP recommends.
 *
 * With ATL_DESKTOP_NOTIFICATIONS set (a desktop session), posted notifications are also forwarded to
 * the desktop's notification service, which is what ATL did before.
 */
public class NotificationManager {
	private static final String TAG = "NotificationManager";

	public static final int IMPORTANCE_UNSPECIFIED = -1000;
	public static final int IMPORTANCE_NONE = 0;
	public static final int IMPORTANCE_MIN = 1;
	public static final int IMPORTANCE_LOW = 2;
	public static final int IMPORTANCE_DEFAULT = 3;
	public static final int IMPORTANCE_HIGH = 4;
	public static final int IMPORTANCE_MAX = 5;

	public static final int INTERRUPTION_FILTER_UNKNOWN = 0;
	public static final int INTERRUPTION_FILTER_ALL = 1;
	public static final int INTERRUPTION_FILTER_PRIORITY = 2;
	public static final int INTERRUPTION_FILTER_NONE = 3;
	public static final int INTERRUPTION_FILTER_ALARMS = 4;

	public static final String ACTION_APP_BLOCK_STATE_CHANGED = "android.app.action.APP_BLOCK_STATE_CHANGED";
	public static final String ACTION_NOTIFICATION_CHANNEL_BLOCK_STATE_CHANGED = "android.app.action.NOTIFICATION_CHANNEL_BLOCK_STATE_CHANGED";
	public static final String ACTION_NOTIFICATION_CHANNEL_GROUP_BLOCK_STATE_CHANGED = "android.app.action.NOTIFICATION_CHANNEL_GROUP_BLOCK_STATE_CHANGED";
	public static final String ACTION_INTERRUPTION_FILTER_CHANGED = "android.app.action.INTERRUPTION_FILTER_CHANGED";
	public static final String EXTRA_BLOCKED_STATE = "android.app.extra.BLOCKED_STATE";
	public static final String EXTRA_NOTIFICATION_CHANNEL_ID = "android.app.extra.NOTIFICATION_CHANNEL_ID";
	public static final String EXTRA_NOTIFICATION_CHANNEL_GROUP_ID = "android.app.extra.NOTIFICATION_CHANNEL_GROUP_ID";

	/* ---- the app's notification state (AOSP keeps it in NotificationManagerService) ---- */
	private static final Object sLock = new Object();
	private static final LinkedHashMap<String, NotificationChannel> sChannels = new LinkedHashMap<>();
	private static final LinkedHashMap<String, NotificationChannelGroup> sGroups = new LinkedHashMap<>();
	/* key "tag|id" -> posted notification, in post order */
	private static final LinkedHashMap<String, StatusBarNotification> sActive = new LinkedHashMap<>();
	private static String sNotificationDelegate;
	private static final boolean sDesktop = System.getenv("ATL_DESKTOP_NOTIFICATIONS") != null;
	private static int sMprisNotificationId = -1;

	public NotificationManager() {
	}

	private static Context tlContext() {
		return ATLLoadedApp.getPrimaryApplication().getApplication();
	}

	private static String key(String tag, int id) {
		return tag + "|" + id;
	}

	/* ---------------- posting ---------------- */

	public void notify(int id, Notification notification) {
		notify(null, id, notification);
	}

	public void notify(String tag, int id, Notification notification) {
		Context context = tlContext();
		String pkg = context.getPackageName();
		if (notification.getSmallIcon() == null && notification.icon == 0) {
			/* AOSP NotificationManager.fixNotification throws IllegalArgumentException here; ATL's own
			 * Toast posts icon-less notifications, so the notification is refused with the same message
			 * instead of ending the app */
			Log.e(TAG, "Invalid notification (no valid small icon): " + notification);
			return;
		}
		/* AOSP NotificationManagerService.enqueueNotificationInternal: an app targeting O or later needs a
		 * channel that exists; without one the notification is dropped with this error */
		NotificationChannel channel;
		synchronized (sLock) {
			channel = notification.getChannelId() != null ? sChannels.get(notification.getChannelId()) : null;
		}
		if (channel == null && context.getApplicationInfo().targetSdkVersion >= Build.VERSION_CODES.O) {
			Log.e("NotificationService", "No Channel found for pkg=" + pkg + ", channelId=" + notification.getChannelId()
			                                 + ", id=" + id + ", tag=" + tag + ", opPkg=" + pkg + ", callingUid=" + Process.myUid()
			                                 + ", userId=" + (Process.myUid() / UserHandle.PER_USER_RANGE)
			                                 + ", notification=" + notification);
			return;
		}
		if (channel != null && channel.getImportance() == IMPORTANCE_NONE && (notification.flags & Notification.FLAG_FOREGROUND_SERVICE) == 0) {
			/* AOSP: a blocked channel's notifications are not posted */
			return;
		}
		if ((notification.flags & Notification.FLAG_FOREGROUND_SERVICE) != 0)
			notification.flags |= Notification.FLAG_NO_CLEAR;
		StatusBarNotification sbn = new StatusBarNotification(pkg, pkg, id, tag, Process.myUid(), Process.myPid(),
		                                                      notification, new UserHandle(Process.myUid() / UserHandle.PER_USER_RANGE),
		                                                      null, System.currentTimeMillis());
		synchronized (sLock) {
			sActive.remove(key(tag, id));
			sActive.put(key(tag, id), sbn);
		}
		if (sDesktop)
			tlDesktopShow(id, notification);
	}

	/* ---------------- cancelling ---------------- */

	public void cancel(int id) {
		cancel(null, id);
	}

	/** AOSP: removes the notification; a foreground service's notification stays until stopForeground. */
	public void cancel(String tag, int id) {
		tlCancel(tag, id, true);
	}

	/** Service.stopForeground: the foreground service's own notification goes too. */
	void tlCancelForService(int id) {
		tlCancel(null, id, false);
	}

	private void tlCancel(String tag, final int id, boolean keepForeground) {
		synchronized (sLock) {
			StatusBarNotification sbn = sActive.get(key(tag, id));
			if (sbn == null)
				return;
			if (keepForeground && (sbn.getNotification().flags & Notification.FLAG_FOREGROUND_SERVICE) != 0)
				return;
			sActive.remove(key(tag, id));
		}
		if (sDesktop)
			tlDesktopCancel(id);
	}

	/** AOSP: removes all the app's notifications except foreground-service ones. */
	public void cancelAll() {
		List<StatusBarNotification> removed = new ArrayList<>();
		synchronized (sLock) {
			for (Map.Entry<String, StatusBarNotification> e : new ArrayList<>(sActive.entrySet())) {
				if ((e.getValue().getNotification().flags & Notification.FLAG_FOREGROUND_SERVICE) != 0)
					continue;
				sActive.remove(e.getKey());
				removed.add(e.getValue());
			}
		}
		if (sDesktop)
			for (StatusBarNotification sbn : removed)
				tlDesktopCancel(sbn.getId());
	}

	public StatusBarNotification[] getActiveNotifications() {
		synchronized (sLock) {
			return sActive.values().toArray(new StatusBarNotification[0]);
		}
	}

	/* ---------------- channels (AOSP PreferencesHelper rules) ---------------- */

	public void createNotificationChannel(NotificationChannel channel) {
		List<NotificationChannel> list = new ArrayList<>();
		list.add(channel);
		createNotificationChannels(list);
	}

	public void createNotificationChannels(List<NotificationChannel> channels) {
		synchronized (sLock) {
			for (NotificationChannel channel : channels) {
				if (channel == null)
					throw new IllegalArgumentException("Channel cannot be null");
				if (channel.getId() == null)
					throw new IllegalArgumentException("Channel id cannot be null");
				int importance = channel.getImportance();
				if (importance < IMPORTANCE_NONE || importance > IMPORTANCE_MAX)
					throw new IllegalArgumentException("Invalid importance level");
				if (channel.getGroup() != null && !sGroups.containsKey(channel.getGroup()))
					throw new IllegalArgumentException("NotificationChannelGroup doesn't exist");
				NotificationChannel existing = sChannels.get(channel.getId());
				if (existing == null) {
					sChannels.put(channel.getId(), channel);
					continue;
				}
				/* an existing channel: the app may rename it, change its description, give it a
				 * group it has none of, and lower its importance (the user never edited it here) */
				if (channel.getName() != null)
					existing.setName(channel.getName());
				if (channel.getDescription() != null)
					existing.setDescription(channel.getDescription());
				if (existing.getGroup() == null && channel.getGroup() != null)
					existing.setGroup(channel.getGroup());
				if (channel.getImportance() < existing.getImportance())
					existing.setImportance(channel.getImportance());
			}
		}
	}

	public NotificationChannel getNotificationChannel(String channelId) {
		synchronized (sLock) {
			return sChannels.get(channelId);
		}
	}

	/** AOSP (API 30): the conversation's own channel when the app made one, else the parent channel. */
	public NotificationChannel getNotificationChannel(String channelId, String conversationId) {
		synchronized (sLock) {
			if (conversationId != null) {
				for (NotificationChannel c : sChannels.values()) {
					if (channelId.equals(c.getParentChannelId()) && conversationId.equals(c.getConversationId()))
						return c;
				}
			}
			return sChannels.get(channelId);
		}
	}

	public List<NotificationChannel> getNotificationChannels() {
		synchronized (sLock) {
			return new ArrayList<>(sChannels.values());
		}
	}

	/** AOSP: deletes the channel and cancels its notifications; a channel a foreground service posts to cannot be deleted. */
	public void deleteNotificationChannel(String channelId) {
		if (NotificationChannel.DEFAULT_CHANNEL_ID.equals(channelId))
			throw new IllegalArgumentException("Cannot delete default channel");
		List<StatusBarNotification> removed = new ArrayList<>();
		synchronized (sLock) {
			for (StatusBarNotification sbn : sActive.values()) {
				if (channelId.equals(sbn.getNotification().getChannelId())
				    && (sbn.getNotification().flags & Notification.FLAG_FOREGROUND_SERVICE) != 0)
					throw new SecurityException("Not allowed to delete channel " + channelId + " with a foreground service");
			}
			for (Map.Entry<String, StatusBarNotification> e : new ArrayList<>(sActive.entrySet())) {
				if (channelId.equals(e.getValue().getNotification().getChannelId())) {
					sActive.remove(e.getKey());
					removed.add(e.getValue());
				}
			}
			sChannels.remove(channelId);
		}
		if (sDesktop)
			for (StatusBarNotification sbn : removed)
				tlDesktopCancel(sbn.getId());
	}

	public void createNotificationChannelGroup(NotificationChannelGroup group) {
		List<NotificationChannelGroup> list = new ArrayList<>();
		list.add(group);
		createNotificationChannelGroups(list);
	}

	public void createNotificationChannelGroups(List<NotificationChannelGroup> groups) {
		synchronized (sLock) {
			for (NotificationChannelGroup group : groups) {
				if (group == null || group.getId() == null)
					throw new IllegalArgumentException("Group id cannot be null");
				NotificationChannelGroup existing = sGroups.get(group.getId());
				if (existing == null) {
					sGroups.put(group.getId(), group.clone());
				} else {
					/* AOSP: name and description can be updated; blocked state is the user's */
					NotificationChannelGroup updated = new NotificationChannelGroup(group.getId(), group.getName());
					updated.setDescription(group.getDescription());
					updated.setBlocked(existing.isBlocked());
					sGroups.put(group.getId(), updated);
				}
			}
		}
	}

	private NotificationChannelGroup tlGroupWithChannels(NotificationChannelGroup group) {
		NotificationChannelGroup copy = group.clone();
		List<NotificationChannel> channels = new ArrayList<>();
		for (NotificationChannel c : sChannels.values())
			if (group.getId().equals(c.getGroup()))
				channels.add(c);
		copy.setChannels(channels);
		return copy;
	}

	public NotificationChannelGroup getNotificationChannelGroup(String channelGroupId) {
		synchronized (sLock) {
			NotificationChannelGroup group = sGroups.get(channelGroupId);
			return group != null ? tlGroupWithChannels(group) : null;
		}
	}

	public List<NotificationChannelGroup> getNotificationChannelGroups() {
		synchronized (sLock) {
			List<NotificationChannelGroup> out = new ArrayList<>();
			for (NotificationChannelGroup g : sGroups.values())
				out.add(tlGroupWithChannels(g));
			return out;
		}
	}

	/** AOSP: deletes the group and every channel in it. */
	public void deleteNotificationChannelGroup(String groupId) {
		List<String> channels = new ArrayList<>();
		synchronized (sLock) {
			for (NotificationChannel c : sChannels.values())
				if (groupId.equals(c.getGroup()))
					channels.add(c.getId());
		}
		for (String id : channels)
			deleteNotificationChannel(id);
		synchronized (sLock) {
			sGroups.remove(groupId);
		}
	}

	/* ---------------- app-level state ---------------- */

	/** AOSP: false only when the user blocked the app's notifications; there is no settings UI to do that here. */
	public boolean areNotificationsEnabled() {
		return true;
	}

	/** AOSP: whether notifications are paused for the app (suspended package); never here. */
	public boolean areNotificationsPaused() {
		return false;
	}

	/** AOSP: the app's overall importance: DEFAULT while its notifications are enabled. */
	public int getImportance() {
		return areNotificationsEnabled() ? IMPORTANCE_DEFAULT : IMPORTANCE_NONE;
	}

	/** AOSP: the Do Not Disturb filter; this device has no DND mode, so everything is let through. */
	public int getCurrentInterruptionFilter() {
		return INTERRUPTION_FILTER_ALL;
	}

	/** AOSP: whether the user gave the app access to the DND policy; there is no such setting here. */
	public boolean isNotificationPolicyAccessGranted() {
		return false;
	}

	public boolean shouldHideSilentStatusBarIcons() {
		return false;
	}

	/** AOSP (API 34): the USE_FULL_SCREEN_INTENT permission's grant. */
	public boolean canUseFullScreenIntent() {
		return tlContext().checkSelfPermission("android.permission.USE_FULL_SCREEN_INTENT") == PackageManager.PERMISSION_GRANTED;
	}

	/** AOSP (API 36): promoted ("live update") notifications need the POST_PROMOTED_NOTIFICATIONS permission. */
	public boolean canPostPromotedNotifications() {
		return tlContext().checkSelfPermission("android.permission.POST_PROMOTED_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED;
	}

	/** AOSP: whether the app may post bubbles; there are no bubbles on this device. */
	public boolean areBubblesAllowed() {
		return false;
	}

	public boolean areBubblesEnabled() {
		return false;
	}

	/** AOSP: the package allowed to post and cancel notifications for this app. */
	public void setNotificationDelegate(String delegate) {
		synchronized (sLock) {
			sNotificationDelegate = delegate;
		}
	}

	public String getNotificationDelegate() {
		synchronized (sLock) {
			return sNotificationDelegate;
		}
	}

	public boolean canNotifyAsPackage(String pkg) {
		return tlContext().getPackageName().equals(pkg);
	}

	/* ---------------- ATL desktop forwarding (ATL_DESKTOP_NOTIFICATIONS) ---------------- */

	private void tlDesktopShow(int id, Notification notification) {
		if (notification.mStyle instanceof Notification.MediaStyle) { // MPRIS content is handled by the MediaSession implementation
			if (sMprisNotificationId == -1) {
				Application application = ATLLoadedApp.getPrimaryApplication().getApplication();
				nativeShowMPRIS(application.getPackageName(), application.get_app_label());
				sMprisNotificationId = id;
			}
			return;
		}
		long builder = nativeInitBuilder();
		if (notification.actions != null) {
			for (Notification.Action action : notification.actions) {
				int intentType = -1;
				Intent intent = null;
				if (action.actionIntent != null) {
					intentType = action.actionIntent.type;
					intent = action.actionIntent.intent;
				}
				nativeAddAction(builder, String.valueOf(action.title), intentType, intent);
			}
		}
		int intentType = -1;
		Intent intent = null;
		if (notification.contentIntent != null) {
			intentType = notification.contentIntent.type;
			intent = notification.contentIntent.intent;
		}
		String iconPath = null;
		if (notification.icon != 0) {
			try {
				iconPath = tlContext().getString(notification.icon);
			} catch (RuntimeException e) {
				iconPath = null;
			}
		}
		CharSequence title = notification.extras.getCharSequence(Notification.EXTRA_TITLE);
		CharSequence text = notification.extras.getCharSequence(Notification.EXTRA_TEXT);
		nativeShowNotification(builder, id, title != null ? title.toString() : null, text != null ? text.toString() : null,
		                       iconPath, (notification.flags & Notification.FLAG_ONGOING_EVENT) != 0, intentType, intent);
	}

	private void tlDesktopCancel(final int id) {
		// remove_notification doesn't work reliably when sent directly after add_notification in GNOME session.
		// So we give some extra delay here.
		new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
			@Override
			public void run() {
				if (sMprisNotificationId == id) {
					sMprisNotificationId = -1;
					nativeCancelMPRIS();
				} else {
					nativeCancel(id);
				}
			}
		}, 100);
	}

	protected native long nativeInitBuilder();
	protected native void nativeAddAction(long builder, String title, int intentType, Intent intent);
	protected native void nativeShowNotification(long builder, int id, String title, String text, String iconPath, boolean ongoing, int intentType, Intent intent);
	protected native void nativeShowMPRIS(String packageName, String identiy);
	protected native void nativeCancel(int id);
	protected native void nativeCancelMPRIS();
}
