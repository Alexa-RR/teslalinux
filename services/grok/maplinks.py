"""Map links, read on the device: the place a Waze, Google Maps, Apple Maps, OpenStreetMap, Bing,
HERE or geo: link points at. A link that carries coordinates is read as it is; one that names a
place without coordinates gives its name or address for Waze's own search; a short link that hides
its target (maps.app.goo.gl, goo.gl/maps) is followed: HEAD requests, redirects only, to known map
hosts only, 5 s, at most 5 hops, no cookies. Nothing else is fetched, and nothing from the link
beyond the place is kept or logged.

parse(url) -> {"latitude", "longitude"} or {"query"}, with "name" (a label the link gives) and
"source" (which kind of link) and "how" (what in it gave the place).
"""
import re
import urllib.error
import urllib.parse
import urllib.request

from carlink import WazeError

SHORT_HOSTS = ("maps.app.goo.gl", "goo.gl", "g.co", "maps.apple")
FOLLOW_HOSTS = SHORT_HOSTS
GOOGLE = re.compile(r"(^|\.)google\.[a-z.]+$|^maps\.google\.[a-z.]+$")
FOLLOW_S, FOLLOW_HOPS = 5.0, 5
NUM = r"[-+]?\d{1,3}(?:\.\d+)?"
PAIR = re.compile(r"^\s*(%s)\s*,\s*(%s)\s*$" % (NUM, NUM))
GEOHASH = "0123456789bcdefghjkmnpqrstuvwxyz"


def looks_like(text):
    if not isinstance(text, str):
        return False
    t = text.strip().lower()
    return bool(re.match(r"^(https?://|geo:|waze://|maps://|comgooglemaps://)", t)) or bool(
        re.match(r"^(www\.)?(waze\.com|maps\.google\.|google\.[a-z.]+/maps|maps\.app\.goo\.gl|goo\.gl/maps|maps\.apple|"
                 r"openstreetmap\.org|osm\.org|bing\.com/maps|share\.here\.com|wego\.here\.com)", t))


def pair(v):
    """(lat, lon) from "LAT,LON", or None."""
    m = PAIR.match(urllib.parse.unquote_plus(v or ""))
    if not m:
        return None
    lat, lon = float(m.group(1)), float(m.group(2))
    return (lat, lon) if -90 <= lat <= 90 and -180 <= lon <= 180 else None


def geohash(h):
    """The centre of a geohash cell: (lat, lon)."""
    lat, lon = [-90.0, 90.0], [-180.0, 180.0]
    even = True
    for ch in h.lower():
        i = GEOHASH.find(ch)
        if i < 0:
            raise WazeError("%r is not a geohash" % h)
        for bit in (16, 8, 4, 2, 1):
            rng = lon if even else lat
            mid = (rng[0] + rng[1]) / 2
            rng[0 if i & bit else 1] = mid
            even = not even
    digits = max(1, min(7, len(h) - 2))
    return round((lat[0] + lat[1]) / 2, digits), round((lon[0] + lon[1]) / 2, digits)


def at(lat, lon, source, how, name=None):
    out = {"latitude": lat, "longitude": lon, "source": source, "how": how}
    if name:
        out["name"] = name
    return out


def named(text, source, how):
    text = re.sub(r"\s+", " ", urllib.parse.unquote_plus(text or "")).strip()
    if not text:
        raise WazeError("the %s names no place" % source)
    return {"query": text[:300], "source": source, "how": how}


def first(q, *keys):
    for k in keys:
        if q.get(k) and q[k][0].strip():
            return k, q[k][0].strip()
    return None, None


def waze(u, q):
    m = re.match(r"^/ul/h([^/]+)/?$", u.path or "", re.I)
    if m:
        lat, lon = geohash(m.group(1))
        return at(lat, lon, "Waze link", "the location code after /ul/h")
    k, v = first(q, "ll", "latlng")
    if v and pair(v):
        return at(*pair(v), "Waze link", "its %s= coordinates" % k)
    k, v = first(q, "to")
    if v and v.startswith("ll.") and pair(v[3:]):
        return at(*pair(v[3:]), "Waze link", "its to=ll. coordinates")
    k, v = first(q, "q")
    if v:
        return named(v, "Waze link", "its q= text")
    if any(q.get(k) for k in ("place", "venue_id", "favorite", "to")):
        raise WazeError("this Waze link names the place by Waze's own id, which only Waze's servers can turn into a place; "
                        "share the place's location link (waze.com/ul/h...) or its address instead")
    raise WazeError("this Waze link has no place in it (no ll=, q= or location code)")


