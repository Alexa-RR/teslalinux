# teslalinux: WebSocket proxying (lighttpd.conf:88-114: proxy.header upgrade, Connection: Upgrade)
proxy_http_version 1.1;
proxy_set_header Upgrade $http_upgrade;
proxy_set_header Connection $tl_connection_upgrade;
proxy_set_header Host $host;
# the streams are long-lived; tacomp pings every second, the Python daemons every 5 s
proxy_read_timeout 3600s;
proxy_send_timeout 3600s;
proxy_buffering off;
tcp_nodelay on;
