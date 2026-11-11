#!/bin/sh
# teslalinux: the frame clock's global layout goes through ViewTreeObserver.tlDispatchFrameGlobalLayout
# (tl_vto_frame). Every activity's window shares ATL's one GtkWindow and so its frame clock: each
# tree observer with global layout listeners hooks that clock's paint signal, and a destroyed
# activity's observer kept firing on every frame of the live one (Waze's launcher activity then
# read null root insets from its detached decor on each frame). AOSP only dispatches global layout
# from a ViewRootImpl's traversal, which a removed window never runs again; the Java entry point
# applies that rule, while dispatchOnGlobalLayout() stays callable by apps as in AOSP. Idempotent.
set -eu
F=src/api-impl-jni/handle_cache.c
if ! grep -q "tlDispatchFrameGlobalLayout" "$F"; then
	sed -i 's|_METHOD(handle_cache.view_tree_observer.class, "dispatchOnGlobalLayout", "()V")|_METHOD(handle_cache.view_tree_observer.class, "tlDispatchFrameGlobalLayout", "()V") /* tl_vto_frame */|' "$F"
fi
grep -c "tlDispatchFrameGlobalLayout" "$F"
