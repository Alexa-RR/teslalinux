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

package android.util;

import java.util.Objects;

/**
 * CloseGuard is a mechanism for flagging implicit finalizer cleanup of resources that should
 * have been cleaned up by explicit close methods (AOSP: a wrapper of libcore's
 * dalvik.system.CloseGuard, which reports through its Reporter when enabled by StrictMode).
 */
public final class CloseGuard {
    private final dalvik.system.CloseGuard mImpl;

    /** Constructs a new CloseGuard instance. */
    public CloseGuard() {
        mImpl = dalvik.system.CloseGuard.get();
    }

    /**
     * Initializes the instance with a warning that the caller should have explicitly called the
     * {@code closeMethodName} method instead of relying on finalization.
     */
    public void open(String closeMethodName) {
        Objects.requireNonNull(closeMethodName, "closeMethodName");
        mImpl.open(closeMethodName);
    }

    /** Marks this CloseGuard instance as closed to avoid warnings on finalization. */
    public void close() {
        mImpl.close();
    }

    /** Logs a warning if the caller did not properly cleanup by calling an explicit close method before finalization. */
    public void warnIfOpen() {
        mImpl.warnIfOpen();
    }
}
