/*
 * tacomp: the teslalinux display compositor.
 *
 * A headless wlroots compositor with one output of TACOMP_SIZE. The app (ATL) connects as an
 * ordinary Wayland client and is shown fullscreen. Every rendered frame is encoded and pushed to
 * the car browser over the display WebSocket (/sockets/display, TACOMP_DISPLAY_PORT, one binary
 * message per frame: JPEG for renderer 0). Touch arrives on the touch WebSocket
 * (/sockets/touchscreen, TACOMP_TOUCH_PORT) in the upstream line protocol and becomes wl_touch
 * events on the seat. The protocols are the ones the unmodified frontend speaks
 * (docs/UPSTREAM-NOTES.md).
 *
 * Encoding: v1 reads the rendered buffer back on the CPU (pixman renderer in the test VM, GPU
 * readback with gles2) and compresses with libjpeg-turbo. The V4L2 M2M path (m2m/, dmabuf in,
 * H.264 or JPEG out on the Pi) plugs into capture_frame().
 *
 * Frame pacing: a headless output re-arms its frame timer one whole refresh interval after each
 * commit (wlroots backend/headless), so the time spent building and encoding a frame added to every
 * frame period (48 Hz at 1472x672 with x264). tacomp runs its own frame clock instead: a periodic
 * timerfd at exactly the output's refresh (drift-free, missed ticks are skipped, not queued); each tick
 * builds and commits the scene, sends the clients their frame callbacks, and only then encodes, so
 * neither the clients' next frame nor the next tick waits for the encoder.
 *
 * Encoding runs on a thread of its own (encoder_main): the frame hands it the committed buffer
 * (locked, and CPU-mapped or as a dmabuf; a GLES read-back is done before, on the main thread, which
 * owns the GL context) and the newest frame wins when the encoder is still busy. The hardware
 * encoder's compress call waits for its output, and x264/libjpeg-turbo take milliseconds: on the
 * main loop that held up compositing, input and the clients' frame callbacks. The buffer comes back
 * to the main thread (eventfd) to be released; the encoders are opened, used and closed only on the
 * encoder thread (a reconfiguration asks it to close them before its next frame).
 *
 * SIGHUP re-reads TACOMP_CONFIG (default /etc/teslalinux/display.env) and applies a new size, encoder or
 * quality in place (tl_reconfigure). Environment: TACOMP_SIZE=WxH@fps (default 1088x832@60), TACOMP_DISPLAY_PORT=9091, TACOMP_TOUCH_PORT=9999,
 * TACOMP_IME_PORT=9996 (the device keyboard: text-input-v3 state to the page, its typing back; 0 = off),
 * TACOMP_QUALITY=70, TACOMP_ENCODER (unset: CPU JPEG; m2m-jpeg / m2m-h264: the Pi's V4L2 encoders;
 * m2m-h264 falls back to software H.264 (x264) where there is none, sw-h264 asks for x264 directly),

 * TACOMP_BIND (address the two WebSocket servers listen on; the car services set 127.0.0.1 so only
 * nginx reaches them; unset: all interfaces), WAYLAND_DISPLAY (socket name, default tesla-0),
 * WLR_RENDERER, XDG_RUNTIME_DIR.
 *
 * H.264: a client that connects mid-GOP cannot decode until the next IDR, so every new display
 * client forces one (upstream does the same); when the screen is static the last buffer is encoded
 * again as that IDR, so the new client gets a picture without waiting for the app to redraw.
 */
#define _POSIX_C_SOURCE 200809L
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <signal.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/eventfd.h>
#include <sys/timerfd.h>
#include <time.h>
#include <unistd.h>

#include <drm_fourcc.h>
#include <libwebsockets.h>
#include <turbojpeg.h>
#include <wayland-server-core.h>
#include <xkbcommon/xkbcommon.h>

#include <wlr/backend.h>
#include <wlr/backend/headless.h>
#include <wlr/interfaces/wlr_keyboard.h>
#include <wlr/render/allocator.h>
#include <wlr/render/dmabuf.h>
#include <wlr/render/wlr_renderer.h>
#include <wlr/render/wlr_texture.h>
#include <wlr/types/wlr_buffer.h>
#include <wlr/types/wlr_compositor.h>
#include <wlr/types/wlr_data_device.h>
#include <wlr/types/wlr_keyboard.h>
#include <wlr/types/wlr_text_input_v3.h>
#include <linux/input-event-codes.h>
#include <wlr/types/wlr_output.h>
#include <wlr/types/wlr_output_layout.h>
#include <wlr/types/wlr_scene.h>
#include <wlr/types/wlr_seat.h>
#include <wlr/types/wlr_subcompositor.h>
#include <wlr/types/wlr_xdg_shell.h>
#include <wlr/util/log.h>

#define MAX_SLOTS 10

struct touch_event {
	int slot;
	int kind; /* 0 down, 1 motion, 2 up, 3 new display client, 4 keyboard commands queued (lws thread -> main thread) */
	int x, y;
};

struct frame {
	unsigned char *data;
	size_t size, cap;
	uint64_t seq;
	bool key; /* a client can start decoding here (always for JPEG; an IDR for H.264) */
};

#define FRAME_RING 64
#define IME_QUEUE 64
#define IME_CMD_MAX 1024

struct server {
	struct wl_display *display;
	struct wl_event_loop *loop;
	struct wlr_backend *backend;
	struct wlr_renderer *renderer;
	struct wlr_allocator *allocator;
	struct wlr_scene *scene;
	struct wlr_output_layout *output_layout;
	struct wlr_scene_output_layout *scene_layout;
	struct wlr_xdg_shell *xdg_shell;
	struct wlr_seat *seat;
	struct wlr_keyboard keyboard;
	struct wlr_output *output;
	struct wlr_scene_output *scene_output;
	struct wl_listener new_output, output_frame, new_toplevel, new_popup;
	int width, height, refresh_mhz;
	/* the frame clock of a headless output (see the top comment); -1 with other backends */
	int frame_tfd;
	struct wl_event_source *frame_tick;
	struct wl_list toplevels; /* struct toplevel.link: each is sized to the output */
	struct wl_event_source *hup_source;

	/* touch state per slot: the surface that got the down and its layout offset */
	struct wlr_surface *slot_surface[MAX_SLOTS];
	double slot_off_x[MAX_SLOTS], slot_off_y[MAX_SLOTS];
	int touch_pipe[2];
	struct wl_event_source *touch_source;

	/* frames to the browser */
	int quality;
	tjhandle tj;
	pthread_mutex_t frame_lock;
	/* the recent encoded frames, owned under frame_lock: frame seq N lives in frames[N % FRAME_RING].
	 * JPEG clients get the newest one (newest frame wins); H.264 clients get every frame in order,
	 * since each P frame references the one before it, and a client that falls behind by a whole
	 * ring skips to the next key frame and asks for an IDR */
	struct frame frames[FRAME_RING];
	uint64_t frame_seq;
	struct lws_context *lws;
	pthread_t lws_thread;
	volatile bool stop;
	/* H.264: the last buffer handed to the encoder (locked) and a pending IDR request */
	struct wlr_buffer *last_buffer;
	bool want_key;
	/* the encoder thread (encoder_main): one pending frame (newest wins), the finished ones go back
	 * to the main thread through enc_efd to be released; enc_reset asks it to close its encoders */
	pthread_t enc_thread;
	pthread_mutex_t enc_lock;
	pthread_cond_t enc_cond;
	struct encode_job *enc_pending, *enc_done;
	int enc_efd;
	bool enc_reset;
	int m2m_unavailable; /* set by the encoder thread when the V4L2 encoder cannot be used */
	/* the device keyboard: text-input-v3 from the app <-> the page's /sockets/keyboard (TACOMP_IME_PORT).
	 * The page is told when a text field wants input; what it types comes back as commands, queued by
	 * the lws thread and applied here on the main thread (wlroots is not thread-safe). */
	struct wlr_text_input_manager_v3 *text_input_mgr;
	struct wl_listener new_text_input;
	struct wl_list text_inputs; /* struct tl_text_input.link */
	struct tl_text_input *ime_active;
	pthread_mutex_t ime_lock;
	char ime_msg[256];          /* the state the page sees, under ime_lock */
	uint64_t ime_seq;
	char ime_queue[IME_QUEUE][IME_CMD_MAX];
	int ime_head, ime_count;
};

struct tl_text_input {
	struct server *server;
	struct wlr_text_input_v3 *ti;
	struct wl_listener enable, commit, disable, destroy;
	struct wl_list link;
};

struct toplevel {
	struct server *server;
	struct wlr_xdg_toplevel *xdg_toplevel;
	struct wlr_scene_tree *scene_tree;
	struct wl_listener map, unmap, commit, destroy, request_fullscreen;
	struct wl_list link;
};

struct popup {
	struct wlr_xdg_popup *xdg_popup;
	struct wl_listener commit, destroy;
};

struct display_client {
	uint64_t sent_seq;
	bool need_key; /* H.264: nothing is sent until a key frame (deltas before it cannot decode) */
};

