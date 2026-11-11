/*
 * Copyright (C) 2009 The Android Open Source Project
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

package android.content;

import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

import java.io.FileNotFoundException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The public interface object used to interact with a specific {@link ContentProvider} (AOSP).
 *
 * <p>teslalinux: ATL's providers are the app's own, created in this process (as an in-process
 * provider is on Android, where the client then talks to the local ContentProvider object), so
 * every call goes to the provider directly. ContentResolver hands a client out only for an
 * authority that has a provider, and returns null otherwise, as on Android.
 */
public class ContentProviderClient implements AutoCloseable {
    private final ContentResolver mContentResolver;
    private final ContentProvider mContentProvider;
    private final String mPackageName;
    private final String mAuthority;
    private final boolean mStable;

    private final AtomicBoolean mClosed = new AtomicBoolean();

    /** @hide */
    ContentProviderClient(ContentResolver contentResolver, ContentProvider contentProvider,
            String authority, boolean stable) {
        mContentResolver = contentResolver;
        mContentProvider = contentProvider;
        mPackageName = contentProvider.getContext() != null
                ? contentProvider.getContext().getPackageName() : null;
        mAuthority = authority;
        mStable = stable;
    }

    private void beforeRemote() {
        if (mClosed.get()) {
            // AOSP: a released client is no longer connected to its provider
            throw new IllegalStateException("Client " + this + " has been released");
        }
    }

    /** See {@link ContentProvider#query ContentProvider.query} */
    public Cursor query(Uri url, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) throws RemoteException {
        return query(url, projection, selection, selectionArgs, sortOrder, null);
    }

