//! Process-wide stale-while-revalidate DNS cache for every reqwest client (API, images,
//! trickplay): the system resolver intermittently stalls ~5s on this app's target hosts, so this
//! cache pays that stall at most once.
//! - First lookup for an unseeded host blocks; a host seeded via [`CachingResolver::seed`] never
//!   blocks.
//! - Later requests are served from cache; past [`REFRESH_AFTER`] a hit also kicks a background
//!   refresh.
//! - A failed refresh keeps the stale answer (serve-stale-on-error) until a later refresh lands.
//!
//! One shared instance ([`shared_dns_resolver`]) backs all clients.

use std::collections::HashMap;
use std::net::{IpAddr, SocketAddr, ToSocketAddrs};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::{Duration, Instant};

use reqwest::dns::{Addrs, Name, Resolve, Resolving};

/// Age past which a cache hit also triggers a background refresh. Generous because the target is a
/// personal media server on a stable address; freshness here only bounds how long a *moved* server
/// keeps failing.
const REFRESH_AFTER: Duration = Duration::from_secs(5 * 60);

/// Blocking-thread lookup through the system resolver, same call reqwest's default `GaiResolver`
/// makes (port 0 is a placeholder; the connector substitutes the real port).
fn system_lookup(host: &str) -> std::io::Result<Vec<SocketAddr>> {
    (host, 0).to_socket_addrs().map(Iterator::collect)
}

type LookupFn = dyn Fn(&str) -> std::io::Result<Vec<SocketAddr>> + Send + Sync;

/// Cap on how long one background refresh may stay "in flight" before a later hit may launch a
/// replacement: a `getaddrinfo` that never returns would otherwise pin the marker forever,
/// disabling refresh for that host for the rest of the process. Well above any observed stall
/// (~5s), well below "forever".
const REFRESH_ATTEMPT_TIMEOUT: Duration = Duration::from_secs(30);

/// Cap on a single `getaddrinfo` call made through [`CachingResolver::bounded_lookup`] -- the
/// first lookup for a host, or one background refresh. Unlike [`REFRESH_ATTEMPT_TIMEOUT`] (which
/// only lets a later hit start a replacement once a stuck refresh is presumed lost), this bounds
/// the call itself so a hung resolver can't block the caller forever.
const LOOKUP_TIMEOUT: Duration = Duration::from_secs(10);

struct CacheEntry {
    addrs: Vec<SocketAddr>,
    resolved_at: Instant,
    /// When the in-flight background refresh began, `None` if none is running. A refresh older than
    /// [`REFRESH_ATTEMPT_TIMEOUT`] is treated as lost and a new one may start.
    refresh_started: Option<Instant>,
    /// Set only by [`CachingResolver::seed`]: born stale without backdating `resolved_at`, so the
    /// first hit serves it and kicks the revalidating refresh; cleared once that refresh starts.
    seeded: bool,
}

pub struct CachingResolver {
    cache: Mutex<HashMap<String, CacheEntry>>,
    lookup: Arc<LookupFn>,
}

impl CachingResolver {
    fn new(lookup: Arc<LookupFn>) -> Self {
        Self {
            cache: Mutex::new(HashMap::new()),
            lookup,
        }
    }

    /// Plants a previous launch's answer for `host` so the first lookup doesn't wait on the system
    /// resolver; served instantly then revalidated in the background. Never clobbers a live entry;
    /// empty `addrs` is a no-op.
    pub fn seed(&self, host: &str, addrs: Vec<IpAddr>) {
        if addrs.is_empty() {
            return;
        }
        let mut cache = self.cache.lock().unwrap_or_else(|e| e.into_inner());
        if cache.contains_key(host) {
            return;
        }
        cache.insert(
            host.to_string(),
            CacheEntry {
                // Port 0 placeholder, matching `system_lookup`; the connector substitutes the real
                // port.
                addrs: addrs.into_iter().map(|ip| SocketAddr::new(ip, 0)).collect(),
                resolved_at: Instant::now(),
                refresh_started: None,
                seeded: true,
            },
        );
        tracing::debug!(host, "dns cache seeded from a previous launch");
    }

    /// The addresses a request for `host` would be served right now (counterpart of
    /// [`Self::seed`]). `None` until something has resolved or seeded the host.
    pub fn snapshot(&self, host: &str) -> Option<Vec<IpAddr>> {
        let cache = self.cache.lock().unwrap_or_else(|e| e.into_inner());
        let entry = cache.get(host)?;
        Some(entry.addrs.iter().map(SocketAddr::ip).collect())
    }

