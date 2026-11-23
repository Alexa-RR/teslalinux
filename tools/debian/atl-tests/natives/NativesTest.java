package org.teslalinux.nativestest;

import android.content.res.AssetManager;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteDebug;
import android.database.sqlite.SQLiteStatement;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.SystemClock;
import android.os.Trace;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.util.DisplayMetrics;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import org.xmlpull.v1.XmlPullParser;

/**
 * ATL runtime test of the Java natives that had no C implementation (a call threw
 * UnsatisfiedLinkError) and of the placeholders next to them:
 *   1. compiled XML (XmlBlock): text, element and attribute namespaces, the id attribute, a block
 *      at an offset, its StringBlock;
 *   2. StringBlock: strings and style spans (StyleSpan for <b>) from a pool's bytes;
 *   3. AssetManager: addAssetPath's cookies, isUpToDate, getSystem, the global asset counts, the
 *      string array info, Theme.dump;
 *   4. KeyCharacterMap over AOSP's Virtual.kcm: characters by meta state, labels, numbers,
 *      matches, fallbacks, key events for a string, the keyboard type; KeyEvent.isSystemKey;
 *   5. Process: names to ids, memory, /proc readers and parsers, pids, Pss, thread group and
 *      scheduler, argv[0], signals;
 *   6. SystemClock's microsecond clocks; Trace sections;
 *   7. SQLite: a blob as a read-only file descriptor, the pager statistics;
 *   8. BitmapFactory: undecodable bytes give null, a seekable descriptor keeps its position.
 * Runs on dalvikvm with the installed ATL; run.sh builds and starts it. Prints PASS/FAIL per check
 * and "RESULT passed/total".
 */
public class NativesTest {
	static int passed = 0, total = 0;

	static void check(String name, boolean ok, String detail) {
		total++;
		if (ok)
			passed++;
		System.out.println((ok ? "PASS " : "FAIL ") + name + (detail != null ? " (" + detail + ")" : ""));
	}

	interface Body {
		void run() throws Throwable;
	}

	/* a group of checks; an exception (UnsatisfiedLinkError before the natives existed) fails it */
	static void group(String name, Body body) {
		try {
			body.run();
		} catch (Throwable t) {
			Throwable cause = t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null ? t.getCause() : t;
			check(name + ": no exception", false, cause.toString());
			cause.printStackTrace(System.out);
		}
	}

	/* ---- a compiled XML and a string pool, written as aapt writes them ---- */

	static byte[] stringPool(String[] strings, int[][][] styles) {
		ByteBuffer data = ByteBuffer.allocate(65536).order(ByteOrder.LITTLE_ENDIAN);
		int[] offsets = new int[strings.length];
		for (int i = 0; i < strings.length; i++) {
			offsets[i] = data.position();
			data.putShort((short)strings[i].length());
			for (char c : strings[i].toCharArray())
				data.putChar(c);
			data.putShort((short)0);
		}
		while (data.position() % 4 != 0)
			data.put((byte)0);
		int stringsLen = data.position();
		int styleCount = styles != null ? styles.length : 0;
		int[] styleOffsets = new int[styleCount];
		ByteBuffer st = ByteBuffer.allocate(65536).order(ByteOrder.LITTLE_ENDIAN);
		for (int i = 0; i < styleCount; i++) {
			styleOffsets[i] = st.position();
			for (int[] span : styles[i]) {
				st.putInt(span[0]);
				st.putInt(span[1]);
				st.putInt(span[2]);
			}
			st.putInt(0xFFFFFFFF);
		}
		if (styleCount > 0) {
			st.putInt(0xFFFFFFFF);
			st.putInt(0xFFFFFFFF);
			st.putInt(0xFFFFFFFF);
		}
		int headerSize = 28;
		int stringsStart = headerSize + 4 * strings.length + 4 * styleCount;
		int stylesStart = styleCount > 0 ? stringsStart + stringsLen : 0;
		int size = stringsStart + stringsLen + st.position();
		ByteBuffer out = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
		out.putShort((short)0x0001).putShort((short)headerSize).putInt(size);
		out.putInt(strings.length).putInt(styleCount).putInt(0).putInt(stringsStart).putInt(stylesStart);
		for (int o : offsets)
			out.putInt(o);
		for (int o : styleOffsets)
			out.putInt(o);
		out.put(data.array(), 0, stringsLen);
		out.put(st.array(), 0, st.position());
		return out.array();
	}

	static void node(ByteArrayOutputStream out, int type, int line, byte[] ext) {
		ByteBuffer b = ByteBuffer.allocate(16 + ext.length).order(ByteOrder.LITTLE_ENDIAN);
		b.putShort((short)type).putShort((short)16).putInt(16 + ext.length).putInt(line).putInt(-1);
		b.put(ext);
		out.write(b.array(), 0, b.capacity());
	}

