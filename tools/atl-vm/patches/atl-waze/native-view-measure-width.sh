#!/bin/sh
# teslalinux: View.onMeasure's width does not shrink to fit an exact height.
#
# native_measure answered an EXACTLY height with a width-for-height measurement of the widget. For a
# wrapping GtkLabel that is the narrowest width at which the text still fits the given height, so a
# TextView with a fixed height and a wrap_content width came out as narrow as its text could be
# wrapped into that height: Waze's map pin bubble buttons (65 px tall, room for two lines) measured
# "Send" 31 px wide and drew "Se/nd", "Ro/utes". AOSP never couples them that way: a view's
# wrap_content width is its own desired width (a TextView's: the unwrapped text, BoringLayout or the
# widest line), clamped by AT_MOST, whatever the height spec. So the width is measured with no height
# constraint (for_size -1, the natural width), then clamped. The height-for-width path for an unknown
# height is unchanged. Run by build-atl.sh from ~/atl-src. Idempotent.
set -eu
F=src/api-impl-jni/views/android_view_View.c
if ! grep -q "tl_measure_width_free" "$F"; then
python3 - "$F" <<'PY'
import sys
p = sys.argv[1]
s = open(p).read()
old = '''	if (width == -1) {
		gtk_widget_measure(widget, GTK_ORIENTATION_HORIZONTAL, height, NULL, &width, NULL, NULL);
		if (width_spec_mode == MEASURE_SPEC_AT_MOST && width > width_spec_size)
			width = width_spec_size;
	}
'''
assert s.count(old) == 1, "native_measure width block"
new = '''	if (width == -1) {
		/* tl_measure_width_free: the desired width, not the narrowest one that fits the height */
		gtk_widget_measure(widget, GTK_ORIENTATION_HORIZONTAL, -1, NULL, &width, NULL, NULL);
		if (width_spec_mode == MEASURE_SPEC_AT_MOST && width > width_spec_size)
			width = width_spec_size;
	}
'''
s = s.replace(old, new, 1)
open(p, "w").write(s)
PY
fi
grep -c "tl_measure_width_free" "$F"