    /** See {@link ContentProvider#query ContentProvider.query} */
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder, CancellationSignal cancellationSignal) throws RemoteException {
        Bundle queryArgs = ContentResolver.createSqlQueryBundle(selection, selectionArgs, sortOrder);
        return query(uri, projection, queryArgs, cancellationSignal);
    }

    /** See {@link ContentProvider#query ContentProvider.query} */
    public Cursor query(Uri uri, String[] projection, Bundle queryArgs,
            CancellationSignal cancellationSignal) throws RemoteException {
        beforeRemote();
        if (cancellationSignal != null) {
            cancellationSignal.throwIfCanceled();
        }
        String selection = queryArgs != null ? queryArgs.getString(ContentResolver.QUERY_ARG_SQL_SELECTION) : null;
        String[] selectionArgs = queryArgs != null ? queryArgs.getStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS) : null;
        String sortOrder = queryArgs != null ? queryArgs.getString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER) : null;
        return mContentProvider.query(uri, projection, selection, selectionArgs, sortOrder);
    }

    /** See {@link ContentProvider#getType ContentProvider.getType} */
    public String getType(Uri url) throws RemoteException {
        beforeRemote();
        return mContentProvider.getType(url);
    }

    /** See {@link ContentProvider#getStreamTypes ContentProvider.getStreamTypes} */
    public String[] getStreamTypes(Uri url, String mimeTypeFilter) throws RemoteException {
        beforeRemote();
        return mContentProvider.getStreamTypes(url, mimeTypeFilter);
    }

    /** See {@link ContentProvider#canonicalize} */
    public final Uri canonicalize(Uri url) throws RemoteException {
        beforeRemote();
        return mContentProvider.canonicalize(url);
    }

    /** See {@link ContentProvider#uncanonicalize} */
    public final Uri uncanonicalize(Uri url) throws RemoteException {
        beforeRemote();
        return mContentProvider.uncanonicalize(url);
    }

    /** See {@link ContentProvider#refresh} */
    public boolean refresh(Uri url, Bundle extras, CancellationSignal cancellationSignal)
            throws RemoteException {
        beforeRemote();
        return mContentProvider.refresh(url, extras, cancellationSignal);
    }

    /** See {@link ContentProvider#insert ContentProvider.insert} */
    public Uri insert(Uri url, ContentValues initialValues) throws RemoteException {
        beforeRemote();
        return mContentProvider.insert(url, initialValues);
    }

    /** See {@link ContentProvider#insert ContentProvider.insert} */
    public Uri insert(Uri uri, ContentValues values, Bundle extras) throws RemoteException {
        return insert(uri, values);
    }

    /** See {@link ContentProvider#bulkInsert ContentProvider.bulkInsert} */
    public int bulkInsert(Uri url, ContentValues[] initialValues) throws RemoteException {
        beforeRemote();
        return mContentProvider.bulkInsert(url, initialValues);
    }

    /** See {@link ContentProvider#delete ContentProvider.delete} */
    public int delete(Uri url, String selection, String[] selectionArgs) throws RemoteException {
        beforeRemote();
        return mContentProvider.delete(url, selection, selectionArgs);
    }

    /** See {@link ContentProvider#delete ContentProvider.delete} */
    public int delete(Uri url, Bundle extras) throws RemoteException {
        return delete(url,
                extras != null ? extras.getString(ContentResolver.QUERY_ARG_SQL_SELECTION) : null,
                extras != null ? extras.getStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS) : null);
    }

    /** See {@link ContentProvider#update ContentProvider.update} */
    public int update(Uri url, ContentValues values, String selection, String[] selectionArgs)
            throws RemoteException {
        beforeRemote();
        return mContentProvider.update(url, values, selection, selectionArgs);
    }

    /** See {@link ContentProvider#update ContentProvider.update} */
    public int update(Uri url, ContentValues values, Bundle extras) throws RemoteException {
        return update(url, values,
                extras != null ? extras.getString(ContentResolver.QUERY_ARG_SQL_SELECTION) : null,
                extras != null ? extras.getStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS) : null);
    }

    /** See {@link ContentProvider#openFile ContentProvider.openFile} */
    public ParcelFileDescriptor openFile(Uri url, String mode)
            throws RemoteException, FileNotFoundException {
        return openFile(url, mode, null);
    }

    /** See {@link ContentProvider#openFile ContentProvider.openFile} */
    public ParcelFileDescriptor openFile(Uri url, String mode, CancellationSignal signal)
            throws RemoteException, FileNotFoundException {
        beforeRemote();
        if (signal != null) {
            signal.throwIfCanceled();
        }
        return mContentProvider.openFile(url, mode);
    }

    /** See {@link ContentProvider#openAssetFile ContentProvider.openAssetFile} */
    public AssetFileDescriptor openAssetFile(Uri url, String mode)
            throws RemoteException, FileNotFoundException {
        return openAssetFile(url, mode, null);
    }

    /** See {@link ContentProvider#openAssetFile ContentProvider.openAssetFile} */
    public AssetFileDescriptor openAssetFile(Uri url, String mode, CancellationSignal signal)
            throws RemoteException, FileNotFoundException {
        beforeRemote();
        if (signal != null) {
            signal.throwIfCanceled();
        }
        return mContentProvider.openAssetFile(url, mode);
    }

    /** See {@link ContentProvider#openTypedAssetFile ContentProvider.openTypedAssetFile} */
    public final AssetFileDescriptor openTypedAssetFileDescriptor(Uri uri, String mimeType,
            Bundle opts) throws RemoteException, FileNotFoundException {
        return openTypedAssetFileDescriptor(uri, mimeType, opts, null);
    }

    /** See {@link ContentProvider#openTypedAssetFile ContentProvider.openTypedAssetFile} */
    public final AssetFileDescriptor openTypedAssetFileDescriptor(Uri uri, String mimeType,
            Bundle opts, CancellationSignal signal) throws RemoteException, FileNotFoundException {
        beforeRemote();
        if (signal != null) {
            signal.throwIfCanceled();
        }
        return mContentProvider.openTypedAssetFile(uri, mimeType, opts);
    }

    /** See {@link ContentProvider#call(String, String, Bundle)} */
    public Bundle call(String method, String arg, Bundle extras) throws RemoteException {
        return call(mAuthority, method, arg, extras);
    }

    /** See {@link ContentProvider#call(String, String, String, Bundle)} */
    public Bundle call(String authority, String method, String arg, Bundle extras)
            throws RemoteException {
        if (method == null)
            throw new NullPointerException("method");
        beforeRemote();
        return mContentProvider.call(authority, method, arg, extras);
    }

    /**
     * Closes this client connection, indicating to the system that the underlying
     * {@link ContentProvider} is no longer needed.
     */
    @Override
    public void close() {
        closeInternal();
    }

    /**
     * @deprecated replaced by {@link #close()}.
     */
    @Deprecated
    public boolean release() {
        return closeInternal();
    }

    private boolean closeInternal() {
        // AOSP: true the first time (the provider reference is released), false afterwards.
        // The in-process provider stays alive with the app, as an in-process provider does.
        return mClosed.compareAndSet(false, true);
    }

    /**
     * Get a reference to the {@link ContentProvider} that is associated with this client. If the
     * {@link ContentProvider} is running in a different process then null will be returned.
     */
    public ContentProvider getLocalContentProvider() {
        return mContentProvider;
    }

    /** {@hide} */
    @Deprecated
    public static void closeQuietly(ContentProviderClient client) {
        if (client != null) {
            try {
                client.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** {@hide} */
    @Deprecated
    public static void releaseQuietly(ContentProviderClient client) {
        closeQuietly(client);
    }

    @Override
    public String toString() {
        return "ContentProviderClient{" + mAuthority + (mStable ? "" : " unstable") + "}";
    }
}
