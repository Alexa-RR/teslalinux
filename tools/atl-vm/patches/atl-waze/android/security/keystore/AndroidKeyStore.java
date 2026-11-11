package android.security.keystore;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.Key;
import java.security.KeyStoreException;
import java.security.KeyStoreSpi;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import javax.crypto.SecretKey;

/* teslalinux: the "AndroidKeyStore" KeyStoreSpi over KeyStoreStorage. Symmetric keys are fully
 * supported (generation, import, lookup, deletion); asymmetric keys and certificates are not stored
 * yet and report absent, the same answers a key-less alias gives on Android. */
public class AndroidKeyStore extends KeyStoreSpi {
	@Override
	public Key engineGetKey(String alias, char[] password) throws NoSuchAlgorithmException, UnrecoverableKeyException {
		return KeyStoreStorage.get(alias);
	}

	@Override public Certificate[] engineGetCertificateChain(String alias) { return null; }
	@Override public Certificate engineGetCertificate(String alias) { return null; }
	@Override public Date engineGetCreationDate(String alias) { return KeyStoreStorage.creationDate(alias); }

	@Override
	public void engineSetKeyEntry(String alias, Key key, char[] password, Certificate[] chain) throws KeyStoreException {
		if (key instanceof AndroidKeyStoreSecretKey) {
			AndroidKeyStoreSecretKey k = (AndroidKeyStoreSecretKey) key;
			try { KeyStoreStorage.put(alias, k.getAlgorithm(), k.material); } catch (IOException e) { throw new KeyStoreException(e); }
			return;
		}
		if (key instanceof SecretKey && key.getEncoded() != null) {
			try { KeyStoreStorage.put(alias, key.getAlgorithm(), key.getEncoded()); } catch (IOException e) { throw new KeyStoreException(e); }
			return;
		}
		throw new KeyStoreException("Only secret keys can be stored in this keystore: " + (key == null ? "null" : key.getClass().getName()));
	}

	@Override
	public void engineSetKeyEntry(String alias, byte[] key, Certificate[] chain) throws KeyStoreException {
		throw new KeyStoreException("Protected (encoded) key entries are not supported by AndroidKeyStore");
	}

	@Override
	public void engineSetCertificateEntry(String alias, Certificate cert) throws KeyStoreException {
		throw new KeyStoreException("Certificate entries are not supported by this keystore yet");
	}

	@Override public void engineDeleteEntry(String alias) throws KeyStoreException { KeyStoreStorage.delete(alias); }
	@Override public Enumeration<String> engineAliases() { return Collections.enumeration(KeyStoreStorage.aliases()); }
	@Override public boolean engineContainsAlias(String alias) { return KeyStoreStorage.contains(alias); }
	@Override public int engineSize() { return KeyStoreStorage.size(); }
	@Override public boolean engineIsKeyEntry(String alias) { return KeyStoreStorage.contains(alias); }
	@Override public boolean engineIsCertificateEntry(String alias) { return false; }
	@Override public String engineGetCertificateAlias(Certificate cert) { return null; }

	@Override
	public void engineStore(OutputStream stream, char[] password) throws IOException, NoSuchAlgorithmException, CertificateException {
		/* entries are persisted as they are created; KeyStore.store(null, null) is a no-op on Android too */
	}

	@Override
	public void engineLoad(InputStream stream, char[] password) throws IOException, NoSuchAlgorithmException, CertificateException {
		/* KeyStore.load(null) on Android: nothing to read, entries come from the keystore daemon (here: KeyStoreStorage) */
	}
}
