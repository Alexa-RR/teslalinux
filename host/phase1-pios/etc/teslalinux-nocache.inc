# teslalinux: lighttpd.conf:72-82 for every non-/api response of the app hosts
add_header Cross-Origin-Embedder-Policy credentialless always;
add_header Cross-Origin-Opener-Policy same-origin always;
add_header Cache-Control "no-store, no-cache, must-revalidate, max-age=0" always;
add_header Pragma no-cache always;
add_header Expires 0 always;
