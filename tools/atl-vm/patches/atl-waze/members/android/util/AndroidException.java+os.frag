	/** AOSP (hidden): control over suppression and the stack trace. */
	protected AndroidException(String message, Throwable cause, boolean enableSuppression,
	                           boolean writableStackTrace) {
		super(message, cause, enableSuppression, writableStackTrace);
	}
