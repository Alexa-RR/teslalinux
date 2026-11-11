#!/bin/sh
# teslalinux: android.widget.ImageView draws its drawable as AOSP's configureBounds() places it, for
# every scale type, including the image matrix.
#
# ATL's ImageView is a GtkPicture whose content fit stood in for the scale type: only FIT_XY, CENTER
# (scaled down instead of cropped), CENTER_CROP and CENTER_INSIDE were mapped ("TODO: somehow handle
# all the types"); MATRIX, FIT_START, FIT_CENTER and FIT_END kept whatever fit was set before, and
# setImageMatrix() was only stored. Waze's compass turns its needle with ScaleType.MATRIX and
# setImageMatrix(rotate), so the needle never turned.
#
# The picture now shows a TlImagePaintable that wraps the drawable's paintable and draws it the AOSP
# way into the content box GtkPicture gives it (the view minus its padding, which is the picture's
# CSS padding), with GTK_CONTENT_FIT_FILL so the box is the full allocation:
#   - no intrinsic size, or FIT_XY: the drawable fills the box;
#   - MATRIX: the drawable at its intrinsic size through the image matrix;
#   - the drawable already the box's size: drawn as is;
#   - CENTER: centred (rounded), not scaled; CENTER_CROP: scaled to cover, centred (rounded);
#   - CENTER_INSIDE: scaled down only, centred (rounded);
#   - FIT_START / FIT_CENTER / FIT_END: Matrix.setRectToRect with START / CENTER / END.
# Content outside the view is clipped by the view (the wrapper's overflow); cropToPadding clips to the
# content box. The intrinsic size is the drawable's, so measuring is unchanged. Contents and size
# invalidations of the drawable's paintable are forwarded. Run by build-atl.sh from ~/atl-src.
# Idempotent.
set -eu
F=src/api-impl-jni/widgets/android_widget_ImageView.c
if ! grep -q "tl_image_paintable" "$F"; then
python3 - "$F" <<'PY'
import sys
p = sys.argv[1]
s = open(p).read()

old_set = '''JNIEXPORT void JNICALL Java_android_widget_ImageView_native_1setDrawable(JNIEnv *env, jobject this, jlong widget_ptr, jlong paintable_ptr)
{
	GtkPicture *picture = _PTR(widget_ptr);
	GdkPaintable *paintable = _PTR(paintable_ptr);
	gtk_picture_set_paintable(picture, paintable);
}
'''
assert s.count(old_set) == 1, "native_setDrawable"

