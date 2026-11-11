/* host-symbols.c: what android-translation-layer's main executable gives api-impl's natives
 * (libtranslation_layer_main.so), for running ATL's framework classes under a plain dalvikvm in
 * tests: the app window (none in a test), the APK path (none) and the app data directory. Built and
 * LD_PRELOADed by run.sh; never part of the runtime. */
#include <stdbool.h>
#include <stdlib.h>

void *window;
void *tl_main_overlay;
char *apk_path;

/* main.c sets apk_path to the app's APKs; the test names one in ATL_TEST_APK_PATH */
__attribute__((constructor)) static void init_apk_path(void)
{
	apk_path = getenv("ATL_TEST_APK_PATH");
}

/* no window, so no back button to update */
void back_button_set_sensitive(bool sensitive)
{
	(void)sensitive;
}

/* main.c: ANDROID_APP_DATA_DIR, which run.sh sets to the test's own directory */
char *get_app_data_dir(void)
{
	return getenv("ANDROID_APP_DATA_DIR");
}
