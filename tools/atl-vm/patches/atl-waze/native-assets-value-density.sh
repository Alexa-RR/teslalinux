#!/bin/sh
# teslalinux: TypedValue.density and changingConfigurations from the asset manager (AOSP CopyValue).
# ATL's loadResourceValue and retrieveArray never wrote the density of the configuration the value was
# picked from, so Resources.getDrawable(id) (ContextCompat, AppCompat's TintTypedArray, every
# getDrawable by id) saw density 0, which Java treats as mdpi: a drawable-xhdpi nine-patch or bitmap
# was then scaled from 160 instead of 320 dpi and came out twice its size (AppCompat's switch track:
# 59x40 instead of 29x20 at 200 dpi). Attribute lookups (ApplyStyle/ResolveAttrs) already filled
# STYLE_DENSITY, which is why drawables inflated from XML attributes were right.
# AOSP android_util_AssetManager.cpp: NativeGetResourceValue copies the selected config's density and
# the type spec flags; NativeGetResourceArray writes STYLE_CHANGING_CONFIGURATIONS and STYLE_DENSITY
# with the density starting at 0 for values that are not references. Theme attribute lookups pass no
# config in AOSP, so they leave the density alone here too. Idempotent.
set -eu
F=src/api-impl-jni/android_content_res_AssetManager.c
if ! grep -q "tl_value_density" "$F"; then
python3 - "$F" <<'EOF'
import sys
f = sys.argv[1]
s = open(f).read()

def func_span(src, name):
    start = src.index("JNICALL " + name + "(")
    end = src.index("\n}\n", start)
    return start, end

# loadResourceValue: density of the selected configuration and the type spec flags
a, b = func_span(s, "Java_android_content_res_AssetManager_loadResourceValue")
body = s[a:b]
anchor = '\t\t_SET_INT_FIELD(outValue, "assetCookie", JAVA_COOKIE(cookie));\n'
assert body.count(anchor) == 1, "loadResourceValue anchor"
body = body.replace(anchor, anchor +
    '\t\t/* tl_value_density: AOSP CopyValue with the selected config */\n'
    '\t\t_SET_INT_FIELD(outValue, "changingConfigurations", outSpecFlags);\n'
    '\t\t_SET_INT_FIELD(outValue, "density", outConfig.density);\n')
s = s[:a] + body + s[b:]

# retrieveArray: STYLE_CHANGING_CONFIGURATIONS and STYLE_DENSITY like NativeGetResourceArray
a, b = func_span(s, "Java_android_content_res_AssetManager_retrieveArray")
body = s[a:b]
decl = '\t\tstruct ResTable_config outConfig;\n'
assert body.count(decl) == 1, "retrieveArray config decl"
body = body.replace(decl, decl + '\t\toutConfig.density = 0; /* tl_value_density: a plain value has no config */\n')
flags = '\t\tuint32_t outSpecFlags;\n'
assert body.count(flags) == 1, "retrieveArray flags decl"
body = body.replace(flags, '\t\tuint32_t outSpecFlags = bag->type_spec_flags;\n')
anchor = '\t\tarray[i * STYLE_NUM_ENTRIES + STYLE_RESOURCE_ID] = ref;\n'
assert body.count(anchor) == 1, "retrieveArray anchor"
body = body.replace(anchor, anchor +
    '\t\tarray[i * STYLE_NUM_ENTRIES + STYLE_CHANGING_CONFIGURATIONS] = outSpecFlags;\n'
    '\t\tarray[i * STYLE_NUM_ENTRIES + STYLE_DENSITY] = outConfig.density;\n')
s = s[:a] + body + s[b:]
open(f, "w").write(s)
EOF
fi
grep -c "tl_value_density" "$F"
