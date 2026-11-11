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

import android.media.MediaCodecInfo.CodecCapabilities;
import android.media.MediaCodecInfo.CodecProfileLevel;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * teslalinux: the AOSP MediaCodecList over the decoders ATL's FFmpeg-backed MediaCodec provides.
 * Each entry carries the name, types, profile levels and capability limits that the AOSP software
 * codec of the same format advertises (media_codecs_google_*.xml); an entry is listed only when
 * this FFmpeg build has the decoder. There are no encoders.
 */
public final class MediaCodecList {
    private static final String TAG = "MediaCodecList";

    public static final int REGULAR_CODECS = 0;
    public static final int ALL_CODECS = 1;

    private static final int FLAG_IS_SOFTWARE_ONLY = (1 << 2); // MediaCodecInfo.FLAG_IS_SOFTWARE_ONLY

    private static native boolean nativeHasDecoder(String ffmpegName);

    private static final class Entry {
        final String name, ffmpegName, mime;
        final boolean video;
        final Map<String, Object> info = new HashMap<>();
        CodecProfileLevel[] profileLevels = new CodecProfileLevel[0];
        int[] colorFormats = new int[0];

        Entry(String name, String ffmpegName, String mime, boolean video) {
            this.name = name;
            this.ffmpegName = ffmpegName;
            this.mime = mime;
            this.video = video;
        }

        Entry audio(String sampleRates, int maxChannels, String bitrates) {
            info.put("sample-rate-ranges", sampleRates);
            info.put("max-channel-count", Integer.toString(maxChannels));
            info.put("bitrate-range", bitrates);
            return this;
        }

        Entry video(String sizes, String blocksPerSecond, String bitrates, String blockSize) {
            info.put("size-range", sizes);
            info.put("alignment", "2x2");
            info.put("block-size", blockSize);
            info.put("blocks-per-second-range", blocksPerSecond);
            info.put("bitrate-range", bitrates);
            info.put("feature-adaptive-playback", 0);
            colorFormats = new int[] {CodecCapabilities.COLOR_FormatYUV420Flexible, CodecCapabilities.COLOR_FormatYUV420Planar,
                    CodecCapabilities.COLOR_FormatYUV420SemiPlanar};
            return this;
        }

        Entry profiles(int level, int... profiles) {
            profileLevels = new CodecProfileLevel[profiles.length];
            for (int i = 0; i < profiles.length; i++) {
                profileLevels[i] = new CodecProfileLevel();
                profileLevels[i].profile = profiles[i];
                profileLevels[i].level = level;
            }
            return this;
        }
    }

    private static final Object sInitLock = new Object();
    private static Entry[] sEntries;
    private static MediaCodecInfo[] sAllCodecInfos;
    private static MediaCodecInfo[] sRegularCodecInfos;
    private static Map<String, Object> sGlobalSettings;

    private static Entry[] table() {
        return new Entry[] {
            new Entry("c2.android.aac.decoder", "aac", MediaFormat.MIMETYPE_AUDIO_AAC, false)
                    .audio("7350,8000,11025,12000,16000,22050,24000,32000,44100,48000", 8, "8000-960000")
                    .profiles(0, CodecProfileLevel.AACObjectLC, CodecProfileLevel.AACObjectHE, CodecProfileLevel.AACObjectHE_PS,
                            CodecProfileLevel.AACObjectLD, CodecProfileLevel.AACObjectELD, CodecProfileLevel.AACObjectERLC, CodecProfileLevel.AACObjectXHE),
            new Entry("c2.android.mp3.decoder", "mp3", MediaFormat.MIMETYPE_AUDIO_MPEG, false)
                    .audio("8000,11025,12000,16000,22050,24000,32000,44100,48000", 2, "8000-320000"),
            new Entry("c2.android.opus.decoder", "opus", MediaFormat.MIMETYPE_AUDIO_OPUS, false)
                    .audio("8000,12000,16000,24000,48000", 8, "6000-510000"),
            new Entry("c2.android.vorbis.decoder", "vorbis", MediaFormat.MIMETYPE_AUDIO_VORBIS, false)
                    .audio("8000-96000", 8, "32000-500000"),
            new Entry("c2.android.flac.decoder", "flac", MediaFormat.MIMETYPE_AUDIO_FLAC, false)
                    .audio("1-655350", 8, "1-21000000"),
            new Entry("c2.android.avc.decoder", "h264", MediaFormat.MIMETYPE_VIDEO_AVC, true)
                    .video("2x2-4096x4096", "1-1966080", "1-48000000", "16x16")
                    .profiles(CodecProfileLevel.AVCLevel52, CodecProfileLevel.AVCProfileBaseline, CodecProfileLevel.AVCProfileConstrainedBaseline,
                            CodecProfileLevel.AVCProfileMain, CodecProfileLevel.AVCProfileHigh, CodecProfileLevel.AVCProfileConstrainedHigh),
            new Entry("c2.android.hevc.decoder", "hevc", MediaFormat.MIMETYPE_VIDEO_HEVC, true)
                    .video("2x2-4096x4096", "1-2097152", "1-10000000", "8x8")
                    .profiles(CodecProfileLevel.HEVCMainTierLevel51, CodecProfileLevel.HEVCProfileMain, CodecProfileLevel.HEVCProfileMain10),
            new Entry("c2.android.vp8.decoder", "vp8", MediaFormat.MIMETYPE_VIDEO_VP8, true)
                    .video("2x2-2048x2048", "1-1000000", "1-40000000", "16x16")
                    .profiles(CodecProfileLevel.VP8Level_Version0, CodecProfileLevel.VP8ProfileMain),
            new Entry("c2.android.vp9.decoder", "vp9", MediaFormat.MIMETYPE_VIDEO_VP9, true)
                    .video("2x2-2048x2048", "1-1000000", "1-40000000", "16x16")
                    .profiles(CodecProfileLevel.VP9Level5, CodecProfileLevel.VP9Profile0, CodecProfileLevel.VP9Profile2),
            new Entry("c2.android.mpeg4.decoder", "mpeg4", MediaFormat.MIMETYPE_VIDEO_MPEG4, true)
                    .video("2x2-2048x2048", "1-1000000", "1-40000000", "16x16")
                    .profiles(CodecProfileLevel.MPEG4Level3, CodecProfileLevel.MPEG4ProfileSimple),
            new Entry("c2.android.h263.decoder", "h263", MediaFormat.MIMETYPE_VIDEO_H263, true)
                    .video("2x2-352x288", "1-60000", "1-10000000", "16x16")
                    .profiles(CodecProfileLevel.H263Level45, CodecProfileLevel.H263ProfileBaseline),
        };
    }

