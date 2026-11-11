/*
 * Copyright (C) 2006 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.graphics;

import android.atl.GskCanvas;

/**
 * teslalinux: AOSP's Canvas API on GSK.
 *
 * android.atl.GskCanvas (a subclass) implements the drawing primitives on a GtkSnapshot. A plain
 * Canvas draws into a Bitmap through a GskCanvas of its own: every primitive hands the bitmap's
 * current snapshot to it first (the bitmap turns its snapshot into a texture whenever it is read).
 * Everything that AOSP builds from the primitives (text variants, colours, pictures, double round
 * rects, text on a path, meshes, ...) is built the same way here, through virtual calls, so it works
 * for both.
 */
public class Canvas {

	public static enum EdgeType {
		/** Black-and-White: Treat edges by just rounding to nearest pixel boundary. */
		BW,
		/** Antialiased: Treat edges by rounding-out, since they may be antialiased. */
		AA;
	}

	public enum VertexMode {
		TRIANGLES,
		TRIANGLE_STRIP,
		TRIANGLE_FAN
	}

	public static final int MATRIX_SAVE_FLAG = 0x01;
	public static final int CLIP_SAVE_FLAG = 0x02;
	public static final int HAS_ALPHA_LAYER_SAVE_FLAG = 0x04;
	public static final int FULL_COLOR_LAYER_SAVE_FLAG = 0x08;
	public static final int CLIP_TO_LAYER_SAVE_FLAG = 0x10;
	public static final int ALL_SAVE_FLAG = 0x1F;

	private Bitmap bitmap;
	private GskCanvas gsk_canvas;
	private int tlDensity = 0;
	private DrawFilter mDrawFilter;

	public static int tlRedTraces;

	public Canvas() {
		if (!(this instanceof GskCanvas)) {
			gsk_canvas = new GskCanvas(0);
		}
	}

	public Canvas(Bitmap bmp) {
		this.bitmap = bmp;
		gsk_canvas = new GskCanvas(bmp.getSnapshot());
		gsk_canvas.tlBaseBitmap = bmp;
		tlDensity = bmp.getDensity();
	}

	/** The bitmap canvas's drawing target, synchronised with the bitmap's current snapshot. */
	private GskCanvas gc() {
		gsk_canvas.tlSetBase(bitmap.getSnapshot());
		return gsk_canvas;
	}

	/**
	 * @hide The GSK canvas this canvas draws through: itself for a view/RenderNode canvas, the bitmap's
	 * for a Canvas(Bitmap), null without a target. Code that draws through GTK/GSK natives (views,
	 * drawables backed by a paintable, nine-patches, text layouts) uses it, so drawing into a Bitmap
	 * draws the same as on screen (AOSP: View.draw on a bitmap canvas, androidx drawToBitmap).
	 */
	public GskCanvas tlGskCanvas() {
		if (this instanceof GskCanvas)
			return (GskCanvas) this;
		return tlNoTarget() ? null : gc();
	}

	/** A plain Canvas without a bitmap draws nothing (Skia's canvas without a device). */
	private boolean tlNoTarget() {
		return bitmap == null || gsk_canvas == null;
	}

	public void setBitmap(Bitmap bitmap) {
		if (bitmap != null && !bitmap.isMutable()) {
			throw new IllegalStateException("Bitmap must be mutable");
		}
		this.bitmap = bitmap;
		if (gsk_canvas != null) {
			gsk_canvas.tlBaseBitmap = bitmap;
			gsk_canvas.snapshot = bitmap == null ? 0 : bitmap.getSnapshot();
		}
	}

	public boolean isHardwareAccelerated() {
		return false;
	}

	public boolean isOpaque() {
		return bitmap != null && !bitmap.hasAlpha();
	}

	public int getWidth() {
		return (bitmap == null) ? 0 : bitmap.getWidth();
	}

	public int getHeight() {
		return (bitmap == null) ? 0 : bitmap.getHeight();
	}

	public int getDensity() {
		return tlDensity;
	}

	public void setDensity(int density) {
		if (bitmap != null)
			bitmap.setDensity(density);
		tlDensity = density;
	}

	/** Skia's limit for a raster canvas. */
	public int getMaximumBitmapWidth() {
		return 32766;
	}

