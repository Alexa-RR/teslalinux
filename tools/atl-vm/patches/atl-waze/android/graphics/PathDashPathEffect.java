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
 * AOSP PathDashPathEffect (Skia's SkPath1DPathEffect): the shape is stamped along each contour
 * every advance, translated, rotated with the tangent, or bent along the path (MORPH).
 */
public class PathDashPathEffect extends PathEffect {

	public enum Style {
		TRANSLATE, //!< translate the shape to each position
		ROTATE,    //!< rotate the shape about its center
		MORPH,     //!< transform each point, and turn lines into curves
	}

	private final Path mShape;
	private final float mAdvance;
	private final float mPhase;
	private final Style mStyle;

	/**
	 * Dash the drawn path by stamping it with the specified shape. This only applies to drawings
	 * when the paint's style is STROKE or FILL_AND_STROKE.
	 * @param shape The path to stamp along
	 * @param advance spacing between each stamp of shape
	 * @param phase amount to offset before the first shape is stamped
	 * @param style how to transform the shape at each position as it is stamped
	 */
	public PathDashPathEffect(Path shape, float advance, float phase, Style style) {
		mShape = new Path(shape);
		mAdvance = advance;
		mPhase = phase;
		mStyle = style != null ? style : Style.TRANSLATE;
	}

	@Override
	public Path tlApply(Path src) {
		if (mAdvance <= 0)
			return src;
		// Skia: the first stamp is at (-phase) mod advance
		float start = (-mPhase) % mAdvance;
		if (start < 0)
			start += mAdvance;
		Path dst = new Path();
		PathMeasure pm = new PathMeasure(src, false);
		float[] pos = new float[2], tan = new float[2];
		java.util.ArrayList<float[]> shapePolys = mStyle == Style.MORPH ? Path.tlFlattenPublic(mShape.tlOps(), 0.25f) : null;
		Matrix m = new Matrix();
		do {
			float len = pm.getLength();
			for (float d = start; d <= len; d += mAdvance) {
				if (mStyle == Style.MORPH) {
					tlMorph(dst, pm, shapePolys, d, len, pos, tan);
					continue;
				}
				if (!pm.getPosTan(d, pos, tan))
					break;
				m.reset();
				if (mStyle == Style.ROTATE)
					m.setSinCos(tan[1], tan[0]);
				m.postTranslate(pos[0], pos[1]);
				dst.addPath(mShape, m);
			}
		} while (pm.nextContour());
		return dst;
	}

	/** MORPH: every shape point (x, y) goes to the path point at distance d + x, moved y along the normal. */
	private static void tlMorph(Path dst, PathMeasure pm, java.util.ArrayList<float[]> polys, float d, float len, float[] pos, float[] tan) {
		for (float[] poly : polys) {
			for (int i = 0; i < poly.length; i += 2) {
				float dist = Math.max(0f, Math.min(len, d + poly[i]));
				if (!pm.getPosTan(dist, pos, tan))
					return;
				float x = pos[0] - tan[1] * poly[i + 1];
				float y = pos[1] + tan[0] * poly[i + 1];
				if (i == 0)
					dst.moveTo(x, y);
				else
					dst.lineTo(x, y);
			}
		}
	}
}
