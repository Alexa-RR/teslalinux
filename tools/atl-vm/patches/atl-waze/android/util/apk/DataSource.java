/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.util.apk;

import android.annotation.NonNull;

import java.io.FileDescriptor;
import java.io.IOException;
import java.security.DigestException;

/** Source of data to be digested. */
interface DataSource {

    /**
     * Returns the size (in bytes) of the data offered by this source.
     */
    long size();

    /**
     * Feeds the specified region of this source's data into the provided digester.
     *
     * @param offset offset of the region inside this data source.
     * @param size size (in bytes) of the region.
     */
    void feedIntoDataDigester(DataDigester md, long offset, int size)
            throws IOException, DigestException;

    /**
     * Creates a DataSource that can handle the passed fd in the most efficient and safe manner.
     * @param fd file descriptor to read from
     * @param pos starting offset
     * @param size size of the region
     * @return created DataSource object
     */
    static @NonNull DataSource create(@NonNull FileDescriptor fd, long pos, long size) {
        // teslalinux: no incremental (IncFS) installs on this platform, so mmap is always safe here
        return new MemoryMappedFileDataSource(fd, pos, size);
    }
}
