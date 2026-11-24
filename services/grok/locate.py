"""What a driver may give as a place, turned into what Waze needs, on the device.

resolve(text, near) returns one of
  {"latitude", "longitude"}            coordinates (Waze drives there)
  {"query"}                            text for Waze's own search near the car
  {"list": "home"|"work"|"saved"|"recent", "name"}   one of Waze's own places
each with "source" (what it was taken for) and "how" (what in it gave the place), or WazeError.

Read here: map links (maplinks.py), coordinates in decimal degrees (signs or N/S/E/W), degrees and
minutes, degrees minutes seconds, "lat lon" or "lat,lon" (lon first only when the numbers leave no
doubt), Plus Codes (a short one from the car's position, `near`), geohashes, geo: URIs and Israeli
grid (ITM, the New Israeli Grid) "E N" pairs. what3words needs its maker's paid service: refused.
"""
import math
import re

import maplinks
from carlink import WazeError

OLC = "23456789CFGHJMPQRVWX"
HOME = {"home", "my home", "go home", "בית", "הביתה", "הבית"}
WORK = {"work", "office", "my work", "the office", "עבודה", "לעבודה", "העבודה"}
LAST = {"my last destination", "last destination", "the last destination", "my last place", "recent", "last",
        "היעד האחרון", "יעד אחרון"}


def resolve(text, near=None):
    if not isinstance(text, str) or not text.strip():
        raise WazeError("give a place: an address or place name, coordinates, or a map link")
    t = text.strip()
    if len(t) > 4000:
        raise WazeError("the place is too long")
    low = t.lower()
    if maplinks.looks_like(t):
        return maplinks.parse(t)
    if re.match(r"^(///|w3w\.co/|https?://w3w\.co/|what3words\.com/)", low) or re.match(r"^[a-z֐-׿]+\.[a-z֐-׿]+\.[a-z֐-׿]+$", low):
        raise WazeError("what3words addresses are not supported: turning them into a place needs what3words' paid online "
                        "service. Give the address, coordinates or a map link instead.")
    for fn in (coordinates, plus_code, itm, bare_geohash):
        out = fn(t, near)
        if out:
            return out
    key = re.sub(r"[\s.!?]+$", "", low)
    if key in HOME:
        return {"list": "home", "name": "Home", "source": "Waze's Home", "how": "the word home"}
    if key in WORK:
        return {"list": "work", "name": "Work", "source": "Waze's Work", "how": "the word work"}
    if key in LAST:
        return {"list": "recent", "index": 0, "source": "Waze's recent places", "how": "the last destination"}
    return {"query": t[:300], "source": "an address or place name", "how": "Waze's own search near the car"}


# ------------------------------------------------------------------ coordinates

def _norm(t):
    t = t.upper()
    for a, b in (("º", "°"), ("˚", "°"), ("′", "'"), ("’", "'"), ("´", "'"), ("`", "'"), ("″", '"'), ("”", '"'),
                 ("“", '"'), ("''", '"'), ("DEG", "°")):
        t = t.replace(a, b)
    return t


def _component(tokens):
    """A coordinate from its tokens: (value, hemisphere letter or None)."""
    hemi = [x for x in tokens if x in "NSEW"]
    nums = [x for x in tokens if re.match(r"^-?\d", x)]
    if len(hemi) > 1 or not 1 <= len(nums) <= 3:
        return None
    vals = [float(x) for x in nums]
    neg = vals[0] < 0 or nums[0].startswith("-")
    d = abs(vals[0])
    if len(vals) > 1 and (vals[1] >= 60 or (len(vals) > 2 and vals[2] >= 60) or any(not v.is_integer() for v in vals[:-1])):
        return None
    v = d + (vals[1] / 60 if len(vals) > 1 else 0) + (vals[2] / 3600 if len(vals) > 2 else 0)
    h = hemi[0] if hemi else None
    if h in ("S", "W"):
        neg = True
    return (-v if neg else v), h


