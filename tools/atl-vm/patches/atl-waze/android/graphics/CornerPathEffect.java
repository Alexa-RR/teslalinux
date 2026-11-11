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

/**
 * AOSP CornerPathEffect: sharp angles between line segments become rounded corners of the given
 * radius (a quadratic through the corner point, as Skia's SkCornerPathEffect). Contours with
 * curves keep their geometry.
 */
public class CornerPathEffect extends PathEffect {
	private final float mRadius;

	/**
	 * Transforms geometries that are drawn (either STROKE or FILL styles) by replacing any sharp
	 * angles between line segments into rounded angles of the specified radius.
	 * @param radius Amount to round sharp angles between line segments.
	 */
	public CornerPathEffect(float radius) {
		mRadius = radius;
	}

	@Override
	public Path tlApply(Path src) {
		if (mRadius <= 0)
			return src;
		float[] ops = src.tlOps();
		Path dst = new Path();
		dst.setFillType(src.getFillType());
		int i = 0, count = ops.length / Path.OP_SIZE;
		while (i < count) {
			// one contour: operations [i, j)
			int j = i + 1;
			while (j < count && (int) ops[j * Path.OP_SIZE] != Path.OP_MOVE)
				j++;
			boolean linesOnly = true, closed = false;
			java.util.ArrayList<float[]> pts = new java.util.ArrayList<>();
			for (int k = i; k < j; k++) {
				int o = k * Path.OP_SIZE;
				int op = (int) ops[o];
				if (op == Path.OP_MOVE)
					pts.add(new float[] { ops[o + 2], ops[o + 3] });
				else if (op == Path.OP_LINE)
					pts.add(new float[] { ops[o + 4], ops[o + 5] });
				else if (op == Path.OP_CLOSE)
					closed = true;
				else
					linesOnly = false;
			}
			if (closed && pts.size() > 1) {
				float[] a = pts.get(0), b = pts.get(pts.size() - 1);
				if (a[0] == b[0] && a[1] == b[1])
					pts.remove(pts.size() - 1);
			}
			if (!linesOnly || pts.size() < 3)
				dst.tlReplay(ops, i, j);
			else
				tlRound(dst, pts, closed);
			i = j;
		}
		return dst;
	}

	private void tlRound(Path dst, java.util.ArrayList<float[]> p, boolean closed) {
		int n = p.size();
		float[][] a = new float[n][], b = new float[n][];
		for (int i = 0; i < n; i++) {
			if (!closed && (i == 0 || i == n - 1))
				continue;
			float[] prev = p.get((i - 1 + n) % n), cur = p.get(i), next = p.get((i + 1) % n);
			float d1 = (float) Math.hypot(prev[0] - cur[0], prev[1] - cur[1]);
			float d2 = (float) Math.hypot(next[0] - cur[0], next[1] - cur[1]);
			float r1 = Math.min(mRadius, d1 / 2), r2 = Math.min(mRadius, d2 / 2);
			a[i] = d1 > 0 ? new float[] { cur[0] + (prev[0] - cur[0]) * r1 / d1, cur[1] + (prev[1] - cur[1]) * r1 / d1 } : cur;
			b[i] = d2 > 0 ? new float[] { cur[0] + (next[0] - cur[0]) * r2 / d2, cur[1] + (next[1] - cur[1]) * r2 / d2 } : cur;
		}
		if (closed) {
			dst.moveTo(b[0][0], b[0][1]);
			for (int k = 1; k <= n; k++) {
				int i = k % n;
				dst.lineTo(a[i][0], a[i][1]);
				dst.quadTo(p.get(i)[0], p.get(i)[1], b[i][0], b[i][1]);
			}
			dst.close();
		} else {
			dst.moveTo(p.get(0)[0], p.get(0)[1]);
			for (int i = 1; i < n - 1; i++) {
				dst.lineTo(a[i][0], a[i][1]);
				dst.quadTo(p.get(i)[0], p.get(i)[1], b[i][0], b[i][1]);
			}
			dst.lineTo(p.get(n - 1)[0], p.get(n - 1)[1]);
		}
	}
}
