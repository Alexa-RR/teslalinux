package android.security.keystore;

import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.NoSuchAlgorithmException;
import java.security.spec.AlgorithmParameterSpec;
import javax.crypto.Mac;
import javax.crypto.MacSpi;
import javax.crypto.spec.SecretKeySpec;

/* teslalinux: HMAC over keystore keys, same unwrap-and-delegate scheme as the cipher. */
public abstract class AndroidKeyStoreMacSpi extends MacSpi {
	public static final class HmacSHA256 extends AndroidKeyStoreMacSpi { public HmacSHA256() { super("HmacSHA256"); } }
	public static final class HmacSHA512 extends AndroidKeyStoreMacSpi { public HmacSHA512() { super("HmacSHA512"); } }

	private final String algorithm;
	private Mac delegate;
	AndroidKeyStoreMacSpi(String algorithm) { this.algorithm = algorithm; }

	@Override protected int engineGetMacLength() { return delegate != null ? delegate.getMacLength() : (algorithm.endsWith("512") ? 64 : 32); }

	@Override
	protected void engineInit(Key key, AlgorithmParameterSpec params) throws InvalidKeyException, InvalidAlgorithmParameterException {
		if (!(key instanceof AndroidKeyStoreSecretKey)) throw new InvalidKeyException("Unsupported key: " + (key == null ? "null" : key.getClass().getName()));
		try {
			delegate = Mac.getInstance(algorithm);
			delegate.init(new SecretKeySpec(((AndroidKeyStoreSecretKey) key).material, algorithm));
		} catch (NoSuchAlgorithmException e) {
			throw new InvalidKeyException(e);
		}
	}

	@Override protected void engineUpdate(byte input) { delegate.update(input); }
	@Override protected void engineUpdate(byte[] input, int offset, int len) { delegate.update(input, offset, len); }
	@Override protected byte[] engineDoFinal() { return delegate.doFinal(); }
	@Override protected void engineReset() { if (delegate != null) delegate.reset(); }
}