    /// Cache-first resolution for `host`. Only the first-ever lookup for an unseeded host awaits
    /// the system resolver; later calls hit cache (plus a detached refresh past [`REFRESH_AFTER`]).
    async fn cached_addrs(self: Arc<Self>, host: String) -> std::io::Result<Vec<SocketAddr>> {
        let cached = {
            let mut cache = self.cache.lock().unwrap_or_else(|e| e.into_inner());
            match cache.get_mut(&host) {
                Some(entry) => {
                    let refresh_available = match entry.refresh_started {
                        None => true,
                        // Presumed-lost refresh (hung getaddrinfo); a late landing just overwrites
                        // with equally-fresh data.
                        Some(started) => started.elapsed() > REFRESH_ATTEMPT_TIMEOUT,
                    };
                    // Seeded entries are born stale: serve, then revalidate on this first hit.
                    let stale = entry.seeded || entry.resolved_at.elapsed() > REFRESH_AFTER;
                    let needs_refresh = stale && refresh_available;
                    if needs_refresh {
                        entry.refresh_started = Some(Instant::now());
                        entry.seeded = false;
                    }
                    Some((entry.addrs.clone(), needs_refresh))
                }
                None => None,
            }
        };
        if let Some((addrs, needs_refresh)) = cached {
            if needs_refresh {
                self.clone().spawn_refresh(host);
            }
            return Ok(addrs);
        }

        // First-ever lookup: nothing to serve yet, so this one waits and may eat the resolver's
        // stall (bounded by LOOKUP_TIMEOUT so a truly hung getaddrinfo can't block it forever).
        let result = Self::bounded_lookup(self.lookup.clone(), host.clone(), LOOKUP_TIMEOUT).await;
        if let Ok(addrs) = &result {
            self.store(host, addrs.clone());
        }
        result
    }

    /// Runs `lookup(host)` on the blocking pool, bounded by `timeout`: an expired lookup surfaces
    /// as an `Err` (the blocking thread itself keeps running to completion, just unobserved).
    async fn bounded_lookup(
        lookup: Arc<LookupFn>,
        host: String,
        timeout: Duration,
    ) -> std::io::Result<Vec<SocketAddr>> {
        match tokio::time::timeout(timeout, tokio::task::spawn_blocking(move || lookup(&host)))
            .await
        {
            Ok(Ok(result)) => result,
            Ok(Err(e)) => Err(std::io::Error::other(format!(
                "dns lookup task failed: {e}"
            ))),
            Err(_elapsed) => Err(std::io::Error::new(
                std::io::ErrorKind::TimedOut,
                format!("dns lookup timed out after {timeout:?}"),
            )),
        }
    }

    fn store(&self, host: String, addrs: Vec<SocketAddr>) {
        let mut cache = self.cache.lock().unwrap_or_else(|e| e.into_inner());
        cache.insert(
            host,
            CacheEntry {
                addrs,
                resolved_at: Instant::now(),
                refresh_started: None,
                // Real answer from this process; no longer a guess off disk.
                seeded: false,
            },
        );
    }

    fn clear_refreshing(&self, host: &str) {
        let mut cache = self.cache.lock().unwrap_or_else(|e| e.into_inner());
        if let Some(entry) = cache.get_mut(host) {
            entry.refresh_started = None;
        }
    }

    /// Detached refresh: success replaces the entry; failure keeps the stale answer. A missing
    /// tokio runtime degrades to skipping the refresh, never a panic.
    fn spawn_refresh(self: Arc<Self>, host: String) {
        let Ok(handle) = tokio::runtime::Handle::try_current() else {
            self.clear_refreshing(&host);
            return;
        };
        handle.spawn(async move {
            let lookup = self.lookup.clone();
            let result = Self::bounded_lookup(lookup, host.clone(), LOOKUP_TIMEOUT).await;
            match result {
                Ok(addrs) => self.store(host, addrs),
                Err(e) => {
                    tracing::debug!(error = %e, host = %host, "dns refresh failed; serving stale");
                    self.clear_refreshing(&host);
                }
            }
        });
    }
}

/// `Resolve`-implementing handle around a [`CachingResolver`]; separate type since `resolve` takes
/// `&self` but returns a `'static` future that must own its own `Arc`.
pub struct ResolverHandle(Arc<CachingResolver>);

impl Resolve for ResolverHandle {
    fn resolve(&self, name: Name) -> Resolving {
        let this = self.0.clone();
        let host = name.as_str().to_string();
        Box::pin(async move {
            let addrs = this.cached_addrs(host).await?;
            let boxed: Addrs = Box::new(addrs.into_iter());
            Ok(boxed)
        })
    }
}

