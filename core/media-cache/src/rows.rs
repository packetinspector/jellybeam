//! `BaseItemDto` -> mirror column extraction; the only place the blob is parsed on the write
//! path (never browse/read).

use jellyfin_api::models::{BaseItemDto, LocationType};

/// Column values extracted from a `BaseItemDto`, ready to bind into an `items` upsert.
pub(crate) struct ItemColumns {
    pub id: String,
    pub parent_id: Option<String>,
    pub series_id: Option<String>,
    pub season_id: Option<String>,
    pub item_type: String,
    pub name: Option<String>,
    pub sort_name: Option<String>,
    pub index_number: Option<i32>,
    pub parent_index_number: Option<i32>,
    pub production_year: Option<i32>,
    pub premiere_date: Option<String>,
    pub runtime_ticks: Option<i64>,
    pub date_created: Option<String>,
    /// `None` means no `UserData` object at all, distinct from an explicit unplayed; the
    /// writer's upsert uses this to decide whether the batch is authoritative for watch state.
    pub played: Option<bool>,
    /// Same `None`-means-no-`UserData` convention as `played`.
    pub playback_position_ticks: Option<i64>,
    pub play_count: i64,
    pub is_favorite: bool,
    pub unplayed_item_count: Option<i32>,
    pub primary_tag: Option<String>,
    pub backdrop_tag: Option<String>,
    pub thumb_tag: Option<String>,
    pub primary_blurhash: Option<String>,
    /// Artwork fallback-chain columns (docs/07 §2); see `CardRow`'s doc comments in lib.rs.
    pub series_primary_tag: Option<String>,
    pub parent_backdrop_item_id: Option<String>,
    pub parent_backdrop_tag: Option<String>,
    /// `UserData.LastPlayedDate`; see `schema.rs`'s `last_played_date` comment for why
    /// `resume()` sorts by this instead of the local write clock.
    pub last_played_date: Option<String>,
    /// `Overview`; see `CardRow::overview` in lib.rs.
    pub overview: Option<String>,
    /// `LocationType == "Virtual"`; see `CardRow::is_virtual` in lib.rs.
    pub is_virtual: bool,
    /// `SeriesName`; see `CardRow::series_name` in lib.rs.
    pub series_name: Option<String>,
    /// docs/16-library-sort-filter.md §1.1: `BaseItemDto.status`, `None` outside Series.
    /// Backs the library grid's Status filter (`schema.rs`'s `series_status` column).
    pub series_status: Option<String>,
    /// docs/16-library-sort-filter.md §1.2: `BaseItemDto.genres`, verbatim (never prettified,
    /// CLAUDE.md); written to `item_genres` under the same authoritative-when-present guard
    /// as `played`/`library_id`.
    pub genres: Vec<String>,
}

/// FTS5 index text for one item (the `search` virtual table's columns, in order).
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub(crate) struct SearchText {
    pub name: String,
    pub original_title: String,
    pub series_name: String,
    pub overview: String,
}

/// The `parent_id` to use for browsing (`idx_items_browse`/`children()`). For Season/Episode
/// this is derived from `season_id`/`series_id` rather than trusting the raw `ParentId`
/// field, since the server only returns `ParentId` when explicitly requested via `fields=...`;
/// falls back to the raw field if those are absent.
fn browse_parent_id(
    item_type: &str,
    raw_parent_id: Option<String>,
    series_id: &Option<String>,
    season_id: &Option<String>,
) -> Option<String> {
    match item_type {
        "Episode" => season_id
            .clone()
            .or_else(|| series_id.clone())
            .or(raw_parent_id),
        "Season" => series_id.clone().or(raw_parent_id),
        _ => raw_parent_id,
    }
}

