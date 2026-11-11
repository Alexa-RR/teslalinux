/*
 * teslalinux: boolean operations on paths for android.graphics.Path.op (Skia's PathOps on Android).
 *
 * Both paths are flattened to polygons (GskPath, line segments at 0.25 px tolerance) and combined
 * with the Martinez-Rueda-Feito sweep-line algorithm (F. Martinez, A. Rueda, F. Feito, "A new
 * algorithm for computing Boolean operations on polygons", Computers & Geosciences 35 (2009)), the
 * same algorithm the martinez JavaScript library implements, including its handling of
 * overlapping edges. The result contours are emitted into a GskPathBuilder (nonzero winding).
 *
 * Operations: 0 DIFFERENCE, 1 INTERSECT, 2 UNION, 3 XOR, 4 REVERSE_DIFFERENCE (Path.Op ordinals).
 */
#include <math.h>
#include <stdio.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <gsk/gsk.h>

#include "../defines.h"

enum { OP_DIFFERENCE = 0, OP_INTERSECTION = 1, OP_UNION = 2, OP_XOR = 3, OP_REVERSE_DIFFERENCE = 4 };
enum { EDGE_NORMAL = 0, EDGE_NON_CONTRIBUTING = 1, EDGE_SAME_TRANSITION = 2, EDGE_DIFFERENT_TRANSITION = 3 };

typedef struct SweepEvent SweepEvent;
struct SweepEvent {
	double x, y;
	bool left;
	bool is_subject;
	SweepEvent *other;      /* the other end of the segment */
	int type;
	bool in_out;            /* inside/outside transition of its own polygon */
	bool other_in_out;      /* ... of the other polygon */
	SweepEvent *prev_in_result;
	bool in_result;
	int result_transition;  /* +1 entering the result, -1 leaving, 0 not in result */
	int pos;                /* index in the sorted result event list */
	bool processed;         /* connectEdges bookkeeping */
	int contour_id;
	bool is_exterior_ring;
};

/* ---- growable arrays ------------------------------------------------------------------------- */

typedef struct {
	SweepEvent **items;
	int len, cap;
} EventList;

static void list_push(EventList *l, SweepEvent *e)
{
	if (l->len == l->cap) {
		l->cap = l->cap ? l->cap * 2 : 64;
		l->items = realloc(l->items, sizeof(SweepEvent *) * l->cap);
	}
	l->items[l->len++] = e;
}

/* all events ever allocated, freed at the end */
static EventList all_events;

static SweepEvent *new_event(double x, double y, bool left, SweepEvent *other, bool is_subject, int type)
{
	SweepEvent *e = calloc(1, sizeof(SweepEvent));
	e->x = x;
	e->y = y;
	e->left = left;
	e->other = other;
	e->is_subject = is_subject;
	e->type = type;
	e->contour_id = -1;
	list_push(&all_events, e);
	return e;
}

/* ---- geometry -------------------------------------------------------------------------------- */

static double signed_area(double ax, double ay, double bx, double by, double cx, double cy)
{
	return (ax - cx) * (by - cy) - (bx - cx) * (ay - cy);
}

static bool is_vertical(const SweepEvent *e)
{
	return e->x == e->other->x;
}

/* whether the segment of event e lies below the point p (reference: SweepEvent.isBelow) */
static bool is_below(const SweepEvent *e, double px, double py)
{
	if (e->left)
		return signed_area(e->x, e->y, e->other->x, e->other->y, px, py) > 0;
	return signed_area(e->other->x, e->other->y, e->x, e->y, px, py) > 0;
}

static bool is_above(const SweepEvent *e, double px, double py)
{
	return !is_below(e, px, py);
}

/* event queue order: smaller x first, then smaller y, then right endpoints before left ones, then
 * by slope so that the lower segment comes first, subject before clipping */
