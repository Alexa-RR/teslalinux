package android.media;

public final class AudioRecordingConfiguration {
	public int getClientAudioSessionId() { return 0; }
	public int getClientAudioSource() { return 0; }
	public AudioFormat getFormat() { return null; }
	public AudioFormat getClientFormat() { return null; }
	public AudioDeviceInfo getAudioDevice() { return null; }
	public boolean isClientSilenced() { return false; }
}
