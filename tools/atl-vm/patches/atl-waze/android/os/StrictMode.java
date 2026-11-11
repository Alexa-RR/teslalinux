package android.os;

import java.util.concurrent.Executor;

/* teslalinux: StrictMode with real policy bookkeeping (AOSP semantics, minus the libcore I/O hooks
 * that would raise violations: this runtime does not instrument file and network I/O). Policies are
 * stored and returned exactly as set, per thread for ThreadPolicy and process-wide for VmPolicy, so
 * code that saves, modifies and restores policies (AndroidX, OkHttp, Firebase) sees consistent state. */
public final class StrictMode {
	private StrictMode() {}

	// ThreadPolicy detect/penalty bits (AOSP values)
	public static final int DETECT_THREAD_DISK_WRITE = 0x01, DETECT_THREAD_DISK_READ = 0x02, DETECT_THREAD_NETWORK = 0x04,
	                        DETECT_THREAD_CUSTOM = 0x08, DETECT_THREAD_RESOURCE_MISMATCH = 0x10, DETECT_THREAD_UNBUFFERED_IO = 0x20,
	                        DETECT_THREAD_EXPLICIT_GC = 0x40;
	// VmPolicy detect bits
	public static final int DETECT_VM_CURSOR_LEAKS = 0x100, DETECT_VM_CLOSABLE_LEAKS = 0x200, DETECT_VM_ACTIVITY_LEAKS = 0x400,
	                        DETECT_VM_INSTANCE_LEAKS = 0x800, DETECT_VM_REGISTRATION_LEAKS = 0x1000, DETECT_VM_FILE_URI_EXPOSURE = 0x2000,
	                        DETECT_VM_CLEARTEXT_NETWORK = 0x4000, DETECT_VM_CONTENT_URI_WITHOUT_PERMISSION = 0x8000,
	                        DETECT_VM_UNTAGGED_SOCKET = 0x10000, DETECT_VM_NON_SDK_API_USAGE = 0x20000, DETECT_VM_IMPLICIT_DIRECT_BOOT = 0x40000,
	                        DETECT_VM_INCORRECT_CONTEXT_USE = 0x80000, DETECT_VM_UNSAFE_INTENT_LAUNCH = 0x100000,
	                        DETECT_VM_CREDENTIAL_PROTECTED_WHILE_LOCKED = 0x200000;
	// Penalty bits (shared)
	public static final int PENALTY_LOG = 0x01000000, PENALTY_DIALOG = 0x02000000, PENALTY_DEATH = 0x04000000, PENALTY_DROPBOX = 0x08000000,
	                        PENALTY_FLASH = 0x10000000, PENALTY_DEATH_ON_NETWORK = 0x20000000, PENALTY_DEATH_ON_CLEARTEXT_NETWORK = 0x40000000,
	                        PENALTY_DEATH_ON_FILE_URI_EXPOSURE = 0x80000000;

	public interface OnThreadViolationListener { void onThreadViolation(Violation v); }
	public interface OnVmViolationListener { void onVmViolation(Violation v); }

	/** A recorded policy violation (only ever produced by noteSlowCall here). */
	public static class Violation extends Throwable {
		public Violation(String message) { super(message); }
	}

	private static final ThreadLocal<ThreadPolicy> threadPolicy = new ThreadLocal<ThreadPolicy>() {
		@Override protected ThreadPolicy initialValue() { return ThreadPolicy.LAX; }
	};
	private static volatile VmPolicy vmPolicy = VmPolicy.LAX;

	public static void setThreadPolicy(final ThreadPolicy policy) { threadPolicy.set(policy == null ? ThreadPolicy.LAX : policy); }
	public static ThreadPolicy getThreadPolicy() { return threadPolicy.get(); }
	public static void setVmPolicy(final VmPolicy policy) { vmPolicy = policy == null ? VmPolicy.LAX : policy; }
	public static VmPolicy getVmPolicy() { return vmPolicy; }
	public static void enableDefaults() {
		setThreadPolicy(new ThreadPolicy.Builder().detectAll().penaltyLog().build());
		setVmPolicy(new VmPolicy.Builder().detectAll().penaltyLog().build());
	}

	public static ThreadPolicy allowThreadDiskWrites() {
		ThreadPolicy old = getThreadPolicy();
		setThreadPolicy(new ThreadPolicy(old.mask & ~(DETECT_THREAD_DISK_WRITE | DETECT_THREAD_DISK_READ), old.listener, old.executor));
		return old;
	}
	public static ThreadPolicy allowThreadDiskReads() {
		ThreadPolicy old = getThreadPolicy();
		setThreadPolicy(new ThreadPolicy(old.mask & ~DETECT_THREAD_DISK_READ, old.listener, old.executor));
		return old;
	}

