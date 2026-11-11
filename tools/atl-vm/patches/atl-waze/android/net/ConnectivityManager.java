/*
 * Copyright (C) 2008 The Android Open Source Project
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
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * teslalinux: the AOSP ConnectivityManager contract on a Linux host that has one default network.
 *
 * The network's existence, its metered hint and its connectivity (full / captive portal / limited)
 * come from GLib's GNetworkMonitor; the transport (Wi-Fi, Ethernet, cellular modem, VPN tunnel) and
 * the link properties come from the default-route interface in /proc/net/route, /sys/class/net and
 * /etc/resolv.conf. Callbacks follow the framework's contract: a registration whose request the
 * network satisfies receives onAvailable, onCapabilitiesChanged, onLinkPropertiesChanged and
 * onBlockedStatusChanged right away (on its Handler, or on the ConnectivityThread like AOSP does
 * when none is given), onLost when the default route disappears, and CONNECTIVITY_ACTION is
 * broadcast on every change for the receivers that still listen to it.
 */
public class ConnectivityManager {
    private static final String TAG = "ConnectivityManager";

    @Deprecated
    public static final String CONNECTIVITY_ACTION = "android.net.conn.CONNECTIVITY_CHANGE";
    public static final String ACTION_CAPTIVE_PORTAL_SIGN_IN = "android.net.conn.CAPTIVE_PORTAL";
    public static final String ACTION_RESTRICT_BACKGROUND_CHANGED = "android.net.conn.RESTRICT_BACKGROUND_CHANGED";
    @Deprecated
    public static final String ACTION_BACKGROUND_DATA_SETTING_CHANGED = "android.net.conn.BACKGROUND_DATA_SETTING_CHANGED";
    public static final String ACTION_DATA_ACTIVITY_CHANGE = "android.net.conn.DATA_ACTIVITY_CHANGE";
    @Deprecated
    public static final String EXTRA_NETWORK_INFO = "networkInfo";
    public static final String EXTRA_NETWORK_TYPE = "networkType";
    public static final String EXTRA_IS_FAILOVER = "isFailover";
    public static final String EXTRA_OTHER_NETWORK_INFO = "otherNetwork";
    public static final String EXTRA_NO_CONNECTIVITY = "noConnectivity";
    public static final String EXTRA_REASON = "reason";
    public static final String EXTRA_EXTRA_INFO = "extraInfo";
    public static final String EXTRA_INET_CONDITION = "inetCondition";
    public static final String EXTRA_CAPTIVE_PORTAL = "android.net.extra.CAPTIVE_PORTAL";
    public static final String EXTRA_CAPTIVE_PORTAL_URL = "android.net.extra.CAPTIVE_PORTAL_URL";
    public static final String EXTRA_CAPTIVE_PORTAL_PROBE_SPEC = "android.net.extra.CAPTIVE_PORTAL_PROBE_SPEC";
    public static final String EXTRA_CAPTIVE_PORTAL_USER_AGENT = "android.net.extra.CAPTIVE_PORTAL_USER_AGENT";
    public static final String EXTRA_DEVICE_TYPE = "deviceType";
    public static final String EXTRA_IS_ACTIVE = "isActive";
    public static final String EXTRA_REALTIME_NS = "tsNanos";
    public static final String EXTRA_IS_CAPTIVE_PORTAL = "captivePortal";
    public static final String EXTRA_NETWORK = "android.net.extra.NETWORK";
    public static final String EXTRA_NETWORK_REQUEST = "android.net.extra.NETWORK_REQUEST";

