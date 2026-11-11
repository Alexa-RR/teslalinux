package android.bluetooth;

/* teslalinux: this device has no Bluetooth radio exposed to apps (the Pi image disables it and the
 * VM has none), so like Android on such hardware the manager reports no adapter. */
public class BluetoothManager {
	public BluetoothAdapter getAdapter() {
		return BluetoothAdapter.getDefaultAdapter();
	}
}
