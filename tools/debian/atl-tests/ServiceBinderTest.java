package android.atl;

import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageParser;
import android.content.pm.ServiceInfo;
import android.content.pm.PackageInfo;
import android.content.res.Resources;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.Process;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Checks ATL's service lifecycle (android.atl.ATLActiveServices), Binder's calling identity, the
 * framework package and virtual displays (DisplayManager.createVirtualDisplay, display contexts),
 * outside an app: run by run.sh with dalvikvm against the installed api-impl.jar
 * and framework-res.apk, as a user whose uid is not the system uid. Prints "PASS name" / "FAIL name"
 * lines and "RESULT passed/total".
 */
public class ServiceBinderTest {
	static final List<String> events = Collections.synchronizedList(new ArrayList<String>());
	static int passed, total;
	static Handler main;

	/** the service under test: records its lifecycle; onUnbind asks for onRebind */
	public static class TestService extends Service {
		static volatile TestService current;
		final Binder binderA = new Binder("A");
		final Binder binderB = new Binder("B");

		@Override
		public void onCreate() {
			super.onCreate();
			current = this;
			events.add("create");
		}

		@Override
		public int onStartCommand(Intent intent, int flags, int startId) {
			events.add("start:" + startId);
			return START_NOT_STICKY;
		}

		@Override
		public IBinder onBind(Intent intent) {
			events.add("bind:" + intent.getAction());
			return "B".equals(intent.getAction()) ? binderB : binderA;
		}

		@Override
		public boolean onUnbind(Intent intent) {
			events.add("unbind:" + intent.getAction());
			return true;
		}

		@Override
		public void onRebind(Intent intent) {
			events.add("rebind:" + intent.getAction());
		}

		@Override
		public void onDestroy() {
			events.add("destroy");
			super.onDestroy();
		}
	}

	static class Conn implements ServiceConnection {
		final String name;
		volatile IBinder binder;

		Conn(String name) {
			this.name = name;
		}

		public void onServiceConnected(ComponentName n, IBinder b) {
			binder = b;
			events.add(name + ".connected:" + ((Binder)b).getInterfaceDescriptor());
		}

		public void onServiceDisconnected(ComponentName n) {
			events.add(name + ".disconnected");
		}

		public void onBindingDied(ComponentName n) {
			events.add(name + ".died");
		}

		public void onNullBinding(ComponentName n) {
			events.add(name + ".null");
		}
	}

	static void check(String name, boolean ok, String detail) {
		total++;
		if (ok)
			passed++;
		System.out.println((ok ? "PASS " : "FAIL ") + name + (ok ? "" : "  " + detail));
	}

	/** the events the main thread produced since the last call, after it ran everything queued */
	static String drain() throws InterruptedException {
		for (int i = 0; i < 3; i++) { /* callbacks post further callbacks */
			final CountDownLatch done = new CountDownLatch(1);
			main.post(new Runnable() {
				public void run() {
					done.countDown();
				}
			});
			done.await(10, TimeUnit.SECONDS);
		}
		synchronized (events) {
			String s = String.join(" ", events);
			events.clear();
			return s;
		}
	}

	static void expect(String name, String want) throws InterruptedException {
		String got = drain();
		check(name, got.equals(want), "want [" + want + "] got [" + got + "]");
	}

	static void setField(Object o, Class<?> c, String field, Object value) throws Exception {
		Field f = c.getDeclaredField(field);
		f.setAccessible(true);
		f.set(o, value);
	}

	/** an app "test.services" whose one service answers the actions A and B */
	static ATLLoadedApp makeApp() throws Exception {
		PackageParser.Package pkg = new PackageParser.Package("test.services");
		pkg.applicationInfo.targetSdkVersion = 32;
		Field unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("THE_ONE");
		unsafeField.setAccessible(true);
		Object unsafe = unsafeField.get(null);
		PackageParser.Service s = (PackageParser.Service)unsafe.getClass().getMethod("allocateInstance", Class.class).invoke(unsafe, PackageParser.Service.class);
		ServiceInfo info = new ServiceInfo();
		info.name = TestService.class.getName();
		info.packageName = pkg.packageName;
		info.applicationInfo = pkg.applicationInfo;
		ArrayList<PackageParser.ServiceIntentInfo> intents = new ArrayList<>();
		for (String action : new String[] {"A", "B"}) {
			PackageParser.ServiceIntentInfo ii = new PackageParser.ServiceIntentInfo(s);
			ii.addAction(action);
			intents.add(ii);
		}
		setField(s, PackageParser.Component.class, "owner", pkg);
		setField(s, PackageParser.Component.class, "intents", intents);
		setField(s, PackageParser.Component.class, "className", TestService.class.getName());
		setField(s, PackageParser.Service.class, "info", info);
		pkg.services.add(s);
		return new ATLLoadedApp(Resources.getSystem(), ServiceBinderTest.class.getClassLoader(), pkg);
	}

