/*
 * teslalinux: android.graphics canvas features on GSK that ATL's GskCanvas lacked
 * (native-gfx-canvas.sh adds this file to the build).
 *
 *  - layers: Canvas.saveLayer records into a fresh GtkSnapshot; on restore the layer node goes into
 *    the parent with the layer paint's alpha and colour filter (Skia's saveLayer semantics).
 *  - transfer modes: a draw whose paint has an Xfermode/BlendMode other than SRC_OVER is composed
 *    with the content recorded so far in the current layer (Skia: result = lerp(dst, mode(src, dst),
 *    coverage)). GSK mask and blend nodes express the Porter-Duff and separable modes.
 *  - shaders: LinearGradient, RadialGradient and SweepGradient as GSK gradient nodes with Skia's
 *    tile modes and Android's premultiplied colour interpolation; BitmapShader tiling helpers.
 *  - mask filters (BlurMaskFilter) and the blur/mask pushes they need.
 *
 * Mode numbers are SkBlendMode values (android.graphics.BlendMode / PorterDuffXfermode.porterDuffMode):
 * 0 CLEAR 1 SRC 2 DST 3 SRC_OVER 4 DST_OVER 5 SRC_IN 6 DST_IN 7 SRC_OUT 8 DST_OUT 9 SRC_ATOP
 * 10 DST_ATOP 11 XOR 12 PLUS 13 MODULATE 14 SCREEN 15 OVERLAY 16 DARKEN 17 LIGHTEN 18 COLOR_DODGE
 * 19 COLOR_BURN 20 HARD_LIGHT 21 SOFT_LIGHT 22 DIFFERENCE 23 EXCLUSION 24 MULTIPLY 25 HUE
 * 26 SATURATION 27 COLOR 28 LUMINOSITY.
 */
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <gtk/gtk.h>
#include <gsk/gsk.h>
#include <jni.h>

#include "../defines.h"
#include "AndroidPaint.h"

/* ---- snapshots and nodes ---------------------------------------------------------------------- */

JNIEXPORT jlong JNICALL Java_android_atl_GskCanvas_native_1layerBegin(JNIEnv *env, jclass clazz)
{
	return _INTPTR(gtk_snapshot_new());
}

/* ends a snapshot (all its pushes are popped) and returns its node, or 0 when nothing was drawn */
JNIEXPORT jlong JNICALL Java_android_atl_GskCanvas_native_1layerFinish(JNIEnv *env, jclass clazz, jlong snapshot_ptr)
{
	return _INTPTR(gtk_snapshot_free_to_node(GTK_SNAPSHOT(_PTR(snapshot_ptr))));
}

JNIEXPORT void JNICALL Java_android_atl_GskCanvas_native_1nodeUnref(JNIEnv *env, jclass clazz, jlong node_ptr)
{
	if (node_ptr)
		gsk_render_node_unref(_PTR(node_ptr));
}

JNIEXPORT void JNICALL Java_android_atl_GskCanvas_native_1appendNode(JNIEnv *env, jclass clazz, jlong snapshot_ptr, jlong node_ptr)
{
	if (node_ptr)
		gtk_snapshot_append_node(GTK_SNAPSHOT(_PTR(snapshot_ptr)), _PTR(node_ptr));
}

/* a finished layer into its parent: the layer paint's alpha (0..1) and colour filter (paint 0: none) */
JNIEXPORT void JNICALL Java_android_atl_GskCanvas_native_1appendLayer(JNIEnv *env, jclass clazz, jlong snapshot_ptr, jlong node_ptr, jfloat alpha, jlong paint_ptr)
{
	GtkSnapshot *snapshot = GTK_SNAPSHOT(_PTR(snapshot_ptr));
	struct AndroidPaint *paint = _PTR(paint_ptr);
	if (!node_ptr || alpha <= 0.f)
		return;
	gboolean filter = paint && paint->use_color_filter;
	if (alpha < 1.f)
		gtk_snapshot_push_opacity(snapshot, alpha);
	if (filter)
		gtk_snapshot_push_color_matrix(snapshot, &paint->color_matrix, &paint->color_offset);
	gtk_snapshot_append_node(snapshot, _PTR(node_ptr));
	if (filter)
		gtk_snapshot_pop(snapshot);
	if (alpha < 1.f)
		gtk_snapshot_pop(snapshot);
}

static GskRenderNode *empty_node(void)
{
	return gsk_container_node_new(NULL, 0);
}

static GskRenderNode *container2(GskRenderNode *bottom, GskRenderNode *top)
{
	GskRenderNode *children[2] = { bottom, top };
	return gsk_container_node_new(children, 2);
}

static GskRenderNode *mask(GskRenderNode *source, GskRenderNode *mask, GskMaskMode mode)
{
	return gsk_mask_node_new(source, mask, mode);
}

