# 29 — Music

Spec of record for music libraries (`CollectionType: music`): browsing,
background playback with a queue, Now Playing and its ambient screen. The
background research is `feature-dev/music-design-brief.md`; code cites this
document. Screens keep their design ids (A1, E2, S3 …): §9 holds A–L, §6 is
M and §10 the states S1–S5.

## 1. Data sources and idle cost

A user who never opens music pays nothing for it: no request, no timer, no
service, no collector and no first-frame work.

- **Mirror:** albums, artists, songs and genres come from the library mirror
  (`core/media-cache`), so the Albums, Artists, Songs and Genres tabs, the
  album and artist pages, and the Home "Latest in" shelf browse with no
  server round trip. The `artists` table fills lazily on the first music open.
- **Artist list:** the Artists tab's sorted list, its total and its A–Z
  groups are built once per (library, scope) in one query and kept in memory
  until the mirror next changes; a page or a scroll past the first page only
  slices it. One build is tens of ms on a few thousand artists, a page is a
  few ms, and nothing is built until the tab opens.
- **Live, on screen only:** playlists, playlist rows, lyrics, the spec line's
  stream facts and Instant Mix are read when their screen or panel asks, and
  never cached past it.
- **Owner and service:** `AppGraph.music` builds `MusicPlaybackOwner` on the
  first music request only. Readers (bar, drawer entry, Current dot, menus)
  see it through `AppGraph.musicStarted`, Compose snapshot state that is `null`
  until the owner exists, so no collector runs until music starts;
  `musicOwnerIfStarted()` gives the same check outside composition (video
  start, update gate). `MusicService` exists only while music plays or is
  paused.
- **Normalisation gate:** Settings reads `musicHasNormalizationGain()` from the
  mirror when the Playback section opens; it never makes a request. The
  Normalize volume switch shows once any fetched audio item carried a gain,
  stays while it is on so it can be turned off, and is off by default; the
  music player applies the server's per-track gain.

## 2. Playback owner and service

- **One owner.** `MusicPlaybackOwner` holds one `MusicFlow` and is its only
  writer (`send` → `reduce` on Main); every screen enters through
  `AppGraph.music.send`. The approved state table is the owner's KDoc and
  `MusicFlowReduceTest`.
- **One player.** `MusicService` is a `MediaSessionService` (foreground type
  `mediaPlayback`) with its own ExoPlayer; the queue is one playlist of
  `MediaItem`s, so tracks play gapless. Audio is Direct Play through
  `/Audio/{id}/universal`; a transcode happens only when the user opted in
  (docs/18).
- **Media keys** go to the music session while music plays; the session's
  commands reach the owner as events (`MusicRoutedPlayer`), never the player
  directly.
- **Reporting:** each track start and end reaches the core through one serial
  queue in reducer order; the session reports `NowPlayingQueue`. Audio never
  writes a resume position, but play count, played and last played are recorded.

## 3. Rules (signed-off answers)

| # | Rule |
|---|---|
| Q1 | Starting music while the mini player shows a video stops that video first (docs/17 §1). Starting a video stops the music and removes the bar before the player opens (`AppGraph.beforePlayback` → `stopForVideo`). |
| Q2 | When the queue ends with repeat off, the session ends and the bar goes. |
| Q3 | Home or leaving the app keeps music playing (service plus the system Now Playing card); Stop ends it. |
| Q4 | Library sync runs during music; an update install waits until music stops. |
| Q5 | Play next or Add to queue with nothing playing starts that item. |
| Q6 | A track that fails for a non-network reason is skipped; a failure on the last track ends the queue as Q2. |
| Q7 | No resume position for audio; play count, played and last played are still recorded. |
| Q8 | Switching account while music starts cancels that start; leaving the account that plays stops it (the owner follows `gateway.accountEpochs`, current and parked). |

## 4. Queue semantics

- The queue is the source (album, artist, playlist, list, genre or mix) plus a
  **user group**: Play next and Add to queue land in the run of user entries
  right after the playing song, Play next at its front (`insertIndex`).
- A played user entry joins the source group once it becomes current, so it
  never rejoins the user group (`originOnceCurrent`).
- Shuffle keeps the playing song and lifts the user group right after it; the
  list shows the order that will actually play (`shuffledOrder`).
- Move (queue) and Reorder (playlist) pick a row up with OK, move it with
  Up/Down, drop with OK, and Back puts it back. The playing song never moves
  in the queue; a playlist drop is one server move, and a refused move puts
  the rows back and names the song (`PlaylistReorder.kt`).
