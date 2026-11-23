/* gtk-init.c: GTK initialised before the test's VM starts (LD_PRELOADed by run.sh). GSK registers its
 * render node types in gtk_init, which an app gets from android-translation-layer's main; drawing
 * into Bitmaps needs nothing else (GSK's cairo renderer runs without a display). */
#include <stdio.h>
#include <gtk/gtk.h>

__attribute__((constructor)) static void tl_test_gtk_init(void)
{
	if (!gtk_init_check())
		fprintf(stderr, "gtk-init: gtk_init_check failed (no display)\n");
}
