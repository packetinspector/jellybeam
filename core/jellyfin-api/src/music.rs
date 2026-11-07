//! docs/feature-dev/music-design-brief.md §1.3: the live music routes (genres, playlists,
//! Instant Mix, lyrics). Bodies the pinned 12.0.0 spec subset doesn't generate (`LyricDto`,
//! `CreatePlaylistDto`, `PlaylistCreationResult`) are hand-modelled with only the fields used.

use crate::models::{BaseItemDto, ItemsResult};
use crate::util::{describe_error_chain, percent_encode};
use crate::{read_json_capped, ApiError, ItemQuery, JellyfinClient};

/// §1.3 Instant Mix: what a mix is seeded from; each picks its own route.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum MixSeed {
    Song(String),
    Album(String),
    Artist(String),
    Playlist(String),
    /// A genre by its verbatim name: `Synthwave;Ambient` is one genre.
    Genre(String),
}

impl MixSeed {
    fn path(&self) -> String {
        let (kind, key) = match self {
            Self::Song(id) => ("Songs", id),
            Self::Album(id) => ("Albums", id),
            Self::Artist(id) => ("Artists", id),
            Self::Playlist(id) => ("Playlists", id),
            Self::Genre(name) => ("MusicGenres", name),
        };
        format!("/{kind}/{}/InstantMix", percent_encode(key))
    }
}

/// Whether a playlist page carries images and user data (`Full`) or ids only.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PlaylistItemsShape {
    Full,
    IdsOnly,
}

/// One served lyric line; `start_ticks` is `None` on unsynced lyrics (1 tick = 100 ns).
#[derive(Debug, Clone, PartialEq, Eq, serde::Deserialize)]
pub struct LyricLine {
    #[serde(rename = "Text", default)]
    pub text: String,
    #[serde(rename = "Start", default)]
    pub start_ticks: Option<i64>,
}

#[derive(serde::Deserialize)]
struct LyricsBody {
    #[serde(rename = "Lyrics", default)]
    lyrics: Vec<LyricLine>,
}

#[derive(serde::Serialize)]
struct CreatePlaylistBody<'a> {
    #[serde(rename = "Name")]
    name: &'a str,
    #[serde(rename = "Ids")]
    ids: &'a [String],
    #[serde(rename = "UserId", skip_serializing_if = "Option::is_none")]
    user_id: Option<&'a str>,
    #[serde(rename = "MediaType")]
    media_type: &'static str,
}

#[derive(serde::Deserialize)]
struct PlaylistCreated {
    #[serde(rename = "Id")]
    id: String,
}

/// Ids per playlist add/remove request: Kestrel refuses a request line past ~8 KB, and 100
/// encoded GUIDs are ~3.5 KB.
const PLAYLIST_EDIT_BATCH: usize = 100;

impl JellyfinClient {
    /// A body-less request answered with no content (playlist edits answer 204).
    async fn send_no_content(
        &self,
        method: reqwest::Method,
        path: &str,
        query: &[(&str, String)],
    ) -> Result<(), ApiError> {
        let url = format!("{}{}", self.inner.base_url, path);
        let resp = self
            .inner
            .http
            .request(method, &url)
            .header("Authorization", self.auth_header())
            .query(query)
            .send()
            .await
            .map_err(|e| ApiError::Transport(describe_error_chain(&e)))?;
        self.check(resp).await?;
        Ok(())
    }

    /// §1.3 Genres: `GET /MusicGenres?parentId=` for each genre's art only; counts come from the
    /// mirror, since `ItemCounts` makes the server count every genre and stalls the tab.
    pub async fn get_music_genres(
        &self,
        parent_id: &str,
        start_index: u32,
        limit: u32,
    ) -> Result<ItemsResult, ApiError> {
        let mut query: Vec<(&str, String)> = vec![
            ("parentId", parent_id.to_string()),
            ("sortBy", "SortName".to_string()),
            ("enableImageTypes", "Primary".to_string()),
            ("imageTypeLimit", "1".to_string()),
            ("enableTotalRecordCount", "false".to_string()),
            ("startIndex", start_index.to_string()),
            ("limit", limit.to_string()),
        ];
        self.push_user_id(&mut query);
        self.get("/MusicGenres", &query).await
    }

    /// §1.3 Playlists: audio ones only, most recently added to first (`DateLastContentAdded`),
    /// then by name. `limit` 0 answers only the total count.
    pub async fn get_playlists(
        &self,
        start_index: u32,
        limit: u32,
        fields: Vec<String>,
    ) -> Result<ItemsResult, ApiError> {
        let query = ItemQuery {
            include_item_types: vec!["Playlist".to_string()],
            media_types: vec!["Audio".to_string()],
            recursive: true,
            sort_by: Some("DateLastContentAdded,SortName".to_string()),
            sort_order: Some("Descending,Ascending".to_string()),
            fields,
            start_index,
            limit,
            ..ItemQuery::new()
        };
        self.get_items(&query).await
    }

