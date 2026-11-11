	/** AOSP: a RemoteException with a cause. */
	public RemoteException(String message, Throwable cause) {
		super(message, cause);
	}

	/** AOSP (hidden): control over suppression and the stack trace (Parcel's remote-stack cause). */
	public RemoteException(String message, Throwable cause, boolean enableSuppression,
	                       boolean writableStackTrace) {
		super(message, cause, enableSuppression, writableStackTrace);
	}

	/** AOSP (API 26): a RemoteException from a system service is a fatal RuntimeException. */
	public RuntimeException rethrowFromSystemServer() {
		if (this instanceof DeadObjectException) {
			throw new RuntimeException(new DeadSystemException());
		} else {
			throw new RuntimeException(this);
		}
	}

	/** AOSP (API 26): rethrown as a RuntimeException. */
	public RuntimeException rethrowAsRuntimeException() {
		throw new RuntimeException(this);
	}