paintable = r'''/* ---- teslalinux: tl_image_paintable, AOSP ImageView.configureBounds (native-imageview-scaletype.sh) ---- */
#define TL_TYPE_IMAGE_PAINTABLE (tl_image_paintable_get_type())
G_DECLARE_FINAL_TYPE(TlImagePaintable, tl_image_paintable, TL, IMAGE_PAINTABLE, GObject)

struct _TlImagePaintable {
	GObject parent_instance;
	GdkPaintable *inner;
	gulong contents_handler;
	gulong size_handler;
	int scale_type;
	gboolean has_matrix;
	float m[6]; /* x' = m[0]x + m[1]y + m[2], y' = m[3]x + m[4]y + m[5] (android.graphics.Matrix order) */
	gboolean crop_to_padding;
	/* ImageView.animateTransform (androidx.transition): a draw matrix that replaces the scale type's
	 * until the next configureBounds (a new drawable, scale type, image matrix or content size) */
	gboolean anim_active;
	gboolean anim_has_matrix;
	float am[6];
	double anim_width, anim_height;
};

static void tl_image_paintable_iface_init(GdkPaintableInterface *iface);
G_DEFINE_TYPE_WITH_CODE(TlImagePaintable, tl_image_paintable, G_TYPE_OBJECT,
                        G_IMPLEMENT_INTERFACE(GDK_TYPE_PAINTABLE, tl_image_paintable_iface_init))

/* java.lang.Math.round for the offsets AOSP rounds */
static float tl_round(float v)
{
	return floorf(v + 0.5f);
}

/* the affine x' = sx*x + kx*y + tx, y' = ky*x + sy*y + ty as 2D snapshot transforms (translate, rotate,
 * skew, scale) that the renderers draw without an offscreen; R(t) * [[a, k], [0, d]] decomposition */
static void tl_snapshot_affine(GtkSnapshot *snapshot, float sx, float kx, float tx, float ky, float sy, float ty)
{
	gtk_snapshot_translate(snapshot, &GRAPHENE_POINT_INIT(tx, ty));
	if (kx == 0 && ky == 0) {
		if (sx != 1 || sy != 1)
			gtk_snapshot_scale(snapshot, sx, sy);
		return;
	}
	const float a = sqrtf(sx * sx + ky * ky);
	if (a == 0)
		return;
	const float theta = atan2f(ky, sx);
	const float c = cosf(theta), s = sinf(theta);
	const float k = kx * c + sy * s;   /* upper-right of R(-t) * M */
	const float d = sy * c - kx * s;   /* lower-right */
	gtk_snapshot_rotate(snapshot, theta * 180.0f / (float)G_PI);
	if (k != 0 && d != 0) {
		GskTransform *skew = gsk_transform_skew(NULL, atanf(k / d) * 180.0f / (float)G_PI, 0);
		gtk_snapshot_transform(snapshot, skew);
		gsk_transform_unref(skew);
	}
	gtk_snapshot_scale(snapshot, a, d);
}

static void tl_image_paintable_snapshot(GdkPaintable *paintable, GdkSnapshot *gdk_snapshot, double width, double height)
{
	TlImagePaintable *self = TL_IMAGE_PAINTABLE(paintable);
	GtkSnapshot *snapshot = GTK_SNAPSHOT(gdk_snapshot);
	if (!self->inner || width <= 0 || height <= 0)
		return;
	const int dwidth = gdk_paintable_get_intrinsic_width(self->inner);
	const int dheight = gdk_paintable_get_intrinsic_height(self->inner);
	const int vwidth = (int)width;
	const int vheight = (int)height;
	if (self->anim_active) {
		if (self->anim_width < 0) {
			self->anim_width = width;
			self->anim_height = height;
		} else if (self->anim_width != width || self->anim_height != height) {
			self->anim_active = FALSE; /* AOSP: a new frame size runs configureBounds again */
		}
	}
	if (self->crop_to_padding)
		gtk_snapshot_push_clip(snapshot, &GRAPHENE_RECT_INIT(0, 0, width, height));
	if (self->anim_active && !self->anim_has_matrix) {
		gdk_paintable_snapshot(self->inner, gdk_snapshot, width, height);
	} else if (self->anim_active) {
		gtk_snapshot_save(snapshot);
		tl_snapshot_affine(snapshot, self->am[0], self->am[1], self->am[2], self->am[3], self->am[4], self->am[5]);
		gdk_paintable_snapshot(self->inner, gdk_snapshot, MAX(dwidth, 0), MAX(dheight, 0));
		gtk_snapshot_restore(snapshot);
	} else if (dwidth <= 0 || dheight <= 0 || self->scale_type == SCALE_TYPE_FIT_XY) {
		/* AOSP: the drawable's bounds are the content box, no matrix */
		gdk_paintable_snapshot(self->inner, gdk_snapshot, width, height);
	} else {
		gboolean identity = FALSE;
		float sx = 1, kx = 0, tx = 0, ky = 0, sy = 1, ty = 0;
		const gboolean fits = vwidth == dwidth && vheight == dheight;
		if (self->scale_type == SCALE_TYPE_MATRIX) {
			if (self->has_matrix) {
				sx = self->m[0]; kx = self->m[1]; tx = self->m[2];
				ky = self->m[3]; sy = self->m[4]; ty = self->m[5];
			} else {
				identity = TRUE;
			}
		} else if (fits) {
			identity = TRUE;
		} else if (self->scale_type == SCALE_TYPE_CENTER) {
			tx = tl_round((vwidth - dwidth) * 0.5f);
			ty = tl_round((vheight - dheight) * 0.5f);
		} else if (self->scale_type == SCALE_TYPE_CENTER_CROP) {
			float scale, dx = 0, dy = 0;
			if ((long)dwidth * vheight > (long)vwidth * dheight) {
				scale = (float)vheight / (float)dheight;
				dx = (vwidth - dwidth * scale) * 0.5f;
			} else {
				scale = (float)vwidth / (float)dwidth;
				dy = (vheight - dheight * scale) * 0.5f;
			}
			sx = sy = scale;
			tx = tl_round(dx);
			ty = tl_round(dy);
		} else if (self->scale_type == SCALE_TYPE_CENTER_INSIDE) {
			float scale;
			if (dwidth <= vwidth && dheight <= vheight)
				scale = 1.0f;
			else
				scale = fminf((float)vwidth / (float)dwidth, (float)vheight / (float)dheight);
			sx = sy = scale;
			tx = tl_round((vwidth - dwidth * scale) * 0.5f);
			ty = tl_round((vheight - dheight * scale) * 0.5f);
		} else {
			/* FIT_START / FIT_CENTER / FIT_END: Matrix.setRectToRect(src, dst, START/CENTER/END) */
			const float scale = fminf((float)vwidth / (float)dwidth, (float)vheight / (float)dheight);
			const float diffx = vwidth - dwidth * scale;
			const float diffy = vheight - dheight * scale;
			sx = sy = scale;
			if (self->scale_type == SCALE_TYPE_FIT_CENTER) {
				tx = diffx * 0.5f;
				ty = diffy * 0.5f;
			} else if (self->scale_type == SCALE_TYPE_FIT_END) {
				tx = diffx;
				ty = diffy;
			}
		}
		if (identity) {
			gdk_paintable_snapshot(self->inner, gdk_snapshot, dwidth, dheight);
		} else {
			gtk_snapshot_save(snapshot);
			tl_snapshot_affine(snapshot, sx, kx, tx, ky, sy, ty);
			gdk_paintable_snapshot(self->inner, gdk_snapshot, dwidth, dheight);
			gtk_snapshot_restore(snapshot);
		}
	}
	if (self->crop_to_padding)
		gtk_snapshot_pop(snapshot);
}

static int tl_image_paintable_get_intrinsic_width(GdkPaintable *paintable)
{
	TlImagePaintable *self = TL_IMAGE_PAINTABLE(paintable);
	return self->inner ? gdk_paintable_get_intrinsic_width(self->inner) : 0;
}

static int tl_image_paintable_get_intrinsic_height(GdkPaintable *paintable)
{
	TlImagePaintable *self = TL_IMAGE_PAINTABLE(paintable);
	return self->inner ? gdk_paintable_get_intrinsic_height(self->inner) : 0;
}

static double tl_image_paintable_get_intrinsic_aspect_ratio(GdkPaintable *paintable)
{
	TlImagePaintable *self = TL_IMAGE_PAINTABLE(paintable);
	return self->inner ? gdk_paintable_get_intrinsic_aspect_ratio(self->inner) : 0;
}

static void tl_image_paintable_iface_init(GdkPaintableInterface *iface)
{
	iface->snapshot = tl_image_paintable_snapshot;
	iface->get_intrinsic_width = tl_image_paintable_get_intrinsic_width;
	iface->get_intrinsic_height = tl_image_paintable_get_intrinsic_height;
	iface->get_intrinsic_aspect_ratio = tl_image_paintable_get_intrinsic_aspect_ratio;
}

static void tl_image_paintable_drop_inner(TlImagePaintable *self)
{
	if (!self->inner)
		return;
	g_signal_handler_disconnect(self->inner, self->contents_handler);
	g_signal_handler_disconnect(self->inner, self->size_handler);
	g_clear_object(&self->inner);
}

static void tl_image_paintable_finalize(GObject *object)
{
	tl_image_paintable_drop_inner(TL_IMAGE_PAINTABLE(object));
	G_OBJECT_CLASS(tl_image_paintable_parent_class)->finalize(object);
}

static void tl_image_paintable_class_init(TlImagePaintableClass *klass)
{
	G_OBJECT_CLASS(klass)->finalize = tl_image_paintable_finalize;
}

static void tl_image_paintable_init(TlImagePaintable *self)
{
	self->scale_type = SCALE_TYPE_FIT_CENTER; /* AOSP's default */
}

static void tl_image_paintable_forward_contents(GdkPaintable *inner, TlImagePaintable *self)
{
	gdk_paintable_invalidate_contents(GDK_PAINTABLE(self));
}

static void tl_image_paintable_forward_size(GdkPaintable *inner, TlImagePaintable *self)
{
	gdk_paintable_invalidate_size(GDK_PAINTABLE(self));
}

static void tl_image_paintable_set_inner(TlImagePaintable *self, GdkPaintable *inner)
{
	self->anim_active = FALSE;
	if (self->inner != inner) {
		tl_image_paintable_drop_inner(self);
		if (inner) {
			self->inner = g_object_ref(inner);
			self->contents_handler = g_signal_connect(inner, "invalidate-contents", G_CALLBACK(tl_image_paintable_forward_contents), self);
			self->size_handler = g_signal_connect(inner, "invalidate-size", G_CALLBACK(tl_image_paintable_forward_size), self);
		}
		gdk_paintable_invalidate_size(GDK_PAINTABLE(self));
	}
	gdk_paintable_invalidate_contents(GDK_PAINTABLE(self));
}

/* the picture's paintable, created on first use (the picture fills its content box with it) */
static TlImagePaintable *tl_image_paintable_for(GtkWidget *widget)
{
	if (!GTK_IS_PICTURE(widget))
		return NULL;
	TlImagePaintable *self = g_object_get_data(G_OBJECT(widget), "tl-image-paintable");
	if (!self) {
		self = g_object_new(TL_TYPE_IMAGE_PAINTABLE, NULL);
		g_object_set_data_full(G_OBJECT(widget), "tl-image-paintable", self, g_object_unref);
		gtk_picture_set_content_fit(GTK_PICTURE(widget), GTK_CONTENT_FIT_FILL);
		gtk_picture_set_paintable(GTK_PICTURE(widget), GDK_PAINTABLE(self));
	}
	return self;
}

JNIEXPORT void JNICALL Java_android_widget_ImageView_native_1setDrawable(JNIEnv *env, jobject this, jlong widget_ptr, jlong paintable_ptr)
{
	TlImagePaintable *self = tl_image_paintable_for(GTK_WIDGET(_PTR(widget_ptr)));
	if (self)
		tl_image_paintable_set_inner(self, _PTR(paintable_ptr));
}

/* android.graphics.Matrix values (9 floats), or null for the identity */
JNIEXPORT void JNICALL Java_android_widget_ImageView_native_1setImageMatrix(JNIEnv *env, jobject this, jlong widget_ptr, jfloatArray values)
{
	TlImagePaintable *self = tl_image_paintable_for(GTK_WIDGET(_PTR(widget_ptr)));
	if (!self)
		return;
	if (values && (*env)->GetArrayLength(env, values) >= 6) {
		jfloat v[9] = {0};
		(*env)->GetFloatArrayRegion(env, values, 0, MIN(9, (*env)->GetArrayLength(env, values)), v);
		for (int i = 0; i < 6; i++)
			self->m[i] = v[i];
		self->has_matrix = TRUE;
	} else {
		self->has_matrix = FALSE;
	}
	self->anim_active = FALSE;
	gdk_paintable_invalidate_contents(GDK_PAINTABLE(self));
}

/* ImageView.animateTransform: values (9 floats) draw the drawable at its intrinsic size through
 * them; null draws it over the content box */
JNIEXPORT void JNICALL Java_android_widget_ImageView_native_1animateTransform(JNIEnv *env, jobject this, jlong widget_ptr, jfloatArray values)
{
	TlImagePaintable *self = tl_image_paintable_for(GTK_WIDGET(_PTR(widget_ptr)));
	if (!self)
		return;
	if (values && (*env)->GetArrayLength(env, values) >= 6) {
		jfloat v[9] = {0};
		(*env)->GetFloatArrayRegion(env, values, 0, MIN(9, (*env)->GetArrayLength(env, values)), v);
		for (int i = 0; i < 6; i++)
			self->am[i] = v[i];
		self->anim_has_matrix = TRUE;
	} else {
		self->anim_has_matrix = FALSE;
	}
	self->anim_active = TRUE;
	self->anim_width = self->anim_height = -1;
	gdk_paintable_invalidate_contents(GDK_PAINTABLE(self));
}

JNIEXPORT void JNICALL Java_android_widget_ImageView_native_1setCropToPadding(JNIEnv *env, jobject this, jlong widget_ptr, jboolean crop)
{
	TlImagePaintable *self = tl_image_paintable_for(GTK_WIDGET(_PTR(widget_ptr)));
	if (!self)
		return;
	self->crop_to_padding = crop;
	gdk_paintable_invalidate_contents(GDK_PAINTABLE(self));
}
'''
s = s.replace(old_set, paintable, 1)

old_scale = s[s.index('JNIEXPORT void JNICALL Java_android_widget_ImageView_native_1setScaleType'):]
assert old_scale.rstrip().endswith('}'), "native_setScaleType is the last function"
new_scale = '''JNIEXPORT void JNICALL Java_android_widget_ImageView_native_1setScaleType(JNIEnv *env, jobject this, jlong widget_ptr, jint scale_type)
{
	/* tl_image_paintable: every AOSP scale type, drawn by the paintable */
	TlImagePaintable *self = tl_image_paintable_for(GTK_WIDGET(_PTR(widget_ptr)));
	if (!self)
		return;
	self->scale_type = scale_type;
	self->anim_active = FALSE;
	gdk_paintable_invalidate_contents(GDK_PAINTABLE(self));
}
'''
s = s[:s.index('JNIEXPORT void JNICALL Java_android_widget_ImageView_native_1setScaleType')] + new_scale
if '#include <math.h>' not in s:
    s = s.replace('#include <gtk/gtk.h>\n', '#include <gtk/gtk.h>\n#include <math.h>\n', 1)
open(p, "w").write(s)
PY
fi
grep -c "tl_image_paintable" "$F"
