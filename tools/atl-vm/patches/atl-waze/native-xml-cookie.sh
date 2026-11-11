#!/bin/sh
# teslalinux: AssetManager.openXmlResourceParser(cookie, file) must open the file from the APK that
# the cookie names (AOSP AssetManager2::OpenNonAsset(filename, cookie)). ATL's openXmlAssetNative
# ignores the cookie and resolves by name across every asset path, which breaks split APKs: base.apk
# and each split_config.*.apk all carry an AndroidManifest.xml, and the last one added won. The C
# libandroidfw API has no cookie-aware open, so the Java side reads the entry from that APK itself
# and hands the bytes to this native, which builds the ResXMLTree from the buffer.
set -eu
F=src/api-impl-jni/android_content_res_AssetManager.c
if ! grep -q "openXmlAssetFromBufferNative" "$F"; then
cat >> "$F" <<'EOF'

/* teslalinux: build an XML block from an in-memory copy of a compiled XML (used when the cookie
 * selects a specific APK, see AssetManager.openXmlBlockAsset) */
JNIEXPORT jlong JNICALL Java_android_content_res_AssetManager_openXmlAssetFromBufferNative(JNIEnv *env, jobject this, jbyteArray data)
{
	jsize len = (*env)->GetArrayLength(env, data);
	jbyte *bytes = (*env)->GetByteArrayElements(env, data, NULL);
	struct ResXMLTree *res_xml = ResXMLTree_new();
	ResXMLTree_setTo(res_xml, bytes, len, true); // copyData: the tree owns its copy
	(*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
	return _INTPTR(res_xml);
}
EOF
fi
grep -c "openXmlAssetFromBufferNative" "$F"
