//! tlplay: the download side of teslalinux-appupdate (docs/APP-UPDATES.md). Two sources, no account:
//!
//! --source apkpure (default): APKPure's public app API as EFF's apkeep uses it (src/apkpure.rs): the
//!   version list, then only our APKs out of the variant's XAPK through HTTP Range requests.
//! --source play: Google Play with an anonymous login the way Aurora Store does it: POST the device
//!   profile as flat JSON to a token dispenser (default https://auroraoss.com/api/auth, AuroraStore
//!   app/src/main/java/com/aurora/Constants.kt URL_DISPENSER), answer {"email", "authToken"} (AuroraStore
//!   data/model/Auth.kt, data/providers/AuthProvider.kt buildAnonymousAuthData); that AUTH token goes
//!   into gpapi's set_auth_token() + login() (checkin, uploadDeviceConfig, toc), as gplayapi's
//!   SessionFactory.authenticate(tokenType = AUTH) does. Off unless the updater's settings enable it:
//!   Aurora's dispenser refuses other clients.
//! tlplay always sends its own User-Agent (teslalinux-appupdate/<version>); no option changes it.
//!
//!   tlplay [options] details PKG          latest versionCode/versionName (no download)
//!   tlplay [options] download PKG --version-code N --out DIR [--split NAME[|ALT...]]... [--max-file-bytes B]
//!                                         [--max-total-bytes B] [--deadline S] [--stall S]
//!                                         base.apk + split_<NAME>.apk into DIR, resumable (*.part + Range);
//!                                         a split with alternatives takes the first the source has
//!   tlplay --source play [options] auth   fetch a new dispensed login, cache it, print the account's email
//!
//! Options: --source apkpure|play (TLPLAY_SOURCE)  --timeout S (per request, default 60)
//!          play only: --auth-file F (default /etc/teslalinux/play/auth.json; 0600, the token is never
//!          printed)  --dispenser URL  --device-properties F  --device NAME (default px_tablet)  --locale en_US
//!
//! Output: one JSON object on stdout. Exit status: 0 ok; 2 usage; 10 dispenser unavailable; 11 Play
//! rejected the login (also with a fresh one); 12 Play unreachable or answered nonsense; 13 the app is
//! not available for this device profile; 14 download failed (size limit, timeout, HTTP, disk).

use std::collections::BTreeMap;
use std::error::Error as StdError;
use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use gpapi::error::ErrorKind as GpErrorKind;
use gpapi::Gpapi;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};

mod apkpure;

const DEFAULT_DISPENSER: &str = "https://auroraoss.com/api/auth";
const DEFAULT_AUTH_FILE: &str = "/etc/teslalinux/play/auth.json";
const DEFAULT_DEVICE: &str = "px_tablet";
const DEFAULT_DEVICE_PROPERTIES: &str = "/usr/local/share/teslalinux/appupdate/device.properties";

const EXIT_USAGE: i32 = 2;
const EXIT_DISPENSER: i32 = 10;
const EXIT_PLAY_AUTH: i32 = 11;
const EXIT_PLAY: i32 = 12;
const EXIT_NOT_AVAILABLE: i32 = 13;
const EXIT_DOWNLOAD: i32 = 14;

#[derive(Debug)]
struct Fail {
    code: i32,
    kind: &'static str,
    message: String,
}

fn fail<S: Into<String>>(code: i32, kind: &'static str, message: S) -> Fail {
    Fail { code, kind, message: message.into() }
}

type Res<T> = Result<T, Fail>;

#[derive(Clone)]
struct Opts {
    auth_file: PathBuf,
    dispenser: String,
    device: String,
    device_properties: PathBuf,
    locale: String,
    timeout: Duration,
    user_agent: String,
}

#[derive(Serialize, Deserialize)]
struct CachedAuth {
    email: String,
    #[serde(rename = "authToken")]
    auth_token: String,
    dispenser: String,
    device: String,
    #[serde(rename = "obtainedAt")]
    obtained_at: u64,
}

fn now() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0)
}

// ------------------------------------------------------------------ device profile

/// The [section] of an Aurora/gpapi device.properties file as Java's Properties.load() would read it
/// (Aurora serialises exactly that map, PropertiesSerializer in gplayapi internal/Serializers.kt).
fn read_device_section(path: &Path, section: &str) -> Res<BTreeMap<String, String>> {
    let text = fs::read_to_string(path)
        .map_err(|e| fail(EXIT_USAGE, "usage", format!("device properties {}: {}", path.display(), e)))?;
    let mut map = BTreeMap::new();
    let mut inside = false;
    let mut found = false;
    for raw in text.lines() {
        let line = raw.trim();
        if line.starts_with('[') && line.ends_with(']') {
            inside = &line[1..line.len() - 1] == section;
            found |= inside;
            continue;
        }
        if !inside || line.is_empty() || line.starts_with('#') || line.starts_with('!') {
            continue;
        }
        if let Some((k, v)) = line.split_once('=') {
            map.insert(java_unescape(k.trim()), java_unescape(v.trim_start()));
        }
    }
    if !found || map.is_empty() {
        return Err(fail(EXIT_USAGE, "usage", format!("no [{}] section in {}", section, path.display())));
    }
    Ok(map)
}

