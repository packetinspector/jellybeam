//! Tick/second conversion.

/// Jellyfin ticks-per-second: a tick is 100ns, matching .NET's `TimeSpan`/`DateTime` resolution the
/// server reports position/segment data in.
const TICKS_PER_SEC: f64 = 10_000_000.0;

/// Converts Jellyfin ticks (100ns units) to seconds.
pub fn ticks_to_secs(ticks: i64) -> f64 {
    ticks as f64 / TICKS_PER_SEC
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ticks_to_secs_converts_100ns_units() {
        assert_eq!(ticks_to_secs(25_200_000_000), 2520.0);
    }
}
