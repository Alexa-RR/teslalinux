package android.app;

import android.media.AudioAttributes;
import android.net.Uri;

/* teslalinux: replaces ATL's no-op NotificationChannel with one that remembers what it was given. */
public class NotificationChannel {
	public static final String DEFAULT_CHANNEL_ID = "miscellaneous";

	private final String id;
	private CharSequence name;
	private int importance;
	private String description, group, conversationId, parentChannelId;
	private boolean showBadge = true, bypassDnd, lights, vibration, bubbles, importantConversation;
	private int lightColor, lockscreenVisibility = -1000;
	private long[] vibrationPattern;
	private Uri sound;
	private AudioAttributes audioAttributes;

	public NotificationChannel(String id, CharSequence name, int importance) {
		this.id = id; this.name = name; this.importance = importance;
	}

	public String getId() { return id; }
	public CharSequence getName() { return name; }
	public void setName(CharSequence name) { this.name = name; }
	public int getImportance() { return importance; }
	public void setImportance(int importance) { this.importance = importance; }
	public String getDescription() { return description; }
	public void setDescription(String description) { this.description = description; }
	public String getGroup() { return group; }
	public void setGroup(String grp) { this.group = grp; }
	public boolean canShowBadge() { return showBadge; }
	public void setShowBadge(boolean a) { showBadge = a; }
	public boolean canBypassDnd() { return bypassDnd; }
	public void setBypassDnd(boolean bypassDnd) { this.bypassDnd = bypassDnd; }
	public int getLockscreenVisibility() { return lockscreenVisibility; }
	public void setLockscreenVisibility(int a) { lockscreenVisibility = a; }
	public void enableLights(boolean en) { lights = en; }
	public boolean shouldShowLights() { return lights; }
	public void setLightColor(int color) { lightColor = color; }
	public int getLightColor() { return lightColor; }
	public void setVibrationPattern(long[] pattern) { vibrationPattern = pattern; vibration = pattern != null && pattern.length > 0; }
	public long[] getVibrationPattern() { return vibrationPattern; }
	public void enableVibration(boolean en) { vibration = en; }
	public boolean shouldVibrate() { return vibration; }
	public void setSound(Uri uri, AudioAttributes attrs) { sound = uri; audioAttributes = attrs; }
	public Uri getSound() { return sound; }
	public AudioAttributes getAudioAttributes() { return audioAttributes; }
	public String getParentChannelId() { return parentChannelId; }
	public String getConversationId() { return conversationId; }
	public void setConversationId(String parentChannelId, String conversationId) { this.parentChannelId = parentChannelId; this.conversationId = conversationId; }
	public boolean isImportantConversation() { return importantConversation; }
	public void setImportantConversation(boolean v) { importantConversation = v; }
	public boolean canBubble() { return bubbles; }
	public void setAllowBubbles(boolean v) { bubbles = v; }
	public boolean hasUserSetImportance() { return false; }
	public boolean hasUserSetSound() { return false; }
	public boolean isBlockable() { return true; }
	@Override public String toString() { return "NotificationChannel{" + id + "," + name + "," + importance + "}"; }
}
