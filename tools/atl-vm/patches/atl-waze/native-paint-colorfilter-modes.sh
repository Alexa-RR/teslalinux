#!/bin/sh
# teslalinux: a paint's blend-mode colour filter with the mode's real result (Skia
# SkColorFilters::Blend(color, mode): the filter colour is the source, the drawn pixel the
# destination). The stock native_set_color_filter ignores the mode and always produces SRC_IN.
# GSK colour-matrix nodes work on un-premultiplied colours; every mode below is a diagonal matrix
# plus an offset there (c = filter colour, ca its alpha, d/da = the pixel):
#   CLEAR    rgb 0           a 0           SRC      rgb c           a ca
#   DST      rgb d           a da          SRC_IN   rgb c           a ca*da
#   DST_IN   rgb d           a da*ca       SRC_OUT  rgb c           a ca*(1-da)
#   DST_OUT  rgb d           a da*(1-ca)   SRC_ATOP rgb ca*c+(1-ca)*d a da
#   MULTIPLY rgb c*d         a ca*da       (PorterDuff MULTIPLY is Skia's modulate, AOSP b/73224934)
#   SRC_OVER with an opaque colour: rgb c, a 1
# Modes whose un-premultiplied result divides by the result alpha (DST_OVER, DST_ATOP, XOR, SCREEN,
# ADD, OVERLAY, DARKEN, LIGHTEN, SRC_OVER with a translucent colour) are not a colour matrix; they
# keep the SRC_IN matrix and are listed in TODO-real.md. Run by build-atl.sh. Idempotent.
set -eu
R=src/api-impl-jni/graphics/android_graphics_Paint.c
if ! grep -q "native_1set_1color_1filter_1mode" "$R"; then
cat >> "$R" <<'EOF'

/* teslalinux: blend-mode colour filter, mode = PorterDuff.Mode ordinal (-1: no filter) */
JNIEXPORT void JNICALL Java_android_graphics_Paint_native_1set_1color_1filter_1mode(JNIEnv *env, jclass clazz, jlong paint_ptr, jint mode, jint color)
{
	struct AndroidPaint *paint = _PTR(paint_ptr);
	const float ca = ((color >> 24) & 0xFF) / 255.f;
	const float c[3] = { ((color >> 16) & 0xFF) / 255.f, ((color >> 8) & 0xFF) / 255.f, ((color >> 0) & 0xFF) / 255.f };
	float diag[4] = { 0, 0, 0, ca }; /* SRC_IN, also the fallback */
	float off[4] = { c[0], c[1], c[2], 0 };
	switch (mode) {
	case -1:
		paint->use_color_filter = FALSE;
		return;
	case 0: /* CLEAR */
		diag[3] = 0; off[0] = off[1] = off[2] = 0;
		break;
	case 1: /* SRC */
		diag[3] = 0; off[3] = ca;
		break;
	case 2: /* DST */
		diag[0] = diag[1] = diag[2] = diag[3] = 1; off[0] = off[1] = off[2] = 0;
		break;
	case 3: /* SRC_OVER */
		if (ca >= 1.f) { diag[3] = 0; off[3] = 1; }
		break;
	case 6: /* DST_IN */
		diag[0] = diag[1] = diag[2] = 1; off[0] = off[1] = off[2] = 0;
		break;
	case 7: /* SRC_OUT */
		diag[3] = -ca; off[3] = ca;
		break;
	case 8: /* DST_OUT */
		diag[0] = diag[1] = diag[2] = 1; off[0] = off[1] = off[2] = 0; diag[3] = 1.f - ca;
		break;
	case 9: /* SRC_ATOP */
		for (int i = 0; i < 3; i++) { diag[i] = 1.f - ca; off[i] = ca * c[i]; }
		diag[3] = 1;
		break;
	case 14: /* MULTIPLY */
		for (int i = 0; i < 3; i++) { diag[i] = c[i]; off[i] = 0; }
		break;
	default: /* 5 SRC_IN and the non-linear modes (see the script header) */
		break;
	}
	/* clang-format off */
	graphene_matrix_init_from_float(&paint->color_matrix, (float[]){
		diag[0], 0, 0, 0,
		0, diag[1], 0, 0,
		0, 0, diag[2], 0,
		0, 0, 0, diag[3],
	});
	/* clang-format on */
	graphene_vec4_init(&paint->color_offset, off[0], off[1], off[2], off[3]);
	paint->use_color_filter = TRUE;
}
EOF
fi
grep -c "native_1set_1color_1filter_1mode" "$R"
