import android.view.KeyEvent;
		/* teslalinux (layout): the AOSP Menu members ATL's menu lacked (MenuBuilder semantics) */
		private boolean tlQwertyMode;

		/** AOSP MenuBuilder.addSubMenu(CharSequence): a submenu at the end, no group or id */
		@Override
		public SubMenu addSubMenu(CharSequence title) {
			return addSubMenu(NONE, NONE, NONE, title);
		}

		/** AOSP: whether the items of the group can be chosen; the GTK menu shows every item enabled */
		@Override
		public void setGroupEnabled(int group, boolean enabled) {
			for (MenuItemImpl item : items) {
				if (item.tlGroupId == group)
					item.setEnabled(enabled);
			}
		}

		/** AOSP MenuBuilder.close: the menu's popup goes away */
		@Override
		public void close() {
			dismiss();
		}

		/** AOSP MenuBuilder.findItemWithShortcutForKey: the visible item whose shortcut is this key */
		MenuItemImpl tlItemForShortcut(int keyCode, KeyEvent event) {
			final boolean qwerty = tlQwertyMode;
			final char wanted = qwerty ? Character.toLowerCase((char) event.getUnicodeChar()) : (char) event.getNumber();
			for (MenuItemImpl item : items) {
				if (!item.isVisible())
					continue;
				final char shortcut = qwerty ? Character.toLowerCase(item.getAlphabeticShortcut()) : item.getNumericShortcut();
				if (shortcut != 0 && shortcut == wanted)
					return item;
				if (item.subMenu != null) {
					MenuItemImpl found = item.subMenu.tlItemForShortcut(keyCode, event);
					if (found != null)
						return found;
				}
			}
			return null;
		}

		/** AOSP: whether a key is the shortcut of one of the items */
		@Override
		public boolean isShortcutKey(int keyCode, KeyEvent event) {
			return tlItemForShortcut(keyCode, event) != null;
		}

		/** AOSP: the item whose shortcut the key is gets performed (FLAG_PERFORM_NO_CLOSE keeps the menu open) */
		@Override
		public boolean performShortcut(int keyCode, KeyEvent event, int flags) {
			final MenuItemImpl item = tlItemForShortcut(keyCode, event);
			boolean handled = false;
			if (item != null)
				handled = item.tlInvoke();
			if ((flags & FLAG_ALWAYS_PERFORM_CLOSE) != 0)
				close();
			return handled;
		}

		/** AOSP: the item with this id gets performed */
		@Override
		public boolean performIdentifierAction(int id, int flags) {
			final MenuItem item = findItem(id);
			final boolean handled = item instanceof MenuItemImpl && ((MenuItemImpl) item).tlInvoke();
			if ((flags & FLAG_PERFORM_NO_CLOSE) == 0)
				close();
			return handled;
		}

		/** AOSP: whether shortcuts are the alphabetic (qwerty keyboard) or the numeric ones */
		@Override
		public void setQwertyMode(boolean isQwerty) {
			tlQwertyMode = isQwerty;
		}
