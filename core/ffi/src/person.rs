//! Library person page (docs/11 §Person page): a person's own record plus their titles in the
//! user's library, fetched live (people are never mirrored), and the Seerr credits not yet
//! owned. The decision functions are plain Rust with unit tests.

use std::collections::{HashMap, HashSet};

use jellyfin_api::models::{BaseItemDto, BaseItemKind};

use crate::error::CoreError;
use crate::object::{live_children_fields, JellybeamCore};
use crate::seerr_types::{SeerrAvailability, SeerrCard, SeerrMediaType};
use crate::types::Card;

/// Cap on one person's library titles; a long career in one library stays a single page.
const MAX_LIBRARY_TITLES: u32 = 200;
/// Cap on the ids-only ownership query behind the Seerr filter, far past any real career.
const MAX_OWNED_TITLES: u32 = 10_000;

/// Whether the display query stopped short of everything the person has in the library; with no
/// count from the server, a full page is assumed to be cut short.
fn titles_truncated(total: Option<i32>, shown: usize) -> bool {
    match total {
        Some(n) => usize::try_from(n).is_ok_and(|n| n > shown),
        None => shown >= MAX_LIBRARY_TITLES as usize,
    }
}

/// A library title's TMDB identity, kind included: a movie and a series can share a number.
#[derive(uniffi::Record, Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct TitleTmdbRef {
    pub media_type: SeerrMediaType,
    pub tmdb_id: i64,
}

/// One person page. Dates are RFC3339 as the server sent them (birth in `PremiereDate`, death in
/// `EndDate`); text fields are server data and shown verbatim.
#[derive(uniffi::Record, Debug, Clone, PartialEq)]
pub struct PersonPage {
    pub id: String,
    pub name: String,
    pub overview: Option<String>,
    pub primary_image_tag: Option<String>,
    pub birth_date: Option<String>,
    pub death_date: Option<String>,
    pub birth_place: Option<String>,
    /// The person's TMDB id, which is also their Seerr person id.
    pub tmdb_person_id: Option<i64>,
    /// Movies and series crediting the person, newest premiere first.
    pub library: Vec<Card>,
    /// TMDB identities of [`Self::library`] for [`JellybeamCore::person_discover_credits`]; `None`
    /// when the display cap hid some titles, so that call enumerates ownership itself.
    pub library_tmdb: Option<Vec<TitleTmdbRef>>,
}

/// The `Tmdb` provider id, matched case-insensitively, `None` unless a positive integer.
fn tmdb_id_of(provider_ids: &HashMap<String, String>) -> Option<i64> {
    provider_ids
        .iter()
        .find(|(key, _)| key.eq_ignore_ascii_case("tmdb"))
        .and_then(|(_, value)| value.trim().parse::<i64>().ok())
        .filter(|id| *id > 0)
}

fn title_ref(dto: &BaseItemDto) -> Option<TitleTmdbRef> {
    let media_type = match dto.type_? {
        BaseItemKind::Movie => SeerrMediaType::Movie,
        BaseItemKind::Series => SeerrMediaType::Tv,
        _ => return None,
    };
    Some(TitleTmdbRef {
        media_type,
        tmdb_id: tmdb_id_of(&dto.provider_ids)?,
    })
}

/// Cap on the "Not in your library" row, applied after owned titles are removed.
const MAX_DISCOVER_CREDITS: usize = 50;

/// Seerr credits whose title is not already in the library (kind and TMDB id both match, or Seerr
/// reports it Available, which covers a library title with no TMDB id), capped only after
/// filtering so a mostly-owned career still fills the row.
pub(crate) fn credits_beside_library(
    credits: Vec<SeerrCard>,
    in_library: &[TitleTmdbRef],
) -> Vec<SeerrCard> {
    let owned: HashSet<TitleTmdbRef> = in_library.iter().copied().collect();
    credits
        .into_iter()
        .filter(|card| {
            card.availability != SeerrAvailability::Available
                && !owned.contains(&TitleTmdbRef {
                    media_type: card.media_type,
                    tmdb_id: card.tmdb_id,
                })
        })
        .take(MAX_DISCOVER_CREDITS)
        .collect()
}

/// `complete` says `titles` is every library title the person is in, not a capped page of them.
fn page_from(person: &BaseItemDto, titles: &[BaseItemDto], complete: bool) -> PersonPage {
    PersonPage {
        id: person.id.map(|id| id.to_string()).unwrap_or_default(),
        name: person.name.clone().unwrap_or_default(),
        overview: person
            .overview
            .clone()
            .filter(|text| !text.trim().is_empty()),
        primary_image_tag: person.image_tags.get("Primary").cloned(),
        birth_date: person.premiere_date.map(|d| d.to_rfc3339()),
        death_date: person.end_date.map(|d| d.to_rfc3339()),
        birth_place: person
            .production_locations
            .iter()
            .find(|place| !place.trim().is_empty())
            .cloned(),
        tmdb_person_id: tmdb_id_of(&person.provider_ids),
        library: titles
            .iter()
            .filter_map(|dto| Card::try_from(dto).ok())
            .collect(),
        library_tmdb: complete.then(|| tmdb_refs(titles)),
    }
}

