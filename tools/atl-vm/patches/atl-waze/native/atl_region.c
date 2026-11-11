/* teslalinux: android.graphics.Region on cairo regions.
 *
 * AOSP's Region is an SkRegion: a set of non-overlapping integer rectangles with union,
 * intersection, difference and xor, built from rectangles or from a rasterised path. cairo_region_t
 * is the same data structure (sorted y-x bands of integer rectangles), so every operation maps
 * directly; paths are rasterised with cairo and turned into spans. */
#include <math.h>
#include <stdio.h>
#include <string.h>
#include <cairo.h>
#include <gsk/gsk.h>
#include <jni.h>

#include "../defines.h"
#include "../util.h"

static inline cairo_region_t *region_of(jlong ptr)
{
	return (cairo_region_t *)_PTR(ptr);
}

JNIEXPORT jlong JNICALL Java_android_graphics_Region_nativeConstructor(JNIEnv *env, jclass clazz)
{
	return _INTPTR(cairo_region_create());
}

JNIEXPORT void JNICALL Java_android_graphics_Region_nativeDestructor(JNIEnv *env, jclass clazz, jlong region)
{
	if (region)
		cairo_region_destroy(region_of(region));
}

static void region_assign(cairo_region_t *dst, cairo_region_t *src)
{
	cairo_rectangle_int_t extents;
	cairo_region_get_extents(dst, &extents);
	cairo_region_subtract_rectangle(dst, &extents);
	cairo_region_union(dst, src);
}

JNIEXPORT void JNICALL Java_android_graphics_Region_nativeSetRegion(JNIEnv *env, jclass clazz, jlong dst, jlong src)
{
	region_assign(region_of(dst), region_of(src));
}

JNIEXPORT jboolean JNICALL Java_android_graphics_Region_nativeSetRect(JNIEnv *env, jclass clazz, jlong dst, jint left, jint top, jint right, jint bottom)
{
	cairo_region_t *region = region_of(dst);
	cairo_rectangle_int_t extents;
	cairo_region_get_extents(region, &extents);
	cairo_region_subtract_rectangle(region, &extents);
	if (right <= left || bottom <= top)
		return JNI_FALSE;
	cairo_rectangle_int_t rect = { left, top, right - left, bottom - top };
	cairo_region_union_rectangle(region, &rect);
	return JNI_TRUE;
}

/* AOSP setPath: the region becomes the pixels inside the path, intersected with the clip region. */
JNIEXPORT jboolean JNICALL Java_android_graphics_Region_nativeSetPath(JNIEnv *env, jclass clazz, jlong dst, jlong path_ptr, jlong clip_ptr)
{
	cairo_region_t *region = region_of(dst);
	cairo_region_t *clip = region_of(clip_ptr);
	GskPath *path = (GskPath *)_PTR(path_ptr);
	cairo_rectangle_int_t extents;
	cairo_region_get_extents(region, &extents);
	cairo_region_subtract_rectangle(region, &extents);
	if (!path || !clip || cairo_region_is_empty(clip))
		return JNI_FALSE;
	graphene_rect_t bounds;
	if (!gsk_path_get_bounds(path, &bounds))
		return JNI_FALSE;
	cairo_rectangle_int_t clip_extents;
	cairo_region_get_extents(clip, &clip_extents);
	int x0 = (int)floorf(bounds.origin.x), y0 = (int)floorf(bounds.origin.y);
	int x1 = (int)ceilf(bounds.origin.x + bounds.size.width), y1 = (int)ceilf(bounds.origin.y + bounds.size.height);
	if (x0 < clip_extents.x) x0 = clip_extents.x;
	if (y0 < clip_extents.y) y0 = clip_extents.y;
	if (x1 > clip_extents.x + clip_extents.width) x1 = clip_extents.x + clip_extents.width;
	if (y1 > clip_extents.y + clip_extents.height) y1 = clip_extents.y + clip_extents.height;
	if (x1 <= x0 || y1 <= y0)
		return JNI_FALSE;
	int width = x1 - x0, height = y1 - y0;
	cairo_surface_t *surface = cairo_image_surface_create(CAIRO_FORMAT_A8, width, height);
	cairo_t *cr = cairo_create(surface);
	cairo_translate(cr, -x0, -y0);
	gsk_path_to_cairo(path, cr);
	cairo_set_fill_rule(cr, CAIRO_FILL_RULE_WINDING);
	cairo_set_antialias(cr, CAIRO_ANTIALIAS_NONE);
	cairo_set_source_rgba(cr, 0, 0, 0, 1);
	cairo_fill(cr);
	cairo_destroy(cr);
	cairo_surface_flush(surface);
	unsigned char *data = cairo_image_surface_get_data(surface);
	int stride = cairo_image_surface_get_stride(surface);
	for (int y = 0; y < height; y++) {
		unsigned char *row = data + y * stride;
		int x = 0;
		while (x < width) {
			while (x < width && row[x] < 128)
				x++;
			int start = x;
			while (x < width && row[x] >= 128)
				x++;
			if (x > start) {
				cairo_rectangle_int_t span = { x0 + start, y0 + y, x - start, 1 };
				cairo_region_union_rectangle(region, &span);
			}
		}
	}
	cairo_surface_destroy(surface);
	cairo_region_intersect(region, clip);
	return !cairo_region_is_empty(region);
}

