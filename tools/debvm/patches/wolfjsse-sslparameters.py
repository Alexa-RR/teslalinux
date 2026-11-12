#!/usr/bin/env python3
"""teslalinux: the SSLParameters bridge of the wolfSSL JSSE provider, for build-dist.sh's wolfjsse step.

art_standalone builds wolfssljni-hostdex.jar without WolfSSLJDK8Helper (its Android.mk leaves it out
for old Android releases whose SSLParameters has no SNI/ALPN methods). WolfSSLParametersHelper reaches
that class by name and ignores ClassNotFoundException, so everything an app sets through
SSLParameters.setApplicationProtocols() or setServerNames() is dropped without a word. OkHttp 4/5 on
Android 10+ (Android10SocketAdapter) sets ALPN only that way: with Conscrypt, as on a phone, it
negotiates HTTP/2; here every OkHttp client fell back to HTTP/1.1 and opened one connection per
concurrent request. Our core-oj-hostdex.jar has the Java 9 ALPN API (core-oj-alpn.py), so the helper
can be built against it.

Also fixed while here: decoupleParams() (SSLSocket.getSSLParameters()) handed the setServerNames
Method to the helper's setApplicationProtocols, so the protocols already set never came back out.

  wolfjsse-sslparameters.py JSSE_SRC_DIR OUT_DIR
      JSSE_SRC_DIR is wolfssljni's src/java/com/wolfssl/provider/jsse; OUT_DIR gets
      WolfSSLJDK8Helper.java (package com.wolfssl.provider.jsse, kept in adapter/ upstream) and the
      fixed WolfSSLParametersHelper.java. build-dist.sh compiles both and puts their dex in front of
      the jar's own (core-oj-alpn.py assemble).
"""
import os
import shutil
import sys

OLD = """                    mth = cls.getDeclaredMethod("setApplicationProtocols", paramList);
                    mth.invoke(obj, ret, setServerNames, in);"""
NEW = """                    mth = cls.getDeclaredMethod("setApplicationProtocols", paramList);
                    mth.invoke(obj, ret, setApplicationProtocols, in);"""


def main(src, out):
    os.makedirs(out, exist_ok=True)
    helper = os.path.join(src, "adapter", "WolfSSLJDK8Helper.java")
    if "package com.wolfssl.provider.jsse;" not in open(helper, encoding="utf-8").read():
        sys.exit("WolfSSLJDK8Helper.java: unexpected package (upstream changed; check the Class.forName name in WolfSSLParametersHelper)")
    shutil.copyfile(helper, os.path.join(out, "WolfSSLJDK8Helper.java"))
    p = os.path.join(src, "WolfSSLParametersHelper.java")
    s = open(p, encoding="utf-8").read()
    if '"com.wolfssl.provider.jsse.WolfSSLJDK8Helper"' not in s:
        sys.exit("WolfSSLParametersHelper.java: no longer loads com.wolfssl.provider.jsse.WolfSSLJDK8Helper")
    if s.count(OLD) == 1:
        s = s.replace(OLD, NEW)
    elif s.count(NEW) != 1:
        sys.exit("WolfSSLParametersHelper.java: decoupleParams anchor not found")
    with open(os.path.join(out, "WolfSSLParametersHelper.java"), "w", encoding="utf-8") as f:
        f.write(s)


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2])
