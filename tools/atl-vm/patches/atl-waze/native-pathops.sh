#!/bin/sh
# teslalinux: boolean path operations (android.graphics.Path.op) on flattened GskPaths with the
# Martinez-Rueda-Feito algorithm (api-impl-jni/graphics/atl_pathops.c) plus the JNI entry in
# android_graphics_Path.c. Run by build-atl.sh from ~/atl-src; the C file is pushed to
# ~/atl-native-patches/files/. Idempotent.
set -eu
cp "$HOME/atl-native-patches/files/atl_pathops.c" src/api-impl-jni/graphics/atl_pathops.c
if ! grep -q "api-impl-jni/graphics/atl_pathops.c" meson.build; then
  sed -i "s|'src/api-impl-jni/egl/com_google_android_gles_jni_EGLImpl.c',|'src/api-impl-jni/egl/com_google_android_gles_jni_EGLImpl.c',\n\t'src/api-impl-jni/graphics/atl_pathops.c',|" meson.build
fi
grep -c "atl_pathops" meson.build

P=src/api-impl-jni/graphics/android_graphics_Path.c
if ! grep -q "native_1op" "$P"; then
cat >> "$P" <<'EOF'

/* ---- teslalinux: Path.op ------------------------------------------------------------------------ */
GskPath *atl_path_op(GskPath *path1, GskPath *path2, int op);

/* returns a new GskPath (owned by the caller) with path1 <op> path2, or 0 */
JNIEXPORT jlong JNICALL Java_android_graphics_Path_native_1op(JNIEnv *env, jclass this, jlong path1_ptr, jlong path2_ptr, jint op)
{
	GskPath *path1 = _PTR(path1_ptr), *path2 = _PTR(path2_ptr);
	if (!path1 || !path2)
		return 0;
	return _INTPTR(atl_path_op(path1, path2, op));
}

/* AOSP Path.isEmpty: no lines or curves in the path */
JNIEXPORT jboolean JNICALL Java_android_graphics_Path_native_1is_1empty(JNIEnv *env, jclass this, jlong path_ptr)
{
	GskPath *path = _PTR(path_ptr);
	return path == NULL || gsk_path_is_empty(path);
}
EOF
fi
grep -c "native_1op" "$P"