def google(u, q):
    path = urllib.parse.unquote(u.path or "")
    m = re.search(r"!3d(%s)!4d(%s)" % (NUM, NUM), path + "?" + u.query)   # the place itself
    name = None
    pm = re.search(r"/place/([^/@]+)", path)
    if pm:
        name = urllib.parse.unquote_plus(pm.group(1)).strip() or None
    if m:
        return at(float(m.group(1)), float(m.group(2)), "Google Maps link", "the place's !3d/!4d coordinates", name)
    for keys in (("destination", "daddr"), ("query", "q"), ("ll", "center", "sll")):
        k, v = first(q, *keys)
        if v:
            if pair(v):
                return at(*pair(v), "Google Maps link", "its %s= coordinates" % k, name)
            if k not in ("ll", "center", "sll"):
                return named(v, "Google Maps link", "its %s= text" % k)
    dm = re.search(r"/maps/dir/(?:[^/]*/)*?([^/@]+)/(?:@|data=|$)", path + "/")
    if dm:
        segs = [s for s in path.split("/maps/dir/", 1)[1].split("/") if s and not s.startswith(("@", "data="))]
        last = segs[-1] if segs else ""
        return at(*pair(last), "Google Maps link", "the route's last stop") if pair(last) else named(last, "Google Maps link", "the route's last stop")
    sm = re.search(r"/maps/search/([^/@]+)", path)
    if sm:
        v = sm.group(1)
        return at(*pair(v), "Google Maps link", "its search coordinates") if pair(v) else named(v, "Google Maps link", "its search text")
    if name:
        return named(name, "Google Maps link", "the place's name")
    am = re.search(r"/@(%s),(%s)" % (NUM, NUM), path)
    if am:
        return at(float(am.group(1)), float(am.group(2)), "Google Maps link", "the map's centre (@lat,lon)")
    raise WazeError("this Google Maps link has no place in it")


def apple(u, q):
    name = first(q, "q", "name")[1]
    for k in ("coordinate", "ll", "daddr"):
        v = first(q, k)[1]
        if v and pair(v):
            return at(*pair(v), "Apple Maps link", "its %s= coordinates" % k, name if k != "daddr" else None)
    for k in ("daddr", "address", "q"):
        v = first(q, k)[1]
        if v:
            return named(v, "Apple Maps link", "its %s= text" % k)
    v = first(q, "sll")[1]
    if v and pair(v):
        return at(*pair(v), "Apple Maps link", "its sll= search location")
    raise WazeError("this Apple Maps link has no place in it")


def osm(u, q):
    if q.get("mlat") and q.get("mlon"):
        p = pair(q["mlat"][0] + "," + q["mlon"][0])
        if p:
            return at(*p, "OpenStreetMap link", "its marker (mlat/mlon)")
    m = re.search(r"map=\d+(?:\.\d+)?/(%s)/(%s)" % (NUM, NUM), u.fragment or "")
    if m:
        return at(float(m.group(1)), float(m.group(2)), "OpenStreetMap link", "the map's centre (#map=)")
    v = first(q, "query")[1]
    if v:
        return at(*pair(v), "OpenStreetMap link", "its query coordinates") if pair(v) else named(v, "OpenStreetMap link", "its query")
    raise WazeError("this OpenStreetMap link has no place in it")


def bing(u, q):
    sp = first(q, "sp")[1]
    m = re.search(r"point\.(%s)_(%s)" % (NUM, NUM), sp or "")
    if m:
        return at(float(m.group(1)), float(m.group(2)), "Bing Maps link", "its sp=point coordinates")
    v = first(q, "where1", "q")[1]
    if v:
        return at(*pair(v), "Bing Maps link", "its where1= coordinates") if pair(v) else named(v, "Bing Maps link", "its where1= text")
    v = first(q, "cp")[1]
    if v and pair(v.replace("~", ",")):
        return at(*pair(v.replace("~", ",")), "Bing Maps link", "the map's centre (cp=)")
    raise WazeError("this Bing Maps link has no place in it")


def here(u, q):
    m = re.match(r"^/l/(%s),(%s)(?:,([^/?]*))?" % (NUM, NUM), u.path or "")
    if m:
        return at(float(m.group(1)), float(m.group(2)), "HERE link", "its /l/ coordinates", urllib.parse.unquote_plus(m.group(3) or "") or None)
    v = first(q, "map")[1]
    if v and pair(",".join(v.split(",")[:2])):
        return at(*pair(",".join(v.split(",")[:2])), "HERE link", "the map's centre (map=)")
    raise WazeError("this HERE link has no place in it")


