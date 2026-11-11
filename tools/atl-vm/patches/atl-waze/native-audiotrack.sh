#!/bin/sh
# teslalinux: AudioTrack natives for the AOSP-shaped android.media.AudioTrack (tl_audiotrack):
# - a PCM device that cannot be opened leaves the track STATE_UNINITIALIZED (Builder.build throws
#   UnsupportedOperationException like AOSP) instead of using a NULL handle;
# - the ALSA buffer size in frames is reported (getBufferSizeInFrames), underruns are counted;
# - native_stop/native_flush (drop + prepare), native_avail (frames writable without blocking)
#   back stop(), flush() and WRITE_NON_BLOCKING;
# - getMinBufferSize returns ERROR when the device cannot be opened and no longer writes a static field.
# Run by build-atl.sh from ~/atl-src. Idempotent.
set -eu
T=src/api-impl-jni/audio/android_media_AudioTrack.c
if ! grep -q "tl_audiotrack" "$T"; then
python3 - "$T" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
def cut(s, old, new, count=1):
    assert s.count(old)==count, (old[:60], s.count(old))
    return s.replace(old, new)
s = cut(s, '''	ret = snd_pcm_open(&pcm_handle, PCM_DEVICE, SND_PCM_STREAM_PLAYBACK, 0);
	if (ret < 0)
		printf("ERROR: Can't open \\"%s\\" PCM device. %s\\n", PCM_DEVICE, snd_strerror(ret));
''', '''	ret = snd_pcm_open(&pcm_handle, PCM_DEVICE, SND_PCM_STREAM_PLAYBACK, 0);
	if (ret < 0) {
		/* tl_audiotrack: no output device: the Java side reports STATE_UNINITIALIZED */
		printf("ERROR: Can't open \\"%s\\" PCM device. %s\\n", PCM_DEVICE, snd_strerror(ret));
		_SET_LONG_FIELD(this, "pcm_handle", 0);
		return;
	}
''')
s = cut(s, '''	_SET_LONG_FIELD(this, "pcm_handle", _INTPTR(pcm_handle));
''', '''	_SET_LONG_FIELD(this, "pcm_handle", _INTPTR(pcm_handle));
	snd_pcm_uframes_t buffer_frames = 0;
	snd_pcm_hw_params_get_buffer_size(params, &buffer_frames);
	_SET_INT_FIELD(this, "buffer_frames", (jint)buffer_frames);
''')
s = cut(s, '''static int write_frames(snd_pcm_t *pcm_handle, const void *buffer, int frames_to_write, float volume)
{
''', '''static int tl_xruns; /* tl_audiotrack: underruns since the last read (per process; tracks read and clear it) */

static int write_frames(snd_pcm_t *pcm_handle, const void *buffer, int frames_to_write, float volume)
{
''')
s = cut(s, '''	if (frames_written == -EPIPE) {
		printf("XRUN.\\n");
''', '''	if (frames_written == -EPIPE) {
		tl_xruns++;
''')
s = cut(s, '''		printf("Error calling snd_pcm_open: %s\\n", snd_strerror(ret));
''', '''	{
		printf("Error calling snd_pcm_open: %s\\n", snd_strerror(ret));
		return -1; /* AOSP: ERROR when the output cannot be queried */
	}
''')
s = cut(s, '''	_SET_STATIC_INT_FIELD(this_class, "frames", frames);
	if ((*env)->ExceptionCheck(env))
		(*env)->ExceptionDescribe(env);
''', '''	/* tl_audiotrack: the period size is returned, not kept in a static field */
''')
s += '''
/* ---- teslalinux additions (tl_audiotrack) ---- */
JNIEXPORT void JNICALL Java_android_media_AudioTrack_native_1stop(JNIEnv *env, jobject this)
{
	snd_pcm_t *pcm_handle = _PTR(_GET_LONG_FIELD(this, "pcm_handle"));
	if (!pcm_handle)
		return;
	snd_pcm_drop(pcm_handle);
	snd_pcm_prepare(pcm_handle);
}

JNIEXPORT void JNICALL Java_android_media_AudioTrack_native_1flush(JNIEnv *env, jobject this)
{
	snd_pcm_t *pcm_handle = _PTR(_GET_LONG_FIELD(this, "pcm_handle"));
	if (!pcm_handle)
		return;
	snd_pcm_drop(pcm_handle);
	snd_pcm_prepare(pcm_handle);
}

/* frames that can be written without blocking (the free part of the ALSA ring buffer) */
JNIEXPORT jint JNICALL Java_android_media_AudioTrack_native_1avail(JNIEnv *env, jobject this)
{
	snd_pcm_t *pcm_handle = _PTR(_GET_LONG_FIELD(this, "pcm_handle"));
	if (!pcm_handle)
		return 0;
	snd_pcm_sframes_t avail = snd_pcm_avail_update(pcm_handle);
	if (avail < 0) {
		snd_pcm_recover(pcm_handle, avail, 1);
		avail = snd_pcm_avail_update(pcm_handle);
	}
	return avail < 0 ? 0 : (jint)avail;
}

JNIEXPORT jint JNICALL Java_android_media_AudioTrack_native_1takeUnderruns(JNIEnv *env, jobject this)
{
	int n = tl_xruns;
	tl_xruns = 0;
	return n;
}
'''
open(p,'w').write(s)
PY
fi
grep -c "tl_audiotrack" "$T"