static GskRenderNode *blend(GskRenderNode *bottom, GskRenderNode *top, GskBlendMode mode)
{
	return gsk_blend_node_new(bottom, top, mode);
}

/* X = mode(src N, dst E) over the whole plane; returns a new reference or NULL (transparent) */
static GskRenderNode *compose_mode(GskRenderNode *E, GskRenderNode *N, int mode)
{
	GskRenderNode *a, *b, *r;
	switch (mode) {
	case 0: /* CLEAR */
		return NULL;
	case 1: /* SRC */
		return gsk_render_node_ref(N);
	case 2: /* DST */
		return gsk_render_node_ref(E);
	case 4: /* DST_OVER */
		return container2(N, E);
	case 5: /* SRC_IN: Sc*Da */
		return mask(N, E, GSK_MASK_MODE_ALPHA);
	case 6: /* DST_IN: Dc*Sa */
		return mask(E, N, GSK_MASK_MODE_ALPHA);
	case 7: /* SRC_OUT: Sc*(1-Da) */
		return mask(N, E, GSK_MASK_MODE_INVERTED_ALPHA);
	case 8: /* DST_OUT: Dc*(1-Sa) */
		return mask(E, N, GSK_MASK_MODE_INVERTED_ALPHA);
	case 9: /* SRC_ATOP: Sc*Da + Dc*(1-Sa); the two terms are composed with source-over (exact where
	         * either alpha is 0 or 1, GSK has no additive node) */
		a = mask(E, N, GSK_MASK_MODE_INVERTED_ALPHA);
		b = mask(N, E, GSK_MASK_MODE_ALPHA);
		r = container2(a, b);
		gsk_render_node_unref(a);
		gsk_render_node_unref(b);
		return r;
	case 10: /* DST_ATOP: Dc*Sa + Sc*(1-Da) */
		a = mask(N, E, GSK_MASK_MODE_INVERTED_ALPHA);
		b = mask(E, N, GSK_MASK_MODE_ALPHA);
		r = container2(a, b);
		gsk_render_node_unref(a);
		gsk_render_node_unref(b);
		return r;
	case 11: /* XOR: Sc*(1-Da) + Dc*(1-Sa) */
		a = mask(N, E, GSK_MASK_MODE_INVERTED_ALPHA);
		b = mask(E, N, GSK_MASK_MODE_INVERTED_ALPHA);
		r = container2(a, b);
		gsk_render_node_unref(a);
		gsk_render_node_unref(b);
		return r;
	case 13: /* MODULATE (PorterDuff.MULTIPLY): Sc*Dc; for opaque colours this is the W3C multiply */
	case 24: /* MULTIPLY */
		return blend(E, N, GSK_BLEND_MODE_MULTIPLY);
	case 14:
		return blend(E, N, GSK_BLEND_MODE_SCREEN);
	case 15:
		return blend(E, N, GSK_BLEND_MODE_OVERLAY);
	case 16:
		return blend(E, N, GSK_BLEND_MODE_DARKEN);
	case 17:
		return blend(E, N, GSK_BLEND_MODE_LIGHTEN);
	case 18:
		return blend(E, N, GSK_BLEND_MODE_COLOR_DODGE);
	case 19:
		return blend(E, N, GSK_BLEND_MODE_COLOR_BURN);
	case 20:
		return blend(E, N, GSK_BLEND_MODE_HARD_LIGHT);
	case 21:
		return blend(E, N, GSK_BLEND_MODE_SOFT_LIGHT);
	case 22:
		return blend(E, N, GSK_BLEND_MODE_DIFFERENCE);
	case 23:
		return blend(E, N, GSK_BLEND_MODE_EXCLUSION);
	case 25:
		return blend(E, N, GSK_BLEND_MODE_HUE);
	case 26:
		return blend(E, N, GSK_BLEND_MODE_SATURATION);
	case 27:
		return blend(E, N, GSK_BLEND_MODE_COLOR);
	case 28:
		return blend(E, N, GSK_BLEND_MODE_LUMINOSITY);
	case 12: /* PLUS: Sc+Dc saturated has no GSK node; source-over is the closest composite */
	case 3:  /* SRC_OVER */
	default:
		return container2(E, N);
	}
}

/*
 * The content so far (dst, node E, consumed) composed with a new draw (src, node N, consumed) by the
 * mode, inside the draw's coverage (node C, consumed; 0 = the whole plane): C*X + (1-C)*E, appended
 * to the fresh snapshot. Returns nothing; all three references are released.
 */
