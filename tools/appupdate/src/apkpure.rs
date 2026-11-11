//! APKPure source (no account, no login): the public app-version API that EFF's apkeep uses.
//!
//! Endpoint and request headers from apkeep 1.1.0: src/consts.rs `APKPURE_VERSIONS_URL_FORMAT`
//! (https://api.pureapk.com/m/v3/cms/app_version?hl=en-US&package_name=) and
//! src/download_sources/apkpure.rs `http_headers` (x-cv, x-sv, x-abis, x-gp). apkeep finds the download URL
//! with a regex over the protobuf answer; here the answer is decoded field by field instead
//! (layout found on the live answer, 2026-11-09):
//!
//!   answer.1.7.2[]          one entry per (version, variant)
//!     .3.2                  app detail: 4 package, 5 versionCode (string), 6 versionName,
//!       .24                 asset: 3 sha1 (hex), 4 size, 8 type ("XAPK" | "APK"), 9 download URL
//!
//! One version has several variants (different XAPKs: other ABI/density sets). An XAPK is a ZIP of
//! APKs plus manifest.json; it is read as a remote ZIP with HTTP Range requests: the end-of-central-
//! directory and the central directory (a few KB) say which APKs a variant holds, and only the APKs
//! we install are fetched, each into NAME.part with resume, checked against its CRC-32.

use std::fs;
use std::io::Write;
use std::path::Path;
use std::time::{Duration, Instant};

use serde_json::{json, Value};

pub const VERSIONS_URL: &str = "https://api.pureapk.com/m/v3/cms/app_version?hl=en-US&package_name=";

#[derive(Debug, Clone)]
pub struct Variant {
    pub package: String,
    pub version_code: i64,
    pub version_name: String,
    pub kind: String,
    pub size: u64,
    pub sha1: String,
    pub url: String,
}

// ------------------------------------------------------------------ protobuf (wire format only)

fn varint(b: &[u8], i: &mut usize) -> Option<u64> {
    let (mut r, mut s) = (0u64, 0u32);
    loop {
        let x = *b.get(*i)?;
        *i += 1;
        if s >= 64 {
            return None;
        }
        r |= ((x & 0x7f) as u64) << s;
        s += 7;
        if x < 0x80 {
            return Some(r);
        }
    }
}

enum Field<'a> {
    Varint(u64),
    Bytes(&'a [u8]),
    Fixed,
}

/// Every (field number, value) of a message; None when b is not a well-formed message.
fn fields(b: &[u8]) -> Option<Vec<(u64, Field<'_>)>> {
    let mut out = Vec::new();
    let mut i = 0;
    while i < b.len() {
        let key = varint(b, &mut i)?;
        let (f, t) = (key >> 3, key & 7);
        if f == 0 {
            return None;
        }
        let v = match t {
            0 => Field::Varint(varint(b, &mut i)?),
            1 => {
                i = i.checked_add(8).filter(|&e| e <= b.len())?;
                Field::Fixed
            }
            5 => {
                i = i.checked_add(4).filter(|&e| e <= b.len())?;
                Field::Fixed
            }
            2 => {
                let n = varint(b, &mut i)? as usize;
                let end = i.checked_add(n).filter(|&e| e <= b.len())?;
                let v = &b[i..end];
                i = end;
                Field::Bytes(v)
            }
            _ => return None,
        };
        out.push((f, v));
    }
    Some(out)
}

fn sub<'a>(b: &'a [u8], f: u64) -> Vec<&'a [u8]> {
    fields(b)
        .unwrap_or_default()
        .into_iter()
        .filter_map(|(n, v)| match (n == f, v) {
            (true, Field::Bytes(x)) => Some(x),
            _ => None,
        })
        .collect()
}

fn text(b: &[u8], f: u64) -> Option<String> {
    sub(b, f).first().and_then(|x| std::str::from_utf8(x).ok()).map(str::to_string)
}