static struct server *g_server;

static uint32_t now_msec(void)
{
	struct timespec ts;
	clock_gettime(CLOCK_MONOTONIC, &ts);
	return (uint32_t)(ts.tv_sec * 1000 + ts.tv_nsec / 1000000);
}

/* ---------------------------------------------------------------- frames */

/* the requested stream is H.264 (renderer 1 or 2) */
static bool want_h264(void)
{
	const char *mode = getenv("TACOMP_ENCODER");
	return mode && (!strcmp(mode, "m2m-h264") || !strcmp(mode, "sw-h264"));
}

/* an Annex-B access unit that holds an IDR slice (NAL type 5) */
static bool h264_has_idr(const unsigned char *d, size_t n)
{
	for (size_t i = 0; i + 3 < n; i++) {
		if (d[i] == 0 && d[i + 1] == 0 && d[i + 2] == 1) {
			if ((d[i + 3] & 0x1f) == 5)
				return true;
			i += 2;
		}
	}
	return false;
}

/* one frame of the stream, from the encoder thread */
struct encode_job {
	struct encode_job *next;
	struct wlr_buffer *buffer;    /* dmabuf frames: locked by the main thread until the job comes back */
	const unsigned char *pixels;  /* CPU pixels (`copy`) */
	unsigned char *copy;          /* the frame's pixels, copied or read back by the main thread */
	int stride;
	uint32_t format;
	bool have_dmabuf;
	struct wlr_dmabuf_attributes dmabuf;
	int width, height, fps, quality;
	char mode[16];                /* TACOMP_ENCODER when the frame was captured ("": CPU JPEG) */
	bool force_key;               /* H.264: this frame is an IDR */
	bool reset;                   /* close the encoders first (reconfiguration) */
};

static bool job_h264(const struct encode_job *j)
{
	return !strcmp(j->mode, "m2m-h264") || !strcmp(j->mode, "sw-h264");
}

static void publish_frame(struct server *s, const unsigned char *jpeg, size_t size, bool h264)
{
	const bool key = !h264 || h264_has_idr(jpeg, size);
	pthread_mutex_lock(&s->frame_lock);
	const uint64_t seq = s->frame_seq + 1;
	struct frame *f = &s->frames[seq % FRAME_RING];
	if (f->cap < size) {
		f->data = realloc(f->data, size);
		f->cap = size;
	}
	memcpy(f->data, jpeg, size);
	f->size = size;
	f->key = key;
	f->seq = seq;
	s->frame_seq = seq;
	pthread_mutex_unlock(&s->frame_lock);
	if (s->lws)
		lws_cancel_service(s->lws);
}

static int tj_pixel_format(uint32_t drm_format, bool *ok)
{
	*ok = true;
	switch (drm_format) {
	case DRM_FORMAT_XRGB8888: return TJPF_BGRX;
	case DRM_FORMAT_ARGB8888: return TJPF_BGRA;
	case DRM_FORMAT_XBGR8888: return TJPF_RGBX;
	case DRM_FORMAT_ABGR8888: return TJPF_RGBA;
	default: *ok = false; return TJPF_BGRX;
	}
}

/* ---- software H.264 (x264): renderer 1/2 where there is no V4L2 M2M encoder (the test VM, or a
 * Pi whose codec is busy or missing). Same stream as the hardware path (docs/UPSTREAM-NOTES.md):
 * Annex-B access units, Constrained Baseline, SPS/PPS repeated on every IDR, GOP 30, ABR at
 * 2000 + 16000*(quality-1)/99 kbit/s with QP 16-32, one access unit per WebSocket message.
 * zerolatency tuning: no B-frames, no lookahead, so every input frame comes straight out. */
#ifdef TACOMP_X264
#include <x264.h>

static x264_t *sw_enc;
static x264_picture_t sw_pic;
static int sw_width, sw_height;
static int64_t sw_pts;
static bool sw_failed;

static void sw_h264_close(void)
{
	if (sw_enc) {
		x264_encoder_close(sw_enc);
		x264_picture_clean(&sw_pic);
		sw_enc = NULL;
	}
}

static bool sw_h264_open(const struct encode_job *j)
{
	x264_param_t p;
	if (x264_param_default_preset(&p, "ultrafast", "zerolatency") < 0)
		return false;
	int fps = j->fps;
	int kbps = 2000 + 16000 * (j->quality - 1) / 99;
	/* one slice per frame, like the hardware encoder: the frontend's WebCodecs worker turns every NAL
	 * unit into one EncodedVideoChunk, so a frame split into slices (x264's sliced threads, which
	 * zerolatency selects) would be decoded as separate half pictures. Frame threads would add a
	 * frame of latency per thread, so x264 runs on one thread (ultrafast keeps up at these sizes). */
	p.i_threads = 1;
	p.b_sliced_threads = 0;
	p.i_slice_count = 1;
	p.i_width = j->width;
	p.i_height = j->height;
	p.i_csp = X264_CSP_I420;
	p.i_fps_num = fps > 0 ? fps : 30;
	p.i_fps_den = 1;
	p.b_vfr_input = 0;
	p.i_keyint_max = 30;
	p.b_repeat_headers = 1; /* SPS/PPS in front of every IDR */
	p.b_annexb = 1;
	p.rc.i_rc_method = X264_RC_ABR;
	p.rc.i_bitrate = kbps;
	p.rc.i_vbv_max_bitrate = kbps;
	p.rc.i_vbv_buffer_size = kbps / 2; /* half a second: low latency */
	p.rc.i_qp_min = 16;
	p.rc.i_qp_max = 32;
	/* signal what rgb_to_i420() produces: BT.709, limited range (browsers otherwise guess) */
	p.vui.i_colorprim = 1;
	p.vui.i_transfer = 1;
	p.vui.i_colmatrix = 1;
	p.vui.b_fullrange = 0;
	p.i_log_level = X264_LOG_WARNING;
	if (x264_param_apply_profile(&p, "baseline") < 0)
		return false;
	sw_enc = x264_encoder_open(&p);
	if (!sw_enc)
		return false;
	if (x264_picture_alloc(&sw_pic, X264_CSP_I420, j->width, j->height) < 0) {
		x264_encoder_close(sw_enc);
		sw_enc = NULL;
		return false;
	}
	sw_width = j->width;
	sw_height = j->height;
	sw_pts = 0;
	wlr_log(WLR_INFO, "software H.264 (x264 ultrafast/zerolatency, baseline, one slice per frame): %dx%d@%d, %d kbit/s",
	        j->width, j->height, p.i_fps_num, kbps);
	return true;
}

/* byte offsets of R, G, B in a 32-bit pixel of this DRM format (little-endian memory order) */
static bool rgb_offsets(uint32_t drm_format, int *r, int *g, int *b)
{
	switch (drm_format) {
	case DRM_FORMAT_XRGB8888:
	case DRM_FORMAT_ARGB8888: *b = 0; *g = 1; *r = 2; return true;
	case DRM_FORMAT_XBGR8888:
	case DRM_FORMAT_ABGR8888: *r = 0; *g = 1; *b = 2; return true;
	default: return false;
	}
}

/* 32-bit RGB to I420, BT.709 limited range (w and h even); chroma from the average of each 2x2 block */
static void rgb_to_i420(const unsigned char *src, int stride, int w, int h, int ro, int go, int bo, x264_image_t *img)
{
	uint8_t *Y = img->plane[0], *U = img->plane[1], *V = img->plane[2];
	const int ys = img->i_stride[0], us = img->i_stride[1], vs = img->i_stride[2];
	for (int y = 0; y < h; y += 2) {
		const unsigned char *row[2] = { src + (size_t)y * stride, src + (size_t)(y + 1) * stride };
		uint8_t *yrow[2] = { Y + (size_t)y * ys, Y + (size_t)(y + 1) * ys };
		uint8_t *u = U + (size_t)(y / 2) * us, *v = V + (size_t)(y / 2) * vs;
		for (int x = 0; x < w; x += 2) {
			int rs = 0, gs = 0, bs = 0;
			for (int dy = 0; dy < 2; dy++) {
				for (int dx = 0; dx < 2; dx++) {
					const unsigned char *px = row[dy] + 4 * (x + dx);
					const int r = px[ro], g = px[go], b = px[bo];
					yrow[dy][x + dx] = (uint8_t)(((47 * r + 157 * g + 16 * b + 128) >> 8) + 16);
					rs += r;
					gs += g;
					bs += b;
				}
			}
			rs = (rs + 2) >> 2;
			gs = (gs + 2) >> 2;
			bs = (bs + 2) >> 2;
			u[x / 2] = (uint8_t)(((-26 * rs - 86 * gs + 112 * bs + 128) >> 8) + 128);
			v[x / 2] = (uint8_t)(((112 * rs - 102 * gs - 10 * bs + 128) >> 8) + 128);
		}
	}
}

