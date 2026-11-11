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

/* teslalinux: AOSP android14 Parcel, unchanged in its Java logic. libbinder's native Parcel (the
 * nativeXxx calls) is the Java class NativeParcel at the end of this file: the same wire layout
 * (little-endian, 4-byte padded, String16/String8 with length and terminator, 24-byte flat objects
 * for binders and file descriptors with an object table), so marshall/unmarshall/appendFrom and the
 * object rules behave as on Android. Binders stay in-process: readStrongBinder returns the very
 * IBinder that was written, so AIDL Stub.asInterface finds the local object. Parcel is not final
 * here because ATL's android.atl.GVariant{Builder,Iter}Parcel subclass it (intent extras over
 * D-Bus); their parcels funnel every primitive through their overridden byte/int/string methods. */

import static com.android.internal.util.Preconditions.checkArgument;

import static java.util.Objects.requireNonNull;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.TestApi;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.MathUtils;
import android.util.Pair;
import android.util.Size;
import android.util.SizeF;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;



import libcore.util.SneakyThrow;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.io.Serializable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.IntFunction;

/**
 * Container for a message (data and object references) that can
 * be sent through an IBinder.  A Parcel can contain both flattened data
 * that will be unflattened on the other side of the IPC (using the various
 * methods here for writing specific types, or the general
 * {@link Parcelable} interface), and references to live {@link IBinder}
 * objects that will result in the other side receiving a proxy IBinder
 * connected with the original IBinder in the Parcel.
 *
 * <p class="note">Parcel is <strong>not</strong> a general-purpose
 * serialization mechanism.  This class (and the corresponding
 * {@link Parcelable} API for placing arbitrary objects into a Parcel) is
 * designed as a high-performance IPC transport.  As such, it is not
 * appropriate to place any Parcel data in to persistent storage: changes
 * in the underlying implementation of any of the data in the Parcel can
 * render older data unreadable.</p>
 *
 * <p>The bulk of the Parcel API revolves around reading and writing data
 * of various types.  There are six major classes of such functions available.</p>
 *
 * <h3>Primitives</h3>
 *
 * <p>The most basic data functions are for writing and reading primitive
 * data types: {@link #writeByte}, {@link #readByte}, {@link #writeDouble},
 * {@link #readDouble}, {@link #writeFloat}, {@link #readFloat}, {@link #writeInt},
 * {@link #readInt}, {@link #writeLong}, {@link #readLong},
 * {@link #writeString}, {@link #readString}.  Most other
 * data operations are built on top of these.  The given data is written and
 * read using the endianess of the host CPU.</p>
 *
 * <h3>Primitive Arrays</h3>
 *
 * <p>There are a variety of methods for reading and writing raw arrays
 * of primitive objects, which generally result in writing a 4-byte length
 * followed by the primitive data items.  The methods for reading can either
 * read the data into an existing array, or create and return a new array.
 * These available types are:</p>
 *
 * <ul>
 * <li> {@link #writeBooleanArray(boolean[])},
 * {@link #readBooleanArray(boolean[])}, {@link #createBooleanArray()}
 * <li> {@link #writeByteArray(byte[])},
 * {@link #writeByteArray(byte[], int, int)}, {@link #readByteArray(byte[])},
 * {@link #createByteArray()}
 * <li> {@link #writeCharArray(char[])}, {@link #readCharArray(char[])},
 * {@link #createCharArray()}
 * <li> {@link #writeDoubleArray(double[])}, {@link #readDoubleArray(double[])},
 * {@link #createDoubleArray()}
 * <li> {@link #writeFloatArray(float[])}, {@link #readFloatArray(float[])},
 * {@link #createFloatArray()}
 * <li> {@link #writeIntArray(int[])}, {@link #readIntArray(int[])},
 * {@link #createIntArray()}
 * <li> {@link #writeLongArray(long[])}, {@link #readLongArray(long[])},
 * {@link #createLongArray()}
 * <li> {@link #writeStringArray(String[])}, {@link #readStringArray(String[])},
 * {@link #createStringArray()}.
 * <li> {@link #writeSparseBooleanArray(SparseBooleanArray)},
 * {@link #readSparseBooleanArray()}.
 * </ul>
 *
 * <h3>Parcelables</h3>
 *
 * <p>The {@link Parcelable} protocol provides an extremely efficient (but
 * low-level) protocol for objects to write and read themselves from Parcels.
 * You can use the direct methods {@link #writeParcelable(Parcelable, int)}
 * and {@link #readParcelable(ClassLoader)} or
 * {@link #writeParcelableArray} and
 * {@link #readParcelableArray(ClassLoader)} to write or read.  These
 * methods write both the class type and its data to the Parcel, allowing
 * that class to be reconstructed from the appropriate class loader when
 * later reading.</p>
 *
 * <p>There are also some methods that provide a more efficient way to work
 * with Parcelables: {@link #writeTypedObject}, {@link #writeTypedArray},
 * {@link #writeTypedList}, {@link #readTypedObject},
 * {@link #createTypedArray} and {@link #createTypedArrayList}.  These methods
 * do not write the class information of the original object: instead, the
 * caller of the read function must know what type to expect and pass in the
 * appropriate {@link Parcelable.Creator Parcelable.Creator} instead to
 * properly construct the new object and read its data.  (To more efficient
 * write and read a single Parcelable object that is not null, you can directly
 * call {@link Parcelable#writeToParcel Parcelable.writeToParcel} and
 * {@link Parcelable.Creator#createFromParcel Parcelable.Creator.createFromParcel}
 * yourself.)</p>
 *
 * <h3>Bundles</h3>
 *
 * <p>A special type-safe container, called {@link Bundle}, is available
 * for key/value maps of heterogeneous values.  This has many optimizations
 * for improved performance when reading and writing data, and its type-safe
 * API avoids difficult to debug type errors when finally marshalling the
 * data contents into a Parcel.  The methods to use are
 * {@link #writeBundle(Bundle)}, {@link #readBundle()}, and
 * {@link #readBundle(ClassLoader)}.
 *
 * <h3>Active Objects</h3>
 *
 * <p>An unusual feature of Parcel is the ability to read and write active
 * objects.  For these objects the actual contents of the object is not
 * written, rather a special token referencing the object is written.  When
 * reading the object back from the Parcel, you do not get a new instance of
 * the object, but rather a handle that operates on the exact same object that
 * was originally written.  There are two forms of active objects available.</p>
 *
 * <p>{@link Binder} objects are a core facility of Android's general cross-process
 * communication system.  The {@link IBinder} interface describes an abstract
 * protocol with a Binder object.  Any such interface can be written in to
 * a Parcel, and upon reading you will receive either the original object
 * implementing that interface or a special proxy implementation
 * that communicates calls back to the original object.  The methods to use are
 * {@link #writeStrongBinder(IBinder)},
 * {@link #writeStrongInterface(IInterface)}, {@link #readStrongBinder()},
 * {@link #writeBinderArray(IBinder[])}, {@link #readBinderArray(IBinder[])},
 * {@link #createBinderArray()},
 * {@link #writeInterfaceArray(T[])}, {@link #readInterfaceArray(T[], Function)},
 * {@link #createInterfaceArray(IntFunction, Function)},
 * {@link #writeBinderList(List)}, {@link #readBinderList(List)},
 * {@link #createBinderArrayList()},
 * {@link #writeInterfaceList(List)}, {@link #readInterfaceList(List, Function)},
 * {@link #createInterfaceArrayList(Function)}.</p>
 *
 * <p>FileDescriptor objects, representing raw Linux file descriptor identifiers,
 * can be written and {@link ParcelFileDescriptor} objects returned to operate
 * on the original file descriptor.  The returned file descriptor is a dup
 * of the original file descriptor: the object and fd is different, but
 * operating on the same underlying file stream, with the same position, etc.
 * The methods to use are {@link #writeFileDescriptor(FileDescriptor)},
 * {@link #readFileDescriptor()}.
 *
  * <h3>Parcelable Containers</h3>
 *
 * <p>A final class of methods are for writing and reading standard Java
 * containers of arbitrary types.  These all revolve around the
 * {@link #writeValue(Object)} and {@link #readValue(ClassLoader)} methods
 * which define the types of objects allowed.  The container methods are
 * {@link #writeArray(Object[])}, {@link #readArray(ClassLoader)},
 * {@link #writeList(List)}, {@link #readList(List, ClassLoader)},
 * {@link #readArrayList(ClassLoader)},
 * {@link #writeMap(Map)}, {@link #readMap(Map, ClassLoader)},
 * {@link #writeSparseArray(SparseArray)},
 * {@link #readSparseArray(ClassLoader)}.
 *
 * <h3>Restricted Parcelable Containers</h3>
 *
 * <p>A final class of methods are for reading standard Java containers of restricted types.
 * These methods replace methods for reading containers of arbitrary types from previous section
 * starting from Android {@link Build.VERSION_CODES#TIRAMISU}. The pairing writing methods are
 * still the same from previous section.
 * These methods accepts additional {@code clazz} parameters as the required types.
 * The Restricted Parcelable container methods are {@link #readArray(ClassLoader, Class)},
 * {@link #readList(List, ClassLoader, Class)},
 * {@link #readArrayList(ClassLoader, Class)},
 * {@link #readMap(Map, ClassLoader, Class, Class)},
 * {@link #readSparseArray(ClassLoader, Class)}.
 */
public class Parcel {

    private static final boolean DEBUG_RECYCLE = false;
    private static final boolean DEBUG_ARRAY_MAP = false;
    private static final String TAG = "Parcel";

    @SuppressWarnings({"UnusedDeclaration"})
    private NativeParcel mNativePtr; // teslalinux: the Java parcel buffer (libbinder's Parcel in AOSP)

    /**
     * Flag indicating if {@link #mNativePtr} was allocated by this object,
     * indicating that we're responsible for its lifecycle.
     */
    private boolean mOwnsNativeParcelObject;
    private long mNativeSize;

    private ArrayMap<Class, Object> mClassCookies;

    private RuntimeException mStack;
    private boolean mRecycled = false;

    /** @hide */
    @TestApi
    public static final int FLAG_IS_REPLY_FROM_BLOCKING_ALLOWED_OBJECT = 1 << 0;

    /** @hide */
    @TestApi
    public static final int FLAG_PROPAGATE_ALLOW_BLOCKING = 1 << 1;

