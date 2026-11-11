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

package android.media;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Handler;
import android.os.PersistableBundle;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * MediaDrm can be used to obtain keys for decrypting protected media streams (AOSP API).
 *
 * teslalinux: AOSP loads the DRM HAL plugin that claims the scheme UUID. This device has no DRM
 * HAL and no plugin (no Widevine, no ClearKey, no PlayReady), which AOSP reports exactly so:
 * {@link #isCryptoSchemeSupported} is false, {@link #getSupportedCryptoSchemes()} is empty and the
 * constructor throws {@link UnsupportedSchemeException}. Since no MediaDrm object can therefore
 * exist, the instance methods can only be reached on a released object, and they behave as
 * AOSP's do after {@link #close()}: IllegalStateException.
 */
public final class MediaDrm implements AutoCloseable {
    private static final String TAG = "MediaDrm";

    public static final int CERTIFICATE_TYPE_NONE = 0;
    public static final int CERTIFICATE_TYPE_X509 = 1;

    public static final int EVENT_PROVISION_REQUIRED = 1;
    public static final int EVENT_KEY_REQUIRED = 2;
    public static final int EVENT_KEY_EXPIRED = 3;
    public static final int EVENT_VENDOR_DEFINED = 4;
    public static final int EVENT_SESSION_RECLAIMED = 5;

    public static final int KEY_TYPE_STREAMING = 1;
    public static final int KEY_TYPE_OFFLINE = 2;
    public static final int KEY_TYPE_RELEASE = 3;

    public static final int OFFLINE_LICENSE_STATE_UNKNOWN = 0;
    public static final int OFFLINE_LICENSE_STATE_USABLE = 1;
    public static final int OFFLINE_LICENSE_STATE_RELEASED = 2;

    public static final int HDCP_LEVEL_UNKNOWN = 0;
    public static final int HDCP_NONE = 1;
    public static final int HDCP_V1 = 2;
    public static final int HDCP_V2 = 3;
    public static final int HDCP_V2_1 = 4;
    public static final int HDCP_V2_2 = 5;
    public static final int HDCP_V2_3 = 6;
    public static final int HDCP_NO_DIGITAL_OUTPUT = Integer.MAX_VALUE;

    public static final int SECURITY_LEVEL_UNKNOWN = 0;
    public static final int SECURITY_LEVEL_SW_SECURE_CRYPTO = 1;
    public static final int SECURITY_LEVEL_SW_SECURE_DECODE = 2;
    public static final int SECURITY_LEVEL_HW_SECURE_CRYPTO = 3;
    public static final int SECURITY_LEVEL_HW_SECURE_DECODE = 4;
    public static final int SECURITY_LEVEL_HW_SECURE_ALL = 5;
    /** @hide */
    public static final int SECURITY_LEVEL_MAX = 6;

    public static final String PROPERTY_VENDOR = "vendor";
    public static final String PROPERTY_VERSION = "version";
    public static final String PROPERTY_DESCRIPTION = "description";
    public static final String PROPERTY_ALGORITHMS = "algorithms";
    public static final String PROPERTY_DEVICE_UNIQUE_ID = "deviceUniqueId";

    /** No DRM plugin is installed on this device. */
    public static final boolean isCryptoSchemeSupported(@NonNull UUID uuid) {
        return isCryptoSchemeSupported(uuid, null, SECURITY_LEVEL_UNKNOWN);
    }

    public static final boolean isCryptoSchemeSupported(@NonNull UUID uuid, @NonNull String mimeType) {
        return isCryptoSchemeSupported(uuid, mimeType, SECURITY_LEVEL_UNKNOWN);
    }

    public static final boolean isCryptoSchemeSupported(@NonNull UUID uuid, @NonNull String mimeType,
            int securityLevel) {
        if (uuid == null) {
            throw new IllegalArgumentException("uuid must not be null");
        }
        return false;
    }

    /** The UUIDs of the installed plugins: none. */
    public static final @NonNull List<UUID> getSupportedCryptoSchemes() {
        return new ArrayList<>();
    }

    /** No secure decoding hardware: the highest level a plugin could reach is unknown. */
    public static int getMaxSecurityLevel() {
        return SECURITY_LEVEL_UNKNOWN;
    }

    public MediaDrm(@NonNull UUID uuid) throws UnsupportedSchemeException {
        if (uuid == null) {
            throw new IllegalArgumentException("uuid must not be null");
        }
        // AOSP JMediaDrm: no plugin found for the scheme
        throw new UnsupportedSchemeException("Failed to instantiate drm object.");
    }

    private static IllegalStateException released() {
        return new IllegalStateException("MediaDrm instance has been released");
    }

    // ---- listeners ----

    public interface OnEventListener {
        void onEvent(@NonNull MediaDrm md, @Nullable byte[] sessionId, int event, int extra,
                @Nullable byte[] data);
    }

    public interface OnExpirationUpdateListener {
        void onExpirationUpdate(@NonNull MediaDrm md, @NonNull byte[] sessionId, long expirationTime);
    }

    public interface OnKeyStatusChangeListener {
        void onKeyStatusChange(@NonNull MediaDrm md, @NonNull byte[] sessionId,
                @NonNull List<KeyStatus> keyInformation, boolean hasNewUsableKey);
    }

    public interface OnSessionLostStateListener {
        void onSessionLostState(@NonNull MediaDrm md, @NonNull byte[] sessionId);
    }

    public void setOnEventListener(@Nullable OnEventListener listener) {
        throw released();
    }

    public void setOnEventListener(@Nullable OnEventListener listener, @Nullable Handler handler) {
        throw released();
    }

    public void setOnEventListener(@NonNull Executor executor, @NonNull OnEventListener listener) {
        throw released();
    }

    public void clearOnEventListener() {
        throw released();
    }

    public void setOnExpirationUpdateListener(@Nullable OnExpirationUpdateListener listener, @Nullable Handler handler) {
        throw released();
    }

    public void setOnKeyStatusChangeListener(@Nullable OnKeyStatusChangeListener listener, @Nullable Handler handler) {
        throw released();
    }

    public void setOnSessionLostStateListener(@Nullable OnSessionLostStateListener listener, @Nullable Handler handler) {
        throw released();
    }

    // ---- sessions and keys ----

    public @NonNull byte[] openSession() throws NotProvisionedException, ResourceBusyException {
        throw released();
    }

    public @NonNull byte[] openSession(int level) throws NotProvisionedException, ResourceBusyException {
        throw released();
    }

    public void closeSession(@NonNull byte[] sessionId) {
        throw released();
    }

    public @NonNull KeyRequest getKeyRequest(@NonNull byte[] scope, @Nullable byte[] init,
            @Nullable String mimeType, int keyType, @Nullable HashMap<String, String> optionalParameters)
            throws NotProvisionedException {
        throw released();
    }

    public @Nullable byte[] provideKeyResponse(@NonNull byte[] scope, @NonNull byte[] response)
            throws NotProvisionedException, DeniedByServerException {
        throw released();
    }

    public void restoreKeys(@NonNull byte[] sessionId, @NonNull byte[] keySetId) {
        throw released();
    }

    public void removeKeys(@NonNull byte[] sessionId) {
        throw released();
    }

    public @NonNull HashMap<String, String> queryKeyStatus(@NonNull byte[] sessionId) {
        throw released();
    }

    public @NonNull ProvisionRequest getProvisionRequest() {
        throw released();
    }

    public void provideProvisionResponse(@NonNull byte[] response) throws DeniedByServerException {
        throw released();
    }

    public @NonNull List<byte[]> getOfflineLicenseKeySetIds() {
        throw released();
    }

    public void removeOfflineLicense(@NonNull byte[] keySetId) {
        throw released();
    }

    public int getOfflineLicenseState(@NonNull byte[] keySetId) {
        throw released();
    }

    public @NonNull List<byte[]> getSecureStops() {
        throw released();
    }

    public void releaseAllSecureStops() {
        throw released();
    }

    public int getConnectedHdcpLevel() {
        throw released();
    }

    public int getMaxHdcpLevel() {
        throw released();
    }

    public int getOpenSessionCount() {
        throw released();
    }

    public int getMaxSessionCount() {
        throw released();
    }

    public int getSecurityLevel(@NonNull byte[] sessionId) {
        throw released();
    }

    public @NonNull String getPropertyString(@NonNull String propertyName) {
        throw released();
    }

    public void setPropertyString(@NonNull String propertyName, @NonNull String value) {
        throw released();
    }

    public @NonNull byte[] getPropertyByteArray(@NonNull String propertyName) {
        throw released();
    }

    public void setPropertyByteArray(@NonNull String propertyName, @NonNull byte[] value) {
        throw released();
    }

    public @NonNull PersistableBundle getMetrics() {
        throw released();
    }

    public @Nullable PlaybackComponent getPlaybackComponent(@NonNull byte[] sessionId) {
        throw released();
    }

    public boolean requiresSecureDecoder(@NonNull String mime) {
        throw released();
    }

    public boolean requiresSecureDecoder(@NonNull String mime, int level) {
        throw released();
    }

    /** Releasing an object that was never created releases nothing. */
    @Override
    public void close() {
    }

    @Deprecated
    public void release() {
        close();
    }

    // ---- nested types ----

    /** Contains the opaque data an app uses to request keys from a license server. */
    public static final class KeyRequest {
        public static final int REQUEST_TYPE_INITIAL = 0;
        public static final int REQUEST_TYPE_RENEWAL = 1;
        public static final int REQUEST_TYPE_RELEASE = 2;
        public static final int REQUEST_TYPE_NONE = 3;
        public static final int REQUEST_TYPE_UPDATE = 4;

        private final byte[] mData;
        private final String mDefaultUrl;
        private final int mRequestType;

        KeyRequest(byte[] data, String defaultUrl, int requestType) {
            mData = data;
            mDefaultUrl = defaultUrl;
            mRequestType = requestType;
        }

        public @NonNull byte[] getData() {
            return mData;
        }

        public @NonNull String getDefaultUrl() {
            return mDefaultUrl;
        }

        public int getRequestType() {
            return mRequestType;
        }
    }

    /** Contains the opaque data an app uses to request a certificate from a provisioning server. */
    public static final class ProvisionRequest {
        private final byte[] mData;
        private final String mDefaultUrl;

        ProvisionRequest(byte[] data, String defaultUrl) {
            mData = data;
            mDefaultUrl = defaultUrl;
        }

        public @NonNull byte[] getData() {
            return mData;
        }

        public @NonNull String getDefaultUrl() {
            return mDefaultUrl;
        }
    }

    /** Information about the status of a key. */
    public static final class KeyStatus {
        public static final int STATUS_USABLE = 0;
        public static final int STATUS_EXPIRED = 1;
        public static final int STATUS_OUTPUT_NOT_ALLOWED = 2;
        public static final int STATUS_PENDING = 3;
        public static final int STATUS_INTERNAL_ERROR = 4;
        public static final int STATUS_USABLE_IN_FUTURE = 5;

        private final byte[] mKeyId;
        private final int mStatusCode;

        KeyStatus(@NonNull byte[] keyId, int statusCode) {
            mKeyId = keyId;
            mStatusCode = statusCode;
        }

        public int getStatusCode() {
            return mStatusCode;
        }

        public @NonNull byte[] getKeyId() {
            return mKeyId;
        }
    }

    /** Thrown when a general failure occurs during a MediaDrm operation. */
    public static final class MediaDrmStateException extends IllegalStateException {
        private final int mErrorCode;
        private final String mDiagnosticInfo;

        /** @hide */
        public MediaDrmStateException(int errorCode, @Nullable String detailMessage) {
            super(detailMessage);
            mErrorCode = errorCode;
            final String sign = errorCode < 0 ? "neg_" : "";
            mDiagnosticInfo = "android.media.MediaDrm.error_" + sign + Math.abs(errorCode);
        }

        /** @hide */
        public int getErrorCode() {
            return mErrorCode;
        }

        public @NonNull String getDiagnosticInfo() {
            return mDiagnosticInfo;
        }
    }

    /** Thrown when an error occurs in any method that has a session context. */
    public static final class SessionException extends RuntimeException {
        public static final int ERROR_UNKNOWN = 0;
        public static final int ERROR_RESOURCE_CONTENTION = 1;

        private final int mErrorCode;

        /** @hide */
        public SessionException(int errorCode, @Nullable String detailMessage) {
            super(detailMessage);
            mErrorCode = errorCode;
        }

        @Deprecated
        public int getErrorCode() {
            return mErrorCode;
        }

        public boolean isTransient() {
            return mErrorCode == ERROR_RESOURCE_CONTENTION;
        }
    }

    /** The playback component of a session (API 31). */
    public final class PlaybackComponent {
        private final byte[] mSessionId;

        PlaybackComponent(@NonNull byte[] sessionId) {
            mSessionId = sessionId;
        }

        public void setLogSessionId(@NonNull android.media.metrics.LogSessionId logSessionId) {
            throw released();
        }

        public @NonNull android.media.metrics.LogSessionId getLogSessionId() {
            throw released();
        }
    }

    /** Keys of {@link #getMetrics()}. */
    public final static class MetricsConstants {
        private MetricsConstants() {}

        public static final String OPEN_SESSION_OK_COUNT = "drm.mediadrm.open_session.ok.count";
        public static final String OPEN_SESSION_ERROR_COUNT = "drm.mediadrm.open_session.error.count";
        public static final String OPEN_SESSION_ERROR_LIST = "drm.mediadrm.open_session.error.list";
        public static final String CLOSE_SESSION_OK_COUNT = "drm.mediadrm.close_session.ok.count";
        public static final String CLOSE_SESSION_ERROR_COUNT = "drm.mediadrm.close_session.error.count";
        public static final String CLOSE_SESSION_ERROR_LIST = "drm.mediadrm.close_session.error.list";
        public static final String SESSION_START_TIMES_MS = "drm.mediadrm.session_start_times_ms";
        public static final String SESSION_END_TIMES_MS = "drm.mediadrm.session_end_times_ms";
        public static final String GET_DEVICE_UNIQUE_ID_OK_COUNT = "drm.mediadrm.get_device_unique_id.ok.count";
        public static final String GET_DEVICE_UNIQUE_ID_ERROR_COUNT = "drm.mediadrm.get_device_unique_id.error.count";
        public static final String GET_DEVICE_UNIQUE_ID_ERROR_LIST = "drm.mediadrm.get_device_unique_id.error.list";
        public static final String GET_KEY_REQUEST_OK_COUNT = "drm.mediadrm.get_key_request.ok.count";
        public static final String GET_KEY_REQUEST_ERROR_COUNT = "drm.mediadrm.get_key_request.error.count";
        public static final String GET_KEY_REQUEST_ERROR_LIST = "drm.mediadrm.get_key_request.error.list";
        public static final String PROVIDE_KEY_RESPONSE_OK_COUNT = "drm.mediadrm.provide_key_response.ok.count";
        public static final String PROVIDE_KEY_RESPONSE_ERROR_COUNT = "drm.mediadrm.provide_key_response.error.count";
        public static final String PROVIDE_KEY_RESPONSE_ERROR_LIST = "drm.mediadrm.provide_key_response.error.list";
        public static final String GET_PROVISION_REQUEST_OK_COUNT = "drm.mediadrm.get_provision_request.ok.count";
        public static final String GET_PROVISION_REQUEST_ERROR_COUNT = "drm.mediadrm.get_provision_request.error.count";
        public static final String GET_PROVISION_REQUEST_ERROR_LIST = "drm.mediadrm.get_provision_request.error.list";
        public static final String PROVIDE_PROVISION_RESPONSE_OK_COUNT = "drm.mediadrm.provide_provision_response.ok.count";
        public static final String PROVIDE_PROVISION_RESPONSE_ERROR_COUNT = "drm.mediadrm.provide_provision_response.error.count";
        public static final String PROVIDE_PROVISION_RESPONSE_ERROR_LIST = "drm.mediadrm.provide_provision_response.error.list";
        public static final String SECURITY_LEVEL = "drm.mediadrm.security_level";
    }
}
