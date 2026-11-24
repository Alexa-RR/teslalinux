"""teslalinux-release-update, part 1: a Teslalinux release on GitHub (docs/SYSTEM-UPDATES.md).

Which release is the newest stable one (GitHub's releases API, the repository the device was
installed from), its download (only the four files a release carries: the dist tarball, MANIFEST,
install.sh, SHA256SUMS; resumable) and its verification against the release's own SHA256SUMS.
Standard library only.
"""
import hashlib
import json
import os
import re
import time
import urllib.error
import urllib.request

API = os.environ.get("RELEASE_UPDATE_API", "https://api.github.com")
# where release assets are fetched: https://github.com/OWNER/REPO/releases/download/TAG/NAME. The
# URL is built here from the repository, the tag and a checked file name; the API's own asset URLs
# are not followed
DOWNLOAD_BASE = os.environ.get("RELEASE_UPDATE_DOWNLOAD_BASE", "https://github.com")
USER_AGENT = "teslalinux-release-update/1"
REPO_RE = re.compile(r"[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}")
# a stable release tag: vMAJOR.MINOR.PATCH (a tag with a pre-release suffix is not offered)
TAG_RE = re.compile(r"v(0|[1-9][0-9]{0,5})\.(0|[1-9][0-9]{0,5})\.(0|[1-9][0-9]{0,5})")
DIST_RE = re.compile(r"teslalinux-dist-[A-Za-z0-9._+-]{1,80}-arm64\.tar\.(zst|xz)")
SUMS, MANIFEST, INSTALLER = "SHA256SUMS", "MANIFEST", "install.sh"
MAX_NOTES = 20000
MAX_TARBALL = int(os.environ.get("RELEASE_UPDATE_MAX_TARBALL", str(1 << 30)))
SMALL_MAX = 4 << 20  # SHA256SUMS, MANIFEST and install.sh are far smaller


class ReleaseError(Exception):
    """kind: "network" (try again later), "release" (the release itself is incomplete or wrong)"""

    def __init__(self, kind, message):
        Exception.__init__(self, message)
        self.kind = kind


def semver(tag):
    """(major, minor, patch) of a stable tag (v0.5.0 or 0.5.0), else None."""
    if not isinstance(tag, str):
        return None
    m = TAG_RE.fullmatch(tag if tag.startswith("v") else "v" + tag)
    return tuple(int(x) for x in m.groups()) if m else None


def _request(url, headers=None):
    h = {"User-Agent": USER_AGENT}
    h.update(headers or {})
    return urllib.request.Request(url, headers=h)


