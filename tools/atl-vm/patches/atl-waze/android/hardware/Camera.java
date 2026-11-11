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

package android.hardware;

/**
 * The (deprecated) Camera class (AOSP API).
 *
 * teslalinux: this device has no camera. AOSP's answers on such a device: no cameras,
 * {@link #open()} returns null (no back-facing camera), {@link #open(int)} and
 * {@link #getCameraInfo} reject every id. No Camera object can exist.
 */
@Deprecated
public class Camera {
    public static final String ACTION_NEW_PICTURE = "android.hardware.action.NEW_PICTURE";
    public static final String ACTION_NEW_VIDEO = "android.hardware.action.NEW_VIDEO";

    public static final int CAMERA_ERROR_UNKNOWN = 1;
    public static final int CAMERA_ERROR_EVICTED = 2;
    public static final int CAMERA_ERROR_DISABLED = 3;
    public static final int CAMERA_ERROR_SERVER_DIED = 100;

    public interface PreviewCallback {
        void onPreviewFrame(byte[] data, Camera camera);
    }

    public interface AutoFocusCallback {
        void onAutoFocus(boolean success, Camera camera);
    }

    public interface ErrorCallback {
        void onError(int error, Camera camera);
    }

    /** Information about a camera (AOSP). */
    public static class CameraInfo {
        public static final int CAMERA_FACING_BACK = 0;
        public static final int CAMERA_FACING_FRONT = 1;

        public int facing;
        public int orientation;
        public boolean canDisableShutterSound;
    }

    private ErrorCallback mErrorCallback;

    private Camera() {
    }

    public static int getNumberOfCameras() {
        return 0;
    }

    public static void getCameraInfo(int cameraId, CameraInfo cameraInfo) {
        if (cameraId >= getNumberOfCameras()) {
            throw new RuntimeException("Unknown camera ID");
        }
    }

    public static Camera open(int cameraId) {
        // AOSP: connecting to an id the camera service does not have fails
        throw new RuntimeException("Fail to connect to camera service");
    }

    /** AOSP: the first back-facing camera, or null when there is none. */
    public static Camera open() {
        return null;
    }

    public final void setErrorCallback(ErrorCallback cb) {
        mErrorCallback = cb;
    }
}
