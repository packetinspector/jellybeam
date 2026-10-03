pub mod policy;

use policy::*;
use reqwest::{Client, Response};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{
    io::{Read, Write},
    path::{Path, PathBuf},
    sync::{Arc, Mutex},
    time::{Duration, SystemTime, UNIX_EPOCH},
};
use tokio::task::JoinHandle;

fn now() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs()
}
#[derive(Default, Serialize, Deserialize)]
struct Journal {
    snapshot: Snapshot,
    cache: Option<Release>,
    etag: Option<String>,
    last_success: u64,
    last_failure: u64,
    last_manual: u64,
    blocked_until: u64,
    #[serde(default)]
    cached_version: u64,
    #[serde(default)]
    last_started: u64,
    #[serde(default)]
    snoozed_until: u64,
    #[serde(default)]
    check_failed: bool,
    /// Viewer notes of `cache` (docs/26 §2); release bodies are not retained.
    #[serde(default)]
    notes: String,
}
impl Journal {
    /// docs/26 §2: stored times from a clock that later moved back must not block checks.
    fn sanitize(&mut self, t: u64) {
        self.last_success = past_stamp(self.last_success, t);
        self.last_failure = past_stamp(self.last_failure, t);
        self.last_manual = past_stamp(self.last_manual, t);
        self.last_started = past_stamp(self.last_started, t);
        self.snapshot.ready_at = past_stamp(self.snapshot.ready_at, t);
        self.snoozed_until = deadline(self.snoozed_until, t, SNOOZE_SECS);
        self.blocked_until = deadline(self.blocked_until, t, BLOCK_MAX);
    }
    /// Keep one notes source: bodies are reduced to `notes` once, then dropped.
    fn migrate_notes(&mut self) {
        if self.notes.is_empty() {
            let body = self
                .cache
                .as_ref()
                .and_then(|r| r.body.as_deref())
                .or_else(|| {
                    self.snapshot
                        .candidate
                        .as_ref()
                        .and_then(|c| c.release.body.as_deref())
                });
            if let Some(body) = body {
                self.notes = viewer_notes(body);
            }
        }
        if let Some(r) = self.cache.as_mut() {
            r.body = None;
        }
        if let Some(c) = self.snapshot.candidate.as_mut() {
            c.release.body = None;
        }
    }
}
struct Inner {
    dir: PathBuf,
    base: String,
    installed: Installed,
    journal: Mutex<Journal>,
    client: Client,
}
pub struct Engine {
    inner: Arc<Inner>,
    commands: Mutex<Option<JoinHandle<()>>>,
    runtime: tokio::runtime::Runtime,
}
fn locked<T>(lock: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    lock.lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner)
}
fn api_base() -> &'static str {
    #[cfg(feature = "update-test-fixture")]
    {
        "https://localhost:18443"
    }
    #[cfg(not(feature = "update-test-fixture"))]
    {
        "https://api.github.com"
    }
}
fn transport_url(raw: &str, _initial: bool) -> bool {
    #[cfg(feature = "update-test-fixture")]
    {
        let Ok(u) = url::Url::parse(raw) else {
            return false;
        };
        u.scheme() == "https"
            && u.host_str() == Some("localhost")
            && u.port() == Some(18443)
            && u.username().is_empty()
            && u.password().is_none()
            && u.fragment().is_none()
    }
    #[cfg(not(feature = "update-test-fixture"))]
    {
        allowed_url(raw, _initial)
    }
}
impl Inner {
    fn save(&self, j: &Journal) -> Result<(), Failure> {
        let bytes = serde_json::to_vec(j).map_err(|_| Failure::Storage)?;
        let part = self.dir.join("journal.tmp");
        let mut f = std::fs::File::create(&part).map_err(|_| Failure::Storage)?;
        f.write_all(&bytes)
            .and_then(|_| f.sync_all())
            .map_err(|_| Failure::Storage)?;
        std::fs::rename(part, self.dir.join("journal.json")).map_err(|_| Failure::Storage)?;
        std::fs::File::open(&self.dir)
            .and_then(|f| f.sync_all())
            .map_err(|_| Failure::Storage)
    }
    fn change(&self, generation: u64, persist: bool, update: impl FnOnce(&mut Journal)) -> bool {
        self.change_when(generation, persist, |_| true, update)
    }
    fn change_when(
        &self,
        generation: u64,
        persist: bool,
        guard: impl FnOnce(&Journal) -> bool,
        update: impl FnOnce(&mut Journal),
    ) -> bool {
        let mut j = locked(&self.journal);
        if j.snapshot.generation != generation || !guard(&j) {
            return false;
        }
        update(&mut j);
        j.snapshot.revision += 1;
        if persist && self.save(&j).is_err() {
            j.snapshot.phase = Phase::Error;
            j.snapshot.failure = Some(Failure::Storage);
        }
        true
    }
    fn paths(&self, generation: u64) -> (PathBuf, PathBuf) {
        let dir = self.dir.join(format!("operation-{generation}"));
        (dir.join("update.apk"), dir.join("update.dm"))
    }
    fn remove_files(&self, generation: u64) {
        let _ = std::fs::remove_dir_all(self.dir.join(format!("operation-{generation}")));
    }
    fn failure(&self, generation: u64, failure: Failure) {
        self.failure_in(generation, None, failure);
    }
    /// docs/26 §2: only failed checks set the check cadence; bad bytes never keep files.
    fn failure_in(&self, generation: u64, from: Option<Phase>, failure: Failure) {
        let applied = self.change_when(
            generation,
            true,
            |j| from.is_none_or(|p| j.snapshot.phase == p),
            |j| {
                let from_check = j.snapshot.phase == Phase::Checking;
                if from_check {
                    j.last_failure = now();
                }
                j.check_failed = from_check;
                j.snapshot.phase = Phase::Error;
                j.snapshot.failure = Some(failure);
            },
        );
        if applied && matches!(failure, Failure::Verification | Failure::Storage) {
            self.remove_files(generation);
        }
    }
    async fn response(
        &self,
        raw: &str,
        api: bool,
        etag: Option<&str>,
    ) -> Result<Response, Failure> {
        let mut url = url::Url::parse(raw).map_err(|_| Failure::Verification)?;
        if !api && !transport_url(raw, true) {
            return Err(Failure::Verification);
        }
        for hop in 0..=5 {
            let mut request = self.client.get(url.clone());
            if api {
                request = request
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2026-03-10");
                if let Some(tag) = etag {
                    request = request.header("If-None-Match", tag);
                }
            }
            let response = request.send().await.map_err(|_| Failure::Network)?;
            let status = response.status();
            if status.is_redirection() && status.as_u16() != 304 {
                if api || hop == 5 {
                    return Err(Failure::Verification);
                }
                let location = response
                    .headers()
                    .get("location")
                    .and_then(|v| v.to_str().ok())
                    .ok_or(Failure::Verification)?;
                let next = url.join(location).map_err(|_| Failure::Verification)?;
                if !transport_url(next.as_str(), false) {
                    return Err(Failure::Verification);
                }
                url = next;
                continue;
            }
            if status.as_u16() == 429
                || (status.as_u16() == 403
                    && (response.headers().contains_key("retry-after")
                        || response
                            .headers()
                            .get("x-ratelimit-remaining")
                            .is_some_and(|v| v == "0")))
            {
                let header = |name: &str| -> Option<u64> {
                    response
                        .headers()
                        .get(name)
                        .and_then(|v| v.to_str().ok())
                        .and_then(|v| v.parse().ok())
                };
                let blocked =
                    rate_limit_deadline(now(), header("retry-after"), header("x-ratelimit-reset"));
                let mut j = locked(&self.journal);
                j.blocked_until = blocked;
                let _ = self.save(&j);
                return Err(Failure::RateLimited);
            }
            if status.as_u16() == 404 {
                return Err(Failure::Withdrawn);
            }
            if status.as_u16() != 200 && !(api && status.as_u16() == 304) {
                return Err(Failure::Network);
            }
            return Ok(response);
        }
        Err(Failure::Verification)
    }
    async fn limited(&self, mut r: Response, limit: u64) -> Result<Vec<u8>, Failure> {
        if r.content_length().is_some_and(|len| len > limit) {
            return Err(Failure::UnsupportedRelease);
        }
        let mut bytes = Vec::new();
        while let Some(chunk) = r.chunk().await.map_err(|_| Failure::Network)? {
            if bytes.len() as u64 + chunk.len() as u64 > limit {
                return Err(Failure::UnsupportedRelease);
            }
            bytes.extend_from_slice(&chunk);
        }
        Ok(bytes)
    }
    async fn metadata(&self, r: &Release) -> Result<Metadata, Failure> {
        let a = select_asset(r, METADATA, 4096)?;
        let response = self.response(&a.browser_download_url, false, None).await?;
        let bytes = self.limited(response, 4096).await?;
        if bytes.len() as u64 != a.size
            || format!("sha256:{:x}", Sha256::digest(&bytes)) != a.digest
        {
            return Err(Failure::Verification);
        }
        serde_json::from_slice(&bytes).map_err(|_| Failure::UnsupportedRelease)
    }
    async fn check(&self, generation: u64) -> Result<(), Failure> {
        let (etag, cached_id, cached_version, candidate_id) = {
            let j = locked(&self.journal);
            (
                j.etag.clone(),
                j.cache.as_ref().map(|r| r.id),
                j.cached_version,
                j.snapshot.candidate.as_ref().map(|c| c.release.id),
            )
        };
        let r = self
            .response(
                &format!("{}/repos/{REPO}/releases/latest", self.base),
                true,
                etag.as_deref(),
            )
            .await?;
        let tag = r
            .headers()
            .get("etag")
            .and_then(|v| v.to_str().ok())
            .filter(|v| v.len() <= 256)
            .map(str::to_owned);
        let not_modified = r.status().as_u16() == 304;
        let reuse = if not_modified {
            reuse_on_not_modified(
                cached_id,
                cached_version,
                self.installed.version_code,
                candidate_id,
            )
        } else {
            Reuse::Refetch
        };
        if reuse != Reuse::Refetch {
            self.change(generation, true, |j| {
                j.etag = tag.or_else(|| j.etag.clone());
                j.last_success = now();
                j.last_failure = 0;
                j.check_failed = false;
                j.snapshot.phase = if reuse == Reuse::Candidate {
                    Phase::Available
                } else {
                    j.snapshot.candidate = None;
                    Phase::Current
                };
                j.snapshot.failure = None;
            });
            return Ok(());
        }
        let (release, notes): (Release, Option<String>) = if not_modified {
            let cached = locked(&self.journal)
                .cache
                .clone()
                .ok_or(Failure::UnsupportedRelease)?;
            (cached, None)
        } else {
            let mut fresh: Release = serde_json::from_slice(&self.limited(r, JSON_LIMIT).await?)
                .map_err(|_| Failure::UnsupportedRelease)?;
            let notes = viewer_notes(fresh.body.as_deref().unwrap_or(""));
            fresh.body = None;
            (fresh, Some(notes))
        };
        eligible_release(&release)?;
        let metadata = self.metadata(&release).await?;
        let version = metadata.version_code;
        let selected = candidate(release.clone(), metadata, &self.installed)?;
        self.change(generation, true, |j| {
            if j.snapshot
                .candidate
                .as_ref()
                .map(|c| c.metadata.version_code)
                != selected.as_ref().map(|c| c.metadata.version_code)
            {
                j.snoozed_until = 0;
            }
            j.cached_version = version;
            j.cache = Some(release);
            if let Some(notes) = notes {
                j.notes = notes;
            }
            j.etag = if not_modified {
                tag.or_else(|| j.etag.clone())
            } else {
                tag
            };
            j.last_success = now();
            j.last_failure = 0;
            j.check_failed = false;
            j.snapshot.phase = if selected.is_some() {
                Phase::Available
            } else {
                Phase::Current
            };
            j.snapshot.candidate = selected;
            j.snapshot.failure = None;
        });
        Ok(())
    }
    async fn download_asset(
        &self,
        generation: u64,
        asset: &Asset,
        path: &Path,
        offset: u64,
    ) -> Result<(), Failure> {
        std::fs::create_dir_all(path.parent().ok_or(Failure::Storage)?)
            .map_err(|_| Failure::Storage)?;
        let part = path.with_extension("part");
        let mut f = std::fs::File::create(&part).map_err(|_| Failure::Storage)?;
        let mut response = self
            .response(&asset.browser_download_url, false, None)
            .await?;
        if response.content_length().is_some_and(|n| n != asset.size) {
            return Err(Failure::Verification);
        }
        let mut count = 0;
        let mut digest = Sha256::new();
        let mut emitted = std::time::Instant::now();
        while let Some(chunk) = response.chunk().await.map_err(|_| Failure::Network)? {
            count += chunk.len() as u64;
            if count > asset.size {
                return Err(Failure::Verification);
            }
            f.write_all(&chunk).map_err(|_| Failure::Storage)?;
            digest.update(&chunk);
            if emitted.elapsed() >= Duration::from_millis(250) {
                self.change(generation, false, |j| j.snapshot.bytes = offset + count);
                emitted = std::time::Instant::now();
            }
        }
        if count != asset.size || format!("sha256:{:x}", digest.finalize()) != asset.digest {
            return Err(Failure::Verification);
        }
        f.sync_all().map_err(|_| Failure::Storage)?;
        std::fs::rename(&part, path).map_err(|_| Failure::Storage)?;
        Ok(())
    }
    async fn download(&self, generation: u64, c: Candidate) -> Result<(), Failure> {
        let (apk, dm) = self.paths(generation);
        self.download_asset(generation, &c.apk, &apk, 0).await?;
        if let Some(profile) = &c.profile {
            self.download_asset(generation, profile, &dm, c.apk.size)
                .await?;
        }
        self.change(generation, true, |j| {
            j.snapshot.bytes = j.snapshot.total;
            j.snapshot.phase = Phase::Verifying;
            j.snapshot.ready_at = now();
        });
        Ok(())
    }
    fn rehash(&self, generation: u64, c: &Candidate) -> Result<(), Failure> {
        let (apk, dm) = self.paths(generation);
        hash_file(&apk, &c.apk)?;
        if let Some(p) = &c.profile {
            hash_file(&dm, p)?;
        }
        Ok(())
    }
    async fn prepare(&self, generation: u64, c: Candidate) -> Result<(), Failure> {
        let r = self
            .response(
                &format!("{}/repos/{REPO}/releases/{}", self.base, c.release.id),
                true,
                None,
            )
            .await?;
        let fresh: Release = serde_json::from_slice(&self.limited(r, JSON_LIMIT).await?)
            .map_err(|_| Failure::Withdrawn)?;
        eligible_release(&fresh).map_err(|_| Failure::Withdrawn)?;
        if fresh.id != c.release.id || fresh.tag_name != c.release.tag_name {
            return Err(Failure::Withdrawn);
        }
        for expected in [&c.apk, &select_asset(&c.release, METADATA, 4096)?]
            .into_iter()
            .chain(c.profile.iter())
        {
            if select_asset(&fresh, &expected.name, APK_LIMIT)? != *expected {
                return Err(Failure::Withdrawn);
            }
        }
        self.rehash(generation, &c)?;
        self.change(generation, true, |j| j.snapshot.phase = Phase::Staging);
        Ok(())
    }
}
pub fn hash_file(path: &Path, asset: &Asset) -> Result<(), Failure> {
    let mut f = std::fs::File::open(path).map_err(|_| Failure::Verification)?;
    if f.metadata().map_err(|_| Failure::Verification)?.len() != asset.size {
        return Err(Failure::Verification);
    }
    let mut digest = Sha256::new();
    let mut buffer = [0; 65536];
    loop {
        let n = f.read(&mut buffer).map_err(|_| Failure::Verification)?;
        if n == 0 {
            break;
        }
        digest.update(&buffer[..n]);
    }
    if format!("sha256:{:x}", digest.finalize()) != asset.digest {
        return Err(Failure::Verification);
    }
    Ok(())
}
impl Engine {
    pub fn new(dir: PathBuf, installed: Installed) -> Result<Self, Failure> {
        std::fs::create_dir_all(&dir).map_err(|_| Failure::Storage)?;
        let mut journal: Journal = std::fs::File::open(dir.join("journal.json"))
            .ok()
            .and_then(|f| {
                let mut bytes = Vec::new();
                f.take(2 * 1024 * 1024 + 1).read_to_end(&mut bytes).ok()?;
                (bytes.len() <= 2 * 1024 * 1024).then_some(bytes)
            })
            .and_then(|b| serde_json::from_slice(&b).ok())
            .unwrap_or_default();
        let builder = Client::builder()
            .user_agent("Jellybeam-TV-Updater")
            .redirect(reqwest::redirect::Policy::none())
            .connect_timeout(Duration::from_secs(10))
            .read_timeout(Duration::from_secs(30))
            .timeout(Duration::from_secs(600));
        #[cfg(feature = "update-test-fixture")]
        let builder = builder.add_root_certificate(
            reqwest::Certificate::from_pem(include_bytes!(env!("JELLYBEAM_UPDATE_TEST_CA")))
                .map_err(|_| Failure::Verification)?,
        );
        let t = now();
        journal.sanitize(t);
        journal.migrate_notes();
        if journal
            .snapshot
            .candidate
            .as_ref()
            .is_some_and(|c| c.metadata.version_code <= installed.version_code)
        {
            journal.snapshot.phase = Phase::Finished;
            journal.snapshot.candidate = None;
        } else {
            let mut phase = restart_phase(
                journal.snapshot.phase,
                journal.snapshot.candidate.is_some(),
                journal.snapshot.ready_at,
                t,
            );
            let files = dir.join(format!("operation-{}", journal.snapshot.generation));
            if phase == Phase::Verifying && !files.exists() {
                phase = Phase::Available;
            }
            journal.snapshot.phase = phase;
            match phase {
                Phase::Idle => {
                    journal.snapshot.candidate = None;
                    journal.snapshot.failure = None;
                }
                Phase::Available => {
                    journal.snapshot.failure = None;
                    journal.snapshot.ready_at = 0;
                    journal.snapshot.bytes = 0;
                    journal.check_failed = false;
                }
                _ => {}
            }
        }
        // docs/26 §5: incomplete operations never resume writes after restart.
        for entry in std::fs::read_dir(&dir)
            .map_err(|_| Failure::Storage)?
            .flatten()
        {
            if entry
                .file_name()
                .to_string_lossy()
                .starts_with("operation-")
                && (!matches!(
                    journal.snapshot.phase,
                    Phase::Verifying | Phase::AwaitingConfirmation
                ) || entry.path()
                    != dir.join(format!("operation-{}", journal.snapshot.generation)))
            {
                let _ = std::fs::remove_dir_all(entry.path());
            }
        }
        let inner = Arc::new(Inner {
            dir,
            base: api_base().to_owned(),
            installed,
            journal: Mutex::new(journal),
            client: builder.build().map_err(|_| Failure::Network)?,
        });
        let runtime = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(1)
            .enable_all()
            .build()
            .map_err(|_| Failure::Network)?;
        let engine = Self {
            inner,
            commands: Mutex::new(None),
            runtime,
        };
        engine.inner.save(&locked(&engine.inner.journal))?;
        Ok(engine)
    }
    pub fn snapshot(&self) -> Snapshot {
        let j = locked(&self.inner.journal);
        let t = now();
        let mut s = j.snapshot.clone();
        s.available_notice = s.candidate.is_some()
            && !matches!(s.phase, Phase::Finished | Phase::Current)
            && t >= deadline(j.snoozed_until, t, SNOOZE_SECS);
        s.last_checked = past_stamp(j.last_started.max(j.last_success).max(j.last_failure), t);
        let release = s.candidate.as_ref().map(|c| &c.release).or_else(|| {
            (j.cached_version == self.inner.installed.version_code)
                .then_some(j.cache.as_ref())
                .flatten()
        });
        if let Some(r) = release {
            s.notes.clone_from(&j.notes);
            s.version_label = r.tag_name.clone();
            s.published_at = r.published_at.clone().unwrap_or_default();
        }
        s
    }
    pub fn snooze(&self) {
        let _task = locked(&self.commands);
        let mut j = locked(&self.inner.journal);
        if j.snapshot.phase.busy() {
            return;
        }
        j.snoozed_until = now().saturating_add(SNOOZE_SECS);
        j.snapshot.revision += 1;
        if self.inner.save(&j).is_err() {
            j.snapshot.failure = Some(Failure::Storage);
        }
    }
    pub fn retry(&self, generation: u64, free_bytes: u64) {
        let route = {
            let j = locked(&self.inner.journal);
            if j.snapshot.generation != generation {
                return;
            }
            retry_route(
                j.snapshot.failure,
                j.snapshot.candidate.is_some(),
                j.check_failed,
            )
        };
        match route {
            Retry::Check => {
                self.check(true);
            }
            Retry::Download => self.download(generation, free_bytes),
        }
    }
    pub fn paths(&self, s: &Snapshot) -> (String, Option<String>) {
        let (apk, dm) = self.inner.paths(s.generation);
        (
            apk.to_string_lossy().into_owned(),
            s.candidate
                .as_ref()
                .and_then(|c| c.profile.as_ref())
                .map(|_| dm.to_string_lossy().into_owned()),
        )
    }
    pub fn check(&self, manual: bool) -> u64 {
        let mut task = locked(&self.commands);
        let mut j = locked(&self.inner.journal);
        let t = now();
        j.sanitize(t);
        if j.snapshot.phase.busy() || j.snapshot.phase == Phase::Ready {
            return j.snapshot.generation;
        }
        if !due(
            t,
            j.last_success,
            j.last_failure,
            j.last_manual,
            j.blocked_until,
            manual,
        ) {
            if manual {
                if let Some(refusal) = manual_refusal(t, j.last_manual, j.blocked_until) {
                    j.snapshot.revision += 1;
                    if refusal == Refusal::Blocked {
                        j.snapshot.failure = Some(Failure::RateLimited);
                        if matches!(
                            j.snapshot.phase,
                            Phase::Idle | Phase::Current | Phase::Error
                        ) {
                            j.snapshot.phase = Phase::Error;
                        }
                    }
                }
            }
            return j.snapshot.generation;
        }
        drop(j);
        if let Some(handle) = task.take() {
            handle.abort();
            let _ = self.runtime.block_on(handle);
        }
        let mut j = locked(&self.inner.journal);
        let (apk, _) = self.inner.paths(j.snapshot.generation);
        if let Some(dir) = apk.parent().filter(|dir| dir.exists()) {
            if std::fs::remove_dir_all(dir).is_err() {
                j.snapshot.phase = Phase::Error;
                j.snapshot.failure = Some(Failure::Storage);
                j.snapshot.revision += 1;
                return j.snapshot.generation;
            }
        }
        j.snapshot.ready_at = 0;
        j.snapshot.bytes = 0;
        j.last_started = now();
        if manual {
            j.last_manual = now();
        }
        j.snapshot.generation += 1;
        let generation = j.snapshot.generation;
        j.snapshot.phase = Phase::Checking;
        j.snapshot.failure = None;
        j.snapshot.revision += 1;
        if self.inner.save(&j).is_err() {
            j.snapshot.phase = Phase::Error;
            j.snapshot.failure = Some(Failure::Storage);
            return generation;
        }
        drop(j);
        let inner = self.inner.clone();
        *task = Some(self.runtime.spawn(async move {
            let result = tokio::time::timeout(Duration::from_secs(15), inner.check(generation))
                .await
                .unwrap_or(Err(Failure::Network));
            if let Err(e) = result {
                inner.failure(generation, e);
            }
        }));
        generation
    }
    pub fn download(&self, generation: u64, free_bytes: u64) {
        let mut task = locked(&self.commands);
        let mut j = locked(&self.inner.journal);
        if j.snapshot.generation != generation
            || !matches!(j.snapshot.phase, Phase::Available | Phase::Error)
        {
            return;
        }
        let Some(c) = j.snapshot.candidate.clone() else {
            return;
        };
        let total = c.apk.size + c.profile.as_ref().map_or(0, |p| p.size);
        if free_bytes < total.saturating_mul(2).saturating_add(8 * 1024 * 1024) {
            drop(j);
            self.inner.failure(generation, Failure::Storage);
            return;
        }
        j.snapshot.phase = Phase::Downloading;
        j.snapshot.failure = None;
        j.snapshot.bytes = 0;
        j.snapshot.total = total;
        j.snapshot.revision += 1;
        if self.inner.save(&j).is_err() {
            j.snapshot.phase = Phase::Error;
            j.snapshot.failure = Some(Failure::Storage);
            return;
        }
        drop(j);
        let inner = self.inner.clone();
        *task = Some(self.runtime.spawn(async move {
            let result =
                tokio::time::timeout(Duration::from_secs(600), inner.download(generation, c))
                    .await
                    .unwrap_or(Err(Failure::Network));
            if let Err(e) = result {
                let (apk, _) = inner.paths(generation);
                if let Some(dir) = apk.parent() {
                    let _ = std::fs::remove_dir_all(dir);
                }
                inner.failure(generation, e);
            }
        }));
    }
    pub fn verified(&self, generation: u64, facts: Verified) {
        // The rehash (up to 128 MiB) runs without `commands` so cancel() is never blocked on it.
        let candidate = {
            let j = locked(&self.inner.journal);
            if j.snapshot.generation != generation || j.snapshot.phase != Phase::Verifying {
                return;
            }
            j.snapshot.candidate.clone()
        };
        let valid = candidate.is_some_and(|c| {
            self.inner.rehash(generation, &c).is_ok()
                && accepts_apk(&c, &self.inner.installed, &facts)
        });
        if valid {
            self.inner.change_when(
                generation,
                true,
                |j| j.snapshot.phase == Phase::Verifying,
                |j| j.snapshot.phase = Phase::Ready,
            );
        } else {
            self.inner
                .failure_in(generation, Some(Phase::Verifying), Failure::Verification);
        }
    }
    pub fn prepare(&self, generation: u64) {
        let mut task = locked(&self.commands);
        let (candidate, expired) = {
            let j = locked(&self.inner.journal);
            if j.snapshot.generation != generation || j.snapshot.phase != Phase::Ready {
                return;
            }
            (
                j.snapshot.candidate.clone(),
                ready_expired(j.snapshot.ready_at, now()),
            )
        };
        if expired {
            self.inner.failure(generation, Failure::Verification);
            return;
        }
        let Some(c) = candidate else { return };
        self.inner
            .change(generation, true, |j| j.snapshot.phase = Phase::Preparing);
        let inner = self.inner.clone();
        *task = Some(self.runtime.spawn(async move {
            let result =
                tokio::time::timeout(Duration::from_secs(15), inner.prepare(generation, c))
                    .await
                    .unwrap_or(Err(Failure::Network));
            if let Err(e) = result {
                inner.failure(generation, e);
            }
        }));
    }
    pub fn cancel(&self) {
        let mut task = locked(&self.commands);
        if locked(&self.inner.journal).snapshot.phase == Phase::AwaitingConfirmation {
            return;
        }
        if let Some(handle) = task.take() {
            handle.abort();
            let _ = self.runtime.block_on(handle);
        }
        // The task may have advanced before the abort: act on the phase seen after the join.
        let (generation, phase, has_candidate) = {
            let j = locked(&self.inner.journal);
            (
                j.snapshot.generation,
                j.snapshot.phase,
                j.snapshot.candidate.is_some(),
            )
        };
        let Some(plan) = cancel_plan(phase, has_candidate) else {
            return;
        };
        let applied = self.inner.change_when(
            generation,
            true,
            |j| j.snapshot.phase == phase,
            |j| {
                j.snapshot.phase = plan.to;
                if plan.new_generation {
                    // Download files are bound to the old generation; a canceled operation starts fresh.
                    j.snapshot.generation += 1;
                    j.snapshot.failure = None;
                    j.snapshot.bytes = 0;
                }
            },
        );
        if applied && plan.remove_files {
            self.inner.remove_files(generation);
        }
    }
    pub fn installer_result(&self, generation: u64, outcome: &str) {
        let _task = locked(&self.commands);
        let (current, phase) = {
            let j = locked(&self.inner.journal);
            (j.snapshot.generation, j.snapshot.phase)
        };
        if current != generation {
            return;
        }
        let allowed = match outcome {
            "committed" => matches!(
                phase,
                Phase::Staging | Phase::Verifying | Phase::Ready | Phase::AwaitingConfirmation
            ),
            "success" => matches!(phase, Phase::AwaitingConfirmation | Phase::Finished),
            "canceled" | "failed" => matches!(phase, Phase::Staging | Phase::AwaitingConfirmation),
            _ => false,
        };
        if !allowed {
            return;
        }
        self.inner.change(generation, true, |j| {
            j.snapshot.phase = match outcome {
                "committed" => Phase::AwaitingConfirmation,
                "success" => Phase::Finished,
                "canceled" => Phase::Ready,
                _ => Phase::Error,
            };
            j.snapshot.failure = if outcome == "failed" {
                Some(Failure::Installation)
            } else {
                None
            };
        });
    }
}
impl Drop for Engine {
    fn drop(&mut self) {
        if let Some(task) = locked(&self.commands).take() {
            task.abort();
            let _ = self.runtime.block_on(task);
        }
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;
    fn installed() -> Installed {
        Installed {
            package: "tv.jellybeam".into(),
            version_code: 1,
            sdk: 34,
            abis: vec!["arm64-v8a".into()],
            signer: SIGNER.into(),
        }
    }
    fn asset(name: &str, id: u64) -> Asset {
        Asset {
            name: name.into(),
            id,
            size: 3,
            digest: format!("sha256:{:x}", Sha256::digest(b"abc")),
            state: "uploaded".into(),
            browser_download_url: format!(
                "https://github.com/{REPO}/releases/download/v-test/{name}"
            ),
        }
    }
    fn release() -> Release {
        Release {
            id: 1,
            tag_name: "v-test".into(),
            draft: false,
            prerelease: false,
            immutable: true,
            published_at: Some("2026-10-03T00:00:00Z".into()),
            body: Some("## Changes\n- Faster\n\n## Install\nHidden".into()),
            assets: vec![
                asset("test.apk", 1),
                asset("test.dm", 2),
                asset(METADATA, 3),
            ],
        }
    }
    fn metadata() -> Metadata {
        Metadata {
            schema: 1,
            package: "tv.jellybeam".into(),
            version_code: 2,
            min_sdk: 23,
            abis: vec!["arm64-v8a".into()],
            apk_asset: "test.apk".into(),
            profiles: vec![Profile {
                min_sdk: 28,
                max_sdk: 36,
                asset: "test.dm".into(),
            }],
        }
    }
    #[test]
    fn numeric_order_and_sdk_abi_gates() {
        let i = installed();
        let c = candidate(release(), metadata(), &i).unwrap().unwrap();
        assert_eq!(c.profile.unwrap().name, "test.dm");
        let mut m = metadata();
        m.version_code = 1;
        assert!(candidate(release(), m, &i).unwrap().is_none());
        let mut m = metadata();
        m.min_sdk = 35;
        assert_eq!(
            candidate(release(), m, &i).unwrap_err(),
            Failure::Incompatible
        );
        let mut m = metadata();
        m.abis = vec!["x86_64".into()];
        assert_eq!(
            candidate(release(), m, &i).unwrap_err(),
            Failure::Incompatible
        );
    }
    #[test]
    fn immutable_stable_only() {
        for flag in 0..3 {
            let mut r = release();
            match flag {
                0 => r.immutable = false,
                1 => r.prerelease = true,
                _ => r.draft = true,
            }
            assert_eq!(
                candidate(r, metadata(), &installed()).unwrap_err(),
                Failure::UnsupportedRelease
            );
        }
    }
    #[test]
    fn requires_unique_uploaded_assets_and_sha256() {
        let mut r = release();
        r.assets.push(r.assets[0].clone());
        assert_eq!(
            select_asset(&r, "test.apk", APK_LIMIT).unwrap_err(),
            Failure::UnsupportedRelease
        );
        for invalid in ["", "sha256:bad", "md5:abc"] {
            let mut r = release();
            r.assets[0].digest = invalid.into();
            assert!(select_asset(&r, "test.apk", APK_LIMIT).is_err());
        }
        let mut r = release();
        r.assets[0].size = APK_LIMIT + 1;
        assert!(select_asset(&r, "test.apk", APK_LIMIT).is_err());
        let mut r = release();
        r.assets[0].state = "new".into();
        assert!(select_asset(&r, "test.apk", APK_LIMIT).is_err());
    }
    #[test]
    fn requires_nonoverlapping_profiles_and_coverage() {
        let mut m = metadata();
        m.profiles.push(m.profiles[0].clone());
        assert!(candidate(release(), m, &installed()).is_err());
        let mut m = metadata();
        m.profiles.clear();
        assert!(candidate(release(), m.clone(), &installed()).is_err());
        let mut i = installed();
        i.sdk = 23;
        assert!(candidate(release(), m, &i)
            .unwrap()
            .unwrap()
            .profile
            .is_none());
    }
    #[test]
    fn strict_metadata_rejects_duplicates_numbers_and_unknown_schema() {
        let good = serde_json::to_string(&metadata()).unwrap();
        let duplicate = good.replacen("\"schema\":1", "\"schema\":1,\"schema\":1", 1);
        assert!(serde_json::from_str::<Metadata>(&duplicate).is_err());
        assert!(serde_json::from_str::<Metadata>(
            &good.replace("\"version_code\":2", "\"version_code\":2.5")
        )
        .is_err());
        assert!(serde_json::from_str::<Metadata>(
            &good.replace("\"version_code\":2", "\"version_code\":-1")
        )
        .is_err());
        let mut m = metadata();
        m.schema = 2;
        assert!(candidate(release(), m, &installed()).is_err());
    }
    #[test]
    fn exact_url_and_redirect_hosts() {
        assert!(allowed_url(
            "https://github.com/packetinspector/jellybeam/releases/download/v-test/test.apk",
            true
        ));
        assert!(allowed_url(
            "https://release-assets.githubusercontent.com/file?token=synthetic",
            false
        ));
        for raw in [
            "http://github.com/packetinspector/jellybeam/releases/download/v-test/test.apk",
            "https://github.com.evil.invalid/file",
            "https://example.invalid/file",
            "https://user@github.com/packetinspector/jellybeam/releases/download/v/test.apk",
            "https://github.com:444/packetinspector/jellybeam/releases/download/v/test.apk",
            "https://github.com/other/repo/releases/download/v/test.apk",
        ] {
            assert!(!allowed_url(raw, true), "{raw}");
        }
        assert!(!allowed_url(
            "https://sub.release-assets.githubusercontent.com/file",
            false
        ));
        for name in ["../x.apk", ".hidden", "a/b.apk", "a%2fb.apk"] {
            assert!(!safe_name(name));
        }
    }
    #[test]
    fn notes_hide_install_and_bound_utf8() {
        assert_eq!(
            viewer_notes("## Changes\n- Faster\n## Install\nHidden"),
            "## Changes\n- Faster"
        );
        for long in ["🎬".repeat(NOTES_LIMIT), "x".repeat(NOTES_LIMIT * 2)] {
            let notes = viewer_notes(&long);
            assert!(notes.ends_with("Notes shortened."));
            assert!(notes.len() <= NOTES_LIMIT);
        }
        assert_eq!(viewer_notes("a\n  ## INSTALL  \nb"), "a");
    }
    #[test]
    fn schedule_respects_manual_failure_and_server_backoff() {
        assert!(due(100000, 0, 0, 0, 0, false));
        assert!(!due(100000, 99999, 0, 0, 0, false));
        assert!(!due(100000, 0, 99999, 0, 0, false));
        assert!(due(100000, 99999, 99999, 0, 0, true));
        assert!(!due(100000, 0, 0, 99999, 0, true));
        assert!(!due(100000, 0, 0, 0, 100001, true));
        assert!(!due(1, 100, 100, 100, 0, false));
    }
    #[test]
    fn independent_signer_and_actual_apk_facts() {
        let i = installed();
        let c = candidate(release(), metadata(), &i).unwrap().unwrap();
        let mut f = Verified {
            package: i.package.clone(),
            code: 2,
            sdk: 23,
            signer: SIGNER.into(),
            abis: i.abis.clone(),
            valid: true,
        };
        assert!(accepts_apk(&c, &i, &f));
        f.valid = false;
        assert!(!accepts_apk(&c, &i, &f));
        f.valid = true;
        f.signer = "other".into();
        assert!(!accepts_apk(&c, &i, &f));
        f.signer = SIGNER.into();
        f.package = "other".into();
        assert!(!accepts_apk(&c, &i, &f));
        f.package = i.package.clone();
        f.code = 3;
        assert!(!accepts_apk(&c, &i, &f));
        f.code = 2;
        f.abis = vec!["x86_64".into()];
        assert!(!accepts_apk(&c, &i, &f));
    }
    #[test]
    fn hash_and_size_are_independent_checks() {
        let d = tempfile::tempdir().unwrap();
        let path = d.path().join("apk");
        std::fs::write(&path, b"abc").unwrap();
        assert!(hash_file(&path, &asset("test.apk", 1)).is_ok());
        std::fs::write(&path, b"abd").unwrap();
        assert_eq!(
            hash_file(&path, &asset("test.apk", 1)),
            Err(Failure::Verification)
        );
        std::fs::write(&path, b"ab").unwrap();
        assert!(hash_file(&path, &asset("test.apk", 1)).is_err());
    }
    #[test]
    fn stale_generation_cannot_publish_or_rewrite_journal() {
        let d = tempfile::tempdir().unwrap();
        let engine = Engine::new(d.path().into(), installed()).unwrap();
        engine.inner.change(0, true, |j| {
            j.snapshot.generation = 4;
            j.snapshot.phase = Phase::AwaitingConfirmation;
        });
        assert!(!engine
            .inner
            .change(3, true, |j| j.snapshot.phase = Phase::Ready));
        let before = engine.snapshot().revision;
        engine.installer_result(5, "success");
        engine.installer_result(3, "success");
        let s = engine.snapshot();
        assert_eq!((s.phase, s.revision), (Phase::AwaitingConfirmation, before));
    }
    #[test]
    fn cancel_joins_native_task_and_cleans_partial() {
        let d = tempfile::tempdir().unwrap();
        let engine = Engine::new(d.path().into(), installed()).unwrap();
        let (apk, _) = engine.inner.paths(0);
        std::fs::create_dir_all(apk.parent().unwrap()).unwrap();
        std::fs::write(apk.with_extension("part"), b"partial").unwrap();
        engine
            .inner
            .change(0, true, |j| j.snapshot.phase = Phase::Downloading);
        let destroyed = Arc::new(std::sync::atomic::AtomicBool::new(false));
        struct Guard(Arc<std::sync::atomic::AtomicBool>);
        impl Drop for Guard {
            fn drop(&mut self) {
                self.0.store(true, std::sync::atomic::Ordering::SeqCst);
            }
        }
        let (started, ready) = std::sync::mpsc::channel();
        let end = destroyed.clone();
        *locked(&engine.commands) = Some(engine.runtime.spawn(async move {
            let _g = Guard(end);
            started.send(()).unwrap();
            std::future::pending::<()>().await;
        }));
        ready.recv_timeout(Duration::from_secs(2)).unwrap();
        engine.cancel();
        assert!(destroyed.load(std::sync::atomic::Ordering::SeqCst));
        assert!(!apk.with_extension("part").exists());
        assert_eq!(engine.snapshot().generation, 1);
    }
    fn facts() -> Verified {
        Verified {
            package: "tv.jellybeam".into(),
            code: 2,
            sdk: 23,
            signer: SIGNER.into(),
            abis: vec!["arm64-v8a".into()],
            valid: true,
        }
    }
    fn write_files(engine: &Engine, bytes: &[u8]) {
        let (apk, dm) = engine.inner.paths(0);
        std::fs::create_dir_all(apk.parent().unwrap()).unwrap();
        std::fs::write(apk, bytes).unwrap();
        std::fs::write(dm, bytes).unwrap();
    }
    #[test]
    fn restart_reverifies_ready_and_recognizes_installed_target() {
        let d = tempfile::tempdir().unwrap();
        {
            let engine = Engine::new(d.path().into(), installed()).unwrap();
            engine.inner.change(0, true, |j| {
                j.snapshot.candidate = candidate(release(), metadata(), &installed()).unwrap();
                j.snapshot.phase = Phase::Ready;
                j.snapshot.ready_at = now();
            });
            write_files(&engine, b"abc");
        }
        {
            let engine = Engine::new(d.path().into(), installed()).unwrap();
            assert_eq!(engine.snapshot().phase, Phase::Verifying);
            let mut bad = facts();
            bad.valid = false;
            engine.verified(0, bad);
            assert_eq!(engine.snapshot().failure, Some(Failure::Verification));
            assert!(!engine.inner.paths(0).0.exists());
        }
        let d2 = tempfile::tempdir().unwrap();
        {
            let engine = Engine::new(d2.path().into(), installed()).unwrap();
            engine.inner.change(0, true, |j| {
                j.snapshot.candidate = candidate(release(), metadata(), &installed()).unwrap();
                j.snapshot.phase = Phase::Preparing;
                j.snapshot.ready_at = now();
            });
            write_files(&engine, b"abc");
        }
        let engine = Engine::new(d2.path().into(), installed()).unwrap();
        assert_eq!(engine.snapshot().phase, Phase::Verifying);
        engine.verified(0, facts());
        assert_eq!(engine.snapshot().phase, Phase::Ready);
        // Corrupt retained bytes fail the rehash and are removed.
        drop(engine);
        let engine = Engine::new(d2.path().into(), installed()).unwrap();
        assert_eq!(engine.snapshot().phase, Phase::Verifying);
        write_files(&engine, b"abd");
        engine.verified(0, facts());
        assert_eq!(engine.snapshot().failure, Some(Failure::Verification));
        assert!(!engine.inner.paths(0).0.exists());
        let mut i = installed();
        i.version_code = 2;
        let engine = Engine::new(d.path().into(), i).unwrap();
        assert_eq!(engine.snapshot().phase, Phase::Finished);
    }
    #[test]
    fn restart_maps_inflight_phases_and_expiry_keeping_candidate() {
        for (phase, ready_at, expect, files) in [
            (Phase::Downloading, 0, Phase::Available, false),
            (Phase::Checking, 0, Phase::Available, false),
            (Phase::Ready, now() - 8 * 86400, Phase::Available, false),
            (Phase::Verifying, now(), Phase::Verifying, true),
            (Phase::Staging, now() + 10_000_000, Phase::Verifying, true),
        ] {
            let d = tempfile::tempdir().unwrap();
            {
                let engine = Engine::new(d.path().into(), installed()).unwrap();
                engine.inner.change(0, true, |j| {
                    j.snapshot.candidate = candidate(release(), metadata(), &installed()).unwrap();
                    j.snapshot.phase = phase;
                    j.snapshot.ready_at = ready_at;
                });
                write_files(&engine, b"abc");
            }
            let engine = Engine::new(d.path().into(), installed()).unwrap();
            let s = engine.snapshot();
            assert_eq!(s.phase, expect, "{phase:?}");
            assert!(s.candidate.is_some());
            assert_eq!(engine.inner.paths(0).0.exists(), files, "{phase:?}");
        }
        let d = tempfile::tempdir().unwrap();
        {
            let engine = Engine::new(d.path().into(), installed()).unwrap();
            engine
                .inner
                .change(0, true, |j| j.snapshot.phase = Phase::Downloading);
        }
        let engine = Engine::new(d.path().into(), installed()).unwrap();
        assert_eq!(engine.snapshot().phase, Phase::Idle);
    }
    #[test]
    fn restart_policy_is_pinned() {
        let t = 1_000_000;
        assert_eq!(
            restart_phase(Phase::Preparing, true, t, t),
            Phase::Verifying
        );
        assert_eq!(restart_phase(Phase::Preparing, false, t, t), Phase::Idle);
        assert_eq!(restart_phase(Phase::Ready, true, 0, t), Phase::Available);
        assert_eq!(
            restart_phase(Phase::AwaitingConfirmation, true, 1, t),
            Phase::AwaitingConfirmation
        );
        assert_eq!(restart_phase(Phase::Current, false, 0, t), Phase::Current);
        assert_eq!(restart_phase(Phase::Error, true, 0, t), Phase::Available);
    }
    #[test]
    fn retry_routes_by_failed_operation() {
        use Failure::*;
        assert_eq!(
            retry_route(Some(Verification), true, false),
            Retry::Download
        );
        assert_eq!(retry_route(Some(Storage), true, false), Retry::Download);
        assert_eq!(retry_route(Some(Network), true, false), Retry::Download);
        assert_eq!(retry_route(Some(Network), true, true), Retry::Check);
        assert_eq!(retry_route(Some(Network), false, false), Retry::Check);
        for f in [Withdrawn, UnsupportedRelease, Incompatible, RateLimited] {
            assert_eq!(retry_route(Some(f), true, false), Retry::Check);
        }
    }
    #[test]
    fn failures_set_check_cadence_only_for_checks() {
        let d = tempfile::tempdir().unwrap();
        let engine = Engine::new(d.path().into(), installed()).unwrap();
        engine
            .inner
            .change(0, true, |j| j.snapshot.phase = Phase::Downloading);
        engine.inner.failure(0, Failure::Network);
        assert_eq!(locked(&engine.inner.journal).last_failure, 0);
        assert!(!locked(&engine.inner.journal).check_failed);
        engine
            .inner
            .change(0, true, |j| j.snapshot.phase = Phase::Checking);
        engine.inner.failure(0, Failure::Network);
        let j = locked(&engine.inner.journal);
        assert!(j.last_failure > 0 && j.check_failed);
    }
    #[test]
    fn verification_failure_removes_retained_files() {
        let d = tempfile::tempdir().unwrap();
        let engine = Engine::new(d.path().into(), installed()).unwrap();
        engine.inner.change(0, true, |j| {
            j.snapshot.candidate = candidate(release(), metadata(), &installed()).unwrap();
            j.snapshot.phase = Phase::Ready;
            j.snapshot.ready_at = now() - 8 * 86400;
        });
        write_files(&engine, b"abc");
        engine.prepare(0);
        assert_eq!(engine.snapshot().failure, Some(Failure::Verification));
        assert!(!engine.inner.paths(0).0.exists());
    }
    #[test]
    fn rate_limit_deadline_prefers_retry_after_and_clamps() {
        let t = 1_000_000;
        assert_eq!(rate_limit_deadline(t, Some(120), Some(t + 5)), t + 120);
        assert_eq!(rate_limit_deadline(t, None, Some(t + 900)), t + 900);
        assert_eq!(rate_limit_deadline(t, None, None), t + 300);
        assert_eq!(rate_limit_deadline(t, Some(u64::MAX), None), t + BLOCK_MAX);
        assert_eq!(rate_limit_deadline(t, None, Some(u64::MAX)), t + BLOCK_MAX);
        assert_eq!(rate_limit_deadline(t, None, Some(5)), t);
    }
    #[test]
    fn future_timestamps_are_stale() {
        let t = 1_000_000;
        assert_eq!(past_stamp(t + CLOCK_SLACK, t), t + CLOCK_SLACK);
        assert_eq!(past_stamp(t + CLOCK_SLACK + 1, t), t);
        assert_eq!(past_stamp(0, t), 0);
        assert_eq!(deadline(t + 10 * 86400, t, SNOOZE_SECS), t + SNOOZE_SECS);
        assert_eq!(deadline(t + 5, t, SNOOZE_SECS), t + 5);
        assert!(ready_expired(0, t));
        assert!(!ready_expired(t + 10_000_000, t));
        let d = tempfile::tempdir().unwrap();
        let engine = Engine::new(d.path().into(), installed()).unwrap();
        let far = now() + 100 * 86400;
        {
            let mut j = locked(&engine.inner.journal);
            j.last_success = far;
            j.snoozed_until = far;
            j.blocked_until = far;
        }
        // Sanitized on the next check: the far-future success/block no longer suppresses it.
        let gen = engine.check(true);
        let j = locked(&engine.inner.journal);
        assert!(j.blocked_until <= now() + BLOCK_MAX);
        assert!(j.snoozed_until <= now() + SNOOZE_SECS);
        assert!(j.last_success <= now() + CLOCK_SLACK);
        assert_eq!(gen, 0);
    }
    #[test]
    fn refused_manual_checks_are_visible() {
        assert_eq!(manual_refusal(100, 0, 200), Some(Refusal::Blocked));
        assert_eq!(manual_refusal(100, 70, 0), Some(Refusal::Coalesced));
        assert_eq!(manual_refusal(100, 30, 0), None);
        let d = tempfile::tempdir().unwrap();
        let engine = Engine::new(d.path().into(), installed()).unwrap();
        engine.inner.change(0, true, |j| {
            j.snapshot.phase = Phase::Current;
            j.blocked_until = now() + 600;
        });
        let before = engine.snapshot().revision;
        assert_eq!(engine.check(true), 0);
        let s = engine.snapshot();
        assert!(s.revision > before);
        assert_eq!(
            (s.phase, s.failure),
            (Phase::Error, Some(Failure::RateLimited))
        );
        // Automatic checks stay silent.
        let before = s.revision;
        engine.check(false);
        assert_eq!(engine.snapshot().revision, before);
        // Coalesced: revision bump only.
        engine.inner.change(0, true, |j| {
            j.snapshot.phase = Phase::Current;
            j.snapshot.failure = None;
            j.blocked_until = 0;
            j.last_manual = now();
        });
        let before = engine.snapshot().revision;
        engine.check(true);
        let s = engine.snapshot();
        assert!(s.revision > before);
        assert_eq!((s.phase, s.failure), (Phase::Current, None));
    }
    #[test]
    fn cancel_plan_follows_observed_phase() {
        let p = |ph, c| cancel_plan(ph, c);
        assert_eq!(p(Phase::Preparing, true).unwrap().to, Phase::Ready);
        assert!(!p(Phase::Staging, true).unwrap().remove_files);
        let dl = p(Phase::Downloading, false).unwrap();
        assert!(dl.remove_files && dl.new_generation && dl.to == Phase::Idle);
        assert_eq!(p(Phase::Verifying, true).unwrap().to, Phase::Available);
        assert!(p(Phase::Checking, true).unwrap().new_generation);
        assert!(p(Phase::Ready, true).is_none());
        assert!(p(Phase::AwaitingConfirmation, true).is_none());
    }
    #[test]
    fn not_modified_reuses_candidate_or_current() {
        assert_eq!(
            reuse_on_not_modified(Some(7), 2, 1, Some(7)),
            Reuse::Candidate
        );
        assert_eq!(reuse_on_not_modified(Some(7), 1, 1, None), Reuse::Current);
        assert_eq!(reuse_on_not_modified(Some(7), 2, 1, None), Reuse::Refetch);
        assert_eq!(reuse_on_not_modified(Some(7), 0, 1, None), Reuse::Refetch);
        assert_eq!(
            reuse_on_not_modified(Some(7), 2, 1, Some(6)),
            Reuse::Refetch
        );
        assert_eq!(reuse_on_not_modified(None, 2, 1, None), Reuse::Refetch);
    }
    #[test]
    fn legacy_journal_bodies_become_single_stored_notes() {
        let d = tempfile::tempdir().unwrap();
        {
            let engine = Engine::new(d.path().into(), installed()).unwrap();
            engine.inner.change(0, true, |j| {
                j.cache = Some(release());
                j.cached_version = 2;
                j.snapshot.phase = Phase::Available;
                j.snapshot.candidate = candidate(release(), metadata(), &installed()).unwrap();
            });
        }
        let engine = Engine::new(d.path().into(), installed()).unwrap();
        assert_eq!(engine.snapshot().notes, "## Changes\n- Faster");
        let j = locked(&engine.inner.journal);
        assert!(j.cache.as_ref().unwrap().body.is_none());
        assert!(j
            .snapshot
            .candidate
            .as_ref()
            .unwrap()
            .release
            .body
            .is_none());
    }
    #[test]
    fn low_space_never_starts_download() {
        let d = tempfile::tempdir().unwrap();
        let engine = Engine::new(d.path().into(), installed()).unwrap();
        engine.inner.change(0, true, |j| {
            j.snapshot.phase = Phase::Available;
            j.snapshot.candidate = candidate(release(), metadata(), &installed()).unwrap();
        });
        engine.download(0, 1);
        assert_eq!(engine.snapshot().failure, Some(Failure::Storage));
    }
    fn http_fixture(reply: &'static [u8]) -> (String, std::sync::mpsc::Receiver<String>) {
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let address = listener.local_addr().unwrap();
        let (tx, rx) = std::sync::mpsc::channel();
        std::thread::spawn(move || {
            let (mut stream, _) = listener.accept().unwrap();
            stream
                .set_read_timeout(Some(Duration::from_secs(2)))
                .unwrap();
            let mut request = Vec::new();
            let mut byte = [0];
            while stream.read_exact(&mut byte).is_ok() {
                request.push(byte[0]);
                if request.ends_with(b"\r\n\r\n") {
                    break;
                }
            }
            tx.send(String::from_utf8(request).unwrap()).unwrap();
            stream.write_all(reply).unwrap();
        });
        (format!("http://{address}/synthetic"), rx)
    }
    #[test]
    fn transport_bounds_declared_streamed_and_truncated_bodies() {
        let d = tempfile::tempdir().unwrap();
        let engine = Engine::new(d.path().into(), installed()).unwrap();
        for reply in [
            &b"HTTP/1.1 200 OK\r\nContent-Length: 10\r\nConnection: close\r\n\r\n0123456789"[..],
            &b"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n5\r\nabcde\r\n0\r\n\r\n"[..],
        ] {
            let (url, _request) = http_fixture(reply);
            let result = engine.runtime.block_on(async {
                let response = engine.inner.response(&url, true, None).await.unwrap();
                engine.inner.limited(response, 3).await
            });
            assert_eq!(result, Err(Failure::UnsupportedRelease));
        }
        let (url, _request) =
            http_fixture(b"HTTP/1.1 200 OK\r\nContent-Length: 10\r\nConnection: close\r\n\r\nabc");
        let result = engine.runtime.block_on(async {
            let response = engine.inner.response(&url, true, None).await.unwrap();
            engine.inner.limited(response, 100).await
        });
        assert_eq!(result, Err(Failure::Network));
    }
    #[test]
    fn transport_conditional_requests_and_persisted_server_backoff() {
        let d = tempfile::tempdir().unwrap();
        let engine = Engine::new(d.path().into(), installed()).unwrap();
        let (url, request) = http_fixture(
            b"HTTP/1.1 304 Not Modified\r\nETag: synthetic\r\nConnection: close\r\n\r\n",
        );
        let response = engine
            .runtime
            .block_on(engine.inner.response(&url, true, Some("synthetic")))
            .unwrap();
        assert_eq!(response.status().as_u16(), 304);
        let headers = request.recv().unwrap().to_lowercase();
        assert!(headers.contains("if-none-match: synthetic"));
        assert!(!headers.contains("authorization:"));
        let (url, _request) = http_fixture(b"HTTP/1.1 429 Too Many Requests\r\nContent-Length: 0\r\nRetry-After: 120\r\nConnection: close\r\n\r\n");
        assert!(matches!(
            engine
                .runtime
                .block_on(engine.inner.response(&url, true, None)),
            Err(Failure::RateLimited)
        ));
        let persisted: Journal =
            serde_json::from_slice(&std::fs::read(d.path().join("journal.json")).unwrap()).unwrap();
        assert!(persisted.blocked_until >= now() + 119);
    }
    #[test]
    fn expired_ready_files_and_early_installer_results_cannot_install() {
        let d = tempfile::tempdir().unwrap();
        let engine = Engine::new(d.path().into(), installed()).unwrap();
        engine.inner.change(0, true, |j| {
            j.snapshot.phase = Phase::Available;
            j.snapshot.candidate = candidate(release(), metadata(), &installed()).unwrap();
        });
        engine.installer_result(0, "success");
        assert_eq!(engine.snapshot().phase, Phase::Available);
        engine.inner.change(0, true, |j| {
            j.snapshot.phase = Phase::Ready;
            j.snapshot.ready_at = now() - 8 * 86400;
        });
        engine.prepare(0);
        assert_eq!(engine.snapshot().failure, Some(Failure::Verification));
    }
    #[test]
    fn snooze_is_persisted_and_keeps_verified_candidate() {
        let d = tempfile::tempdir().unwrap();
        let engine = Engine::new(d.path().into(), installed()).unwrap();
        engine.inner.change(0, true, |j| {
            j.snapshot.phase = Phase::Available;
            j.snapshot.candidate = candidate(release(), metadata(), &installed()).unwrap();
        });
        assert!(engine.snapshot().available_notice);
        engine.snooze();
        assert!(!engine.snapshot().available_notice);
        assert!(engine.snapshot().candidate.is_some());
        let j: Journal =
            serde_json::from_slice(&std::fs::read(d.path().join("journal.json")).unwrap()).unwrap();
        assert!(j.snoozed_until >= now() + 86399);
    }
    #[test]
    fn available_notice_and_cadence_survive_restart_without_downloads() {
        let d = tempfile::tempdir().unwrap();
        for phase in [Phase::Available, Phase::Error] {
            {
                let engine = Engine::new(d.path().into(), installed()).unwrap();
                engine.inner.change(0, true, |j| {
                    j.snapshot.phase = phase;
                    j.snapshot.candidate = candidate(release(), metadata(), &installed()).unwrap();
                    j.last_success = now();
                });
                let (apk, _) = engine.inner.paths(0);
                std::fs::create_dir_all(apk.parent().unwrap()).unwrap();
                std::fs::write(apk.with_extension("part"), b"partial").unwrap();
            }
            let engine = Engine::new(d.path().into(), installed()).unwrap();
            assert_eq!(engine.snapshot().phase, Phase::Available);
            assert!(engine.snapshot().available_notice);
            assert_eq!(engine.check(false), 0);
            assert!(!engine.inner.paths(0).0.parent().unwrap().exists());
        }
    }
    #[test]
    fn retry_after_withdrawal_removes_previous_generation_files() {
        let d = tempfile::tempdir().unwrap();
        let mut engine = Engine::new(d.path().into(), installed()).unwrap();
        // Hermetic: the spawned check connects to a closed local port.
        Arc::get_mut(&mut engine.inner).unwrap().base = "http://127.0.0.1:1".into();
        engine.inner.change(0, true, |j| {
            j.snapshot.phase = Phase::Error;
            j.snapshot.failure = Some(Failure::Withdrawn);
            j.snapshot.candidate = candidate(release(), metadata(), &installed()).unwrap();
            j.snapshot.ready_at = now();
        });
        let (apk, dm) = engine.inner.paths(0);
        std::fs::create_dir_all(apk.parent().unwrap()).unwrap();
        std::fs::write(&apk, b"old-apk").unwrap();
        std::fs::write(&dm, b"old-profile").unwrap();
        engine.retry(0, u64::MAX);
        assert_eq!(engine.snapshot().generation, 1);
        assert!(!apk.exists());
        assert!(!dm.exists());
        assert_eq!(engine.snapshot().ready_at, 0);
        engine.cancel();
    }
    #[test]
    fn current_version_notes_survive_and_expired_committed_sessions_reconcile() {
        let d = tempfile::tempdir().unwrap();
        {
            let engine = Engine::new(d.path().into(), installed()).unwrap();
            engine.inner.change(0, true, |j| {
                j.cache = Some(release());
                j.migrate_notes();
                j.cached_version = 1;
                j.snapshot.phase = Phase::Current;
            });
            assert_eq!(engine.snapshot().notes, "## Changes\n- Faster");
            engine.inner.change(0, true, |j| {
                j.snapshot.candidate = candidate(release(), metadata(), &installed()).unwrap();
                j.snapshot.phase = Phase::AwaitingConfirmation;
                j.snapshot.ready_at = now() - 8 * 86400;
            });
        }
        let engine = Engine::new(d.path().into(), installed()).unwrap();
        assert_eq!(engine.snapshot().phase, Phase::AwaitingConfirmation);
        engine.installer_result(0, "canceled");
        assert_eq!(engine.snapshot().phase, Phase::Ready);
    }
}