JNIEXPORT void JNICALL Java_android_atl_GskCanvas_native_1appendComposite(JNIEnv *env, jclass clazz, jlong snapshot_ptr, jlong dst_ptr, jlong src_ptr, jlong coverage_ptr, jint mode)
{
	GtkSnapshot *snapshot = GTK_SNAPSHOT(_PTR(snapshot_ptr));
	GskRenderNode *E = dst_ptr ? _PTR(dst_ptr) : empty_node();
	GskRenderNode *N = src_ptr ? _PTR(src_ptr) : empty_node();
	GskRenderNode *C = _PTR(coverage_ptr);
	GskRenderNode *X = compose_mode(E, N, mode);
	if (C) {
		GskRenderNode *outside = mask(E, C, GSK_MASK_MODE_INVERTED_ALPHA);
		gtk_snapshot_append_node(snapshot, outside);
		gsk_render_node_unref(outside);
		if (X) {
			GskRenderNode *inside = mask(X, C, GSK_MASK_MODE_ALPHA);
			gtk_snapshot_append_node(snapshot, inside);
			gsk_render_node_unref(inside);
		}
		gsk_render_node_unref(C);
	} else if (X) {
		gtk_snapshot_append_node(snapshot, X);
	}
	if (X)
		gsk_render_node_unref(X);
	gsk_render_node_unref(E);
	gsk_render_node_unref(N);
}

/* ---- pushes used by mask filters and shaders -------------------------------------------------- */

/* mode 0: alpha, 1: inverted alpha. The mask is recorded until the first pop, the source until the second. */
JNIEXPORT void JNICALL Java_android_atl_GskCanvas_native_1pushMask(JNIEnv *env, jclass clazz, jlong snapshot_ptr, jint mode)
{
	gtk_snapshot_push_mask(GTK_SNAPSHOT(_PTR(snapshot_ptr)), mode ? GSK_MASK_MODE_INVERTED_ALPHA : GSK_MASK_MODE_ALPHA);
}

/* sigma: Skia's Gaussian standard deviation; GSK's blur radius is twice the standard deviation */
JNIEXPORT void JNICALL Java_android_atl_GskCanvas_native_1pushBlur(JNIEnv *env, jclass clazz, jlong snapshot_ptr, jfloat sigma)
{
	gtk_snapshot_push_blur(GTK_SNAPSHOT(_PTR(snapshot_ptr)), sigma > 0.f ? 2.f * sigma : 0.f);
}

JNIEXPORT void JNICALL Java_android_atl_GskCanvas_native_1pushOpacity(JNIEnv *env, jclass clazz, jlong snapshot_ptr, jfloat opacity)
{
	gtk_snapshot_push_opacity(GTK_SNAPSHOT(_PTR(snapshot_ptr)), opacity);
}

/* repeat the child drawn until the pop (its extent: the child rect) over the bounds */
JNIEXPORT void JNICALL Java_android_atl_GskCanvas_native_1pushRepeat(JNIEnv *env, jclass clazz, jlong snapshot_ptr,
		jfloat l, jfloat t, jfloat r, jfloat b, jfloat cl, jfloat ct, jfloat cr, jfloat cb)
{
	gtk_snapshot_push_repeat(GTK_SNAPSHOT(_PTR(snapshot_ptr)), &GRAPHENE_RECT_INIT(l, t, r - l, b - t), &GRAPHENE_RECT_INIT(cl, ct, cr - cl, cb - ct));
}

/* a texture over a rect with the given filter (0 nearest, 1 linear) */
JNIEXPORT void JNICALL Java_android_atl_GskCanvas_native_1appendTexture(JNIEnv *env, jclass clazz, jlong snapshot_ptr, jlong texture_ptr,
		jfloat l, jfloat t, jfloat r, jfloat b, jint filter)
{
	gtk_snapshot_append_scaled_texture(GTK_SNAPSHOT(_PTR(snapshot_ptr)), GDK_TEXTURE(_PTR(texture_ptr)),
	                                   filter ? GSK_SCALING_FILTER_LINEAR : GSK_SCALING_FILTER_NEAREST,
	                                   &GRAPHENE_RECT_INIT(l, t, r - l, b - t));
}

/* opaque black over a rect (coverage masks) */
JNIEXPORT void JNICALL Java_android_atl_GskCanvas_native_1appendCoverageRect(JNIEnv *env, jclass clazz, jlong snapshot_ptr, jfloat l, jfloat t, jfloat r, jfloat b)
{
	gtk_snapshot_append_color(GTK_SNAPSHOT(_PTR(snapshot_ptr)), &(GdkRGBA){0, 0, 0, 1}, &GRAPHENE_RECT_INIT(l, t, r - l, b - t));
}

/* ---- gradients -------------------------------------------------------------------------------- */