static int compare_events(const SweepEvent *e1, const SweepEvent *e2)
{
	if (e1->x > e2->x) return 1;
	if (e1->x < e2->x) return -1;
	if (e1->y != e2->y) return e1->y > e2->y ? 1 : -1;
	if (e1->left != e2->left) return e1->left ? 1 : -1;
	if (signed_area(e1->x, e1->y, e1->other->x, e1->other->y, e2->other->x, e2->other->y) != 0)
		return !is_below(e1, e2->other->x, e2->other->y) ? 1 : -1;
	/* collinear: subject first */
	return (!e1->is_subject && e2->is_subject) ? 1 : -1;
}

/* sweep line (status) order: by the segments' vertical position at the sweep */
static int compare_segments(const SweepEvent *le1, const SweepEvent *le2)
{
	if (le1 == le2)
		return 0;
	if (signed_area(le1->x, le1->y, le1->other->x, le1->other->y, le2->x, le2->y) != 0 ||
	    signed_area(le1->x, le1->y, le1->other->x, le1->other->y, le2->other->x, le2->other->y) != 0) {
		/* segments are not collinear: if they share the left endpoint use the right one to sort */
		if (le1->x == le2->x && le1->y == le2->y)
			return is_below(le1, le2->other->x, le2->other->y) ? -1 : 1;
		/* different left endpoint: use the left endpoint to sort */
		if (le1->x == le2->x)
			return le1->y < le2->y ? -1 : 1;
		if (compare_events(le1, le2) == 1)
			return is_above(le2, le1->x, le1->y) ? -1 : 1;
		return is_below(le1, le2->x, le2->y) ? -1 : 1;
	}
	/* collinear */
	if (le1->is_subject == le2->is_subject) {
		if (le1->x == le2->x && le1->y == le2->y) {
			if (le1->other->x == le2->other->x && le1->other->y == le2->other->y)
				return 0;
			return le1->contour_id > le2->contour_id ? 1 : -1;
		}
		return compare_events(le1, le2) == 1 ? 1 : -1;
	}
	return le1->is_subject ? -1 : 1;
}

/* ---- binary heap event queue ------------------------------------------------------------------ */

typedef struct {
	SweepEvent **a;
	int len, cap;
} Heap;

static void heap_push(Heap *h, SweepEvent *e)
{
	if (h->len == h->cap) {
		h->cap = h->cap ? h->cap * 2 : 256;
		h->a = realloc(h->a, sizeof(SweepEvent *) * h->cap);
	}
	int i = h->len++;
	h->a[i] = e;
	while (i > 0) {
		int p = (i - 1) / 2;
		if (compare_events(h->a[i], h->a[p]) >= 0)
			break;
		SweepEvent *t = h->a[i]; h->a[i] = h->a[p]; h->a[p] = t;
		i = p;
	}
}

static SweepEvent *heap_pop(Heap *h)
{
	SweepEvent *top = h->a[0];
	h->a[0] = h->a[--h->len];
	int i = 0;
	for (;;) {
		int l = 2 * i + 1, r = l + 1, m = i;
		if (l < h->len && compare_events(h->a[l], h->a[m]) < 0) m = l;
		if (r < h->len && compare_events(h->a[r], h->a[m]) < 0) m = r;
		if (m == i)
			break;
		SweepEvent *t = h->a[i]; h->a[i] = h->a[m]; h->a[m] = t;
		i = m;
	}
	return top;
}

/* ---- sweep line status: ordered array ------------------------------------------------------- */

typedef struct {
	SweepEvent **a;
	int len, cap;
} Status;

static int status_insert(Status *s, SweepEvent *e)
{
	if (s->len == s->cap) {
		s->cap = s->cap ? s->cap * 2 : 64;
		s->a = realloc(s->a, sizeof(SweepEvent *) * s->cap);
	}
	int i = 0;
	while (i < s->len && compare_segments(s->a[i], e) < 0)
		i++;
	memmove(&s->a[i + 1], &s->a[i], sizeof(SweepEvent *) * (s->len - i));
	s->a[i] = e;
	s->len++;
	return i;
}

