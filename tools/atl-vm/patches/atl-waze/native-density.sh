#!/bin/sh
# teslalinux: the asset manager configuration density follows ATL_DENSITY_DPI (see DisplayMetrics),
# so density-qualified resources (drawable-xhdpi, @2x asset variants chosen by apps) match the
# density the Java side reports. Default stays 160 (mdpi). Idempotent.
set -eu
F=src/api-impl-jni/android_content_res_AssetManager.c
if ! grep -q "static int tl_density_dpi" "$F"; then
  cat > /tmp/tl_density.c <<'EOF'
/* teslalinux: ATL_DENSITY_DPI, the ro.sf.lcd_density of this runtime */
static int tl_density_dpi(void)
{
	const char *env = getenv("ATL_DENSITY_DPI");
	int dpi = env ? atoi(env) : 0;
	return dpi > 0 ? dpi : 160;
}

EOF
  awk -v helper=/tmp/tl_density.c '
    /^JNIEXPORT void JNICALL Java_android_content_res_AssetManager_init\(/ && !done { while ((getline line < helper) > 0) print line; done = 1 }
    { print }' "$F" > "$F.tmp" && mv "$F.tmp" "$F"
  grep -q "#include <stdlib.h>" "$F" || sed -i '1i #include <stdlib.h>' "$F"
fi
sed -i 's|\.density = /\*ACONFIGURATION_DENSITY_MEDIUM\*/ 160,|.density = tl_density_dpi(),|' "$F"
grep -c "tl_density_dpi" "$F"
