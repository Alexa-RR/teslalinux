#!/bin/sh
# teslalinux: Canvas.clipPath (Region.Op.INTERSECT) as an alpha mask of the filled path.
#
# native-canvas-clip.sh pushed a GskFillNode (gtk_snapshot_push_fill). Its content vanished whenever
# it held GTK widget nodes: Compose clips a graphics layer with a path outline itself on API 32
# (Outline.canClip is false for paths there), and Waze's navigation sheet is such a layer whose whole
# content is classic Views drawn through their GTK widgets (AndroidViewHolder): the ETA bar and the
# expanded route sheet drew as an empty dark panel. The same content clipped by a rectangle drew.
# A mask node with the filled path as its alpha mask is the same clip (antialiased edge, the path's
# fill rule) and keeps that content; clipOutPath already clips this way with the inverted alpha.
# The caller still owes exactly one pop. Run by build-atl.sh from ~/atl-src after
# native-canvas-clip.sh. Idempotent.
set -eu
C=src/api-impl-jni/graphics/android_atl_GskCanvas.c
if ! grep -q "tl_clip_path_mask" "$C"; then
python3 - "$C" <<'PY'
import sys
p = sys.argv[1]
s = open(p).read()
old = '''	gtk_snapshot_push_fill(snapshot, (GskPath *)_PTR(path_ptr), fill_rule ? GSK_FILL_RULE_EVEN_ODD : GSK_FILL_RULE_WINDING);
'''
assert s.count(old) == 1, "native_clipPath push_fill"
new = '''	/* tl_clip_path_mask: the content is masked by the alpha of the filled path (a fill node lost
	 * GTK widget content); the mask child is closed here, the content's pop is the caller's */
	gtk_snapshot_push_mask(snapshot, GSK_MASK_MODE_ALPHA);
	gtk_snapshot_append_fill(snapshot, (GskPath *)_PTR(path_ptr), fill_rule ? GSK_FILL_RULE_EVEN_ODD : GSK_FILL_RULE_WINDING, &(GdkRGBA){0, 0, 0, 1});
	gtk_snapshot_pop(snapshot);
'''
s = s.replace(old, new, 1)
open(p, "w").write(s)
PY
fi
grep -c "tl_clip_path_mask" "$C"