static int status_find(Status *s, SweepEvent *e)
{
	for (int i = 0; i < s->len; i++)
		if (s->a[i] == e)
			return i;
	return -1;
}

static void status_remove_at(Status *s, int i)
{
	memmove(&s->a[i], &s->a[i + 1], sizeof(SweepEvent *) * (s->len - i - 1));
	s->len--;
}

/* ---- polygon input --------------------------------------------------------------------------- */

typedef struct {
	double *pts; /* x0 y0 x1 y1 ... */
	int n, cap;
} Contour;

typedef struct {
	Contour *c;
	int n, cap;
	double minx, miny, maxx, maxy;
} Polygon;

static void contour_add(Contour *c, double x, double y)
{
	if (c->n == c->cap) {
		c->cap = c->cap ? c->cap * 2 : 32;
		c->pts = realloc(c->pts, sizeof(double) * 2 * c->cap);
	}
	c->pts[2 * c->n] = x;
	c->pts[2 * c->n + 1] = y;
	c->n++;
}

static Contour *polygon_new_contour(Polygon *p)
{
	if (p->n == p->cap) {
		p->cap = p->cap ? p->cap * 2 : 8;
		p->c = realloc(p->c, sizeof(Contour) * p->cap);
	}
	Contour *c = &p->c[p->n++];
	memset(c, 0, sizeof(*c));
	return c;
}

static gboolean flatten_cb(GskPathOperation op, const graphene_point_t *pts, gsize n_pts, float weight, gpointer user_data)
{
	Polygon *poly = user_data;
	Contour *c = poly->n ? &poly->c[poly->n - 1] : NULL;
	switch (op) {
	case GSK_PATH_MOVE:
		c = polygon_new_contour(poly);
		contour_add(c, pts[0].x, pts[0].y);
		break;
	case GSK_PATH_LINE:
		if (!c)
			c = polygon_new_contour(poly);
		contour_add(c, pts[1].x, pts[1].y);
		break;
	case GSK_PATH_CLOSE:
		break; /* contours are implicitly closed */
	default:
		break; /* curves were flattened to lines by the foreach flags */
	}
	return TRUE;
}

static void polygon_from_path(GskPath *path, Polygon *poly)
{
	memset(poly, 0, sizeof(*poly));
	gsk_path_foreach(path, GSK_PATH_FOREACH_ALLOW_ONLY_LINES, flatten_cb, poly);
	poly->minx = poly->miny = INFINITY;
	poly->maxx = poly->maxy = -INFINITY;
	for (int i = 0; i < poly->n; i++) {
		Contour *c = &poly->c[i];
		/* drop a duplicated closing point */
		if (c->n > 1 && c->pts[0] == c->pts[2 * (c->n - 1)] && c->pts[1] == c->pts[2 * (c->n - 1) + 1])
			c->n--;
		for (int k = 0; k < c->n; k++) {
			double x = c->pts[2 * k], y = c->pts[2 * k + 1];
			if (x < poly->minx) poly->minx = x;
			if (y < poly->miny) poly->miny = y;
			if (x > poly->maxx) poly->maxx = x;
			if (y > poly->maxy) poly->maxy = y;
		}
	}
}

static void polygon_free(Polygon *p)
{
	for (int i = 0; i < p->n; i++)
		free(p->c[i].pts);
	free(p->c);
}

/* ---- the algorithm ---------------------------------------------------------------------------- */

static void process_polygon(Polygon *poly, bool is_subject, Heap *queue, int *contour_counter)
{
	for (int i = 0; i < poly->n; i++) {
		Contour *c = &poly->c[i];
		if (c->n < 3)
			continue;
		int cid = (*contour_counter)++;
		for (int k = 0; k < c->n; k++) {
			int k2 = (k + 1) % c->n;
			double x1 = c->pts[2 * k], y1 = c->pts[2 * k + 1], x2 = c->pts[2 * k2], y2 = c->pts[2 * k2 + 1];
			if (x1 == x2 && y1 == y2)
				continue; /* skip degenerate edges */
			SweepEvent *e1 = new_event(x1, y1, false, NULL, is_subject, EDGE_NORMAL);
			SweepEvent *e2 = new_event(x2, y2, false, e1, is_subject, EDGE_NORMAL);
			e1->other = e2;
			e1->contour_id = e2->contour_id = cid;
			if (compare_events(e1, e2) < 0)
				e1->left = true;
			else
				e2->left = true;
			heap_push(queue, e1);
			heap_push(queue, e2);
		}
	}
}

