/*
 * Copyright (C) 2006 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.os;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.util.Log;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintWriter;

/**
 * Base class for a remotable object (AOSP android14 Binder).
 *
 * <p>teslalinux: ATL runs an app in one process and has no binder driver, so every Binder is a
 * local object, as when caller and callee share a process on Android: {@link #transact} rewinds
 * the parcels and calls {@link #onTransact} directly, AIDL's Stub.asInterface finds the local
 * object through {@link #queryLocalInterface}, and a parcelled binder comes back as the very same
 * object. The per-thread calling identity, strict-mode policy and work source that libbinder's
 * IPCThreadState keeps are kept here in Java with the same token encodings; with no incoming
 * transactions the calling identity is always this process.
 */
public class Binder implements IBinder {
    static final String TAG = "Binder";

    /** @hide */
    public static boolean LOG_RUNTIME_EXCEPTION = false; // DO NOT SUBMIT WITH TRUE

    /**
     * Value to represents that a calling work source is not set.
     *
     * <p>This constant needs to be kept in sync with IPCThreadState::kUnsetWorkSource.
     *
     * @hide
     */
    public static final int UNSET_WORKSOURCE = -1;

    /** Control whether dump() calls are allowed. */
    private static volatile String sDumpDisabled = null;

    static volatile boolean sWarnOnBlocking = false;

    static ThreadLocal<Boolean> sWarnOnBlockingOnCurrentThread = new ThreadLocal<Boolean>() {
        @Override
        protected Boolean initialValue() {
            return sWarnOnBlocking;
        }
    };

    /** libbinder's IPCThreadState, per thread. */
    private static final class ThreadState {
        int callingUid = Process.myUid();
        int callingPid = Process.myPid();
        boolean hasExplicitIdentity;
        int strictModePolicy;
        int workSource = UNSET_WORKSOURCE;
        boolean propagateWorkSource;
    }

    private static final ThreadLocal<ThreadState> sThreadState = new ThreadLocal<ThreadState>() {
        @Override
        protected ThreadState initialValue() {
            return new ThreadState();
        }
    };

    /** IPCThreadState::kWorkSourcePropagatedBitIndex */
    private static final int WORK_SOURCE_PROPAGATED_BIT_INDEX = 32;
    /** IPCThreadState's calling identity token: pid in the low 30 bits, this bit, uid above */
    private static final long EXPLICIT_IDENTITY_BIT = 1L << 30;

    private IInterface mOwner;
    private String mDescriptor;
    private IBinder mExtension;

    /**
     * Return the ID of the process that sent you the current transaction that is being processed.
     * This PID can be used with higher-level system services to determine its identity and check
     * permissions. If the current thread is not currently executing an incoming transaction, then
     * its own PID is returned.
     */
    public static final int getCallingPid() {
        return sThreadState.get().callingPid;
    }

    /**
     * Return the Linux UID assigned to the process that sent you the current transaction that is
     * being processed. If the current thread is not currently executing an incoming transaction,
     * then its own UID is returned.
     */
    public static final int getCallingUid() {
        return sThreadState.get().callingUid;
    }

    /**
     * Returns {@code true} if the current thread is currently executing an incoming transaction.
     * Local calls never are (there is no binder thread pool receiving transactions in ATL).
     *
     * @hide
     */
    public static final boolean isDirectlyHandlingTransaction() {
        return false;
    }

    private static boolean hasExplicitIdentity() {
        return sThreadState.get().hasExplicitIdentity;
    }

    /**
     * Return the Linux UID assigned to the process that sent the transaction currently being
     * processed.
     *
     * @throws IllegalStateException if the current thread is not currently executing an incoming
     * transaction and the calling identity has not been explicitly set with
     * {@link #clearCallingIdentity()}
     */
    public static final int getCallingUidOrThrow() {
        if (!isDirectlyHandlingTransaction() && !hasExplicitIdentity()) {
            throw new IllegalStateException(
                    "Thread is not in a binder transaction, "
                    + "and the calling identity has not been "
                    + "explicitly set with clearCallingIdentity");
        }
        return getCallingUid();
    }

