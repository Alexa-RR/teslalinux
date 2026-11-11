/*
 * Copyright (C) 2014 The Android Open Source Project
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
package android.net;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;

import java.io.FileDescriptor;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.URL;
import java.net.URLConnection;
import java.net.UnknownHostException;

import javax.net.SocketFactory;

/**
 * teslalinux: AOSP Network for a host with a single default network. The framework binds
 * sockets and name resolution to a netId through netd; here every socket already uses the host's
 * one routing table, so binding is the identity and resolution goes through the host resolver.
 */
public class Network implements Parcelable {
    /** AOSP (hidden): the id of this network. */
    public final int netId;

    private static final long HANDLE_MAGIC = 0xcafed00dL;
    private static final int HANDLE_MAGIC_SIZE = 32;

    public Network(int netId) {
        this.netId = netId;
    }

    public Network(@NonNull Network that) {
        this(that.netId);
    }

    @NonNull
    public InetAddress[] getAllByName(String host) throws UnknownHostException {
        return InetAddress.getAllByName(host);
    }

    @NonNull
    public InetAddress getByName(String host) throws UnknownHostException {
        return InetAddress.getByName(host);
    }

    /** AOSP (hidden): the id for the private DNS resolver; the host resolver here. */
    public int getNetIdForResolv() {
        return netId;
    }

    @NonNull
    public SocketFactory getSocketFactory() {
        return SocketFactory.getDefault();
    }

    @NonNull
    public URLConnection openConnection(@NonNull URL url) throws IOException {
        return url.openConnection();
    }

    @NonNull
    public URLConnection openConnection(@NonNull URL url, @NonNull Proxy proxy) throws IOException {
        if (proxy == null) {
            throw new IllegalArgumentException("proxy is null");
        }
        return url.openConnection(proxy);
    }

    public void bindSocket(@NonNull DatagramSocket socket) throws IOException {
        if (socket.isConnected()) {
            throw new java.net.SocketException("Socket is connected");
        }
        // the host has one routing table: the socket already uses this network
    }

    public void bindSocket(@NonNull Socket socket) throws IOException {
        if (socket.isConnected()) {
            throw new java.net.SocketException("Socket is connected");
        }
    }

    public void bindSocket(@NonNull FileDescriptor fd) throws IOException {
        if (fd == null || !fd.valid()) {
            throw new java.net.SocketException("Invalid file descriptor");
        }
    }

    public long getNetworkHandle() {
        if (netId == 0) {
            return 0L; // NETID_UNSET
        }
        return (((long) netId) << HANDLE_MAGIC_SIZE) | HANDLE_MAGIC;
    }

    @Nullable
    public static Network fromNetworkHandle(long networkHandle) {
        if (networkHandle == 0) {
            throw new IllegalArgumentException("Network.fromNetworkHandle refusing to instantiate NETID_UNSET Network.");
        }
        if ((networkHandle & ((1L << HANDLE_MAGIC_SIZE) - 1)) != HANDLE_MAGIC || networkHandle < 0) {
            throw new IllegalArgumentException("Value passed to fromNetworkHandle() is not a network handle.");
        }
        return new Network((int) (networkHandle >> HANDLE_MAGIC_SIZE));
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(netId);
    }

    public static final Creator<Network> CREATOR = new Creator<Network>() {
        public Network createFromParcel(Parcel in) {
            return new Network(in.readInt());
        }

        public Network[] newArray(int size) {
            return new Network[size];
        }
    };

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof Network)) return false;
        Network other = (Network) obj;
        return this.netId == other.netId;
    }

    @Override
    public int hashCode() {
        return netId * 11;
    }

    @Override
    public String toString() {
        return Integer.toString(netId);
    }
}
