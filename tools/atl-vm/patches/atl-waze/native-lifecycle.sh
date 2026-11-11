#!/bin/sh
# teslalinux: point ATL's native activity driver at Activity.perform*() so AOSP's Pre/Post lifecycle
# callbacks are dispatched (run in the guest against ~/atl-src, then rebuild natives).
set -eu
F="$HOME/atl-src/src/api-impl-jni/handle_cache.c"
for m in Create Start Resume Pause Stop Destroy; do
  lc=$(echo "$m" | tr 'A-Z' 'a-z')
  sed -i "s|handle_cache.activity.on$m = _METHOD(handle_cache.activity.class, \"on$m\", |handle_cache.activity.on$m = _METHOD(handle_cache.activity.class, \"perform$m\", |" "$F"
done
grep -n '"perform' "$F"