fn num(b: &[u8], f: u64) -> Option<u64> {
    fields(b)?.into_iter().find_map(|(n, v)| match (n == f, v) {
        (true, Field::Varint(x)) => Some(x),
        _ => None,
    })
}

/// The variants in a version-list answer, for pkg, highest versionCode first (answer order kept
/// within one version).
pub fn parse_versions(body: &[u8], pkg: &str) -> Result<Vec<Variant>, String> {
    let mut out = Vec::new();
    for l1 in sub(body, 1) {
        for l7 in sub(l1, 7) {
            for entry in sub(l7, 2) {
                for l3 in sub(entry, 3) {
                    for d in sub(l3, 2) {
                        let package = text(d, 4).unwrap_or_default();
                        let vc = text(d, 5).and_then(|s| s.trim().parse::<i64>().ok());
                        let (Some(vc), true) = (vc, package == pkg) else { continue };
                        for a in sub(d, 24) {
                            let url = text(a, 9).unwrap_or_default();
                            if !url.starts_with("https://") {
                                continue;
                            }
                            out.push(Variant {
                                package: package.clone(),
                                version_code: vc,
                                version_name: text(d, 6).unwrap_or_default(),
                                kind: text(a, 8).unwrap_or_default(),
                                size: num(a, 4).unwrap_or(0),
                                sha1: text(a, 3).unwrap_or_default(),
                                url,
                            });
                        }
                    }
                }
            }
        }
    }
    if out.is_empty() {
        return Err(format!("the APKPure answer lists no downloadable {} (format changed, or not on APKPure)", pkg));
    }
    out.sort_by(|a, b| b.version_code.cmp(&a.version_code)); // stable: answer order within a version
    Ok(out)
}

// ------------------------------------------------------------------ HTTP

pub struct Http {
    pub client: reqwest::Client,
    pub timeout: Duration,
}

impl Http {
    pub fn new(ua: &str, timeout: Duration) -> Result<Http, String> {
        let client = reqwest::Client::builder()
            .connect_timeout(Duration::from_secs(30))
            .user_agent(ua.to_string())
            .build()
            .map_err(|e| format!("http client: {}", e))?;
        Ok(Http { client, timeout })
    }

    /// The version list (a protobuf message) for pkg, with apkeep's request headers: x-cv (the API client
    /// version apkeep sends), x-sv (the device SDK level), x-abis (comma-separated ABIs), x-gp.
    pub async fn versions(&self, pkg: &str, sdk: u32, abis: &str) -> Result<Vec<u8>, (bool, String)> {
        let resp = self
            .client
            .get(format!("{}{}", VERSIONS_URL, pkg))
            .timeout(self.timeout)
            .header("x-cv", "3172501")
            .header("x-sv", sdk.to_string())
            .header("x-abis", abis)
            .header("x-gp", "1")
            .send()
            .await
            .map_err(|e| (true, format!("api.pureapk.com unreachable: {}", e)))?;
        let status = resp.status().as_u16();
        if status != 200 {
            return Err((true, format!("api.pureapk.com answered HTTP {}", status)));
        }
        let body = resp.bytes().await.map_err(|e| (true, format!("reading the answer: {}", e)))?;
        if body.len() > 16 << 20 {
            return Err((false, "version list over 16 MB".into()));
        }
        Ok(body.to_vec())
    }

    /// bytes [start, start+len) of url; Err unless the server honours the range (206 + matching Content-Range).
    pub async fn range(&self, url: &str, start: u64, len: u64) -> Result<(Vec<u8>, u64), String> {
        let resp = self
            .client
            .get(url)
            .timeout(self.timeout)
            .header("Range", format!("bytes={}-{}", start, start + len - 1))
            .send()
            .await
            .map_err(|e| format!("download server: {}", e))?;
        if resp.status().as_u16() != 206 {
            return Err(format!("download server answered HTTP {} to a range request", resp.status().as_u16()));
        }
        let total = content_range_total(&resp, start)?;
        let body = resp.bytes().await.map_err(|e| format!("download server: {}", e))?;
        if body.len() as u64 != len.min(total - start) {
            return Err(format!("range answer has {} bytes, expected {}", body.len(), len));
        }
        Ok((body.to_vec(), total))
    }

