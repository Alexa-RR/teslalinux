	/* ---- teslalinux (gfx): layers, transfer modes, shaders, mask filters, text decoration ----
	 * (native/atl_gfx_canvas.c)
	 *
	 * A saveLayer records into its own GtkSnapshot; restore puts the layer node into the parent
	 * with the layer paint's alpha and colour filter. A draw whose paint has a transfer mode other
	 * than SRC_OVER is composed with the content of the current target (the innermost layer, a
	 * display-list recording, or a Bitmap's content): the target's snapshot is ended, the draw and
	 * its coverage are recorded on their own, the composite starts a fresh snapshot, and the canvas
	 * state (saves, transforms, clips) is replayed into it. A widget's own snapshot belongs to GTK and
	 * cannot be read back; there a transfer mode that adds nothing to the destination (DST, DST_IN,
	 * DST_OUT, CLEAR) draws nothing and the others draw source-over. */

	/** True for a canvas whose base snapshot this canvas owns (display-list recordings). */
	public boolean tlBaseComposable;
	/** The bitmap a bitmap canvas draws into (its content can be read back as a texture). */
	public Bitmap tlBaseBitmap;

	private static final int TL_CLIP_RECT = 0, TL_CLIP_PATH = 1, TL_CLIP_OUT = 2;

	private static final class TlClip {
		int level, kind, rule;
		float[] matrix;
		float l, t, r, b;
		Path path;
	}

	private static final class TlLayer {
		long outer;
		int level;
		float[] base;
		Paint paint;
		float alpha;
		RectF bounds;
	}

	private java.util.ArrayList<TlClip> tlClips;
	private java.util.ArrayList<TlLayer> tlLayers;

	private boolean tlCanCompose() {
		return (tlLayers != null && !tlLayers.isEmpty()) || tlBaseComposable || tlBaseBitmap != null;
	}

	/** The base canvas of a bitmap canvas hands in the bitmap's current snapshot before each call. */
	public void tlSetBase(long base) {
		if (tlLayers != null && !tlLayers.isEmpty())
			tlLayers.get(0).outer = base;
		else
			snapshot = base;
	}

	/** Clip pushes are recorded while a composite could have to replay them. */
	void tlRecordClip(int kind, float l, float t, float r, float b, Path path, int rule) {
		if (!tlCanCompose())
			return;
		if (tlClips == null)
			tlClips = new java.util.ArrayList<>();
		TlClip c = new TlClip();
		c.level = getSaveCount();
		c.kind = kind;
		c.l = l;
		c.t = t;
		c.r = r;
		c.b = b;
		c.path = path != null ? new Path(path) : null;
		c.rule = rule;
		c.matrix = new float[9];
		state_stack.peek().getValues(c.matrix);
		tlClips.add(c);
	}

	/** After restore() closed save level `level`: drop its clip records, end its layer. */
	void tlRestored(int level) {
		if (tlClips != null)
			for (int i = tlClips.size() - 1; i >= 0 && tlClips.get(i).level >= level; i--)
				tlClips.remove(i);
		if (tlLayers != null && !tlLayers.isEmpty() && tlLayers.get(tlLayers.size() - 1).level == level)
			tlEndLayer(tlLayers.remove(tlLayers.size() - 1));
	}

	/** AOSP saveLayer: an offscreen layer, bounded by bounds (null: the clip), composited with paint on restore. */
	@Override
	public int tlSaveLayer(RectF bounds, Paint paint) {
		if (tlLayers == null)
			tlLayers = new java.util.ArrayList<>();
		TlLayer layer = new TlLayer();
		layer.outer = snapshot;
		layer.base = new float[9];
		state_stack.peek().getValues(layer.base);
		layer.paint = paint != null ? new Paint(paint) : null;
		layer.alpha = paint != null ? paint.getAlpha() / 255f : 1f;
		layer.bounds = bounds != null ? new RectF(bounds) : null;
		snapshot = native_layerBegin();
		int ret = save();
		layer.level = getSaveCount();
		tlLayers.add(layer);
		if (bounds != null)
			clipRect(bounds.left, bounds.top, bounds.right, bounds.bottom);
		return ret;
	}

	private static boolean tlHasNativeFilter(Paint p) {
		ColorFilter f = p != null ? p.getColorFilter() : null;
		return f instanceof PorterDuffColorFilter || f instanceof BlendModeColorFilter
		       || f instanceof ColorMatrixColorFilter || f instanceof LightingColorFilter;
	}

	private void tlEndLayer(TlLayer layer) {
		long node = native_layerFinish(snapshot);
		snapshot = layer.outer;
		int mode = layer.paint != null ? layer.paint.tlBlendModeValue() : -1;
		long filterPaint = tlHasNativeFilter(layer.paint) ? layer.paint.paint : 0;
		if (mode >= 0 && tlCanCompose()) {
			long dst = tlTakeTarget();
			long t = native_layerBegin();
			tlReplayState(t);
			native_appendLayer(t, node, layer.alpha, filterPaint);
			long src = tlFinishReplayed(t);
			long cov = 0;
			if (layer.bounds != null) {
				// Skia draws the layer as an image of its bounds: that is the coverage
				long c = native_layerBegin();
				tlReplayState(c);
				native_appendCoverageRect(c, layer.bounds.left, layer.bounds.top, layer.bounds.right, layer.bounds.bottom);
				cov = tlFinishReplayed(c);
			}
			long s = native_layerBegin();
			native_appendComposite(s, dst, src, cov, mode);
			tlInstallTarget(s);
		} else if (mode < 0 || !tlModeAddsNothing(mode)) {
			native_appendLayer(snapshot, node, layer.alpha, filterPaint);
		}
		native_nodeUnref(node);
	}

	private static boolean tlModeAddsNothing(int mode) {
		return mode == 0 || mode == 2 || mode == 6 || mode == 8;
	}

	/** Ends the current target and returns its content as a node (0: empty). */
	private long tlTakeTarget() {
		if ((tlLayers != null && !tlLayers.isEmpty()) || tlBaseComposable) {
			long n = tlFinishReplayed(snapshot);
			snapshot = 0;
			return n;
		}
		tlUnwind(snapshot);
		Bitmap bm = tlBaseBitmap;
		return native_textureNode(bm.getTexture(), bm.getWidth(), bm.getHeight());
	}

	/** Makes s (holding the composite) the current target, with the canvas state replayed into it. */
	private void tlInstallTarget(long s) {
		tlReplayState(s);
		if ((tlLayers != null && !tlLayers.isEmpty()) || tlBaseComposable)
			snapshot = s;
		else {
			tlBaseBitmap.tlSetSnapshot(s);
			snapshot = s;
		}
	}

	private static Matrix tlMatrix(float[] v) {
		Matrix m = new Matrix();
		if (v != null)
			m.setValues(v);
		return m;
	}

	private void tlConcatDelta(long s, Matrix from, Matrix to) {
		if (from.equals(to))
			return;
		Matrix inv = new Matrix();
		if (!from.invert(inv))
			return;
		inv.preConcat(to);
		native_concat(s, inv.native_instance);
	}

	/**
	 * Replays the saves, transforms and clips of the current target's levels into snapshot s, so
	 * that drawing into s continues exactly where the target was.
	 */
	private void tlReplayState(long s) {
		int start;
		Matrix cur;
		if (tlLayers != null && !tlLayers.isEmpty()) {
			TlLayer top = tlLayers.get(tlLayers.size() - 1);
			start = top.level;
			cur = tlMatrix(top.base);
		} else {
			start = 1;
			cur = new Matrix();
		}
		for (int lvl = start; lvl <= getSaveCount(); lvl++) {
			if (lvl >= 2)
				native_save(s);
			if (tlClips != null) {
				for (TlClip c : tlClips) {
					if (c.level != lvl)
						continue;
					Matrix cm = tlMatrix(c.matrix);
					tlConcatDelta(s, cur, cm);
					cur = cm;
					if (c.kind == TL_CLIP_RECT)
						native_clipRect(s, c.l, c.t, c.r, c.b);
					else if (c.kind == TL_CLIP_PATH)
						native_clipPath(s, c.path.getGskPath(), c.rule);
					else
						native_clipOutPath(s, c.path.getGskPath(), c.rule);
				}
			}
			Matrix target = state_stack.get(lvl - 1);
			tlConcatDelta(s, cur, target);
			cur = new Matrix(target);
		}
	}

	/**
	 * Closes the saves and pushes of the target's levels in s (the inverse of tlReplayState, so GTK
	 * finishes a balanced snapshot), then ends s and returns its node.
	 */
	private long tlFinishReplayed(long s) {
		tlUnwind(s);
		return native_layerFinish(s);
	}

	private void tlUnwind(long s) {
		int start = (tlLayers != null && !tlLayers.isEmpty()) ? tlLayers.get(tlLayers.size() - 1).level : 1;
		for (int lvl = getSaveCount(); lvl >= start; lvl--) {
			int pops = (push_history != null && push_history.length > lvl) ? push_history[lvl] : 0;
			if (pops > 0)
				native_pop(s, pops);
			if (lvl >= 2)
				native_restore(s);
		}
	}

	/** One drawing primitive, redrawn with modified paints by the pipeline. */
	abstract static class TlOp {
		final float l, t, r, b;

		TlOp(float l, float t, float r, float b) {
			this.l = Math.min(l, r);
			this.t = Math.min(t, b);
			this.r = Math.max(l, r);
			this.b = Math.max(t, b);
		}

		abstract void draw(Paint p);

		/** The draw's coverage (opaque black paint); for most primitives the shape itself. */
		void coverage(Paint p) {
			draw(p);
		}
	}

	/** Whether the paint needs the pipeline (a shader, a blur, or a transfer mode). */
	private static boolean tlNeeds(Paint paint, boolean isBitmap) {
		if (!isBitmap && paint.getShader() != null)
			return true;
		MaskFilter mf = paint.getMaskFilter();
		if (mf instanceof BlurMaskFilter && ((BlurMaskFilter) mf).tlSigma() > 0)
			return true;
		return paint.tlBlendModeValue() >= 0;
	}

	private void tlDraw(TlOp op, Paint paint, boolean isBitmap) {
		int mode = paint.tlBlendModeValue();
		if (mode < 0) {
			tlDrawEffects(op, paint, isBitmap, false);
			return;
		}
		Paint src = new Paint(paint);
		src.setXfermode(null);
		if (!tlCanCompose()) {
			if (!tlModeAddsNothing(mode))
				tlDrawEffects(op, src, isBitmap, false);
			return;
		}
		Paint cov = new Paint(src);
		cov.setShader(null);
		cov.setColorFilter(null);
		cov.setColor(0xff000000);
		long dst = tlTakeTarget();
		long n = tlRecordOp(op, src, isBitmap, false);
		long c = tlRecordOp(op, cov, isBitmap, true);
		long s = native_layerBegin();
		native_appendComposite(s, dst, n, c, mode);
		tlInstallTarget(s);
	}

	private long tlRecordOp(TlOp op, Paint paint, boolean isBitmap, boolean coverage) {
		long saved = snapshot;
		long t = native_layerBegin();
		tlReplayState(t);
		snapshot = t;
		try {
			tlDrawEffects(op, paint, isBitmap, coverage);
		} finally {
			snapshot = saved;
		}
		return tlFinishReplayed(t);
	}

	private static void tlLeaf(TlOp op, Paint p, boolean coverage) {
		if (coverage)
			op.coverage(p);
		else
			op.draw(p);
	}

	/** Mask filter, then shader, then the plain primitive. */
	private void tlDrawEffects(TlOp op, Paint paint, boolean isBitmap, boolean coverage) {
		MaskFilter mf = paint.getMaskFilter();
		if (mf instanceof BlurMaskFilter && ((BlurMaskFilter) mf).tlSigma() > 0) {
			BlurMaskFilter bf = (BlurMaskFilter) mf;
			float sigma = bf.tlSigma();
			Paint inner = new Paint(paint);
			inner.setMaskFilter(null);
			Paint shape = new Paint(inner);
			shape.setShader(null);
			shape.setColorFilter(null);
			shape.setColor(0xff000000);
			switch (bf.tlStyle()) {
				case 1: // SOLID: the shape itself, blurred outside
					native_pushBlur(snapshot, sigma);
					tlDrawEffects(op, inner, isBitmap, coverage);
					native_pop(snapshot, 1);
					tlDrawEffects(op, inner, isBitmap, coverage);
					break;
				case 2: // OUTER: only the blur outside the shape
				case 3: // INNER: only the blur inside the shape
					native_pushMask(snapshot, bf.tlStyle() == 2 ? 1 : 0);
					tlLeaf(op, shape, true);
					native_pop(snapshot, 1);
					native_pushBlur(snapshot, sigma);
					tlDrawEffects(op, inner, isBitmap, coverage);
					native_pop(snapshot, 1);
					native_pop(snapshot, 1);
					break;
				default: // NORMAL
					native_pushBlur(snapshot, sigma);
					tlDrawEffects(op, inner, isBitmap, coverage);
					native_pop(snapshot, 1);
			}
			return;
		}
		Shader sh = isBitmap ? null : paint.getShader();
		if (sh != null && !coverage) {
			// the shape (with the paint's alpha) masks the shader's colours; the colour filter acts
			// on the shader's output (Skia: shader, then colour filter)
			Paint mask = new Paint(paint);
			mask.setShader(null);
			mask.setColorFilter(null);
			mask.setColor(paint.getAlpha() << 24);
			RectF b = new RectF(op.l, op.t, op.r, op.b);
			if (paint.getStyle() != Paint.Style.FILL) {
				float w = Math.max(1f, paint.getStrokeWidth()) * Math.max(1f, paint.getStrokeMiter()) + 1f;
				b.inset(-w, -w);
			}
			Rect clip = new Rect();
			if (tlCurrentClip() != null && getClipBounds(clip) && !b.intersect(clip.left, clip.top, clip.right, clip.bottom))
				return;
			native_pushMask(snapshot, 0);
			op.draw(mask);
			native_pop(snapshot, 1);
			boolean filtered = tlHasNativeFilter(paint);
			if (filtered)
				native_pushColorMatrix(snapshot, paint.paint);
			tlAppendShader(snapshot, sh, b, paint);
			if (filtered)
				native_pop(snapshot, 1);
			native_pop(snapshot, 1);
			return;
		}
		if (sh != null) {
			paint = new Paint(paint);
			paint.setShader(null);
		}
		tlLeaf(op, paint, coverage);
	}

	/** The shader's colours over b (local coordinates) into snapshot s. */
	private void tlAppendShader(long s, Shader sh, RectF b, Paint paint) {
		Matrix local = sh.tlLocalMatrix();
		float l = b.left, t = b.top, r = b.right, bt = b.bottom;
		if (local != null) {
			Matrix inv = new Matrix();
			if (!local.invert(inv))
				return; // Skia: a shader with a singular local matrix draws nothing
			RectF sb = new RectF(b);
			inv.mapRect(sb);
			l = sb.left;
			t = sb.top;
			r = sb.right;
			bt = sb.bottom;
			native_save(s);
			native_concat(s, local.native_instance);
		}
		if (sh instanceof LinearGradient) {
			LinearGradient g = (LinearGradient) sh;
			native_appendLinearGradient(s, l, t, r, bt, g.tlX0(), g.tlY0(), g.tlX1(), g.tlY1(), g.tlColors(), g.tlPositions(), g.tlTileMode().ordinal());
		} else if (sh instanceof RadialGradient) {
			RadialGradient g = (RadialGradient) sh;
			native_appendRadialGradient(s, l, t, r, bt, g.tlCenterX(), g.tlCenterY(), g.tlRadius(), g.tlColors(), g.tlPositions(), g.tlTileMode().ordinal());
		} else if (sh instanceof SweepGradient) {
			SweepGradient g = (SweepGradient) sh;
			native_appendSweepGradient(s, l, t, r, bt, g.tlCx(), g.tlCy(), g.tlColors(), g.tlPositions());
		} else if (sh instanceof BitmapShader) {
			tlAppendBitmapShader(s, (BitmapShader) sh, l, t, r, bt, paint);
		} else if (sh instanceof ComposeShader) {
			ComposeShader cs = (ComposeShader) sh;
			RectF sb = new RectF(l, t, r, bt);
			long a = native_layerBegin();
			tlAppendShader(a, cs.tlShaderA(), sb, paint);
			long na = native_layerFinish(a);
			long c = native_layerBegin();
			tlAppendShader(c, cs.tlShaderB(), sb, paint);
			long nb = native_layerFinish(c);
			native_appendComposite(s, na, nb, 0, cs.tlMode() == PorterDuff.Mode.SRC_OVER.nativeInt ? 3 : cs.tlMode());
		}
		if (local != null)
			native_restore(s);
	}

	/**
	 * BitmapShader: per axis, CLAMP spreads the edge row/column beyond the bitmap, REPEAT and MIRROR
	 * tile it (MIRROR with flipped copies), DECAL draws it once.
	 */
	private void tlAppendBitmapShader(long s, BitmapShader bs, float l, float t, float r, float b, Paint paint) {
		Bitmap bm = bs.tlBitmap();
		int w = bm.getWidth(), h = bm.getHeight();
		if (w <= 0 || h <= 0 || r <= l || b <= t)
			return;
		long tex = bm.getTexture();
		int fm = bs.getFilterMode();
		int filter = fm == BitmapShader.FILTER_MODE_NEAREST ? 0 : fm == BitmapShader.FILTER_MODE_LINEAR ? 1 : (paint.isFilterBitmap() ? 1 : 0);
		float[][] xs = tlSegments(bs.tlTileX(), l, r, w);
		float[][] ys = tlSegments(bs.tlTileY(), t, b, h);
		for (float[] sx : xs)
			for (float[] sy : ys)
				tlTile(s, tex, w, h, sx, sy, filter);
	}

	/** Segments {start, end, kind} along one axis; kind 0 once, 1 repeat, 2 mirror, 3 low edge, 4 high edge. */
	private static float[][] tlSegments(Shader.TileMode mode, float lo, float hi, int size) {
		switch (mode) {
			case REPEAT:
				return new float[][] { { lo, hi, 1 } };
			case MIRROR:
				return new float[][] { { lo, hi, 2 } };
			case DECAL: {
				float a = Math.max(lo, 0), e = Math.min(hi, size);
				return a < e ? new float[][] { { a, e, 0 } } : new float[0][];
			}
			default: {
				java.util.ArrayList<float[]> out = new java.util.ArrayList<>();
				if (lo < 0)
					out.add(new float[] { lo, Math.min(0, hi), 3 });
				float a = Math.max(lo, 0), e = Math.min(hi, size);
				if (a < e)
					out.add(new float[] { a, e, 0 });
				if (hi > size)
					out.add(new float[] { Math.max(size, lo), hi, 4 });
				return out.toArray(new float[0][]);
			}
		}
	}

	/** Where the texture goes along an axis for a segment: the bitmap itself, or an edge spread over the segment. */
	private static float[] tlAxisRect(int kind, float[] seg, int size) {
		float len = seg[1] - seg[0];
		if (kind == 3)
			return new float[] { seg[0], seg[0] + size * len };
		if (kind == 4)
			return new float[] { seg[1] - size * len, seg[1] };
		return new float[] { 0, size };
	}

	private void tlTile(long s, long tex, int w, int h, float[] sx, float[] sy, int filter) {
		int kx = (int) sx[2], ky = (int) sy[2];
		if (sx[1] <= sx[0] || sy[1] <= sy[0])
			return;
		boolean repX = kx == 1 || kx == 2, repY = ky == 1 || ky == 2;
		native_clipRect(s, sx[0], sy[0], sx[1], sy[1]);
		if (repX || repY)
			native_pushRepeat(s, sx[0], sy[0], sx[1], sy[1],
			                  repX ? 0 : sx[0], repY ? 0 : sy[0],
			                  repX ? (kx == 2 ? 2 * w : w) : sx[1], repY ? (ky == 2 ? 2 * h : h) : sy[1]);
		// an edge spread one texel over the segment needs nearest sampling to stay that texel
		int f = (kx >= 3 || ky >= 3) ? 0 : filter;
		float[] xr = tlAxisRect(kx, sx, w), yr = tlAxisRect(ky, sy, h);
		int nx = kx == 2 ? 2 : 1, ny = ky == 2 ? 2 : 1;
		for (int ix = 0; ix < nx; ix++) {
			for (int iy = 0; iy < ny; iy++) {
				native_save(s);
				if (ix == 1) {
					native_translate(s, 2 * w, 0);
					native_scale(s, -1, 1);
				}
				if (iy == 1) {
					native_translate(s, 0, 2 * h);
					native_scale(s, 1, -1);
				}
				native_appendTexture(s, tex, xr[0], yr[0], xr[1], yr[1], f);
				native_restore(s);
			}
		}
		if (repX || repY)
			native_pop(s, 1);
		native_pop(s, 1);
	}

	/* ---- text: skew, underline, strike-through (Skia's positions) ---- */

	private static boolean tlTextDecorated(Paint p) {
		return p.getTextSkewX() != 0 || (p.getFlags() & (Paint.UNDERLINE_TEXT_FLAG | Paint.STRIKE_THRU_TEXT_FLAG)) != 0;
	}

	private void tlDrawDecoratedText(String text, float x, float y, Paint paint) {
		Paint p = new Paint(paint);
		float skew = p.getTextSkewX();
		boolean underline = p.isUnderlineText(), strike = p.isStrikeThruText();
		p.setTextSkewX(0);
		p.setUnderlineText(false);
		p.setStrikeThruText(false);
		if (skew != 0) {
			save();
			translate(x, y);
			Matrix m = new Matrix();
			m.setSkew(skew, 0);
			concat(m);
			drawText(text, 0, 0, p);
			restore();
		} else {
			drawText(text, x, y, p);
		}
		if (!underline && !strike)
			return;
		float size = p.getTextSize();
		float w = p.measureText(text);
		float left = x;
		if (p.getTextAlign() == Paint.Align.CENTER)
			left -= w / 2;
		else if (p.getTextAlign() == Paint.Align.RIGHT)
			left -= w;
		Paint fill = new Paint(p);
		fill.setStyle(Paint.Style.FILL);
		float thickness = size / 18f; // SkTextUtils kStdUnderline_Thickness
		if (underline) {
			float top = y + size / 9f; // kStdUnderline_Offset
			drawRect(left, top, left + w, top + thickness, fill);
		}
		if (strike) {
			float top = y + size * (-6f / 21f); // kStdStrikeThru_Offset
			drawRect(left, top, left + w, top + thickness, fill);
		}
	}

	private static native long native_layerBegin();
	private static native long native_layerFinish(long snapshot);
	private static native void native_nodeUnref(long node);
	private static native void native_appendNode(long snapshot, long node);
	private static native void native_appendLayer(long snapshot, long node, float alpha, long paint);
	private static native void native_appendComposite(long snapshot, long dst, long src, long coverage, int mode);
	private static native void native_pushMask(long snapshot, int mode);
	private static native void native_pushBlur(long snapshot, float sigma);
	private static native void native_pushOpacity(long snapshot, float opacity);
	private static native void native_pushRepeat(long snapshot, float l, float t, float r, float b, float cl, float ct, float cr, float cb);
	private static native void native_appendTexture(long snapshot, long texture, float l, float t, float r, float b, int filter);
	private static native void native_appendCoverageRect(long snapshot, float l, float t, float r, float b);
	private static native long native_textureNode(long texture, float width, float height);
	private static native void native_appendLinearGradient(long snapshot, float l, float t, float r, float b, float x0, float y0, float x1, float y1, int[] colors, float[] positions, int tile);
	private static native void native_appendRadialGradient(long snapshot, float l, float t, float r, float b, float cx, float cy, float radius, int[] colors, float[] positions, int tile);
	private static native void native_appendSweepGradient(long snapshot, float l, float t, float r, float b, float cx, float cy, int[] colors, float[] positions);
