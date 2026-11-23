#!/usr/bin/env python3
"""A QR code encoder (ISO/IEC 18004), stdlib only: byte mode, error correction level M,
versions 1 to 10 (up to 213 bytes), the mask with the lowest penalty.

Used for the PalGate device-linking code the car page shows (services/gates/gates.py); written
from the standard, small on purpose: the Pi needs no QR package for one short text.

    matrix(text) -> list of rows, each a list of 0/1 (1 = dark), without the quiet zone
    svg(text) -> an SVG image of it with a four-module quiet zone
"""

# level M, per version: (EC codewords per block, [(blocks, data codewords per block), ...])
_BLOCKS_M = {
    1: (10, [(1, 16)]),
    2: (16, [(1, 28)]),
    3: (26, [(1, 44)]),
    4: (18, [(2, 32)]),
    5: (24, [(2, 43)]),
    6: (16, [(4, 27)]),
    7: (18, [(4, 31)]),
    8: (22, [(2, 38), (2, 39)]),
    9: (22, [(3, 36), (2, 37)]),
    10: (26, [(4, 43), (1, 44)]),
}
_ALIGN = {1: [], 2: [6, 18], 3: [6, 22], 4: [6, 26], 5: [6, 30], 6: [6, 34],
          7: [6, 22, 38], 8: [6, 24, 42], 9: [6, 26, 46], 10: [6, 28, 50]}
_ECL_M_BITS = 0b00  # L 01, M 00, Q 11, H 10

# GF(256) with the QR polynomial x^8 + x^4 + x^3 + x^2 + 1
_EXP = [0] * 512
_LOG = [0] * 256
_x = 1
for _i in range(255):
    _EXP[_i] = _x
    _LOG[_x] = _i
    _x <<= 1
    if _x & 0x100:
        _x ^= 0x11D
for _i in range(255, 512):
    _EXP[_i] = _EXP[_i - 255]


def _gf_mul(a, b):
    return 0 if a == 0 or b == 0 else _EXP[_LOG[a] + _LOG[b]]


def _rs_generator(n):
    """coefficients (highest degree first, leading 1 left out) of prod (x - a^i), i < n"""
    g = [1]
    for i in range(n):
        nxt = [0] * (len(g) + 1)
        for j, c in enumerate(g):
            nxt[j] ^= c
            nxt[j + 1] ^= _gf_mul(c, _EXP[i])
        g = nxt
    return g[1:]


def _rs_remainder(data, n):
    gen = _rs_generator(n)
    rem = [0] * n
    for b in data:
        factor = b ^ rem[0]
        rem = rem[1:] + [0]
        for j in range(n):
            rem[j] ^= _gf_mul(gen[j], factor)
    return rem


def _capacity(version):
    ec, groups = _BLOCKS_M[version]
    return sum(n * d for n, d in groups)


def _codewords(data, version):
    """the data bit stream, padded, split into blocks, interleaved with their EC codewords"""
    count_bits = 8 if version <= 9 else 16
    bits = []

    def put(value, n):
        for i in range(n - 1, -1, -1):
            bits.append((value >> i) & 1)

    put(0b0100, 4)
    put(len(data), count_bits)
    for b in data:
        put(b, 8)
    cap_bits = _capacity(version) * 8
    put(0, min(4, cap_bits - len(bits)))
    while len(bits) % 8:
        bits.append(0)
    stream = [int("".join(map(str, bits[i:i + 8])), 2) for i in range(0, len(bits), 8)]
    pad = (0xEC, 0x11)
    i = 0
    while len(stream) < _capacity(version):
        stream.append(pad[i % 2])
        i += 1

    ec, groups = _BLOCKS_M[version]
    blocks, pos = [], 0
    for n, d in groups:
        for _ in range(n):
            blocks.append(stream[pos:pos + d])
            pos += d
    ecs = [_rs_remainder(b, ec) for b in blocks]
    out = []
    for i in range(max(len(b) for b in blocks)):
        for b in blocks:
            if i < len(b):
                out.append(b[i])
    for i in range(ec):
        for e in ecs:
            out.append(e[i])
    return out


def _bch(value, poly, poly_bits):
    """value followed by the remainder of value * x^(poly_bits-1) divided by poly"""
    v = value << (poly_bits - 1)
    for i in range(v.bit_length() - poly_bits, -1, -1):
        if (v >> (i + poly_bits - 1)) & 1:
            v ^= poly << i
    return (value << (poly_bits - 1)) | v