/* true when the frame went out as H.264 */
static bool encode_h264_sw(struct server *s, const struct encode_job *j)
{
	int ro, go, bo;
	if (sw_failed || !rgb_offsets(j->format, &ro, &go, &bo) || (j->width & 1) || (j->height & 1))
		return false;
	if (sw_enc && (sw_width != j->width || sw_height != j->height))
		sw_h264_close();
	if (!sw_enc && !sw_h264_open(j)) {
		wlr_log(WLR_ERROR, "x264 encoder unavailable");
		sw_failed = true;
		return false;
	}
	rgb_to_i420(j->pixels, j->stride, j->width, j->height, ro, go, bo, &sw_pic.img);
	sw_pic.i_pts = sw_pts++;
	sw_pic.i_type = j->force_key ? X264_TYPE_IDR : X264_TYPE_AUTO;
	x264_nal_t *nal;
	int n;
	x264_picture_t out;
	int size = x264_encoder_encode(sw_enc, &nal, &n, &sw_pic, &out);
	if (size < 0) {
		wlr_log(WLR_ERROR, "x264_encoder_encode failed");
		return false;
	}
	if (size > 0) /* the NAL payloads of one call are contiguous: one access unit */
		publish_frame(s, nal[0].p_payload, (size_t)size, true);
	return true;
}
#endif

/* encoder thread: the CPU pixels of a job as H.264 (x264) or JPEG */
static void encode_and_publish(struct server *s, const struct encode_job *j)
{
#ifdef TACOMP_X264
	if (job_h264(j) && encode_h264_sw(s, j))
		return;
#endif
	bool ok;
	int pf = tj_pixel_format(j->format, &ok);
	if (!ok) {
		static int warned;
		if (!warned++)
			wlr_log(WLR_ERROR, "unsupported pixel format 0x%08x for JPEG", j->format);
		return;
	}
	unsigned char *jpeg = NULL;
	unsigned long jpeg_size = 0;
	if (tjCompress2(s->tj, j->pixels, j->width, j->stride, j->height, pf, &jpeg, &jpeg_size, TJSAMP_420, j->quality,
	                TJFLAG_FASTDCT | TJFLAG_NOREALLOC * 0) != 0) {
		wlr_log(WLR_ERROR, "tjCompress2: %s", tjGetErrorStr2(s->tj));
		return;
	}
	publish_frame(s, jpeg, jpeg_size, false);
	tjFree(jpeg);
}

/* ---- the V4L2 M2M path (the Pi): the output buffer's dmabuf straight into the hardware encoder */
#ifdef TACOMP_M2M
#include <linux/videodev2.h>
#include "../m2m/m2m.h"

static us_m2m_encoder_s *m2m_enc;
static us_frame_s *m2m_src, *m2m_dst;
static bool m2m_failed;

static unsigned v4l2_format_for_drm(uint32_t drm_format)
{
	switch (drm_format) {
	case DRM_FORMAT_XRGB8888: return V4L2_PIX_FMT_XBGR32; /* bytes B G R X */
	case DRM_FORMAT_ARGB8888: return V4L2_PIX_FMT_ABGR32;
	case DRM_FORMAT_XBGR8888: return V4L2_PIX_FMT_XRGB32 ? V4L2_PIX_FMT_RGBX32 : 0;
	case DRM_FORMAT_ABGR8888: return V4L2_PIX_FMT_RGBA32;
	default: return 0;
	}
}

static void m2m_close(void)
{
	if (m2m_enc) {
		us_m2m_encoder_destroy(m2m_enc);
		m2m_enc = NULL;
	}
	m2m_failed = false;
}

/* encoder thread: true when the frame went out through the hardware encoder */
static bool encode_m2m(struct server *s, const struct encode_job *j)
{
	if (m2m_failed)
		return false;
	if (!m2m_enc) {
		if (!strcmp(j->mode, "m2m-h264"))
			m2m_enc = us_m2m_h264_encoder_init("tacomp-h264", NULL, 2000 + 16000 * (j->quality - 1) / 99, 30);
		else
			m2m_enc = us_m2m_mjpeg_encoder_init("tacomp-mjpeg", NULL, j->quality);
		if (!m2m_enc) {
			wlr_log(WLR_ERROR, "V4L2 M2M encoder unavailable, using the CPU path");
			m2m_failed = true;
			__atomic_store_n(&s->m2m_unavailable, 1, __ATOMIC_RELEASE);
			return false;
		}
		if (!m2m_src)
			m2m_src = us_frame_init();
		if (!m2m_dst)
			m2m_dst = us_frame_init();
	}
	const struct wlr_dmabuf_attributes attrs = j->dmabuf;
	unsigned fmt = v4l2_format_for_drm(attrs.format);
	if (!fmt)
		return false;
	m2m_src->dma_fd = attrs.fd[0];
	m2m_src->width = attrs.width;
	m2m_src->height = attrs.height;
	m2m_src->format = fmt;
	m2m_src->stride = attrs.stride[0];
	m2m_src->used = (size_t)attrs.stride[0] * attrs.height;
	bool h264 = !strcmp(j->mode, "m2m-h264");
	bool force_key = h264 && j->force_key;
	if (us_m2m_encoder_compress(m2m_enc, m2m_src, m2m_dst, force_key) != 0) {
		static int warned;
		if (!warned++)
			wlr_log(WLR_ERROR, "V4L2 M2M compress failed, using the CPU path");
		m2m_failed = true;
		__atomic_store_n(&s->m2m_unavailable, 1, __ATOMIC_RELEASE);
		return false;
	}
	publish_frame(s, m2m_dst->data, m2m_dst->used, h264);
	return true;
}
#endif

static void capture_frame(struct server *s, struct wlr_buffer *buffer);

/* main thread: a display client connected (cb_display ESTABLISHED, via the pipe) */
static void new_display_client(struct server *s)
{
	if (want_h264()) {
		/* hardware or x264: the new client needs an IDR to start decoding */
		s->want_key = true;
		if (s->last_buffer)
			capture_frame(s, s->last_buffer); /* an IDR of the current picture now */
	}
	if (s->output)
		wlr_output_schedule_frame(s->output); /* and a fresh frame for every encoder */
}

/* ---- the encoder thread ---- */

/* main thread: give a finished (or dropped) job's buffer back */
static void job_free(struct encode_job *j)
{
	if (j->buffer)
		wlr_buffer_unlock(j->buffer);
	free(j->copy);
	free(j);
}

static void enc_release_done(struct server *s)
{
	pthread_mutex_lock(&s->enc_lock);
	struct encode_job *list = s->enc_done;
	s->enc_done = NULL;
	pthread_mutex_unlock(&s->enc_lock);
	while (list) {
		struct encode_job *j = list;
		list = j->next;
		job_free(j);
	}
}

static int handle_enc_done(int fd, uint32_t mask, void *data)
{
	uint64_t n;
	if (read(fd, &n, sizeof n) < 0 && errno != EAGAIN)
		wlr_log(WLR_ERROR, "encoder eventfd: %s", strerror(errno));
	enc_release_done(data);
	return 0;
}

/* encoder thread: one frame */
static void encode_job(struct server *s, struct encode_job *j)
{
	if (j->reset) {
#ifdef TACOMP_X264
		sw_h264_close();
		sw_failed = false;
#endif
#ifdef TACOMP_M2M
		m2m_close();
		__atomic_store_n(&s->m2m_unavailable, 0, __ATOMIC_RELEASE);
#endif
	}
#ifdef TACOMP_M2M
	if (j->have_dmabuf && encode_m2m(s, j))
		return;
#endif
	if (j->pixels) /* (a dmabuf-only frame the hardware encoder refused is dropped; the next carries pixels) */
		encode_and_publish(s, j);
}

static void *encoder_main(void *data)
{
	struct server *s = data;
	pthread_mutex_lock(&s->enc_lock);
	for (;;) {
		while (!s->enc_pending && !s->stop)
			pthread_cond_wait(&s->enc_cond, &s->enc_lock);
		if (s->stop)
			break;
		struct encode_job *j = s->enc_pending;
		s->enc_pending = NULL;
		pthread_mutex_unlock(&s->enc_lock);
		encode_job(s, j);
		pthread_mutex_lock(&s->enc_lock);
		j->next = s->enc_done;
		s->enc_done = j;
		const uint64_t one = 1;
		if (write(s->enc_efd, &one, sizeof one) < 0 && errno != EAGAIN)
			wlr_log(WLR_ERROR, "encoder eventfd: %s", strerror(errno));
	}
	pthread_mutex_unlock(&s->enc_lock);
	return NULL;
}

/* main thread: the job becomes the encoder's next frame; one it has not started yet is dropped
 * (its key-frame and reset requests carry over) */
static void enc_submit(struct server *s, struct encode_job *j)
{
	pthread_mutex_lock(&s->enc_lock);
	struct encode_job *dropped = s->enc_pending;
	if (dropped) {
		j->force_key |= dropped->force_key;
		j->reset |= dropped->reset;
		dropped->next = s->enc_done;
		s->enc_done = dropped;
	}
	s->enc_pending = j;
	pthread_cond_signal(&s->enc_cond);
	pthread_mutex_unlock(&s->enc_lock);
	if (dropped)
		enc_release_done(s);
}