    /// The last `len` bytes (a suffix range) and the file size.
    pub async fn tail(&self, url: &str, len: u64) -> Result<(Vec<u8>, u64), String> {
        let resp = self
            .client
            .get(url)
            .timeout(self.timeout)
            .header("Range", format!("bytes=-{}", len))
            .send()
            .await
            .map_err(|e| format!("download server: {}", e))?;
        if resp.status().as_u16() != 206 {
            return Err(format!("download server answered HTTP {} to a range request (no partial downloads)", resp.status().as_u16()));
        }
        let cr = resp.headers().get("content-range").and_then(|v| v.to_str().ok()).unwrap_or("").to_string();
        let total = cr.rsplit('/').next().and_then(|s| s.parse::<u64>().ok()).ok_or(format!("no file size in Content-Range '{}'", cr))?;
        let body = resp.bytes().await.map_err(|e| format!("download server: {}", e))?;
        Ok((body.to_vec(), total))
    }
}

fn content_range_total(resp: &reqwest::Response, start: u64) -> Result<u64, String> {
    let cr = resp.headers().get("content-range").and_then(|v| v.to_str().ok()).unwrap_or("").to_string();
    let s = cr.strip_prefix("bytes ").and_then(|r| r.split('-').next()).and_then(|s| s.parse::<u64>().ok());
    let total = cr.rsplit('/').next().and_then(|s| s.parse::<u64>().ok());
    match (s, total) {
        (Some(s), Some(t)) if s == start => Ok(t),
        _ => Err(format!("unexpected Content-Range '{}' for a range at {}", cr, start)),
    }
}

// ------------------------------------------------------------------ remote ZIP

#[derive(Debug, Clone)]
pub struct ZipEntry {
    pub name: String,
    pub method: u16,
    pub crc: u32,
    pub csize: u64,
    pub usize: u64,
    pub lho: u64,
}

fn u16le(b: &[u8], o: usize) -> u16 {
    u16::from_le_bytes([b[o], b[o + 1]])
}
fn u32le(b: &[u8], o: usize) -> u32 {
    u32::from_le_bytes([b[o], b[o + 1], b[o + 2], b[o + 3]])
}
fn u64le(b: &[u8], o: usize) -> u64 {
    let mut a = [0u8; 8];
    a.copy_from_slice(&b[o..o + 8]);
    u64::from_le_bytes(a)
}

