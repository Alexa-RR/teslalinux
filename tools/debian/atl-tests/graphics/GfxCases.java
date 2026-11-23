package org.teslalinux.gfxtest;

import android.graphics.BlendMode;
import android.graphics.BlendModeColorFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Region;
import android.graphics.Shader;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LightingColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import java.util.ArrayList;
import java.util.List;

/**
 * Drawing cases for ATL's canvas, shared by the Bitmap test (GfxTest, dalvikvm: GSK's cairo renderer)
 * and the on-screen test (GfxActivity: the window's renderer, cairo or GL). Each case draws into a
 * CELL x CELL square at (0, 0) and names probe pixels with the colour AOSP/Skia gives them, as a
 * premultiplied ARGB colour over a transparent cell (the screen test composites it over its cell
 * background). The expected colours of the colour filters come from Skia's blend-mode formulas
 * (SkBlendMode / SkRasterPipeline), written out here on their own.
 */
public final class GfxCases {
	public static final int CELL = 40;

	public static final class Probe {
		public final int x, y;
		public final float[] premul; /* r, g, b, a in 0..1 */
		public final float tol;

		Probe(int x, int y, float[] premul, float tol) {
			this.x = x;
			this.y = y;
			this.premul = premul;
			this.tol = tol;
		}
	}

	public abstract static class Case {
		public final String name;
		public final List<Probe> probes = new ArrayList<>();

		Case(String name) {
			this.name = name;
		}

		public abstract void draw(Canvas c);

		Case probe(int x, int y, int argb, float tol) {
			probes.add(new Probe(x, y, premul(argb), tol));
			return this;
		}

		Case probe(int x, int y, float[] premul, float tol) {
			probes.add(new Probe(x, y, premul, tol));
			return this;
		}
	}

	static float[] premul(int argb) {
		float a = ((argb >>> 24) & 0xff) / 255f;
		return new float[] { ((argb >> 16) & 0xff) / 255f * a, ((argb >> 8) & 0xff) / 255f * a, (argb & 0xff) / 255f * a, a };
	}

	static final float TOL = 3 / 255f;

	/* ---- Skia's blend modes on premultiplied colours (independent of ATL's native code) ---- */

	static float inv(float x) { return 1 - x; }

	static float lum(float r, float g, float b) { return r * 0.30f + g * 0.59f + b * 0.11f; }

