package org.teslalinux.platformtest;

import android.app.AppOpsManager;
import android.app.usage.StorageStats;
import android.app.usage.StorageStatsManager;
import android.atl.ATLLoadedApp;
import android.car.Car;
import android.car.ICar;
import android.car.drivingstate.CarDrivingStateEvent;
import android.car.drivingstate.CarUxRestrictions;
import android.car.drivingstate.CarUxRestrictionsConfiguration;
import android.car.drivingstate.ICarDrivingState;
import android.car.drivingstate.ICarUxRestrictionsManager;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Rect;
import android.location.Location;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.Process;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.storage.StorageManager;
import android.security.keystore.KeyExpiredException;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyInfo;
import android.security.keystore.KeyNotYetValidException;
import android.security.keystore.KeyProperties;
import android.security.keystore.StrongBoxUnavailableException;
import android.view.Choreographer;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.security.auth.x500.X500Principal;

/**
 * ATL runtime tests of platform state an app reads: its permissions and app ops (from its
 * manifest), Intent and Location through a Parcel, the AndroidKeyStore (KeyGenParameterSpec and
 * the key's authorizations), the car service's staged UX restrictions configuration, storage
 * statistics and Choreographer's frame timelines. run.sh builds the test APK (mkapk.py) and runs
 * the phases in separate processes (dalvikvm, the app's uid): "main", then "keystore2" (keys made
 * in "main" keep their authorizations), then the car phases "stage", "moving", "idle" (the staged
 * configuration is promoted at a later start, and only while the vehicle stands).
 * Prints PASS/FAIL per check and "RESULT passed/total".
 */
public class PlatformTest {
	static int passed, total;
	static Handler main;
	static final String P = "android.permission.";
	static final String PKG = "org.teslalinux.platformtest";

	static void check(String name, boolean ok, String detail) {
		total++;
		if (ok)
			passed++;
		System.out.println((ok ? "PASS " : "FAIL ") + name + (!ok && detail != null ? " (" + detail + ")" : ""));
	}

	static Context appContext() {
		return new android.app.ContextImpl(Resources.getSystem(), ATLLoadedApp.getPrimaryApplication(), 0);
	}

	interface Body {
		void run() throws Exception;
	}

	static void asSystem(final Body r) throws Exception {
		final Exception[] failure = new Exception[1];
		Thread t = new Thread(new Runnable() {
			public void run() {
				Binder.tlEnterParty(Process.SYSTEM_UID);
				try {
					r.run();
				} catch (Exception e) {
					failure[0] = e;
				}
			}
		});
		t.start();
		t.join(20000);
		if (failure[0] != null)
			throw failure[0];
	}

	/* ---- 2. permissions and app ops ---- */