- Clear queue keeps the playing song.
- **Playlist rows are live and re-read only after an edit.** Every add or
  remove goes through the gateway, which names the playlist it changed
  (`playlistEdits`). A playlist page re-reads its rows once on that signal
  while it shows, or once when it shows again if the edit happened while it
  was covered; with no edit it reads nothing. A move is not announced: only
  the playlist's own page moves rows, and it already shows the new order.

## 5. Surfaces

| Surface | Code owner |
|---|---|
| Library landing: Albums, Artists, Songs, Playlists, Genres tabs, sort strip, facts, A–Z rail, New playlist | `ui/music/MusicLibraryScreen.kt`, `MusicLibraryViewModel` |
| Album, artist, playlist pages, playlist reorder | `MusicAlbumScreen.kt`, `MusicArtistScreen.kt`, `MusicPageViewModels.kt`, `PlaylistReorder.kt` |
| Song lists (all, favourites, recently played, genre) | `MusicSongsScreen.kt` |
| Music bar while browsing, bottom inset | `MusicBar.kt` (`musicBarInset`, `LocalMusicBarInset`) |
| Now Playing, lyrics, spec line | `MusicNowPlayingScreen.kt`, `NowPlayingViewModel.kt` |
| Ambient screen (M1/M2) | `AmbientLogic.kt` (`reduceAmbient`, `AmbientController`), `AmbientHost.kt`, `AmbientScreen.kt` |
| Queue | `MusicQueueScreen.kt`, `player/music/MusicQueue.kt` |
| ··· menus, Add to playlist, New playlist | `MusicMenuModel.kt`, `MusicMenuViewModel.kt`, `MusicActionPanel.kt` |
| Home shelf, grouped search | `ui/home/`, `ui/search/` |
| Normalize volume | `ui/settings/SettingsSections.kt`, `MusicService.normalizationVolume` |

### Bar room and a parked session

- **The bar's room is reserved by the layer, not the screen.** Every retained
  layer carries `musicBarInset` (the bar's height while a session exists and
  its screen shows the bar) as `LocalMusicBarInset`, so the room is the same on
  every screen and survives a screen being retained under another; a screen
  never pads for the bar itself.
- **The bar stays inside the title-safe frame.** Its surface runs to the
  screen's bottom edge, but its row, buttons and focus rings end 27 dp (the TV
  title-safe margin, 54 px at 1080p) above it, so the inset is 83 dp (56 dp
  row plus the margin). The Queue, which has no bar, pads its list by the same
  27 dp so a focused row's ring clears the edge.
- **A session on a parked account** (docs/18 §2.1) keeps playing and shows
  everywhere, but Now Playing hides the heart and ··· (every row acts on the
  current account) and reads that account's art, spec line and source name
  live on the session's own epoch. A queue row's menu on it offers Play next,
  Move and Remove, which act on the in-player queue, and omits Go to album,
  which would open on the current account.
- **A favourite flipped** on Now Playing or in a menu updates the owner's copy
  of the song's card, so coming back to the song shows the new heart.

## 6. Ambient screen (M1, M2)

- Starts on any screen after 2 min with no key input while a music session
  exists, playing or paused. It never counts while the app isn't in front (a
  video plays, or the app is in the background), while a dialog, menu or panel
  is open (the ··· menus and New playlist panel, Detail's menu, Discover's
  request and cancel dialogs, the Report logging-off dialog, the crash prompt),
  or while the keyboard shows or the search box has focus; each of those restarts the full 2 min after.
- Any key only closes it and is consumed with its repeats and release; it never
  takes focus or navigates, so you are back on the same screen with the same
  focus. Keys reach it first through `MainActivity.dispatchKeyEvent`.
- Draws the art (200 dp, brightness 0.82), title and artist, drifting about
  0.5 dp a second on a 20-minute loop; a track change cross-fades (240 ms) to a
  new start point. Paused adds the Sleepy mascot beside the art and
  `PAUSED │ m:ss`.
- After 10 min paused while drawing it stops drawing and lets the screen go,
  so the system screensaver takes over.
- The screen is kept on only while ambient draws (`AmbientScreen`'s
  `keepScreenOn`); no other music screen keeps it on.
- One owner, `AmbientController`, composed by the root's `AmbientHost` only
  while a session exists, holds one pending tick at the next deadline; it is
  cancelled while anything blocks ambient, and without a session nothing is
  composed, timed or collected.

## 7. Perf invariants

- Cold start to Home `dataReady`, Home first frame and video TTFF are
  unchanged for every user; no music request blocks Home's first frame.
