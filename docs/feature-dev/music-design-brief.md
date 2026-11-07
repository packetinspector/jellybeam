# Jellybeam TV — Music (design brief, #34)

Two parts. **Part 1** is the system and the inventory: what the server
really returns, what the platform gives us, what already exists in the app,
and the rules nothing may break. **Part 2** is the visual brief: the screens,
the states each must cover, the tokens, the don'ts, and where the designer is
free.

The direction from the product owner: music, artists, albums and songs get
**their own UX**, not reskinned video screens. Wherever this brief states no
constraint, the designer is free to invent.

Everything in §1 was checked against a Jellyfin 12 dev server on 2026-11-05,
using a synthetic library generated for this brief (sine-tone tracks with
fake tags; every name below is made up). Fields marked **not served** were
requested and did not come back.

---

# Part 1 — System and inventory

## 1. Jellyfin data (verified)

### 1.1 The synthetic test library

| Album (album artist) | Shape it tests |
|---|---|
| *Tidal Lights* (Neon Harbor), 2019, FLAC, 4 tracks | Plain single-disc album with embedded square art. Track 1 has synced lyrics. |
| *Paper Satellites* (Neon Harbor), 2022, MP3, 2 discs × 3 tracks | Multi-disc album. Its genre tag reads `Synthwave;Ambient`. |
| *Summer Static* (Various Artists), 2021, AAC/M4A, 4 tracks | A compilation where each track has its own artist, one of them very long ("Mara Okonkwo-Lindqvist and the Extraordinarily Long Band Name Orchestra"). |
| *Unlabelled Demos* (The Quiet Engines), 2024, Opus, 3 tracks | No art anywhere. Very long track titles. |

There is one playlist, *Synthetic Drive Mix*, holding three tracks from two
albums.

### 1.2 Item types and what each carries

All five types exist and come back from `/Items` with `IncludeItemTypes`.

**`MusicArtist`**
- `Name`, `SortName`, `Genres`, `AlbumCount`, `SongCount`, `ChildCount`,
  `RunTimeTicks` (the total), `UserData` (`IsFavorite`, `PlayCount`).
- Art: `Primary`, `Backdrop`, `Logo`, `Banner` and `Thumb` are possible.
  They come only from internet metadata providers.
  - Our four local artists came back with **no art at all**: empty
    `ImageTags`, no backdrops.
  - The one artist the providers recognised ("Various Artists") got
    Primary 700×700 (1:1), Logo 800×310 (~2.6:1) and a Backdrop 1920×1080
    (16:9).
- **Design for artists without art as the common case, not the exception.**
- `Overview` (a bio) is provider-only and was null for every local artist.
  No year.

**`MusicAlbum`**
- `Name`, `ProductionYear`, `PremiereDate`, `Genres`, `AlbumArtist`,
  `AlbumArtists[]` (name + id), `Artists[]`, `ChildCount` (the track count),
  `RunTimeTicks` (the total), `DateCreated` (added), `UserData`.
- Art: `Primary` only (the embedded cover, or `folder.jpg`).
  `PrimaryImageAspectRatio` was 1.0 for our square covers. Real libraries
  send what the file has, which is mostly square but not always.
- `BlurHash` is provided per image, the same as for video.
- No `Overview` locally. No backdrop.
- The compilation's `AlbumArtist` is the literal string "Various Artists".

**`Audio`** (a song)
- `Name`, `IndexNumber` (track), `ParentIndexNumber` (disc), `RunTimeTicks`,
  `Album`, `AlbumId`, `AlbumArtist`, `AlbumArtists[]`, `Artists[]`,
  `ArtistItems[]`, `Genres`, `ProductionYear`, `HasLyrics`, `Container`,
  `UserData`.
- Art: the track's own `Primary` when its file embeds one, plus
  `AlbumPrimaryImageTag` to fall back to the album cover.
- Audio stream facts, usable for a mono spec strip:
  - `Codec`, `BitRate`, `SampleRate` and `Channels` are always present
    (e.g. `FLAC │ 44.1 KHZ │ 16-BIT`).
  - `BitDepth` comes back for FLAC only (16). It is null for MP3, AAC and
    Opus.
  - `Container` is raw: for M4A it reads `mov,mp4,m4a,3gp,3g2,mj2`, so the
    app must format it.