/// The process-wide resolver every client passes to `ClientBuilder::dns_resolver`; one cache shared
/// by API, image, and trickplay clients.
pub fn shared_dns_resolver() -> Arc<ResolverHandle> {
    static RESOLVER: OnceLock<Arc<ResolverHandle>> = OnceLock::new();
    RESOLVER
        .get_or_init(|| {
            Arc::new(ResolverHandle(Arc::new(CachingResolver::new(Arc::new(
                system_lookup,
            )))))
        })
        .clone()
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicUsize, Ordering};

    fn addr(n: u8) -> SocketAddr {
        SocketAddr::from(([127, 0, 0, n], 0))
    }

    fn counting_resolver(fail: bool, counter: Arc<AtomicUsize>) -> Arc<CachingResolver> {
        Arc::new(CachingResolver::new(Arc::new(move |_host: &str| {
            let n = counter.fetch_add(1, Ordering::SeqCst) + 1;
            if fail && n > 1 {
                Err(std::io::Error::other("simulated resolver failure"))
            } else {
                Ok(vec![addr(n as u8)])
            }
        })))
    }

    #[tokio::test]
    async fn a_lost_refresh_does_not_permanently_disable_refreshing() {
        // Pins: a timed-out refresh must not permanently block future refreshes.
        let count = Arc::new(AtomicUsize::new(0));
        let resolver = counting_resolver(false, count.clone());
        resolver
            .clone()
            .cached_addrs("server".into())
            .await
            .expect("prime");
        {
            let mut cache = resolver.cache.lock().expect("lock");
            let entry = cache.get_mut("server").expect("entry");
            // Simulate a refresh that started long ago and never landed.
            entry.resolved_at = Instant::now() - REFRESH_AFTER - Duration::from_secs(1);
            entry.refresh_started =
                Some(Instant::now() - REFRESH_ATTEMPT_TIMEOUT - Duration::from_secs(1));
        }

        resolver
            .clone()
            .cached_addrs("server".into())
            .await
            .expect("stale hit");
        for _ in 0..200 {
            tokio::time::sleep(Duration::from_millis(5)).await;
            if count.load(Ordering::SeqCst) >= 2 {
                break;
            }
        }
        assert!(
            count.load(Ordering::SeqCst) >= 2,
            "a replacement refresh must run after the timeout"
        );
    }

    #[tokio::test]
    async fn second_lookup_is_served_from_cache_without_a_resolver_call() {
        let count = Arc::new(AtomicUsize::new(0));
        let resolver = counting_resolver(false, count.clone());

        let first = resolver
            .clone()
            .cached_addrs("server".into())
            .await
            .expect("first lookup");
        let second = resolver
            .clone()
            .cached_addrs("server".into())
            .await
            .expect("second lookup");

        assert_eq!(first, second);
        assert_eq!(
            count.load(Ordering::SeqCst),
            1,
            "a fresh cache hit must not call the system resolver again"
        );
    }

    #[tokio::test]
    async fn stale_hit_serves_immediately_and_refreshes_in_background() {
        let count = Arc::new(AtomicUsize::new(0));
        let resolver = counting_resolver(false, count.clone());

        resolver
            .clone()
            .cached_addrs("server".into())
            .await
            .expect("prime");
        // Age the entry past REFRESH_AFTER without waiting real time.
        {
            let mut cache = resolver.cache.lock().expect("lock");
            cache.get_mut("server").expect("entry").resolved_at =
                Instant::now() - REFRESH_AFTER - Duration::from_secs(1);
        }

        let stale = resolver
            .clone()
            .cached_addrs("server".into())
            .await
            .expect("stale hit");
        assert_eq!(
            stale,
            vec![addr(1)],
            "stale hit must serve the cached answer"
        );

        // Poll the cache, not the counter: the counter increments before the async task stores the
        // result.
        let mut refreshed = Vec::new();
        for _ in 0..200 {
            tokio::time::sleep(Duration::from_millis(5)).await;
            refreshed = resolver
                .clone()
                .cached_addrs("server".into())
                .await
                .expect("post-refresh hit");
            if refreshed == vec![addr(2)] {
                break;
            }
        }
        assert_eq!(refreshed, vec![addr(2)], "refresh must replace the entry");
        assert_eq!(
            count.load(Ordering::SeqCst),
            2,
            "exactly one background refresh must run"
        );
    }

    /// Pins: a seeded host is served without touching the system resolver, and revalidates itself
    /// on the first hit.
    #[tokio::test]
    async fn a_seeded_host_serves_instantly_and_revalidates_in_the_background() {
        let count = Arc::new(AtomicUsize::new(0));
        let resolver = counting_resolver(false, count.clone());
        resolver.seed("server", vec![addr(7).ip()]);

        let first = resolver
            .clone()
            .cached_addrs("server".into())
            .await
            .expect("seeded hit");
        assert_eq!(
            first,
            vec![addr(7)],
            "a seeded host must be served from the seed, not resolved"
        );

        // Poll the cache for the replacement (see stale-hit test above).
        let mut refreshed = Vec::new();
        for _ in 0..200 {
            tokio::time::sleep(Duration::from_millis(5)).await;
            refreshed = resolver
                .clone()
                .cached_addrs("server".into())
                .await
                .expect("post-refresh hit");
            if refreshed == vec![addr(1)] {
                break;
            }
        }
        assert_eq!(
            refreshed,
            vec![addr(1)],
            "the seeded entry must be revalidated by a background refresh"
        );
        assert_eq!(
            count.load(Ordering::SeqCst),
            1,
            "exactly one background refresh must run -- and never a blocking \
             first lookup"
        );
        // Post-refresh: ordinary fresh entry, no further traffic until REFRESH_AFTER elapses.
        resolver
            .clone()
            .cached_addrs("server".into())
            .await
            .expect("fresh hit");
        assert_eq!(count.load(Ordering::SeqCst), 1);
    }

    /// Pins: `snapshot` mirrors what's served, and `seed` never overwrites a live answer.
    #[tokio::test]
    async fn snapshot_reports_the_served_addrs_and_seed_never_clobbers_a_live_one() {
        let count = Arc::new(AtomicUsize::new(0));
        let resolver = counting_resolver(false, count.clone());
        assert_eq!(resolver.snapshot("server"), None, "nothing cached yet");

        resolver
            .clone()
            .cached_addrs("server".into())
            .await
            .expect("prime");
        assert_eq!(resolver.snapshot("server"), Some(vec![addr(1).ip()]));

        // Seed after a real lookup is ignored.
        resolver.seed("server", vec![addr(7).ip()]);
        assert_eq!(resolver.snapshot("server"), Some(vec![addr(1).ip()]));
        // Empty seed is a no-op, not a poisoned entry.
        resolver.seed("elsewhere", vec![]);
        assert_eq!(resolver.snapshot("elsewhere"), None);
    }

    #[tokio::test]
    async fn failed_refresh_keeps_serving_the_stale_answer() {
        let count = Arc::new(AtomicUsize::new(0));
        let resolver = counting_resolver(true, count.clone());

        resolver
            .clone()
            .cached_addrs("server".into())
            .await
            .expect("prime");
        {
            let mut cache = resolver.cache.lock().expect("lock");
            cache.get_mut("server").expect("entry").resolved_at =
                Instant::now() - REFRESH_AFTER - Duration::from_secs(1);
        }

        let stale = resolver
            .clone()
            .cached_addrs("server".into())
            .await
            .expect("stale hit");
        assert_eq!(stale, vec![addr(1)]);
        // Poll the in-flight marker (cleared only after the failed lookup returns), not the
        // counter.
        let mut cleared = false;
        for _ in 0..200 {
            tokio::time::sleep(Duration::from_millis(5)).await;
            cleared = resolver
                .cache
                .lock()
                .expect("lock")
                .get("server")
                .expect("entry")
                .refresh_started
                .is_none();
            if cleared {
                break;
            }
        }
        assert!(cleared, "a failed refresh must clear the in-flight marker");
        let after = resolver
            .clone()
            .cached_addrs("server".into())
            .await
            .expect("post-failed-refresh hit");
        assert_eq!(after, vec![addr(1)], "serve-stale-on-error");
    }

    /// Pins: a `getaddrinfo` that outlives its timeout must not block the caller past that bound
    /// -- it surfaces as an error instead of hanging forever.
    #[tokio::test]
    async fn bounded_lookup_times_out_instead_of_blocking_the_caller_forever() {
        let short_timeout = Duration::from_millis(20);
        let lookup: Arc<LookupFn> = Arc::new(|_host: &str| {
            std::thread::sleep(Duration::from_millis(300));
            Ok(vec![addr(1)])
        });

        let result = tokio::time::timeout(
            Duration::from_millis(200),
            CachingResolver::bounded_lookup(lookup, "server".to_string(), short_timeout),
        )
        .await
        .expect("bounded_lookup must return within its own timeout, not hang on the stuck lookup");

        assert!(
            result.is_err(),
            "a lookup that outlives the timeout must surface as an error"
        );
    }
}
