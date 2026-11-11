package android.security.keystore;

import javax.crypto.SecretKey;

/* teslalinux: a symmetric key that lives in the keystore. Like on Android, the material is never
 * handed to the application: getEncoded()/getFormat() return null, so only the keystore's own
 * Cipher/Mac implementations (which read it package-privately) can use it. */
public final class AndroidKeyStoreSecretKey implements SecretKey {
	private final String alias;
	private final String algorithm;
	final byte[] material;

	AndroidKeyStoreSecretKey(String alias, String algorithm, byte[] material) {
		this.alias = alias;
		this.algorithm = algorithm;
		this.material = material;
	}

	public String getAlias() { return alias; }
	@Override public String getAlgorithm() { return algorithm; }
	@Override public String getFormat() { return null; }
	@Override public byte[] getEncoded() { return null; }
	@Override public int hashCode() { return alias.hashCode(); }
	@Override public boolean equals(Object o) { return o instanceof AndroidKeyStoreSecretKey && ((AndroidKeyStoreSecretKey) o).alias.equals(alias); }
	@Override public String toString() { return "AndroidKeyStoreSecretKey{" + alias + ", " + algorithm + "}"; }
}