/// java.util.Properties escapes: "\:" -> ":", "\=" -> "=", "\\" -> "\", "\t" "\n" "\r" "\f".
fn java_unescape(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    let mut it = s.chars();
    while let Some(c) = it.next() {
        if c != '\\' {
            out.push(c);
            continue;
        }
        match it.next() {
            Some('t') => out.push('\t'),
            Some('n') => out.push('\n'),
            Some('r') => out.push('\r'),
            Some('f') => out.push('\x0c'),
            Some(o) => out.push(o),
            None => {}
        }
    }
    out
}

// ------------------------------------------------------------------ dispenser + auth cache

async fn fetch_dispensed(opts: &Opts) -> Res<CachedAuth> {
    let props = read_device_section(&opts.device_properties, &opts.device)?;
    let body = serde_json::to_vec(&props).expect("properties serialise");
    let client = reqwest::Client::builder()
        .timeout(opts.timeout)
        .user_agent(opts.user_agent.clone())
        .build()
        .map_err(|e| fail(EXIT_DISPENSER, "dispenser", format!("http client: {}", e)))?;
    let resp = client
        .post(&opts.dispenser)
        .header("Content-Type", "application/json")
        .body(body)
        .send()
        .await
        .map_err(|e| fail(EXIT_DISPENSER, "dispenser", format!("{} unreachable: {}", opts.dispenser, chain(&e))))?;
    let status = resp.status();
    if !status.is_success() {
        // the meanings AuroraStore's AuthProvider.throwError gives these codes
        let why = match status.as_u16() {
            400 => "bad request",
            403 => "access denied (the dispenser blocks this network)",
            404 => "dispenser not found",
            429 => "rate limited",
            503 => "dispenser under maintenance / out of accounts",
            _ => "error",
        };
        // the dispenser's own explanation, if any (short, printable characters only)
        // (an HTML block page, e.g. a CDN's firewall, is reduced to its <title>)
        let body = resp.text().await.unwrap_or_default();
        let body = match (body.find("<title>"), body.find("</title>")) {
            (Some(a), Some(b)) if b > a + 7 => body[a + 7..b].to_string(),
            _ => body,
        };
        let said: String = body.chars().filter(|c| !c.is_control()).take(160).collect();
        let said = if said.trim().is_empty() { String::new() } else { format!(" ({})", said.trim()) };
        return Err(fail(EXIT_DISPENSER, "dispenser", format!("{} answered HTTP {}: {}{}", opts.dispenser, status.as_u16(), why, said)));
    }
    let text = resp
        .text()
        .await
        .map_err(|e| fail(EXIT_DISPENSER, "dispenser", format!("reading the dispenser's answer: {}", e)))?;
    let v: Value = serde_json::from_str(&text)
        .map_err(|_| fail(EXIT_DISPENSER, "dispenser", "the dispenser's answer is not JSON"))?;
    let email = v.get("email").and_then(Value::as_str).unwrap_or_default().to_string();
    let token = v.get("authToken").and_then(Value::as_str).unwrap_or_default().to_string();
    if email.is_empty() || token.is_empty() {
        return Err(fail(EXIT_DISPENSER, "dispenser", "the dispenser's answer has no email/authToken"));
    }
    Ok(CachedAuth {
        email,
        auth_token: token,
        dispenser: opts.dispenser.clone(),
        device: opts.device.clone(),
        obtained_at: now(),
    })
}

fn load_cached(opts: &Opts) -> Option<CachedAuth> {
    let text = fs::read_to_string(&opts.auth_file).ok()?;
    let a: CachedAuth = serde_json::from_str(&text).ok()?;
    // a login dispensed for another device profile or by another dispenser is not reused
    (a.device == opts.device && a.dispenser == opts.dispenser && !a.auth_token.is_empty()).then_some(a)
}

