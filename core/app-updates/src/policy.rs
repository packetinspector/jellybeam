use serde::{Deserialize, Serialize};
use url::Url;

pub const REPO: &str = "packetinspector/jellybeam";
pub const SIGNER: &str = "5e0de7a9bcc9406eb470a5a88e368cc6bbcdd6ade06ff1114f304c409d605edf";
pub const METADATA: &str = "jellybeam-tv-update.json";
pub const APK_LIMIT: u64 = 128 * 1024 * 1024;
pub const PROFILE_LIMIT: u64 = 16 * 1024 * 1024;
pub const JSON_LIMIT: u64 = 256 * 1024;
pub const NOTES_LIMIT: usize = 16 * 1024;

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq, Default)]
pub enum Phase {
    #[default]
    Idle,
    Checking,
    Current,
    Available,
    Downloading,
    Verifying,
    Ready,
    Preparing,
    Staging,
    AwaitingConfirmation,
    Finished,
    Error,
}
impl Phase {
    pub fn busy(self) -> bool {
        matches!(
            self,
            Self::Checking
                | Self::Downloading
                | Self::Verifying
                | Self::Preparing
                | Self::Staging
                | Self::AwaitingConfirmation
        )
    }
}
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq, thiserror::Error)]
pub enum Failure {
    #[error("Connection failed. Try again.")]
    Network,
    #[error("GitHub is busy. Try again later.")]
    RateLimited,
    #[error("This release does not support secure in-app updates.")]
    UnsupportedRelease,
    #[error("This update is not compatible with this device.")]
    Incompatible,
    #[error("The update could not be verified. Download it again.")]
    Verification,
    #[error("Not enough storage for this update.")]
    Storage,
    #[error("Android could not install the update. Try again.")]
    Installation,
    #[error("The release is no longer available for installation.")]
    Withdrawn,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Installed {
    pub package: String,
    pub version_code: u64,
    pub sdk: u32,
    pub abis: Vec<String>,
    pub signer: String,
}
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct Asset {
    pub id: u64,
    pub name: String,
    pub size: u64,
    pub digest: String,
    pub state: String,
    pub browser_download_url: String,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Release {
    pub id: u64,
    pub tag_name: String,
    pub draft: bool,
    pub prerelease: bool,
    pub immutable: bool,
    pub body: Option<String>,
    #[serde(default)]
    pub published_at: Option<String>,
    pub assets: Vec<Asset>,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Profile {
    pub min_sdk: u32,
    pub max_sdk: u32,
    pub asset: String,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Metadata {
    pub schema: u32,
    pub package: String,
    pub version_code: u64,
    pub min_sdk: u32,
    pub abis: Vec<String>,
    pub apk_asset: String,
    pub profiles: Vec<Profile>,
}
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Candidate {
    pub release: Release,
    pub metadata: Metadata,
    pub apk: Asset,
    pub profile: Option<Asset>,
}
#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct Snapshot {
    pub generation: u64,
    pub revision: u64,
    pub phase: Phase,
    pub failure: Option<Failure>,
    pub candidate: Option<Candidate>,
    pub bytes: u64,
    pub total: u64,
    pub ready_at: u64,
    #[serde(skip)]
    pub available_notice: bool,
    #[serde(skip)]
    pub last_checked: u64,
    #[serde(skip)]
    pub notes: String,
    #[serde(skip)]
    pub version_label: String,
    #[serde(skip)]
    pub published_at: String,
}

pub fn safe_name(name: &str) -> bool {
    !name.is_empty()
        && name.len() <= 128
        && !name.starts_with('.')
        && name
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b"-_.".contains(&b))
}
pub fn allowed_url(raw: &str, initial: bool) -> bool {
    let Ok(u) = Url::parse(raw) else { return false };
    if u.scheme() != "https"
        || !u.username().is_empty()
        || u.password().is_some()
        || u.fragment().is_some()
        || u.port_or_known_default() != Some(443)
    {
        return false;
    }
    if initial {
        u.host_str() == Some("github.com")
            && u.path().starts_with(&format!("/{REPO}/releases/download/"))
            && u.query().is_none()
    } else {
        matches!(
            u.host_str(),
            Some(
                "github.com"
                    | "release-assets.githubusercontent.com"
                    | "objects.githubusercontent.com"
            )
        )
    }
}
pub fn eligible_release(r: &Release) -> Result<(), Failure> {
    if r.id == 0
        || r.tag_name.is_empty()
        || r.tag_name.len() > 128
        || r.tag_name.chars().any(char::is_control)
        || r.draft
        || r.prerelease
        || !r.immutable
        || r.assets.len() > 64
    {
        return Err(Failure::UnsupportedRelease);
    }
    Ok(())
}
pub fn select_asset(r: &Release, name: &str, limit: u64) -> Result<Asset, Failure> {
    let mut matches = r.assets.iter().filter(|a| a.name == name);
    let a = matches.next().ok_or(Failure::UnsupportedRelease)?;
    if matches.next().is_some()
        || !safe_name(name)
        || a.id == 0
        || a.state != "uploaded"
        || a.size == 0
        || a.size > limit
        || !a.digest.starts_with("sha256:")
        || a.digest.len() != 71
        || !a.digest[7..]
            .bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
    {
        return Err(Failure::UnsupportedRelease);
    }
    Ok(a.clone())
}
pub fn candidate(
    r: Release,
    m: Metadata,
    installed: &Installed,
) -> Result<Option<Candidate>, Failure> {
    eligible_release(&r)?;
    if m.schema != 1
        || m.package != installed.package
        || m.version_code == 0
        || m.version_code > i32::MAX as u64
        || m.min_sdk == 0
        || m.abis.is_empty()
        || m.abis.len() > 8
        || m.abis.iter().enumerate().any(|(i, abi)| {
            !matches!(abi.as_str(), "arm64-v8a" | "armeabi-v7a" | "x86_64")
                || m.abis[..i].contains(abi)
        })
        || m.profiles.len() > 8
        || !m.apk_asset.ends_with(".apk")
    {
        return Err(Failure::UnsupportedRelease);
    }
    if m.version_code <= installed.version_code {
        return Ok(None);
    }
    if m.min_sdk > installed.sdk || !m.abis.iter().any(|abi| installed.abis.contains(abi)) {
        return Err(Failure::Incompatible);
    }
    for (i, p) in m.profiles.iter().enumerate() {
        if p.min_sdk < 28
            || p.min_sdk > p.max_sdk
            || !p.asset.ends_with(".dm")
            || m.profiles[..i]
                .iter()
                .any(|q| p.min_sdk <= q.max_sdk && q.min_sdk <= p.max_sdk)
        {
            return Err(Failure::UnsupportedRelease);
        }
    }
    let apk = select_asset(&r, &m.apk_asset, APK_LIMIT)?;
    let profile = if installed.sdk >= 28 {
        let p = m
            .profiles
            .iter()
            .find(|p| p.min_sdk <= installed.sdk && installed.sdk <= p.max_sdk)
            .ok_or(Failure::UnsupportedRelease)?;
        Some(select_asset(&r, &p.asset, PROFILE_LIMIT)?)
    } else {
        None
    };
    Ok(Some(Candidate {
        release: r,
        metadata: m,
        apk,
        profile,
    }))
}
const NOTES_SUFFIX: &str = "\n\nNotes shortened.";
/// docs/26 §2: cut at the `## Install` line (trimmed, case-insensitive); total length <= NOTES_LIMIT.
pub fn viewer_notes(body: &str) -> String {
    let notes = body
        .lines()
        .take_while(|line| !line.trim().eq_ignore_ascii_case("## Install"))
        .collect::<Vec<_>>()
        .join("\n");
    let notes = notes.trim().to_owned();
    if notes.len() <= NOTES_LIMIT {
        return notes;
    }
    let mut end = NOTES_LIMIT - NOTES_SUFFIX.len();
    while !notes.is_char_boundary(end) {
        end -= 1;
    }
    format!("{}{NOTES_SUFFIX}", &notes[..end])
}
pub fn due(
    now: u64,
    last_success: u64,
    last_failure: u64,
    last_manual: u64,
    blocked_until: u64,
    manual: bool,
) -> bool {
    now >= blocked_until
        && if manual {
            last_manual == 0 || now.saturating_sub(last_manual) >= 60
        } else {
            (last_success == 0 || now.saturating_sub(last_success) >= 86400)
                && (last_failure == 0 || now.saturating_sub(last_failure) >= 21600)
        }
}
#[derive(Clone, Debug)]
pub struct Verified {
    pub package: String,
    pub code: u64,
    pub sdk: u32,
    pub signer: String,
    pub abis: Vec<String>,
    pub valid: bool,
}
pub fn accepts_apk(c: &Candidate, installed: &Installed, facts: &Verified) -> bool {
    #[cfg(not(feature = "update-test-fixture"))]
    let trusted = installed.signer == SIGNER && facts.signer == SIGNER;
    #[cfg(feature = "update-test-fixture")]
    let trusted = installed.signer == facts.signer;
    let mut actual = facts.abis.clone();
    actual.sort();
    let mut declared = c.metadata.abis.clone();
    declared.sort();
    facts.valid
        && actual == declared
        && trusted
        && facts.package == installed.package
        && facts.package == c.metadata.package
        && facts.code == c.metadata.version_code
        && facts.code > installed.version_code
        && facts.sdk == c.metadata.min_sdk
        && facts.sdk <= installed.sdk
}

pub const CLOCK_SLACK: u64 = 300;
pub const READY_TTL: u64 = 7 * 86400;
pub const SNOOZE_SECS: u64 = 86400;
pub const BLOCK_MAX: u64 = 3600;
const BLOCK_DEFAULT: u64 = 300;

/// docs/26 §2: a recorded past time ahead of the wall clock (clock moved back) counts as now.
pub fn past_stamp(ts: u64, now: u64) -> u64 {
    if ts > now.saturating_add(CLOCK_SLACK) {
        now
    } else {
        ts
    }
}
/// docs/26 §2: a deadline never lies farther ahead than the longest value we ever set.
pub fn deadline(ts: u64, now: u64, max_ahead: u64) -> u64 {
    ts.min(now.saturating_add(max_ahead))
}
/// docs/26 §2: `retry-after` is delta seconds, `x-ratelimit-reset` epoch seconds; clamp to one hour.
pub fn rate_limit_deadline(now: u64, retry_after: Option<u64>, reset: Option<u64>) -> u64 {
    let raw = match (retry_after, reset) {
        (Some(r), _) => now.saturating_add(r),
        (None, Some(t)) => t,
        (None, None) => now.saturating_add(BLOCK_DEFAULT),
    };
    raw.clamp(now, now.saturating_add(BLOCK_MAX))
}
/// docs/26 §5: Ready files expire after seven days.
pub fn ready_expired(ready_at: u64, now: u64) -> bool {
    ready_at == 0 || now.saturating_sub(past_stamp(ready_at, now)) >= READY_TTL
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Retry {
    Check,
    Download,
}
/// docs/26 §1: a failed check or a release problem re-checks; transfer/verify failures re-download the same candidate.
pub fn retry_route(failure: Option<Failure>, has_candidate: bool, check_failed: bool) -> Retry {
    if !has_candidate
        || check_failed
        || matches!(
            failure,
            Some(
                Failure::Withdrawn
                    | Failure::UnsupportedRelease
                    | Failure::Incompatible
                    | Failure::RateLimited
            )
        )
    {
        Retry::Check
    } else {
        Retry::Download
    }
}

/// docs/26 §5: restart maps in-flight phases to the nearest safe resting phase; the candidate survives unless Idle.
pub fn restart_phase(phase: Phase, has_candidate: bool, ready_at: u64, now: u64) -> Phase {
    match phase {
        Phase::Current | Phase::Finished => phase,
        _ if !has_candidate => Phase::Idle,
        Phase::AwaitingConfirmation => Phase::AwaitingConfirmation,
        Phase::Ready | Phase::Verifying | Phase::Preparing | Phase::Staging => {
            if ready_expired(ready_at, now) {
                Phase::Available
            } else {
                Phase::Verifying
            }
        }
        _ => Phase::Available,
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct CancelPlan {
    pub remove_files: bool,
    pub new_generation: bool,
    pub to: Phase,
}
/// docs/26 §5: cancel from the phase observed after the task is joined.
pub fn cancel_plan(phase: Phase, has_candidate: bool) -> Option<CancelPlan> {
    let rest = if has_candidate {
        Phase::Available
    } else {
        Phase::Idle
    };
    match phase {
        Phase::Preparing | Phase::Staging => Some(CancelPlan {
            remove_files: false,
            new_generation: false,
            to: Phase::Ready,
        }),
        Phase::Downloading | Phase::Verifying => Some(CancelPlan {
            remove_files: true,
            new_generation: true,
            to: rest,
        }),
        Phase::Checking => Some(CancelPlan {
            remove_files: false,
            new_generation: true,
            to: rest,
        }),
        _ => None,
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Refusal {
    Blocked,
    Coalesced,
}
/// docs/26 §2: why a manual check was not started, so the viewer sees a response.
pub fn manual_refusal(now: u64, last_manual: u64, blocked_until: u64) -> Option<Refusal> {
    if now < blocked_until {
        Some(Refusal::Blocked)
    } else if last_manual != 0 && now.saturating_sub(last_manual) < 60 {
        Some(Refusal::Coalesced)
    } else {
        None
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Reuse {
    Candidate,
    Current,
    Refetch,
}
/// docs/26 §2: a 304 reuses the accepted result without refetching metadata when it is still decisive.
pub fn reuse_on_not_modified(
    cached_id: Option<u64>,
    cached_version: u64,
    installed_code: u64,
    candidate_id: Option<u64>,
) -> Reuse {
    match cached_id {
        Some(id) if candidate_id == Some(id) => Reuse::Candidate,
        Some(_)
            if candidate_id.is_none()
                && cached_version != 0
                && cached_version <= installed_code =>
        {
            Reuse::Current
        }
        _ => Reuse::Refetch,
    }
}