	static float sat(float r, float g, float b) { return Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)); }

	static float[] setSat(float r, float g, float b, float s) {
		float mn = Math.min(r, Math.min(g, b)), mx = Math.max(r, Math.max(g, b)), st = mx - mn;
		if (st == 0)
			return new float[] { 0, 0, 0 };
		return new float[] { (r - mn) * s / st, (g - mn) * s / st, (b - mn) * s / st };
	}

	static float[] setLum(float[] c, float l) {
		float d = l - lum(c[0], c[1], c[2]);
		return new float[] { c[0] + d, c[1] + d, c[2] + d };
	}

	static float[] clipColor(float[] c, float a) {
		float mn = Math.min(c[0], Math.min(c[1], c[2])), mx = Math.max(c[0], Math.max(c[1], c[2])), l = lum(c[0], c[1], c[2]);
		float[] o = new float[3];
		for (int i = 0; i < 3; i++) {
			float v = c[i];
			if (mn < 0 && l - mn != 0)
				v = l + (v - l) * l / (l - mn);
			if (mx > a && mx - l != 0)
				v = l + (v - l) * (a - l) / (mx - l);
			o[i] = Math.max(v, 0);
		}
		return o;
	}

	static float sep(BlendMode m, float s, float d, float sa, float da) {
		switch (m) {
			case SCREEN: return s + d - s * d;
			case OVERLAY: return s * inv(da) + d * inv(sa) + (2 * d <= da ? 2 * s * d : sa * da - 2 * (da - d) * (sa - s));
			case DARKEN: return s + d - Math.max(s * da, d * sa);
			case LIGHTEN: return s + d - Math.min(s * da, d * sa);
			case COLOR_DODGE:
				if (d == 0) return s * inv(da);
				if (s == sa) return s + d * inv(sa);
				return sa * Math.min(da, d * sa / (sa - s)) + s * inv(da) + d * inv(sa);
			case COLOR_BURN:
				if (d == da) return d + s * inv(da);
				if (s == 0) return d * inv(sa);
				return sa * (da - Math.min(da, (da - d) * sa / s)) + s * inv(da) + d * inv(sa);
			case HARD_LIGHT: return s * inv(da) + d * inv(sa) + (2 * s <= sa ? 2 * s * d : sa * da - 2 * (da - d) * (sa - s));
			case SOFT_LIGHT: {
				float mm = da > 0 ? d / da : 0, s2 = 2 * s, m4 = 4 * mm;
				float darkSrc = d * (sa + (s2 - sa) * (1 - mm));
				float darkDst = (m4 * m4 + m4) * (mm - 1) + 7 * mm;
				float liteDst = (float) Math.sqrt(mm) - mm;
				float liteSrc = d * sa + da * (s2 - sa) * (4 * d <= da ? darkDst : liteDst);
				return s * inv(da) + d * inv(sa) + (s2 <= sa ? darkSrc : liteSrc);
			}
			case DIFFERENCE: return s + d - 2 * Math.min(s * da, d * sa);
			case EXCLUSION: return s + d - 2 * s * d;
			case MULTIPLY: return s * inv(da) + d * inv(sa) + s * d;
			default: throw new IllegalArgumentException(m.toString());
		}
	}

	/** mode(src, dst), premultiplied */
	public static float[] blend(BlendMode m, float[] s, float[] d) {
		float sa = s[3], da = d[3];
		float[] o = new float[4];
		switch (m) {
			case CLEAR: return o;
			case SRC: return s.clone();
			case DST: return d.clone();
			case SRC_OVER: for (int i = 0; i < 4; i++) o[i] = s[i] + d[i] * inv(sa); return o;
			case DST_OVER: for (int i = 0; i < 4; i++) o[i] = d[i] + s[i] * inv(da); return o;
			case SRC_IN: for (int i = 0; i < 4; i++) o[i] = s[i] * da; return o;
			case DST_IN: for (int i = 0; i < 4; i++) o[i] = d[i] * sa; return o;
			case SRC_OUT: for (int i = 0; i < 4; i++) o[i] = s[i] * inv(da); return o;
			case DST_OUT: for (int i = 0; i < 4; i++) o[i] = d[i] * inv(sa); return o;
			case SRC_ATOP: for (int i = 0; i < 4; i++) o[i] = s[i] * da + d[i] * inv(sa); return o;
			case DST_ATOP: for (int i = 0; i < 4; i++) o[i] = d[i] * sa + s[i] * inv(da); return o;
			case XOR: for (int i = 0; i < 4; i++) o[i] = s[i] * inv(da) + d[i] * inv(sa); return o;
			case PLUS: for (int i = 0; i < 4; i++) o[i] = Math.min(1, s[i] + d[i]); return o;
			case MODULATE: for (int i = 0; i < 4; i++) o[i] = s[i] * d[i]; return o;
			case HUE: case SATURATION: case COLOR: case LUMINOSITY: {
				float[] c;
				if (m == BlendMode.HUE) {
					c = setSat(s[0] * da, s[1] * da, s[2] * da, sat(d[0], d[1], d[2]) * sa);
					c = setLum(c, lum(d[0], d[1], d[2]) * sa);
				} else if (m == BlendMode.SATURATION) {
					c = setSat(d[0] * sa, d[1] * sa, d[2] * sa, sat(s[0], s[1], s[2]) * da);
					c = setLum(c, lum(d[0], d[1], d[2]) * sa);
				} else if (m == BlendMode.COLOR) {
					c = setLum(new float[] { s[0] * da, s[1] * da, s[2] * da }, lum(d[0], d[1], d[2]) * sa);
				} else {
					c = setLum(new float[] { d[0] * sa, d[1] * sa, d[2] * sa }, lum(s[0], s[1], s[2]) * da);
				}
				c = clipColor(c, sa * da);
				for (int i = 0; i < 3; i++)
					o[i] = s[i] * inv(da) + d[i] * inv(sa) + c[i];
				o[3] = sa + da - sa * da;
				return o;
			}
			default:
				for (int i = 0; i < 3; i++)
					o[i] = sep(m, s[i], d[i], sa, da);
				o[3] = sa + da - sa * da;
				return o;
		}
	}

	/** Skia's colour matrix filter: on the un-premultiplied colour, clamped, premultiplied again */
	static float[] matrix(float[] m, float[] p) {
		float a = p[3];
		float[] u = a > 0 ? new float[] { p[0] / a, p[1] / a, p[2] / a, a } : new float[4];
		float[] r = new float[4];
		for (int row = 0; row < 4; row++) {
			float v = m[row * 5 + 4] / 255f;
			for (int col = 0; col < 4; col++)
				v += m[row * 5 + col] * u[col];
			r[row] = Math.max(0, Math.min(1, v));
		}
		return new float[] { r[0] * r[3], r[1] * r[3], r[2] * r[3], r[3] };
	}

	static Paint fill(int color) {
		Paint p = new Paint();
		p.setColor(color);
		return p;
	}

	static Paint stroke(int color, float width) {
		Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
		p.setColor(color);
		p.setStyle(Paint.Style.STROKE);
		p.setStrokeWidth(width);
		return p;
	}

	/** a 4x1 source bitmap: opaque red, translucent green, transparent, opaque white */
	static final int[] TEXELS = { 0xFFFF0000, 0x8000FF00, 0x00000000, 0xFFFFFFFF };

	static Bitmap texels() {
		Bitmap b = Bitmap.createBitmap(4, 1, Bitmap.Config.ARGB_8888);
		Canvas c = new Canvas(b);
		for (int i = 0; i < 4; i++)
			c.drawRect(i, 0, i + 1, 1, fill(TEXELS[i])); /* onto transparent: the colour itself */
		return b;
	}

	static final int P_OPAQUE = 0xFF3366CC, P_TRANSLUCENT = 0x80CC3300;
	static final int C_OPAQUE = 0xFF22AA44, C_TRANSLUCENT = 0x6644AAFF;

	interface Filter {
		float[] apply(float[] premulPixel);
	}

	static Filter modeFilter(final BlendMode m, final int color) {
		return p -> blend(m, premul(color), p);
	}

	static Filter matrixFilter(final float[] cm) {
		return p -> matrix(cm, p);
	}

	/** a filled cell with the filter: the paint colour through the filter */
	static Case solidCase(String name, final ColorFilter cf, int paintColor, Filter f) {
		final int pc = paintColor;
		Case c = new Case(name) {
			public void draw(Canvas canvas) {
				Paint p = fill(pc);
				p.setColorFilter(cf);
				canvas.drawRect(4, 4, CELL - 4, CELL - 4, p);
			}
		};
		c.probe(CELL / 2, CELL / 2, f.apply(premul(pc)), TOL);
		c.probe(1, 1, 0, TOL); /* outside the rect: nothing */
		return c;
	}

	/** the 4x1 texel bitmap stretched to 36x40 with the filter: every texel through it (each texel
	 * 9 pixels wide, so a texel's centre is a pixel's centre and filtering takes nothing from its
	 * neighbours) */
	static Case bitmapCase(String name, final ColorFilter cf, Filter f) {
		Case c = new Case(name) {
			Bitmap src;

			public void draw(Canvas canvas) {
				if (src == null)
					src = texels();
				Paint p = new Paint();
				p.setColorFilter(cf);
				canvas.drawBitmap(src, new Rect(0, 0, 4, 1), new Rect(0, 0, 36, CELL), p);
			}
		};
		for (int i = 0; i < 4; i++)
			c.probe(9 * i + 4, CELL / 2, f.apply(premul(TEXELS[i])), 4 / 255f);
		c.probe(38, CELL / 2, 0, TOL); /* outside the bitmap: nothing, whatever the filter */
		return c;
	}

	public static List<Case> all() {
		List<Case> cases = new ArrayList<>();
		/* ---- colour filters: every blend mode, solid colours and bitmaps ---- */
		for (BlendMode m : BlendMode.values()) {
			cases.add(solidCase("filter-solid " + m + " translucent", new BlendModeColorFilter(C_TRANSLUCENT, m), P_TRANSLUCENT, modeFilter(m, C_TRANSLUCENT)));
			cases.add(solidCase("filter-solid " + m + " opaque", new BlendModeColorFilter(C_OPAQUE, m), P_OPAQUE, modeFilter(m, C_OPAQUE)));
		}
		BlendMode[] bitmapModes = { BlendMode.SRC_IN, BlendMode.MODULATE, BlendMode.SRC_OVER, BlendMode.DST_OVER, BlendMode.DST_ATOP,
		                            BlendMode.XOR, BlendMode.PLUS, BlendMode.SCREEN, BlendMode.OVERLAY, BlendMode.DARKEN,
		                            BlendMode.LIGHTEN, BlendMode.MULTIPLY, BlendMode.DIFFERENCE, BlendMode.HUE, BlendMode.LUMINOSITY };
		for (BlendMode m : bitmapModes) {
			cases.add(bitmapCase("filter-bitmap " + m + " translucent", new BlendModeColorFilter(C_TRANSLUCENT, m), modeFilter(m, C_TRANSLUCENT)));
			cases.add(bitmapCase("filter-bitmap " + m + " opaque", new BlendModeColorFilter(C_OPAQUE, m), modeFilter(m, C_OPAQUE)));
		}
		/* PorterDuff modes are the same SkBlendModes (MULTIPLY is MODULATE, ADD is PLUS) */
		cases.add(solidCase("filter-solid PorterDuff ADD", new PorterDuffColorFilter(C_TRANSLUCENT, PorterDuff.Mode.ADD), P_TRANSLUCENT, modeFilter(BlendMode.PLUS, C_TRANSLUCENT)));
		cases.add(solidCase("filter-solid PorterDuff MULTIPLY", new PorterDuffColorFilter(C_TRANSLUCENT, PorterDuff.Mode.MULTIPLY), P_TRANSLUCENT, modeFilter(BlendMode.MODULATE, C_TRANSLUCENT)));
		cases.add(bitmapCase("filter-bitmap PorterDuff DST_OVER", new PorterDuffColorFilter(C_TRANSLUCENT, PorterDuff.Mode.DST_OVER), modeFilter(BlendMode.DST_OVER, C_TRANSLUCENT)));
		cases.add(bitmapCase("filter-bitmap PorterDuff SCREEN", new PorterDuffColorFilter(C_OPAQUE, PorterDuff.Mode.SCREEN), modeFilter(BlendMode.SCREEN, C_OPAQUE)));
		/* lighting and colour matrix filters */
		LightingColorFilter lighting = new LightingColorFilter(0xFF8040C0, 0xFF102030);
		float[] lm = { 0x80 / 255f, 0, 0, 0, 0x10, 0, 0x40 / 255f, 0, 0, 0x20, 0, 0, 0xC0 / 255f, 0, 0x30, 0, 0, 0, 1, 0 };
		cases.add(solidCase("filter-solid Lighting", lighting, P_TRANSLUCENT, matrixFilter(lm)));
		cases.add(bitmapCase("filter-bitmap Lighting", lighting, matrixFilter(lm)));
		ColorMatrix sat0 = new ColorMatrix();
		sat0.setSaturation(0);
		float[] cm = sat0.getArray().clone();
		cm[18] = 0.5f; /* and half the alpha */
		cases.add(solidCase("filter-solid ColorMatrix", new ColorMatrixColorFilter(cm), P_OPAQUE, matrixFilter(cm)));
		cases.add(bitmapCase("filter-bitmap ColorMatrix", new ColorMatrixColorFilter(cm), matrixFilter(cm)));

		/* ---- stroke styles ---- */
		/* FILL_AND_STROKE with a translucent colour covers the fill/stroke overlap once */
		cases.add(new Case("stroke FILL_AND_STROKE circle translucent") {
			public void draw(Canvas c) {
				Paint p = stroke(0x80FF0000, 6);
				p.setStyle(Paint.Style.FILL_AND_STROKE);
				c.drawCircle(20, 20, 10, p);
			}
		}.probe(20, 28, 0x80FF0000, TOL).probe(20, 20, 0x80FF0000, TOL).probe(20, 31, 0x80FF0000, TOL).probe(20, 37, 0, TOL));
		cases.add(new Case("stroke FILL_AND_STROKE rect translucent") {
			public void draw(Canvas c) {
				Paint p = fill(0x80000000);
				p.setStyle(Paint.Style.FILL_AND_STROKE);
				p.setStrokeWidth(4);
				c.drawRect(10, 10, 30, 30, p);
			}
		}.probe(10, 20, 0x80000000, TOL).probe(8, 20, 0x80000000, TOL).probe(20, 20, 0x80000000, TOL).probe(7, 20, 0, TOL));
		/* FILL_AND_STROKE at width 0 is a fill (no hairline around it) */
		cases.add(new Case("stroke FILL_AND_STROKE width 0 is a fill") {
			public void draw(Canvas c) {
				Paint p = fill(0xFF0000FF);
				p.setStyle(Paint.Style.FILL_AND_STROKE);
				p.setStrokeWidth(0);
				Path path = new Path();
				path.addRect(10, 10, 30, 30, Path.Direction.CW);
				c.drawPath(path, p);
			}
		}.probe(20, 20, 0xFF0000FF, TOL).probe(9, 20, 0, TOL).probe(20, 30, 0, TOL));
		/* a hairline is one device pixel wide under a scaling matrix */
		cases.add(new Case("stroke hairline line under scale(4)") {
			public void draw(Canvas c) {
				c.save();
				c.scale(4, 4);
				c.drawLine(1, 5.125f, 9, 5.125f, stroke(0xFF000000, 0));
				c.restore();
			}
		}.probe(20, 20, 0xFF000000, 40 / 255f).probe(20, 22, 0, TOL).probe(20, 18, 0, TOL));
		cases.add(new Case("stroke hairline rect under scale(4)") {
			public void draw(Canvas c) {
				c.save();
				c.scale(4, 4);
				c.drawRect(2.125f, 2.125f, 7.875f, 7.875f, stroke(0xFF000000, 0));
				c.restore();
			}
		}.probe(20, 8, 0xFF000000, 40 / 255f).probe(20, 10, 0, TOL).probe(20, 6, 0, TOL));
		/* stroke joins of a rectangle: round and bevel corners, a miter limit under sqrt(2) bevels */
		cases.add(new Case("stroke rect ROUND join") {
			public void draw(Canvas c) {
				Paint p = stroke(0xFF000000, 8);
				p.setStrokeJoin(Paint.Join.ROUND);
				c.drawRect(10, 10, 30, 30, p);
			}
		}.probe(6, 6, 0, TOL).probe(8, 8, 0xFF000000, TOL).probe(20, 6, 0xFF000000, TOL).probe(20, 20, 0, TOL));
		cases.add(new Case("stroke rect BEVEL join") {
			public void draw(Canvas c) {
				Paint p = stroke(0xFF000000, 8);
				p.setStrokeJoin(Paint.Join.BEVEL);
				c.drawRect(10, 10, 30, 30, p);
			}
		}.probe(6, 6, 0, TOL).probe(9, 9, 0xFF000000, TOL).probe(20, 6, 0xFF000000, TOL));
		cases.add(new Case("stroke rect MITER join, limit 1") {
			public void draw(Canvas c) {
				Paint p = stroke(0xFF000000, 8);
				p.setStrokeMiter(1);
				c.drawRect(10, 10, 30, 30, p);
			}
		}.probe(6, 6, 0, TOL).probe(9, 9, 0xFF000000, TOL));
		cases.add(new Case("stroke rect MITER join") {
			public void draw(Canvas c) {
				c.drawRect(10, 10, 30, 30, stroke(0xFF000000, 8));
			}
		}.probe(6, 6, 0xFF000000, TOL).probe(20, 20, 0, TOL));
		/* the outline of Paint.getFillPath, filled, covers what the stroke covers */
		cases.add(new Case("getFillPath outline filled (round caps)") {
			public void draw(Canvas c) {
				Paint p = stroke(0xFF000000, 8);
				p.setStrokeCap(Paint.Cap.ROUND);
				Path src = new Path();
				src.moveTo(10, 20);
				src.lineTo(30, 20);
				Path dst = new Path();
				p.getFillPath(src, dst);
				c.drawPath(dst, fill(0xFF008000));
			}
		}.probe(20, 20, 0xFF008000, TOL).probe(7, 20, 0xFF008000, TOL).probe(20, 17, 0xFF008000, TOL).probe(4, 20, 0, TOL).probe(20, 26, 0, TOL));

		/* ---- clip out ---- */
		cases.add(new Case("clipOutRect") {
			public void draw(Canvas c) {
				c.save();
				c.clipOutRect(10, 10, 30, 30);
				c.drawColor(0xFF0000FF);
				c.restore();
				c.drawRect(15, 15, 17, 17, fill(0xFFFF0000)); /* after restore the hole is drawable again */
			}
		}.probe(20, 20, 0, TOL).probe(5, 5, 0xFF0000FF, TOL).probe(15, 15, 0xFFFF0000, TOL));
		cases.add(new Case("clipOutPath circle") {
			public void draw(Canvas c) {
				Path circle = new Path();
				circle.addCircle(20, 20, 10, Path.Direction.CW);
				c.save();
				c.clipRect(0, 0, CELL, CELL);
				c.clipOutPath(circle);
				c.drawColor(0xFF00FF00);
				c.restore();
			}
		}.probe(20, 20, 0, TOL).probe(2, 2, 0xFF00FF00, TOL).probe(20, 8, 0xFF00FF00, TOL));

		/* ---- the clip ops that expand the clip, for an app targeting API 27 ---- */
		cases.add(new LegacyClipCase("clip legacy REPLACE") {
			void clips(Canvas c) {
				c.clipRect(10, 10, 20, 20);
				c.clipRect(0, 0, CELL, CELL, Region.Op.REPLACE);
			}
		}.probe(5, 5, 0xFF0000FF, TOL).probe(15, 15, 0xFF0000FF, TOL).probe(35, 35, 0xFF0000FF, TOL));
		cases.add(new LegacyClipCase("clip legacy UNION") {
			void clips(Canvas c) {
				c.clipRect(0, 0, 10, CELL);
				c.clipRect(30, 0, CELL, CELL, Region.Op.UNION);
			}
		}.probe(5, 20, 0xFF0000FF, TOL).probe(20, 20, 0, TOL).probe(35, 20, 0xFF0000FF, TOL));
		cases.add(new LegacyClipCase("clip legacy XOR") {
			void clips(Canvas c) {
				c.clipRect(0, 0, 30, CELL);
				c.clipRect(10, 0, CELL, CELL, Region.Op.XOR);
			}
		}.probe(5, 20, 0xFF0000FF, TOL).probe(20, 20, 0, TOL).probe(35, 20, 0xFF0000FF, TOL));
		cases.add(new LegacyClipCase("clip legacy REVERSE_DIFFERENCE") {
			void clips(Canvas c) {
				c.clipRect(0, 0, 30, CELL);
				c.clipRect(10, 0, CELL, CELL, Region.Op.REVERSE_DIFFERENCE);
			}
		}.probe(5, 20, 0, TOL).probe(20, 20, 0, TOL).probe(35, 20, 0xFF0000FF, TOL));
		cases.add(new LegacyClipCase("clip legacy UNION of paths") {
			void clips(Canvas c) {
				Path a = new Path();
				a.addRect(0, 0, 10, 10, Path.Direction.CW);
				Path b = new Path();
				b.addRect(30, 30, 40, 40, Path.Direction.CW);
				c.clipPath(a);
				c.clipPath(b, Region.Op.UNION);
			}
		}.probe(5, 5, 0xFF0000FF, TOL).probe(35, 35, 0xFF0000FF, TOL).probe(20, 20, 0, TOL).probe(35, 5, 0, TOL));
		/* restoring the expanded level brings the clip below it back */
		cases.add(new Case("clip legacy REPLACE then restore") {
			public void draw(Canvas c) {
				int saved = Canvas.tlCompatibilityVersion();
				Canvas.setCompatibilityVersion(27);
				try {
					c.save();
					c.clipRect(0, 0, 20, CELL);
					c.save();
					c.clipRect(0, 0, CELL, CELL, Region.Op.REPLACE);
					c.drawRect(25, 2, 35, 10, fill(0xFFFF0000));
					c.restore();
					c.drawColor(0xFF00FF00);
					c.restore();
				} finally {
					Canvas.setCompatibilityVersion(saved);
				}
			}
		}.probe(30, 6, 0xFFFF0000, TOL).probe(10, 20, 0xFF00FF00, TOL).probe(30, 25, 0, TOL));

		/* ---- vertices: colours interpolated across each triangle (Skia's Gouraud), not averaged ---- */
		cases.add(new Case("drawVertices colours interpolate") {
			public void draw(Canvas c) {
				float[] v = { 0, 0, CELL, 0, 0, CELL };
				int[] col = { 0xFFFF0000, 0xFF00FF00, 0xFF0000FF };
				c.drawVertices(Canvas.VertexMode.TRIANGLES, 6, v, 0, null, 0, col, 0, null, 0, 0, new Paint());
			}
		}.probe(1, 1, gouraud(1.5f, 1.5f), 12 / 255f).probe(37, 1, gouraud(37.5f, 1.5f), 12 / 255f).probe(1, 37, gouraud(1.5f, 37.5f), 12 / 255f)
		 .probe(13, 13, gouraud(13.5f, 13.5f), 12 / 255f).probe(35, 35, 0, TOL));
		cases.add(new Case("drawVertices shader modulated by colours") {
			Bitmap src;

			public void draw(Canvas c) {
				if (src == null) {
					src = Bitmap.createBitmap(CELL, CELL, Bitmap.Config.ARGB_8888);
					new Canvas(src).drawRect(0, 0, CELL / 2, CELL, fill(0xFFFFFFFF)); /* white left, transparent right */
				}
				Paint p = new Paint();
				p.setShader(new BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
				float[] v = { 0, 0, CELL, 0, CELL, CELL, 0, CELL };
				int[] col = { 0xFFFF0000, 0xFFFF0000, 0xFF0000FF, 0xFF0000FF }; /* red at the top, blue at the bottom */
				short[] idx = { 0, 1, 2, 0, 2, 3 };
				c.drawVertices(Canvas.VertexMode.TRIANGLES, 8, v, 0, v, 0, col, 0, idx, 0, 6, p);
			}
		}.probe(10, 2, mix(0xFFFF0000, 0xFF0000FF, 2.5f / CELL), 12 / 255f).probe(10, 37, mix(0xFFFF0000, 0xFF0000FF, 37.5f / CELL), 12 / 255f)
		 .probe(30, 20, 0, TOL));
		cases.add(new Case("drawBitmapMesh colours interpolate") {
			Bitmap src;

			public void draw(Canvas c) {
				if (src == null) {
					src = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888);
					src.eraseColor(0xFFFFFFFF);
				}
				float[] v = { 0, 0, CELL, 0, 0, CELL, CELL, CELL };
				int[] col = { 0xFFFF0000, 0xFFFF0000, 0xFF0000FF, 0xFF0000FF };
				c.drawBitmapMesh(src, 1, 1, v, 0, col, 0, null);
			}
		}.probe(20, 2, mix(0xFFFF0000, 0xFF0000FF, 2.5f / CELL), 12 / 255f).probe(20, 37, mix(0xFFFF0000, 0xFF0000FF, 37.5f / CELL), 12 / 255f));
		cases.add(new Case("drawBitmapMesh without colours") {
			Bitmap src;

			public void draw(Canvas c) {
				if (src == null) {
					src = Bitmap.createBitmap(2, 1, Bitmap.Config.ARGB_8888);
					Canvas s = new Canvas(src);
					s.drawRect(0, 0, 1, 1, fill(0xFFFF0000));
					s.drawRect(1, 0, 2, 1, fill(0xFF0000FF));
				}
				/* the bitmap stretched over the cell, mirrored (right edge at x = 0) */
				float[] v = { CELL, 0, 0, 0, CELL, CELL, 0, CELL };
				c.drawBitmapMesh(src, 1, 1, v, 0, null, 0, null);
			}
		}.probe(35, 20, 0xFFFF0000, 6 / 255f).probe(5, 20, 0xFF0000FF, 6 / 255f));

		/* ---- saveLayerAlpha: the layer composites at that alpha (what it holds is opaque) ---- */
		cases.add(new Case("saveLayerAlpha") {
			public void draw(Canvas c) {
				c.saveLayerAlpha(0, 0, CELL, CELL, 0x80);
				c.drawRect(0, 0, 30, 30, fill(0xFFFF0000));
				c.drawRect(10, 10, 40, 40, fill(0xFF0000FF));
				c.restore();
			}
		}.probe(5, 5, 0x80FF0000, TOL).probe(20, 20, 0x800000FF, TOL).probe(35, 35, 0x800000FF, TOL));
		return cases;
	}

	/** A case whose clips use the Region.Ops an app targeting API 27 may use, then fills blue. */
	abstract static class LegacyClipCase extends Case {
		LegacyClipCase(String name) {
			super(name);
		}

		abstract void clips(Canvas c);

		public void draw(Canvas c) {
			int saved = Canvas.tlCompatibilityVersion();
			Canvas.setCompatibilityVersion(27);
			try {
				c.save();
				clips(c);
				c.drawColor(0xFF0000FF);
				c.restore();
			} finally {
				Canvas.setCompatibilityVersion(saved);
			}
		}
	}

	/** the colour at (x, y) of the triangle (0,0) red, (CELL,0) green, (0,CELL) blue: barycentric weights */
	static float[] gouraud(float x, float y) {
		float g = x / CELL, b = y / CELL, r = 1 - g - b;
		return new float[] { r, g, b, 1 };
	}

	/** the opaque colour a + (b - a) * t */
	static float[] mix(int a, int b, float t) {
		float[] pa = premul(a), pb = premul(b);
		return new float[] { pa[0] + (pb[0] - pa[0]) * t, pa[1] + (pb[1] - pa[1]) * t, pa[2] + (pb[2] - pa[2]) * t, 1 };
	}
}
