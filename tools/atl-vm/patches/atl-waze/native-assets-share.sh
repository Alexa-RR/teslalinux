#!/bin/sh
# teslalinux: AssetManager siblings over shared ApkAssets (tl_assets_share). AOSP gives every
# Resources (per configuration context) its own AssetManager2 while the loaded APK data is shared;
# ATL shared one native AssetManager between all Resources, so each updateConfiguration (an
# activity's night-mode override, the app's, the window's) overwrote the others and resources
# resolved against whichever configuration was set last.
# native_setApkAssets keeps the loaded ApkAssets array; native_shareApkAssets puts it under a new
# manager. Run by build-atl.sh from ~/atl-src. Idempotent.
set -eu
A=src/api-impl-jni/android_content_res_AssetManager.c
if ! grep -q "tl_assets_share" "$A"; then
python3 - "$A" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
old='''	AssetManager_setApkAssets(asset_manager, apk_assets, num_assets, true, true);
}
'''
new='''	AssetManager_setApkAssets(asset_manager, apk_assets, num_assets, true, true);
	/* tl_assets_share: keep the loaded ApkAssets so sibling managers can share them */
	const struct ApkAssets **keep = malloc(sizeof(*keep) * (num_assets > 0 ? num_assets : 1));
	memcpy(keep, apk_assets, sizeof(*keep) * num_assets);
	_SET_LONG_FIELD(this, "tlApkAssets", _INTPTR(keep));
	_SET_INT_FIELD(this, "tlApkAssetsCount", num_assets);
}

JNIEXPORT void JNICALL Java_android_content_res_AssetManager_native_1shareApkAssets(JNIEnv *env, jobject this, jlong apk_assets_ptr, jint num_assets)
{
	struct AssetManager *asset_manager = _PTR(_GET_LONG_FIELD(this, "mObject"));
	AM_SCOPEDLOCK(asset_manager)
	const struct ApkAssets **apk_assets = _PTR(apk_assets_ptr);
	AssetManager_setApkAssets(asset_manager, apk_assets, num_assets, true, true);
	_SET_LONG_FIELD(this, "tlApkAssets", apk_assets_ptr);
	_SET_INT_FIELD(this, "tlApkAssetsCount", num_assets);
}
'''
assert s.count(old)==1, s.count(old)
open(p,'w').write(s.replace(old,new,1))
PY
fi
grep -c "tl_assets_share" "$A"