/* the rendered buffer of this frame, handed to the encoder thread: as a dmabuf for the hardware
 * encoder, CPU-mapped when the renderer draws into shm, else read back here (GL is this thread's) */
static void capture_frame(struct server *s, struct wlr_buffer *buffer)
{
	struct encode_job *j = calloc(1, sizeof *j);
	if (!j)
		return;
	const char *mode = getenv("TACOMP_ENCODER");
	snprintf(j->mode, sizeof j->mode, "%s", mode ? mode : "");
	j->width = s->width;
	j->height = s->height;
	j->fps = s->refresh_mhz > 0 ? (s->refresh_mhz + 500) / 1000 : 30;
	j->quality = s->quality;
	j->force_key = job_h264(j) && s->want_key;
	if (j->force_key)
		s->want_key = false;
	j->reset = s->enc_reset;
	s->enc_reset = false;
	if (job_h264(j) && buffer != s->last_buffer) {
		/* keep the buffer for an IDR on the next client connect while the screen does not change */
		struct wlr_buffer *old = s->last_buffer;
		s->last_buffer = wlr_buffer_lock(buffer);
		if (old)
			wlr_buffer_unlock(old);
	}
#ifdef TACOMP_M2M
	if (!strncmp(j->mode, "m2m", 3) && !__atomic_load_n(&s->m2m_unavailable, __ATOMIC_ACQUIRE) &&
	    wlr_buffer_get_dmabuf(buffer, &j->dmabuf) && j->dmabuf.n_planes >= 1) {
		j->have_dmabuf = true;
		j->buffer = wlr_buffer_lock(buffer); /* the encoder reads the dmabuf */
		enc_submit(s, j);
		return;
	}
#endif
	const size_t need = (size_t)s->width * 4 * s->height;
	void *data;
	uint32_t format;
	size_t stride;
	if (wlr_buffer_begin_data_ptr_access(buffer, WLR_BUFFER_DATA_PTR_ACCESS_READ, &data, &format, &stride)) {
		/* a copy: the access cannot stay open across threads (the same buffer may be captured again,
		 * e.g. the last picture as an IDR for a new client, while it is being encoded) */
		j->copy = malloc(need);
		if (j->copy)
			for (int y = 0; y < s->height; y++)
				memcpy(j->copy + (size_t)y * s->width * 4, (const unsigned char *)data + (size_t)y * stride, (size_t)s->width * 4);
		wlr_buffer_end_data_ptr_access(buffer);
		if (!j->copy) {
			job_free(j);
			return;
		}
		j->pixels = j->copy;
		j->stride = s->width * 4;
		j->format = format;
		enc_submit(s, j);
		return;
	}
	struct wlr_texture *tex = wlr_texture_from_buffer(s->renderer, buffer);
	j->copy = tex ? malloc(need) : NULL;
	struct wlr_texture_read_pixels_options opts = {
		.data = j->copy,
		.format = DRM_FORMAT_XRGB8888,
		.stride = (uint32_t)s->width * 4,
	};
	const bool read = j->copy && wlr_texture_read_pixels(tex, &opts);
	if (tex)
		wlr_texture_destroy(tex);
	if (!read) {
		job_free(j);
		return;
	}
	j->pixels = j->copy;
	j->stride = s->width * 4;
	j->format = DRM_FORMAT_XRGB8888;
	enc_submit(s, j);
}

/* one output frame: build and commit the scene, frame callbacks to the clients, then encode the
 * committed buffer (after the commit and the callbacks: the encoder's time delays neither) */
static void output_frame(struct server *s)
{
	static unsigned frames, built, captured;
	frames++;
	if (!wlr_scene_output_needs_frame(s->scene_output)) {
		/* nothing changed (no damage, no frame requested): nothing to build, commit or encode; the
		 * clients still get their frame callbacks (wlr_scene_output_commit skips such frames too) */
		struct timespec now;
		clock_gettime(CLOCK_MONOTONIC, &now);
		wlr_scene_output_send_frame_done(s->scene_output, &now);
		return;
	}
	struct wlr_output_state state;
	wlr_output_state_init(&state);
	struct wlr_buffer *capture = NULL;
	if (wlr_scene_output_build_state(s->scene_output, &state, NULL)) {
		built++;
		if ((state.committed & WLR_OUTPUT_STATE_BUFFER) && state.buffer)
			capture = state.buffer; /* referenced by the state until wlr_output_state_finish */
		if (!wlr_output_commit_state(s->output, &state))
			capture = NULL;
	}
	struct timespec now;
	clock_gettime(CLOCK_MONOTONIC, &now);
	wlr_scene_output_send_frame_done(s->scene_output, &now);
	if (capture) {
		captured++;
		capture_frame(s, capture);
	}
	wlr_output_state_finish(&state);
	if (built <= 3 || built % 300 == 0)
		wlr_log(WLR_INFO, "frame ticks %u, built %u, captured %u, published %llu", frames, built, captured, (unsigned long long)s->frame_seq);
}

/* the backend's frame event: the frame clock for outputs without tacomp's own (not headless) */
static void handle_output_frame(struct wl_listener *listener, void *data)
{
	struct server *s = wl_container_of(listener, s, output_frame);
	if (s->frame_tfd < 0)
		output_frame(s);
}

/* tacomp's frame clock for a headless output: one frame per tick of a periodic timerfd */
static int handle_frame_tick(int fd, uint32_t mask, void *data)
{
	struct server *s = data;
	uint64_t expirations;
	if (read(fd, &expirations, sizeof expirations) != sizeof expirations)
		return 0;
	if (s->output && s->scene_output)
		output_frame(s); /* one frame however many ticks passed: a late frame is not repeated */
	return 0;
}

/* (re)start the frame clock at the output's refresh, the first tick one period from now */
static void frame_clock_arm(struct server *s)
{
	if (s->frame_tfd < 0)
		return;
	const long long period_ns = 1000000000000LL / (s->refresh_mhz > 0 ? s->refresh_mhz : 60000);
	struct itimerspec its = {
		.it_interval = { .tv_sec = period_ns / 1000000000LL, .tv_nsec = period_ns % 1000000000LL },
		.it_value = { .tv_sec = period_ns / 1000000000LL, .tv_nsec = period_ns % 1000000000LL },
	};
	if (timerfd_settime(s->frame_tfd, 0, &its, NULL) != 0)
		wlr_log(WLR_ERROR, "frame clock: timerfd_settime: %s", strerror(errno));
	else
		wlr_log(WLR_INFO, "frame clock: %.3f ms", period_ns / 1e6);
}

/* ---------------------------------------------------------------- output */

static void handle_new_output(struct wl_listener *listener, void *data)
{
	struct server *s = wl_container_of(listener, s, new_output);
	struct wlr_output *output = data;
	wlr_output_init_render(output, s->allocator, s->renderer);
	struct wlr_output_state state;
	wlr_output_state_init(&state);
	wlr_output_state_set_enabled(&state, true);
	wlr_output_state_set_custom_mode(&state, s->width, s->height, s->refresh_mhz);
	wlr_output_commit_state(output, &state);
	wlr_output_state_finish(&state);
	s->output = output;
	s->output_frame.notify = handle_output_frame;
	wl_signal_add(&output->events.frame, &s->output_frame);
	if (wlr_output_is_headless(output) && s->frame_tfd < 0) {
		s->frame_tfd = timerfd_create(CLOCK_MONOTONIC, TFD_NONBLOCK | TFD_CLOEXEC);
		if (s->frame_tfd < 0)
			wlr_log(WLR_ERROR, "frame clock: timerfd_create: %s; using the backend's frame events", strerror(errno));
		else
			s->frame_tick = wl_event_loop_add_fd(s->loop, s->frame_tfd, WL_EVENT_READABLE, handle_frame_tick, s);
	}
	struct wlr_output_layout_output *l_output = wlr_output_layout_add(s->output_layout, output, 0, 0);
	s->scene_output = wlr_scene_output_create(s->scene, output);
	wlr_scene_output_layout_add_output(s->scene_layout, l_output, s->scene_output);
	wlr_log(WLR_INFO, "output %dx%d @ %d mHz", s->width, s->height, s->refresh_mhz);
	frame_clock_arm(s);
}

/* ---------------------------------------------------------------- device keyboard (text-input-v3) */

/* main thread: publish the IME state to the page ({"type":"ime","active":...}); t NULL = no field */
static void ime_publish(struct server *s, struct tl_text_input *t)
{
	char msg[256];
	if (t)
		snprintf(msg, sizeof msg, "{\"type\":\"ime\",\"active\":true,\"purpose\":%u,\"hint\":%u}",
		         t->ti->current.content_type.purpose, t->ti->current.content_type.hint);
	else
		snprintf(msg, sizeof msg, "{\"type\":\"ime\",\"active\":false}");
	pthread_mutex_lock(&s->ime_lock);
	bool changed = strcmp(msg, s->ime_msg) != 0;
	if (changed) {
		snprintf(s->ime_msg, sizeof s->ime_msg, "%s", msg);
		s->ime_seq++;
	}
	pthread_mutex_unlock(&s->ime_lock);
	if (changed && s->lws)
		lws_cancel_service(s->lws);
}

