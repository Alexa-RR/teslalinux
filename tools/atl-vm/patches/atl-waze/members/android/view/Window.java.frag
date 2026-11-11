	/* teslalinux: Window additions */
	@SuppressWarnings("unchecked")
	public <T extends View> T requireViewById(int id) {
		View v = findViewById(id);
		if (v == null) throw new IllegalArgumentException("ID does not reference a View inside this Window");
		return (T) v;
	}
	public void setHideOverlayWindows(boolean hide) {}
	public int getColorMode() { return 0; }
	public void setColorMode(int colorMode) {}
	public boolean isWideColorGamut() { return false; }
	public void setPreferMinimalPostProcessing(boolean isPreferred) {}
	public void setSustainedPerformanceMode(boolean enable) {}
	/** teslalinux: removes the decor from the window (a destroyed activity's window is gone in AOSP). */
	public native void tlRemoveRoot(long native_window, long widget);
	private int tlSoftInputMode;

	public int tlGetSoftInputMode() {
		return tlSoftInputMode;
	}

	/* ---- teslalinux: the process's windows (tl_touch_mode) ----
	 * AOSP's WindowManagerGlobal knows every window root of the process (it reports a display-wide
	 * change such as touch mode to each ViewRootImpl). Windows are held weakly; a window whose
	 * activity or dialog is gone drops out once collected. */
	private static final java.util.ArrayList<java.lang.ref.WeakReference<Window>> tlWindows = new java.util.ArrayList<>();

	private static void tlRegisterWindow(Window window) {
		synchronized (tlWindows) {
			tlWindows.add(new java.lang.ref.WeakReference<>(window));
		}
	}

	/** the live windows of the process, oldest first */
	public static java.util.List<Window> tlLiveWindows() {
		java.util.ArrayList<Window> live = new java.util.ArrayList<>();
		synchronized (tlWindows) {
			for (java.util.Iterator<java.lang.ref.WeakReference<Window>> it = tlWindows.iterator(); it.hasNext();) {
				Window w = it.next().get();
				if (w == null)
					it.remove();
				else
					live.add(w);
			}
		}
		return live;
	}