- **Artists vs album artists:**
  - On a compilation track, `AlbumArtist` = "Various Artists" and
    `Artists` = the per-track performer.
  - Per-track performers are real `MusicArtist` items. They are listed by
    `/Artists`, not by `/Artists/AlbumArtists`.

**`Playlist`**
- `Name`, `MediaType: "Audio"`, `ChildCount`, `RunTimeTicks` (the total).
- `Primary` is a server-generated collage, 1:1.
- Each entry carries a `PlaylistItemId` (the entry id, used for move and
  remove).
- Creating the first playlist makes a **"Playlists" view**
  (`CollectionType: playlists`) appear in the user's libraries.

**`MusicGenre`**
- `Name`, `SongCount`, `AlbumCount`.
- Some genres get a generated `Primary`, others have none.
- Genre strings are verbatim from tags. `Synthwave;Ambient` stayed **one
  genre**, because the server did not split it. A genre the user never
  tagged ("Rock") also appeared, pulled in by provider metadata.

**`UserData`** (all types)
- `IsFavorite`, `PlayCount`, `Played`, `LastPlayedDate`,
  `PlaybackPositionTicks`.
- Verified: favouriting a track and an album works. A reported play bumped
  `PlayCount` to 1 and set `LastPlayedDate`.
- Audio never enters Resume: `/UserItems/Resume?MediaTypes=Audio` returned
  0 after a partial play. **There is no "continue listening" from the
  server**; "recently played" is a sort, not a row the server keeps.

**Ordering**
- `SortName` on tracks is `0001 - 0001 - {title}` (disc - track - title).
- Albums sort by name. Artists sort by a normalised `SortName` with "the"
  dropped.

### 1.3 Endpoints

