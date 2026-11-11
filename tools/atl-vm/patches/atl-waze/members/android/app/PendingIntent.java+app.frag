	/* ==== teslalinux (app): AOSP PendingIntent behaviour ==== */
	public static final int FLAG_ONE_SHOT = 1 << 30;
	public static final int FLAG_NO_CREATE = 1 << 29;
	public static final int FLAG_CANCEL_CURRENT = 1 << 28;
	public static final int FLAG_UPDATE_CURRENT = 1 << 27;
	public static final int FLAG_IMMUTABLE = 1 << 26;
	public static final int FLAG_MUTABLE = 1 << 25;
	public static final int FLAG_ALLOW_UNSAFE_IMPLICIT_INTENT = 1 << 24;

	/* Intent.fillIn flags (AOSP Intent.FILL_IN_*) */
	private static final int FILL_IN_ACTION = 1 << 0;
	private static final int FILL_IN_DATA = 1 << 1;
	private static final int FILL_IN_CATEGORIES = 1 << 2;
	private static final int FILL_IN_COMPONENT = 1 << 3;
	private static final int FILL_IN_PACKAGE = 1 << 4;

	private boolean tlCanceled;
	private int tlFlags = -1;
	private Intent[] tlIntents;
	private TlIntentSender tlSender;

	/** AOSP: the IntentSender of this PendingIntent (the same target; sending it sends this). */
	public IntentSender getIntentSender() {
		synchronized (this) {
			if (tlSender == null)
				tlSender = new TlIntentSender(this);
			return tlSender;
		}
	}

	/** The IntentSender of a PendingIntent (AOSP wraps the same IIntentSender in both). */
	public static final class TlIntentSender extends IntentSender {
		final PendingIntent mPendingIntent;

		TlIntentSender(PendingIntent pendingIntent) {
			mPendingIntent = pendingIntent;
		}

		public String getCreatorPackage() {
			return mPendingIntent.getCreatorPackage();
		}

		@Override
		public boolean equals(Object o) {
			return o instanceof TlIntentSender && ((TlIntentSender) o).mPendingIntent.equals(mPendingIntent);
		}

		@Override
		public int hashCode() {
			return mPendingIntent.hashCode();
		}

		@Override
		public String toString() {
			return "IntentSender{" + Integer.toHexString(System.identityHashCode(this)) + ": " + mPendingIntent + "}";
		}
	}

	/**
	 * The intent this PendingIntent sends with a fill-in intent, after AOSP Intent.fillIn: the fill-in
	 * gives the fields the original leaves empty (component and package only when flagsMask/flagsValues
	 * ask for it), its extras are merged in, and FLAG_IMMUTABLE intents take nothing from it.
	 * null when the PendingIntent was canceled.
	 */
	Intent tlFillIn(Intent fillIn, int flagsMask, int flagsValues) {
		if (tlCanceled)
			return null;
		Intent target = new Intent(intent);
		if (fillIn != null && (tlFlags < 0 || (tlFlags & FLAG_IMMUTABLE) == 0)) {
			if (fillIn.getAction() != null && target.getAction() == null)
				target.setAction(fillIn.getAction());
			if ((fillIn.getData() != null || fillIn.getType() != null) && target.getData() == null && target.getType() == null)
				target.setDataAndType(fillIn.getData(), fillIn.getType());
			if (fillIn.getCategories() != null && target.getCategories() == null) {
				for (String c : fillIn.getCategories())
					target.addCategory(c);
			}
			if (fillIn.getComponent() != null && (flagsValues & FILL_IN_COMPONENT) != 0 && target.getComponent() == null)
				target.setComponent(fillIn.getComponent());
			if (fillIn.getPackage() != null && (flagsValues & FILL_IN_PACKAGE) != 0 && target.getPackage() == null && target.getComponent() == null)
				target.setPackage(fillIn.getPackage());
			if (fillIn.getExtras() != null)
				target.putExtras(fillIn.getExtras());
			target.addFlags(fillIn.getFlags());
		}
		if (flagsMask != 0)
			target.setFlags((target.getFlags() & ~flagsMask) | (flagsValues & flagsMask));
		return target;
	}

	/** AOSP: the package the intent goes to (the creator's, here the app's). */
	@Deprecated
	public String getTargetPackage() {
		return getCreatorPackage();
	}

	public android.os.UserHandle getCreatorUserHandle() {
		return new android.os.UserHandle(getCreatorUid() / android.os.UserHandle.PER_USER_RANGE);
	}

	public boolean isActivity() {
		return type == 0;
	}

	public boolean isService() {
		return type == 1;
	}

	public boolean isBroadcast() {
		return type == 2;
	}

	public boolean isForegroundService() {
		return type == 1 && tlForegroundService;
	}

	private boolean tlForegroundService;

	public boolean isImmutable() {
		return tlFlags >= 0 && (tlFlags & FLAG_IMMUTABLE) != 0;
	}

	/** AOSP: activities started from all the intents, the last one on top. */
	public static PendingIntent getActivities(Context context, int requestCode, Intent[] intents, int flags) {
		return getActivities(context, requestCode, intents, flags, null);
	}

	public static PendingIntent getActivity(Context context, int requestCode, Intent intent, int flags, Bundle options) {
		return getActivity(context, requestCode, intent, flags);
	}

	/** PendingIntent creation with AOSP's flag semantics: an equal PendingIntent (same kind, request code and filterEquals intent) is reused. */
	static PendingIntent tlObtain(PendingIntent created, int flags, Intent[] intents) {
		synchronized (tlActive) {
			PendingIntent existing = null;
			for (PendingIntent p : tlActive) {
				if (p.type == created.type && p.requestCode == created.requestCode && p.intent.filterEquals(created.intent)) {
					existing = p;
					break;
				}
			}
			if ((flags & FLAG_NO_CREATE) != 0)
				return existing;
			if (existing != null && (flags & FLAG_CANCEL_CURRENT) != 0) {
				existing.cancel();
				existing = null;
			}
			if (existing != null) {
				if ((flags & FLAG_UPDATE_CURRENT) != 0 && created.intent.getExtras() != null) {
					existing.intent.replaceExtras(created.intent.getExtras());
					if (intents != null)
						existing.tlIntents = intents;
				}
				return existing;
			}
			created.tlFlags = flags;
			created.tlIntents = intents;
			tlActive.add(created);
			return created;
		}
	}

	private static final java.util.ArrayList<PendingIntent> tlActive = new java.util.ArrayList<>();

	/** AOSP: the PendingIntent stops working (send throws CanceledException) and a later get* creates a new one. */
	public void cancel() {
		tlCanceled = true;
		synchronized (tlActive) {
			tlActive.remove(this);
		}
	}

	@Override
	public boolean equals(Object o) {
		if (this == o)
			return true;
		if (!(o instanceof PendingIntent))
			return false;
		PendingIntent other = (PendingIntent) o;
		return other.type == type && other.requestCode == requestCode && other.intent.filterEquals(intent);
	}

	@Override
	public int hashCode() {
		return (type * 31 + requestCode) * 31 + intent.filterHashCode();
	}
