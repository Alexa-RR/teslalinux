#!/bin/sh
# teslalinux: GNetworkMonitor-backed ConnectivityManager natives (static, with a change signal that
# feeds the Java callback registry) replacing ATL's per-callback signal handlers. The Java side is
# patches/atl-waze/android/net/ConnectivityManager.java. Run by build-atl.sh from ~/atl-src.
# Idempotent.
set -eu
T=src/api-impl-jni/net/android_net_ConnectivityManager.c
if ! grep -q "tl_connectivity" "$T"; then
  cp "$HOME/atl-native-patches/files/android_net_ConnectivityManager.c" "$T"
fi
grep -c "tl_connectivity" "$T"
