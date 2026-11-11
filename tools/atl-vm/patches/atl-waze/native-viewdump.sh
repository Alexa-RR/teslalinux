#!/bin/sh
# teslalinux: GTK widget tree dump for android.atl.ViewDump (ATL_DEBUG_DUMPVIEWS). Run by
# build-atl.sh from ~/atl-src; the C file is pushed to ~/atl-native-patches/files/. Idempotent.
set -eu
mkdir -p src/api-impl-jni/debug
cp "$HOME/atl-native-patches/files/atl_viewdump.c" src/api-impl-jni/debug/atl_viewdump.c
if ! grep -q "api-impl-jni/debug/atl_viewdump.c" meson.build; then
  sed -i "s|'src/api-impl-jni/egl/com_google_android_gles_jni_EGLImpl.c',|'src/api-impl-jni/egl/com_google_android_gles_jni_EGLImpl.c',\n\t'src/api-impl-jni/debug/atl_viewdump.c',|" meson.build
fi
grep -c "atl_viewdump" meson.build