    /** @hide */
    @IntDef(flag = true, prefix = { "FLAG_" }, value = {
            FLAG_IS_REPLY_FROM_BLOCKING_ALLOWED_OBJECT,
            FLAG_PROPAGATE_ALLOW_BLOCKING,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ParcelFlags {}

    @ParcelFlags
    private int mFlags;

    /**
     * Whether or not to parcel the stack trace of an exception. This has a performance
     * impact, so should only be included in specific processes and only on debug builds.
     */
    private static boolean sParcelExceptionStackTrace;

    private static final Object sPoolSync = new Object();

    /** Next item in the linked list pool, if any */
    private Parcel mPoolNext;

    /** Head of a linked list pool of {@link Parcel} objects */
    private static Parcel sOwnedPool;
    /** Head of a linked list pool of {@link Parcel} objects */
    private static Parcel sHolderPool;

    /** Total size of pool with head at {@link #sOwnedPool} */
    private static int sOwnedPoolSize = 0;
    /** Total size of pool with head at {@link #sHolderPool} */
    private static int sHolderPoolSize = 0;

    /**
     * We're willing to pool up to 32 objects, which is sized to accommodate
     * both a data and reply Parcel for the maximum of 16 Binder threads.
     */
    private static final int POOL_SIZE = 32;

    // Keep in sync with frameworks/native/include/private/binder/ParcelValTypes.h.
    private static final int VAL_NULL = -1;
    private static final int VAL_STRING = 0;
    private static final int VAL_INTEGER = 1;
    private static final int VAL_MAP = 2; // length-prefixed
    private static final int VAL_BUNDLE = 3;
    private static final int VAL_PARCELABLE = 4; // length-prefixed
    private static final int VAL_SHORT = 5;
    private static final int VAL_LONG = 6;
    private static final int VAL_FLOAT = 7;
    private static final int VAL_DOUBLE = 8;
    private static final int VAL_BOOLEAN = 9;
    private static final int VAL_CHARSEQUENCE = 10;
    private static final int VAL_LIST  = 11; // length-prefixed
    private static final int VAL_SPARSEARRAY = 12; // length-prefixed
    private static final int VAL_BYTEARRAY = 13;
    private static final int VAL_STRINGARRAY = 14;
    private static final int VAL_IBINDER = 15;
    private static final int VAL_PARCELABLEARRAY = 16; // length-prefixed
    private static final int VAL_OBJECTARRAY = 17; // length-prefixed
    private static final int VAL_INTARRAY = 18;
    private static final int VAL_LONGARRAY = 19;
    private static final int VAL_BYTE = 20;
    private static final int VAL_SERIALIZABLE = 21; // length-prefixed
    private static final int VAL_SPARSEBOOLEANARRAY = 22;
    private static final int VAL_BOOLEANARRAY = 23;
    private static final int VAL_CHARSEQUENCEARRAY = 24;
    private static final int VAL_PERSISTABLEBUNDLE = 25;
    private static final int VAL_SIZE = 26;
    private static final int VAL_SIZEF = 27;
    private static final int VAL_DOUBLEARRAY = 28;
    private static final int VAL_CHAR = 29;
    private static final int VAL_SHORTARRAY = 30;
    private static final int VAL_CHARARRAY = 31;
    private static final int VAL_FLOATARRAY = 32;

    // The initial int32 in a Binder call's reply Parcel header:
    // Keep these in sync with libbinder's binder/Status.h.
    private static final int EX_SECURITY = -1;
    private static final int EX_BAD_PARCELABLE = -2;
    private static final int EX_ILLEGAL_ARGUMENT = -3;
    private static final int EX_NULL_POINTER = -4;
    private static final int EX_ILLEGAL_STATE = -5;
    private static final int EX_NETWORK_MAIN_THREAD = -6;
    private static final int EX_UNSUPPORTED_OPERATION = -7;
    private static final int EX_SERVICE_SPECIFIC = -8;
    private static final int EX_PARCELABLE = -9;
    /** @hide */
    // WARNING: DO NOT add more 'reply' headers. These also need to add work to native
    // code and this encodes extra information in object statuses. If we need to expand
    // this design, we should add a generic way to attach parcelables/structured parcelables
    // to transactions which can work across languages.
    public static final int EX_HAS_NOTED_APPOPS_REPLY_HEADER = -127; // special; see below
    // WARNING: DO NOT add more 'reply' headers. These also need to add work to native
    // code and this encodes extra information in object statuses. If we need to expand
    // this design, we should add a generic way to attach parcelables/structured parcelables
    // to transactions which can work across languages.
    private static final int EX_HAS_STRICTMODE_REPLY_HEADER = -128;  // special; see below
    // EX_TRANSACTION_FAILED is used exclusively in native code.
    // see libbinder's binder/Status.h
    private static final int EX_TRANSACTION_FAILED = -129;








    /** Last time exception with a stack trace was written */
    private static volatile long sLastWriteExceptionStackTrace;
    /** Used for throttling of writing stack trace, which is costly */
    private static final int WRITE_EXCEPTION_STACK_TRACE_THRESHOLD_MS = 1000;


    public final static Parcelable.Creator<String> STRING_CREATOR
             = new Parcelable.Creator<String>() {
        public String createFromParcel(Parcel source) {
            return source.readString();
        }
        public String[] newArray(int size) {
            return new String[size];
        }
    };

    /**
     * @hide
     */
    public static class ReadWriteHelper {

        public ReadWriteHelper() {
        }

        public static final ReadWriteHelper DEFAULT = new ReadWriteHelper();

        /**
         * Called when writing a string to a parcel. Subclasses wanting to write a string
         * must use {@link #writeStringNoHelper(String)} to avoid
         * infinity recursive calls.
         */
        public void writeString8(Parcel p, String s) {
            p.writeString8NoHelper(s);
        }

        public void writeString16(Parcel p, String s) {
            p.writeString16NoHelper(s);
        }

        /**
         * Called when reading a string to a parcel. Subclasses wanting to read a string
         * must use {@link #readStringNoHelper()} to avoid
         * infinity recursive calls.
         */
        public String readString8(Parcel p) {
            return p.readString8NoHelper();
        }

        public String readString16(Parcel p) {
            return p.readString16NoHelper();
        }
    }

    private ReadWriteHelper mReadWriteHelper = ReadWriteHelper.DEFAULT;

    /**
     * Retrieve a new Parcel object from the pool.
     */
    @NonNull
    public static Parcel obtain() {
        Parcel res = null;
        synchronized (sPoolSync) {
            if (sOwnedPool != null) {
                res = sOwnedPool;
                sOwnedPool = res.mPoolNext;
                res.mPoolNext = null;
                sOwnedPoolSize--;
            }
        }

        // When no cache found above, create from scratch; otherwise prepare the
        // cached object to be used
        if (res == null) {
            res = new Parcel(0);
        } else {
            res.mRecycled = false;
            if (DEBUG_RECYCLE) {
                res.mStack = new RuntimeException();
            }
            res.mReadWriteHelper = ReadWriteHelper.DEFAULT;
        }
        return res;
    }

    /**
     * Retrieve a new Parcel object from the pool for use with a specific binder.
     *
     * Associate this parcel with a binder object. This marks the parcel as being prepared for a
     * transaction on this specific binder object. Based on this, the format of the wire binder
     * protocol may change. For future compatibility, it is recommended to use this for all
     * Parcels.
     */
    @NonNull
    public static Parcel obtain(@NonNull IBinder binder) {
        Parcel parcel = Parcel.obtain();
        parcel.markForBinder(binder);
        return parcel;
    }

    /**
     * Put a Parcel object back into the pool.  You must not touch
     * the object after this call.
     */
    public final void recycle() {
        if (mRecycled) {
            Log.wtf(TAG, "Recycle called on unowned Parcel. (recycle twice?) Here: "
                    + Log.getStackTraceString(new Throwable())
                    + " Original recycle call (if DEBUG_RECYCLE): ", mStack);

            return;
        }
        mRecycled = true;

        // We try to reset the entire object here, but in order to be
        // able to print a stack when a Parcel is recycled twice, that
        // is cleared in obtain instead.

        mClassCookies = null;
        freeBuffer();

        if (getClass() != Parcel.class) {
            // teslalinux: ATL's GVariant parcel subclasses are never pooled
            return;
        }
        if (mOwnsNativeParcelObject) {
            synchronized (sPoolSync) {
                if (sOwnedPoolSize < POOL_SIZE) {
                    mPoolNext = sOwnedPool;
                    sOwnedPool = this;
                    sOwnedPoolSize++;
                }
            }
        } else {
            mNativePtr = null;
            synchronized (sPoolSync) {
                if (sHolderPoolSize < POOL_SIZE) {
                    mPoolNext = sHolderPool;
                    sHolderPool = this;
                    sHolderPoolSize++;
                }
            }
        }
    }

    /**
     * Set a {@link ReadWriteHelper}, which can be used to avoid having duplicate strings, for
     * example.
     *
     * @hide
     */
    public void setReadWriteHelper(@Nullable ReadWriteHelper helper) {
        mReadWriteHelper = helper != null ? helper : ReadWriteHelper.DEFAULT;
    }

    /**
     * @return whether this parcel has a {@link ReadWriteHelper}.
     *
     * @hide
     */
    public boolean hasReadWriteHelper() {
        return (mReadWriteHelper != null) && (mReadWriteHelper != ReadWriteHelper.DEFAULT);
    }

    /** @hide */

    /** @hide */

    /**
     * Parcel data should be zero'd before realloc'd or deleted.
     *
     * Note: currently this feature requires multiple things to work in concert:
     * - markSensitive must be called on every relative Parcel
     * - FLAG_CLEAR_BUF must be passed into the kernel
     * This requires having code which does the right thing in every method and in every backend
     * of AIDL. Rather than exposing this API, it should be replaced with a single API on
     * IBinder objects which can be called once, and the information should be fed into the
     * Parcel using markForBinder APIs. In terms of code size and number of API calls, this is
     * much more extensible.
     *
     * @hide
     */
    public final void markSensitive() {
        nativeMarkSensitive(mNativePtr);
    }

    /**
     * @hide
     */
    private void markForBinder(@NonNull IBinder binder) {
        nativeMarkForBinder(mNativePtr, binder);
    }

    /**
     * Whether this Parcel is written for an RPC transaction.
     *
     * @hide
     */
    public final boolean isForRpc() {
        return nativeIsForRpc(mNativePtr);
    }

    /** @hide */
    @ParcelFlags
    @TestApi
    public int getFlags() {
        return mFlags;
    }

    /** @hide */
    public void setFlags(@ParcelFlags int flags) {
        mFlags = flags;
    }

    /** @hide */
    public void addFlags(@ParcelFlags int flags) {
        mFlags |= flags;
    }

    /** @hide */
    private boolean hasFlags(@ParcelFlags int flags) {
        return (mFlags & flags) == flags;
    }

    /**
     * This method is used by the AIDL compiler for system components. Not intended to be
     * used by non-system apps.
     */
    // Note: Ideally this method should be @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES),
    // but we need to make this method public due to the way the aidl compiler is compiled.
    // We don't really need to protect it; even if 3p / non-system apps, nothing would happen.
    // This would only work when used on a reply parcel by a binder object that's allowed-blocking.
    public void setPropagateAllowBlocking() {
        addFlags(FLAG_PROPAGATE_ALLOW_BLOCKING);
    }

    /**
     * Returns the total amount of data contained in the parcel.
     */
    public int dataSize() {
        return nativeDataSize(mNativePtr);
    }

    /**
     * Returns the amount of data remaining to be read from the
     * parcel.  That is, {@link #dataSize}-{@link #dataPosition}.
     */
    public final int dataAvail() {
        return nativeDataAvail(mNativePtr);
    }

    /**
     * Returns the current position in the parcel data.  Never
     * more than {@link #dataSize}.
     */
    public final int dataPosition() {
        return nativeDataPosition(mNativePtr);
    }

    /**
     * Returns the total amount of space in the parcel.  This is always
     * >= {@link #dataSize}.  The difference between it and dataSize() is the
     * amount of room left until the parcel needs to re-allocate its
     * data buffer.
     */
    public final int dataCapacity() {
        return nativeDataCapacity(mNativePtr);
    }

    /**
     * Change the amount of data in the parcel.  Can be either smaller or
     * larger than the current size.  If larger than the current capacity,
     * more memory will be allocated.
     *
     * @param size The new number of bytes in the Parcel.
     */
    public final void setDataSize(int size) {
        nativeSetDataSize(mNativePtr, size);
    }

    /**
     * Move the current read/write position in the parcel.
     * @param pos New offset in the parcel; must be between 0 and
     * {@link #dataSize}.
     */
    public final void setDataPosition(int pos) {
        nativeSetDataPosition(mNativePtr, pos);
    }

    /**
     * Change the capacity (current available space) of the parcel.
     *
     * @param size The new capacity of the parcel, in bytes.  Can not be
     * less than {@link #dataSize} -- that is, you can not drop existing data
     * with this method.
     */
    public final void setDataCapacity(int size) {
        nativeSetDataCapacity(mNativePtr, size);
    }

    /** @hide */
    public final boolean pushAllowFds(boolean allowFds) {
        return nativePushAllowFds(mNativePtr, allowFds);
    }

    /** @hide */
    public final void restoreAllowFds(boolean lastValue) {
        nativeRestoreAllowFds(mNativePtr, lastValue);
    }

    /**
     * Returns the raw bytes of the parcel.
     *
     * <p class="note">The data you retrieve here <strong>must not</strong>
     * be placed in any kind of persistent storage (on local disk, across
     * a network, etc).  For that, you should use standard serialization
     * or another kind of general serialization mechanism.  The Parcel
     * marshalled representation is highly optimized for local IPC, and as
     * such does not attempt to maintain compatibility with data created
     * in different versions of the platform.
     */
    public final byte[] marshall() {
        return nativeMarshall(mNativePtr);
    }

    /**
     * Fills the raw bytes of this Parcel with the supplied data.
     */
    public final void unmarshall(@NonNull byte[] data, int offset, int length) {
        nativeUnmarshall(mNativePtr, data, offset, length);
    }

    public final void appendFrom(Parcel parcel, int offset, int length) {
        nativeAppendFrom(mNativePtr, parcel.mNativePtr, offset, length);
    }

    /** @hide */
    public int compareData(Parcel other) {
        return nativeCompareData(mNativePtr, other.mNativePtr);
    }

    /** @hide */
    public static boolean compareData(Parcel a, int offsetA, Parcel b, int offsetB, int length) {
        return nativeCompareDataInRange(a.mNativePtr, offsetA, b.mNativePtr, offsetB, length);
    }

    /** @hide */
    public final void setClassCookie(Class clz, Object cookie) {
        if (mClassCookies == null) {
            mClassCookies = new ArrayMap<>();
        }
        mClassCookies.put(clz, cookie);
    }

    /** @hide */
    @Nullable
    public final Object getClassCookie(Class clz) {
        return mClassCookies != null ? mClassCookies.get(clz) : null;
    }

    /** @hide */
    public final void adoptClassCookies(Parcel from) {
        mClassCookies = from.mClassCookies;
    }

    /** @hide */
    public Map<Class, Object> copyClassCookies() {
        return new ArrayMap<>(mClassCookies);
    }

    /** @hide */
    public void putClassCookies(Map<Class, Object> cookies) {
        if (cookies == null) {
            return;
        }
        if (mClassCookies == null) {
            mClassCookies = new ArrayMap<>();
        }
        mClassCookies.putAll(cookies);
    }

    /**
     * Report whether the parcel contains any marshalled file descriptors.
     */
    public boolean hasFileDescriptors() {
        return nativeHasFileDescriptors(mNativePtr);
    }

    /**
     * Report whether the parcel contains any marshalled file descriptors in the range defined by
     * {@code offset} and {@code length}.
     *
     * @param offset The offset from which the range starts. Should be between 0 and
     *     {@link #dataSize()}.
     * @param length The length of the range. Should be between 0 and {@link #dataSize()} - {@code
     *     offset}.
     * @return whether there are file descriptors or not.
     * @throws IllegalArgumentException if the parameters are out of the permitted ranges.
     */
    public boolean hasFileDescriptors(int offset, int length) {
        return nativeHasFileDescriptorsInRange(mNativePtr, offset, length);
    }

    /**
     * Check if the object has file descriptors.
     *
     * <p>Objects supported are {@link Parcel} and objects that can be passed to {@link
     * #writeValue(Object)}}
     *
     * <p>For most cases, it will use the self-reported {@link Parcelable#describeContents()} method
     * for that.
     *
     * @throws IllegalArgumentException if you provide any object not supported by above methods
     *     (including if the unsupported object is inside a nested container).
     *
     * @hide
     */
    public static boolean hasFileDescriptors(Object value) {
        if (value instanceof Parcel) {
            Parcel parcel = (Parcel) value;
            if (parcel.hasFileDescriptors()) {
                return true;
            }
        } else if (value instanceof LazyValue) {
            LazyValue lazy = (LazyValue) value;
            if (lazy.hasFileDescriptors()) {
                return true;
            }
        } else if (value instanceof Parcelable) {
            Parcelable parcelable = (Parcelable) value;
            if ((parcelable.describeContents() & Parcelable.CONTENTS_FILE_DESCRIPTOR) != 0) {
                return true;
            }
        } else if (value instanceof ArrayMap<?, ?>) {
            ArrayMap<?, ?> map = (ArrayMap<?, ?>) value;
            for (int i = 0, n = map.size(); i < n; i++) {
                if (hasFileDescriptors(map.keyAt(i))
                        || hasFileDescriptors(map.valueAt(i))) {
                    return true;
                }
            }
        } else if (value instanceof Map<?, ?>) {
            Map<?, ?> map = (Map<?, ?>) value;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (hasFileDescriptors(entry.getKey())
                        || hasFileDescriptors(entry.getValue())) {
                    return true;
                }
            }
        } else if (value instanceof List<?>) {
            List<?> list = (List<?>) value;
            for (int i = 0, n = list.size(); i < n; i++) {
                if (hasFileDescriptors(list.get(i))) {
                    return true;
                }
            }
        } else if (value instanceof SparseArray<?>) {
            SparseArray<?> array = (SparseArray<?>) value;
            for (int i = 0, n = array.size(); i < n; i++) {
                if (hasFileDescriptors(array.valueAt(i))) {
                    return true;
                }
            }
        } else if (value instanceof Object[]) {
            Object[] array = (Object[]) value;
            for (int i = 0, n = array.length; i < n; i++) {
                if (hasFileDescriptors(array[i])) {
                    return true;
                }
            }
        } else {
            getValueType(value); // Will throw if value is not supported
        }
        return false;
    }

    /**
     * Store or read an IBinder interface token in the parcel at the current
     * {@link #dataPosition}. This is used to validate that the marshalled
     * transaction is intended for the target interface. This is typically written
     * at the beginning of transactions as a header.
     */
    public final void writeInterfaceToken(@NonNull String interfaceName) {
        nativeWriteInterfaceToken(mNativePtr, interfaceName);
    }

    /**
     * Read the header written by writeInterfaceToken and verify it matches
     * the interface name in question. If the wrong interface type is present,
     * {@link SecurityException} is thrown. When used over binder, this exception
     * should propagate to the caller.
     */
    public final void enforceInterface(@NonNull String interfaceName) {
        nativeEnforceInterface(mNativePtr, interfaceName);
    }

    /**
     * Verify there are no bytes left to be read on the Parcel.
     *
     * @throws BadParcelableException If the current position hasn't reached the end of the Parcel.
     * When used over binder, this exception should propagate to the caller.
     */
    public void enforceNoDataAvail() {
        final int n = dataAvail();
        if (n > 0) {
            throw new BadParcelableException("Parcel data not fully consumed, unread size: " + n);
        }
    }

    /**
     * Writes the work source uid to the request headers.
     *
     * <p>It requires the headers to have been written/read already to replace the work source.
     *
     * @return true if the request headers have been updated.
     *
     * @hide
     */
    public boolean replaceCallingWorkSourceUid(int workSourceUid) {
        return nativeReplaceCallingWorkSourceUid(mNativePtr, workSourceUid);
    }

    /**
     * Reads the work source uid from the request headers.
     *
     * <p>Unlike other read methods, this method does not read the parcel at the current
     * {@link #dataPosition}. It will set the {@link #dataPosition} before the read and restore the
     * position after reading the request header.
     *
     * @return the work source uid or {@link Binder#UNSET_WORKSOURCE} if headers have not been
     * written/parsed yet.
     *
     * @hide
     */
    public int readCallingWorkSourceUid() {
        return nativeReadCallingWorkSourceUid(mNativePtr);
    }

    /**
     * Write a byte array into the parcel at the current {@link #dataPosition},
     * growing {@link #dataCapacity} if needed.
     * @param b Bytes to place into the parcel.
     */
    public final void writeByteArray(@Nullable byte[] b) {
        writeByteArray(b, 0, (b != null) ? b.length : 0);
    }

    /**
     * Write a byte array into the parcel at the current {@link #dataPosition},
     * growing {@link #dataCapacity} if needed.
     * @param b Bytes to place into the parcel.
     * @param offset Index of first byte to be written.
     * @param len Number of bytes to write.
     */
    public final void writeByteArray(@Nullable byte[] b, int offset, int len) {
        if (b == null) {
            writeInt(-1);
            return;
        }
        throwsIfOutOfBounds(b.length, offset, len);
        nativeWriteByteArray(mNativePtr, b, offset, len);
    }

    /**
     * Write a blob of data into the parcel at the current {@link #dataPosition},
     * growing {@link #dataCapacity} if needed.
     *
     * <p> If the blob is small, then it is stored in-place, otherwise it is transferred by way of
     * an anonymous shared memory region. If you prefer send in-place, please use
     * {@link #writeByteArray(byte[])}.
     *
     * @param b Bytes to place into the parcel.
     *
     * @see #readBlob()
     */
    public final void writeBlob(@Nullable byte[] b) {
        writeBlob(b, 0, (b != null) ? b.length : 0);
    }

    /**
     * Write a blob of data into the parcel at the current {@link #dataPosition},
     * growing {@link #dataCapacity} if needed.
     *
     * <p> If the blob is small, then it is stored in-place, otherwise it is transferred by way of
     * an anonymous shared memory region. If you prefer send in-place, please use
     * {@link #writeByteArray(byte[], int, int)}.
     *
     * @param b Bytes to place into the parcel.
     * @param offset Index of first byte to be written.
     * @param len Number of bytes to write.
     *
     * @see #readBlob()
     */
    public final void writeBlob(@Nullable byte[] b, int offset, int len) {
        if (b == null) {
            writeInt(-1);
            return;
        }
        throwsIfOutOfBounds(b.length, offset, len);
        nativeWriteBlob(mNativePtr, b, offset, len);
    }

    // The OK status from system/core/libutils/include/utils/Errors.h .
    // We shall pass all other error codes back to native for throwing exceptions. The error
    // check is done in Java to allow using @CriticalNative calls for the success path.
    private static final int OK = 0;

    /**
     * Write an integer value into the parcel at the current dataPosition(),
     * growing dataCapacity() if needed.
     */
    public void writeInt(int val) { // teslalinux: not final, android.atl.GVariant*Parcel override it
        int err = nativeWriteInt(mNativePtr, val);
        if (err != OK) {
            nativeSignalExceptionForError(err);
        }
    }

    /**
     * Write a long integer value into the parcel at the current dataPosition(),
     * growing dataCapacity() if needed.
     */
    public final void writeLong(long val) {
        int err = nativeWriteLong(mNativePtr, val);
        if (err != OK) {
            nativeSignalExceptionForError(err);
        }
    }

    /**
     * Write a floating point value into the parcel at the current
     * dataPosition(), growing dataCapacity() if needed.
     */
    public final void writeFloat(float val) {
        int err = nativeWriteFloat(mNativePtr, val);
        if (err != OK) {
            nativeSignalExceptionForError(err);
        }
    }

    /**
     * Write a double precision floating point value into the parcel at the
     * current dataPosition(), growing dataCapacity() if needed.
     */
    public final void writeDouble(double val) {
        int err = nativeWriteDouble(mNativePtr, val);
        if (err != OK) {
            nativeSignalExceptionForError(err);
        }
    }

    /**
     * Write a string value into the parcel at the current dataPosition(),
     * growing dataCapacity() if needed.
     */
    public void writeString(@Nullable String val) { // teslalinux: not final (GVariant*Parcel)
        writeString16(val);
    }

    /** {@hide} */
    public final void writeString8(@Nullable String val) {
        mReadWriteHelper.writeString8(this, val);
    }

    /** {@hide} */
    public final void writeString16(@Nullable String val) {
        mReadWriteHelper.writeString16(this, val);
    }

    /**
     * Write a string without going though a {@link ReadWriteHelper}.  Subclasses of
     * {@link ReadWriteHelper} must use this method instead of {@link #writeString} to avoid
     * infinity recursive calls.
     *
     * @hide
     */
    public void writeStringNoHelper(@Nullable String val) {
        writeString16NoHelper(val);
    }

    /** {@hide} */
    public void writeString8NoHelper(@Nullable String val) {
        nativeWriteString8(mNativePtr, val);
    }

    /** {@hide} */
    public void writeString16NoHelper(@Nullable String val) {
        nativeWriteString16(mNativePtr, val);
    }

    /**
     * Write a boolean value into the parcel at the current dataPosition(),
     * growing dataCapacity() if needed.
     *
     * <p>Note: This method currently delegates to writeInt with a value of 1 or 0
     * for true or false, respectively, but may change in the future.
     */
    public final void writeBoolean(boolean val) {
        writeInt(val ? 1 : 0);
    }

    /**
     * Write a CharSequence value into the parcel at the current dataPosition(),
     * growing dataCapacity() if needed.
     * @hide
     */
    public final void writeCharSequence(@Nullable CharSequence val) {
        TextUtils.writeToParcel(val, this, 0);
    }

    /**
     * Write an object into the parcel at the current dataPosition(),
     * growing dataCapacity() if needed.
     */
    public final void writeStrongBinder(IBinder val) {
        nativeWriteStrongBinder(mNativePtr, val);
    }

    /**
     * Write an object into the parcel at the current dataPosition(),
     * growing dataCapacity() if needed.
     */
    public final void writeStrongInterface(IInterface val) {
        writeStrongBinder(val == null ? null : val.asBinder());
    }

    /**
     * Write a FileDescriptor into the parcel at the current dataPosition(),
     * growing dataCapacity() if needed.
     *
     * <p class="caution">The file descriptor will not be closed, which may
     * result in file descriptor leaks when objects are returned from Binder
     * calls.  Use {@link ParcelFileDescriptor#writeToParcel} instead, which
     * accepts contextual flags and will close the original file descriptor
     * if {@link Parcelable#PARCELABLE_WRITE_RETURN_VALUE} is set.</p>
     */
    public final void writeFileDescriptor(@NonNull FileDescriptor val) {
        nativeWriteFileDescriptor(mNativePtr, val);
    }

    /**
     * {@hide}
     * This will be the new name for writeFileDescriptor, for consistency.
     **/
    public final void writeRawFileDescriptor(@NonNull FileDescriptor val) {
        nativeWriteFileDescriptor(mNativePtr, val);
    }

    /**
     * {@hide}
     * Write an array of FileDescriptor objects into the Parcel.
     *
     * @param value The array of objects to be written.
     */
    public final void writeRawFileDescriptorArray(@Nullable FileDescriptor[] value) {
        if (value != null) {
            int N = value.length;
            writeInt(N);
            for (int i=0; i<N; i++) {
                writeRawFileDescriptor(value[i]);
            }
        } else {
            writeInt(-1);
        }
    }

    /**
     * Write a byte value into the parcel at the current dataPosition(),
     * growing dataCapacity() if needed.
     *
     * <p>Note: This method currently delegates to writeInt but may change in
     * the future.
     */
    public void writeByte(byte val) { // teslalinux: not final (GVariant*Parcel)
        writeInt(val);
    }

    /**
     * Please use {@link #writeBundle} instead.  Flattens a Map into the parcel
     * at the current dataPosition(),
     * growing dataCapacity() if needed.  The Map keys must be String objects.
     * The Map values are written using {@link #writeValue} and must follow
     * the specification there.
     *
     * <p>It is strongly recommended to use {@link #writeBundle} instead of
     * this method, since the Bundle class provides a type-safe API that
     * allows you to avoid mysterious type errors at the point of marshalling.
     */
    public final void writeMap(@Nullable Map val) {
        writeMapInternal((Map<String, Object>) val);
    }

    /**
     * Flatten a Map into the parcel at the current dataPosition(),
     * growing dataCapacity() if needed.  The Map keys must be String objects.
     */
    /* package */ void writeMapInternal(@Nullable Map<String,Object> val) {
        if (val == null) {
            writeInt(-1);
            return;
        }
        Set<Map.Entry<String,Object>> entries = val.entrySet();
        int size = entries.size();
        writeInt(size);

        for (Map.Entry<String,Object> e : entries) {
            writeValue(e.getKey());
            writeValue(e.getValue());
            size--;
        }

        if (size != 0) {
            throw new BadParcelableException("Map size does not match number of entries!");
        }

    }

    /**
     * Flatten an ArrayMap into the parcel at the current dataPosition(),
     * growing dataCapacity() if needed.  The Map keys must be String objects.
     */
    /* package */ void writeArrayMapInternal(@Nullable ArrayMap<String, Object> val) {
        if (val == null) {
            writeInt(-1);
            return;
        }
        // Keep the format of this Parcel in sync with writeToParcelInner() in
        // frameworks/native/libs/binder/PersistableBundle.cpp.
        final int N = val.size();
        writeInt(N);
        if (DEBUG_ARRAY_MAP) {
            RuntimeException here =  new RuntimeException("here");
            here.fillInStackTrace();
            Log.d(TAG, "Writing " + N + " ArrayMap entries", here);
        }
        int startPos;
        for (int i=0; i<N; i++) {
            if (DEBUG_ARRAY_MAP) startPos = dataPosition();
            writeString(val.keyAt(i));
            writeValue(val.valueAt(i));
            if (DEBUG_ARRAY_MAP) Log.d(TAG, "  Write #" + i + " "
                    + (dataPosition()-startPos) + " bytes: key=0x"
                    + Integer.toHexString(val.keyAt(i) != null ? val.keyAt(i).hashCode() : 0)
                    + " " + val.keyAt(i));
        }
    }

    /**
     * @hide For testing only.
     */
    public void writeArrayMap(@Nullable ArrayMap<String, Object> val) {
        writeArrayMapInternal(val);
    }

    /**
     * Flatten an {@link ArrayMap} with string keys containing a particular object
     * type into the parcel at the current dataPosition() and growing dataCapacity()
     * if needed. The type of the objects in the array must be one that implements
     * Parcelable. Only the raw data of the objects is written and not their type,
     * so you must use the corresponding {@link #createTypedArrayMap(Parcelable.Creator)}
     *
     * @param val The map of objects to be written.
     * @param parcelableFlags The parcelable flags to use.
     *
     * @see #createTypedArrayMap(Parcelable.Creator)
     * @see Parcelable
     */
    public <T extends Parcelable> void writeTypedArrayMap(@Nullable ArrayMap<String, T> val,
            int parcelableFlags) {
        if (val == null) {
            writeInt(-1);
            return;
        }
        final int count = val.size();
        writeInt(count);
        for (int i = 0; i < count; i++) {
            writeString(val.keyAt(i));
            writeTypedObject(val.valueAt(i), parcelableFlags);
        }
    }

    /**
     * Write an array set to the parcel.
     *
     * @param val The array set to write.
     *
     * @hide
     */
    public void writeArraySet(@Nullable ArraySet<? extends Object> val) {
        final int size = (val != null) ? val.size() : -1;
        writeInt(size);
        for (int i = 0; i < size; i++) {
            writeValue(val.valueAt(i));
        }
    }

    /**
     * Flatten a Bundle into the parcel at the current dataPosition(),
     * growing dataCapacity() if needed.
     */
    public final void writeBundle(@Nullable Bundle val) {
        if (val == null) {
            writeInt(-1);
            return;
        }

        val.writeToParcel(this, 0);
    }

    /**
     * Flatten a PersistableBundle into the parcel at the current dataPosition(),
     * growing dataCapacity() if needed.
     */
    public final void writePersistableBundle(@Nullable PersistableBundle val) {
        if (val == null) {
            writeInt(-1);
            return;
        }

        val.writeToParcel(this, 0);
    }

    /**
     * Flatten a Size into the parcel at the current dataPosition(),
     * growing dataCapacity() if needed.
     */
    public final void writeSize(@NonNull Size val) {
        writeInt(val.getWidth());
        writeInt(val.getHeight());
    }

    /**
     * Flatten a SizeF into the parcel at the current dataPosition(),
     * growing dataCapacity() if needed.
     */
    public final void writeSizeF(@NonNull SizeF val) {
        writeFloat(val.getWidth());
        writeFloat(val.getHeight());
    }

    /**
     * Flatten a List into the parcel at the current dataPosition(), growing
     * dataCapacity() if needed.  The List values are written using
     * {@link #writeValue} and must follow the specification there.
     */
    public final void writeList(@Nullable List val) {
        if (val == null) {
            writeInt(-1);
            return;
        }
        int N = val.size();
        int i=0;
        writeInt(N);
        while (i < N) {
            writeValue(val.get(i));
            i++;
        }
    }

    /**
     * Flatten an Object array into the parcel at the current dataPosition(),
     * growing dataCapacity() if needed.  The array values are written using
     * {@link #writeValue} and must follow the specification there.
     */
    public final void writeArray(@Nullable Object[] val) {
        if (val == null) {
            writeInt(-1);
            return;
        }
        int N = val.length;
        int i=0;
        writeInt(N);
        while (i < N) {
            writeValue(val[i]);
            i++;
        }
    }

    /**
     * Flatten a generic SparseArray into the parcel at the current
     * dataPosition(), growing dataCapacity() if needed.  The SparseArray
     * values are written using {@link #writeValue} and must follow the
     * specification there.
     */
    public final <T> void writeSparseArray(@Nullable SparseArray<T> val) {
        if (val == null) {
            writeInt(-1);
            return;
        }
        int N = val.size();
        writeInt(N);
        int i=0;
        while (i < N) {
            writeInt(val.keyAt(i));
            writeValue(val.valueAt(i));
            i++;
        }
    }

    public final void writeSparseBooleanArray(@Nullable SparseBooleanArray val) {
        if (val == null) {
            writeInt(-1);
            return;
        }
        int N = val.size();
        writeInt(N);
        int i=0;
        while (i < N) {
            writeInt(val.keyAt(i));
            writeByte((byte)(val.valueAt(i) ? 1 : 0));
            i++;
        }
    }

    /**
     * @hide
     */
    public final void writeSparseIntArray(@Nullable SparseIntArray val) {
        if (val == null) {
            writeInt(-1);
            return;
        }
        int N = val.size();
        writeInt(N);
        int i=0;
        while (i < N) {
            writeInt(val.keyAt(i));
            writeInt(val.valueAt(i));
            i++;
        }
    }

    public final void writeBooleanArray(@Nullable boolean[] val) {
        if (val != null) {
            int N = val.length;
            writeInt(N);
            for (int i=0; i<N; i++) {
                writeInt(val[i] ? 1 : 0);
            }
        } else {
            writeInt(-1);
        }
    }

    @Nullable
    public final boolean[] createBooleanArray() {
        int N = readInt();
        // >>2 as a fast divide-by-4 works in the create*Array() functions
        // because dataAvail() will never return a negative number.  4 is
        // the size of a stored boolean in the stream.
        if (N >= 0 && N <= (dataAvail() >> 2)) {
            boolean[] val = new boolean[N];
            for (int i=0; i<N; i++) {
                val[i] = readInt() != 0;
            }
            return val;
        } else {
            return null;
        }
    }

    public final void readBooleanArray(@NonNull boolean[] val) {
        int N = readInt();
        if (N == val.length) {
            for (int i=0; i<N; i++) {
                val[i] = readInt() != 0;
            }
        } else {
            throw new RuntimeException("bad array lengths");
        }
    }

    /** @hide */
    public void writeShortArray(@Nullable short[] val) {
        if (val != null) {
            int n = val.length;
            writeInt(n);
            for (int i = 0; i < n; i++) {
                writeInt(val[i]);
            }
        } else {
            writeInt(-1);
        }
    }

    /** @hide */
    @Nullable
    public short[] createShortArray() {
        int n = readInt();
        if (n >= 0 && n <= (dataAvail() >> 2)) {
            short[] val = new short[n];
            for (int i = 0; i < n; i++) {
                val[i] = (short) readInt();
            }
            return val;
        } else {
            return null;
        }
    }

    /** @hide */
    public void readShortArray(@NonNull short[] val) {
        int n = readInt();
        if (n == val.length) {
            for (int i = 0; i < n; i++) {
                val[i] = (short) readInt();
            }
        } else {
            throw new RuntimeException("bad array lengths");
        }
    }

    public final void writeCharArray(@Nullable char[] val) {
        if (val != null) {
            int N = val.length;
            writeInt(N);
            for (int i=0; i<N; i++) {
                writeInt((int)val[i]);
            }
        } else {
            writeInt(-1);
        }
    }

    @Nullable
    public final char[] createCharArray() {
        int N = readInt();
        if (N >= 0 && N <= (dataAvail() >> 2)) {
            char[] val = new char[N];
            for (int i=0; i<N; i++) {
                val[i] = (char)readInt();
            }
            return val;
        } else {
            return null;
        }
    }

    public final void readCharArray(@NonNull char[] val) {
        int N = readInt();
        if (N == val.length) {
            for (int i=0; i<N; i++) {
                val[i] = (char)readInt();
            }
        } else {
            throw new RuntimeException("bad array lengths");
        }
    }

    public final void writeIntArray(@Nullable int[] val) {
        if (val != null) {
            int N = val.length;
            writeInt(N);
            for (int i=0; i<N; i++) {
                writeInt(val[i]);
            }
        } else {
            writeInt(-1);
        }
    }

    @Nullable
    public final int[] createIntArray() {
        int N = readInt();
        if (N >= 0 && N <= (dataAvail() >> 2)) {
            int[] val = new int[N];
            for (int i=0; i<N; i++) {
                val[i] = readInt();
            }
            return val;
        } else {
            return null;
        }
    }

    public final void readIntArray(@NonNull int[] val) {
        int N = readInt();
        if (N == val.length) {
            for (int i=0; i<N; i++) {
                val[i] = readInt();
            }
        } else {
            throw new RuntimeException("bad array lengths");
        }
    }

    public final void writeLongArray(@Nullable long[] val) {
        if (val != null) {
            int N = val.length;
            writeInt(N);
            for (int i=0; i<N; i++) {
                writeLong(val[i]);
            }
        } else {
            writeInt(-1);
        }
    }

    @Nullable
    public final long[] createLongArray() {
        int N = readInt();
        // >>3 because stored longs are 64 bits
        if (N >= 0 && N <= (dataAvail() >> 3)) {
            long[] val = new long[N];
            for (int i=0; i<N; i++) {
                val[i] = readLong();
            }
            return val;
        } else {
            return null;
        }
    }

    public final void readLongArray(@NonNull long[] val) {
        int N = readInt();
        if (N == val.length) {
            for (int i=0; i<N; i++) {
                val[i] = readLong();
            }
        } else {
            throw new RuntimeException("bad array lengths");
        }
    }

    public final void writeFloatArray(@Nullable float[] val) {
        if (val != null) {
            int N = val.length;
            writeInt(N);
            for (int i=0; i<N; i++) {
                writeFloat(val[i]);
            }
        } else {
            writeInt(-1);
        }
    }

    @Nullable
    public final float[] createFloatArray() {
        int N = readInt();
        // >>2 because stored floats are 4 bytes
        if (N >= 0 && N <= (dataAvail() >> 2)) {
            float[] val = new float[N];
            for (int i=0; i<N; i++) {
                val[i] = readFloat();
            }
            return val;
        } else {
            return null;
        }
    }

    public final void readFloatArray(@NonNull float[] val) {
        int N = readInt();
        if (N == val.length) {
            for (int i=0; i<N; i++) {
                val[i] = readFloat();
            }
        } else {
            throw new RuntimeException("bad array lengths");
        }
    }

    public final void writeDoubleArray(@Nullable double[] val) {
        if (val != null) {
            int N = val.length;
            writeInt(N);
            for (int i=0; i<N; i++) {
                writeDouble(val[i]);
            }
        } else {
            writeInt(-1);
        }
    }

    @Nullable
    public final double[] createDoubleArray() {
        int N = readInt();
        // >>3 because stored doubles are 8 bytes
        if (N >= 0 && N <= (dataAvail() >> 3)) {
            double[] val = new double[N];
            for (int i=0; i<N; i++) {
                val[i] = readDouble();
            }
            return val;
        } else {
            return null;
        }
    }

    public final void readDoubleArray(@NonNull double[] val) {
        int N = readInt();
        if (N == val.length) {
            for (int i=0; i<N; i++) {
                val[i] = readDouble();
            }
        } else {
            throw new RuntimeException("bad array lengths");
        }
    }

    public final void writeStringArray(@Nullable String[] val) {
        writeString16Array(val);
    }

    @Nullable
    public final String[] createStringArray() {
        return createString16Array();
    }

    public final void readStringArray(@NonNull String[] val) {
        readString16Array(val);
    }

    /** {@hide} */
    public final void writeString8Array(@Nullable String[] val) {
        if (val != null) {
            int N = val.length;
            writeInt(N);
            for (int i=0; i<N; i++) {
                writeString8(val[i]);
            }
        } else {
            writeInt(-1);
        }
    }

    /** {@hide} */
    @Nullable
    public final String[] createString8Array() {
        int N = readInt();
        if (N >= 0) {
            String[] val = new String[N];
            for (int i=0; i<N; i++) {
                val[i] = readString8();
            }
            return val;
        } else {
            return null;
        }
    }

    /** {@hide} */
    public final void readString8Array(@NonNull String[] val) {
        int N = readInt();
        if (N == val.length) {
            for (int i=0; i<N; i++) {
                val[i] = readString8();
            }
        } else {
            throw new RuntimeException("bad array lengths");
        }
    }

    /** {@hide} */
    public final void writeString16Array(@Nullable String[] val) {
        if (val != null) {
            int N = val.length;
            writeInt(N);
            for (int i=0; i<N; i++) {
                writeString16(val[i]);
            }
        } else {
            writeInt(-1);
        }
    }

    /** {@hide} */
    @Nullable
    public final String[] createString16Array() {
        int N = readInt();
        if (N >= 0) {
            String[] val = new String[N];
            for (int i=0; i<N; i++) {
                val[i] = readString16();
            }
            return val;
        } else {
            return null;
        }
    }

    /** {@hide} */
    public final void readString16Array(@NonNull String[] val) {
        int N = readInt();
        if (N == val.length) {
            for (int i=0; i<N; i++) {
                val[i] = readString16();
            }
        } else {
            throw new RuntimeException("bad array lengths");
        }
    }

    public final void writeBinderArray(@Nullable IBinder[] val) {
        if (val != null) {
            int N = val.length;
            writeInt(N);
            for (int i=0; i<N; i++) {
                writeStrongBinder(val[i]);
            }
        } else {
            writeInt(-1);
        }
    }

    /**
     * Flatten a homogeneous array containing an IInterface type into the parcel,
     * at the current dataPosition() and growing dataCapacity() if needed.  The
     * type of the objects in the array must be one that implements IInterface.
     *
     * @param val The array of objects to be written.
     *
     * @see #createInterfaceArray
     * @see #readInterfaceArray
     * @see IInterface
     */
    public final <T extends IInterface> void writeInterfaceArray(
            @SuppressLint("ArrayReturn") @Nullable T[] val) {
        if (val != null) {
            int N = val.length;
            writeInt(N);
            for (int i=0; i<N; i++) {
                writeStrongInterface(val[i]);
            }
        } else {
            writeInt(-1);
        }
    }

    /**
     * @hide
     */
    public final void writeCharSequenceArray(@Nullable CharSequence[] val) {
        if (val != null) {
            int N = val.length;
            writeInt(N);
            for (int i=0; i<N; i++) {
                writeCharSequence(val[i]);
            }
        } else {
            writeInt(-1);
        }
    }

    /**
     * @hide
     */
    public final void writeCharSequenceList(@Nullable ArrayList<CharSequence> val) {
        if (val != null) {
            int N = val.size();
            writeInt(N);
            for (int i=0; i<N; i++) {
                writeCharSequence(val.get(i));
            }
        } else {
            writeInt(-1);
        }
    }

    @Nullable
    public final IBinder[] createBinderArray() {
        int N = readInt();
        if (N >= 0) {
            IBinder[] val = new IBinder[N];
            for (int i=0; i<N; i++) {
                val[i] = readStrongBinder();
            }
            return val;
        } else {
            return null;
        }
    }

    public final void readBinderArray(@NonNull IBinder[] val) {
        int N = readInt();
        if (N == val.length) {
            for (int i=0; i<N; i++) {
                val[i] = readStrongBinder();
            }
        } else {
            throw new RuntimeException("bad array lengths");
        }
    }

    /**
     * Read and return a new array of T (IInterface) from the parcel.
     *
     * @return the IInterface array of type T
     * @param newArray a function to create an array of T with a given length
     * @param asInterface a function to convert IBinder object into T (IInterface)
     */
    @SuppressLint({"ArrayReturn", "NullableCollection", "SamShouldBeLast"})
    @Nullable
    public final <T extends IInterface> T[] createInterfaceArray(
            @NonNull IntFunction<T[]> newArray, @NonNull Function<IBinder, T> asInterface) {
        int N = readInt();
        if (N >= 0) {
            T[] val = newArray.apply(N);
            for (int i=0; i<N; i++) {
                val[i] = asInterface.apply(readStrongBinder());
            }
            return val;
        } else {
            return null;
        }
    }

    /**
     * Read an array of T (IInterface) from a parcel.
     *
     * @param asInterface a function to convert IBinder object into T (IInterface)
     *
     * @throws BadParcelableException Throws BadParcelableException if the length of `val`
     *    mismatches the number of items in the parcel.
     */
    public final <T extends IInterface> void readInterfaceArray(
            @SuppressLint("ArrayReturn") @NonNull T[] val,
            @NonNull Function<IBinder, T> asInterface) {
        int N = readInt();
        if (N == val.length) {
            for (int i=0; i<N; i++) {
                val[i] = asInterface.apply(readStrongBinder());
            }
        } else {
            throw new BadParcelableException("bad array lengths");
        }
    }

    /**
     * Flatten a List containing a particular object type into the parcel, at
     * the current dataPosition() and growing dataCapacity() if needed.  The
     * type of the objects in the list must be one that implements Parcelable.
     * Unlike the generic writeList() method, however, only the raw data of the
     * objects is written and not their type, so you must use the corresponding
     * readTypedList() to unmarshall them.
     *
     * @param val The list of objects to be written.
     *
     * @see #createTypedArrayList
     * @see #readTypedList
     * @see Parcelable
     */
    public final <T extends Parcelable> void writeTypedList(@Nullable List<T> val) {
        writeTypedList(val, 0);
    }

    /**
     * Flatten a {@link SparseArray} containing a particular object type into the parcel
     * at the current dataPosition() and growing dataCapacity() if needed. The
     * type of the objects in the array must be one that implements Parcelable.
     * Unlike the generic {@link #writeSparseArray(SparseArray)} method, however, only
     * the raw data of the objects is written and not their type, so you must use the
     * corresponding {@link #createTypedSparseArray(Parcelable.Creator)}.
     *
     * @param val The list of objects to be written.
     * @param parcelableFlags The parcelable flags to use.
     *
     * @see #createTypedSparseArray(Parcelable.Creator)
     * @see Parcelable
     */
    public final <T extends Parcelable> void writeTypedSparseArray(@Nullable SparseArray<T> val,
            int parcelableFlags) {
        if (val == null) {
            writeInt(-1);
            return;
        }
        final int count = val.size();
        writeInt(count);
        for (int i = 0; i < count; i++) {
            writeInt(val.keyAt(i));
            writeTypedObject(val.valueAt(i), parcelableFlags);
        }
    }

    /**
     * Flatten a List containing a particular object type into the parcel, at
     * the current dataPosition() and growing dataCapacity() if needed.  The
     * type of the objects in the list must be one that implements Parcelable.
     * Unlike the generic writeList() method, however, only the raw data of the
     * objects is written and not their type, so you must use the corresponding
     * readTypedList() to unmarshall them.
     *
     * @param val The list of objects to be written.
     * @param parcelableFlags Contextual flags as per
     * {@link Parcelable#writeToParcel(Parcel, int) Parcelable.writeToParcel()}.
     *
     * @see #createTypedArrayList
     * @see #readTypedList
     * @see Parcelable
     */
    public <T extends Parcelable> void writeTypedList(@Nullable List<T> val, int parcelableFlags) {
        if (val == null) {
            writeInt(-1);
            return;
        }
        int N = val.size();
        int i=0;
        writeInt(N);
        while (i < N) {
            writeTypedObject(val.get(i), parcelableFlags);
            i++;
        }
    }

    /**
     * Flatten a List containing String objects into the parcel, at
     * the current dataPosition() and growing dataCapacity() if needed.  They
     * can later be retrieved with {@link #createStringArrayList} or
     * {@link #readStringList}.
     *
     * @param val The list of strings to be written.
     *
     * @see #createStringArrayList
     * @see #readStringList
     */
    public final void writeStringList(@Nullable List<String> val) {
        if (val == null) {
            writeInt(-1);
            return;
        }
        int N = val.size();
        int i=0;
        writeInt(N);
        while (i < N) {
            writeString(val.get(i));
            i++;
        }
    }

    /**
     * Flatten a List containing IBinder objects into the parcel, at
     * the current dataPosition() and growing dataCapacity() if needed.  They
     * can later be retrieved with {@link #createBinderArrayList} or
     * {@link #readBinderList}.
     *
     * @param val The list of strings to be written.
     *
     * @see #createBinderArrayList
     * @see #readBinderList
     */
    public final void writeBinderList(@Nullable List<IBinder> val) {
        if (val == null) {
            writeInt(-1);
            return;
        }
        int N = val.size();
        int i=0;
        writeInt(N);
        while (i < N) {
            writeStrongBinder(val.get(i));
            i++;
        }
    }

    /**
     * Flatten a {@code List} containing T (IInterface) objects into this parcel
     * at the current position. They can later be retrieved with
     * {@link #createInterfaceArrayList} or {@link #readInterfaceList}.
     *
     * @see #createInterfaceArrayList
     * @see #readInterfaceList
     */
    public final <T extends IInterface> void writeInterfaceList(@Nullable List<T> val) {
        if (val == null) {
            writeInt(-1);
            return;
        }
        int N = val.size();
        int i=0;
        writeInt(N);
        while (i < N) {
            writeStrongInterface(val.get(i));
            i++;
        }
    }

    /**
     * Flatten a {@code List} containing arbitrary {@code Parcelable} objects into this parcel
     * at the current position. They can later be retrieved using
     * {@link #readParcelableList(List, ClassLoader)} if required.
     *
     * @see #readParcelableList(List, ClassLoader)
     */
    public final <T extends Parcelable> void writeParcelableList(@Nullable List<T> val, int flags) {
        if (val == null) {
            writeInt(-1);
            return;
        }

        int N = val.size();
        int i=0;
        writeInt(N);
        while (i < N) {
            writeParcelable(val.get(i), flags);
            i++;
        }
    }

    /**
     * Flatten a homogeneous array containing a particular object type into
     * the parcel, at
     * the current dataPosition() and growing dataCapacity() if needed.  The
     * type of the objects in the array must be one that implements Parcelable.
     * Unlike the {@link #writeParcelableArray} method, however, only the
     * raw data of the objects is written and not their type, so you must use
     * {@link #readTypedArray} with the correct corresponding
     * {@link Parcelable.Creator} implementation to unmarshall them.
     *
     * @param val The array of objects to be written.
     * @param parcelableFlags Contextual flags as per
     * {@link Parcelable#writeToParcel(Parcel, int) Parcelable.writeToParcel()}.
     *
     * @see #readTypedArray
     * @see #writeParcelableArray
     * @see Parcelable.Creator
     */
    public final <T extends Parcelable> void writeTypedArray(@Nullable T[] val,
            int parcelableFlags) {
        if (val != null) {
            int N = val.length;
            writeInt(N);
            for (int i = 0; i < N; i++) {
                writeTypedObject(val[i], parcelableFlags);
            }
        } else {
            writeInt(-1);
        }
    }

    /**
     * Flatten the Parcelable object into the parcel.
     *
     * @param val The Parcelable object to be written.
     * @param parcelableFlags Contextual flags as per
     * {@link Parcelable#writeToParcel(Parcel, int) Parcelable.writeToParcel()}.
     *
     * @see #readTypedObject
     */
    public final <T extends Parcelable> void writeTypedObject(@Nullable T val,
            int parcelableFlags) {
        if (val != null) {
            writeInt(1);
            val.writeToParcel(this, parcelableFlags);
        } else {
            writeInt(0);
        }
    }

    /**
     * Flatten a homogeneous multi-dimensional array with fixed-size.  This delegates to other
     * APIs to write a one-dimensional array.  Use {@link #readFixedArray(Object)} or
     * {@link #createFixedArray(Class, int[])} with the same dimensions to unmarshal.
     *
     * @param val The array to be written.
     * @param parcelableFlags Contextual flags as per
     *   {@link Parcelable#writeToParcel(Parcel, int) Parcelable.writeToParcel()}.
     *   Used only if val is an array of Parcelable objects.
     * @param dimensions an array of int representing length of each dimension. The array should be
     *   sized with the exact size of dimensions.
     *
     * @see #readFixedArray
     * @see #createFixedArray createFixedArray(Class&lt;T&gt;, Parcelable.Creator&lt;S&gt;, int...)
     * @see #writeBooleanArray
     * @see #writeByteArray
     * @see #writeCharArray
     * @see #writeIntArray
     * @see #writeLongArray
     * @see #writeFloatArray
     * @see #writeDoubleArray
     * @see #writeBinderArray
     * @see #writeInterfaceArray
     * @see #writeTypedArray
     * @throws BadParcelableException If the array's component type is not supported or if its
     *   size doesn't match with the given dimensions.
     */
    public <T> void writeFixedArray(@Nullable T val, int parcelableFlags,
            @NonNull int... dimensions) {
        if (val == null) {
            writeInt(-1);
            return;
        }
        writeFixedArrayInternal(val, parcelableFlags, /*index=*/0, dimensions);
    }

    private <T> void writeFixedArrayInternal(T val, int parcelableFlags, int index,
            int[] dimensions) {
        if (index >= dimensions.length) {
            throw new BadParcelableException("Array has more dimensions than expected: "
                + dimensions.length);
        }

        int length = dimensions[index];

        // val should be an array of length N
        if (val == null) {
            throw new BadParcelableException("Non-null array shouldn't have a null array.");
        }
        if (!val.getClass().isArray()) {
            throw new BadParcelableException("Not an array: " + val);
        }
        if (Array.getLength(val) != length) {
            throw new BadParcelableException("bad length: expected " + length + ", but got "
                + Array.getLength(val));
        }

        // Delegates to other writers if this is a one-dimensional array.
        // Otherwise, write component arrays with recursive calls.

        final Class<?> componentType = val.getClass().getComponentType();
        if (!componentType.isArray() && index + 1 != dimensions.length) {
            throw new BadParcelableException("Array has fewer dimensions than expected: "
                + dimensions.length);
        }
        if (componentType == boolean.class) {
            writeBooleanArray((boolean[]) val);
        } else if (componentType == byte.class) {
            writeByteArray((byte[]) val);
        } else if (componentType == char.class) {
            writeCharArray((char[]) val);
        } else if (componentType == int.class) {
            writeIntArray((int[]) val);
        } else if (componentType == long.class) {
            writeLongArray((long[]) val);
        } else if (componentType == float.class) {
            writeFloatArray((float[]) val);
        } else if (componentType == double.class) {
            writeDoubleArray((double[]) val);
        } else if (componentType == IBinder.class) {
            writeBinderArray((IBinder[]) val);
        } else if (IInterface.class.isAssignableFrom(componentType)) {
            writeInterfaceArray((IInterface[]) val);
        } else if (Parcelable.class.isAssignableFrom(componentType)) {
            writeTypedArray((Parcelable[]) val, parcelableFlags);
        } else if (componentType.isArray()) {
            writeInt(length);
            for (int i = 0; i < length; i++) {
                writeFixedArrayInternal(Array.get(val, i), parcelableFlags, index + 1,
                        dimensions);
            }
        } else {
            throw new BadParcelableException("unknown type for fixed-size array: " + componentType);
        }
    }

    /**
     * Flatten a generic object in to a parcel.  The given Object value may
     * currently be one of the following types:
     *
     * <ul>
     * <li> null
     * <li> String
     * <li> Byte
     * <li> Short
     * <li> Integer
     * <li> Long
     * <li> Float
     * <li> Double
     * <li> Boolean
     * <li> String[]
     * <li> boolean[]
     * <li> byte[]
     * <li> int[]
     * <li> long[]
     * <li> Object[] (supporting objects of the same type defined here).
     * <li> {@link Bundle}
     * <li> Map (as supported by {@link #writeMap}).
     * <li> Any object that implements the {@link Parcelable} protocol.
     * <li> Parcelable[]
     * <li> CharSequence (as supported by {@link TextUtils#writeToParcel}).
     * <li> List (as supported by {@link #writeList}).
     * <li> {@link SparseArray} (as supported by {@link #writeSparseArray(SparseArray)}).
     * <li> {@link IBinder}
     * <li> Any object that implements Serializable (but see
     *      {@link #writeSerializable} for caveats).  Note that all of the
     *      previous types have relatively efficient implementations for
     *      writing to a Parcel; having to rely on the generic serialization
     *      approach is much less efficient and should be avoided whenever
     *      possible.
     * </ul>
     *
     * <p class="caution">{@link Parcelable} objects are written with
     * {@link Parcelable#writeToParcel} using contextual flags of 0.  When
     * serializing objects containing {@link ParcelFileDescriptor}s,
     * this may result in file descriptor leaks when they are returned from
     * Binder calls (where {@link Parcelable#PARCELABLE_WRITE_RETURN_VALUE}
     * should be used).</p>
     */
    public final void writeValue(@Nullable Object v) {
        if (v instanceof LazyValue) {
            LazyValue value = (LazyValue) v;
            value.writeToParcel(this);
            return;
        }
        int type = getValueType(v);
        writeInt(type);
        if (isLengthPrefixed(type)) {
            // Length
            int length = dataPosition();
            writeInt(-1); // Placeholder
            // Object
            int start = dataPosition();
            writeValue(type, v);
            int end = dataPosition();
            // Backpatch length
            setDataPosition(length);
            writeInt(end - start);
            setDataPosition(end);
        } else {
            writeValue(type, v);
        }
    }

    /** @hide */
    public static int getValueType(@Nullable Object v) {
        if (v == null) {
            return VAL_NULL;
        } else if (v instanceof String) {
            return VAL_STRING;
        } else if (v instanceof Integer) {
            return VAL_INTEGER;
        } else if (v instanceof Map) {
            return VAL_MAP;
        } else if (v instanceof Bundle) {
            // Must be before Parcelable
            return VAL_BUNDLE;
        } else if (v instanceof PersistableBundle) {
            // Must be before Parcelable
            return VAL_PERSISTABLEBUNDLE;
        } else if (v instanceof SizeF) {
            // Must be before Parcelable
            return VAL_SIZEF;
        } else if (v instanceof Parcelable) {
            // IMPOTANT: cases for classes that implement Parcelable must
            // come before the Parcelable case, so that their speci fic VAL_*
            // types will be written.
            return VAL_PARCELABLE;
        } else if (v instanceof Short) {
            return VAL_SHORT;
        } else if (v instanceof Long) {
            return VAL_LONG;
        } else if (v instanceof Float) {
            return VAL_FLOAT;
        } else if (v instanceof Double) {
            return VAL_DOUBLE;
        } else if (v instanceof Boolean) {
            return VAL_BOOLEAN;
        } else if (v instanceof CharSequence) {
            // Must be after String
            return VAL_CHARSEQUENCE;
        } else if (v instanceof List) {
            return VAL_LIST;
        } else if (v instanceof SparseArray) {
            return VAL_SPARSEARRAY;
        } else if (v instanceof boolean[]) {
            return VAL_BOOLEANARRAY;
        } else if (v instanceof byte[]) {
            return VAL_BYTEARRAY;
        } else if (v instanceof String[]) {
            return VAL_STRINGARRAY;
        } else if (v instanceof CharSequence[]) {
            // Must be after String[] and before Object[]
            return VAL_CHARSEQUENCEARRAY;
        } else if (v instanceof IBinder) {
            return VAL_IBINDER;
        } else if (v instanceof Parcelable[]) {
            return VAL_PARCELABLEARRAY;
        } else if (v instanceof int[]) {
            return VAL_INTARRAY;
        } else if (v instanceof long[]) {
            return VAL_LONGARRAY;
        } else if (v instanceof Byte) {
            return VAL_BYTE;
        } else if (v instanceof Size) {
            return VAL_SIZE;
        } else if (v instanceof double[]) {
            return VAL_DOUBLEARRAY;
        } else if (v instanceof Character) {
            return VAL_CHAR;
        } else if (v instanceof short[]) {
            return VAL_SHORTARRAY;
        } else if (v instanceof char[]) {
            return VAL_CHARARRAY;
        } else  if (v instanceof float[]) {
            return VAL_FLOATARRAY;
        } else {
            Class<?> clazz = v.getClass();
            if (clazz.isArray() && clazz.getComponentType() == Object.class) {
                // Only pure Object[] are written here, Other arrays of non-primitive types are
                // handled by serialization as this does not record the component type.
                return VAL_OBJECTARRAY;
            } else if (v instanceof Serializable) {
                // Must be last
                return VAL_SERIALIZABLE;
            } else {
                throw new IllegalArgumentException("Parcel: unknown type for value " + v);
            }
        }
    }
    /**
     * Writes value {@code v} in the parcel. This does NOT write the int representing the type
     * first.
     *
     * @hide
     */
    public void writeValue(int type, @Nullable Object v) {
        switch (type) {
            case VAL_NULL:
                break;
            case VAL_STRING:
                writeString((String) v);
                break;
            case VAL_INTEGER:
                writeInt((Integer) v);
                break;
            case VAL_MAP:
                writeMap((Map) v);
                break;
            case VAL_BUNDLE:
                writeBundle((Bundle) v);
                break;
            case VAL_PERSISTABLEBUNDLE:
                writePersistableBundle((PersistableBundle) v);
                break;
            case VAL_PARCELABLE:
                writeParcelable((Parcelable) v, 0);
                break;
            case VAL_SHORT:
                writeInt(((Short) v).intValue());
                break;
            case VAL_LONG:
                writeLong((Long) v);
                break;
            case VAL_FLOAT:
                writeFloat((Float) v);
                break;
            case VAL_DOUBLE:
                writeDouble((Double) v);
                break;
            case VAL_BOOLEAN:
                writeInt((Boolean) v ? 1 : 0);
                break;
            case VAL_CHARSEQUENCE:
                writeCharSequence((CharSequence) v);
                break;
            case VAL_LIST:
                writeList((List) v);
                break;
            case VAL_SPARSEARRAY:
                writeSparseArray((SparseArray) v);
                break;
            case VAL_BOOLEANARRAY:
                writeBooleanArray((boolean[]) v);
                break;
            case VAL_BYTEARRAY:
                writeByteArray((byte[]) v);
                break;
            case VAL_STRINGARRAY:
                writeStringArray((String[]) v);
                break;
            case VAL_CHARSEQUENCEARRAY:
                writeCharSequenceArray((CharSequence[]) v);
                break;
            case VAL_IBINDER:
                writeStrongBinder((IBinder) v);
                break;
            case VAL_PARCELABLEARRAY:
                writeParcelableArray((Parcelable[]) v, 0);
                break;
            case VAL_INTARRAY:
                writeIntArray((int[]) v);
                break;
            case VAL_LONGARRAY:
                writeLongArray((long[]) v);
                break;
            case VAL_BYTE:
                writeInt((Byte) v);
                break;
            case VAL_SIZE:
                writeSize((Size) v);
                break;
            case VAL_SIZEF:
                writeSizeF((SizeF) v);
                break;
            case VAL_DOUBLEARRAY:
                writeDoubleArray((double[]) v);
                break;
            case VAL_CHAR:
                writeInt((Character) v);
                break;
            case VAL_SHORTARRAY:
                writeShortArray((short[]) v);
                break;
            case VAL_CHARARRAY:
                writeCharArray((char[]) v);
                break;
            case VAL_FLOATARRAY:
                writeFloatArray((float[]) v);
                break;
            case VAL_OBJECTARRAY:
                writeArray((Object[]) v);
                break;
            case VAL_SERIALIZABLE:
                writeSerializable((Serializable) v);
                break;
            default:
                throw new RuntimeException("Parcel: unable to marshal value " + v);
        }
    }

    /**
     * Flatten the name of the class of the Parcelable and its contents
     * into the parcel.
     *
     * @param p The Parcelable object to be written.
     * @param parcelableFlags Contextual flags as per
     * {@link Parcelable#writeToParcel(Parcel, int) Parcelable.writeToParcel()}.
     */
    public final void writeParcelable(@Nullable Parcelable p, int parcelableFlags) {
        if (p == null) {
            writeString(null);
            return;
        }
        writeParcelableCreator(p);
        p.writeToParcel(this, parcelableFlags);
    }

    /**
     * Flatten the name of the class of the Parcelable into this Parcel.
     *
     * @param p The Parcelable object to be written.
     * @see #readParcelableCreator
     */
    public final void writeParcelableCreator(@NonNull Parcelable p) {
        String name = p.getClass().getName();
        writeString(name);
    }

    /**
     * A map used by {@link #maybeWriteSquashed} to keep track of what parcelables have
     * been seen, and what positions they were written. The value is the absolute position of
     * each parcelable.
     */
    private ArrayMap<Parcelable, Integer> mWrittenSquashableParcelables;

    private void ensureWrittenSquashableParcelables() {
        if (mWrittenSquashableParcelables != null) {
            return;
        }
        mWrittenSquashableParcelables = new ArrayMap<>();
    }

    private boolean mAllowSquashing = false;

    /**
     * Allow "squashing" writes in {@link #maybeWriteSquashed}. This allows subsequent calls to
     * {@link #maybeWriteSquashed(Parcelable)} to "squash" the same instances into one in a Parcel.
     *
     * Typically, this method is called at the beginning of {@link Parcelable#writeToParcel}. The
     * caller must retain the return value from this method and call {@link #restoreAllowSquashing}
     * with it.
     *
     * See {@link #maybeWriteSquashed(Parcelable)} for the details.
     *
     * @see #restoreAllowSquashing(boolean)
     * @see #maybeWriteSquashed(Parcelable)
     * @see #readSquashed(SquashReadHelper)
     *
     * @hide
     */
    @TestApi
    public boolean allowSquashing() {
        boolean previous = mAllowSquashing;
        mAllowSquashing = true;
        return previous;
    }

    /**
     * @see #allowSquashing()
     * @hide
     */
    @TestApi
    public void restoreAllowSquashing(boolean previous) {
        mAllowSquashing = previous;
        if (!mAllowSquashing) {
            mWrittenSquashableParcelables = null;
        }
    }

    private void resetSqaushingState() {
        if (mAllowSquashing) {
            Slog.wtf(TAG, "allowSquashing wasn't restored.");
        }
        mWrittenSquashableParcelables = null;
        mReadSquashableParcelables = null;
        mAllowSquashing = false;
    }

    /**
     * A map used by {@link #readSquashed} to cache parcelables. It's a map from
     * an absolute position in a Parcel to the parcelable stored at the position.
     */
    private SparseArray<Parcelable> mReadSquashableParcelables;

    private void ensureReadSquashableParcelables() {
        if (mReadSquashableParcelables != null) {
            return;
        }
        mReadSquashableParcelables = new SparseArray<>();
    }

    /**
     * Write a parcelable with "squash" -- that is, when the same instance is written to the
     * same Parcelable multiple times, instead of writing the entire instance multiple times,
     * only write it once, and in subsequent writes we'll only write the offset to the original
     * object.
     *
     * This approach does not work of the resulting Parcel is copied with {@link #appendFrom} with
     * a non-zero offset, so we do not enable this behavior by default. Instead, we only enable
     * it between {@link #allowSquashing} and {@link #restoreAllowSquashing}, in order to make sure
     * we only do so within each "top level" Parcelable.
     *
     * Usage: Use this method in {@link Parcelable#writeToParcel}.
     * If this method returns TRUE, it's a subsequent call, and the offset is already written,
     * so the caller doesn't have to do anything. If this method returns FALSE, it's the first
     * time for the instance to be written to this parcel. The caller has to proceed with its
     * {@link Parcelable#writeToParcel}.
     *
     * (See {@code ApplicationInfo} for the example.)
     *
     * @param p the target Parcelable to write.
     *
     * @see #allowSquashing()
     * @see #restoreAllowSquashing(boolean)
     * @see #readSquashed(SquashReadHelper)
     *
     * @hide
     */
    public boolean maybeWriteSquashed(@NonNull Parcelable p) {
        if (!mAllowSquashing) {
            // Don't squash, and don't put it in the map either.
            writeInt(0);
            return false;
        }
        ensureWrittenSquashableParcelables();
        final Integer firstPos = mWrittenSquashableParcelables.get(p);
        if (firstPos != null) {
            // Already written.
            // Write the relative offset from the current position to the first position.
            final int pos = dataPosition();

            // We want the offset from the next byte of this integer, so we need to +4.
            writeInt(pos - firstPos + 4);
            return true;
        }
        // First time seen, write a marker.
        writeInt(0);

        // Remember the position.
        final int pos = dataPosition();
        mWrittenSquashableParcelables.put(p, pos);

        // Return false and let the caller actually write the content.
        return false;
    }

    /**
     * Helper function that's used by {@link #readSquashed(SquashReadHelper)}
     * @hide
     */
    public interface SquashReadHelper<T> {
        /** Read and instantiate {@code T} from a Parcel. */
        @NonNull
        T readRawParceled(@NonNull Parcel p);
    }

    /**
     * Read a {@link Parcelable} that's written with {@link #maybeWriteSquashed}.
     *
     * @param reader a callback function that instantiates an instance from a parcel.
     * Typicallly, a lambda to the instructor that takes a {@link Parcel} is passed.
     *
     * @see #maybeWriteSquashed(Parcelable)
     *
     * @hide
     */
    @SuppressWarnings("unchecked")
    @Nullable
    public <T extends Parcelable> T readSquashed(SquashReadHelper<T> reader) {
        final int offset = readInt();
        final int pos = dataPosition();

        if (offset == 0) {
            // First time read. Unparcel, and remember it.
            final T p = reader.readRawParceled(this);
            ensureReadSquashableParcelables();
            mReadSquashableParcelables.put(pos, p);
            return p;
        }
        // Subsequent read.
        final int firstAbsolutePos = pos - offset;

        final Parcelable p = mReadSquashableParcelables.get(firstAbsolutePos);
        if (p == null) {
            final StringBuilder sb = new StringBuilder();
            for (int i = 0; i < mReadSquashableParcelables.size(); i++) {
                sb.append(mReadSquashableParcelables.keyAt(i)).append(' ');
            }
            Slog.wtfStack(TAG, "Map doesn't contain offset "
                    + firstAbsolutePos
                    + " : contains=" + sb.toString());
        }
        return (T) p;
    }

    /**
     * Write a generic serializable object in to a Parcel.  It is strongly
     * recommended that this method be avoided, since the serialization
     * overhead is extremely large, and this approach will be much slower than
     * using the other approaches to writing data in to a Parcel.
     */
    public final void writeSerializable(@Nullable Serializable s) {
        if (s == null) {
            writeString(null);
            return;
        }
        String name = s.getClass().getName();
        writeString(name);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            ObjectOutputStream oos = new ObjectOutputStream(baos);
            oos.writeObject(s);
            oos.close();

            writeByteArray(baos.toByteArray());
        } catch (IOException ioe) {
            throw new BadParcelableException("Parcelable encountered "
                    + "IOException writing serializable object (name = "
                    + name + ")", ioe);
        }
    }

