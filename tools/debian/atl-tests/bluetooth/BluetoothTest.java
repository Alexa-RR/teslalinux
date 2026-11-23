package org.teslalinux.bttest;

import android.app.ActivityThread;
import android.app.Application;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.SystemClock;

import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * ATL's Bluetooth LE scanning over BlueZ (android.bluetooth, TlBluez), against mock-bluez on a
 * private system bus: the adapter (feature, service, state, name, address), LE scans (filters,
 * ALL_MATCHES, FIRST_MATCH/MATCH_LOST, report delay batches, the legacy startLeScan, the
 * already-started, unsupported-combination and too-frequent errors), the discovery session the
 * scans hold (SetDiscoveryFilter le + DuplicateData, StartDiscovery, StopDiscovery), opportunistic
 * scans, the adapter coming and going (power, removal, bluetoothd restarts) with ACTION_STATE_CHANGED,
 * remote devices (name, type, bond state, UUIDs), AOSP's location rules for scan results, and the
 * host switch ATL_BLUETOOTH=off. The phase "live" scans a real adapter instead (the Pi) and prints
 * the Eddystone frames it hears.
 * run.sh runs the phases in separate processes; prints PASS/FAIL and "RESULT passed/total".
 */
public class BluetoothTest {
	static int passed, total;
	static final String PKG = "org.teslalinux.bttest";
	static final ParcelUuid EDDYSTONE = ParcelUuid.fromString("0000FEAA-0000-1000-8000-00805F9B34FB");
	static final String FEAA = "0000feaa-0000-1000-8000-00805f9b34fb";
	/* an Eddystone-UID frame: type 0, TX power at 0 m -18 dBm, namespace (10 bytes), instance (6), RFU (2) */
	static final String UID_FRAME = "00EE" + "EDE5A7B1986E2BE4CA5A" + "010203040506" + "0000";
	/* an Eddystone-TLM frame: type 0x20, version 0, 3000 mV, 25.0 C, 16 advertisements, 3.2 s */
	static final String TLM_FRAME = "2000" + "0BB8" + "1900" + "00000010" + "00000020";
	static final String B1 = "11:22:33:44:55:66", B2 = "22:33:44:55:66:77";

	static void check(String name, boolean ok, String detail) {
		total++;
		if (ok)
			passed++;
		System.out.println((ok ? "PASS " : "FAIL ") + name + (!ok && detail != null ? " (" + detail + ")" : ""));
	}

	static void mock(String command) throws Exception {
		try (FileOutputStream o = new FileOutputStream(System.getenv("TL_MOCK_CTL"))) {
			o.write((command + "\n").getBytes(StandardCharsets.UTF_8));
		}
		/* the mock answers "ok COMMAND" once it emitted the signals */
		awaitMockLine("ok " + command, 3000);
	}

	static List<String> mockLines() throws Exception {
		return Files.readAllLines(Paths.get(System.getenv("TL_MOCK_OUT")), StandardCharsets.UTF_8);
	}

	static int mockCount(String line) throws Exception {
		int n = 0;
		for (String l : mockLines())
			if (l.equals(line))
				n++;
		return n;
	}

	/** how many discovery sessions the mock started */
	static int startCount() {
		try {
			return mockCount("call StartDiscovery ok");
		} catch (Exception e) {
			return -1;
		}
	}

	static boolean awaitMockLine(String line, long ms) throws Exception {
		long end = SystemClock.uptimeMillis() + ms;
		while (SystemClock.uptimeMillis() < end) {
			if (mockLines().contains(line))
				return true;
			Thread.sleep(20);
		}
		return false;
	}

	interface Cond {
		boolean ok();
	}

	static boolean await(Cond c, long ms) throws InterruptedException {
		long end = SystemClock.uptimeMillis() + ms;
		while (SystemClock.uptimeMillis() < end) {
			if (c.ok())
				return true;
			Thread.sleep(20);
		}
		return c.ok();
	}

