package android.window;

public interface OnBackInvokedDispatcher {
	int PRIORITY_DEFAULT = 0;
	int PRIORITY_OVERLAY = 1000000;
	void registerOnBackInvokedCallback(int priority, OnBackInvokedCallback callback);
	void unregisterOnBackInvokedCallback(OnBackInvokedCallback callback);

	/* teslalinux: a dispatcher that never dispatches; back navigation goes through Activity.onBackPressed */
	OnBackInvokedDispatcher STUB = new OnBackInvokedDispatcher() {
		public void registerOnBackInvokedCallback(int priority, OnBackInvokedCallback callback) {}
		public void unregisterOnBackInvokedCallback(OnBackInvokedCallback callback) {}
	};
}
