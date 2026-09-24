//! Shared capped-exponential-with-jitter backoff, used by [`crate::event_bus::EventBus`]'s
//! reconnect loop and [`crate::reporting::ReportingSession`]'s report retries.

use std::time::Duration;

/// Doubles from `base` up to `cap`, ±25% jitter on every value so a fleet of clients reconnecting
/// after a server restart doesn't thunder-herd.
#[derive(Debug, Clone)]
pub(crate) struct Backoff {
    base: Duration,
    cap: Duration,
    attempt: u32,
}

impl Backoff {
    pub(crate) fn new(base: Duration, cap: Duration) -> Self {
        Self {
            base,
            cap,
            attempt: 0,
        }
    }

    /// Next delay, advancing internal state. Non-deterministic (rand jitter);
    /// callers assert on bounds, not exact values.
    pub(crate) fn next_delay(&mut self) -> Duration {
        let unjittered = self.unjittered_delay_for(self.attempt);
        self.attempt = self.attempt.saturating_add(1);
        jitter(unjittered, self.cap)
    }

    fn unjittered_delay_for(&self, attempt: u32) -> Duration {
        let shift = attempt.min(20); // avoid overflow on 1u64 << shift
        let scaled = self.base.saturating_mul(1u32 << shift);
        scaled.min(self.cap)
    }

    /// Bounds `next_delay()` would currently produce, without advancing state.
    #[cfg(test)]
    pub(crate) fn current_bounds(&self) -> (Duration, Duration) {
        let base = self.unjittered_delay_for(self.attempt);
        jitter_bounds(base, self.cap)
    }

    pub(crate) fn reset(&mut self) {
        self.attempt = 0;
    }
}

fn jitter(delay: Duration, cap: Duration) -> Duration {
    let factor = unit_random().mul_add(0.5, 0.75); // ±25% jitter, matching the old rand::gen_range(0.75..=1.25)
    delay.mul_f64(factor).min(cap)
}

/// `SplitMix64` draw in `[0, 1)`, seeded from a per-process call counter mixed with elapsed time
/// -- avoids pulling in the `rand` crate for this one jitter call site. Precision loss in the
/// truncation/cast chain is fine: this seeds a jitter factor, not a value anything checks equality on.
#[allow(clippy::cast_possible_truncation, clippy::cast_precision_loss)]
fn unit_random() -> f64 {
    use std::sync::atomic::{AtomicU64, Ordering};
    use std::sync::OnceLock;
    use std::time::Instant;

    static COUNTER: AtomicU64 = AtomicU64::new(0);
    static EPOCH: OnceLock<Instant> = OnceLock::new();

    let count = COUNTER.fetch_add(1, Ordering::Relaxed);
    let elapsed_nanos = EPOCH.get_or_init(Instant::now).elapsed().as_nanos() as u64;

    let mut z = (count ^ elapsed_nanos).wrapping_add(0x9E37_79B9_7F4A_7C15);
    z = (z ^ (z >> 30)).wrapping_mul(0xBF58_476D_1CE4_E5B9);
    z = (z ^ (z >> 27)).wrapping_mul(0x94D0_49BB_1331_11EB);
    z ^= z >> 31;
    (z >> 11) as f64 * (1.0 / (1u64 << 53) as f64)
}

#[cfg(test)]
fn jitter_bounds(delay: Duration, cap: Duration) -> (Duration, Duration) {
    (delay.mul_f64(0.75).min(cap), delay.mul_f64(1.25).min(cap))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn starts_at_base_and_doubles_up_to_cap() {
        let mut b = Backoff::new(Duration::from_secs(1), Duration::from_secs(60));
        let (lo, hi) = b.current_bounds();
        assert!(lo <= Duration::from_secs(1) && hi >= Duration::from_millis(750));

        for _ in 0..10 {
            b.next_delay();
        }
        let (lo, hi) = b.current_bounds();
        assert!(lo <= Duration::from_secs(60));
        assert!(hi <= Duration::from_secs(60));
        // Pins: saturates at the cap well before 10 doublings (1*2^10=1024s).
        assert!(lo >= Duration::from_secs(44));
    }

    #[test]
    fn reset_returns_to_base() {
        let mut b = Backoff::new(Duration::from_millis(100), Duration::from_secs(10));
        for _ in 0..5 {
            b.next_delay();
        }
        b.reset();
        let (lo, hi) = b.current_bounds();
        assert!(lo <= Duration::from_millis(100));
        assert!(hi <= Duration::from_millis(130));
    }

    #[test]
    fn never_exceeds_cap() {
        let mut b = Backoff::new(Duration::from_secs(30), Duration::from_secs(60));
        for _ in 0..5 {
            let d = b.next_delay();
            assert!(d <= Duration::from_secs(60));
        }
    }
}
