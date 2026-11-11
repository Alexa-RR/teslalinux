# teslalinux: lighttpd's base server behaviour (lighttpd.conf:13-14,27-29): the frontend as static
# files, index.html, the 404 page, url.access-deny. Used by the catch-all and the connman servers.
root @WEB_ROOT@;
index index.html;
include @NGINX_DIR@/teslalinux-mime.inc;
error_page 404 /404.html;
# url.access-deny = ( "~", ".inc", ".md", ".yml", ".ini" ): suffix match, 403
location ~ "(~|\.inc|\.md|\.yml|\.ini)$" { return 403; }
location / { try_files $uri $uri/ =404; }
