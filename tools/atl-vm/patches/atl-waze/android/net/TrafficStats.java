/*
 * Copyright (C) 2007 The Android Open Source Project
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

import dalvik.system.SocketTagger;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileReader;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.HashMap;
import java.util.Map;

/**
 * Class that provides network traffic statistics (AOSP android11-release API).
 *
 * teslalinux: AOSP reads these counters from the system server (NetworkStatsService, backed by
 * eBPF maps). Here they come straight from the Linux kernel:
 * - per-interface and total counters: /proc/net/dev and /sys/class/net/IFACE/statistics, which is
 *   exactly what AOSP's interface stats are (the loopback interface is excluded from the totals,
 *   as AOSP does).
 * - "mobile" counters: the interfaces ConnectivityManager classifies as TRANSPORT_CELLULAR (the
 *   Pi's LTE modem: wwan*, rmnet*, ppp*, qmi*).
 * - per-UID counters: the Linux kernel keeps no per-UID traffic accounting without Android's
 *   eBPF/xt_qtaguid programs, so these report UNSUPPORTED, exactly as AOSP does on a kernel without
 *   per-UID accounting.
 * Thread stats tags and UIDs are real per-thread state (AOSP's NetworkManagementSocketTagger);
 * socket tagging goes through libcore's SocketTagger hook like on AOSP. This platform installs no
 * kernel tagger (there is no qtaguid), so libcore's default tagger applies, which is what AOSP does
 * on a kernel without socket tagging support.
 */
public class TrafficStats {
    /** The return value to indicate that the device does not support the statistic. */
    public final static int UNSUPPORTED = -1;

    /** @hide @deprecated use {@code DataUnit} instead to clarify SI-vs-IEC */
    @Deprecated
    public static final long KB_IN_BYTES = 1024;
    /** @hide @deprecated */
    @Deprecated
    public static final long MB_IN_BYTES = KB_IN_BYTES * 1024;
    /** @hide @deprecated */
    @Deprecated
    public static final long GB_IN_BYTES = MB_IN_BYTES * 1024;
    /** @hide @deprecated */
    @Deprecated
    public static final long TB_IN_BYTES = GB_IN_BYTES * 1024;
    /** @hide @deprecated */
    @Deprecated
    public static final long PB_IN_BYTES = TB_IN_BYTES * 1024;

    /** @hide */
    public static final int UID_REMOVED = -4;
    /** @hide */
    public static final int UID_TETHERING = -5;

    /** @hide */
    public static final int TAG_NETWORK_STACK_RANGE_START = 0xFFFFFD00;
    /** @hide */
    public static final int TAG_NETWORK_STACK_RANGE_END = 0xFFFFFEFF;
    /** @hide */
    public static final int TAG_SYSTEM_IMPERSONATION_RANGE_START = 0xFFFFFF00;
    /** @hide */
    public static final int TAG_SYSTEM_IMPERSONATION_RANGE_END = 0xFFFFFF0F;
    /** @hide */
    public static final int TAG_NETWORK_STACK_IMPERSONATION_RANGE_START = 0xFFFFFF80;
    /** @hide */
    public static final int TAG_NETWORK_STACK_IMPERSONATION_RANGE_END = 0xFFFFFF8F;
    /** @hide */
    public static final int TAG_SYSTEM_DOWNLOAD = 0xFFFFFF01;
    /** @hide */
    public static final int TAG_SYSTEM_MEDIA = 0xFFFFFF02;
    /** @hide */
    public static final int TAG_SYSTEM_BACKUP = 0xFFFFFF03;
    /** @hide */
    public static final int TAG_SYSTEM_RESTORE = 0xFFFFFF04;
    /** @hide */
    public static final int TAG_SYSTEM_APP = 0xFFFFFF05;
    /** @hide */
    public static final int TAG_SYSTEM_PROBE = 0xFFFFFF42;

    private static final String LOOPBACK_IFACE = "lo";

    /** AOSP NetworkManagementSocketTagger.SocketTags: the per-thread tag and UID. */
    private static final class SocketTags {
        int statsTag = -1;
        int statsUid = -1;
    }

    private static final ThreadLocal<SocketTags> sThreadSocketTags = new ThreadLocal<SocketTags>() {
        @Override
        protected SocketTags initialValue() {
            return new SocketTags();
        }
    };

