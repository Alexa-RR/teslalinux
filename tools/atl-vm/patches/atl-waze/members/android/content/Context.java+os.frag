	/** the Handler each registered receiver asked to run on (registerReceiver with a scheduler) */
	private static final java.util.Map<BroadcastReceiver, Handler> tlReceiverSchedulers = new ConcurrentHashMap<>();

	/**
	 * AOSP LoadedApk.ReceiverDispatcher: the broadcast reaches a registered receiver later, on its
	 * scheduler (the main thread by default), as a copy of the sent intent, with a PendingResult the
	 * receiver may take with goAsync(); one it leaves is finished after onReceive. A receiver
	 * unregistered before delivery does not get it.
	 */
	private void tlDeliverBroadcast(final BroadcastReceiver receiver, Intent intent) {
		if (receiver == null)
			return;
		Handler scheduler = tlReceiverSchedulers.get(receiver);
		if (scheduler == null)
			scheduler = new Handler(Looper.getMainLooper());
		final Intent delivered = new Intent(intent);
		final Context context = this;
		scheduler.post(new Runnable() {
			@Override
			public void run() {
				if (!receiverMap.containsValue(receiver))
					return;
				receiver.setPendingResult(new BroadcastReceiver.PendingResult(0, null, null,
				    BroadcastReceiver.PendingResult.TYPE_REGISTERED, false, false, null,
				    android.os.UserHandle.myUserId(), delivered.getFlags()));
				try {
					receiver.onReceive(context, delivered);
				} finally {
					BroadcastReceiver.PendingResult result = receiver.getPendingResult();
					if (result != null) {
						receiver.setPendingResult(null);
						result.finish();
					}
				}
			}
		});
	}
