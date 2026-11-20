#!/usr/bin/env python3
"""teslalinux: androidfw's C API frees what it hands out (art_standalone, libandroidfw).

ATL's AssetManager.destroy() needs two functions the C API lacks:
- AssetManager_delete: AssetManager_new returns `new GuardedAssetManager2()`, a class defined in
  androidfw_c_api.cpp, so only that file can delete it.
- ApkAssets_release: ApkAssets_load and ApkAssets_loadDir return ApkAssets::Load(...).release(),
  which leaves the caller one strong reference to drop with decStrong() (StrongPointer.h,
  sp::release). AssetManager2 keeps an ApkAssets only weakly between operations (AssetManager2.cpp,
  apk_assets_ and its ScopedOperation), so that reference is what keeps a loaded APK alive, and ATL
  drops it once no manager uses the APK any more.
It also stops AssetManager_setApkAssets leaking a heap-allocated std::span per call: SetApkAssets
copies the list (BuildDynamicRefTable), so a span on the stack does.

Idempotent; run from the art_standalone source root.
"""
H = 'libandroidfw/include/androidfw/androidfw_c_api.h'
C = 'libandroidfw/androidfw_c_api.cpp'
MARK = 'teslalinux: androidfw-free'


def patch(path, edits):
    s = open(path).read()
    if MARK in s:
        return False
    for old, new in edits:
        assert s.count(old) == 1, '%s: %r not found once' % (path, old[:60])
        s = s.replace(old, new, 1)
    open(path, 'w').write(s)
    return True


h = patch(H, [
    ('const struct ApkAssets *ApkAssets_loadDir(const char *path);\n',
     'const struct ApkAssets *ApkAssets_loadDir(const char *path);\n'
     '/* %s: drops the strong reference ApkAssets_load/ApkAssets_loadDir handed out */\n'
     'void ApkAssets_release(const struct ApkAssets *apk_assets);\n' % MARK),
    ('struct AssetManager *AssetManager_new(void);\n',
     'struct AssetManager *AssetManager_new(void);\n'
     '/* %s: deletes a manager from AssetManager_new (its themes stay valid to delete) */\n'
     'void AssetManager_delete(struct AssetManager *asset_manager);\n' % MARK),
])

c = patch(C, [
    ('''extern "C" struct AssetManager *AssetManager_new(void)
{
  GuardedAssetManager2 *assetManager = new GuardedAssetManager2();
  return reinterpret_cast<struct AssetManager *>(assetManager);
}
''', '''extern "C" struct AssetManager *AssetManager_new(void)
{
  GuardedAssetManager2 *assetManager = new GuardedAssetManager2();
  return reinterpret_cast<struct AssetManager *>(assetManager);
}

/* %s */
extern "C" void AssetManager_delete(struct AssetManager *asset_manager)
{
  delete reinterpret_cast<GuardedAssetManager2 *>(asset_manager);
}

/* %s: the reference sp::release() left to the caller (ApkAssets_load) */
extern "C" void ApkAssets_release(const struct ApkAssets *apk_assets)
{
  if (apk_assets)
    reinterpret_cast<const android::ApkAssets *>(apk_assets)->decStrong(nullptr);
}
''' % (MARK, MARK)),
    ('''  const GuardedAssetManager2::ApkAssetsList *apkAssets = new const GuardedAssetManager2::ApkAssetsList(apkAssets_vec.begin(), apkAssets_vec.size());
  auto op = assetManager->StartOperation();
  return assetManager->SetApkAssets(*apkAssets, invalidate_caches);
''', '''  /* %s: SetApkAssets copies the list; the span no longer leaks */
  auto op = assetManager->StartOperation();
  return assetManager->SetApkAssets(GuardedAssetManager2::ApkAssetsList(apkAssets_vec.data(), apkAssets_vec.size()), invalidate_caches);
''' % MARK),
])

print("art_standalone-androidfw-free: %s" % ("applied" if h or c else "already applied"))