    /** Operation counts per tag (AOSP NetworkStatsService.incrementOperationCount, for our UID). */
    private static final Map<Integer, Long> sOperationCounts = new HashMap<>();

    public static void setThreadStatsTag(int tag) {
        sThreadSocketTags.get().statsTag = tag;
    }

    /** @hide */
    public static int getAndSetThreadStatsTag(int tag) {
        SocketTags t = sThreadSocketTags.get();
        int old = t.statsTag;
        t.statsTag = tag;
        return old;
    }

    /** @hide */
    public static void setThreadStatsTagBackup() {
        setThreadStatsTag(TAG_SYSTEM_BACKUP);
    }

    /** @hide */
    public static void setThreadStatsTagRestore() {
        setThreadStatsTag(TAG_SYSTEM_RESTORE);
    }

    /** @hide */
    public static void setThreadStatsTagApp() {
        setThreadStatsTag(TAG_SYSTEM_APP);
    }

    public static int getThreadStatsTag() {
        return sThreadSocketTags.get().statsTag;
    }

    public static void clearThreadStatsTag() {
        sThreadSocketTags.get().statsTag = -1;
    }

    public static void setThreadStatsUid(int uid) {
        sThreadSocketTags.get().statsUid = uid;
    }

    public static int getThreadStatsUid() {
        return sThreadSocketTags.get().statsUid;
    }

    @Deprecated
    public static void setThreadStatsUidSelf() {
        setThreadStatsUid(android.os.Process.myUid());
    }

    public static void clearThreadStatsUid() {
        sThreadSocketTags.get().statsUid = -1;
    }

    public static void tagSocket(Socket socket) throws SocketException {
        SocketTagger.get().tag(socket);
    }

    public static void untagSocket(Socket socket) throws SocketException {
        SocketTagger.get().untag(socket);
    }

    public static void tagDatagramSocket(DatagramSocket socket) throws SocketException {
        SocketTagger.get().tag(socket);
    }

    public static void untagDatagramSocket(DatagramSocket socket) throws SocketException {
        SocketTagger.get().untag(socket);
    }

    public static void tagFileDescriptor(FileDescriptor fd) throws IOException {
        SocketTagger.get().tag(fd);
    }

    public static void untagFileDescriptor(FileDescriptor fd) throws IOException {
        SocketTagger.get().untag(fd);
    }

    public static void incrementOperationCount(int operationCount) {
        final int tag = getThreadStatsTag();
        incrementOperationCount(tag, operationCount);
    }

    public static void incrementOperationCount(int tag, int operationCount) {
        synchronized (sOperationCounts) {
            Long old = sOperationCounts.get(tag);
            sOperationCounts.put(tag, (old == null ? 0L : old) + operationCount);
        }
    }

    // ---- interface counters ----

    /** Column indexes in /proc/net/dev after the "iface:" field. */
    private static final int RX_BYTES = 0, RX_PACKETS = 1, TX_BYTES = 8, TX_PACKETS = 9;

    private interface IfaceFilter {
        boolean accept(String iface);
    }