static void argb_to_rgba(jint c, GdkRGBA *out)
{
	out->alpha = ((c >> 24) & 0xff) / 255.f;
	out->red = ((c >> 16) & 0xff) / 255.f;
	out->green = ((c >> 8) & 0xff) / 255.f;
	out->blue = (c & 0xff) / 255.f;
}

#define TL_INTERP_STEPS 8

/*
 * Android interpolates gradient colours premultiplied (hwui Shader.cpp passes Skia's
 * kInterpolateColorsInPremul_Flag to every gradient), so white -> transparent fades without a grey
 * middle. GSK's GL renderer interpolates premultiplied too, but its cairo renderer (pixman) does not;
 * where two neighbouring stops differ in alpha and colour, intermediate stops computed in
 * premultiplied space give Android's curve under either renderer.
 * mirror: the stop list is followed by its reflection over [0, 1] (offsets halved), for a repeating
 * gradient of twice the length. Returns the stop count; *out is g_free'd by the caller.
 */
static int build_stops(JNIEnv *env, jintArray colors_arr, jfloatArray pos_arr, gboolean mirror, GskColorStop **out)
{
	int n = (*env)->GetArrayLength(env, colors_arr);
	jint *colors = (*env)->GetIntArrayElements(env, colors_arr, NULL);
	jfloat *pos = pos_arr ? (*env)->GetFloatArrayElements(env, pos_arr, NULL) : NULL;
	int max = (n * (TL_INTERP_STEPS + 2) + 2) * (mirror ? 2 : 1);
	GskColorStop *stops = g_new0(GskColorStop, max);
	int k = 0;
	float last = 0.f;
	for (int i = 0; i < n; i++) {
		float off = pos ? pos[i] : (n > 1 ? (float)i / (n - 1) : 0.f);
		if (off < last) /* Skia pins positions into a non-decreasing sequence */
			off = last;
		if (off > 1.f)
			off = 1.f;
		GdkRGBA c;
		argb_to_rgba(colors[i], &c);
		/* premultiplied, a transparent stop has no colour of its own: it takes its neighbour's on each
		 * side (a second stop at the same offset when the next colour differs), which is exact under
		 * an unpremultiplied interpolation too */
		if (k > 0 && c.alpha == 0.f) {
			c.red = stops[k - 1].color.red;
			c.green = stops[k - 1].color.green;
			c.blue = stops[k - 1].color.blue;
		} else if (k > 0 && stops[k - 1].color.alpha == 0.f && c.alpha > 0.f) {
			stops[k].offset = stops[k - 1].offset;
			stops[k].color = c;
			stops[k].color.alpha = 0.f;
			k++;
		}
		if (k > 0) {
			GdkRGBA p = stops[k - 1].color;
			float poff = stops[k - 1].offset;
			if (p.alpha != c.alpha && (p.red != c.red || p.green != c.green || p.blue != c.blue) && off > poff) {
				for (int s = 1; s < TL_INTERP_STEPS; s++) {
					float f = (float)s / TL_INTERP_STEPS;
					GskColorStop *st = &stops[k++];
					const float a = p.alpha + (c.alpha - p.alpha) * f;
					st->offset = poff + (off - poff) * f;
					st->color.alpha = a;
					if (a > 0.f) { /* premultiplied interpolation, stored unpremultiplied */
						st->color.red = (p.red * p.alpha + (c.red * c.alpha - p.red * p.alpha) * f) / a;
						st->color.green = (p.green * p.alpha + (c.green * c.alpha - p.green * p.alpha) * f) / a;
						st->color.blue = (p.blue * p.alpha + (c.blue * c.alpha - p.blue * p.alpha) * f) / a;
					} else {
						st->color.red = st->color.green = st->color.blue = 0.f;
					}
				}
			}
		}
		stops[k].offset = off;
		stops[k].color = c;
		k++;
		last = off;
	}
	(*env)->ReleaseIntArrayElements(env, colors_arr, colors, JNI_ABORT);
	if (pos)
		(*env)->ReleaseFloatArrayElements(env, pos_arr, pos, JNI_ABORT);
	if (k == 1) { /* a single colour: a flat gradient */
		stops[1] = stops[0];
		stops[0].offset = 0.f;
		stops[1].offset = 1.f;
		k = 2;
	}
	if (mirror) {
		for (int i = 0; i < k; i++)
			stops[i].offset *= 0.5f;
		for (int i = 0; i < k; i++) {
			stops[k + i].offset = 1.f - stops[k - 1 - i].offset;
			stops[k + i].color = stops[k - 1 - i].color;
		}
		k *= 2;
	}
	*out = stops;
	return k;
}