/// Skips (returns `None` for) a row with no `id` rather than panicking downstream; server
/// rows should always have one.
pub(crate) fn extract_columns(item: &BaseItemDto) -> Option<ItemColumns> {
    let id = item.id?.to_string();

    let primary_tag = item.image_tags.get("Primary").cloned();
    let thumb_tag = item.image_tags.get("Thumb").cloned();
    let backdrop_tag = item.backdrop_image_tags.first().cloned();
    let primary_blurhash = primary_tag.as_ref().and_then(|tag| {
        item.image_blur_hashes
            .as_ref()
            .and_then(|hashes| hashes.primary.get(tag).cloned())
    });

    let user_data = item.user_data.as_ref();

    let item_type = item
        .type_
        .map(|t| t.to_string())
        .unwrap_or_else(|| "Unknown".to_string());
    let series_id = item.series_id.map(|u| u.to_string());
    let season_id = item.season_id.map(|u| u.to_string());
    let parent_id = browse_parent_id(
        &item_type,
        item.parent_id.map(|u| u.to_string()),
        &series_id,
        &season_id,
    );

    Some(ItemColumns {
        id,
        parent_id,
        series_id,
        season_id,
        item_type,
        name: item.name.clone(),
        sort_name: item.sort_name.clone().or_else(|| item.name.clone()),
        index_number: item.index_number,
        parent_index_number: item.parent_index_number,
        production_year: item.production_year,
        premiere_date: item.premiere_date.map(|d| d.to_rfc3339()),
        runtime_ticks: item.run_time_ticks,
        date_created: item.date_created.map(|d| d.to_rfc3339()),
        // `Option::map`: a `UserData` object present but missing `Played`/
        // `PlaybackPositionTicks` still means authoritatively unplayed at 0
        // (`Some(false)`/`Some(0)`); only a missing `UserData` means "don't know"
        // (`None`). See `writer::UPSERT_ITEM_SQL`'s `COALESCE` guard.
        played: user_data.map(|u| u.played.unwrap_or(false)),
        playback_position_ticks: user_data.map(|u| u.playback_position_ticks.unwrap_or(0)),
        play_count: i64::from(user_data.and_then(|u| u.play_count).unwrap_or(0)),
        is_favorite: user_data.and_then(|u| u.is_favorite).unwrap_or(false),
        unplayed_item_count: user_data.and_then(|u| u.unplayed_item_count),
        primary_tag,
        backdrop_tag,
        thumb_tag,
        primary_blurhash,
        series_primary_tag: item.series_primary_image_tag.clone(),
        parent_backdrop_item_id: item.parent_backdrop_item_id.map(|u| u.to_string()),
        parent_backdrop_tag: item.parent_backdrop_image_tags.first().cloned(),
        last_played_date: user_data
            .and_then(|u| u.last_played_date)
            .map(|d| d.to_rfc3339()),
        overview: item.overview.clone(),
        is_virtual: item.location_type == Some(LocationType::Virtual),
        series_name: item.series_name.clone(),
        series_status: item.status.clone(),
        genres: item.genres.clone(),
    })
}

pub(crate) fn search_text(item: &BaseItemDto) -> SearchText {
    SearchText {
        name: item.name.clone().unwrap_or_default(),
        original_title: item.original_title.clone().unwrap_or_default(),
        series_name: item.series_name.clone().unwrap_or_default(),
        overview: item.overview.clone().unwrap_or_default(),
    }
}

/// Re-serializes the DTO for blob storage. This is the typed `BaseItemDto`'s JSON, not the
/// original wire bytes, so fields our generated model doesn't know about are dropped, not
/// preserved. Goes through `serde_json::Value` so map keys come out sorted: the model's
/// `HashMap` fields iterate in a per-parse random order, and the writer's no-op comparison
/// needs identical items to produce identical bytes.
pub(crate) fn to_dto_bytes(item: &BaseItemDto) -> Vec<u8> {
    serde_json::to_value(item)
        .and_then(|value| serde_json::to_vec(&value))
        .unwrap_or_else(|e| {
            tracing::warn!(error = %e, "failed to serialize BaseItemDto for blob storage");
            b"{}".to_vec()
        })
}

#[cfg(test)]
mod tests {
    use super::*;
    use jellyfin_api::models::{BaseItemDto, BaseItemKind, ImageBlurHashes, UserItemDataDto};
    use std::collections::HashMap;

