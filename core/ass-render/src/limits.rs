//! Admission policy for untrusted subtitles: substation's `SecurityConfig` sized for a 32-bit TV
//! with a 512 MB heap, and the back-off after a refused frame.

use substation::api::{FontLimits, RenderLimits};
use substation::{InputLimits, SecurityConfig};

use crate::engine::EngineConfig;
use crate::events::{MAX_EVENT_BYTES, MAX_HEADER_BYTES};

const MIB: usize = 1 << 20;

/// One font file larger than this is never fetched or loaded; real CJK fonts run 5-30 MB.
pub(crate) const MAX_FONT_BYTES: usize = 32 * MIB;

/// docs/18 §3.2: a track's retained input, and so the largest sidecar script worth fetching.
pub const MAX_SCRIPT_BYTES: usize = 16 * MIB;

/// Every track is MKV input and so untrusted; these bound one item's subtitle work and memory.
pub(crate) fn security(cfg: &EngineConfig) -> SecurityConfig {
    let render_pixels = usize::try_from(cfg.max_render_pixels).unwrap_or(usize::MAX);
    SecurityConfig {
        input: InputLimits {
            block_bytes: MAX_HEADER_BYTES,
            line_bytes: MAX_EVENT_BYTES,
            // Per track; events::MAX_LIVE_BYTES separately caps live events across tracks.
            retained_bytes: MAX_SCRIPT_BYTES,
            events: 1 << 15,
            styles: 4096,
        },
        // The same caps the font store and the player's fetch apply (`EngineConfig::font_limits`).
        fonts: FontLimits {
            per_font: MAX_FONT_BYTES,
            total: cfg.font_budget_bytes,
            count: 128,
        },
        render: RenderLimits {
            // A 17-font script peaks just over 128 MiB of charges; this keeps 2x headroom.
            renderer_memory_bytes: 256 * MIB,
            frame_work: cfg.frame_work,
            // The engine already caps the render buffer; this backstops it.
            frame_pixels: render_pixels.saturating_mul(2),
            // The heaviest real 1080p frame adds about a quarter of 2160p's 73 MiB.
            frame_bitmap_bytes: 32 * MIB,
            font_index_bytes: 8 * MIB,
            hint_state_bytes: 8 * MIB,
            metrics_cache_entries: 1 << 16,
            outline_cache_bytes: 24 * MIB,
            ..RenderLimits::default()
        },
        glyph_cache_entries: cfg.glyph_cache_max,
        bitmap_cache_megabytes: cfg.bitmap_cache_mb,
    }
}

/// Media time skipped after the first refused frame; doubles per refusal in a row.
pub(crate) const REFUSAL_BACKOFF_MS: i64 = 1_000;
/// The longest skip, so a track that recovers shows again within half a minute.
pub(crate) const MAX_REFUSAL_BACKOFF_MS: i64 = 30_000;

/// Media time to skip after `in_a_row` consecutive refused frames (1 = the first), so a hostile
/// event can't spend a full work budget every frame.
pub(crate) fn refusal_backoff_ms(in_a_row: u32) -> i64 {
    let doublings = in_a_row.saturating_sub(1).min(16);
    (REFUSAL_BACKOFF_MS << doublings).min(MAX_REFUSAL_BACKOFF_MS)
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;

    #[test]
    fn caches_fit_inside_the_renderer_ledger() {
        let s = security(&EngineConfig::default());
        let caches = usize::try_from(s.bitmap_cache_megabytes).unwrap() * MIB
            + s.render.outline_cache_bytes
            + s.render.font_index_bytes
            + s.render.hint_state_bytes
            + s.render.frame_bitmap_bytes;
        assert!(caches < s.render.renderer_memory_bytes, "{caches}");
        assert!(s.render.frame_work != u64::MAX && s.render.renderer_memory_bytes != usize::MAX);
        assert_eq!(s.render.frame_work, EngineConfig::default().frame_work);
    }

    #[test]
    fn every_render_size_the_engine_picks_is_admitted() {
        let cfg = EngineConfig::default();
        let s = security(&cfg);
        assert!(s.render.frame_pixels >= 1920 * 1080);
        assert!(s.render.frame_pixels <= 2 * 1920 * 1080);
    }

    #[test]
    fn input_limits_match_the_engines_own_gates() {
        let s = security(&EngineConfig::default());
        assert_eq!(s.input.block_bytes, MAX_HEADER_BYTES);
        assert_eq!(s.input.line_bytes, MAX_EVENT_BYTES);
        assert_eq!(s.fonts.total, EngineConfig::default().font_budget_bytes);
        assert_eq!(s.fonts.per_font, MAX_FONT_BYTES);
    }

    /// docs/18 §3.2: a sidecar is parsed whole, so the per-track input caps are its only bound.
    #[test]
    fn a_whole_script_is_held_to_the_input_limits() {
        let s = security(&EngineConfig::default());
        let mut lib = substation::Library::new();
        s.apply_library(&mut lib);
        let mut data = b"[Script Info]\nScriptType: v4.00+\n\n[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n".to_vec();
        while data.len() < 20 * MIB {
            data.extend_from_slice(b"Dialogue: 0,0:00:00.00,0:00:01.00,Default,,0,0,0,,x\n");
        }
        let track = substation::script::read_memory(&mut lib, &data, None).unwrap();
        assert!(!track.events().is_empty());
        assert!(
            track.events().len() <= s.input.events,
            "{}",
            track.events().len()
        );
        assert!(track.retained_input_bytes() <= s.input.retained_bytes);
    }

    #[test]
    fn backoff_doubles_then_caps() {
        assert_eq!(refusal_backoff_ms(0), 1_000);
        assert_eq!(refusal_backoff_ms(1), 1_000);
        assert_eq!(refusal_backoff_ms(2), 2_000);
        assert_eq!(refusal_backoff_ms(5), 16_000);
        assert_eq!(refusal_backoff_ms(6), 30_000);
        assert_eq!(refusal_backoff_ms(u32::MAX), 30_000);
    }
}
