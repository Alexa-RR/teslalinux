#!/usr/bin/env python3
"""Print facts about an APK that an installer needs, without any Android tooling:

    apk-info.py base.apk            -> JSON with package, versionCode, versionName, split,
                                       minSdk, abis (lib/ dirs), signer (SHA-256 of the signing
                                       certificate from the APK Signing Block, v3 then v2)
    apk-info.py --verify [--sdk N] F...  the same plus "verified" / "verifyError": the full APK
                                       Signature Scheme v3 (else v2) check Android's installer
                                       makes (apk_verify.py below), so a truncated, corrupted or
                                       tampered APK is refused, not only one with a foreign signer

Only the binary AndroidManifest.xml (AXML) and the APK Signing Block are parsed; both formats are
stable and documented (frameworks/base ResourceTypes.h, apksig). Used by install-app.sh to make
sure an update is the same package, signed with the same key, and not a downgrade: the same checks
the Android package manager makes.
"""
import hashlib
import json
import struct
import sys
import zipfile

RES_STRING_POOL_TYPE = 0x0001
RES_XML_TYPE = 0x0003
RES_XML_START_ELEMENT_TYPE = 0x0102
RES_XML_RESOURCE_MAP_TYPE = 0x0180
TYPE_STRING = 0x03
TYPE_INT_DEC = 0x10
TYPE_INT_HEX = 0x11
TYPE_INT_BOOLEAN = 0x12
UTF8_FLAG = 0x100

# attribute resource ids from android.R.attr (public, frozen)
ATTR_VERSION_CODE = 0x0101021B
ATTR_VERSION_NAME = 0x0101021C
ATTR_MIN_SDK = 0x0101020C


def _u16(b, o):
    return struct.unpack_from("<H", b, o)[0]


def _u32(b, o):
    return struct.unpack_from("<I", b, o)[0]


def _read_string_pool(b, off):
    """Returns the list of strings of a ResStringPool chunk starting at off."""
    header_size = _u16(b, off + 2)
    chunk_size = _u32(b, off + 4)
    count = _u32(b, off + 8)
    flags = _u32(b, off + 16)
    strings_start = _u32(b, off + 20)
    utf8 = bool(flags & UTF8_FLAG)
    strings = []
    for i in range(count):
        s_off = off + strings_start + _u32(b, off + header_size + 4 * i)
        if utf8:
            # two varlen lengths: UTF-16 char count, then byte count
            n = b[s_off]
            s_off += 2 if n & 0x80 else 1
            n = b[s_off]
            if n & 0x80:
                n = ((n & 0x7F) << 8) | b[s_off + 1]
                s_off += 2
            else:
                s_off += 1
            strings.append(b[s_off:s_off + n].decode("utf-8", "replace"))
        else:
            n = _u16(b, s_off)
            s_off += 2
            if n & 0x8000:
                n = ((n & 0x7FFF) << 16) | _u16(b, s_off)
                s_off += 2
            strings.append(b[s_off:s_off + 2 * n].decode("utf-16-le", "replace"))
    return strings, off + chunk_size