fn save_cached(opts: &Opts, a: &CachedAuth) -> Res<()> {
    let err = |e: std::io::Error| fail(EXIT_USAGE, "local", format!("auth file {}: {}", opts.auth_file.display(), e));
    let dir = opts.auth_file.parent().unwrap_or(Path::new("."));
    if !dir.as_os_str().is_empty() && !dir.exists() {
        fs::create_dir_all(dir).map_err(err)?;
        set_mode(dir, 0o700).map_err(err)?;
    }
    let tmp = opts.auth_file.with_extension("json.tmp");
    {
        let mut f = open_private(&tmp).map_err(err)?;
        f.write_all(serde_json::to_string_pretty(a).expect("auth serialises").as_bytes()).map_err(err)?;
        f.write_all(b"\n").map_err(err)?;
        f.sync_all().map_err(err)?;
    }
    fs::rename(&tmp, &opts.auth_file).map_err(err)
}

fn drop_cached(opts: &Opts) {
    let _ = fs::remove_file(&opts.auth_file);
}

#[cfg(unix)]
fn open_private(p: &Path) -> std::io::Result<fs::File> {
    use std::os::unix::fs::OpenOptionsExt;
    let _ = fs::remove_file(p);
    fs::OpenOptions::new().write(true).create_new(true).mode(0o600).open(p)
}

#[cfg(unix)]
fn set_mode(p: &Path, mode: u32) -> std::io::Result<()> {
    use std::os::unix::fs::PermissionsExt;
    fs::set_permissions(p, fs::Permissions::from_mode(mode))
}

// ------------------------------------------------------------------ Play session

