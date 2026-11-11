package android.media;

public final class PlaybackParams {
	public static final int AUDIO_FALLBACK_MODE_DEFAULT = 0, AUDIO_FALLBACK_MODE_MUTE = 1, AUDIO_FALLBACK_MODE_FAIL = 2;
	public static final int AUDIO_STRETCH_MODE_DEFAULT = 0, AUDIO_STRETCH_MODE_VOICE = 1;
	private float speed = 1f, pitch = 1f; private int fallback, stretch;
	public PlaybackParams allowDefaults() { return this; }
	public PlaybackParams setSpeed(float v) { speed = v; return this; }
	public float getSpeed() { return speed; }
	public PlaybackParams setPitch(float v) { pitch = v; return this; }
	public float getPitch() { return pitch; }
	public PlaybackParams setAudioFallbackMode(int v) { fallback = v; return this; }
	public int getAudioFallbackMode() { return fallback; }
	public PlaybackParams setAudioStretchMode(int v) { stretch = v; return this; }
	public int getAudioStretchMode() { return stretch; }
}
