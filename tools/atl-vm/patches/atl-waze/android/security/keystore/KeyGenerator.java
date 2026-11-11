package android.security.keystore;

import java.io.IOException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidParameterException;
import java.security.ProviderException;
import java.security.SecureRandom;
import java.security.spec.AlgorithmParameterSpec;
import javax.crypto.KeyGeneratorSpi;
import javax.crypto.SecretKey;

/* teslalinux: KeyGenerator for the AndroidKeyStore provider. Generates random key material of the
 * requested size and stores it under the alias from KeyGenParameterSpec, returning a non-exportable
 * AndroidKeyStoreSecretKey, exactly like Android's keystore-backed generators. */
public abstract class KeyGenerator extends KeyGeneratorSpi {
	private final String algorithm;
	private final int defaultKeySizeBits;
	protected KeyGenParameterSpec spec;
	protected SecureRandom random;

	KeyGenerator(String algorithm, int defaultKeySizeBits) { this.algorithm = algorithm; this.defaultKeySizeBits = defaultKeySizeBits; }

	public static final class AES extends KeyGenerator { public AES() { super("AES", 256); } }
	public static final class HmacSHA256 extends KeyGenerator { public HmacSHA256() { super("HmacSHA256", 256); } }
	public static final class HmacSHA512 extends KeyGenerator { public HmacSHA512() { super("HmacSHA512", 512); } }

	@Override
	protected void engineInit(AlgorithmParameterSpec params, SecureRandom random) throws InvalidAlgorithmParameterException {
		if (!(params instanceof KeyGenParameterSpec))
			throw new InvalidAlgorithmParameterException("Cannot initialize without a " + KeyGenParameterSpec.class.getName() + " parameter");
		this.spec = (KeyGenParameterSpec) params;
		this.random = random;
		if ("AES".equals(algorithm) && spec.getKeySize() > 0 && spec.getKeySize() != 128 && spec.getKeySize() != 192 && spec.getKeySize() != 256)
			throw new InvalidAlgorithmParameterException("Unsupported AES key size: " + spec.getKeySize());
	}

	@Override
	protected void engineInit(SecureRandom random) {
		throw new InvalidParameterException("Cannot initialize without a " + KeyGenParameterSpec.class.getName() + " parameter");
	}

	@Override
	protected void engineInit(int keysize, SecureRandom random) {
		throw new InvalidParameterException("Cannot initialize without a " + KeyGenParameterSpec.class.getName() + " parameter");
	}

	@Override
	protected SecretKey engineGenerateKey() {
		if (spec == null) throw new IllegalStateException("Not initialized");
		int bits = spec.getKeySize() > 0 ? spec.getKeySize() : defaultKeySizeBits;
		byte[] material = new byte[bits / 8];
		(random != null ? random : new SecureRandom()).nextBytes(material);
		try {
			return KeyStoreStorage.put(spec.getKeystoreAlias(), algorithm, material);
		} catch (IOException e) {
			throw new ProviderException("Failed to store generated key", e);
		}
	}
}