	static Intent intent(String action) {
		Intent i = new Intent(action);
		i.setPackage("test.services");
		return i;
	}

	static void serviceTests() throws Exception {
		ATLLoadedApp app = makeApp();
		Conn c1 = new Conn("c1"), c2 = new Conn("c2"), c3 = new Conn("c3"), cb = new Conn("cB"), c4 = new Conn("c4");
		int AUTO = Context.BIND_AUTO_CREATE;

		ATLActiveServices.bindService(app, intent("A"), c1, AUTO);
		expect("bind creates the service and calls onBind once", "create bind:A c1.connected:A");
		Intent a2 = intent("A");
		a2.putExtra("extras-do-not-count", 1);
		ATLActiveServices.bindService(app, a2, c2, AUTO);
		expect("an equal intent gets the cached binder without onBind", "c2.connected:A");
		ATLActiveServices.bindService(app, intent("B"), cb, AUTO);
		expect("a different intent gets its own onBind", "bind:B cB.connected:B");
		ATLActiveServices.unbindService(c1);
		expect("unbinding one of two connections of an intent calls nothing", "");
		ATLActiveServices.unbindService(c2);
		expect("the last connection of an intent calls onUnbind", "unbind:A");
		ATLActiveServices.bindService(app, intent("A"), c3, AUTO);
		expect("after onUnbind returned true, a bind gets the binder and onRebind", "c3.connected:A rebind:A");
		ATLActiveServices.startService(app, intent("A"));
		ATLActiveServices.startService(app, intent("A"));
		expect("starts get increasing start ids", "start:1 start:2");
		TestService svc = TestService.current;
		check("stopSelfResult with an old start id keeps the service", !svc.stopSelfResult(1), "returned true");
		ATLActiveServices.unbindService(c3);
		ATLActiveServices.unbindService(cb);
		expect("a started service survives its last unbind", "unbind:A unbind:B");
		check("stopSelfResult with the latest start id stops", svc.stopSelfResult(2), "returned false");
		expect("a stopped, unbound service is destroyed", "destroy");

		ATLActiveServices.bindService(app, intent("A"), c4, 0);
		expect("a bind without BIND_AUTO_CREATE does not create the service", "");
		ATLActiveServices.startService(app, intent("A"));
		expect("starting the service connects the waiting binding", "create bind:A c4.connected:A start:1");
		ATLActiveServices.stopService(app, intent("A"));
		expect("stopService destroys it; the remaining connection is told", "c4.disconnected c4.died unbind:A destroy");
		ATLActiveServices.unbindService(c4);
		expect("unbinding a dead connection is allowed", "");
		boolean threw = false;
		try {
			ATLActiveServices.unbindService(new Conn("never"));
		} catch (IllegalArgumentException e) {
			threw = true;
		}
		check("unbinding an unknown connection throws IllegalArgumentException", threw, "no exception");

		Conn c5 = new Conn("c5");
		ATLActiveServices.bindService(app, intent("A"), c5, AUTO);
		ATLActiveServices.startService(app, intent("A"));
		drain();
		ATLActiveServices.stopService(app, intent("A"));
		expect("stopService keeps a service bound with BIND_AUTO_CREATE", "");
		ATLActiveServices.unbindService(c5);
		expect("its last unbind then destroys it", "unbind:A destroy");
	}

	/** a binder that records the calling identity it sees */
	static class Probe extends Binder {
		volatile int uid = -1, nestedUid = -1, clearedUid = -1, restoredUid = -1;
		volatile boolean incoming;
		Binder nested;

