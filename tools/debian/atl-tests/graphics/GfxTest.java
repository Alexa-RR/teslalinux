package org.teslalinux.gfxtest;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Insets;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.NinePatchDrawable;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * ATL canvas test under a plain dalvikvm (run.sh): the GfxCases drawn into Bitmaps (GSK's cairo
 * renderer) with their probe pixels checked, and the non-pixel behaviour of the same area:
 * Paint.getFillPath, stroke width values, clip bounds after clipOut, nine-patch optical and outline
 * insets (aapt's npLb/npOl chunks). Prints PASS/FAIL per check and "RESULT passed/total".
 */
public class GfxTest {
	static int passed, total;

	static void check(String name, boolean ok, String detail) {
		total++;
		if (ok)
			passed++;
		System.out.println((ok ? "PASS " : "FAIL ") + name + (ok || detail == null ? "" : "  (" + detail + ")"));
	}

	/** getPixel's convention here: true when it returns premultiplied colour (AOSP: un-premultiplied) */
	static boolean premultipliedReadback;

	static float[] readPremul(Bitmap b, int x, int y) {
		int c = b.getPixel(x, y);
		float a = ((c >>> 24) & 0xff) / 255f;
		float r = ((c >> 16) & 0xff) / 255f, g = ((c >> 8) & 0xff) / 255f, bl = (c & 0xff) / 255f;
		if (premultipliedReadback)
			return new float[] { r, g, bl, a };
		return new float[] { r * a, g * a, bl * a, a };
	}

	static String fmt(float[] p) {
		return String.format("%.3f,%.3f,%.3f,%.3f", p[0], p[1], p[2], p[3]);
	}

	static void runCases() {
		Bitmap cal = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
		new Canvas(cal).drawColor(0x80FF0000);
		int c = cal.getPixel(0, 0);
		premultipliedReadback = ((c >> 16) & 0xff) < 0xC0;
		System.out.println("INFO Bitmap.getPixel returns " + (premultipliedReadback ? "premultiplied" : "un-premultiplied") + " colour (" + Integer.toHexString(c) + ")");
		for (GfxCases.Case k : GfxCases.all()) {
			Bitmap b = Bitmap.createBitmap(GfxCases.CELL, GfxCases.CELL, Bitmap.Config.ARGB_8888);
			Canvas canvas = new Canvas(b);
			String err = null;
			try {
				k.draw(canvas);
				for (GfxCases.Probe p : k.probes) {
					float[] got = readPremul(b, p.x, p.y);
					for (int i = 0; i < 4; i++) {
						if (Math.abs(got[i] - p.premul[i]) > p.tol) {
							err = "at " + p.x + "," + p.y + " got " + fmt(got) + " want " + fmt(p.premul);
							/* the alpha of the probe's column, for a look at what was drawn */
							StringBuilder col = new StringBuilder(" column alpha:");
							for (int y = Math.max(0, p.y - 6); y < Math.min(GfxCases.CELL, p.y + 7); y++)
								col.append(' ').append(y).append('=').append(Math.round(readPremul(b, p.x, y)[3] * 255));
							err += col;
							break;
						}
					}
					if (err != null)
						break;
				}
			} catch (Throwable t) {
				err = t.toString();
			}
			check("bitmap " + k.name, err == null, err);
		}
	}

	static RectF bounds(Path p) {
		RectF r = new RectF();
		p.computeBounds(r, true);
		return r;
	}

	static boolean near(RectF r, float l, float t, float rr, float b, float tol) {
		return Math.abs(r.left - l) <= tol && Math.abs(r.top - t) <= tol && Math.abs(r.right - rr) <= tol && Math.abs(r.bottom - b) <= tol;
	}

	static void fillPath() {
		Path line = new Path();
		line.moveTo(0, 10);
		line.lineTo(20, 10);
		Paint p = new Paint();
		p.setStyle(Paint.Style.STROKE);
		p.setStrokeWidth(4);
		Path dst = new Path();
		boolean r = p.getFillPath(line, dst);
		check("getFillPath stroke: true, BUTT outline bounds", r && near(bounds(dst), 0, 8, 20, 12, 0.01f), r + " " + bounds(dst));
		p.setStrokeCap(Paint.Cap.SQUARE);
		r = p.getFillPath(line, dst);
		check("getFillPath SQUARE caps extend half the width", r && near(bounds(dst), -2, 8, 22, 12, 0.01f), r + " " + bounds(dst));
		p.setStrokeCap(Paint.Cap.ROUND);
		r = p.getFillPath(line, dst);
		check("getFillPath ROUND caps", r && near(bounds(dst), -2, 8, 22, 12, 0.05f), r + " " + bounds(dst));
		check("getFillPath result is WINDING", dst.getFillType() == Path.FillType.WINDING, "" + dst.getFillType());

		Path corner = new Path();
		corner.moveTo(0, 0);
		corner.lineTo(10, 0);
		corner.lineTo(10, 10);
		Paint q = new Paint();
		q.setStyle(Paint.Style.STROKE);
		q.setStrokeWidth(2);
		q.getFillPath(corner, dst);
		check("getFillPath MITER join reaches the corner", near(bounds(dst), 0, -1, 11, 10, 0.01f), "" + bounds(dst));

		Paint hair = new Paint();
		hair.setStyle(Paint.Style.STROKE);
		hair.setStrokeWidth(0);
		r = hair.getFillPath(line, dst);
		check("getFillPath hairline: false, dst = src", !r && near(bounds(dst), 0, 10, 20, 10, 0.01f), r + " " + bounds(dst));

		Paint f = new Paint();
		Path rect = new Path();
		rect.addRect(0, 0, 10, 10, Path.Direction.CW);
		r = f.getFillPath(rect, dst);
		check("getFillPath FILL: true, dst = src", r && near(bounds(dst), 0, 0, 10, 10, 0.01f), r + " " + bounds(dst));

		Paint fs = new Paint();
		fs.setStyle(Paint.Style.FILL_AND_STROKE);
		fs.setStrokeWidth(4);
		r = fs.getFillPath(rect, dst);
		check("getFillPath FILL_AND_STROKE: stroke and fill", r && near(bounds(dst), -2, -2, 12, 12, 0.01f), r + " " + bounds(dst));
		/* the fill's interior is in the result: drawn, its centre is covered */
		Bitmap b = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888);
		Canvas c = new Canvas(b);
		c.translate(5, 5);
		Paint black = new Paint();
		black.setColor(0xFF000000);
		c.drawPath(dst, black);
		check("getFillPath FILL_AND_STROKE covers the interior", (b.getPixel(10, 10) >>> 24) == 0xff && (b.getPixel(1, 1) >>> 24) == 0, Integer.toHexString(b.getPixel(10, 10)));

		Path inv = new Path(rect);
		inv.setFillType(Path.FillType.INVERSE_EVEN_ODD);
		fs.setStyle(Paint.Style.STROKE);
		fs.getFillPath(inv, dst);
		check("getFillPath keeps an inverse source inverse", dst.getFillType() == Path.FillType.INVERSE_WINDING, "" + dst.getFillType());

		Paint dash = new Paint();
		dash.setStyle(Paint.Style.STROKE);
		dash.setStrokeWidth(2);
		dash.setPathEffect(new DashPathEffect(new float[] { 4, 4 }, 0));
		dash.getFillPath(line, dst);
		Bitmap db = Bitmap.createBitmap(24, 20, Bitmap.Config.ARGB_8888);
		new Canvas(db).drawPath(dst, black);
		check("getFillPath applies the path effect first (dashes)", (db.getPixel(1, 10) >>> 24) == 0xff && (db.getPixel(5, 10) >>> 24) == 0 && (db.getPixel(9, 10) >>> 24) == 0xff,
		      Integer.toHexString(db.getPixel(1, 10)) + " " + Integer.toHexString(db.getPixel(5, 10)));
	}

	static void pathBounds() {
		Path conic = new Path();
		conic.moveTo(100, 100);
		conic.conicTo(110, 100, 110, 110, 0.7071f);
		check("computeBounds of a conic: its points (not the weight)", near(bounds(conic), 100, 100, 110, 110, 0.01f), "" + bounds(conic));
		Path circle = new Path();
		circle.addCircle(100, 100, 10, Path.Direction.CW);
		check("computeBounds of a circle", near(bounds(circle), 90, 90, 110, 110, 0.01f), "" + bounds(circle));
		Path arc = new Path();
		arc.addArc(50, 50, 70, 70, 0, 90);
		RectF ab = bounds(arc);
		check("computeBounds of an arc stays at the arc", ab.left >= 49.9f && ab.top >= 49.9f && ab.right <= 70.1f && ab.bottom <= 70.1f && ab.right > 69.9f, "" + ab);
	}

	static void matrixCopy() {
		android.graphics.Matrix m = new android.graphics.Matrix();
		m.setScale(4, 2);
		m.postTranslate(3, 5);
		android.graphics.Matrix copy = new android.graphics.Matrix(m);
		check("new Matrix(src) copies src", copy.equals(m) && !copy.isIdentity(), copy.toString());
		Bitmap b = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888);
		Canvas c = new Canvas(b);
		c.scale(4, 4);
		c.save();
		c.translate(1, 0);
		float[] v = new float[9];
		c.getMatrix().getValues(v);
		check("Canvas.getMatrix after save() keeps the outer matrix", v[0] == 4 && v[2] == 4, java.util.Arrays.toString(v));
		c.restore();
	}

	static void strokeWidth() {
		Paint p = new Paint();
		check("Paint stroke width defaults to 0 (hairline)", p.getStrokeWidth() == 0, "" + p.getStrokeWidth());
		p.setStrokeWidth(3);
		p.setStrokeWidth(0);
		check("setStrokeWidth(0) reads back 0", p.getStrokeWidth() == 0, "" + p.getStrokeWidth());
		p.setStrokeWidth(2);
		p.setStrokeWidth(-1);
		check("a negative stroke width is ignored", p.getStrokeWidth() == 2, "" + p.getStrokeWidth());
		check("a copied paint keeps the width", new Paint(p).getStrokeWidth() == 2, "");
	}

	static void clipBounds() {
		Bitmap b = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888);
		Canvas c = new Canvas(b);
		c.save();
		c.clipRect(0, 0, 40, 40);
		boolean nonEmpty = c.clipOutRect(0, 0, 40, 20);
		Rect r = c.getClipBounds();
		check("clipOutRect of the top half: clip bounds are the bottom half", nonEmpty && r.equals(new Rect(0, 20, 40, 40)), nonEmpty + " " + r);
		check("quickReject inside the clipped-out half", c.quickReject(5, 5, 35, 15), "");
		check("no quickReject in the rest", !c.quickReject(5, 25, 35, 35), "");
		c.save();
		boolean left = c.clipOutRect(0, 0, 40, 40);
		check("clipping out everything: empty, false", !left && c.getClipBounds().isEmpty(), left + " " + c.getClipBounds());
		c.restore();
		c.restore();
		c.save();
		c.clipRect(0, 0, 40, 40);
		c.clipOutRect(10, 10, 30, 30);
		check("a hole in the middle keeps the bounds", c.getClipBounds().equals(new Rect(0, 0, 40, 40)), "" + c.getClipBounds());
		c.restore();
		c.save();
		c.clipRect(0, 0, 40, 40);
		c.translate(10, 0);
		c.clipOutRect(20, -5, 40, 50);
		check("clipOutRect under a translation (right part)", c.getClipBounds().equals(new Rect(-10, 0, 20, 40)), "" + c.getClipBounds());
		c.restore();
	}

	/* ---- a nine-patch PNG as aapt compiles it: npTc divs, npLb layout bounds, npOl outline ---- */

	static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
		ByteBuffer len = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(data.length);
		out.write(len.array(), 0, 4);
		byte[] t = type.getBytes();
		out.write(t, 0, 4);
		out.write(data, 0, data.length);
		CRC32 crc = new CRC32();
		crc.update(t);
		crc.update(data);
		out.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt((int) crc.getValue()).array(), 0, 4);
	}

	static byte[] ninePatchPng(boolean withInsets) {
		int w = 8, h = 8;
		ByteArrayOutputStream png = new ByteArrayOutputStream();
		png.write(new byte[] { (byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a }, 0, 8);
		ByteBuffer ihdr = ByteBuffer.allocate(13).order(ByteOrder.BIG_ENDIAN);
		ihdr.putInt(w).putInt(h).put((byte) 8).put((byte) 6).put((byte) 0).put((byte) 0).put((byte) 0);
		chunk(png, "IHDR", ihdr.array());
		/* npTc (Res_png_9patch, serialized): divs, padding and colours in network order, the offsets
		 * as the structure holds them */
		ByteBuffer tc = ByteBuffer.allocate(32 + 4 * (2 + 2 + 9));
		tc.order(ByteOrder.LITTLE_ENDIAN).put((byte) 0).put((byte) 2).put((byte) 2).put((byte) 9).putInt(32).putInt(40);
		tc.order(ByteOrder.BIG_ENDIAN).putInt(1).putInt(1).putInt(1).putInt(1);
		tc.order(ByteOrder.LITTLE_ENDIAN).putInt(48);
		tc.order(ByteOrder.BIG_ENDIAN).putInt(2).putInt(6).putInt(2).putInt(6);
		for (int i = 0; i < 9; i++)
			tc.putInt(1); /* Res_png_9patch::NO_COLOR */
		chunk(png, "npTc", tc.array());
		if (withInsets) {
			ByteBuffer lb = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
			lb.putInt(1).putInt(2).putInt(1).putInt(2);
			chunk(png, "npLb", lb.array());
			ByteBuffer ol = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
			ol.putInt(1).putInt(1).putInt(1).putInt(1).putFloat(2.5f).putInt(0x80);
			chunk(png, "npOl", ol.array());
		}
		byte[] raw = new byte[h * (1 + 4 * w)];
		for (int y = 0; y < h; y++)
			for (int x = 0; x < w; x++) {
				int o = y * (1 + 4 * w) + 1 + 4 * x;
				raw[o] = (byte) 0x80;
				raw[o + 1] = (byte) 0x80;
				raw[o + 2] = (byte) 0x80;
				raw[o + 3] = (byte) 0xff;
			}
		Deflater d = new Deflater();
		d.setInput(raw);
		d.finish();
		byte[] buf = new byte[4096];
		int n = d.deflate(buf);
		byte[] idat = new byte[n];
		System.arraycopy(buf, 0, idat, 0, n);
		chunk(png, "IDAT", idat);
		chunk(png, "IEND", new byte[0]);
		return png.toByteArray();
	}

	static void ninePatch() {
		Drawable d = Drawable.createFromStream(new ByteArrayInputStream(ninePatchPng(true)), "test.9.png");
		check("a .9.png stream decodes to a NinePatchDrawable", d instanceof NinePatchDrawable, String.valueOf(d));
		if (!(d instanceof NinePatchDrawable))
			return;
		d.setBounds(0, 0, 40, 40);
		Insets in = d.getOpticalInsets();
		check("getOpticalInsets: the npLb layout bounds", in.equals(Insets.of(1, 2, 1, 2)), "" + in);
		Outline o = new Outline();
		d.getOutline(o);
		Rect r = new Rect();
		boolean rect = o.getRect(r);
		check("getOutline: bounds minus the npOl insets, its radius", rect && r.equals(new Rect(1, 1, 39, 39)) && Math.abs(o.getRadius() - 2.5f) < 1e-3f, rect + " " + r + " " + o.getRadius());
		check("getOutline: the npOl alpha", Math.abs(o.getAlpha() - 0x80 / 255f) < 1e-3f, "" + o.getAlpha());
		d.setAlpha(0x80);
		d.getOutline(o);
		check("getOutline: alpha times the drawable's", Math.abs(o.getAlpha() - (0x80 / 255f) * (0x80 / 255f)) < 1e-3f, "" + o.getAlpha());
		Rect pad = new Rect();
		check("getPadding: the npTc padding", d.getPadding(pad) && pad.equals(new Rect(1, 1, 1, 1)), "" + pad);

		Drawable plain = Drawable.createFromStream(new ByteArrayInputStream(ninePatchPng(false)), "plain.9.png");
		plain.setBounds(0, 0, 40, 40);
		Outline po = new Outline();
		plain.getOutline(po);
		Rect pr = new Rect();
		check("without npLb/npOl: no optical insets, the outline is the bounds",
		      plain.getOpticalInsets().equals(Insets.NONE) && po.getRect(pr) && pr.equals(new Rect(0, 0, 40, 40)), plain.getOpticalInsets() + " " + pr);
	}

	public static void main(String[] args) {
		System.loadLibrary("translation_layer_main"); /* the executable loads api-impl's natives in an app */
		for (Runnable r : new Runnable[] { GfxTest::fillPath, GfxTest::pathBounds, GfxTest::matrixCopy, GfxTest::strokeWidth, GfxTest::clipBounds, GfxTest::ninePatch, GfxTest::runCases }) {
			try {
				r.run();
			} catch (Throwable t) {
				t.printStackTrace(System.out);
				check("no exception", false, t.toString());
			}
		}
		System.out.println("RESULT " + passed + "/" + total);
		System.exit(passed == total ? 0 : 1);
	}
}
