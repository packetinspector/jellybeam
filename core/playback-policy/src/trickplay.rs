//! Trickplay scrub-preview tile geometry: which sprite-sheet image and pixel
//! offset within it corresponds to a given playback position. Kotlin's Coil
//! loads the tile image; this module only computes *which* tile and *where*.
//!
//! Narrower than `jellybeam-ffi`'s `TrickplayMetaFfi` (no `item_id`/
//! `media_source_id` fields), and [`locate`] returns pixel offsets
//! directly so Kotlin's crop is a trivial passthrough.

use std::collections::HashMap;

use jellyfin_api::models::TrickplayInfoDto;

/// One playback session's trickplay geometry: the manifest entry closest to [`PREFERRED_WIDTH`]
/// (see [`resolve_trickplay_meta`]).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct TrickplayMeta {
    pub width: u32,
    pub height: u32,
    pub tile_width: u32,  // thumbnails per row
    pub tile_height: u32, // thumbnails per column
    pub interval_ms: u32,
    pub thumbnail_count: u32,
}

/// One resolved sprite-sheet tile: which numbered sheet (`trickplay_tile_url`'s `{index}.jpg`) and
/// the pixel offset of this thumbnail's top-left corner within it.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct TrickplayTile {
    pub image_index: u32,
    pub x: u32,
    pub y: u32,
}

/// Narrow enough to stay cheap over a constrained link,
/// wide enough to read as a preview on a 10-ft TV.
const PREFERRED_WIDTH: u32 = 320;
const MAX_TILE_DIMENSION: u32 = 4_096;
const MAX_TILES_PER_AXIS: u32 = 128;
const MAX_TILES_PER_SHEET: u32 = MAX_TILES_PER_AXIS * MAX_TILES_PER_AXIS;

/// Picks the best manifest entry for `media_source_id` (falls back to any entry if the id isn't a
/// key), preferring the width closest to [`PREFERRED_WIDTH`].
///
/// `trickplay` is `BaseItemDto::trickplay`'s shape: media source id -> (width-as-string -> manifest
/// entry). `None` if there's no entry, or every candidate fails validation
/// (missing/non-positive/oversized dimensions).
pub fn resolve_trickplay_meta(
    trickplay: &HashMap<String, HashMap<String, TrickplayInfoDto>>,
    media_source_id: &str,
) -> Option<TrickplayMeta> {
    let by_width = trickplay
        .get(media_source_id)
        .or_else(|| trickplay.values().next())?;
    let (_, info) = by_width
        .iter()
        .filter(|(_, info)| {
            let Some(width) = info.width else {
                return false;
            };
            let Some(height) = info.height else {
                return false;
            };
            let tile_width = info.tile_width.unwrap_or(1);
            let tile_height = info.tile_height.unwrap_or(1);
            width > 0
                && height > 0
                && width <= MAX_TILE_DIMENSION as i32
                && height <= MAX_TILE_DIMENSION as i32
                && tile_width > 0
                && tile_width <= MAX_TILES_PER_AXIS as i32
                && tile_height > 0
                && tile_height <= MAX_TILES_PER_AXIS as i32
                && tile_width
                    .checked_mul(tile_height)
                    .is_some_and(|n| n <= MAX_TILES_PER_SHEET as i32)
        })
        .min_by_key(|(_, info)| {
            let w = info.width.unwrap_or(0);
            let distance = (w - PREFERRED_WIDTH as i32).unsigned_abs();
            // Tie-break on width too: `HashMap` iteration order is unspecified, so two
            // equidistant candidates need a deterministic pick (the narrower one).
            (distance, w)
        })?;
    Some(TrickplayMeta {
        // The predicate above proves these conversions are safe.
        width: info.width? as u32,
        height: info.height? as u32,
        tile_width: info.tile_width.unwrap_or(1) as u32,
        tile_height: info.tile_height.unwrap_or(1) as u32,
        interval_ms: info.interval.unwrap_or(10_000).max(1) as u32,
        thumbnail_count: info.thumbnail_count.unwrap_or(0).max(0) as u32,
    })
}