	static String hex(byte[] b) {
		if (b == null)
			return "null";
		StringBuilder sb = new StringBuilder();
		for (byte x : b)
			sb.append(String.format("%02X", x & 0xff));
		return sb.toString();
	}

	static final class Recorder extends ScanCallback {
		final List<ScanResult> results = Collections.synchronizedList(new ArrayList<ScanResult>());
		final List<Integer> types = Collections.synchronizedList(new ArrayList<Integer>());
		final List<List<ScanResult>> batches = Collections.synchronizedList(new ArrayList<List<ScanResult>>());
		final List<Integer> errors = Collections.synchronizedList(new ArrayList<Integer>());
		volatile boolean onMainThread = true;

		@Override
		public void onScanResult(int callbackType, ScanResult result) {
			if (Looper.myLooper() != Looper.getMainLooper())
				onMainThread = false;
			types.add(callbackType);
			results.add(result);
		}

		@Override
		public void onBatchScanResults(List<ScanResult> list) {
			batches.add(new ArrayList<>(list));
		}

		@Override
		public void onScanFailed(int errorCode) {
			errors.add(errorCode);
		}

		ScanResult last() {
			synchronized (results) {
				return results.isEmpty() ? null : results.get(results.size() - 1);
			}
		}

		boolean has(final String address, final Cond extra) {
			synchronized (results) {
				for (ScanResult r : results)
					if (r.getDevice().getAddress().equals(address))
						return true;
			}
			return false;
		}

		int countType(int t) {
			int n = 0;
			synchronized (types) {
				for (int x : types)
					if (x == t)
						n++;
			}
			return n;
		}
	}

	static ScanResult find(Recorder r, String address, byte frameType) {
		synchronized (r.results) {
			for (ScanResult s : r.results) {
				if (!s.getDevice().getAddress().equals(address) || s.getScanRecord() == null)
					continue;
				byte[] sd = s.getScanRecord().getServiceData(EDDYSTONE);
				if (frameType < 0 || (sd != null && sd.length > 0 && sd[0] == frameType))
					return s;
			}
		}
		return null;
	}

	static ScanResult findManufacturer(Recorder r, String address, int company) {
		synchronized (r.results) {
			for (ScanResult s : r.results)
				if (s.getDevice().getAddress().equals(address) && s.getScanRecord() != null
				    && s.getScanRecord().getManufacturerSpecificData(company) != null)
					return s;
		}
		return null;
	}

	static Context app() {
		return ActivityThread.currentApplication();
	}

	static final ScanSettings LOW_LATENCY = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();

	static List<ScanFilter> eddystoneFilter() {
		List<ScanFilter> f = new ArrayList<>();
		f.add(new ScanFilter.Builder().setServiceUuid(EDDYSTONE).build());
		return f;
	}

	/* ---- bt1: the adapter, scans, filters, callback types, errors, devices ---- */