/* tile: 0 CLAMP, 1 MIRROR, 2 REPEAT, 3 DECAL (Shader.TileMode ordinals) */
JNIEXPORT void JNICALL Java_android_atl_GskCanvas_native_1appendLinearGradient(JNIEnv *env, jclass clazz, jlong snapshot_ptr,
		jfloat l, jfloat t, jfloat r, jfloat b, jfloat x0, jfloat y0, jfloat x1, jfloat y1,
		jintArray colors, jfloatArray positions, jint tile)
{
	GtkSnapshot *snapshot = GTK_SNAPSHOT(_PTR(snapshot_ptr));
	GskColorStop *stops;
	int n = build_stops(env, colors, positions, tile == 1, &stops);
	graphene_rect_t bounds = GRAPHENE_RECT_INIT(l, t, r - l, b - t);
	if (x0 == x1 && y0 == y1) {
		/* degenerate: Skia draws the last colour (clamp) or nothing (decal) */
		if (tile != 3)
			gtk_snapshot_append_color(snapshot, &stops[n - 1].color, &bounds);
		g_free(stops);
		return;
	}
	if (tile == 1) {
		gtk_snapshot_append_repeating_linear_gradient(snapshot, &bounds, &GRAPHENE_POINT_INIT(x0, y0),
		                                              &GRAPHENE_POINT_INIT(x0 + 2 * (x1 - x0), y0 + 2 * (y1 - y0)), stops, n);
	} else if (tile == 2) {
		gtk_snapshot_append_repeating_linear_gradient(snapshot, &bounds, &GRAPHENE_POINT_INIT(x0, y0), &GRAPHENE_POINT_INIT(x1, y1), stops, n);
	} else if (tile == 3) {
		/* decal: transparent outside the band between the two end lines; drawn in a frame whose x
		 * axis runs along the gradient */
		float dx = x1 - x0, dy = y1 - y0, len = sqrtf(dx * dx + dy * dy);
		float c = dx / len, s = dy / len;
		float minu = INFINITY, maxu = -INFINITY, minv = INFINITY, maxv = -INFINITY;
		float px[4] = { l, r, r, l }, py[4] = { t, t, b, b };
		for (int i = 0; i < 4; i++) {
			float u = (px[i] - x0) * c + (py[i] - y0) * s;
			float v = -(px[i] - x0) * s + (py[i] - y0) * c;
			minu = fminf(minu, u); maxu = fmaxf(maxu, u);
			minv = fminf(minv, v); maxv = fmaxf(maxv, v);
		}
		minu = fmaxf(minu, 0.f);
		maxu = fminf(maxu, len);
		if (maxu > minu && maxv > minv) {
			gtk_snapshot_save(snapshot);
			gtk_snapshot_translate(snapshot, &GRAPHENE_POINT_INIT(x0, y0));
			gtk_snapshot_rotate(snapshot, atan2f(dy, dx) * (180.f / (float)M_PI));
			gtk_snapshot_append_linear_gradient(snapshot, &GRAPHENE_RECT_INIT(minu, minv, maxu - minu, maxv - minv),
			                                    &GRAPHENE_POINT_INIT(0, 0), &GRAPHENE_POINT_INIT(len, 0), stops, n);
			gtk_snapshot_restore(snapshot);
		}
	} else {
		gtk_snapshot_append_linear_gradient(snapshot, &bounds, &GRAPHENE_POINT_INIT(x0, y0), &GRAPHENE_POINT_INIT(x1, y1), stops, n);
	}
	g_free(stops);
}

JNIEXPORT void JNICALL Java_android_atl_GskCanvas_native_1appendRadialGradient(JNIEnv *env, jclass clazz, jlong snapshot_ptr,
		jfloat l, jfloat t, jfloat r, jfloat b, jfloat cx, jfloat cy, jfloat radius,
		jintArray colors, jfloatArray positions, jint tile)
{
	GtkSnapshot *snapshot = GTK_SNAPSHOT(_PTR(snapshot_ptr));
	GskColorStop *stops;
	int n = build_stops(env, colors, positions, tile == 1, &stops);
	graphene_rect_t bounds = GRAPHENE_RECT_INIT(l, t, r - l, b - t);
	const graphene_point_t center = GRAPHENE_POINT_INIT(cx, cy);
	if (radius <= 0.f) {
		if (tile != 3)
			gtk_snapshot_append_color(snapshot, &stops[n - 1].color, &bounds);
	} else if (tile == 1) {
		gtk_snapshot_append_repeating_radial_gradient(snapshot, &bounds, &center, 2 * radius, 2 * radius, 0.f, 1.f, stops, n);
	} else if (tile == 2) {
		gtk_snapshot_append_repeating_radial_gradient(snapshot, &bounds, &center, radius, radius, 0.f, 1.f, stops, n);
	} else if (tile == 3) {
		GskRoundedRect circle;
		gsk_rounded_rect_init_from_rect(&circle, &GRAPHENE_RECT_INIT(cx - radius, cy - radius, 2 * radius, 2 * radius), radius);
		gtk_snapshot_push_rounded_clip(snapshot, &circle);
		gtk_snapshot_append_radial_gradient(snapshot, &bounds, &center, radius, radius, 0.f, 1.f, stops, n);
		gtk_snapshot_pop(snapshot);
	} else {
		gtk_snapshot_append_radial_gradient(snapshot, &bounds, &center, radius, radius, 0.f, 1.f, stops, n);
	}
	g_free(stops);
}