	public int getMaximumBitmapHeight() {
		return 32766;
	}

	// ---- state ------------------------------------------------------------------------------

	public int save() {
		if (tlNoTarget())
			return 1;
		return gc().save();
	}

	/** API 21 save with flags; AOSP (API 28+) always saves matrix and clip. */
	public int save(int saveFlags) {
		return save();
	}

	public void restore() {
		if (tlNoTarget())
			return;
		gc().restore();
	}

	public int getSaveCount() {
		if (tlNoTarget())
			return 1;
		return gc().getSaveCount();
	}

	public void restoreToCount(int count) {
		if (count < 1)
			throw new IllegalArgumentException("count must be >= 1");
		while (getSaveCount() > count)
			restore();
	}

	/** The layer behind every saveLayer variant: bounds null means the current clip. */
	public int tlSaveLayer(RectF bounds, Paint paint) {
		if (tlNoTarget())
			return save();
		return gc().tlSaveLayer(bounds, paint);
	}

	public int saveLayer(RectF bounds, Paint paint, int saveFlags) {
		return tlSaveLayer(bounds, paint);
	}

	public int saveLayer(RectF bounds, Paint paint) {
		return tlSaveLayer(bounds, paint);
	}

	public int saveLayer(float left, float top, float right, float bottom, Paint paint, int saveFlags) {
		return tlSaveLayer(new RectF(left, top, right, bottom), paint);
	}

	public int saveLayer(float left, float top, float right, float bottom, Paint paint) {
		return tlSaveLayer(new RectF(left, top, right, bottom), paint);
	}

	public int saveLayerAlpha(RectF bounds, int alpha, int saveFlags) {
		return saveLayerAlpha(bounds, alpha);
	}

	public int saveLayerAlpha(RectF bounds, int alpha) {
		alpha = Math.min(255, Math.max(0, alpha));
		Paint p = null;
		if (alpha != 255) {
			p = new Paint();
			p.setAlpha(alpha);
		}
		return tlSaveLayer(bounds, p);
	}

	public int saveLayerAlpha(float left, float top, float right, float bottom, int alpha, int saveFlags) {
		return saveLayerAlpha(new RectF(left, top, right, bottom), alpha);
	}

	public int saveLayerAlpha(float left, float top, float right, float bottom, int alpha) {
		return saveLayerAlpha(new RectF(left, top, right, bottom), alpha);
	}

	// ---- matrix -----------------------------------------------------------------------------

	public void translate(float dx, float dy) {
		if (tlNoTarget())
			return;
		gc().translate(dx, dy);
	}

	public void scale(float sx, float sy) {
		if (tlNoTarget())
			return;
		gc().scale(sx, sy);
	}

	public final void scale(float sx, float sy, float px, float py) {
		if (sx == 1.0f && sy == 1.0f)
			return;
		translate(px, py);
		scale(sx, sy);
		translate(-px, -py);
	}

	public void rotate(float degrees) {
		if (tlNoTarget())
			return;
		gc().rotate(degrees);
	}

	public final void rotate(float degrees, float px, float py) {
		if (degrees == 0.0f)
			return;
		translate(px, py);
		rotate(degrees);
		translate(-px, -py);
	}

	/** AOSP: preconcat the current matrix with the specified skew. */
	public void skew(float sx, float sy) {
		Matrix m = new Matrix();
		m.setSkew(sx, sy);
		concat(m);
	}

	public void concat(Matrix matrix) {
		if (tlNoTarget())
			return;
		gc().concat(matrix);
	}

	public void getMatrix(Matrix matrix) {
		if (tlNoTarget()) {
			matrix.reset();
			return;
		}
		gc().getMatrix(matrix);
	}

	public final Matrix getMatrix() {
		Matrix matrix = new Matrix();
		getMatrix(matrix);
		return matrix;
	}

	/** AOSP: replaces the current matrix (null: identity). */
	public void setMatrix(Matrix matrix) {
		Matrix current = getMatrix();
		Matrix target = matrix != null ? matrix : new Matrix();
		if (current.equals(target))
			return;
		Matrix inv = new Matrix();
		if (!current.invert(inv))
			return;
		inv.preConcat(target);
		concat(inv);
	}

	// ---- clip -------------------------------------------------------------------------------