/// Maps a playback position (ms) to which sprite sheet covers it and the pixel offset of that
/// thumbnail within it. Returns pixel offsets
/// directly (`x = col * width`, `y = row * height`) instead of a `(row, col)` pair.
///
/// `None` on degenerate geometry (`width`/`height`/`interval_ms` zero, or `tile_width *
/// tile_height` zero/overflowing/exceeding [`MAX_TILES_PER_SHEET`]). Out-of-range `position_ms`
/// clamps to the last advertised thumbnail instead, since the manifest can lag the item's real
/// runtime by a few seconds.
pub fn locate(meta: &TrickplayMeta, position_ms: u32) -> Option<TrickplayTile> {
    locate_biased(meta, position_ms, TileBias::Earlier)
}

/// Which side of the tile grid to land on when `position_ms` falls between two thumbnails
/// (docs/12 §11). A thumbnail is the frame captured at `index * interval`, so flooring always
/// shows a frame *behind* the position -- fine when travelling backwards (the frame is beyond
/// the landing point in the direction of travel, so the viewer never lands past what they saw)
/// and wrong when travelling forwards, where the same lag reads as having undershot.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TileBias {
    /// The thumbnail at or before the position (floor): rest, and backward travel.
    Earlier,
    /// The thumbnail at or after the position (ceil).
    Later,
    /// Whichever thumbnail is closest (round half up): forward travel, halving both the
    /// worst-case and the mean error instead of pushing it entirely to one side.
    Nearest,
}

/// [`locate`] with an explicit [`TileBias`]; see that enum for why direction matters.
pub fn locate_biased(
    meta: &TrickplayMeta,
    position_ms: u32,
    bias: TileBias,
) -> Option<TrickplayTile> {
    let tiles_per_sheet = meta.tile_width.checked_mul(meta.tile_height)?;
    if meta.width == 0
        || meta.height == 0
        || meta.interval_ms == 0
        || tiles_per_sheet == 0
        || tiles_per_sheet > MAX_TILES_PER_SHEET
    {
        return None;
    }
    let max_index = meta.thumbnail_count.saturating_sub(1);
    let floor_index = position_ms / meta.interval_ms;
    let raw_index = match bias {
        TileBias::Earlier => floor_index,
        // Already exactly on a thumbnail: that one is both at-or-before and at-or-after.
        TileBias::Later if position_ms.is_multiple_of(meta.interval_ms) => floor_index,
        TileBias::Later => floor_index.saturating_add(1),
        TileBias::Nearest => {
            // `into_tile * 2` cannot overflow: `into_tile < interval_ms <= u32::MAX / 2` for
            // every interval a manifest can advertise in milliseconds.
            let into_tile = u64::from(position_ms % meta.interval_ms);
            if into_tile * 2 >= u64::from(meta.interval_ms) {
                floor_index.saturating_add(1)
            } else {
                floor_index
            }
        }
    };
    let local_index = raw_index.min(max_index);
    let image_index = local_index / tiles_per_sheet;
    let pos = local_index % tiles_per_sheet;
    let row = pos / meta.tile_width;
    let col = pos % meta.tile_width;
    Some(TrickplayTile {
        image_index,
        x: col * meta.width,
        y: row * meta.height,
    })
}

/// Glide-seek trickplay tunables (docs/12 §11). Every number the hold-to-seek preview pipeline
/// paces itself by lives here; change them here and nowhere else.
pub mod glide_tunables {
    /// A sampled tile stays on screen at least this long before the next sample may replace it.
    /// ~7 changes/s is the most a viewer can read; above that the work is invisible.
    pub const TILE_DWELL_MS: u64 = 150;
    /// How far ahead the sheet want-list reaches, in real milliseconds of glide at the current
    /// rate. 1.5 s covers the next sheet at every tier without fetching a third.
    pub const PREFETCH_LOOKAHEAD_MS: u64 = 1500;
    /// An in-flight sheet fetch is abandoned once the target has moved this many whole sheets
    /// past it in the glide direction; short of that its bytes are still worth having.
    pub const ABANDON_SLACK_SHEETS: u32 = 1;
}

/// Which numbered sprite sheet covers `position_ms` under `bias`, or `None` on degenerate
/// geometry.
pub fn sheet_index_biased(meta: &TrickplayMeta, position_ms: u32, bias: TileBias) -> Option<u32> {
    locate_biased(meta, position_ms, bias).map(|tile| tile.image_index)
}

