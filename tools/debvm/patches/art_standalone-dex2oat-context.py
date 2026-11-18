#!/usr/bin/env python3
"""teslalinux: art_standalone's first-launch dex2oat compiles an app for the class loader it runs in.

art_standalone (79ab0c15, "also compile non bootclasspath jars on first launch") runs dex2oat from
OatFileAssistant::GetBestOatFile() when a dex location has no up-to-date oat file, but without
--class-loader-context. dex2oat then records an empty context (PCL[]), while the app's class loader
has ATL's api-impl.jar (and ghax.jar) as its parent. OatFileManager's collision check rejects every
such file (an app that ships a framework class of its own, as Waze does with
android.animation.ValueAnimator$DurationScaleChangeListener, hits a duplicate), so ART extracts the
dex from the APK into memory and runs it without the compiled code, on every start: Waze 5.27 on a
Pi 4 throws away a 289 MB oat file it took 40 s to build, and verifies at run time instead. And
because the stale file still counts as up to date, it is never rebuilt.

This passes the loader's context to GetBestOatFile, gives it to dex2oat (as AOSP's installd does)
and decides whether to compile with OatFileInfo::GetDexOptNeeded, which also says "redo" when the
file was made for a different context, and "nothing to do" for an APK without code (resource
splits: dex2oat failed on each of them at every start). Idempotent; run from the art_standalone
source root.
"""
import sys


def patch(path, old, new, marker):
    s = open(path).read()
    if marker in s:
        return False
    if s.count(old) != 1:
        sys.exit(f"art_standalone-dex2oat-context: anchor not found once in {path}: {old[:60]!r}")
    open(path, 'w').write(s.replace(old, new, 1))
    return True


H = 'art/runtime/oat_file_assistant.h'
C = 'art/runtime/oat_file_assistant.cc'
M = 'art/runtime/oat_file_manager.cc'
MARK = 'teslalinux: class loader context'

done = []
done.append(patch(H,
    '  std::unique_ptr<OatFile> GetBestOatFile();\n',
    '  // teslalinux: class loader context of the loader the dex file is opened in (null: unknown)\n'
    '  std::unique_ptr<OatFile> GetBestOatFile(ClassLoaderContext* context = nullptr);\n',
    MARK))
done.append(patch(C,
    'std::unique_ptr<OatFile> OatFileAssistant::GetBestOatFile() {\n'
    '  // framework-res.apk contains no dex files.\n',
    'std::unique_ptr<OatFile> OatFileAssistant::GetBestOatFile(ClassLoaderContext* context) {\n'
    '  // framework-res.apk contains no dex files.\n',
    'GetBestOatFile(ClassLoaderContext* context) {'))
done.append(patch(C,
    '  if (!IsUpToDate() && !is_framework_res) {\n',
    '  // teslalinux: class loader context. Compile when the file is missing or out of date, or was made\n'
    '  // for another class loader context (the collision check would reject it); GetDexOptNeeded\n'
    '  // also says there is nothing to do for an APK without code.\n'
    '  bool compile = !is_framework_res && GetBestInfo().GetDexOptNeeded(CompilerFilter::kVerify,\n'
    '      /*profile_changed=*/ false, /*downgrade=*/ false, context, std::vector<int>()) !=\n'
    '      kNoDexOptNeeded;\n'
    '  if (compile) {\n',
    MARK))
done.append(patch(C,
    '    arg_vector.push_back(std::string("--boot-image=") + Runtime::Current()->GetImageLocation());\n',
    '    arg_vector.push_back(std::string("--boot-image=") + Runtime::Current()->GetImageLocation());\n'
    '    if (context != nullptr) {\n'
    '      // teslalinux: the loader\'s context, recorded in the oat file and checked when it is opened\n'
    '      arg_vector.push_back(std::string("--class-loader-context=") +\n'
    '                           context->EncodeContextForDex2oat(""));\n'
    '    }\n',
    'teslalinux: the loader'))
done.append(patch(M,
    '  std::unique_ptr<const OatFile> oat_file(oat_file_assistant.GetBestOatFile().release());\n',
    '  std::unique_ptr<const OatFile> oat_file(\n'
    '      oat_file_assistant.GetBestOatFile(context.get()).release());  // teslalinux: class loader context\n',
    MARK))
print("art_standalone-dex2oat-context: " + ("applied" if any(done) else "already applied"))
