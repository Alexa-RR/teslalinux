	/** AOSP: PackageInfo flag, fill in PackageInfo.signingInfo. */
	public static final int GET_SIGNING_CERTIFICATES = 0x08000000;
	/** AOSP: hasSigningCertificate input is the raw X.509 certificate. */
	public static final int CERT_INPUT_RAW_X509 = 0;
	/** AOSP: hasSigningCertificate input is the SHA-256 digest of the certificate. */
	public static final int CERT_INPUT_SHA256 = 1;

	/** the parsed package of packageName when it is the app ATL runs, else null */
	private static PackageParser.Package tlPackage(String packageName) {
		PackageParser.Package pkg = ATLLoadedApp.getPrimaryApplication().pkg;
		return pkg.packageName.equals(packageName) ? pkg : null;
	}

	/** every feature name the device could report: the FEATURE_ constants and the extra names hasSystemFeature knows */
	private static java.util.List<String> tlKnownFeatures() {
		java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
		for (java.lang.reflect.Field f : PackageManager.class.getFields()) {
			if (f.getName().startsWith("FEATURE_") && f.getType() == String.class
			    && java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
				try {
					names.add((String) f.get(null));
				} catch (IllegalAccessException e) {
					// public constants are always readable
				}
			}
		}
		java.util.Collections.addAll(names, "android.hardware.touchscreen", "android.hardware.touchscreen.multitouch",
		                             "android.hardware.touchscreen.multitouch.distinct", "android.hardware.touchscreen.multitouch.jazzhand",
		                             "android.hardware.faketouch", "android.hardware.location", "android.hardware.location.gps",
		                             "android.hardware.location.network", "android.hardware.wifi", "android.hardware.screen.landscape",
		                             "android.software.app_widgets", "android.software.webview", "android.hardware.opengles.aep",
		                             "android.hardware.vulkan.level", "android.hardware.vulkan.version");
		return new java.util.ArrayList<>(names);
	}

	/** AOSP: IllegalArgumentException for a component the package does not declare. */
	private static void tlCheckComponent(ComponentName c) {
		PackageParser.Package pkg = tlPackage(c.getPackageName());
		if (pkg != null) {
			String cls = c.getClassName();
			for (PackageParser.Activity a : pkg.activities)
				if (a.className.equals(cls)) return;
			for (PackageParser.Activity a : pkg.receivers)
				if (a.className.equals(cls)) return;
			for (PackageParser.Service s : pkg.services)
				if (s.className.equals(cls)) return;
			for (PackageParser.Provider p : pkg.providers)
				if (p.className.equals(cls)) return;
		}
		throw new IllegalArgumentException("Unknown component: " + c);
	}

	/*
	 * Component enabled-state overrides. AOSP keeps them in the package manager's per-user
	 * package-restrictions file; ATL keeps them in the app's data directory
	 * (atl_component_states.properties), so they survive restarts the same way.
	 */
	private static java.util.Properties tlComponentStates;

	private static java.io.File tlComponentStateFile() {
		String dataDir = ATLLoadedApp.getPrimaryApplication().pkg.applicationInfo.dataDir;
		if (dataDir == null)
			dataDir = android.os.Environment.getDataDirectory().getPath();
		return new java.io.File(dataDir, "atl_component_states.properties");
	}

	private static synchronized java.util.Properties tlStates() {
		if (tlComponentStates == null) {
			tlComponentStates = new java.util.Properties();
			java.io.File f = tlComponentStateFile();
			if (f.exists()) {
				try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
					tlComponentStates.load(in);
				} catch (java.io.IOException e) {
					android.util.Slog.w(TAG, "cannot read " + f + ": " + e);
				}
			}
		}
		return tlComponentStates;
	}

	private static synchronized int tlGetComponentState(ComponentName c) {
		String v = tlStates().getProperty(c.flattenToString());
		try {
			return v != null ? Integer.parseInt(v) : COMPONENT_ENABLED_STATE_DEFAULT;
		} catch (NumberFormatException e) {
			return COMPONENT_ENABLED_STATE_DEFAULT;
		}
	}

	private static synchronized void tlSetComponentState(ComponentName c, int state) {
		java.util.Properties p = tlStates();
		if (state == COMPONENT_ENABLED_STATE_DEFAULT)
			p.remove(c.flattenToString());
		else
			p.setProperty(c.flattenToString(), Integer.toString(state));
		java.io.File f = tlComponentStateFile();
		java.io.File tmp = new java.io.File(f.getPath() + ".new");
		try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) {
			p.store(out, "component enabled states (PackageManager.setComponentEnabledSetting)");
			out.getFD().sync();
		} catch (java.io.IOException e) {
			android.util.Slog.w(TAG, "cannot write " + f + ": " + e);
			return;
		}
		if (!tmp.renameTo(f))
			android.util.Slog.w(TAG, "cannot replace " + f);
	}

	/** the signatures (and past signing certificates) of a package for hasSigningCertificate */
	private static boolean tlHasCertificate(PackageParser.Package pkg, byte[] certificate, int type) {
		if (pkg == null || pkg.mSignatures == null || certificate == null)
			return false;
		for (Signature s : pkg.mSignatures) {
			byte[] raw = s.toByteArray();
			if (type == CERT_INPUT_RAW_X509) {
				if (java.util.Arrays.equals(raw, certificate))
					return true;
			} else if (type == CERT_INPUT_SHA256) {
				try {
					byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(raw);
					if (java.util.Arrays.equals(digest, certificate))
						return true;
				} catch (java.security.NoSuchAlgorithmException e) {
					return false;
				}
			}
		}
		return false;
	}