	public static void noteSlowCall(String tag) {
		ThreadPolicy p = getThreadPolicy();
		if ((p.mask & DETECT_THREAD_CUSTOM) == 0) return;
		Violation v = new Violation("StrictMode custom violation: " + tag);
		if ((p.mask & PENALTY_LOG) != 0) android.util.Log.d("StrictMode", v.getMessage());
		if (p.listener != null) {
			final OnThreadViolationListener l = p.listener;
			Runnable r = new Runnable() { public void run() { l.onThreadViolation(v); } };
			if (p.executor != null) p.executor.execute(r); else r.run();
		}
		if ((p.mask & PENALTY_DEATH) != 0) throw new RuntimeException(v);
	}
	public static void noteDiskRead() {}
	public static void noteDiskWrite() {}

	public static final class ThreadPolicy {
		public static final ThreadPolicy LAX = new ThreadPolicy(0, null, null);
		final int mask;
		final OnThreadViolationListener listener;
		final Executor executor;
		ThreadPolicy(int mask, OnThreadViolationListener listener, Executor executor) { this.mask = mask; this.listener = listener; this.executor = executor; }
		@Override public String toString() { return "[StrictMode.ThreadPolicy; mask=" + mask + "]"; }

		public static final class Builder {
			private int mask;
			private OnThreadViolationListener listener;
			private Executor executor;
			public Builder() {}
			public Builder(ThreadPolicy policy) { mask = policy.mask; listener = policy.listener; executor = policy.executor; }
			public Builder detectAll() { return enable(DETECT_THREAD_DISK_WRITE | DETECT_THREAD_DISK_READ | DETECT_THREAD_NETWORK | DETECT_THREAD_CUSTOM | DETECT_THREAD_RESOURCE_MISMATCH | DETECT_THREAD_UNBUFFERED_IO); }
			public Builder permitAll() { return disable(0xff); }
			public Builder detectNetwork() { return enable(DETECT_THREAD_NETWORK); }
			public Builder permitNetwork() { return disable(DETECT_THREAD_NETWORK); }
			public Builder detectDiskReads() { return enable(DETECT_THREAD_DISK_READ); }
			public Builder permitDiskReads() { return disable(DETECT_THREAD_DISK_READ); }
			public Builder detectDiskWrites() { return enable(DETECT_THREAD_DISK_WRITE); }
			public Builder permitDiskWrites() { return disable(DETECT_THREAD_DISK_WRITE); }
			public Builder detectCustomSlowCalls() { return enable(DETECT_THREAD_CUSTOM); }
			public Builder permitCustomSlowCalls() { return disable(DETECT_THREAD_CUSTOM); }
			public Builder detectResourceMismatches() { return enable(DETECT_THREAD_RESOURCE_MISMATCH); }
			public Builder permitResourceMismatches() { return disable(DETECT_THREAD_RESOURCE_MISMATCH); }
			public Builder detectUnbufferedIo() { return enable(DETECT_THREAD_UNBUFFERED_IO); }
			public Builder permitUnbufferedIo() { return disable(DETECT_THREAD_UNBUFFERED_IO); }
			public Builder detectExplicitGc() { return enable(DETECT_THREAD_EXPLICIT_GC); }
			public Builder permitExplicitGc() { return disable(DETECT_THREAD_EXPLICIT_GC); }
			public Builder penaltyDialog() { return enable(PENALTY_DIALOG); }
			public Builder penaltyDeath() { return enable(PENALTY_DEATH); }
			public Builder penaltyDeathOnNetwork() { return enable(PENALTY_DEATH_ON_NETWORK); }
			public Builder penaltyFlashScreen() { return enable(PENALTY_FLASH); }
			public Builder penaltyLog() { return enable(PENALTY_LOG); }
			public Builder penaltyDropBox() { return enable(PENALTY_DROPBOX); }
			public Builder penaltyListener(Executor executor, OnThreadViolationListener listener) { this.executor = executor; this.listener = listener; return this; }
			private Builder enable(int bits) { mask |= bits; return this; }
			private Builder disable(int bits) { mask &= ~bits; return this; }
			public ThreadPolicy build() { return new ThreadPolicy(mask, listener, executor); }
		}
	}