/* Android's sweep starts at 3 o'clock and turns clockwise (y down); GSK's conic starts at 12 o'clock */
JNIEXPORT void JNICALL Java_android_atl_GskCanvas_native_1appendSweepGradient(JNIEnv *env, jclass clazz, jlong snapshot_ptr,
		jfloat l, jfloat t, jfloat r, jfloat b, jfloat cx, jfloat cy, jintArray colors, jfloatArray positions)
{
	GtkSnapshot *snapshot = GTK_SNAPSHOT(_PTR(snapshot_ptr));
	GskColorStop *stops;
	int n = build_stops(env, colors, positions, FALSE, &stops);
	gtk_snapshot_append_conic_gradient(snapshot, &GRAPHENE_RECT_INIT(l, t, r - l, b - t), &GRAPHENE_POINT_INIT(cx, cy), 90.f, stops, n);
	g_free(stops);
}

/* ---- paint: stroke miter, dash, colour-matrix filters ----------------------------------------- */

JNIEXPORT void JNICALL Java_android_graphics_Paint_native_1set_1stroke_1miter(JNIEnv *env, jclass clazz, jlong paint_ptr, jfloat miter)
{
	struct AndroidPaint *paint = _PTR(paint_ptr);
	gsk_stroke_set_miter_limit(paint->gsk_stroke, miter);
}

JNIEXPORT jfloat JNICALL Java_android_graphics_Paint_native_1get_1stroke_1miter(JNIEnv *env, jclass clazz, jlong paint_ptr)
{
	struct AndroidPaint *paint = _PTR(paint_ptr);
	return gsk_stroke_get_miter_limit(paint->gsk_stroke);
}

/* DashPathEffect on the stroke (intervals null: solid) */
JNIEXPORT void JNICALL Java_android_graphics_Paint_native_1set_1dash(JNIEnv *env, jclass clazz, jlong paint_ptr, jfloatArray intervals_arr, jfloat phase)
{
	struct AndroidPaint *paint = _PTR(paint_ptr);
	if (!intervals_arr) {
		gsk_stroke_set_dash(paint->gsk_stroke, NULL, 0);
		gsk_stroke_set_dash_offset(paint->gsk_stroke, 0.f);
		return;
	}
	int n = (*env)->GetArrayLength(env, intervals_arr);
	jfloat *intervals = (*env)->GetFloatArrayElements(env, intervals_arr, NULL);
	gsk_stroke_set_dash(paint->gsk_stroke, intervals, n);
	gsk_stroke_set_dash_offset(paint->gsk_stroke, phase);
	(*env)->ReleaseFloatArrayElements(env, intervals_arr, intervals, JNI_ABORT);
}

/*
 * ColorMatrixColorFilter / LightingColorFilter: Android's 4x5 matrix (row-major, offsets in 0..255)
 * on un-premultiplied colours, the space GSK colour-matrix nodes work in. GSK multiplies the colour
 * as a row vector (rgba * M + offset), so M is the transpose of Android's 4x4 part.
 */
JNIEXPORT void JNICALL Java_android_graphics_Paint_native_1set_1color_1matrix(JNIEnv *env, jclass clazz, jlong paint_ptr, jfloatArray matrix_arr)
{
	struct AndroidPaint *paint = _PTR(paint_ptr);
	if (!matrix_arr) {
		paint->use_color_filter = FALSE;
		return;
	}
	jfloat *a = (*env)->GetFloatArrayElements(env, matrix_arr, NULL);
	float m[16];
	for (int row = 0; row < 4; row++)
		for (int col = 0; col < 4; col++)
			m[col * 4 + row] = a[row * 5 + col];
	graphene_matrix_init_from_float(&paint->color_matrix, m);
	graphene_vec4_init(&paint->color_offset, a[4] / 255.f, a[9] / 255.f, a[14] / 255.f, a[19] / 255.f);
	(*env)->ReleaseFloatArrayElements(env, matrix_arr, a, JNI_ABORT);
	paint->use_color_filter = TRUE;
}

/*
 * Bitmap.compress for JPEG and WebP through gdk-pixbuf. JPEG has no alpha: like Skia's encoder the
 * premultiplied colour is written (transparent pixels become black). Returns null when the
 * encoder is missing (e.g. no WebP pixbuf loader) or fails, and Bitmap.compress reports false.
 */
