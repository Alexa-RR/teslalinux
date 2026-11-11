#!/usr/bin/env bash
# teslalinux: give ATL's ART libcore (core-oj-hostdex.jar) the Java 9 / Android 10 ALPN API that
# OkHttp and gRPC need for HTTP/2 over TLS: SSLParameters.{set,get}ApplicationProtocols,
# SSLSocket.getApplicationProtocol/getHandshakeApplicationProtocol/setHandshakeApplicationProtocolSelector,
# SSLEngine.getApplicationProtocol/... The classes are AOSP libcore's (ojluni), with one adaptation:
# SSLSocket.getApplicationProtocol() maps onto the Conscrypt-style getAlpnSelectedProtocol() that the
# wolfSSL JNI provider implements (the provider predates the Java 9 API on the socket side; its
# SSLEngine and SSLParameters import already use it reflectively).
#
# The jar is rebuilt as classes.dex (the 3 new classes) + classes2.dex (the original, untouched dex).
# ART resolves a boot class from the first dex file that defines it, and dex2oat accepts the
# duplicate definitions in the second dex (verified: boot image builds, the first definitions win).
# Rewriting the original dex to drop the 3 class_defs is not an option: the verifier rejects the
# orphaned class_data items ("could not find declaring class for instance field").
#
#   patch-core-oj.sh            build + install into the guest (backs up the original once)
#   patch-core-oj.sh --restore  put the original jar back
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# WORK (default <repo>/out/atl-work), JARS, PY, topath, hostenv_use_jdk; atl-host/jars needs
# core-all_classes.jar and dx.jar (hostbuild-setup.sh pulls both from the guest)
# shellcheck source=hostenv.sh
. "$HERE/hostenv.sh"
T="$WORK/tlssl"
# Alpine VM: the system ART jar; the Debian build VM sets ATL_GUEST_CORE_OJ=/opt/teslalinux/lib/java/dex/art/core-oj-hostdex.jar
GUEST_JAR="${ATL_GUEST_CORE_OJ:-/usr/lib/java/dex/art/core-oj-hostdex.jar}"
log() { printf '\033[1;32m[core-oj]\033[0m %s\n' "$*"; }

if [[ "${1:-}" == "--restore" ]]; then
  bash "$HERE/vm.sh" ssh "doas cp $GUEST_JAR.orig $GUEST_JAR; rm -f ~/.cache/art/arm64/*boot*; ls -la $GUEST_JAR"
  exit 0
fi

for j in core-all_classes.jar dx.jar; do [[ -s "$JARS/$j" ]] || hostenv_die "work dir $WORK is missing atl-host/jars/$j -- run tools/atl-vm/hostbuild-setup.sh jars"; done
hostenv_use_jdk
mkdir -p "$T/src/javax/net/ssl" "$T/classes" "$WORK/aosp/libcore"
# boot-classpath classes of our own (patches/atl-waze/bootcp/**): java.lang.invoke.LambdaMetafactory, the
# bootstrap every lambda inside libcore reaches (the shipped one is a placeholder returning null)
cp -r "$HERE/patches/atl-waze/bootcp/." "$T/src/"
# 1. libcore sources (ojluni, AOSP main)
for f in SSLParameters SSLSocket SSLEngine; do
  [[ -s "$WORK/aosp/libcore/$f.java" ]] || curl -sS -f -L "https://android.googlesource.com/platform/libcore/+/refs/heads/main/ojluni/src/main/java/javax/net/ssl/$f.java?format=TEXT" | base64 -d > "$WORK/aosp/libcore/$f.java"
done
"$PY" - "$(topath "$WORK")" <<'PYEOF'
import sys
S = sys.argv[1]
for f in ("SSLParameters", "SSLSocket", "SSLEngine"):
    s = open(f"{S}/aosp/libcore/{f}.java").read()
    if f == "SSLSocket":
        old = "    public String getApplicationProtocol() {\n        throw new UnsupportedOperationException();\n    }"
        new = """    public String getApplicationProtocol() {
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
        assert old in s
        s = s.replace(old, new, 1)
    open(f"{S}/tlssl/src/javax/net/ssl/{f}.java", "w", newline="\n").write(s)
PYEOF
# 2. compile + dex (core library)
log "javac + dx"
rm -rf "$T/classes"; mkdir -p "$T/classes"
javac -g -nowarn -source 1.8 -target 1.8 -encoding UTF-8 -Xlint:-options -bootclasspath "$(topath "$JARS/core-all_classes.jar")" -d "$(topath "$T/classes")" $(find "$T/src" -name "*.java")
java -jar "$(topath "$JARS/dx.jar")" --dex --core-library --output="$(topath "$T/tl-ssl.dex")" "$(topath "$T/classes")"
# 3. original jar from the guest (kept as a backup there); assemble new-classes.dex + original dex
[[ -s "$WORK/core-oj-hostdex.jar" ]] || bash "$HERE/vm.sh" pull "$GUEST_JAR.orig" "$WORK/core-oj-hostdex.jar" >/dev/null 2>&1 || bash "$HERE/vm.sh" pull "$GUEST_JAR" "$WORK/core-oj-hostdex.jar" >/dev/null
"$PY" -I - "$(topath "$WORK")" <<'PYEOF'
import sys, zipfile
S = sys.argv[1]
src = zipfile.ZipFile(f"{S}/core-oj-hostdex.jar")
out = zipfile.ZipFile(f"{S}/core-oj-hostdex-tl.jar", "w")
ct = zipfile.ZIP_DEFLATED
orig = None
for info in src.infolist():
    if info.filename == "classes.dex":
        ct = info.compress_type
        orig = src.read(info.filename)
        continue
    out.writestr(info, src.read(info.filename))
out.writestr(zipfile.ZipInfo("classes.dex"), open(f"{S}/tlssl/tl-ssl.dex", "rb").read(), compress_type=ct)
out.writestr(zipfile.ZipInfo("classes2.dex"), orig, compress_type=ct)
out.close()
PYEOF
# 4. install (the prefix copy is a symlink to the system jar) and drop the cached boot image
log "install"
bash "$HERE/vm.sh" push "$WORK/core-oj-hostdex-tl.jar" /home/atl/core-oj-hostdex-tl.jar >/dev/null
bash "$HERE/vm.sh" ssh "doas cp -n $GUEST_JAR $GUEST_JAR.orig; doas cp ~/core-oj-hostdex-tl.jar $GUEST_JAR; rm -f ~/.cache/art/arm64/*boot* ~/.cache/art/arm64/*api-impl*; ls -la $GUEST_JAR*"
log "done (ART rebuilds the boot image and the app oat on the next start)"
