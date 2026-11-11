	/* ---- teslalinux: path clipping and clip bounds (AOSP Canvas semantics on GSK) ---- */

	/** Device-space clip bounds per save level (null entry: unclipped); kept next to state_stack. */
	private java.util.ArrayList<RectF> clip_stack;

	private void tlEnsureClipStack() {
		if (clip_stack == null) {
			clip_stack = new java.util.ArrayList<>();
			while (clip_stack.size() < getSaveCount())
				clip_stack.add(null);
		}
	}

	/** Called by save(): the new level starts with the clip of the level below. */
	void tlClipPush() {
		tlEnsureClipStack();
		RectF top = clip_stack.isEmpty() ? null : clip_stack.get(clip_stack.size() - 1);
		clip_stack.add(top == null ? null : new RectF(top));
	}

	/** Called by restore(): drop the level. */
	void tlClipPop() {
		if (clip_stack != null && !clip_stack.isEmpty())
			clip_stack.remove(clip_stack.size() - 1);
	}

	/** Bounding box in device space of a local-space rectangle under the current matrix. */
	private RectF tlToDevice(float left, float top, float right, float bottom) {
		Matrix m = state_stack.peek();
		if (m == null || m.isIdentity())
			return new RectF(left, top, right, bottom);
		float[] pts = { left, top, right, top, right, bottom, left, bottom };
		m.mapPoints(pts);
		float l = pts[0], t = pts[1], r = pts[0], b = pts[1];
		for (int i = 2; i < 8; i += 2) {
			l = Math.min(l, pts[i]);
			r = Math.max(r, pts[i]);
			t = Math.min(t, pts[i + 1]);
			b = Math.max(b, pts[i + 1]);
		}
		return new RectF(l, t, r, b);
	}

	/** Intersects the current level's clip with a local-space rectangle. */
	void tlClipIntersect(float left, float top, float right, float bottom) {
		tlEnsureClipStack();
		RectF dev = tlToDevice(left, top, right, bottom);
		int i = clip_stack.size() - 1;
		RectF cur = clip_stack.get(i);
		if (cur == null) {
			clip_stack.set(i, dev);
		} else if (!cur.intersect(dev)) {
			cur.setEmpty();
		}
	}

	private RectF tlCurrentClip() {
		tlEnsureClipStack();
		return clip_stack.get(clip_stack.size() - 1);
	}

	/** One more GSK container to pop at the current save level (clip, fill, mask). */
	private void tlNotePush() {
		int save_count = getSaveCount();
		if (push_history == null)
			push_history = new int[save_count + 1];
		else if (push_history.length <= save_count)
			push_history = Arrays.copyOf(push_history, save_count + 1);
		push_history[save_count]++;
	}

	/**
	 * AOSP RenderNode.endRecording (HWUI finishes the recording with its saves implicitly restored):
	 * what the recording still has open ends with it, the saves and layers and the clips pushed at
	 * the base level (Compose clips a layer with clipPath without a save). GTK otherwise popped them
	 * itself when the snapshot became a node, with a warning per recording.
	 */
	public void tlCloseRecording() {
		if (snapshot == 0)
			return;
		while (getSaveCount() > 1)
			restore();
		if (push_history != null && push_history.length > 1 && push_history[1] > 0) {
			native_pop(snapshot, push_history[1]);
			push_history[1] = 0;
		}
	}

	private static int tlFillRule(Path path) {
		Path.FillType ft = path.getFillType();
		return (ft == Path.FillType.EVEN_ODD || ft == Path.FillType.INVERSE_EVEN_ODD) ? 1 : 0;
	}

	private static boolean tlIsInverse(Path path) {
		Path.FillType ft = path.getFillType();
		return ft == Path.FillType.INVERSE_WINDING || ft == Path.FillType.INVERSE_EVEN_ODD;
	}

	@Override
	public boolean clipPath(Path path) {
		return clipPath(path, Region.Op.INTERSECT);
	}

	/** AOSP (API 28+): only INTERSECT and DIFFERENCE are valid for a hardware-accelerated canvas. */
	@Override
	public boolean clipPath(Path path, Region.Op op) {
		if (op == Region.Op.INTERSECT) {
			if (tlIsInverse(path))
				return tlClipOut(path);
			native_clipPath(snapshot, path.getGskPath(), tlFillRule(path));
			tlNotePush();
			tlRecordClip(TL_CLIP_PATH, 0, 0, 0, 0, path, tlFillRule(path));
			RectF bounds = new RectF();
			path.computeBounds(bounds, true);
			tlClipIntersect(bounds.left, bounds.top, bounds.right, bounds.bottom);
			RectF clip = tlCurrentClip();
			return clip == null || !clip.isEmpty();
		} else if (op == Region.Op.DIFFERENCE) {
			if (tlIsInverse(path)) {
				native_clipPath(snapshot, path.getGskPath(), tlFillRule(path));
				tlNotePush();
				tlRecordClip(TL_CLIP_PATH, 0, 0, 0, 0, path, tlFillRule(path));
				return true;
			}
			return tlClipOut(path);
		}
		throw new IllegalArgumentException("Invalid Region.Op - only INTERSECT and DIFFERENCE are allowed");
	}

	private boolean tlClipOut(Path path) {
		native_clipOutPath(snapshot, path.getGskPath(), tlFillRule(path));
		tlNotePush();
		tlRecordClip(TL_CLIP_OUT, 0, 0, 0, 0, path, tlFillRule(path));
		RectF clip = tlCurrentClip();
		return clip == null || !clip.isEmpty();
	}

	@Override
	public boolean clipOutPath(Path path) {
		return clipPath(path, Region.Op.DIFFERENCE);
	}

	@Override
	public boolean clipOutRect(float left, float top, float right, float bottom) {
		Path p = new Path();
		p.addRect(left, top, right, bottom, Path.Direction.CW);
		return tlClipOut(p);
	}

	@Override
	public boolean clipOutRect(int left, int top, int right, int bottom) {
		return clipOutRect((float) left, (float) top, (float) right, (float) bottom);
	}

	@Override
	public boolean clipOutRect(Rect rect) {
		return clipOutRect(rect.left, rect.top, rect.right, rect.bottom);
	}

	@Override
	public boolean clipOutRect(RectF rect) {
		return clipOutRect(rect.left, rect.top, rect.right, rect.bottom);
	}

	/** AOSP: the clip bounds in local coordinates; false if the clip is empty. */
	@Override
	public boolean getClipBounds(Rect outRect) {
		RectF clip = tlCurrentClip();
		if (clip == null) {
			// unclipped: the whole canvas as the framework reports it for an unclipped layer; a widget
			// snapshot canvas has no bitmap, its extent is the display it is drawn on
			if (outRect != null) {
				int w = getWidth(), h = getHeight();
				if (w <= 0 || h <= 0) {
					android.util.DisplayMetrics dm = android.content.res.Resources.getSystem().getDisplayMetrics();
					w = dm.widthPixels;
					h = dm.heightPixels;
				}
				outRect.set(0, 0, w, h);
			}
			return true;
		}
		if (clip.isEmpty()) {
			if (outRect != null)
				outRect.setEmpty();
			return false;
		}
		if (outRect != null) {
			Matrix inv = new Matrix();
			Matrix m = state_stack.peek();
			RectF local = new RectF(clip);
			if (m != null && !m.isIdentity() && m.invert(inv)) {
				float[] pts = { clip.left, clip.top, clip.right, clip.top, clip.right, clip.bottom, clip.left, clip.bottom };
				inv.mapPoints(pts);
				local.set(pts[0], pts[1], pts[0], pts[1]);
				for (int i = 2; i < 8; i += 2)
					local.union(pts[i], pts[i + 1]);
			}
			outRect.set(Math.round(local.left), Math.round(local.top), Math.round(local.right), Math.round(local.bottom));
		}
		return true;
	}

	/** AOSP quickReject: true when the rectangle (after the current matrix) lies completely outside the clip. */
	@Override
	public boolean quickReject(float left, float top, float right, float bottom) {
		RectF clip = tlCurrentClip();
		if (clip == null)
			return false;
		if (clip.isEmpty())
			return true;
		RectF dev = tlToDevice(left, top, right, bottom);
		return !RectF.intersects(clip, dev);
	}

	@Override
	public boolean quickReject(float left, float top, float right, float bottom, EdgeType type) {
		return quickReject(left, top, right, bottom);
	}

	@Override
	public boolean quickReject(RectF rect) {
		return quickReject(rect.left, rect.top, rect.right, rect.bottom);
	}

	@Override
	public boolean quickReject(RectF rect, EdgeType type) {
		return quickReject(rect);
	}

	@Override
	public boolean quickReject(Path path) {
		RectF bounds = new RectF();
		path.computeBounds(bounds, true);
		return quickReject(bounds);
	}

	@Override
	public boolean quickReject(Path path, EdgeType type) {
		return quickReject(path);
	}

	private static native void native_clipPath(long snapshot, long path, int fillRule);
	private static native void native_clipOutPath(long snapshot, long path, int fillRule);

	/* ---- teslalinux: arcs and points through GskPath (fill and stroke follow the Paint) ---- */

	/** AOSP drawArc: an arc of the oval, optionally closed through the centre as a wedge. */
	@Override
	public void drawArc(float left, float top, float right, float bottom, float startAngle, float sweepAngle, boolean useCenter, Paint paint) {
		Path p = new Path();
		if (useCenter) {
			p.moveTo((left + right) / 2f, (top + bottom) / 2f);
			p.arcTo(left, top, right, bottom, startAngle, sweepAngle, false);
			p.close();
		} else {
			p.arcTo(left, top, right, bottom, startAngle, sweepAngle, true);
		}
		drawPath(p, paint);
	}

	@Override
	public void drawArc(RectF oval, float startAngle, float sweepAngle, boolean useCenter, Paint paint) {
		if (oval == null)
			throw new NullPointerException();
		drawArc(oval.left, oval.top, oval.right, oval.bottom, startAngle, sweepAngle, useCenter, paint);
	}

	/** AOSP drawPoint: a dot of the stroke width, round or square after the stroke cap (hairline: one pixel). */
	@Override
	public void drawPoint(float x, float y, Paint paint) {
		Paint p = paint != null ? paint : default_paint;
		float w = p.getStrokeWidth();
		if (w <= 0)
			w = 1f;
		float h = w / 2f;
		Paint fill = new Paint(p);
		fill.setStyle(Paint.Style.FILL);
		if (p.getStrokeCap() == Paint.Cap.ROUND)
			drawRoundRect(x - h, y - h, x + h, y + h, h, h, fill);
		else
			drawRect(x - h, y - h, x + h, y + h, fill);
	}

	@Override
	public void drawPoints(float[] pts, int offset, int count, Paint paint) {
		for (int i = offset; i + 1 < offset + count; i += 2)
			drawPoint(pts[i], pts[i + 1], paint);
	}

	@Override
	public void drawPoints(float[] pts, Paint paint) {
		drawPoints(pts, 0, pts.length, paint);
	}

	/* ---- teslalinux: colour filter groups for drawables (PorterDuff tints as GSK colour matrices) ---- */
	private Paint tlFilterPaint;

	/** Pushes a colour-matrix group for the filter; returns false (nothing pushed) for filters GSK cannot express. */
	public boolean tlPushColorFilter(ColorFilter filter) {
		if (!(filter instanceof PorterDuffColorFilter))
			return false;
		if (tlFilterPaint == null)
			tlFilterPaint = new Paint();
		tlFilterPaint.setColorFilter(filter);
		native_pushColorMatrix(snapshot, tlFilterPaint.paint);
		return true;
	}

	/** Closes the group opened by tlPushColorFilter. */
	public void tlPopGroup() {
		native_pop(snapshot, 1);
	}

	private static native void native_pushColorMatrix(long snapshot, long paint);

	/** AOSP: a display-list canvas is hardware accelerated; GSK renders the snapshot on the GPU. */
	@Override
	public boolean isHardwareAccelerated() {
		return true;
	}
