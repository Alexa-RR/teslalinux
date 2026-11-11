# teslalinux: /api proxying (lighttpd.conf:120-123); COEP/COOP come from the server level
proxy_http_version 1.1;
proxy_set_header Connection "";
proxy_set_header Host $host;
proxy_connect_timeout 5s;
proxy_read_timeout 30s;
expires 1s;