/* a text input of the client that has keyboard focus gets enter for the focused surface, others leave */
static void ime_follow_focus(struct server *s, struct tl_text_input *t)
{
	struct wlr_surface *focus = s->seat->keyboard_state.focused_surface;
	if (!focus || wl_resource_get_client(focus->resource) != wl_resource_get_client(t->ti->resource)) {
		if (t->ti->focused_surface)
			wlr_text_input_v3_send_leave(t->ti);
		return;
	}
	if (t->ti->focused_surface == focus)
		return;
	if (t->ti->focused_surface)
		wlr_text_input_v3_send_leave(t->ti);
	wlr_text_input_v3_send_enter(t->ti, focus);
}

static void ti_enable(struct wl_listener *l, void *data)
{
	struct tl_text_input *t = wl_container_of(l, t, enable);
	t->server->ime_active = t;
	ime_publish(t->server, t);
}

static void ti_commit(struct wl_listener *l, void *data)
{
	struct tl_text_input *t = wl_container_of(l, t, commit);
	if (t->server->ime_active == t && t->ti->current_enabled)
		ime_publish(t->server, t); /* the content type may have changed */
}

static void ti_disable(struct wl_listener *l, void *data)
{
	struct tl_text_input *t = wl_container_of(l, t, disable);
	if (t->server->ime_active == t) {
		t->server->ime_active = NULL;
		ime_publish(t->server, NULL);
	}
}

static void ti_destroy(struct wl_listener *l, void *data)
{
	struct tl_text_input *t = wl_container_of(l, t, destroy);
	if (t->server->ime_active == t) {
		t->server->ime_active = NULL;
		ime_publish(t->server, NULL);
	}
	wl_list_remove(&t->enable.link);
	wl_list_remove(&t->commit.link);
	wl_list_remove(&t->disable.link);
	wl_list_remove(&t->destroy.link);
	wl_list_remove(&t->link);
	free(t);
}

static void handle_new_text_input(struct wl_listener *l, void *data)
{
	struct server *s = wl_container_of(l, s, new_text_input);
	struct wlr_text_input_v3 *ti = data;
	if (ti->seat != s->seat)
		return;
	struct tl_text_input *t = calloc(1, sizeof *t);
	if (!t)
		return;
	t->server = s;
	t->ti = ti;
	t->enable.notify = ti_enable;
	wl_signal_add(&ti->events.enable, &t->enable);
	t->commit.notify = ti_commit;
	wl_signal_add(&ti->events.commit, &t->commit);
	t->disable.notify = ti_disable;
	wl_signal_add(&ti->events.disable, &t->disable);
	t->destroy.notify = ti_destroy;
	wl_signal_add(&ti->events.destroy, &t->destroy);
	wl_list_insert(&s->text_inputs, &t->link);
	ime_follow_focus(s, t);
}

/* a key press and release on the seat's keyboard (evdev code), to the focused surface */
static void ime_key(struct server *s, uint32_t key)
{
	uint32_t ms = now_msec();
	wlr_seat_keyboard_notify_key(s->seat, ms, key, WL_KEYBOARD_KEY_STATE_PRESSED);
	wlr_seat_keyboard_notify_key(s->seat, ms + 1, key, WL_KEYBOARD_KEY_STATE_RELEASED);
}

/* one command from the page: "t TEXT" commits TEXT into the active field, "k NAME" presses a key */
static void ime_apply(struct server *s, const char *cmd)
{
	if (cmd[0] == 't' && cmd[1] == ' ') {
		struct tl_text_input *t = s->ime_active;
		if (t && t->ti->current_enabled && cmd[2]) {
			wlr_text_input_v3_send_commit_string(t->ti, cmd + 2);
			wlr_text_input_v3_send_done(t->ti);
		}
	} else if (cmd[0] == 'k' && cmd[1] == ' ') {
		const char *k = cmd + 2;
		if (!strcmp(k, "enter"))
			ime_key(s, KEY_ENTER);
		else if (!strcmp(k, "backspace"))
			ime_key(s, KEY_BACKSPACE);
		else if (!strcmp(k, "tab"))
			ime_key(s, KEY_TAB);
		else if (!strcmp(k, "escape"))
			ime_key(s, KEY_ESC);
	}
}

static void ime_drain(struct server *s)
{
	for (;;) {
		char cmd[IME_CMD_MAX];
		pthread_mutex_lock(&s->ime_lock);
		if (s->ime_count == 0) {
			pthread_mutex_unlock(&s->ime_lock);
			return;
		}
		memcpy(cmd, s->ime_queue[s->ime_head], IME_CMD_MAX);
		s->ime_head = (s->ime_head + 1) % IME_QUEUE;
		s->ime_count--;
		pthread_mutex_unlock(&s->ime_lock);
		ime_apply(s, cmd);
	}
}

/* ---------------------------------------------------------------- xdg shell */

static void focus_toplevel(struct toplevel *t)
{
	struct wlr_surface *surface = t->xdg_toplevel->base->surface;
	struct wlr_keyboard *kb = wlr_seat_get_keyboard(t->server->seat);
	if (kb)
		wlr_seat_keyboard_notify_enter(t->server->seat, surface, kb->keycodes, kb->num_keycodes, &kb->modifiers);
	else
		wlr_seat_keyboard_notify_enter(t->server->seat, surface, NULL, 0, NULL);
	struct tl_text_input *ti;
	wl_list_for_each(ti, &t->server->text_inputs, link)
		ime_follow_focus(t->server, ti);
}

static void toplevel_map(struct wl_listener *listener, void *data)
{
	struct toplevel *t = wl_container_of(listener, t, map);
	wlr_scene_node_set_position(&t->scene_tree->node, 0, 0);
	wlr_scene_node_raise_to_top(&t->scene_tree->node);
	focus_toplevel(t);
}

static void toplevel_unmap(struct wl_listener *listener, void *data) {}

static void toplevel_commit(struct wl_listener *listener, void *data)
{
	struct toplevel *t = wl_container_of(listener, t, commit);
	if (t->xdg_toplevel->base->initial_commit) {
		/* the one and only window: fullscreen at the output size */
		wlr_xdg_toplevel_set_size(t->xdg_toplevel, t->server->width, t->server->height);
		wlr_xdg_toplevel_set_fullscreen(t->xdg_toplevel, true);
		wlr_xdg_toplevel_set_activated(t->xdg_toplevel, true);
	}
}

static void toplevel_request_fullscreen(struct wl_listener *listener, void *data)
{
	struct toplevel *t = wl_container_of(listener, t, request_fullscreen);
	if (t->xdg_toplevel->base->initialized)
		wlr_xdg_surface_schedule_configure(t->xdg_toplevel->base);
}

static void toplevel_destroy(struct wl_listener *listener, void *data)
{
	struct toplevel *t = wl_container_of(listener, t, destroy);
	wl_list_remove(&t->map.link);
	wl_list_remove(&t->unmap.link);
	wl_list_remove(&t->commit.link);
	wl_list_remove(&t->destroy.link);
	wl_list_remove(&t->request_fullscreen.link);
	wl_list_remove(&t->link);
	free(t);
}

static void handle_new_toplevel(struct wl_listener *listener, void *data)
{
	struct server *s = wl_container_of(listener, s, new_toplevel);
	struct wlr_xdg_toplevel *xdg_toplevel = data;
	struct toplevel *t = calloc(1, sizeof(*t));
	t->server = s;
	t->xdg_toplevel = xdg_toplevel;
	wl_list_insert(&s->toplevels, &t->link);
	t->scene_tree = wlr_scene_xdg_surface_create(&s->scene->tree, xdg_toplevel->base);
	t->scene_tree->node.data = t;
	xdg_toplevel->base->data = t->scene_tree;
	t->map.notify = toplevel_map;
	wl_signal_add(&xdg_toplevel->base->surface->events.map, &t->map);
	t->unmap.notify = toplevel_unmap;
	wl_signal_add(&xdg_toplevel->base->surface->events.unmap, &t->unmap);
	t->commit.notify = toplevel_commit;
	wl_signal_add(&xdg_toplevel->base->surface->events.commit, &t->commit);
	t->destroy.notify = toplevel_destroy;
	wl_signal_add(&xdg_toplevel->events.destroy, &t->destroy);
	t->request_fullscreen.notify = toplevel_request_fullscreen;
	wl_signal_add(&xdg_toplevel->events.request_fullscreen, &t->request_fullscreen);
}

static void popup_commit(struct wl_listener *listener, void *data)
{
	struct popup *p = wl_container_of(listener, p, commit);
	if (p->xdg_popup->base->initial_commit)
		wlr_xdg_surface_schedule_configure(p->xdg_popup->base);
}