- Every list pages 100 items at a time; nothing loads a whole library.
- Animations (Current dot, loading dots, progress, ambient drift) run on the
  frame clock, so they stop with the screen or the Activity.
- The bar, the drawer's Now playing entry and the Current dot are not composed
  while nothing plays.

## 8. Look and components

Music screens use the app's tokens only (no new colours), content starts at
x = 80 px on the 1920 canvas, the focus curve is `cubic-bezier(0.2,0,0,1)`,
and the no-art mark is PANNA at alpha 0x40. Ambra appears only on a
transcode's spec line.

- **8.1 Track row.** One line, or two (artist · album, never wrapping). The
  gutter holds the Current dot (pulses while playing, holds still paused,
  turns onAccent when focused), then number ("—" when missing), optional
  thumb, title, optional artist and extra column, `LRC` when the song has
  lyrics, a heart when favourite, and the duration. A focused one-line row
  may grow to show a second title line; two-line rows never grow. OK held
  or Menu opens the row's ···.
- **8.2 Square card.** 128 dp square art, title 2 lines, subline 1 line, 6
  across; no art draws the no-art tile, never a generated image.
- **8.3 Artist circle.** Primary art cropped to a circle; no art draws the
  first letter of the SortName. Subline `n ALBUMS` or `APPEARS ON n`.
- **8.4 Music bar.** Bottom-anchored, exists only while music plays or is
  paused, absent on Now Playing, the Queue, ambient and the video player.
  Progress line at its top; art, title, artist, `m:ss / m:ss`, Prev,
  Play/Pause, Next. Focus: Down from the lowest element lands on Play/Pause;
  Left reaches Prev, then the art block (OK opens Now Playing); Up or Back
  returns. A confirmation (`ADDED TO QUEUE`, `PLAYING NEXT`, `ADDED TO …`,
  `ALREADY IN · ADDED AGAIN`) shows for 3 s; `CONNECTION LOST │ PAUSED`
  stays until playback resumes. Room and safe margin: §5.
- **8.5 Transport.** Play/Pause is always an accent fill; shuffle and repeat
  "on" is a panel fill, never accent, so on and focused can't be confused;
  repeat-one adds a small "1". A loading control shows the loading dots.
- **8.6 Loading dots.** Three pulsing dots in a glyph's footprint, so a
  control never changes size; no spinner rings anywhere.
- **8.7 Side panel.** docs/19's panel: fixed groups, items absent rather than
  disabled, never scrolls.

## 9. Screens

- **A · Library landing (A1–A5).** The drawer entry opens the last-used tab
  (Albums the first time). Tabs Albums · Artists · Songs · Playlists · Genres
  switch on OK, not focus. A sort strip `{n} {TYPE} │ {ORDER}` names the
  tab's fixed order. The focused item's facts sit top right; the A–Z rail
  shows only when the list is longer than a screen. Artists has a Show:
  Album artists | All artists toggle (A2). Playlists starts with New playlist
  and sorts by most recently changed (A4). Genres are verbatim tiles that
  open that genre's song list (A5).
- **B · Artist page (B1–B3).** Name, counts, then Play all · Shuffle ·
  Instant Mix · ♥ · ···; Most played (top 5, hidden when nothing played);
  Albums and Appears on shelves, each absent when empty. A performer with
  no albums shows its songs instead (B2). Provider art draws a circle and a
  backdrop panel that clears the action row (B3). Opens on Play all.
- **C · Album page (C1–C3).** Fixed left column (cover, spec capsule §11,
  verbatim genre chip); right column with `ALBUM │ ADDED {date}`, title, the
  album-artist link, facts and Play · Shuffle · Instant Mix · ♥ · ···. Only
  the track list scrolls; disc headers only on multi-disc albums;
  compilations show each row's artists verbatim; tracks with no number sort
  last. Opens on the playing track's row when it is on this album, else on
  Play.
- **D · Song list (D1, D1b).** One screen for all songs, favourites,
  recently played, artist and genre songs: title, count strip, Play and
  Shuffle, two-line rows with the track's own art, else its album's.
  There is no "continue listening": audio has no resume.
- **E · Playlists (E1–E4).** E1: the album frame with the server's collage,
  rows in playlist order (rows and refresh: §4). E2 Add to playlist: New
  playlist, the 7 most recently changed, then All playlists…; a playlist
  that holds the song shows ✓ `IN PLAYLIST` and still adds it again. E3 New
  playlist: the field at the panel's top, Create and add absent while the
  name is blank, Back closes the keyboard first. E4 Reorder: §4. A song
  opened from a playlist adds Remove from playlist to its menu.