    fn base_item() -> BaseItemDto {
        BaseItemDto {
            id: Some(uuid::Uuid::parse_str("e2f5a5f1-1a0b-4b3a-9c2e-000000000001").expect("uuid")),
            name: Some("Sample Movie".to_string()),
            type_: Some(BaseItemKind::Movie),
            ..Default::default()
        }
    }

    #[test]
    fn extracts_basic_columns() {
        let item = base_item();
        let cols = extract_columns(&item).expect("has id");
        assert_eq!(cols.id, "e2f5a5f1-1a0b-4b3a-9c2e-000000000001");
        assert_eq!(cols.item_type, "Movie");
        assert_eq!(cols.name.as_deref(), Some("Sample Movie"));
        assert_eq!(cols.sort_name.as_deref(), Some("Sample Movie"));
        // No `UserData` at all: "don't know", not "definitely unplayed".
        assert_eq!(cols.played, None);
        assert_eq!(cols.playback_position_ticks, None);
    }

    #[test]
    fn missing_id_yields_none() {
        let mut item = base_item();
        item.id = None;
        assert!(extract_columns(&item).is_none());
    }

    #[test]
    fn user_data_columns_extracted() {
        let mut item = base_item();
        item.user_data = Some(UserItemDataDto {
            played: Some(true),
            playback_position_ticks: Some(4200),
            play_count: Some(3),
            is_favorite: Some(true),
            unplayed_item_count: Some(2),
            key: Some("k".to_string()),
            item_id: None,
            last_played_date: None,
            likes: None,
            played_percentage: None,
            rating: None,
        });
        let cols = extract_columns(&item).expect("has id");
        assert_eq!(cols.played, Some(true));
        assert_eq!(cols.playback_position_ticks, Some(4200));
        assert_eq!(cols.play_count, 3);
        assert!(cols.is_favorite);
        assert_eq!(cols.unplayed_item_count, Some(2));
    }

    /// A `UserData` present but missing `Played`/`PlaybackPositionTicks` is still
    /// authoritative unplayed-at-0, unlike no `UserData` at all (`None`).
    #[test]
    fn user_data_present_without_played_fields_is_authoritatively_unplayed() {
        let mut item = base_item();
        item.user_data = Some(UserItemDataDto {
            played: None,
            playback_position_ticks: None,
            play_count: None,
            is_favorite: None,
            unplayed_item_count: None,
            key: Some("k".to_string()),
            item_id: None,
            last_played_date: None,
            likes: None,
            played_percentage: None,
            rating: None,
        });
        let cols = extract_columns(&item).expect("has id");
        assert_eq!(cols.played, Some(false));
        assert_eq!(cols.playback_position_ticks, Some(0));
    }

    #[test]
    fn image_tags_and_blurhash_extracted() {
        let mut item = base_item();
        let mut tags = HashMap::new();
        tags.insert("Primary".to_string(), "abc123".to_string());
        tags.insert("Thumb".to_string(), "thumbtag".to_string());
        item.image_tags = tags;
        item.backdrop_image_tags = vec!["bd1".to_string(), "bd2".to_string()];
        let mut blur = ImageBlurHashes::default();
        blur.primary
            .insert("abc123".to_string(), "L6PZfSi_.AyE".to_string());
        item.image_blur_hashes = Some(blur);

        let cols = extract_columns(&item).expect("has id");
        assert_eq!(cols.primary_tag.as_deref(), Some("abc123"));
        assert_eq!(cols.thumb_tag.as_deref(), Some("thumbtag"));
        assert_eq!(cols.backdrop_tag.as_deref(), Some("bd1"));
        assert_eq!(cols.primary_blurhash.as_deref(), Some("L6PZfSi_.AyE"));
    }

    /// Artwork fallback fields come straight off the DTO's `Series*`/`Parent*` fields, no
    /// derivation.
    #[test]
    fn artwork_fallback_columns_extracted() {
        let mut item = episode_item(Some(9), Some(8), Some(2));
        item.series_primary_image_tag = Some("series-poster-tag".to_string());
        item.parent_backdrop_item_id = Some(uuid_field(2));
        item.parent_backdrop_image_tags = vec!["season-backdrop".to_string()];

        let cols = extract_columns(&item).expect("has id");
        assert_eq!(
            cols.series_primary_tag.as_deref(),
            Some("series-poster-tag")
        );
        assert_eq!(
            cols.parent_backdrop_item_id.as_deref(),
            Some(uuid_field(2).to_string()).as_deref()
        );
        assert_eq!(cols.parent_backdrop_tag.as_deref(), Some("season-backdrop"));
    }