    /** @hide For debugging purposes */
    public static void setStackTraceParceling(boolean enabled) {
        sParcelExceptionStackTrace = enabled;
    }

    /**
     * Special function for writing an exception result at the header of
     * a parcel, to be used when returning an exception from a transaction.
     * Note that this currently only supports a few exception types; any other
     * exception will be re-thrown by this function as a RuntimeException
     * (to be caught by the system's last-resort exception handling when
     * dispatching a transaction).
     *
     * <p>The supported exception types are:
     * <ul>
     * <li>{@link BadParcelableException}
     * <li>{@link IllegalArgumentException}
     * <li>{@link IllegalStateException}
     * <li>{@link NullPointerException}
     * <li>{@link SecurityException}
     * <li>{@link UnsupportedOperationException}
     * <li>{@link NetworkOnMainThreadException}
     * </ul>
     *
     * @param e The Exception to be written.
     *
     * @see #writeNoException
     * @see #readException
     */
    public final void writeException(@NonNull Exception e) {
        // teslalinux: no app-op notes are collected in ATL (AppOpsManager.prefixParcelWithAppOpsIfNeeded
        // writes nothing then), and StrictMode gathers no binder-call violations in-process.
        int code = getExceptionCode(e);
        writeInt(code);
        if (code == 0) {
            if (e instanceof RuntimeException) {
                throw (RuntimeException) e;
            }
            throw new RuntimeException(e);
        }
        writeString(e.getMessage());
        final long timeNow = sParcelExceptionStackTrace ? SystemClock.elapsedRealtime() : 0;
        if (sParcelExceptionStackTrace && (timeNow - sLastWriteExceptionStackTrace
                > WRITE_EXCEPTION_STACK_TRACE_THRESHOLD_MS)) {
            sLastWriteExceptionStackTrace = timeNow;
            writeStackTrace(e);
        } else {
            writeInt(0);
        }
        switch (code) {
            case EX_SERVICE_SPECIFIC:
                writeInt(((ServiceSpecificException) e).errorCode);
                break;
            case EX_PARCELABLE:
                // Write parceled exception prefixed by length
                final int sizePosition = dataPosition();
                writeInt(0);
                writeParcelable((Parcelable) e, Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
                final int payloadPosition = dataPosition();
                setDataPosition(sizePosition);
                writeInt(payloadPosition - sizePosition);
                setDataPosition(payloadPosition);
                break;
        }
    }

    /** @hide */
    public static int getExceptionCode(@NonNull Throwable e) {
        int code = 0;
        if (e instanceof Parcelable
                && (e.getClass().getClassLoader() == Parcelable.class.getClassLoader())) {
            // We only send Parcelable exceptions that are in the
            // BootClassLoader to ensure that the receiver can unpack them
            code = EX_PARCELABLE;
        } else if (e instanceof SecurityException) {
            code = EX_SECURITY;
        } else if (e instanceof BadParcelableException) {
            code = EX_BAD_PARCELABLE;
        } else if (e instanceof IllegalArgumentException) {
            code = EX_ILLEGAL_ARGUMENT;
        } else if (e instanceof NullPointerException) {
            code = EX_NULL_POINTER;
        } else if (e instanceof IllegalStateException) {
            code = EX_ILLEGAL_STATE;
        } else if (e instanceof NetworkOnMainThreadException) {
            code = EX_NETWORK_MAIN_THREAD;
        } else if (e instanceof UnsupportedOperationException) {
            code = EX_UNSUPPORTED_OPERATION;
        } else if (e instanceof ServiceSpecificException) {
            code = EX_SERVICE_SPECIFIC;
        }
        return code;
    }

    /** @hide */
    public void writeStackTrace(@NonNull Throwable e) {
        final int sizePosition = dataPosition();
        writeInt(0); // Header size will be filled in later
        StackTraceElement[] stackTrace = e.getStackTrace();
        final int truncatedSize = Math.min(stackTrace.length, 5);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < truncatedSize; i++) {
            sb.append("\tat ").append(stackTrace[i]).append('\n');
        }
        writeString(sb.toString());
        final int payloadPosition = dataPosition();
        setDataPosition(sizePosition);
        // Write stack trace header size. Used in native side to skip the header
        writeInt(payloadPosition - sizePosition);
        setDataPosition(payloadPosition);
    }

