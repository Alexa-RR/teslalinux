#!/bin/sh
# teslalinux (webkit): android.webkit.WebView on WebKitGTK 6.0 (tl_webkit). Replaces ATL's small
# WebView native (load URL/data only, behind ATL_UGLY_ENABLE_WEBVIEW) with the full engine binding:
# lazy engine creation, persistent network session, WebViewClient/WebChromeClient signals, history and
# session state, evaluateJavascript, the JavaScript interface bootstrap, settings, cookies.
# The Java side is patches/atl-waze/android/webkit/WebView.java (+ WebSettings, CookieManager).
# Run by build-atl.sh from ~/atl-src. Idempotent.
set -eu
T=src/api-impl-jni/widgets/android_webkit_WebView.c
if ! grep -q "tl_webkit" "$T"; then
  cp "$HOME/atl-native-patches/files/android_webkit_WebView.c" "$T"
fi
grep -c "tl_webkit" "$T"