	public boolean clipRect(float left, float top, float right, float bottom) {
		if (tlNoTarget())
			return false;
		return gc().clipRect(left, top, right, bottom);
	}

	public boolean clipRect(int left, int top, int right, int bottom) {
		return clipRect((float) left, (float) top, (float) right, (float) bottom);
	}

	public boolean clipRect(Rect rect) {
		return clipRect((float) rect.left, (float) rect.top, (float) rect.right, (float) rect.bottom);
	}

	public boolean clipRect(RectF rect) {
		return clipRect(rect.left, rect.top, rect.right, rect.bottom);
	}

	/** AOSP (API 28+): only INTERSECT and DIFFERENCE are valid. */
	public boolean clipRect(float left, float top, float right, float bottom, Region.Op op) {
		if (op == Region.Op.INTERSECT)
			return clipRect(left, top, right, bottom);
		if (op == Region.Op.DIFFERENCE)
			return clipOutRect(left, top, right, bottom);
		throw new IllegalArgumentException("Invalid Region.Op - only INTERSECT and DIFFERENCE are allowed");
	}

	public boolean clipRect(Rect rect, Region.Op op) {
		return clipRect((float) rect.left, (float) rect.top, (float) rect.right, (float) rect.bottom, op);
	}

	public boolean clipRect(RectF rect, Region.Op op) {
		return clipRect(rect.left, rect.top, rect.right, rect.bottom, op);
	}

	public boolean clipOutRect(float left, float top, float right, float bottom) {
		if (tlNoTarget())
			return false;
		return gc().clipOutRect(left, top, right, bottom);
	}

	public boolean clipOutRect(int left, int top, int right, int bottom) {
		return clipOutRect((float) left, (float) top, (float) right, (float) bottom);
	}

	public boolean clipOutRect(Rect rect) {
		return clipOutRect((float) rect.left, (float) rect.top, (float) rect.right, (float) rect.bottom);
	}

	public boolean clipOutRect(RectF rect) {
		return clipOutRect(rect.left, rect.top, rect.right, rect.bottom);
	}

	public boolean clipPath(Path path, Region.Op op) {
		if (tlNoTarget())
			return false;
		return gc().clipPath(path, op);
	}

	public boolean clipPath(Path path) {
		return clipPath(path, Region.Op.INTERSECT);
	}

	public boolean clipOutPath(Path path) {
		return clipPath(path, Region.Op.DIFFERENCE);
	}

	public boolean getClipBounds(Rect bounds) {
		if (tlNoTarget()) {
			if (bounds != null)
				bounds.setEmpty();
			return false;
		}
		return gc().getClipBounds(bounds);
	}

	public final Rect getClipBounds() {
		Rect rect = new Rect();
		getClipBounds(rect);
		return rect;
	}

	public boolean quickReject(float left, float top, float right, float bottom) {
		if (tlNoTarget())
			return true;
		return gc().quickReject(left, top, right, bottom);
	}

	public boolean quickReject(float left, float top, float right, float bottom, EdgeType type) {
		return quickReject(left, top, right, bottom);
	}

	public boolean quickReject(RectF rect) {
		return quickReject(rect.left, rect.top, rect.right, rect.bottom);
	}

	public boolean quickReject(RectF rect, EdgeType type) {
		return quickReject(rect);
	}

	public boolean quickReject(Path path) {
		RectF bounds = new RectF();
		path.computeBounds(bounds, true);
		return quickReject(bounds);
	}

	public boolean quickReject(Path path, EdgeType type) {
		return quickReject(path);
	}

	public DrawFilter getDrawFilter() {
		return mDrawFilter;
	}

	/**
	 * AOSP: a filter (PaintFlagsDrawFilter) that changes paint flags while drawing. The flags it
	 * can change (anti-aliasing, bitmap filtering, dithering) select Skia rasterisation modes;
	 * GSK always renders anti-aliased and filters scaled textures, so the filter is kept and
	 * reported but leaves the GSK output unchanged.
	 */
	public void setDrawFilter(DrawFilter filter) {
		mDrawFilter = filter;
	}

	// ---- primitives ---------------------------------------------------------------------------

	public void drawRect(float left, float top, float right, float bottom, Paint paint) {
		if (tlNoTarget())
			return;
		gc().drawRect(left, top, right, bottom, paint);
	}