    #[test]
    fn artwork_fallback_columns_default_to_none() {
        let item = base_item();
        let cols = extract_columns(&item).expect("has id");
        assert_eq!(cols.series_primary_tag, None);
        assert_eq!(cols.parent_backdrop_item_id, None);
        assert_eq!(cols.parent_backdrop_tag, None);
    }

    /// `series_name` extracts straight off the DTO's `SeriesName`.
    #[test]
    fn series_name_extracted() {
        let mut item = episode_item(Some(9), Some(8), Some(2));
        item.series_name = Some("Series Alpha".to_string());
        let cols = extract_columns(&item).expect("has id");
        assert_eq!(cols.series_name.as_deref(), Some("Series Alpha"));
    }

    #[test]
    fn series_name_defaults_to_none() {
        let item = base_item();
        let cols = extract_columns(&item).expect("has id");
        assert_eq!(cols.series_name, None);
    }

    /// docs/16-library-sort-filter.md §1.1: `series_status` extracts straight off the DTO's
    /// `Status`.
    #[test]
    fn series_status_extracted() {
        let mut item = episode_item(Some(9), Some(8), Some(2));
        item.status = Some("Continuing".to_string());
        let cols = extract_columns(&item).expect("has id");
        assert_eq!(cols.series_status.as_deref(), Some("Continuing"));
    }

    #[test]
    fn series_status_defaults_to_none() {
        let item = base_item();
        let cols = extract_columns(&item).expect("has id");
        assert_eq!(cols.series_status, None);
    }

    /// docs/16-library-sort-filter.md §1.2: genres extract verbatim, never prettified/reordered
    /// (CLAUDE.md).
    #[test]
    fn genres_extracted_verbatim() {
        let mut item = base_item();
        item.genres = vec!["Science Fiction".to_string(), "Drama".to_string()];
        let cols = extract_columns(&item).expect("has id");
        assert_eq!(
            cols.genres,
            vec!["Science Fiction".to_string(), "Drama".to_string()]
        );
    }

    #[test]
    fn genres_default_to_empty() {
        let item = base_item();
        let cols = extract_columns(&item).expect("has id");
        assert!(cols.genres.is_empty());
    }

    #[test]
    fn sort_name_falls_back_to_name_when_absent() {
        let mut item = base_item();
        item.sort_name = None;
        item.name = Some("The Thing".to_string());
        let cols = extract_columns(&item).expect("has id");
        assert_eq!(cols.sort_name.as_deref(), Some("The Thing"));
    }

    fn uuid_field(n: u8) -> uuid::Uuid {
        uuid::Uuid::parse_str(&format!("e2f5a5f1-1a0b-4b3a-9c2e-{n:012}")).expect("uuid")
    }

    /// Mirrors the live server's Episode `ParentId == SeasonId`; ids kept distinct so tests
    /// can tell which one `parent_id` actually came from.
    fn episode_item(raw_parent: Option<u8>, series: Option<u8>, season: Option<u8>) -> BaseItemDto {
        BaseItemDto {
            id: Some(uuid_field(1)),
            name: Some("S01E01".to_string()),
            type_: Some(BaseItemKind::Episode),
            parent_id: raw_parent.map(uuid_field),
            series_id: series.map(uuid_field),
            season_id: season.map(uuid_field),
            ..Default::default()
        }
    }

    fn season_item(raw_parent: Option<u8>, series: Option<u8>) -> BaseItemDto {
        BaseItemDto {
            id: Some(uuid_field(1)),
            name: Some("Season 1".to_string()),
            type_: Some(BaseItemKind::Season),
            parent_id: raw_parent.map(uuid_field),
            series_id: series.map(uuid_field),
            season_id: None,
            ..Default::default()
        }
    }