	static void scanTests() throws Exception {
		Context ctx = app();
		PackageManager pm = ctx.getPackageManager();
		check("FEATURE_BLUETOOTH and FEATURE_BLUETOOTH_LE with BlueZ on the bus",
		      pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH) && pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE), "");
		BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
		check("getSystemService(BluetoothManager) is a manager", bm != null, "null");
		BluetoothAdapter adapter = bm.getAdapter();
		check("the manager's adapter is the default adapter", adapter != null && adapter == BluetoothAdapter.getDefaultAdapter(), "" + adapter);
		check("the adapter is on (BlueZ's Powered)", adapter.isEnabled() && adapter.getState() == BluetoothAdapter.STATE_ON, "" + adapter.getState());
		check("getName is BlueZ's Alias", "tl-mock".equals(adapter.getName()), adapter.getName());
		check("getAddress is AOSP's 02:00:00:00:00:00 without LOCAL_MAC_ADDRESS", "02:00:00:00:00:00".equals(adapter.getAddress()), adapter.getAddress());
		check("filters and batching are supported while on", adapter.isOffloadedFilteringSupported() && adapter.isOffloadedScanBatchingSupported(), "");
		check("the legacy advertising data length is 31", adapter.getLeMaximumAdvertisingDataLength() == 31, "" + adapter.getLeMaximumAdvertisingDataLength());
		check("enable() is false for an Android 13+ target (this APK targets 31: already on, true)", adapter.enable(), "false");
		BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();
		check("getBluetoothLeScanner while on, the same object each time", scanner != null && scanner == adapter.getBluetoothLeScanner(), "" + scanner);

		/* 1: a filtered scan, as Waze starts it */
		Recorder filtered = new Recorder();
		scanner.startScan(eddystoneFilter(), LOW_LATENCY, filtered);
		check("a scan sets BlueZ's discovery filter: LE, every advertisement, the scan's service UUID",
		      awaitMockLine("call SetDiscoveryFilter transport=le duplicate=1 uuids=" + FEAA, 3000), "" + mockLines());
		check("and starts discovery", awaitMockLine("call StartDiscovery ok", 3000), "" + mockLines());

		/* 2: an unfiltered scan joins the session */
		Recorder all = new Recorder();
		scanner.startScan(null, new ScanSettings.Builder().build(), all);
		check("an unfiltered scan drops the UUID filter of the running session",
		      awaitMockLine("call SetDiscoveryFilter transport=le duplicate=1 uuids=-", 3000), "" + mockLines());
		Thread.sleep(200);
		check("a second scan starts no second session", mockCount("call StartDiscovery ok") == 1 && mockCount("call StartDiscovery InProgress") == 0, "" + mockLines());

		mock("found " + B1 + " random -60 Beacon1");
		check("a device found (InterfacesAdded) reaches the unfiltered scan", await(() -> find(all, B1, (byte) -1) != null, 2000), "" + all.results);
		ScanResult first = find(all, B1, (byte) -1);
		check("its name and flags are in the scan record", first != null && "Beacon1".equals(first.getScanRecord().getDeviceName())
		      && first.getScanRecord().getAdvertiseFlags() == 6, first == null ? "" : first.getScanRecord().toString());
		check("its RSSI", first != null && first.getRssi() == -60, first == null ? "" : "" + first.getRssi());
		Thread.sleep(200);
		check("the Eddystone filter keeps it from the filtered scan", filtered.results.isEmpty(), "" + filtered.results);
		/* an Eddystone beacon lists its service (AD 0x03, FEAA) besides the service data: BlueZ's UUIDs */
		mock("uuids " + B1 + " " + FEAA);

		long before = SystemClock.elapsedRealtimeNanos();
		mock("sd " + B1 + " -55 " + FEAA + " " + UID_FRAME);
		check("an Eddystone-UID advertisement reaches the filtered scan", await(() -> find(filtered, B1, (byte) 0) != null, 2000), "" + filtered.results);
		ScanResult uid = find(filtered, B1, (byte) 0);
		long after = SystemClock.elapsedRealtimeNanos();
		check("with its service data, byte for byte", uid != null && UID_FRAME.equals(hex(uid.getScanRecord().getServiceData(EDDYSTONE))),
		      uid == null ? "" : hex(uid.getScanRecord().getServiceData(EDDYSTONE)));
		check("with this advertisement's RSSI", uid != null && uid.getRssi() == -55, uid == null ? "" : "" + uid.getRssi());
		check("the record lists the advertised service UUID (what ScanFilter.setServiceUuid matches)",
		      uid != null && uid.getScanRecord().getServiceUuids() != null && uid.getScanRecord().getServiceUuids().contains(EDDYSTONE)
		      && hex(uid.getScanRecord().getBytes()).contains("0303AAFE"), uid == null ? "" : "" + uid.getScanRecord().getServiceUuids());
		check("the raw record holds the 16-bit service data structure (len 0x17, 0x16, AA FE)",
		      uid != null && hex(uid.getScanRecord().getBytes()).contains("1716AAFE" + UID_FRAME), uid == null ? "" : hex(uid.getScanRecord().getBytes()));
		check("its timestamp is SystemClock.elapsedRealtimeNanos at arrival",
		      uid != null && uid.getTimestampNanos() >= before && uid.getTimestampNanos() <= after, uid == null ? "" : before + " " + uid.getTimestampNanos() + " " + after);
		check("the device has its LE address type and address", uid != null && uid.getDevice().getAddressType() == BluetoothDevice.ADDRESS_TYPE_RANDOM
		      && B1.equals(uid.getDevice().getAddress()), uid == null ? "" : "" + uid.getDevice().getAddressType());
		check("a legacy 1M advertisement", uid != null && uid.isLegacy() && uid.getPrimaryPhy() == BluetoothDevice.PHY_LE_1M, "");
		check("callbacks run on the main thread, CALLBACK_TYPE_ALL_MATCHES", filtered.onMainThread && filtered.countType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES) == filtered.results.size(), "");
		check("the unfiltered scan has it too", await(() -> find(all, B1, (byte) 0) != null, 2000), "");

		mock("sd " + B1 + " -57 " + FEAA + " " + TLM_FRAME);
		check("an Eddystone-TLM frame of the same beacon follows", await(() -> find(filtered, B1, (byte) 0x20) != null, 2000), "" + filtered.results);
		ScanResult tlm = find(filtered, B1, (byte) 0x20);
		check("as its service data (the UID frame replaced)", tlm != null && TLM_FRAME.equals(hex(tlm.getScanRecord().getServiceData(EDDYSTONE))), "");

		mock("found " + B2 + " public -70");
		mock("md " + B2 + " -71 0x004C 0215F7826DA64FA24E988024BC5B71E0893E00010002C5");
		check("manufacturer data reaches the unfiltered scan", await(() -> findManufacturer(all, B2, 0x004C) != null, 2000), "" + all.results);
		ScanResult ib = findManufacturer(all, B2, 0x004C);
		check("as AOSP's manufacturer specific data (company id 0x004C, then the data)",
		      ib != null && "0215F7826DA64FA24E988024BC5B71E0893E00010002C5".equals(hex(ib.getScanRecord().getManufacturerSpecificData(0x004C))), "");
		Thread.sleep(200);
		check("and not the Eddystone scan", find(filtered, B2, (byte) -1) == null, "");

		/* errors */
		Recorder again = filtered;
		scanner.startScan(eddystoneFilter(), LOW_LATENCY, again);
		check("the same callback again: SCAN_FAILED_ALREADY_STARTED", await(() -> again.errors.contains(ScanCallback.SCAN_FAILED_ALREADY_STARTED), 2000), "" + again.errors);
		final Recorder bad = new Recorder();
		scanner.startScan(null, new ScanSettings.Builder().setCallbackType(ScanSettings.CALLBACK_TYPE_FIRST_MATCH).build(), bad);
		check("FIRST_MATCH without filters: SCAN_FAILED_FEATURE_UNSUPPORTED", await(() -> bad.errors.contains(ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED), 2000), "" + bad.errors);

		/* 3: FIRST_MATCH and MATCH_LOST */
		final Recorder firstLost = new Recorder();
		List<ScanFilter> byAddress = new ArrayList<>();
		byAddress.add(new ScanFilter.Builder().setDeviceAddress(B1).build());
		scanner.startScan(byAddress, new ScanSettings.Builder().setCallbackType(ScanSettings.CALLBACK_TYPE_FIRST_MATCH | ScanSettings.CALLBACK_TYPE_MATCH_LOST)
		                                       .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE).build(), firstLost);
		mock("sd " + B1 + " -54 " + FEAA + " " + UID_FRAME);
		mock("sd " + B1 + " -53 " + FEAA + " " + UID_FRAME);
		check("FIRST_MATCH once for the device", await(() -> firstLost.countType(ScanSettings.CALLBACK_TYPE_FIRST_MATCH) == 1, 2000)
		      && firstLost.countType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES) == 0, "" + firstLost.types);
		check("MATCH_LOST after a second without it (aggressive)", await(() -> firstLost.countType(ScanSettings.CALLBACK_TYPE_MATCH_LOST) == 1, 3000), "" + firstLost.types);

		/* 4: a report delay batches */
		final Recorder batched = new Recorder();
		scanner.startScan(eddystoneFilter(), new ScanSettings.Builder().setReportDelay(500).build(), batched);
		for (int i = 0; i < 3; i++)
			mock("sd " + B1 + " -5" + i + " " + FEAA + " " + UID_FRAME);
		check("a report delay delivers onBatchScanResults with the advertisements",
		      await(() -> !batched.batches.isEmpty(), 2000) && batched.batches.get(0).size() == 3 && batched.results.isEmpty(), "" + batched.batches);

		/* 5: the legacy API */
		final List<String> legacy = Collections.synchronizedList(new ArrayList<String>());
		BluetoothAdapter.LeScanCallback leCb = (device, rssi, record) -> legacy.add(device.getAddress() + " " + rssi + " " + hex(record));
		check("startLeScan starts", adapter.startLeScan(leCb), "false");
		mock("sd " + B1 + " -50 " + FEAA + " " + UID_FRAME);
		check("onLeScan has the device, RSSI and raw record", await(() -> {
			synchronized (legacy) {
				for (String s : legacy)
					if (s.startsWith(B1 + " -50 ") && s.contains("1716AAFE" + UID_FRAME))
						return true;
			}
			return false;
		}, 2000), "" + legacy);
		adapter.stopLeScan(leCb);

		/* 6: AOSP: more than 5 starts in 30 s */
		final Recorder sixth = new Recorder();
		scanner.startScan(null, new ScanSettings.Builder().build(), sixth);
		check("a sixth start within 30 s: SCAN_FAILED_SCANNING_TOO_FREQUENTLY", await(() -> sixth.errors.contains(ScanCallback.SCAN_FAILED_SCANNING_TOO_FREQUENTLY), 2000), "" + sixth.errors);

		scanner.stopScan(filtered);
		scanner.stopScan(firstLost);
		scanner.stopScan(batched);
		Thread.sleep(200);
		check("the session stays while a scan runs", mockCount("call StopDiscovery ok") == 0, "" + mockLines());
		scanner.stopScan(all);
		check("the last stop ends the session (StopDiscovery)", awaitMockLine("call StopDiscovery ok", 3000), "" + mockLines());
		int n = filtered.results.size();
		mock("sd " + B1 + " -50 " + FEAA + " " + UID_FRAME);
		Thread.sleep(300);
		check("a stopped scan gets nothing more", filtered.results.size() == n, "");

		/* remote devices */
		BluetoothDevice d = adapter.getRemoteDevice(B1);
		check("a remote device's name and type (LE: random address, advertising flags)",
		      "Beacon1".equals(d.getName()) && d.getType() == BluetoothDevice.DEVICE_TYPE_LE, d.getName() + " " + d.getType());
		check("not bonded", d.getBondState() == BluetoothDevice.BOND_NONE && !adapter.getBondedDevices().contains(d), "");
		mock("bond " + B1);
		mock("uuids " + B1 + " " + FEAA + ",0000180f-0000-1000-8000-00805f9b34fb");
		check("bonded once BlueZ says so, and in getBondedDevices", await(() -> d.getBondState() == BluetoothDevice.BOND_BONDED, 2000)
		      && adapter.getBondedDevices().contains(d), "");
		check("its UUIDs", d.getUuids() != null && Arrays.asList(d.getUuids()).contains(EDDYSTONE), "" + Arrays.toString(d.getUuids()));
		check("an unknown device: no name, DEVICE_TYPE_UNKNOWN", adapter.getRemoteDevice("01:02:03:04:05:06").getName() == null
		      && adapter.getRemoteDevice("01:02:03:04:05:06").getType() == BluetoothDevice.DEVICE_TYPE_UNKNOWN, "");
		PackageInfo pi = ctx.getPackageManager().getPackageInfo(PKG, PackageManager.GET_PERMISSIONS);
		boolean flagged = false;
		for (int i = 0; i < pi.requestedPermissions.length; i++)
			if (pi.requestedPermissions[i].equals("android.permission.BLUETOOTH_SCAN"))
				flagged = (pi.requestedPermissionsFlags[i] & PackageInfo.REQUESTED_PERMISSION_NEVER_FOR_LOCATION) != 0;
		check("BLUETOOTH_SCAN without neverForLocation: no REQUESTED_PERMISSION_NEVER_FOR_LOCATION", !flagged, "flagged");
	}

	/* ---- bt2: opportunistic scans, the adapter coming and going ---- */

	static final List<String> states = Collections.synchronizedList(new ArrayList<String>());

	static void stateTests() throws Exception {
		Context ctx = app();
		ctx.registerReceiver(new BroadcastReceiver() {
			@Override
			public void onReceive(Context context, Intent intent) {
				states.add(intent.getIntExtra(BluetoothAdapter.EXTRA_PREVIOUS_STATE, -1) + ">" + intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1));
			}
		}, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));
		BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
		BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();
		check("on at the start", adapter.isEnabled() && scanner != null, "");

		final Recorder opp = new Recorder();
		scanner.startScan(null, new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_OPPORTUNISTIC).build(), opp);
		Thread.sleep(300);
		check("an opportunistic scan starts no discovery", mockCount("call StartDiscovery ok") == 0, "" + mockLines());
		mock("found " + B1 + " random -61 Beacon1");
		mock("sd " + B1 + " -62 " + FEAA + " " + UID_FRAME);
		check("and sees the advertisements of other scans", await(() -> find(opp, B1, (byte) 0) != null, 2000), "" + opp.results);
		final Recorder regular = new Recorder();
		scanner.startScan(null, new ScanSettings.Builder().build(), regular);
		check("a regular scan beside it starts discovery", awaitMockLine("call StartDiscovery ok", 3000), "");
		scanner.stopScan(regular);
		check("stopping it ends discovery; the opportunistic one needs none", awaitMockLine("call StopDiscovery ok", 3000), "");
		scanner.stopScan(opp);

		final Recorder running = new Recorder();
		scanner.startScan(null, new ScanSettings.Builder().build(), running);
		check("a scan runs before the power cycle", await(() -> startCount() == 2, 3000), "" + mockLines());
		states.clear();
		mock("powered 0");
		check("powered off: STATE_OFF", await(() -> adapter.getState() == BluetoothAdapter.STATE_OFF, 2000) && !adapter.isEnabled(), "" + adapter.getState());
		check("ACTION_STATE_CHANGED ON>TURNING_OFF, TURNING_OFF>OFF", await(() -> states.size() >= 2, 2000)
		      && states.get(0).equals("12>13") && states.get(1).equals("13>10"), "" + states);
		check("no scanner while off", adapter.getBluetoothLeScanner() == null, "");
		boolean threw = false;
		try {
			scanner.startScan(new Recorder());
		} catch (IllegalStateException e) {
			threw = "BT Adapter is not turned ON".equals(e.getMessage());
		}
		check("startScan while off: IllegalStateException (BT Adapter is not turned ON)", threw, "");
		check("getName is null while off", adapter.getName() == null, adapter.getName());
		states.clear();
		mock("powered 1");
		check("powered on: STATE_ON with OFF>TURNING_ON, TURNING_ON>ON", await(() -> states.size() >= 2, 2000) && adapter.isEnabled()
		      && states.get(0).equals("10>11") && states.get(1).equals("11>12"), "" + states);
		int n = running.results.size();
		mock("found " + B2 + " public -70");
		mock("md " + B2 + " -71 0x0075 0102");
		Thread.sleep(300);
		check("a scan from before the power cycle ended with it (AOSP)", running.results.size() == n, "" + running.results);
		check("and no discovery runs for it", mockCount("call StartDiscovery ok") == 2, "" + mockLines());

		scanner = adapter.getBluetoothLeScanner();
		final Recorder r2 = new Recorder();
		scanner.startScan(null, new ScanSettings.Builder().build(), r2);
		check("a new scan after power on starts discovery again", await(() -> startCount() == 3, 3000), "" + mockLines());
		states.clear();
		mock("remove_adapter");
		check("the adapter removed (USB unplugged): STATE_OFF", await(() -> adapter.getState() == BluetoothAdapter.STATE_OFF, 2000)
		      && await(() -> states.size() >= 2, 2000), "" + states);
		mock("add_adapter");
		check("plugged in again: STATE_ON", await(() -> adapter.getState() == BluetoothAdapter.STATE_ON, 2000), "" + adapter.getState());
		states.clear();
		mock("quit_bluez");
		check("bluetoothd gone: STATE_OFF", await(() -> adapter.getState() == BluetoothAdapter.STATE_OFF, 2000), "" + adapter.getState());
		mock("own_bluez");
		check("bluetoothd back: STATE_ON", await(() -> adapter.getState() == BluetoothAdapter.STATE_ON, 3000), "" + adapter.getState());
		check("each change broadcast", await(() -> states.size() >= 4, 2000) && states.get(3).equals("11>12"), "" + states);
		final Recorder r3 = new Recorder();
		adapter.getBluetoothLeScanner().startScan(null, new ScanSettings.Builder().build(), r3);
		mock("found " + B1 + " random -60 Beacon1");
		mock("sd " + B1 + " -59 " + FEAA + " " + UID_FRAME);
		check("scanning works with the new bluetoothd", await(() -> find(r3, B1, (byte) 0) != null, 3000), "" + r3.results);
		adapter.getBluetoothLeScanner().stopScan(r3);
	}

	/* ---- nolocation: no location permission, no results ---- */

	static void noLocationTests() throws Exception {
		BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
		final Recorder r = new Recorder();
		adapter.getBluetoothLeScanner().startScan(null, new ScanSettings.Builder().build(), r);
		check("scanning starts without location permission", awaitMockLine("call StartDiscovery ok", 3000), "");
		mock("found " + B1 + " random -60 Beacon1");
		mock("sd " + B1 + " -59 " + FEAA + " " + UID_FRAME);
		Thread.sleep(500);
		check("AOSP: no results without ACCESS_FINE_LOCATION (BLUETOOTH_SCAN not neverForLocation)", r.results.isEmpty(), "" + r.results);
		adapter.getBluetoothLeScanner().stopScan(r);
	}

	/* ---- disavow: BLUETOOTH_SCAN neverForLocation ---- */

	static void disavowTests() throws Exception {
		PackageInfo pi = app().getPackageManager().getPackageInfo(PKG, PackageManager.GET_PERMISSIONS);
		boolean flagged = false;
		for (int i = 0; i < pi.requestedPermissions.length; i++)
			if (pi.requestedPermissions[i].equals("android.permission.BLUETOOTH_SCAN"))
				flagged = (pi.requestedPermissionsFlags[i] & PackageInfo.REQUESTED_PERMISSION_NEVER_FOR_LOCATION) != 0;
		check("usesPermissionFlags neverForLocation: REQUESTED_PERMISSION_NEVER_FOR_LOCATION", flagged, "not flagged");
		BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
		final Recorder r = new Recorder();
		adapter.getBluetoothLeScanner().startScan(null, new ScanSettings.Builder().build(), r);
		awaitMockLine("call StartDiscovery ok", 3000);
		mock("found " + B2 + " public -70");
		mock("md " + B2 + " -71 0x0075 0102");
		check("results without location permission", await(() -> findManufacturer(r, B2, 0x0075) != null, 2000), "" + r.results);
		mock("found " + B1 + " random -60");
		mock("sd " + B1 + " -59 " + FEAA + " " + UID_FRAME);
		mock("md " + B2 + " -72 0x004C 0215F7826DA64FA24E988024BC5B71E0893E00010002C5");
		Thread.sleep(500);
		check("but no Eddystone beacons", find(r, B1, (byte) 0) == null, "");
		check("and no iBeacons (AOSP's location deny list)", findManufacturer(r, B2, 0x004C) == null, "");
		adapter.getBluetoothLeScanner().stopScan(r);
	}

	/* ---- off: ATL_BLUETOOTH=off ---- */

	static void offTests() throws Exception {
		Context ctx = app();
		check("ATL_BLUETOOTH=off: no Bluetooth feature", !ctx.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)
		      && !ctx.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE), "");
		check("no BluetoothManager", ctx.getSystemService(Context.BLUETOOTH_SERVICE) == null, "");
		check("no default adapter", BluetoothAdapter.getDefaultAdapter() == null, "");
	}

	/* ---- live: a real adapter (the Pi): every Eddystone frame for LIVE_SECONDS ---- */

	static void liveTests() throws Exception {
		BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
		check("an adapter", adapter != null, "none");
		if (adapter == null)
			return;
		check("it is on", await(adapter::isEnabled, 5000), "" + adapter.getState());
		System.out.println("adapter " + adapter.getName() + " state " + adapter.getState());
		final Recorder r = new Recorder();
		adapter.getBluetoothLeScanner().startScan(eddystoneFilter(), LOW_LATENCY, r);
		int seconds = Integer.parseInt(System.getenv("LIVE_SECONDS") != null ? System.getenv("LIVE_SECONDS") : "15");
		long end = SystemClock.uptimeMillis() + seconds * 1000L;
		int printed = 0;
		while (SystemClock.uptimeMillis() < end) {
			Thread.sleep(200);
			synchronized (r.results) {
				for (; printed < r.results.size(); printed++) {
					ScanResult s = r.results.get(printed);
					byte[] sd = s.getScanRecord().getServiceData(EDDYSTONE);
					String kind = sd == null || sd.length == 0 ? "?" : sd[0] == 0 ? "UID" : sd[0] == 0x20 ? "TLM" : String.format("type%02X", sd[0]);
					System.out.println(String.format("eddystone %s %s rssi %d t %.3f %s", s.getDevice().getAddress(), kind, s.getRssi(),
					                                 s.getTimestampNanos() / 1e9, hex(sd)));
				}
			}
		}
		adapter.getBluetoothLeScanner().stopScan(r);
		check("Eddystone frames heard in " + seconds + " s: " + r.results.size(), !r.results.isEmpty(), "none");
		check("errors: " + r.errors, r.errors.isEmpty(), "");
	}

	public static void main(final String[] args) throws Exception {
		System.loadLibrary("translation_layer_main");
		if (System.getenv("TL_GLIBLOOP") != null)
			System.load(System.getenv("TL_GLIBLOOP")); // runs GLib's main loop on a thread (glibloop.c)
		Looper.prepareMainLooper();
		Thread tests = new Thread(new Runnable() {
			public void run() {
				String phase = args.length > 0 ? args[0] : "bt1";
				try {
					switch (phase) {
						case "bt1":
							scanTests();
							break;
						case "bt2":
							stateTests();
							break;
						case "nolocation":
							noLocationTests();
							break;
						case "disavow":
							disavowTests();
							break;
						case "off":
							offTests();
							break;
						case "live":
							liveTests();
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
