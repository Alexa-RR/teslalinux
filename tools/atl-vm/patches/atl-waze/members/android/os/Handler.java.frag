	/** AOSP: like postDelayed(Runnable, long) with a token the Runnable can be removed by (removeCallbacksAndMessages). */
	public final boolean postDelayed(Runnable r, Object token, long delayMillis) {
		return postAtTime(r, token, android.os.SystemClock.uptimeMillis() + delayMillis);
	}

	/** AOSP API 28: a Handler whose messages are asynchronous (not subject to sync barriers). */
	public static Handler createAsync(Looper looper, Callback callback) {
		if (looper == null)
			throw new NullPointerException("looper must not be null");
		if (callback == null)
			throw new NullPointerException("callback must not be null");
		return new Handler(looper, callback, true);
	}
