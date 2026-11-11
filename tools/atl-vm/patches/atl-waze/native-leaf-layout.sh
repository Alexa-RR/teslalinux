#!/bin/sh
# teslalinux: onLayout/onSizeChanged for leaf views (tl_leaf_layout). AOSP lays out every View;
# ATL only called layoutInternal for views whose child widget has an AndroidLayout (ViewGroups),
# so a TextView subclass that places its own drawables in onLayout (AppCompat's SwitchCompat,
# AOSP's Switch) never got its size. The wrapper's allocate now calls layoutInternal for such views
# when their class overrides onLayout or onSizeChanged. Run by build-atl.sh from ~/atl-src. Idempotent.
set -eu
H=src/api-impl-jni/widgets/WrapperWidget.h
C=src/api-impl-jni/widgets/WrapperWidget.c
if ! grep -q "tl_leaf_layout" "$H"; then
python3 - "$H" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
old='	jmethodID computeScroll_method;\n'
new='	jmethodID computeScroll_method;\n	gboolean tl_layout; /* tl_leaf_layout: class overrides onLayout/onSizeChanged */\n'
assert s.count(old)==1, s.count(old)
open(p,'w').write(s.replace(old,new,1))
PY
fi
if ! grep -q "tl_leaf_layout" "$C"; then
python3 - "$C" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
old='''	} else {
		gtk_widget_size_allocate(wrapper->child, &allocation, baseline);
	}
	if (wrapper->background)
'''
new='''	} else {
		gtk_widget_size_allocate(wrapper->child, &allocation, baseline);
		/* tl_leaf_layout: AOSP delivers onSizeChanged/onLayout to every view */
		extern int snapshot_in_progress;
		if (wrapper->tl_layout && !snapshot_in_progress && wrapper->jobj) {
			JNIEnv *env = get_jni_env();
			(*env)->CallVoidMethod(env, wrapper->jobj, handle_cache.view.layoutInternal, width, height);
			if ((*env)->ExceptionCheck(env))
				(*env)->ExceptionDescribe(env);
		}
	}
	if (wrapper->background)
'''
assert s.count(old)==1, s.count(old)
s=s.replace(old,new,1)
old2='''	jmethodID computeScroll_method = _METHOD(_CLASS(jobj), "computeScroll", "()V");
'''
new2='''	/* tl_leaf_layout */
	jmethodID on_layout_method = _METHOD(_CLASS(jobj), "onLayout", "(ZIIII)V");
	jmethodID on_size_changed_method = _METHOD(_CLASS(jobj), "onSizeChanged", "(IIII)V");
	wrapper->tl_layout = on_layout_method != handle_cache.view.onLayout
	                     || on_size_changed_method != _METHOD(handle_cache.view.class, "onSizeChanged", "(IIII)V");
	jmethodID computeScroll_method = _METHOD(_CLASS(jobj), "computeScroll", "()V");
'''
assert s.count(old2)==1, s.count(old2)
s=s.replace(old2,new2,1)
open(p,'w').write(s)
PY
fi
grep -c "tl_leaf_layout" "$C"
