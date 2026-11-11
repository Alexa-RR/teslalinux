/*
 * Copyright (C) 2017 The Android Open Source Project
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

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.Closeable;
import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SharedMemory enables the creation, mapping, and protection control over anonymous shared
 * memory (AOSP).
 *
 * <p>teslalinux: Android backs it with ashmem/memfd. Here the region is an unlinked file in
 * /dev/shm (tmpfs, the same kind of memory), sized with ftruncate and mapped MAP_SHARED through a
 * FileChannel on the descriptor. ashmem's protection mask (setProtect, which can only drop rights)
 * is enforced for the mappings made through this object; mappings fail with EPERM as ashmem's
 * mmap does.
 */
public final class SharedMemory implements Parcelable, Closeable {
    private static final AtomicInteger sCounter = new AtomicInteger();

    private static final int PROT_MASK = OsConstants.PROT_READ | OsConstants.PROT_WRITE
            | OsConstants.PROT_EXEC | OsConstants.PROT_NONE;

    private final FileDescriptor mFileDescriptor;
    private final int mSize;
    private int mProtect = OsConstants.PROT_READ | OsConstants.PROT_WRITE | OsConstants.PROT_EXEC;
    private boolean mClosed;

    private SharedMemory(FileDescriptor fd) {
        if (fd == null) {
            throw new IllegalArgumentException("Unable to create SharedMemory from a null FileDescriptor");
        }
        if (!fd.valid()) {
            throw new IllegalArgumentException("Unable to create SharedMemory from closed FileDescriptor");
        }
        mFileDescriptor = fd;
        mSize = nGetSize(mFileDescriptor);
        if (mSize <= 0) {
            throw new IllegalArgumentException("FileDescriptor is not a valid ashmem fd");
        }
    }

    /**
     * Creates an anonymous SharedMemory instance with the provided debug name and size. The name
     * is only used for debugging.
     */
    public static SharedMemory create(String name, int size) throws ErrnoException {
        if (size <= 0) {
            throw new IllegalArgumentException("Size must be greater than zero");
        }
        return new SharedMemory(nCreate(name, size));
    }

    private void checkOpen() {
        if (mClosed) {
            throw new IllegalStateException("SharedMemory is closed");
        }
    }

    /** @hide a SharedMemory from an existing shared-memory file descriptor (dup'd) */
    public static SharedMemory fromFileDescriptor(ParcelFileDescriptor fd) {
        try {
            return new SharedMemory(Os.dup(fd.getFileDescriptor()));
        } catch (ErrnoException e) {
            throw new IllegalArgumentException("Unable to dup the descriptor: " + e);
        }
    }

    private static boolean isValidProtectionMode(int prot) {
        return (prot & ~PROT_MASK) == 0;
    }

    /**
     * Restricts the protection of the shared memory to the given bits; a protection that has been
     * removed cannot be added back.
     */
    public boolean setProtect(int prot) {
        checkOpen();
        if (!isValidProtectionMode(prot)) {
            throw new IllegalArgumentException("Invalid prot value");
        }
        if ((prot & ~mProtect) != 0) {
            // ashmem: protection bits can only be removed (EINVAL otherwise)
            return false;
        }
        mProtect = prot;
        return true;
    }

    /** Returns the backing FileDescriptor for this SharedMemory object. */
    public FileDescriptor getFileDescriptor() {
        return mFileDescriptor;
    }

    /** @hide the backing native fd */
    public int getFd() {
        return mFileDescriptor.getInt$();
    }

    /** @return The size of the SharedMemory region. */
    public int getSize() {
        checkOpen();
        return mSize;
    }

    /** Creates a read/write mapping of the entire shared memory region. */
    public ByteBuffer mapReadWrite() throws ErrnoException {
        return map(OsConstants.PROT_READ | OsConstants.PROT_WRITE, 0, mSize);
    }