/// "network" when the failure is a transport error (no route, DNS, TLS, timeout), else "protocol":
/// gpapi decodes whatever came back, so a rejected token shows up as a decode error or a missing
/// device-config token / DFE cookie.
fn classify(e: &(dyn StdError + 'static)) -> &'static str {
    let mut cur: Option<&(dyn StdError + 'static)> = Some(e);
    while let Some(x) = cur {
        if x.downcast_ref::<reqwest::Error>().is_some() || x.downcast_ref::<std::io::Error>().is_some() {
            return "network";
        }
        if let Some(g) = x.downcast_ref::<gpapi::error::Error>() {
            match g.kind() {
                GpErrorKind::Other(inner) => {
                    if classify(inner.as_ref()) == "network" {
                        return "network";
                    }
                }
                GpErrorKind::IO(_) => return "network",
                _ => {}
            }
        }
        cur = x.source();
    }
    "protocol"
}

fn chain(e: &(dyn StdError + 'static)) -> String {
    let mut s = e.to_string();
    let mut cur = e.source();
    while let Some(x) = cur {
        s.push_str(": ");
        s.push_str(&x.to_string());
        cur = x.source();
    }
    s
}

async fn login_with(opts: &Opts, a: &CachedAuth) -> Result<Gpapi, (&'static str, String)> {
    let props = opts.device_properties.to_string_lossy().to_string();
    let mut api = Gpapi::from_device_properties_file(opts.device.clone(), a.email.clone(), props);
    api.set_locale(opts.locale.clone());
    api.set_auth_token(a.auth_token.clone());
    for attempt in 0..2 {
        let r = tokio::time::timeout(opts.timeout * 3, api.login()).await;
        match r {
            Err(_) => return Err(("network", "Play login timed out".into())),
            Ok(Ok(())) => return Ok(api),
            Ok(Err(e)) => {
                if matches!(e.kind(), GpErrorKind::TermsOfService) && attempt == 0 {
                    // a fresh dispensed account can still owe Play's ToS acceptance; accept and log in again
                    match tokio::time::timeout(opts.timeout, api.accept_tos()).await {
                        Ok(Ok(_)) => continue,
                        Ok(Err(e)) => return Err((classify(e.as_ref()), format!("Play ToS: {}", chain(e.as_ref())))),
                        Err(_) => return Err(("network", "Play ToS acceptance timed out".into())),
                    }
                }
                return Err((classify(&e), format!("Play login: {}", chain(&e))));
            }
        }
    }
    Err(("protocol", "Play login: terms of service loop".into()))
}

/// A logged-in session: the cached dispensed login while Play accepts it, else a fresh one.
async fn session(opts: &Opts) -> Res<(Gpapi, bool)> {
    if let Some(a) = load_cached(opts) {
        match login_with(opts, &a).await {
            Ok(api) => return Ok((api, false)),
            Err(("network", m)) => return Err(fail(EXIT_PLAY, "play", m)),
            Err((_, _)) => drop_cached(opts), // rejected: the dispenser's account was recycled or blocked
        }
    }
    let a = fetch_dispensed(opts).await?;
    match login_with(opts, &a).await {
        Ok(api) => {
            save_cached(opts, &a)?;
            Ok((api, true))
        }
        Err(("network", m)) => Err(fail(EXIT_PLAY, "play", m)),
        Err((_, m)) => Err(fail(EXIT_PLAY_AUTH, "play_auth", format!("Play rejected a fresh dispensed login: {}", m))),
    }
}

// ------------------------------------------------------------------ commands

async fn cmd_auth(opts: &Opts) -> Res<Value> {
    drop_cached(opts);
    let (_api, _) = session(opts).await?;
    let a = load_cached(opts).ok_or_else(|| fail(EXIT_PLAY_AUTH, "play_auth", "no login cached"))?;
    Ok(json!({"ok": true, "email": a.email, "device": a.device, "dispenser": a.dispenser, "obtainedAt": a.obtained_at}))
}

async fn details_of(api: &Gpapi, opts: &Opts, pkg: &str) -> Result<Value, (&'static str, String)> {
    let r = tokio::time::timeout(opts.timeout, api.details(pkg)).await;
    let resp = match r {
        Err(_) => return Err(("network", "details timed out".into())),
        Ok(Err(e)) => return Err((classify(e.as_ref()), format!("details: {}", chain(e.as_ref())))),
        Ok(Ok(resp)) => resp,
    };
    let item = resp.and_then(|d| d.item);
    let title = item.as_ref().and_then(|i| i.title.clone());
    let app = item.and_then(|i| i.details).and_then(|d| d.app_details);
    match app {
        Some(app) if app.version_code.is_some() => Ok(json!({
            "ok": true,
            "package": app.package_name.clone().unwrap_or_else(|| pkg.to_string()),
            "title": title,
            "versionCode": app.version_code,
            "versionName": app.version_string,
            "targetSdk": app.target_sdk_version,
            "downloadSize": app.info_download_size,
            "updatedOn": app.info_updated_on,
            "splitIds": app.split_id,
        })),
        _ => Err(("missing", format!("{} has no app details for this device profile", pkg))),
    }
}

async fn cmd_details(opts: &Opts, pkg: &str) -> Res<Value> {
    let (api, fresh) = session(opts).await?;
    match details_of(&api, opts, pkg).await {
        Ok(v) => Ok(v),
        Err(("network", m)) => Err(fail(EXIT_PLAY, "play", m)),
        Err(("missing", m)) => Err(fail(EXIT_NOT_AVAILABLE, "not_available", m)),
        Err((_, m)) if fresh => Err(fail(EXIT_PLAY_AUTH, "play_auth", m)),
        Err(_) => {
            // the cached login was accepted at login but not here: once more with a fresh one
            drop_cached(opts);
            let (api, _) = session(opts).await?;
            details_of(&api, opts, pkg).await.map_err(|(k, m)| match k {
                "network" => fail(EXIT_PLAY, "play", m),
                "missing" => fail(EXIT_NOT_AVAILABLE, "not_available", m),
                _ => fail(EXIT_PLAY_AUTH, "play_auth", m),
            })
        }
    }
}

struct DlLimits {
    max_file: u64,
    max_total: u64,
    deadline: Instant,
    stall: Duration,
}

/// One file: OUT/NAME.part grows with HTTP Range requests (the Play CDN serves them) until complete,
/// then it is fsynced and renamed to OUT/NAME. A complete OUT/NAME is kept as is (an earlier run).
async fn fetch_file(client: &reqwest::Client, url: &str, out: &Path, name: &str, lim: &DlLimits, total: &mut u64) -> Res<u64> {
    let dst = out.join(name);
    if let Ok(m) = fs::metadata(&dst) {
        *total += m.len();
        return Ok(m.len());
    }
    let part = out.join(format!("{}.part", name));
    let derr = |m: String| fail(EXIT_DOWNLOAD, "download", format!("{}: {}", name, m));
    let mut have = fs::metadata(&part).map(|m| m.len()).unwrap_or(0);
    let mut req = client.get(url);
    if have > 0 {
        req = req.header("Range", format!("bytes={}-", have));
    }
    let left = lim.deadline.saturating_duration_since(Instant::now());
    if left.is_zero() {
        return Err(derr("download deadline reached".into()));
    }
    let mut resp = tokio::time::timeout(left.min(lim.stall * 2), req.send())
        .await
        .map_err(|_| derr("no answer from the download server".into()))?
        .map_err(|e| derr(chain(&e)))?;
    let status = resp.status().as_u16();
    let mut f;
    let size;
    match status {
        206 => {
            // Content-Range: bytes START-END/TOTAL; only a continuation of what we have is accepted
            let cr = resp.headers().get("content-range").and_then(|v| v.to_str().ok()).unwrap_or("").to_string();
            let start = cr.strip_prefix("bytes ").and_then(|r| r.split('-').next()).and_then(|s| s.parse::<u64>().ok());
            let tot = cr.rsplit('/').next().and_then(|s| s.parse::<u64>().ok());
            if start != Some(have) || tot.is_none() {
                let _ = fs::remove_file(&part);
                return Err(derr(format!("unexpected Content-Range '{}' for a resume at {}; restarting next time", cr, have)));
            }
            size = tot.unwrap();
            f = fs::OpenOptions::new().append(true).open(&part).map_err(|e| derr(e.to_string()))?;
        }
        200 => {
            have = 0;
            size = resp.content_length().unwrap_or(0);
            f = fs::File::create(&part).map_err(|e| derr(e.to_string()))?;
        }
        416 if have > 0 => {
            // the .part already holds everything (the server says the range starts past the end)
            size = have;
            f = fs::OpenOptions::new().append(true).open(&part).map_err(|e| derr(e.to_string()))?;
        }
        s => return Err(derr(format!("HTTP {}", s))),
    }
    if size > lim.max_file {
        let _ = fs::remove_file(&part);
        return Err(derr(format!("{} bytes is over the per-file limit {}", size, lim.max_file)));
    }
    if *total + size > lim.max_total {
        return Err(derr(format!("the download would exceed the total limit {}", lim.max_total)));
    }
    if status != 416 {
        loop {
            let left = lim.deadline.saturating_duration_since(Instant::now());
            if left.is_zero() {
                f.sync_all().ok();
                return Err(derr(format!("download deadline reached at {} of {} bytes (resumes next time)", have, size)));
            }
            let chunk = tokio::time::timeout(left.min(lim.stall), resp.chunk())
                .await
                .map_err(|_| {
                    f.sync_all().ok();
                    let why = if Instant::now() >= lim.deadline { "deadline reached" } else { "stalled" };
                    derr(format!("{} at {} of {} bytes (resumes next time)", why, have, size))
                })?
                .map_err(|e| {
                    f.sync_all().ok();
                    derr(format!("{} at {} of {} bytes (resumes next time)", chain(&e), have, size))
                })?;
            let Some(chunk) = chunk else { break };
            have += chunk.len() as u64;
            if have > lim.max_file || (size > 0 && have > size) {
                let _ = fs::remove_file(&part);
                return Err(derr(format!("server sent more than announced ({} bytes)", have)));
            }
            f.write_all(&chunk).map_err(|e| derr(e.to_string()))?;
        }
    }
    f.sync_all().map_err(|e| derr(e.to_string()))?;
    drop(f);
    if size > 0 && have != size {
        return Err(derr(format!("got {} of {} bytes (resumes next time)", have, size)));
    }
    fs::rename(&part, &dst).map_err(|e| derr(e.to_string()))?;
    *total += have;
    Ok(have)
}

async fn cmd_download(opts: &Opts, pkg: &str, vc: i64, out: &Path, want: &[String], lim: DlLimits) -> Res<Value> {
    fs::create_dir_all(out).map_err(|e| fail(EXIT_DOWNLOAD, "download", format!("{}: {}", out.display(), e)))?;
    let (api, fresh) = session(opts).await?;
    let info = match tokio::time::timeout(opts.timeout * 2, api.get_download_info(pkg, Some(vc))).await {
        Err(_) => return Err(fail(EXIT_PLAY, "play", "delivery request timed out")),
        Ok(Ok(i)) => i,
        Ok(Err(e)) => {
            let k = classify(&e);
            if k == "network" {
                return Err(fail(EXIT_PLAY, "play", format!("delivery: {}", chain(&e))));
            }
            if matches!(e.kind(), GpErrorKind::InvalidApp) && fresh {
                return Err(fail(EXIT_NOT_AVAILABLE, "not_available", format!("Play delivers no {} versionCode {} to this device profile", pkg, vc)));
            }
            // possibly a stale login: once more with a fresh one
            drop_cached(opts);
            let (api, _) = session(opts).await?;
            match tokio::time::timeout(opts.timeout * 2, api.get_download_info(pkg, Some(vc))).await {
                Err(_) => return Err(fail(EXIT_PLAY, "play", "delivery request timed out")),
                Ok(Ok(i)) => i,
                Ok(Err(e)) => {
                    return Err(match (classify(&e), e.kind()) {
                        ("network", _) => fail(EXIT_PLAY, "play", format!("delivery: {}", chain(&e))),
                        (_, GpErrorKind::InvalidApp) => fail(EXIT_NOT_AVAILABLE, "not_available", format!("Play delivers no {} versionCode {} to this device profile", pkg, vc)),
                        _ => fail(EXIT_PLAY_AUTH, "play_auth", format!("delivery: {}", chain(&e))),
                    })
                }
            }
        }
    };
    let (base_url, splits, _obb, _dm) = info;
    let base_url = base_url.ok_or_else(|| fail(EXIT_NOT_AVAILABLE, "not_available", "Play gave no base APK URL"))?;
    let offered: Vec<String> = splits.iter().filter_map(|(n, _)| n.clone()).collect();
    let mut jobs: Vec<(String, String)> = vec![("base.apk".into(), base_url)];
    let mut missing = Vec::new();
    for w in want {
        // "a|b|c": the first alternative Play offers
        let hit = w.split('|').find_map(|a| splits.iter().find(|(n, u)| n.as_deref() == Some(a) && u.is_some()).map(|(_, u)| (a, u.clone().unwrap())));
        match hit {
            Some((a, url)) => jobs.push((format!("split_{}.apk", a), url)),
            None => missing.push(w.clone()),
        }
    }
    let client = reqwest::Client::builder()
        .connect_timeout(Duration::from_secs(30))
        .build()
        .map_err(|e| fail(EXIT_DOWNLOAD, "download", format!("http client: {}", e)))?;
    let mut total = 0u64;
    let mut files = Vec::new();
    for (name, url) in &jobs {
        let n = fetch_file(&client, url, out, name, &lim, &mut total).await?;
        files.push(json!({"file": name, "bytes": n}));
    }
    Ok(json!({
        "ok": true, "package": pkg, "versionCode": vc, "dir": out.to_string_lossy(),
        "files": files, "totalBytes": total, "offeredSplits": offered, "missingSplits": missing,
    }))
}

// ------------------------------------------------------------------ APKPure source

const APKPURE_SDK: u32 = 32; // the SDK level ATL reports (x-sv)
const APKPURE_ABIS: &str = "arm64-v8a";

async fn apkpure_variants(opts: &Opts, pkg: &str) -> Res<(Vec<apkpure::Variant>, usize, apkpure::Http)> {
    let http = apkpure::Http::new(&opts.user_agent, opts.timeout).map_err(|m| fail(EXIT_PLAY, "apkpure", m))?;
    let body = http.versions(pkg, APKPURE_SDK, APKPURE_ABIS).await.map_err(|(_, m)| fail(EXIT_PLAY, "apkpure", m))?;
    let n = body.len();
    let v = apkpure::parse_versions(&body, pkg).map_err(|m| fail(EXIT_NOT_AVAILABLE, "not_available", m))?;
    Ok((v, n, http))
}

async fn apkpure_details(opts: &Opts, pkg: &str) -> Res<Value> {
    let (v, n, _) = apkpure_variants(opts, pkg).await?;
    let top = &v[0];
    let variants: Vec<Value> = v.iter().filter(|x| x.version_code == top.version_code).map(apkpure::variant_json).collect();
    Ok(json!({
        "ok": true, "source": "apkpure", "package": pkg, "versionCode": top.version_code,
        "versionName": top.version_name, "variants": variants, "answerBytes": n,
    }))
}

async fn apkpure_download(opts: &Opts, pkg: &str, vc: i64, out: &Path, want: &[String], lim: DlLimits) -> Res<Value> {
    let derr = |m: String| fail(EXIT_DOWNLOAD, "download", m);
    fs::create_dir_all(out).map_err(|e| derr(format!("{}: {}", out.display(), e)))?;
    let (all, _, http) = apkpure_variants(opts, pkg).await?;
    let cands: Vec<&apkpure::Variant> = all.iter().filter(|x| x.version_code == vc).collect();
    if cands.is_empty() {
        return Err(fail(EXIT_NOT_AVAILABLE, "not_available", format!("APKPure lists no {} versionCode {}", pkg, vc)));
    }
    // each --split is a group of alternatives in preference order ("config.xhdpi|config.xxhdpi|..."):
    // the first one a variant holds counts
    let groups: Vec<Vec<String>> = want.iter().map(|w| w.split('|').map(|a| format!("split_{}.apk", a)).collect()).collect();
    let pick = |m: &[(String, apkpure::ZipEntry)], g: &[String]| g.iter().position(|a| m.iter().any(|(n, _)| n == a));
    // the variant whose XAPK holds base + the most groups, then the most preferred alternatives
    // (central directories only: a few KB each)
    let mut best: Option<((usize, i64), &apkpure::Variant, Vec<(String, apkpure::ZipEntry)>)> = None;
    let mut looked = Vec::new();
    for v in &cands {
        if v.kind != "XAPK" {
            looked.push(json!({"sha1": v.sha1, "type": v.kind, "size": v.size}));
            let score = (0usize, 0i64);
            if best.as_ref().map(|b| score > b.0).unwrap_or(true) {
                best = Some((score, v, Vec::new()));
            }
            continue;
        }
        let (entries, _) = apkpure::list_zip(&http, &v.url).await.map_err(derr)?;
        let m = apkpure::members(&entries, pkg);
        let names: Vec<&String> = m.iter().map(|(n, _)| n).collect();
        looked.push(json!({"sha1": v.sha1, "type": v.kind, "size": v.size, "apks": names}));
        if !m.iter().any(|(n, _)| n == "base.apk") {
            continue;
        }
        let hits: Vec<usize> = groups.iter().filter_map(|g| pick(&m, g)).collect();
        let score = (1 + hits.len(), -(hits.iter().sum::<usize>() as i64));
        if best.as_ref().map(|b| score > b.0).unwrap_or(true) {
            best = Some((score, v, m));
        }
    }
    let (_, v, members) = best.ok_or_else(|| fail(EXIT_NOT_AVAILABLE, "not_available", "no APKPure variant holds a base APK"))?;
    // a different variant than an interrupted earlier run: its partial files do not belong to this one
    let marker = out.join(".variant");
    if fs::read_to_string(&marker).map(|s| s.trim() != v.sha1).unwrap_or(false) {
        for e in fs::read_dir(out).map_err(|e| derr(e.to_string()))?.flatten() {
            let _ = fs::remove_file(e.path());
        }
    }
    fs::write(&marker, &v.sha1).map_err(|e| derr(e.to_string()))?;
    let alim = apkpure::Limits { max_file: lim.max_file, deadline: lim.deadline, stall: lim.stall };
    let mut files = Vec::new();
    let mut fetched = 0u64;
    let mut missing = Vec::new();
    if v.kind != "XAPK" {
        // a single APK (no splits): the whole file is base.apk
        let client = reqwest::Client::builder().connect_timeout(Duration::from_secs(30)).build().map_err(|e| derr(e.to_string()))?;
        let mut total = 0u64;
        let n = fetch_file(&client, &v.url, out, "base.apk", &lim, &mut total).await?;
        files.push(json!({"file": "base.apk", "bytes": n}));
        fetched += n;
        missing = want.to_vec();
    } else {
        let mut need: Vec<(String, apkpure::ZipEntry)> = members.iter().filter(|(n, _)| n == "base.apk").cloned().collect();
        for (w, g) in want.iter().zip(&groups) {
            match pick(&members, g) {
                Some(k) => need.push(members.iter().find(|(n, _)| *n == g[k]).cloned().expect("picked member")),
                None => missing.push(w.clone()),
            }
        }
        let total: u64 = need.iter().map(|(_, e)| e.usize).sum();
        if total > lim.max_total {
            return Err(derr(format!("the APKs we need are {} bytes, over the total limit {}", total, lim.max_total)));
        }
        for (n, e) in &need {
            fetched += apkpure::fetch_member(&http, &v.url, e, out, n, &alim).await.map_err(derr)?;
            files.push(json!({"file": n, "bytes": e.usize}));
        }
    }
    let offered: Vec<String> = members.iter().filter_map(|(n, _)| n.strip_prefix("split_").and_then(|x| x.strip_suffix(".apk")).map(str::to_string)).collect();
    let needed_bytes: u64 = files.iter().filter_map(|f| f["bytes"].as_u64()).sum();
    Ok(json!({
        "ok": true, "source": "apkpure", "package": pkg, "versionCode": vc, "dir": out.to_string_lossy(),
        "variant": apkpure::variant_json(v), "variantsSeen": looked, "files": files, "totalBytes": needed_bytes,
        "fetchedBytes": fetched, "offeredSplits": offered, "missingSplits": missing,
    }))
}

// ------------------------------------------------------------------ CLI

fn usage() -> Fail {
    fail(EXIT_USAGE, "usage", "usage: tlplay [--source apkpure|play] [--auth-file F] [--dispenser URL] [--device-properties F] [--device NAME] [--locale L] [--timeout S] auth | details PKG | download PKG --version-code N --out DIR [--split NAME]... [--max-file-bytes B] [--max-total-bytes B] [--deadline S] [--stall S]")
}

async fn run(args: Vec<String>) -> Res<Value> {
    let mut opts = Opts {
        auth_file: PathBuf::from(std::env::var("TLPLAY_AUTH_FILE").unwrap_or_else(|_| DEFAULT_AUTH_FILE.into())),
        dispenser: std::env::var("TLPLAY_DISPENSER").unwrap_or_else(|_| DEFAULT_DISPENSER.into()),
        device: DEFAULT_DEVICE.into(),
        device_properties: PathBuf::from(std::env::var("TLPLAY_DEVICE_PROPERTIES").unwrap_or_else(|_| DEFAULT_DEVICE_PROPERTIES.into())),
        locale: "en_US".into(),
        timeout: Duration::from_secs(60),
        // who we are, always: never another client's identity (no option changes it)
        user_agent: format!("teslalinux-appupdate/{}", env!("CARGO_PKG_VERSION")),
    };
    let mut pos = Vec::new();
    let mut source = std::env::var("TLPLAY_SOURCE").unwrap_or_else(|_| "apkpure".into());
    let (mut vc, mut out, mut want) = (None, None, Vec::new());
    let mut lim = DlLimits { max_file: 600 << 20, max_total: 1 << 30, deadline: Instant::now() + Duration::from_secs(3600), stall: Duration::from_secs(120) };
    let mut it = args.into_iter();
    while let Some(a) = it.next() {
        let mut val = || it.next().ok_or_else(usage);
        let num = |s: String| s.parse::<u64>().map_err(|_| usage());
        match a.as_str() {
            "--auth-file" => opts.auth_file = PathBuf::from(val()?),
            "--dispenser" => opts.dispenser = val()?,
            "--device" => opts.device = val()?,
            "--device-properties" => opts.device_properties = PathBuf::from(val()?),
            "--locale" => opts.locale = val()?,
            "--timeout" => opts.timeout = Duration::from_secs(num(val()?)?),
            "--source" => source = val()?,
            "--version-code" => vc = Some(val()?.parse::<i64>().map_err(|_| usage())?),
            "--out" => out = Some(PathBuf::from(val()?)),
            "--split" => want.push(val()?),
            "--max-file-bytes" => lim.max_file = num(val()?)?,
            "--max-total-bytes" => lim.max_total = num(val()?)?,
            "--deadline" => lim.deadline = Instant::now() + Duration::from_secs(num(val()?)?),
            "--stall" => lim.stall = Duration::from_secs(num(val()?)?),
            s if s.starts_with("--") => return Err(usage()),
            _ => pos.push(a),
        }
    }
    if !(opts.dispenser.starts_with("https://") || opts.dispenser.starts_with("http://127.0.0.1") || opts.dispenser.starts_with("http://localhost")) {
        return Err(fail(EXIT_USAGE, "usage", "the dispenser URL must be https:// (or a local http://127.0.0.1 one)"));
    }
    if source != "apkpure" && source != "play" {
        return Err(fail(EXIT_USAGE, "usage", "--source is apkpure or play"));
    }
    match pos.iter().map(String::as_str).collect::<Vec<_>>().as_slice() {
        ["auth"] => cmd_auth(&opts).await,
        ["details", pkg] if source == "apkpure" => apkpure_details(&opts, pkg).await,
        ["download", pkg] if source == "apkpure" => {
            let vc = vc.ok_or_else(usage)?;
            let out = out.ok_or_else(usage)?;
            apkpure_download(&opts, pkg, vc, &out, &want, lim).await
        }
        ["details", pkg] => cmd_details(&opts, pkg).await,
        ["download", pkg] => {
            let vc = vc.ok_or_else(usage)?;
            let out = out.ok_or_else(usage)?;
            cmd_download(&opts, pkg, vc, &out, &want, lim).await
        }
        _ => Err(usage()),
    }
}

fn main() {
    let rt = tokio::runtime::Builder::new_current_thread().enable_all().build().expect("tokio runtime");
    let args: Vec<String> = std::env::args().skip(1).collect();
    let code = match rt.block_on(run(args)) {
        Ok(v) => {
            println!("{}", v);
            0
        }
        Err(f) => {
            println!("{}", json!({"ok": false, "error": f.kind, "message": f.message, "exitCode": f.code}));
            f.code
        }
    };
    std::process::exit(code);
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn unescape_like_java_properties() {
        assert_eq!(java_unescape(r"google/tangorpro/tangorpro\:13/TQ3A\:user"), "google/tangorpro/tangorpro:13/TQ3A:user");
        assert_eq!(java_unescape(r"a\\b\=c"), r"a\b=c");
    }

    #[test]
    fn device_profile_matches_our_splits() {
        let p = read_device_section(Path::new(concat!(env!("CARGO_MANIFEST_DIR"), "/device.properties")), DEFAULT_DEVICE).unwrap();
        assert_eq!(p["Platforms"], "arm64-v8a");
        assert_eq!(p["Screen.Density"], "320"); // xhdpi
        assert!(p["Build.VERSION.SDK_INT"].parse::<u32>().unwrap() >= 32); // Waze 5.25 minSdk
        assert!(!p["Build.FINGERPRINT"].contains('\\'));
    }
}
