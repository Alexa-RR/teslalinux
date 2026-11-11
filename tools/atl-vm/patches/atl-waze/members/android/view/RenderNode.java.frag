	/* teslalinux: hooks for android.graphics.RenderNode (the public API 29 class, which extends this
	 * node and records through android.graphics.RecordingCanvas). They mirror start()/end() above. */

	/** A fresh GtkSnapshot to record a display list into (owned by the caller until atlEndRecording). */
	public long atlCreateSnapshot(int width, int height) {
		this.width = width;
		this.height = height;
		children.clear();
		children_nodes.clear();
		return nativeCreateSnapshot();
	}

	/** Records a child node: a stub container goes into the snapshot and is patched with the child's
	 * current node whenever this node is drawn (getGskNode), so a child re-recorded later shows up. */
	public void atlRecordChild(RenderNode child, long snapshot) {
		long stub_node = nativeAddStubNode(snapshot);
		children.add(child);
		children_nodes.add(stub_node);
	}

	/** Turns the recorded snapshot into this node's display list (the snapshot is consumed). */
	public void atlEndRecording(long snapshot) {
		if (render_node != 0)
			nativeUnref(render_node);
		render_node = nativeCreateNode(snapshot);
		if (transformed_node != 0) {
			nativeUnref(transformed_node);
			transformed_node = 0;
		}
	}

	/** Recorded content width/height (set by the last recording or setLeftTopRightBottom). */
	public float atlGetWidth() {
		return width;
	}

	public float atlGetHeight() {
		return height;
	}

	/* teslalinux: clip helpers that leave the input node referenced (native-rendernode-clip.sh) */
	private static native long nativeClipKeep(long node, float left, float top, float right, float bottom);
	private static native long nativeRoundedClipKeep(long node, float left, float top, float right, float bottom, float radius);
