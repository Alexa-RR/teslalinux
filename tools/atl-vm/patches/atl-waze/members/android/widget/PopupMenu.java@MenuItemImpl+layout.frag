		/* teslalinux (layout): AOSP MenuItemImpl state ATL's item lacked */
		int tlGroupId;
		int tlOrder;
		private Intent tlIntent;
		private char tlShortcutNumericChar;
		private int tlShortcutNumericModifiers = KeyEvent.META_CTRL_ON;
		private char tlShortcutAlphabeticChar;
		private int tlShortcutAlphabeticModifiers = KeyEvent.META_CTRL_ON;
		private android.view.ActionProvider tlActionProvider;
		private View tlActionView;
		private boolean tlActionViewExpanded;
		private OnActionExpandListener tlOnActionExpandListener;
		private OnMenuItemClickListener tlClickListener;
		private boolean tlCheckable;
		private boolean tlChecked;
		private boolean tlEnabled = true;
		private CharSequence tlTitleCondensed;
		private int tlShowAsAction;
		private CharSequence tlContentDescription;
		private CharSequence tlTooltipText;

		/**
		 * AOSP MenuItemImpl.invoke: the item's own listener, then the popup's listener, then the
		 * intent (started as an activity), then the action provider's default action.
		 */
		boolean tlInvoke() {
			if (tlClickListener != null && tlClickListener.onMenuItemClick(this))
				return true;
			if (mMenuItemClickListener != null && mMenuItemClickListener.onMenuItemClick(this))
				return true;
			if (tlIntent != null) {
				try {
					mContext.startActivity(tlIntent);
					return true;
				} catch (android.content.ActivityNotFoundException e) {
					android.util.Log.e("PopupMenu", "Can't find activity to handle intent; ignoring", e);
				}
			}
			return tlActionProvider != null && tlActionProvider.onPerformDefaultAction();
		}

		@Override
		public Intent getIntent() {
			return tlIntent;
		}

		@Override
		public char getNumericShortcut() {
			return tlShortcutNumericChar;
		}

		@Override
		public char getAlphabeticShortcut() {
			return tlShortcutAlphabeticChar;
		}

		@Override
		public MenuItem setNumericShortcut(char numericChar, int numericModifiers) {
			tlShortcutNumericChar = numericChar;
			tlShortcutNumericModifiers = KeyEvent.normalizeMetaState(numericModifiers);
			return this;
		}

		@Override
		public int getNumericModifiers() {
			return tlShortcutNumericModifiers;
		}

		@Override
		public MenuItem setAlphabeticShortcut(char alphaChar, int alphaModifiers) {
			tlShortcutAlphabeticChar = Character.toLowerCase(alphaChar);
			tlShortcutAlphabeticModifiers = KeyEvent.normalizeMetaState(alphaModifiers);
			return this;
		}

		@Override
		public int getAlphabeticModifiers() {
			return tlShortcutAlphabeticModifiers;
		}

		@Override
		public MenuItem setShortcut(char numericChar, char alphaChar, int numericModifiers, int alphaModifiers) {
			setNumericShortcut(numericChar, numericModifiers);
			return setAlphabeticShortcut(alphaChar, alphaModifiers);
		}

		/** AOSP: an item of a context menu carries the menu info; a popup menu's items have none */
		@Override
		public android.view.ContextMenu.ContextMenuInfo getMenuInfo() {
			return null;
		}

		@Override
		public MenuItem setActionProvider(android.view.ActionProvider actionProvider) {
			if (tlActionProvider != null)
				tlActionProvider.reset();
			tlActionView = null;
			tlActionProvider = actionProvider;
			return this;
		}

		@Override
		public android.view.ActionProvider getActionProvider() {
			return tlActionProvider;
		}

		/** AOSP: an expanded action view collapses, if the listener lets it */
		@Override
		public boolean collapseActionView() {
			if ((tlShowAsAction & SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW) == 0 || tlActionView == null)
				return false;
			if (!tlActionViewExpanded)
				return false;
			if (tlOnActionExpandListener == null || tlOnActionExpandListener.onMenuItemActionCollapse(this)) {
				tlActionViewExpanded = false;
				return true;
			}
			return false;
		}

		@Override
		public MenuItem setContentDescription(CharSequence contentDescription) {
			tlContentDescription = contentDescription;
			return this;
		}

		@Override
		public CharSequence getContentDescription() {
			return tlContentDescription;
		}

		@Override
		public MenuItem setTooltipText(CharSequence tooltipText) {
			tlTooltipText = tooltipText;
			return this;
		}

		@Override
		public CharSequence getTooltipText() {
			return tlTooltipText;
		}
