package android.media;

import android.os.Handler;

public interface AudioRouting {
	interface OnRoutingChangedListener { void onRoutingChanged(AudioRouting router); }
	boolean setPreferredDevice(AudioDeviceInfo deviceInfo);
	AudioDeviceInfo getPreferredDevice();
	AudioDeviceInfo getRoutedDevice();
	void addOnRoutingChangedListener(OnRoutingChangedListener listener, Handler handler);
	void removeOnRoutingChangedListener(OnRoutingChangedListener listener);
}
