/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.os.ext;

import java.util.Collections;
import java.util.Map;

/**
 * Methods for interacting with the extension SDK (AOSP).
 *
 * <p>teslalinux: the SDK extensions are the APIs mainline modules add on top of a platform
 * release. ATL ships no mainline modules, so every extension is at version 0 (none of its APIs
 * present), which is what AOSP reports for an extension a device does not have.
 */
public class SdkExtensions {
    /** AOSP: the extension of the ad services module */
    public static final int AD_SERVICES = 1000000;

    private SdkExtensions() {
    }

    /**
     * Return the version of the specified extensions.
     *
     * @param extension the extension to get the version of.
     * @throws IllegalArgumentException if extension is not a valid extension
     */
    public static int getExtensionVersion(int extension) {
        if (extension < android.os.Build.VERSION_CODES.R) {
            throw new IllegalArgumentException("not a valid extension: " + extension);
        }
        return 0;
    }

    /** Return all extension versions that exist on this device (none here). */
    public static Map<Integer, Integer> getAllExtensionVersions() {
        return Collections.emptyMap();
    }
}