| Need | Endpoint | Verified result |
|---|---|---|
| Artists who appear on any track | `GET /Artists?parentId={musicLib}` | 4 (includes per-track compilation artists) |
| Album artists only | `GET /Artists/AlbumArtists?parentId={musicLib}` | 3 (includes "Various Artists") |
| An artist's albums | `GET /Items?IncludeItemTypes=MusicAlbum&AlbumArtistIds={id}` | 2 for Neon Harbor |
| Albums an artist *appears on* | `…&ContributingArtistIds={id}` or `ArtistIds={id}` | the compilation, for a per-track artist |
| An artist's songs | `GET /Items?IncludeItemTypes=Audio&ArtistIds={id}` | 2 songs on the compilation |
| Album tracks | `GET /Items?ParentId={albumId}&SortBy=ParentIndexNumber,IndexNumber` | 1.1, 1.2, 1.3, 2.1, 2.2, 2.3 |
| Genres | `GET /MusicGenres?parentId={musicLib}` | 5 |
| Latest | `GET /Items/Latest?parentId={musicLib}` | **grouped into albums** by default; `GroupItems=false` returns tracks |
| Recently played | `GET /Items?IncludeItemTypes=Audio&Filters=IsPlayed&SortBy=DatePlayed&SortOrder=Descending` | works |
| Most played | `SortBy=PlayCount` | works (same query shape) |
| Favourites | `Filters=IsFavorite` + `POST/DELETE /UserFavoriteItems/{id}` | works for tracks and albums |
| Playlists | `GET /Items?IncludeItemTypes=Playlist`, `GET /Playlists/{id}/Items` | works |
| Playlist edit | `POST /Playlists` (create), `POST /Playlists/{id}/Items` (add), `POST /Playlists/{id}/Items/{entryId}/Move/{index}`, `DELETE /Playlists/{id}/Items?entryIds=` | create, add and move verified (204) |
| Instant Mix | `GET /Items/{id}/InstantMix`, `/Albums/{id}/…`, `/Artists/{id}/…`, `/Songs/{id}/…`, `/Playlists/{id}/…`, `/MusicGenres/{name}/…` | all return a shuffled `Audio` list. A song or album seed stays near the seed; an artist seed pulled 17 tracks across genres |
| Similar | `GET /Artists/{id}/Similar`, `GET /Albums/{id}/Similar` | artists: 4; albums: 0 on this library (don't rely on it) |
| Lyrics | `GET /Audio/{id}/Lyrics` | **served.** `{"Lyrics":[{"Text":"First synthetic line","Start":10000000,"Cues":[]},…]}`, with `Start` in ticks (synced, line-level). 404 when the track has none. Gate on `HasLyrics` |
| Search | `GET /Search/Hints?searchTerm=` | mixed types with `Album` and `AlbumArtist` on hits. An artist also comes back as a duplicate `Person` hit. Title match only: "neon" finds the artist, not its albums |
| Stream | `GET /Audio/{id}/universal` | a file the device can play comes back as the file (`audio/mpeg`); one it can't comes back as HLS (`application/vnd.apple.mpegurl`), which is a transcode |
| Now-playing report | `POST /Sessions/Playing` with `NowPlayingQueue` | accepted (204). The server can show our queue to other clients |

**Not served on this server:**
- `NormalizationGain` (ReplayGain or LUFS). It stayed null even after
  running the server's "Audio Normalization" task on untagged files; it
  comes from ReplayGain tags or a LUFS scan.
- Album `Overview`.
- Artist art and bio for local-only artists.
- Word-level lyric `Cues`.

The supported server floor is 10.11; these routes predate it.

## 2. Platform capabilities

The app runs on Media3 1.11.1 with the Jellyfin FFmpeg decoder extension,
minSdk 23 and targetSdk 36.

- **Gapless**
  - What it needs:
    - A queue built as one ExoPlayer playlist of `MediaItem`s, whose
      transitions are seamless, even across mixed formats
      ([playlists](https://developer.android.com/media/media3/exoplayer/playlists)).
    - Encoder delay and padding trimmed by `TrimmingAudioProcessor`. The
      values come from iTunSMPB (M4A) and LAME/Xing (MP3)
      ([GaplessInfoHolder](https://github.com/androidx/media/blob/release/libraries/extractor/src/main/java/androidx/media3/extractor/GaplessInfoHolder.java),
      [release notes](https://github.com/androidx/media/blob/release/RELEASENOTES.md)).
  - FLAC and Ogg are sample-exact by design (unverified in the docs).
  - Direct Play keeps gapless. A per-track HLS transcode probably does not.
- **Background playback and system surfaces**
  - What it needs:
    - A `MediaSessionService`, declared as a foreground service of type
      `mediaPlayback` (required when targeting 34+). That gives the system
      media controls, peripheral media buttons and Assistant
      ([background playback](https://developer.android.com/media/media3/session/background-playback),
      [FGS types](https://developer.android.com/about/versions/14/changes/fgs-types-required)).
  - The Android TV **Now Playing card**:
    - It appears on the launcher while the session is active and holds
      audio focus. It shows art, title and the app icon, and Select
      returns to the app.
    - Guidance: deactivate at once on stop, and after a delay on pause
      ([now playing](https://developer.android.com/training/tv/playback/now-playing)).
  - From API 33 the system media UI is filled from session metadata, so it
    is not drawn by us.
- **Queue, shuffle, repeat**
  - The player has native support: insert, move and remove items while
    playing (moving the current item doesn't interrupt it), repeat
    off/one/all, and a stable shuffle order that turns off without
    jumping (same playlists page).
  - Session buttons are customisable through `setMediaButtonPreferences`.
- **Formats**
  - The device profile already lets these Direct Play through the FFmpeg
    extension: AAC, MP3, MP2, FLAC, ALAC, Opus, Vorbis, PCM/WAV, AC-3,
    E-AC-3, DTS and TrueHD.
  - Needs a server transcode: DSD, AIFF (not in Media3's container list),
    and anything outside the profile
    ([supported formats](https://developer.android.com/media/media3/exoplayer/supported-formats)).
  - Hi-res (above 48 kHz) plays, but Android's mixer usually resamples to
    48 kHz. Don't promise bit-perfect output in the UI. That is secondary
    sources only, unverified on our devices.
- **ReplayGain**
  - ExoPlayer doesn't apply it, and per-item gain is the app's job
    ([androidx/media#418](https://github.com/androidx/media/issues/418)).
  - Jellyfin exposes `NormalizationGain` and `AlbumNormalizationGain` when
    the files carry gain tags or have been LUFS-scanned (`BaseItemDto`).
  - **This is a possible setting, not a design surface.**
- **Screen and screensaver**
  - Audio keeps playing under the system screensaver (Ambient Mode).
  - Audio apps must **not** hold the screen on, unless they ship their own
    screensaver with non-static imagery
    ([ambient mode](https://developer.android.com/training/tv/playback/ambient-mode)).
  - Our video player sets `FLAG_KEEP_SCREEN_ON`; the music screens must
    not.
  - No official burn-in guidance was found. Treat "non-static" as the bar.

## 3. What exists in Jellybeam today

**Today the app has no music UI, no queue and no background playback.**
- A music library already appears in the nav drawer under its server name,
  as a plain 2:3 grid of albums.
- The local mirror already syncs music items.
- Playback lives only inside the video player's Activity. Its
  `MediaSession` is created and released with that Activity, and there is
  no service.

| Surface (spec) | Behaviour today | What music needs from it |
|---|---|---|
| Mini player (docs/17) | **Off by default.** Back or Home during video shrinks it into system PiP, bottom right. Dismissing stops playback. | PiP is a video window. Music needs its own persistent "now playing" affordance while browsing. It must coexist with video PiP, and a video start must end or hand over the music. See frame 05. |
| OSD (docs/12) | Video overlay: a title, a time row, a scrubber with trickplay, transport buttons, a Direct Play line and track pickers. Show and hide hard-cut. See frame 07. | Nothing in it is reused as-is. The transport vocabulary (±10 s, play/pause glyphs, mono time readouts, Direct Play line) is the reference for music controls. |
| Focus (docs/15) | One language: an accent ring, a 1.04 art scale and a brightness lift. Selected = a filled control or a `✓` in a 20dp gutter. Current = a 6dp accent dot (see the drawer, frame 08). | A song row needs a list-row focus state. "Now playing" uses the existing **Current** dot vocabulary, not a new marker. |
| Library grid + sort/filter (docs/16) | 2:3 grid, about 7 across, a sticky sort strip and an A-Z rail. It exists only for movies, TV and favourites. Favourites excludes music today. See frame 02. | A square-art grid. Tabs or a switcher for artists, albums, songs, playlists and genres. A-Z for large libraries. |
| Detail (docs/11, docs/23) | Poster left, then title, meta, actions and overview, a mono spec capsule, and a cast band pinned to the bottom. Audio and albums currently fall into the movie layout. See frame 03. | Album and artist pages are new screens. The spec capsule pattern can carry audio facts. |
| Action menu (docs/19) | A `···` pill right of Play opens a right-side panel. It has three fixed groups (This title / Playback / Library), absent rather than disabled, and never scrolls. See frame 04. | Music rows: Play next, Add to queue, Instant Mix, Add to playlist, Go to artist, Go to album, Favourite. "Never scrolls" caps the count. |
| Home (docs/07, docs/25) | Hero, Continue Watching, Next Up, Favourites, then "Latest in {library}" per library in server order. The later shelves mount progressively. See frame 01. | A music shelf (square art). The server's Latest already groups albums. Whether music sits on main Home at all is open (§6). |
| Search | One query field over the mirror's title index. It returns a flat poster grid, optionally with Discover results. See frame 06. | Grouped results (artists / albums / songs / playlists) in square art. |
| Nav drawer | Home, Favourites, each library verbatim, Discover, Search, Watch together, Settings, servers. It opens on Left; the closed edge is the 19dp spine. See frame 08. | Decide whether a Now Playing or queue entry exists, and where. |
| Watch Together (docs/28) | SyncPlay for video. Each viewer keeps their own tracks; next-up and autoplay are off in a group. | Out of scope unless decided otherwise (§6). |
| Settings (docs/09) | One Rust-owned settings record (playback quality, skip lengths, mini player, accent). | Any music setting (gapless, normalisation) needs a default decided. |

## 4. Hard product rules

- **Names verbatim.**
  - Artist, album, track, genre and playlist names are shown exactly as the
    server sends them. That includes `Synthwave;Ambient`, "Various Artists"
    and a 70-character band name.
  - No prettifying, no splitting, no "feat." parsing.
- **Direct Play by default.** Transcoding is opt-in only. The UI must never
  imply a transcode happened silently. The existing Direct Play line is the
  model.
- **Zero idle cost.**
  - A user who never opens music pays nothing: no startup requests, no
    timers, no always-on collectors, no heap, and no extra work on the Home
    or Detail first frame.
  - A music mini player, service or shelf exists only while music is
    playing or shown.
  - Animated now-playing visuals stop when off screen.
- **No decorative glow.**
  - No gradients, washes, glows, blur, translucency or tinted shadows on UI
    chrome.
  - Dividers are one device pixel.
  - Persistent chrome never reacts to focus: a state change is geometry and
    glyph brightness only.
  - Album art may fill a surface. Nothing is *derived* from it (no
    colour-picked tints or art-coloured gradients).
- **Brand** (docs/brand.md)
  - **Tokens:**
    - Colours: NOTTE `#14100D`, SURFACE `#1D1814`, SurfacePanel `#261F19`,
      HAIRLINE `#322A22`.
    - Text is PANNA `#F7E9CE` at four alphas only (FF/B3/73/40).
      PANNA-2 is `#C9C0B2`, and GRIGIO `#8C8478` is for mono/technical
      text.
  - **Accent:**
    - One accent at a time, chosen by the user from twelve. The screenshots
      in this pack use a violet scheme; the default is PISTACCHIO
      `#A8CB6B`.
    - Design against the accent **role**, not a hex: `accent` fill,
      `onAccent` text on it, `accentInk` text and thin bars on dark.
    - Accent is only for focus, progress, primary action and state.
  - **Type:**
    - Archivo for UI. Martian Mono (uppercase, `│`-joined) for technical
      data. Bagel Fat One only for the word "Jellybeam". Minimum 11sp.
  - **Mascot:**
    - Only on empty states (Curious = empty library, Searching = no
      results), never over art.
    - Sleepy is reserved for a screensaver.
    - Empty-state copy is one factual line, with no jokes and no
      exclamation marks.
- **10-ft, D-pad only.**
  - 1920×1080 canvas, 32dp page margin, overscan-safe.
  - No pointer, no hover, no touch targets.
  - Every element is reachable by Up/Down/Left/Right; Select acts, Back
    leaves.
  - Long-press Select is the Android TV "options for this thing"
    convention. The app doesn't use it yet; today options open from the
    `···` pill on detail pages.
  - Remote media keys (play/pause, next, previous) must work from anywhere
    while music plays.
- **Perf-sensitive paths.**
  - Cold start to Home, and Home's first data, are measured on every
    change.
  - A music shelf is one more shelf that mounts progressively; nothing
    music-related may block Home's first frame.
  - Large libraries (tens of thousands of tracks) must page, never load
    whole.
- **Fail open.** Missing metadata shows the item without it; never hide a
  track because it has no number, year or art.

## 5. Full-music scope to design

1. **Music library landing**: the entry from the drawer. It switches
   between artists, albums, songs, playlists and genres. Sort and A-Z for
   big lists.
2. **Artist page**:
   - With and without art or bio.
   - Albums (as album artist) and "appears on" (compilations) shown
     separately.
   - Top or most-played songs, if desired.
   - Play all, Shuffle, Instant Mix.
3. **Album page with track list**:
   - Disc headers on multi-disc albums.
   - On compilations, the per-track artist is shown on every row.
   - The currently playing track is marked.
   - Play, Shuffle, Instant Mix, Favourite, and Go to artist.
4. **Song lists**: all songs, favourites, recently played, artist songs.
   - Columns are the designer's call; the data has track/disc, duration,
     artist, album, year, play count, favourite and lyric availability.
5. **Playlists**:
   - A list of playlists, and a playlist page.
   - Add to playlist and new playlist from the action menu.
   - Reorder and remove are available server-side; whether they ship in
     v1 is a design question.
6. **Now Playing, full screen**:
   - Art, title, artist, album, a time row and transport.
   - Shuffle and repeat state.
   - Queue access, and lyrics (if in scope).
   - The audio spec readout (Direct Play line).
7. **The music mini player while browsing**: what it shows, where it sits,
   how focus reaches it, and how Back and media keys behave.
8. **Queue view**:
   - Up next, play next vs add to queue, shuffle and repeat, and clear.
   - Reorder and remove, if in v1.
9. **Instant Mix**: where it is started (artist, album, song, genre,
   playlist) and what the user sees while it loads.
10. **Home shelf for music**: e.g. Latest albums, and/or recently played.
11. **Search results for music**: grouped by type.
12. **Action-menu entries**: Play next, Add to queue, Add to playlist,
    Instant Mix, Favourite / Unfavourite, Go to artist, Go to album.

## 6. Open questions

For the user and the designer to decide before final frames.

1. **Background playback while browsing.** Recommendation: **yes**. Music
   keeps playing across every screen. It stops only on an explicit stop, on
   video starting, or on leaving the account.
2. **Ambient / screensaver mode for Now Playing.**
   - Either Jellybeam ships its own non-static now-playing screensaver with
     burn-in safety (slow drift, no static chrome; the Sleepy mascot is
     reserved for screensavers), or we leave the system screensaver to run
     over audio.
   - The platform rule allows either, but not a static screen held awake.
3. **Lyrics.**
   - The server serves synced, line-level lyrics when the library has them.
   - Questions: in v1 or later? A full-screen mode, a pane on Now Playing,
     or both?
4. **Watch Together for music.** Recommendation: out of scope for v1. It
   would need SyncPlay queue semantics this app doesn't use yet.
5. **Music on the main Home, or only inside the music library.**
   - Today every library gets a "Latest in {library}" shelf, so music would
     appear on Home by default with 2:3 cards.
   - Options:
     - a square-art music shelf on Home;
     - music on Home only via Hide (Settings › Home);
     - a separate music home.
6. **Video and music collision.** Starting a video while music plays:
   recommendation is that video stops the music, and the music mini player
   goes away. Confirm.
7. **Normalisation (ReplayGain).** Possible only where the server has gain
   values. A setting with a default of off, or not in v1?

---

# Part 2 — Visual brief

## Screens

Design every screen at 1920×1080. "Free" marks where the designer is
expected to invent.

**A. Music library landing**
- The way to move between artists, albums, songs, playlists and genres:
  tabs, a rail or a hub. **Free.**
- One frame per view.
- Square-art grid for albums.
- The artist tile must work with **no image** (the common case) and with a
  provider image.

**B. Artist page**
- Layout **free**.
- Must hold the following:
  - the name (up to 2 lines at 70+ characters);
  - optional art, backdrop and logo;
  - album and song counts;
  - albums vs "appears on";
  - Play / Shuffle / Instant Mix;
  - favourite state.

**C. Album page**
- Cover, title, album artist (focusable → artist page), year, track count
  and total time.
- A genre string, verbatim.
- An audio spec line in mono (`FLAC │ 44.1 KHZ │ 16-BIT`) where it fits.
- A track list with disc grouping.
- The playing track uses the **Current** dot.
- The track-row focus treatment is yours to define within docs/15.

**D. Song list**
- One generic list used for all songs, favourites, recently played and
  artist songs.
- Row content and density are **free**.
- Must read at 10 ft and scroll by D-pad without a pointer.

**E. Playlist page + Add to playlist panel**
- The page uses the server collage as its art.
- The add panel follows the docs/19 panel language and includes "New
  playlist".

**F. Now Playing (full screen)**
- **Free composition.** This is the signature music screen.
- Must carry:
  - art;
  - title, artist and album;
  - elapsed and remaining time;
  - transport (prev / play-pause / next; seek is optional);
  - shuffle and repeat state;
  - a queue entry point;
  - lyrics (if in scope);
  - the Direct Play / transcode line.
- No art-derived colour.

**G. Music mini player while browsing**
- Placement, size and contents are **free**.
- It must not cover the drawer spine or a focused card's ring.
- Define how focus reaches it (or doesn't, if media keys and a long-press
  suffice).
- Show it over Home, over an album page and over the drawer open.

**H. Queue**
- Up next with the current track marked.
- Shuffle and repeat states.
- "Play next" vs "Add to queue" results.
- Clear queue.
- Reorder/remove affordances only if they fit D-pad cleanly.

**I. Instant Mix entry + loading**
- From an album, an artist and a song.
- Loading uses the existing pulsing-dots motif, never a spinner ring.

**J. Home with a music shelf**
- One frame with music on Home, in whatever form is proposed for §6 Q5.

**K. Search results with music**
- Grouped by type.
- The mixed video + music case.
- No results uses the Searching mascot.

**L. Action menu, music variants**
- For a track, an album, an artist and a playlist.
- Fixed groups, absent-not-disabled, no scrolling (docs/19).

**M. (If Q2 = yes) Now Playing ambient mode**
- A burn-in-safe idle state.

## States every screen must cover

- **Loading** (skeletons that reserve final height), **empty** (mascot + one
  factual line), **server unreachable** (the existing error pattern, with
  retry).
- **Missing art**:
  - an album with no cover;
  - an artist with no image;
  - a track whose only art is the album's;
  - a playlist collage.
- **Multi-disc** album (disc headers) and **compilation** (per-track artist
  on every row; album artist "Various Artists").
- **Very long names**: a 70-character artist, a 75-character track title,
  a long album title.
  - State the clamp (lines) and the truncation for each.
  - Never wrap a mono spec value; wrap the line instead.
- **A track playing while browsing**: the mini player visible, plus the
  Current dot wherever that track appears.
- **Focused vs unfocused** for every focusable element, including
  track rows, tabs, transport buttons, the mini player and shuffle/repeat.
  Show both.
- **Paused** vs playing, and shuffle/repeat **on** vs **off** (as
  selected-state, docs/15).
- **Favourite** vs not.
- **Transcoding** reading (Ambra `#E0B45C` mono text, the existing caution
  colour that the video player uses for its TRANSCODE line).

## Tokens and type (must use)

- Colours, alphas, fonts and the type scale are as §4.
- Type scale: hero 34 bold, page title 28 bold, shelf header 20 semibold,
  card title 14 medium, metadata 13, caption 11 semibold, spec strip mono
  10.
- Spacing: 4/8/12/16/20/24/32/40, page margin 32, cell gap 16, shelf gap 20.
- Radii: art 2dp, card 8dp, pill full.
- Focus:
  - a 3dp accent ring at a 3dp gap, 1.04 scale on art inside a fixed slot,
    and a 12% brightness lift;
  - 180ms in and 240ms out, bezier(0.2,0,0,1);
  - never border-only, never shadow or glow.
- Panels: SurfacePanel fill, a 1px HAIRLINE border, 8dp radius, no scrim,
  no blur.
- Square art cells are new. Pick the size(s) and say how many across; the
  2:3 poster is 160dp wide and the library grid clamps cells to 120–220dp.

## Don'ts

- Don't reskin the movie detail page or the video OSD as an album or now
  playing screen.
- No gradients, glows, washes, blur, translucency, or colour sampled from
  album art.
- No new accent colours. No status colours beyond what exists (Ambra is
  the one caution colour).
- No renamed, prettified or split names. No "feat." parsing.
- No spinner rings. No mascot over art.
- No hover states, pointer affordances or touch-sized hit targets.
- No focus-reactive persistent chrome.
- No UI that runs (animation, polling) when music isn't playing or shown.
- No icons that only make sense with a pointer (drag handles) unless the
  D-pad interaction is specified.

## Where you're free

- The library landing structure, the artist and album page layouts, the
  Now Playing composition, mini-player placement and form, queue
  presentation, track-row design, Home-shelf form, and any motion within
  the timing rules above.
- Inventing a music-specific visual signature is wanted, as long as it is
  built from the tokens.

## Deliverable

- Frames A–L (and M if chosen) at 1920×1080, each in the states above that
  apply.
- Component variants: the track row (default / focused / playing /
  favourite), the mini player (playing / paused / focused) and transport
  buttons (all states).
- Focus order annotated on the album page, Now Playing and the queue.

Reference screenshots of today's analogous screens (Home, a library grid, a
movie detail page, the action menu, the mini player over Home and over
detail, search, the OSD, the nav drawer), plus a contact sheet, accompany
this brief.