fn tmdb_refs(titles: &[BaseItemDto]) -> Vec<TitleTmdbRef> {
    titles.iter().filter_map(title_ref).collect()
}

/// An empty id would turn `/Items/{id}` and `personIds=` into unfiltered listings, never a person.
fn require_person_id(person_id: &str) -> Result<(), CoreError> {
    if person_id.trim().is_empty() {
        return Err(CoreError::Api {
            detail: "empty person id".to_string(),
        });
    }
    Ok(())
}

/// The person's Movie/Series titles in the user's library, newest premiere first.
fn titles_query(person_id: String) -> jellyfin_api::ItemQuery {
    let mut fields = live_children_fields();
    fields.push("ProviderIds".to_string());
    jellyfin_api::ItemQuery {
        person_ids: vec![person_id],
        include_item_types: vec!["Movie".to_string(), "Series".to_string()],
        recursive: true,
        sort_by: Some("PremiereDate".to_string()),
        sort_order: Some("Descending".to_string()),
        fields,
        limit: MAX_LIBRARY_TITLES,
        ..jellyfin_api::ItemQuery::new()
    }
}

/// Every owned title's provider ids and nothing else, for the Seerr filter past the display cap.
fn ownership_query(person_id: String) -> jellyfin_api::ItemQuery {
    jellyfin_api::ItemQuery {
        fields: vec!["ProviderIds".to_string()],
        sort_by: None,
        sort_order: None,
        limit: MAX_OWNED_TITLES,
        enable_images: Some(false),
        enable_user_data: Some(false),
        ..titles_query(person_id)
    }
}

fn person_fields() -> Vec<String> {
    [
        "Overview",
        "ProviderIds",
        "PremiereDate",
        "EndDate",
        "ProductionLocations",
    ]
    .into_iter()
    .map(String::from)
    .collect()
}

/// The person's record; a server without the by-id route (404/405) is asked through `/Items?ids=`.
async fn person_record(
    client: &jellyfin_api::JellyfinClient,
    person_id: &str,
) -> Result<BaseItemDto, jellyfin_api::ApiError> {
    let fields = person_fields();
    match client.get_item_by_id(person_id, &fields).await {
        Err(jellyfin_api::ApiError::Status {
            code: 404 | 405, ..
        }) => client
            .get_items(&jellyfin_api::ItemQuery {
                ids: vec![person_id.to_string()],
                fields,
                limit: 1,
                ..jellyfin_api::ItemQuery::new()
            })
            .await?
            .items
            .into_iter()
            .next()
            .ok_or(jellyfin_api::ApiError::Status {
                code: 404,
                body: String::new(),
            }),
        other => other,
    }
}

#[uniffi::export]
impl JellybeamCore {
    /// The person's record and their Movie/Series titles in the signed-in user's library, live.
    /// A failed titles query fails the page; the person record alone is not worth showing.
    pub fn get_person_page(&self, person_id: String) -> Result<PersonPage, CoreError> {
        require_person_id(&person_id)?;
        let client = self.require_client()?;
        let titles_query = titles_query(person_id.clone());
        let (person, titles) = self.runtime().block_on(async {
            tokio::join!(
                person_record(&client, &person_id),
                client.get_items(&titles_query),
            )
        });
        let (person, titles) = (person?, titles?);
        let complete = !titles_truncated(titles.total_record_count, titles.items.len());
        Ok(page_from(&person, &titles.items, complete))
    }

