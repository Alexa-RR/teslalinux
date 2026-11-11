#!/usr/bin/env python3
"""APK Signature Scheme v2/v3 verification with the Python standard library only.

    apk_verify.py [--sdk N] FILE...     prints "OK <signer-sha256> <scheme> FILE" or "FAIL <why> FILE"

What Android's package installer checks before it accepts an APK (AOSP
frameworks/base/core/java/android/util/apk/ApkSignatureSchemeV2Verifier.java and ...V3Verifier.java,
apksig's ApkSigningBlockUtils): the v3 block when there is one (else v2); for the signer that applies
to SDK N (v3: its [minSdk, maxSdk] range), the signature over its signed data verifies with its public
key, the first certificate carries that same public key, the signature algorithms listed in the signed
data match the signatures, and the content digest (1 MiB chunked SHA-256/512 over the ZIP entries, the
central directory and the end-of-central-directory record with the central-directory offset pointing
at the signing block) equals the signed one. One supported algorithm per signer is checked, the
strongest, as the platform does. A file with no v2/v3 block is refused (apps we install all have one).

Algorithms: RSASSA-PSS (0x0101 SHA-256, 0x0102 SHA-512), RSASSA-PKCS1-v1_5 (0x0103, 0x0104), ECDSA on
P-256/P-384/P-521 (0x0201 SHA-256, 0x0202 SHA-512), DSA (0x0301 SHA-256; Waze's key is a DSA key).
The verity-digest variants (0x0421/0x0423/0x0425) are only ever present next to one of these, so they
are skipped, like apksig does when choosing which digest to check.
"""
import hashlib
import struct
import sys

V2_ID = 0x7109871A
V3_ID = 0xF05368C0
CHUNK = 1 << 20

# id -> (kind, hash name, strength order as in apksig's compareSignatureAlgorithm)
ALGS = {
    0x0101: ("rsa-pss", "sha256", 2),
    0x0102: ("rsa-pss", "sha512", 3),
    0x0103: ("rsa-pkcs1", "sha256", 2),
    0x0104: ("rsa-pkcs1", "sha512", 3),
    0x0201: ("ecdsa", "sha256", 2),
    0x0202: ("ecdsa", "sha512", 3),
    0x0301: ("dsa", "sha256", 2),
}

# DigestInfo prefixes for RSASSA-PKCS1-v1_5 (RFC 8017 9.2 note 1)
DIGEST_INFO = {
    "sha256": bytes.fromhex("3031300d060960864801650304020105000420"),
    "sha512": bytes.fromhex("3051300d060960864801650304020305000440"),
}

OID_RSA = "1.2.840.113549.1.1.1"
OID_EC = "1.2.840.10045.2.1"
OID_DSA = "1.2.840.10040.4.1"
CURVES = {  # SEC 2 / FIPS 186-4: p, a, b, Gx, Gy, n
    "1.2.840.10045.3.1.7": (
        0xFFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF,
        -3,
        0x5AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B,
        0x6B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296,
        0x4FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5,
        0xFFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551),
    "1.3.132.0.34": (
        int("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFFFF0000000000000000FFFFFFFF", 16),
        -3,
        int("B3312FA7E23EE7E4988E056BE3F82D19181D9C6EFE8141120314088F5013875AC656398D8A2ED19D2A85C8EDD3EC2AEF", 16),
        int("AA87CA22BE8B05378EB1C71EF320AD746E1D3B628BA79B9859F741E082542A385502F25DBF55296C3A545E3872760AB7", 16),
        int("3617DE4A96262C6F5D9E98BF9292DC29F8F41DBD289A147CE9DA3113B5F0B8C00A60B1CE1D7E819D7A431D7C90EA0E5F", 16),
        int("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFC7634D81F4372DDF581A0DB248B0A77AECEC196ACCC52973", 16)),
    "1.3.132.0.35": (
        (1 << 521) - 1,
        -3,
        int("0051953EB9618E1C9A1F929A21A0B68540EEA2DA725B99B315F3B8B489918EF109E156193951EC7E937B1652C0BD3BB1BF073573DF883D2C34F1EF451FD46B503F00", 16),
        int("00C6858E06B70404E9CD9E3ECB662395B4429C648139053FB521F828AF606B4D3DBAA14B5E77EFE75928FE1DC127A2FFA8DE3348B3C1856A429BF97E7E31C2E5BD66", 16),
        int("011839296A789A3BC0045C8A5FB42C7D1BD998F54449579B446817AFBD17273E662C97EE72995EF42640C550B9013FAD0761353C7086A272C24088BE94769FD16650", 16),
        int("01FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFA51868783BF2F966B7FCC0148F709A5D03BB5C9B8899C47AEBB6FB71E91386409", 16)),
}


