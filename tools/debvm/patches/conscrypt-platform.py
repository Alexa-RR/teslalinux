#!/usr/bin/env python3
"""conscrypt-platform.py: the platform Conscrypt's two settings that differ under art_standalone.

  conscrypt-platform.py CONSCRYPT_CHECKOUT SYSTEM_CACERTS_DIR

1. Where the system CA certificates are. Conscrypt's platform TrustedCertificateStore (the
   AndroidCAStore key store behind the default TrustManager) reads them from
   /apex/com.android.conscrypt/cacerts on API 34+ devices with that APEX, else from
   $ANDROID_ROOT/etc/security/cacerts: one PEM file per CA named <X509_NAME_hash_old>.<n>. Under
   art_standalone ANDROID_ROOT stays unset, because ART itself would then look for dex2oat and its
   boot image there (host/phase1-pios/teslalinux-atl), so the path would come out as
   "null/etc/security/cacerts", relative to the app's working directory, and nothing would be
   trusted. With ANDROID_ROOT unset the store now uses SYSTEM_CACERTS_DIR (versions.env
   CONSCRYPT_SYSTEM_CACERTS; host/teslalinux-android-cacerts fills it from the system's CAs); with
   ANDROID_ROOT set, and for the APEX directory, AOSP's rule is unchanged. User-added CAs keep
   AOSP's place, $ANDROID_DATA/misc/keychain/cacerts-added.

2. Which SSLSocket a socket factory makes. Conscrypt has two: ConscryptEngineSocket (an SSLEngine
   over the plain socket's streams; the default, SSLUtils.USE_ENGINE_SOCKET_BY_DEFAULT) and
   ConscryptFileDescriptorSocket (BoringSSL reads and writes the socket's file descriptor itself).
   Measured on the Raspberry Pi 4 (dalvikvm, TLS 1.3, loopback): the file-descriptor socket takes
   9.7 ms of CPU per MB received instead of 21 (ChaCha20-Poly1305), 5.9 ms per full handshake
   instead of 9.5, 2.4 per resumed one instead of 3.6. It is AOSP code with the same API; a
   socket without a file descriptor still gets the engine socket (OpenSSLSocketFactoryImpl
   .hasFileDescriptor), and a read blocked when another thread closes the socket (how OkHttp
   cancels a call) is woken through libandroidio (patches/conscrypt-libandroidio.cpp) as on
   Android. So the default becomes the file-descriptor socket; AOSP's system property
   -Dcom.android.org.conscrypt.useEngineSocketByDefault=true still selects the engine socket.

Idempotent: a second run finds the markers and changes nothing.
"""
import os
import sys

EDITS = [
    ("repackaged/platform/src/main/java/com/android/org/conscrypt/TrustedCertificateStore.java",
     "teslalinux: ANDROID_ROOT unset",
     """            } else {
                defaultCaCertsSystemDir = new File(ANDROID_ROOT + "/etc/security/cacerts");
            }""",
     """            } else if (ANDROID_ROOT == null) {
                // teslalinux: ANDROID_ROOT unset (art_standalone: ART would look for its boot image
                // there): the system's CAs in Android's layout, kept by teslalinux-android-cacerts
                defaultCaCertsSystemDir = new File("@CACERTS@");
            } else {
                defaultCaCertsSystemDir = new File(ANDROID_ROOT + "/etc/security/cacerts");
            }"""),
    ("repackaged/common/src/main/java/com/android/org/conscrypt/SSLUtils.java",
     "teslalinux: file-descriptor sockets",
     """    static final boolean USE_ENGINE_SOCKET_BY_DEFAULT = Boolean.parseBoolean(
            System.getProperty("com.android.org.conscrypt.useEngineSocketByDefault", "true"));""",
     """    // teslalinux: file-descriptor sockets by default (tools/debvm/patches/conscrypt-platform.py:
    // half the CPU per byte and per handshake on a Raspberry Pi 4); the property still decides
    static final boolean USE_ENGINE_SOCKET_BY_DEFAULT = Boolean.parseBoolean(
            System.getProperty("com.android.org.conscrypt.useEngineSocketByDefault", "false"));"""),
]


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    root, cacerts = sys.argv[1], sys.argv[2]
    if not cacerts.startswith("/") or '"' in cacerts or "\\" in cacerts:
        sys.exit("SYSTEM_CACERTS_DIR must be an absolute path without quotes or backslashes")
    for rel, mark, old, new in EDITS:
        path = os.path.join(root, rel)
        with open(path, encoding="utf-8") as f:
            src = f.read()
        if mark in src:
            print("conscrypt-platform: %s already patched" % os.path.basename(rel))
            continue
        if src.count(old) != 1:
            sys.exit("conscrypt-platform: anchor not found once in " + rel)
        src = src.replace(old, new.replace("@CACERTS@", cacerts))
        with open(path, "w", encoding="utf-8") as f:
            f.write(src)
        print("conscrypt-platform: %s patched" % os.path.basename(rel))


if __name__ == "__main__":
    main()
