#!/bin/sh
# teslalinux: a View drawn by its own draw() into its widget's snapshot leaves nothing open.
#
# A WrapperWidget whose View overrides draw/onDraw/dispatchDraw snapshots by calling the View's
# draw() on the widget's GskCanvas, straight into GTK's snapshot. What that drawing left open (a save
# without restore, or a clipPath at the base level, which Compose's layer clip and some app views
# do) stayed open in GTK's snapshot, clipping the widgets drawn after it, and the canvas object (one
# per widget, reused every frame) carried the open state into the next frame. GTK then closed the
# frame itself with 'Too many gtk_snapshot_push() calls' (about 50 a second on Waze's route overview).
# AOSP's View.draw on a hardware canvas runs inside a RenderNode recording, whose end closes
# everything; here the canvas is closed after each draw (GskCanvas.tlCloseRecording). Run by
# build-atl.sh from ~/atl-src after native-node-debug.sh. Idempotent.
set -eu
W=src/api-impl-jni/widgets/WrapperWidget.c
if ! grep -q "tl_close_view_canvas" "$W"; then
python3 - "$W" <<'PY'
import sys
p = sys.argv[1]
s = open(p).read()
call = '''(*env)->CallVoidMethod(env, wrapper->jobj, wrapper->draw_method, wrapper->canvas);
			if ((*env)->ExceptionCheck(env))
				(*env)->ExceptionDescribe(env);
'''
n = s.count(call)
assert n == 2, "WrapperWidget snapshot draw calls: %d" % n
s = s.replace(call, call + '''			tl_close_view_canvas(env, wrapper->canvas);
''')
helper = '''
/* teslalinux: tl_close_view_canvas (native-wrapper-canvas-close.sh): what the View's draw left open
 * on the widget's canvas is closed before the widget's snapshot goes on */
static void tl_close_view_canvas(JNIEnv *env, jobject canvas)
{
	static jmethodID close_method = NULL;
	if (!canvas)
		return;
	if (!close_method) {
		/* the widget canvas is an android.atl.GskCanvas (WrapperWidget creates it); its own class
		 * resolves the method without a class loader lookup from this native callback */
		jclass canvas_class = (*env)->GetObjectClass(env, canvas);
		close_method = (*env)->GetMethodID(env, canvas_class, "tlCloseRecording", "()V");
		(*env)->DeleteLocalRef(env, canvas_class);
		if ((*env)->ExceptionCheck(env)) {
			(*env)->ExceptionClear(env);
			return;
		}
	}
	if (!close_method)
		return;
	(*env)->CallVoidMethod(env, canvas, close_method);
	if ((*env)->ExceptionCheck(env))
		(*env)->ExceptionDescribe(env);
}
'''
anchor = 'static void tl_node_debug_report('
if anchor not in s:
    raise SystemExit("anchor tl_node_debug_report missing")
i = s.index(anchor)
j = s.rfind('\n', 0, i)
s = s[:j] + '\n' + helper + s[j:]
open(p, "w").write(s)
PY
fi
grep -c "tl_close_view_canvas" "$W"
