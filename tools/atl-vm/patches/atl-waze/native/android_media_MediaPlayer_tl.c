
/* tl_mediaplayer: transport, volume, looping and release for the AOSP-shaped MediaPlayer
 * (patches/atl-waze/android/media/MediaPlayer.java). Appended by native-media-mediaplayer.sh. */

JNIEXPORT void JNICALL Java_android_media_MediaPlayer_native_1pause(JNIEnv *env, jclass this, jlong media_stream_ptr)
{
	gtk_media_stream_pause(GTK_MEDIA_STREAM(_PTR(media_stream_ptr)));
}

JNIEXPORT void JNICALL Java_android_media_MediaPlayer_native_1seekTo(JNIEnv *env, jclass this, jlong media_stream_ptr, jlong msec)
{
	GtkMediaStream *media_stream = GTK_MEDIA_STREAM(_PTR(media_stream_ptr));

	if (gtk_media_stream_is_seekable(media_stream))
		gtk_media_stream_seek(media_stream, msec * 1000); /* microseconds */
}

JNIEXPORT void JNICALL Java_android_media_MediaPlayer_native_1setVolume(JNIEnv *env, jclass this, jlong media_stream_ptr, jfloat volume)
{
	gtk_media_stream_set_volume(GTK_MEDIA_STREAM(_PTR(media_stream_ptr)), volume);
}

JNIEXPORT void JNICALL Java_android_media_MediaPlayer_native_1setLooping(JNIEnv *env, jclass this, jlong media_stream_ptr, jboolean looping)
{
	gtk_media_stream_set_loop(GTK_MEDIA_STREAM(_PTR(media_stream_ptr)), looping ? TRUE : FALSE);
}

JNIEXPORT jboolean JNICALL Java_android_media_MediaPlayer_native_1isPlaying(JNIEnv *env, jclass this, jlong media_stream_ptr)
{
	return gtk_media_stream_get_playing(GTK_MEDIA_STREAM(_PTR(media_stream_ptr))) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_android_media_MediaPlayer_native_1release(JNIEnv *env, jclass this, jlong media_stream_ptr)
{
	GtkMediaStream *media_stream = GTK_MEDIA_STREAM(_PTR(media_stream_ptr));
	jobject player = g_object_get_data(G_OBJECT(media_stream), "media_player");

	gtk_media_stream_pause(media_stream);
	g_signal_handlers_disconnect_matched(media_stream, G_SIGNAL_MATCH_FUNC, 0, 0, NULL, G_CALLBACK(on_ended), NULL);
	g_signal_handlers_disconnect_matched(media_stream, G_SIGNAL_MATCH_FUNC, 0, 0, NULL, G_CALLBACK(on_prepared), NULL);
	g_object_set_data(G_OBJECT(media_stream), "media_player", NULL);
	g_object_unref(media_stream);
	if (player)
		_UNREF(player);
}