    @Deprecated public static final int TYPE_NONE = -1;
    @Deprecated public static final int TYPE_MOBILE = 0;
    @Deprecated public static final int TYPE_WIFI = 1;
    @Deprecated public static final int TYPE_MOBILE_MMS = 2;
    @Deprecated public static final int TYPE_MOBILE_SUPL = 3;
    @Deprecated public static final int TYPE_MOBILE_DUN = 4;
    @Deprecated public static final int TYPE_MOBILE_HIPRI = 5;
    @Deprecated public static final int TYPE_WIMAX = 6;
    @Deprecated public static final int TYPE_BLUETOOTH = 7;
    @Deprecated public static final int TYPE_DUMMY = 8;
    @Deprecated public static final int TYPE_ETHERNET = 9;
    public static final int TYPE_MOBILE_FOTA = 10;
    public static final int TYPE_MOBILE_IMS = 11;
    public static final int TYPE_MOBILE_CBS = 12;
    public static final int TYPE_WIFI_P2P = 13;
    public static final int TYPE_MOBILE_IA = 14;
    public static final int TYPE_MOBILE_EMERGENCY = 15;
    public static final int TYPE_PROXY = 16;
    @Deprecated public static final int TYPE_VPN = 17;
    public static final int TYPE_TEST = 18;
    public static final int MAX_RADIO_TYPE = TYPE_TEST;
    public static final int MAX_NETWORK_TYPE = TYPE_TEST;
    @Deprecated public static final int DEFAULT_NETWORK_PREFERENCE = TYPE_WIFI;
    public static final int NETID_UNSET = 0;
    /** AOSP (hidden): the request id of a request that was never registered. */
    public static final int REQUEST_ID_UNSET = 0;

    public static final int RESTRICT_BACKGROUND_STATUS_DISABLED = 1;
    public static final int RESTRICT_BACKGROUND_STATUS_WHITELISTED = 2;
    public static final int RESTRICT_BACKGROUND_STATUS_ENABLED = 3;

    public static final int MULTIPATH_PREFERENCE_HANDOVER = 1 << 0;
    public static final int MULTIPATH_PREFERENCE_RELIABILITY = 1 << 1;
    public static final int MULTIPATH_PREFERENCE_PERFORMANCE = 1 << 2;
    /** AOSP (hidden): every multipath preference, the value for unmetered networks. */
    public static final int MULTIPATH_PREFERENCE_UNMETERED = MULTIPATH_PREFERENCE_HANDOVER | MULTIPATH_PREFERENCE_RELIABILITY | MULTIPATH_PREFERENCE_PERFORMANCE;

    /** The id the single default network gets; it changes when the default interface changes (a new network in AOSP terms). */
    private static final int FIRST_NETID = 100;

    public interface OnNetworkActiveListener {
        void onNetworkActive();
    }

    /** AOSP: callbacks about the networks that match a request. */
    public static class NetworkCallback {
        /** AOSP (API 31): the callback wants location-sensitive transport info. */
        public static final int FLAG_NONE = 0;
        public static final int FLAG_INCLUDE_LOCATION_INFO = 1 << 0;

        final int mFlags;

        public NetworkCallback() {
            this(FLAG_NONE);
        }

        public NetworkCallback(int flags) {
            mFlags = flags;
        }

        public void onPreCheck(@NonNull Network network) {}

        /** AOSP (hidden): the composite callback the framework delivers; it fans out to the public ones. */
        public void onAvailable(@NonNull Network network, @NonNull NetworkCapabilities networkCapabilities,
                @NonNull LinkProperties linkProperties, boolean blocked) {
            onAvailable(network);
            if (!networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)) {
                onNetworkSuspended(network);
            }
            onCapabilitiesChanged(network, networkCapabilities);
            onLinkPropertiesChanged(network, linkProperties);
            onBlockedStatusChanged(network, blocked);
        }

        public void onAvailable(@NonNull Network network) {}

        public void onLosing(@NonNull Network network, int maxMsToLive) {}

        public void onLost(@NonNull Network network) {}

        public void onUnavailable() {}

        public void onCapabilitiesChanged(@NonNull Network network, @NonNull NetworkCapabilities networkCapabilities) {}

        public void onLinkPropertiesChanged(@NonNull Network network, @NonNull LinkProperties linkProperties) {}

        public void onNetworkSuspended(@NonNull Network network) {}

        public void onNetworkResumed(@NonNull Network network) {}