    /**
     * Like {@link #getCallingUidOrThrow}, but logs an error instead of throwing.
     *
     * @hide
     */
    public static final int getCallingUidOrWtf(String message) {
        if (!isDirectlyHandlingTransaction() && !hasExplicitIdentity()) {
            Log.wtf(TAG, message + ": Thread is not in a binder transaction, "
                    + "and the calling identity has not been explicitly set with "
                    + "clearCallingIdentity");
        }
        return getCallingUid();
    }

    /**
     * Return the UserHandle assigned to the process that sent you the current transaction that is
     * being processed. This is the user of the caller.
     */
    public static final @NonNull UserHandle getCallingUserHandle() {
        return UserHandle.of(UserHandle.getUserId(getCallingUid()));
    }

    /**
     * Reset the identity of the incoming IPC on the current thread. Returns an opaque token that
     * can be used to restore the original calling identity by passing it to
     * {@link #restoreCallingIdentity(long)}.
     */
    public static final long clearCallingIdentity() {
        ThreadState s = sThreadState.get();
        long token = ((long) s.callingUid << 32) | (s.hasExplicitIdentity ? EXPLICIT_IDENTITY_BIT : 0)
                | (s.callingPid & (EXPLICIT_IDENTITY_BIT - 1));
        s.callingUid = Process.myUid();
        s.callingPid = Process.myPid();
        s.hasExplicitIdentity = true;
        return token;
    }

    /**
     * Restore the identity of the incoming IPC on the current thread back to a previously
     * identity that was returned by {@link #clearCallingIdentity}.
     */
    public static final void restoreCallingIdentity(long token) {
        int uid = (int) (token >> 32);
        if (uid > 0 && uid < 999) {
            // In Android currently there are no uids in this range.
            throw new IllegalStateException("Restoring bad calling ident: 0x" + Long.toHexString(token));
        }
        ThreadState s = sThreadState.get();
        s.callingUid = uid;
        s.hasExplicitIdentity = (token & EXPLICIT_IDENTITY_BIT) != 0;
        s.callingPid = (int) (token & (EXPLICIT_IDENTITY_BIT - 1));
    }

    /**
     * Sets the native thread-local StrictMode policy mask.
     *
     * @hide
     */
    public static final void setThreadStrictModePolicy(int policyMask) {
        sThreadState.get().strictModePolicy = policyMask;
    }

    /**
     * Gets the current native thread-local StrictMode policy mask.
     *
     * @hide
     */
    public static final int getThreadStrictModePolicy() {
        return sThreadState.get().strictModePolicy;
    }

    /**
     * Sets the work source for this thread; outgoing transactions carry it. Returns a token to
     * restore the previous one with {@link #restoreCallingWorkSource(long)}.
     *
     * @hide
     */
    public static final long setCallingWorkSourceUid(int workSource) {
        long token = setCallingWorkSourceUidWithoutPropagation(workSource);
        sThreadState.get().propagateWorkSource = true;
        return token;
    }

    /** IPCThreadState::setCallingWorkSourceUidWithoutPropagation (Parcel.enforceInterface) */
    static long setCallingWorkSourceUidWithoutPropagation(int uid) {
        ThreadState s = sThreadState.get();
        long propagatedBit = ((long) (s.propagateWorkSource ? 1 : 0)) << WORK_SOURCE_PROPAGATED_BIT_INDEX;
        long token = propagatedBit | (s.workSource & 0xffffffffL);
        s.workSource = uid;
        return token;
    }

    /** whether outgoing interface tokens carry the work source (Parcel.writeInterfaceToken) */
    static boolean shouldPropagateWorkSource() {
        return sThreadState.get().propagateWorkSource;
    }

    /**
     * Returns the work source set by the caller.
     *
     * @hide
     */
    public static final int getCallingWorkSourceUid() {
        return sThreadState.get().workSource;
    }

    /**
     * Clears the work source on this thread.
     *
     * @hide
     */
    public static final long clearCallingWorkSource() {
        return setCallingWorkSourceUid(UNSET_WORKSOURCE);
    }

    /**
     * Restores the work source on this thread using a token returned by
     * {@link #setCallingWorkSourceUid(int)} or {@link #clearCallingWorkSource()}.
     *
     * @hide
     */
    public static final void restoreCallingWorkSource(long token) {
        setCallingWorkSourceUidWithoutPropagation((int) token);
        sThreadState.get().propagateWorkSource = ((token >> WORK_SOURCE_PROPAGATED_BIT_INDEX) & 0x1) != 0;
    }