static void popup_destroy(struct wl_listener *listener, void *data)
{
	struct popup *p = wl_container_of(listener, p, destroy);
	wl_list_remove(&p->commit.link);
	wl_list_remove(&p->destroy.link);
	free(p);
}

static void handle_new_popup(struct wl_listener *listener, void *data)
{
	struct wlr_xdg_popup *xdg_popup = data;
	struct popup *p = calloc(1, sizeof(*p));
	p->xdg_popup = xdg_popup;
	struct wlr_xdg_surface *parent = wlr_xdg_surface_try_from_wlr_surface(xdg_popup->parent);
	assert(parent != NULL);
	struct wlr_scene_tree *parent_tree = parent->data;
	xdg_popup->base->data = wlr_scene_xdg_surface_create(parent_tree, xdg_popup->base);
	p->commit.notify = popup_commit;
	wl_signal_add(&xdg_popup->base->surface->events.commit, &p->commit);
	p->destroy.notify = popup_destroy;
	wl_signal_add(&xdg_popup->events.destroy, &p->destroy);
}

/* ---------------------------------------------------------------- touch injection (main thread) */

static void apply_touch(struct server *s, const struct touch_event *ev)
{
	if (ev->slot < 0 || ev->slot >= MAX_SLOTS)
		return;
	uint32_t t = now_msec();
	int id = ev->slot;
	switch (ev->kind) {
	case 0: {
		double sx, sy;
		struct wlr_scene_node *node = wlr_scene_node_at(&s->scene->tree.node, ev->x, ev->y, &sx, &sy);
		struct wlr_scene_surface *ss = NULL;
		if (node && node->type == WLR_SCENE_NODE_BUFFER)
			ss = wlr_scene_surface_try_from_buffer(wlr_scene_buffer_from_node(node));
		if (!ss)
			return;
		s->slot_surface[ev->slot] = ss->surface;
		s->slot_off_x[ev->slot] = ev->x - sx;
		s->slot_off_y[ev->slot] = ev->y - sy;
		wlr_seat_touch_notify_down(s->seat, ss->surface, t, id, sx, sy);
		break;
	}
	case 1:
		if (!s->slot_surface[ev->slot])
			return;
		wlr_seat_touch_notify_motion(s->seat, t, id, ev->x - s->slot_off_x[ev->slot], ev->y - s->slot_off_y[ev->slot]);
		break;
	case 2:
		if (!s->slot_surface[ev->slot])
			return;
		wlr_seat_touch_notify_up(s->seat, t, id);
		s->slot_surface[ev->slot] = NULL;
		break;
	}
	wlr_seat_touch_notify_frame(s->seat);
}

static int handle_touch_pipe(int fd, uint32_t mask, void *data)
{
	struct server *s = data;
	struct touch_event ev;
	while (read(fd, &ev, sizeof ev) == (ssize_t)sizeof ev) {
		if (ev.kind == 3)
			new_display_client(s);
		else if (ev.kind == 4)
			ime_drain(s);
		else
			apply_touch(s, &ev);
	}
	return 0;
}

/* ---------------------------------------------------------------- websockets (lws thread) */

/* The upstream touch protocol is Linux multitouch protocol B over text (docs/UPSTREAM-NOTES.md):
 * "s N" selects a slot, "T id" / "a 0|1" / "X" / "Y" change it, "S 0" (SYN_REPORT) commits every
 * slot changed since the previous report, in slot order, as one frame. A report may change several
 * slots: the frontend lifts all of them with one report (resetTouchscreen). Committing only the
 * selected slot dropped the others, and a finger lifted that way stayed down for good: after the
 * first pinch every new gesture carried ghost fingers and pinch stopped working. Positions persist
 * per slot between reports, as evdev's do. */
struct touch_slot {
	int x, y;
	bool have_x, have_y; /* a position was ever reported for the current contact */
	bool dirty, lifted, has_down;
};

struct touch_parse {
	int slot, tracking;
	struct touch_slot s[MAX_SLOTS];
	bool active[MAX_SLOTS];
	char line[64];
	int line_len;
};

static void touch_commit(struct touch_parse *p)
{
	for (int sl = 0; sl < MAX_SLOTS; sl++) {
		struct touch_slot *t = &p->s[sl];
		if (!t->dirty)
			continue;
		struct touch_event ev = { .slot = sl, .x = t->x, .y = t->y };
		bool send = true;
		if (t->lifted) {
			send = p->active[sl];
			ev.kind = 2;
			p->active[sl] = false;
			t->have_x = t->have_y = false;
		} else if (!p->active[sl]) {
			send = t->have_x && t->have_y;
			ev.kind = 0;
			if (send)
				p->active[sl] = true;
		} else {
			send = t->have_x && t->have_y;
			ev.kind = 1;
		}
		t->dirty = t->lifted = t->has_down = false;
		if (send && write(g_server->touch_pipe[1], &ev, sizeof ev) != (ssize_t)sizeof ev)
			wlr_log(WLR_ERROR, "touch pipe write failed");
	}
}

static void touch_line(struct touch_parse *p, const char *line)
{
	char c = line[0];
	int v = atoi(line + 1);
	struct touch_slot *t = (p->slot >= 0 && p->slot < MAX_SLOTS) ? &p->s[p->slot] : NULL;
	switch (c) {
	case 's': p->slot = v; break;
	case 'T':
		if (!t)
			break;
		p->tracking = v;
		t->dirty = true;
		if (v < 0) {
			t->lifted = true;
		} else {
			if (t->lifted || !p->active[p->slot])
				t->have_x = t->have_y = false; /* a new contact reports its own position */
			t->lifted = false;
			t->has_down = true;
		}
		break;
	case 'a':
		if (!t)
			break;
		t->dirty = true;
		if (v == 0)
			t->lifted = true;
		else
			t->has_down = true;
		break;
	case 'X': if (t) { t->x = v; t->have_x = true; t->dirty = true; } break;
	case 'Y': if (t) { t->y = v; t->have_y = true; t->dirty = true; } break;
	case 'S': touch_commit(p); break;
	default: break;
	}
}

static int cb_touch(struct lws *wsi, enum lws_callback_reasons reason, void *user, void *in, size_t len)
{
	struct touch_parse *p = user;
	switch (reason) {
	case LWS_CALLBACK_ESTABLISHED:
		memset(p, 0, sizeof *p);
		break;
	case LWS_CALLBACK_RECEIVE:
		for (size_t i = 0; i < len; i++) {
			char ch = ((const char *)in)[i];
			if (ch == '\n') {
				p->line[p->line_len] = 0;
				if (p->line_len)
					touch_line(p, p->line);
				p->line_len = 0;
			} else if (p->line_len < (int)sizeof p->line - 1) {
				p->line[p->line_len++] = ch;
			}
		}
		break;
	case LWS_CALLBACK_CLOSED:
		/* the frontend lifts all slots on open; lift what it left down */
		for (int sl = 0; sl < MAX_SLOTS; sl++) {
			if (p->active[sl]) {
				struct touch_event ev = { .slot = sl, .kind = 2 };
				if (write(g_server->touch_pipe[1], &ev, sizeof ev) != (ssize_t)sizeof ev)
					wlr_log(WLR_ERROR, "touch pipe write failed");
			}
		}
		break;
	default:
		break;
	}
	return 0;
}

static int cb_display(struct lws *wsi, enum lws_callback_reasons reason, void *user, void *in, size_t len)
{
	struct display_client *c = user;
	struct server *s = g_server;
	switch (reason) {
	case LWS_CALLBACK_ESTABLISHED: {
		c->sent_seq = 0;
		c->need_key = want_h264(); /* H.264 starts at the IDR the main thread forces for us */
		lws_callback_on_writable(wsi); /* the latest frame right away */
		/* wlroots is not thread-safe: the frame request and the IDR are made on the main thread */
		struct touch_event ev = { .kind = 3 };
		if (write(s->touch_pipe[1], &ev, sizeof ev) != (ssize_t)sizeof ev)
			wlr_log(WLR_ERROR, "display client pipe write failed");
		break;
	}
	case LWS_CALLBACK_EVENT_WAIT_CANCELLED:
		lws_callback_on_writable_all_protocol(lws_get_context(wsi), lws_get_protocol(wsi));
		break;
	case LWS_CALLBACK_SERVER_WRITEABLE: {
		pthread_mutex_lock(&s->frame_lock);
		const uint64_t latest = s->frame_seq;
		if (latest == 0 || c->sent_seq >= latest) {
			pthread_mutex_unlock(&s->frame_lock);
			break;
		}
		uint64_t next = latest; /* JPEG: the newest frame */
		bool request_key = false;
		if (want_h264()) {
			const uint64_t oldest = latest >= FRAME_RING ? latest - FRAME_RING + 1 : 1;
			next = c->sent_seq + 1;
			if (c->need_key || next < oldest) {
				/* start (or restart after falling a whole ring behind) at the newest key frame */
				uint64_t k = 0;
				for (uint64_t q = latest; q >= oldest && q > c->sent_seq; q--) {
					const struct frame *f = &s->frames[q % FRAME_RING];
					if (f->seq == q && f->key) {
						k = q;
						break;
					}
				}
				if (!k) {
					request_key = !c->need_key; /* connect already forced one */
					c->need_key = true;
					c->sent_seq = latest;
					pthread_mutex_unlock(&s->frame_lock);
					if (request_key) {
						struct touch_event ev = { .kind = 3 }; /* main thread: an IDR now */
						if (write(s->touch_pipe[1], &ev, sizeof ev) != (ssize_t)sizeof ev)
							wlr_log(WLR_ERROR, "display client pipe write failed");
					}
					break;
				}
				next = k;
				c->need_key = false;
			}
		}
		const struct frame *f = &s->frames[next % FRAME_RING];
		size_t size = f->size;
		unsigned char *buf = malloc(LWS_PRE + size);
		memcpy(buf + LWS_PRE, f->data, size);
		c->sent_seq = next;
		const bool more = next < latest;
		pthread_mutex_unlock(&s->frame_lock);
		if (more)
			lws_callback_on_writable(wsi); /* H.264: the frames after it, in order */
		int n = lws_write(wsi, buf + LWS_PRE, size, LWS_WRITE_BINARY);
		free(buf);
		if (n < (int)size)
			return -1;
		break;
	}
	default:
		break;
	}
	return 0;
}

