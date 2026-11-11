#!/bin/sh
# teslalinux: android.graphics.Region natives on cairo regions (ATL declared them and never
# implemented them). Copies native/atl_region.c into the ATL tree and adds it to the build. Run by
# build-atl.sh from ~/atl-src. Idempotent.
set -eu
cp "$HOME/atl-native-patches/files/atl_region.c" src/api-impl-jni/graphics/atl_region.c
if ! grep -q "api-impl-jni/graphics/atl_region.c" meson.build; then
  sed -i "s|'src/api-impl-jni/egl/com_google_android_gles_jni_EGLImpl.c',|'src/api-impl-jni/egl/com_google_android_gles_jni_EGLImpl.c',\n\t'src/api-impl-jni/graphics/atl_region.c',|" meson.build
fi
grep -c "atl_region" meson.build