		@Override
		protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
			uid = Binder.getCallingUid();
			incoming = Binder.isDirectlyHandlingTransaction();
			if (nested != null) {
				Parcel d = Parcel.obtain();
				try {
					nested.transact(1, d, null, 0);
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
				d.recycle();
				nestedUid = ((Probe)nested).uid;
			}
			long token = Binder.clearCallingIdentity();
			clearedUid = Binder.getCallingUid();
			Binder.restoreCallingIdentity(token);
			restoredUid = Binder.getCallingUid();
			return true;
		}
	}

	static void call(Binder b) throws Exception {
		Parcel d = Parcel.obtain();
		b.transact(1, d, null, 0);
		d.recycle();
	}

	static void identityTests() throws Exception {
		final int me = Process.myUid();
		check("the test runs as an app uid other than the system uid", me != Process.SYSTEM_UID, "uid " + me);
		check("outside a transaction the calling uid is this app's", Binder.getCallingUid() == me, "got " + Binder.getCallingUid());
		final Probe appBinder = new Probe();
		final Probe appNested = new Probe();
		appBinder.nested = appNested;
		final Probe[] platformBinder = new Probe[1];
		final int[] platformOutside = new int[1];
		Thread platform = new Thread(new Runnable() {
			public void run() {
				Binder.setThreadActingUid(Process.SYSTEM_UID);
				platformOutside[0] = Binder.getCallingUid();
				platformBinder[0] = new Probe();
				try {
					call(appBinder);
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
			}
		});
		platform.start();
		platform.join();
		check("a platform thread's own calling uid is the system uid", platformOutside[0] == Process.SYSTEM_UID, "got " + platformOutside[0]);
		check("a call from the platform reaches the app as the system uid", appBinder.uid == Process.SYSTEM_UID, "got " + appBinder.uid);
		check("the app sees it as an incoming transaction", appBinder.incoming, "not incoming");
		check("a local call inside it keeps the caller's identity", appNested.uid == Process.SYSTEM_UID, "got " + appNested.uid);
		check("the nested local call runs inside the incoming transaction", appNested.incoming, "not incoming");
		check("clearCallingIdentity gives the app's own uid", appBinder.clearedUid == me, "got " + appBinder.clearedUid);
		check("restoreCallingIdentity gives the caller back", appBinder.restoredUid == Process.SYSTEM_UID, "got " + appBinder.restoredUid);
		call(platformBinder[0]);
		check("a call from the app reaches a platform binder as the app's uid", platformBinder[0].uid == me, "got " + platformBinder[0].uid);
		check("after the transactions the app thread's calling uid is its own again", Binder.getCallingUid() == me, "got " + Binder.getCallingUid());
		Probe local = new Probe();
		call(local);
		check("an app-to-app call is local (no identity change)", local.uid == me && !local.incoming, "uid " + local.uid + " incoming " + local.incoming);
	}

	static void frameworkPackageTests() throws Exception {
		/* without an app, the runtime's primary application is the framework package itself; Context's
		 * initialisation sets that up, as android-translation-layer does before anything else */
		Class.forName("android.content.Context", true, ServiceBinderTest.class.getClassLoader());
		PackageParser.Package sys = ATLLoadedApp.getSystemApplication().pkg;
		check("the framework package is \"android\"", "android".equals(sys.packageName), sys.packageName);
		check("it runs as the system uid", sys.applicationInfo.uid == Process.SYSTEM_UID, "uid " + sys.applicationInfo.uid);
		check("it is a system package", (sys.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0, "flags " + sys.applicationInfo.flags);
		check("it is signed (framework-res.apk's platform signature)", sys.mSignatures != null && sys.mSignatures.length == 1,
		      "signatures " + (sys.mSignatures == null ? "null" : sys.mSignatures.length));
		PackageManager pm = new PackageManager();
		PackageInfo pi = pm.getPackageInfo("android", PackageManager.GET_SIGNATURES | PackageManager.GET_SIGNING_CERTIFICATES);
		check("getPackageInfo(android) has the application info with the system uid", pi.applicationInfo != null && pi.applicationInfo.uid == Process.SYSTEM_UID, "" + pi.applicationInfo);
		check("getPackageInfo(android) has signingInfo", pi.signingInfo != null && pi.signingInfo.getSigningCertificateHistory().length == 1, "" + pi.signingInfo);
		check("getPackageInfo(android) has signatures", pi.signatures != null && pi.signatures.length == 1, "none");
		String[] pkgs = pm.getPackagesForUid(Process.SYSTEM_UID);
		check("getPackagesForUid(1000) is {android}", pkgs != null && pkgs.length == 1 && "android".equals(pkgs[0]), pkgs == null ? "null" : String.join(",", pkgs));
		check("getNameForUid(1000) is android.uid.system:1000", "android.uid.system:1000".equals(pm.getNameForUid(Process.SYSTEM_UID)), pm.getNameForUid(Process.SYSTEM_UID));
		check("checkSignatures(android, android) matches", pm.checkSignatures("android", "android") == PackageManager.SIGNATURE_MATCH, "" + pm.checkSignatures("android", "android"));
	}

	static void virtualDisplayTests() throws Exception {
		android.hardware.display.DisplayManager dm = new android.hardware.display.DisplayManager();
		final List<String> displayEvents = Collections.synchronizedList(new ArrayList<String>());
		dm.registerDisplayListener(new android.hardware.display.DisplayManager.DisplayListener() {
			public void onDisplayAdded(int id) { displayEvents.add("added:" + id); }
			public void onDisplayRemoved(int id) { displayEvents.add("removed:" + id); }
			public void onDisplayChanged(int id) { displayEvents.add("changed:" + id); }
		}, main);
		android.hardware.display.VirtualDisplay vd = dm.createVirtualDisplay("CarAppService", 800, 480, 160, null,
		    android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY);
		android.view.Display d = vd.getDisplay();
		drain();
		int id = d.getDisplayId();
		check("a virtual display gets a new id and is announced", id > 0 && displayEvents.contains("added:" + id), "id " + id + " events " + displayEvents);
		check("it has its size", d.getWidth() == 800 && d.getHeight() == 480, d.toString());
		check("without a surface it is off", d.getState() == android.view.Display.STATE_OFF, "state " + d.getState());
		check("without VIRTUAL_DISPLAY_FLAG_PUBLIC it is private", (d.getFlags() & android.view.Display.FLAG_PRIVATE) != 0, "flags " + d.getFlags());
		check("getDisplay(id) finds it", dm.getDisplay(id) == d, "" + dm.getDisplay(id));
		check("getDisplays() lists it after the default display", dm.getDisplays().length == 2 && dm.getDisplays()[1] == d, "" + dm.getDisplays().length);
		Context base = new android.app.ContextImpl(Resources.getSystem(), ATLLoadedApp.getSystemApplication(), 0);
		Context dc = base.createDisplayContext(d);
		android.util.DisplayMetrics m = dc.getResources().getDisplayMetrics();
		check("a display context has the display's metrics", m.widthPixels == 800 && m.heightPixels == 480 && m.densityDpi == 160,
		      m.widthPixels + "x" + m.heightPixels + "@" + m.densityDpi);
		android.content.res.Configuration c = dc.getResources().getConfiguration();
		check("and the display's configuration", c.screenWidthDp == 800 && c.screenHeightDp == 480 && c.densityDpi == 160,
		      c.screenWidthDp + "x" + c.screenHeightDp + "dp @" + c.densityDpi);
		check("and its display", dc.getDisplay() == d, "" + dc.getDisplay());
		check("a configuration context of it stays on the display", dc.createConfigurationContext(new android.content.res.Configuration()).getDisplay() == d, "other display");
		vd.release();
		drain();
		check("release removes it", !d.isValid() && dm.getDisplay(id) == null && displayEvents.contains("removed:" + id), "events " + displayEvents);
	}

	public static void main(String[] args) throws Exception {
		/* api-impl's natives; android-translation-layer has them linked in */
		System.loadLibrary("translation_layer_main");
		Looper.prepareMainLooper();
		main = new Handler(Looper.myLooper());
		Thread tests = new Thread(new Runnable() {
			public void run() {
				try {
					identityTests();
					frameworkPackageTests();
					serviceTests();
					virtualDisplayTests();
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