/* ---------------------------------------------------------------- the keyboard WebSocket (lws thread) */

struct ime_client {
	uint64_t sent_seq;
	bool sent;
	char line[IME_CMD_MAX];
	int len;
};

static void ime_queue_cmd(struct server *s, const char *cmd)
{
	pthread_mutex_lock(&s->ime_lock);
	if (s->ime_count < IME_QUEUE) {
		int idx = (s->ime_head + s->ime_count) % IME_QUEUE;
		snprintf(s->ime_queue[idx], IME_CMD_MAX, "%s", cmd);
		s->ime_count++;
	}
	pthread_mutex_unlock(&s->ime_lock);
	struct touch_event ev = { .kind = 4 };
	if (write(s->touch_pipe[1], &ev, sizeof ev) != (ssize_t)sizeof ev)
		wlr_log(WLR_ERROR, "keyboard pipe write failed");
}

static int cb_ime(struct lws *wsi, enum lws_callback_reasons reason, void *user, void *in, size_t len)
{
	struct ime_client *c = user;
	struct server *s = g_server;
	switch (reason) {
	case LWS_CALLBACK_ESTABLISHED:
		memset(c, 0, sizeof *c);
		lws_callback_on_writable(wsi); /* the current state right away */
		break;
	case LWS_CALLBACK_EVENT_WAIT_CANCELLED:
		lws_callback_on_writable_all_protocol(lws_get_context(wsi), lws_get_protocol(wsi));
		break;
	case LWS_CALLBACK_SERVER_WRITEABLE: {
		unsigned char buf[LWS_PRE + 256];
		pthread_mutex_lock(&s->ime_lock);
		uint64_t seq = s->ime_seq;
		const char *msg = s->ime_msg[0] ? s->ime_msg : "{\"type\":\"ime\",\"active\":false}";
		size_t n = strlen(msg);
		memcpy(buf + LWS_PRE, msg, n);
		pthread_mutex_unlock(&s->ime_lock);
		if (c->sent && seq == c->sent_seq)
			break;
		if (lws_write(wsi, buf + LWS_PRE, n, LWS_WRITE_TEXT) < (int)n)
			return -1;
		c->sent = true;
		c->sent_seq = seq;
		break;
	}
	case LWS_CALLBACK_RECEIVE:
		/* commands, one per line: "t TEXT" (UTF-8, no newline) or "k enter|backspace|tab|escape" */
		for (size_t i = 0; i < len; i++) {
			char ch = ((const char *)in)[i];
			if (ch == '\n') {
				c->line[c->len] = 0;
				if (c->len)
					ime_queue_cmd(s, c->line);
				c->len = 0;
			} else if (c->len < IME_CMD_MAX - 1) {
				c->line[c->len++] = ch;
			}
		}
		break;
	default:
		break;
	}
	return 0;
}

static const struct lws_protocols ime_protocols[] = {
	{ "keyboard", cb_ime, sizeof(struct ime_client), 4096, 0, NULL, 0 },
	{ NULL, NULL, 0, 0, 0, NULL, 0 },
};

static const struct lws_protocols display_protocols[] = {
	{ "display", cb_display, sizeof(struct display_client), 0, 0, NULL, 0 },
	{ NULL, NULL, 0, 0, 0, NULL, 0 },
};
static const struct lws_protocols touch_protocols[] = {
	{ "touch", cb_touch, sizeof(struct touch_parse), 4096, 0, NULL, 0 },
	{ NULL, NULL, 0, 0, 0, NULL, 0 },
};

/* the frontend expects a ping every second */
static const lws_retry_bo_t retry = { .secs_since_valid_ping = 1, .secs_since_valid_hangup = 10 };

static void *lws_main(void *arg)
{
	struct server *s = arg;
	while (!s->stop)
		lws_service(s->lws, 0);
	return NULL;
}

static bool start_websockets(struct server *s, int display_port, int touch_port, int ime_port, const char *bind)
{
	struct lws_context_creation_info info = { 0 };
	info.port = CONTEXT_PORT_NO_LISTEN;
	info.options = LWS_SERVER_OPTION_EXPLICIT_VHOSTS;
	info.gid = -1;
	info.uid = -1;
	s->lws = lws_create_context(&info);
	if (!s->lws)
		return false;
	struct lws_context_creation_info v1 = { 0 };
	v1.port = display_port;
	v1.protocols = display_protocols;
	v1.vhost_name = "display";
	v1.iface = bind;
	v1.retry_and_idle_policy = &retry;
	if (!lws_create_vhost(s->lws, &v1))
		return false;
	struct lws_context_creation_info v2 = { 0 };
	v2.port = touch_port;
	v2.protocols = touch_protocols;
	v2.vhost_name = "touch";
	v2.iface = bind;
	v2.retry_and_idle_policy = &retry;
	if (!lws_create_vhost(s->lws, &v2))
		return false;
	if (ime_port > 0) {
		struct lws_context_creation_info v3 = { 0 };
		v3.port = ime_port;
		v3.protocols = ime_protocols;
		v3.vhost_name = "keyboard";
		v3.iface = bind;
		v3.retry_and_idle_policy = &retry;
		if (!lws_create_vhost(s->lws, &v3))
			return false;
	}
	return pthread_create(&s->lws_thread, NULL, lws_main, s) == 0;
}

/* ---------------------------------------------------------------- live reconfiguration */

/* SIGHUP: the display state changed (teslalinux-apply-display wrote TACOMP_CONFIG, by default
 * /etc/teslalinux/display.env: TACOMP_SIZE=WxH@fps, TACOMP_ENCODER, TACOMP_QUALITY). Applied in place,
 * as upstream's configure_virtual_display resizes its virtual display: the output gets the new mode,
 * every window the new size (the app sees a configure and lays itself out again, a configuration
 * change, instead of a restart), and the encoders reopen for a new quality or encoder; H.264 clients
 * get an IDR of the new stream. */
static void tl_reconfigure(struct server *s)
{
	const char *path = getenv("TACOMP_CONFIG") ? getenv("TACOMP_CONFIG") : "/etc/teslalinux/display.env";
	FILE *f = fopen(path, "r");
	if (!f) {
		wlr_log(WLR_ERROR, "reconfigure: cannot read %s", path);
		return;
	}
	int w = s->width, h = s->height, fps = s->refresh_mhz / 1000, q = s->quality;
	char enc[32] = "";
	const char *cur_enc = getenv("TACOMP_ENCODER");
	snprintf(enc, sizeof enc, "%s", cur_enc ? cur_enc : "");
	char line[256];
	while (fgets(line, sizeof line, f)) {
		if (!strncmp(line, "TACOMP_SIZE=", 12))
			sscanf(line + 12, "%dx%d@%d", &w, &h, &fps);
		else if (!strncmp(line, "TACOMP_QUALITY=", 15))
			q = atoi(line + 15);
		else if (!strncmp(line, "TACOMP_ENCODER=", 15))
			sscanf(line + 15, "%31s", enc);
	}
	fclose(f);
	if (w < 64 || h < 64 || w > 4096 || h > 4096 || fps < 1 || fps > 240 || q < 1 || q > 100) {
		wlr_log(WLR_ERROR, "reconfigure: %s has out of range values, ignored", path);
		return;
	}
	const bool size_changed = w != s->width || h != s->height || fps * 1000 != s->refresh_mhz;
	const bool enc_changed = strcmp(enc, cur_enc ? cur_enc : "") != 0 || q != s->quality;
	if (!size_changed && !enc_changed)
		return;
	wlr_log(WLR_INFO, "reconfigure: %dx%d@%d -> %dx%d@%d, encoder %s q%d -> %s q%d", s->width, s->height,
	        s->refresh_mhz / 1000, w, h, fps, cur_enc ? cur_enc : "-", s->quality, enc[0] ? enc : "-", q);
	if (enc_changed) {
		if (enc[0])
			setenv("TACOMP_ENCODER", enc, 1);
		else
			unsetenv("TACOMP_ENCODER");
		s->quality = q;
		s->enc_reset = true; /* the encoder thread closes its encoders before the next frame */
	}
	if (size_changed && s->output) {
		s->width = w;
		s->height = h;
		s->refresh_mhz = fps * 1000;
		struct wlr_output_state state;
		wlr_output_state_init(&state);
		wlr_output_state_set_custom_mode(&state, w, h, s->refresh_mhz);
		if (!wlr_output_commit_state(s->output, &state))
			wlr_log(WLR_ERROR, "reconfigure: the output refused %dx%d", w, h);
		wlr_output_state_finish(&state);
		frame_clock_arm(s);
		struct toplevel *t;
		wl_list_for_each(t, &s->toplevels, link) {
			if (t->xdg_toplevel->base->initialized)
				wlr_xdg_toplevel_set_size(t->xdg_toplevel, w, h);
		}
	}
	/* the new stream starts at a key frame for every client */
	if (s->last_buffer) {
		wlr_buffer_unlock(s->last_buffer);
		s->last_buffer = NULL;
	}
	s->want_key = true;
	if (s->output)
		wlr_output_schedule_frame(s->output);
}

