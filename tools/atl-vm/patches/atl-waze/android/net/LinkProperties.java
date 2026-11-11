/*
 * Copyright (C) 2010 The Android Open Source Project
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

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Hashtable;
import java.util.List;
import java.util.Objects;

/**
 * teslalinux: AOSP LinkProperties (the public API and the hidden members apps reach through the
 * framework), with a builder that reads a Linux interface's addresses, routes and resolver.
 */
public final class LinkProperties implements Parcelable {
    private String mIfaceName;
    private final ArrayList<LinkAddress> mLinkAddresses = new ArrayList<>();
    private final ArrayList<InetAddress> mDnses = new ArrayList<>();
    private final ArrayList<InetAddress> mValidatedPrivateDnses = new ArrayList<>();
    private boolean mUsePrivateDns;
    private String mPrivateDnsServerName;
    private String mDomains;
    private final ArrayList<RouteInfo> mRoutes = new ArrayList<>();
    private Inet4Address mDhcpServerAddress;
    private ProxyInfo mHttpProxy;
    private int mMtu;
    private String mTcpBufferSizes;
    private IpPrefix mNat64Prefix;
    private boolean mWakeOnLanSupported;
    private final Hashtable<String, LinkProperties> mStackedLinks = new Hashtable<>();

    public LinkProperties() {
    }

    public LinkProperties(@Nullable LinkProperties source) {
        if (source != null) {
            mIfaceName = source.mIfaceName;
            mLinkAddresses.addAll(source.mLinkAddresses);
            mDnses.addAll(source.mDnses);
            mValidatedPrivateDnses.addAll(source.mValidatedPrivateDnses);
            mUsePrivateDns = source.mUsePrivateDns;
            mPrivateDnsServerName = source.mPrivateDnsServerName;
            mDomains = source.mDomains;
            mRoutes.addAll(source.mRoutes);
            mHttpProxy = source.mHttpProxy == null ? null : new ProxyInfo(source.mHttpProxy);
            for (LinkProperties l : source.mStackedLinks.values()) {
                addStackedLink(l);
            }
            mMtu = source.mMtu;
            mTcpBufferSizes = source.mTcpBufferSizes;
            mNat64Prefix = source.mNat64Prefix;
            mWakeOnLanSupported = source.mWakeOnLanSupported;
            mDhcpServerAddress = source.mDhcpServerAddress;
        }
    }

