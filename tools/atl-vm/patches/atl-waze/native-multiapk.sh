#!/bin/sh
# teslalinux: the ATL revision in ~/atl-src only puts files[0] on the app classpath ("TODO: pass all
# files to classpath"). Upstream ATL (later revisions) joins every APK given on the command line, which is
# what split APKs need: base.apk carries the code and manifest, split_config.* carry natives/resources.
# Keeping the splits (instead of a merged, re-signed APK) preserves the original APK signing block.
set -eu
F=src/main-executable/main.c
if ! grep -q "apk_classpaths" "$F"; then
python3 - "$F" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read()
old=r'''	char *apk_classpath = g_file_get_path(files[0]);
	char *apk_name = g_file_get_basename(files[0]);

	if (apk_classpath == NULL) {
		fprintf(stderr, "error: the specified file path doesn't seem to be valid\n");
		exit(1);
	}

	if (access(apk_classpath, F_OK) < 0) {
		fprintf(stderr, "error: the specified file path (%s) doesn't seem to exist (%m)\n", apk_classpath);
		exit(1);
	}
'''
new=r'''	char **apk_classpaths = g_new0(gchar *, nfiles + 1); // +1 for NULL terminator for g_strjoinv
	char *apk_name = g_file_get_basename(files[0]);
	for (int i = 0; i < nfiles; i++) {
		apk_classpaths[i] = g_file_get_path(files[i]);
		if (apk_classpaths[i] == NULL) {
			fprintf(stderr, "error: the specified file path (%s) doesn't seem to be valid\n", g_file_get_uri(files[i]));
			exit(1);
		}
		if (access(apk_classpaths[i], F_OK) < 0) {
			fprintf(stderr, "error: the specified file path (%s) doesn't seem to exist (%m)\n", apk_classpaths[i]);
			exit(1);
		}
	}
	char *apk_classpath = g_strjoinv(":", apk_classpaths);
	g_free(apk_classpaths);
'''
assert old in s, "main.c classpath block not found"
s=s.replace(old,new)
open(p,'w').write(s)
PY
fi
grep -c "apk_classpaths" "$F"