JNIEXPORT jbyteArray JNICALL Java_android_graphics_Bitmap_native_1compress(JNIEnv *env, jclass clazz, jlong texture_ptr, jstring format_str, jint quality, jboolean lossless)
{
	GdkTexture *texture = GDK_TEXTURE(_PTR(texture_ptr));
	int w = gdk_texture_get_width(texture), h = gdk_texture_get_height(texture);
	guchar *rgba = g_malloc((gsize)w * h * 4);
	GdkTextureDownloader *dl = gdk_texture_downloader_new(texture);
	gdk_texture_downloader_set_format(dl, GDK_MEMORY_R8G8B8A8);
	gdk_texture_downloader_download_into(dl, rgba, (gsize)w * 4);
	gdk_texture_downloader_free(dl);
	const char *format = (*env)->GetStringUTFChars(env, format_str, NULL);
	gboolean jpeg = strcmp(format, "jpeg") == 0;
	GdkPixbuf *pixbuf = gdk_pixbuf_new(GDK_COLORSPACE_RGB, !jpeg, 8, w, h);
	int rowstride = gdk_pixbuf_get_rowstride(pixbuf);
	guchar *dst = gdk_pixbuf_get_pixels(pixbuf);
	for (int y = 0; y < h; y++) {
		for (int x = 0; x < w; x++) {
			const guchar *s = rgba + ((gsize)y * w + x) * 4;
			if (jpeg) {
				guchar *d = dst + (gsize)y * rowstride + x * 3;
				d[0] = s[0] * s[3] / 255;
				d[1] = s[1] * s[3] / 255;
				d[2] = s[2] * s[3] / 255;
			} else {
				memcpy(dst + (gsize)y * rowstride + x * 4, s, 4);
			}
		}
	}
	g_free(rgba);
	char q[16];
	snprintf(q, sizeof(q), "%d", quality);
	gchar *buf = NULL;
	gsize len = 0;
	GError *err = NULL;
	gboolean ok;
	if (jpeg)
		ok = gdk_pixbuf_save_to_buffer(pixbuf, &buf, &len, "jpeg", &err, "quality", q, NULL);
	else if (lossless)
		ok = gdk_pixbuf_save_to_buffer(pixbuf, &buf, &len, "webp", &err, "lossless", "true", NULL);
	else
		ok = gdk_pixbuf_save_to_buffer(pixbuf, &buf, &len, "webp", &err, "quality", q, NULL);
	(*env)->ReleaseStringUTFChars(env, format_str, format);
	g_object_unref(pixbuf);
	if (!ok) {
		if (err) {
			g_warning("Bitmap.compress: %s", err->message);
			g_error_free(err);
		}
		return NULL;
	}
	jbyteArray out = (*env)->NewByteArray(env, len);
	(*env)->SetByteArrayRegion(env, out, 0, len, (const jbyte *)buf);
	g_free(buf);
	return out;
}

/* a texture as a node of the given size (a bitmap's content as the destination of a transfer mode) */
JNIEXPORT jlong JNICALL Java_android_atl_GskCanvas_native_1textureNode(JNIEnv *env, jclass clazz, jlong texture_ptr, jfloat width, jfloat height)
{
	return _INTPTR(gsk_texture_node_new(GDK_TEXTURE(_PTR(texture_ptr)), &GRAPHENE_RECT_INIT(0, 0, width, height)));
}

/* RenderNode layer paint (setLayerType/setLayerPaint, setUseCompositingLayer): the content with the
 * paint's colour filter (paint 0: none) and alpha; the input node stays referenced */
JNIEXPORT jlong JNICALL Java_android_view_RenderNode_nativeLayerPaintKeep(JNIEnv *env, jclass clazz, jlong node_ptr, jlong paint_ptr, jfloat alpha)
{
	GskRenderNode *node = gsk_render_node_ref(_PTR(node_ptr));
	struct AndroidPaint *paint = _PTR(paint_ptr);
	if (paint && paint->use_color_filter) {
		GskRenderNode *filtered = gsk_color_matrix_node_new(node, &paint->color_matrix, &paint->color_offset);
		gsk_render_node_unref(node);
		node = filtered;
	}
	if (alpha < 1.f) {
		GskRenderNode *faded = gsk_opacity_node_new(node, alpha);
		gsk_render_node_unref(node);
		node = faded;
	}
	return _INTPTR(node);
}

/* Paint.setFontVariationSettings: Pango's "tag=value,..." form (null clears) */
JNIEXPORT void JNICALL Java_android_graphics_Paint_native_1set_1font_1variations(JNIEnv *env, jclass clazz, jlong paint_ptr, jstring variations)
{
	struct AndroidPaint *paint = _PTR(paint_ptr);
	if (!variations) {
		pango_font_description_set_variations(paint->font, NULL);
		return;
	}
	const char *v = (*env)->GetStringUTFChars(env, variations, NULL);
	pango_font_description_set_variations(paint->font, v);
	(*env)->ReleaseStringUTFChars(env, variations, v);
}