static bool in_result(const SweepEvent *e, int op)
{
	switch (e->type) {
	case EDGE_NORMAL:
		switch (op) {
		case OP_INTERSECTION: return !e->other_in_out;
		case OP_UNION: return e->other_in_out;
		case OP_DIFFERENCE: return (e->is_subject && e->other_in_out) || (!e->is_subject && !e->other_in_out);
		case OP_XOR: return true;
		}
		break;
	case EDGE_SAME_TRANSITION:
		return op == OP_INTERSECTION || op == OP_UNION;
	case EDGE_DIFFERENT_TRANSITION:
		return op == OP_DIFFERENCE;
	case EDGE_NON_CONTRIBUTING:
		return false;
	}
	return false;
}

static void compute_fields(SweepEvent *e, SweepEvent *prev, int op)
{
	if (prev == NULL) {
		e->in_out = false;
		e->other_in_out = true;
	} else if (e->is_subject == prev->is_subject) {
		e->in_out = !prev->in_out;
		e->other_in_out = prev->other_in_out;
	} else {
		e->in_out = !prev->other_in_out;
		e->other_in_out = is_vertical(prev) ? !prev->in_out : prev->in_out;
	}
	if (prev)
		e->prev_in_result = (!in_result(prev, op) || is_vertical(prev)) ? prev->prev_in_result : prev;
	bool ir = in_result(e, op);
	e->in_result = ir;
#ifdef ATL_PATHOPS_DEBUG
	fprintf(stderr, "  event (%.3f,%.3f)-(%.3f,%.3f) %s type=%d prev=%s in_out=%d other_in_out=%d in_result=%d%c", e->x, e->y, e->other->x, e->other->y,
	        e->is_subject ? "subj" : "clip", e->type, prev ? "yes" : "none", e->in_out, e->other_in_out, ir, 10);
#endif
	if (ir)
		e->result_transition = (e->type == EDGE_NORMAL) ? ((op == OP_INTERSECTION) ? (!e->other_in_out ? 1 : 0) : 0) : 0;
}

static void divide_segment(SweepEvent *se, double px, double py, Heap *queue)
{
	SweepEvent *r = new_event(px, py, false, se, se->is_subject, EDGE_NORMAL);
	SweepEvent *l = new_event(px, py, true, se->other, se->is_subject, EDGE_NORMAL);
	r->contour_id = l->contour_id = se->contour_id;
	/* avoid a rounding error: the left event would be processed after the right one */
	if (compare_events(l, se->other) > 0) {
		se->other->left = true;
		l->left = false;
	}
	se->other->other = l;
	se->other = r;
	heap_push(queue, l);
	heap_push(queue, r);
}