    /** Builds the properties of a host interface from the kernel's view of it. */
    static LinkProperties tlFromSystem(String iface) {
        LinkProperties lp = new LinkProperties();
        lp.setInterfaceName(iface);
        try {
            NetworkInterface ni = NetworkInterface.getByName(iface);
            if (ni != null) {
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    if (ia.getAddress() != null && !ia.getAddress().isLoopbackAddress())
                        lp.addLinkAddress(new LinkAddress(ia.getAddress(), ia.getNetworkPrefixLength()));
                }
                lp.setMtu(ni.getMTU());
            }
        } catch (SocketException e) {
            // an interface without addresses, like a link that is still coming up
        }
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/net/route"))) {
            String line = r.readLine();
            while ((line = r.readLine()) != null) {
                String[] f = line.trim().split("\\s+");
                if (f.length < 8 || !f[0].equals(iface))
                    continue;
                int flags = Integer.parseInt(f[3], 16);
                if ((flags & 1) == 0) // RTF_UP
                    continue;
                InetAddress dest = hexToInet4(f[1]);
                InetAddress gateway = (flags & 2) != 0 ? hexToInet4(f[2]) : null; // RTF_GATEWAY
                int prefix = Integer.bitCount(Integer.reverseBytes((int) Long.parseLong(f[7], 16)));
                lp.addRoute(new RouteInfo(new IpPrefix(dest, prefix), gateway, iface, RouteInfo.RTN_UNICAST));
            }
        } catch (IOException | NumberFormatException e) {
        }
        try (BufferedReader r = new BufferedReader(new FileReader("/etc/resolv.conf"))) {
            String line;
            StringBuilder domains = new StringBuilder();
            while ((line = r.readLine()) != null) {
                String t = line.trim();
                if (t.startsWith("nameserver ")) {
                    try {
                        lp.addDnsServer(InetAddress.getByName(t.substring(11).trim().split("%")[0]));
                    } catch (UnknownHostException | IllegalArgumentException e) {
                    }
                } else if (t.startsWith("search ") || t.startsWith("domain ")) {
                    for (String d : t.substring(7).trim().split("\\s+")) {
                        if (domains.length() > 0)
                            domains.append(',');
                        domains.append(d);
                    }
                }
            }
            if (domains.length() > 0)
                lp.setDomains(domains.toString());
        } catch (IOException e) {
        }
        return lp;
    }

    private static InetAddress hexToInet4(String hex) throws NumberFormatException {
        long v = Long.parseLong(hex, 16); // little-endian in /proc/net/route
        byte[] b = {(byte) (v & 0xff), (byte) ((v >> 8) & 0xff), (byte) ((v >> 16) & 0xff), (byte) ((v >> 24) & 0xff)};
        try {
            return InetAddress.getByAddress(b);
        } catch (UnknownHostException e) {
            throw new NumberFormatException(hex);
        }
    }

    public void setInterfaceName(@Nullable String iface) {
        mIfaceName = iface;
        ArrayList<RouteInfo> newRoutes = new ArrayList<>(mRoutes.size());
        for (RouteInfo route : mRoutes) {
            newRoutes.add(routeWithInterface(route));
        }
        mRoutes.clear();
        mRoutes.addAll(newRoutes);
    }

    @Nullable
    public String getInterfaceName() {
        return mIfaceName;
    }

    /** AOSP (hidden): this interface and every stacked one. */
    @NonNull
    public List<String> getAllInterfaceNames() {
        List<String> interfaceNames = new ArrayList<>(mStackedLinks.size() + 1);
        if (mIfaceName != null) interfaceNames.add(mIfaceName);
        for (LinkProperties stacked : mStackedLinks.values()) {
            interfaceNames.addAll(stacked.getAllInterfaceNames());
        }
        return interfaceNames;
    }

    /** AOSP (hidden): the addresses of this link. */
    @NonNull
    public List<InetAddress> getAddresses() {
        final List<InetAddress> addresses = new ArrayList<>();
        for (LinkAddress linkAddress : mLinkAddresses) {
            addresses.add(linkAddress.getAddress());
        }
        return Collections.unmodifiableList(addresses);
    }

    @NonNull
    public List<InetAddress> getAllAddresses() {
        List<InetAddress> addresses = new ArrayList<>();
        for (LinkAddress linkAddress : mLinkAddresses) {
            addresses.add(linkAddress.getAddress());
        }
        for (LinkProperties stacked : mStackedLinks.values()) {
            addresses.addAll(stacked.getAllAddresses());
        }
        return addresses;
    }

    private int findLinkAddressIndex(LinkAddress address) {
        for (int i = 0; i < mLinkAddresses.size(); i++) {
            if (mLinkAddresses.get(i).isSameAddressAs(address)) {
                return i;
            }
        }
        return -1;
    }

    public boolean addLinkAddress(@NonNull LinkAddress address) {
        if (address == null) {
            return false;
        }
        int i = findLinkAddressIndex(address);
        if (i < 0) {
            mLinkAddresses.add(address);
            return true;
        } else if (mLinkAddresses.get(i).equals(address)) {
            return false;
        } else {
            mLinkAddresses.set(i, address);
            return true;
        }
    }

    public boolean removeLinkAddress(@NonNull LinkAddress toRemove) {
        int i = findLinkAddressIndex(toRemove);
        if (i >= 0) {
            mLinkAddresses.remove(i);
            return true;
        }
        return false;
    }

    @NonNull
    public List<LinkAddress> getLinkAddresses() {
        return Collections.unmodifiableList(mLinkAddresses);
    }

    @NonNull
    public List<LinkAddress> getAllLinkAddresses() {
        List<LinkAddress> addresses = new ArrayList<>(mLinkAddresses);
        for (LinkProperties stacked : mStackedLinks.values()) {
            addresses.addAll(stacked.getAllLinkAddresses());
        }
        return addresses;
    }

    public void setLinkAddresses(@NonNull Collection<LinkAddress> addresses) {
        mLinkAddresses.clear();
        for (LinkAddress address : addresses) {
            addLinkAddress(address);
        }
    }

    public boolean addDnsServer(@NonNull InetAddress dnsServer) {
        if (dnsServer != null && !mDnses.contains(dnsServer)) {
            mDnses.add(dnsServer);
            return true;
        }
        return false;
    }

    public boolean removeDnsServer(@NonNull InetAddress dnsServer) {
        return mDnses.remove(dnsServer);
    }

    public void setDnsServers(@NonNull Collection<InetAddress> dnsServers) {
        mDnses.clear();
        for (InetAddress dnsServer : dnsServers) {
            addDnsServer(dnsServer);
        }
    }

    @NonNull
    public List<InetAddress> getDnsServers() {
        return Collections.unmodifiableList(mDnses);
    }

    public void setUsePrivateDns(boolean usePrivateDns) {
        mUsePrivateDns = usePrivateDns;
    }

    public boolean isPrivateDnsActive() {
        return mUsePrivateDns;
    }

    public void setPrivateDnsServerName(@Nullable String privateDnsServerName) {
        mPrivateDnsServerName = privateDnsServerName;
    }

    @Nullable
    public String getPrivateDnsServerName() {
        return mPrivateDnsServerName;
    }

    public boolean addValidatedPrivateDnsServer(@NonNull InetAddress dnsServer) {
        if (dnsServer != null && !mValidatedPrivateDnses.contains(dnsServer)) {
            mValidatedPrivateDnses.add(dnsServer);
            return true;
        }
        return false;
    }

    public boolean removeValidatedPrivateDnsServer(@NonNull InetAddress dnsServer) {
        return mValidatedPrivateDnses.remove(dnsServer);
    }

    public void setValidatedPrivateDnsServers(@NonNull Collection<InetAddress> dnsServers) {
        mValidatedPrivateDnses.clear();
        for (InetAddress dnsServer : dnsServers) {
            addValidatedPrivateDnsServer(dnsServer);
        }
    }

    @NonNull
    public List<InetAddress> getValidatedPrivateDnsServers() {
        return Collections.unmodifiableList(mValidatedPrivateDnses);
    }

    public void setDhcpServerAddress(@Nullable Inet4Address serverAddress) {
        mDhcpServerAddress = serverAddress;
    }

    @Nullable
    public Inet4Address getDhcpServerAddress() {
        return mDhcpServerAddress;
    }

    public void setDomains(@Nullable String domains) {
        mDomains = domains;
    }

    @Nullable
    public String getDomains() {
        return mDomains;
    }

    public void setMtu(int mtu) {
        mMtu = mtu;
    }

    public int getMtu() {
        return mMtu;
    }

    public void setTcpBufferSizes(@Nullable String tcpBufferSizes) {
        mTcpBufferSizes = tcpBufferSizes;
    }

    @Nullable
    public String getTcpBufferSizes() {
        return mTcpBufferSizes;
    }

    private RouteInfo routeWithInterface(RouteInfo route) {
        return new RouteInfo(route.getDestination(), route.getGateway(), mIfaceName, route.getType());
    }

    private int findRouteIndexByRouteKey(RouteInfo route) {
        for (int i = 0; i < mRoutes.size(); i++) {
            RouteInfo r = mRoutes.get(i);
            if (Objects.equals(r.getDestination(), route.getDestination()) && Objects.equals(r.getInterface(), route.getInterface()))
                return i;
        }
        return -1;
    }

    public boolean addRoute(@NonNull RouteInfo route) {
        String routeIface = route.getInterface();
        if (routeIface != null && !routeIface.equals(mIfaceName)) {
            throw new IllegalArgumentException("Route added with non-matching interface: " + routeIface + " vs. " + mIfaceName);
        }
        route = routeWithInterface(route);
        int i = findRouteIndexByRouteKey(route);
        if (i == -1) {
            mRoutes.add(route);
            return true;
        }
        if (!mRoutes.get(i).equals(route)) {
            mRoutes.set(i, route);
            return true;
        }
        return false;
    }

    public boolean removeRoute(@NonNull RouteInfo route) {
        return Objects.equals(mIfaceName, route.getInterface()) && mRoutes.remove(route);
    }

    @NonNull
    public List<RouteInfo> getRoutes() {
        return Collections.unmodifiableList(mRoutes);
    }

    /** AOSP (hidden): the routes of this link and the stacked ones. */
    @NonNull
    public List<RouteInfo> getAllRoutes() {
        List<RouteInfo> routes = new ArrayList<>(mRoutes);
        for (LinkProperties stacked : mStackedLinks.values()) {
            routes.addAll(stacked.getAllRoutes());
        }
        return routes;
    }

    public void setHttpProxy(@Nullable ProxyInfo proxy) {
        mHttpProxy = proxy;
    }

    @Nullable
    public ProxyInfo getHttpProxy() {
        return mHttpProxy;
    }

    @Nullable
    public IpPrefix getNat64Prefix() {
        return mNat64Prefix;
    }

    public void setNat64Prefix(@Nullable IpPrefix prefix) {
        if (prefix != null && prefix.getPrefixLength() != 96) {
            throw new IllegalArgumentException("Only 96-bit NAT64 prefixes are supported: " + prefix);
        }
        mNat64Prefix = prefix;
    }

    public boolean addStackedLink(@NonNull LinkProperties link) {
        if (link.getInterfaceName() != null) {
            mStackedLinks.put(link.getInterfaceName(), link);
            return true;
        }
        return false;
    }

    public boolean removeStackedLink(@NonNull String iface) {
        LinkProperties removed = mStackedLinks.remove(iface);
        return removed != null;
    }

    @NonNull
    public List<LinkProperties> getStackedLinks() {
        if (mStackedLinks.isEmpty()) {
            return Collections.emptyList();
        }
        final List<LinkProperties> stacked = new ArrayList<>();
        for (LinkProperties link : mStackedLinks.values()) {
            stacked.add(new LinkProperties(link));
        }
        return Collections.unmodifiableList(stacked);
    }

    public void clear() {
        mIfaceName = null;
        mLinkAddresses.clear();
        mDnses.clear();
        mUsePrivateDns = false;
        mPrivateDnsServerName = null;
        mDomains = null;
        mRoutes.clear();
        mHttpProxy = null;
        mStackedLinks.clear();
        mMtu = 0;
        mTcpBufferSizes = null;
        mNat64Prefix = null;
        mWakeOnLanSupported = false;
        mDhcpServerAddress = null;
        mValidatedPrivateDnses.clear();
    }

    public boolean hasIpv4Address() {
        for (LinkAddress address : mLinkAddresses) {
            if (address.getAddress() instanceof Inet4Address) {
                return true;
            }
        }
        return false;
    }

    public boolean hasGlobalIpv6Address() {
        for (LinkAddress address : mLinkAddresses) {
            if (address.getAddress() instanceof Inet6Address && address.isGlobalPreferred()) {
                return true;
            }
        }
        return false;
    }

    public boolean hasIpv4DefaultRoute() {
        for (RouteInfo r : mRoutes) {
            if (r.isIPv4Default()) {
                return true;
            }
        }
        return false;
    }

    public boolean hasIpv6DefaultRoute() {
        for (RouteInfo r : mRoutes) {
            if (r.isIPv6Default()) {
                return true;
            }
        }
        return false;
    }

    public boolean hasIpv4DnsServer() {
        for (InetAddress ia : mDnses) {
            if (ia instanceof Inet4Address) {
                return true;
            }
        }
        return false;
    }

    public boolean hasIpv6DnsServer() {
        for (InetAddress ia : mDnses) {
            if (ia instanceof Inet6Address) {
                return true;
            }
        }
        return false;
    }

    public boolean isIpv4Provisioned() {
        return (hasIpv4Address() && hasIpv4DefaultRoute() && hasIpv4DnsServer());
    }

    public boolean isIpv6Provisioned() {
        return (hasGlobalIpv6Address() && hasIpv6DefaultRoute() && hasIpv6DnsServer());
    }

    public boolean isProvisioned() {
        return (isIpv4Provisioned() || isIpv6Provisioned());
    }

    public boolean isReachable(@NonNull InetAddress ip) {
        final List<RouteInfo> allRoutes = getAllRoutes();
        final RouteInfo bestRoute = RouteInfo.selectBestRoute(allRoutes, ip);
        if (bestRoute == null) {
            return false;
        }
        final List<LinkAddress> allAddresses = getAllLinkAddresses();
        for (LinkAddress addr : allAddresses) {
            if (addr.getAddress().equals(ip)) {
                return true; // one of our own
            }
        }
        if (bestRoute.hasGateway()) {
            return isReachable(bestRoute.getGateway());
        }
        return true; // an on-link destination
    }

    public void setWakeOnLanSupported(boolean supported) {
        mWakeOnLanSupported = supported;
    }

    public boolean isWakeOnLanSupported() {
        return mWakeOnLanSupported;
    }

    @Override
    public String toString() {
        StringBuilder resultJoiner = new StringBuilder("{");
        resultJoiner.append("InterfaceName: ").append(mIfaceName);
        resultJoiner.append(" LinkAddresses: ").append(mLinkAddresses);
        resultJoiner.append(" DnsAddresses: ").append(mDnses);
        if (mUsePrivateDns) {
            resultJoiner.append(" UsePrivateDns: true");
        }
        if (mPrivateDnsServerName != null) {
            resultJoiner.append(" PrivateDnsServerName: ").append(mPrivateDnsServerName);
        }
        if (!mValidatedPrivateDnses.isEmpty()) {
            resultJoiner.append(" ValidatedPrivateDnsAddresses: ").append(mValidatedPrivateDnses);
        }
        resultJoiner.append(" Domains: ").append(mDomains);
        resultJoiner.append(" MTU: ").append(mMtu);
        if (mWakeOnLanSupported) {
            resultJoiner.append(" WakeOnLanSupported: true");
        }
        if (mDhcpServerAddress != null) {
            resultJoiner.append(" ServerAddress: ").append(mDhcpServerAddress);
        }
        if (mTcpBufferSizes != null) {
            resultJoiner.append(" TcpBufferSizes: ").append(mTcpBufferSizes);
        }
        resultJoiner.append(" Routes: ").append(mRoutes);
        if (mHttpProxy != null) {
            resultJoiner.append(" HttpProxy: ").append(mHttpProxy);
        }
        if (mNat64Prefix != null) {
            resultJoiner.append(" Nat64Prefix: ").append(mNat64Prefix);
        }
        if (!mStackedLinks.isEmpty()) {
            resultJoiner.append(" Stacked: [").append(mStackedLinks.values()).append("]");
        }
        return resultJoiner.append("}").toString();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof LinkProperties)) return false;
        LinkProperties target = (LinkProperties) obj;
        return Objects.equals(mIfaceName, target.mIfaceName)
                && mLinkAddresses.size() == target.mLinkAddresses.size() && mLinkAddresses.containsAll(target.mLinkAddresses)
                && mDnses.size() == target.mDnses.size() && mDnses.containsAll(target.mDnses)
                && mUsePrivateDns == target.mUsePrivateDns
                && Objects.equals(mPrivateDnsServerName, target.mPrivateDnsServerName)
                && mValidatedPrivateDnses.size() == target.mValidatedPrivateDnses.size() && mValidatedPrivateDnses.containsAll(target.mValidatedPrivateDnses)
                && Objects.equals(mDomains, target.mDomains)
                && mRoutes.size() == target.mRoutes.size() && mRoutes.containsAll(target.mRoutes)
                && Objects.equals(mHttpProxy, target.mHttpProxy)
                && mStackedLinks.equals(target.mStackedLinks)
                && mMtu == target.mMtu
                && Objects.equals(mTcpBufferSizes, target.mTcpBufferSizes)
                && Objects.equals(mNat64Prefix, target.mNat64Prefix)
                && mWakeOnLanSupported == target.mWakeOnLanSupported
                && Objects.equals(mDhcpServerAddress, target.mDhcpServerAddress);
    }

    @Override
    public int hashCode() {
        return ((null == mIfaceName) ? 0 : mIfaceName.hashCode())
                + mLinkAddresses.size() * 31
                + mDnses.size() * 37
                + mValidatedPrivateDnses.size() * 61
                + ((null == mDomains) ? 0 : mDomains.hashCode())
                + mRoutes.size() * 41
                + ((null == mHttpProxy) ? 0 : mHttpProxy.hashCode())
                + mStackedLinks.hashCode() * 47
                + mMtu * 51
                + ((null == mTcpBufferSizes) ? 0 : mTcpBufferSizes.hashCode())
                + (mUsePrivateDns ? 57 : 0)
                + ((null == mPrivateDnsServerName) ? 0 : mPrivateDnsServerName.hashCode())
                + Objects.hash(mNat64Prefix)
                + (mWakeOnLanSupported ? 71 : 0)
                + Objects.hash(mDhcpServerAddress);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(getInterfaceName());
        dest.writeInt(mLinkAddresses.size());
        for (LinkAddress linkAddress : mLinkAddresses) {
            dest.writeParcelable(linkAddress, flags);
        }
        writeAddresses(dest, mDnses);
        writeAddresses(dest, mValidatedPrivateDnses);
        dest.writeBoolean(mUsePrivateDns);
        dest.writeString(mPrivateDnsServerName);
        dest.writeString(mDomains);
        dest.writeInt(mMtu);
        dest.writeString(mTcpBufferSizes);
        dest.writeInt(mRoutes.size());
        for (RouteInfo route : mRoutes) {
            dest.writeParcelable(route, flags);
        }
        if (mHttpProxy != null) {
            dest.writeByte((byte) 1);
            dest.writeParcelable(mHttpProxy, flags);
        } else {
            dest.writeByte((byte) 0);
        }
        dest.writeParcelable(mNat64Prefix, 0);
        ArrayList<LinkProperties> stackedLinks = new ArrayList<>(mStackedLinks.values());
        dest.writeList(stackedLinks);
        dest.writeBoolean(mWakeOnLanSupported);
        writeAddress(dest, mDhcpServerAddress);
    }

    private static void writeAddresses(Parcel dest, List<InetAddress> addresses) {
        dest.writeInt(addresses.size());
        for (InetAddress a : addresses) {
            writeAddress(dest, a);
        }
    }

    private static void writeAddress(Parcel dest, InetAddress address) {
        dest.writeByteArray(address == null ? null : address.getAddress());
    }

    private static InetAddress readAddress(Parcel in) {
        byte[] b = in.createByteArray();
        if (b == null)
            return null;
        try {
            return InetAddress.getByAddress(b);
        } catch (UnknownHostException e) {
            return null;
        }
    }

    public static final Creator<LinkProperties> CREATOR = new Creator<LinkProperties>() {
        public LinkProperties createFromParcel(Parcel in) {
            LinkProperties netProp = new LinkProperties();
            netProp.setInterfaceName(in.readString());
            int addressCount = in.readInt();
            for (int i = 0; i < addressCount; i++) {
                netProp.addLinkAddress(in.readParcelable(null, LinkAddress.class));
            }
            addressCount = in.readInt();
            for (int i = 0; i < addressCount; i++) {
                InetAddress a = readAddress(in);
                if (a != null)
                    netProp.addDnsServer(a);
            }
            addressCount = in.readInt();
            for (int i = 0; i < addressCount; i++) {
                InetAddress a = readAddress(in);
                if (a != null)
                    netProp.addValidatedPrivateDnsServer(a);
            }
            netProp.setUsePrivateDns(in.readBoolean());
            netProp.setPrivateDnsServerName(in.readString());
            netProp.setDomains(in.readString());
            netProp.setMtu(in.readInt());
            netProp.setTcpBufferSizes(in.readString());
            addressCount = in.readInt();
            for (int i = 0; i < addressCount; i++) {
                netProp.addRoute(in.readParcelable(null, RouteInfo.class));
            }
            if (in.readByte() == 1) {
                netProp.setHttpProxy(in.readParcelable(null, ProxyInfo.class));
            }
            netProp.setNat64Prefix(in.readParcelable(null, IpPrefix.class));
            ArrayList<Object> stackedLinks = new ArrayList<>();
            try {
                in.readList(stackedLinks, LinkProperties.class.getClassLoader());
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
            for (Object stackedLink : stackedLinks) {
                netProp.addStackedLink((LinkProperties) stackedLink);
            }
            netProp.setWakeOnLanSupported(in.readBoolean());
            InetAddress dhcp = readAddress(in);
            if (dhcp instanceof Inet4Address)
                netProp.setDhcpServerAddress((Inet4Address) dhcp);
            return netProp;
        }

        public LinkProperties[] newArray(int size) {
            return new LinkProperties[size];
        }
    };
}