static int handle_sighup(int sig, void *data)
{
	tl_reconfigure(data);
	return 0;
}

/* ---------------------------------------------------------------- main */

static void on_signal(int sig)
{
	if (g_server)
		wl_display_terminate(g_server->display);
}

int main(int argc, char **argv)
{
	wlr_log_init(getenv("TACOMP_DEBUG") ? WLR_DEBUG : WLR_INFO, NULL);
	struct server s = { 0 };
	g_server = &s;
	s.width = 1088;
	s.height = 832;
	int fps = 60; /* the target: the output refresh drives the app's frame clock and the stream */
	const char *size = getenv("TACOMP_SIZE");
	if (size)
		sscanf(size, "%dx%d@%d", &s.width, &s.height, &fps);
	s.refresh_mhz = fps * 1000;
	s.frame_tfd = -1;
	s.quality = getenv("TACOMP_QUALITY") ? atoi(getenv("TACOMP_QUALITY")) : 70;
	int display_port = getenv("TACOMP_DISPLAY_PORT") ? atoi(getenv("TACOMP_DISPLAY_PORT")) : 9091;
	int touch_port = getenv("TACOMP_TOUCH_PORT") ? atoi(getenv("TACOMP_TOUCH_PORT")) : 9999;
	int ime_port = getenv("TACOMP_IME_PORT") ? atoi(getenv("TACOMP_IME_PORT")) : 9996;
	const char *socket_name = getenv("WAYLAND_DISPLAY") ? getenv("WAYLAND_DISPLAY") : "tesla-0";
	pthread_mutex_init(&s.frame_lock, NULL);
	s.tj = tjInitCompress();

	s.display = wl_display_create();
	s.loop = wl_display_get_event_loop(s.display);
	wl_list_init(&s.toplevels);
	/* SIGHUP: apply a new display state in place (tl_reconfigure) */
	s.hup_source = wl_event_loop_add_signal(s.loop, SIGHUP, handle_sighup, &s);
	s.backend = wlr_headless_backend_create(s.loop);
	if (!s.backend) {
		wlr_log(WLR_ERROR, "no headless backend");
		return 1;
	}
	s.renderer = wlr_renderer_autocreate(s.backend);
	if (!s.renderer) {
		wlr_log(WLR_ERROR, "no renderer");
		return 1;
	}
	wlr_renderer_init_wl_display(s.renderer, s.display);
	s.allocator = wlr_allocator_autocreate(s.backend, s.renderer);
	if (!s.allocator) {
		wlr_log(WLR_ERROR, "no allocator");
		return 1;
	}
	wlr_compositor_create(s.display, 6, s.renderer);
	wlr_subcompositor_create(s.display);
	wlr_data_device_manager_create(s.display);
	s.output_layout = wlr_output_layout_create(s.display);
	s.scene = wlr_scene_create();
	s.scene_layout = wlr_scene_attach_output_layout(s.scene, s.output_layout);
	s.new_output.notify = handle_new_output;
	wl_signal_add(&s.backend->events.new_output, &s.new_output);

	s.xdg_shell = wlr_xdg_shell_create(s.display, 6);
	s.new_toplevel.notify = handle_new_toplevel;
	wl_signal_add(&s.xdg_shell->events.new_toplevel, &s.new_toplevel);
	s.new_popup.notify = handle_new_popup;
	wl_signal_add(&s.xdg_shell->events.new_popup, &s.new_popup);

	/* a seat with touch (the car), plus a keyboard so toolkits get a keymap */
	s.seat = wlr_seat_create(s.display, "seat0");
	wlr_keyboard_init(&s.keyboard, NULL, "tacomp-keyboard");
	struct xkb_context *xkb = xkb_context_new(XKB_CONTEXT_NO_FLAGS);
	struct xkb_keymap *keymap = xkb_keymap_new_from_names(xkb, NULL, XKB_KEYMAP_COMPILE_NO_FLAGS);
	wlr_keyboard_set_keymap(&s.keyboard, keymap);
	xkb_keymap_unref(keymap);
	xkb_context_unref(xkb);
	wlr_seat_set_keyboard(s.seat, &s.keyboard);
	wlr_seat_set_capabilities(s.seat, WL_SEAT_CAPABILITY_TOUCH | WL_SEAT_CAPABILITY_KEYBOARD | WL_SEAT_CAPABILITY_POINTER);
	/* text-input-v3: GTK's input method context in the app tells us when a field wants a keyboard */
	pthread_mutex_init(&s.ime_lock, NULL);
	wl_list_init(&s.text_inputs);
	s.text_input_mgr = wlr_text_input_manager_v3_create(s.display);
	s.new_text_input.notify = handle_new_text_input;
	wl_signal_add(&s.text_input_mgr->events.new_text_input, &s.new_text_input);

	if (pipe2(s.touch_pipe, O_NONBLOCK | O_CLOEXEC) != 0) {
		wlr_log(WLR_ERROR, "pipe: %s", strerror(errno));
		return 1;
	}
	s.touch_source = wl_event_loop_add_fd(s.loop, s.touch_pipe[0], WL_EVENT_READABLE, handle_touch_pipe, &s);

	pthread_mutex_init(&s.enc_lock, NULL);
	pthread_cond_init(&s.enc_cond, NULL);
	s.enc_efd = eventfd(0, EFD_NONBLOCK | EFD_CLOEXEC);
	if (s.enc_efd < 0 || pthread_create(&s.enc_thread, NULL, encoder_main, &s) != 0) {
		wlr_log(WLR_ERROR, "encoder thread: %s", strerror(errno));
		return 1;
	}
	wl_event_loop_add_fd(s.loop, s.enc_efd, WL_EVENT_READABLE, handle_enc_done, &s);

	if (wl_display_add_socket(s.display, socket_name) != 0) {
		wlr_log(WLR_ERROR, "cannot create the socket %s", socket_name);
		return 1;
	}
	if (!wlr_backend_start(s.backend)) {
		wlr_log(WLR_ERROR, "backend start failed");
		return 1;
	}
	wlr_headless_add_output(s.backend, s.width, s.height);
	const char *bind = getenv("TACOMP_BIND");
	if (bind && !*bind)
		bind = NULL;
	if (!start_websockets(&s, display_port, touch_port, ime_port, bind)) {
		wlr_log(WLR_ERROR, "websocket servers failed (ports %d, %d, bind %s)", display_port, touch_port, bind ? bind : "*");
		return 1;
	}
	setenv("WAYLAND_DISPLAY", socket_name, true);
	signal(SIGINT, on_signal);
	signal(SIGTERM, on_signal);
	wlr_log(WLR_INFO, "tacomp on %s: display ws :%d, touch ws :%d, %dx%d@%d", socket_name, display_port, touch_port, s.width, s.height, fps);
	wl_display_run(s.display);

	s.stop = true;
	pthread_mutex_lock(&s.enc_lock);
	pthread_cond_signal(&s.enc_cond);
	pthread_mutex_unlock(&s.enc_lock);
	pthread_join(s.enc_thread, NULL);
	if (s.enc_pending) {
		s.enc_pending->next = s.enc_done;
		s.enc_done = s.enc_pending;
		s.enc_pending = NULL;
	}
	enc_release_done(&s);
	lws_cancel_service(s.lws);
	pthread_join(s.lws_thread, NULL);
	lws_context_destroy(s.lws);
	if (s.last_buffer)
		wlr_buffer_unlock(s.last_buffer);
	wl_display_destroy_clients(s.display);
	wl_display_destroy(s.display);
	tjDestroy(s.tj);
	return 0;
}
