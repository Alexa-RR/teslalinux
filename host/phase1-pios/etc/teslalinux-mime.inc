# lighttpd.conf:31-51 mimetype.assign, the same map (charset=utf-8 on the text types via charset_types)
types {
  image/x-icon                  ico;
  image/jpeg                    jpeg jpg;
  image/png                     png;
  image/svg+xml                 svg;
  text/css                      css;
  text/html                     html;
  text/javascript               js mjs;
  application/json              json map;
  text/plain                    txt;
  application/vnd.ms-fontobject eot;
  font/otf                      otf;
  font/collection               ttc;
  font/ttf                      ttf;
  font/woff                     woff;
  font/woff2                    woff2;
  application/wasm              wasm;
}
default_type application/octet-stream;
charset utf-8;
# .mjs keeps a bare text/javascript like upstream (lighttpd.conf:50); nginx applies the charset per
# type, so text/javascript gets it for both .js and .mjs
charset_types text/css text/javascript application/json text/plain;  # text/html is in by default