    /**
     * Flush any Binder commands pending in the current thread to the kernel driver. ATL has no
     * binder driver and local transactions run synchronously, so nothing is ever pending.
     */
    public static final void flushPendingCommands() {
        // no driver: local transactions complete inside transact(), there is no command queue
    }

    /**
     * Returns true if the specified interface is a proxy.
     *
     * @hide
     */
    public static final boolean isProxy(IInterface iface) {
        return iface.asBinder() != iface;
    }

    /**
     * Allow blocking calls on the given interface, overriding the requested value of
     * {@link #setWarnOnBlocking(boolean)}. Local binders never warn, so only the check of AOSP
     * remains.
     *
     * @hide
     */
    public static IBinder allowBlocking(IBinder binder) {
        try {
            if (binder != null && binder.getInterfaceDescriptor() != null
                    && binder.queryLocalInterface(binder.getInterfaceDescriptor()) == null) {
                Log.w(TAG, "Unable to allow blocking on interface " + binder);
            }
        } catch (RemoteException ignored) {
        }
        return binder;
    }

    /**
     * Reset the given interface back to the default blocking behavior (local binders: unchanged).
     *
     * @hide
     */
    public static IBinder defaultBlocking(IBinder binder) {
        return binder;
    }

    /**
     * Inherit the current {@link #allowBlocking(IBinder)} value from one given interface to another
     * (proxies only; ATL has none).
     *
     * @hide
     */
    public static void copyAllowBlocking(IBinder fromBinder, IBinder toBinder) {
        // only BinderProxy objects carry the setting; every binder in ATL is local
    }

    /** @hide */
    public static void setWarnOnBlocking(boolean warnOnBlocking) {
        sWarnOnBlocking = warnOnBlocking;
    }

    /** @hide */
    public static void allowBlockingForCurrentThread() {
        sWarnOnBlockingOnCurrentThread.set(false);
    }

    /** @hide */
    public static void defaultBlockingForCurrentThread() {
        sWarnOnBlockingOnCurrentThread.set(sWarnOnBlocking);
    }

    /**
     * Default constructor just initializes the object.
     *
     * <p>If you're creating a Binder token (a Binder object without an attached interface),
     * you should use {@link #Binder(String)} instead.
     */
    public Binder() {
        this(null);
    }

    /**
     * Constructor for creating a raw Binder object (token) along with a descriptor.
     */
    public Binder(@Nullable String descriptor) {
        mDescriptor = descriptor;
    }

    /**
     * Convenience method for associating a specific interface with the Binder.
     * After calling, {@link #queryLocalInterface(String) queryLocalInterface()}
     * will be implemented for you to return the given owner IInterface when
     * the corresponding descriptor is requested.
     */
    public void attachInterface(@Nullable IInterface owner, @Nullable String descriptor) {
        mOwner = owner;
        mDescriptor = descriptor;
    }

    /**
     * Default implementation returns an empty interface name.
     */
    public @Nullable String getInterfaceDescriptor() {
        return mDescriptor;
    }

    /**
     * Default implementation always returns true -- if you got here,
     * the object is alive.
     */
    public boolean pingBinder() {
        return true;
    }

    /**
     * Note that if you're calling on a local binder, this always returns true
     * because your process is alive if you're calling it.
     */
    public boolean isBinderAlive() {
        return true;
    }

    /**
     * Use information supplied to {@link #attachInterface attachInterface()}
     * to return the associated {@link IInterface} if it matches the requested
     * descriptor.
     */
    public @Nullable IInterface queryLocalInterface(@NonNull String descriptor) {
        if (mDescriptor != null && mDescriptor.equals(descriptor)) {
            return mOwner;
        }
        return null;
    }

    /**
     * Control disabling of dump calls in this process.
     *
     * @hide
     */
    public static void setDumpDisabled(String msg) {
        sDumpDisabled = msg;
    }

