#!/bin/sh
# teslalinux ATL test VM: which symbols does an app's native library import that neither
# bionic_translation's shims nor the host libraries ATL maps provide?  usage: symcheck.sh libfoo.so
set -eu
L="$1"
echo "lib=$L"
echo "NEEDED: $(readelf -d "$L" | grep NEEDED | sed -E 's/.*\[(.*)\]/\1/' | tr '\n' ' ')"
nm -D --undefined-only "$L" | sed -E 's/^ *[Uw] +//' | sed -E 's/@.*//' | sort -u > /tmp/undef.txt
: > /tmp/provided.txt
for f in /usr/lib/lib*_bio.so* /usr/lib/libandroid.so* /usr/lib/libEGL.so* /usr/lib/libGLESv1_CM.so* /usr/lib/libGLESv2.so* /usr/lib/libGLESv3.so* \
         /usr/lib/liblog.so* /usr/lib/libz.so* /lib/ld-musl-*.so.1 /usr/lib/libstdc++.so* /usr/lib/libc++.so* /usr/lib/libc++abi.so* \
         /usr/lib/libOpenSLES*.so* /usr/lib/libvulkan.so* /usr/lib/libmediandk*.so* /usr/lib/libcamera2ndk*.so* /usr/lib/libaaudio*.so* \
         /usr/lib/libjnigraphics*.so* /usr/lib/libstdc++_standalone*.so* /usr/lib/libgcc_s.so* /usr/lib/libunwind.so* \
         "$HOME"/atl-prefix/lib/*.so* "$HOME"/atl-prefix/lib/*/*.so* "$HOME"/atl-prefix/lib/java/dex/android_translation_layer/natives/*.so*; do
  [ -e "$f" ] || continue
  nm -D --defined-only "$f" 2>/dev/null | sed -E 's/^[0-9a-fA-F]* +[A-Za-z] +//' | sed -E 's/@.*//' >> /tmp/provided.txt
  # bionic_translation exports shims as bionic_<name>; the linker maps <name> -> bionic_<name>
  nm -D --defined-only "$f" 2>/dev/null | sed -E 's/^[0-9a-fA-F]* +[A-Za-z] +//' | grep '^bionic_' | sed 's/^bionic_//' >> /tmp/provided.txt
done
sort -u /tmp/provided.txt -o /tmp/provided.txt
echo "undefined: $(wc -l < /tmp/undef.txt)   provided set: $(wc -l < /tmp/provided.txt)"
echo "== MISSING (imported by $L, provided by nothing):"
comm -23 /tmp/undef.txt /tmp/provided.txt | tr '\n' ' '; echo
