#!/bin/sh
# teslalinux: add the android.opengl.EGL14/EGL15/EGLExt JNI (api-impl-jni/opengl/android_opengl_EGL14.c)
# to ATL's native library. Run by build-atl.sh from ~/atl-src; the C file is pushed to
# ~/atl-native-patches/files/. Idempotent.
set -eu
mkdir -p src/api-impl-jni/opengl
cp "$HOME/atl-native-patches/files/android_opengl_EGL14.c" src/api-impl-jni/opengl/android_opengl_EGL14.c
if ! grep -q "api-impl-jni/opengl/android_opengl_EGL14.c" meson.build; then
  sed -i "s|'src/api-impl-jni/egl/com_google_android_gles_jni_EGLImpl.c',|'src/api-impl-jni/egl/com_google_android_gles_jni_EGLImpl.c',\n\t'src/api-impl-jni/opengl/android_opengl_EGL14.c',|" meson.build
fi
grep -n "android_opengl_EGL14" meson.build
