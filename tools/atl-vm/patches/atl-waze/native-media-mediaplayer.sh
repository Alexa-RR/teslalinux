#!/bin/sh
# teslalinux: MediaPlayer natives for the AOSP-shaped android.media.MediaPlayer (tl_mediaplayer):
# pause, seek, volume, looping, isPlaying and release of the GtkMediaStream. Appends
# native/android_media_MediaPlayer_tl.c (pushed to ~/atl-native-patches/files/) to ATL's file, which
# already defines on_prepared/on_ended used by the release function.
# Run by build-atl.sh from ~/atl-src. Idempotent.
set -eu
T=src/api-impl-jni/media/android_media_MediaPlayer.c
if ! grep -q "tl_mediaplayer" "$T"; then
  cat "$HOME/atl-native-patches/files/android_media_MediaPlayer_tl.c" >> "$T"
fi
grep -c "tl_mediaplayer" "$T"
