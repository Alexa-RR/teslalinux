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

	static boolean near(int a, int b, int tol) {
		for (int s = 0; s < 32; s += 8)
			if (Math.abs(((a >>> s) & 0xff) - ((b >>> s) & 0xff)) > tol)
				return false;
		return true;
	}

	static String hex(int c) {
		return String.format("%08x", c);
	}

	static boolean throwsOf(Class<? extends Throwable> type, Runnable r) {
		try {
			r.run();
		} catch (Throwable t) {
			return type.isInstance(t);
		}
		return false;
	}

	/* ---- Bitmap pixel access (AOSP: colour ints are un-premultiplied; setPixels replaces) ---- */
	static void pixels() {
		Bitmap b = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888);
		new Canvas(b).drawColor(0x80FF0000);
		check("getPixel returns un-premultiplied colour", near(b.getPixel(1, 1), 0x80FF0000, 1), hex(b.getPixel(1, 1)));
		int[] row = new int[4];
		b.getPixels(row, 0, 4, 0, 2, 4, 1);
		check("getPixels returns un-premultiplied colour", near(row[3], 0x80FF0000, 1), hex(row[3]));

		Bitmap s = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888);
		s.setPixel(0, 0, 0xFFFF0000);
		check("setPixel keeps the channels (red stays red)", s.getPixel(0, 0) == 0xFFFF0000, hex(s.getPixel(0, 0)));
		s.setPixel(1, 0, 0x80336699);
		check("setPixel/getPixel round trip of a translucent colour", near(s.getPixel(1, 0), 0x80336699, 2), hex(s.getPixel(1, 0)));
		s.eraseColor(0xFF0000FF);
		s.setPixel(2, 2, 0x00000000);
		check("setPixel replaces the pixel (transparent over blue is transparent)", s.getPixel(2, 2) == 0, hex(s.getPixel(2, 2)));
		check("setPixel leaves the other pixels", s.getPixel(1, 2) == 0xFF0000FF, hex(s.getPixel(1, 2)));
		s.setPixel(3, 3, 0x40FFFFFF);
		check("setPixel of a translucent colour over opaque blue replaces it", near(s.getPixel(3, 3), 0x40FFFFFF, 2), hex(s.getPixel(3, 3)));

		int[] src = { 9, 0xFF010203, 0xFF040506, 9, 0xFF070809, 0xFF0A0B0C };
		Bitmap r = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888);
		r.setPixels(src, 1, 3, 1, 1, 2, 2);
		check("setPixels with offset and stride", r.getPixel(1, 1) == 0xFF010203 && r.getPixel(2, 1) == 0xFF040506 && r.getPixel(1, 2) == 0xFF070809 && r.getPixel(2, 2) == 0xFF0A0B0C
		      && r.getPixel(0, 0) == 0, hex(r.getPixel(1, 1)) + " " + hex(r.getPixel(2, 2)) + " " + hex(r.getPixel(0, 0)));
		int[] out = new int[6];
		r.getPixels(out, 1, 3, 1, 1, 2, 2);
		check("getPixels with offset and stride", out[1] == 0xFF010203 && out[2] == 0xFF040506 && out[4] == 0xFF070809 && out[5] == 0xFF0A0B0C && out[0] == 0,
		      hex(out[1]) + " " + hex(out[5]));
		/* drawn into after a pixel write: the drawing goes over the written pixels */
		Canvas rc = new Canvas(r);
		rc.drawRect(0, 0, 1, 1, paint(0xFF00FF00));
		check("drawing after setPixels keeps the written pixels", r.getPixel(0, 0) == 0xFF00FF00 && r.getPixel(1, 1) == 0xFF010203, hex(r.getPixel(0, 0)) + " " + hex(r.getPixel(1, 1)));

		check("getPixel outside the bitmap: IllegalArgumentException", throwsOf(IllegalArgumentException.class, () -> r.getPixel(4, 0)), null);
		check("getPixels beyond the bitmap: IllegalArgumentException", throwsOf(IllegalArgumentException.class, () -> r.getPixels(new int[16], 0, 4, 1, 0, 4, 1)), null);
		check("getPixels into a short array: ArrayIndexOutOfBoundsException", throwsOf(ArrayIndexOutOfBoundsException.class, () -> r.getPixels(new int[3], 0, 4, 0, 0, 4, 1)), null);
		Bitmap imm = Bitmap.createBitmap(new int[] { 0xFF000000 }, 1, 1, Bitmap.Config.ARGB_8888);
		check("setPixel on an immutable bitmap: IllegalStateException", throwsOf(IllegalStateException.class, () -> imm.setPixel(0, 0, 0)), null);
		check("createBitmap(int[]) keeps the colours", imm.getPixel(0, 0) == 0xFF000000, hex(imm.getPixel(0, 0)));
		Bitmap cb = Bitmap.createBitmap(new int[] { 0xFF102030, 0x80FF8000 }, 2, 1, Bitmap.Config.ARGB_8888);
		check("createBitmap(int[]) of translucent colours", cb.getPixel(0, 0) == 0xFF102030 && near(cb.getPixel(1, 0), 0x80FF8000, 2), hex(cb.getPixel(0, 0)) + " " + hex(cb.getPixel(1, 0)));

		/* RGB_565: the colour is stored in 5/6/5 bits, read back widened */
		Bitmap q = Bitmap.createBitmap(2, 1, Bitmap.Config.RGB_565);
		q.setPixel(0, 0, 0xFF123456);
		check("RGB_565 setPixel stores 5/6/5 bits", q.getPixel(0, 0) == 0xFF103452, hex(q.getPixel(0, 0)));

		/* raw memory: premultiplied RGBA bytes, copied exactly both ways */
		Bitmap m = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
		m.setPixel(0, 0, 0x80FF0000);
		ByteBuffer buf = ByteBuffer.allocate(4);
		m.copyPixelsToBuffer(buf);
		byte[] bytes = buf.array();
		check("copyPixelsToBuffer: premultiplied RGBA bytes", (bytes[0] & 0xff) == 0x80 && bytes[1] == 0 && bytes[2] == 0 && (bytes[3] & 0xff) == 0x80,
		      (bytes[0] & 0xff) + "," + (bytes[1] & 0xff) + "," + (bytes[2] & 0xff) + "," + (bytes[3] & 0xff));
		Bitmap m2 = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
		m2.copyPixelsFromBuffer(ByteBuffer.wrap(new byte[] { 0x21, 0x13, 0x05, 0x40 }));
		ByteBuffer back = ByteBuffer.allocate(4);
		m2.copyPixelsToBuffer(back);
		byte[] bb = back.array();
		check("copyPixelsFromBuffer then copyPixelsToBuffer: the same bytes", bb[0] == 0x21 && bb[1] == 0x13 && bb[2] == 0x05 && bb[3] == 0x40,
		      (bb[0] & 0xff) + "," + (bb[1] & 0xff) + "," + (bb[2] & 0xff) + "," + (bb[3] & 0xff));

		/* a loop of getPixel/setPixel over a whole bitmap stays fast (the pixel memory is kept) */
		Bitmap big = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888);
		long t0 = System.nanoTime();
		for (int y = 0; y < 200; y++)
			for (int x = 0; x < 200; x++)
				big.setPixel(x, y, 0xFF000000 | (x << 8) | y);
		int sum = 0;
		for (int y = 0; y < 200; y++)
			for (int x = 0; x < 200; x++)
				sum += big.getPixel(x, y) & 0xff;
		long ms = (System.nanoTime() - t0) / 1000000;
		check("40000 setPixel + 40000 getPixel calls in under 3 s", ms < 3000 && sum == 200 * (199 * 200 / 2), ms + " ms, sum " + sum);
	}

	static Paint paint(int color) {
		Paint p = new Paint();
		p.setColor(color);
		return p;
	}

	/* ---- the Region.Ops that expand the clip: kept for apps targeting API 27, refused from 28 ---- */
	static void legacyClipOps() {
		int saved = Canvas.tlCompatibilityVersion();
		try {
			Bitmap b = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888);
			Canvas c = new Canvas(b);
			Canvas.setCompatibilityVersion(28);
			check("target 28: clipRect with UNION throws IllegalArgumentException",
			      throwsOf(IllegalArgumentException.class, () -> c.clipRect(0, 0, 10, 10, android.graphics.Region.Op.UNION)), null);
			check("target 28: clipPath with REPLACE throws IllegalArgumentException",
			      throwsOf(IllegalArgumentException.class, () -> c.clipPath(new Path(), android.graphics.Region.Op.REPLACE)), null);
			Canvas.setCompatibilityVersion(27);
			c.save();
			c.clipRect(0, 0, 10, 10);
			boolean ne = c.clipRect(20, 20, 30, 30, android.graphics.Region.Op.UNION);
			check("target 27: UNION of two rects: non-empty, the bounds of both", ne && c.getClipBounds().equals(new Rect(0, 0, 30, 30)), ne + " " + c.getClipBounds());
			boolean xr = c.clipRect(0, 0, 10, 10, android.graphics.Region.Op.XOR);
			check("XOR removes the first rect again", xr && c.getClipBounds().equals(new Rect(20, 20, 30, 30)), xr + " " + c.getClipBounds());
			c.restore();
			check("restore: the clip before them", c.getClipBounds().equals(new Rect(0, 0, 40, 40)), "" + c.getClipBounds());
			c.save();
			c.clipRect(0, 0, 10, 10);
			boolean rd = c.clipRect(0, 0, 10, 10, android.graphics.Region.Op.REVERSE_DIFFERENCE);
			check("REVERSE_DIFFERENCE of the same rect: empty, false", !rd && c.getClipBounds().isEmpty(), rd + " " + c.getClipBounds());
			boolean rp = c.clipRect(5, 5, 15, 15, android.graphics.Region.Op.REPLACE);
			check("REPLACE: just the new rect", rp && c.getClipBounds().equals(new Rect(5, 5, 15, 15)), rp + " " + c.getClipBounds());
			c.restore();
		} finally {
			Canvas.setCompatibilityVersion(saved);
		}
	}

	static boolean mapsTo(android.graphics.Matrix m, float[] src, float[] dst) {
		float[] out = new float[src.length];
		m.mapPoints(out, src);
		for (int i = 0; i < out.length; i++)
			if (Math.abs(out[i] - dst[i]) > 1e-3f)
				return false;
		return true;
	}

	/* ---- Matrix operations that were declared without natives (SkMatrix's) ---- */
	static void matrixOps() {
		android.graphics.Matrix m = new android.graphics.Matrix();
		float[] s3 = { 0, 0, 10, 0, 0, 10 }, d3 = { 5, 5, 25, 5, 5, 15 };
		check("setPolyToPoly with 3 points maps them", m.setPolyToPoly(s3, 0, d3, 0, 3) && mapsTo(m, s3, d3), m.toString());
		float[] s4 = { 0, 0, 10, 0, 10, 10, 0, 10 }, d4 = { 0, 0, 20, 2, 18, 12, 1, 9 };
		check("setPolyToPoly with 4 points (perspective) maps them", m.setPolyToPoly(s4, 0, d4, 0, 4) && mapsTo(m, s4, d4), m.toString());
		float[] s2 = { 0, 0, 10, 0 }, d2 = { 10, 10, 10, 20 };
		check("setPolyToPoly with 2 points: rotation and scale", m.setPolyToPoly(s2, 0, d2, 0, 2) && mapsTo(m, s2, d2) && mapsTo(m, new float[] { 0, 10 }, new float[] { 0, 10 }), m.toString());
		check("setPolyToPoly of degenerate points fails", !m.setPolyToPoly(new float[] { 0, 0, 0, 0, 0, 0 }, 0, d3, 0, 3), null);
		m.setSkew(0.5f, 0);
		check("setSkew", mapsTo(m, new float[] { 0, 10 }, new float[] { 5, 10 }), m.toString());
		m.setSkew(0.5f, 0, 0, 10);
		check("setSkew about a pivot", mapsTo(m, new float[] { 0, 10, 0, 0 }, new float[] { 0, 10, -5, 0 }), m.toString());
		m.setTranslate(1, 0);
		m.preSkew(1, 0);
		check("preSkew: the skew first", mapsTo(m, new float[] { 0, 2 }, new float[] { 3, 2 }), m.toString());
		m.setTranslate(0, 1);
		m.postSkew(1, 0);
		check("postSkew: the skew last", mapsTo(m, new float[] { 0, 0 }, new float[] { 1, 1 }), m.toString());
		m.setSinCos(1, 0);
		check("setSinCos(1, 0) rotates 90 degrees", mapsTo(m, new float[] { 1, 0 }, new float[] { 0, 1 }), m.toString());
		android.graphics.Matrix a = new android.graphics.Matrix(), b = new android.graphics.Matrix();
		a.setScale(2, 2);
		b.setTranslate(3, 0);
		m.setConcat(a, b);
		check("setConcat(a, b): b first, then a", mapsTo(m, new float[] { 0, 0 }, new float[] { 6, 0 }), m.toString());
		m.setScale(2, 8);
		check("mapRadius: the geometric mean of the scales", Math.abs(m.mapRadius(1) - 4) < 1e-4f, "" + m.mapRadius(1));
	}

	/* ---- Path.approximate and PathInterpolator(Path) (AOSP: Skia's flattening, hwui Path.cpp) ---- */
	static void approximate() {
		Path p = new Path();
		p.moveTo(0, 0);
		p.lineTo(3, 4);
		p.lineTo(3, 9);
		float[] a = p.approximate(0.5f);
		check("approximate of two lines: (fraction, x, y) of each point", a.length == 9 && a[0] == 0 && a[3] == 0.5f && a[4] == 3 && a[5] == 4 && a[6] == 1 && a[8] == 9,
		      java.util.Arrays.toString(a));
		Path jump = new Path();
		jump.lineTo(0.25f, 0.25f);
		jump.moveTo(0.25f, 0.5f);
		jump.lineTo(1, 1);
		float[] j = jump.approximate(0.002f);
		check("a move is a point at the same fraction", j.length == 12 && j[3] == j[6] && j[7] == 0.25f && j[8] == 0.5f, java.util.Arrays.toString(j));
		Path cubic = new Path();
		cubic.cubicTo(0.4f, 0, 0.2f, 1, 1, 1);
		float[] c = cubic.approximate(0.002f);
		boolean close = c.length / 3 > 10;
		for (int i = 0; close && i + 5 < c.length; i += 3) {
			// every chord's midpoint lies on the curve within the error (checked against the point at the
			// matching parameter by a fine search)
			float mx = (c[i + 1] + c[i + 4]) / 2, my = (c[i + 2] + c[i + 5]) / 2;
			float best = Float.MAX_VALUE;
			for (int k = 0; k <= 2000; k++) {
				float t = k / 2000f, mt = 1 - t;
				float x = 3 * mt * mt * t * 0.4f + 3 * mt * t * t * 0.2f + t * t * t, y = 3 * mt * t * t + t * t * t;
				best = Math.min(best, (x - mx) * (x - mx) + (y - my) * (y - my));
			}
			close = Math.sqrt(best) < 0.004f && c[i] <= c[i + 3];
		}
		check("approximate of a cubic: chords within the error, fractions increasing", close, c.length / 3 + " points");
		check("approximate of an empty path: (0, 0) twice", java.util.Arrays.equals(new Path().approximate(0.5f), new float[] { 0, 0, 0, 1, 0, 0 }),
		      java.util.Arrays.toString(new Path().approximate(0.5f)));
		android.view.animation.PathInterpolator pi = new android.view.animation.PathInterpolator(jump);
		check("PathInterpolator(Path) follows the path (jump at x 0.25)", Math.abs(pi.getInterpolation(0.1f) - 0.1f) < 1e-3f && Math.abs(pi.getInterpolation(0.5f) - (0.5f + (0.5f - 0.25f) / 0.75f * 0.5f)) < 1e-3f,
		      pi.getInterpolation(0.1f) + " " + pi.getInterpolation(0.5f));
		android.view.animation.PathInterpolator fosi = new android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f);
		check("PathInterpolator cubic (fast_out_slow_in) at 0.5", Math.abs(fosi.getInterpolation(0.5f) - 0.7756f) < 0.003f, "" + fosi.getInterpolation(0.5f));
		check("PathInterpolator of a path not ending at (1, 1) is refused", throwsOf(IllegalArgumentException.class, () -> {
			Path bad = new Path();
			bad.lineTo(1, 0.5f);
			new android.view.animation.PathInterpolator(bad);
		}), null);
		Path parsed = android.util.PathParser.createPathFromPathData("M 0,0 L 0.5,0.2 L 1,1");
		check("PathParser.createPathFromPathData (pathInterpolator pathData)", Math.abs(new android.view.animation.PathInterpolator(parsed).getInterpolation(0.5f) - 0.2f) < 1e-3f, null);
	}

	/** an animation target with float, int and two-argument setters */
	public static class AnimTarget {
		public float x, y;
		public int color;
		public float a, b;
		public void setX(float v) { x = v; }
		public float getX() { return x; }
		public void setY(float v) { y = v; }
		public float getY() { return y; }
		public void setColor(int v) { color = v; }
		public int getColor() { return color; }
		public void setPos(float a, float b) { this.a = a; this.b = b; }
	}

	static float pvhValue(android.animation.PropertyValuesHolder p, float fraction) {
		p.calculateValue(fraction);
		return ((Number) p.getAnimatedValue()).floatValue();
	}

	/* ---- PropertyValuesHolder / ObjectAnimator values (AOSP KeyframeSet, evaluators, Path) ---- */
	static void animatorValues() {
		android.animation.PropertyValuesHolder f = android.animation.PropertyValuesHolder.ofFloat("x", 0, 10, 30);
		check("ofFloat with three values: the second interval at 0.75", Math.abs(pvhValue(f, 0.75f) - 20) < 1e-3f, "" + pvhValue(f, 0.75f));
		android.animation.PropertyValuesHolder o = android.animation.PropertyValuesHolder.ofFloat("x", 0, 10);
		check("an overshooting fraction extrapolates (not clamped)", Math.abs(pvhValue(o, 1.2f) - 12) < 1e-3f, "" + pvhValue(o, 1.2f));
		android.animation.PropertyValuesHolder c = android.animation.PropertyValuesHolder.ofInt("color", 0xFFFF0000, 0xFF0000FF);
		c.setEvaluator(android.animation.ArgbEvaluator.getInstance());
		c.calculateValue(0.5f);
		Object want = android.animation.ArgbEvaluator.getInstance().evaluate(0.5f, 0xFFFF0000, 0xFF0000FF);
		check("ofInt with ArgbEvaluator uses the evaluator", want.equals(c.getAnimatedValue()), c.getAnimatedValue() + " want " + want);
		android.animation.PropertyValuesHolder k = android.animation.PropertyValuesHolder.ofKeyframe("x", android.animation.Keyframe.ofFloat(0f, 0f),
		                                                                                     android.animation.Keyframe.ofFloat(0.5f, 100f), android.animation.Keyframe.ofFloat(1f, 0f));
		check("ofKeyframe: the keyframes are evaluated", Math.abs(pvhValue(k, 0.25f) - 50) < 1e-3f && Math.abs(pvhValue(k, 0.75f) - 50) < 1e-3f, pvhValue(k, 0.25f) + " " + pvhValue(k, 0.75f));
		Path p = new Path();
		p.moveTo(0, 0);
		p.lineTo(100, 0);
		p.lineTo(100, 100);
		AnimTarget t = new AnimTarget();
		android.animation.ObjectAnimator a = android.animation.ObjectAnimator.ofFloat(t, "x", "y", p);
		android.animation.PropertyValuesHolder[] vs = a.getValues();
		boolean ok = vs != null && vs.length == 2;
		if (ok) {
			for (android.animation.PropertyValuesHolder v : vs) {
				v.setupSetterAndGetter(t);
				v.calculateValue(0.75f);
				v.setAnimatedValue(t);
			}
		}
		check("ObjectAnimator.ofFloat(target, x, y, Path): three quarters along the path", ok && Math.abs(t.x - 100) < 0.5f && Math.abs(t.y - 50) < 0.5f, t.x + "," + t.y);
		android.animation.PropertyValuesHolder m = android.animation.PropertyValuesHolder.ofMultiFloat("pos", p);
		m.setupSetterAndGetter(t);
		m.calculateValue(0.25f);
		m.setAnimatedValue(t);
		check("ofMultiFloat(name, Path) calls the two-argument setter", Math.abs(t.a - 50) < 0.5f && Math.abs(t.b) < 0.5f, t.a + "," + t.b);
		android.animation.PropertyValuesHolder conv = android.animation.PropertyValuesHolder.ofObject("x", new android.animation.TypeConverter<android.graphics.PointF, Float>(android.graphics.PointF.class, Float.class) {
			@Override
			public Float convert(android.graphics.PointF value) {
				return value.x + value.y;
			}
		}, p);
		conv.setupSetterAndGetter(t);
		conv.calculateValue(1f);
		conv.setAnimatedValue(t);
		check("ofObject(name, TypeConverter, Path) converts the point", Math.abs(t.x - 200) < 0.5f, "" + t.x);
		check("PropertyValuesHolder.getPropertyName (AOSP)", "x".equals(conv.getPropertyName()), conv.getPropertyName());
	}

	/* ---- GLUtils: the GL format and type of a bitmap's config (AOSP util.cpp) ---- */
	static void glUtils() {
		Bitmap a = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
		Bitmap r = Bitmap.createBitmap(2, 2, Bitmap.Config.RGB_565);
		Bitmap al = Bitmap.createBitmap(2, 2, Bitmap.Config.ALPHA_8);
		check("GLUtils ARGB_8888: GL_RGBA, GL_UNSIGNED_BYTE", android.opengl.GLUtils.getInternalFormat(a) == 0x1908 && android.opengl.GLUtils.getType(a) == 0x1401,
		      Integer.toHexString(android.opengl.GLUtils.getInternalFormat(a)) + " " + Integer.toHexString(android.opengl.GLUtils.getType(a)));
		check("GLUtils RGB_565: GL_RGB, GL_UNSIGNED_SHORT_5_6_5", android.opengl.GLUtils.getInternalFormat(r) == 0x1907 && android.opengl.GLUtils.getType(r) == 0x8363,
		      Integer.toHexString(android.opengl.GLUtils.getInternalFormat(r)) + " " + Integer.toHexString(android.opengl.GLUtils.getType(r)));
		check("GLUtils ALPHA_8: GL_ALPHA, GL_UNSIGNED_BYTE", android.opengl.GLUtils.getInternalFormat(al) == 0x1906 && android.opengl.GLUtils.getType(al) == 0x1401, null);
		check("GLUtils.getEGLErrorString", "EGL_BAD_SURFACE".equals(android.opengl.GLUtils.getEGLErrorString(0x300D)), android.opengl.GLUtils.getEGLErrorString(0x300D));
	}

	/* a bitmap read while a canvas on it has a clip open, and a copy drawn on */
	static void bitmapReads() {
		android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(10, 10, android.graphics.Bitmap.Config.ARGB_8888);
		android.graphics.Canvas c = new android.graphics.Canvas(bmp);
		c.drawColor(0xFFFF0000);
		c.save();
		c.translate(1, 1);
		c.clipRect(0, 0, 4, 4);
		int mid = bmp.getPixel(0, 0); // read with the clip open
		c.drawColor(0xFF0000FF);
		c.restore();
		c.drawRect(8, 8, 10, 10, paint(0xFF00FF00));
		check("a read with a clip open sees the drawing so far", mid == 0xFFFF0000, Integer.toHexString(mid));
		check("... the drawing made before the clip stays", bmp.getPixel(9, 0) == 0xFFFF0000 && bmp.getPixel(0, 9) == 0xFFFF0000, Integer.toHexString(bmp.getPixel(9, 0)));
		check("... the clip and transform still apply after the read", bmp.getPixel(2, 2) == 0xFF0000FF && bmp.getPixel(0, 0) == 0xFFFF0000 && bmp.getPixel(6, 6) == 0xFFFF0000,
		      Integer.toHexString(bmp.getPixel(2, 2)) + " " + Integer.toHexString(bmp.getPixel(0, 0)) + " " + Integer.toHexString(bmp.getPixel(6, 6)));
		check("... and the restore ends them", bmp.getPixel(9, 9) == 0xFF00FF00, Integer.toHexString(bmp.getPixel(9, 9)));
		android.graphics.Bitmap src = android.graphics.Bitmap.createBitmap(10, 10, android.graphics.Bitmap.Config.ARGB_8888);
		src.eraseColor(0xFFFF0000);
		android.graphics.Bitmap copy = src.copy(android.graphics.Bitmap.Config.ARGB_8888, true);
		new android.graphics.Canvas(copy).drawRect(0, 0, 5, 5, paint(0xFF0000FF));
		check("drawing on a copy keeps the copied pixels", copy.getPixel(9, 9) == 0xFFFF0000 && copy.getPixel(1, 1) == 0xFF0000FF,
		      Integer.toHexString(copy.getPixel(9, 9)) + " " + Integer.toHexString(copy.getPixel(1, 1)));
		check("... and leaves the source alone", src.getPixel(1, 1) == 0xFFFF0000, Integer.toHexString(src.getPixel(1, 1)));
		android.graphics.Bitmap drawn = android.graphics.Bitmap.createBitmap(10, 10, android.graphics.Bitmap.Config.ARGB_8888);
		new android.graphics.Canvas(drawn).drawColor(0xFF00FF00);
		android.graphics.Bitmap copy2 = drawn.copy(android.graphics.Bitmap.Config.ARGB_8888, true);
		new android.graphics.Canvas(copy2).drawRect(0, 0, 5, 5, paint(0xFF0000FF));
		check("a copy of a drawn bitmap keeps its drawing too", copy2.getPixel(9, 9) == 0xFF00FF00, Integer.toHexString(copy2.getPixel(9, 9)));
	}

	public static void main(String[] args) {
		System.loadLibrary("translation_layer_main"); /* the executable loads api-impl's natives in an app */
		for (Runnable r : new Runnable[] { GfxTest::fillPath, GfxTest::pathBounds, GfxTest::matrixCopy, GfxTest::strokeWidth, GfxTest::clipBounds, GfxTest::ninePatch,
		                                   GfxTest::pixels, GfxTest::legacyClipOps, GfxTest::matrixOps, GfxTest::approximate, GfxTest::animatorValues, GfxTest::glUtils,
		                                   GfxTest::bitmapReads, GfxTest::runCases }) {
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
