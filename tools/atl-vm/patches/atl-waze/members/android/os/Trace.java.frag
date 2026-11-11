	/* teslalinux: Trace additions (AOSP API 29); like every Trace call they emit only while the APP tag is enabled */
	/** AOSP: an asynchronous section begins (cookie pairs it with its end). */
	public static void beginAsyncSection(String methodName, int cookie) {
		asyncTraceBegin(TRACE_TAG_APP, methodName, cookie);
	}
	/** AOSP: the asynchronous section with this name and cookie ends. */
	public static void endAsyncSection(String methodName, int cookie) {
		asyncTraceEnd(TRACE_TAG_APP, methodName, cookie);
	}
	/** AOSP: a named counter's value. ATL's trace native carries 32-bit values, so it is clamped. */
	public static void setCounter(String counterName, long counterValue) {
		if (isTagEnabled(TRACE_TAG_APP)) {
			int v = counterValue > Integer.MAX_VALUE ? Integer.MAX_VALUE
			      : counterValue < Integer.MIN_VALUE ? Integer.MIN_VALUE : (int) counterValue;
			nativeTraceCounter(TRACE_TAG_APP, counterName, v);
		}
	}