	static byte[] ints(int... v) {
		ByteBuffer b = ByteBuffer.allocate(4 * v.length).order(ByteOrder.LITTLE_ENDIAN);
		for (int i : v)
			b.putInt(i);
		return b.array();
	}

	/* <root xmlns:android="..." android:name="value" id="plain-id">hello text</root> */
	static final String NS = "http://schemas.android.com/apk/res/android";
	static final String[] XML_STRINGS = {"android", NS, "root", "id", "name", "plain-id", "value", "hello text"};

	static byte[] compiledXml() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		byte[] pool = stringPool(XML_STRINGS, null);
		body.write(pool, 0, pool.length);
		node(body, 0x0100, 1, ints(0, 1)); // start namespace android=NS
		ByteBuffer el = ByteBuffer.allocate(20 + 2 * 20).order(ByteOrder.LITTLE_ENDIAN);
		el.putInt(-1).putInt(2); // ns, name "root"
		el.putShort((short)20).putShort((short)20).putShort((short)2); // attributeStart, Size, Count
		el.putShort((short)2).putShort((short)0).putShort((short)0);   // idIndex (1-based), class, style
		// android:name="value"
		el.putInt(1).putInt(4).putInt(6).putShort((short)8).put((byte)0).put((byte)0x03).putInt(6);
		// id="plain-id"
		el.putInt(-1).putInt(3).putInt(5).putShort((short)8).put((byte)0).put((byte)0x03).putInt(5);
		node(body, 0x0102, 1, el.array());
		ByteBuffer text = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
		text.putInt(7).putShort((short)8).put((byte)0).put((byte)0).putInt(0); // cdata "hello text", typed: null
		node(body, 0x0104, 1, text.array());
		node(body, 0x0103, 1, ints(-1, 2)); // end element root
		node(body, 0x0101, 1, ints(0, 1));  // end namespace
		ByteBuffer head = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
		head.putShort((short)0x0003).putShort((short)8).putInt(8 + body.size());
		ByteArrayOutputStream all = new ByteArrayOutputStream();
		all.write(head.array(), 0, 8);
		byte[] b = body.toByteArray();
		all.write(b, 0, b.length);
		return all.toByteArray();
	}

	static Object newXmlBlock(byte[] data, int off, int len) throws Exception {
		Class<?> c = Class.forName("android.content.res.XmlBlock");
		Constructor<?> k = c.getDeclaredConstructor(byte[].class, int.class, int.class);
		k.setAccessible(true);
		return k.newInstance(data, off, len);
	}

	static String readText(String path) throws Exception {
		try (FileInputStream in = new FileInputStream(path)) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buf = new byte[4096];
			int n;
			while ((n = in.read(buf)) > 0)
				out.write(buf, 0, n);
			return new String(out.toByteArray(), StandardCharsets.UTF_8);
		}
	}

	public static void main(String[] args) throws Exception {
		/* api-impl's natives; android-translation-layer has them linked in */
		System.loadLibrary("translation_layer_main");
		final String apk = System.getenv("ATL_TEST_APK_PATH");

		// 1. compiled XML
		group("XmlBlock", () -> {
			byte[] xml = compiledXml();
			byte[] padded = new byte[xml.length + 16];
			System.arraycopy(xml, 0, padded, 8, xml.length);
			Object block = newXmlBlock(padded, 8, xml.length);
			Method newParser = block.getClass().getMethod("newParser");
			XmlResourceParser p = (XmlResourceParser)newParser.invoke(block);
			int ev;
			while ((ev = p.next()) != XmlPullParser.START_TAG && ev != XmlPullParser.END_DOCUMENT)
				;
			check("a block at an offset of its array parses", ev == XmlPullParser.START_TAG && "root".equals(p.getName()), p.getName());
			check("getNamespace of an element without one is \"\"", "".equals(p.getNamespace()), p.getNamespace());
			check("getAttributeNamespace of android:name is the android namespace", NS.equals(p.getAttributeNamespace(0)), p.getAttributeNamespace(0));
			check("getAttributeNamespace of a plain attribute is \"\"", "".equals(p.getAttributeNamespace(1)), p.getAttributeNamespace(1));
			check("getIdAttribute is the id attribute's value", "plain-id".equals(p.getIdAttribute()), p.getIdAttribute());
			check("getText of a start tag is null", p.getText() == null, p.getText());
			ev = p.next();
			check("the text node's getText", ev == XmlPullParser.TEXT && "hello text".equals(p.getText()), ev + " " + p.getText());
			ev = p.next();
			check("then the end tag", ev == XmlPullParser.END_TAG && "root".equals(p.getName()), String.valueOf(ev));
			p.close();
		});
		group("XmlBlock range", () -> {
			boolean threw = false;
			try {
				newXmlBlock(new byte[16], 8, 32);
			} catch (java.lang.reflect.InvocationTargetException e) {
				threw = e.getCause() instanceof IndexOutOfBoundsException;
			}
			check("a block past the array's end throws IndexOutOfBoundsException", threw, null);
		});

		// 2. StringBlock with styles
		group("StringBlock", () -> {
			byte[] pool = stringPool(new String[] {"bold text", "b"}, new int[][][] {{{1, 0, 3}}});
			Class<?> c = Class.forName("android.content.res.StringBlock");
			Object sb = c.getConstructor(byte[].class, boolean.class).newInstance(pool, false);
			CharSequence s = (CharSequence)c.getMethod("get", int.class).invoke(sb, 0);
			check("StringBlock.get returns the string", s != null && "bold text".equals(s.toString()), String.valueOf(s));
			boolean bold = false;
			if (s instanceof Spanned) {
				for (StyleSpan span : ((Spanned)s).getSpans(0, s.length(), StyleSpan.class))
					bold |= span.getStyle() == android.graphics.Typeface.BOLD && ((Spanned)s).getSpanStart(span) == 0 && ((Spanned)s).getSpanEnd(span) == 4;
			}
			check("... with its <b> span over \"bold\"", bold, s != null ? s.getClass().getName() : null);
			CharSequence plain = (CharSequence)c.getMethod("get", int.class).invoke(sb, 1);
			check("a string without styles is plain", plain instanceof String && "b".equals(plain), String.valueOf(plain));
			c.getMethod("close").invoke(sb);
		});

		// 3. AssetManager
		group("AssetManager", () -> {
			AssetManager am = new AssetManager();
			int cookie = am.addAssetPath(apk);
			check("addAssetPath of a loaded APK returns its cookie", cookie > 0, String.valueOf(cookie));
			check("... the same cookie again", am.addAssetPath(apk) == cookie, null);
			check("addAssetPath of a missing file returns 0", am.addAssetPath("/nonexistent/x.apk") == 0, null);
			check("isUpToDate", am.isUpToDate(), null);
			check("getGlobalAssetCount", AssetManager.getGlobalAssetCount() >= 0, String.valueOf(AssetManager.getGlobalAssetCount()));
			AssetManager.getAssetAllocations();
			check("getAssetAllocations and getGlobalAssetManagerCount answer", AssetManager.getGlobalAssetManagerCount() >= 0, null);
			AssetManager sys = AssetManager.getSystem();
			Resources sr = new Resources(sys, new DisplayMetrics(), null);
			int ok = sr.getIdentifier("ok", "string", "android");
			check("AssetManager.getSystem resolves a framework string", ok != 0 && !sr.getString(ok).isEmpty(), null);
			int arr = sr.getIdentifier("emailAddressTypes", "array", "android");
			Method info = AssetManager.class.getDeclaredMethod("getResourceStringArrayInfo", int.class);
			info.setAccessible(true);
			int[] pairs = arr != 0 ? (int[])info.invoke(sys, arr) : null;
			check("getResourceStringArrayInfo: a cookie and a string index per entry", pairs != null && pairs.length > 0 && pairs.length % 2 == 0 && pairs[0] > 0 && pairs[1] >= 0,
			      pairs != null ? Arrays.toString(Arrays.copyOf(pairs, Math.min(pairs.length, 6))) : "no array " + arr);
			Resources.Theme theme = sr.newTheme();
			theme.dump(android.util.Log.DEBUG, "NativesTest", "theme");
			check("Theme.dump", true, null);
		});

		// 4. KeyCharacterMap
		group("KeyCharacterMap", () -> {
			KeyCharacterMap kcm = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD);
			check("the virtual keyboard is FULL", kcm.getKeyboardType() == KeyCharacterMap.FULL, String.valueOf(kcm.getKeyboardType()));
			check("A base is 'a'", kcm.get(KeyEvent.KEYCODE_A, 0) == 'a', String.valueOf(kcm.get(KeyEvent.KEYCODE_A, 0)));
			check("A shift is 'A'", kcm.get(KeyEvent.KEYCODE_A, KeyEvent.META_SHIFT_ON) == 'A', null);
			check("2 shift is '@'", kcm.get(KeyEvent.KEYCODE_2, KeyEvent.META_SHIFT_ON) == '@', String.valueOf((char)kcm.get(KeyEvent.KEYCODE_2, KeyEvent.META_SHIFT_ON)));
			check("A ctrl gives no character", kcm.get(KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON) == 0, null);
			check("the display label of A is 'A'", kcm.getDisplayLabel(KeyEvent.KEYCODE_A) == 'A', null);
			check("the number of 1 is '1'", kcm.getNumber(KeyEvent.KEYCODE_1) == '1', null);
			check("getMatch(A, {'A'}) is 'A'", kcm.getMatch(KeyEvent.KEYCODE_A, new char[] {'A'}) == 'A', null);
			check("A is a printing key, SPACE is not", kcm.isPrintingKey(KeyEvent.KEYCODE_A) && !kcm.isPrintingKey(KeyEvent.KEYCODE_SPACE), null);
			KeyCharacterMap.FallbackAction fb = kcm.getFallbackAction(KeyEvent.KEYCODE_BUTTON_A, 0);
			check("BUTTON_A falls back to DPAD_CENTER", fb != null && fb.keyCode == KeyEvent.KEYCODE_DPAD_CENTER, fb != null ? String.valueOf(fb.keyCode) : "null");
			KeyEvent[] evs = kcm.getEvents("Hi".toCharArray());
			StringBuilder seq = new StringBuilder();
			if (evs != null)
				for (KeyEvent e : evs)
					seq.append(e.getAction() == KeyEvent.ACTION_DOWN ? "+" : "-").append(e.getKeyCode()).append(' ');
			check("getEvents(\"Hi\"): shift, h, shift up, i", evs != null && evs.length == 6 && evs[0].getKeyCode() == KeyEvent.KEYCODE_SHIFT_LEFT
			      && evs[1].getKeyCode() == KeyEvent.KEYCODE_H && (evs[1].getMetaState() & KeyEvent.META_SHIFT_ON) != 0 && evs[4].getKeyCode() == KeyEvent.KEYCODE_I,
			      seq.toString());
			KeyEvent back = new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK);
			check("KEYCODE_BACK is a system key, A is not", back.isSystem() && !KeyEvent.isSystemKey(KeyEvent.KEYCODE_A), null);
			check("KeyEvent.getDisplayLabel", new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_B).getDisplayLabel() == 'B', null);
			check("keyCodeFromString without the prefix", KeyEvent.keyCodeFromString("DPAD_UP") == KeyEvent.KEYCODE_DPAD_UP, null);
		});

		// 5. Process
		group("Process", () -> {
			check("getUidForName(root) and getGidForName(root) are 0", Process.getUidForName("root") == 0 && Process.getGidForName("root") == 0, null);
			check("getUidForName of a number", Process.getUidForName("1234") == 1234 && Process.getUidForName("no-such-user-x") == -1, null);
			long total = Process.getTotalMemory(), free = Process.getFreeMemory();
			check("getTotalMemory > getFreeMemory > 0", total > free && free > 0, total + " " + free);
			long[] sizes = {-1};
			Process.readProcLines("/proc/self/status", new String[] {"VmRSS:"}, sizes);
			check("readProcLines(/proc/self/status, VmRSS:)", sizes[0] > 0, String.valueOf(sizes[0]));
			long[] longs = new long[1];
			String[] strings = new String[2]; // AOSP: one output index across the arrays
			int[] format = {Process.PROC_SPACE_TERM | Process.PROC_OUT_LONG, Process.PROC_SPACE_TERM | Process.PROC_PARENS | Process.PROC_OUT_STRING};
			boolean ok = Process.readProcFile("/proc/self/stat", format, strings, longs, null);
			check("readProcFile(/proc/self/stat): pid and command", ok && longs[0] == Process.myPid() && strings[1] != null && !strings[1].isEmpty(), longs[0] + " " + strings[1]);
			byte[] line = "12 (x y) S".getBytes(StandardCharsets.US_ASCII);
			ok = Process.parseProcLine(line, 0, line.length, format, strings, longs, null);
			check("parseProcLine", ok && longs[0] == 12 && "x y".equals(strings[1]), longs[0] + " " + strings[1]);
			int[] pids = Process.getPids("/proc", null);
			boolean mine = false;
			for (int pid : pids)
				mine |= pid == Process.myPid();
			check("getPids(/proc) has this process", mine, null);
			String comm = readText("/proc/self/cmdline").split("\0")[0];
			int[] byCmd = Process.getPidsForCommands(new String[] {comm});
			mine = false;
			for (int pid : byCmd)
				mine |= pid == Process.myPid();
			check("getPidsForCommands finds this process", mine, comm);
			check("getPss of this process", Process.getPss(Process.myPid()) > 0, null);
			Process.setThreadGroup(Process.myTid(), Process.THREAD_GROUP_BG_NONINTERACTIVE);
			String slack = readText("/proc/" + Process.myTid() + "/timerslack_ns").trim();
			check("setThreadGroup(background) sets the background timer slack", "40000000".equals(slack), slack);
			Process.setThreadGroup(Process.myTid(), Process.THREAD_GROUP_DEFAULT);
			boolean threw = false;
			try {
				Process.setThreadGroup(Process.myTid(), 99);
			} catch (IllegalArgumentException e) {
				threw = true;
			}
			check("setThreadGroup with no such group throws IllegalArgumentException", threw, null);
			Process.setThreadScheduler(Process.myTid(), 0 /* SCHED_OTHER */, 0);
			check("setThreadScheduler(SCHED_OTHER)", true, null);
			Process.setArgV0("natives-test");
			check("setArgV0 renames the process", readText("/proc/self/cmdline").startsWith("natives-test"), readText("/proc/self/cmdline").split("\0")[0]);
			Process.sendSignal(Process.myPid(), 0);
			check("sendSignal", true, null);
		});

		// 6. clocks and Trace
		group("SystemClock", () -> {
			long wall = SystemClock.currentTimeMicro();
			check("currentTimeMicro is the wall clock", Math.abs(wall / 1000 - System.currentTimeMillis()) < 1000, null);
			long cpu0 = SystemClock.currentThreadTimeMicro();
			long x = 0;
			for (int i = 0; i < 20000000; i++)
				x += i ^ (x >>> 3);
			long cpu1 = SystemClock.currentThreadTimeMicro();
			check("currentThreadTimeMicro advances with the thread's work", cpu1 > cpu0 && cpu0 >= 0, cpu0 + " -> " + cpu1 + " " + (x & 1));
			check("uptimeMillis is the monotonic clock truncated", SystemClock.uptimeMillis() <= System.nanoTime() / 1000000 + 1, null);
		});
		group("Trace", () -> {
			Trace.beginSection("natives-test");
			Trace.endSection();
			Trace.setTracingEnabled(true);
			Trace.traceCounter(Trace.TRACE_TAG_APP, "c", 1);
			check("Trace sections and counters", true, null);
		});

		// 7. SQLite
		group("SQLite", () -> {
			SQLiteDatabase db = SQLiteDatabase.create(null);
			db.execSQL("CREATE TABLE t (b BLOB)");
			db.execSQL("INSERT INTO t VALUES (x'0102030405')");
			SQLiteStatement st = db.compileStatement("SELECT b FROM t");
			ParcelFileDescriptor pfd = st.simpleQueryForBlobFileDescriptor();
			byte[] got = new byte[8];
			int n = -1;
			if (pfd != null) {
				try (FileInputStream in = new FileInputStream(pfd.getFileDescriptor())) {
					n = in.read(got);
				}
			}
			check("simpleQueryForBlobFileDescriptor reads the blob", n == 5 && got[0] == 1 && got[4] == 5, String.valueOf(n));
			boolean readOnly = false;
			if (pfd != null) {
				try (FileOutputStream out = new FileOutputStream(pfd.getFileDescriptor())) {
					out.write(9);
				} catch (java.io.IOException e) {
					readOnly = true;
				}
			}
			check("... and the descriptor is read-only", readOnly, null);
			SQLiteDebug.PagerStats stats = SQLiteDebug.getDatabaseInfo();
			check("SQLiteDebug.getDatabaseInfo", stats != null && stats.memoryUsed > 0, stats != null ? String.valueOf(stats.memoryUsed) : null);
			db.close();
		});

		// 8. BitmapFactory
		group("BitmapFactory", () -> {
			check("bytes no decoder knows give null", BitmapFactory.decodeByteArray(new byte[] {1, 2, 3, 4, 5, 6, 7, 8}, 0, 8) == null, null);
			Bitmap bmp = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888);
			File f = File.createTempFile("natives", ".png");
			try (FileOutputStream out = new FileOutputStream(f)) {
				out.write(new byte[] {'x', 'y'});
				bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
			}
			try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
				raf.seek(2);
				Bitmap back = BitmapFactory.decodeFileDescriptor(raf.getFD());
				check("decodeFileDescriptor decodes from the position", back != null && back.getWidth() == 4, null);
				check("... and leaves the position where it was", raf.getFilePointer() == 2, String.valueOf(raf.getFilePointer()));
			}
			f.delete();
		});

		System.out.println("RESULT " + passed + "/" + total);
		System.exit(passed == total ? 0 : 1);
	}
}