/// Central directory of a remote ZIP: two range requests (the tail with the EOCD, then the directory).
pub async fn list_zip(http: &Http, url: &str) -> Result<(Vec<ZipEntry>, u64), String> {
    let (tail, size) = http.tail(url, 65536 + 22).await?;
    let pos = (0..tail.len().saturating_sub(21)).rev().find(|&i| tail[i..i + 4] == [0x50, 0x4b, 0x05, 0x06])
        .ok_or("no ZIP end of central directory (not a ZIP file)")?;
    let mut cd_size = u32le(&tail, pos + 12) as u64;
    let mut cd_off = u32le(&tail, pos + 16) as u64;
    let mut count = u16le(&tail, pos + 10) as u64;
    if cd_off == 0xFFFF_FFFF || cd_size == 0xFFFF_FFFF || count == 0xFFFF {
        // ZIP64: the locator sits right before the EOCD and points at the ZIP64 EOCD record
        let loc = pos.checked_sub(20).filter(|&l| tail[l..l + 4] == [0x50, 0x4b, 0x06, 0x07]).ok_or("ZIP64 locator missing")?;
        let rec_off = u64le(&tail, loc + 8);
        let (rec, _) = http.range(url, rec_off, 56).await?;
        if rec[0..4] != [0x50, 0x4b, 0x06, 0x06] {
            return Err("bad ZIP64 end of central directory".into());
        }
        count = u64le(&rec, 32);
        cd_size = u64le(&rec, 40);
        cd_off = u64le(&rec, 48);
    }
    if cd_size > 8 << 20 || cd_off + cd_size > size {
        return Err("implausible ZIP central directory".into());
    }
    let (cd, _) = http.range(url, cd_off, cd_size).await?;
    let mut out = Vec::new();
    let mut i = 0usize;
    while out.len() < count as usize {
        if i + 46 > cd.len() || cd[i..i + 4] != [0x50, 0x4b, 0x01, 0x02] {
            return Err("truncated ZIP central directory".into());
        }
        let (nl, xl, cl) = (u16le(&cd, i + 28) as usize, u16le(&cd, i + 30) as usize, u16le(&cd, i + 32) as usize);
        let name = String::from_utf8_lossy(&cd[i + 46..i + 46 + nl]).to_string();
        let mut e = ZipEntry {
            name,
            method: u16le(&cd, i + 10),
            crc: u32le(&cd, i + 16),
            csize: u32le(&cd, i + 20) as u64,
            usize: u32le(&cd, i + 24) as u64,
            lho: u32le(&cd, i + 42) as u64,
        };
        // ZIP64 extra field (0x0001): the 0xFFFFFFFF fields, in order usize, csize, lho
        let mut x = i + 46 + nl;
        let xe = x + xl;
        while x + 4 <= xe {
            let (id, len) = (u16le(&cd, x), u16le(&cd, x + 2) as usize);
            if id == 1 {
                let mut p = x + 4;
                if e.usize == 0xFFFF_FFFF { e.usize = u64le(&cd, p); p += 8; }
                if e.csize == 0xFFFF_FFFF { e.csize = u64le(&cd, p); p += 8; }
                if e.lho == 0xFFFF_FFFF { e.lho = u64le(&cd, p); }
            }
            x += 4 + len;
        }
        out.push(e);
        i += 46 + nl + xl + cl;
    }
    Ok((out, size))
}

/// Maps an XAPK member to the name install-app.sh expects: the base APK (`<pkg>.apk` or `base.apk`)
/// -> base.apk, `config.X.apk` / `split_config.X.apk` -> split_config.X.apk; anything else None.
pub fn install_name(member: &str, pkg: &str) -> Option<String> {
    let m = member.rsplit('/').next().unwrap_or(member);
    if m == format!("{}.apk", pkg) || m == "base.apk" {
        return Some("base.apk".into());
    }
    let q = m.strip_prefix("split_").unwrap_or(m);
    if q.starts_with("config.") && q.ends_with(".apk") {
        return Some(format!("split_{}", q));
    }
    None
}

pub struct Limits {
    pub max_file: u64,
    pub deadline: Instant,
    pub stall: Duration,
}