    private static void initCodecList() {
        synchronized (sInitLock) {
            if (sAllCodecInfos != null)
                return;
            ArrayList<Entry> present = new ArrayList<>();
            ArrayList<MediaCodecInfo> infos = new ArrayList<>();
            for (Entry e : table()) {
                if (!nativeHasDecoder(e.ffmpegName)) {
                    Log.v(TAG, "no FFmpeg decoder " + e.ffmpegName + " for " + e.name);
                    continue;
                }
                Map<String, Object> defaultFormat = new HashMap<>();
                defaultFormat.put(MediaFormat.KEY_MIME, e.mime);
                Map<String, Object> info = new HashMap<>(e.info);
                info.put(MediaFormat.KEY_MIME, e.mime);
                CodecCapabilities caps = new CodecCapabilities(e.profileLevels, e.colorFormats, false, defaultFormat, info);
                infos.add(new MediaCodecInfo(e.name, e.name, FLAG_IS_SOFTWARE_ONLY, new CodecCapabilities[] {caps}));
                present.add(e);
            }
            sEntries = present.toArray(new Entry[0]);
            sAllCodecInfos = infos.toArray(new MediaCodecInfo[0]);
            sRegularCodecInfos = sAllCodecInfos; // none needs a secure or tunneled path
        }
    }

    public static final int getCodecCount() {
        initCodecList();
        return sAllCodecInfos.length;
    }

    public static final MediaCodecInfo getCodecInfoAt(int index) {
        initCodecList();
        if (index < 0 || index > sAllCodecInfos.length) {
            throw new IllegalArgumentException();
        }
        return sAllCodecInfos[index];
    }

    /* package private */ static final Map<String, Object> getGlobalSettings() {
        synchronized (sInitLock) {
            if (sGlobalSettings == null) {
                sGlobalSettings = new HashMap<>();
                sGlobalSettings.put("max-concurrent-instances", "32");
                sGlobalSettings.put("supports-multiple-secure-codecs", "false");
                sGlobalSettings.put("supports-secure-with-non-secure-codec", "false");
            }
            return sGlobalSettings;
        }
    }

    /** AOSP (hidden): the info of a codec by (canonical) name, or null. */
    public static MediaCodecInfo getInfoFor(String codec) {
        initCodecList();
        for (MediaCodecInfo info : sAllCodecInfos) {
            if (info.getName().equals(codec) || info.getCanonicalName().equals(codec))
                return info;
        }
        return null;
    }

    /** The FFmpeg decoder behind a codec name (AOSP name, or an FFmpeg name as older ATL callers used), or null. */
    static String tlFfmpegName(String codec) {
        initCodecList();
        for (Entry e : sEntries) {
            if (e.name.equals(codec) || e.ffmpegName.equals(codec))
                return e.ffmpegName;
        }
        return null;
    }

    /** The codec name (AOSP) for an FFmpeg decoder name. */
    static String tlCodecName(String ffmpegName) {
        initCodecList();
        for (Entry e : sEntries) {
            if (e.ffmpegName.equals(ffmpegName))
                return e.name;
        }
        return ffmpegName;
    }

    static boolean tlIsVideo(String codec) {
        initCodecList();
        for (Entry e : sEntries) {
            if (e.name.equals(codec) || e.ffmpegName.equals(codec))
                return e.video;
        }
        return false;
    }

    static String tlMimeOf(String codec) {
        initCodecList();
        for (Entry e : sEntries) {
            if (e.name.equals(codec) || e.ffmpegName.equals(codec))
                return e.mime;
        }
        return null;
    }

    private MediaCodecInfo[] mCodecInfos;

    public MediaCodecList(int kind) {
        initCodecList();
        if (kind == REGULAR_CODECS) {
            mCodecInfos = sRegularCodecInfos;
        } else {
            mCodecInfos = sAllCodecInfos;
        }
    }

    public final MediaCodecInfo[] getCodecInfos() {
        return Arrays.copyOf(mCodecInfos, mCodecInfos.length);
    }

    public final String findDecoderForFormat(MediaFormat format) {
        return findCodecForFormat(false /* encoder */, format);
    }

    public final String findEncoderForFormat(MediaFormat format) {
        return findCodecForFormat(true /* encoder */, format);
    }

    private String findCodecForFormat(boolean encoder, MediaFormat format) {
        String mime = format.getString(MediaFormat.KEY_MIME);
        for (MediaCodecInfo info : mCodecInfos) {
            if (info.isEncoder() != encoder) {
                continue;
            }
            try {
                CodecCapabilities caps = info.getCapabilitiesForType(mime);
                if (caps != null && caps.isFormatSupported(format)) {
                    return info.getName();
                }
            } catch (IllegalArgumentException e) {
                // mime is not supported
            }
        }
        return null;
    }
}
