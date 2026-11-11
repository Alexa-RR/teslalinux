package android.view;

import android.graphics.Rect;

public final class WindowMetrics {
	private final Rect bounds; private final WindowInsets insets; private final float density;
	public WindowMetrics(Rect bounds, WindowInsets windowInsets) { this(bounds, windowInsets, 1.0f); }
	public WindowMetrics(Rect bounds, WindowInsets windowInsets, float density) { this.bounds = bounds; this.insets = windowInsets; this.density = density; }
	public Rect getBounds() { return bounds; }
	public WindowInsets getWindowInsets() { return insets; }
	public float getDensity() { return density; }
}
