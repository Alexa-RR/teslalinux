/*
 * teslalinux: android.graphics.PathMeasure on GskPathMeasure, and path iteration for
 * android.graphics.Path (native-gfx-path.sh adds this file to the build).
 *
 * PathMeasure (Skia's SkContourMeasureIter): the path is split into its contours, zero-length
 * contours are skipped as Skia skips them, and with forceClosed every contour is measured as if
 * closed. getLength/getPosTan/getSegment work on the current contour; nextContour moves on.
 */
#include <math.h>
#include <stdlib.h>
#include <string.h>
#include <gsk/gsk.h>
#include <jni.h>

#include "../defines.h"

struct tl_measure {
	GPtrArray *contours; /* GskPath*, one contour each */
	guint idx;
	GskPathMeasure *measure; /* of contours[idx], or NULL past the end */
};

struct split_ctx {
	GPtrArray *paths;
	GskPathBuilder *b;
	gboolean has_geom;
	gboolean closed;
	gboolean force_closed;
};

static void split_flush(struct split_ctx *c)
{
	if (c->b) {
		if (c->has_geom) {
			if (c->force_closed && !c->closed)
				gsk_path_builder_close(c->b);
			g_ptr_array_add(c->paths, gsk_path_builder_free_to_path(c->b));
		} else {
			gsk_path_builder_unref(c->b);
		}
		c->b = NULL;
	}
	c->has_geom = FALSE;
	c->closed = FALSE;
}

static gboolean split_cb(GskPathOperation op, const graphene_point_t *pts, gsize n_pts, float weight, gpointer data)
{
	struct split_ctx *c = data;
	if (op == GSK_PATH_MOVE) {
		split_flush(c);
		c->b = gsk_path_builder_new();
		gsk_path_builder_move_to(c->b, pts[0].x, pts[0].y);
		return TRUE;
	}
	if (!c->b) {
		c->b = gsk_path_builder_new();
		gsk_path_builder_move_to(c->b, pts[0].x, pts[0].y);
	}
	switch (op) {
	case GSK_PATH_CLOSE:
		gsk_path_builder_close(c->b);
		c->closed = TRUE;
		break;
	case GSK_PATH_LINE:
		gsk_path_builder_line_to(c->b, pts[1].x, pts[1].y);
		c->has_geom = TRUE;
		break;
	case GSK_PATH_QUAD:
		gsk_path_builder_quad_to(c->b, pts[1].x, pts[1].y, pts[2].x, pts[2].y);
		c->has_geom = TRUE;
		break;
	case GSK_PATH_CUBIC:
		gsk_path_builder_cubic_to(c->b, pts[1].x, pts[1].y, pts[2].x, pts[2].y, pts[3].x, pts[3].y);
		c->has_geom = TRUE;
		break;
	case GSK_PATH_CONIC:
		gsk_path_builder_conic_to(c->b, pts[1].x, pts[1].y, pts[2].x, pts[2].y, weight);
		c->has_geom = TRUE;
		break;
	default:
		break;
	}
	return TRUE;
}

/* moves to the first contour at or after idx that has a length; FALSE past the last */
static gboolean measure_seek(struct tl_measure *m)
{
	if (m->measure) {
		gsk_path_measure_unref(m->measure);
		m->measure = NULL;
	}
	while (m->idx < m->contours->len) {
		GskPathMeasure *pm = gsk_path_measure_new(g_ptr_array_index(m->contours, m->idx));
		if (gsk_path_measure_get_length(pm) > 0.f) {
			m->measure = pm;
			return TRUE;
		}
		gsk_path_measure_unref(pm);
		m->idx++;
	}
	return FALSE;
}

JNIEXPORT jlong JNICALL Java_android_graphics_PathMeasure_native_1create(JNIEnv *env, jclass clazz, jlong path_ptr, jboolean force_closed)
{
	struct tl_measure *m = g_new0(struct tl_measure, 1);
	m->contours = g_ptr_array_new_with_free_func((GDestroyNotify)gsk_path_unref);
	if (path_ptr) {
		struct split_ctx c = { .paths = m->contours, .force_closed = force_closed };
		gsk_path_foreach(_PTR(path_ptr), GSK_PATH_FOREACH_ALLOW_QUAD | GSK_PATH_FOREACH_ALLOW_CUBIC | GSK_PATH_FOREACH_ALLOW_CONIC, split_cb, &c);
		split_flush(&c);
	}
	measure_seek(m);
	return _INTPTR(m);
}

