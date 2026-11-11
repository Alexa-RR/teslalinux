package android.content;

import android.os.IBinder;

/* teslalinux: the full AOSP interface. ATL declared only onServiceConnected; apps implement the
 * complete contract and the runtime must be able to report disconnection and null bindings. */
public interface ServiceConnection {
	void onServiceConnected(ComponentName name, IBinder service);
	void onServiceDisconnected(ComponentName name);
	default void onBindingDied(ComponentName name) {}
	default void onNullBinding(ComponentName name) {}
}
