	/* ---- teslalinux (gfx): path queries on the GskPath operations (native/atl_gfx_path.c) ---- */

	/** Size of one operation record from native_get_ops: GskPathOperation, conic weight, 4 points. */
	static final int OP_SIZE = 10;
	static final int OP_MOVE = 0, OP_CLOSE = 1, OP_LINE = 2, OP_QUAD = 3, OP_CUBIC = 4, OP_CONIC = 5;

	/** The builder to append to (PathMeasure.getSegment appends to its destination path). */
	long tlBuilder() {
		return getBuilder();
	}

	/** The path's operations as records of OP_SIZE floats. */
	float[] tlOps() {
		if (path == 0 && builder == 0)
			return new float[0];
		return native_get_ops(getGskPath());
	}

	private static native float[] native_get_ops(long path);
	private static native void native_add_reverse_path(long builder, long path);
	private static native void native_conic_to(long builder, float x1, float y1, float x2, float y2, float weight);

	/** Replays ops into this (reset) path; the end point of the operation at index target becomes (x, y). */
	private void tlRebuild(float[] ops, int target, float x, float y) {
		// setLastPoint: the last point is the end of the last operation that is not a close
		while (target > 0 && (int) ops[target * OP_SIZE] == OP_CLOSE)
			target--;
		FillType ft = fillType;
		reset();
		fillType = ft;
		for (int i = 0, n = ops.length / OP_SIZE; i < n; i++) {
			int o = i * OP_SIZE;
			int op = (int) ops[o];
			float[] p = new float[8];
			System.arraycopy(ops, o + 2, p, 0, 8);
			if (i == target) {
				int last = op == OP_MOVE ? 0 : op == OP_LINE ? 1 : op == OP_CUBIC ? 3 : 2;
				p[last * 2] = x;
				p[last * 2 + 1] = y;
			}
			switch (op) {
				case OP_MOVE: moveTo(p[0], p[1]); break;
				case OP_CLOSE: close(); break;
				case OP_LINE: lineTo(p[2], p[3]); break;
				case OP_QUAD: quadTo(p[2], p[3], p[4], p[5]); break;
				case OP_CUBIC: cubicTo(p[2], p[3], p[4], p[5], p[6], p[7]); break;
				case OP_CONIC: conicTo(p[2], p[3], p[4], p[5], ops[o + 1]); break;
			}
		}
	}

	/** AOSP API 34: a conic (rational quadratic) from the last point through (x1, y1) to (x2, y2). */
	public void conicTo(float x1, float y1, float x2, float y2, float weight) {
		native_conic_to(getBuilder(), x1, y1, x2, y2, weight);
	}

	/** AOSP API 34: conicTo relative to the last point. */
	public void rConicTo(float dx1, float dy1, float dx2, float dy2, float weight) {
		float[] ops = tlOps();
		float lx = 0, ly = 0;
		if (ops.length > 0) {
			float[] last = tlLastPoint(ops);
			lx = last[0];
			ly = last[1];
		}
		conicTo(lx + dx1, ly + dy1, lx + dx2, ly + dy2, weight);
	}

	/** The current point after the operations (the contour start after a close). */
	private static float[] tlLastPoint(float[] ops) {
		int o = ops.length - OP_SIZE;
		int op = (int) ops[o];
		switch (op) {
			case OP_MOVE: return new float[] { ops[o + 2], ops[o + 3] };
			case OP_CLOSE: return new float[] { ops[o + 4], ops[o + 5] };
			case OP_LINE: return new float[] { ops[o + 4], ops[o + 5] };
			case OP_CUBIC: return new float[] { ops[o + 8], ops[o + 9] };
			default: return new float[] { ops[o + 6], ops[o + 7] };
		}
	}

	/** AOSP: toggles between the inverse and non-inverse variant of the fill type. */
	public void toggleInverseFillType() {
		switch (fillType) {
			case WINDING: fillType = FillType.INVERSE_WINDING; break;
			case EVEN_ODD: fillType = FillType.INVERSE_EVEN_ODD; break;
			case INVERSE_WINDING: fillType = FillType.WINDING; break;
			case INVERSE_EVEN_ODD: fillType = FillType.EVEN_ODD; break;
		}
	}

	/** AOSP: true for the inverse fill types (the outside of the path is filled). */
	public boolean isInverseFillType() {
		return fillType == FillType.INVERSE_WINDING || fillType == FillType.INVERSE_EVEN_ODD;
	}

	/** AOSP: offsets the path into dst (this path is unchanged), or this path if dst is null. */
	public void offset(float dx, float dy, Path dst) {
		if (dst == null || dst == this) {
			offset(dx, dy);
			return;
		}
		dst.set(this);
		dst.offset(dx, dy);
	}

	/** AOSP: true when the path is one axis-aligned rectangle (its bounds go into rect if not null). */
	public boolean isRect(RectF rect) {
		float[] ops = tlOps();
		java.util.ArrayList<float[]> pts = new java.util.ArrayList<>();
		boolean closed = false;
		for (int i = 0, n = ops.length / OP_SIZE; i < n; i++) {
			int o = i * OP_SIZE;
			int op = (int) ops[o];
			if (op == OP_MOVE) {
				if (!pts.isEmpty() && (pts.size() > 1 || closed))
					return false; // a second contour
				pts.clear();
				pts.add(new float[] { ops[o + 2], ops[o + 3] });
			} else if (op == OP_LINE) {
				pts.add(new float[] { ops[o + 4], ops[o + 5] });
			} else if (op == OP_CLOSE) {
				closed = true;
			} else {
				return false; // a curve
			}
		}
		if (pts.size() > 1) {
			float[] first = pts.get(0), last = pts.get(pts.size() - 1);
			if (first[0] == last[0] && first[1] == last[1])
				pts.remove(pts.size() - 1);
		}
		// drop collinear middle points
		for (int i = 0; pts.size() > 4 && i < pts.size(); ) {
			float[] a = pts.get((i + pts.size() - 1) % pts.size()), b = pts.get(i), c = pts.get((i + 1) % pts.size());
			if ((a[0] == b[0] && b[0] == c[0]) || (a[1] == b[1] && b[1] == c[1]))
				pts.remove(i);
			else
				i++;
		}
		if (pts.size() != 4)
			return false;
		for (int i = 0; i < 4; i++) {
			float[] a = pts.get(i), b = pts.get((i + 1) % 4);
			if (a[0] != b[0] && a[1] != b[1])
				return false; // not axis aligned
			if (a[0] == b[0] && a[1] == b[1])
				return false;
		}
		float l = Float.MAX_VALUE, t = Float.MAX_VALUE, r = -Float.MAX_VALUE, b = -Float.MAX_VALUE;
		for (float[] p : pts) {
			l = Math.min(l, p[0]); r = Math.max(r, p[0]);
			t = Math.min(t, p[1]); b = Math.max(b, p[1]);
		}
		if (rect != null)
			rect.set(l, t, r, b);
		return true;
	}

	/** Flattened polyline of every contour: lists of x, y pairs (curves subdivided until within err). */
	private static java.util.ArrayList<float[]> tlFlatten(float[] ops, float err, boolean closeContours) {
		java.util.ArrayList<float[]> contours = new java.util.ArrayList<>();
		float[] cur = null;
		int n = 0;
		float sx = 0, sy = 0;
		for (int i = 0, cnt = ops.length / OP_SIZE; i < cnt; i++) {
			int o = i * OP_SIZE;
			int op = (int) ops[o];
			if (op == OP_MOVE || cur == null) {
				if (cur != null)
					contours.add(java.util.Arrays.copyOf(cur, n));
				cur = new float[64];
				n = 0;
				sx = ops[o + 2];
				sy = ops[o + 3];
				cur[n++] = sx;
				cur[n++] = sy;
				if (op == OP_MOVE)
					continue;
			}
			int segs = 1;
			float x0 = ops[o + 2], y0 = ops[o + 3];
			if (op == OP_QUAD || op == OP_CONIC || op == OP_CUBIC) {
				float dd;
				if (op == OP_CUBIC) {
					float d1 = Math.abs(x0 - 2 * ops[o + 4] + ops[o + 6]) + Math.abs(y0 - 2 * ops[o + 5] + ops[o + 7]);
					float d2 = Math.abs(ops[o + 4] - 2 * ops[o + 6] + ops[o + 8]) + Math.abs(ops[o + 5] - 2 * ops[o + 7] + ops[o + 9]);
					dd = 0.75f * Math.max(d1, d2);
				} else {
					dd = 0.25f * (Math.abs(x0 - 2 * ops[o + 4] + ops[o + 6]) + Math.abs(y0 - 2 * ops[o + 5] + ops[o + 7]));
				}
				segs = (int) Math.ceil(Math.sqrt(dd / Math.max(err, 1e-4f)));
				segs = Math.max(1, Math.min(segs, 1000));
			}
			for (int s = 1; s <= segs; s++) {
				float t = (float) s / segs, x, y;
				switch (op) {
					case OP_LINE:
						x = ops[o + 4]; y = ops[o + 5];
						break;
					case OP_CLOSE:
						x = sx; y = sy;
						break;
					case OP_QUAD: {
						float u = 1 - t;
						x = u * u * x0 + 2 * u * t * ops[o + 4] + t * t * ops[o + 6];
						y = u * u * y0 + 2 * u * t * ops[o + 5] + t * t * ops[o + 7];
						break;
					}
					case OP_CONIC: {
						float u = 1 - t, w = ops[o + 1];
						float den = u * u + 2 * w * u * t + t * t;
						x = (u * u * x0 + 2 * w * u * t * ops[o + 4] + t * t * ops[o + 6]) / den;
						y = (u * u * y0 + 2 * w * u * t * ops[o + 5] + t * t * ops[o + 7]) / den;
						break;
					}
					case OP_CUBIC: {
						float u = 1 - t;
						x = u * u * u * x0 + 3 * u * u * t * ops[o + 4] + 3 * u * t * t * ops[o + 6] + t * t * t * ops[o + 8];
						y = u * u * u * y0 + 3 * u * u * t * ops[o + 5] + 3 * u * t * t * ops[o + 7] + t * t * t * ops[o + 9];
						break;
					}
					default:
						continue;
				}
				if (n + 2 > cur.length)
					cur = java.util.Arrays.copyOf(cur, cur.length * 2);
				cur[n++] = x;
				cur[n++] = y;
			}
		}
		if (cur != null)
			contours.add(java.util.Arrays.copyOf(cur, n));
		return contours;
	}

	private static boolean tlIsConvex(float[] ops) {
		java.util.ArrayList<float[]> contours = tlFlatten(ops, 0.25f, true);
		float[] poly = null;
		for (float[] c : contours) {
			if (c.length <= 2)
				continue; // a lone moveTo
			if (poly != null)
				return false; // Skia: more than one contour is concave
			poly = c;
		}
		if (poly == null)
			return true; // empty path
		// unique consecutive points, implicitly closed
		java.util.ArrayList<float[]> p = new java.util.ArrayList<>();
		for (int i = 0; i < poly.length; i += 2) {
			float[] q = { poly[i], poly[i + 1] };
			if (p.isEmpty() || Math.abs(p.get(p.size() - 1)[0] - q[0]) > 1e-4f || Math.abs(p.get(p.size() - 1)[1] - q[1]) > 1e-4f)
				p.add(q);
		}
		while (p.size() > 1 && Math.abs(p.get(0)[0] - p.get(p.size() - 1)[0]) <= 1e-4f && Math.abs(p.get(0)[1] - p.get(p.size() - 1)[1]) <= 1e-4f)
			p.remove(p.size() - 1);
		if (p.size() < 3)
			return true; // a point or a line
		int sign = 0;
		double turn = 0;
		boolean collinear = true;
		for (int i = 0, n = p.size(); i < n; i++) {
			float[] a = p.get(i), b = p.get((i + 1) % n), c = p.get((i + 2) % n);
			double e1x = b[0] - a[0], e1y = b[1] - a[1], e2x = c[0] - b[0], e2y = c[1] - b[1];
			double cross = e1x * e2y - e1y * e2x;
			double dot = e1x * e2x + e1y * e2y;
			double scale = Math.hypot(e1x, e1y) * Math.hypot(e2x, e2y);
			if (Math.abs(cross) <= 1e-6 * scale) {
				if (dot < 0)
					return false; // the outline doubles back on itself
				continue;
			}
			collinear = false;
			int s = cross > 0 ? 1 : -1;
			if (sign != 0 && s != sign)
				return false;
			sign = s;
			turn += Math.atan2(cross, dot);
		}
		// a convex outline turns exactly once around (a star turns more than once, all one way)
		return collinear || Math.abs(Math.abs(turn) - 2 * Math.PI) < 1e-3;
	}

	/**
	 * AOSP API 26: approximates the path with line segments. Three floats per point: the fraction of
	 * the path's length at the point, then x and y. A moveTo gives two points with the same fraction.
	 */
	public float[] approximate(float acceptableError) {
		if (acceptableError < 0)
			throw new IllegalArgumentException("AcceptableError must be greater than or equal to 0");
		java.util.ArrayList<float[]> contours = tlFlatten(tlOps(), acceptableError, false);
		int count = 0;
		for (float[] c : contours)
			count += c.length / 2;
		if (count == 0)
			return new float[] { 0, 0, 0, 1, 0, 0 };
		float[] out = new float[count * 3];
		float total = 0;
		for (float[] c : contours)
			for (int i = 2; i < c.length; i += 2)
				total += (float) Math.hypot(c[i] - c[i - 2], c[i + 1] - c[i - 1]);
		float dist = 0;
		int k = 0;
		for (float[] c : contours) {
			for (int i = 0; i < c.length; i += 2) {
				if (i > 0)
					dist += (float) Math.hypot(c[i] - c[i - 2], c[i + 1] - c[i - 1]);
				out[k++] = total > 0 ? dist / total : 0f;
				out[k++] = c[i];
				out[k++] = c[i + 1];
			}
		}
		if (total > 0)
			out[out.length - 3] = 1f;
		return out;
	}

	/** AOSP API 34: an iterator over the path's segments. */
	public PathIterator getPathIterator() {
		return new PathIterator(this);
	}

	/** AOSP API 34: true if this path and other have the same verbs and conic weights. */
	public boolean isInterpolatable(Path otherPath) {
		float[] a = tlOps(), b = otherPath.tlOps();
		if (a.length != b.length)
			return false;
		for (int o = 0; o < a.length; o += OP_SIZE)
			if (a[o] != b[o] || ((int) a[o] == OP_CONIC && a[o + 1] != b[o + 1]))
				return false;
		return true;
	}

	/** AOSP API 34: linear interpolation from this path (t = 0) to otherPath (t = 1), point by point. */
	public boolean interpolate(Path otherPath, float t, Path interpolatedPath) {
		if (!isInterpolatable(otherPath))
			return false;
		float[] a = tlOps(), b = otherPath.tlOps();
		float[] r = new float[a.length];
		for (int o = 0; o < a.length; o += OP_SIZE) {
			r[o] = a[o];
			r[o + 1] = a[o + 1];
			for (int k = 2; k < OP_SIZE; k++)
				r[o + k] = a[o + k] + (b[o + k] - a[o + k]) * t;
		}
		interpolatedPath.tlRebuild(r, -1, 0, 0);
		interpolatedPath.fillType = fillType;
		return true;
	}

	/** Appends operations [from, to) of an ops array to this path. */
	void tlReplay(float[] ops, int from, int to) {
		for (int i = from; i < to; i++) {
			int o = i * OP_SIZE;
			switch ((int) ops[o]) {
				case OP_MOVE: moveTo(ops[o + 2], ops[o + 3]); break;
				case OP_CLOSE: close(); break;
				case OP_LINE: lineTo(ops[o + 4], ops[o + 5]); break;
				case OP_QUAD: quadTo(ops[o + 4], ops[o + 5], ops[o + 6], ops[o + 7]); break;
				case OP_CUBIC: cubicTo(ops[o + 4], ops[o + 5], ops[o + 6], ops[o + 7], ops[o + 8], ops[o + 9]); break;
				case OP_CONIC: conicTo(ops[o + 4], ops[o + 5], ops[o + 6], ops[o + 7], ops[o + 1]); break;
			}
		}
	}

	/** Flattened contours (x, y pairs) for other android.graphics classes. */
	static java.util.ArrayList<float[]> tlFlattenPublic(float[] ops, float err) {
		return tlFlatten(ops, err, false);
	}