class _Grid:
    def __init__(self, version):
        self.version = version
        self.size = 17 + 4 * version
        self.dark = [[0] * self.size for _ in range(self.size)]
        self.fixed = [[False] * self.size for _ in range(self.size)]

    def set(self, x, y, dark):
        self.dark[y][x] = 1 if dark else 0
        self.fixed[y][x] = True

    def functions(self):
        s = self.size
        for i in range(s):  # timing patterns
            self.set(6, i, i % 2 == 0)
            self.set(i, 6, i % 2 == 0)
        for cx, cy in ((3, 3), (s - 4, 3), (3, s - 4)):  # finders with their separators
            for dy in range(-4, 5):
                for dx in range(-4, 5):
                    x, y = cx + dx, cy + dy
                    if 0 <= x < s and 0 <= y < s:
                        d = max(abs(dx), abs(dy))
                        self.set(x, y, d not in (2, 4))
        pos = _ALIGN[self.version]
        for i, ay in enumerate(pos):
            for j, ax in enumerate(pos):
                if (i == 0 and j == 0) or (i == 0 and j == len(pos) - 1) or (i == len(pos) - 1 and j == 0):
                    continue  # the finder corners
                for dy in range(-2, 3):
                    for dx in range(-2, 3):
                        self.set(ax + dx, ay + dy, max(abs(dx), abs(dy)) != 1)
        self.format_bits(0)  # reserved now, written for real with the mask
        if self.version >= 7:
            bits = _bch(self.version, 0x1F25, 13)
            for i in range(18):
                b = (bits >> i) & 1
                a, c = s - 11 + i % 3, i // 3
                self.set(a, c, b)
                self.set(c, a, b)

    def format_bits(self, mask):
        s = self.size
        bits = _bch((_ECL_M_BITS << 3) | mask, 0x537, 11) ^ 0x5412
        bit = lambda i: (bits >> i) & 1
        for i in range(6):
            self.set(8, i, bit(i))
        self.set(8, 7, bit(6))
        self.set(8, 8, bit(7))
        self.set(7, 8, bit(8))
        for i in range(9, 15):
            self.set(14 - i, 8, bit(i))
        for i in range(8):
            self.set(s - 1 - i, 8, bit(i))
        for i in range(8, 15):
            self.set(8, s - 15 + i, bit(i))
        self.set(8, s - 8, 1)  # the dark module

    def place(self, codewords):
        s = self.size
        i, total = 0, len(codewords) * 8
        right = s - 1
        while right >= 1:
            if right == 6:
                right = 5
            upward = ((right + 1) & 2) == 0
            for vert in range(s):
                y = s - 1 - vert if upward else vert
                for x in (right, right - 1):
                    if self.fixed[y][x]:
                        continue
                    if i < total:
                        self.dark[y][x] = (codewords[i >> 3] >> (7 - (i & 7))) & 1
                    i += 1  # past the end: the remainder bits, 0
            right -= 2

    def masked(self, mask):
        fn = (
            lambda x, y: (x + y) % 2 == 0,
            lambda x, y: y % 2 == 0,
            lambda x, y: x % 3 == 0,
            lambda x, y: (x + y) % 3 == 0,
            lambda x, y: (x // 3 + y // 2) % 2 == 0,
            lambda x, y: x * y % 2 + x * y % 3 == 0,
            lambda x, y: (x * y % 2 + x * y % 3) % 2 == 0,
            lambda x, y: ((x + y) % 2 + x * y % 3) % 2 == 0,
        )[mask]
        g = _Grid(self.version)
        g.dark = [row[:] for row in self.dark]
        g.fixed = self.fixed
        for y in range(self.size):
            for x in range(self.size):
                if not self.fixed[y][x] and fn(x, y):
                    g.dark[y][x] ^= 1
        g.format_bits(mask)
        return g


def _penalty(m):
    s = len(m)
    score = 0
    lines = [row for row in m] + [[m[y][x] for y in range(s)] for x in range(s)]
    finder_a = [1, 0, 1, 1, 1, 0, 1, 0, 0, 0, 0]
    finder_b = finder_a[::-1]
    for line in lines:
        run, prev = 0, None
        for v in line:  # rule 1: runs of five or more
            if v == prev:
                run += 1
            else:
                if run >= 5:
                    score += run - 2
                run, prev = 1, v
        if run >= 5:
            score += run - 2
        for i in range(s - 10):  # rule 3: finder-like patterns
            seg = line[i:i + 11]
            if seg == finder_a or seg == finder_b:
                score += 40
    for y in range(s - 1):  # rule 2: 2x2 blocks
        for x in range(s - 1):
            v = m[y][x]
            if v == m[y][x + 1] == m[y + 1][x] == m[y + 1][x + 1]:
                score += 3
    dark = sum(map(sum, m))  # rule 4: balance
    k = abs(dark * 20 - s * s * 10) // (s * s)
    score += k * 10
    return score


def matrix(text):
    data = text.encode("utf-8") if isinstance(text, str) else bytes(text)
    for version in range(1, 11):
        count_bits = 8 if version <= 9 else 16
        if 4 + count_bits + 8 * len(data) <= _capacity(version) * 8:
            break
    else:
        raise ValueError("too long for a version 10 QR code: %d bytes" % len(data))
    g = _Grid(version)
    g.functions()
    g.place(_codewords(data, version))
    best = min((g.masked(k) for k in range(8)), key=lambda c: _penalty(c.dark))
    return best.dark


def svg(text, module=8, quiet=4):
    m = matrix(text)
    n = len(m) + 2 * quiet
    path = []
    for y, row in enumerate(m):
        x = 0
        while x < len(row):
            if row[x]:
                start = x
                while x < len(row) and row[x]:
                    x += 1
                path.append("M%d %dh%dv1h-%dz" % (start + quiet, y + quiet, x - start, x - start))
            else:
                x += 1
    return ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 %d %d" width="%d" height="%d" shape-rendering="crispEdges">'
            '<rect width="%d" height="%d" fill="#fff"/><path fill="#000" d="%s"/></svg>'
            % (n, n, n * module, n * module, n, n, "".join(path)))


if __name__ == "__main__":
    import sys
    sys.stdout.write(svg(sys.argv[1] if len(sys.argv) > 1 else "teslalinux") + "\n")