def parse_manifest(b):
    """Attributes of the <manifest> and <uses-sdk> elements of a binary AndroidManifest.xml."""
    if _u16(b, 0) != RES_XML_TYPE:
        raise ValueError("not a binary XML file")
    off = _u16(b, 2)
    strings, res_map = [], []
    out = {}
    while off < len(b):
        ctype = _u16(b, off)
        chunk_size = _u32(b, off + 4)
        if ctype == RES_STRING_POOL_TYPE:
            strings, _ = _read_string_pool(b, off)
        elif ctype == RES_XML_RESOURCE_MAP_TYPE:
            header_size = _u16(b, off + 2)
            res_map = [_u32(b, off + header_size + 4 * i) for i in range((chunk_size - header_size) // 4)]
        elif ctype == RES_XML_START_ELEMENT_TYPE:
            name = strings[_u32(b, off + 20)]
            attr_start = off + 16 + _u16(b, off + 24)
            attr_size = _u16(b, off + 26)
            attr_count = _u16(b, off + 28)
            attrs = {}
            for i in range(attr_count):
                a = attr_start + i * attr_size
                name_idx = _u32(b, a + 4)
                raw = _u32(b, a + 8)
                dtype = b[a + 15]
                data = _u32(b, a + 16)
                attr_name = strings[name_idx] if name_idx < len(strings) else ""
                res_id = res_map[name_idx] if name_idx < len(res_map) else 0
                if dtype == TYPE_STRING:
                    value = strings[raw] if raw < len(strings) else strings[data]
                elif dtype == TYPE_INT_BOOLEAN:
                    value = data != 0
                else:
                    value = data
                attrs[attr_name] = value
                if res_id:
                    attrs[res_id] = value
            if name == "manifest":
                out["package"] = attrs.get("package")
                out["split"] = attrs.get("split")
                out["versionCode"] = attrs.get(ATTR_VERSION_CODE, attrs.get("versionCode"))
                out["versionName"] = attrs.get(ATTR_VERSION_NAME, attrs.get("versionName"))
            elif name == "uses-sdk":
                out["minSdk"] = attrs.get(ATTR_MIN_SDK, attrs.get("minSdkVersion"))
        off += chunk_size
    return out


def _len_prefixed(buf, off):
    n = _u32(buf, off)
    return buf[off + 4:off + 4 + n], off + 4 + n


def signing_certificate(path):
    """DER of the first signer's signing certificate from the APK Signing Block (v3, else v2), or None."""
    with open(path, "rb") as f:
        f.seek(0, 2)
        size = f.tell()
        tail = min(size, 65536 + 22)
        f.seek(size - tail)
        end = f.read(tail)
        eocd = end.rfind(b"PK\x05\x06")
        if eocd < 0:
            raise ValueError("no end of central directory")
        cd_offset = _u32(end, eocd + 16)
        f.seek(cd_offset - 24)
        footer = f.read(24)
        if footer[8:] != b"APK Sig Block 42":
            return None
        block_size = struct.unpack_from("<Q", footer, 0)[0]
        f.seek(cd_offset - block_size - 8)
        block = f.read(block_size + 8)
    off = 8
    pairs = {}
    while off < len(block) - 24:
        pair_len = struct.unpack_from("<Q", block, off)[0]
        pair_id = _u32(block, off + 8)
        pairs[pair_id] = block[off + 12:off + 8 + pair_len]
        off += 8 + pair_len
    for scheme_id in (0xF05368C0, 0x7109871A):  # v3, v2 (v3.1 0x1B93AD61 only adds rotation targets)
        value = pairs.get(scheme_id)
        if value is None:
            continue
        signers, _ = _len_prefixed(value, 0)
        signer, _ = _len_prefixed(signers, 0)
        signed_data, _ = _len_prefixed(signer, 0)
        _digests, o = _len_prefixed(signed_data, 0)
        certs, _ = _len_prefixed(signed_data, o)
        cert, _ = _len_prefixed(certs, 0)
        return cert
    return None


def apk_info(path):
    with zipfile.ZipFile(path) as z:
        manifest = parse_manifest(z.read("AndroidManifest.xml"))
        abis = sorted({n.split("/")[1] for n in z.namelist() if n.startswith("lib/") and n.count("/") >= 2})
    cert = signing_certificate(path)
    manifest["abis"] = abis
    manifest["signer"] = hashlib.sha256(cert).hexdigest() if cert else None
    manifest["file"] = path
    return manifest


def main(argv):
    verify, sdk, paths = False, None, []
    it = iter(argv)
    for a in it:
        if a == "--verify":
            verify = True
        elif a == "--sdk":
            sdk = int(next(it))
        else:
            paths.append(a)
    if not paths:
        sys.exit(__doc__)
    out = []
    for p in paths:
        info = apk_info(p)
        if verify:
            import os
            sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
            import apk_verify
            try:
                apk_verify.verify(p, sdk)
                info["verified"], info["verifyError"] = True, None
            except apk_verify.VerifyError as e:
                info["verified"], info["verifyError"] = False, str(e)
        out.append(info)
    print(json.dumps(out if len(out) > 1 else out[0], indent=2))


if __name__ == "__main__":
    main(sys.argv[1:])