/// Fetch one member of the remote ZIP into out/name (via name.part with Range resume), inflating
/// deflated members, and check its CRC-32. A complete out/name from an earlier run is kept.
pub async fn fetch_member(http: &Http, url: &str, e: &ZipEntry, out: &Path, name: &str, lim: &Limits) -> Result<u64, String> {
    let dst = out.join(name);
    if fs::metadata(&dst).map(|m| m.len() == e.usize).unwrap_or(false) {
        return Ok(0);
    }
    if e.usize > lim.max_file || e.csize > lim.max_file {
        return Err(format!("{}: {} bytes is over the per-file limit {}", name, e.usize, lim.max_file));
    }
    if e.method != 0 && e.method != 8 {
        return Err(format!("{}: ZIP method {} (only stored and deflated are read)", name, e.method));
    }
    // the local header's name and extra lengths can differ from the central directory's
    let (lh, _) = http.range(url, e.lho, 30).await?;
    if lh[0..4] != [0x50, 0x4b, 0x03, 0x04] {
        return Err(format!("{}: no local file header at {}", name, e.lho));
    }
    let data_off = e.lho + 30 + u16le(&lh, 26) as u64 + u16le(&lh, 28) as u64;
    // compressed bytes go to .part (resumable); a stored member is the APK itself
    let part = out.join(format!("{}.part", name));
    let mut have = fs::metadata(&part).map(|m| m.len()).unwrap_or(0);
    if have > e.csize {
        let _ = fs::remove_file(&part);
        have = 0;
    }
    let mut fetched = 0u64;
    if have < e.csize {
        let left = lim.deadline.saturating_duration_since(Instant::now());
        if left.is_zero() {
            return Err(format!("{}: download deadline reached", name));
        }
        let mut resp = http
            .client
            .get(url)
            .header("Range", format!("bytes={}-{}", data_off + have, data_off + e.csize - 1))
            .send()
            .await
            .map_err(|er| format!("{}: {}", name, er))?;
        if resp.status().as_u16() != 206 {
            return Err(format!("{}: HTTP {} to a range request", name, resp.status().as_u16()));
        }
        content_range_total(&resp, data_off + have).map_err(|m| format!("{}: {}", name, m))?;
        let mut f = fs::OpenOptions::new().create(true).append(true).open(&part).map_err(|er| format!("{}: {}", name, er))?;
        loop {
            let left = lim.deadline.saturating_duration_since(Instant::now());
            if left.is_zero() {
                f.sync_all().ok();
                return Err(format!("{}: deadline reached at {} of {} bytes (resumes next time)", name, have, e.csize));
            }
            let chunk = tokio::time::timeout(left.min(lim.stall), resp.chunk()).await;
            let chunk = match chunk {
                Err(_) => {
                    f.sync_all().ok();
                    let why = if Instant::now() >= lim.deadline { "deadline reached" } else { "stalled" };
                    return Err(format!("{}: {} at {} of {} bytes (resumes next time)", name, why, have, e.csize));
                }
                Ok(Err(er)) => {
                    f.sync_all().ok();
                    return Err(format!("{}: {} at {} of {} bytes (resumes next time)", name, er, have, e.csize));
                }
                Ok(Ok(c)) => c,
            };
            let Some(chunk) = chunk else { break };
            have += chunk.len() as u64;
            fetched += chunk.len() as u64;
            if have > e.csize {
                drop(f);
                let _ = fs::remove_file(&part);
                return Err(format!("{}: server sent more than the member's size", name));
            }
            f.write_all(&chunk).map_err(|er| format!("{}: {}", name, er))?;
        }
        f.sync_all().map_err(|er| format!("{}: {}", name, er))?;
        if have != e.csize {
            return Err(format!("{}: got {} of {} bytes (resumes next time)", name, have, e.csize));
        }
    }
    // inflate (method 8) or take as is (0), then the CRC-32 of the result
    let tmp = out.join(format!("{}.tmp", name));
    let mut crc = flate2::Crc::new();
    {
        let src = fs::File::open(&part).map_err(|er| format!("{}: {}", name, er))?;
        let mut w = fs::File::create(&tmp).map_err(|er| format!("{}: {}", name, er))?;
        let mut reader: Box<dyn std::io::Read> = if e.method == 8 {
            Box::new(flate2::read::DeflateDecoder::new(std::io::BufReader::new(src)))
        } else {
            Box::new(std::io::BufReader::new(src))
        };
        let mut buf = vec![0u8; 1 << 20];
        let mut n_total = 0u64;
        loop {
            let n = reader.read(&mut buf).map_err(|er| format!("{}: {}", name, er))?;
            if n == 0 {
                break;
            }
            n_total += n as u64;
            if n_total > e.usize {
                break;
            }
            crc.update(&buf[..n]);
            w.write_all(&buf[..n]).map_err(|er| format!("{}: {}", name, er))?;
        }
        w.sync_all().map_err(|er| format!("{}: {}", name, er))?;
        if n_total != e.usize {
            drop(w);
            let _ = fs::remove_file(&tmp);
            let _ = fs::remove_file(&part);
            return Err(format!("{}: {} bytes after extraction, the directory says {}; fetched again next time", name, n_total, e.usize));
        }
    }
    if crc.sum() != e.crc {
        let _ = fs::remove_file(&tmp);
        let _ = fs::remove_file(&part);
        return Err(format!("{}: CRC-32 mismatch (corrupt download); fetched again next time", name));
    }
    fs::rename(&tmp, &dst).map_err(|er| format!("{}: {}", name, er))?;
    let _ = fs::remove_file(&part);
    Ok(fetched)
}

