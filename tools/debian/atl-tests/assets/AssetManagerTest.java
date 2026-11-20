package org.teslalinux.assettest;

import android.content.res.AssetManager;
import android.content.res.Resources;
import android.util.DisplayMetrics;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.InputStream;
import java.lang.reflect.Field;

/**
 * ATL runtime test of AssetManager's lifetime (AOSP's reference counting, ATL's native destroy):
 *   1. an asset stream holds a reference to its manager: closing it leaves the manager working, and
 *      a manager closed while a stream is open is freed when the stream closes;
 *   2. a manager closed while a theme is open is freed once the theme is gone (finalized);
 *   3. a manager made with tlNewSharing keeps resolving resources after the manager whose APKs it
 *      shares is freed, and so does one made from it;
 *   4. managers created and dropped round after round (closed, or only unreachable) do not make the
 *      process grow: their native managers and APKs are freed.
 * Runs on dalvikvm with the installed ATL and framework-res.apk ($ATL_TEST_APK_PATH); run.sh builds
 * and starts it. Prints PASS/FAIL per check and "RESULT passed/total".
 */
public class AssetManagerTest {
	static int passed = 0, total = 0;

	static void check(String name, boolean ok, String detail) {
		total++;
		if (ok)
			passed++;
		System.out.println((ok ? "PASS " : "FAIL ") + name + (detail != null ? " (" + detail + ")" : ""));
	}

	static long nativeOf(AssetManager am) throws Exception {
		Field f = AssetManager.class.getDeclaredField("mObject");
		f.setAccessible(true);
		return f.getLong(am);
	}

	static Resources resources(AssetManager am) {
		return new Resources(am, new DisplayMetrics(), null);
	}

	static String okString(Resources r) {
		int id = r.getIdentifier("ok", "string", "android");
		return id == 0 ? null : r.getString(id);
	}

	static void readSome(InputStream in) throws Exception {
		byte[] buf = new byte[4096];
		in.read(buf);
	}

	static void collect() {
		for (int i = 0; i < 4; i++) {
			System.gc();
			System.runFinalization();
		}
	}

	static long rssKb() throws Exception {
		try (BufferedReader r = new BufferedReader(new FileReader("/proc/self/status"))) {
			String line;
			while ((line = r.readLine()) != null)
				if (line.startsWith("VmRSS:"))
					return Long.parseLong(line.replaceAll("[^0-9]", ""));
		}
		return -1;
	}

	/* a theme nobody keeps: it holds a reference to the manager until it is finalized */
	static void themeAndForget(Resources r) {
		r.newTheme();
	}

	public static void main(String[] args) throws Exception {
		/* api-impl's natives; android-translation-layer has them linked in */
		System.loadLibrary("translation_layer_main");
		String apk = System.getenv("ATL_TEST_APK_PATH");

		// 1. streams
		AssetManager a = new AssetManager(apk);
		Resources ra = resources(a);
		String ok = okString(ra);
		check("a framework string resolves", ok != null && !ok.isEmpty(), ok);
		InputStream in = a.openNonAsset(0, "AndroidManifest.xml", AssetManager.ACCESS_STREAMING);
		readSome(in);
		in.close();
		check("closing an asset stream leaves its manager working", nativeOf(a) != 0 && ok.equals(okString(ra)), null);
		InputStream open = a.openNonAsset(0, "AndroidManifest.xml", AssetManager.ACCESS_STREAMING);
		a.close();
		check("a manager closed with a stream open stays until the stream closes", nativeOf(a) != 0, null);
		readSome(open);
		open.close();
		check("... and is freed when it closes", nativeOf(a) == 0, null);

		// 2. themes
		AssetManager b = new AssetManager(apk);
		themeAndForget(resources(b));
		b.close();
		long deadline = System.currentTimeMillis() + 10000;
		while (nativeOf(b) != 0 && System.currentTimeMillis() < deadline)
			collect();
		check("a manager closed with a theme open is freed once the theme is finalized", nativeOf(b) == 0, null);

		// 3. sharing
		AssetManager c = new AssetManager(apk);
		AssetManager d = c.tlNewSharing();
		Resources rd = resources(d);
		c.close();
		check("the source manager is freed", nativeOf(c) == 0, null);
		check("a sharing manager resolves after its source is freed", ok.equals(okString(rd)), okString(rd));
		AssetManager e = d.tlNewSharing();
		check("a manager shared from it resolves too", ok.equals(okString(resources(e))), null);
		d.close();
		check("... and after the one it was made from is freed", ok.equals(okString(resources(e))), null);
		e.close();
		check("the last sharing manager is freed", nativeOf(d) == 0 && nativeOf(e) == 0, null);

		// 4. memory: rounds of managers, half closed, half only dropped (finalized)
		collect();
		long base = rssKb();
		long[] after = new long[4];
		for (int round = 0; round < after.length; round++) {
			for (int i = 0; i < 150; i++) {
				AssetManager m = new AssetManager(apk);
				Resources r = resources(m);
				okString(r);
				AssetManager s = m.tlNewSharing();
				okString(resources(s));
				if (i % 2 == 0) {
					s.close();
					m.close();
				}
			}
			collect();
			after[round] = rssKb();
		}
		StringBuilder sb = new StringBuilder("RSS kB: start " + base);
		for (int round = 0; round < after.length; round++)
			sb.append(", round ").append(round + 1).append(' ').append(after[round]);
		System.out.println(sb);
		/* the first rounds settle the allocator and the caches; a leak grows every round by the same
		 * amount (300 managers and 150 loads of framework-res.apk each) */
		long growth = after[3] - after[1];
		check("300 managers a round do not grow the process (rounds 2 to 4)", growth < 8 * 1024, growth + " kB");

		System.out.println("RESULT " + passed + "/" + total);
		System.exit(passed == total ? 0 : 1);
	}
}