    /** Creates a read-only mapping of the entire shared memory region. */
    public ByteBuffer mapReadOnly() throws ErrnoException {
        return map(OsConstants.PROT_READ, 0, mSize);
    }

    /** Creates an mmap of the SharedMemory with the specified prot, offset, and length. */
    public ByteBuffer map(int prot, int offset, int length) throws ErrnoException {
        checkOpen();
        if (!isValidProtectionMode(prot)) {
            throw new IllegalArgumentException("Invalid prot value");
        }
        if (offset < 0) {
            throw new IllegalArgumentException("Offset must be >= 0");
        }
        if (length <= 0) {
            throw new IllegalArgumentException("Length must be > 0");
        }
        if (offset + length > mSize) {
            throw new IllegalArgumentException("offset + length must not exceed getSize()");
        }
        if ((prot & ~mProtect) != 0) {
            throw new ErrnoException("mmap", OsConstants.EPERM);
        }
        boolean readOnly = (prot & OsConstants.PROT_WRITE) == 0;
        try (FileChannel ch = readOnly
                ? FileChannel.open(Paths.get("/proc/self/fd/" + getFd()), StandardOpenOption.READ)
                : FileChannel.open(Paths.get("/proc/self/fd/" + getFd()), StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            return ch.map(readOnly ? FileChannel.MapMode.READ_ONLY : FileChannel.MapMode.READ_WRITE, offset, length);
        } catch (IOException e) {
            throw new ErrnoException("mmap", OsConstants.EIO);
        }
    }

    /** Unmaps a buffer previously returned by {@link #map(int, int, int)}. */
    public static void unmap(ByteBuffer buffer) {
        if (buffer != null && buffer.isDirect()) {
            java.nio.NioUtils.freeDirectBuffer(buffer);
        } else {
            throw new IllegalArgumentException("ByteBuffer wasn't created by #map(int, int, int); can't unmap");
        }
    }

    /** Close the backing FileDescriptor of this SharedMemory instance. */
    @Override
    public void close() {
        if (!mClosed) {
            mClosed = true;
            try {
                Os.close(mFileDescriptor);
            } catch (ErrnoException e) {
                // already closed
            }
        }
    }

    @Override
    public int describeContents() {
        return CONTENTS_FILE_DESCRIPTOR;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        checkOpen();
        dest.writeFileDescriptor(mFileDescriptor);
    }

    /** @hide a dup of the descriptor */
    public ParcelFileDescriptor getFdDup() throws IOException {
        try {
            return new ParcelFileDescriptor(Os.dup(mFileDescriptor));
        } catch (ErrnoException e) {
            throw new IOException(e);
        }
    }

    public static final Parcelable.Creator<SharedMemory> CREATOR = new Parcelable.Creator<SharedMemory>() {
        @Override
        public SharedMemory createFromParcel(Parcel source) {
            FileDescriptor descriptor = source.readRawFileDescriptor();
            return new SharedMemory(descriptor);
        }

        @Override
        public SharedMemory[] newArray(int size) {
            return new SharedMemory[size];
        }
    };

    private static FileDescriptor nCreate(String name, int size) throws ErrnoException {
        String dir = new java.io.File("/dev/shm").isDirectory() ? "/dev/shm"
                : System.getProperty("java.io.tmpdir", "/tmp");
        String path = dir + "/atl-shm-" + Os.getpid() + "-" + sCounter.incrementAndGet();
        FileDescriptor fd = Os.open(path, OsConstants.O_RDWR | OsConstants.O_CREAT | OsConstants.O_EXCL
                                          | OsConstants.O_CLOEXEC, 0600);
        try {
            Os.unlink(path);
            Os.ftruncate(fd, size);
        } catch (ErrnoException e) {
            Os.close(fd);
            throw e;
        }
        return fd;
    }

    private static int nGetSize(FileDescriptor fd) {
        try {
            return (int) Os.fstat(fd).st_size;
        } catch (ErrnoException e) {
            return -1;
        }
    }
}