	public void drawRect(RectF r, Paint paint) {
		drawRect(r.left, r.top, r.right, r.bottom, paint);
	}

	public void drawRect(Rect r, Paint paint) {
		drawRect(r.left, r.top, r.right, r.bottom, paint);
	}

	public void drawRoundRect(float left, float top, float right, float bottom, float rx, float ry, Paint paint) {
		if (tlNoTarget())
			return;
		gc().drawRoundRect(left, top, right, bottom, rx, ry, paint);
	}

	public void drawRoundRect(RectF rect, float rx, float ry, Paint paint) {
		drawRoundRect(rect.left, rect.top, rect.right, rect.bottom, rx, ry, paint);
	}

	public void drawCircle(float cx, float cy, float radius, Paint paint) {
		if (tlNoTarget())
			return;
		gc().drawCircle(cx, cy, radius, paint);
	}

	public void drawOval(float left, float top, float right, float bottom, Paint paint) {
		if (tlNoTarget())
			return;
		gc().drawOval(left, top, right, bottom, paint);
	}

	public void drawOval(RectF oval, Paint paint) {
		if (oval == null)
			throw new NullPointerException();
		drawOval(oval.left, oval.top, oval.right, oval.bottom, paint);
	}

	public void drawArc(float left, float top, float right, float bottom, float startAngle, float sweepAngle, boolean useCenter, Paint paint) {
		if (tlNoTarget())
			return;
		gc().drawArc(left, top, right, bottom, startAngle, sweepAngle, useCenter, paint);
	}

	public void drawArc(RectF oval, float startAngle, float sweepAngle, boolean useCenter, Paint paint) {
		if (oval == null)
			throw new NullPointerException();
		drawArc(oval.left, oval.top, oval.right, oval.bottom, startAngle, sweepAngle, useCenter, paint);
	}

	public void drawPath(Path path, Paint paint) {
		if (tlNoTarget())
			return;
		gc().drawPath(path, paint);
	}

	public void drawLine(float startX, float startY, float stopX, float stopY, Paint paint) {
		if (tlNoTarget())
			return;
		gc().drawLine(startX, startY, stopX, stopY, paint);
	}

	public void drawLines(float[] points, int offset, int count, Paint paint) {
		if (tlNoTarget())
			return;
		gc().drawLines(points, offset, count, paint);
	}

	public void drawLines(float[] points, Paint paint) {
		drawLines(points, 0, points.length, paint);
	}

	public void drawPoint(float x, float y, Paint paint) {
		if (tlNoTarget())
			return;
		gc().drawPoint(x, y, paint);
	}

	/** AOSP: count floats from offset, two per point. */
	public void drawPoints(float[] pts, int offset, int count, Paint paint) {
		for (int i = offset; i + 1 < offset + count; i += 2)
			drawPoint(pts[i], pts[i + 1], paint);
	}

	public void drawPoints(float[] pts, Paint paint) {
		drawPoints(pts, 0, pts.length, paint);
	}

	/** AOSP: fills the clip with the paint. */
	public void drawPaint(Paint paint) {
		Rect r = new Rect();
		if (!getClipBounds(r))
			return;
		drawRect(r.left, r.top, r.right, r.bottom, paint);
	}

	public void drawColor(int color) {
		if (System.getenv("ATL_DEBUG_RED") != null && color == 0xFFFF0000) {
			if (tlRedTraces++ < 3)
				new Throwable("[red] drawColor").printStackTrace(System.out);
		}
		Paint paint = new Paint();
		paint.setColor(color);
		drawPaint(paint);
	}

	/** AOSP: fills the clip with the colour through the mode (the colour is the source). */
	public void drawColor(int color, PorterDuff.Mode mode) {
		Paint paint = new Paint();
		paint.setColor(color);
		paint.setXfermode(new PorterDuffXfermode(mode));
		drawPaint(paint);
	}

	public void drawColor(int color, BlendMode mode) {
		Paint paint = new Paint();
		paint.setColor(color);
		paint.setBlendMode(mode);
		drawPaint(paint);
	}

	/** API 29: fill with a ColorLong. */
	public void drawColor(long color) {
		drawColor(Color.toArgb(color));
	}

	public void drawColor(long color, BlendMode mode) {
		drawColor(Color.toArgb(color), mode);
	}