	static void permissionTests() throws Exception {
		PackageManager pm = new PackageManager();
		check("the primary app is the test APK", PKG.equals(ATLLoadedApp.getPrimaryApplication().pkg.packageName), ATLLoadedApp.getPrimaryApplication().pkg.packageName);
		int G = PackageManager.PERMISSION_GRANTED;
		check("a requested normal permission is granted (INTERNET)", pm.checkPermission(P + "INTERNET", PKG) == G, "");
		check("a normal permission it does not request is denied (VIBRATE)", pm.checkPermission(P + "VIBRATE", PKG) != G, "granted");
		check("a requested runtime permission is granted (CAMERA)", pm.checkPermission(P + "CAMERA", PKG) == G, "");
		check("and READ_CONTACTS", pm.checkPermission(P + "READ_CONTACTS", PKG) == G, "");
		check("a <uses-permission-sdk-23> is requested on SDK 23+ (READ_CALENDAR)", pm.checkPermission(P + "READ_CALENDAR", PKG) == G, "");
		check("location with a location source (FINE)", pm.checkPermission(P + "ACCESS_FINE_LOCATION", PKG) == G, "");
		check("FINE implies COARSE (AOSP split permission)", pm.checkPermission(P + "ACCESS_COARSE_LOCATION", PKG) == G, "");
		check("a split for an older target SDK does not apply (BACKGROUND_LOCATION, split below 29)", pm.checkPermission(P + "ACCESS_BACKGROUND_LOCATION", PKG) != G, "granted");
		check("the microphone without a host microphone is denied (RECORD_AUDIO)", pm.checkPermission(P + "RECORD_AUDIO", PKG) != G, "granted");
		check("a framework permission newer than framework-res (FOREGROUND_SERVICE_LOCATION)", pm.checkPermission(P + "FOREGROUND_SERVICE_LOCATION", PKG) == G, "");
		check("maxSdkVersion 30 on SDK 32: not requested (BLUETOOTH)", pm.checkPermission(P + "BLUETOOTH", PKG) != G, "granted");
		check("a requested signature permission of the platform is denied (WRITE_SECURE_SETTINGS)", pm.checkPermission(P + "WRITE_SECURE_SETTINGS", PKG) != G, "granted");
		check("a requested signature|appop permission is denied (SYSTEM_ALERT_WINDOW)", pm.checkPermission(P + "SYSTEM_ALERT_WINDOW", PKG) != G, "granted");
		check("a permission nobody defines is denied", pm.checkPermission("org.example.UNDEFINED_PERMISSION", PKG) != G, "granted");
		check("its own signature permission is granted", pm.checkPermission(PKG + ".OWN_SIGNATURE", PKG) == G, "");
		check("a Play services permission (stand-in) is granted", pm.checkPermission("com.google.android.c2dm.permission.RECEIVE", PKG) == G, "");
		check("the framework package holds every permission", pm.checkPermission(P + "WRITE_SECURE_SETTINGS", "android") == G, "");

		Context ctx = appContext();
		check("Context.checkSelfPermission agrees (CAMERA granted, VIBRATE denied)", ctx.checkSelfPermission(P + "CAMERA") == G && ctx.checkSelfPermission(P + "VIBRATE") != G, "");
		check("Context.checkPermission for the app's uid agrees", ctx.checkPermission(P + "WRITE_SECURE_SETTINGS", Process.myPid(), Process.myUid()) != G
		    && ctx.checkPermission(P + "INTERNET", Process.myPid(), Process.myUid()) == G, "");
		check("checkCallingOrSelfPermission outside a call is the app's own", ctx.checkCallingOrSelfPermission(P + "RECORD_AUDIO") != G
		    && ctx.checkCallingOrSelfPermission(P + "CAMERA") == G, "");

		PackageInfo info = pm.getPackageInfo(PKG, PackageManager.GET_PERMISSIONS);
		int camera = -1, audio = -1;
		for (int i = 0; info.requestedPermissions != null && i < info.requestedPermissions.length; i++) {
			if ((P + "CAMERA").equals(info.requestedPermissions[i]))
				camera = info.requestedPermissionsFlags[i];
			if ((P + "RECORD_AUDIO").equals(info.requestedPermissions[i]))
				audio = info.requestedPermissionsFlags[i];
		}
		check("PackageInfo: CAMERA requested and granted", camera >= 0 && (camera & PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0, "flags " + camera);
		check("PackageInfo: RECORD_AUDIO requested, not granted", audio >= 0 && (audio & PackageInfo.REQUESTED_PERMISSION_GRANTED) == 0, "flags " + audio);
		check("PackageInfo lists no permission it did not request", info.requestedPermissions != null && !Arrays.asList(info.requestedPermissions).contains(P + "VIBRATE"), "");

		AppOpsManager ops = new AppOpsManager(ctx);
		int me = Process.myUid();
		check("app op of a granted foreground permission: raw MODE_FOREGROUND (camera)", ops.unsafeCheckOpRawNoThrow(AppOpsManager.OPSTR_CAMERA, me, PKG) == AppOpsManager.MODE_FOREGROUND, "" + ops.unsafeCheckOpRawNoThrow(AppOpsManager.OPSTR_CAMERA, me, PKG));
		check("evaluated for the foreground app: MODE_ALLOWED", ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_CAMERA, me, PKG) == AppOpsManager.MODE_ALLOWED, "");
		check("app op of a denied runtime permission: MODE_IGNORED (record audio)", ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_RECORD_AUDIO, me, PKG) == AppOpsManager.MODE_IGNORED, "" + ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_RECORD_AUDIO, me, PKG));
		check("fine location without background location: raw MODE_FOREGROUND", ops.unsafeCheckOpRawNoThrow(AppOpsManager.OPSTR_FINE_LOCATION, me, PKG) == AppOpsManager.MODE_FOREGROUND, "");
		check("an appop permission's op is at MODE_DEFAULT (SYSTEM_ALERT_WINDOW)", ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, me, PKG) == AppOpsManager.MODE_DEFAULT, "");
		check("Settings.canDrawOverlays follows the op and the permission (false)", !android.provider.Settings.canDrawOverlays(ctx), "true");
		check("an op without a permission has its default (TOAST_WINDOW allowed)", ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_TOAST_WINDOW, me, PKG) == AppOpsManager.MODE_ALLOWED, "");
		check("MOCK_LOCATION is MODE_ERRORED by default", ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, me, PKG) == AppOpsManager.MODE_ERRORED, "");
		boolean threw = false;
		try {
			ops.noteOp(AppOpsManager.OPSTR_MOCK_LOCATION, me, PKG, null, null);
		} catch (SecurityException e) {
			threw = true;
		}
		check("noteOp of an errored op throws SecurityException", threw, "");
		check("noteOp for a package of another uid is MODE_ERRORED", ops.noteOpNoThrow(AppOpsManager.OPSTR_CAMERA, me + 1, PKG, null, null) == AppOpsManager.MODE_ERRORED, "");
		check("permissionToOp: CAMERA -> android:camera, INTERNET -> null", AppOpsManager.OPSTR_CAMERA.equals(AppOpsManager.permissionToOp(P + "CAMERA")) && AppOpsManager.permissionToOp(P + "INTERNET") == null, "");
		threw = false;
		try {
			ops.checkPackage(me, "org.example.other");
		} catch (SecurityException e) {
			threw = true;
		}
		check("checkPackage refuses a package of another uid", threw, "");
		threw = false;
		try {
			ops.setMode(AppOpsManager.OP_CAMERA, me, PKG, AppOpsManager.MODE_IGNORED);
		} catch (SecurityException e) {
			threw = true;
		}
		check("the app cannot set modes (MANAGE_APP_OPS_MODES)", threw, "");
		final String[] changed = new String[1];
		AppOpsManager.OnOpChangedListener l = new AppOpsManager.OnOpChangedListener() {
			public void onOpChanged(String op, String packageName) {
				changed[0] = op + "/" + packageName;
			}
		};
		ops.startWatchingMode(AppOpsManager.OPSTR_CAMERA, PKG, l);
		final AppOpsManager sysOps = new AppOpsManager(ctx);
		asSystem(new Body() {
			public void run() {
				sysOps.setMode(AppOpsManager.OP_CAMERA, 12345, PKG, AppOpsManager.MODE_IGNORED);
			}
		});
		/* the platform set the mode for another uid above: no effect on the app */
		check("a mode set for another uid leaves the app's op alone", ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_CAMERA, me, PKG) == AppOpsManager.MODE_ALLOWED, "");
		final int myUid = me;
		asSystem(new Body() {
			public void run() {
				sysOps.setMode(AppOpsManager.OP_CAMERA, myUid, PKG, AppOpsManager.MODE_IGNORED);
			}
		});
		check("the platform's setMode applies", ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_CAMERA, me, PKG) == AppOpsManager.MODE_IGNORED, "");
		check("and the watcher heard it", (AppOpsManager.OPSTR_CAMERA + "/" + PKG).equals(changed[0]), "" + changed[0]);
		asSystem(new Body() {
			public void run() {
				sysOps.setMode(AppOpsManager.OP_CAMERA, myUid, PKG, AppOpsManager.MODE_FOREGROUND);
			}
		});
		ops.stopWatchingMode(l);
		final boolean[] active = new boolean[2];
		ops.startWatchingActive(new String[] {AppOpsManager.OPSTR_CAMERA}, null, new AppOpsManager.OnOpActiveChangedListener() {
			public void onOpActiveChanged(String op, int uid, String packageName, boolean a) {
				active[a ? 0 : 1] = true;
			}
		});
		ops.startOp(AppOpsManager.OPSTR_CAMERA, me, PKG, null, null);
		boolean was = ops.isOpActive(AppOpsManager.OPSTR_CAMERA, me, PKG);
		ops.finishOp(AppOpsManager.OPSTR_CAMERA, me, PKG, null);
		check("startOp/finishOp: active while started, listeners told", was && !ops.isOpActive(AppOpsManager.OPSTR_CAMERA, me, PKG) && active[0] && active[1], was + " " + active[0] + " " + active[1]);
	}

	/* ---- 3. Intent and Location through a Parcel ---- */

	static void parcelTests() {
		Intent sel = new Intent(Intent.ACTION_MAIN);
		sel.addCategory(Intent.CATEGORY_APP_MAPS);
		Intent in = new Intent("org.teslalinux.ACTION");
		in.setDataAndType(Uri.parse("geo:32.1,34.8?q=home"), "text/plain");
		in.setIdentifier("id-7");
		in.addCategory("org.teslalinux.CAT_A");
		in.addCategory("org.teslalinux.CAT_B");
		in.setComponent(new ComponentName(PKG, PKG + ".Target"));
		in.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
		in.putExtra("s", "text");
		in.putExtra("i", 42);
		in.putExtra("d", 2.5);
		in.putExtra("arr", new int[] {1, 2, 3});
		Bundle nested = new Bundle();
		nested.putLong("l", 1L << 40);
		in.putExtra("b", nested);
		Location carried = new Location("gps");
		carried.setLatitude(1.5);
		in.putExtra("loc", carried);
		in.setClipData(ClipData.newPlainText("label", "clip text"));
		in.setSelector(sel);
		in.setSourceBounds(new Rect(1, 2, 3, 4));
		Parcel p = Parcel.obtain();
		in.writeToParcel(p, 0);
		p.setDataPosition(0);
		Intent out = Intent.CREATOR.createFromParcel(p);
		check("parcel consumed exactly", p.dataPosition() == p.dataSize(), p.dataPosition() + "/" + p.dataSize());
		p.recycle();
		check("Intent: action, data, type", "org.teslalinux.ACTION".equals(out.getAction()) && Uri.parse("geo:32.1,34.8?q=home").equals(out.getData()) && "text/plain".equals(out.getType()), out.toString());
		check("Intent: identifier, component, flags", "id-7".equals(out.getIdentifier()) && new ComponentName(PKG, PKG + ".Target").equals(out.getComponent())
		    && out.getFlags() == (Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION), "");
		check("Intent: categories", out.getCategories() != null && out.getCategories().size() == 2 && out.hasCategory("org.teslalinux.CAT_B"), "" + out.getCategories());
		check("Intent: extras of every kind", "text".equals(out.getStringExtra("s")) && out.getIntExtra("i", 0) == 42 && out.getDoubleExtra("d", 0) == 2.5
		    && Arrays.equals(new int[] {1, 2, 3}, out.getIntArrayExtra("arr")) && out.getBundleExtra("b") != null && out.getBundleExtra("b").getLong("l") == (1L << 40), "");
		Location back = (Location) out.getParcelableExtra("loc");
		check("Intent: a Parcelable extra", back != null && back.getLatitude() == 1.5 && "gps".equals(back.getProvider()), "" + back);
		check("Intent: clip data", out.getClipData() != null && out.getClipData().getItemCount() == 1 && "clip text".contentEquals(out.getClipData().getItemAt(0).getText()), "");
		check("Intent: selector", out.getSelector() != null && Intent.ACTION_MAIN.equals(out.getSelector().getAction()) && out.getSelector().hasCategory(Intent.CATEGORY_APP_MAPS), "");
		check("Intent: source bounds", new Rect(1, 2, 3, 4).equals(out.getSourceBounds()), "" + out.getSourceBounds());
		check("Intent: filterEquals the original", in.filterEquals(out), "");
		Intent withPackage = new Intent("x").setPackage("org.example.pkg");
		p = Parcel.obtain();
		withPackage.writeToParcel(p, 0);
		p.setDataPosition(0);
		check("Intent: package", "org.example.pkg".equals(Intent.CREATOR.createFromParcel(p).getPackage()), "");
		p.recycle();

		Location l = new Location("fused");
		l.setTime(1_700_000_000_123L);
		l.setElapsedRealtimeNanos(987_654_321_000L);
		l.setElapsedRealtimeUncertaintyNanos(12_345.0);
		l.setLatitude(32.0853);
		l.setLongitude(34.7818);
		l.setAltitude(41.5);
		l.setSpeed(13.9f);
		l.setBearing(271.25f);
		l.setAccuracy(3.5f);
		l.setVerticalAccuracyMeters(5.25f);
		l.setSpeedAccuracyMetersPerSecond(0.75f);
		l.setBearingAccuracyDegrees(2.5f);
		l.setMslAltitudeMeters(22.75);
		l.setMslAltitudeAccuracyMeters(1.25f);
		Bundle ex = new Bundle();
		ex.putInt("satellites", 9);
		l.setExtras(ex);
		p = Parcel.obtain();
		l.writeToParcel(p, 0);
		p.setDataPosition(0);
		Location r = Location.CREATOR.createFromParcel(p);
		check("Location parcel consumed exactly", p.dataPosition() == p.dataSize(), "");
		p.recycle();
		check("Location: provider, time, elapsed realtime and its uncertainty", "fused".equals(r.getProvider()) && r.getTime() == 1_700_000_000_123L
		    && r.getElapsedRealtimeNanos() == 987_654_321_000L && r.hasElapsedRealtimeUncertaintyNanos() && r.getElapsedRealtimeUncertaintyNanos() == 12_345.0, r.toString());
		check("Location: position and altitude", r.getLatitude() == 32.0853 && r.getLongitude() == 34.7818 && r.hasAltitude() && r.getAltitude() == 41.5, "");
		check("Location: speed, bearing, accuracies", r.getSpeed() == 13.9f && r.getBearing() == 271.25f && r.getAccuracy() == 3.5f && r.getVerticalAccuracyMeters() == 5.25f
		    && r.getSpeedAccuracyMetersPerSecond() == 0.75f && r.getBearingAccuracyDegrees() == 2.5f, "");
		check("Location: MSL altitude and its accuracy", r.hasMslAltitude() && r.getMslAltitudeMeters() == 22.75 && r.hasMslAltitudeAccuracy() && r.getMslAltitudeAccuracyMeters() == 1.25f, "");
		check("Location: extras", r.getExtras() != null && r.getExtras().getInt("satellites") == 9, "");
		check("Location equals the original", l.equals(r), "");
	}

	/* ---- 4. keystore ---- */

	static KeyStore keystore() throws Exception {
		android.security.keystore.AndroidKeyStoreProvider.install();
		KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
		ks.load(null);
		return ks;
	}

	static SecretKey aes(KeyGenParameterSpec spec) throws Exception {
		KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
		g.init(spec);
		return g.generateKey();
	}

	static void keystoreTests() throws Exception {
		KeyStore ks = keystore();
		Date start = new Date(System.currentTimeMillis() - 1000), end = new Date(System.currentTimeMillis() + 3600_000);
		KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder("t-gcm", KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
		    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256)
		    .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512).setKeyValidityStart(start).setKeyValidityEnd(end)
		    .setCertificateSubject(new X500Principal("CN=t-gcm")).setCertificateSerialNumber(BigInteger.valueOf(77))
		    .setCertificateNotBefore(start).setCertificateNotAfter(end).setUserAuthenticationValidityDurationSeconds(30).setMaxUsageCount(5).build();
		check("KeyGenParameterSpec carries digests", Arrays.equals(new String[] {KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512}, spec.getDigests()) && spec.isDigestsSpecified(), "");
		check("and validity", start.equals(spec.getKeyValidityStart()) && end.equals(spec.getKeyValidityForOriginationEnd()) && end.equals(spec.getKeyValidityForConsumptionEnd()), "");
		check("and the certificate fields", new X500Principal("CN=t-gcm").equals(spec.getCertificateSubject()) && BigInteger.valueOf(77).equals(spec.getCertificateSerialNumber())
		    && start.equals(spec.getCertificateNotBefore()) && end.equals(spec.getCertificateNotAfter()), "");
		check("and paddings, modes, key size, usage count, auth timeout", Arrays.equals(new String[] {KeyProperties.ENCRYPTION_PADDING_NONE}, spec.getEncryptionPaddings())
		    && Arrays.equals(new String[] {KeyProperties.BLOCK_MODE_GCM}, spec.getBlockModes()) && spec.getKeySize() == 256 && spec.getMaxUsageCount() == 5
		    && spec.getUserAuthenticationValidityDurationSeconds() == 30 && spec.isRandomizedEncryptionRequired(), "");
		check("defaults: no auth, not StrongBox, randomized encryption, unlimited uses", !new KeyGenParameterSpec.Builder("x", 1).build().isUserAuthenticationRequired()
		    && !new KeyGenParameterSpec.Builder("x", 1).build().isStrongBoxBacked() && new KeyGenParameterSpec.Builder("x", 1).build().getMaxUsageCount() == KeyProperties.UNRESTRICTED_USAGE_COUNT, "");

		ks.deleteEntry("t-gcm");
		SecretKey key = aes(new KeyGenParameterSpec.Builder("t-gcm", KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
		    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build());
		Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
		c.init(Cipher.ENCRYPT_MODE, key);
		byte[] iv = c.getIV();
		byte[] ct = c.doFinal("secret".getBytes("UTF-8"));
		check("Cipher.getInstance without a provider takes a keystore key (keystore IV, 12 bytes)", iv != null && iv.length == 12 && "AndroidKeyStoreBCWorkaround".equals(c.getProvider().getName()), c.getProvider().getName());
		Cipher d = Cipher.getInstance("AES/GCM/NoPadding");
		d.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
		check("and decrypts", "secret".equals(new String(d.doFinal(ct), "UTF-8")), "");
		boolean refused = false;
		try {
			Cipher e2 = Cipher.getInstance("AES/GCM/NoPadding");
			e2.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, new byte[12]));
		} catch (InvalidAlgorithmParameterException e) {
			refused = true;
		}
		check("randomized encryption: a caller IV is refused", refused, "");
		refused = false;
		try {
			Cipher.getInstance("AES/CBC/PKCS7Padding").init(Cipher.ENCRYPT_MODE, key);
		} catch (InvalidKeyException e) {
			refused = true;
		}
		check("a block mode the key is not for is refused (CBC on a GCM key)", refused, "");
		Cipher plain = Cipher.getInstance("AES/GCM/NoPadding");
		plain.init(Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec.class.getConstructor(byte[].class, String.class).newInstance(new byte[16], "AES"));
		check("other keys keep the platform's AES", !"AndroidKeyStoreBCWorkaround".equals(plain.getProvider().getName()), plain.getProvider().getName());

		SecretKey encOnly = aes(new KeyGenParameterSpec.Builder("t-enc", KeyProperties.PURPOSE_ENCRYPT).setBlockModes(KeyProperties.BLOCK_MODE_CBC)
		    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_PKCS7).setRandomizedEncryptionRequired(false).build());
		refused = false;
		try {
			Cipher.getInstance("AES/CBC/PKCS7Padding").init(Cipher.DECRYPT_MODE, encOnly, new IvParameterSpec(new byte[16]));
		} catch (InvalidKeyException e) {
			refused = true;
		}
		check("a purpose the key is not for is refused (decrypt with an encrypt-only key)", refused, "");
		Cipher cbc = Cipher.getInstance("AES/CBC/PKCS7Padding");
		cbc.init(Cipher.ENCRYPT_MODE, encOnly, new IvParameterSpec(new byte[16]));
		check("without randomized encryption a caller IV is allowed (PKCS7)", cbc.doFinal(new byte[5]).length == 16, "");
		refused = false;
		try {
			aes(new KeyGenParameterSpec.Builder("t-ecb", KeyProperties.PURPOSE_ENCRYPT).setBlockModes(KeyProperties.BLOCK_MODE_ECB).build());
		} catch (InvalidAlgorithmParameterException e) {
			refused = true;
		}
		check("ECB with randomized encryption is refused at init", refused, "");
		refused = false;
		try {
			aes(new KeyGenParameterSpec.Builder("t-size", KeyProperties.PURPOSE_ENCRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setKeySize(100).build());
		} catch (InvalidAlgorithmParameterException e) {
			refused = true;
		}
		check("an AES key size other than 128/192/256 is refused", refused, "");

		SecretKey expired = aes(new KeyGenParameterSpec.Builder("t-exp", KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
		    .setKeyValidityForOriginationEnd(new Date(System.currentTimeMillis() - 1000)).build());
		refused = false;
		try {
			Cipher.getInstance("AES/GCM/NoPadding").init(Cipher.ENCRYPT_MODE, expired);
		} catch (KeyExpiredException e) {
			refused = true;
		}
		check("past origination end: KeyExpiredException when encrypting", refused, "");
		boolean decrypts = true;
		try {
			Cipher.getInstance("AES/GCM/NoPadding").init(Cipher.DECRYPT_MODE, expired, new GCMParameterSpec(128, new byte[12]));
		} catch (InvalidKeyException e) {
			decrypts = false;
		}
		check("decrypting is still allowed (consumption end not reached)", decrypts, "");
		SecretKey future = aes(new KeyGenParameterSpec.Builder("t-fut", KeyProperties.PURPOSE_ENCRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
		    .setKeyValidityStart(new Date(System.currentTimeMillis() + 3600_000)).build());
		refused = false;
		try {
			Cipher.getInstance("AES/GCM/NoPadding").init(Cipher.ENCRYPT_MODE, future);
		} catch (KeyNotYetValidException e) {
			refused = true;
		}
		check("before validity start: KeyNotYetValidException", refused, "");

		String msg = null;
		try {
			aes(new KeyGenParameterSpec.Builder("t-auth", KeyProperties.PURPOSE_ENCRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
			    .setUserAuthenticationRequired(true).setUserAuthenticationValidityDurationSeconds(60).build());
		} catch (InvalidAlgorithmParameterException e) {
			msg = String.valueOf(e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
		}
		check("user authentication without a secure lock screen is refused (AOSP message)", msg != null && msg.contains("Secure lock screen must be enabled"), msg);
		boolean strongbox = false;
		try {
			aes(new KeyGenParameterSpec.Builder("t-sb", KeyProperties.PURPOSE_ENCRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setIsStrongBoxBacked(true).build());
		} catch (StrongBoxUnavailableException e) {
			strongbox = true;
		}
		check("StrongBox: StrongBoxUnavailableException at generation", strongbox && !ks.containsAlias("t-sb"), "");

		KeyGenerator hg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore");
		hg.init(new KeyGenParameterSpec.Builder("t-hmac", KeyProperties.PURPOSE_SIGN).build());
		SecretKey hk = hg.generateKey();
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(hk);
		check("HmacSHA256 keystore key makes a 32-byte MAC", mac.doFinal("x".getBytes()).length == 32, "");
		refused = false;
		try {
			hg.init(new KeyGenParameterSpec.Builder("t-hmac2", KeyProperties.PURPOSE_SIGN).setDigests(KeyProperties.DIGEST_SHA512).build());
		} catch (InvalidAlgorithmParameterException e) {
			refused = true;
		}
		check("an HMAC key with another digest is refused", refused, "");

		SecretKeyFactory f = SecretKeyFactory.getInstance(key.getAlgorithm(), "AndroidKeyStore");
		KeyInfo ki = (KeyInfo) f.getKeySpec(key, KeyInfo.class);
		check("KeyInfo: software, not in secure hardware", !ki.isInsideSecureHardware() && ki.getSecurityLevel() == KeyProperties.SECURITY_LEVEL_SOFTWARE, "");
		check("KeyInfo: size, purposes, block modes, origin", ki.getKeySize() == 256 && ki.getPurposes() == (KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
		    && Arrays.equals(new String[] {KeyProperties.BLOCK_MODE_GCM}, ki.getBlockModes()) && ki.getOrigin() == KeyProperties.ORIGIN_GENERATED, "");

		SecretKey once = aes(new KeyGenParameterSpec.Builder("t-once", KeyProperties.PURPOSE_ENCRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setMaxUsageCount(1).build());
		Cipher.getInstance("AES/GCM/NoPadding").init(Cipher.ENCRYPT_MODE, once);
		refused = false;
		try {
			Cipher.getInstance("AES/GCM/NoPadding").init(Cipher.ENCRYPT_MODE, once);
		} catch (InvalidKeyException e) {
			refused = true;
		}
		check("a key with one use is gone after it", refused && !ks.containsAlias("t-once"), "");
	}

	/* a new process: the keys of the main phase keep their authorizations */
	static void keystorePersistenceTests() throws Exception {
		KeyStore ks = keystore();
		SecretKey enc = (SecretKey) ks.getKey("t-enc", null);
		check("a stored key is there in a new process", enc != null, "");
		boolean refused = false;
		try {
			Cipher.getInstance("AES/CBC/PKCS7Padding").init(Cipher.DECRYPT_MODE, enc, new IvParameterSpec(new byte[16]));
		} catch (InvalidKeyException e) {
			refused = true;
		}
		check("with its purposes", refused, "");
		KeyInfo ki = (KeyInfo) SecretKeyFactory.getInstance("AES", "AndroidKeyStore").getKeySpec((SecretKey) ks.getKey("t-gcm", null), KeyInfo.class);
		check("and its block modes", Arrays.equals(new String[] {KeyProperties.BLOCK_MODE_GCM}, ki.getBlockModes()), Arrays.toString(ki.getBlockModes()));
	}

	/* ---- 5. car UX restrictions: the staged configuration ---- */

	static ICarUxRestrictionsManager uxManager() throws Exception {
		ICar car = ICar.Stub.asInterface(android.atl.car.ATLCarService.getServiceBinder());
		return ICarUxRestrictionsManager.Stub.asInterface(car.getCarService(Car.CAR_UX_RESTRICTION_SERVICE));
	}

	static ICarDrivingState drivingState() throws Exception {
		ICar car = ICar.Stub.asInterface(android.atl.car.ATLCarService.getServiceBinder());
		return ICarDrivingState.Stub.asInterface(car.getCarService(Car.CAR_DRIVING_STATE_SERVICE));
	}

	static boolean hasCustomConfig(List<CarUxRestrictionsConfiguration> configs) {
		return configs != null && configs.size() == 1
		    && configs.get(0).getUxRestrictions(CarDrivingStateEvent.DRIVING_STATE_MOVING, 10f, "baseline").getMaxRestrictedStringLength() == 42;
	}

	static void stageTests() throws Exception {
		final ICarUxRestrictionsManager ux = uxManager();
		check("the car service has the UX restrictions service", ux != null, "");
		boolean threw = false;
		try {
			ux.getStagedConfigs();
		} catch (SecurityException e) {
			threw = true;
		}
		check("an app without CAR_UX_RESTRICTIONS_CONFIGURATION is refused", threw, "");
		asSystem(new Body() {
			public void run() throws Exception {
				check("nothing is staged at first (null)", ux.getStagedConfigs() == null, "");
				CarUxRestrictionsConfiguration.Builder b = new CarUxRestrictionsConfiguration.Builder();
				b.setMaxStringLength(42);
				b.setUxRestrictions(CarDrivingStateEvent.DRIVING_STATE_PARKED, false, CarUxRestrictions.UX_RESTRICTIONS_BASELINE);
				b.setUxRestrictions(CarDrivingStateEvent.DRIVING_STATE_IDLING, false, CarUxRestrictions.UX_RESTRICTIONS_BASELINE);
				b.setUxRestrictions(CarDrivingStateEvent.DRIVING_STATE_MOVING, true, CarUxRestrictions.UX_RESTRICTIONS_FULLY_RESTRICTED);
				List<CarUxRestrictionsConfiguration> list = new ArrayList<>();
				list.add(b.build());
				check("saveUxRestrictionsConfigurationForNextBoot stages it", ux.saveUxRestrictionsConfigurationForNextBoot(list), "");
				check("getStagedConfigs returns it", hasCustomConfig(ux.getStagedConfigs()), "" + ux.getStagedConfigs());
				check("getConfigs is unchanged (the default map)", !hasCustomConfig(ux.getConfigs()), "");
				boolean refused = false;
				try {
					ux.saveUxRestrictionsConfigurationForNextBoot(new ArrayList<CarUxRestrictionsConfiguration>());
				} catch (IllegalArgumentException e) {
					refused = true;
				}
				check("an empty configuration is refused (validateConfigs)", refused, "");
			}
		});
		File staged = new File(android.atl.ATLPaths.app_data_dir_base, "_system_/car/ux_restrictions_staged_config.json");
		String json = staged.isFile() ? new String(java.nio.file.Files.readAllBytes(staged.toPath()), "UTF-8") : "";
		check("the staged file is AOSP's V2 JSON", json.contains("\"schema_version\":2") && json.contains("\"restrictions\""), json.length() > 200 ? json.substring(0, 200) : json);
	}

	static void injectState(final int state) throws Exception {
		final ICarDrivingState ds = drivingState();
		asSystem(new Body() {
			public void run() throws Exception {
				ds.injectDrivingState(new CarDrivingStateEvent(state, SystemClock.elapsedRealtimeNanos()));
			}
		});
	}

	static void movingTests() throws Exception {
		final ICarUxRestrictionsManager ux = uxManager();
		asSystem(new Body() {
			public void run() throws Exception {
				check("a new start: still staged, not yet in use", hasCustomConfig(ux.getStagedConfigs()) && !hasCustomConfig(ux.getConfigs()), "");
			}
		});
		injectState(CarDrivingStateEvent.DRIVING_STATE_MOVING);
		asSystem(new Body() {
			public void run() throws Exception {
				check("while moving the staged configuration is not promoted", hasCustomConfig(ux.getStagedConfigs()) && !hasCustomConfig(ux.getConfigs()), "");
			}
		});
		injectState(CarDrivingStateEvent.DRIVING_STATE_IDLING);
		asSystem(new Body() {
			public void run() throws Exception {
				check("and not later in the same start", hasCustomConfig(ux.getStagedConfigs()) && !hasCustomConfig(ux.getConfigs()), "");
			}
		});
	}

	static void idleTests() throws Exception {
		final ICarUxRestrictionsManager ux = uxManager();
		injectState(CarDrivingStateEvent.DRIVING_STATE_IDLING);
		asSystem(new Body() {
			public void run() throws Exception {
				check("standing at the first known state of a start: promoted", hasCustomConfig(ux.getConfigs()), "" + ux.getConfigs());
				check("nothing is staged any more", ux.getStagedConfigs() == null, "");
			}
		});
		File prod = new File(android.atl.ATLPaths.app_data_dir_base, "_system_/car/ux_restrictions_prod_config.json");
		check("the production file is in place", prod.isFile(), "");
		injectState(CarDrivingStateEvent.DRIVING_STATE_MOVING);
		asSystem(new Body() {
			public void run() throws Exception {
				CarUxRestrictions r = ux.getCurrentUxRestrictions(0);
				check("moving: the promoted configuration's restrictions apply", r.getActiveRestrictions() == CarUxRestrictions.UX_RESTRICTIONS_FULLY_RESTRICTED && r.getMaxRestrictedStringLength() == 42,
				    r.getActiveRestrictions() + " " + r.getMaxRestrictedStringLength());
			}
		});
	}

	/* ---- 8. storage statistics and Choreographer frame data ---- */

	static long size(File f) {
		if (f.isFile())
			return f.length();
		long t = 0;
		File[] c = f.listFiles();
		if (c != null)
			for (File x : c)
				t += size(x);
		return t;
	}

	static void storageTests() throws Exception {
		Context ctx = appContext();
		StorageStatsManager ssm = (StorageStatsManager) ctx.getSystemService("storagestats");
		check("getSystemService(storagestats)", ssm != null, "");
		File apk = new File(System.getProperty("atl.app.class.path"));
		File lib = new File(System.getProperty("atl.app.library.path"));
		ATLLoadedApp.getPrimaryApplication().pkg.applicationInfo.nativeLibraryDir = lib.getPath();
		File data = ctx.getDataDir();
		data.mkdirs();
		try (FileOutputStream o = new FileOutputStream(new File(data, "blob.bin"))) {
			o.write(new byte[50_000]);
		}
		File cache = ctx.getCacheDir();
		try (FileOutputStream o = new FileOutputStream(new File(cache, "cached.bin"))) {
			o.write(new byte[20_000]);
		}
		StorageStats s = ssm.queryStatsForPackage(StorageManager.UUID_DEFAULT, PKG, UserHandle.getUserHandleForUid(Process.myUid()));
		long expectApp = apk.length() + size(lib);
		check("app bytes: the APK and its native libraries", s.getAppBytes() >= expectApp && s.getAppBytes() < expectApp + 4_000_000, s.getAppBytes() + " vs " + expectApp);
		check("by type: APK bytes and library bytes", s.getAppBytesByDataType(StorageStats.APP_DATA_TYPE_FILE_TYPE_APK) == apk.length()
		    && s.getAppBytesByDataType(StorageStats.APP_DATA_TYPE_LIB) == size(lib), s.getAppBytesByDataType(3) + " " + s.getAppBytesByDataType(5));
		check("cache bytes include the cache directory", s.getCacheBytes() >= 20_000, "" + s.getCacheBytes());
		check("data bytes include the data directory and the cache", s.getDataBytes() >= 70_000 && s.getDataBytes() >= s.getCacheBytes(), "" + s.getDataBytes());
		StorageStats u = ssm.queryStatsForUid(StorageManager.UUID_DEFAULT, Process.myUid());
		check("the app's uid has the same stats", u.getAppBytes() == s.getAppBytes() && u.getDataBytes() == s.getDataBytes(), u.getAppBytes() + " " + u.getDataBytes());
		boolean threw = false;
		try {
			ssm.queryStatsForUser(StorageManager.UUID_DEFAULT, UserHandle.getUserHandleForUid(Process.myUid()));
		} catch (SecurityException e) {
			threw = true;
		}
		check("the whole user's stats need PACKAGE_USAGE_STATS", threw, "");
		threw = false;
		try {
			ssm.getTotalBytes(UUID.randomUUID());
		} catch (java.io.IOException e) {
			threw = true;
		}
		check("an unknown volume: IOException", threw, "");
		check("total >= free > 0", ssm.getTotalBytes(StorageManager.UUID_DEFAULT) >= ssm.getFreeBytes(StorageManager.UUID_DEFAULT) && ssm.getFreeBytes(StorageManager.UUID_DEFAULT) > 0, "");
		Parcel p = Parcel.obtain();
		s.writeToParcel(p, 0);
		p.setDataPosition(0);
		StorageStats back = StorageStats.CREATOR.createFromParcel(p);
		p.recycle();
		check("StorageStats through a Parcel", back.getAppBytes() == s.getAppBytes() && back.getCacheBytes() == s.getCacheBytes(), "");
	}

	static void choreographerTests() throws Exception {
		final CountDownLatch done = new CountDownLatch(1);
		final Object[] got = new Object[1];
		final long[] sent = new long[4];
		main.post(new Runnable() {
			public void run() {
				try {
					Choreographer ch = Choreographer.getInstance();
					ch.postVsyncCallback(new Choreographer.VsyncCallback() {
						public void onVsync(Choreographer.FrameData data) {
							got[0] = data;
							done.countDown();
						}
					});
					long now = System.nanoTime();
					sent[0] = now - 1_000_000;
					sent[1] = 4242;
					sent[2] = now + 9_000_000;
					sent[3] = 11_111_111;
					Method m = Choreographer.class.getDeclaredMethod("tlOnVsync", long.class, long.class, long.class, long.class);
					m.setAccessible(true);
					m.invoke(ch, sent[0], sent[1], sent[2], sent[3]);
				} catch (Exception e) {
					e.printStackTrace();
					done.countDown();
				}
			}
		});
		done.await(5, TimeUnit.SECONDS);
		Choreographer.FrameData fd = (Choreographer.FrameData) got[0];
		check("a vsync callback gets the frame clock's frame data", fd != null && fd.getFrameTimeNanos() == sent[0], fd == null ? "none" : "" + fd.getFrameTimeNanos());
		if (fd == null)
			return;
		Choreographer.FrameTimeline pref = fd.getPreferredFrameTimeline();
		check("preferred timeline: the vsync id and the predicted presentation time", pref.getVsyncId() == sent[1] && pref.getExpectedPresentationTimeNanos() == sent[2],
		    pref.getVsyncId() + " " + pref.getExpectedPresentationTimeNanos() + " vs " + sent[2]);
		check("its deadline: the next frame start, at most the presentation time", pref.getDeadlineNanos() == Math.min(sent[0] + sent[3], sent[2]), "" + pref.getDeadlineNanos());
		Choreographer.FrameTimeline[] tl = fd.getFrameTimelines();
		boolean spaced = tl.length > 1;
		for (int i = 1; i < tl.length; i++)
			spaced &= tl[i].getExpectedPresentationTimeNanos() - tl[i - 1].getExpectedPresentationTimeNanos() == sent[3] && tl[i].getVsyncId() == tl[i - 1].getVsyncId() + 1;
		check("later timelines one refresh interval apart", spaced, "" + tl.length);
		final long[] interval = new long[1];
		final CountDownLatch d2 = new CountDownLatch(1);
		main.post(new Runnable() {
			public void run() {
				interval[0] = Choreographer.getInstance().getFrameIntervalNanos();
				d2.countDown();
			}
		});
		d2.await(5, TimeUnit.SECONDS);
		check("the frame interval follows the frame clock's refresh interval", interval[0] == sent[3], "" + interval[0]);
	}

	/* ---- 1. the default network, against the mock NetworkManager and ModemManager ---- */

	static native void runGlibLoop();

	static void mock(String command) throws Exception {
		try (FileOutputStream o = new FileOutputStream(System.getenv("TL_MOCK_CTL"))) {
			o.write((command + "\n").getBytes("UTF-8"));
		}
	}

	/** waits until the active network's capabilities satisfy cond (D-Bus signal, GLib loop, re-observation) */
	interface Cond {
		boolean ok(android.net.NetworkCapabilities nc);
	}

	static android.net.NetworkCapabilities await(android.net.ConnectivityManager cm, Cond cond) throws Exception {
		android.net.NetworkCapabilities nc = null;
		for (int i = 0; i < 50; i++) {
			android.net.Network n = cm.getActiveNetwork();
			nc = n != null ? cm.getNetworkCapabilities(n) : null;
			if (nc != null && cond.ok(nc))
				return nc;
			Thread.sleep(100);
		}
		return nc;
	}

	static final class Recorder extends android.net.ConnectivityManager.NetworkCallback {
		final List<String> events = java.util.Collections.synchronizedList(new ArrayList<String>());
		volatile android.net.NetworkCapabilities last;

		@Override
		public void onAvailable(android.net.Network network) {
			events.add("available");
		}

		@Override
		public void onLost(android.net.Network network) {
			events.add("lost");
		}

		@Override
		public void onCapabilitiesChanged(android.net.Network network, android.net.NetworkCapabilities nc) {
			last = nc;
			events.add("caps:" + (nc.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ? "unmetered" : "metered"));
		}

		boolean awaitEvent(String e) throws InterruptedException {
			for (int i = 0; i < 50; i++) {
				if (events.contains(e))
					return true;
				Thread.sleep(100);
			}
			return false;
		}
	}

	static void networkTests() throws Exception {
		System.load(System.getenv("TL_GLIBLOOP"));
		runGlibLoop();
		final int CELL = android.net.NetworkCapabilities.TRANSPORT_CELLULAR, WIFI = android.net.NetworkCapabilities.TRANSPORT_WIFI,
		          ETH = android.net.NetworkCapabilities.TRANSPORT_ETHERNET;
		final int NOT_METERED = android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED, NOT_ROAMING = android.net.NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING;
		android.net.ConnectivityManager cm = new android.net.ConnectivityManager(appContext());
		android.net.Network n = cm.getActiveNetwork();
		android.net.NetworkCapabilities nc = n != null ? cm.getNetworkCapabilities(n) : null;
		check("the default route's interface is the active network", n != null && nc != null, "" + n);
		if (nc == null)
			return;
		/* an LTE stick in HiLink mode: NetworkManager sees an Ethernet device (cdc_ether) and guesses unmetered */
		check("an LTE stick (cdc_ether, no modem) is cellular", nc.hasTransport(CELL) && !nc.hasTransport(ETH) && !nc.hasTransport(WIFI), nc.toString());
		check("and metered although NetworkManager guesses not", !nc.hasCapability(NOT_METERED) && cm.isActiveNetworkMetered(), nc.toString());
		check("it reaches the internet: INTERNET and VALIDATED", nc.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
		    && nc.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED), nc.toString());
		check("without a radio technology the bandwidth is unspecified", nc.getLinkDownstreamBandwidthKbps() == 0, "" + nc.getLinkDownstreamBandwidthKbps());
		android.net.NetworkInfo info = cm.getActiveNetworkInfo();
		check("the legacy NetworkInfo is TYPE_MOBILE, connected", info != null && info.getType() == android.net.ConnectivityManager.TYPE_MOBILE && info.isConnected(), "" + info);
		check("getNetworkInfo(TYPE_WIFI) is not connected", cm.getNetworkInfo(android.net.ConnectivityManager.TYPE_WIFI) != null && !cm.getNetworkInfo(android.net.ConnectivityManager.TYPE_WIFI).isConnected(), "");

		HandlerThreadHolder h = new HandlerThreadHolder();
		Recorder def = new Recorder();
		cm.registerDefaultNetworkCallback(def, h.handler);
		check("a default network callback hears onAvailable at once", def.awaitEvent("available") && def.awaitEvent("caps:metered"), "" + def.events);
		Recorder unmetered = new Recorder();
		cm.registerNetworkCallback(new android.net.NetworkRequest.Builder().addCapability(NOT_METERED).build(), unmetered, h.handler);
		Thread.sleep(300);
		check("a request for an unmetered network is not satisfied", !unmetered.events.contains("available"), "" + unmetered.events);

		mock("nm_metered 2");
		nc = await(cm, c -> c.hasCapability(NOT_METERED));
		check("NetworkManager says not metered (set on the connection): NOT_METERED", nc != null && nc.hasCapability(NOT_METERED), "" + nc);
		check("the default callback hears the capabilities change", def.awaitEvent("caps:unmetered"), "" + def.events);
		check("the unmetered request becomes available", unmetered.awaitEvent("available"), "" + unmetered.events);

		mock("nm_metered 1");
		nc = await(cm, c -> !c.hasCapability(NOT_METERED));
		check("metered again: NOT_METERED is gone", nc != null && !nc.hasCapability(NOT_METERED), "" + nc);
		check("and the unmetered request loses the network", unmetered.awaitEvent("lost"), "" + unmetered.events);

		/* a ModemManager modem on the interface: LTE, roaming */
		mock("mm_access 16384");
		mock("mm_registration 5");
		mock("modem_port tlwwan0");
		nc = await(cm, c -> c.getLinkDownstreamBandwidthKbps() == 30000 && !c.hasCapability(NOT_ROAMING));
		check("a modem's LTE: AOSP's LTE bandwidth (30000/15000 kbps)", nc != null && nc.getLinkDownstreamBandwidthKbps() == 30000 && nc.getLinkUpstreamBandwidthKbps() == 15000, "" + nc);
		check("roaming: NOT_ROAMING is gone", nc != null && !nc.hasCapability(NOT_ROAMING), "" + nc);
		info = cm.getActiveNetworkInfo();
		check("NetworkInfo: subtype LTE, roaming", info != null && info.getSubtype() == 13 && "LTE".equals(info.getSubtypeName()) && info.isRoaming(), "" + info);
		mock("mm_access 49152"); // LTE + 5GNR: non-standalone 5G
		nc = await(cm, c -> c.getLinkDownstreamBandwidthKbps() == 47000);
		check("5G with LTE: NR_NSA bandwidth (47000 kbps), subtype NR", nc != null && nc.getLinkDownstreamBandwidthKbps() == 47000 && cm.getActiveNetworkInfo().getSubtype() == 20, "" + nc);
		mock("mm_registration 1");
		mock("mm_quit");
		mock("nm_driver e1000e");
		mock("nm_metered 4");
		mock("nm_speed 1000");
		nc = await(cm, c -> c.hasTransport(ETH));
		check("ModemManager gone, a plain Ethernet NIC: ETHERNET", nc != null && nc.hasTransport(ETH) && !nc.hasTransport(CELL), "" + nc);
		check("NetworkManager's guess for Ethernet: not metered", nc != null && nc.hasCapability(NOT_METERED) && nc.hasCapability(NOT_ROAMING), "" + nc);
		check("its link speed (1000 Mb/s)", nc != null && nc.getLinkDownstreamBandwidthKbps() == 1_000_000, "" + nc);
		check("the network stays the same one (same interface)", cm.getActiveNetwork() != null && cm.getActiveNetwork().equals(n), "" + cm.getActiveNetwork());

		mock("wifi_bitrate 300000");
		mock("wifi_strength 80");
		mock("nm_type 2");
		nc = await(cm, c -> c.hasTransport(WIFI) && c.getLinkDownstreamBandwidthKbps() == 300000);
		check("a Wi-Fi device: WIFI with its link rate", nc != null && nc.hasTransport(WIFI) && nc.getLinkDownstreamBandwidthKbps() == 300000, "" + nc);
		check("and its signal in dBm (NetworkManager strength 80 -> -60)", nc != null && nc.getSignalStrength() == -60, "" + (nc != null ? nc.getSignalStrength() : 0));
		check("NetworkInfo is TYPE_WIFI", cm.getActiveNetworkInfo() != null && cm.getActiveNetworkInfo().getType() == android.net.ConnectivityManager.TYPE_WIFI, "");
		mock("nm_metered 3");
		nc = await(cm, c -> !c.hasCapability(NOT_METERED));
		check("a Wi-Fi hotspot NetworkManager guesses metered: metered", nc != null && !nc.hasCapability(NOT_METERED), "" + nc);
		cm.unregisterNetworkCallback(def);
		cm.unregisterNetworkCallback(unmetered);
	}

	static final class HandlerThreadHolder {
		final Handler handler;

		HandlerThreadHolder() {
			android.os.HandlerThread t = new android.os.HandlerThread("callbacks");
			t.start();
			handler = new Handler(t.getLooper());
		}
	}

	public static void main(final String[] args) throws Exception {
		System.loadLibrary("translation_layer_main");
		Looper.prepareMainLooper();
		main = new Handler(Looper.myLooper());
		Thread tests = new Thread(new Runnable() {
			public void run() {
				String phase = args.length > 0 ? args[0] : "main";
				try {
					switch (phase) {
						case "main":
							permissionTests();
							parcelTests();
							keystoreTests();
							storageTests();
							choreographerTests();
							break;
						case "keystore2":
							keystorePersistenceTests();
							break;
						case "stage":
							stageTests();
							break;
						case "moving":
							movingTests();
							break;
						case "idle":
							idleTests();
							break;
						case "network":
							networkTests();
							break;
						default:
							check("known phase " + phase, false, "");
					}
				} catch (Throwable t) {
					t.printStackTrace();
					check("tests ran to the end", false, t.toString());
				}
				System.out.println("RESULT " + passed + "/" + total);
				System.out.flush();
				System.exit(passed == total ? 0 : 1);
			}
		});
		tests.start();
		Looper.loop();
	}
}
