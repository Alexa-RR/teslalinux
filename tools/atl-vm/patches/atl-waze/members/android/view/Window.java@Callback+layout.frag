		/* teslalinux (layout): the AOSP Window.Callback members ATL lacked */

		/** AOSP default: the callback adds no keyboard shortcuts of its own */
		default void onProvideKeyboardShortcuts(java.util.List<KeyboardShortcutGroup> data, Menu menu, int deviceId) {}

		/**
		 * AOSP: the search key or a search request, with the event that caused it; Activity keeps the
		 * event for getSearchEvent and runs onSearchRequested(), which a callback without its own
		 * handling of the event does here.
		 */
		default boolean onSearchRequested(SearchEvent searchEvent) {
			return onSearchRequested();
		}

		/**
		 * AOSP: fills an accessibility event about the window (Activity: its title, class and
		 * package). A callback that does not describe its window returns false, as View does for a
		 * view with nothing to add.
		 */
		default boolean dispatchPopulateAccessibilityEvent(android.view.accessibility.AccessibilityEvent event) {
			return false;
		}
