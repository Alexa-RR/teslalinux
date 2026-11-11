# teslalinux: the app host block (lighttpd.conf:71-124) for our names and private-range IP literals.
root @WEB_ROOT@;
index index.html;
include @NGINX_DIR@/teslalinux-mime.inc;
error_page 404 /404.html;

# lighttpd.conf:72-75 setenv.add-response-header on every response of these hosts, /api included.
# nginx drops inherited add_header lines in a location that has its own, so the locations below that
# add the no-cache set repeat these two through teslalinux-nocache.inc.
add_header Cross-Origin-Embedder-Policy credentialless always;
add_header Cross-Origin-Opener-Policy same-origin always;

# lighttpd.conf:77-86: no-store/no-cache/max-age=0, Pragma, Expires 0 and no ETags for everything
# except /api; the static files keep Last-Modified like lighttpd.
etag off;

# lighttpd.conf:120-123 /api -> config API with a 1 s expiry (mod_expire: Expires + Cache-Control
# max-age=1); upstream's URL regex is ^/api(/.*)?$
location = /api { proxy_pass http://127.0.0.1:8081; include @NGINX_DIR@/teslalinux-api.inc; }
location /api/  { proxy_pass http://127.0.0.1:8081; include @NGINX_DIR@/teslalinux-api.inc; }
# teslalinux: an app bundle uploaded from Settings > App updates (services/api/api.py streams it into
# teslalinux-appupdate receive-upload): up to 400 MB, passed through as it arrives (no buffering to disk
# here), and the answer comes after the verification
location = /api/appUpdateUpload {
  proxy_pass http://127.0.0.1:8081;
  proxy_http_version 1.1;
  proxy_set_header Connection "";
  proxy_set_header Host $host;
  client_max_body_size 400m;
  proxy_request_buffering off;
  proxy_connect_timeout 5s;
  proxy_send_timeout 600s;
  proxy_read_timeout 900s;
}

# lighttpd.conf:88-114 WebSocket upgrades to the four daemons
location /sockets/touchscreen { proxy_pass http://127.0.0.1:9999; include @NGINX_DIR@/teslalinux-ws.inc; include @NGINX_DIR@/teslalinux-nocache.inc; }
location /sockets/gps         { proxy_pass http://127.0.0.1:9998; include @NGINX_DIR@/teslalinux-ws.inc; include @NGINX_DIR@/teslalinux-nocache.inc; }
location /sockets/audio       { proxy_pass http://127.0.0.1:8080; include @NGINX_DIR@/teslalinux-ws.inc; include @NGINX_DIR@/teslalinux-nocache.inc; }
location /sockets/display     { proxy_pass http://127.0.0.1:9091; include @NGINX_DIR@/teslalinux-ws.inc; include @NGINX_DIR@/teslalinux-nocache.inc; }
# teslalinux: the device keyboard (tacomp TACOMP_IME_PORT; Settings > Keyboard, README.teslalinux.md)
location /sockets/keyboard    { proxy_pass http://127.0.0.1:9996; include @NGINX_DIR@/teslalinux-ws.inc; include @NGINX_DIR@/teslalinux-nocache.inc; }
# teslalinux: the car app interface (Car mode): the app's car templates as JSON, interactions back
# (ATL's car-app host, ATL_CAR_APP_CHANNEL_PORT; docs/UPSTREAM-NOTES.md "Car app templates")
location /sockets/carapp      { proxy_pass http://127.0.0.1:9995; include @NGINX_DIR@/teslalinux-ws.inc; include @NGINX_DIR@/teslalinux-nocache.inc; }

# lighttpd.conf:116-118 proxies /stream to 127.0.0.1:9090. Not carried over: nothing in the frontend
# (web/js, upstream's frontend and beta/ copy) requests /stream and no upstream daemon listens on 9090
# any more (the old MJPEG-over-HTTP display endpoint, replaced by /sockets/display). A proxy to a
# closed port would only turn into 502s; /stream is an ordinary 404 here.

# url.access-deny (lighttpd.conf:28)
location ~ "(~|\.inc|\.md|\.yml|\.ini)$" { include @NGINX_DIR@/teslalinux-nocache.inc; return 403; }
location = /404.html { include @NGINX_DIR@/teslalinux-nocache.inc; }
location / { include @NGINX_DIR@/teslalinux-nocache.inc; try_files $uri $uri/ =404; }