	public void drawARGB(int a, int r, int g, int b) {
		drawColor(Color.argb(a, r, g, b));
	}

	public void drawRGB(int r, int g, int b) {
		drawColor(Color.rgb(r, g, b));
	}

	/** AOSP API 29: the area between two round rects (the inner one is cut out). */
	public void drawDoubleRoundRect(RectF outer, float outerRx, float outerRy, RectF inner, float innerRx, float innerRy, Paint paint) {
		Path p = new Path();
		p.setFillType(Path.FillType.EVEN_ODD);
		p.addRoundRect(outer, outerRx, outerRy, Path.Direction.CW);
		p.addRoundRect(inner, innerRx, innerRy, Path.Direction.CW);
		drawPath(p, paint);
	}

	public void drawDoubleRoundRect(RectF outer, float[] outerRadii, RectF inner, float[] innerRadii, Paint paint) {
		Path p = new Path();
		p.setFillType(Path.FillType.EVEN_ODD);
		p.addRoundRect(outer, outerRadii, Path.Direction.CW);
		p.addRoundRect(inner, innerRadii, Path.Direction.CW);
		drawPath(p, paint);
	}

	// ---- bitmaps ----------------------------------------------------------------------------

	public void drawBitmap(Bitmap bitmap, Rect src, Rect dst, Paint paint) {
		if (tlNoTarget())
			return;
		gc().drawBitmap(bitmap, src, dst, paint);
	}

	public void drawBitmap(Bitmap bitmap, Rect src, RectF dst, Paint paint) {
		drawBitmap(bitmap, src, new Rect(Math.round(dst.left), Math.round(dst.top), Math.round(dst.right), Math.round(dst.bottom)), paint);
	}

	/** AOSP: the bitmap at (left, top); a fractional position is kept with a translation. */
	public void drawBitmap(Bitmap bitmap, float left, float top, Paint paint) {
		int il = (int) Math.floor(left), it = (int) Math.floor(top);
		if (il != left || it != top) {
			save();
			translate(left - il, top - it);
			drawBitmap(bitmap, null, new Rect(il, it, il + bitmap.getWidth(), it + bitmap.getHeight()), paint);
			restore();
			return;
		}
		drawBitmap(bitmap, null, new Rect(il, it, il + bitmap.getWidth(), it + bitmap.getHeight()), paint);
	}

	public void drawBitmap(Bitmap bitmap, Matrix matrix, Paint paint) {
		save();
		concat(matrix);
		drawBitmap(bitmap, 0, 0, paint);
		restore();
	}

	/**
	 * AOSP: the colours as a bitmap at (x, y). hasAlpha false treats every pixel as opaque.
	 */
	public void drawBitmap(int[] colors, int offset, int stride, float x, float y, int width, int height, boolean hasAlpha, Paint paint) {
		if (width < 0)
			throw new IllegalArgumentException("width must be >= 0");
		if (height < 0)
			throw new IllegalArgumentException("height must be >= 0");
		if (Math.abs(stride) < width)
			throw new IllegalArgumentException("abs(stride) must be >= width");
		int lastScanline = offset + (height - 1) * stride;
		int length = colors.length;
		if (offset < 0 || (offset + width > length) || lastScanline < 0 || (lastScanline + width > length))
			throw new ArrayIndexOutOfBoundsException();
		if (width == 0 || height == 0)
			return;
		int[] px = new int[width * height];
		for (int row = 0; row < height; row++)
			for (int col = 0; col < width; col++) {
				int c = colors[offset + row * stride + col];
				px[row * width + col] = hasAlpha ? c : (c | 0xff000000);
			}
		drawBitmap(Bitmap.createBitmap(px, width, height, Bitmap.Config.ARGB_8888), x, y, paint);
	}

	public void drawBitmap(int[] colors, int offset, int stride, int x, int y, int width, int height, boolean hasAlpha, Paint paint) {
		drawBitmap(colors, offset, stride, (float) x, (float) y, width, height, hasAlpha, paint);
	}