JNIEXPORT void JNICALL Java_android_graphics_PathMeasure_native_1destroy(JNIEnv *env, jclass clazz, jlong ptr)
{
	struct tl_measure *m = _PTR(ptr);
	if (!m)
		return;
	if (m->measure)
		gsk_path_measure_unref(m->measure);
	g_ptr_array_unref(m->contours);
	g_free(m);
}

JNIEXPORT jfloat JNICALL Java_android_graphics_PathMeasure_native_1get_1length(JNIEnv *env, jclass clazz, jlong ptr)
{
	struct tl_measure *m = _PTR(ptr);
	return (m && m->measure) ? gsk_path_measure_get_length(m->measure) : 0.f;
}

JNIEXPORT jboolean JNICALL Java_android_graphics_PathMeasure_native_1is_1closed(JNIEnv *env, jclass clazz, jlong ptr)
{
	struct tl_measure *m = _PTR(ptr);
	return (m && m->measure) ? gsk_path_is_closed(gsk_path_measure_get_path(m->measure)) : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_android_graphics_PathMeasure_native_1next_1contour(JNIEnv *env, jclass clazz, jlong ptr)
{
	struct tl_measure *m = _PTR(ptr);
	if (!m || !m->measure)
		return JNI_FALSE;
	m->idx++;
	return measure_seek(m);
}

/* Skia pins the distance into [0, length]; pos/tan may be null */
JNIEXPORT jboolean JNICALL Java_android_graphics_PathMeasure_native_1get_1pos_1tan(JNIEnv *env, jclass clazz, jlong ptr, jfloat distance, jfloatArray pos_arr, jfloatArray tan_arr)
{
	struct tl_measure *m = _PTR(ptr);
	if (!m || !m->measure || isnan(distance))
		return JNI_FALSE;
	float len = gsk_path_measure_get_length(m->measure);
	if (distance < 0.f)
		distance = 0.f;
	if (distance > len)
		distance = len;
	GskPathPoint point;
	if (!gsk_path_measure_get_point(m->measure, distance, &point))
		return JNI_FALSE;
	GskPath *path = gsk_path_measure_get_path(m->measure);
	if (pos_arr) {
		graphene_point_t p;
		gsk_path_point_get_position(&point, path, &p);
		jfloat v[2] = { p.x, p.y };
		(*env)->SetFloatArrayRegion(env, pos_arr, 0, 2, v);
	}
	if (tan_arr) {
		graphene_vec2_t t;
		/* at the end of the contour the tangent of the segment arriving there, else the one leaving */
		gsk_path_point_get_tangent(&point, path, distance >= len ? GSK_PATH_FROM_START : GSK_PATH_TO_END, &t);
		jfloat v[2] = { graphene_vec2_get_x(&t), graphene_vec2_get_y(&t) };
		(*env)->SetFloatArrayRegion(env, tan_arr, 0, 2, v);
	}
	return JNI_TRUE;
}

struct seg_ctx {
	GskPathBuilder *dst;
	gboolean move_to;
	gboolean first;
};

static gboolean seg_cb(GskPathOperation op, const graphene_point_t *pts, gsize n_pts, float weight, gpointer data)
{
	struct seg_ctx *c = data;
	switch (op) {
	case GSK_PATH_MOVE:
		/* Skia: without startWithMoveTo the segment continues from dst's last point */
		if (!c->first || c->move_to)
			gsk_path_builder_move_to(c->dst, pts[0].x, pts[0].y);
		break;
	case GSK_PATH_CLOSE:
		gsk_path_builder_close(c->dst);
		break;
	case GSK_PATH_LINE:
		gsk_path_builder_line_to(c->dst, pts[1].x, pts[1].y);
		break;
	case GSK_PATH_QUAD:
		gsk_path_builder_quad_to(c->dst, pts[1].x, pts[1].y, pts[2].x, pts[2].y);
		break;
	case GSK_PATH_CUBIC:
		gsk_path_builder_cubic_to(c->dst, pts[1].x, pts[1].y, pts[2].x, pts[2].y, pts[3].x, pts[3].y);
		break;
	case GSK_PATH_CONIC:
		gsk_path_builder_conic_to(c->dst, pts[1].x, pts[1].y, pts[2].x, pts[2].y, weight);
		break;
	default:
		break;
	}
	c->first = FALSE;
	return TRUE;
}

/* appends the piece [start, stop] of the current contour to the builder (the caller checked start < stop) */
JNIEXPORT jboolean JNICALL Java_android_graphics_PathMeasure_native_1get_1segment(JNIEnv *env, jclass clazz, jlong ptr, jfloat start, jfloat stop, jlong builder_ptr, jboolean move_to)
{
	struct tl_measure *m = _PTR(ptr);
	if (!m || !m->measure || !builder_ptr)
		return JNI_FALSE;
	GskPathPoint p0, p1;
	if (!gsk_path_measure_get_point(m->measure, start, &p0) || !gsk_path_measure_get_point(m->measure, stop, &p1))
		return JNI_FALSE;
	GskPathBuilder *tmp = gsk_path_builder_new();
	gsk_path_builder_add_segment(tmp, gsk_path_measure_get_path(m->measure), &p0, &p1);
	GskPath *seg = gsk_path_builder_free_to_path(tmp);
	struct seg_ctx c = { .dst = _PTR(builder_ptr), .move_to = move_to, .first = TRUE };
	gsk_path_foreach(seg, GSK_PATH_FOREACH_ALLOW_QUAD | GSK_PATH_FOREACH_ALLOW_CUBIC | GSK_PATH_FOREACH_ALLOW_CONIC, seg_cb, &c);
	gsk_path_unref(seg);
	return JNI_TRUE;
}

/* ---- Path iteration --------------------------------------------------------------------------- */

/* every operation as 10 floats: GskPathOperation, conic weight, then up to 4 points (x, y) */
static gboolean ops_cb(GskPathOperation op, const graphene_point_t *pts, gsize n_pts, float weight, gpointer data)
{
	GArray *a = data;
	float rec[10] = { (float)op, weight, 0, 0, 0, 0, 0, 0, 0, 0 };
	for (gsize i = 0; i < n_pts && i < 4; i++) {
		rec[2 + 2 * i] = pts[i].x;
		rec[3 + 2 * i] = pts[i].y;
	}
	g_array_append_vals(a, rec, 10);
	return TRUE;
}

JNIEXPORT jfloatArray JNICALL Java_android_graphics_Path_native_1get_1ops(JNIEnv *env, jclass clazz, jlong path_ptr)
{
	GArray *a = g_array_new(FALSE, FALSE, sizeof(float));
	if (path_ptr)
		gsk_path_foreach(_PTR(path_ptr), GSK_PATH_FOREACH_ALLOW_QUAD | GSK_PATH_FOREACH_ALLOW_CUBIC | GSK_PATH_FOREACH_ALLOW_CONIC, ops_cb, a);
	jfloatArray out = (*env)->NewFloatArray(env, a->len);
	if (a->len)
		(*env)->SetFloatArrayRegion(env, out, 0, a->len, (const jfloat *)a->data);
	g_array_free(a, TRUE);
	return out;
}

/* adds the path's contours with reversed direction (Path.Direction.CCW shapes) */
JNIEXPORT void JNICALL Java_android_graphics_Path_native_1add_1reverse_1path(JNIEnv *env, jclass clazz, jlong builder_ptr, jlong path_ptr)
{
	if (path_ptr)
		gsk_path_builder_add_reverse_path(_PTR(builder_ptr), _PTR(path_ptr));
}

/* Path.conicTo (API 34) */
JNIEXPORT void JNICALL Java_android_graphics_Path_native_1conic_1to(JNIEnv *env, jclass clazz, jlong builder_ptr, jfloat x1, jfloat y1, jfloat x2, jfloat y2, jfloat weight)
{
	gsk_path_builder_conic_to(_PTR(builder_ptr), x1, y1, x2, y2, weight);
}