    /**
     * Special function for writing information at the front of the Parcel
     * indicating that no exception occurred.
     *
     * @see #writeException
     * @see #readException
     */
    public final void writeNoException() {
        // teslalinux: see writeException for the app-op prefix.

        // Despite the name of this function ("write no exception"),
        // it should instead be thought of as "write the RPC response
        // header", but because this function name is written out by
        // the AIDL compiler, we're not going to rename it.
        //
        // The response header, in the non-exception case (see also
        // writeException above, also called by the AIDL compiler), is
        // either a 0 (the default case), or EX_HAS_STRICTMODE_REPLY_HEADER if
        // StrictMode has gathered up violations that have occurred
        // during a Binder call, in which case we write out the number
        // of violations and their details, serialized, before the
        // actual RPC respons data.  The receiving end of this is
        // readException(), below.
        // teslalinux: ATL's StrictMode never gathers violations for a binder reply (penaltyGather is
        // only set by the binder thread pool of a remote call), so the header is always the plain 0.
        writeInt(0);
    }

    /**
     * Special function for reading an exception result from the header of
     * a parcel, to be used after receiving the result of a transaction.  This
     * will throw the exception for you if it had been written to the Parcel,
     * otherwise return and let you read the normal result data from the Parcel.
     *
     * @see #writeException
     * @see #writeNoException
     */
    public final void readException() {
        int code = readExceptionCode();
        if (code != 0) {
            String msg = readString();
            readException(code, msg);
        }
    }

    /**
     * Parses the header of a Binder call's response Parcel and
     * returns the exception code.  Deals with lite or fat headers.
     * In the common successful case, this header is generally zero.
     * In less common cases, it's a small negative number and will be
     * followed by an error string.
     *
     * This exists purely for android.database.DatabaseUtils and
     * insulating it from having to handle fat headers as returned by
     * e.g. StrictMode-induced RPC responses.
     *
     * @hide
     */
    @TestApi
    public final int readExceptionCode() {
        int code = readInt();
        if (code == EX_HAS_NOTED_APPOPS_REPLY_HEADER) {
            skipNotedAppOpsHeader();
            // Read next header or real exception if there is no more header
            code = readInt();
        }

        if (code == EX_HAS_STRICTMODE_REPLY_HEADER) {
            int headerSize = readInt();
            if (headerSize == 0) {
                Log.e(TAG, "Unexpected zero-sized Parcel reply header.");
            } else {
                // Currently the only thing in the header is StrictMode stacks,
                // but discussions around event/RPC tracing suggest we might
                // put that here too.  If so, switch on sub-header tags here.
                // But for now, just parse out the StrictMode stuff.
                // teslalinux: no StrictMode handler for remote violations; skip the header payload.
                setDataPosition(dataPosition() - 4 + headerSize);
            }
            // And fat response headers are currently only used when
            // there are no exceptions, so return no error:
            return 0;
        }
        return code;
    }

    /**
     * Throw an exception with the given message. Not intended for use
     * outside the Parcel class.
     *
     * @param code Used to determine which exception class to throw.
     * @param msg The exception message.
     */
    public final void readException(int code, String msg) {
        String remoteStackTrace = null;
        final int remoteStackPayloadSize = readInt();
        if (remoteStackPayloadSize > 0) {
            remoteStackTrace = readString();
        }
        Exception e = createException(code, msg);
        // Attach remote stack trace if availalble
        if (remoteStackTrace != null) {
            RemoteException cause = new RemoteException(
                    "Remote stack trace:\n" + remoteStackTrace, null, false, false);
            appendCause(e, cause);
        }
        SneakyThrow.sneakyThrow(e);
    }

    /**
     * Creates an exception with the given message.
     *
     * @param code Used to determine which exception class to throw.
     * @param msg The exception message.
     */
    private Exception createException(int code, String msg) {
        Exception exception = createExceptionOrNull(code, msg);
        return exception != null
                ? exception
                : new RuntimeException("Unknown exception code: " + code + " msg " + msg);
    }

    /** @hide */
    public Exception createExceptionOrNull(int code, String msg) {
        switch (code) {
            case EX_PARCELABLE:
                if (readInt() > 0) {
                    return (Exception) readParcelable(Parcelable.class.getClassLoader(), java.lang.Exception.class);
                } else {
                    return new RuntimeException(msg + " [missing Parcelable]");
                }
            case EX_SECURITY:
                return new SecurityException(msg);
            case EX_BAD_PARCELABLE:
                return new BadParcelableException(msg);
            case EX_ILLEGAL_ARGUMENT:
                return new IllegalArgumentException(msg);
            case EX_NULL_POINTER:
                return new NullPointerException(msg);
            case EX_ILLEGAL_STATE:
                return new IllegalStateException(msg);
            case EX_NETWORK_MAIN_THREAD:
                return new NetworkOnMainThreadException();
            case EX_UNSUPPORTED_OPERATION:
                return new UnsupportedOperationException(msg);
            case EX_SERVICE_SPECIFIC:
                return new ServiceSpecificException(readInt(), msg);
            default:
                return null;
        }
    }

    /**
     * Read an integer value from the parcel at the current dataPosition().
     */
    public int readInt() { // teslalinux: not final (GVariant*Parcel)
        return nativeReadInt(mNativePtr);
    }

    /**
     * Read a long integer value from the parcel at the current dataPosition().
     */
    public final long readLong() {
        return nativeReadLong(mNativePtr);
    }

    /**
     * Read a floating point value from the parcel at the current
     * dataPosition().
     */
    public final float readFloat() {
        return nativeReadFloat(mNativePtr);
    }

    /**
     * Read a double precision floating point value from the parcel at the
     * current dataPosition().
     */
    public final double readDouble() {
        return nativeReadDouble(mNativePtr);
    }

    /**
     * Read a string value from the parcel at the current dataPosition().
     */
    @Nullable
    public String readString() { // teslalinux: not final (GVariant*Parcel)
        return readString16();
    }

    /** {@hide} */
    public final @Nullable String readString8() {
        return mReadWriteHelper.readString8(this);
    }

    /** {@hide} */
    public final @Nullable String readString16() {
        return mReadWriteHelper.readString16(this);
    }

    /**
     * Read a string without going though a {@link ReadWriteHelper}.  Subclasses of
     * {@link ReadWriteHelper} must use this method instead of {@link #readString} to avoid
     * infinity recursive calls.
     *
     * @hide
     */
    public @Nullable String readStringNoHelper() {
        return readString16NoHelper();
    }

    /** {@hide} */
    public @Nullable String readString8NoHelper() {
        return nativeReadString8(mNativePtr);
    }

    /** {@hide} */
    public @Nullable String readString16NoHelper() {
        return nativeReadString16(mNativePtr);
    }

    /**
     * Read a boolean value from the parcel at the current dataPosition().
     */
    public final boolean readBoolean() {
        return readInt() != 0;
    }

    /**
     * Read a CharSequence value from the parcel at the current dataPosition().
     * @hide
     */
    @Nullable
    public final CharSequence readCharSequence() {
        return TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(this);
    }

    /**
     * Read an object from the parcel at the current dataPosition().
     */
    public final IBinder readStrongBinder() {
        final IBinder result = nativeReadStrongBinder(mNativePtr);

        // If it's a reply from a method with @PropagateAllowBlocking, then inherit allow-blocking
        // from the object that returned it.
        if (result != null && hasFlags(
                FLAG_IS_REPLY_FROM_BLOCKING_ALLOWED_OBJECT | FLAG_PROPAGATE_ALLOW_BLOCKING)) {
            Binder.allowBlocking(result);
        }
        return result;
    }

    /**
     * Read a FileDescriptor from the parcel at the current dataPosition().
     */
    public final ParcelFileDescriptor readFileDescriptor() {
        FileDescriptor fd = nativeReadFileDescriptor(mNativePtr);
        return fd != null ? new ParcelFileDescriptor(fd) : null;
    }

    /** {@hide} */
    public final FileDescriptor readRawFileDescriptor() {
        return nativeReadFileDescriptor(mNativePtr);
    }

    /**
     * {@hide}
     * Read and return a new array of FileDescriptors from the parcel.
     * @return the FileDescriptor array, or null if the array is null.
     **/
    @Nullable
    public final FileDescriptor[] createRawFileDescriptorArray() {
        int N = readInt();
        if (N < 0) {
            return null;
        }
        FileDescriptor[] f = new FileDescriptor[N];
        for (int i = 0; i < N; i++) {
            f[i] = readRawFileDescriptor();
        }
        return f;
    }

    /**
     * {@hide}
     * Read an array of FileDescriptors from a parcel.
     * The passed array must be exactly the length of the array in the parcel.
     * @return the FileDescriptor array, or null if the array is null.
     **/
    public final void readRawFileDescriptorArray(FileDescriptor[] val) {
        int N = readInt();
        if (N == val.length) {
            for (int i=0; i<N; i++) {
                val[i] = readRawFileDescriptor();
            }
        } else {
            throw new RuntimeException("bad array lengths");
        }
    }

    /**
     * Read a byte value from the parcel at the current dataPosition().
     */
    public byte readByte() { // teslalinux: not final (GVariant*Parcel)
        return (byte)(readInt() & 0xff);
    }

    /**
     * Please use {@link #readBundle(ClassLoader)} instead (whose data must have
     * been written with {@link #writeBundle}.  Read into an existing Map object
     * from the parcel at the current dataPosition().
     *
     * @deprecated Consider using {@link #readBundle(ClassLoader)} as stated above, in case this
     *      method is still preferred use the type-safer version {@link #readMap(Map, ClassLoader,
     *      Class, Class)} starting from Android {@link Build.VERSION_CODES#TIRAMISU}.
     */
    @Deprecated
    public final void readMap(@NonNull Map outVal, @Nullable ClassLoader loader) {
        readMapInternal(outVal, loader, /* clazzKey */ null, /* clazzValue */ null);
    }

    /**
     * Same as {@link #readMap(Map, ClassLoader)} but accepts {@code clazzKey} and
     * {@code clazzValue} parameter as the types required for each key and value pair.
     *
     * @throws BadParcelableException If the item to be deserialized is not an instance of that
     * class or any of its children class
     */
    public <K, V> void readMap(@NonNull Map<? super K, ? super V> outVal,
            @Nullable ClassLoader loader, @NonNull Class<K> clazzKey,
            @NonNull Class<V> clazzValue) {
        Objects.requireNonNull(clazzKey);
        Objects.requireNonNull(clazzValue);
        readMapInternal(outVal, loader, clazzKey, clazzValue);
    }

    /**
     * Read into an existing List object from the parcel at the current
     * dataPosition(), using the given class loader to load any enclosed
     * Parcelables.  If it is null, the default class loader is used.
     *
     * @deprecated Use the type-safer version {@link #readList(List, ClassLoader, Class)} starting
     *      from Android {@link Build.VERSION_CODES#TIRAMISU}. Also consider changing the format to
     *      use {@link #readTypedList(List, Parcelable.Creator)} if possible (eg. if the items'
     *      class is final) since this is also more performant. Note that changing to the latter
     *      also requires changing the writes.
     */
    @Deprecated
    // teslalinux: ATL's earlier Parcel declared the checked ReflectiveOperationException here, and
    // ATL classes outside android.os still catch it around this call; the clause is kept so they
    // compile. Nothing throws it (failures are BadParcelableException, as in AOSP), and a throws
    // clause is not part of the method's binary signature, so apps see the AOSP method.
    public final void readList(@NonNull List outVal, @Nullable ClassLoader loader)
            throws ReflectiveOperationException {
        int N = readInt();
        readListInternal(outVal, N, loader, /* clazz */ null);
    }

    /**
     * Same as {@link #readList(List, ClassLoader)} but accepts {@code clazz} parameter as
     * the type required for each item.
     *
     * <p><b>Warning: </b> if the list contains items implementing the {@link Parcelable} interface,
     * the class that implements {@link Parcelable} has to be the immediately
     * enclosing class of the runtime type of its CREATOR field (that is,
     * {@link Class#getEnclosingClass()} has to return the parcelable implementing class),
     * otherwise this method might throw an exception. If the Parcelable class does not enclose the
     * CREATOR, use the deprecated {@link #readList(List, ClassLoader)} instead.
     *
     * @throws BadParcelableException Throws BadParcelableException if the item to be deserialized
     * is not an instance of that class or any of its children classes or there was an error
     * trying to instantiate an element.
     */
    public <T> void readList(@NonNull List<? super T> outVal,
            @Nullable ClassLoader loader, @NonNull Class<T> clazz) {
        Objects.requireNonNull(clazz);
        int n = readInt();
        readListInternal(outVal, n, loader, clazz);
    }

    /**
     * Please use {@link #readBundle(ClassLoader)} instead (whose data must have
     * been written with {@link #writeBundle}.  Read and return a new HashMap
     * object from the parcel at the current dataPosition(), using the given
     * class loader to load any enclosed Parcelables.  Returns null if
     * the previously written map object was null.
     *
     * @deprecated Consider using {@link #readBundle(ClassLoader)} as stated above, in case this
     *      method is still preferred use the type-safer version {@link #readHashMap(ClassLoader,
     *      Class, Class)} starting from Android {@link Build.VERSION_CODES#TIRAMISU}.
     */
    @Deprecated
    @Nullable
    public HashMap readHashMap(@Nullable ClassLoader loader) {
        return readHashMapInternal(loader, /* clazzKey */ null, /* clazzValue */ null);
    }

    /**
     * Same as {@link #readHashMap(ClassLoader)} but accepts {@code clazzKey} and
     * {@code clazzValue} parameter as the types required for each key and value pair.
     *
     * @throws BadParcelableException if the item to be deserialized is not an instance of that
     * class or any of its children class
     */
    @SuppressLint({"ConcreteCollection", "NullableCollection"})
    @Nullable
    public <K, V> HashMap<K, V> readHashMap(@Nullable ClassLoader loader,
            @NonNull Class<? extends K> clazzKey, @NonNull Class<? extends V> clazzValue) {
        Objects.requireNonNull(clazzKey);
        Objects.requireNonNull(clazzValue);
        return readHashMapInternal(loader, clazzKey, clazzValue);
    }

    /**
     * Read and return a new Bundle object from the parcel at the current
     * dataPosition().  Returns null if the previously written Bundle object was
     * null.
     */
    @Nullable
    public final Bundle readBundle() {
        return readBundle(null);
    }

    /**
     * Read and return a new Bundle object from the parcel at the current
     * dataPosition(), using the given class loader to initialize the class
     * loader of the Bundle for later retrieval of Parcelable objects.
     * Returns null if the previously written Bundle object was null.
     */
    @Nullable
    public final Bundle readBundle(@Nullable ClassLoader loader) {
        int length = readInt();
        if (length < 0) {
            if (Bundle.DEBUG) Log.d(TAG, "null bundle: length=" + length);
            return null;
        }

        final Bundle bundle = new Bundle(this, length);
        if (loader != null) {
            bundle.setClassLoader(loader);
        }
        return bundle;
    }

    /**
     * Read and return a new Bundle object from the parcel at the current
     * dataPosition().  Returns null if the previously written Bundle object was
     * null.
     */
    @Nullable
    public final PersistableBundle readPersistableBundle() {
        return readPersistableBundle(null);
    }

