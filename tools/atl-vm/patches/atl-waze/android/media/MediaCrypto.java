/*
 * Copyright (C) 2012 The Android Open Source Project
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

import java.util.UUID;

/**
 * MediaCrypto class can be used in conjunction with {@link android.media.MediaCodec} to decode
 * encrypted media data (AOSP API).
 *
 * teslalinux: AOSP instantiates a crypto plugin of the DRM HAL for the scheme. This device has no
 * DRM HAL and no crypto plugin (no Widevine, no ClearKey), so, as on AOSP when no plugin claims
 * the scheme, {@link #isCryptoSchemeSupported} is false and the constructor fails with
 * MediaCryptoException. No instance can exist, so the instance methods only keep AOSP's
 * released-object behaviour.
 */
public final class MediaCrypto {
    private boolean mReleased;

    /** No crypto plugin is installed on this device. */
    public static final boolean isCryptoSchemeSupported(@NonNull UUID uuid) {
        if (uuid == null) {
            throw new IllegalArgumentException("uuid must not be null");
        }
        return false;
    }

    public MediaCrypto(@NonNull UUID uuid, @NonNull byte[] initData) throws MediaCryptoException {
        if (uuid == null) {
            throw new IllegalArgumentException("uuid must not be null");
        }
        // AOSP JMediaCrypto: "Failed to instantiate crypto object." when no plugin supports it
        throw new MediaCryptoException("Failed to instantiate crypto object.");
    }

    public final boolean requiresSecureDecoderComponent(@NonNull String mime) {
        throw new IllegalStateException("MediaCrypto has been released");
    }

    public final void setMediaDrmSession(@NonNull byte[] sessionId) throws MediaCryptoException {
        throw new IllegalStateException("MediaCrypto has been released");
    }

    public final void release() {
        mReleased = true;
    }
}
