	/* ---- teslalinux: transform attributes from XML and Z order (AOSP View constructor + RenderNode Z) ----
	 * AOSP's View constructor reads translationX/Y/Z, elevation, rotation, rotationX/Y, scaleX/Y and
	 * transformPivotX/Y; ATL read none of them, so a layout that stacks siblings by translationZ (a
	 * segmented control's labels above its sliding bubble) drew them in child order. A Z change tells
	 * the parent, which draws (and picks touches from) its children ordered by Z. */

	/** AOSP View(Context, AttributeSet, int, int): the transform part of the attribute loop */
	private void tlReadTransformAttrs(TypedArray a) {
		boolean transformSet = false;
		float tx = 0, ty = 0, tz = 0, elev = 0, rot = 0, rotX = 0, rotY = 0, sx = 1f, sy = 1f;
		if (a.hasValue(com.android.internal.R.styleable.View_translationX)) {
			tx = a.getDimension(com.android.internal.R.styleable.View_translationX, 0);
			transformSet = true;
		}
		if (a.hasValue(com.android.internal.R.styleable.View_translationY)) {
			ty = a.getDimension(com.android.internal.R.styleable.View_translationY, 0);
			transformSet = true;
		}
		if (a.hasValue(com.android.internal.R.styleable.View_translationZ)) {
			tz = a.getDimension(com.android.internal.R.styleable.View_translationZ, 0);
			transformSet = true;
		}
		if (a.hasValue(com.android.internal.R.styleable.View_elevation)) {
			elev = a.getDimension(com.android.internal.R.styleable.View_elevation, 0);
			transformSet = true;
		}
		if (a.hasValue(com.android.internal.R.styleable.View_rotation)) {
			rot = a.getFloat(com.android.internal.R.styleable.View_rotation, 0);
			transformSet = true;
		}
		if (a.hasValue(com.android.internal.R.styleable.View_rotationX)) {
			rotX = a.getFloat(com.android.internal.R.styleable.View_rotationX, 0);
			transformSet = true;
		}
		if (a.hasValue(com.android.internal.R.styleable.View_rotationY)) {
			rotY = a.getFloat(com.android.internal.R.styleable.View_rotationY, 0);
			transformSet = true;
		}
		if (a.hasValue(com.android.internal.R.styleable.View_scaleX)) {
			sx = a.getFloat(com.android.internal.R.styleable.View_scaleX, 1f);
			transformSet = true;
		}
		if (a.hasValue(com.android.internal.R.styleable.View_scaleY)) {
			sy = a.getFloat(com.android.internal.R.styleable.View_scaleY, 1f);
			transformSet = true;
		}
		if (a.hasValue(com.android.internal.R.styleable.View_transformPivotX))
			setPivotX(a.getDimension(com.android.internal.R.styleable.View_transformPivotX, 0));
		if (a.hasValue(com.android.internal.R.styleable.View_transformPivotY))
			setPivotY(a.getDimension(com.android.internal.R.styleable.View_transformPivotY, 0));
		if (transformSet) {
			setTranslationX(tx);
			setTranslationY(ty);
			setTranslationZ(tz);
			setElevation(elev);
			setRotation(rot);
			setRotationX(rotX);
			setRotationY(rotY);
			setScaleX(sx);
			setScaleY(sy);
		}
	}

	/** the view's Z (elevation + translationZ) changed: the parent re-orders what it draws */
	private void tlZChanged() {
		if (parent instanceof ViewGroup)
			((ViewGroup) parent).tlRestackChildren();
	}
