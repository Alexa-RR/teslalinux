	/* ---- teslalinux: View transform properties and view animations (AOSP View + RenderNode + HWUI) ----
	 * scaleX/Y, rotation, rotationX/Y, pivot (the view's centre unless set), camera distance and the
	 * running android.view.animation.Animation's Transformation compose, with translationX/Y, into one
	 * matrix the way HWUI draws a view (RenderNode::setViewProperties: position, animation matrix,
	 * T(translation) . R/S about the pivot). The parent's native layout allocates the child's GTK widget
	 * with that matrix (native-view-transform.sh); GTK's picking honours it, so touches follow the view.
	 * While the matrix is a pure translation tlLayerMatrix stays null and the old allocation offset
	 * path is used. View animations are sampled on every Choreographer frame like ViewGroup.drawChild
	 * samples them (AOSP: applyLegacyAnimation), instead of jumping to their end. */
	private float tlScaleX = 1f;
	private float tlScaleY = 1f;
	private float tlRotation;
	private float tlRotationX;
	private float tlRotationY;
	private float tlPivotX;
	private float tlPivotY;
	private boolean tlPivotSet;
	private float tlCameraDistance;
	/** read by the native layout (native-view-transform.sh): the matrix minus left/top, null when only translated */
	private float[] tlLayerMatrix;
	private int tlLayerW = -1;
	private int tlLayerH = -1;

	private Animation tlAnimation;
	/** the animation's first frame waits for this view's first layout (tlStepAnimation) */
	private boolean tlAnimWaitingForLayout;

	/** after a layout: an animation that waited for it starts now */
	private void tlAnimAfterLayout() {
		if (tlAnimWaitingForLayout && tlAnimation != null) {
			tlAnimWaitingForLayout = false;
			tlScheduleAnimationFrame();
		}
	}
	private final android.view.animation.Transformation tlAnimTransformation = new android.view.animation.Transformation();
	private boolean tlAnimApplied;
	private float tlAnimAlpha = 1f;
	private boolean tlAnimFramePending;
	private boolean tlAnimStartReported;
	private Choreographer.FrameCallback tlAnimFrame;

	/** AOSP default camera distance: 8 inches (RenderNode), in pixels at this density */
	private float tlDefaultCameraDistance() {
		return 8f * getResources().getDisplayMetrics().densityDpi;
	}

	public float getCameraDistance() {
		return tlCameraDistance != 0f ? tlCameraDistance : tlDefaultCameraDistance();
	}

	public boolean hasIdentityMatrix() {
		return getMatrix().isIdentity();
	}

	/** AOSP View.getMatrix(): translation, then rotation and scale about the pivot (no left/top, no animation) */
	private void tlPropertyMatrix(Matrix m) {
		final float px = getPivotX(), py = getPivotY();
		m.reset();
		m.setTranslate(getTranslationX(), getTranslationY());
		m.preTranslate(px, py);
		if (tlRotationX != 0f || tlRotationY != 0f) {
			// Sk3DView/Camera as HWUI uses it: rotateX, rotateY, then a perspective projection from the
			// camera; the depth is 72 points per inch of camera distance (RenderNode's unit is inches)
			final float dpi = getResources().getDisplayMetrics().densityDpi;
			final float depth = 72f * getCameraDistance() / (dpi > 0 ? dpi : 160f);
			final double ax = Math.toRadians(tlRotationX), ay = Math.toRadians(-tlRotationY);
			final float cx = (float) Math.cos(ax), sxr = (float) Math.sin(ax);
			final float cy = (float) Math.cos(ay), syr = (float) Math.sin(ay);
			// R = Ry . Rx applied to (x, y, 0): x' = cy*x + syr*sxr*y, y' = cx*y, z' = -syr*x + cy*sxr*y
			final Matrix p = new Matrix();
			p.setValues(new float[] {
				cy, syr * sxr, 0,
				0, cx, 0,
				-syr / depth, cy * sxr / depth, 1,
			});
			m.preConcat(p);
		}
		m.preRotate(tlRotation);
		m.preScale(tlScaleX, tlScaleY);
		m.preTranslate(-px, -py);
	}

	/** the composed matrix changed (or may have): publish it for the native layout and re-allocate */
	private void tlTransformChanged() {
		tlComputeLayerMatrix();
		if (parent instanceof View)
			((View) parent).native_queueAllocate(((View) parent).widget);
		invalidate();
	}

	/** composes and publishes the matrix (tlLayerMatrix) without asking for a new allocation */
	private void tlComputeLayerMatrix() {
		final Matrix m = new Matrix();
		tlPropertyMatrix(m);
		if (tlAnimApplied) {
			final Matrix anim = tlAnimTransformation.getMatrix();
			if (!anim.isIdentity())
				m.postConcat(anim);
		}
		float[] v = new float[9];
		m.getValues(v);
		final boolean onlyTranslation = v[0] == 1f && v[1] == 0f && v[3] == 0f && v[4] == 1f && v[6] == 0f && v[7] == 0f && v[8] == 1f
		                                && v[2] == getTranslationX() && v[5] == getTranslationY();
		tlLayerMatrix = onlyTranslation ? null : v;
		tlLayerW = getWidth();
		tlLayerH = getHeight();
	}

	/** the default pivot is the centre: a size change moves it (AOSP RenderNode keeps it centred) */
	private void tlCheckPivotForSize() {
		if (!tlPivotSet && tlLayerMatrix != null && (tlLayerW != getWidth() || tlLayerH != getHeight()))
			tlTransformChanged();
	}

	/** view alpha times the running animation's alpha (AOSP: transformToApply.getAlpha()) */
	private void tlApplyAnimAlpha() {
		native_setVisibility(widget, visibility, getAlpha() * getTransitionAlpha() * tlAnimAlpha, atl_enabled);
	}

	private void tlScheduleAnimationFrame() {
		if (tlAnimFrame == null) {
			tlAnimFrame = new Choreographer.FrameCallback() {
				@Override
				public void doFrame(long frameTimeNanos) {
					tlAnimFramePending = false;
					tlStepAnimation();
				}
			};
		}
		if (!tlAnimFramePending) {
			tlAnimFramePending = true;
			Choreographer.getInstance().postFrameCallback(tlAnimFrame);
		}
	}

	/** AOSP View.applyLegacyAnimation() + ViewGroup.finishAnimatingView(), once per frame */
	private void tlStepAnimation() {
		final Animation a = tlAnimation;
		if (a == null)
			return;
		if (!a.isInitialized()) {
			// AOSP initializes a legacy animation in draw(), after the frame's layout; this frame
			// callback runs before it, so a view that has not been laid out yet waits for its layout
			// (otherwise percentage pivots resolve against a 0x0 view: a ring spinning around its corner)
			if (!isLaidOut()) {
				tlAnimWaitingForLayout = true;
				return;
			}
			final View p = parent instanceof View ? (View) parent : null;
			a.initialize(getWidth(), getHeight(), p != null ? p.getWidth() : getWidth(), p != null ? p.getHeight() : getHeight());
		}
		if (!tlAnimStartReported) {
			tlAnimStartReported = true;
			onAnimationStart();
		}
		final boolean more = a.getTransformation(android.view.animation.AnimationUtils.currentAnimationTimeMillis(), tlAnimTransformation, 1f);
		if (tlAnimation != a)
			return; // a listener replaced or cleared the animation
		tlAnimApplied = true;
		final int type = tlAnimTransformation.getTransformationType();
		tlAnimAlpha = (type & android.view.animation.Transformation.TYPE_ALPHA) != 0 ? tlAnimTransformation.getAlpha() : 1f;
		tlTransformChanged();
		tlApplyAnimAlpha();
		if (more) {
			tlScheduleAnimationFrame();
			return;
		}
		// finished: AOSP keeps a fillAfter animation attached (its last frame stays applied), clears
		// any other, and tells the view
		onAnimationEnd();
		if (!a.getFillAfter() && tlAnimation == a)
			clearAnimation();
	}
