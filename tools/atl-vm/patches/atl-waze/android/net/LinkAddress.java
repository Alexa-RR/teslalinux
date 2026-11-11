/*
 * Copyright (C) 2013 The Android Open Source Project
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

/**
 * teslalinux: AOSP LinkAddress, an address with its prefix length and the kernel's flags/scope.
 */
public class LinkAddress implements Parcelable {
    /* RFC 3549 / linux/if_addr.h */
    public static final int IFA_F_SECONDARY = 0x01;
    public static final int IFA_F_TEMPORARY = IFA_F_SECONDARY;
    public static final int IFA_F_NODAD = 0x02;
    public static final int IFA_F_OPTIMISTIC = 0x04;
    public static final int IFA_F_DADFAILED = 0x08;
    public static final int IFA_F_HOMEADDRESS = 0x10;
    public static final int IFA_F_DEPRECATED = 0x20;
    public static final int IFA_F_TENTATIVE = 0x40;
    public static final int IFA_F_PERMANENT = 0x80;
    public static final int RT_SCOPE_UNIVERSE = 0;
    public static final int RT_SCOPE_SITE = 200;
    public static final int RT_SCOPE_LINK = 253;
    public static final int RT_SCOPE_HOST = 254;
    public static final int RT_SCOPE_NOWHERE = 255;

    public static final long LIFETIME_UNKNOWN = -1;
    public static final long LIFETIME_PERMANENT = Long.MAX_VALUE;

    private InetAddress address;
    private int prefixLength;
    private int flags;
    private int scope;
    private long deprecationTime;
    private long expirationTime;

    private static int scopeForUnicastAddress(InetAddress addr) {
        if (addr.isAnyLocalAddress()) {
            return RT_SCOPE_HOST;
        }
        if (addr.isLoopbackAddress() || addr.isLinkLocalAddress()) {
            return RT_SCOPE_LINK;
        }
        if (addr instanceof Inet6Address && addr.isSiteLocalAddress()) {
            return RT_SCOPE_SITE;
        }
        return RT_SCOPE_UNIVERSE;
    }

    /** AOSP (hidden): whether the address is a usable global or private address. */
    public boolean isIpv6() {
        return address instanceof Inet6Address;
    }

    /** @deprecated use isIpv6 */
    @Deprecated
    public boolean isIPv6() {
        return isIpv6();
    }

    public boolean isIpv4() {
        return address instanceof Inet4Address;
    }

    private void init(InetAddress address, int prefixLength, int flags, int scope, long deprecationTime, long expirationTime) {
        if (address == null || address.isMulticastAddress() || prefixLength < 0
                || (address instanceof Inet4Address && prefixLength > 32) || (prefixLength > 128)) {
            throw new IllegalArgumentException("Bad LinkAddress params " + address + "/" + prefixLength);
        }
        this.address = address;
        this.prefixLength = prefixLength;
        this.flags = flags;
        this.scope = scope;
        this.deprecationTime = deprecationTime;
        this.expirationTime = expirationTime;
    }

    public LinkAddress(@NonNull InetAddress address, int prefixLength, int flags, int scope) {
        init(address, prefixLength, flags, scope, LIFETIME_UNKNOWN, LIFETIME_UNKNOWN);
    }

    public LinkAddress(@NonNull InetAddress address, int prefixLength, int flags, int scope, long deprecationTime, long expirationTime) {
        init(address, prefixLength, flags, scope, deprecationTime, expirationTime);
    }

    public LinkAddress(@NonNull InetAddress address, int prefixLength) {
        this(address, prefixLength, 0, 0);
        this.scope = scopeForUnicastAddress(address);
    }

    public LinkAddress(@NonNull java.net.InterfaceAddress interfaceAddress) {
        this(interfaceAddress.getAddress(), interfaceAddress.getNetworkPrefixLength());
    }

    public LinkAddress(@NonNull String address) {
        this(address, 0, 0);
        this.scope = scopeForUnicastAddress(this.address);
    }