JNIEXPORT jboolean JNICALL Java_android_graphics_Region_isEmpty(JNIEnv *env, jobject this)
{
	return cairo_region_is_empty(region_of(_GET_LONG_FIELD(this, "mNativeRegion")));
}

JNIEXPORT jboolean JNICALL Java_android_graphics_Region_isRect(JNIEnv *env, jobject this)
{
	return cairo_region_num_rectangles(region_of(_GET_LONG_FIELD(this, "mNativeRegion"))) == 1;
}

JNIEXPORT jboolean JNICALL Java_android_graphics_Region_isComplex(JNIEnv *env, jobject this)
{
	return cairo_region_num_rectangles(region_of(_GET_LONG_FIELD(this, "mNativeRegion"))) > 1;
}

JNIEXPORT jboolean JNICALL Java_android_graphics_Region_contains(JNIEnv *env, jobject this, jint x, jint y)
{
	return cairo_region_contains_point(region_of(_GET_LONG_FIELD(this, "mNativeRegion")), x, y);
}

JNIEXPORT jboolean JNICALL Java_android_graphics_Region_quickContains(JNIEnv *env, jobject this, jint left, jint top, jint right, jint bottom)
{
	cairo_region_t *region = region_of(_GET_LONG_FIELD(this, "mNativeRegion"));
	/* AOSP: true only when the region is a single rectangle containing the rectangle */
	if (cairo_region_num_rectangles(region) != 1 || right <= left || bottom <= top)
		return JNI_FALSE;
	cairo_rectangle_int_t rect = { left, top, right - left, bottom - top };
	return cairo_region_contains_rectangle(region, &rect) == CAIRO_REGION_OVERLAP_IN;
}

JNIEXPORT jboolean JNICALL Java_android_graphics_Region_quickReject__IIII(JNIEnv *env, jobject this, jint left, jint top, jint right, jint bottom)
{
	cairo_region_t *region = region_of(_GET_LONG_FIELD(this, "mNativeRegion"));
	if (right <= left || bottom <= top || cairo_region_is_empty(region))
		return JNI_TRUE;
	cairo_rectangle_int_t rect = { left, top, right - left, bottom - top };
	return cairo_region_contains_rectangle(region, &rect) == CAIRO_REGION_OVERLAP_OUT;
}

