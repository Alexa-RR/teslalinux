	/* teslalinux: Choreographer vsync callbacks (API 33), mapped onto the frame callback */
	public interface VsyncCallback { void onVsync(FrameData data); }
	public static final class FrameTimeline {
		private final long vsync, deadline, present;
		FrameTimeline(long vsync, long deadline, long present) { this.vsync = vsync; this.deadline = deadline; this.present = present; }
		public long getVsyncId() { return vsync; }
		public long getDeadlineNanos() { return deadline; }
		public long getExpectedPresentationTimeNanos() { return present; }
	}
	public static final class FrameData {
		private final long frameTimeNanos; private final FrameTimeline[] timelines;
		FrameData(long t) { frameTimeNanos = t; timelines = new FrameTimeline[] { new FrameTimeline(0, t + 8000000L, t + 16666667L) }; }
		public long getFrameTimeNanos() { return frameTimeNanos; }
		public FrameTimeline[] getFrameTimelines() { return timelines; }
		public FrameTimeline getPreferredFrameTimeline() { return timelines[0]; }
	}
	private final java.util.Map<VsyncCallback, FrameCallback> tlVsync = new java.util.HashMap<>();
	public void postVsyncCallback(final VsyncCallback callback) {
		FrameCallback fc = new FrameCallback() { public void doFrame(long frameTimeNanos) { tlVsync.remove(callback); callback.onVsync(new FrameData(frameTimeNanos)); } };
		tlVsync.put(callback, fc);
		postFrameCallback(fc);
	}
	public void removeVsyncCallback(VsyncCallback callback) {
		FrameCallback fc = tlVsync.remove(callback);
		if (fc != null) removeFrameCallback(fc);
	}
	public long getVsyncId() { return 0; }
	/* teslalinux: AOSP's FrameDisplayEventReceiver over the toplevel's GTK frame clock */
	private long mTimestampNanos;

	/** Requests one vsync from the frame clock; false when the toplevel has no frame clock yet. */
	private boolean scheduleVsyncLocked() {
		boolean ok = nativeScheduleVsync();
		if (TL_DEBUG_VSYNC && tlVsyncLogged < 3) {
			tlVsyncLogged++;
			Log.i(TAG, "[vsync] scheduleVsyncLocked native=" + ok + " mainLooper=" + (mLooper == Looper.getMainLooper()));
		}
		return ok;
	}
	private static final boolean TL_DEBUG_VSYNC = System.getenv("ATL_DEBUG_VSYNC") != null;
	private int tlVsyncLogged;

	void doScheduleVsync() {
		synchronized (mLock) {
			if (mFrameScheduled && !scheduleVsyncLocked()) {
				final long now = SystemClock.uptimeMillis();
				Message msg = mHandler.obtainMessage(MSG_DO_FRAME);
				msg.setAsynchronous(true);
				mHandler.sendMessageAtTime(msg, Math.max(mLastFrameTimeNanos / NANOS_PER_MS + sFrameDelay, now));
			}
		}
	}

	/**
	 * The frame clock's update phase (main thread): AOSP FrameDisplayEventReceiver.onVsync, which
	 * leads to doFrame. On Android doFrame runs input, animation and traversal (layout and draw) in
	 * one pass per vsync, so a frame never shows state the animation callbacks have not processed.
	 * Here layout and paint are the frame clock's LAYOUT and PAINT phases, which follow UPDATE, so
	 * doFrame runs right here, as a GTK tick callback does: posting it to the looper ran it after
	 * that frame's paint, every animation frame was shown one frame late, and a paint between a state
	 * change and the recomposition that animates it showed the end state for a frame (Waze's menu
	 * flashed open before sliding in).
	 */
	private void tlOnVsync(long timestampNanos) {
		long now = System.nanoTime();
		if (timestampNanos > now) {
			timestampNanos = now;
		}
		mTimestampNanos = timestampNanos;
		doFrame(timestampNanos, 0);
	}

	private native boolean nativeScheduleVsync();