class VerifyError(Exception):
    pass


# ------------------------------------------------------------------ binary helpers

def _u32(b, o):
    return struct.unpack_from("<I", b, o)[0]


def _lp(buf, off):
    """A uint32-length-prefixed slice; raises on truncation."""
    if off + 4 > len(buf):
        raise VerifyError("truncated length-prefixed field")
    n = _u32(buf, off)
    if off + 4 + n > len(buf):
        raise VerifyError("length-prefixed field runs past its parent")
    return buf[off + 4:off + 4 + n], off + 4 + n


def _seq(buf):
    """Every length-prefixed element of a length-prefixed sequence's payload."""
    out, off = [], 0
    while off < len(buf):
        item, off = _lp(buf, off)
        out.append(item)
    return out


# ------------------------------------------------------------------ minimal DER

def _der(buf, off=0):
    """(tag, content, next offset) of the DER element at off."""
    if off + 2 > len(buf):
        raise VerifyError("truncated DER")
    tag, n = buf[off], buf[off + 1]
    off += 2
    if n & 0x80:
        k = n & 0x7F
        if k == 0 or k > 4 or off + k > len(buf):
            raise VerifyError("bad DER length")
        n = int.from_bytes(buf[off:off + k], "big")
        off += k
    if off + n > len(buf):
        raise VerifyError("DER element runs past its parent")
    return tag, buf[off:off + n], off + n


def _der_children(content):
    out, off = [], 0
    while off < len(content):
        tag, c, nxt = _der(content, off)
        out.append((tag, c, content[off:nxt]))
        off = nxt
    return out