    /**
     * Read and return a new Bundle object from the parcel at the current
     * dataPosition(), using the given class loader to initialize the class
     * loader of the Bundle for later retrieval of Parcelable objects.
     * Returns null if the previously written Bundle object was null.
     */
    @Nullable
    public final PersistableBundle readPersistableBundle(@Nullable ClassLoader loader) {
        int length = readInt();
        if (length < 0) {
            if (Bundle.DEBUG) Log.d(TAG, "null bundle: length=" + length);
            return null;
        }

        final PersistableBundle bundle = new PersistableBundle(this, length);
        if (loader != null) {
            bundle.setClassLoader(loader);
        }
        return bundle;
    }

    /**
     * Read a Size from the parcel at the current dataPosition().
     */
    @NonNull
    public final Size readSize() {
        final int width = readInt();
        final int height = readInt();
        return new Size(width, height);
    }

    /**
     * Read a SizeF from the parcel at the current dataPosition().
     */
    @NonNull
    public final SizeF readSizeF() {
        final float width = readFloat();
        final float height = readFloat();
        return new SizeF(width, height);
    }

    /**
     * Read and return a byte[] object from the parcel.
     */
    @Nullable
    public final byte[] createByteArray() {
        return nativeCreateByteArray(mNativePtr);
    }

    /**
     * Read a byte[] object from the parcel and copy it into the
     * given byte array.
     */
    public final void readByteArray(@NonNull byte[] val) {
        boolean valid = nativeReadByteArray(mNativePtr, val, (val != null) ? val.length : 0);
        if (!valid) {
            throw new RuntimeException("bad array lengths");
        }
    }

    /**
     * Read a blob of data from the parcel and return it as a byte array.
     * @see #writeBlob(byte[], int, int)
     */
    @Nullable
    public final byte[] readBlob() {
        return nativeReadBlob(mNativePtr);
    }

    /**
     * Read and return a String[] object from the parcel.
     * {@hide}
     */
    @Nullable
    public final String[] readStringArray() {
        return createString16Array();
    }

    /**
     * Read and return a CharSequence[] object from the parcel.
     * {@hide}
     */
    @Nullable
    public final CharSequence[] readCharSequenceArray() {
        CharSequence[] array = null;

        int length = readInt();
        if (length >= 0)
        {
            array = new CharSequence[length];

            for (int i = 0 ; i < length ; i++)
            {
                array[i] = readCharSequence();
            }
        }

        return array;
    }

    /**
     * Read and return an ArrayList&lt;CharSequence&gt; object from the parcel.
     * {@hide}
     */
    @Nullable
    public final ArrayList<CharSequence> readCharSequenceList() {
        ArrayList<CharSequence> array = null;

        int length = readInt();
        if (length >= 0) {
            array = new ArrayList<CharSequence>(length);

            for (int i = 0 ; i < length ; i++) {
                array.add(readCharSequence());
            }
        }

        return array;
    }

    /**
     * Read and return a new ArrayList object from the parcel at the current
     * dataPosition().  Returns null if the previously written list object was
     * null.  The given class loader will be used to load any enclosed
     * Parcelables.
     *
     * @deprecated Use the type-safer version {@link #readArrayList(ClassLoader, Class)} starting
     *      from Android {@link Build.VERSION_CODES#TIRAMISU}. Also consider changing the format to
     *      use {@link #createTypedArrayList(Parcelable.Creator)} if possible (eg. if the items'
     *      class is final) since this is also more performant. Note that changing to the latter
     *      also requires changing the writes.
     */
    @Deprecated
    @Nullable
    public ArrayList readArrayList(@Nullable ClassLoader loader) {
        return readArrayListInternal(loader, /* clazz */ null);
    }

    /**
     * Same as {@link #readArrayList(ClassLoader)} but accepts {@code clazz} parameter as
     * the type required for each item.
     *
     * <p><b>Warning: </b> if the list contains items implementing the {@link Parcelable} interface,
     * the class that implements {@link Parcelable} has to be the immediately
     * enclosing class of the runtime type of its CREATOR field (that is,
     * {@link Class#getEnclosingClass()} has to return the parcelable implementing class),
     * otherwise this method might throw an exception. If the Parcelable class does not enclose the
     * CREATOR, use the deprecated {@link #readArrayList(ClassLoader)} instead.
     *
     * @throws BadParcelableException Throws BadParcelableException if the item to be deserialized
     * is not an instance of that class or any of its children classes or there was an error
     * trying to instantiate an element.
     */
    @SuppressLint({"ConcreteCollection", "NullableCollection"})
    @Nullable
    public <T> ArrayList<T> readArrayList(@Nullable ClassLoader loader,
            @NonNull Class<? extends T> clazz) {
        Objects.requireNonNull(clazz);
        return readArrayListInternal(loader, clazz);
    }

    /**
     * Read and return a new Object array from the parcel at the current
     * dataPosition().  Returns null if the previously written array was
     * null.  The given class loader will be used to load any enclosed
     * Parcelables.
     *
     * @deprecated Use the type-safer version {@link #readArray(ClassLoader, Class)} starting from
     *      Android {@link Build.VERSION_CODES#TIRAMISU}. Also consider changing the format to use
     *      {@link #createTypedArray(Parcelable.Creator)} if possible (eg. if the items' class is
     *      final) since this is also more performant. Note that changing to the latter also
     *      requires changing the writes.
     */
    @Deprecated
    @Nullable
    public Object[] readArray(@Nullable ClassLoader loader) {
        return readArrayInternal(loader, /* clazz */ null);
    }

    /**
     * Same as {@link #readArray(ClassLoader)} but accepts {@code clazz} parameter as
     * the type required for each item.
     *
     * <p><b>Warning: </b> if the list contains items implementing the {@link Parcelable} interface,
     * the class that implements {@link Parcelable} has to be the immediately
     * enclosing class of the runtime type of its CREATOR field (that is,
     * {@link Class#getEnclosingClass()} has to return the parcelable implementing class),
     * otherwise this method might throw an exception. If the Parcelable class does not enclose the
     * CREATOR, use the deprecated {@link #readArray(ClassLoader)} instead.
     *
     * @throws BadParcelableException Throws BadParcelableException if the item to be deserialized
     * is not an instance of that class or any of its children classes or there was an error
     * trying to instantiate an element.
     */
    @SuppressLint({"ArrayReturn", "NullableCollection"})
    @Nullable
    public <T> T[] readArray(@Nullable ClassLoader loader, @NonNull Class<T> clazz) {
        Objects.requireNonNull(clazz);
        return readArrayInternal(loader, clazz);
    }

    /**
     * Read and return a new SparseArray object from the parcel at the current
     * dataPosition().  Returns null if the previously written list object was
     * null.  The given class loader will be used to load any enclosed
     * Parcelables.
     *
     * @deprecated Use the type-safer version {@link #readSparseArray(ClassLoader, Class)} starting
     *      from Android {@link Build.VERSION_CODES#TIRAMISU}. Also consider changing the format to
     *      use {@link #createTypedSparseArray(Parcelable.Creator)} if possible (eg. if the items'
     *      class is final) since this is also more performant. Note that changing to the latter
     *      also requires changing the writes.
     */
    @Deprecated
    @Nullable
    public <T> SparseArray<T> readSparseArray(@Nullable ClassLoader loader) {
        return readSparseArrayInternal(loader, /* clazz */ null);
    }

    /**
     * Same as {@link #readSparseArray(ClassLoader)} but accepts {@code clazz} parameter as
     * the type required for each item.
     *
     * <p><b>Warning: </b> if the list contains items implementing the {@link Parcelable} interface,
     * the class that implements {@link Parcelable} has to be the immediately
     * enclosing class of the runtime type of its CREATOR field (that is,
     * {@link Class#getEnclosingClass()} has to return the parcelable implementing class),
     * otherwise this method might throw an exception. If the Parcelable class does not enclose the
     * CREATOR, use the deprecated {@link #readSparseArray(ClassLoader)} instead.
     *
     * @throws BadParcelableException Throws BadParcelableException if the item to be deserialized
     * is not an instance of that class or any of its children classes or there was an error
     * trying to instantiate an element.
     */
    @Nullable
    public <T> SparseArray<T> readSparseArray(@Nullable ClassLoader loader,
            @NonNull Class<? extends T> clazz) {
        Objects.requireNonNull(clazz);
        return readSparseArrayInternal(loader, clazz);
    }

    /**
     * Read and return a new SparseBooleanArray object from the parcel at the current
     * dataPosition().  Returns null if the previously written list object was
     * null.
     */
    @Nullable
    public final SparseBooleanArray readSparseBooleanArray() {
        int N = readInt();
        if (N < 0) {
            return null;
        }
        SparseBooleanArray sa = new SparseBooleanArray(N);
        readSparseBooleanArrayInternal(sa, N);
        return sa;
    }

    /**
     * Read and return a new SparseIntArray object from the parcel at the current
     * dataPosition(). Returns null if the previously written array object was null.
     * @hide
     */
    @Nullable
    public final SparseIntArray readSparseIntArray() {
        int N = readInt();
        if (N < 0) {
            return null;
        }
        SparseIntArray sa = new SparseIntArray(N);
        readSparseIntArrayInternal(sa, N);
        return sa;
    }

    /**
     * Read and return a new ArrayList containing a particular object type from
     * the parcel that was written with {@link #writeTypedList} at the
     * current dataPosition().  Returns null if the
     * previously written list object was null.  The list <em>must</em> have
     * previously been written via {@link #writeTypedList} with the same object
     * type.
     *
     * @return A newly created ArrayList containing objects with the same data
     *         as those that were previously written.
     *
     * @see #writeTypedList
     */
    @Nullable
    public final <T> ArrayList<T> createTypedArrayList(@NonNull Parcelable.Creator<T> c) {
        int N = readInt();
        if (N < 0) {
            return null;
        }
        ArrayList<T> l = new ArrayList<T>(N);
        while (N > 0) {
            l.add(readTypedObject(c));
            N--;
        }
        return l;
    }

    /**
     * Read into the given List items containing a particular object type
     * that were written with {@link #writeTypedList} at the
     * current dataPosition().  The list <em>must</em> have
     * previously been written via {@link #writeTypedList} with the same object
     * type.
     *
     * @see #writeTypedList
     */
    public final <T> void readTypedList(@NonNull List<T> list, @NonNull Parcelable.Creator<T> c) {
        int M = list.size();
        int N = readInt();
        int i = 0;
        for (; i < M && i < N; i++) {
            list.set(i, readTypedObject(c));
        }
        for (; i<N; i++) {
            list.add(readTypedObject(c));
        }
        for (; i<M; i++) {
            list.remove(N);
        }
    }

    /**
     * Read into a new {@link SparseArray} items containing a particular object type
     * that were written with {@link #writeTypedSparseArray(SparseArray, int)} at the
     * current dataPosition().  The list <em>must</em> have previously been written
     * via {@link #writeTypedSparseArray(SparseArray, int)} with the same object type.
     *
     * @param creator The creator to use when for instantiation.
     *
     * @return A newly created {@link SparseArray} containing objects with the same data
     *         as those that were previously written.
     *
     * @see #writeTypedSparseArray(SparseArray, int)
     */
    public final @Nullable <T extends Parcelable> SparseArray<T> createTypedSparseArray(
            @NonNull Parcelable.Creator<T> creator) {
        final int count = readInt();
        if (count < 0) {
            return null;
        }
        final SparseArray<T> array = new SparseArray<>(count);
        for (int i = 0; i < count; i++) {
            final int index = readInt();
            final T value = readTypedObject(creator);
            array.append(index, value);
        }
        return array;
    }

    /**
     * Read into a new {@link ArrayMap} with string keys items containing a particular
     * object type that were written with {@link #writeTypedArrayMap(ArrayMap, int)} at the
     * current dataPosition().  The list <em>must</em> have previously been written
     * via {@link #writeTypedArrayMap(ArrayMap, int)} with the same object type.
     *
     * @param creator The creator to use when for instantiation.
     *
     * @return A newly created {@link ArrayMap} containing objects with the same data
     *         as those that were previously written.
     *
     * @see #writeTypedArrayMap(ArrayMap, int)
     */
    public final @Nullable <T extends Parcelable> ArrayMap<String, T> createTypedArrayMap(
            @NonNull Parcelable.Creator<T> creator) {
        final int count = readInt();
        if (count < 0) {
            return null;
        }
        final ArrayMap<String, T> map = new ArrayMap<>(count);
        for (int i = 0; i < count; i++) {
            final String key = readString();
            final T value = readTypedObject(creator);
            map.append(key, value);
        }
        return map;
    }

    /**
     * Read and return a new ArrayList containing String objects from
     * the parcel that was written with {@link #writeStringList} at the
     * current dataPosition().  Returns null if the
     * previously written list object was null.
     *
     * @return A newly created ArrayList containing strings with the same data
     *         as those that were previously written.
     *
     * @see #writeStringList
     */
    @Nullable
    public final ArrayList<String> createStringArrayList() {
        int N = readInt();
        if (N < 0) {
            return null;
        }
        ArrayList<String> l = new ArrayList<String>(N);
        while (N > 0) {
            l.add(readString());
            N--;
        }
        return l;
    }

    /**
     * Read and return a new ArrayList containing IBinder objects from
     * the parcel that was written with {@link #writeBinderList} at the
     * current dataPosition().  Returns null if the
     * previously written list object was null.
     *
     * @return A newly created ArrayList containing strings with the same data
     *         as those that were previously written.
     *
     * @see #writeBinderList
     */
    @Nullable
    public final ArrayList<IBinder> createBinderArrayList() {
        int N = readInt();
        if (N < 0) {
            return null;
        }
        ArrayList<IBinder> l = new ArrayList<IBinder>(N);
        while (N > 0) {
            l.add(readStrongBinder());
            N--;
        }
        return l;
    }

    /**
     * Read and return a new ArrayList containing T (IInterface) objects from
     * the parcel that was written with {@link #writeInterfaceList} at the
     * current dataPosition().  Returns null if the
     * previously written list object was null.
     *
     * @return A newly created ArrayList containing T (IInterface)
     *
     * @see #writeInterfaceList
     */
    @SuppressLint({"ConcreteCollection", "NullableCollection"})
    @Nullable
    public final <T extends IInterface> ArrayList<T> createInterfaceArrayList(
            @NonNull Function<IBinder, T> asInterface) {
        int N = readInt();
        if (N < 0) {
            return null;
        }
        ArrayList<T> l = new ArrayList<T>(N);
        while (N > 0) {
            l.add(asInterface.apply(readStrongBinder()));
            N--;
        }
        return l;
    }

    /**
     * Read into the given List items String objects that were written with
     * {@link #writeStringList} at the current dataPosition().
     *
     * @see #writeStringList
     */
    public final void readStringList(@NonNull List<String> list) {
        int M = list.size();
        int N = readInt();
        int i = 0;
        for (; i < M && i < N; i++) {
            list.set(i, readString());
        }
        for (; i<N; i++) {
            list.add(readString());
        }
        for (; i<M; i++) {
            list.remove(N);
        }
    }

    /**
     * Read into the given List items IBinder objects that were written with
     * {@link #writeBinderList} at the current dataPosition().
     *
     * @see #writeBinderList
     */
    public final void readBinderList(@NonNull List<IBinder> list) {
        int M = list.size();
        int N = readInt();
        int i = 0;
        for (; i < M && i < N; i++) {
            list.set(i, readStrongBinder());
        }
        for (; i<N; i++) {
            list.add(readStrongBinder());
        }
        for (; i<M; i++) {
            list.remove(N);
        }
    }

    /**
     * Read into the given List items IInterface objects that were written with
     * {@link #writeInterfaceList} at the current dataPosition().
     *
     * @see #writeInterfaceList
     */
    public final <T extends IInterface> void readInterfaceList(@NonNull List<T> list,
            @NonNull Function<IBinder, T> asInterface) {
        int M = list.size();
        int N = readInt();
        int i = 0;
        for (; i < M && i < N; i++) {
            list.set(i, asInterface.apply(readStrongBinder()));
        }
        for (; i<N; i++) {
            list.add(asInterface.apply(readStrongBinder()));
        }
        for (; i<M; i++) {
            list.remove(N);
        }
    }

    /**
     * Read the list of {@code Parcelable} objects at the current data position into the
     * given {@code list}. The contents of the {@code list} are replaced. If the serialized
     * list was {@code null}, {@code list} is cleared.
     *
     * @see #writeParcelableList(List, int)
     *
     * @deprecated Use the type-safer version {@link #readParcelableList(List, ClassLoader, Class)}
     *      starting from Android {@link Build.VERSION_CODES#TIRAMISU}. Also consider changing the
     *      format to use {@link #readTypedList(List, Parcelable.Creator)} if possible (eg. if the
     *      items' class is final) since this is also more performant. Note that changing to the
     *      latter also requires changing the writes.
     */
    @Deprecated
    @NonNull
    public final <T extends Parcelable> List<T> readParcelableList(@NonNull List<T> list,
            @Nullable ClassLoader cl) {
        return readParcelableListInternal(list, cl, /*clazz*/ null);
    }

    /**
     * Same as {@link #readParcelableList(List, ClassLoader)} but accepts {@code clazz} parameter as
     * the type required for each item.
     *
     * <p><b>Warning: </b> if the list contains items implementing the {@link Parcelable} interface,
     * the class that implements {@link Parcelable} has to be the immediately
     * enclosing class of the runtime type of its CREATOR field (that is,
     * {@link Class#getEnclosingClass()} has to return the parcelable implementing class),
     * otherwise this method might throw an exception. If the Parcelable class does not enclose the
     * CREATOR, use the deprecated {@link #readParcelableList(List, ClassLoader)} instead.
     *
     * @throws BadParcelableException Throws BadParcelableException if the item to be deserialized
     * is not an instance of that class or any of its children classes or there was an error
     * trying to instantiate an element.
     */
    @NonNull
    public <T> List<T> readParcelableList(@NonNull List<T> list,
            @Nullable ClassLoader cl, @NonNull Class<? extends T> clazz) {
        Objects.requireNonNull(list);
        Objects.requireNonNull(clazz);
        return readParcelableListInternal(list, cl, clazz);
    }

    /**
     * @param clazz The type of the object expected or {@code null} for performing no checks.
     */
    @NonNull
    private <T> List<T> readParcelableListInternal(@NonNull List<T> list,
            @Nullable ClassLoader cl, @Nullable Class<? extends T> clazz) {
        final int n = readInt();
        if (n == -1) {
            list.clear();
            return list;
        }

        final int m = list.size();
        int i = 0;
        for (; i < m && i < n; i++) {
            list.set(i, (T) readParcelableInternal(cl, clazz));
        }
        for (; i < n; i++) {
            list.add((T) readParcelableInternal(cl, clazz));
        }
        for (; i < m; i++) {
            list.remove(n);
        }
        return list;
    }

    /**
     * Read and return a new array containing a particular object type from
     * the parcel at the current dataPosition().  Returns null if the
     * previously written array was null.  The array <em>must</em> have
     * previously been written via {@link #writeTypedArray} with the same
     * object type.
     *
     * @return A newly created array containing objects with the same data
     *         as those that were previously written.
     *
     * @see #writeTypedArray
     */
    @Nullable
    public final <T> T[] createTypedArray(@NonNull Parcelable.Creator<T> c) {
        int N = readInt();
        if (N < 0) {
            return null;
        }
        T[] l = c.newArray(N);
        for (int i=0; i<N; i++) {
            l[i] = readTypedObject(c);
        }
        return l;
    }

    public final <T> void readTypedArray(@NonNull T[] val, @NonNull Parcelable.Creator<T> c) {
        int N = readInt();
        if (N == val.length) {
            for (int i=0; i<N; i++) {
                val[i] = readTypedObject(c);
            }
        } else {
            throw new RuntimeException("bad array lengths");
        }
    }

    /**
     * @deprecated
     * @hide
     */
    @Deprecated
    public final <T> T[] readTypedArray(Parcelable.Creator<T> c) {
        return createTypedArray(c);
    }

    /**
     * Read and return a typed Parcelable object from a parcel.
     * Returns null if the previous written object was null.
     * The object <em>must</em> have previous been written via
     * {@link #writeTypedObject} with the same object type.
     *
     * @return A newly created object of the type that was previously
     *         written.
     *
     * @see #writeTypedObject
     */
    @Nullable
    public final <T> T readTypedObject(@NonNull Parcelable.Creator<T> c) {
        if (readInt() != 0) {
            return c.createFromParcel(this);
        } else {
            return null;
        }
    }

    /**
     * Read a new multi-dimensional array from a parcel.  If you want to read Parcelable or
     * IInterface values, use {@link #readFixedArray(Object, Parcelable.Creator)} or
     * {@link #readFixedArray(Object, Function)}.
     * @param val the destination array to hold the read values.
     *
     * @see #writeTypedArray
     * @see #readBooleanArray
     * @see #readByteArray
     * @see #readCharArray
     * @see #readIntArray
     * @see #readLongArray
     * @see #readFloatArray
     * @see #readDoubleArray
     * @see #readBinderArray
     * @see #readInterfaceArray
     * @see #readTypedArray
     */
    public <T> void readFixedArray(@NonNull T val) {
        Class<?> componentType = val.getClass().getComponentType();
        if (componentType == boolean.class) {
            readBooleanArray((boolean[]) val);
        } else if (componentType == byte.class) {
            readByteArray((byte[]) val);
        } else if (componentType == char.class) {
            readCharArray((char[]) val);
        } else if (componentType == int.class) {
            readIntArray((int[]) val);
        } else if (componentType == long.class) {
            readLongArray((long[]) val);
        } else if (componentType == float.class) {
            readFloatArray((float[]) val);
        } else if (componentType == double.class) {
            readDoubleArray((double[]) val);
        } else if (componentType == IBinder.class) {
            readBinderArray((IBinder[]) val);
        } else if (componentType.isArray()) {
            int length = readInt();
            if (length != Array.getLength(val)) {
                throw new BadParcelableException("Bad length: expected " + Array.getLength(val)
                    + ", but got " + length);
            }
            for (int i = 0; i < length; i++) {
                readFixedArray(Array.get(val, i));
            }
        } else {
            throw new BadParcelableException("Unknown type for fixed-size array: " + componentType);
        }
    }

    /**
     * Read a new multi-dimensional array of typed interfaces from a parcel.
     * If you want to read Parcelable values, use
     * {@link #readFixedArray(Object, Parcelable.Creator)}. For values of other types, use
     * {@link #readFixedArray(Object)}.
     * @param val the destination array to hold the read values.
     */
    public <T, S extends IInterface> void readFixedArray(@NonNull T val,
            @NonNull Function<IBinder, S> asInterface) {
        Class<?> componentType = val.getClass().getComponentType();
        if (IInterface.class.isAssignableFrom(componentType)) {
            readInterfaceArray((S[]) val, asInterface);
        } else if (componentType.isArray()) {
            int length = readInt();
            if (length != Array.getLength(val)) {
                throw new BadParcelableException("Bad length: expected " + Array.getLength(val)
                    + ", but got " + length);
            }
            for (int i = 0; i < length; i++) {
                readFixedArray(Array.get(val, i), asInterface);
            }
        } else {
            throw new BadParcelableException("Unknown type for fixed-size array: " + componentType);
        }
    }

    /**
     * Read a new multi-dimensional array of typed parcelables from a parcel.
     * If you want to read IInterface values, use
     * {@link #readFixedArray(Object, Function)}. For values of other types, use
     * {@link #readFixedArray(Object)}.
     * @param val the destination array to hold the read values.
     */
    public <T, S extends Parcelable> void readFixedArray(@NonNull T val,
            @NonNull Parcelable.Creator<S> c) {
        Class<?> componentType = val.getClass().getComponentType();
        if (Parcelable.class.isAssignableFrom(componentType)) {
            readTypedArray((S[]) val, c);
        } else if (componentType.isArray()) {
            int length = readInt();
            if (length != Array.getLength(val)) {
                throw new BadParcelableException("Bad length: expected " + Array.getLength(val)
                    + ", but got " + length);
            }
            for (int i = 0; i < length; i++) {
                readFixedArray(Array.get(val, i), c);
            }
        } else {
            throw new BadParcelableException("Unknown type for fixed-size array: " + componentType);
        }
    }

    private void ensureClassHasExpectedDimensions(@NonNull Class<?> cls, int numDimension) {
        if (numDimension <= 0) {
            throw new BadParcelableException("Fixed-size array should have dimensions.");
        }

        for (int i = 0; i < numDimension; i++) {
            if (!cls.isArray()) {
                throw new BadParcelableException("Array has fewer dimensions than expected: "
                    + numDimension);
            }
            cls = cls.getComponentType();
        }
        if (cls.isArray()) {
            throw new BadParcelableException("Array has more dimensions than expected: "
                + numDimension);
        }
    }

