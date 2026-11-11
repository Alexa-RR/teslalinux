	/* teslalinux: the node clipped to a path's fill (GskFillNode); the input keeps its reference
	 * (native-rendernode-pathclip.sh) */
	private static native long nativePathClipKeep(long node, long gskPath, boolean evenOdd);