    /// §1.3 `GET /Playlists/{id}/Items` in playlist order; each item carries its
    /// `PlaylistItemId`.
    pub async fn get_playlist_items(
        &self,
        playlist_id: &str,
        start_index: u32,
        limit: u32,
        fields: &[String],
        shape: PlaylistItemsShape,
    ) -> Result<ItemsResult, ApiError> {
        let path = format!("/Playlists/{}/Items", percent_encode(playlist_id));
        let mut query: Vec<(&str, String)> = vec![
            ("startIndex", start_index.to_string()),
            ("limit", limit.to_string()),
        ];
        if !fields.is_empty() {
            query.push(("fields", fields.join(",")));
        }
        if shape == PlaylistItemsShape::IdsOnly {
            query.push(("enableImages", "false".to_string()));
            query.push(("enableUserData", "false".to_string()));
        }
        self.push_user_id(&mut query);
        self.get(&path, &query).await
    }

    /// §1.3 `POST /Playlists` as an audio playlist holding `item_ids`; returns the new id.
    pub async fn create_playlist(
        &self,
        name: &str,
        item_ids: &[String],
    ) -> Result<String, ApiError> {
        let url = format!("{}/Playlists", self.inner.base_url);
        let body = CreatePlaylistBody {
            name,
            ids: item_ids,
            user_id: self.user_id(),
            media_type: "Audio",
        };
        let resp = self
            .inner
            .http
            .post(&url)
            .header("Authorization", self.auth_header())
            .json(&body)
            .send()
            .await
            .map_err(|e| ApiError::Transport(describe_error_chain(&e)))?;
        let resp = self.check(resp).await?;
        let created: PlaylistCreated = read_json_capped(resp, "/Playlists").await?;
        Ok(created.id)
    }

    /// §1.3 `POST /Playlists/{id}/Items?ids=`: appends, in order, in batches of
    /// [`PLAYLIST_EDIT_BATCH`]; duplicates are allowed (handoff §4 E2).
    pub async fn add_to_playlist(
        &self,
        playlist_id: &str,
        item_ids: &[String],
    ) -> Result<(), ApiError> {
        let path = format!("/Playlists/{}/Items", percent_encode(playlist_id));
        for batch in item_ids.chunks(PLAYLIST_EDIT_BATCH) {
            let mut query: Vec<(&str, String)> = vec![("ids", batch.join(","))];
            self.push_user_id(&mut query);
            self.send_no_content(reqwest::Method::POST, &path, &query)
                .await?;
        }
        Ok(())
    }

    /// §1.3 `POST /Playlists/{id}/Items/{entryId}/Move/{index}`; `entry_id` is the
    /// `PlaylistItemId`, not the item id.
    pub async fn move_playlist_item(
        &self,
        playlist_id: &str,
        entry_id: &str,
        new_index: u32,
    ) -> Result<(), ApiError> {
        let path = format!(
            "/Playlists/{}/Items/{}/Move/{new_index}",
            percent_encode(playlist_id),
            percent_encode(entry_id)
        );
        self.send_no_content(reqwest::Method::POST, &path, &[])
            .await
    }

    /// §1.3 `DELETE /Playlists/{id}/Items?entryIds=`, by `PlaylistItemId`, in batches of
    /// [`PLAYLIST_EDIT_BATCH`].
    pub async fn remove_from_playlist(
        &self,
        playlist_id: &str,
        entry_ids: &[String],
    ) -> Result<(), ApiError> {
        let path = format!("/Playlists/{}/Items", percent_encode(playlist_id));
        for batch in entry_ids.chunks(PLAYLIST_EDIT_BATCH) {
            let query = [("entryIds", batch.join(","))];
            self.send_no_content(reqwest::Method::DELETE, &path, &query)
                .await?;
        }
        Ok(())
    }

    /// §1.3 Instant Mix: a shuffled `Audio` list around `seed`.
    pub async fn get_instant_mix(
        &self,
        seed: &MixSeed,
        limit: u32,
        fields: &[String],
    ) -> Result<Vec<BaseItemDto>, ApiError> {
        let mut query: Vec<(&str, String)> = vec![("limit", limit.to_string())];
        if !fields.is_empty() {
            query.push(("fields", fields.join(",")));
        }
        self.push_user_id(&mut query);
        let result: ItemsResult = self.get(&seed.path(), &query).await?;
        Ok(result.items)
    }

