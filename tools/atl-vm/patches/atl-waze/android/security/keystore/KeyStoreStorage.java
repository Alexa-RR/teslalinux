package android.security.keystore;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/* teslalinux: persistent, software-backed keystore storage (what Android does on devices without a
 * secure element). One file per alias under <app data dir>/keystore, owner-only permissions.
 * Format: line 1 algorithm, line 2 base64 key material. */
final class KeyStoreStorage {
	private static final Map<String, AndroidKeyStoreSecretKey> cache = new HashMap<>();
	private static File dir;
	private static boolean loaded;

	private KeyStoreStorage() {}

	private static synchronized File dir() {
		if (dir == null) {
			String base = System.getenv("ANDROID_APP_DATA_DIR");
			if (base == null || base.isEmpty()) base = System.getProperty("user.home") + "/.local/share/android_translation_layer";
			dir = new File(base, "keystore");
			dir.mkdirs();
			try { dir.setReadable(false, false); dir.setReadable(true, true); dir.setExecutable(false, false); dir.setExecutable(true, true); dir.setWritable(false, false); dir.setWritable(true, true); } catch (SecurityException ignored) {}
		}
		return dir;
	}

	private static synchronized void loadAll() {
		if (loaded) return;
		loaded = true;
		File[] files = dir().listFiles();
		if (files == null) return;
		for (File f : files) {
			try {
				List<String> lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
				if (lines.size() >= 2)
					cache.put(f.getName(), new AndroidKeyStoreSecretKey(f.getName(), lines.get(0).trim(), Base64.getDecoder().decode(lines.get(1).trim())));
			} catch (IOException | IllegalArgumentException e) {
				android.util.Log.w("AndroidKeyStore", "ignoring unreadable key file " + f + ": " + e);
			}
		}
	}

	static synchronized AndroidKeyStoreSecretKey get(String alias) { loadAll(); return cache.get(alias); }
	static synchronized boolean contains(String alias) { loadAll(); return cache.containsKey(alias); }
	static synchronized Set<String> aliases() { loadAll(); return new java.util.HashSet<>(cache.keySet()); }
	static synchronized int size() { loadAll(); return cache.size(); }

	static synchronized Date creationDate(String alias) {
		File f = new File(dir(), alias);
		return f.exists() ? new Date(f.lastModified()) : null;
	}

	static synchronized AndroidKeyStoreSecretKey put(String alias, String algorithm, byte[] material) throws IOException {
		loadAll();
		AndroidKeyStoreSecretKey k = new AndroidKeyStoreSecretKey(alias, algorithm, material);
		File f = new File(dir(), alias);
		String body = algorithm + "\n" + Base64.getEncoder().encodeToString(material) + "\n";
		Files.write(f.toPath(), body.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
		try { f.setReadable(false, false); f.setReadable(true, true); f.setWritable(false, false); f.setWritable(true, true); } catch (SecurityException ignored) {}
		cache.put(alias, k);
		return k;
	}

	static synchronized void delete(String alias) {
		loadAll();
		cache.remove(alias);
		new File(dir(), alias).delete();
	}
}