- **F · Now Playing (F1–F3).** No spine; Back returns where it was opened
  from. `PLAYING FROM │ {source}`, title, artist link, album line, spec line
  (§11), `QUEUE │ n OF m`, the queue strip (segments by duration in play
  order, a 40-track window with `… +n` at the ends when they would be too
  thin; Left/Right seeks while focused), elapsed and `ENDS h:mm`, transport,
  then Queue · Lyrics · ♥ · ··· and Up next. Opens on Play/Pause; media keys
  work anywhere. F2 Lyrics: the art shrinks left, synced lines keep the
  current line centred, unsynced lyrics scroll with the D-pad; requested
  only for songs that have lyrics. F3 Paused: `PAUSED` after elapsed, no
  `ENDS`, the Current dot holds still. No art-derived colour or backdrop.
- **G · Bar and drawer (G1–G3).** The bar is 8.4; G2 is its focus. G3:
  while music plays or is paused the drawer shows a Now playing entry under
  Home that opens Now Playing.
- **H · Queue (H1–H2).** From Now Playing › Queue, full screen, no bar. Left:
  counts, art, strip, shuffle and repeat toggles, Clear queue (absent when
  nothing follows). Right: Now playing, Next in queue (the user group,
  tagged `PLAY NEXT` or `ADDED`), Next from {source}. A row's ··· offers
  Play next · Move · Remove · Go to album (H2); semantics are §4. Opens on
  the first row after the playing one; OK plays from there.
- **I · Instant Mix (I1–I2).** From artist and album rows, the ··· menus and
  a genre list. After 400 ms the pressed control shows the loading dots and
  the artist page adds `BUILDING INSTANT MIX`; Back cancels and the queue
  stays. Success replaces the queue and opens Now Playing on track 1; no
  result or a failure shows `NO MIX FOR THIS {TYPE} YET` in GRIGIO.
- **J · Home (J1).** "Latest in {library}" in its server-order slot with
  square album cards (album artist as subline); OK opens the album, Play
  plays it; hideable like any shelf.
- **K · Search (K1–K3).** Same field and title index. Results group by type
  in a fixed order (Films · Series · Episodes · Artists · Albums · Songs ·
  Playlists), empty groups absent, each with its own art; song rows play on
  OK and open the song menu on ···. A Person hit that duplicates an artist
  is dropped (K2). No results (K3): the searching mascot, the query, and
  what was searched.
- **L · Action menus (L1).** THIS … (favourite, go to album/artist, reorder
  songs), PLAYBACK (Play next · Add to queue · Instant Mix), LIBRARY (Add to
  playlist ›, Refresh metadata, Remove from playlist); 8 entries at most.
  Play and Shuffle live on the pages, Watch together never appears.

## 10. States

- **S1 Loading.** A skeleton in the final geometry, no shimmer; each block
  fades in when its data arrives (§12). Now Playing keeps the previous art
  until the new one decodes.
- **S2 Empty.** The curious mascot and one factual line; the tabs stay.
- **S3 Unreachable.** `Couldn't reach {host}.`, `LAST TRIED {time}` and Retry
  focused; Retry reloads the current tab and never resumes playback.
- **S4 Missing art.** Album no-art tile, artist initial, track own art →
  album art → no-art thumb, playlist collage as sent; nothing is hidden for
  missing art, number or year.
- **S5 Clamps.** Names are never shortened or re-cased; only the visible
  lines are limited (page titles 2 lines, row titles 1 line, 2 when focused,
  spec lines wrap only at `│`).

## 11. Spec line

- Direct Play: `DIRECT PLAY │ {CODEC} │ {RATE} KHZ │ {DEPTH}-BIT` when the
  stream has a bit depth, else `… │ {BITRATE} KBPS`. The album capsule is
  the same without `DIRECT PLAY`, from the first track.
- The codec comes from the stream, never the raw container; rates print as
  `44.1 KHZ` / `48 KHZ`.
- A transcode (only when the user opted in) is `TRANSCODE │ {SRC} → {DST}
  {KBPS} KBPS │ {REASON}` in Ambra. No "bit-perfect" or "lossless" claims.

## 12. Motion

Focus 180 / 240 ms on the focus curve; Current dot 1.6 s loop only while
playing and visible; loading dots 1.2 s loop, shown after 400 ms; lyric line
change 240 ms; skeleton to content 180 ms fade per block (content that was
already there starts opaque); ambient drift §6.