        public void onBlockedStatusChanged(@NonNull Network network, boolean blocked) {}
    }

    /** One registered callback: its request, delivery handler and whether it has seen the current network. */
    private static final class Registration {
        final NetworkRequest request;
        final NetworkCallback callback;
        final PendingIntent operation;
        final Handler handler;
        final boolean isRequest;
        int notifiedNetId = NETID_UNSET;
        NetworkCapabilities lastCapabilities;
        LinkProperties lastLinkProperties;

        Registration(NetworkRequest request, NetworkCallback callback, PendingIntent operation, Handler handler, boolean isRequest) {
            this.request = request;
            this.callback = callback;
            this.operation = operation;
            this.handler = handler;
            this.isRequest = isRequest;
        }
    }

    /** The default network as last observed. */
    private static final class State {
        boolean available;
        boolean metered;
        int connectivity; // GNetworkConnectivity: 1 local, 2 limited, 3 portal, 4 full
        String iface;
        int transport = -1;
        int netId = NETID_UNSET;
        NetworkCapabilities capabilities;
        LinkProperties linkProperties;
    }

    private static final Object sLock = new Object();
    private static final List<Registration> sRegistrations = new ArrayList<>();
    private static final List<OnNetworkActiveListener> sActiveListeners = new ArrayList<>();
    private static final List<ConnectivityManager> sInstances = new ArrayList<>();
    private static State sState;
    private static int sNextNetId = FIRST_NETID;
    private static Handler sConnectivityHandler;
    private static Network sBoundNetwork;

    private final Context mContext;

    private static native boolean nativeGetNetworkAvailable();
    private static native boolean nativeIsMetered();
    private static native int nativeGetConnectivity();
    private static native void nativeStartMonitor();

    public ConnectivityManager() {
        this(null);
    }

    public ConnectivityManager(Context context) {
        mContext = context;
        synchronized (sLock) {
            if (sState == null) {
                sState = observe(null);
                nativeStartMonitor();
            }
            if (context != null)
                sInstances.add(this);
        }
    }

    /** AOSP (hidden): the manager of a context. */
    public static ConnectivityManager from(Context context) {
        return (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    // ---- static helpers (AOSP) ----

    public static boolean isNetworkTypeValid(int networkType) {
        return networkType >= 0 && networkType <= MAX_NETWORK_TYPE;
    }

    public static String getNetworkTypeName(int type) {
        switch (type) {
            case TYPE_NONE: return "NONE";
            case TYPE_MOBILE: return "MOBILE";
            case TYPE_WIFI: return "WIFI";
            case TYPE_MOBILE_MMS: return "MOBILE_MMS";
            case TYPE_MOBILE_SUPL: return "MOBILE_SUPL";
            case TYPE_MOBILE_DUN: return "MOBILE_DUN";
            case TYPE_MOBILE_HIPRI: return "MOBILE_HIPRI";
            case TYPE_WIMAX: return "WIMAX";
            case TYPE_BLUETOOTH: return "BLUETOOTH";
            case TYPE_DUMMY: return "DUMMY";
            case TYPE_ETHERNET: return "ETHERNET";
            case TYPE_MOBILE_FOTA: return "MOBILE_FOTA";
            case TYPE_MOBILE_IMS: return "MOBILE_IMS";
            case TYPE_MOBILE_CBS: return "MOBILE_CBS";
            case TYPE_WIFI_P2P: return "WIFI_P2P";
            case TYPE_MOBILE_IA: return "MOBILE_IA";
            case TYPE_MOBILE_EMERGENCY: return "MOBILE_EMERGENCY";
            case TYPE_PROXY: return "PROXY";
            case TYPE_VPN: return "VPN";
            default: return Integer.toString(type);
        }
    }

    /** AOSP (hidden): whether a legacy type is one of the mobile data types. */
    public static boolean isNetworkTypeMobile(int networkType) {
        switch (networkType) {
            case TYPE_MOBILE:
            case TYPE_MOBILE_MMS:
            case TYPE_MOBILE_SUPL:
            case TYPE_MOBILE_DUN:
            case TYPE_MOBILE_HIPRI:
            case TYPE_MOBILE_FOTA:
            case TYPE_MOBILE_IMS:
            case TYPE_MOBILE_CBS:
            case TYPE_MOBILE_IA:
            case TYPE_MOBILE_EMERGENCY:
                return true;
            default:
                return false;
        }
    }

    public static boolean isNetworkTypeWifi(int networkType) {
        switch (networkType) {
            case TYPE_WIFI:
            case TYPE_WIFI_P2P:
                return true;
            default:
                return false;
        }
    }

    // ---- observing the host network ----

    /** The interface of the first default (destination 0.0.0.0) route, or null. */
    private static String defaultRouteInterface() {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/net/route"))) {
            String line = r.readLine(); // header
            while ((line = r.readLine()) != null) {
                String[] f = line.trim().split("\\s+");
                if (f.length >= 4 && "00000000".equals(f[1]) && (Integer.parseInt(f[3], 16) & 1) != 0) // RTF_UP
                    return f[0];
            }
        } catch (IOException | NumberFormatException e) {
            // no routing table: treated as no network, like a device without any connectivity
        }
        // an IPv6-only default route
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/net/ipv6_route"))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] f = line.trim().split("\\s+");
                if (f.length >= 10 && "00000000000000000000000000000000".equals(f[0]) && "00".equals(f[1]))
                    return f[9];
            }
        } catch (IOException e) {
        }
        return null;
    }

    /** Also used by TrafficStats to pick the mobile (cellular) interfaces. */
    static int transportOf(String iface) {
        if (iface == null)
            return -1;
        if (new File("/sys/class/net/" + iface + "/wireless").exists() || new File("/sys/class/net/" + iface + "/phy80211").exists())
            return NetworkCapabilities.TRANSPORT_WIFI;
        if (iface.startsWith("tun") || iface.startsWith("wg") || iface.startsWith("tap") || iface.startsWith("vpn"))
            return NetworkCapabilities.TRANSPORT_VPN;
        if (iface.startsWith("wwan") || iface.startsWith("rmnet") || iface.startsWith("ppp") || iface.startsWith("qmi"))
            return NetworkCapabilities.TRANSPORT_CELLULAR;
        if (iface.startsWith("bnep") || iface.startsWith("bt"))
            return NetworkCapabilities.TRANSPORT_BLUETOOTH;
        if (iface.startsWith("usb") || iface.startsWith("rndis"))
            return NetworkCapabilities.TRANSPORT_USB;
        return NetworkCapabilities.TRANSPORT_ETHERNET;
    }

    private static int legacyTypeOf(int transport) {
        switch (transport) {
            case NetworkCapabilities.TRANSPORT_CELLULAR: return TYPE_MOBILE;
            case NetworkCapabilities.TRANSPORT_WIFI: return TYPE_WIFI;
            case NetworkCapabilities.TRANSPORT_BLUETOOTH: return TYPE_BLUETOOTH;
            case NetworkCapabilities.TRANSPORT_VPN: return TYPE_VPN;
            case NetworkCapabilities.TRANSPORT_ETHERNET:
            case NetworkCapabilities.TRANSPORT_USB:
            default: return TYPE_ETHERNET;
        }
    }

    private static int readIntFile(String path, int fallback) {
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            String line = r.readLine();
            return line == null ? fallback : Integer.parseInt(line.trim());
        } catch (IOException | NumberFormatException e) {
            return fallback;
        }
    }

    /** The Wi-Fi signal level of an interface in dBm from /proc/net/wireless, or SIGNAL_STRENGTH_UNSPECIFIED. */
    private static int wirelessLevel(String iface) {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/net/wireless"))) {
            String line;
            while ((line = r.readLine()) != null) {
                String t = line.trim();
                if (t.startsWith(iface + ":")) {
                    String[] f = t.substring(iface.length() + 1).trim().split("\\s+");
                    if (f.length >= 3)
                        return (int) Float.parseFloat(f[2].replace(".", ""));
                }
            }
        } catch (IOException | NumberFormatException e) {
        }
        return NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED;
    }

    private static NetworkCapabilities buildCapabilities(State s) {
        NetworkCapabilities nc = new NetworkCapabilities();
        nc.addTransportType(s.transport);
        nc.addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        nc.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED);
        nc.addCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED);
        nc.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING);
        nc.addCapability(NetworkCapabilities.NET_CAPABILITY_FOREGROUND);
        nc.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_CONGESTED);
        nc.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED);
        nc.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED);
        if (s.transport != NetworkCapabilities.TRANSPORT_VPN)
            nc.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN);
        if (!s.metered)
            nc.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
        if (s.connectivity == 4 /* G_NETWORK_CONNECTIVITY_FULL */ || s.connectivity == 0)
            nc.addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        if (s.connectivity == 3 /* G_NETWORK_CONNECTIVITY_PORTAL */)
            nc.addCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL);
        int mbps = readIntFile("/sys/class/net/" + s.iface + "/speed", -1);
        if (mbps > 0) {
            nc.setLinkDownstreamBandwidthKbps(mbps * 1000);
            nc.setLinkUpstreamBandwidthKbps(mbps * 1000);
        }
        if (s.transport == NetworkCapabilities.TRANSPORT_WIFI)
            nc.setSignalStrength(wirelessLevel(s.iface));
        nc.setOwnerUid(Process.INVALID_UID);
        return nc;
    }

    /** Reads the current host state; keeps the netId of the previous state when the interface is unchanged. */
    private static State observe(State previous) {
        State s = new State();
        s.available = nativeGetNetworkAvailable();
        s.metered = nativeIsMetered();
        s.connectivity = nativeGetConnectivity();
        s.iface = s.available ? defaultRouteInterface() : null;
        if (s.iface == null)
            s.available = false;
        if (s.available) {
            s.transport = transportOf(s.iface);
            if (previous != null && previous.available && s.iface.equals(previous.iface)) {
                s.netId = previous.netId;
            } else {
                s.netId = sNextNetId++;
            }
            s.capabilities = buildCapabilities(s);
            s.linkProperties = LinkProperties.tlFromSystem(s.iface);
        }
        return s;
    }

    private static Network networkOf(State s) {
        return s.available ? new Network(s.netId) : null;
    }

    private static NetworkInfo infoOf(State s, int type) {
        int currentType = s.available ? legacyTypeOf(s.transport) : TYPE_NONE;
        if (type == TYPE_NONE)
            type = currentType;
        NetworkInfo info = new NetworkInfo(type, 0, getNetworkTypeName(type), "");
        if (s.available && type == currentType) {
            info.setIsAvailable(true);
            info.setDetailedState(NetworkInfo.DetailedState.CONNECTED, null, s.iface);
        } else {
            info.setIsAvailable(false);
            info.setDetailedState(NetworkInfo.DetailedState.DISCONNECTED, null, null);
        }
        return info;
    }

    /** Called from the GNetworkMonitor "network-changed" signal (main thread). */
    static void tlOnNetworkChanged(boolean available) {
        List<Runnable> deliveries = new ArrayList<>();
        State old, now;
        synchronized (sLock) {
            old = sState;
            now = observe(old);
            sState = now;
            Network oldNetwork = networkOf(old), newNetwork = networkOf(now);
            boolean sameNetwork = old.available && now.available && old.netId == now.netId;
            for (final Registration reg : sRegistrations) {
                if (old.available && reg.notifiedNetId == old.netId && !sameNetwork) {
                    final Network lost = oldNetwork;
                    reg.notifiedNetId = NETID_UNSET;
                    deliveries.add(deliver(reg, () -> {
                        if (reg.callback != null)
                            reg.callback.onLost(lost);
                    }));
                }
                if (now.available && now.capabilities != null && reg.request.canBeSatisfiedBy(now.capabilities)) {
                    if (reg.notifiedNetId != now.netId) {
                        deliveries.add(availableDelivery(reg, now));
                    } else {
                        final Network n = newNetwork;
                        if (!now.capabilities.equals(reg.lastCapabilities)) {
                            final NetworkCapabilities nc = new NetworkCapabilities(now.capabilities);
                            reg.lastCapabilities = nc;
                            deliveries.add(deliver(reg, () -> {
                                if (reg.callback != null)
                                    reg.callback.onCapabilitiesChanged(n, nc);
                            }));
                        }
                        if (!now.linkProperties.equals(reg.lastLinkProperties)) {
                            final LinkProperties lp = new LinkProperties(now.linkProperties);
                            reg.lastLinkProperties = lp;
                            deliveries.add(deliver(reg, () -> {
                                if (reg.callback != null)
                                    reg.callback.onLinkPropertiesChanged(n, lp);
                            }));
                        }
                    }
                } else if (reg.notifiedNetId != NETID_UNSET && reg.notifiedNetId == now.netId) {
                    // the network no longer satisfies this request
                    final Network lost = newNetwork;
                    reg.notifiedNetId = NETID_UNSET;
                    deliveries.add(deliver(reg, () -> {
                        if (reg.callback != null)
                            reg.callback.onLost(lost);
                    }));
                }
            }
            if (now.available && !old.available) {
                for (OnNetworkActiveListener l : sActiveListeners)
                    deliveries.add(deliver(null, l::onNetworkActive));
            }
        }
        for (Runnable r : deliveries)
            r.run();
        if (old.available != now.available || old.netId != now.netId)
            broadcastConnectivity(now);
    }

    private static void broadcastConnectivity(State s) {
        List<ConnectivityManager> instances;
        synchronized (sLock) {
            instances = new ArrayList<>(sInstances);
        }
        NetworkInfo info = infoOf(s, TYPE_NONE);
        Intent intent = new Intent(CONNECTIVITY_ACTION);
        intent.putExtra(EXTRA_NETWORK_INFO, info);
        intent.putExtra(EXTRA_NETWORK_TYPE, info.getType());
        if (!s.available)
            intent.putExtra(EXTRA_NO_CONNECTIVITY, true);
        for (ConnectivityManager cm : instances) {
            try {
                cm.mContext.sendBroadcast(intent);
            } catch (RuntimeException e) {
                Log.w(TAG, "connectivity broadcast failed", e);
            }
            break; // one broadcast per process
        }
    }

    private static Handler connectivityHandler() {
        synchronized (sLock) {
            if (sConnectivityHandler == null) {
                HandlerThread thread = new HandlerThread("ConnectivityThread");
                thread.start();
                sConnectivityHandler = new Handler(thread.getLooper());
            }
            return sConnectivityHandler;
        }
    }

    /** Wraps a callback invocation so it runs on the registration's handler. */
    private static Runnable deliver(Registration reg, Runnable r) {
        Handler h = reg != null && reg.handler != null ? reg.handler : connectivityHandler();
        return () -> h.post(r);
    }

    private static Runnable availableDelivery(final Registration reg, State s) {
        final Network network = new Network(s.netId);
        final NetworkCapabilities nc = new NetworkCapabilities(s.capabilities);
        final LinkProperties lp = new LinkProperties(s.linkProperties);
        reg.notifiedNetId = s.netId;
        reg.lastCapabilities = nc;
        reg.lastLinkProperties = lp;
        return deliver(reg, () -> {
            if (reg.callback != null) {
                reg.callback.onAvailable(network, nc, lp, false);
            } else if (reg.operation != null) {
                Intent fill = new Intent();
                fill.putExtra(EXTRA_NETWORK, network);
                fill.putExtra(EXTRA_NETWORK_REQUEST, reg.request);
                try {
                    reg.operation.send(null, 0, fill);
                } catch (RuntimeException e) {
                    Log.w(TAG, "network PendingIntent failed", e);
                }
            }
        });
    }

    private static void register(NetworkRequest request, NetworkCallback callback, PendingIntent operation, Handler handler, boolean isRequest, int timeoutMs) {
        if (callback == null && operation == null)
            throw new IllegalArgumentException("null NetworkCallback");
        if (request == null)
            throw new IllegalArgumentException("null NetworkRequest");
        Runnable delivery = null;
        final Registration reg = new Registration(request, callback, operation, handler, isRequest);
        synchronized (sLock) {
            if (callback != null) {
                for (Registration r : sRegistrations) {
                    if (r.callback == callback)
                        throw new IllegalArgumentException("NetworkCallback was already registered");
                }
            }
            sRegistrations.add(reg);
            if (sState.available && sState.capabilities != null && request.canBeSatisfiedBy(sState.capabilities)) {
                delivery = availableDelivery(reg, sState);
            } else if (isRequest && timeoutMs > 0 && callback != null) {
                final Handler h = handler != null ? handler : connectivityHandler();
                h.postDelayed(() -> {
                    boolean still;
                    synchronized (sLock) {
                        still = sRegistrations.contains(reg) && reg.notifiedNetId == NETID_UNSET;
                    }
                    if (still)
                        callback.onUnavailable();
                }, timeoutMs);
            }
        }
        if (delivery != null)
            delivery.run();
    }

    // ---- queries ----

    @Nullable
    public Network getActiveNetwork() {
        synchronized (sLock) {
            return networkOf(sState);
        }
    }

    @Deprecated
    @Nullable
    public NetworkInfo getActiveNetworkInfo() {
        synchronized (sLock) {
            return sState.available ? infoOf(sState, TYPE_NONE) : null;
        }
    }

    @Deprecated
    @Nullable
    public NetworkInfo getNetworkInfo(int networkType) {
        if (!isNetworkTypeValid(networkType))
            return null;
        synchronized (sLock) {
            return infoOf(sState, networkType);
        }
    }

    @Deprecated
    @Nullable
    public NetworkInfo getNetworkInfo(@Nullable Network network) {
        synchronized (sLock) {
            if (network == null || !sState.available || network.netId != sState.netId)
                return null;
            return infoOf(sState, TYPE_NONE);
        }
    }

    @Deprecated
    @NonNull
    public NetworkInfo[] getAllNetworkInfo() {
        synchronized (sLock) {
            return sState.available ? new NetworkInfo[] {infoOf(sState, TYPE_NONE)} : new NetworkInfo[0];
        }
    }

    @NonNull
    public Network[] getAllNetworks() {
        synchronized (sLock) {
            return sState.available ? new Network[] {new Network(sState.netId)} : new Network[0];
        }
    }

    @Nullable
    public NetworkCapabilities getNetworkCapabilities(@Nullable Network network) {
        synchronized (sLock) {
            if (network == null || !sState.available || network.netId != sState.netId)
                return null;
            return new NetworkCapabilities(sState.capabilities);
        }
    }

    @Nullable
    public LinkProperties getLinkProperties(@Nullable Network network) {
        synchronized (sLock) {
            if (network == null || !sState.available || network.netId != sState.netId)
                return null;
            return new LinkProperties(sState.linkProperties);
        }
    }

    /** AOSP (hidden, deprecated): the link properties of a legacy type. */
    public LinkProperties getLinkProperties(int networkType) {
        synchronized (sLock) {
            if (!sState.available || legacyTypeOf(sState.transport) != networkType)
                return null;
            return new LinkProperties(sState.linkProperties);
        }
    }

    public boolean isActiveNetworkMetered() {
        synchronized (sLock) {
            return sState.available && sState.metered;
        }
    }

    public boolean isDefaultNetworkActive() {
        synchronized (sLock) {
            return sState.available;
        }
    }

    public void addDefaultNetworkActiveListener(final OnNetworkActiveListener l) {
        synchronized (sLock) {
            sActiveListeners.add(l);
        }
    }

    public void removeDefaultNetworkActiveListener(@NonNull OnNetworkActiveListener l) {
        synchronized (sLock) {
            sActiveListeners.remove(l);
        }
    }

    public int getRestrictBackgroundStatus() {
        return RESTRICT_BACKGROUND_STATUS_DISABLED;
    }

    public int getMultipathPreference(@Nullable Network network) {
        return isActiveNetworkMetered() ? 0 : MULTIPATH_PREFERENCE_UNMETERED;
    }

    @Deprecated
    public boolean getBackgroundDataSetting() {
        return true;
    }

    @Deprecated
    public void setNetworkPreference(int preference) {
        // AOSP: ignored since Lollipop
    }

    @Deprecated
    public int getNetworkPreference() {
        return TYPE_NONE;
    }

    @Deprecated
    public boolean requestRouteToHost(int networkType, int hostAddress) {
        // AOSP: no longer supported; every host is reachable through the default network
        return false;
    }

    @Deprecated
    public int startUsingNetworkFeature(int networkType, String feature) {
        return -1; // AOSP: PhoneConstants.APN_REQUEST_FAILED, features are no longer supported
    }

    @Deprecated
    public int stopUsingNetworkFeature(int networkType, String feature) {
        return -1;
    }

    /** Proxy from the http_proxy environment, the way a Linux host configures one; null when unset. */
    @Nullable
    public ProxyInfo getDefaultProxy() {
        String env = System.getenv("http_proxy");
        if (env == null || env.isEmpty())
            env = System.getenv("HTTP_PROXY");
        if (env == null || env.isEmpty())
            return null;
        try {
            java.net.URI uri = new java.net.URI(env.contains("://") ? env : "http://" + env);
            if (uri.getHost() == null)
                return null;
            String noProxy = System.getenv("no_proxy");
            List<String> excl = new ArrayList<>();
            if (noProxy != null) {
                for (String e : noProxy.split(",")) {
                    if (!e.trim().isEmpty())
                        excl.add(e.trim());
                }
            }
            return ProxyInfo.buildDirectProxy(uri.getHost(), uri.getPort() > 0 ? uri.getPort() : 8080, excl);
        } catch (java.net.URISyntaxException e) {
            return null;
        }
    }

    public int getConnectionOwnerUid(int protocol, @NonNull InetSocketAddress local, @NonNull InetSocketAddress remote) {
        return Process.INVALID_UID; // AOSP: only the system and VPN apps may ask; others get INVALID_UID
    }

    public boolean bindProcessToNetwork(@Nullable Network network) {
        synchronized (sLock) {
            if (network != null && (!sState.available || network.netId != sState.netId))
                return false;
            sBoundNetwork = network;
            return true;
        }
    }

    @Nullable
    public Network getBoundNetworkForProcess() {
        synchronized (sLock) {
            return sBoundNetwork;
        }
    }

    @Deprecated
    public static boolean setProcessDefaultNetwork(@Nullable Network network) {
        synchronized (sLock) {
            if (network != null && (sState == null || !sState.available || network.netId != sState.netId))
                return false;
            sBoundNetwork = network;
            return true;
        }
    }

    @Deprecated
    @Nullable
    public static Network getProcessDefaultNetwork() {
        synchronized (sLock) {
            return sBoundNetwork;
        }
    }

    public void reportNetworkConnectivity(@Nullable Network network, boolean hasConnectivity) {
        // AOSP re-validates the network on a report; the host validation is GNetworkMonitor's
    }

    @Deprecated
    public void reportBadNetwork(@Nullable Network network) {
        reportNetworkConnectivity(network, false);
    }

    // ---- callbacks ----

    public void registerNetworkCallback(@NonNull NetworkRequest request, @NonNull NetworkCallback networkCallback) {
        register(request, networkCallback, null, null, false, 0);
    }

    public void registerNetworkCallback(@NonNull NetworkRequest request, @NonNull NetworkCallback networkCallback, @NonNull Handler handler) {
        register(request, networkCallback, null, handler, false, 0);
    }

    public void registerNetworkCallback(@NonNull NetworkRequest request, @NonNull PendingIntent operation) {
        register(request, null, operation, null, false, 0);
    }

    public void registerDefaultNetworkCallback(@NonNull NetworkCallback networkCallback) {
        registerDefaultNetworkCallback(networkCallback, null);
    }

    public void registerDefaultNetworkCallback(@NonNull NetworkCallback networkCallback, @Nullable Handler handler) {
        register(new NetworkRequest.Builder().build(), networkCallback, null, handler, false, 0);
    }

    /** AOSP (API 31): the best network for the request; there is one network, so it is the default one. */
    public void registerBestMatchingNetworkCallback(@NonNull NetworkRequest request, @NonNull NetworkCallback networkCallback, @NonNull Handler handler) {
        register(request, networkCallback, null, handler, false, 0);
    }

    public void requestNetwork(@NonNull NetworkRequest request, @NonNull NetworkCallback networkCallback) {
        register(request, networkCallback, null, null, true, 0);
    }

    public void requestNetwork(@NonNull NetworkRequest request, @NonNull NetworkCallback networkCallback, @NonNull Handler handler) {
        register(request, networkCallback, null, handler, true, 0);
    }

    public void requestNetwork(@NonNull NetworkRequest request, @NonNull NetworkCallback networkCallback, int timeoutMs) {
        register(request, networkCallback, null, null, true, timeoutMs);
    }

    public void requestNetwork(@NonNull NetworkRequest request, @NonNull NetworkCallback networkCallback, @NonNull Handler handler, int timeoutMs) {
        register(request, networkCallback, null, handler, true, timeoutMs);
    }

    public void requestNetwork(@NonNull NetworkRequest request, @NonNull PendingIntent operation) {
        register(request, null, operation, null, true, 0);
    }

    public void releaseNetworkRequest(@NonNull PendingIntent operation) {
        unregisterNetworkCallback(operation);
    }

    public void unregisterNetworkCallback(@NonNull NetworkCallback networkCallback) {
        if (networkCallback == null)
            throw new IllegalArgumentException("null NetworkCallback");
        synchronized (sLock) {
            for (int i = sRegistrations.size() - 1; i >= 0; i--) {
                if (sRegistrations.get(i).callback == networkCallback)
                    sRegistrations.remove(i);
            }
        }
    }

    public void unregisterNetworkCallback(@NonNull PendingIntent operation) {
        if (operation == null)
            throw new IllegalArgumentException("null PendingIntent");
        synchronized (sLock) {
            for (int i = sRegistrations.size() - 1; i >= 0; i--) {
                if (sRegistrations.get(i).operation == operation)
                    sRegistrations.remove(i);
            }
        }
    }
}
