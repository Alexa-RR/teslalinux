#!/bin/sh
# teslalinux: the font files of android.graphics.fonts.Font reach Pango (native/atl_fonts.c), and a
# Paint's typeface reaches its Pango font description (Paint.native_set_face).
#
# Typeface/Font/FontFamily loaded app fonts (res/font, assets, files) in Java only: text was shaped
# with the system default family, so Waze's Proxima Nova and its bundled Roboto styles fell back to
# Noto Sans, and Canvas.drawText ignored the typeface altogether (not even bold or italic).
# Run by build-atl.sh from ~/atl-src; the C file is pushed to ~/atl-native-patches/files/. Idempotent.
set -eu
f=atl_fonts.c
cp "$HOME/atl-native-patches/files/$f" "src/api-impl-jni/graphics/$f"
if ! grep -q "api-impl-jni/graphics/$f" meson.build; then
  sed -i "s|'src/api-impl-jni/egl/com_google_android_gles_jni_EGLImpl.c',|'src/api-impl-jni/egl/com_google_android_gles_jni_EGLImpl.c',\n\t'src/api-impl-jni/graphics/$f',|" meson.build
fi
grep -c "graphics/$f" meson.build
