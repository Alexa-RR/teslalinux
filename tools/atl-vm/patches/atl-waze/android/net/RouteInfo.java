/*
 * Copyright (C) 2011 The Android Open Source Project
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

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collection;
import java.util.Objects;

/**
 * teslalinux: AOSP RouteInfo, a destination prefix with its gateway, interface and type.
 */
public final class RouteInfo implements Parcelable {
    public static final int RTN_UNICAST = 1;
    public static final int RTN_UNREACHABLE = 7;
    public static final int RTN_THROW = 9;

    private final IpPrefix mDestination;
    private final InetAddress mGateway;
    private final String mInterface;
    private final int mType;
    private final boolean mIsHost;
    private final boolean mHasGateway;

    public RouteInfo(@Nullable IpPrefix destination, @Nullable InetAddress gateway, @Nullable String iface, int type) {
        switch (type) {
            case RTN_UNICAST:
            case RTN_UNREACHABLE:
            case RTN_THROW:
                break;
            default:
                throw new IllegalArgumentException("Unknown route type " + type);
        }
        if (destination == null) {
            if (gateway != null) {
                if (gateway instanceof Inet4Address) {
                    destination = new IpPrefix(anyAddress(4), 0);
                } else {
                    destination = new IpPrefix(anyAddress(16), 0);
                }
            } else {
                throw new IllegalArgumentException("Invalid arguments passed in: " + gateway + "," + destination);
            }
        }
        if (gateway == null) {
            if (destination.getAddress() instanceof Inet4Address) {
                gateway = anyAddress(4);
            } else {
                gateway = anyAddress(16);
            }
        }
        mHasGateway = (!gateway.isAnyLocalAddress());
        if ((destination.getAddress() instanceof Inet4Address && !(gateway instanceof Inet4Address))
                || (destination.getAddress() instanceof Inet6Address && !(gateway instanceof Inet6Address))) {
            throw new IllegalArgumentException("address family mismatch in RouteInfo constructor");
        }
        mDestination = destination;
        mGateway = gateway;
        mInterface = iface;
        mType = type;
        mIsHost = isHost();
    }

    public RouteInfo(@Nullable IpPrefix destination, @Nullable InetAddress gateway, @Nullable String iface) {
        this(destination, gateway, iface, RTN_UNICAST);
    }

    public RouteInfo(@Nullable IpPrefix destination, @Nullable InetAddress gateway) {
        this(destination, gateway, null);
    }

    public RouteInfo(@Nullable InetAddress gateway) {
        this((IpPrefix) null, gateway, null);
    }

    public RouteInfo(@NonNull IpPrefix destination) {
        this(destination, null, null);
    }

    public RouteInfo(@NonNull IpPrefix destination, int type) {
        this(destination, null, null, type);
    }

    private static InetAddress anyAddress(int bytes) {
        try {
            return InetAddress.getByAddress(new byte[bytes]);
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    private boolean isHost() {
        return (mDestination.getAddress() instanceof Inet4Address && mDestination.getPrefixLength() == 32)
                || (mDestination.getAddress() instanceof Inet6Address && mDestination.getPrefixLength() == 128);
    }

    @NonNull
    public IpPrefix getDestination() {
        return mDestination;
    }

    @Nullable
    public InetAddress getGateway() {
        return mGateway;
    }

    @Nullable
    public String getInterface() {
        return mInterface;
    }

    public int getType() {
        return mType;
    }

    public boolean isHostRoute() {
        return mIsHost;
    }

    public boolean isDefaultRoute() {
        return mType == RTN_UNICAST && mDestination.getPrefixLength() == 0;
    }

    public boolean isIPv4Default() {
        return isDefaultRoute() && mDestination.getAddress() instanceof Inet4Address;
    }

    public boolean isIPv6Default() {
        return isDefaultRoute() && mDestination.getAddress() instanceof Inet6Address;
    }

    public boolean hasGateway() {
        return mHasGateway;
    }

    public boolean matches(InetAddress destination) {
        return mDestination.contains(destination);
    }

    /** AOSP (hidden): the most specific route for a destination. */
    @Nullable
    public static RouteInfo selectBestRoute(Collection<RouteInfo> routes, InetAddress dest) {
        if ((routes == null) || (dest == null)) return null;
        RouteInfo bestRoute = null;
        for (RouteInfo route : routes) {
            if (bestRoute != null && bestRoute.mDestination.getPrefixLength() >= route.mDestination.getPrefixLength()) {
                continue;
            }
            if (route.matches(dest)) bestRoute = route;
        }
        return bestRoute;
    }

    @Override
    public String toString() {
        String val = "";
        if (mDestination != null) val = mDestination.toString();
        if (mType == RTN_UNREACHABLE) {
            val += " unreachable";
        } else if (mType == RTN_THROW) {
            val += " throw";
        } else {
            val += " ->";
            if (mGateway != null) val += " " + mGateway.getHostAddress();
            if (mInterface != null) val += " " + mInterface;
            if (mType != RTN_UNICAST) {
                val += " unknown type " + mType;
            }
        }
        return val;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof RouteInfo)) return false;
        RouteInfo target = (RouteInfo) obj;
        return Objects.equals(mDestination, target.getDestination())
                && Objects.equals(mGateway, target.getGateway())
                && Objects.equals(mInterface, target.getInterface())
                && mType == target.getType();
    }

    @Override
    public int hashCode() {
        return (mDestination.hashCode() * 41)
                + (mGateway == null ? 0 : mGateway.hashCode() * 47)
                + (mInterface == null ? 0 : mInterface.hashCode() * 67)
                + (mType * 71);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeParcelable(mDestination, flags);
        byte[] gatewayBytes = (mGateway == null) ? null : mGateway.getAddress();
        dest.writeByteArray(gatewayBytes);
        dest.writeString(mInterface);
        dest.writeInt(mType);
    }

    public static final Creator<RouteInfo> CREATOR = new Creator<RouteInfo>() {
        public RouteInfo createFromParcel(Parcel in) {
            IpPrefix dest = in.readParcelable(null, IpPrefix.class);
            InetAddress gateway = null;
            byte[] addr = in.createByteArray();
            try {
                gateway = InetAddress.getByAddress(addr);
            } catch (UnknownHostException e) {
            }
            String iface = in.readString();
            int type = in.readInt();
            return new RouteInfo(dest, gateway, iface, type);
        }

        public RouteInfo[] newArray(int size) {
            return new RouteInfo[size];
        }
    };
}
