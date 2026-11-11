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
import android.text.TextUtils;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * teslalinux: AOSP ProxyInfo (a direct HTTP proxy or a PAC URL, with an exclusion list).
 */
public class ProxyInfo implements Parcelable {
    private final String mHost;
    private final int mPort;
    private final String mExclusionList;
    private final String[] mParsedExclusionList;
    private final Uri mPacFileUrl;

    /** AOSP (hidden): the port the local PAC proxy listens on. */
    public static final String LOCAL_EXCL_LIST = "";
    public static final int LOCAL_PORT = -1;
    public static final String LOCAL_HOST = "localhost";

    public static ProxyInfo buildDirectProxy(String host, int port) {
        return new ProxyInfo(host, port, null);
    }

    public static ProxyInfo buildDirectProxy(String host, int port, List<String> exclList) {
        String[] array = exclList.toArray(new String[exclList.size()]);
        return new ProxyInfo(host, port, TextUtils.join(",", array), array);
    }

    public static ProxyInfo buildPacProxy(Uri pacUri) {
        return new ProxyInfo(pacUri);
    }

    @NonNull
    public static ProxyInfo buildPacProxy(@NonNull Uri pacUrl, int port) {
        return new ProxyInfo(pacUrl, port);
    }

    public ProxyInfo(String host, int port, String exclList) {
        mHost = host;
        mPort = port;
        mExclusionList = exclList;
        mParsedExclusionList = parseExclusionList(mExclusionList);
        mPacFileUrl = Uri.EMPTY;
    }

    public ProxyInfo(@NonNull Uri pacFileUrl) {
        mHost = LOCAL_HOST;
        mPort = LOCAL_PORT;
        mExclusionList = LOCAL_EXCL_LIST;
        mParsedExclusionList = parseExclusionList(mExclusionList);
        if (pacFileUrl == null) {
            throw new NullPointerException();
        }
        mPacFileUrl = pacFileUrl;
    }

    public ProxyInfo(@NonNull Uri pacFileUrl, int localProxyPort) {
        mHost = LOCAL_HOST;
        mPort = localProxyPort;
        mExclusionList = LOCAL_EXCL_LIST;
        mParsedExclusionList = parseExclusionList(mExclusionList);
        if (pacFileUrl == null) {
            throw new NullPointerException();
        }
        mPacFileUrl = pacFileUrl;
    }

    private ProxyInfo(String host, int port, String exclList, String[] parsedExclList) {
        mHost = host;
        mPort = port;
        mExclusionList = exclList;
        mParsedExclusionList = parsedExclList;
        mPacFileUrl = Uri.EMPTY;
    }

    public ProxyInfo(@Nullable ProxyInfo source) {
        if (source != null) {
            mHost = source.getHost();
            mPort = source.getPort();
            mPacFileUrl = source.mPacFileUrl;
            mExclusionList = source.getExclusionListAsString();
            mParsedExclusionList = source.mParsedExclusionList;
        } else {
            mHost = null;
            mPort = 0;
            mExclusionList = null;
            mParsedExclusionList = null;
            mPacFileUrl = Uri.EMPTY;
        }
    }

    private static String[] parseExclusionList(String exclusionList) {
        if (exclusionList == null) {
            return new String[0];
        } else {
            return exclusionList.toLowerCase(Locale.ROOT).split(",");
        }
    }

    public InetSocketAddress getSocketAddress() {
        InetSocketAddress inetSocketAddress = null;
        try {
            inetSocketAddress = new InetSocketAddress(mHost, mPort);
        } catch (IllegalArgumentException e) {
        }
        return inetSocketAddress;
    }

    @NonNull
    public Uri getPacFileUrl() {
        return mPacFileUrl;
    }

    public String getHost() {
        return mHost;
    }

    public int getPort() {
        return mPort;
    }

    public String[] getExclusionList() {
        return mParsedExclusionList;
    }

    /** AOSP (hidden): the exclusion list as the comma-separated string it was set from. */
    public String getExclusionListAsString() {
        return mExclusionList;
    }

    public boolean isValid() {
        if (!Uri.EMPTY.equals(mPacFileUrl)) return true;
        if (TextUtils.isEmpty(mHost)) return false;
        if (mPort < 0 || mPort > 0xFFFF) return false;
        return true;
    }

    /** AOSP (hidden): the java.net.Proxy for this configuration. */
    public java.net.Proxy makeProxy() {
        java.net.Proxy proxy = java.net.Proxy.NO_PROXY;
        if (mHost != null) {
            try {
                InetSocketAddress inetSocketAddress = new InetSocketAddress(mHost, mPort);
                proxy = new java.net.Proxy(java.net.Proxy.Type.HTTP, inetSocketAddress);
            } catch (IllegalArgumentException e) {
            }
        }
        return proxy;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        if (!Uri.EMPTY.equals(mPacFileUrl)) {
            sb.append("PAC Script: ");
            sb.append(mPacFileUrl);
        }
        if (mHost != null) {
            sb.append("[");
            sb.append(mHost);
            if (mPort != -1) {
                sb.append(":");
                sb.append(mPort);
            }
            sb.append("]");
        } else {
            sb.append("[ProxyProperties.mHost == null]");
        }
        if (mExclusionList != null) {
            sb.append(" xl=").append(mExclusionList);
        }
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof ProxyInfo)) return false;
        ProxyInfo p = (ProxyInfo) o;
        if (!Uri.EMPTY.equals(mPacFileUrl)) {
            return mPacFileUrl.equals(p.getPacFileUrl()) && mPort == p.mPort;
        }
        if (!Uri.EMPTY.equals(p.mPacFileUrl)) {
            return false;
        }
        if (mExclusionList != null && !mExclusionList.equals(p.getExclusionListAsString())) {
            return false;
        }
        if (mHost != null && p.getHost() != null && mHost.equals(p.getHost()) == false) {
            return false;
        }
        if (mHost != null && p.mHost == null) return false;
        if (mHost == null && p.mHost != null) return false;
        if (mPort != p.mPort) return false;
        return true;
    }

    @Override
    public int hashCode() {
        return ((null == mHost) ? 0 : mHost.hashCode())
                + ((null == mExclusionList) ? 0 : mExclusionList.hashCode())
                + mPort;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        if (!Uri.EMPTY.equals(mPacFileUrl)) {
            dest.writeByte((byte) 1);
            mPacFileUrl.writeToParcel(dest, 0);
            dest.writeInt(mPort);
            return;
        } else {
            dest.writeByte((byte) 0);
        }
        if (mHost != null) {
            dest.writeByte((byte) 1);
            dest.writeString(mHost);
            dest.writeInt(mPort);
        } else {
            dest.writeByte((byte) 0);
        }
        dest.writeString(mExclusionList);
        dest.writeStringArray(mParsedExclusionList);
    }

    public static final Creator<ProxyInfo> CREATOR = new Creator<ProxyInfo>() {
        public ProxyInfo createFromParcel(Parcel in) {
            String host = null;
            int port = 0;
            if (in.readByte() != 0) {
                Uri url = Uri.CREATOR.createFromParcel(in);
                int localPort = in.readInt();
                return new ProxyInfo(url, localPort);
            }
            if (in.readByte() != 0) {
                host = in.readString();
                port = in.readInt();
            }
            String exclList = in.readString();
            String[] parsedExclList = in.readStringArray();
            return new ProxyInfo(host, port, exclList, parsedExclList);
        }

        public ProxyInfo[] newArray(int size) {
            return new ProxyInfo[size];
        }
    };
}