	/**
	 * AOSP: the bitmap distorted over a mesh of (meshWidth+1) x (meshHeight+1) vertices. Each
	 * mesh cell is two triangles, each drawn as the bitmap under the affine map of its texture
	 * triangle onto the vertex triangle, clipped to that triangle (exact for every triangle).
	 * The optional per-vertex colours multiply the bitmap; they are averaged per triangle.
	 */
	public void drawBitmapMesh(Bitmap bitmap, int meshWidth, int meshHeight, float[] verts, int vertOffset, int[] colors, int colorOffset, Paint paint) {
		if ((meshWidth | meshHeight | vertOffset | colorOffset) < 0)
			throw new ArrayIndexOutOfBoundsException();
		if (meshWidth == 0 || meshHeight == 0)
			return;
		int count = (meshWidth + 1) * (meshHeight + 1);
		if (verts.length < vertOffset + count * 2 || (colors != null && colors.length < colorOffset + count))
			throw new ArrayIndexOutOfBoundsException();
		float bw = bitmap.getWidth(), bh = bitmap.getHeight();
		for (int my = 0; my < meshHeight; my++) {
			for (int mx = 0; mx < meshWidth; mx++) {
				int i00 = my * (meshWidth + 1) + mx, i10 = i00 + 1, i01 = i00 + meshWidth + 1, i11 = i01 + 1;
				float u0 = bw * mx / meshWidth, u1 = bw * (mx + 1) / meshWidth;
				float v0 = bh * my / meshHeight, v1 = bh * (my + 1) / meshHeight;
				tlMeshTriangle(bitmap, verts, vertOffset, colors, colorOffset, paint, i00, i10, i11, new float[] { u0, v0, u1, v0, u1, v1 });
				tlMeshTriangle(bitmap, verts, vertOffset, colors, colorOffset, paint, i00, i11, i01, new float[] { u0, v0, u1, v1, u0, v1 });
			}
		}
	}

	private void tlMeshTriangle(Bitmap bitmap, float[] verts, int vo, int[] colors, int co, Paint paint, int a, int b, int c, float[] tex) {
		float[] dst = { verts[vo + a * 2], verts[vo + a * 2 + 1], verts[vo + b * 2], verts[vo + b * 2 + 1], verts[vo + c * 2], verts[vo + c * 2 + 1] };
		Matrix m = new Matrix();
		if (!m.setPolyToPoly(tex, 0, dst, 0, 3))
			return;
		Paint p = paint != null ? new Paint(paint) : new Paint();
		if (colors != null) {
			int c0 = colors[co + a], c1 = colors[co + b], c2 = colors[co + c];
			int avg = Color.argb((Color.alpha(c0) + Color.alpha(c1) + Color.alpha(c2)) / 3, (Color.red(c0) + Color.red(c1) + Color.red(c2)) / 3,
			                     (Color.green(c0) + Color.green(c1) + Color.green(c2)) / 3, (Color.blue(c0) + Color.blue(c1) + Color.blue(c2)) / 3);
			p.setColorFilter(new PorterDuffColorFilter(avg, PorterDuff.Mode.MULTIPLY));
		}
		Path tri = new Path();
		tri.moveTo(tex[0], tex[1]);
		tri.lineTo(tex[2], tex[3]);
		tri.lineTo(tex[4], tex[5]);
		tri.close();
		save();
		concat(m);
		clipPath(tri);
		drawBitmap(bitmap, 0, 0, p);
		restore();
	}

