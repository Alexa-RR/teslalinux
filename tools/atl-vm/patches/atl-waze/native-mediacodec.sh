#!/bin/sh
# teslalinux: MediaCodec natives for the AOSP-shaped android.media.MediaCodec (tl_mediacodec):
# - an unknown codec name is an IOException in Java, not exit(0);
# - any channel count gets FFmpeg's default layout (ATL handled mono and stereo only);
# - native_flush (avcodec_flush_buffers) backs MediaCodec.flush();
# - native_setOutputSurface backs MediaCodec.setOutputSurface/detachOutputSurface;
# - MediaCodecList.nativeHasDecoder reports which decoders this FFmpeg build has.
# Run by build-atl.sh from ~/atl-src. Idempotent. The guard is the full marker of the appended block:
# native-mediacodec-no-exit.sh (sorted before this script) writes "tl_mediacodec_drop" into the same
# file, so a bare "tl_mediacodec" guard skipped this script on every fresh build.
set -eu
M=src/api-impl-jni/media/android_media_MediaCodec.c
if ! grep -qF "teslalinux additions (tl_mediacodec)" "$M"; then
python3 - "$M" <<'PY'
import sys, re
p=sys.argv[1]; s=open(p).read()
old='''	if (!codec) {
		printf("Codec %s not found\\n", name);
		exit(0);
	}
'''
new='''	if (!codec) {
		/* tl_mediacodec: MediaCodec.createByCodecName turns this into an IOException */
		(*env)->ReleaseStringUTFChars(env, codec_name, name);
		return 0;
	}
'''
if old in s:
	assert s.count(old)==1, s.count(old)
	s=s.replace(old,new,1)
else:
	assert "tl_mediacodec: MediaCodec.createByCodecName" in s
n=len(re.findall(r'^\s*printf\("MediaCodec: Unsupported number of channels %d\\n", nb_channels\);\s*$', s, flags=re.M))
assert n==1 or "/* AOSP: any channel count */" in s, n
s=re.sub(r'^(\s*)printf\("MediaCodec: Unsupported number of channels %d\\n", nb_channels\);\s*$', r'\1av_channel_layout_default(&codec_ctx->ch_layout, nb_channels); /* AOSP: any channel count */', s, flags=re.M)
s += '''
/* ---- teslalinux additions (tl_mediacodec) ---- */
JNIEXPORT void JNICALL Java_android_media_MediaCodec_native_1flush(JNIEnv *env, jobject this, jlong codec)
{
	struct ATL_codec_context *ctx = _PTR(codec);
	if (ctx && ctx->codec && avcodec_is_open(ctx->codec))
		avcodec_flush_buffers(ctx->codec);
}

JNIEXPORT void JNICALL Java_android_media_MediaCodec_native_1setOutputSurface(JNIEnv *env, jobject this, jlong codec, jobject surface_obj)
{
	struct ATL_codec_context *ctx = _PTR(codec);
	if (!ctx || !ctx->codec || ctx->codec->codec_type != AVMEDIA_TYPE_VIDEO)
		return;
	if (!surface_obj) {
		ctx->video.surface_view_widget = NULL;
		return;
	}
	ctx->video.surface_view_widget = SURFACE_VIEW_WIDGET(gtk_widget_get_first_child(_PTR(_GET_LONG_FIELD(surface_obj, "widget"))));
}

JNIEXPORT jboolean JNICALL Java_android_media_MediaCodecList_nativeHasDecoder(JNIEnv *env, jclass this, jstring codec_name)
{
	const char *name = (*env)->GetStringUTFChars(env, codec_name, NULL);
	const AVCodec *codec = avcodec_find_decoder_by_name(name);
	(*env)->ReleaseStringUTFChars(env, codec_name, name);
	return codec != NULL;
}
'''
open(p,'w').write(s)
PY
fi
grep -c "teslalinux additions (tl_mediacodec)\|nativeHasDecoder" "$M"
