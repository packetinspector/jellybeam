use app_updates::{
    policy::{self, Installed},
    Engine,
};
use std::sync::Arc;

#[derive(Clone, Debug, uniffi::Record)]
pub struct InstalledUpdateFacts {
    pub package: String,
    pub version_code: u64,
    pub sdk: u32,
    pub abis: Vec<String>,
    pub signer: String,
}
#[derive(Clone, Debug, uniffi::Record)]
pub struct UpdateSnapshot {
    pub generation: u64,
    pub revision: u64,
    pub phase: String,
    pub failure: Option<String>,
    pub version_code: u64,
    pub version_label: String,
    pub notes: String,
    pub bytes: u64,
    pub total: u64,
    pub apk_path: String,
    pub profile_path: Option<String>,
    pub available_notice: bool,
    pub last_checked: u64,
    pub published_at: String,
}
#[derive(Clone, Debug, uniffi::Record)]
pub struct VerifiedUpdateFacts {
    pub package: String,
    pub version_code: u64,
    pub min_sdk: u32,
    pub signer: String,
    pub abis: Vec<String>,
    pub valid: bool,
}
#[derive(uniffi::Object)]
pub struct AppUpdater {
    engine: Engine,
}
impl AppUpdater {
    pub(crate) fn create(
        dir: std::path::PathBuf,
        facts: InstalledUpdateFacts,
    ) -> Result<Arc<Self>, crate::CoreError> {
        let installed = Installed {
            package: facts.package,
            version_code: facts.version_code,
            sdk: facts.sdk,
            abis: facts.abis,
            signer: facts.signer,
        };
        Engine::new(dir, installed)
            .map(|engine| Arc::new(Self { engine }))
            .map_err(|_| crate::CoreError::Cache {
                detail: "Update storage is unavailable".into(),
            })
    }
}
#[uniffi::export]
impl AppUpdater {
    pub fn snapshot(&self) -> UpdateSnapshot {
        let s = self.engine.snapshot();
        let (apk_path, profile_path) = self.engine.paths(&s);
        UpdateSnapshot {
            generation: s.generation,
            revision: s.revision,
            phase: format!("{:?}", s.phase),
            failure: s.failure.map(|f| f.to_string()),
            version_code: s.candidate.as_ref().map_or(0, |c| c.metadata.version_code),
            version_label: s.version_label,
            notes: s.notes,
            available_notice: s.available_notice,
            last_checked: s.last_checked,
            published_at: s.published_at,
            total: s.candidate.as_ref().map_or(s.total, |c| {
                c.apk.size + c.profile.as_ref().map_or(0, |p| p.size)
            }),
            bytes: s.bytes,
            apk_path,
            profile_path,
        }
    }
    pub fn check(&self, manual: bool) -> u64 {
        self.engine.check(manual)
    }
    pub fn download(&self, generation: u64, free_bytes: u64) {
        self.engine.download(generation, free_bytes);
    }
    pub fn snooze(&self) {
        self.engine.snooze();
    }
    pub fn retry(&self, generation: u64, free_bytes: u64) {
        self.engine.retry(generation, free_bytes);
    }
    pub fn cancel(&self) {
        self.engine.cancel();
    }
    pub fn verified(&self, generation: u64, facts: VerifiedUpdateFacts) {
        self.engine.verified(
            generation,
            policy::Verified {
                package: facts.package,
                code: facts.version_code,
                sdk: facts.min_sdk,
                signer: facts.signer,
                abis: facts.abis,
                valid: facts.valid,
            },
        );
    }

    pub fn prepare_install(&self, generation: u64) {
        self.engine.prepare(generation);
    }
    pub fn installer_result(&self, generation: u64, outcome: String) {
        self.engine.installer_result(generation, &outcome);
    }
}
