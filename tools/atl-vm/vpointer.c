/*
 * vpointer: a persistent virtual pointer for driving a wlroots compositor (cage) in the test VM.
 *
 * wlrctl creates a virtual pointer device per invocation and destroys it on exit; the seat's pointer
 * capability flips with it, GTK4 then re-creates its wl_pointer and the button events of a quick
 * "click" are lost (seen as wl_pointer enter/leave with no button in WAYLAND_DEBUG). This program
 * keeps one device alive and takes commands on stdin (one per line):
 *
 *   move X Y        absolute position in output pixels (W H from `output`, default 1280x720)
 *   output W H      extent used by move
 *   down | up       left button press / release
 *   click [X Y]     move (optional), press, release with a short hold
 *   sleep MS
 *   quit
 *
 * Build: wayland-scanner private-code wlr-virtual-pointer-unstable-v1.xml > vp-proto.c
 *        wayland-scanner client-header wlr-virtual-pointer-unstable-v1.xml > vp-proto.h
 *        cc -O2 -o vpointer vpointer.c vp-proto.c -lwayland-client
 * Run:   mkfifo ~/vpointer.fifo; (tail -f ~/vpointer.fifo | ./vpointer &) ; echo "click 640 530" > ~/vpointer.fifo
 */
#include <linux/input-event-codes.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <wayland-client.h>
#include "vp-proto.h"

static struct wl_seat *seat;
static struct zwlr_virtual_pointer_manager_v1 *manager;
static struct zwlr_virtual_pointer_v1 *pointer;
static unsigned extent_w = 1280, extent_h = 720;

static uint32_t now_ms(void)
{
	struct timespec ts;
	clock_gettime(CLOCK_MONOTONIC, &ts);
	return (uint32_t)(ts.tv_sec * 1000 + ts.tv_nsec / 1000000);
}

static void registry_global(void *data, struct wl_registry *registry, uint32_t name, const char *interface, uint32_t version)
{
	if (strcmp(interface, wl_seat_interface.name) == 0 && !seat)
		seat = wl_registry_bind(registry, name, &wl_seat_interface, 1);
	else if (strcmp(interface, zwlr_virtual_pointer_manager_v1_interface.name) == 0)
		manager = wl_registry_bind(registry, name, &zwlr_virtual_pointer_manager_v1_interface, 1);
}

static void registry_global_remove(void *data, struct wl_registry *registry, uint32_t name) {}

static const struct wl_registry_listener registry_listener = { registry_global, registry_global_remove };

static void move_to(struct wl_display *display, unsigned x, unsigned y)
{
	zwlr_virtual_pointer_v1_motion_absolute(pointer, now_ms(), x, y, extent_w, extent_h);
	zwlr_virtual_pointer_v1_frame(pointer);
	wl_display_flush(display);
}

static void button(struct wl_display *display, int pressed)
{
	zwlr_virtual_pointer_v1_button(pointer, now_ms(), BTN_LEFT, pressed ? WL_POINTER_BUTTON_STATE_PRESSED : WL_POINTER_BUTTON_STATE_RELEASED);
	zwlr_virtual_pointer_v1_frame(pointer);
	wl_display_flush(display);
}

static void msleep(int ms)
{
	struct timespec ts = { ms / 1000, (ms % 1000) * 1000000L };
	nanosleep(&ts, NULL);
}

int main(int argc, char **argv)
{
	struct wl_display *display = wl_display_connect(NULL);
	if (!display) {
		fprintf(stderr, "vpointer: cannot connect to the Wayland display\n");
		return 1;
	}
	struct wl_registry *registry = wl_display_get_registry(display);
	wl_registry_add_listener(registry, &registry_listener, NULL);
	wl_display_roundtrip(display);
	if (!manager || !seat) {
		fprintf(stderr, "vpointer: compositor lacks zwlr_virtual_pointer_manager_v1 or a seat\n");
		return 1;
	}
	pointer = zwlr_virtual_pointer_manager_v1_create_virtual_pointer(manager, seat);
	wl_display_roundtrip(display);
	fprintf(stderr, "vpointer: ready\n");

	char line[256];
	while (fgets(line, sizeof line, stdin)) {
		unsigned x, y;
		int ms;
		if (sscanf(line, "move %u %u", &x, &y) == 2) {
			move_to(display, x, y);
		} else if (sscanf(line, "output %u %u", &x, &y) == 2) {
			extent_w = x;
			extent_h = y;
		} else if (sscanf(line, "click %u %u", &x, &y) == 2) {
			move_to(display, x, y);
			msleep(60);
			button(display, 1);
			msleep(80);
			button(display, 0);
		} else if (strncmp(line, "click", 5) == 0) {
			button(display, 1);
			msleep(80);
			button(display, 0);
		} else if (strncmp(line, "down", 4) == 0) {
			button(display, 1);
		} else if (strncmp(line, "up", 2) == 0) {
			button(display, 0);
		} else if (sscanf(line, "sleep %d", &ms) == 1) {
			msleep(ms);
		} else if (strncmp(line, "quit", 4) == 0) {
			break;
		} else if (line[0] != '\n' && line[0] != '#') {
			fprintf(stderr, "vpointer: unknown command: %s", line);
		}
		wl_display_dispatch_pending(display);
	}
	zwlr_virtual_pointer_v1_destroy(pointer);
	wl_display_disconnect(display);
	return 0;
}
