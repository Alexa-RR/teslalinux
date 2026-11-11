package android.content;

import android.os.Process;

/* teslalinux: API 31 attribution plumbing; Firebase's init provider calls Context.getAttributionSource().
 * There is one process and one app, so a single static source describes everything. */
public final class AttributionSource {
	private final int uid;
	private final String packageName;
	private final String attributionTag;

	public AttributionSource() {
		this(Process.myUid(), null, null);
	}

	public AttributionSource(int uid, String packageName, String attributionTag) {
		this.uid = uid;
		this.packageName = packageName;
		this.attributionTag = attributionTag;
	}

	public int getUid() { return uid; }
	public int getPid() { return Process.myPid(); }
	public String getPackageName() { return packageName; }
	public String getAttributionTag() { return attributionTag; }
	public AttributionSource getNext() { return null; }
	public boolean checkCallingUid() { return true; }
	public boolean isTrusted(Context context) { return true; }

	public static final class Builder {
		private final int uid;
		private String packageName;
		private String attributionTag;
		public Builder(int uid) { this.uid = uid; }
		public Builder(AttributionSource current) { this.uid = current.uid; this.packageName = current.packageName; this.attributionTag = current.attributionTag; }
		public Builder setPackageName(String packageName) { this.packageName = packageName; return this; }
		public Builder setAttributionTag(String attributionTag) { this.attributionTag = attributionTag; return this; }
		public Builder setNext(AttributionSource next) { return this; }
		public AttributionSource build() { return new AttributionSource(uid, packageName, attributionTag); }
	}
}