    public LinkAddress(@NonNull String address, int flags, int scope) {
        String[] parts = address.split("/");
        if (parts.length < 1 || parts.length > 2) {
            throw new IllegalArgumentException("Invalid link address " + address);
        }
        InetAddress inet;
        try {
            inet = InetAddress.getByName(parts[0].replace("[", "").replace("]", ""));
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Invalid link address " + address);
        }
        int prefix = inet instanceof Inet4Address ? 32 : 128;
        if (parts.length == 2) {
            prefix = Integer.parseInt(parts[1]);
        }
        init(inet, prefix, flags, scope, LIFETIME_UNKNOWN, LIFETIME_UNKNOWN);
    }

    @Override
    public String toString() {
        return address.getHostAddress() + "/" + prefixLength;
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof LinkAddress)) {
            return false;
        }
        LinkAddress linkAddress = (LinkAddress) obj;
        return this.address.equals(linkAddress.address)
                && this.prefixLength == linkAddress.prefixLength
                && this.flags == linkAddress.flags
                && this.scope == linkAddress.scope
                && this.deprecationTime == linkAddress.deprecationTime
                && this.expirationTime == linkAddress.expirationTime;
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(address, prefixLength, flags, scope, deprecationTime, expirationTime);
    }

    public boolean isSameAddressAs(LinkAddress other) {
        if (other == null) {
            return false;
        }
        return address.equals(other.address) && prefixLength == other.prefixLength;
    }

    public InetAddress getAddress() {
        return address;
    }

    public int getPrefixLength() {
        return prefixLength;
    }

    /** @deprecated use getPrefixLength */
    @Deprecated
    public int getNetworkPrefixLength() {
        return getPrefixLength();
    }

    public int getFlags() {
        int flags = this.flags;
        if (deprecationTime != LIFETIME_UNKNOWN) {
            if (android.os.SystemClock.elapsedRealtime() >= deprecationTime) {
                flags |= IFA_F_DEPRECATED;
            } else {
                flags &= ~IFA_F_DEPRECATED;
            }
        }
        if (expirationTime == LIFETIME_PERMANENT) {
            flags |= IFA_F_PERMANENT;
        } else if (expirationTime != LIFETIME_UNKNOWN) {
            flags &= ~IFA_F_PERMANENT;
        }
        return flags;
    }

    public int getScope() {
        return scope;
    }

    public long getDeprecationTime() {
        return deprecationTime;
    }

    public long getExpirationTime() {
        return expirationTime;
    }

    /** AOSP (hidden): whether the address is a global, non-deprecated, usable address. */
    public boolean isGlobalPreferred() {
        int flags = getFlags();
        return (scope == RT_SCOPE_UNIVERSE
                && !isIpv6ULA()
                && (flags & (IFA_F_DADFAILED | IFA_F_DEPRECATED)) == 0L
                && ((flags & IFA_F_TENTATIVE) == 0L || (flags & IFA_F_OPTIMISTIC) != 0L));
    }

    private boolean isIpv6ULA() {
        if (isIpv6()) {
            byte[] bytes = address.getAddress();
            return ((bytes[0] & (byte) 0xfe) == (byte) 0xfc);
        }
        return false;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeByteArray(address.getAddress());
        dest.writeInt(prefixLength);
        dest.writeInt(this.flags);
        dest.writeInt(scope);
        dest.writeLong(deprecationTime);
        dest.writeLong(expirationTime);
    }

    public static final Creator<LinkAddress> CREATOR = new Creator<LinkAddress>() {
        public LinkAddress createFromParcel(Parcel in) {
            InetAddress address = null;
            try {
                address = InetAddress.getByAddress(in.createByteArray());
            } catch (UnknownHostException e) {
                // the parcel came from writeToParcel, so this cannot happen
            }
            int prefixLength = in.readInt();
            int flags = in.readInt();
            int scope = in.readInt();
            long deprecationTime = in.readLong();
            long expirationTime = in.readLong();
            return new LinkAddress(address, prefixLength, flags, scope, deprecationTime, expirationTime);
        }

        public LinkAddress[] newArray(int size) {
            return new LinkAddress[size];
        }
    };
}
