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

/*
 * libandroidio: the C ABI of AOSP libcore's libandroidio (luni/src/main/native/
 * AsynchronousCloseMonitor.cpp, "C ABI and API boundary", and libandroidio.map.txt), which
 * Conscrypt's CompatibilityCloseMonitor dlopen()s to register a thread blocked in poll/read/write
 * on a socket, so that closing that socket from another thread (IoBridge.closeAndSignalBlockedThreads,
 * how OkHttp cancels a call) wakes it with BLOCKED_THREAD_SIGNAL.
 *
 * teslalinux: art_standalone's libcore predates the libandroidio split (Android 10): the monitor
 * lives in its libnativehelper, and libjavacore (Posix I/O, IoBridge) registers and signals
 * blocked threads through that one list. This library therefore exports AOSP's functions over
 * libnativehelper's AsynchronousCloseMonitor instead of carrying a second copy of the class; a
 * second list would never be signalled by libjavacore. The behaviour per function is AOSP's.
 */

#include <nativehelper/AsynchronousCloseMonitor.h>

#define ANDROIDIO_EXPORT extern "C" __attribute__((visibility("default")))

ANDROIDIO_EXPORT void async_close_monitor_static_init() {
    AsynchronousCloseMonitor::init();
}

ANDROIDIO_EXPORT void async_close_monitor_signal_blocked_threads(int fd) {
    AsynchronousCloseMonitor::signalBlockedThreads(fd);
}

ANDROIDIO_EXPORT void* async_close_monitor_create(int fd) {
    return new AsynchronousCloseMonitor(fd);
}

ANDROIDIO_EXPORT void async_close_monitor_destroy(void* instance) {
    auto monitor = reinterpret_cast<AsynchronousCloseMonitor*>(instance);
    delete monitor;
}

ANDROIDIO_EXPORT int async_close_monitor_was_signalled(const void* instance) {
    auto monitor = reinterpret_cast<const AsynchronousCloseMonitor*>(instance);
    return monitor->wasSignaled() ? 1 : 0;
}
