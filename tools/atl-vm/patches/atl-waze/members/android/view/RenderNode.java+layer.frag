	/* teslalinux: alpha and the full HWUI layer matrix (native-rendernode-layer.sh) */
	private native long nativeOpacityKeep(long node, float alpha);
	private native long nativeLayerTransformKeep(long node, float scaleX, float scaleY, float translationX, float translationY,
	                                             float rotationZ, float rotationX, float rotationY, float cameraDistanceInches,
	                                             float pivotX, float pivotY);

	/** a layer property changed: the cached transformed node is rebuilt on the next getGskNode() */
	private void tlDropTransformed() {
		if (transformed_node != 0) {
			nativeUnref(transformed_node);
			transformed_node = 0;
		}
	}