    /**
     * Read and return a new multi-dimensional array from a parcel.  Returns null if the
     * previously written array object is null.  If you want to read Parcelable or
     * IInterface values, use {@link #createFixedArray(Class, Parcelable.Creator, int[])} or
     * {@link #createFixedArray(Class, Function, int[])}.
     * @param cls  the Class object for the target array type. (e.g. int[][].class)
     * @param dimensions an array of int representing length of each dimension.
     *
     * @see #writeTypedArray
     * @see #createBooleanArray
     * @see #createByteArray
     * @see #createCharArray
     * @see #createIntArray
     * @see #createLongArray
     * @see #createFloatArray
     * @see #createDoubleArray
     * @see #createBinderArray
     * @see #createInterfaceArray
     * @see #createTypedArray
     */
    @Nullable
    public <T> T createFixedArray(@NonNull Class<T> cls, @NonNull int... dimensions) {
        // Check if type matches with dimensions
        // If type is one-dimensional array, delegate to other creators
        // Otherwise, create an multi-dimensional array at once and then fill it with readFixedArray

        ensureClassHasExpectedDimensions(cls, dimensions.length);

        T val = null;
        final Class<?> componentType = cls.getComponentType();
        if (componentType == boolean.class) {
            val = (T) createBooleanArray();
        } else if (componentType == byte.class) {
            val = (T) createByteArray();
        } else if (componentType == char.class) {
            val = (T) createCharArray();
        } else if (componentType == int.class) {
            val = (T) createIntArray();
        } else if (componentType == long.class) {
            val = (T) createLongArray();
        } else if (componentType == float.class) {
            val = (T) createFloatArray();
        } else if (componentType == double.class) {
            val = (T) createDoubleArray();
        } else if (componentType == IBinder.class) {
            val = (T) createBinderArray();
        } else if (componentType.isArray()) {
            int length = readInt();
            if (length < 0) {
                return null;
            }
            if (length != dimensions[0]) {
                throw new BadParcelableException("Bad length: expected " + dimensions[0]
                    + ", but got " + length);
            }

            // Create a multi-dimensional array with an innermost component type and dimensions
            Class<?> innermost = componentType.getComponentType();
            while (innermost.isArray()) {
                innermost = innermost.getComponentType();
            }
            val = (T) Array.newInstance(innermost, dimensions);
            for (int i = 0; i < length; i++) {
                readFixedArray(Array.get(val, i));
            }
            return val;
        } else {
            throw new BadParcelableException("Unknown type for fixed-size array: " + componentType);
        }

        // Check if val is null (which is OK) or has the expected size.
        // This check doesn't have to be multi-dimensional because multi-dimensional arrays
        // are created with expected dimensions.
        if (val != null && Array.getLength(val) != dimensions[0]) {
            throw new BadParcelableException("Bad length: expected " + dimensions[0] + ", but got "
                + Array.getLength(val));
        }
        return val;
    }

    /**
     * Read and return a new multi-dimensional array of typed interfaces from a parcel.
     * Returns null if the previously written array object is null.  If you want to read
     * Parcelable values, use {@link #createFixedArray(Class, Parcelable.Creator, int[])}.
     * For values of other types use {@link #createFixedArray(Class, int[])}.
     * @param cls  the Class object for the target array type. (e.g. IFoo[][].class)
     * @param dimensions an array of int representing length of each dimension.
     */
    @Nullable
    public <T, S extends IInterface> T createFixedArray(@NonNull Class<T> cls,
            @NonNull Function<IBinder, S> asInterface, @NonNull int... dimensions) {
        // Check if type matches with dimensions
        // If type is one-dimensional array, delegate to other creators
        // Otherwise, create an multi-dimensional array at once and then fill it with readFixedArray

        ensureClassHasExpectedDimensions(cls, dimensions.length);

        T val = null;
        final Class<?> componentType = cls.getComponentType();
        if (IInterface.class.isAssignableFrom(componentType)) {
            val = (T) createInterfaceArray(n -> (S[]) Array.newInstance(componentType, n),
                    asInterface);
        } else if (componentType.isArray()) {
            int length = readInt();
            if (length < 0) {
                return null;
            }
            if (length != dimensions[0]) {
                throw new BadParcelableException("Bad length: expected " + dimensions[0]
                    + ", but got " + length);
            }

            // Create a multi-dimensional array with an innermost component type and dimensions
            Class<?> innermost = componentType.getComponentType();
            while (innermost.isArray()) {
                innermost = innermost.getComponentType();
            }
            val = (T) Array.newInstance(innermost, dimensions);
            for (int i = 0; i < length; i++) {
                readFixedArray(Array.get(val, i), asInterface);
            }
            return val;
        } else {
            throw new BadParcelableException("Unknown type for fixed-size array: " + componentType);
        }

        // Check if val is null (which is OK) or has the expected size.
        // This check doesn't have to be multi-dimensional because multi-dimensional arrays
        // are created with expected dimensions.
        if (val != null && Array.getLength(val) != dimensions[0]) {
            throw new BadParcelableException("Bad length: expected " + dimensions[0] + ", but got "
                + Array.getLength(val));
        }
        return val;
    }

    /**
     * Read and return a new multi-dimensional array of typed parcelables from a parcel.
     * Returns null if the previously written array object is null.  If you want to read
     * IInterface values, use {@link #createFixedArray(Class, Function, int[])}.
     * For values of other types use {@link #createFixedArray(Class, int[])}.
     * @param cls  the Class object for the target array type. (e.g. Foo[][].class)
     * @param dimensions an array of int representing length of each dimension.
     */
    @Nullable
    public <T, S extends Parcelable> T createFixedArray(@NonNull Class<T> cls,
            @NonNull Parcelable.Creator<S> c, @NonNull int... dimensions) {
        // Check if type matches with dimensions
        // If type is one-dimensional array, delegate to other creators
        // Otherwise, create an multi-dimensional array at once and then fill it with readFixedArray

        ensureClassHasExpectedDimensions(cls, dimensions.length);

        T val = null;
        final Class<?> componentType = cls.getComponentType();
        if (Parcelable.class.isAssignableFrom(componentType)) {
            val = (T) createTypedArray(c);
        } else if (componentType.isArray()) {
            int length = readInt();
            if (length < 0) {
                return null;
            }
            if (length != dimensions[0]) {
                throw new BadParcelableException("Bad length: expected " + dimensions[0]
                    + ", but got " + length);
            }

            // Create a multi-dimensional array with an innermost component type and dimensions
            Class<?> innermost = componentType.getComponentType();
            while (innermost.isArray()) {
                innermost = innermost.getComponentType();
            }
            val = (T) Array.newInstance(innermost, dimensions);
            for (int i = 0; i < length; i++) {
                readFixedArray(Array.get(val, i), c);
            }
            return val;
        } else {
            throw new BadParcelableException("Unknown type for fixed-size array: " + componentType);
        }

        // Check if val is null (which is OK) or has the expected size.
        // This check doesn't have to be multi-dimensional because multi-dimensional arrays
        // are created with expected dimensions.
        if (val != null && Array.getLength(val) != dimensions[0]) {
            throw new BadParcelableException("Bad length: expected " + dimensions[0] + ", but got "
                + Array.getLength(val));
        }
        return val;
    }

    /**
     * Write a heterogeneous array of Parcelable objects into the Parcel.
     * Each object in the array is written along with its class name, so
     * that the correct class can later be instantiated.  As a result, this
     * has significantly more overhead than {@link #writeTypedArray}, but will
     * correctly handle an array containing more than one type of object.
     *
     * @param value The array of objects to be written.
     * @param parcelableFlags Contextual flags as per
     * {@link Parcelable#writeToParcel(Parcel, int) Parcelable.writeToParcel()}.
     *
     * @see #writeTypedArray
     */
    public final <T extends Parcelable> void writeParcelableArray(@Nullable T[] value,
            int parcelableFlags) {
        if (value != null) {
            int N = value.length;
            writeInt(N);
            for (int i=0; i<N; i++) {
                writeParcelable(value[i], parcelableFlags);
            }
        } else {
            writeInt(-1);
        }
    }

    /**
     * Read a typed object from a parcel.  The given class loader will be
     * used to load any enclosed Parcelables.  If it is null, the default class
     * loader will be used.
     */
    @Nullable
    // teslalinux: ATL's earlier Parcel declared the checked ReflectiveOperationException here, and
    // ATL classes outside android.os still catch it around this call; the clause is kept so they
    // compile. Nothing throws it (failures are BadParcelableException, as in AOSP), and a throws
    // clause is not part of the method's binary signature, so apps see the AOSP method.
    public final Object readValue(@Nullable ClassLoader loader)
            throws ReflectiveOperationException {
        return readValue(loader, /* clazz */ null);
    }


    /**
     * @see #readValue(int, ClassLoader, Class, Class[])
     */
    @Nullable
    private <T> T readValue(@Nullable ClassLoader loader, @Nullable Class<T> clazz,
            @Nullable Class<?>... itemTypes) {
        int type = readInt();
        final T object;
        if (isLengthPrefixed(type)) {
            int length = readInt();
            int start = dataPosition();
            object = readValue(type, loader, clazz, itemTypes);
            int actual = dataPosition() - start;
            if (actual != length) {
                Slog.wtfStack(TAG,
                        "Unparcelling of " + object + " of type " + Parcel.valueTypeToString(type)
                                + "  consumed " + actual + " bytes, but " + length + " expected.");
            }
        } else {
            object = readValue(type, loader, clazz, itemTypes);
        }
        return object;
    }

    /**
     * This will return a {@link BiFunction} for length-prefixed types that deserializes the object
     * when {@link BiFunction#apply} is called (the arguments correspond to the ones of {@link
     * #readValue(int, ClassLoader, Class, Class[])} after the class loader), for other types it
     * will return the object itself.
     *
     * <p>After calling {@link BiFunction#apply} the parcel cursor will not change. Note that you
     * shouldn't recycle the parcel, not at least until all objects have been retrieved. No
     * synchronization attempts are made.
     *
     * </p>The function returned implements {@link #equals(Object)} and {@link #hashCode()}. Two
     * function objects are equal if either of the following is true:
     * <ul>
     *   <li>{@link BiFunction#apply} has been called on both and both objects returned are equal.
     *   <li>{@link BiFunction#apply} hasn't been called on either one and everything below is true:
     *   <ul>
     *       <li>The {@code loader} parameters used to retrieve each are equal.
     *       <li>They both have the same type.
     *       <li>They have the same payload length.
     *       <li>Their binary content is the same.
     *   </ul>
     * </ul>
     *
     * @hide
     */
    @Nullable
    public Object readLazyValue(@Nullable ClassLoader loader) {
        int start = dataPosition();
        int type = readInt();
        if (isLengthPrefixed(type)) {
            int objectLength = readInt();
            if (objectLength < 0) {
                return null;
            }
            int end = MathUtils.addOrThrow(dataPosition(), objectLength);
            int valueLength = end - start;
            setDataPosition(end);
            return new LazyValue(this, start, valueLength, type, loader);
        } else {
            return readValue(type, loader, /* clazz */ null);
        }
    }


    private static final class LazyValue implements BiFunction<Class<?>, Class<?>[], Object> {
        /**
         *                      |   4B   |   4B   |
         * mSource = Parcel{... |  type  | length | object | ...}
         *                      a        b        c        d
         * length = d - c
         * mPosition = a
         * mLength = d - a
         */
        private final int mPosition;
        private final int mLength;
        private final int mType;
        @Nullable private final ClassLoader mLoader;
        @Nullable private Object mObject;

        /**
         * This goes from non-null to null once. Always check the nullability of this object before
         * performing any operations, either involving itself or mObject since the happens-before
         * established by this volatile will guarantee visibility of either. We can assume this
         * parcel won't change anymore.
         */
        @Nullable private volatile Parcel mSource;

        LazyValue(Parcel source, int position, int length, int type, @Nullable ClassLoader loader) {
            mSource = requireNonNull(source);
            mPosition = position;
            mLength = length;
            mType = type;
            mLoader = loader;
        }

        @Override
        public Object apply(@Nullable Class<?> clazz, @Nullable Class<?>[] itemTypes) {
            Parcel source = mSource;
            if (source != null) {
                synchronized (source) {
                    // Check mSource != null guarantees callers won't ever see different objects.
                    if (mSource != null) {
                        int restore = source.dataPosition();
                        try {
                            source.setDataPosition(mPosition);
                            mObject = source.readValue(mLoader, clazz, itemTypes);
                        } finally {
                            source.setDataPosition(restore);
                        }
                        mSource = null;
                    }
                }
            }
            return mObject;
        }

        public void writeToParcel(Parcel out) {
            Parcel source = mSource;
            if (source != null) {
                synchronized (source) {
                    if (mSource != null) {
                        out.appendFrom(source, mPosition, mLength);
                        return;
                    }
                }
            }

            out.writeValue(mObject);
        }

        public boolean hasFileDescriptors() {
            Parcel source = mSource;
            if (source != null) {
                synchronized (source) {
                    if (mSource != null) {
                        return source.hasFileDescriptors(mPosition, mLength);
                    }
                }
            }

            return Parcel.hasFileDescriptors(mObject);
        }

        @Override
        public String toString() {
            return (mSource != null)
                    ? "Supplier{" + valueTypeToString(mType) + "@" + mPosition + "+" + mLength + '}'
                    : "Supplier{" + mObject + "}";
        }