/* intersection of segments (a1,a2) and (b1,b2); returns the number of points (0, 1 or 2) */
static int segment_intersection(double a1x, double a1y, double a2x, double a2y, double b1x, double b1y, double b2x, double b2y,
                                bool no_endpoint_touch, double out[4])
{
	double vax = a2x - a1x, vay = a2y - a1y, vbx = b2x - b1x, vby = b2y - b1y;
	double ex = b1x - a1x, ey = b1y - a1y;
	double kross = vax * vby - vay * vbx;
	double sqr_kross = kross * kross;
	double sqr_len_a = vax * vax + vay * vay;
	const double EPS = 1e-9;
	if (sqr_kross > 0) {
		double s = (ex * vby - ey * vbx) / kross;
		if (s < 0 || s > 1)
			return 0;
		double t = (ex * vay - ey * vax) / kross;
		if (t < 0 || t > 1)
			return 0;
		if (s == 0 || s == 1) {
			if (no_endpoint_touch)
				return 0;
		}
		if (t == 0 || t == 1) {
			if (no_endpoint_touch)
				return 0;
		}
		out[0] = a1x + s * vax;
		out[1] = a1y + s * vay;
		return 1;
	}
	/* parallel */
	double sqr_len_e = ex * ex + ey * ey;
	double kross2 = ex * vay - ey * vax;
	if (kross2 * kross2 > EPS * sqr_len_a * sqr_len_e)
		return 0; /* parallel, not collinear */
	/* collinear: find overlap */
	double sa = (vax * ex + vay * ey) / sqr_len_a;
	double sb = sa + (vax * vbx + vay * vby) / sqr_len_a;
	double smin = fmin(sa, sb), smax = fmax(sa, sb);
	if (smin > 1 || smax < 0)
		return 0;
	if (smin == 1) {
		if (no_endpoint_touch) return 0;
		out[0] = a1x + vax; out[1] = a1y + vay;
		return 1;
	}
	if (smax == 0) {
		if (no_endpoint_touch) return 0;
		out[0] = a1x; out[1] = a1y;
		return 1;
	}
	if (no_endpoint_touch && smin == 0 && smax == 1)
		return 0;
	double lo = smin > 0 ? smin : 0, hi = smax < 1 ? smax : 1;
	if (lo == hi) {
		out[0] = a1x + lo * vax; out[1] = a1y + lo * vay;
		return 1;
	}
	out[0] = a1x + lo * vax; out[1] = a1y + lo * vay;
	out[2] = a1x + hi * vax; out[3] = a1y + hi * vay;
	return 2;
}

static bool same_point(const SweepEvent *a, const SweepEvent *b)
{
	return a->x == b->x && a->y == b->y;
}

/* returns 2 on an error that invalidates the result, otherwise the number of intersections found */
static int possible_intersection(SweepEvent *se1, SweepEvent *se2, Heap *queue)
{
	double inter[4];
	int n = segment_intersection(se1->x, se1->y, se1->other->x, se1->other->y, se2->x, se2->y, se2->other->x, se2->other->y, false, inter);
	if (n == 0)
		return 0;
	if (n == 1 && (same_point(se1, se2) || same_point(se1->other, se2->other)))
		return 0; /* segments touch at an endpoint */
	if (n == 2 && se1->is_subject == se2->is_subject)
		return 0; /* overlapping edges of the same polygon: ignore */

	if (n == 1) {
		if (!(se1->x == inter[0] && se1->y == inter[1]) && !(se1->other->x == inter[0] && se1->other->y == inter[1]))
			divide_segment(se1, inter[0], inter[1], queue);
		if (!(se2->x == inter[0] && se2->y == inter[1]) && !(se2->other->x == inter[0] && se2->other->y == inter[1]))
			divide_segment(se2, inter[0], inter[1], queue);
		return 1;
	}

	/* overlapping segments */
	SweepEvent *events[4];
	int ne = 0;
	bool left_coincide = false, right_coincide = false;
	if (same_point(se1, se2)) {
		left_coincide = true;
	} else if (compare_events(se1, se2) == 1) {
		events[ne++] = se2; events[ne++] = se1;
	} else {
		events[ne++] = se1; events[ne++] = se2;
	}
	if (same_point(se1->other, se2->other)) {
		right_coincide = true;
	} else if (compare_events(se1->other, se2->other) == 1) {
		events[ne++] = se2->other; events[ne++] = se1->other;
	} else {
		events[ne++] = se1->other; events[ne++] = se2->other;
	}

	if ((left_coincide && right_coincide) || left_coincide) {
		/* both segments equal or share the left endpoint */
		se2->type = EDGE_NON_CONTRIBUTING;
		se1->type = (se2->in_out == se1->in_out) ? EDGE_SAME_TRANSITION : EDGE_DIFFERENT_TRANSITION;
		if (left_coincide && !right_coincide)
			divide_segment(events[1]->other, events[0]->x, events[0]->y, queue);
		return 2;
	}
	if (right_coincide) {
		/* the segments share the right endpoint */
		divide_segment(events[0], events[1]->x, events[1]->y, queue);
		return 3;
	}
	if (events[0] != events[3]->other) {
		/* no line segment includes totally the other one */
		divide_segment(events[0], events[1]->x, events[1]->y, queue);
		divide_segment(events[1], events[2]->x, events[2]->y, queue);
		return 3;
	}
	/* one segment includes the other one */
	divide_segment(events[0], events[1]->x, events[1]->y, queue);
	divide_segment(events[3]->other, events[2]->x, events[2]->y, queue);
	return 3;
}