	/**
	 * AOSP: triangles from vertices. With texs, the paint's shader is sampled at the texture
	 * coordinates (each triangle is drawn under the affine map from its texture triangle);
	 * without, the shader is in canvas coordinates. Per-vertex colours are averaged per triangle.
	 */
	public void drawVertices(VertexMode mode, int vertexCount, float[] verts, int vertOffset, float[] texs, int texOffset,
	                         int[] colors, int colorOffset, short[] indices, int indexOffset, int indexCount, Paint paint) {
		if (vertexCount < 0 || verts.length < vertOffset + vertexCount)
			throw new ArrayIndexOutOfBoundsException();
		int n = indices != null ? indexCount : vertexCount / 2;
		int[] idx = new int[Math.max(0, n)];
		for (int i = 0; i < idx.length; i++)
			idx[i] = indices != null ? indices[indexOffset + i] : i;
		java.util.ArrayList<int[]> tris = new java.util.ArrayList<>();
		if (mode == VertexMode.TRIANGLES) {
			for (int i = 0; i + 2 < idx.length; i += 3)
				tris.add(new int[] { idx[i], idx[i + 1], idx[i + 2] });
		} else if (mode == VertexMode.TRIANGLE_STRIP) {
			for (int i = 0; i + 2 < idx.length; i++)
				tris.add(new int[] { idx[i], idx[i + 1], idx[i + 2] });
		} else {
			for (int i = 1; i + 1 < idx.length; i++)
				tris.add(new int[] { idx[0], idx[i], idx[i + 1] });
		}
		for (int[] t : tris) {
			Path tri = new Path();
			tri.moveTo(verts[vertOffset + t[0] * 2], verts[vertOffset + t[0] * 2 + 1]);
			tri.lineTo(verts[vertOffset + t[1] * 2], verts[vertOffset + t[1] * 2 + 1]);
			tri.lineTo(verts[vertOffset + t[2] * 2], verts[vertOffset + t[2] * 2 + 1]);
			tri.close();
			Paint p = new Paint(paint);
			p.setStyle(Paint.Style.FILL);
			if (colors != null) {
				int c0 = colors[colorOffset + t[0]], c1 = colors[colorOffset + t[1]], c2 = colors[colorOffset + t[2]];
				int avg = Color.argb((Color.alpha(c0) + Color.alpha(c1) + Color.alpha(c2)) / 3, (Color.red(c0) + Color.red(c1) + Color.red(c2)) / 3,
				                     (Color.green(c0) + Color.green(c1) + Color.green(c2)) / 3, (Color.blue(c0) + Color.blue(c1) + Color.blue(c2)) / 3);
				if (paint.getShader() == null)
					p.setColor(avg);
				else
					p.setColorFilter(new PorterDuffColorFilter(avg, PorterDuff.Mode.MULTIPLY));
			}
			if (texs != null && paint.getShader() != null) {
				float[] tex = { texs[texOffset + t[0] * 2], texs[texOffset + t[0] * 2 + 1], texs[texOffset + t[1] * 2], texs[texOffset + t[1] * 2 + 1],
				                texs[texOffset + t[2] * 2], texs[texOffset + t[2] * 2 + 1] };
				float[] dst = { verts[vertOffset + t[0] * 2], verts[vertOffset + t[0] * 2 + 1], verts[vertOffset + t[1] * 2], verts[vertOffset + t[1] * 2 + 1],
				                verts[vertOffset + t[2] * 2], verts[vertOffset + t[2] * 2 + 1] };
				Matrix m = new Matrix();
				if (!m.setPolyToPoly(tex, 0, dst, 0, 3))
					continue;
				Path texTri = new Path();
				texTri.moveTo(tex[0], tex[1]);
				texTri.lineTo(tex[2], tex[3]);
				texTri.lineTo(tex[4], tex[5]);
				texTri.close();
				save();
				concat(m);
				drawPath(texTri, p);
				restore();
			} else {
				drawPath(tri, p);
			}
		}
	}

	public void drawPicture(Picture picture) {
		picture.endRecording();
		int restoreCount = save();
		picture.draw(this);
		restoreToCount(restoreCount);
	}

	public void drawPicture(Picture picture, RectF dst) {
		save();
		translate(dst.left, dst.top);
		if (picture.getWidth() > 0 && picture.getHeight() > 0)
			scale(dst.width() / picture.getWidth(), dst.height() / picture.getHeight());
		drawPicture(picture);
		restore();
	}

	public void drawPicture(Picture picture, Rect dst) {
		save();
		translate(dst.left, dst.top);
		if (picture.getWidth() > 0 && picture.getHeight() > 0)
			scale((float) dst.width() / picture.getWidth(), (float) dst.height() / picture.getHeight());
		drawPicture(picture);
		restore();
	}

	public void drawPatch(NinePatch patch, Rect dst, Paint paint) {
		patch.draw(this, dst, paint);
	}

	public void drawPatch(NinePatch patch, RectF dst, Paint paint) {
		Rect r = new Rect();
		dst.roundOut(r);
		patch.draw(this, r, paint);
	}

	/** Draws a RenderNode's display list (API 29). */
	public void drawRenderNode(android.view.RenderNode node) {
		if (tlNoTarget())
			return;
		gc().drawRenderNode(node);
	}

