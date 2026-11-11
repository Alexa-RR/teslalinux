#!/bin/sh
# teslalinux: Bitmap.getPixels of a sub-rectangle (tl_getpixels_rect). AOSP copies any rectangle of
# the bitmap into the caller's array at the given offset and stride; ATL printed "partial read not
# supported" and called exit(1), which ended the process on the first app that reads a bitmap
# region. The texture is downloaded whole once and the rectangle copied row by row. Run by
# build-atl.sh from ~/atl-src. Idempotent.
set -eu
B=src/api-impl-jni/graphics/android_graphics_Bitmap.c
if ! grep -q "tl_getpixels_rect" "$B"; then
python3 - "$B" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
old='''	GdkTexture *texture = GDK_TEXTURE(_PTR(texture_ptr));
	if (x != 0 || y != 0 || width != gdk_texture_get_width(texture) || height != gdk_texture_get_height(texture)) {
		printf("Bitmap.readPixels: partial read not supported\\n");
		exit(1);
	}
	jint *array = (*env)->GetIntArrayElements(env, pixels, NULL);
	gdk_texture_download(texture, (guchar *)(array + offset), stride * 4);
	(*env)->ReleaseIntArrayElements(env, pixels, array, 0);
}
'''
new='''	GdkTexture *texture = GDK_TEXTURE(_PTR(texture_ptr));
	int tex_width = gdk_texture_get_width(texture);
	int tex_height = gdk_texture_get_height(texture);
	jint *array = (*env)->GetIntArrayElements(env, pixels, NULL);
	if (x == 0 && y == 0 && width == tex_width && height == tex_height) {
		gdk_texture_download(texture, (guchar *)(array + offset), stride * 4);
	} else {
		/* tl_getpixels_rect: AOSP copies the rectangle into the array at offset with the given stride */
		if (x < 0 || y < 0 || width < 0 || height < 0 || x + width > tex_width || y + height > tex_height) {
			(*env)->ReleaseIntArrayElements(env, pixels, array, 0);
			jclass iae = (*env)->FindClass(env, "java/lang/IllegalArgumentException");
			(*env)->ThrowNew(env, iae, "x + width and y + height must be <= bitmap dimensions");
			return;
		}
		guchar *whole = g_malloc((gsize)tex_width * tex_height * 4);
		gdk_texture_download(texture, whole, tex_width * 4);
		for (int row = 0; row < height; row++)
			memcpy(array + offset + (gsize)row * stride, whole + ((gsize)(y + row) * tex_width + x) * 4, (gsize)width * 4);
		g_free(whole);
	}
	(*env)->ReleaseIntArrayElements(env, pixels, array, 0);
}
'''
assert s.count(old)==1, s.count(old)
s=s.replace(old,new,1)
if '#include <string.h>' not in s:
    s='#include <string.h>\n'+s
open(p,'w').write(s)
PY
fi
grep -c "tl_getpixels_rect" "$B"