	public static final class VmPolicy {
		public static final VmPolicy LAX = new VmPolicy(0, null, null);
		final int mask;
		final OnVmViolationListener listener;
		final Executor executor;
		VmPolicy(int mask, OnVmViolationListener listener, Executor executor) { this.mask = mask; this.listener = listener; this.executor = executor; }
		@Override public String toString() { return "[StrictMode.VmPolicy; mask=" + mask + "]"; }

		public static final class Builder {
			private int mask;
			private OnVmViolationListener listener;
			private Executor executor;
			public Builder() {}
			public Builder(VmPolicy base) { mask = base.mask; listener = base.listener; executor = base.executor; }
			public Builder detectAll() {
				return enable(DETECT_VM_CURSOR_LEAKS | DETECT_VM_CLOSABLE_LEAKS | DETECT_VM_ACTIVITY_LEAKS | DETECT_VM_INSTANCE_LEAKS
				              | DETECT_VM_REGISTRATION_LEAKS | DETECT_VM_FILE_URI_EXPOSURE | DETECT_VM_CLEARTEXT_NETWORK
				              | DETECT_VM_CONTENT_URI_WITHOUT_PERMISSION | DETECT_VM_UNTAGGED_SOCKET | DETECT_VM_IMPLICIT_DIRECT_BOOT
				              | DETECT_VM_INCORRECT_CONTEXT_USE | DETECT_VM_UNSAFE_INTENT_LAUNCH | DETECT_VM_CREDENTIAL_PROTECTED_WHILE_LOCKED);
			}
			public Builder detectActivityLeaks() { return enable(DETECT_VM_ACTIVITY_LEAKS); }
			public Builder permitActivityLeaks() { return disable(DETECT_VM_ACTIVITY_LEAKS); }
			public Builder detectLeakedSqlLiteObjects() { return enable(DETECT_VM_CURSOR_LEAKS); }
			public Builder detectLeakedClosableObjects() { return enable(DETECT_VM_CLOSABLE_LEAKS); }
			public Builder detectLeakedRegistrationObjects() { return enable(DETECT_VM_REGISTRATION_LEAKS); }
			public Builder detectFileUriExposure() { return enable(DETECT_VM_FILE_URI_EXPOSURE); }
			public Builder detectCleartextNetwork() { return enable(DETECT_VM_CLEARTEXT_NETWORK); }
			public Builder detectContentUriWithoutPermission() { return enable(DETECT_VM_CONTENT_URI_WITHOUT_PERMISSION); }
			public Builder detectUntaggedSockets() { return enable(DETECT_VM_UNTAGGED_SOCKET); }
			public Builder permitUntaggedSockets() { return disable(DETECT_VM_UNTAGGED_SOCKET); }
			public Builder detectNonSdkApiUsage() { return enable(DETECT_VM_NON_SDK_API_USAGE); }
			public Builder permitNonSdkApiUsage() { return disable(DETECT_VM_NON_SDK_API_USAGE); }
			public Builder detectImplicitDirectBoot() { return enable(DETECT_VM_IMPLICIT_DIRECT_BOOT); }
			public Builder detectCredentialProtectedWhileLocked() { return enable(DETECT_VM_CREDENTIAL_PROTECTED_WHILE_LOCKED); }
			public Builder detectIncorrectContextUse() { return enable(DETECT_VM_INCORRECT_CONTEXT_USE); }
			public Builder detectUnsafeIntentLaunch() { return enable(DETECT_VM_UNSAFE_INTENT_LAUNCH); }
			public Builder permitUnsafeIntentLaunch() { return disable(DETECT_VM_UNSAFE_INTENT_LAUNCH); }
			public Builder setClassInstanceLimit(Class<?> klass, int instanceLimit) { return enable(DETECT_VM_INSTANCE_LEAKS); }
			public Builder penaltyDeath() { return enable(PENALTY_DEATH); }
			public Builder penaltyDeathOnCleartextNetwork() { return enable(PENALTY_DEATH_ON_CLEARTEXT_NETWORK); }
			public Builder penaltyDeathOnFileUriExposure() { return enable(PENALTY_DEATH_ON_FILE_URI_EXPOSURE); }
			public Builder penaltyLog() { return enable(PENALTY_LOG); }
			public Builder penaltyDropBox() { return enable(PENALTY_DROPBOX); }
			public Builder penaltyListener(Executor executor, OnVmViolationListener listener) { this.executor = executor; this.listener = listener; return this; }
			private Builder enable(int bits) { mask |= bits; return this; }
			private Builder disable(int bits) { mask &= ~bits; return this; }
			public VmPolicy build() { return new VmPolicy(mask, listener, executor); }
		}
	}
}