	public void drawRenderNode(android.graphics.RenderNode node) {
		drawRenderNode((android.view.RenderNode) node);
	}

	/** AOSP: Z reordering of RenderNodes (HWUI); a raster canvas has nothing to reorder. */
	public void enableZ() {}

	public void disableZ() {}

	// ---- text -------------------------------------------------------------------------------

	public void drawText(String text, float x, float y, Paint paint) {
		if (tlNoTarget())
			return;
		gc().drawText(text, x, y, paint);
	}

	public void drawText(String text, int start, int end, float x, float y, Paint paint) {
		if ((start | end | (end - start) | (text.length() - end)) < 0)
			throw new IndexOutOfBoundsException();
		drawText(text.substring(start, end), x, y, paint);
	}

	public void drawText(CharSequence text, int start, int end, float x, float y, Paint paint) {
		if ((start | end | (end - start) | (text.length() - end)) < 0)
			throw new IndexOutOfBoundsException();
		drawText(text.subSequence(start, end).toString(), x, y, paint);
	}

	/** AOSP: index and count select the characters. */
	public void drawText(char[] text, int index, int count, float x, float y, Paint paint) {
		if ((index | count | (index + count) | (text.length - index - count)) < 0)
			throw new IndexOutOfBoundsException();
		drawText(new String(text, index, count), x, y, paint);
	}

	/** AOSP: a run of text; Pango shapes it with its bidi context itself. */
	public void drawTextRun(CharSequence text, int start, int end, int contextStart, int contextEnd, float x, float y, boolean isRtl, Paint paint) {
		drawText(text, start, end, x, y, paint);
	}

	public void drawTextRun(char[] text, int index, int count, int contextIndex, int contextCount, float x, float y, boolean isRtl, Paint paint) {
		drawText(text, index, count, x, y, paint);
	}

	/** AOSP: each character at its own position (pos holds x, y pairs). */
	public void drawPosText(char[] text, int index, int count, float[] pos, Paint paint) {
		if (index < 0 || index + count > text.length || count * 2 > pos.length)
			throw new IndexOutOfBoundsException();
		Paint p = new Paint(paint);
		p.setTextAlign(Paint.Align.LEFT);
		for (int i = 0; i < count; i++)
			drawText(String.valueOf(text[index + i]), pos[i * 2], pos[i * 2 + 1], p);
	}

	public void drawPosText(String text, float[] pos, Paint paint) {
		drawPosText(text.toCharArray(), 0, text.length(), pos, paint);
	}

	/**
	 * AOSP: the text along the path, starting hOffset along it (after the paint's alignment) and
	 * vOffset above (negative) or below it. Each glyph is placed upright on the path's tangent at
	 * the middle of its advance.
	 */
	public void drawTextOnPath(String text, Path path, float hOffset, float vOffset, Paint paint) {
		if (text.isEmpty())
			return;
		float[] widths = new float[text.length()];
		paint.getTextWidths(text, widths);
		float total = 0;
		for (float w : widths)
			total += w;
		PathMeasure pm = new PathMeasure(path, false);
		float length = pm.getLength();
		float start = hOffset;
		if (paint.getTextAlign() == Paint.Align.CENTER)
			start += (length - total) / 2;
		else if (paint.getTextAlign() == Paint.Align.RIGHT)
			start += length - total;
		Paint p = new Paint(paint);
		p.setTextAlign(Paint.Align.CENTER);
		float[] pos = new float[2], tan = new float[2];
		float d = start;
		for (int i = 0; i < widths.length; i++) {
			float mid = d + widths[i] / 2;
			d += widths[i];
			if (mid < 0 || mid > length || !pm.getPosTan(mid, pos, tan))
				continue;
			save();
			translate(pos[0], pos[1]);
			rotate((float) Math.toDegrees(Math.atan2(tan[1], tan[0])));
			drawText(String.valueOf(text.charAt(i)), 0, vOffset, p);
			restore();
		}
	}

	public void drawTextOnPath(char[] text, int index, int count, Path path, float hOffset, float vOffset, Paint paint) {
		if (index < 0 || index + count > text.length)
			throw new ArrayIndexOutOfBoundsException();
		drawTextOnPath(new String(text, index, count), path, hOffset, vOffset, paint);
	}
}
