	/**
	 * AOSP (API 30): executes a SELECT or PRAGMA on every connection of the pool now and on every
	 * connection opened later (for example a PRAGMA that is per connection).
	 */
	public void execPerConnectionSQL(String sql, Object[] bindArgs) throws android.database.SQLException {
		java.util.Objects.requireNonNull(sql);
		// copy so that later changes by the caller do not reach future connections
		if (bindArgs != null) {
			bindArgs = bindArgs.clone();
			for (int i = 0; i < bindArgs.length; i++) {
				if (bindArgs[i] instanceof byte[])
					bindArgs[i] = ((byte[]) bindArgs[i]).clone();
			}
		}
		synchronized (mLock) {
			throwIfNotOpenLocked();
			final int index = mConfigurationLocked.perConnectionSql.size();
			mConfigurationLocked.perConnectionSql.add(android.util.Pair.create(sql, bindArgs));
			try {
				mConnectionPoolLocked.reconfigure(mConfigurationLocked);
			} catch (RuntimeException ex) {
				mConfigurationLocked.perConnectionSql.remove(index);
				throw ex;
			}
		}
	}
