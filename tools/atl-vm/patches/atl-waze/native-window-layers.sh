#!/bin/sh
# teslalinux: WindowManager windows the app adds (PopupWindow, Compose popups, panels) become layers of
# the main window with AOSP sizing, placement and input rules (tl_window_layers). Installs
# native/android_view_WindowManagerImpl.c over ATL's file of that name (which put each window into a
# GtkPopover pointing at x/y). The file is pushed to ~/atl-native-patches/files/. Needs
# tl_main_overlay (native-dialog-overlay.sh). Run by build-atl.sh from ~/atl-src. Idempotent.
set -eu
F=src/api-impl-jni/views/android_view_WindowManagerImpl.c
cp "$HOME/atl-native-patches/files/android_view_WindowManagerImpl.c" "$F"
grep -c "tl_window_layers" "$F"