def _oid(content):
    first = content[0]
    parts = [first // 40, first % 40] if first < 80 else [2, first - 80]
    v = 0
    for byte in content[1:]:
        v = (v << 7) | (byte & 0x7F)
        if not byte & 0x80:
            parts.append(v)
            v = 0
    return ".".join(map(str, parts))


def _int(content):
    return int.from_bytes(content, "big")


def spki_of_certificate(cert_der):
    """The SubjectPublicKeyInfo (whole DER element) of an X.509 certificate."""
    tag, cert, _ = _der(cert_der)
    tbs_tag, tbs, _ = _der(cert)
    if tag != 0x30 or tbs_tag != 0x30:
        raise VerifyError("certificate is not a DER SEQUENCE")
    kids = _der_children(tbs)
    i = 1 if kids and kids[0][0] == 0xA0 else 0  # optional [0] version
    # serialNumber, signature, issuer, validity, subject, subjectPublicKeyInfo
    if len(kids) < i + 6:
        raise VerifyError("certificate too short")
    return kids[i + 5][2]


def parse_public_key(spki):
    tag, content, _ = _der(spki)
    kids = _der_children(content)
    if tag != 0x30 or len(kids) != 2 or kids[0][0] != 0x30 or kids[1][0] != 0x03:
        raise VerifyError("bad SubjectPublicKeyInfo")
    alg = _der_children(kids[0][1])
    oid = _oid(alg[0][1])
    key = kids[1][1][1:]  # BIT STRING without its unused-bits byte
    if oid == OID_RSA:
        _, rsa, _ = _der(key)
        n, e = [_int(c) for _, c, _ in _der_children(rsa)][:2]
        return ("rsa", (n, e))
    if oid == OID_EC:
        curve = _oid(alg[1][1])
        if curve not in CURVES:
            raise VerifyError("unsupported EC curve %s" % curve)
        size = (CURVES[curve][0].bit_length() + 7) // 8
        if key[0] != 4 or len(key) != 1 + 2 * size:
            raise VerifyError("EC public key is not an uncompressed point")
        return ("ec", (curve, int.from_bytes(key[1:1 + size], "big"), int.from_bytes(key[1 + size:], "big")))
    if oid == OID_DSA:
        p, q, g = [_int(c) for _, c, _ in _der_children(alg[1][1])]
        _, y, _ = _der(key)
        return ("dsa", (p, q, g, _int(y)))
    raise VerifyError("unsupported public key algorithm %s" % oid)


# ------------------------------------------------------------------ signature primitives

def _mgf1(seed, length, hname):
    out, counter = b"", 0
    while len(out) < length:
        out += hashlib.new(hname, seed + counter.to_bytes(4, "big")).digest()
        counter += 1
    return out[:length]


def _verify_rsa(key, kind, hname, msg, sig):
    n, e = key
    k = (n.bit_length() + 7) // 8
    if len(sig) != k:
        raise VerifyError("RSA signature length")
    s = int.from_bytes(sig, "big")
    if s >= n:
        raise VerifyError("RSA signature out of range")
    em = pow(s, e, n).to_bytes(k, "big")
    h = hashlib.new(hname, msg).digest()
    if kind == "rsa-pkcs1":
        t = DIGEST_INFO[hname] + h
        if em != b"\x00\x01" + b"\xff" * (k - len(t) - 3) + b"\x00" + t:
            raise VerifyError("RSA PKCS#1 v1.5 signature does not verify")
        return
    # RSASSA-PSS, MGF1 with the same hash, salt length = hash length (apksig's parameters)
    hlen = len(h)
    embits = n.bit_length() - 1
    emlen = (embits + 7) // 8
    em = em[-emlen:]
    if em[-1] != 0xBC:
        raise VerifyError("RSA-PSS trailer")
    masked, hh = em[:emlen - hlen - 1], em[emlen - hlen - 1:-1]
    if masked[0] >> (8 - (8 * emlen - embits)) if (8 * emlen - embits) else 0:
        raise VerifyError("RSA-PSS leftmost bits")
    db = bytes(a ^ b for a, b in zip(masked, _mgf1(hh, len(masked), hname)))
    db = bytes([db[0] & (0xFF >> (8 * emlen - embits))]) + db[1:]
    ps_len = emlen - 2 * hlen - 2
    if db[:ps_len] != b"\x00" * ps_len or db[ps_len] != 1:
        raise VerifyError("RSA-PSS padding")
    salt = db[ps_len + 1:]
    if hashlib.new(hname, b"\x00" * 8 + h + salt).digest() != hh:
        raise VerifyError("RSA-PSS signature does not verify")


def _rs(sig):
    tag, content, _ = _der(sig)
    kids = _der_children(content)
    if tag != 0x30 or len(kids) != 2:
        raise VerifyError("DER (r, s) signature expected")
    return _int(kids[0][1]), _int(kids[1][1])


def _trunc_hash(hname, msg, q):
    h = int.from_bytes(hashlib.new(hname, msg).digest(), "big")
    hbits = hashlib.new(hname).digest_size * 8
    qbits = q.bit_length()
    return h >> (hbits - qbits) if hbits > qbits else h


def _verify_dsa(key, hname, msg, sig):
    p, q, g, y = key
    r, s = _rs(sig)
    if not (0 < r < q and 0 < s < q):
        raise VerifyError("DSA signature out of range")
    w = pow(s, -1, q)
    z = _trunc_hash(hname, msg, q)
    v = (pow(g, z * w % q, p) * pow(y, r * w % q, p)) % p % q
    if v != r:
        raise VerifyError("DSA signature does not verify")


def _ec_add(P, Q, a, p):
    if P is None:
        return Q
    if Q is None:
        return P
    if P[0] == Q[0]:
        if (P[1] + Q[1]) % p == 0:
            return None
        lam = (3 * P[0] * P[0] + a) * pow(2 * P[1], -1, p) % p
    else:
        lam = (Q[1] - P[1]) * pow(Q[0] - P[0], -1, p) % p
    x = (lam * lam - P[0] - Q[0]) % p
    return x, (lam * (P[0] - x) - P[1]) % p


def _ec_mul(k, P, a, p):
    R = None
    while k:
        if k & 1:
            R = _ec_add(R, P, a, p)
        P = _ec_add(P, P, a, p)
        k >>= 1
    return R


def _verify_ecdsa(key, hname, msg, sig):
    curve, qx, qy = key
    p, a, b, gx, gy, n = CURVES[curve]
    if (qy * qy - qx * qx * qx - a * qx - b) % p:
        raise VerifyError("EC public key is not on its curve")
    r, s = _rs(sig)
    if not (0 < r < n and 0 < s < n):
        raise VerifyError("ECDSA signature out of range")
    z = _trunc_hash(hname, msg, n)
    w = pow(s, -1, n)
    X = _ec_add(_ec_mul(z * w % n, (gx, gy), a, p), _ec_mul(r * w % n, (qx, qy), a, p), a, p)
    if X is None or X[0] % n != r:
        raise VerifyError("ECDSA signature does not verify")


def verify_signature(spki, alg_id, msg, sig):
    kind, hname, _ = ALGS[alg_id]
    ktype, key = parse_public_key(spki)
    if kind.startswith("rsa") and ktype == "rsa":
        return _verify_rsa(key, kind, hname, msg, sig)
    if kind == "ecdsa" and ktype == "ec":
        return _verify_ecdsa(key, hname, msg, sig)
    if kind == "dsa" and ktype == "dsa":
        return _verify_dsa(key, hname, msg, sig)
    raise VerifyError("signature algorithm 0x%04x does not match a %s key" % (alg_id, ktype))


# ------------------------------------------------------------------ the APK

def read_layout(f):
    """Offsets of the signing block, central directory and EOCD, plus the block and EOCD bytes."""
    f.seek(0, 2)
    size = f.tell()
    tail = min(size, 65535 + 22)
    f.seek(size - tail)
    end = f.read(tail)
    eocd_rel = end.rfind(b"PK\x05\x06")
    if eocd_rel < 0 or len(end) - eocd_rel < 22:
        raise VerifyError("no ZIP end of central directory")
    eocd_off = size - tail + eocd_rel
    eocd = end[eocd_rel:]
    if len(eocd) != 22 + struct.unpack_from("<H", eocd, 20)[0]:
        raise VerifyError("ZIP comment length does not match the end of the file")
    cd_size, cd_off = struct.unpack_from("<II", eocd, 12)
    if cd_off + cd_size != eocd_off:
        raise VerifyError("central directory is not followed by the EOCD record (data inserted?)")
    if cd_off < 32:
        raise VerifyError("no APK Signing Block")
    f.seek(cd_off - 24)
    footer = f.read(24)
    if footer[8:] != b"APK Sig Block 42":
        raise VerifyError("no APK Signing Block (v1-only or unsigned APK)")
    block_size = struct.unpack_from("<Q", footer, 0)[0]
    block_off = cd_off - block_size - 8
    if block_size < 24 or block_off < 0:
        raise VerifyError("bad APK Signing Block size")
    f.seek(block_off)
    block = f.read(block_size + 8)
    if struct.unpack_from("<Q", block, 0)[0] != block_size:
        raise VerifyError("APK Signing Block sizes disagree")
    pairs, off = {}, 8
    while off < len(block) - 24:
        plen = struct.unpack_from("<Q", block, off)[0]
        if plen < 4 or off + 8 + plen > len(block) - 24:
            raise VerifyError("bad ID-value pair in the APK Signing Block")
        pairs[_u32(block, off + 8)] = block[off + 12:off + 8 + plen]
        off += 8 + plen
    return {"block_off": block_off, "cd_off": cd_off, "cd_size": cd_size, "eocd_off": eocd_off, "eocd": eocd, "pairs": pairs}


def content_digest(f, layout, hname):
    """ApkSigningBlockUtils.computeContentDigests for CHUNKED_SHA256 / CHUNKED_SHA512."""
    eocd = bytearray(layout["eocd"])
    struct.pack_into("<I", eocd, 16, layout["block_off"])
    sections = [(0, layout["block_off"]), (layout["cd_off"], layout["cd_size"]), None]
    chunk_digests = []
    for sec in sections:
        if sec is None:
            data_iter = [bytes(eocd[i:i + CHUNK]) for i in range(0, len(eocd), CHUNK)]
        else:
            start, length = sec

            def gen(start=start, length=length):
                f.seek(start)
                left = length
                while left > 0:
                    buf = f.read(min(CHUNK, left))
                    if not buf:
                        raise VerifyError("file shorter than its ZIP structure says")
                    left -= len(buf)
                    yield buf
            data_iter = gen()
        for chunk in data_iter:
            h = hashlib.new(hname)
            h.update(b"\xa5" + struct.pack("<I", len(chunk)))
            h.update(chunk)
            chunk_digests.append(h.digest())
    top = hashlib.new(hname)
    top.update(b"\x5a" + struct.pack("<I", len(chunk_digests)))
    for d in chunk_digests:
        top.update(d)
    return top.digest()


def _check_signer(signer, scheme, sdk):
    """Verifies one signer's signature; returns (algorithm id, signed digest, first cert DER) or None
    when (v3) the signer does not apply to sdk."""
    signed_data, off = _lp(signer, 0)
    if scheme == 3:
        if off + 8 > len(signer):
            raise VerifyError("truncated v3 signer")
        min_sdk, max_sdk = struct.unpack_from("<II", signer, off)
        off += 8
        if sdk is not None and not (min_sdk <= sdk <= max_sdk):
            return None
    sigs_buf, off = _lp(signer, off)
    pubkey, off = _lp(signer, off)
    sigs = []
    for s in _seq(sigs_buf):
        alg = _u32(s, 0)
        sig, _ = _lp(s, 4)
        sigs.append((alg, sig))
    if not sigs:
        raise VerifyError("signer has no signatures")
    supported = [x for x in sigs if x[0] in ALGS]
    if not supported:
        raise VerifyError("no supported signature algorithm (%s)" % ", ".join("0x%04x" % a for a, _ in sigs))
    alg, sig = max(supported, key=lambda x: ALGS[x[0]][2])
    verify_signature(pubkey, alg, signed_data, sig)
    # signed data: digests, certificates, [v3: minSdk, maxSdk], additional attributes
    digests_buf, o = _lp(signed_data, 0)
    certs_buf, o = _lp(signed_data, o)
    if scheme == 3:
        sd_min, sd_max = struct.unpack_from("<II", signed_data, o)
        if (sd_min, sd_max) != (min_sdk, max_sdk):
            raise VerifyError("v3 signer SDK range differs from its signed data")
    digests = []
    for d in _seq(digests_buf):
        dig, _ = _lp(d, 4)
        digests.append((_u32(d, 0), dig))
    if [a for a, _ in digests] != [a for a, _ in sigs]:
        raise VerifyError("signature algorithms differ between signatures and signed digests")
    certs = _seq(certs_buf)
    if not certs:
        raise VerifyError("signer has no certificate")
    if spki_of_certificate(certs[0]) != pubkey:
        raise VerifyError("public key of the first certificate differs from the signer's public key")
    return alg, dict(digests)[alg], certs[0]


def verify(path, sdk=None):
    """Raises VerifyError unless the APK verifies; returns (scheme, signer cert SHA-256 hex)."""
    with open(path, "rb") as f:
        layout = read_layout(f)
        for scheme, block_id in ((3, V3_ID), (2, V2_ID)):
            value = layout["pairs"].get(block_id)
            if value is None:
                continue
            signers_buf, _ = _lp(value, 0)
            signers = _seq(signers_buf)
            if not signers:
                raise VerifyError("v%d block has no signers" % scheme)
            if scheme == 2 and len(signers) != 1:
                raise VerifyError("v2 block with %d signers (only one supported, as for every Play APK)" % len(signers))
            results = [r for r in (_check_signer(s, scheme, sdk) for s in signers) if r is not None]
            if not results:
                raise VerifyError("no v3 signer for SDK %s" % sdk)
            if len(results) > 1:
                raise VerifyError("more than one v3 signer applies to SDK %s" % sdk)
            alg, signed_digest, cert = results[0]
            if content_digest(f, layout, ALGS[alg][1]) != signed_digest:
                raise VerifyError("content digest mismatch: the APK was modified or is incomplete")
            return "v%d" % scheme, hashlib.sha256(cert).hexdigest()
    raise VerifyError("no APK Signature Scheme v2/v3 block")


if __name__ == "__main__":
    args = sys.argv[1:]
    sdk = None
    if args[:1] == ["--sdk"]:
        sdk, args = int(args[1]), args[2:]
    if not args:
        sys.exit(__doc__)
    rc = 0
    for p in args:
        try:
            scheme, signer = verify(p, sdk)
            print("OK %s %s %s" % (signer, scheme, p))
        except (VerifyError, OSError) as e:
            print("FAIL %s %s" % (e, p))
            rc = 1
    sys.exit(rc)