def geo_uri(text):
    m = re.match(r"^geo:(%s),(%s)(?:,[-\d.]+)?(?:;[^?]*)?(?:\?(.*))?$" % (NUM, NUM), text.strip(), re.I)
    if not m:
        raise WazeError("this geo: URI is not geo:LAT,LON[?q=...]")
    lat, lon = float(m.group(1)), float(m.group(2))
    q = urllib.parse.parse_qs(m.group(3) or "")
    v = first(q, "q")[1]
    if v:
        lm = re.match(r"^\s*(%s)\s*,\s*(%s)\s*(?:\((.*)\))?\s*$" % (NUM, NUM), v)
        if lm:
            return at(float(lm.group(1)), float(lm.group(2)), "geo: URI", "its q= coordinates", lm.group(3))
        if lat == 0 and lon == 0:
            return named(v, "geo: URI", "its q= text")
        return at(lat, lon, "geo: URI", "its coordinates", v)
    return at(lat, lon, "geo: URI", "its coordinates")


def follow(url, opener=None):
    """The URL a short map link redirects to (HEAD, redirects only, known hosts, 5 s, 5 hops)."""
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, *a, **k):
            return None
    op = opener or urllib.request.build_opener(NoRedirect)
    for _ in range(FOLLOW_HOPS):
        u = urllib.parse.urlsplit(url)
        host = (u.hostname or "").lower()
        if not (host in FOLLOW_HOSTS or GOOGLE.search(host)):
            raise WazeError("the short link leads outside the known map sites; give the place another way")
        try:
            r = op.open(urllib.request.Request(url, method="HEAD", headers={"User-Agent": "teslalinux"}), timeout=FOLLOW_S)
            r.close()
            return url  # no redirect: this is the target
        except urllib.error.HTTPError as e:
            loc = e.headers.get("Location") if e.code in (301, 302, 303, 307, 308) else None
            if not loc:
                raise WazeError("the short link did not lead to a place (HTTP %d)" % e.code)
            url = urllib.parse.urljoin(url, loc)
            nu = urllib.parse.urlsplit(url)
            if (nu.hostname or "").startswith("consent.google."):
                cont = urllib.parse.parse_qs(nu.query).get("continue")
                if cont:
                    return cont[0]
            if (nu.hostname or "").lower() not in SHORT_HOSTS:
                return url  # a full map link now: read, not fetched
        except (OSError, ValueError) as e:
            raise WazeError("the short link could not be followed (%s); give the place another way" % e)
    raise WazeError("the short link redirects too many times")


def parse(link, opener=None):
    if not isinstance(link, str) or not link.strip() or len(link) > 4000:
        raise WazeError("that is not a map link")
    raw = link.strip()
    low = raw.lower()
    if low.startswith("geo:"):
        return geo_uri(raw)
    if low.startswith("waze://"):
        u = urllib.parse.urlsplit(raw)
        return waze(u, urllib.parse.parse_qs(u.query))
    if low.startswith(("maps://", "comgooglemaps://")):
        u = urllib.parse.urlsplit(raw)
        q = urllib.parse.parse_qs(u.query)
        return apple(u, q) if low.startswith("maps://") else google(u, q)
    u = urllib.parse.urlsplit(raw if "://" in raw else "https://" + raw)
    host = (u.hostname or "").lower()
    if host in ("maps.app.goo.gl", "g.co") or host == "goo.gl" and (u.path or "").startswith("/maps") or host == "maps.apple":
        out = parse(follow(u.geturl(), opener), opener)
        out["how"] += " (after following the short link)"
        return out
    q = urllib.parse.parse_qs(u.query)
    if host.endswith("waze.com"):
        return waze(u, q)
    if GOOGLE.search(host) and ("/maps" in (u.path or "") or host.startswith("maps.")):
        return google(u, q)
    if host in ("maps.apple.com", "beta.maps.apple.com"):
        return apple(u, q)
    if host.endswith(("openstreetmap.org", "osm.org")):
        return osm(u, q)
    if host.endswith("bing.com") and (u.path or "").startswith("/maps"):
        return bing(u, q)
    if host.endswith("here.com"):
        return here(u, q)
    raise WazeError("that link is not one this device reads (Waze, Google Maps, Apple Maps, OpenStreetMap, Bing, HERE, geo:)")
