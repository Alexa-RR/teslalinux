#!/bin/sh
# teslalinux: complete JNI for com.google.android.gles_jni.EGLImpl (the javax EGL10 implementation;
# upstream ATL left eglGetCurrentContext/Surface/Display, eglGetError, eglQueryString, eglGetConfigs,
# eglQueryContext/Surface, eglTerminate, eglWaitGL/Native and eglReleaseThread unimplemented, and its
# EGL10 config/pbuffer path bypassed the bionic_egl* wrappers the EGL14 path uses). Run by
# build-atl.sh from ~/atl-src; the C file is pushed to ~/atl-native-patches/files/. Idempotent.
#
# Also fixes libandroid/egl.c bionic_eglCreatePbufferSurface: the copied attribute list was
# malloc'ed with the element count as the byte size (undersized for any list beyond width/height).
set -eu
cp "$HOME/atl-native-patches/files/com_google_android_gles_jni_EGLImpl.c" src/api-impl-jni/egl/com_google_android_gles_jni_EGLImpl.c
grep -c "native_1eglGetCurrentContext" src/api-impl-jni/egl/com_google_android_gles_jni_EGLImpl.c

F=src/libandroid/egl.c
if grep -q "new_attrib_list = malloc(attrib_list_len);" "$F"; then
  sed -i 's|new_attrib_list = malloc(attrib_list_len);|new_attrib_list = malloc(sizeof(EGLint) * (attrib_list_len + 1));|' "$F"
fi
grep -c "malloc(sizeof(EGLint) \* (attrib_list_len + 1))" "$F"

# bionic_eglGetCurrentSurface only knew ATL-managed window surfaces (the per-context hash tables);
# for any other current surface (pbuffers, EGL14-created surfaces) it returned NULL instead of what
# EGL reports, so apps comparing eglGetCurrentSurface(EGL_DRAW) with their surface re-made it current
# every frame (Waze's render loop). Fall back to the EGL answer when the table has no entry.
if ! grep -q "tl_current_surface_fallback" "$F"; then
python3 - "$F" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
old='''	EGLContext current_context = eglGetCurrentContext();
	if (readdraw == EGL_READ)
		return tl_table_lookup(read_surface_hashtable, current_context);
	else if (readdraw == EGL_DRAW)
		return tl_table_lookup(draw_surface_hashtable, current_context);

	return NULL;
}'''
new='''	EGLContext current_context = eglGetCurrentContext();
	EGLSurface surface = NULL; /* tl_current_surface_fallback */
	if (readdraw == EGL_READ)
		surface = tl_table_lookup(read_surface_hashtable, current_context);
	else if (readdraw == EGL_DRAW)
		surface = tl_table_lookup(draw_surface_hashtable, current_context);
	if (!surface)
		surface = eglGetCurrentSurface(readdraw);
	return surface;
}'''
assert old in s, "bionic_eglGetCurrentSurface body not found"
s=s.replace(old,new,1)
open(p,'w').write(s)
PY
fi
grep -c "tl_current_surface_fallback" "$F"
