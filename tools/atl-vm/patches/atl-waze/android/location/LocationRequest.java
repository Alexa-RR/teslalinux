package android.location;

public final class LocationRequest {
	public static final int QUALITY_LOW_POWER = 104, QUALITY_BALANCED_POWER_ACCURACY = 102, QUALITY_HIGH_ACCURACY = 100;
	public static final long PASSIVE_INTERVAL = Long.MAX_VALUE;
	private final long intervalMillis, minUpdateIntervalMillis, maxUpdateDelayMillis, durationMillis;
	private final int quality, maxUpdates;
	private final float minUpdateDistanceMeters;

	LocationRequest(long interval, long minInterval, long maxDelay, long duration, int quality, int maxUpdates, float minDistance) {
		intervalMillis = interval; minUpdateIntervalMillis = minInterval; maxUpdateDelayMillis = maxDelay; durationMillis = duration;
		this.quality = quality; this.maxUpdates = maxUpdates; minUpdateDistanceMeters = minDistance;
	}
	public long getIntervalMillis() { return intervalMillis; }
	public long getMinUpdateIntervalMillis() { return minUpdateIntervalMillis; }
	public long getMaxUpdateDelayMillis() { return maxUpdateDelayMillis; }
	public long getDurationMillis() { return durationMillis; }
	public int getQuality() { return quality; }
	public int getMaxUpdates() { return maxUpdates; }
	public float getMinUpdateDistanceMeters() { return minUpdateDistanceMeters; }
	public boolean isLocationSettingsIgnored() { return false; }

	public static final class Builder {
		private long interval, minInterval = -1, maxDelay, duration = Long.MAX_VALUE;
		private int quality = QUALITY_BALANCED_POWER_ACCURACY, maxUpdates = Integer.MAX_VALUE;
		private float minDistance;
		public Builder(long intervalMillis) { interval = intervalMillis; }
		public Builder(LocationRequest r) { interval = r.intervalMillis; minInterval = r.minUpdateIntervalMillis; maxDelay = r.maxUpdateDelayMillis; duration = r.durationMillis; quality = r.quality; maxUpdates = r.maxUpdates; minDistance = r.minUpdateDistanceMeters; }
		public Builder setIntervalMillis(long v) { interval = v; return this; }
		public Builder setMinUpdateIntervalMillis(long v) { minInterval = v; return this; }
		public Builder clearMinUpdateIntervalMillis() { minInterval = -1; return this; }
		public Builder setMaxUpdateDelayMillis(long v) { maxDelay = v; return this; }
		public Builder setDurationMillis(long v) { duration = v; return this; }
		public Builder setQuality(int v) { quality = v; return this; }
		public Builder setMaxUpdates(int v) { maxUpdates = v; return this; }
		public Builder setMinUpdateDistanceMeters(float v) { minDistance = v; return this; }
		public Builder setLocationSettingsIgnored(boolean v) { return this; }
		public LocationRequest build() { return new LocationRequest(interval, minInterval < 0 ? interval : minInterval, maxDelay, duration, quality, maxUpdates, minDistance); }
	}
}