JNIEXPORT jboolean JNICALL Java_android_graphics_Region_quickReject__Landroid_graphics_Region_2(JNIEnv *env, jobject this, jobject other)
{
	cairo_region_t *region = region_of(_GET_LONG_FIELD(this, "mNativeRegion"));
	if (!other)
		return JNI_TRUE;
	cairo_region_t *rgn = region_of(_GET_LONG_FIELD(other, "mNativeRegion"));
	if (cairo_region_is_empty(region) || cairo_region_is_empty(rgn))
		return JNI_TRUE;
	cairo_rectangle_int_t a, b;
	cairo_region_get_extents(region, &a);
	cairo_region_get_extents(rgn, &b);
	return a.x + a.width <= b.x || b.x + b.width <= a.x || a.y + a.height <= b.y || b.y + b.height <= a.y;
}

JNIEXPORT void JNICALL Java_android_graphics_Region_translate(JNIEnv *env, jobject this, jint dx, jint dy, jobject dst)
{
	cairo_region_t *region = region_of(_GET_LONG_FIELD(this, "mNativeRegion"));
	if (!dst) {
		cairo_region_translate(region, dx, dy);
		return;
	}
	cairo_region_t *copy = cairo_region_copy(region);
	cairo_region_translate(copy, dx, dy);
	region_assign(region_of(_GET_LONG_FIELD(dst, "mNativeRegion")), copy);
	cairo_region_destroy(copy);
}

JNIEXPORT void JNICALL Java_android_graphics_Region_scale(JNIEnv *env, jobject this, jfloat scale, jobject dst)
{
	cairo_region_t *region = region_of(_GET_LONG_FIELD(this, "mNativeRegion"));
	cairo_region_t *scaled = cairo_region_create();
	int n = cairo_region_num_rectangles(region);
	for (int i = 0; i < n; i++) {
		cairo_rectangle_int_t r;
		cairo_region_get_rectangle(region, i, &r);
		int left = (int)roundf(r.x * scale), top = (int)roundf(r.y * scale);
		int right = (int)roundf((r.x + r.width) * scale), bottom = (int)roundf((r.y + r.height) * scale);
		if (right > left && bottom > top) {
			cairo_rectangle_int_t s = { left, top, right - left, bottom - top };
			cairo_region_union_rectangle(scaled, &s);
		}
	}
	region_assign(dst ? region_of(_GET_LONG_FIELD(dst, "mNativeRegion")) : region, scaled);
	cairo_region_destroy(scaled);
}

JNIEXPORT jboolean JNICALL Java_android_graphics_Region_nativeEquals(JNIEnv *env, jclass clazz, jlong r1, jlong r2)
{
	return cairo_region_equal(region_of(r1), region_of(r2));
}

/* AOSP Region.Op order: DIFFERENCE, INTERSECT, UNION, XOR, REVERSE_DIFFERENCE, REPLACE */
static jboolean region_op(cairo_region_t *dst, cairo_region_t *a, cairo_region_t *b, jint op)
{
	cairo_region_t *result = cairo_region_copy(a);
	switch (op) {
	case 0: cairo_region_subtract(result, b); break;
	case 1: cairo_region_intersect(result, b); break;
	case 2: cairo_region_union(result, b); break;
	case 3: cairo_region_xor(result, b); break;
	case 4: { cairo_region_t *rev = cairo_region_copy(b); cairo_region_subtract(rev, a); cairo_region_destroy(result); result = rev; break; }
	case 5: cairo_region_destroy(result); result = cairo_region_copy(b); break;
	default: cairo_region_destroy(result); return JNI_FALSE;
	}
	region_assign(dst, result);
	jboolean non_empty = !cairo_region_is_empty(result);
	cairo_region_destroy(result);
	return non_empty;
}

