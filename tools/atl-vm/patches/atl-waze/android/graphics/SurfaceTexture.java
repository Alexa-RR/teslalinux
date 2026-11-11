package android.graphics;

public class SurfaceTexture {

	public static interface OnFrameAvailableListener {}

	public SurfaceTexture(int texName) {}

	public void setOnFrameAvailableListener(OnFrameAvailableListener listener) {}

	public void release() {}

	private int mDefaultWidth, mDefaultHeight;

	/** Default size of the image buffers producers allocate when they do not set one (AOSP). */
	public void setDefaultBufferSize(int width, int height) {
		mDefaultWidth = width;
		mDefaultHeight = height;
	}
}