def fetch_releases(repo, timeout=30):
    """The repository's releases (newest first, as GitHub lists them), at most 30."""
    if not REPO_RE.fullmatch(repo or ""):
        raise ReleaseError("release", "not a repository name: %r" % repo)
    url = "%s/repos/%s/releases?per_page=30" % (API, repo)
    req = _request(url, {"Accept": "application/vnd.github+json", "X-GitHub-Api-Version": "2022-11-28"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            data = json.loads(r.read(8 << 20).decode("utf-8"))
    except urllib.error.HTTPError as e:
        if e.code == 404:
            raise ReleaseError("release", "GitHub has no repository %s (or it is private)" % repo)
        if e.code in (403, 429):
            raise ReleaseError("network", "GitHub refused the check for now (HTTP %d, rate limit)" % e.code)
        raise ReleaseError("network", "GitHub answered HTTP %d" % e.code)
    except (urllib.error.URLError, OSError) as e:
        raise ReleaseError("network", "GitHub cannot be reached: %s" % getattr(e, "reason", e))
    except ValueError:
        raise ReleaseError("network", "GitHub's answer was not JSON")
    if not isinstance(data, list):
        raise ReleaseError("network", "GitHub's answer was not a release list")
    return data


def release_notes(body):
    """The release's text up to the install instructions the release workflow adds ("## Install")."""
    text = body if isinstance(body, str) else ""
    text = text.replace("\r\n", "\n")
    cut = re.search(r"(?m)^## Install\s*$", text)
    if cut:
        text = text[:cut.start()]
    return text.strip()[:MAX_NOTES]


def describe(rel):
    """A release as the updater keeps it, or None when it is not a complete, stable release:
    {tag, publishedAt, notes, tarball, assets: {name: size}, size}."""
    if not isinstance(rel, dict) or rel.get("draft") or rel.get("prerelease"):
        return None
    tag = rel.get("tag_name")
    if semver(tag) is None or not str(tag).startswith("v"):
        return None
    assets = {}
    for a in rel.get("assets") or []:
        if isinstance(a, dict) and isinstance(a.get("name"), str) and isinstance(a.get("size"), int):
            assets[a["name"]] = a["size"]
    tarballs = sorted(n for n in assets if DIST_RE.fullmatch(n))
    if len(tarballs) != 1 or not all(n in assets for n in (SUMS, MANIFEST, INSTALLER)):
        return None
    keep = {n: assets[n] for n in (SUMS, MANIFEST, INSTALLER, tarballs[0])}
    return {"tag": tag, "publishedAt": rel.get("published_at") if isinstance(rel.get("published_at"), str) else None,
            "notes": release_notes(rel.get("body")), "tarball": tarballs[0], "assets": keep,
            "size": sum(keep.values())}


def latest_stable(releases):
    """The newest stable, complete release by version number (not by date), or None."""
    best = None
    for rel in releases:
        d = describe(rel)
        if d and (best is None or semver(d["tag"]) > semver(best["tag"])):
            best = d
    return best


def newer(tag, installed_tag):
    a, b = semver(tag), semver(installed_tag)
    return a is not None and b is not None and a > b


# ---------------------------------------------------------------------------- download

def asset_url(repo, tag, name):
    if not REPO_RE.fullmatch(repo) or semver(tag) is None or not (name in (SUMS, MANIFEST, INSTALLER) or DIST_RE.fullmatch(name)):
        raise ReleaseError("release", "refusing to fetch %r" % name)
    return "%s/%s/releases/download/%s/%s" % (DOWNLOAD_BASE, repo, tag, name)


def download_file(url, out, size, progress=None, keep_going=None, deadline=None, timeout=30):
    """url into out, resuming out.part. size is the release's asset size: the file must have exactly
    that many bytes. progress(done) is called about once a second; keep_going() returning a reason
    stops the download (the part stays for the next try): ReleaseError("paused", reason)."""
    limit = MAX_TARBALL if DIST_RE.fullmatch(os.path.basename(out)) else SMALL_MAX
    if not isinstance(size, int) or not 0 < size <= limit:
        raise ReleaseError("release", "%s: size %r is not plausible" % (os.path.basename(out), size))
    part = out + ".part"
    done = os.path.getsize(part) if os.path.exists(part) else 0
    if done > size:
        os.unlink(part)
        done = 0
    if done < size:
        headers = {"Range": "bytes=%d-" % done} if done else {}
        try:
            r = urllib.request.urlopen(_request(url, headers), timeout=timeout)
        except urllib.error.HTTPError as e:
            if e.code == 416 and done:
                os.unlink(part)
                raise ReleaseError("network", "the server refused to resume %s; it starts again" % os.path.basename(out))
            raise ReleaseError("network", "%s: HTTP %d" % (os.path.basename(out), e.code))
        except (urllib.error.URLError, OSError) as e:
            raise ReleaseError("network", "%s: %s" % (os.path.basename(out), getattr(e, "reason", e)))
        with r:
            if done and r.status != 206:
                done = 0  # the server sent the whole file
            last = 0.0
            with open(part, "ab" if done else "wb") as f:
                while True:
                    try:
                        buf = r.read(1 << 16)
                    except OSError as e:
                        raise ReleaseError("network", "%s: %s" % (os.path.basename(out), e))
                    if not buf:
                        break
                    done += len(buf)
                    if done > size:
                        raise ReleaseError("release", "%s is larger than the release says (%d bytes)" % (os.path.basename(out), size))
                    f.write(buf)
                    now = time.monotonic()
                    if now - last >= 1.0:
                        last = now
                        if progress:
                            progress(done)
                        why = keep_going() if keep_going else None
                        if why:
                            raise ReleaseError("paused", why)
                        if deadline and time.time() > deadline:
                            raise ReleaseError("network", "the download took too long; it goes on later")
        if done != size:
            raise ReleaseError("network", "%s ended early (%d of %d bytes); it goes on later" % (os.path.basename(out), done, size))
    os.replace(part, out)
    if progress:
        progress(size)


# ---------------------------------------------------------------------------- verification

def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for buf in iter(lambda: f.read(1 << 20), b""):
            h.update(buf)
    return h.hexdigest()


def parse_sums(text):
    """{name: sha256} from sha256sum's output; ReleaseError for anything else."""
    out = {}
    for line in text.splitlines():
        if not line.strip():
            continue
        m = re.fullmatch(r"([0-9a-f]{64}) [ *]([A-Za-z0-9._+-]{1,120})", line.strip())
        if not m or m.group(2) in out:
            raise ReleaseError("release", "SHA256SUMS has a line that is not a checksum: %r" % line[:80])
        out[m.group(2)] = m.group(1)
    return out


def manifest_values(text):
    out = {}
    for line in text.splitlines():
        k, sep, v = line.partition("=")
        if sep and not k.startswith("#") and k not in out:
            out[k.strip()] = v.strip()
    return out


def verify_release(d, rel):
    """The downloaded release in directory d against its own SHA256SUMS: every file listed with the
    size the release gives, the dist's MANIFEST naming this tag, arm64 and this tarball. Returns the
    MANIFEST's values; ReleaseError("verify", why) otherwise."""
    try:
        with open(os.path.join(d, SUMS)) as f:
            sums = parse_sums(f.read(SMALL_MAX))
    except (OSError, UnicodeDecodeError) as e:
        raise ReleaseError("verify", "SHA256SUMS cannot be read: %s" % e)
    except ReleaseError as e:
        raise ReleaseError("verify", str(e))
    for name in (MANIFEST, INSTALLER, rel["tarball"]):
        p = os.path.join(d, name)
        if name not in sums:
            raise ReleaseError("verify", "SHA256SUMS does not list %s" % name)
        if not os.path.isfile(p) or os.path.getsize(p) != rel["assets"][name]:
            raise ReleaseError("verify", "%s is missing or not the size the release gives" % name)
        got = sha256_file(p)
        if got != sums[name]:
            raise ReleaseError("verify", "%s does not match the release's SHA256SUMS (sha256 %s, expected %s)" % (name, got[:16], sums[name][:16]))
    with open(os.path.join(d, MANIFEST), errors="replace") as f:
        man = manifest_values(f.read(SMALL_MAX))
    if man.get("release") != rel["tag"]:
        raise ReleaseError("verify", "the MANIFEST is of release %r, not %s" % (man.get("release"), rel["tag"]))
    if man.get("arch") != "arm64":
        raise ReleaseError("verify", "the dist is not for arm64 (arch=%r)" % man.get("arch"))
    if man.get("tarball") and man["tarball"] != rel["tarball"]:
        raise ReleaseError("verify", "the MANIFEST names another tarball (%s)" % man["tarball"])
    if not re.fullmatch(r"[A-Za-z0-9._+-]{1,64}", man.get("version") or ""):
        raise ReleaseError("verify", "the MANIFEST has no valid dist version")
    return man
