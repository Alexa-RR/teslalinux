#!/usr/bin/env python3
"""teslalinux: Conscrypt is ART's TLS and crypto provider, as on Android.

art_standalone ships wolfSSL's JSSE provider (external/wolfssljni) as libcore's provider 1 and default
socket factory, and puts its jar on the default boot class path. Apps written for Android expect
Conscrypt there: OkHttp's Android platform, android.net.ssl.SSLSockets, X509TrustManagerExtensions,
per-connection TLS 1.3 session tickets, and the JCA algorithms (digests, ciphers, signatures, key
agreement) in native BoringSSL code instead of Bouncy Castle's Java. build-dist.sh's boringssl and
conscrypt steps build AOSP's Conscrypt (com.android.org.conscrypt, the platform flavour) into
lib/java/dex/art/conscrypt-hostdex.jar and natives/libjavacrypto.so; this registers it:

  - libcore's java/security/security.properties: the provider list and default socket factories of
    AOSP libcore (luni/src/main/java/java/security/security.properties, identical from Android 9 to
    15): OpenSSLProvider, CertPathProvider, BouncyCastleProvider, JSSEProvider;
  - the default boot class path (parsed_options.cc, used when no -Xbootclasspath/BOOTCLASSPATH is
    given, which is how dalvikvm and ATL run): conscrypt-hostdex.jar right after core-libart, where
    AOSP's BOOTCLASSPATH has conscrypt; wolfssljni-hostdex.jar leaves it (nothing registers it any
    more, and every boot class path jar is compiled into the boot image).

Idempotent; run from the art_standalone source root.
"""
import re
import sys

PROPS = 'libcore/luni/src/main/java/java/security/security.properties'
BCP = 'art/runtime/parsed_options.cc'
MARK = 'teslalinux: Conscrypt'

AOSP_PROVIDERS = """# teslalinux: Conscrypt (tools/debvm/patches/art_standalone-conscrypt.py), AOSP libcore's list
security.provider.1=com.android.org.conscrypt.OpenSSLProvider
security.provider.2=sun.security.provider.CertPathProvider
security.provider.3=com.android.org.bouncycastle.jce.provider.BouncyCastleProvider
security.provider.4=com.android.org.conscrypt.JSSEProvider
"""
AOSP_FACTORIES = """ssl.SocketFactory.provider=com.android.org.conscrypt.OpenSSLSocketFactoryImpl
ssl.ServerSocketFactory.provider=com.android.org.conscrypt.OpenSSLSocketFactoryImpl
"""


def props():
    s = open(PROPS).read()
    if MARK in s:
        return False
    lines = s.splitlines(keepends=True)
    prov = [i for i, l in enumerate(lines) if re.match(r'security\.provider\.\d+=', l)]
    fact = [i for i, l in enumerate(lines) if re.match(r'ssl\.(Server)?SocketFactory\.provider=', l)]
    if not prov or len(fact) != 2:
        sys.exit(f'art_standalone-conscrypt: provider lines not found in {PROPS}')
    # the provider block keeps its place (its comments stay), its entries become AOSP's
    out = []
    for i, l in enumerate(lines):
        if i == prov[0]:
            out.append(AOSP_PROVIDERS)
        elif i == fact[0]:
            out.append(AOSP_FACTORIES)
        if i in prov or i in fact:
            continue
        out.append(l)
    open(PROPS, 'w').write(''.join(out))
    return True


def bootclasspath():
    s = open(BCP).read()
    if MARK in s:
        return False
    libart = re.search(r'( *)bootclasspath_prefix \+ "/core-libart-hostdex\.jar" *\+ \':\' \+\n', s)
    wolf = re.search(r' *\+ \':\' \+\n *bootclasspath_prefix \+ "/wolfssljni-hostdex\.jar";\n', s)
    if not libart or not wolf or s.count('wolfssljni-hostdex.jar') != 1:
        sys.exit(f'art_standalone-conscrypt: default boot class path not found in {BCP}')
    ind = libart.group(1)
    s = s[:wolf.start()] + ';\n' + s[wolf.end():]
    s = s.replace(libart.group(0), libart.group(0) +
                  f'{ind}// {MARK} (AOSP: core-oj:core-libart:conscrypt:okhttp:bouncycastle:apache-xml)\n'
                  f'{ind}bootclasspath_prefix + "/conscrypt-hostdex.jar"     + \':\' +\n', 1)
    open(BCP, 'w').write(s)
    return True


done = [props(), bootclasspath()]
print('art_standalone-conscrypt: ' + ('applied' if any(done) else 'already applied'))
