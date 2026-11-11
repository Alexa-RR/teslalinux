/* teslalinux: standalone check of atl_path_op (cc test_pathops.c atl_pathops.c $(pkg-config --cflags --libs gtk4) -lm) */
#include <stdio.h>
#include <gsk/gsk.h>

GskPath *atl_path_op(GskPath *path1, GskPath *path2, int op);

static void show(const char *what, GskPath *p)
{
	graphene_rect_t b;
	gboolean has = gsk_path_get_bounds(p, &b);
	char *s = gsk_path_to_string(p);
	printf("%s: empty=%d bounds=%s %.3f,%.3f %.3fx%.3f  %s%c", what, gsk_path_is_empty(p), has ? "" : "(none)", b.origin.x, b.origin.y, b.size.width, b.size.height, s, 10);
	g_free(s);
}

int main(void)
{
	GskPathBuilder *b = gsk_path_builder_new();
	gsk_path_builder_add_rounded_rect(b, &(GskRoundedRect){ .bounds = GRAPHENE_RECT_INIT(0, 0, 223, 37), .corner = { {18, 18}, {18, 18}, {18, 18}, {18, 18} } });
	GskPath *round = gsk_path_builder_free_to_path(b);

	b = gsk_path_builder_new();
	gsk_path_builder_add_rect(b, &GRAPHENE_RECT_INIT(110.995f, 17.995f, 0.01f, 0.01f));
	GskPath *tiny = gsk_path_builder_free_to_path(b);

	b = gsk_path_builder_new();
	gsk_path_builder_add_rect(b, &GRAPHENE_RECT_INIT(-5, 17, 0.01f, 0.01f));
	GskPath *outside = gsk_path_builder_free_to_path(b);

	b = gsk_path_builder_new();
	gsk_path_builder_add_rect(b, &GRAPHENE_RECT_INIT(100, 10, 200, 100));
	GskPath *big = gsk_path_builder_free_to_path(b);

	show("tiny inside, INTERSECT", atl_path_op(round, tiny, 1));
	show("tiny outside, INTERSECT", atl_path_op(round, outside, 1));
	show("big overlap, INTERSECT", atl_path_op(round, big, 1));
	show("big overlap, UNION", atl_path_op(round, big, 2));
	show("big overlap, DIFFERENCE", atl_path_op(round, big, 0));
	show("big overlap, XOR", atl_path_op(round, big, 3));
	return 0;
}