JNIEXPORT jboolean JNICALL Java_android_graphics_Region_nativeOp__JIIIII(JNIEnv *env, jclass clazz, jlong dst, jint left, jint top, jint right, jint bottom, jint op)
{
	cairo_region_t *rect_region = cairo_region_create();
	if (right > left && bottom > top) {
		cairo_rectangle_int_t rect = { left, top, right - left, bottom - top };
		cairo_region_union_rectangle(rect_region, &rect);
	}
	cairo_region_t *self = region_of(dst);
	jboolean r = region_op(self, self, rect_region, op);
	cairo_region_destroy(rect_region);
	return r;
}

JNIEXPORT jboolean JNICALL Java_android_graphics_Region_nativeOp__JLandroid_graphics_Rect_2JI(JNIEnv *env, jclass clazz, jlong dst, jobject rect, jlong region, jint op)
{
	cairo_region_t *rect_region = cairo_region_create();
	jint left = _GET_INT_FIELD(rect, "left"), top = _GET_INT_FIELD(rect, "top");
	jint right = _GET_INT_FIELD(rect, "right"), bottom = _GET_INT_FIELD(rect, "bottom");
	if (right > left && bottom > top) {
		cairo_rectangle_int_t r = { left, top, right - left, bottom - top };
		cairo_region_union_rectangle(rect_region, &r);
	}
	jboolean r = region_op(region_of(dst), rect_region, region_of(region), op);
	cairo_region_destroy(rect_region);
	return r;
}

JNIEXPORT jboolean JNICALL Java_android_graphics_Region_nativeOp__JJJI(JNIEnv *env, jclass clazz, jlong dst, jlong region1, jlong region2, jint op)
{
	return region_op(region_of(dst), region_of(region1), region_of(region2), op);
}

JNIEXPORT jboolean JNICALL Java_android_graphics_Region_nativeGetBounds(JNIEnv *env, jclass clazz, jlong region, jobject rect)
{
	cairo_rectangle_int_t extents;
	cairo_region_get_extents(region_of(region), &extents);
	_SET_INT_FIELD(rect, "left", extents.x);
	_SET_INT_FIELD(rect, "top", extents.y);
	_SET_INT_FIELD(rect, "right", extents.x + extents.width);
	_SET_INT_FIELD(rect, "bottom", extents.y + extents.height);
	return !cairo_region_is_empty(region_of(region));
}

JNIEXPORT jint JNICALL Java_android_graphics_Region_nativeGetRectCount(JNIEnv *env, jclass clazz, jlong region)
{
	return cairo_region_num_rectangles(region_of(region));
}

JNIEXPORT jboolean JNICALL Java_android_graphics_Region_nativeGetRect(JNIEnv *env, jclass clazz, jlong region, jint index, jobject rect)
{
	cairo_region_t *r = region_of(region);
	if (index < 0 || index >= cairo_region_num_rectangles(r))
		return JNI_FALSE;
	cairo_rectangle_int_t ri;
	cairo_region_get_rectangle(r, index, &ri);
	_SET_INT_FIELD(rect, "left", ri.x);
	_SET_INT_FIELD(rect, "top", ri.y);
	_SET_INT_FIELD(rect, "right", ri.x + ri.width);
	_SET_INT_FIELD(rect, "bottom", ri.y + ri.height);
	return JNI_TRUE;
}

JNIEXPORT jstring JNICALL Java_android_graphics_Region_nativeToString(JNIEnv *env, jclass clazz, jlong region)
{
	cairo_region_t *r = region_of(region);
	int n = cairo_region_num_rectangles(r);
	GString *s = g_string_new("Region(");
	for (int i = 0; i < n; i++) {
		cairo_rectangle_int_t ri;
		cairo_region_get_rectangle(r, i, &ri);
		g_string_append_printf(s, "%s[%d,%d,%d,%d]", i ? ", " : "", ri.x, ri.y, ri.x + ri.width, ri.y + ri.height);
	}
	g_string_append(s, ")");
	jstring result = (*env)->NewStringUTF(env, s->str);
	g_string_free(s, TRUE);
	return result;
}
