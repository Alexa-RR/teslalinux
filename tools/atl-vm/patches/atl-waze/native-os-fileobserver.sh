#!/bin/sh
# teslalinux: android.os.FileObserver on inotify (AOSP android_util_FileObserver.cpp). Copies
# native/android_os_FileObserver.c into the ATL tree and adds it to the build; the Java side is
# patches/atl-waze/android/os/FileObserver.java (AOSP). Run by build-atl.sh from ~/atl-src.
# Idempotent.
set -eu
cp "$HOME/atl-native-patches/files/android_os_FileObserver.c" src/api-impl-jni/os/android_os_FileObserver.c
if ! grep -q "api-impl-jni/os/android_os_FileObserver.c" meson.build; then
  sed -i "s|'src/api-impl-jni/egl/com_google_android_gles_jni_EGLImpl.c',|'src/api-impl-jni/egl/com_google_android_gles_jni_EGLImpl.c',\n\t'src/api-impl-jni/os/android_os_FileObserver.c',|" meson.build
fi
grep -c "android_os_FileObserver" meson.build