/// [`sheet_index_biased`] with [`TileBias::Earlier`].
pub fn sheet_index(meta: &TrickplayMeta, position_ms: u32) -> Option<u32> {
    sheet_index_biased(meta, position_ms, TileBias::Earlier)
}

/// Dwell-gated tile sample for a glide (docs/12 §11): the tile covering `target_ms` when this is
/// the first sample or at least [`glide_tunables::TILE_DWELL_MS`] has passed since
/// `last_sample_ms`; `None` while the dwell is still running or on degenerate geometry. The
/// caller records `now_ms` as the new `last_sample_ms` whenever this returns `Some`.
pub fn glide_sample(
    meta: &TrickplayMeta,
    target_ms: u32,
    now_ms: u64,
    last_sample_ms: Option<u64>,
    forward: bool,
) -> Option<TrickplayTile> {
    if let Some(last) = last_sample_ms {
        if now_ms.saturating_sub(last) < glide_tunables::TILE_DWELL_MS {
            return None;
        }
    }
    locate_biased(meta, target_ms, bias_for(forward))
}

/// The [`TileBias`] for a direction of travel, settled by device use rather than by symmetry
/// (docs/12 §11): backward reads as exact with [`TileBias::Earlier`], while forward read as
/// behind the landing point with it and as ahead of it with [`TileBias::Later`], so forward
/// takes the nearest thumbnail and halves the error rather than choosing a side.
pub fn bias_for(forward: bool) -> TileBias {
    if forward {
        TileBias::Nearest
    } else {
        TileBias::Earlier
    }
}

/// Sheets a glide should have ready, in fetch order: the sheet under `target_ms`, then the
/// adjacent sheet in the glide direction when the glide reaches past the current sheet within
/// [`glide_tunables::PREFETCH_LOOKAHEAD_MS`] at `rate` media-seconds per real second. The
/// adjacent sheet, not the lookahead's endpoint: the glide crosses into the adjacent one first,
/// and a fetch of a farther sheet would sit in the single in-flight slot while the viewer waits
/// on the nearer. Never more than two. Empty on degenerate geometry.
pub fn glide_want_list(meta: &TrickplayMeta, target_ms: u32, rate: u32, forward: bool) -> Vec<u32> {
    // The sheet holding the tile actually shown, so the bias cannot leave the transport
    // fetching a neighbour of it near a sheet boundary.
    let Some(current) = sheet_index_biased(meta, target_ms, bias_for(forward)) else {
        return Vec::new();
    };
    let reach_ms = u64::from(rate) * glide_tunables::PREFETCH_LOOKAHEAD_MS;
    let ahead_ms = if forward {
        u64::from(target_ms).saturating_add(reach_ms)
    } else {
        u64::from(target_ms).saturating_sub(reach_ms)
    };
    let ahead_ms = u32::try_from(ahead_ms).unwrap_or(u32::MAX);
    let mut wanted = vec![current];
    if let Some(ahead) = sheet_index(meta, ahead_ms) {
        if ahead != current {
            wanted.push(if forward { current + 1 } else { current - 1 });
        }
    }
    wanted
}

