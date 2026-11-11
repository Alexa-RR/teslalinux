#!/bin/sh
# teslalinux: libandroid/egl.c looks surfaces up in hash tables that are only created when the first
# window surface is made; a GLES client (Godot in Waze) that uses pbuffer surfaces hits
# g_hash_table_lookup(NULL, ...) on every eglMakeCurrent/eglSwapBuffers/eglQuerySurface call,
# which GLib logs as a CRITICAL (~50k lines per minute). Treat a missing table as "no entry".
set -eu
F=src/libandroid/egl.c
if ! grep -q "tl_table_lookup" "$F"; then
  sed -i 's|^static GHashTable \*egl_surface_hashtable;|static GHashTable *egl_surface_hashtable;\n#define tl_table_lookup(table, key) ((table) ? g_hash_table_lookup((table), (key)) : NULL)|' "$F"
  sed -i 's|g_hash_table_lookup(egl_surface_hashtable, |tl_table_lookup(egl_surface_hashtable, |g; s|g_hash_table_lookup(draw_surface_hashtable, |tl_table_lookup(draw_surface_hashtable, |g; s|g_hash_table_lookup(read_surface_hashtable, |tl_table_lookup(read_surface_hashtable, |g' "$F"
fi
grep -c "tl_table_lookup" "$F"
