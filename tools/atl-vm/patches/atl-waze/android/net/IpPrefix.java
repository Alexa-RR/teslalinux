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
import android.os.Parcel;
import android.os.Parcelable;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;

/**
 * teslalinux: AOSP IpPrefix, an address with a prefix length; the address bits past the prefix
 * are cleared.
 */
public final class IpPrefix implements Parcelable {
    private final byte[] address;
    private final int prefixLength;

    private static void maskRawAddress(byte[] array, int prefixLength) {
        if (prefixLength < 0 || prefixLength > array.length * 8) {
            throw new IllegalArgumentException("IP address with " + array.length + " bytes has invalid prefix length " + prefixLength);
        }
        int offset = prefixLength / 8;
        int remainder = prefixLength % 8;
        byte mask = (byte) (0xFF << (8 - remainder));
        if (offset < array.length) array[offset] = (byte) (array[offset] & mask);
        offset++;
        for (; offset < array.length; offset++) {
            array[offset] = 0;
        }
    }

    private void checkAndMaskAddressAndPrefixLength() {
        if (address.length != 4 && address.length != 16) {
            throw new IllegalArgumentException("IpPrefix has " + address.length + " bytes which is neither 4 nor 16");
        }
        maskRawAddress(address, prefixLength);
    }

    public IpPrefix(@NonNull byte[] address, int prefixLength) {
        this.address = address.clone();
        this.prefixLength = prefixLength;
        checkAndMaskAddressAndPrefixLength();
    }

    public IpPrefix(@NonNull InetAddress address, int prefixLength) {
        this.address = address.getAddress();
        this.prefixLength = prefixLength;
        checkAndMaskAddressAndPrefixLength();
    }

    public IpPrefix(@NonNull String prefix) {
        String[] parts = prefix.split("/", -1);
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid IP prefix " + prefix);
        }
        try {
            this.address = InetAddress.getByName(parts[0]).getAddress();
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Invalid IP prefix " + prefix);
        }
        this.prefixLength = Integer.parseInt(parts[1]);
        checkAndMaskAddressAndPrefixLength();
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof IpPrefix)) {
            return false;
        }
        IpPrefix that = (IpPrefix) obj;
        return Arrays.equals(this.address, that.address) && this.prefixLength == that.prefixLength;
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(address) + 11 * prefixLength;
    }

    @NonNull
    public InetAddress getAddress() {
        try {
            return InetAddress.getByAddress(address);
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    @NonNull
    public byte[] getRawAddress() {
        return address.clone();
    }

    public int getPrefixLength() {
        return prefixLength;
    }

    public boolean contains(@NonNull InetAddress address) {
        byte[] addrBytes = address.getAddress();
        if (addrBytes == null || addrBytes.length != this.address.length) {
            return false;
        }
        maskRawAddress(addrBytes, prefixLength);
        return Arrays.equals(this.address, addrBytes);
    }

    /** AOSP (hidden): whether this prefix contains the other one. */
    public boolean containsPrefix(@NonNull IpPrefix otherPrefix) {
        if (otherPrefix.getPrefixLength() < prefixLength) return false;
        final byte[] otherAddress = otherPrefix.getRawAddress();
        maskRawAddress(otherAddress, prefixLength);
        return Arrays.equals(otherAddress, address);
    }

    public boolean isIPv6() {
        return getAddress() instanceof Inet6Address;
    }

    public boolean isIPv4() {
        return getAddress() instanceof Inet4Address;
    }

    @Override
    public String toString() {
        try {
            return InetAddress.getByAddress(address).getHostAddress() + "/" + prefixLength;
        } catch (UnknownHostException e) {
            throw new IllegalStateException("IpPrefix with invalid address! Shouldn't happen.", e);
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeByteArray(address);
        dest.writeInt(prefixLength);
    }

    public static final Creator<IpPrefix> CREATOR = new Creator<IpPrefix>() {
        public IpPrefix createFromParcel(Parcel in) {
            byte[] address = in.createByteArray();
            int prefixLength = in.readInt();
            return new IpPrefix(address, prefixLength);
        }

        public IpPrefix[] newArray(int size) {
            return new IpPrefix[size];
        }
    };
}