static int cmp_result_events(const void *a, const void *b)
{
	/* the same order the sweep used */
	return compare_events(*(SweepEvent *const *)a, *(SweepEvent *const *)b);
}

/* next unprocessed event at the same position, walking in both directions */
static int next_pos(int pos, SweepEvent **events, int n, const bool *processed, int orig_pos)
{
	int new_pos = pos + 1;
	double px = events[pos]->x, py = events[pos]->y;
	while (new_pos < n && events[new_pos]->x == px && events[new_pos]->y == py) {
		if (!processed[new_pos])
			return new_pos;
		new_pos++;
	}
	new_pos = pos - 1;
	while (new_pos > orig_pos && processed[new_pos])
		new_pos--;
	return new_pos;
}

/* ---- result: subdivide (sweep) and connect edges ----------------------------------------------- */

static void subdivide(Heap *queue, Polygon *subject, Polygon *clipping, int op, EventList *sorted)
{
	Status status = {0};
	double rightbound = fmin(subject->maxx, clipping->maxx);
	while (queue->len) {
		SweepEvent *e = heap_pop(queue);
		list_push(sorted, e);
		/* optimisation: skip events beyond the relevant bound */
		if ((op == OP_INTERSECTION && e->x > rightbound) || (op == OP_DIFFERENCE && e->x > subject->maxx))
			break;
		if (e->left) {
			int i = status_insert(&status, e);
			SweepEvent *prev = i > 0 ? status.a[i - 1] : NULL;
			SweepEvent *next = i + 1 < status.len ? status.a[i + 1] : NULL;
			compute_fields(e, prev, op);
			if (next) {
				if (possible_intersection(e, next, queue) == 2) {
					compute_fields(e, prev, op);
					compute_fields(next, e, op);
				}
			}
			if (prev) {
				if (possible_intersection(prev, e, queue) == 2) {
					SweepEvent *prevprev = i > 1 ? status.a[i - 2] : NULL;
					compute_fields(prev, prevprev, op);
					compute_fields(e, prev, op);
				}
			}
		} else {
			e = e->other;
			int i = status_find(&status, e);
			if (i >= 0) {
				SweepEvent *prev = i > 0 ? status.a[i - 1] : NULL;
				SweepEvent *next = i + 1 < status.len ? status.a[i + 1] : NULL;
				status_remove_at(&status, i);
				if (next && prev)
					possible_intersection(prev, next, queue);
			}
		}
	}
	free(status.a);
}

