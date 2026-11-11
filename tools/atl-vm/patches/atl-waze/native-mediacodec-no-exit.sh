#!/bin/sh
# teslalinux: a decoded video frame that cannot be shown is dropped (tl_mediacodec_drop). AOSP's
# MediaCodec reports a decoder or buffer failure to the app and keeps running; ATL called exit(1)
# when the dmabuf texture of a hardware frame could not be built or the VA-API frame could not be
# mapped, which ended the whole process on the first such frame. The frame is logged and skipped,
# the previous texture stays. Run by build-atl.sh from ~/atl-src. Idempotent.
set -eu
M=src/api-impl-jni/media/android_media_MediaCodec.c
if ! grep -q "tl_mediacodec_drop" "$M"; then
python3 - "$M" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
old='''	GdkTexture *texture = gdk_dmabuf_texture_builder_build(builder, handle_dmabuftexture_destroy, drm_frame, &error);
	if (error) {
		fprintf(stderr, "Failed to build texture: %s\\n", error->message);
		exit(1);
	}
	g_object_unref(builder);
	return texture;
'''
new='''	GdkTexture *texture = gdk_dmabuf_texture_builder_build(builder, handle_dmabuftexture_destroy, drm_frame, &error);
	if (error) {
		/* tl_mediacodec_drop: AOSP drops a frame it cannot present; the app keeps running */
		fprintf(stderr, "MediaCodec: cannot build the texture of a %dx%d frame (%s): frame dropped\\n", width, height, error->message);
		g_error_free(error);
		g_object_unref(builder);
		av_frame_free(&drm_frame);
		return NULL;
	}
	g_object_unref(builder);
	return texture;
'''
assert s.count(old)==1, s.count(old)
s=s.replace(old,new,1)
old2='''		ret = av_hwframe_map(drm_frame, frame, 0);
		if (ret < 0) {
			fprintf(stderr, "Failed to map frame: %s\\n", av_err2str(ret));
			exit(1);
		}
		av_frame_free(&frame);
'''
new2='''		ret = av_hwframe_map(drm_frame, frame, 0);
		if (ret < 0) {
			/* tl_mediacodec_drop: the frame is skipped, the previous one stays on screen */
			fprintf(stderr, "MediaCodec: cannot map a %dx%d frame (%s): frame dropped\\n", frame->width, frame->height, av_err2str(ret));
			av_frame_free(&drm_frame);
			av_frame_free(&frame);
			free(d);
			return G_SOURCE_REMOVE;
		}
		av_frame_free(&frame);
'''
assert s.count(old2)==1, s.count(old2)
s=s.replace(old2,new2,1)
old3='''	GdkTexture *texture = import_drm_frame_desc_as_texture(drm_frame_desc, drm_frame->width, drm_frame->height, drm_frame);
	surface_view_widget_set_texture(d->surface_view_widget, texture, FALSE);
	free(d);
'''
new3='''	GdkTexture *texture = import_drm_frame_desc_as_texture(drm_frame_desc, drm_frame->width, drm_frame->height, drm_frame);
	if (texture) /* tl_mediacodec_drop */
		surface_view_widget_set_texture(d->surface_view_widget, texture, FALSE);
	free(d);
'''
assert s.count(old3)==1, s.count(old3)
s=s.replace(old3,new3,1)
open(p,'w').write(s)
PY
fi
grep -c "tl_mediacodec_drop" "$M"
