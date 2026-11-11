#!/usr/bin/env python3
"""teslalinux: the ALPN API in ART's libcore (core-oj-hostdex.jar), for build-dist.sh's coreoj step.

The same change as tools/atl-vm/patch-core-oj.sh (whose header explains the why), split in the two
pure-file parts so the Linux build can run it without the Mac or a VM:

  core-oj-alpn.py edit AOSP_DIR OUT_DIR
      AOSP_DIR holds AOSP libcore's ojluni SSLParameters.java, SSLSocket.java, SSLEngine.java;
      OUT_DIR gets them back, SSLSocket.getApplicationProtocol() mapped onto the Conscrypt-style
      getAlpnSelectedProtocol() of the wolfSSL JNI provider plus Conscrypt's remaining socket surface.
  core-oj-alpn.py assemble ORIGINAL_JAR NEW_CLASSES_DEX OUT_JAR
      OUT_JAR = ORIGINAL_JAR with NEW_CLASSES_DEX as classes.dex and the original classes.dex as
      classes2.dex (ART takes a boot class from the first dex that defines it).
"""
import sys
import zipfile

OLD = "    public String getApplicationProtocol() {\n        throw new UnsupportedOperationException();\n    }"
NEW = """    public String getApplicationProtocol() {
        /* teslalinux: the JSSE provider in this runtime (wolfSSL JNI) implements the Conscrypt-style
         * getAlpnSelectedProtocol() instead of overriding this Java 9 method; map one onto the other,
         * and keep the specified UnsupportedOperationException for providers that have neither. */
        try {
            java.lang.reflect.Method m = getClass().getMethod("getAlpnSelectedProtocol");
            byte[] selected = (byte[]) m.invoke(this);
            return selected == null ? null : new String(selected, java.nio.charset.StandardCharsets.UTF_8);
        } catch (NoSuchMethodException e) {
            throw new UnsupportedOperationException();
        } catch (ReflectiveOperationException e) {
            throw new UnsupportedOperationException(e);
        }
    }

    /* teslalinux: Conscrypt compatibility surface. OkHttp and gRPC on Android look these up by name
     * on the socket class (OptionalMethod / getMethod, so inherited methods count). The wolfSSL
     * provider implements setUseSessionTickets, setAlpnProtocols and getAlpnSelectedProtocol itself;
     * the remaining three are provided here with the semantics Conscrypt has today: NPN was removed
     * from Conscrypt in 2016, so its setters are no-ops and nothing is ever selected; setHostname is
     * the SNI host, expressed through the standard SSLParameters server names. */
    public void setHostname(String hostname) {
        SSLParameters params = getSSLParameters();
        if (hostname == null || hostname.isEmpty()) {
            params.setServerNames(java.util.Collections.<javax.net.ssl.SNIServerName>emptyList());
        } else {
            params.setServerNames(java.util.Collections.<javax.net.ssl.SNIServerName>singletonList(new javax.net.ssl.SNIHostName(hostname)));
        }
        setSSLParameters(params);
    }

    public void setNpnProtocols(byte[] npnProtocols) {
        // NPN is obsolete; Conscrypt ignores it as well
    }

    public byte[] getNpnSelectedProtocol() {
        return null;
    }"""


def edit(aosp_dir, out_dir):
    for name in ("SSLParameters", "SSLSocket", "SSLEngine"):
        with open("%s/%s.java" % (aosp_dir, name), encoding="utf-8") as f:
            s = f.read()
        if name == "SSLSocket":
            if OLD not in s:
                sys.exit("core-oj-alpn: SSLSocket.getApplicationProtocol() is not the expected AOSP text (moved LIBCORE_COMMIT?)")
            s = s.replace(OLD, NEW, 1)
        with open("%s/%s.java" % (out_dir, name), "w", encoding="utf-8", newline="\n") as f:
            f.write(s)


def assemble(orig_jar, new_dex, out_jar):
    with zipfile.ZipFile(orig_jar) as src, zipfile.ZipFile(out_jar, "w") as out:
        names = src.namelist()
        if "classes.dex" not in names or "classes2.dex" in names:
            sys.exit("core-oj-alpn: %s is not an unpatched single-dex jar (%s)" % (orig_jar, ", ".join(n for n in names if n.endswith(".dex"))))
        ct = zipfile.ZIP_DEFLATED
        orig = None
        for info in src.infolist():
            if info.filename == "classes.dex":
                ct = info.compress_type
                orig = src.read(info.filename)
                continue
            out.writestr(info, src.read(info.filename))
        with open(new_dex, "rb") as f:
            out.writestr(zipfile.ZipInfo("classes.dex"), f.read(), compress_type=ct)
        out.writestr(zipfile.ZipInfo("classes2.dex"), orig, compress_type=ct)


if __name__ == "__main__":
    if len(sys.argv) == 4 and sys.argv[1] == "edit":
        edit(sys.argv[2], sys.argv[3])
    elif len(sys.argv) == 5 and sys.argv[1] == "assemble":
        assemble(sys.argv[2], sys.argv[3], sys.argv[4])
    else:
        sys.exit(__doc__)