def coordinates(text, near=None):
    t = _norm(text)
    if re.search(r"[^\W\d_]", re.sub(r"[NSEW]", "", t)):
        return None  # other letters: not coordinates
    toks = re.findall(r"[NSEW]|-?\d+(?:\.\d+)?|[°'\",;]", t)
    rest = re.sub(r"[NSEW]|-?\d+(?:\.\d+)?|[°'\",;]|\s", "", t)
    if rest or not toks:
        return None
    toks = [x for x in toks if x not in "°'\""]
    if "," in toks or ";" in toks:
        i = next(k for k, x in enumerate(toks) if x in ",;")
        halves = [toks[:i], [x for x in toks[i + 1:] if x not in ",;"]]
    else:
        hemis = [k for k, x in enumerate(toks) if x in "NSEW"]
        nums = [k for k, x in enumerate(toks) if x not in "NSEW"]
        if len(hemis) == 2 and hemis[0] > nums[0]:        # 32 4 51 N 34 46 50 E
            halves = [toks[:hemis[0] + 1], toks[hemis[0] + 1:]]
        elif len(hemis) == 2:                              # N 32 4 51 E 34 46 50
            halves = [toks[:hemis[1]], toks[hemis[1]:]]
        elif not hemis and len(nums) in (2, 4, 6):
            halves = [toks[:len(nums) // 2], toks[len(nums) // 2:]]
        else:
            return None
    a, b = _component(halves[0]), _component(halves[1])
    if not a or not b:
        return None
    (va, ha), (vb, hb) = a, b
    if ha in ("E", "W") or hb in ("N", "S"):
        (va, ha), (vb, hb) = (vb, hb), (va, ha)
        how = "coordinates (longitude first, by their N/S/E/W)"
    elif not ha and not hb and abs(va) > 90 and abs(vb) <= 90:
        va, vb = vb, va
        how = "coordinates (longitude first: the first number is beyond 90)"
    else:
        how = "coordinates"
    if not (-90 <= va <= 90 and -180 <= vb <= 180):
        return None
    if all(abs(v) > 1000 for v in (va, vb)):
        return None
    n = len([x for x in halves[0] if x not in "NSEW"])
    form = {1: "decimal degrees", 2: "degrees and minutes", 3: "degrees, minutes and seconds"}[n]
    return {"latitude": round(va, 7), "longitude": round(vb, 7), "source": form, "how": how}


# ------------------------------------------------------------------ Plus Codes (Open Location Code)

def plus_code(text, near=None):
    m = re.match(r"^\s*([23456789CFGHJMPQRVWX]{2,8}\+[23456789CFGHJMPQRVWX]{0,3})(?:[\s,]+(.+))?\s*$", text.strip().upper())
    if not m:
        return None
    code, locality = m.group(1), m.group(2)
    lead = code.index("+")
    if lead == 8:
        lat, lon = olc_decode(code)
        return {"latitude": lat, "longitude": lon, "source": "Plus Code", "how": "the full code"}
    if lead < 8 and lead % 2 == 0:
        if near is None:
            raise WazeError("a short Plus Code needs the car's position (or its town, which this device cannot look up "
                            "offline); give the full code (8 characters before the +) instead")
        lat, lon = olc_recover(code, near[0], near[1])
        out = {"latitude": lat, "longitude": lon, "source": "Plus Code (short)", "how": "the code nearest the car"}
        if locality:
            out["how"] += " (the town after it was not looked up)"
        return out
    raise WazeError("%s is not a valid Plus Code" % code)


def olc_decode(code):
    """The centre of a full Plus Code's area: (lat, lon)."""
    c = code.replace("+", "").rstrip("0")
    if len(c) < 2 or len(c) % 2 and len(c) < 10:
        raise WazeError("%s is not a complete Plus Code" % code)
    lat, lon = -90.0, -180.0
    rlat = rlon = 20.0
    for k in range(0, min(len(c), 10), 2):
        rlat = rlon = 20.0 / (20 ** (k // 2))
        lat += OLC.index(c[k]) * rlat
        lon += OLC.index(c[k + 1]) * rlon
    for ch in c[10:]:
        rlat, rlon = rlat / 5, rlon / 4
        i = OLC.index(ch)
        lat += (i // 4) * rlat
        lon += (i % 4) * rlon
    return round(lat + rlat / 2, 7), round(lon + rlon / 2, 7)


def olc_recover(short, ref_lat, ref_lon):
    """A short Plus Code's place nearest the reference (Open Location Code's recoverNearest)."""
    pad = 8 - short.index("+")
    res = 20.0 ** (2 - pad / 2)
    half = res / 2
    lat, lon = olc_decode(olc_encode(ref_lat, ref_lon)[:pad] + short)
    if ref_lat + half < lat and lat - res >= -90:
        lat -= res
    elif ref_lat - half > lat and lat + res <= 90:
        lat += res
    if ref_lon + half < lon:
        lon -= res
    elif ref_lon - half > lon:
        lon += res
    return round(lat, 7), round(lon, 7)


def olc_encode(lat, lon):
    lat = min(max(lat, -90), 90 - 1e-9) + 90
    lon = ((lon + 180) % 360)
    out = ""
    res = 20.0
    for _ in range(5):
        dl, dn = int(lat // res), int(lon // res)
        out += OLC[dl] + OLC[dn]
        lat -= dl * res
        lon -= dn * res
        res /= 20
    return out[:8] + "+" + out[8:]


# ------------------------------------------------------------------ geohash, Israeli grid

# Israeli Transverse Mercator (EPSG:2039): GRS80, Israel 1993 datum; to WGS84 by the EPSG/PROJ
# Helmert parameters (position vector: metres, arc-seconds, ppm)
ITM = {"lat0": 31.7343936111111, "lon0": 35.2045169444444, "k0": 1.0000067, "E0": 219529.584, "N0": 626907.39}
GRS80 = (6378137.0, 1 / 298.257222101)
WGS84 = (6378137.0, 1 / 298.257223563)
TOWGS84 = (-24.0024, -17.1032, -17.8444, -0.33077, -1.85269, 1.66969, 5.4248)


def itm(text, near=None):
    m = re.match(r"^\s*(?:itm|ing|new israel(?:i)? grid|רשת ישראל החדשה)?[:\s]*(\d{6}(?:\.\d+)?)\s*[,/\s]\s*(\d{6}(?:\.\d+)?)\s*$",
                 text.strip(), re.I)
    if not m:
        return None
    a, b = float(m.group(1)), float(m.group(2))
    e_ok = lambda v: 100000 <= v <= 300000
    n_ok = lambda v: 350000 <= v <= 820000
    if e_ok(a) and n_ok(b):
        e, n, how = a, b, "an Israeli grid (ITM) E N pair"
    elif n_ok(a) and e_ok(b):
        e, n, how = b, a, "an Israeli grid (ITM) N E pair"
    else:
        return None
    lat, lon = itm_to_wgs84(e, n)
    return {"latitude": round(lat, 7), "longitude": round(lon, 7), "source": "Israeli grid (ITM)", "how": how}


def _tm_inverse(e, n, a, f, lat0, lon0, k0, e0, n0):
    es = f * (2 - f)
    ep2 = es / (1 - es)

    def arc(phi):
        return a * ((1 - es / 4 - 3 * es ** 2 / 64 - 5 * es ** 3 / 256) * phi - (3 * es / 8 + 3 * es ** 2 / 32 + 45 * es ** 3 / 1024) * math.sin(2 * phi)
                    + (15 * es ** 2 / 256 + 45 * es ** 3 / 1024) * math.sin(4 * phi) - (35 * es ** 3 / 3072) * math.sin(6 * phi))
    m = arc(math.radians(lat0)) + (n - n0) / k0
    mu = m / (a * (1 - es / 4 - 3 * es ** 2 / 64 - 5 * es ** 3 / 256))
    e1 = (1 - math.sqrt(1 - es)) / (1 + math.sqrt(1 - es))
    phi1 = (mu + (3 * e1 / 2 - 27 * e1 ** 3 / 32) * math.sin(2 * mu) + (21 * e1 ** 2 / 16 - 55 * e1 ** 4 / 32) * math.sin(4 * mu)
            + (151 * e1 ** 3 / 96) * math.sin(6 * mu) + (1097 * e1 ** 4 / 512) * math.sin(8 * mu))
    c1 = ep2 * math.cos(phi1) ** 2
    t1 = math.tan(phi1) ** 2
    n1 = a / math.sqrt(1 - es * math.sin(phi1) ** 2)
    r1 = a * (1 - es) / (1 - es * math.sin(phi1) ** 2) ** 1.5
    d = (e - e0) / (n1 * k0)
    lat = phi1 - (n1 * math.tan(phi1) / r1) * (d ** 2 / 2 - (5 + 3 * t1 + 10 * c1 - 4 * c1 ** 2 - 9 * ep2) * d ** 4 / 24
                                                 + (61 + 90 * t1 + 298 * c1 + 45 * t1 ** 2 - 252 * ep2 - 3 * c1 ** 2) * d ** 6 / 720)
    lon = math.radians(lon0) + (d - (1 + 2 * t1 + c1) * d ** 3 / 6
                                + (5 - 2 * c1 + 28 * t1 - 3 * c1 ** 2 + 8 * ep2 + 24 * t1 ** 2) * d ** 5 / 120) / math.cos(phi1)
    return lat, lon


def _to_ecef(lat, lon, a, f):
    es = f * (2 - f)
    nn = a / math.sqrt(1 - es * math.sin(lat) ** 2)
    return (nn * math.cos(lat) * math.cos(lon), nn * math.cos(lat) * math.sin(lon), nn * (1 - es) * math.sin(lat))


def _from_ecef(x, y, z, a, f):
    es = f * (2 - f)
    lon = math.atan2(y, x)
    p = math.hypot(x, y)
    lat = math.atan2(z, p * (1 - es))
    for _ in range(8):
        nn = a / math.sqrt(1 - es * math.sin(lat) ** 2)
        lat = math.atan2(z + es * nn * math.sin(lat), p)
    return lat, lon


def itm_to_wgs84(e, n):
    lat, lon = _tm_inverse(e, n, GRS80[0], GRS80[1], ITM["lat0"], ITM["lon0"], ITM["k0"], ITM["E0"], ITM["N0"])
    x, y, z = _to_ecef(lat, lon, *GRS80)
    tx, ty, tz, rx, ry, rz, s = TOWGS84
    rx, ry, rz = (math.radians(v / 3600) for v in (rx, ry, rz))
    k = 1 + s * 1e-6
    x2 = tx + k * (x - rz * y + ry * z)
    y2 = ty + k * (rz * x + y - rx * z)
    z2 = tz + k * (-ry * x + rx * y + z)
    lat, lon = _from_ecef(x2, y2, z2, *WGS84)
    return math.degrees(lat), math.degrees(lon)


def bare_geohash(text, near=None):
    t = text.strip().lower()
    if re.match(r"^(geohash[:\s]+)?[0-9bcdefghjkmnpqrstuvwxyz]{6,12}$", t) and re.search(r"\d", t) and re.search(r"[a-z]", t.split()[-1]):
        h = t.split()[-1].split(":")[-1]
        lat, lon = maplinks.geohash(h)
        return {"latitude": lat, "longitude": lon, "source": "geohash", "how": "the geohash cell's centre"}
    return None
