	/** Callback for the result of a sent PendingIntent (AOSP). */
	public interface OnFinished {
		void onSendFinished(PendingIntent pendingIntent, Intent intent, int resultCode, String resultData, Bundle resultExtras);
	}

	public void send(int code) throws CanceledException {
		send(null, code, null, null, null, null, null);
	}

	public void send(Bundle options) throws CanceledException {
		send(null, 0, null, null, null, null, options);
	}

	public void send(int code, OnFinished onFinished, android.os.Handler handler) throws CanceledException {
		send(null, code, null, onFinished, handler, null, null);
	}

	public void send(Context context, int code, Intent intent, OnFinished onFinished, android.os.Handler handler) throws CanceledException {
		send(context, code, intent, onFinished, handler, null, null);
	}

	public void send(Context context, int code, Intent intent, OnFinished onFinished, android.os.Handler handler, String requiredPermission) throws CanceledException {
		send(context, code, intent, onFinished, handler, requiredPermission, null);
	}

	/** The full send: performs the intent, then reports completion to onFinished on the handler. */
	public void send(final Context context, final int code, final Intent intent, final OnFinished onFinished, android.os.Handler handler, String requiredPermission, Bundle options) throws CanceledException {
		send(context, code, intent);
		if (onFinished != null) {
			Runnable done = new Runnable() {
				@Override
				public void run() {
					onFinished.onSendFinished(PendingIntent.this, intent, code, null, null);
				}
			};
			if (handler != null)
				handler.post(done);
			else
				done.run();
		}
	}
