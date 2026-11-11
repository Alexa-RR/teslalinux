package android.telephony;

/* teslalinux: API 31 TelephonyCallback. Waze registers one at startup to watch call state. There is
 * no telephony here: TelephonyManager delivers the registry's initial states (idle, disconnected)
 * and nothing changes them afterwards. */
public class TelephonyCallback {
	public interface CallStateListener { void onCallStateChanged(int state); }
	public interface DisplayInfoListener { void onDisplayInfoChanged(TelephonyDisplayInfo telephonyDisplayInfo); }
	public interface DataConnectionStateListener { void onDataConnectionStateChanged(int state, int networkType); }
	public interface DataActivityListener { void onDataActivity(int direction); }
	public interface CellInfoListener { void onCellInfoChanged(java.util.List<Object> cellInfo); }
	public interface UserMobileDataStateListener { void onUserMobileDataStateChanged(boolean enabled); }
	public interface ActiveDataSubscriptionIdListener { void onActiveDataSubscriptionIdChanged(int subId); }
	public interface CarrierNetworkListener { void onCarrierNetworkChange(boolean active); }
	public TelephonyCallback() {}
}