/* ---- android.graphics.Movie on GdkPixbufAnimation ------------------------------------------------ */

struct tl_movie {
	GdkPixbufAnimation *anim;
	int duration; /* ms of one loop, 0 for a still image */
};

G_GNUC_BEGIN_IGNORE_DEPRECATIONS
static GdkPixbufAnimationIter *movie_iter_at(GdkPixbufAnimation *anim, int ms)
{
	GTimeVal start = { 0, 0 };
	GdkPixbufAnimationIter *iter = gdk_pixbuf_animation_get_iter(anim, &start);
	if (ms > 0) {
		GTimeVal t = { ms / 1000, (ms % 1000) * 1000 };
		gdk_pixbuf_animation_iter_advance(iter, &t);
	}
	return iter;
}

/* the sum of the frame delays until the animation returns to its first frame */
static int movie_duration(GdkPixbufAnimation *anim)
{
	if (gdk_pixbuf_animation_is_static_image(anim))
		return 0;
	GTimeVal start = { 0, 0 };
	GdkPixbufAnimationIter *iter = gdk_pixbuf_animation_get_iter(anim, &start);
	GdkPixbuf *first = gdk_pixbuf_animation_iter_get_pixbuf(iter);
	long total = 0;
	for (int i = 0; i < 100000; i++) {
		int delay = gdk_pixbuf_animation_iter_get_delay_time(iter);
		if (delay < 0)
			break; /* the last frame stays forever */
		total += delay;
		GTimeVal t = { total / 1000, (total % 1000) * 1000 };
		gdk_pixbuf_animation_iter_advance(iter, &t);
		if (gdk_pixbuf_animation_iter_get_pixbuf(iter) == first)
			break;
	}
	g_object_unref(iter);
	return (int)total;
}
G_GNUC_END_IGNORE_DEPRECATIONS

JNIEXPORT jlong JNICALL Java_android_graphics_Movie_native_1decode(JNIEnv *env, jclass clazz, jbyteArray data_arr, jint offset, jint length)
{
	GdkPixbufLoader *loader = gdk_pixbuf_loader_new();
	jbyte *data = (*env)->GetByteArrayElements(env, data_arr, NULL);
	gboolean ok = gdk_pixbuf_loader_write(loader, (const guchar *)data + offset, length, NULL);
	(*env)->ReleaseByteArrayElements(env, data_arr, data, JNI_ABORT);
	ok = gdk_pixbuf_loader_close(loader, NULL) && ok;
	GdkPixbufAnimation *anim = ok ? gdk_pixbuf_loader_get_animation(loader) : NULL;
	if (!anim) {
		g_object_unref(loader);
		return 0;
	}
	struct tl_movie *movie = g_new0(struct tl_movie, 1);
	movie->anim = g_object_ref(anim);
	g_object_unref(loader);
	movie->duration = movie_duration(movie->anim);
	return _INTPTR(movie);
}

JNIEXPORT jintArray JNICALL Java_android_graphics_Movie_native_1info(JNIEnv *env, jclass clazz, jlong ptr)
{
	struct tl_movie *movie = _PTR(ptr);
	jint info[3] = { gdk_pixbuf_animation_get_width(movie->anim), gdk_pixbuf_animation_get_height(movie->anim), movie->duration };
	jintArray out = (*env)->NewIntArray(env, 3);
	(*env)->SetIntArrayRegion(env, out, 0, 3, info);
	return out;
}

JNIEXPORT jlong JNICALL Java_android_graphics_Movie_native_1frame(JNIEnv *env, jclass clazz, jlong ptr, jint ms)
{
	struct tl_movie *movie = _PTR(ptr);
	G_GNUC_BEGIN_IGNORE_DEPRECATIONS
	GdkPixbufAnimationIter *iter = movie_iter_at(movie->anim, ms);
	GdkPixbuf *pixbuf = gdk_pixbuf_animation_iter_get_pixbuf(iter);
	GdkTexture *texture = gdk_texture_new_for_pixbuf(pixbuf);
	G_GNUC_END_IGNORE_DEPRECATIONS
	g_object_unref(iter);
	return _INTPTR(texture);
}

JNIEXPORT void JNICALL Java_android_graphics_Movie_native_1destroy(JNIEnv *env, jclass clazz, jlong ptr)
{
	struct tl_movie *movie = _PTR(ptr);
	if (!movie)
		return;
	g_object_unref(movie->anim);
	g_free(movie);
}
