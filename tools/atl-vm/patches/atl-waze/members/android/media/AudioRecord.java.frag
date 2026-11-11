import java.util.concurrent.Executor;
	/* teslalinux: AudioRecord additions. The recorder state the constructor records (repl) backs the getters. */
	private int tlAudioFormat = AudioFormat.ENCODING_PCM_16BIT;
	private int tlChannelMask;
	private int tlBufferSizeInBytes;
	private int tlSessionId;
	private AudioDeviceInfo tlPreferredDevice;
	private final java.util.concurrent.CopyOnWriteArrayList<AudioManager.AudioRecordingCallback> tlRecordingCallbacks = new java.util.concurrent.CopyOnWriteArrayList<>();

	/** AOSP: the session the recorder was created in, allocated like AudioFlinger's unique ids. */
	public int getAudioSessionId() {
		synchronized (this) {
			if (tlSessionId == 0)
				tlSessionId = AudioSystem.newAudioSessionId();
			return tlSessionId;
		}
	}

	/** AOSP returns ERROR_INVALID_OPERATION when no capture timestamp is available; the ALSA capture path reports none. */
	public int getTimestamp(AudioTimestamp outTimestamp, int timebase) {
		if (outTimestamp == null || (timebase != AudioTimestamp.TIMEBASE_BOOTTIME && timebase != AudioTimestamp.TIMEBASE_MONOTONIC))
			throw new IllegalArgumentException();
		return -3; // ERROR_INVALID_OPERATION
	}

	public void registerAudioRecordingCallback(Executor executor, AudioManager.AudioRecordingCallback cb) {
		if (cb == null)
			throw new IllegalArgumentException("Illegal null AudioRecordingCallback");
		if (executor == null)
			throw new IllegalArgumentException("Illegal null Executor");
		if (!tlRecordingCallbacks.contains(cb))
			tlRecordingCallbacks.add(cb);
	}

	public void unregisterAudioRecordingCallback(AudioManager.AudioRecordingCallback cb) {
		if (cb == null)
			throw new IllegalArgumentException("Illegal null AudioRecordingCallback argument");
		tlRecordingCallbacks.remove(cb);
	}

	/** AOSP: null while the recorder is not recording; the recording registry has no entry for ALSA capture. */
	public AudioRecordingConfiguration getActiveRecordingConfiguration() {
		return null;
	}

	public boolean setPreferredDevice(AudioDeviceInfo deviceInfo) {
		if (deviceInfo != null && !deviceInfo.isSource())
			return false;
		tlPreferredDevice = deviceInfo;
		return true;
	}

	public AudioDeviceInfo getPreferredDevice() {
		return tlPreferredDevice;
	}

	/** AOSP: the input device while recording, else null. */
	public AudioDeviceInfo getRoutedDevice() {
		return getRecordingState() == RECORDSTATE_RECORDING ? AudioManager.tlDefaultInputDevice() : null;
	}

	public int getChannelCount() {
		return channels > 0 ? channels : Math.max(1, Integer.bitCount(tlChannelMask));
	}

	public int getChannelConfiguration() {
		return tlChannelMask;
	}

	public int getAudioFormat() {
		return tlAudioFormat;
	}

	public int getBufferSizeInFrames() {
		int frameBytes = getChannelCount() * AudioFormat.getBytesPerSample(tlAudioFormat);
		return frameBytes > 0 ? tlBufferSizeInBytes / frameBytes : 0;
	}

	/** AOSP read(ByteBuffer, int): 16-bit PCM into a direct buffer, in native byte order. */
	public int read(java.nio.ByteBuffer audioBuffer, int sizeInBytes) {
		if (audioBuffer == null || sizeInBytes < 0 || sizeInBytes > audioBuffer.capacity())
			return ERROR_BAD_VALUE;
		short[] tmp = new short[sizeInBytes / 2];
		int shorts = read(tmp, 0, tmp.length);
		if (shorts <= 0)
			return shorts;
		java.nio.ByteBuffer b = audioBuffer.duplicate().order(java.nio.ByteOrder.nativeOrder());
		b.position(0);
		b.asShortBuffer().put(tmp, 0, shorts);
		return shorts * 2;
	}