static void connect_edges(EventList *sorted, GskPathBuilder *out)
{
	/* result events in order, with their index stored in pos */
	EventList res = {0};
	for (int i = 0; i < sorted->len; i++) {
		SweepEvent *e = sorted->items[i];
		if ((e->left && e->in_result) || (!e->left && e->other->in_result))
			list_push(&res, e);
	}
	bool sorted_ok = false;
	while (!sorted_ok) {
		sorted_ok = true;
		for (int i = 0; i < res.len; i++) {
			if (i + 1 < res.len && cmp_result_events(&res.items[i], &res.items[i + 1]) == 1) {
				SweepEvent *t = res.items[i]; res.items[i] = res.items[i + 1]; res.items[i + 1] = t;
				sorted_ok = false;
			}
		}
	}
	for (int i = 0; i < res.len; i++)
		res.items[i]->pos = i;
	/* the right event of a pair must point to the position of the left one and vice versa */
	for (int i = 0; i < res.len; i++) {
		SweepEvent *e = res.items[i];
		if (!e->left) {
			int t = e->pos; e->pos = e->other->pos; e->other->pos = t;
		}
	}

	bool *processed = calloc(res.len ? res.len : 1, sizeof(bool));
	for (int i = 0; i < res.len; i++) {
		if (processed[i])
			continue;
		SweepEvent *start = res.items[i];
		int pos = i;
		double sx = start->x, sy = start->y;
		gsk_path_builder_move_to(out, (float)sx, (float)sy);
		int guard = 0;
		for (;;) {
			processed[pos] = true;
			pos = res.items[pos]->pos;
			if (pos < 0 || pos >= res.len)
				break;
			processed[pos] = true;
			SweepEvent *e = res.items[pos];
			if (!(e->x == sx && e->y == sy))
				gsk_path_builder_line_to(out, (float)e->x, (float)e->y);
			pos = next_pos(pos, res.items, res.len, processed, i);
			if (pos == i || pos < 0 || guard++ > res.len * 2)
				break;
		}
		gsk_path_builder_close(out);
	}
	free(processed);
	free(res.items);
}

/* ---- entry point ------------------------------------------------------------------------------- */

GskPath *atl_path_op(GskPath *path1, GskPath *path2, int op)
{
	Polygon subject, clipping;
	GskPathBuilder *out = gsk_path_builder_new();
	if (op == OP_REVERSE_DIFFERENCE) {
		polygon_from_path(path2, &subject);
		polygon_from_path(path1, &clipping);
		op = OP_DIFFERENCE;
	} else {
		polygon_from_path(path1, &subject);
		polygon_from_path(path2, &clipping);
	}

	bool subject_empty = subject.n == 0, clipping_empty = clipping.n == 0;
	bool disjoint = subject.minx > clipping.maxx || clipping.minx > subject.maxx || subject.miny > clipping.maxy || clipping.miny > subject.maxy;
	if (subject_empty || clipping_empty || disjoint) {
		/* trivial cases, as in the reference implementation: an intersection is empty, a difference
		 * is the subject, a union or xor is both polygons */
		Polygon *keep[2] = { NULL, NULL };
		int nk = 0;
		if (op == OP_DIFFERENCE) {
			keep[nk++] = &subject;
		} else if (op == OP_UNION || op == OP_XOR) {
			keep[nk++] = &subject;
			keep[nk++] = &clipping;
		}
		for (int k = 0; k < nk; k++) {
			for (int i = 0; i < keep[k]->n; i++) {
				Contour *c = &keep[k]->c[i];
				if (c->n < 3)
					continue;
				gsk_path_builder_move_to(out, (float)c->pts[0], (float)c->pts[1]);
				for (int j = 1; j < c->n; j++)
					gsk_path_builder_line_to(out, (float)c->pts[2 * j], (float)c->pts[2 * j + 1]);
				gsk_path_builder_close(out);
			}
		}
		polygon_free(&subject);
		polygon_free(&clipping);
		return gsk_path_builder_free_to_path(out);
	}

	memset(&all_events, 0, sizeof(all_events));
	Heap queue = {0};
	int contour_counter = 0;
	process_polygon(&subject, true, &queue, &contour_counter);
	process_polygon(&clipping, false, &queue, &contour_counter);
	EventList sorted = {0};
	subdivide(&queue, &subject, &clipping, op, &sorted);
	connect_edges(&sorted, out);

	for (int i = 0; i < all_events.len; i++)
		free(all_events.items[i]);
	free(all_events.items);
	memset(&all_events, 0, sizeof(all_events));
	free(sorted.items);
	free(queue.a);
	polygon_free(&subject);
	polygon_free(&clipping);
	return gsk_path_builder_free_to_path(out);
}