    /// Seerr credits for `tmdb_person_id` minus the library. `in_library` is
    /// [`PersonPage::library_tmdb`]; when `None`, ownership is listed here first (docs/11 §Person
    /// page: the display cap is not ownership). Errors, including an unconfigured Seerr or a
    /// failed ownership listing, are the caller's to swallow: the row is optional.
    pub fn person_discover_credits(
        &self,
        person_id: String,
        tmdb_person_id: i64,
        in_library: Option<Vec<TitleTmdbRef>>,
    ) -> Result<Vec<SeerrCard>, CoreError> {
        require_person_id(&person_id)?;
        // No Seerr, no row: refuse before listing ownership for nothing.
        self.require_seerr_handle()?;
        let in_library = match in_library {
            Some(refs) => refs,
            None => {
                let client = self.require_client()?;
                let owned = self
                    .runtime()
                    .block_on(client.get_items(&ownership_query(person_id)))?;
                tmdb_refs(&owned.items)
            }
        };
        let credits = self.seerr_person_credit_cards(tmdb_person_id)?;
        Ok(credits_beside_library(credits, &in_library))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn seerr_card(media_type: SeerrMediaType, tmdb_id: i64) -> SeerrCard {
        SeerrCard {
            media_type,
            tmdb_id,
            title: format!("Title {tmdb_id}"),
            year: None,
            overview: None,
            poster_url: None,
            backdrop_url: None,
            availability: SeerrAvailability::NotRequested,
            jellyfin_item_id: None,
        }
    }

    fn dto(json: serde_json::Value) -> BaseItemDto {
        serde_json::from_value(json).expect("valid BaseItemDto json")
    }

    #[test]
    fn tmdb_id_reads_any_key_case_and_rejects_junk() {
        let ids = |key: &str, value: &str| HashMap::from([(key.to_string(), value.to_string())]);
        assert_eq!(tmdb_id_of(&ids("Tmdb", "9001")), Some(9001));
        assert_eq!(tmdb_id_of(&ids("tmdb", " 9001 ")), Some(9001));
        assert_eq!(tmdb_id_of(&ids("Imdb", "tt0000001")), None);
        assert_eq!(tmdb_id_of(&ids("Tmdb", "abc")), None);
        assert_eq!(tmdb_id_of(&ids("Tmdb", "0")), None);
        assert_eq!(tmdb_id_of(&HashMap::new()), None);
    }

    #[test]
    fn owned_credits_never_crowd_out_the_row() {
        let owned: Vec<TitleTmdbRef> = (0..60)
            .map(|id| TitleTmdbRef {
                media_type: SeerrMediaType::Movie,
                tmdb_id: id,
            })
            .collect();
        let credits = (0..120)
            .map(|id| seerr_card(SeerrMediaType::Movie, id))
            .collect();
        let row = credits_beside_library(credits, &owned);
        assert_eq!(row.len(), MAX_DISCOVER_CREDITS);
        assert_eq!(row.first().map(|c| c.tmdb_id), Some(60));
    }

    #[test]
    fn credits_beside_library_matches_on_kind_and_id() {
        let credits = vec![
            seerr_card(SeerrMediaType::Movie, 1),
            seerr_card(SeerrMediaType::Tv, 1),
            seerr_card(SeerrMediaType::Movie, 2),
        ];
        let in_library = [TitleTmdbRef {
            media_type: SeerrMediaType::Movie,
            tmdb_id: 1,
        }];
        let rest = credits_beside_library(credits, &in_library);
        let ids: Vec<(SeerrMediaType, i64)> =
            rest.iter().map(|c| (c.media_type, c.tmdb_id)).collect();
        assert_eq!(
            ids,
            vec![(SeerrMediaType::Tv, 1), (SeerrMediaType::Movie, 2)],
            "the series sharing a number with an owned movie must stay"
        );
    }

    #[test]
    fn credits_seerr_already_has_are_never_offered() {
        let mut available = seerr_card(SeerrMediaType::Movie, 5);
        available.availability = SeerrAvailability::Available;
        let mut partial = seerr_card(SeerrMediaType::Tv, 6);
        partial.availability = SeerrAvailability::PartiallyAvailable;
        let rest = credits_beside_library(vec![available, partial.clone()], &[]);
        assert_eq!(rest, vec![partial]);
    }

    #[test]
    fn credits_beside_an_empty_library_keeps_everything_in_order() {
        let credits = vec![
            seerr_card(SeerrMediaType::Movie, 9),
            seerr_card(SeerrMediaType::Tv, 8),
        ];
        assert_eq!(credits_beside_library(credits.clone(), &[]), credits);
    }

    #[test]
    fn page_maps_person_fields_and_library_titles() {
        let person = dto(serde_json::json!({
            "Id": "00000000-0000-0000-0000-0000000000aa",
            "Name": "Sample Person",
            "Type": "Person",
            "Overview": "A synthetic biography.",
            "ImageTags": { "Primary": "tag1" },
            "PremiereDate": "1970-05-04T00:00:00.0000000Z",
            "EndDate": "2020-01-02T00:00:00.0000000Z",
            "ProductionLocations": ["Sampletown, Nowhere"],
            "ProviderIds": { "Tmdb": "4242" }
        }));
        let titles = vec![
            dto(serde_json::json!({
                "Id": "00000000-0000-0000-0000-000000000001",
                "Name": "Sample Movie",
                "Type": "Movie",
                "ProviderIds": { "Tmdb": "11" }
            })),
            dto(serde_json::json!({
                "Id": "00000000-0000-0000-0000-000000000002",
                "Name": "Sample Series",
                "Type": "Series",
                "ProviderIds": { "Tmdb": "22" }
            })),
            dto(serde_json::json!({
                "Id": "00000000-0000-0000-0000-000000000003",
                "Name": "No Provider Movie",
                "Type": "Movie"
            })),
        ];
        let page = page_from(&person, &titles, true);
        assert_eq!(page.name, "Sample Person");
        assert_eq!(page.overview.as_deref(), Some("A synthetic biography."));
        assert_eq!(page.primary_image_tag.as_deref(), Some("tag1"));
        assert!(page
            .birth_date
            .as_deref()
            .is_some_and(|d| d.starts_with("1970-05-04")));
        assert!(page
            .death_date
            .as_deref()
            .is_some_and(|d| d.starts_with("2020-01-02")));
        assert_eq!(page.birth_place.as_deref(), Some("Sampletown, Nowhere"));
        assert_eq!(page.tmdb_person_id, Some(4242));
        assert_eq!(
            page.library.len(),
            3,
            "a title without a TMDB id is still shown"
        );
        assert_eq!(
            page.library_tmdb.as_deref(),
            Some(
                &[
                    TitleTmdbRef {
                        media_type: SeerrMediaType::Movie,
                        tmdb_id: 11
                    },
                    TitleTmdbRef {
                        media_type: SeerrMediaType::Tv,
                        tmdb_id: 22
                    },
                ][..]
            )
        );
    }

    #[test]
    fn a_capped_display_claims_no_ownership() {
        let person = dto(serde_json::json!({ "Name": "Sample Person" }));
        let shown = vec![dto(serde_json::json!({
            "Id": "00000000-0000-0000-0000-000000000001",
            "Name": "Newest Movie",
            "Type": "Movie",
            "ProviderIds": { "Tmdb": "11" }
        }))];
        let page = page_from(&person, &shown, false);
        assert_eq!(page.library.len(), 1);
        assert_eq!(page.library_tmdb, None);
    }

    #[test]
    fn ownership_query_lists_ids_only_without_the_display_cap() {
        let q = ownership_query("person-1".to_string());
        assert_eq!(q.person_ids, vec!["person-1".to_string()]);
        assert_eq!(q.limit, MAX_OWNED_TITLES);
        assert_eq!(q.fields, vec!["ProviderIds".to_string()]);
        assert_eq!(
            (q.enable_images, q.enable_user_data),
            (Some(false), Some(false))
        );
        assert_eq!(q.sort_by, None);
    }

    #[test]
    fn titles_are_truncated_only_when_the_server_counted_more() {
        assert!(titles_truncated(Some(201), 200));
        assert!(!titles_truncated(Some(200), 200));
        assert!(
            titles_truncated(None, 200),
            "no count and a full page: assume more"
        );
        assert!(!titles_truncated(None, 199));
        assert!(!titles_truncated(Some(-1), 0));
    }

    #[test]
    fn page_tolerates_a_bare_person() {
        let person = dto(serde_json::json!({
            "Id": "00000000-0000-0000-0000-0000000000aa",
            "Name": "Sample Person",
            "Overview": "   "
        }));
        let page = page_from(&person, &[], true);
        assert_eq!(page.overview, None, "a blank biography reads as absent");
        assert_eq!(page.primary_image_tag, None);
        assert_eq!(page.birth_date, None);
        assert_eq!(page.tmdb_person_id, None);
        assert!(page.library.is_empty());
    }

    #[test]
    fn person_page_before_sign_in_is_not_signed_in() {
        let dir = tempfile::tempdir().expect("tempdir");
        let core = JellybeamCore::new(dir.path().to_string_lossy().to_string());
        let err = core
            .get_person_page("person-1".to_string())
            .expect_err("no client yet");
        assert!(matches!(err, CoreError::NotSignedIn));
    }

    #[test]
    fn an_empty_person_id_is_refused_before_any_request() {
        let dir = tempfile::tempdir().expect("tempdir");
        let core = JellybeamCore::new(dir.path().to_string_lossy().to_string());
        for id in ["", "  "] {
            let err = core.get_person_page(id.to_string()).expect_err("refused");
            assert!(matches!(err, CoreError::Api { .. }), "{err:?}");
            let err = core
                .person_discover_credits(id.to_string(), 7, None)
                .expect_err("refused");
            assert!(matches!(err, CoreError::Api { .. }), "{err:?}");
        }
    }

    #[test]
    fn the_seerr_row_is_refused_before_the_ownership_listing_without_seerr() {
        let dir = tempfile::tempdir().expect("tempdir");
        let core = JellybeamCore::new(dir.path().to_string_lossy().to_string());
        // Not signed in either: Seerr's absence must answer first, or the row would list for nothing.
        let err = core
            .person_discover_credits("person-1".to_string(), 7, None)
            .expect_err("no seerr");
        assert!(matches!(err, CoreError::SeerrNotConfigured), "{err:?}");
    }
}
