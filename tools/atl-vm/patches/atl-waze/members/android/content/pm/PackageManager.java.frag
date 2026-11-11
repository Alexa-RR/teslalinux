	/* teslalinux: PackageManager additions (API 29-34) */
	public static final class Property {
		private final String name, packageName, className; private final Object value;
		public Property(String name, Object value, String packageName, String className) { this.name = name; this.value = value; this.packageName = packageName; this.className = className; }
		public String getName() { return name; }
		public String getPackageName() { return packageName; }
		public String getClassName() { return className; }
		public boolean isBoolean() { return value instanceof Boolean; }
		public boolean getBoolean() { return value instanceof Boolean && (Boolean) value; }
		public boolean isString() { return value instanceof String; }
		public String getString() { return value instanceof String ? (String) value : null; }
		public boolean isInteger() { return value instanceof Integer; }
		public int getInteger() { return value instanceof Integer ? (Integer) value : 0; }
		public boolean isFloat() { return value instanceof Float; }
		public float getFloat() { return value instanceof Float ? (Float) value : 0; }
		public boolean isResourceId() { return false; }
		public int getResourceId() { return 0; }
	}
	public static final class ComponentInfoFlags { public final long value; private ComponentInfoFlags(long v) { value = v; } public static ComponentInfoFlags of(long v) { return new ComponentInfoFlags(v); } public long getValue() { return value; } }
	public static final class ResolveInfoFlags { public final long value; private ResolveInfoFlags(long v) { value = v; } public static ResolveInfoFlags of(long v) { return new ResolveInfoFlags(v); } public long getValue() { return value; } }
	public static final class ApplicationInfoFlags { public final long value; private ApplicationInfoFlags(long v) { value = v; } public static ApplicationInfoFlags of(long v) { return new ApplicationInfoFlags(v); } public long getValue() { return value; } }
	public static final class PackageInfoFlags { public final long value; private PackageInfoFlags(long v) { value = v; } public static PackageInfoFlags of(long v) { return new PackageInfoFlags(v); } public long getValue() { return value; } }
	public Property getProperty(String propertyName, String packageName) throws NameNotFoundException { throw new NameNotFoundException(propertyName); }
	public Property getProperty(String propertyName, android.content.ComponentName component) throws NameNotFoundException { throw new NameNotFoundException(propertyName); }
	/** AOSP: whether the package is signed by the certificate (raw X.509 or its SHA-256), from the APK's signers. */
	public boolean hasSigningCertificate(String packageName, byte[] certificate, int type) { return tlHasCertificate(tlPackage(packageName), certificate, type); }
	public boolean hasSigningCertificate(int uid, byte[] certificate, int type) {
		String[] pkgs = getPackagesForUid(uid);
		return pkgs != null && hasSigningCertificate(pkgs[0], certificate, type);
	}
	public boolean isAutoRevokeWhitelisted() { return true; }
	public boolean isAutoRevokeWhitelisted(String packageName) { return true; }
	public boolean canRequestPackageInstalls() { return false; }
	public boolean isPackageSuspended() { return false; }
