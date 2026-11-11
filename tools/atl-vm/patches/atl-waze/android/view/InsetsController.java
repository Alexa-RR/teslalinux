package android.view;

import android.view.WindowInsets.Type.InsetsType;
import android.view.inputmethod.InputMethodManager;
import java.util.ArrayList;

/**
 * teslalinux: the WindowInsetsController of a window (AOSP InsetsController, the app-facing part).
 * The car screen has no status, navigation or caption bar, so the only inset source a window can
 * control is the input method: show(ime()) and hide(ime()) show and hide the on-screen keyboard
 * for the window's focused view, as AOSP's InsetsController does through the IMM. The bar
 * appearance and behavior are kept per window (AOSP stores them for the bars' next state); showing
 * or hiding bars that do not exist changes nothing, as on a device without them. The controllable
 * types are therefore ime(), and a listener hears them when it is added.
 */
public class InsetsController implements WindowInsetsController {
	private final Window mWindow;
	private int mAppearance;
	private int mAppearanceMask;
	private int mBehavior = BEHAVIOR_DEFAULT;
	private final ArrayList<OnControllableInsetsChangedListener> mControllableInsetsChangedListeners = new ArrayList<>();

	/** a controller without a window (a view not attached yet): nothing to control */
	public InsetsController() {
		this(null);
	}

	public InsetsController(Window window) {
		mWindow = window;
	}

	/** the types this controller can show and hide (AOSP calculateControllableTypes) */
	private int tlControllableTypes() {
		return mWindow != null ? WindowInsets.Type.ime() : 0;
	}

	private View tlFocusedView() {
		if (mWindow == null)
			return null;
		final View decor = mWindow.getDecorView();
		return decor != null ? decor.findFocus() : null;
	}

	@Override
	public void show(@InsetsType int types) {
		if ((types & WindowInsets.Type.ime()) != 0 && mWindow != null) {
			final View focused = tlFocusedView();
			if (focused != null) {
				final InputMethodManager imm = (InputMethodManager) mWindow.getContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
				if (imm != null)
					imm.showSoftInput(focused, 0);
			}
		}
	}

	@Override
	public void hide(@InsetsType int types) {
		if ((types & WindowInsets.Type.ime()) != 0 && mWindow != null) {
			final InputMethodManager imm = (InputMethodManager) mWindow.getContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
			if (imm != null) {
				final View decor = mWindow.getDecorView();
				imm.hideSoftInputFromWindow(decor != null ? decor.getWindowToken() : null, 0);
			}
		}
	}

	@Override
	public void setSystemBarsAppearance(int appearance, int mask) {
		mAppearanceMask |= mask;
		mAppearance = (mAppearance & ~mask) | (appearance & mask);
	}

	@Override
	public int getSystemBarsAppearance() {
		return mAppearance;
	}

	@Override
	public void setSystemBarsBehavior(int behavior) {
		mBehavior = behavior;
	}

	@Override
	public int getSystemBarsBehavior() {
		return mBehavior;
	}

	/** AOSP: the listener hears the controllable types at once and on every change */
	@Override
	public void addOnControllableInsetsChangedListener(OnControllableInsetsChangedListener listener) {
		if (mControllableInsetsChangedListeners.contains(listener))
			return;
		mControllableInsetsChangedListeners.add(listener);
		listener.onControllableInsetsChanged(this, tlControllableTypes());
	}

	@Override
	public void removeOnControllableInsetsChangedListener(OnControllableInsetsChangedListener listener) {
		mControllableInsetsChangedListeners.remove(listener);
	}
}