/// Whether an in-flight fetch of `sheet` is no longer worth finishing: the target has moved more
/// than [`glide_tunables::ABANDON_SLACK_SHEETS`] sheets past it in the glide direction. A sheet
/// behind the direction of travel is never worth abandoning early since a reversal comes back to
/// it, and a fetch is cheapest to finish once its first bytes have arrived.
pub fn should_abandon_sheet(
    meta: &TrickplayMeta,
    sheet: u32,
    target_ms: u32,
    forward: bool,
) -> bool {
    let Some(current) = sheet_index(meta, target_ms) else {
        return true;
    };
    let slack = glide_tunables::ABANDON_SLACK_SHEETS;
    if forward {
        current > sheet.saturating_add(slack)
    } else {
        current.saturating_add(slack) < sheet
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn meta() -> TrickplayMeta {
        TrickplayMeta {
            width: 320,
            height: 180,
            tile_width: 10,
            tile_height: 10,
            interval_ms: 10_000,
            thumbnail_count: 1_000,
        }
    }

    fn info(width: i32, height: i32, tile_width: i32, tile_height: i32) -> TrickplayInfoDto {
        TrickplayInfoDto {
            bandwidth: None,
            height: Some(height),
            interval: Some(10_000),
            thumbnail_count: Some(1_000),
            tile_height: Some(tile_height),
            tile_width: Some(tile_width),
            width: Some(width),
        }
    }

    #[test]
    fn locate_first_thumbnail_is_the_top_left_tile_of_sheet_zero() {
        assert_eq!(
            locate(&meta(), 0),
            Some(TrickplayTile {
                image_index: 0,
                x: 0,
                y: 0
            })
        );
    }

    #[test]
    fn locate_last_thumbnail_clamps_to_the_last_advertised_index() {
        let mut m = meta();
        m.thumbnail_count = 100; // indices 0..=99
                                 // Anything at/after the last interval, including u32::MAX, lands on the same final tile.
        let expected = Some(TrickplayTile {
            image_index: 0,
            x: 9 * m.width,
            y: 9 * m.height,
        });
        assert_eq!(locate(&m, 990_000), expected);
        assert_eq!(locate(&m, u32::MAX), expected);
    }

    #[test]
    fn locate_crosses_an_image_boundary_at_tiles_per_sheet() {
        let m = meta(); // tiles_per_sheet = 100; index 99 is sheet 0's last tile, 100 is sheet 1's first.
        assert_eq!(
            locate(&m, 99 * m.interval_ms),
            Some(TrickplayTile {
                image_index: 0,
                x: 9 * m.width,
                y: 9 * m.height
            })
        );
        assert_eq!(
            locate(&m, 100 * m.interval_ms),
            Some(TrickplayTile {
                image_index: 1,
                x: 0,
                y: 0
            })
        );
    }

    #[test]
    fn locate_rounds_down_within_an_interval() {
        let m = meta();
        // Short of the next interval boundary still addresses the current thumbnail (rounds down).
        assert_eq!(
            locate(&m, m.interval_ms - 1),
            Some(TrickplayTile {
                image_index: 0,
                x: 0,
                y: 0
            })
        );
        assert_eq!(
            locate(&m, m.interval_ms),
            Some(TrickplayTile {
                image_index: 0,
                x: m.width,
                y: 0
            })
        );
    }

    #[test]
    fn locate_is_none_on_degenerate_geometry() {
        let mut m = meta();
        m.interval_ms = 0;
        assert_eq!(locate(&m, 0), None);

        let mut m = meta();
        m.tile_width = 0;
        assert_eq!(locate(&m, 0), None);

        let mut m = meta();
        m.width = 0;
        assert_eq!(locate(&m, 0), None);

        let mut m = meta();
        // Overflows the u32 multiply -- must not panic.
        m.tile_width = u32::MAX;
        m.tile_height = 2;
        assert_eq!(locate(&m, 0), None);

        let mut m = meta();
        // Legal multiply, but exceeds MAX_TILES_PER_SHEET.
        m.tile_width = MAX_TILES_PER_AXIS;
        m.tile_height = MAX_TILES_PER_AXIS + 1;
        assert_eq!(locate(&m, 0), None);
    }

    #[test]
    fn resolve_trickplay_meta_picks_the_width_closest_to_preferred() {
        let mut by_width = HashMap::new();
        by_width.insert("120".to_string(), info(120, 68, 8, 8));
        by_width.insert("320".to_string(), info(320, 180, 10, 10));
        by_width.insert("640".to_string(), info(640, 360, 10, 10));
        let mut trickplay = HashMap::new();
        trickplay.insert("src-1".to_string(), by_width);

        let resolved = resolve_trickplay_meta(&trickplay, "src-1").expect("resolves");
        assert_eq!(resolved.width, 320);
        assert_eq!(resolved.height, 180);
    }

    /// Pins: two candidates equidistant from `PREFERRED_WIDTH` (320) resolve deterministically to
    /// the narrower one, regardless of `HashMap` iteration order.
    #[test]
    fn resolve_trickplay_meta_breaks_equidistant_ties_on_the_narrower_width() {
        let mut by_width = HashMap::new();
        by_width.insert("220".to_string(), info(220, 124, 10, 10)); // distance 100
        by_width.insert("420".to_string(), info(420, 236, 10, 10)); // distance 100
        let mut trickplay = HashMap::new();
        trickplay.insert("src".to_string(), by_width);

        let resolved = resolve_trickplay_meta(&trickplay, "src").expect("resolves");
        assert_eq!(
            resolved.width, 220,
            "an equidistant tie must prefer the narrower width"
        );
    }

    #[test]
    fn resolve_trickplay_meta_falls_back_to_any_entry_when_source_id_is_unknown() {
        let mut by_width = HashMap::new();
        by_width.insert("320".to_string(), info(320, 180, 10, 10));
        let mut trickplay = HashMap::new();
        trickplay.insert("only-source".to_string(), by_width);

        let resolved =
            resolve_trickplay_meta(&trickplay, "does-not-exist").expect("falls back to any entry");
        assert_eq!(resolved.width, 320);
    }

    #[test]
    fn resolve_trickplay_meta_skips_invalid_entries() {
        let mut by_width = HashMap::new();
        by_width.insert("bad".to_string(), info(0, 180, 10, 10)); // non-positive width, invalid
        by_width.insert("good".to_string(), info(320, 180, 10, 10));
        let mut trickplay = HashMap::new();
        trickplay.insert("src".to_string(), by_width);

        let resolved = resolve_trickplay_meta(&trickplay, "src").expect("one valid entry");
        assert_eq!(resolved.width, 320);
    }

    #[test]
    fn resolve_trickplay_meta_is_none_when_the_map_is_empty() {
        assert_eq!(resolve_trickplay_meta(&HashMap::new(), "src"), None);
    }

    #[test]
    fn resolve_trickplay_meta_is_none_when_every_entry_is_invalid() {
        let mut by_width = HashMap::new();
        by_width.insert("bad".to_string(), info(0, 0, 0, 0));
        let mut trickplay = HashMap::new();
        trickplay.insert("src".to_string(), by_width);

        assert_eq!(resolve_trickplay_meta(&trickplay, "src"), None);
    }

    // --- glide sampling and prefetch ------------------------------------

    #[test]
    fn sheet_index_follows_locate() {
        let m = meta(); // 100 tiles per sheet, 10 s each -> 1000 s per sheet
        assert_eq!(sheet_index(&m, 0), Some(0));
        assert_eq!(sheet_index(&m, 999_999), Some(0));
        assert_eq!(sheet_index(&m, 1_000_000), Some(1));
        assert_eq!(sheet_index(&m, 2_500_000), Some(2));
    }

    #[test]
    fn glide_sample_first_sample_is_immediate() {
        assert_eq!(
            glide_sample(&meta(), 15_000, 5_000, None, false),
            locate(&meta(), 15_000)
        );
    }

    #[test]
    fn glide_sample_holds_until_the_dwell_elapses() {
        let m = meta();
        let last = Some(10_000u64);
        assert_eq!(
            glide_sample(
                &m,
                15_000,
                10_000 + glide_tunables::TILE_DWELL_MS - 1,
                last,
                false
            ),
            None
        );
        assert_eq!(
            glide_sample(
                &m,
                15_000,
                10_000 + glide_tunables::TILE_DWELL_MS,
                last,
                false
            ),
            locate(&m, 15_000)
        );
    }

    #[test]
    fn glide_sample_is_none_on_degenerate_geometry() {
        let mut m = meta();
        m.interval_ms = 0;
        assert_eq!(glide_sample(&m, 15_000, 1_000, None, true), None);
    }

    /// docs/12 §11: forward rounds to the nearest thumbnail, backward keeps the one at or
    /// before the target. Settled by device use, not symmetry.
    #[test]
    fn forward_takes_the_nearest_thumbnail_and_backward_the_earlier_one() {
        let m = meta(); // 10 s interval
        assert_eq!(glide_sample(&m, 14_000, 0, None, true), locate(&m, 10_000));
        assert_eq!(glide_sample(&m, 16_000, 0, None, true), locate(&m, 20_000));
        // Exactly half way rounds up, deterministically.
        assert_eq!(
            locate_biased(&m, 15_000, TileBias::Nearest),
            locate(&m, 20_000)
        );
        // Backward is unchanged: always the thumbnail at or before.
        assert_eq!(glide_sample(&m, 19_999, 0, None, false), locate(&m, 10_000));
        // The explicit biases still mean what they say.
        assert_eq!(
            locate_biased(&m, 15_000, TileBias::Later),
            locate(&m, 20_000)
        );
        assert_eq!(
            locate_biased(&m, 15_000, TileBias::Earlier),
            locate(&m, 10_000)
        );
        assert_eq!(
            locate_biased(&m, 20_000, TileBias::Nearest),
            locate(&m, 20_000)
        );
    }

    #[test]
    fn a_nearest_bias_past_the_last_thumbnail_clamps() {
        let m = meta();
        assert_eq!(
            locate_biased(&m, u32::MAX, TileBias::Nearest),
            locate(&m, 9_990_000)
        );
    }

    #[test]
    fn a_later_bias_past_the_last_thumbnail_clamps_instead_of_running_off_the_grid() {
        let m = meta(); // 1000 thumbnails, indices 0..=999
        let last = locate(&m, 9_990_000);
        assert_eq!(locate_biased(&m, 9_999_000, TileBias::Later), last);
        assert_eq!(locate_biased(&m, u32::MAX, TileBias::Later), last);
    }

    #[test]
    fn the_want_list_follows_the_biased_sheet_at_a_boundary() {
        let m = meta(); // 100 tiles per sheet, 1000 s per sheet
                        // 999_996 ms forward rounds to 1_000_000 ms, which is sheet 1, not sheet 0.
        assert_eq!(glide_want_list(&m, 999_996, 6, true)[0], 1);
        assert_eq!(glide_want_list(&m, 999_996, 6, false)[0], 0);
    }

    #[test]
    fn want_list_is_the_current_sheet_alone_when_the_lookahead_stays_inside_it() {
        // 6x for 1.5 s = 9 s of media, well inside the 1000 s sheet.
        assert_eq!(glide_want_list(&meta(), 500_000, 6, true), vec![0]);
    }

    #[test]
    fn want_list_adds_the_next_sheet_in_the_glide_direction() {
        // 900x for 1.5 s = 1350 s of media: from 500 s forward reaches sheet 1, back stays in 0.
        assert_eq!(glide_want_list(&meta(), 500_000, 900, true), vec![0, 1]);
        assert_eq!(glide_want_list(&meta(), 500_000, 900, false), vec![0]);
        assert_eq!(glide_want_list(&meta(), 1_500_000, 900, false), vec![1, 0]);
    }

    #[test]
    fn want_list_prefetches_the_adjacent_sheet_even_when_the_lookahead_reaches_farther() {
        // 900x from 990 s: 1.5 s reaches 2340 s (sheet 2), but sheet 1 is crossed first.
        assert_eq!(glide_want_list(&meta(), 990_000, 900, true), vec![0, 1]);
        assert_eq!(glide_want_list(&meta(), 2_010_000, 900, false), vec![2, 1]);
        assert_eq!(glide_want_list(&meta(), 0, u32::MAX, true), vec![0, 1]);
    }

    #[test]
    fn want_list_never_exceeds_two_sheets_and_clamps_at_the_ends() {
        let m = meta(); // 1000 thumbnails -> sheets 0..=9
        assert_eq!(glide_want_list(&m, 9_990_000, 900, true), vec![9]);
        assert_eq!(glide_want_list(&m, 0, 900, false), vec![0]);
    }

    #[test]
    fn want_list_is_empty_on_degenerate_geometry() {
        let mut m = meta();
        m.width = 0;
        assert!(glide_want_list(&m, 0, 6, true).is_empty());
    }

    #[test]
    fn abandon_only_once_the_target_is_more_than_one_sheet_past_the_fetch() {
        let m = meta();
        // Forward glide fetching sheet 3: targets in sheets 3 and 4 keep it, sheet 5 drops it.
        assert!(!should_abandon_sheet(&m, 3, 3_500_000, true));
        assert!(!should_abandon_sheet(&m, 3, 4_500_000, true));
        assert!(should_abandon_sheet(&m, 3, 5_000_000, true));
        // A sheet behind the direction of travel is kept.
        assert!(!should_abandon_sheet(&m, 3, 500_000, true));
        // Mirror for a backward glide.
        assert!(!should_abandon_sheet(&m, 3, 2_500_000, false));
        assert!(should_abandon_sheet(&m, 3, 1_500_000, false));
        assert!(!should_abandon_sheet(&m, 3, 6_000_000, false));
    }

    #[test]
    fn abandon_is_true_on_degenerate_geometry() {
        let mut m = meta();
        m.interval_ms = 0;
        assert!(should_abandon_sheet(&m, 0, 0, true));
    }
}