    /** Sums one /proc/net/dev column over the interfaces the filter accepts; UNSUPPORTED if unreadable. */
    private static long sumProcNetDev(int column, IfaceFilter filter) {
        long total = 0;
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/net/dev"))) {
            String line;
            while ((line = r.readLine()) != null) {
                int colon = line.indexOf(':');
                if (colon < 0)
                    continue; // the two header lines
                String iface = line.substring(0, colon).trim();
                if (!filter.accept(iface))
                    continue;
                String[] f = line.substring(colon + 1).trim().split("\\s+");
                if (f.length > column)
                    total += Long.parseLong(f[column]);
            }
        } catch (IOException | NumberFormatException e) {
            return UNSUPPORTED;
        }
        return total;
    }

    private static final IfaceFilter ALL_BUT_LOOPBACK = new IfaceFilter() {
        public boolean accept(String iface) {
            return !LOOPBACK_IFACE.equals(iface);
        }
    };

    private static final IfaceFilter MOBILE = new IfaceFilter() {
        public boolean accept(String iface) {
            return ConnectivityManager.transportOf(iface) == NetworkCapabilities.TRANSPORT_CELLULAR;
        }
    };

    private static long ifaceStat(String iface, String name) {
        if (iface == null || iface.indexOf('/') >= 0)
            return UNSUPPORTED;
        File f = new File("/sys/class/net/" + iface + "/statistics/" + name);
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String s = r.readLine();
            return s == null ? UNSUPPORTED : Long.parseLong(s.trim());
        } catch (IOException | NumberFormatException e) {
            return UNSUPPORTED; // AOSP: no stats for an unknown interface
        }
    }

    public static long getMobileTxPackets() {
        return sumProcNetDev(TX_PACKETS, MOBILE);
    }

    public static long getMobileRxPackets() {
        return sumProcNetDev(RX_PACKETS, MOBILE);
    }

    public static long getMobileTxBytes() {
        return sumProcNetDev(TX_BYTES, MOBILE);
    }

    public static long getMobileRxBytes() {
        return sumProcNetDev(RX_BYTES, MOBILE);
    }

    /** @hide AOSP: the TCP segment counters of mobile ifaces come from /proc/net/xt_qtaguid, absent here. */
    public static long getMobileTcpRxPackets() {
        return UNSUPPORTED;
    }

    /** @hide see getMobileTcpRxPackets */
    public static long getMobileTcpTxPackets() {
        return UNSUPPORTED;
    }

    public static long getTxPackets(String iface) {
        return ifaceStat(iface, "tx_packets");
    }

    public static long getRxPackets(String iface) {
        return ifaceStat(iface, "rx_packets");
    }

    public static long getTxBytes(String iface) {
        return ifaceStat(iface, "tx_bytes");
    }

    public static long getRxBytes(String iface) {
        return ifaceStat(iface, "rx_bytes");
    }

    /** @hide */
    public static long getLoopbackTxPackets() {
        return getTxPackets(LOOPBACK_IFACE);
    }

    /** @hide */
    public static long getLoopbackRxPackets() {
        return getRxPackets(LOOPBACK_IFACE);
    }

    /** @hide */
    public static long getLoopbackTxBytes() {
        return getTxBytes(LOOPBACK_IFACE);
    }

    /** @hide */
    public static long getLoopbackRxBytes() {
        return getRxBytes(LOOPBACK_IFACE);
    }

    public static long getTotalTxPackets() {
        return sumProcNetDev(TX_PACKETS, ALL_BUT_LOOPBACK);
    }

    public static long getTotalRxPackets() {
        return sumProcNetDev(RX_PACKETS, ALL_BUT_LOOPBACK);
    }

    public static long getTotalTxBytes() {
        return sumProcNetDev(TX_BYTES, ALL_BUT_LOOPBACK);
    }

    public static long getTotalRxBytes() {
        return sumProcNetDev(RX_BYTES, ALL_BUT_LOOPBACK);
    }

    // ---- per-UID counters: no per-UID accounting in this kernel (see the class comment) ----

    public static long getUidTxBytes(int uid) {
        return UNSUPPORTED;
    }

    public static long getUidRxBytes(int uid) {
        return UNSUPPORTED;
    }

    public static long getUidTxPackets(int uid) {
        return UNSUPPORTED;
    }

    public static long getUidRxPackets(int uid) {
        return UNSUPPORTED;
    }

    @Deprecated
    public static long getUidTcpTxBytes(int uid) {
        return UNSUPPORTED;
    }

    @Deprecated
    public static long getUidTcpRxBytes(int uid) {
        return UNSUPPORTED;
    }

    @Deprecated
    public static long getUidUdpTxBytes(int uid) {
        return UNSUPPORTED;
    }

    @Deprecated
    public static long getUidUdpRxBytes(int uid) {
        return UNSUPPORTED;
    }

    @Deprecated
    public static long getUidTcpTxSegments(int uid) {
        return UNSUPPORTED;
    }

    @Deprecated
    public static long getUidTcpRxSegments(int uid) {
        return UNSUPPORTED;
    }

    @Deprecated
    public static long getUidUdpTxPackets(int uid) {
        return UNSUPPORTED;
    }

    @Deprecated
    public static long getUidUdpRxPackets(int uid) {
        return UNSUPPORTED;
    }
}