    /**
     * Default implementation is a stub that returns false. You will want
     * to override this to do the appropriate unmarshalling of transactions.
     *
     * <p>If you want to call this, call transact().
     */
    protected boolean onTransact(int code, @NonNull Parcel data, @Nullable Parcel reply,
            int flags) throws RemoteException {
        if (code == INTERFACE_TRANSACTION) {
            reply.writeString(getInterfaceDescriptor());
            return true;
        } else if (code == DUMP_TRANSACTION) {
            ParcelFileDescriptor fd = data.readFileDescriptor();
            String[] args = data.readStringArray();
            if (fd != null) {
                try {
                    dump(fd.getFileDescriptor(), args);
                } finally {
                    libcore.io.IoUtils.closeQuietly(fd);
                }
            }
            // Write the StrictMode header.
            if (reply != null) {
                reply.writeNoException();
            }
            return true;
        }
        // SHELL_COMMAND_TRANSACTION: ATL has no shell (no ShellCallback / cmd service), so the
        // code is not understood here, which is what returning false reports.
        return false;
    }

    /**
     * Resolves a transaction code to a human readable name.
     *
     * <p>Default implementation returns null; AIDL generated code will return the original
     * method name.
     *
     * @hide
     */
    public @Nullable String getTransactionName(int transactionCode) {
        return null;
    }

    /**
     * @return The highest user-defined transaction id of all transactions.
     * @hide
     */
    public int getMaxTransactionId() {
        return 0;
    }

    /**
     * Implemented to call the more convenient version
     * {@link #dump(FileDescriptor, PrintWriter, String[])}.
     */
    public void dump(@NonNull FileDescriptor fd, @Nullable String[] args) {
        FileOutputStream fout = new FileOutputStream(fd);
        PrintWriter pw = new PrintWriter(fout);
        try {
            doDump(fd, pw, args);
        } finally {
            pw.flush();
        }
    }

    void doDump(FileDescriptor fd, PrintWriter pw, String[] args) {
        final String disabled = sDumpDisabled;
        if (disabled == null) {
            try {
                dump(fd, pw, args);
            } catch (SecurityException e) {
                pw.println("Security exception: " + e.getMessage());
                throw e;
            } catch (Throwable e) {
                // Unlike usual calls, in this case if an exception gets thrown
                // back to us we want to print it back in to the dump data, since
                // that is where the caller expects all interesting information to
                // go.
                pw.println();
                pw.println("Exception occurred while dumping:");
                e.printStackTrace(pw);
            }
        } else {
            pw.println(sDumpDisabled);
        }
    }

    /**
     * Like {@link #dump(FileDescriptor, String[])}, but ensures the target
     * executes asynchronously.
     */
    public void dumpAsync(@NonNull final FileDescriptor fd, @Nullable final String[] args) {
        final FileOutputStream fout = new FileOutputStream(fd);
        final PrintWriter pw = new PrintWriter(fout);
        Thread thr = new Thread("Binder.dumpAsync") {
            public void run() {
                try {
                    dump(fd, pw, args);
                } finally {
                    pw.flush();
                }
            }
        };
        thr.start();
    }

    /**
     * Print the object's state into the given stream.
     *
     * @param fd The raw file descriptor that the dump is being sent to.
     * @param fout The file to which you should dump your state. This will be
     * closed for you after you return.
     * @param args additional arguments to the dump request.
     */
    protected void dump(@NonNull FileDescriptor fd, @NonNull PrintWriter fout,
            @Nullable String[] args) {
    }

    /**
     * Get the binder extension of this binder interface.
     */
    public final @Nullable IBinder getExtension() {
        return mExtension;
    }

    /**
     * Set the binder extension.
     * This should be called immediately when the object is created.
     *
     * @hide
     */
    public final void setExtension(@Nullable IBinder extension) {
        mExtension = extension;
    }

    /**
     * Default implementation rewinds the parcels and calls onTransact. On
     * the remote side, transact calls into the binder to do the IPC.
     */
    public final boolean transact(int code, @NonNull Parcel data, @Nullable Parcel reply,
            int flags) throws RemoteException {
        if (data != null) {
            data.setDataPosition(0);
        }
        boolean r = onTransact(code, data, reply, flags);
        if (reply != null) {
            reply.setDataPosition(0);
        }
        return r;
    }

    /**
     * Local implementation is a no-op.
     */
    public void linkToDeath(@NonNull DeathRecipient recipient, int flags) {
    }

    /**
     * Local implementation is a no-op.
     */
    public boolean unlinkToDeath(@NonNull DeathRecipient recipient, int flags) {
        return true;
    }
}
