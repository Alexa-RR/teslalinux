# teslalinux TLS (lighttpd.conf:53-59). One certificate covers DEVICE_HOST, www., fullscreen. and
# www.fullscreen. (upstream used a second certificate for the fullscreen names, lighttpd.conf:65-68;
# one SAN certificate serves the same names with one key). The key is made on the Pi and never leaves
# it (host/tls/teslalinux-tls); the cloud renewer signs its CSR.
ssl_certificate     @TLS_CERT@;
ssl_certificate_key @TLS_KEY@;
ssl_protocols TLSv1.2 TLSv1.3;
# Upstream's cipher list is "AES128-SHA" (RSA key exchange, TLS 1.2 only; TLS 1.3 suites are not
# affected by it). Our certificate key is ECDSA P-256, which AES128-SHA cannot use, so the list
# keeps AES128-SHA for RSA placeholders and puts ChaCha20 first: the Pi 4's Cortex-A72 has no AES
# instructions, ChaCha20-Poly1305 is several times cheaper per byte there, and the display stream
# is the bulk of the traffic.
ssl_ciphers ECDHE-ECDSA-CHACHA20-POLY1305:ECDHE-RSA-CHACHA20-POLY1305:ECDHE-ECDSA-AES128-GCM-SHA256:ECDHE-RSA-AES128-GCM-SHA256:AES128-SHA;
ssl_conf_command Ciphersuites TLS_CHACHA20_POLY1305_SHA256:TLS_AES_128_GCM_SHA256:TLS_AES_256_GCM_SHA384;
ssl_prefer_server_ciphers on;
# ssl.session-cache = "enable"; its own zone name, the distribution configs define shared:SSL
ssl_session_cache shared:teslalinux:1m;
ssl_session_timeout 1h;