    /// §1.3 `GET /Audio/{id}/Lyrics`; `Ok(None)` on the 404 a track without lyrics answers.
    pub async fn get_lyrics(&self, item_id: &str) -> Result<Option<Vec<LyricLine>>, ApiError> {
        let url = format!(
            "{}/Audio/{}/Lyrics",
            self.inner.base_url,
            percent_encode(item_id)
        );
        let resp = self
            .inner
            .http
            .get(&url)
            .header("Authorization", self.auth_header())
            .send()
            .await
            .map_err(|e| ApiError::Transport(describe_error_chain(&e)))?;
        if resp.status() == reqwest::StatusCode::NOT_FOUND {
            return Ok(None);
        }
        let resp = self.check(resp).await?;
        let body: LyricsBody = read_json_capped(resp, "/Audio/{itemId}/Lyrics").await?;
        Ok(Some(body.lyrics))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::test_server::capturing_server;
    use crate::ClientIdentity;

    fn client(base_url: &str) -> JellyfinClient {
        let identity = ClientIdentity {
            client: "Jellybeam".to_string(),
            device: "Test TV".to_string(),
            device_id: "device-test".to_string(),
            version: "0.0.0".to_string(),
        };
        JellyfinClient::from_token(base_url, identity, "tok")
            .with_user_id("00000000-0000-4000-8000-000000000001")
    }

    /// The request line ("METHOD target HTTP/1.1") of a captured request.
    async fn request_line(rx: tokio::sync::oneshot::Receiver<String>) -> String {
        let raw = rx.await.expect("request captured");
        raw.lines().next().unwrap_or_default().to_string()
    }

    const EMPTY_PAGE: &str = r#"{"Items":[],"TotalRecordCount":0}"#;

    fn assert_has(line: &str, parts: &[&str]) {
        for part in parts {
            assert!(line.contains(part), "{part} missing from {line}");
        }
    }

    #[tokio::test]
    async fn music_genres_query_the_view_without_item_counts() {
        let (base, rx) = capturing_server("200 OK", EMPTY_PAGE).await;
        client(&base)
            .get_music_genres("00000000-0000-4000-8000-000000000010", 20, 40)
            .await
            .expect("genres");
        let line = request_line(rx).await;
        assert!(line.starts_with("GET /MusicGenres?"), "{line}");
        assert!(!line.contains("ItemCounts"), "{line}");
        assert_has(
            &line,
            &[
                "parentId=00000000-0000-4000-8000-000000000010",
                "enableTotalRecordCount=false",
                "startIndex=20",
                "limit=40",
            ],
        );
    }

    #[tokio::test]
    async fn audio_playlists_are_listed_recursively_by_last_addition_then_name() {
        let (base, rx) = capturing_server("200 OK", EMPTY_PAGE).await;
        client(&base)
            .get_playlists(0, 0, Vec::new())
            .await
            .expect("playlists");
        let line = request_line(rx).await;
        assert!(line.starts_with("GET /Items?"), "{line}");
        assert_has(
            &line,
            &[
                "includeItemTypes=Playlist",
                "mediaTypes=Audio",
                "recursive=true",
                "sortBy=DateLastContentAdded%2CSortName",
                "sortOrder=Descending%2CAscending",
                "limit=0",
            ],
        );
    }

    #[tokio::test]
    async fn an_id_only_playlist_page_drops_images_and_user_data() {
        let (base, rx) = capturing_server("200 OK", EMPTY_PAGE).await;
        client(&base)
            .get_playlist_items("pl-1", 0, 500, &[], PlaylistItemsShape::IdsOnly)
            .await
            .expect("items");
        let line = request_line(rx).await;
        assert!(line.starts_with("GET /Playlists/pl-1/Items?"), "{line}");
        assert_has(
            &line,
            &["enableImages=false", "enableUserData=false", "limit=500"],
        );
    }

    #[tokio::test]
    async fn create_playlist_posts_an_audio_playlist_and_returns_its_id() {
        let (base, rx) = capturing_server("200 OK", r#"{"Id":"pl-new"}"#).await;
        let id = client(&base)
            .create_playlist("Late Night; Drive", &["song-1".to_string()])
            .await
            .expect("create");
        assert_eq!(id, "pl-new");
        let raw = rx.await.expect("request");
        assert!(raw.starts_with("POST /Playlists HTTP/1.1"), "{raw}");
        let body = raw.split("\r\n\r\n").nth(1).unwrap_or_default();
        let body: serde_json::Value = serde_json::from_str(body).expect("json body");
        assert_eq!(body["Name"], "Late Night; Drive");
        assert_eq!(body["Ids"], serde_json::json!(["song-1"]));
        assert_eq!(body["MediaType"], "Audio");
        assert_eq!(body["UserId"], "00000000-0000-4000-8000-000000000001");
    }

    #[tokio::test]
    async fn playlist_edits_use_their_routes_and_verbs() {
        let (base, rx) = capturing_server("204 No Content", "").await;
        client(&base)
            .add_to_playlist("pl-1", &["a".to_string(), "b".to_string()])
            .await
            .expect("add");
        let line = request_line(rx).await;
        assert!(
            line.starts_with("POST /Playlists/pl-1/Items?ids=a%2Cb"),
            "{line}"
        );

        let (base, rx) = capturing_server("204 No Content", "").await;
        client(&base)
            .move_playlist_item("pl-1", "entry-7", 3)
            .await
            .expect("move");
        assert_eq!(
            request_line(rx).await,
            "POST /Playlists/pl-1/Items/entry-7/Move/3 HTTP/1.1"
        );

        let (base, rx) = capturing_server("204 No Content", "").await;
        client(&base)
            .remove_from_playlist("pl-1", &["entry-7".to_string(), "entry-8".to_string()])
            .await
            .expect("remove");
        assert_eq!(
            request_line(rx).await,
            "DELETE /Playlists/pl-1/Items?entryIds=entry-7%2Centry-8 HTTP/1.1"
        );
    }

    /// Kestrel refuses a request line past ~8 KB: a big add or remove goes out in batches of
    /// 100, in order, each under it.
    #[tokio::test]
    async fn playlist_edits_go_out_in_ordered_batches_of_one_hundred() {
        let ids: Vec<String> = (0..250).map(|n| format!("id-{n:03}")).collect();
        let batch = |range: std::ops::Range<usize>| ids[range].join("%2C");
        for (verb, key) in [("POST", "ids"), ("DELETE", "entryIds")] {
            let (base, lines) = crate::test_server::recording_server("204 No Content").await;
            let client = client(&base);
            if verb == "POST" {
                client.add_to_playlist("pl-1", &ids).await.expect("add");
            } else {
                client
                    .remove_from_playlist("pl-1", &ids)
                    .await
                    .expect("remove");
            }
            let lines = lines.lock().expect("lines").clone();
            assert_eq!(lines.len(), 3, "{verb}: {lines:?}");
            for (line, range) in lines.iter().zip([0..100, 100..200, 200..250]) {
                let prefix = format!("{verb} /Playlists/pl-1/Items?");
                assert!(line.starts_with(&prefix), "{line}");
                assert!(line.contains(&format!("{key}={}", batch(range))), "{line}");
            }
        }
    }

    /// §1.3: every seed has its own route; a genre name is one path segment, verbatim.
    #[tokio::test]
    async fn each_mix_seed_reaches_its_own_route() {
        let rows = [
            (MixSeed::Song("s1".into()), "GET /Songs/s1/InstantMix?"),
            (MixSeed::Album("a1".into()), "GET /Albums/a1/InstantMix?"),
            (MixSeed::Artist("r1".into()), "GET /Artists/r1/InstantMix?"),
            (
                MixSeed::Playlist("p1".into()),
                "GET /Playlists/p1/InstantMix?",
            ),
            (
                MixSeed::Genre("Synthwave;Ambient Dream".into()),
                "GET /MusicGenres/Synthwave%3BAmbient%20Dream/InstantMix?",
            ),
        ];
        for (seed, expected) in rows {
            let (base, rx) = capturing_server("200 OK", EMPTY_PAGE).await;
            let items = client(&base)
                .get_instant_mix(&seed, 200, &[])
                .await
                .expect("mix");
            assert!(items.is_empty(), "{seed:?}");
            let line = request_line(rx).await;
            assert!(line.starts_with(expected), "{seed:?}: {line}");
            assert!(line.contains("limit=200"), "{seed:?}: {line}");
        }
    }

    #[tokio::test]
    async fn lyrics_decode_their_lines_and_a_404_is_none() {
        let served = r#"{"Metadata":{},"Lyrics":[
            {"Text":"First synthetic line","Start":10000000,"Cues":[]},
            {"Text":"Unsynced line"}]}"#;
        let (base, rx) = capturing_server("200 OK", served).await;
        let lines = client(&base)
            .get_lyrics("song-1")
            .await
            .expect("lyrics")
            .expect("served");
        assert_eq!(request_line(rx).await, "GET /Audio/song-1/Lyrics HTTP/1.1");
        let expected = [
            ("First synthetic line", Some(10_000_000)),
            ("Unsynced line", None),
        ]
        .map(|(text, start_ticks)| LyricLine {
            text: text.to_string(),
            start_ticks,
        });
        assert_eq!(lines, expected);

        let (base, _rx) = capturing_server("404 Not Found", "").await;
        assert_eq!(client(&base).get_lyrics("song-2").await.expect("404"), None);
    }
}