use std::io::Read;

/// One variant's members in install names: (install name, entry).
pub fn members(entries: &[ZipEntry], pkg: &str) -> Vec<(String, ZipEntry)> {
    entries.iter().filter_map(|e| install_name(&e.name, pkg).map(|n| (n, e.clone()))).collect()
}

pub fn variant_json(v: &Variant) -> Value {
    json!({"package": v.package, "versionCode": v.version_code, "versionName": v.version_name, "type": v.kind, "size": v.size, "sha1": v.sha1})
}

#[cfg(test)]
mod tests {
    use super::*;

    fn put_varint(o: &mut Vec<u8>, mut n: u64) {
        loop {
            let b = (n & 0x7f) as u8;
            n >>= 7;
            if n == 0 { o.push(b); break } else { o.push(b | 0x80) }
        }
    }

    fn pb_bytes(f: u64, v: &[u8]) -> Vec<u8> {
        let mut o = Vec::new();
        put_varint(&mut o, f << 3 | 2);
        put_varint(&mut o, v.len() as u64);
        o.extend_from_slice(v);
        o
    }

    #[test]
    fn parses_the_version_list_layout() {
        let mut asset = pb_bytes(3, b"049bcf64");
        asset.extend([4 << 3, 0x2a]); // size 42
        asset.extend(pb_bytes(8, b"XAPK"));
        asset.extend(pb_bytes(9, b"https://download.example/x"));
        let mut d = pb_bytes(4, b"com.waze");
        d.extend(pb_bytes(5, b"1030744"));
        d.extend(pb_bytes(6, b"5.26.5.0"));
        d.extend(pb_bytes(24, &asset));
        let mut d2 = pb_bytes(4, b"com.waze");
        d2.extend(pb_bytes(5, b"1030736"));
        d2.extend(pb_bytes(24, &asset));
        let entry = |d: &[u8]| pb_bytes(2, &[pb_bytes(1, b"version_list"), pb_bytes(3, &pb_bytes(2, d))].concat());
        let body = pb_bytes(1, &pb_bytes(7, &[entry(&d2), entry(&d)].concat()));
        let v = parse_versions(&body, "com.waze").unwrap();
        assert_eq!(v.len(), 2);
        assert_eq!((v[0].version_code, v[0].version_name.as_str(), v[0].size, v[0].kind.as_str()), (1030744, "5.26.5.0", 42, "XAPK"));
        assert!(parse_versions(&body, "com.other").is_err());
        assert!(parse_versions(b"\xff\xff", "com.waze").is_err());
    }

    #[test]
    fn xapk_member_names() {
        assert_eq!(install_name("com.waze.apk", "com.waze").as_deref(), Some("base.apk"));
        assert_eq!(install_name("config.arm64_v8a.apk", "com.waze").as_deref(), Some("split_config.arm64_v8a.apk"));
        assert_eq!(install_name("split_config.xhdpi.apk", "com.waze").as_deref(), Some("split_config.xhdpi.apk"));
        assert_eq!(install_name("manifest.json", "com.waze"), None);
        assert_eq!(install_name("icon.png", "com.waze"), None);
        assert_eq!(install_name("Android/obb/com.waze/main.obb", "com.waze"), None);
    }
}
