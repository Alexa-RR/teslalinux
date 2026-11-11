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

package android.media;

import android.annotation.NonNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Provides information about an audio device (AOSP android14 API).
 *
 * teslalinux: AOSP wraps an AudioDevicePort of the native audio policy. This runtime has no audio
 * server, so AudioManager builds these from what the host really has (the output AudioTrack plays
 * to, a capture device if ALSA lists one) and the values a port would carry are fields here. The
 * accessors keep AOSP's derivations (product name falling back to Build.MODEL, channel counts from
 * the masks, encodings filtered to the public ones).
 */
public final class AudioDeviceInfo {

    public static final int TYPE_UNKNOWN          = 0;
    public static final int TYPE_BUILTIN_EARPIECE = 1;
    public static final int TYPE_BUILTIN_SPEAKER  = 2;
    public static final int TYPE_WIRED_HEADSET    = 3;
    public static final int TYPE_WIRED_HEADPHONES = 4;
    public static final int TYPE_LINE_ANALOG      = 5;
    public static final int TYPE_LINE_DIGITAL     = 6;
    public static final int TYPE_BLUETOOTH_SCO    = 7;
    public static final int TYPE_BLUETOOTH_A2DP   = 8;
    public static final int TYPE_HDMI             = 9;
    public static final int TYPE_HDMI_ARC         = 10;
    public static final int TYPE_USB_DEVICE       = 11;
    public static final int TYPE_USB_ACCESSORY    = 12;
    public static final int TYPE_DOCK             = 13;
    public static final int TYPE_FM               = 14;
    public static final int TYPE_BUILTIN_MIC      = 15;
    public static final int TYPE_FM_TUNER         = 16;
    public static final int TYPE_TV_TUNER         = 17;
    public static final int TYPE_TELEPHONY        = 18;
    public static final int TYPE_AUX_LINE         = 19;
    public static final int TYPE_IP               = 20;
    public static final int TYPE_BUS              = 21;
    public static final int TYPE_USB_HEADSET      = 22;
    public static final int TYPE_HEARING_AID      = 23;
    public static final int TYPE_BUILTIN_SPEAKER_SAFE = 24;
    public static final int TYPE_REMOTE_SUBMIX    = 25;
    public static final int TYPE_BLE_HEADSET      = 26;
    public static final int TYPE_BLE_SPEAKER      = 27;
    public static final int TYPE_ECHO_REFERENCE   = 28;
    public static final int TYPE_HDMI_EARC        = 29;
    public static final int TYPE_BLE_BROADCAST    = 30;
    public static final int TYPE_DOCK_ANALOG      = 31;

    // the AOSP internal (audio.h) device types of the devices this runtime creates
    /** @hide */
    public static final int INTERNAL_DEVICE_OUT_SPEAKER = 0x2;
    /** @hide */
    public static final int INTERNAL_DEVICE_IN_BUILTIN_MIC = 0x80000004;

    private final int mId;
    private final int mType;
    private final boolean mSink;
    private final String mName;
    private final String mAddress;
    private final int[] mSampleRates;
    private final int[] mChannelMasks;
    private final int[] mChannelIndexMasks;
    private final int[] mFormats;
    private final List<AudioProfile> mProfiles;
    private final List<AudioDescriptor> mDescriptors;

    /**
     * @hide what an AudioDevicePort would carry. Empty rate / mask arrays mean "arbitrary", as in
     * AOSP. One AudioProfile is derived per format.
     */
    AudioDeviceInfo(int id, int type, boolean sink, String name, String address,
            int[] sampleRates, int[] channelMasks, int[] channelIndexMasks, int[] formats) {
        mId = id;
        mType = type;
        mSink = sink;
        mName = name;
        mAddress = address == null ? "" : address;
        mSampleRates = sampleRates;
        mChannelMasks = channelMasks;
        mChannelIndexMasks = channelIndexMasks;
        mFormats = formats;
        List<AudioProfile> profiles = new ArrayList<>();
        for (int format : formats) {
            profiles.add(new AudioProfile(format, sampleRates, channelMasks, channelIndexMasks,
                    AudioProfile.AUDIO_ENCAPSULATION_TYPE_NONE));
        }
        mProfiles = Collections.unmodifiableList(profiles);
        mDescriptors = Collections.emptyList();
    }

    /** @hide the internal (audio.h) device type */
    public int getInternalType() {
        switch (mType) {
            case TYPE_BUILTIN_SPEAKER:
                return INTERNAL_DEVICE_OUT_SPEAKER;
            case TYPE_BUILTIN_MIC:
                return INTERNAL_DEVICE_IN_BUILTIN_MIC;
            default:
                return 0;
        }
    }

    public int getId() {
        return mId;
    }

    public CharSequence getProductName() {
        return (mName != null && mName.length() != 0) ? mName : android.os.Build.MODEL;
    }

    public @NonNull String getAddress() {
        return mAddress;
    }

    public boolean isSource() {
        return !mSink;
    }

    public boolean isSink() {
        return mSink;
    }

    public @NonNull int[] getSampleRates() {
        return mSampleRates.clone();
    }

    public @NonNull int[] getChannelMasks() {
        return mChannelMasks.clone();
    }

    public @NonNull int[] getChannelIndexMasks() {
        return mChannelIndexMasks.clone();
    }

    public @NonNull int[] getChannelCounts() {
        TreeSet<Integer> countSet = new TreeSet<Integer>();
        for (int mask : getChannelMasks()) {
            countSet.add(isSink() ?
                    AudioFormat.channelCountFromOutChannelMask(mask)
                    : AudioFormat.channelCountFromInChannelMask(mask));
        }
        for (int index_mask : getChannelIndexMasks()) {
            countSet.add(Integer.bitCount(index_mask));
        }
        int[] counts = new int[countSet.size()];
        int index = 0;
        for (int count : countSet) {
            counts[index++] = count;
        }
        return counts;
    }

    public @NonNull int[] getEncodings() {
        return AudioFormat.filterPublicFormats(mFormats);
    }

    public @NonNull List<AudioProfile> getAudioProfiles() {
        return mProfiles;
    }

    public @NonNull List<AudioDescriptor> getAudioDescriptors() {
        return mDescriptors;
    }

    /** No compressed-stream encapsulation on a PCM output (AOSP: an empty array). */
    public @NonNull int[] getEncapsulationModes() {
        return new int[0];
    }

    /** No encapsulation metadata on a PCM output (AOSP: an empty array). */
    public @NonNull int[] getEncapsulationMetadataTypes() {
        return new int[0];
    }

    /**
     * The physical speaker layout (API 34). Only audio HALs that describe one (car buses, TVs)
     * set it; the host output here describes none, so this is AudioFormat.CHANNEL_INVALID, as
     * AOSP reports for a port without a layout.
     */
    public int getSpeakerLayoutChannelMask() {
        return AudioFormat.CHANNEL_INVALID;
    }

    public int getType() {
        return mType;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AudioDeviceInfo that = (AudioDeviceInfo) o;
        return mId == that.mId && mType == that.mType && mSink == that.mSink
                && Objects.equals(mAddress, that.mAddress)
                && Arrays.equals(mFormats, that.mFormats);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mId, mType, mSink, mAddress);
    }
}