    /// Once `ParentId` is populated, browse-time `parent_id` must still come from `SeasonId`,
    /// not the raw field, so `children(season_id)` works regardless of what `ParentId` points at.
    #[test]
    fn episode_parent_id_prefers_season_id_over_raw_parent_id() {
        let item = episode_item(Some(9), Some(8), Some(2));
        let cols = extract_columns(&item).expect("has id");
        assert_eq!(
            cols.parent_id.as_deref(),
            Some(uuid_field(2).to_string()).as_deref()
        );
        assert_eq!(
            cols.season_id.as_deref(),
            Some(uuid_field(2).to_string()).as_deref()
        );
    }

    #[test]
    fn episode_parent_id_falls_back_to_series_id_when_season_id_absent() {
        let item = episode_item(Some(9), Some(8), None);
        let cols = extract_columns(&item).expect("has id");
        assert_eq!(
            cols.parent_id.as_deref(),
            Some(uuid_field(8).to_string()).as_deref()
        );
    }

    #[test]
    fn episode_parent_id_falls_back_to_raw_parent_id_when_series_and_season_absent() {
        let item = episode_item(Some(9), None, None);
        let cols = extract_columns(&item).expect("has id");
        assert_eq!(
            cols.parent_id.as_deref(),
            Some(uuid_field(9).to_string()).as_deref()
        );
    }

    #[test]
    fn episode_parent_id_is_none_when_nothing_is_available() {
        let item = episode_item(None, None, None);
        let cols = extract_columns(&item).expect("has id");
        assert_eq!(cols.parent_id, None);
    }

    /// Same as the Episode case one level up: a Season's `parent_id` must come from `SeriesId`.
    #[test]
    fn season_parent_id_prefers_series_id_over_raw_parent_id() {
        let item = season_item(Some(9), Some(3));
        let cols = extract_columns(&item).expect("has id");
        assert_eq!(
            cols.parent_id.as_deref(),
            Some(uuid_field(3).to_string()).as_deref()
        );
        assert_eq!(
            cols.series_id.as_deref(),
            Some(uuid_field(3).to_string()).as_deref()
        );
    }

    #[test]
    fn season_parent_id_falls_back_to_raw_parent_id_when_series_id_absent() {
        let item = season_item(Some(9), None);
        let cols = extract_columns(&item).expect("has id");
        assert_eq!(
            cols.parent_id.as_deref(),
            Some(uuid_field(9).to_string()).as_deref()
        );
    }

    /// Regression guard: the Season/Episode override must not leak to other item types.
    #[test]
    fn other_item_types_use_raw_parent_id_unchanged() {
        let mut item = base_item(); // Movie
        item.parent_id = Some(uuid_field(7));
        item.series_id = Some(uuid_field(3)); // should be ignored for a Movie
        let cols = extract_columns(&item).expect("has id");
        assert_eq!(
            cols.parent_id.as_deref(),
            Some(uuid_field(7).to_string()).as_deref()
        );
    }

    #[test]
    fn search_text_defaults_missing_fields_to_empty() {
        let item = base_item();
        let text = search_text(&item);
        assert_eq!(text.name, "Sample Movie");
        assert_eq!(text.original_title, "");
        assert_eq!(text.overview, "");
    }

    #[test]
    fn dto_bytes_are_identical_for_identical_items_regardless_of_map_insertion_order() {
        let mut a = BaseItemDto::default();
        let mut b = BaseItemDto::default();
        for (k, v) in [
            ("Primary", "t1"),
            ("Backdrop", "t2"),
            ("Thumb", "t3"),
            ("Logo", "t4"),
        ] {
            a.image_tags.insert(k.to_string(), v.to_string());
        }
        for (k, v) in [
            ("Logo", "t4"),
            ("Thumb", "t3"),
            ("Backdrop", "t2"),
            ("Primary", "t1"),
        ] {
            b.image_tags.insert(k.to_string(), v.to_string());
        }
        for i in 0..8 {
            a.provider_ids.insert(format!("P{i}"), i.to_string());
            b.provider_ids
                .insert(format!("P{}", 7 - i), (7 - i).to_string());
        }
        assert_eq!(to_dto_bytes(&a), to_dto_bytes(&b));
    }
}
