	/** AOSP: the surface is destroyed when the view loses its window (native unrealize). */
	private void surfaceDestroyed() {
		if (!tlSurfaceCreated)
			return;
		tlSurfaceCreated = false;
		android.util.Log.i("SurfaceView", "surfaceDestroyed " + this + " callbacks=" + mCallbacks.size());
		for (SurfaceHolder.Callback c : new java.util.ArrayList<>(mCallbacks)) {
			c.surfaceDestroyed(mSurfaceHolder);
		}
	}
