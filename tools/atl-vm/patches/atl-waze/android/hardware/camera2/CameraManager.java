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

package android.hardware.camera2;

import android.annotation.NonNull;
import android.os.Handler;
import android.os.Looper;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;

/**
 * A system service manager for detecting, characterizing, and connecting to camera devices
 * (AOSP API).
 *
 * teslalinux: this device has no camera, so the camera service knows no device: the id list is
 * empty, availability callbacks are registered but no camera ever becomes available, and every
 * per-camera call rejects the id as AOSP does for an unknown camera. The camera2 device classes
 * (CameraDevice, CameraCharacteristics, sessions) are not provided, since no camera can be opened.
 */
public final class CameraManager {
    private final CopyOnWriteArrayList<Object> mAvailabilityCallbacks = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Object> mTorchCallbacks = new CopyOnWriteArrayList<>();

    /** @hide */
    public CameraManager() {
    }

    @NonNull
    public String[] getCameraIdList() throws CameraAccessException {
        return new String[0];
    }

    /** No concurrent streaming combinations without cameras (AOSP: an empty set). */
    @NonNull
    public Set<Set<String>> getConcurrentCameraIds() throws CameraAccessException {
        return new HashSet<>();
    }

    private static IllegalArgumentException unknown(String cameraId) {
        return new IllegalArgumentException("Camera id " + cameraId + " does not match any currently connected camera device");
    }

    public boolean isCameraDeviceSetupSupported(@NonNull String cameraId) throws CameraAccessException {
        if (cameraId == null) {
            throw new IllegalArgumentException("Camera ID was null");
        }
        throw unknown(cameraId);
    }

    public void setTorchMode(@NonNull String cameraId, boolean enabled) throws CameraAccessException {
        if (cameraId == null) {
            throw new IllegalArgumentException("cameraId was null");
        }
        throw unknown(cameraId);
    }

    public int getTorchStrengthLevel(@NonNull String cameraId) throws CameraAccessException {
        if (cameraId == null) {
            throw new IllegalArgumentException("cameraId was null");
        }
        throw unknown(cameraId);
    }

    public void registerAvailabilityCallback(@NonNull AvailabilityCallback callback, Handler handler) {
        if (callback == null) {
            throw new IllegalArgumentException("callback was null");
        }
        if (!mAvailabilityCallbacks.contains(callback))
            mAvailabilityCallbacks.add(callback);
    }

    public void registerAvailabilityCallback(@NonNull Executor executor, @NonNull AvailabilityCallback callback) {
        if (executor == null) {
            throw new IllegalArgumentException("executor was null");
        }
        registerAvailabilityCallback(callback, (Handler) null);
    }

    public void unregisterAvailabilityCallback(@NonNull AvailabilityCallback callback) {
        mAvailabilityCallbacks.remove(callback);
    }

    public void registerTorchCallback(@NonNull TorchCallback callback, Handler handler) {
        if (callback == null) {
            throw new IllegalArgumentException("callback was null");
        }
        if (!mTorchCallbacks.contains(callback))
            mTorchCallbacks.add(callback);
    }

    public void registerTorchCallback(@NonNull Executor executor, @NonNull TorchCallback callback) {
        if (executor == null) {
            throw new IllegalArgumentException("executor was null");
        }
        registerTorchCallback(callback, (Handler) null);
    }

    public void unregisterTorchCallback(@NonNull TorchCallback callback) {
        mTorchCallbacks.remove(callback);
    }

    /** Callback for camera devices becoming available or unavailable to open. */
    public static abstract class AvailabilityCallback {
        public void onCameraAvailable(@NonNull String cameraId) {
        }

        public void onCameraUnavailable(@NonNull String cameraId) {
        }

        public void onCameraAccessPrioritiesChanged() {
        }

        public void onPhysicalCameraAvailable(@NonNull String cameraId, @NonNull String physicalCameraId) {
        }

        public void onPhysicalCameraUnavailable(@NonNull String cameraId, @NonNull String physicalCameraId) {
        }
    }

    /** Callback for camera flash torch modes becoming unavailable, disabled, or enabled. */
    public static abstract class TorchCallback {
        public void onTorchModeUnavailable(@NonNull String cameraId) {
        }

        public void onTorchModeChanged(@NonNull String cameraId, boolean enabled) {
        }

        public void onTorchStrengthLevelChanged(@NonNull String cameraId, int newStrengthLevel) {
        }
    }
}