        /**
         * We're checking if the *lazy value* is equal to another one, not if the *object*
         * represented by the lazy value is equal to the other one. So, if there are two lazy values
         * and one of them has been deserialized but the other hasn't this will always return false.
         */
        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof LazyValue)) {
                return false;
            }
            LazyValue value = (LazyValue) other;
            // Check if they are either both serialized or both deserialized.
            Parcel source = mSource;
            Parcel otherSource = value.mSource;
            if ((source == null) != (otherSource == null)) {
                return false;
            }
            // If both are deserialized, compare the live objects.
            if (source == null) {
                // Note that here it's guaranteed that both mObject references contain valid values
                // (possibly null) since mSource will have provided the memory barrier for those and
                // once deserialized we never go back to serialized state.
                return Objects.equals(mObject, value.mObject);
            }
            // Better safely fail here since this could mean we get different objects.
            if (!Objects.equals(mLoader, value.mLoader)) {
                return false;
            }
            // Otherwise compare metadata prior to comparing payload.
            if (mType != value.mType || mLength != value.mLength) {
                return false;
            }
            // Finally we compare the payload.
            return Parcel.compareData(source, mPosition, otherSource, value.mPosition, mLength);
        }

        @Override
        public int hashCode() {
            // Accessing mSource first to provide memory barrier for mObject
            return Objects.hash(mSource == null, mObject, mLoader, mType, mLength);
        }
    }

    /** Same as {@link #readValue(ClassLoader, Class, Class[])} without any item types. */
    private <T> T readValue(int type, @Nullable ClassLoader loader, @Nullable Class<T> clazz) {
        // Avoids allocating Class[0] array
        return readValue(type, loader, clazz, (Class<?>[]) null);
    }

    /**
     * Reads a value from the parcel of type {@code type}. Does NOT read the int representing the
     * type first.
     *
     * @param clazz The type of the object expected or {@code null} for performing no checks.
     * @param itemTypes If the value is a container, these represent the item types (eg. for a list
     *                  it's the item type, for a map, it's the key type, followed by the value
     *                  type).
     */
    @SuppressWarnings("unchecked")
    @Nullable
    private <T> T readValue(int type, @Nullable ClassLoader loader, @Nullable Class<T> clazz,
            @Nullable Class<?>... itemTypes) {
        final Object object;
        switch (type) {
            case VAL_NULL:
                object = null;
                break;

            case VAL_STRING:
                object = readString();
                break;

            case VAL_INTEGER:
                object = readInt();
                break;

            case VAL_MAP:
                checkTypeToUnparcel(clazz, HashMap.class);
                Class<?> keyType = getOrNull(itemTypes, 0);
                Class<?> valueType = getOrNull(itemTypes, 1);
                checkArgument((keyType == null) == (valueType == null));
                object = readHashMapInternal(loader, keyType, valueType);
                break;

            case VAL_PARCELABLE:
                object = readParcelableInternal(loader, clazz);
                break;

            case VAL_SHORT:
                object = (short) readInt();
                break;

            case VAL_LONG:
                object = readLong();
                break;

            case VAL_FLOAT:
                object = readFloat();
                break;

            case VAL_DOUBLE:
                object = readDouble();
                break;

            case VAL_BOOLEAN:
                object = readInt() == 1;
                break;

            case VAL_CHARSEQUENCE:
                object = readCharSequence();
                break;

            case VAL_LIST: {
                checkTypeToUnparcel(clazz, ArrayList.class);
                Class<?> itemType = getOrNull(itemTypes, 0);
                object = readArrayListInternal(loader, itemType);
                break;
            }
            case VAL_BOOLEANARRAY:
                object = createBooleanArray();
                break;

            case VAL_BYTEARRAY:
                object = createByteArray();
                break;

            case VAL_STRINGARRAY:
                object = readStringArray();
                break;

            case VAL_CHARSEQUENCEARRAY:
                object = readCharSequenceArray();
                break;

            case VAL_IBINDER:
                object = readStrongBinder();
                break;

            case VAL_OBJECTARRAY: {
                Class<?> itemType = getOrNull(itemTypes, 0);
                checkArrayTypeToUnparcel(clazz, (itemType != null) ? itemType : Object.class);
                object = readArrayInternal(loader, itemType);
                break;
            }
            case VAL_INTARRAY:
                object = createIntArray();
                break;

            case VAL_LONGARRAY:
                object = createLongArray();
                break;

            case VAL_BYTE:
                object = readByte();
                break;

            case VAL_SERIALIZABLE:
                object = readSerializableInternal(loader, clazz);
                break;

            case VAL_PARCELABLEARRAY: {
                Class<?> itemType = getOrNull(itemTypes, 0);
                checkArrayTypeToUnparcel(clazz, (itemType != null) ? itemType : Parcelable.class);
                object = readParcelableArrayInternal(loader, itemType);
                break;
            }
            case VAL_SPARSEARRAY: {
                checkTypeToUnparcel(clazz, SparseArray.class);
                Class<?> itemType = getOrNull(itemTypes, 0);
                object = readSparseArrayInternal(loader, itemType);
                break;
            }
            case VAL_SPARSEBOOLEANARRAY:
                object = readSparseBooleanArray();
                break;

            case VAL_BUNDLE:
                object = readBundle(loader); // loading will be deferred
                break;

            case VAL_PERSISTABLEBUNDLE:
                object = readPersistableBundle(loader);
                break;

            case VAL_SIZE:
                object = readSize();
                break;

            case VAL_SIZEF:
                object = readSizeF();
                break;

            case VAL_DOUBLEARRAY:
                object = createDoubleArray();
                break;

            case VAL_CHAR:
                object = (char) readInt();
                break;

            case VAL_SHORTARRAY:
                object = createShortArray();
                break;

            case VAL_CHARARRAY:
                object = createCharArray();
                break;

            case VAL_FLOATARRAY:
                object = createFloatArray();
                break;

            default:
                int off = dataPosition() - 4;
                throw new BadParcelableException(
                    "Parcel " + this + ": Unmarshalling unknown type code " + type
                            + " at offset " + off);
        }
        if (object != null && clazz != null && !clazz.isInstance(object)) {
            throw new BadTypeParcelableException("Unparcelled object " + object
                    + " is not an instance of required class " + clazz.getName()
                    + " provided in the parameter");
        }
        return (T) object;
    }

    private boolean isLengthPrefixed(int type) {
        // In general, we want custom types and containers of custom types to be length-prefixed,
        // this allows clients (eg. Bundle) to skip their content during deserialization. The
        // exception to this is Bundle, since Bundle is already length-prefixed and already copies
        // the correspondent section of the parcel internally.
        switch (type) {
            case VAL_MAP:
            case VAL_PARCELABLE:
            case VAL_LIST:
            case VAL_SPARSEARRAY:
            case VAL_PARCELABLEARRAY:
            case VAL_OBJECTARRAY:
            case VAL_SERIALIZABLE:
                return true;
            default:
                return false;
        }
    }

    /**
     * Checks that an array of type T[], where T is {@code componentTypeToUnparcel}, is a subtype of
     * {@code requiredArrayType}.
     */
    private void checkArrayTypeToUnparcel(@Nullable Class<?> requiredArrayType,
            Class<?> componentTypeToUnparcel) {
        if (requiredArrayType != null) {
            // In Java 12, we could use componentTypeToUnparcel.arrayType() for the check
            Class<?> requiredComponentType = requiredArrayType.getComponentType();
            if (requiredComponentType == null) {
                throw new BadTypeParcelableException(
                        "About to unparcel an array but type "
                                + requiredArrayType.getCanonicalName()
                                + " required by caller is not an array.");
            }
            checkTypeToUnparcel(requiredComponentType, componentTypeToUnparcel);
        }
    }

    /**
     * Checks that {@code typeToUnparcel} is a subtype of {@code requiredType}, if {@code
     * requiredType} is not {@code null}.
     */
    private void checkTypeToUnparcel(@Nullable Class<?> requiredType, Class<?> typeToUnparcel) {
        if (requiredType != null && !requiredType.isAssignableFrom(typeToUnparcel)) {
            throw new BadTypeParcelableException(
                    "About to unparcel a " + typeToUnparcel.getCanonicalName()
                            + ", which is not a subtype of type " + requiredType.getCanonicalName()
                            + " required by caller.");
        }
    }

    /**
     * Read and return a new Parcelable from the parcel.  The given class loader
     * will be used to load any enclosed Parcelables.  If it is null, the default
     * class loader will be used.
     * @param loader A ClassLoader from which to instantiate the Parcelable
     * object, or null for the default class loader.
     * @return Returns the newly created Parcelable, or null if a null
     * object has been written.
     * @throws BadParcelableException Throws BadParcelableException if there
     * was an error trying to instantiate the Parcelable.
     *
     * @deprecated Use the type-safer version {@link #readParcelable(ClassLoader, Class)} starting
     *      from Android {@link Build.VERSION_CODES#TIRAMISU}. Also consider changing the format to
     *      use {@link Parcelable.Creator#createFromParcel(Parcel)} if possible since this is also
     *      more performant. Note that changing to the latter also requires changing the writes.
     */
    @Deprecated
    @Nullable
    // teslalinux: ATL's earlier Parcel declared the checked ReflectiveOperationException here, and
    // ATL classes outside android.os still catch it around this call; the clause is kept so they
    // compile. Nothing throws it (failures are BadParcelableException, as in AOSP), and a throws
    // clause is not part of the method's binary signature, so apps see the AOSP method.
    public final <T extends Parcelable> T readParcelable(@Nullable ClassLoader loader)
            throws ReflectiveOperationException {
        return readParcelableInternal(loader, /* clazz */ null);
    }

    /**
     * Same as {@link #readParcelable(ClassLoader)} but accepts {@code clazz} parameter as the type
     * required for each item.
     *
     * <p><b>Warning: </b> the class that implements {@link Parcelable} has to be the immediately
     * enclosing class of the runtime type of its CREATOR field (that is,
     * {@link Class#getEnclosingClass()} has to return the parcelable implementing class),
     * otherwise this method might throw an exception. If the Parcelable class does not enclose the
     * CREATOR, use the deprecated {@link #readParcelable(ClassLoader)} instead.
     *
     * @throws BadParcelableException Throws BadParcelableException if the item to be deserialized
     * is not an instance of that class or any of its children classes or there was an error
     * trying to instantiate an element.
     */
    @Nullable
    public <T> T readParcelable(@Nullable ClassLoader loader, @NonNull Class<T> clazz) {
        Objects.requireNonNull(clazz);
        return readParcelableInternal(loader, clazz);
    }

    /**
     * @param clazz The type of the parcelable expected or {@code null} for performing no checks.
     */
    @SuppressWarnings("unchecked")
    @Nullable
    private <T> T readParcelableInternal(@Nullable ClassLoader loader, @Nullable Class<T> clazz) {
        Parcelable.Creator<?> creator = readParcelableCreatorInternal(loader, clazz);
        if (creator == null) {
            return null;
        }
        if (creator instanceof Parcelable.ClassLoaderCreator<?>) {
            Parcelable.ClassLoaderCreator<?> classLoaderCreator =
                    (Parcelable.ClassLoaderCreator<?>) creator;
            return (T) classLoaderCreator.createFromParcel(this, loader);
        }
        return (T) creator.createFromParcel(this);
    }

    /** @hide */
    @SuppressWarnings("unchecked")
    @Nullable
    public final <T extends Parcelable> T readCreator(@NonNull Parcelable.Creator<?> creator,
            @Nullable ClassLoader loader) {
        if (creator instanceof Parcelable.ClassLoaderCreator<?>) {
          Parcelable.ClassLoaderCreator<?> classLoaderCreator =
              (Parcelable.ClassLoaderCreator<?>) creator;
          return (T) classLoaderCreator.createFromParcel(this, loader);
        }
        return (T) creator.createFromParcel(this);
    }

    /**
     * Read and return a Parcelable.Creator from the parcel. The given class loader will be used to
     * load the {@link Parcelable.Creator}. If it is null, the default class loader will be used.
     *
     * @param loader A ClassLoader from which to instantiate the {@link Parcelable.Creator}
     * object, or null for the default class loader.
     * @return the previously written {@link Parcelable.Creator}, or null if a null Creator was
     * written.
     * @throws BadParcelableException Throws BadParcelableException if there was an error trying to
     * read the {@link Parcelable.Creator}.
     *
     * @see #writeParcelableCreator
     *
     * @deprecated Use the type-safer version {@link #readParcelableCreator(ClassLoader, Class)}
     *       starting from Android {@link Build.VERSION_CODES#TIRAMISU}.
     */
    @Deprecated
    @Nullable
    public final Parcelable.Creator<?> readParcelableCreator(@Nullable ClassLoader loader) {
        return readParcelableCreatorInternal(loader, /* clazz */ null);
    }

    /**
     * Same as {@link #readParcelableCreator(ClassLoader)} but accepts {@code clazz} parameter
     * as the required type.
     *
     * <p><b>Warning: </b> the class that implements {@link Parcelable} has to be the immediately
     * enclosing class of the runtime type of its CREATOR field (that is,
     * {@link Class#getEnclosingClass()} has to return the parcelable implementing class),
     * otherwise this method might throw an exception. If the Parcelable class does not enclose the
     * CREATOR, use the deprecated {@link #readParcelableCreator(ClassLoader) instead.
     *
     * @throws BadParcelableException Throws BadParcelableException if the item to be deserialized
     * is not an instance of that class or any of its children classes or there there was an error
     * trying to read the {@link Parcelable.Creator}.
     */
    @Nullable
    public <T> Parcelable.Creator<T> readParcelableCreator(
            @Nullable ClassLoader loader, @NonNull Class<T> clazz) {
        Objects.requireNonNull(clazz);
        return readParcelableCreatorInternal(loader, clazz);
    }

    /**
     * @param clazz The type of the parcelable expected or {@code null} for performing no checks.
     */
    @SuppressWarnings("unchecked")
    @Nullable
    private <T> Parcelable.Creator<T> readParcelableCreatorInternal(
            @Nullable ClassLoader loader, @Nullable Class<T> clazz) {
        String name = readString();
        if (name == null) {
            return null;
        }

        Pair<Parcelable.Creator<?>, Class<?>> creatorAndParcelableClass;
        synchronized (sPairedCreators) {
            HashMap<String, Pair<Parcelable.Creator<?>, Class<?>>> map =
                    sPairedCreators.get(loader);
            if (map == null) {
                sPairedCreators.put(loader, new HashMap<>());
                mCreators.put(loader, new HashMap<>());
                creatorAndParcelableClass = null;
            } else {
                creatorAndParcelableClass = map.get(name);
            }
        }

        if (creatorAndParcelableClass != null) {
            Parcelable.Creator<?> creator = creatorAndParcelableClass.first;
            Class<?> parcelableClass = creatorAndParcelableClass.second;
            if (clazz != null) {
                if (!clazz.isAssignableFrom(parcelableClass)) {
                    throw new BadTypeParcelableException("Parcelable creator " + name + " is not "
                            + "a subclass of required class " + clazz.getName()
                            + " provided in the parameter");
                }
            }

            return (Parcelable.Creator<T>) creator;
        }

        Parcelable.Creator<?> creator;
        Class<?> parcelableClass;
        try {
            // If loader == null, explicitly emulate Class.forName(String) "caller
            // classloader" behavior.
            ClassLoader parcelableClassLoader =
                    (loader == null ? getClass().getClassLoader() : loader);
            // Avoid initializing the Parcelable class until we know it implements
            // Parcelable and has the necessary CREATOR field. http://b/1171613.
            parcelableClass = Class.forName(name, false /* initialize */,
                    parcelableClassLoader);
            if (!Parcelable.class.isAssignableFrom(parcelableClass)) {
                throw new BadParcelableException("Parcelable protocol requires subclassing "
                        + "from Parcelable on class " + name);
            }
            if (clazz != null) {
                if (!clazz.isAssignableFrom(parcelableClass)) {
                    throw new BadTypeParcelableException("Parcelable creator " + name + " is not "
                            + "a subclass of required class " + clazz.getName()
                            + " provided in the parameter");
                }
            }

            Field f = parcelableClass.getField("CREATOR");
            if ((f.getModifiers() & Modifier.STATIC) == 0) {
                throw new BadParcelableException("Parcelable protocol requires "
                        + "the CREATOR object to be static on class " + name);
            }
            Class<?> creatorType = f.getType();
            if (!Parcelable.Creator.class.isAssignableFrom(creatorType)) {
                // Fail before calling Field.get(), not after, to avoid initializing
                // parcelableClass unnecessarily.
                throw new BadParcelableException("Parcelable protocol requires a "
                        + "Parcelable.Creator object called "
                        + "CREATOR on class " + name);
            }
            creator = (Parcelable.Creator<?>) f.get(null);
        } catch (IllegalAccessException e) {
            Log.e(TAG, "Illegal access when unmarshalling: " + name, e);
            throw new BadParcelableException(
                    "IllegalAccessException when unmarshalling: " + name, e);
        } catch (ClassNotFoundException e) {
            Log.e(TAG, "Class not found when unmarshalling: " + name, e);
            throw new BadParcelableException(
                    "ClassNotFoundException when unmarshalling: " + name, e);
        } catch (NoSuchFieldException e) {
            throw new BadParcelableException("Parcelable protocol requires a "
                    + "Parcelable.Creator object called "
                    + "CREATOR on class " + name, e);
        }
        if (creator == null) {
            throw new BadParcelableException("Parcelable protocol requires a "
                    + "non-null Parcelable.Creator object called "
                    + "CREATOR on class " + name);
        }

        synchronized (sPairedCreators) {
            sPairedCreators.get(loader).put(name, Pair.create(creator, parcelableClass));
            mCreators.get(loader).put(name, creator);
        }

        return (Parcelable.Creator<T>) creator;
    }

    /**
     * Read and return a new Parcelable array from the parcel.
     * The given class loader will be used to load any enclosed
     * Parcelables.
     * @return the Parcelable array, or null if the array is null
     *
     * @deprecated Use the type-safer version {@link #readParcelableArray(ClassLoader, Class)}
     *      starting from Android {@link Build.VERSION_CODES#TIRAMISU}. Also consider changing the
     *      format to use {@link #createTypedArray(Parcelable.Creator)} if possible (eg. if the
     *      items' class is final) since this is also more performant. Note that changing to the
     *      latter also requires changing the writes.
     */
    @Deprecated
    @Nullable
    public Parcelable[] readParcelableArray(@Nullable ClassLoader loader) {
        return readParcelableArrayInternal(loader, /* clazz */ null);
    }

    /**
     * Same as {@link #readParcelableArray(ClassLoader)}  but accepts {@code clazz} parameter as
     * the type required for each item.
     *
     * <p><b>Warning: </b> the class that implements {@link Parcelable} has to be the immediately
     * enclosing class of the runtime type of its CREATOR field (that is,
     * {@link Class#getEnclosingClass()} has to return the parcelable implementing class),
     * otherwise this method might throw an exception. If the Parcelable class does not enclose the
     * CREATOR, use the deprecated {@link #readParcelableArray(ClassLoader)} instead.
     *
     * @throws BadParcelableException Throws BadParcelableException if the item to be deserialized
     * is not an instance of that class or any of its children classes or there was an error
     * trying to instantiate an element.
     */
    @SuppressLint({"ArrayReturn", "NullableCollection"})
    @Nullable
    public <T> T[] readParcelableArray(@Nullable ClassLoader loader, @NonNull Class<T> clazz) {
        return readParcelableArrayInternal(loader, requireNonNull(clazz));
    }

    @SuppressWarnings("unchecked")
    @Nullable
    private <T> T[] readParcelableArrayInternal(@Nullable ClassLoader loader,
            @Nullable Class<T> clazz) {
        int n = readInt();
        if (n < 0) {
            return null;
        }
        T[] p = (T[]) ((clazz == null) ? new Parcelable[n] : Array.newInstance(clazz, n));
        for (int i = 0; i < n; i++) {
            p[i] = readParcelableInternal(loader, clazz);
        }
        return p;
    }

    /**
     * Read and return a new Serializable object from the parcel.
     * @return the Serializable object, or null if the Serializable name
     * wasn't found in the parcel.
     *
     * Unlike {@link #readSerializable(ClassLoader, Class)}, it uses the nearest valid class loader
     * up the execution stack to instantiate the Serializable object.
     *
     * @deprecated Use the type-safer version {@link #readSerializable(ClassLoader, Class)} starting
     *       from Android {@link Build.VERSION_CODES#TIRAMISU}.
     */
    @Deprecated
    @Nullable
    public Serializable readSerializable() {
        return readSerializableInternal(/* loader */ null, /* clazz */ null);
    }

    /**
     * Same as {@link #readSerializable()} but accepts {@code loader} and {@code clazz} parameters.
     *
     * @param loader A ClassLoader from which to instantiate the Serializable object,
     * or null for the default class loader.
     * @param clazz The type of the object expected.
     *
     * @throws BadParcelableException Throws BadParcelableException if the item to be deserialized
     * is not an instance of that class or any of its children class or there there was an error
     * deserializing the object.
     */
    @Nullable
    public <T> T readSerializable(@Nullable ClassLoader loader, @NonNull Class<T> clazz) {
        Objects.requireNonNull(clazz);
        return readSerializableInternal(
                loader == null ? getClass().getClassLoader() : loader, clazz);
    }

    /**
     * @param clazz The type of the serializable expected or {@code null} for performing no checks
     */
    @Nullable
    private <T> T readSerializableInternal(@Nullable final ClassLoader loader,
            @Nullable Class<T> clazz) {
        String name = readString();
        if (name == null) {
            // For some reason we were unable to read the name of the Serializable (either there
            // is nothing left in the Parcel to read, or the next value wasn't a String), so
            // return null, which indicates that the name wasn't found in the parcel.
            return null;
        }

        try {
            if (clazz != null && loader != null) {
                // If custom classloader is provided, resolve the type of serializable using the
                // name, then check the type before deserialization. As in this case we can resolve
                // the class the same way as ObjectInputStream, using the provided classloader.
                Class<?> cl = Class.forName(name, false, loader);
                if (!clazz.isAssignableFrom(cl)) {
                    throw new BadTypeParcelableException("Serializable object "
                            + cl.getName() + " is not a subclass of required class "
                            + clazz.getName() + " provided in the parameter");
                }
            }
            byte[] serializedData = createByteArray();
            ByteArrayInputStream bais = new ByteArrayInputStream(serializedData);
            ObjectInputStream ois = new ObjectInputStream(bais) {
                @Override
                protected Class<?> resolveClass(ObjectStreamClass osClass)
                        throws IOException, ClassNotFoundException {
                    // try the custom classloader if provided
                    if (loader != null) {
                        Class<?> c = Class.forName(osClass.getName(), false, loader);
                        return Objects.requireNonNull(c);
                    }
                    return super.resolveClass(osClass);
                }
            };
            T object = (T) ois.readObject();
            if (clazz != null && loader == null) {
                // If custom classloader is not provided, check the type of the serializable using
                // the deserialized object, as we cannot resolve the class the same way as
                // ObjectInputStream.
                if (!clazz.isAssignableFrom(object.getClass())) {
                    throw new BadTypeParcelableException("Serializable object "
                            + object.getClass().getName() + " is not a subclass of required class "
                            + clazz.getName() + " provided in the parameter");
                }
            }
            return object;
        } catch (IOException ioe) {
            throw new BadParcelableException("Parcelable encountered "
                    + "IOException reading a Serializable object (name = "
                    + name + ")", ioe);
        } catch (ClassNotFoundException cnfe) {
            throw new BadParcelableException("Parcelable encountered "
                    + "ClassNotFoundException reading a Serializable object (name = "
                    + name + ")", cnfe);
        }
    }


    // Left due to the UnsupportedAppUsage. Do not use anymore - use sPairedCreators instead
    private static final HashMap<ClassLoader, HashMap<String, Parcelable.Creator<?>>>
            mCreators = new HashMap<>();

    // Cache of previously looked up CREATOR.createFromParcel() methods for particular classes.
    // Keys are the names of the classes, values are a pair consisting of a parcelable creator,
    // and the class of the parcelable type for the object.
    private static final HashMap<ClassLoader, HashMap<String,
            Pair<Parcelable.Creator<?>, Class<?>>>> sPairedCreators = new HashMap<>();

    /** @hide for internal use only. */
    static protected final Parcel obtain(int obj) {
        throw new UnsupportedOperationException();
    }

    /**
     * teslalinux: for ATL's GVariant parcel subclasses (android.atl). Their buffer funnels every
     * primitive through the subclass's writeByte/readByte, so the subclass sees the whole stream.
     */
    protected Parcel() {
        mNativePtr = new FunnelParcel(this);
        mOwnsNativeParcelObject = true;
    }

    private Parcel(long nativePtr) {
        if (DEBUG_RECYCLE) {
            mStack = new RuntimeException();
        }
        //Log.i(TAG, "Initializing obj=0x" + Integer.toHexString(obj), mStack);
        init(nativePtr);
    }

    private void init(long nativePtr) {
        // teslalinux: every parcel owns its buffer (no parcels wrapping a native libbinder one)
        mNativePtr = nativeCreate();
        mOwnsNativeParcelObject = true;
    }

    private void freeBuffer() {
        mFlags = 0;
        resetSqaushingState();
        if (mOwnsNativeParcelObject) {
            nativeFreeBuffer(mNativePtr);
        }
        mReadWriteHelper = ReadWriteHelper.DEFAULT;
    }

    private void destroy() {
        resetSqaushingState();
        if (mNativePtr != null) {
            if (mOwnsNativeParcelObject) {
                nativeDestroy(mNativePtr);
            }
            mNativePtr = null;
        }
    }

    @Override
    protected void finalize() throws Throwable {
        if (DEBUG_RECYCLE) {
            // we could always have this log on, but it's spammy
            if (!mRecycled) {
                Log.w(TAG, "Client did not call Parcel.recycle()", mStack);
            }
        }
        destroy();
    }

    /**
     * To be replaced by {@link #readMapInternal(Map, int, ClassLoader, Class, Class)}, but keep
     * the old API for compatibility usages.
     */
    /* package */ void readMapInternal(@NonNull Map outVal, int n,
            @Nullable ClassLoader loader) {
        readMapInternal(outVal, n, loader, /* clazzKey */null, /* clazzValue */null);
    }

    @Nullable
    private <K, V> HashMap<K, V> readHashMapInternal(@Nullable ClassLoader loader,
            @NonNull Class<? extends K> clazzKey, @NonNull Class<? extends V> clazzValue) {
        int n = readInt();
        if (n < 0) {
            return null;
        }
        HashMap<K, V> map = new HashMap<>(n);
        readMapInternal(map, n, loader, clazzKey, clazzValue);
        return map;
    }

    private <K, V> void readMapInternal(@NonNull Map<? super K, ? super V> outVal,
            @Nullable ClassLoader loader, @Nullable Class<K> clazzKey,
            @Nullable Class<V> clazzValue) {
        int n = readInt();
        readMapInternal(outVal, n, loader, clazzKey, clazzValue);
    }

    private <K, V> void readMapInternal(@NonNull Map<? super K, ? super V> outVal, int n,
            @Nullable ClassLoader loader, @Nullable Class<K> clazzKey,
            @Nullable Class<V> clazzValue) {
        while (n > 0) {
            K key = readValue(loader, clazzKey);
            V value = readValue(loader, clazzValue);
            outVal.put(key, value);
            n--;
        }
    }

    private void readArrayMapInternal(@NonNull ArrayMap<? super String, Object> outVal,
            int size, @Nullable ClassLoader loader) {
        readArrayMap(outVal, size, /* sorted */ true, /* lazy */ false, loader);
    }

    /**
     * Reads a map into {@code map}.
     *
     * @param sorted Whether the keys are sorted by their hashes, if so we use an optimized path.
     * @param lazy   Whether to populate the map with lazy {@link Function} objects for
     *               length-prefixed values. See {@link Parcel#readLazyValue(ClassLoader)} for more
     *               details.
     * @return a count of the lazy values in the map
     * @hide
     */
    int readArrayMap(ArrayMap<? super String, Object> map, int size, boolean sorted,
            boolean lazy, @Nullable ClassLoader loader) {
        int lazyValues = 0;
        while (size > 0) {
            String key = readString();
            Object value = (lazy) ? readLazyValue(loader) : readValue(loader, /* clazz */ null);
            if (value instanceof LazyValue) {
                lazyValues++;
            }
            if (sorted) {
                map.append(key, value);
            } else {
                map.put(key, value);
            }
            size--;
        }
        if (sorted) {
            map.validate();
        }
        return lazyValues;
    }

    /**
     * @hide For testing only.
     */
    public void readArrayMap(@NonNull ArrayMap<? super String, Object> outVal,
            @Nullable ClassLoader loader) {
        final int N = readInt();
        if (N < 0) {
            return;
        }
        readArrayMapInternal(outVal, N, loader);
    }

    /**
     * Reads an array set.
     *
     * @param loader The class loader to use.
     *
     * @hide
     */
    public @Nullable ArraySet<? extends Object> readArraySet(@Nullable ClassLoader loader) {
        final int size = readInt();
        if (size < 0) {
            return null;
        }
        ArraySet<Object> result = new ArraySet<>(size);
        for (int i = 0; i < size; i++) {
            Object value = readValue(loader, /* clazz */ null);
            result.append(value);
        }
        return result;
    }

    /**
     * The method is replaced by {@link #readListInternal(List, int, ClassLoader, Class)}, however
     * we are keeping this unused method here to allow unsupported app usages.
     */
    private void readListInternal(@NonNull List outVal, int n, @Nullable ClassLoader loader) {
        readListInternal(outVal, n, loader,  /* clazz */ null);
    }

    /**
     * @param clazz The type of the object expected or {@code null} for performing no checks.
     */
    private <T> void readListInternal(@NonNull List<? super T> outVal, int n,
            @Nullable ClassLoader loader, @Nullable Class<T> clazz) {
        while (n > 0) {
            T value = readValue(loader, clazz);
            //Log.d(TAG, "Unmarshalling value=" + value);
            outVal.add(value);
            n--;
        }
    }

    /**
     * @param clazz The type of the object expected or {@code null} for performing no checks.
     */
    @SuppressLint({"ConcreteCollection", "NullableCollection"})
    @Nullable
    private <T> ArrayList<T> readArrayListInternal(@Nullable ClassLoader loader,
            @Nullable Class<? extends T> clazz) {
        int n = readInt();
        if (n < 0) {
            return null;
        }
        ArrayList<T> l = new ArrayList<>(n);
        readListInternal(l, n, loader, clazz);
        return l;
    }

    /**
     * The method is replaced by {@link #readArrayInternal(ClassLoader, Class)}, however
     * we are keeping this unused method here to allow unsupported app usages.
     */
    private void readArrayInternal(@NonNull Object[] outVal, int N,
            @Nullable ClassLoader loader) {
        for (int i = 0; i < N; i++) {
            Object value = readValue(loader, /* clazz */ null);
            outVal[i] = value;
        }
    }

    /**
     * @param clazz The type of the object expected or {@code null} for performing no checks.
     */
    @SuppressWarnings("unchecked")
    @Nullable
    private <T> T[] readArrayInternal(@Nullable ClassLoader loader, @Nullable Class<T> clazz) {
        int n = readInt();
        if (n < 0) {
            return null;
        }
        T[] outVal = (T[]) ((clazz == null) ? new Object[n] : Array.newInstance(clazz, n));

        for (int i = 0; i < n; i++) {
            T value = readValue(loader, clazz);
            outVal[i] = value;
        }
        return outVal;
    }

    /**
     * The method is replaced by {@link #readSparseArray(ClassLoader, Class)}, however
     * we are keeping this unused method here to allow unsupported app usages.
     */
    private void readSparseArrayInternal(@NonNull SparseArray outVal, int N,
            @Nullable ClassLoader loader) {
        while (N > 0) {
            int key = readInt();
            Object value = readValue(loader, /* clazz */ null);
            outVal.append(key, value);
            N--;
        }
    }

    /**
     * @param clazz The type of the object expected or {@code null} for performing no checks.
     */
    @Nullable
    private <T> SparseArray<T> readSparseArrayInternal(@Nullable ClassLoader loader,
            @Nullable Class<? extends T> clazz) {
        int n = readInt();
        if (n < 0) {
            return null;
        }
        SparseArray<T> outVal = new SparseArray<>(n);

        while (n > 0) {
            int key = readInt();
            T value = readValue(loader, clazz);
            outVal.append(key, value);
            n--;
        }
        return outVal;
    }


    private void readSparseBooleanArrayInternal(@NonNull SparseBooleanArray outVal, int N) {
        while (N > 0) {
            int key = readInt();
            boolean value = this.readByte() == 1;
            //Log.i(TAG, "Unmarshalling key=" + key + " value=" + value);
            outVal.append(key, value);
            N--;
        }
    }

    private void readSparseIntArrayInternal(@NonNull SparseIntArray outVal, int N) {
        while (N > 0) {
            int key = readInt();
            int value = readInt();
            outVal.append(key, value);
            N--;
        }
    }

    /**
     * @hide For testing
     */
    public long getOpenAshmemSize() {
        return nativeGetOpenAshmemSize(mNativePtr);
    }

    private static String valueTypeToString(int type) {
        switch (type) {
            case VAL_NULL: return "VAL_NULL";
            case VAL_INTEGER: return "VAL_INTEGER";
            case VAL_MAP: return "VAL_MAP";
            case VAL_BUNDLE: return "VAL_BUNDLE";
            case VAL_PERSISTABLEBUNDLE: return "VAL_PERSISTABLEBUNDLE";
            case VAL_PARCELABLE: return "VAL_PARCELABLE";
            case VAL_SHORT: return "VAL_SHORT";
            case VAL_LONG: return "VAL_LONG";
            case VAL_FLOAT: return "VAL_FLOAT";
            case VAL_DOUBLE: return "VAL_DOUBLE";
            case VAL_BOOLEAN: return "VAL_BOOLEAN";
            case VAL_CHARSEQUENCE: return "VAL_CHARSEQUENCE";
            case VAL_LIST: return "VAL_LIST";
            case VAL_SPARSEARRAY: return "VAL_SPARSEARRAY";
            case VAL_BOOLEANARRAY: return "VAL_BOOLEANARRAY";
            case VAL_BYTEARRAY: return "VAL_BYTEARRAY";
            case VAL_STRINGARRAY: return "VAL_STRINGARRAY";
            case VAL_CHARSEQUENCEARRAY: return "VAL_CHARSEQUENCEARRAY";
            case VAL_IBINDER: return "VAL_IBINDER";
            case VAL_PARCELABLEARRAY: return "VAL_PARCELABLEARRAY";
            case VAL_INTARRAY: return "VAL_INTARRAY";
            case VAL_LONGARRAY: return "VAL_LONGARRAY";
            case VAL_BYTE: return "VAL_BYTE";
            case VAL_SIZE: return "VAL_SIZE";
            case VAL_SIZEF: return "VAL_SIZEF";
            case VAL_DOUBLEARRAY: return "VAL_DOUBLEARRAY";
            case VAL_CHAR: return "VAL_CHAR";
            case VAL_SHORTARRAY: return "VAL_SHORTARRAY";
            case VAL_CHARARRAY: return "VAL_CHARARRAY";
            case VAL_FLOATARRAY: return "VAL_FLOATARRAY";
            case VAL_OBJECTARRAY: return "VAL_OBJECTARRAY";
            case VAL_SERIALIZABLE: return "VAL_SERIALIZABLE";
            default: return "UNKNOWN(" + type + ")";
        }
    }
    // ---------------------------------------------------------------------------------------------
    // teslalinux: the parts of AOSP that live in C++ (frameworks/base/core/jni/android_os_Parcel.cpp
    // and frameworks/native/libs/binder/Parcel.cpp), in Java. Same names as the AOSP natives so the
    // Java code above is unchanged.
    // ---------------------------------------------------------------------------------------------

    /** AOSP: total bytes held by parcel buffers in this process. */
    public static long getGlobalAllocSize() {
        return NativeParcel.sGlobalAllocSize.get();
    }

    /** AOSP: number of parcel buffers allocated in this process. */
    public static long getGlobalAllocCount() {
        return NativeParcel.sGlobalAllocCount.get();
    }

    private static final int STRICT_MODE_PENALTY_GATHER = 1 << 31;
    /** libbinder's kHeader, B_PACK_CHARS('S', 'Y', 'S', 'T') */
    private static final int INTERFACE_HEADER = ('S' << 24) | ('Y' << 16) | ('S' << 8) | 'T';

    private static <T> T getOrNull(T[] items, int i) {
        return (items != null && i >= 0 && i < items.length) ? items[i] : null;
    }

    private static void throwsIfOutOfBounds(int len, int offset, int count) {
        if (len < 0) {
            throw new ArrayIndexOutOfBoundsException("Negative length: " + len);
        }
        if ((offset | count) < 0 || offset > len - count) {
            throw new ArrayIndexOutOfBoundsException(
                    "length=" + len + "; regionStart=" + offset + "; regionLength=" + count);
        }
    }

    /** android.util.ExceptionUtils.appendCause */
    private static void appendCause(Throwable t, Throwable cause) {
        if (cause != null) {
            Throwable root = t;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            root.initCause(cause);
        }
    }

    /**
     * The reply header AppOpsManager.prefixParcelWithAppOpsIfNeeded writes (a count, then per
     * attribution tag the tag and the two 64-bit noted-op masks). ATL has no app-op service to log
     * them to, so they are read past.
     */
    private void skipNotedAppOpsHeader() {
        int n = readInt();
        for (int i = 0; i < n; i++) {
            readString();
            readLong();
            readLong();
        }
    }

    private static void nativeSignalExceptionForError(int error) {
        switch (error) {
            case NativeParcel.NO_MEMORY:
                throw new OutOfMemoryError();
            case NativeParcel.BAD_VALUE:
                throw new IllegalArgumentException("Bad parcel value");
            case NativeParcel.FDS_NOT_ALLOWED:
                throw new RuntimeException("Not allowed to write file descriptors here");
            case NativeParcel.BAD_TYPE:
                throw new IllegalArgumentException("Bad type");
            case NativeParcel.NOT_ENOUGH_DATA:
                throw new BadParcelableException("Not enough data");
            default:
                throw new RuntimeException("Unknown binder error code. 0x" + Integer.toHexString(error));
        }
    }

    private static NativeParcel nativeCreate() { return new NativeParcel(); }
    private static void nativeFreeBuffer(NativeParcel p) { p.freeData(); }
    private static void nativeDestroy(NativeParcel p) { p.freeData(); }
    private static void nativeMarkSensitive(NativeParcel p) { p.mSensitive = true; }
    private static void nativeMarkForBinder(NativeParcel p, IBinder binder) {
        // in-process binders all speak the kernel-binder wire format, as local BBinders do in AOSP
    }
    private static boolean nativeIsForRpc(NativeParcel p) { return false; }
    private static int nativeDataSize(NativeParcel p) { return p.dataSize(); }
    private static int nativeDataAvail(NativeParcel p) { return p.dataAvail(); }
    private static int nativeDataPosition(NativeParcel p) { return p.dataPosition(); }
    private static int nativeDataCapacity(NativeParcel p) { return p.dataCapacity(); }
    private static void nativeSetDataSize(NativeParcel p, int size) {
        if (size < 0) throw new IllegalArgumentException("Bad parcel value");
        p.setDataSize(size);
    }
    private static void nativeSetDataPosition(NativeParcel p, int pos) { p.setDataPosition(pos); }
    private static void nativeSetDataCapacity(NativeParcel p, int size) {
        if (size < 0) throw new IllegalArgumentException("Bad parcel value");
        p.setDataCapacity(size);
    }
    private static boolean nativePushAllowFds(NativeParcel p, boolean allowFds) {
        boolean last = p.mAllowFds;
        if (!allowFds) p.mAllowFds = false;
        return last;
    }
    private static void nativeRestoreAllowFds(NativeParcel p, boolean lastValue) { p.mAllowFds = lastValue; }
    private static void nativeWriteByteArray(NativeParcel p, byte[] b, int offset, int len) {
        if (b == null) {
            p.writeInt32(-1);
            return;
        }
        p.writeInt32(len);
        p.writeInplace(b, offset, len);
    }
    private static void nativeWriteBlob(NativeParcel p, byte[] b, int offset, int len) {
        if (b == null) {
            p.writeInt32(-1);
            return;
        }
        p.writeInt32(len);
        // libbinder writes blobs larger than 16 KiB to ashmem when FDs are allowed; in-process the
        // in-place form (BLOB_INPLACE) is what it uses whenever FDs are not allowed.
        p.writeInt32(NativeParcel.BLOB_INPLACE);
        p.writeInplace(b, offset, len);
    }
    private static int nativeWriteInt(NativeParcel p, int val) { return p.writeInt32(val); }
    private static int nativeWriteLong(NativeParcel p, long val) { return p.writeInt64(val); }
    private static int nativeWriteFloat(NativeParcel p, float val) { return p.writeInt32(Float.floatToRawIntBits(val)); }
    private static int nativeWriteDouble(NativeParcel p, double val) { return p.writeInt64(Double.doubleToRawLongBits(val)); }
    private static void nativeWriteString8(NativeParcel p, String val) { p.writeString8(val); }
    private static void nativeWriteString16(NativeParcel p, String val) { p.writeString16(val); }
    private static void nativeWriteStrongBinder(NativeParcel p, IBinder val) { p.writeStrongBinder(val); }
    private static void nativeWriteFileDescriptor(NativeParcel p, FileDescriptor val) {
        int err = p.writeDupFileDescriptor(val);
        if (err != OK) nativeSignalExceptionForError(err);
    }
    private static byte[] nativeCreateByteArray(NativeParcel p) {
        int len = p.readInt32();
        if (len < 0 || len > p.dataAvail()) return null;
        byte[] out = new byte[len];
        p.readInplace(out, 0, len);
        return out;
    }
    private static boolean nativeReadByteArray(NativeParcel p, byte[] dest, int destLen) {
        int len = p.readInt32();
        if (len >= 0 && len <= p.dataAvail() && len == destLen) {
            p.readInplace(dest, 0, len);
            return true;
        }
        return false;
    }
    private static byte[] nativeReadBlob(NativeParcel p) {
        int len = p.readInt32();
        if (len < 0) return null;
        int type = p.readInt32();
        if (type != NativeParcel.BLOB_INPLACE || len > p.dataAvail()) {
            throw new BadParcelableException("Failed to read blob (" + len + " bytes)");
        }
        byte[] out = new byte[len];
        p.readInplace(out, 0, len);
        return out;
    }
    private static int nativeReadInt(NativeParcel p) { return p.readInt32(); }
    private static long nativeReadLong(NativeParcel p) { return p.readInt64(); }
    private static float nativeReadFloat(NativeParcel p) { return Float.intBitsToFloat(p.readInt32()); }
    private static double nativeReadDouble(NativeParcel p) { return Double.longBitsToDouble(p.readInt64()); }
    private static String nativeReadString8(NativeParcel p) { return p.readString8(); }
    private static String nativeReadString16(NativeParcel p) { return p.readString16(); }
    private static IBinder nativeReadStrongBinder(NativeParcel p) { return p.readStrongBinder(); }
    private static FileDescriptor nativeReadFileDescriptor(NativeParcel p) {
        // android_os_Parcel_readFileDescriptor: the parcel keeps its fd, the caller gets a dup
        int fd = p.readFileDescriptor();
        if (fd < 0) return null;
        try {
            return NativeParcel.fdOf(android.system.Os.fcntlInt(NativeParcel.fdOf(fd),
                    android.system.OsConstants.F_DUPFD_CLOEXEC, 0));
        } catch (android.system.ErrnoException e) {
            return null;
        }
    }
    private static byte[] nativeMarshall(NativeParcel p) { return p.marshall(); }
    private static void nativeUnmarshall(NativeParcel p, byte[] data, int offset, int length) {
        if (data == null || length < 0 || offset < 0 || offset > data.length - length) {
            throw new IllegalArgumentException("Bad parcel value");
        }
        p.unmarshall(data, offset, length);
    }
    private static int nativeCompareData(NativeParcel a, NativeParcel b) { return a.compareData(b); }
    private static boolean nativeCompareDataInRange(NativeParcel a, int offsetA, NativeParcel b, int offsetB, int length) {
        if (length < 0 || offsetA < 0 || offsetB < 0
                || offsetA > a.dataSize() - length || offsetB > b.dataSize() - length) {
            throw new IllegalArgumentException("Bad parcel value");
        }
        return NativeParcel.compareRange(a, offsetA, b, offsetB, length);
    }
    private static void nativeAppendFrom(NativeParcel thisP, NativeParcel otherP, int offset, int length) {
        int err = thisP.appendFrom(otherP, offset, length);
        if (err != OK) nativeSignalExceptionForError(err);
    }
    private static boolean nativeHasFileDescriptors(NativeParcel p) { return p.hasFileDescriptorsInRange(0, p.dataSize()); }
    private static boolean nativeHasFileDescriptorsInRange(NativeParcel p, int offset, int length) {
        if (offset < 0 || length < 0 || offset > p.dataSize() - length) {
            throw new IllegalArgumentException("Bad parcel value");
        }
        return p.hasFileDescriptorsInRange(offset, length);
    }
    private static void nativeWriteInterfaceToken(NativeParcel p, String interfaceName) {
        p.writeInt32(Binder.getThreadStrictModePolicy() | STRICT_MODE_PENALTY_GATHER);
        p.mWorkSourceRequestHeaderPosition = p.dataPosition();
        p.mRequestHeaderPresent = true;
        p.writeInt32(Binder.shouldPropagateWorkSource() ? Binder.getCallingWorkSourceUid() : Binder.UNSET_WORKSOURCE);
        p.writeInt32(INTERFACE_HEADER);
        p.writeString16(interfaceName);
    }
    private static void nativeEnforceInterface(NativeParcel p, String interfaceName) {
        // Parcel::enforceInterface: strict-mode policy, work source, header, then the descriptor
        int strictPolicy = p.readInt32();
        Binder.setThreadStrictModePolicy(strictPolicy & ~STRICT_MODE_PENALTY_GATHER);
        int workSource = p.readInt32();
        Binder.setCallingWorkSourceUidWithoutPropagation(workSource);
        int header = p.readInt32();
        if (header != INTERFACE_HEADER) {
            Log.e(TAG, "Expecting header 0x" + Integer.toHexString(INTERFACE_HEADER) + " but found 0x"
                    + Integer.toHexString(header) + ". Mixing copies of libbinder?");
            throw new SecurityException("Binder invocation to an incorrect interface");
        }
        String str = p.readString16();
        if (interfaceName == null || !interfaceName.equals(str)) {
            Log.w(TAG, "**** enforceInterface() expected '" + interfaceName + "' but read '" + str + "'");
            throw new SecurityException("Binder invocation to an incorrect interface");
        }
    }
    private static boolean nativeReplaceCallingWorkSourceUid(NativeParcel p, int workSourceUid) {
        if (!p.mRequestHeaderPresent) return false;
        int pos = p.dataPosition();
        p.setDataPosition(p.mWorkSourceRequestHeaderPosition);
        p.writeInt32(workSourceUid);
        p.setDataPosition(pos);
        return true;
    }
    private static int nativeReadCallingWorkSourceUid(NativeParcel p) {
        return Binder.getCallingWorkSourceUid();
    }
    private static long nativeGetOpenAshmemSize(NativeParcel p) {
        // file descriptors in an ATL parcel are never ashmem regions (Linux has no ashmem)
        return 0;
    }

    /**
     * teslalinux: libbinder's Parcel in Java. Layout as on Android: every item is padded to 4 bytes,
     * integers are little-endian, strings are length + characters + NUL, binders and file
     * descriptors are 24-byte flat objects whose offsets are kept in an object table. The flat
     * object's 64-bit "binder" field is the index of the object in mObjectValues.
     */
    private static class NativeParcel {
        static final int OK = 0;
        static final int NO_MEMORY = -12;
        static final int BAD_VALUE = -22;
        static final int BAD_TYPE = 0x80000001;
        static final int NOT_ENOUGH_DATA = -61;
        static final int FDS_NOT_ALLOWED = 0x80000007;
        static final int BLOB_INPLACE = 0;

        static final int TYPE_BINDER = 0x73622a85; // BINDER_TYPE_BINDER: B_PACK_CHARS('s', 'b', '*', 0x7f)
        static final int TYPE_FD = 0x66642a85;     // BINDER_TYPE_FD:     B_PACK_CHARS('f', 'd', '*', 0x7f)
        static final int FLAT_OBJECT_SIZE = 24;

        static final java.util.concurrent.atomic.AtomicLong sGlobalAllocSize = new java.util.concurrent.atomic.AtomicLong();
        static final java.util.concurrent.atomic.AtomicLong sGlobalAllocCount = new java.util.concurrent.atomic.AtomicLong();

        private static final byte[] EMPTY = new byte[0];

        byte[] mData = EMPTY;
        int mDataSize;
        int mDataPos;
        /** offset of each flat object -> the IBinder, or the owned fd (Integer) */
        java.util.TreeMap<Integer, Object> mObjects;
        boolean mAllowFds = true;
        boolean mSensitive;
        boolean mRequestHeaderPresent;
        int mWorkSourceRequestHeaderPosition;

        static FileDescriptor fdOf(int fd) {
            FileDescriptor f = new FileDescriptor();
            f.setInt$(fd);
            return f;
        }

        static int pad(int len) {
            return (len + 3) & ~3;
        }

        void freeData() {
            closeOwnedFds(mObjects);
            if (mSensitive) {
                java.util.Arrays.fill(mData, 0, mDataSize, (byte) 0);
            }
            if (mData.length > 0) {
                sGlobalAllocSize.addAndGet(-mData.length);
                sGlobalAllocCount.decrementAndGet();
            }
            mData = EMPTY;
            mDataSize = 0;
            mDataPos = 0;
            mObjects = null;
            mAllowFds = true;
            mSensitive = false;
            mRequestHeaderPresent = false;
            mWorkSourceRequestHeaderPosition = 0;
        }

        private static void closeOwnedFds(java.util.Map<Integer, Object> objects) {
            if (objects == null) return;
            for (Object o : objects.values()) {
                if (o instanceof Integer) {
                    try {
                        android.system.Os.close(fdOf((Integer) o));
                    } catch (android.system.ErrnoException e) {
                        // already closed: nothing to release
                    }
                }
            }
        }

        int dataSize() { return mDataSize > mDataPos ? mDataSize : mDataPos; }
        int dataAvail() {
            int r = mDataSize - mDataPos;
            return r > 0 ? r : 0;
        }
        int dataPosition() { return mDataPos; }
        int dataCapacity() { return mData.length; }

        void setDataPosition(int pos) {
            if (pos < 0) throw new IllegalArgumentException("Bad parcel value");
            mDataPos = pos;
        }

        void setDataSize(int size) {
            ensureCapacity(size);
            if (size < mDataSize && mObjects != null) {
                // objects past the new end are released (Parcel::continueWrite)
                java.util.SortedMap<Integer, Object> cut = mObjects.tailMap(size > FLAT_OBJECT_SIZE ? size - FLAT_OBJECT_SIZE + 1 : 0);
                java.util.TreeMap<Integer, Object> gone = new java.util.TreeMap<>(cut);
                cut.clear();
                closeOwnedFds(gone);
            }
            mDataSize = size;
            if (mDataPos > size) mDataPos = size;
        }

        void setDataCapacity(int size) {
            if (size > mData.length) ensureCapacity(size);
        }

        private void ensureCapacity(int needed) {
            if (needed <= mData.length) return;
            int cap = needed + needed / 2;
            if (cap < 64) cap = 64;
            byte[] n = java.util.Arrays.copyOf(mData, cap);
            if (mData.length > 0) {
                sGlobalAllocSize.addAndGet(cap - mData.length);
            } else {
                sGlobalAllocSize.addAndGet(cap);
                sGlobalAllocCount.incrementAndGet();
            }
            if (mSensitive) java.util.Arrays.fill(mData, (byte) 0);
            mData = n;
        }

        /** reserves len bytes (padded) at the current position and returns their offset */
        private int grow(int len) {
            int padded = pad(len);
            int at = mDataPos;
            ensureCapacity(at + padded);
            // zero the padding, as libbinder does
            for (int i = at + len; i < at + padded; i++) mData[i] = 0;
            mDataPos = at + padded;
            if (mDataPos > mDataSize) mDataSize = mDataPos;
            return at;
        }

        int writeInt32(int v) {
            int at = grow(4);
            byte[] d = mData;
            d[at] = (byte) v;
            d[at + 1] = (byte) (v >>> 8);
            d[at + 2] = (byte) (v >>> 16);
            d[at + 3] = (byte) (v >>> 24);
            return OK;
        }

        int writeInt64(long v) {
            int at = grow(8);
            byte[] d = mData;
            for (int i = 0; i < 8; i++) d[at + i] = (byte) (v >>> (8 * i));
            return OK;
        }

        void writeInplace(byte[] b, int offset, int len) {
            int at = grow(len);
            System.arraycopy(b, offset, mData, at, len);
        }

        /** the position of len readable bytes (padded), or -1 when the parcel is too short */
        private int take(int len) {
            int padded = pad(len);
            if (mDataPos < 0 || padded > mDataSize - mDataPos) return -1;
            int at = mDataPos;
            mDataPos += padded;
            return at;
        }

        int readInt32() {
            int at = take(4);
            if (at < 0) return 0;
            byte[] d = mData;
            return (d[at] & 0xff) | (d[at + 1] & 0xff) << 8 | (d[at + 2] & 0xff) << 16 | (d[at + 3] & 0xff) << 24;
        }

        long readInt64() {
            int at = take(8);
            if (at < 0) return 0;
            long v = 0;
            for (int i = 7; i >= 0; i--) v = (v << 8) | (mData[at + i] & 0xff);
            return v;
        }

        void readInplace(byte[] dest, int offset, int len) {
            int at = take(len);
            if (at < 0) throw new BadParcelableException("Not enough data");
            System.arraycopy(mData, at, dest, offset, len);
        }

        void writeString16(String s) {
            if (s == null) {
                writeInt32(-1);
                return;
            }
            int n = s.length();
            writeInt32(n);
            int at = grow((n + 1) * 2);
            byte[] d = mData;
            for (int i = 0; i < n; i++) {
                char c = s.charAt(i);
                d[at + 2 * i] = (byte) c;
                d[at + 2 * i + 1] = (byte) (c >>> 8);
            }
            d[at + 2 * n] = 0;
            d[at + 2 * n + 1] = 0;
        }

        String readString16() {
            int n = readInt32();
            if (n < 0) return null;
            if (n >= Integer.MAX_VALUE / 2) return null;
            int at = take((n + 1) * 2);
            if (at < 0) return null;
            char[] c = new char[n];
            byte[] d = mData;
            for (int i = 0; i < n; i++) c[i] = (char) ((d[at + 2 * i] & 0xff) | (d[at + 2 * i + 1] & 0xff) << 8);
            return new String(c);
        }

        void writeString8(String s) {
            if (s == null) {
                writeInt32(-1);
                return;
            }
            byte[] b = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            writeInt32(b.length);
            int at = grow(b.length + 1);
            System.arraycopy(b, 0, mData, at, b.length);
            mData[at + b.length] = 0;
        }

        String readString8() {
            int n = readInt32();
            if (n < 0 || n == Integer.MAX_VALUE) return null;
            int at = take(n + 1);
            if (at < 0) return null;
            return new String(mData, at, n, java.nio.charset.StandardCharsets.UTF_8);
        }

        private void writeFlatObject(int type, long index, boolean record, Object value) {
            int at = grow(FLAT_OBJECT_SIZE);
            byte[] d = mData;
            for (int i = 0; i < FLAT_OBJECT_SIZE; i++) d[at + i] = 0;
            d[at] = (byte) type;
            d[at + 1] = (byte) (type >>> 8);
            d[at + 2] = (byte) (type >>> 16);
            d[at + 3] = (byte) (type >>> 24);
            for (int i = 0; i < 8; i++) d[at + 8 + i] = (byte) (index >>> (8 * i));
            if (mObjects != null) {
                Object old = mObjects.remove(at);
                if (old instanceof Integer) closeOwnedFds(java.util.Collections.singletonMap(at, old));
            }
            if (record) {
                if (mObjects == null) mObjects = new java.util.TreeMap<>();
                mObjects.put(at, value);
            }
        }

        /** the object recorded at the current position (the position advances over it), else null */
        private Object readFlatObject(int expectedType) {
            int at = take(FLAT_OBJECT_SIZE);
            if (at < 0) return null;
            byte[] d = mData;
            int type = (d[at] & 0xff) | (d[at + 1] & 0xff) << 8 | (d[at + 2] & 0xff) << 16 | (d[at + 3] & 0xff) << 24;
            if (type != expectedType) return null;
            // libbinder only accepts objects at offsets in its object table
            return mObjects != null ? mObjects.get(at) : null;
        }

        void writeStrongBinder(IBinder binder) {
            // Parcel::flattenBinder: a null binder is a flat object that is not in the object table
            writeFlatObject(TYPE_BINDER, binder == null ? 0 : mObjectsCount(), binder != null, binder);
        }

        private long mObjectsCount() {
            return mObjects == null ? 1 : mObjects.size() + 1;
        }

        IBinder readStrongBinder() {
            Object o = readFlatObject(TYPE_BINDER);
            return o instanceof IBinder ? (IBinder) o : null;
        }

        int writeDupFileDescriptor(FileDescriptor fd) {
            if (fd == null || !fd.valid()) {
                return BAD_VALUE;
            }
            if (!mAllowFds) {
                return FDS_NOT_ALLOWED;
            }
            int dup;
            try {
                dup = android.system.Os.fcntlInt(fd, android.system.OsConstants.F_DUPFD_CLOEXEC, 0);
            } catch (android.system.ErrnoException e) {
                return -e.errno;
            }
            writeFlatObject(TYPE_FD, dup, true, Integer.valueOf(dup));
            return OK;
        }

        int readFileDescriptor() {
            Object o = readFlatObject(TYPE_FD);
            return o instanceof Integer ? (Integer) o : -1;
        }

        boolean hasFileDescriptorsInRange(int offset, int length) {
            if (mObjects == null) return false;
            for (java.util.Map.Entry<Integer, Object> e : mObjects.subMap(offset, true, offset + length, false).entrySet()) {
                if (e.getValue() instanceof Integer) return true;
            }
            return false;
        }

        byte[] marshall() {
            if (mObjects != null && !mObjects.isEmpty()) {
                throw new RuntimeException("Tried to marshall a Parcel that contained Binder objects.");
            }
            return java.util.Arrays.copyOf(mData, dataSize());
        }

        void unmarshall(byte[] data, int offset, int length) {
            freeData();
            ensureCapacity(length);
            System.arraycopy(data, offset, mData, 0, length);
            mDataSize = length;
            mDataPos = 0;
        }

        int compareData(NativeParcel other) {
            int n = Math.min(dataSize(), other.dataSize());
            for (int i = 0; i < n; i++) {
                int a = mData[i] & 0xff, b = other.mData[i] & 0xff;
                if (a != b) return a < b ? -1 : 1;
            }
            int ds = dataSize(), os = other.dataSize();
            return ds == os ? 0 : (ds < os ? -1 : 1);
        }

        static boolean compareRange(NativeParcel a, int offsetA, NativeParcel b, int offsetB, int length) {
            for (int i = 0; i < length; i++) {
                if (a.mData[offsetA + i] != b.mData[offsetB + i]) return false;
            }
            return true;
        }

        int appendFrom(NativeParcel src, int offset, int len) {
            if (len == 0) return OK;
            if (offset < 0 || len < 0 || offset > src.dataSize() - len) {
                return BAD_VALUE;
            }
            java.util.Map<Integer, Object> objs = src.mObjects == null ? null
                    : src.mObjects.subMap(offset, true, offset + len, false);
            if (objs != null && !mAllowFds) {
                for (Object o : objs.values()) {
                    if (o instanceof Integer) return FDS_NOT_ALLOWED;
                }
            }
            int start = mDataPos;
            ensureCapacity(start + len);
            System.arraycopy(src.mData, offset, mData, start, len);
            mDataPos = start + len;
            if (mDataPos > mDataSize) mDataSize = mDataPos;
            if (objs != null) {
                for (java.util.Map.Entry<Integer, Object> e : objs.entrySet()) {
                    int at = e.getKey() - offset + start;
                    Object v = e.getValue();
                    if (v instanceof Integer) {
                        // Parcel::appendFrom duplicates file descriptors, the copy owns its own
                        try {
                            v = android.system.Os.fcntlInt(fdOf((Integer) v), android.system.OsConstants.F_DUPFD_CLOEXEC, 0);
                        } catch (android.system.ErrnoException ex) {
                            return -ex.errno;
                        }
                    }
                    if (mObjects == null) mObjects = new java.util.TreeMap<>();
                    mObjects.put(at, v);
                }
            }
            return OK;
        }
    }

    /**
     * teslalinux: the buffer of ATL's GVariant parcel subclasses. Every primitive is sent through
     * the subclass's (overridden) writeByte/readByte, so the subclass sees the whole stream; there
     * is no position, size or object table.
     */
    private static final class FunnelParcel extends NativeParcel {
        private final Parcel mOwner;

        FunnelParcel(Parcel owner) {
            mOwner = owner;
        }

        private void put(long v, int bytes) {
            for (int i = 0; i < bytes; i++) mOwner.writeByte((byte) (v >>> (8 * i)));
        }

        private long get(int bytes) {
            long v = 0;
            for (int i = 0; i < bytes; i++) v |= (long) (mOwner.readByte() & 0xff) << (8 * i);
            return v;
        }

        @Override int writeInt32(int v) { put(v, 4); return OK; }
        @Override int writeInt64(long v) { put(v, 8); return OK; }
        @Override void writeInplace(byte[] b, int offset, int len) {
            for (int i = 0; i < len; i++) mOwner.writeByte(b[offset + i]);
        }
        @Override int readInt32() { return (int) get(4); }
        @Override long readInt64() { return get(8); }
        @Override void readInplace(byte[] dest, int offset, int len) {
            for (int i = 0; i < len; i++) dest[offset + i] = mOwner.readByte();
        }
        @Override void writeString16(String s) {
            if (s == null) {
                writeInt32(-1);
                return;
            }
            writeInt32(s.length());
            for (int i = 0; i < s.length(); i++) put(s.charAt(i), 2);
        }
        @Override String readString16() {
            int n = readInt32();
            if (n < 0) return null;
            char[] c = new char[n];
            for (int i = 0; i < n; i++) c[i] = (char) get(2);
            return new String(c);
        }
        @Override void writeString8(String s) {
            writeString16(s);
        }
        @Override String readString8() {
            return readString16();
        }
        @Override void writeStrongBinder(IBinder binder) {
            throw new UnsupportedOperationException("binders cannot be sent over D-Bus");
        }
        @Override IBinder readStrongBinder() {
            throw new UnsupportedOperationException("binders cannot be sent over D-Bus");
        }
        @Override int writeDupFileDescriptor(FileDescriptor fd) {
            return FDS_NOT_ALLOWED;
        }
        @Override int readFileDescriptor() {
            return -1;
        }
        @Override byte[] marshall() {
            throw new UnsupportedOperationException("a GVariant parcel has no byte buffer");
        }
        @Override void unmarshall(byte[] data, int offset, int length) {
            throw new UnsupportedOperationException("a GVariant parcel has no byte buffer");
        }
        @Override int appendFrom(NativeParcel src, int offset, int len) {
            for (int i = 0; i < len; i++) mOwner.writeByte(src.mData[offset + i]);
            return OK;
        }
    }
}
