package android.security.keystore;

import java.security.AlgorithmParameters;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.AlgorithmParameterSpec;
import java.security.spec.InvalidParameterSpecException;
import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.CipherSpi;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.ShortBufferException;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/* teslalinux: Cipher implementations for keystore keys. The application never sees the key; we
 * unwrap it here and drive the platform's AES implementation. For GCM, encryption without explicit
 * parameters generates the 12-byte IV the keystore is responsible for (Tink/EncryptedSharedPreferences
 * depend on exactly that behavior). */
public abstract class AndroidKeyStoreCipherSpi extends CipherSpi {
	public static final class AesGcmNoPadding extends AndroidKeyStoreCipherSpi { public AesGcmNoPadding() { super("AES/GCM/NoPadding", true, 12); } }
	public static final class AesCbcNoPadding extends AndroidKeyStoreCipherSpi { public AesCbcNoPadding() { super("AES/CBC/NoPadding", false, 16); } }
	public static final class AesCbcPkcs7Padding extends AndroidKeyStoreCipherSpi { public AesCbcPkcs7Padding() { super("AES/CBC/PKCS7Padding", false, 16); } }
	public static final class AesCtrNoPadding extends AndroidKeyStoreCipherSpi { public AesCtrNoPadding() { super("AES/CTR/NoPadding", false, 16); } }

	private final String transformation;
	private final boolean gcm;
	private final int ivSize;
	private Cipher delegate;
	private byte[] iv;
	private int tagLenBits = 128;

	AndroidKeyStoreCipherSpi(String transformation, boolean gcm, int ivSize) { this.transformation = transformation; this.gcm = gcm; this.ivSize = ivSize; }

	@Override protected void engineSetMode(String mode) throws NoSuchAlgorithmException { if (!transformation.contains("/" + mode.toUpperCase() + "/")) throw new NoSuchAlgorithmException("Unsupported mode " + mode); }
	@Override protected void engineSetPadding(String padding) throws NoSuchPaddingException { if (!transformation.toUpperCase().endsWith("/" + padding.toUpperCase())) throw new NoSuchPaddingException("Unsupported padding " + padding); }
	@Override protected int engineGetBlockSize() { return 16; }
	@Override protected int engineGetOutputSize(int inputLen) { return delegate != null ? delegate.getOutputSize(inputLen) : inputLen + 16 + 16; }
	@Override protected byte[] engineGetIV() { return iv == null ? null : iv.clone(); }

	@Override
	protected AlgorithmParameters engineGetParameters() {
		if (iv == null) return null;
		try {
			AlgorithmParameters p = AlgorithmParameters.getInstance(gcm ? "GCM" : "AES");
			p.init(gcm ? new GCMParameterSpec(tagLenBits, iv) : new IvParameterSpec(iv));
			return p;
		} catch (NoSuchAlgorithmException | InvalidParameterSpecException e) {
			return null;
		}
	}

	private static byte[] material(Key key) throws InvalidKeyException {
		if (!(key instanceof AndroidKeyStoreSecretKey)) throw new InvalidKeyException("Unsupported key: " + (key == null ? "null" : key.getClass().getName()));
		return ((AndroidKeyStoreSecretKey) key).material;
	}

	@Override
	protected void engineInit(int opmode, Key key, SecureRandom random) throws InvalidKeyException {
		byte[] raw = material(key);
		if (opmode == Cipher.DECRYPT_MODE || opmode == Cipher.UNWRAP_MODE)
			throw new InvalidKeyException(new InvalidAlgorithmParameterException("IV required when decrypting. Use IvParameterSpec or AlgorithmParameters to provide it."));
		iv = new byte[ivSize];
		(random != null ? random : new SecureRandom()).nextBytes(iv);
		initDelegate(opmode, raw);
	}

	@Override
	protected void engineInit(int opmode, Key key, AlgorithmParameterSpec params, SecureRandom random) throws InvalidKeyException, InvalidAlgorithmParameterException {
		byte[] raw = material(key);
		if (params == null) { engineInit(opmode, key, random); return; }
		if (gcm) {
			if (!(params instanceof GCMParameterSpec)) throw new InvalidAlgorithmParameterException("Only GCMParameterSpec supported");
			GCMParameterSpec g = (GCMParameterSpec) params;
			iv = g.getIV(); tagLenBits = g.getTLen();
		} else {
			if (!(params instanceof IvParameterSpec)) throw new InvalidAlgorithmParameterException("Only IvParameterSpec supported");
			iv = ((IvParameterSpec) params).getIV();
		}
		initDelegate(opmode, raw);
	}

	@Override
	protected void engineInit(int opmode, Key key, AlgorithmParameters params, SecureRandom random) throws InvalidKeyException, InvalidAlgorithmParameterException {
		if (params == null) { engineInit(opmode, key, random); return; }
		try {
			AlgorithmParameterSpec spec = gcm ? params.getParameterSpec(GCMParameterSpec.class) : params.getParameterSpec(IvParameterSpec.class);
			engineInit(opmode, key, spec, random);
		} catch (InvalidParameterSpecException e) {
			throw new InvalidAlgorithmParameterException(e);
		}
	}

	private void initDelegate(int opmode, byte[] raw) throws InvalidKeyException {
		try {
			delegate = Cipher.getInstance(transformation);
			delegate.init(opmode, new SecretKeySpec(raw, "AES"), gcm ? new GCMParameterSpec(tagLenBits, iv) : new IvParameterSpec(iv));
		} catch (NoSuchAlgorithmException | NoSuchPaddingException | InvalidAlgorithmParameterException e) {
			throw new InvalidKeyException("Cannot initialize " + transformation, e);
		}
	}

	@Override protected void engineUpdateAAD(byte[] src, int offset, int len) { delegate.updateAAD(src, offset, len); }
	@Override protected void engineUpdateAAD(java.nio.ByteBuffer src) { delegate.updateAAD(src); }
	@Override protected byte[] engineUpdate(byte[] input, int inputOffset, int inputLen) { return delegate.update(input, inputOffset, inputLen); }
	@Override protected int engineUpdate(byte[] input, int inputOffset, int inputLen, byte[] output, int outputOffset) throws ShortBufferException { return delegate.update(input, inputOffset, inputLen, output, outputOffset); }
	@Override protected byte[] engineDoFinal(byte[] input, int inputOffset, int inputLen) throws IllegalBlockSizeException, BadPaddingException {
		return input == null ? delegate.doFinal() : delegate.doFinal(input, inputOffset, inputLen);
	}
	@Override protected int engineDoFinal(byte[] input, int inputOffset, int inputLen, byte[] output, int outputOffset) throws ShortBufferException, IllegalBlockSizeException, BadPaddingException {
		return delegate.doFinal(input, inputOffset, inputLen, output, outputOffset);
	}
}
