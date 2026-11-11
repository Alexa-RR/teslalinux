package android.telephony;

public final class TelephonyDisplayInfo {
	public static final int OVERRIDE_NETWORK_TYPE_NONE = 0, OVERRIDE_NETWORK_TYPE_LTE_CA = 1, OVERRIDE_NETWORK_TYPE_LTE_ADVANCED_PRO = 2,
	                        OVERRIDE_NETWORK_TYPE_NR_NSA = 3, OVERRIDE_NETWORK_TYPE_NR_ADVANCED = 5;
	public int getNetworkType() { return 0; }
	public int getOverrideNetworkType() { return OVERRIDE_NETWORK_TYPE_NONE; }
	public boolean isRoaming() { return false; }
}
