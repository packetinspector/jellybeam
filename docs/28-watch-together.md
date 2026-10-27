# 28 — Watch Together

Status: **Built on the `watch-together` branch; awaiting TV acceptance.** The
socket decode and REST calls (`core/jellyfin-api/src/syncplay.rs`), the group
reducer (`core/playback-policy/src/syncplay.rs`), the core owner
(`core/ffi/src/syncplay.rs`), the Kotlin executor
(`app/.../player/SyncPlayExecutor.kt`), the room list and lobby
(`app/.../ui/watchtogether/`) and the player's group readout
(`app/.../player/GroupOsd.kt`) exist. Watch Together is a client for the server's own
SyncPlay. It needs no server change, no new port, and no plugin.

## 1. Viewer experience

- **Start:** the Detail page's action, then a lobby showing the room's
  name, the members as they join, and Start or Cancel. A room is named
  "<user>'s group", as jellyfin-web names its own; when the list already
  holds that name, the next free " 2", " 3"… is appended (compared trimmed,
  ignoring case). Every member sees the name. The chosen title is held
  locally and sent only at Start.
- **Join:** Home menu → Watch together → the room list (each room's name,
  its members in server order, head count and state) → one press joins.
  A room that is playing goes straight to the player at the group's
  position; one in its lobby opens the read-only lobby, where playback
  begins when the host presses Start. Starting a room stays on the Detail page. Leaving a room
  joined from the list returns to the list once the server confirms the
  leave. The list's footnote says that everyone with watch-together access
  sees it.
- **In the player:** the group readout (room name, members, latest event) at
  the OSD's top right; with the OSD hidden a fresh join or leave shows
  alone for a moment. While the group waits, the OSD shows a "Waiting for
  everyone…" pill with Leave. A seek's own short wait never wakes the OSD,
  so repeated Left/Right taps keep seeking; a stall that reaches the §6
  prompt brings the OSD up.
- **The end of the queue** returns to the lobby, and the viewer stays in
  the group.
- **When the host leaves the lobby,** each guest leaves too and sees "The
  host left" (the host is the one member already in the group when a guest
  joined; with several, the guest leaves once all of them have gone); guests
  watching keep watching until the queue ends.
- **Each viewer keeps their own audio, subtitles, subtitle delay and
  quality.** SyncPlay carries item ids, positions and play state only.
- **Refusals:** starting while anything plays (PiP included) is refused
  with a Stop action; Stop ends that player, waits until it has closed and
  its final report is on its way, then starts the room for the same title. An account without SyncPlay access sees "Your
  account can't use watch-together".
- **In a group,** next-up, autoplay and the playback speed menu are off.
  A segment skip is one seek for the whole group: every member sees the
  skip pill (a guest whose own setting is auto-skip gets the pill), and
  only the host's own auto-skip setting skips by itself, so one member
  decides and members never race each other's seeks.
- **In a group,** a subtitle that needs the server to re-encode the stream
  (burning one in, removing the one burned in, or adding text the stream
  lacks) can't be chosen, since that reload can't land on the group's exact
  position (§5); delivered text switches as usual.

## 2. Server protocol and compatibility

- **Supported:** Jellyfin 10.11+ and Jellyswarrm.
  - The wire format is identical across 10.11, 12.0 and 12.3.
  - All traffic uses the existing `/socket` and REST connections.
- **jellyfin-web members:** jellyfin-web expands a queue of one episode
  into that episode and every later real episode of its series, then pairs
  its list with the server's by position; a shorter queue breaks its
  player. So a host's episode pick sends that same list (from the local
  mirror, missing and unaired episodes left out); anything else goes alone.
- **Messages in:** `SyncPlayCommand` (Unpause, Pause, Stop, Seek at a
  server-UTC `When`) and `SyncPlayGroupUpdate` (GroupJoined, UserJoined,
  UserLeft, GroupLeft, NotInGroup, GroupDoesNotExist,
  LibraryAccessDenied, StateUpdate, PlayQueue).
  - The update's `Type` is switched by hand. The spec's `oneOf` form
    can't tell UserJoined from UserLeft.
  - Unknown `Type`, state or reason values decode instead of failing.
- **Requests out:** one method per `/SyncPlay/*` endpoint plus
  `GET /GetUtcTime`.
  - A 403 is a status error, not a sign-out. It means no SyncPlay access,
    and the token is fine.
- **The server owns the group state** (Idle, Waiting, Paused, Playing).
  Clients obey commands, report Buffering/Ready at their *landed*
  position, and send member actions as requests.
- **Error replies carry an empty group id.** Jellyfin sends
  `GroupDoesNotExist` and `NotInGroup` with `Guid.Empty`
  (`SyncPlayManager.cs`); Jellyswarrm sends all three error replies,
  `LibraryAccessDenied` included, with a nil id. So none of the three is
  filtered by group: they answer our own requests.
- **Version differences that matter:**

  | | 10.11 / 12.0 | 12.3 |
  |---|---|---|
  | Wait for a member that never reports Ready | indefinitely | 30 s |
  | Catch-up tolerance after buffering | 500 ms | 60 s |

## 3. Privacy

The rule: **Jellybeam reveals nothing beyond what the server already
exposes, and can't itself be used to spy.**

**What the server exposes, regardless of client:**
- `GET /Users` returns every account's last-activity time to any signed-in
  user, so online status is already public.
- While a group exists, every account with SyncPlay access (the default)
  can list it: name, member usernames, state.
- Any of them can join it by id, uninvited, and immediately receives the
  title and position. There are no kicks, passwords or private groups.
- The admin lever is configuration, not modification:
  `SyncPlayAccess = None` per account, or library restrictions (a group
  is hidden from accounts that can't access its queue). Jellyswarrm has no
  such setting.

**What Jellybeam guarantees:**
1. The room list shows only what the server already exposes — group name,
   member names, state — and is requested only while its screen shows.
2. A group's name is its creator's user name plus "'s group", which the
   member list already exposes.
3. It never creates a group while a playback report is active. The server
   would otherwise seed the group from the creator's now-playing state.
4. A group exists only while its members are in the lobby or player.
5. Outside a group the only SyncPlay request is that list, while its
   screen shows or once as a room starts; no ping.
6. User names and room names never reach logs: redacting `Debug`, and
   decode failures log only the message type.

## 4. Group lifecycle

One owner decides everything: `playback_policy::syncplay::SyncPlay::step`,
a pure reducer over states Idle, Creating, Joining, Member and Leaving.

- **Stale results die in one place.** Every request, directive, timer
  and membership carries a tag, and `step` drops anything that isn't
  current.
- **Socket drop and reconnect.** Jellyfin removes a member the moment its
  socket drops; Jellyswarrm keeps it for 30 s and replays the group and
  queue. After reconnecting, Jellybeam waits 2 s for a replayed
  GroupJoined, then sends Join.
  - Every (re)entry resets the queue cursor. A 12.0 rejoin snapshot
    repeats the old `LastUpdate`, and dropping it would leave us silent.
  - GroupDoesNotExist on rejoin ends the group with a notice.
  - The 2 s wait starts once the reconnect's server refresh returns.
  - A lagged event bus may have lost a group frame, so a member rejoins
    at once. Jellyfin treats it as a session restore; Jellyswarrm leaves
    and rejoins, pausing everyone briefly.
- **Entry timeout.** A create or join with no GroupJoined after 10 s gives
  up with a notice and sends Leave, in case the server made us a member
  anyway.
- **Never the silent member.** A command that arrives while our item
  loads is held until the item lands and Ready is sent. A held Seek skips
  that Ready, because its own landing reports one.
- **A recreated player stays in the group.** A player screen the system
  recreates re-attaches as the same player; only a different one is closed.
- **A group load opens the player** through the same request slot as any
  Play (docs/17 §3). While Jellybeam is in the background, the mini player
  over another app included, the load waits until Jellybeam is opened. A
  load interrupted while expanding the mini player is dropped, since the
  group's next Load opens it again; a Play of anything else that replaces a
  waiting load leaves the group.
- **Exits.** Leaving the player, sign-out, account switch or a fatal
  playback error leaves the group. Every exit returns the player to solo
  controls, and a playing video keeps playing solo.
  An account change leaves with the outgoing account's own token.
  Every Leave goes out at once. If a create or join is still unanswered
  when the group leaves, for any reason, one more Leave follows its
  answer, however late, so an entry that reaches the server after the
  first Leave is undone too. A Leave outside a group is refused by the
  server, which is harmless. A sign-out's logout waits for the last of
  these Leaves, or for our own Leave already in flight, up to the
  final-report budget.
- **App teardown.** Exiting the app sends a best-effort Leave first.
  Swiping it from recents gives no callback, so that case falls to the
  killed-process rule below. A core dropped mid-group sends its Leave at once,
  waiting at most 1 s; a second Leave after an unanswered entry is
  best-effort. A killed process is left to the server: Jellyfin
  drops us when the socket closes, Jellyswarrm after its 30 s grace.
- **Commands** missing their kind, time or playlist item (Stop excepted)
  are ignored. So are commands emitted before we joined, and queue updates
  that aren't newer than the last one applied.
- **After the queue ends,** the player has returned to the lobby, and group
  commands and snapshots are ignored until a new title loads.
- **The client never guesses the server's internal state.** Whether the
  server means to resume, or when the lobby really started, differs
  between Jellyfin and Jellyswarrm. Decisions use local facts (our Ready
  was sent, the playlist has an item, a command arrived) and the user's
  own presses.

## 5. Playback sync

- **Clock:** `GET /GetUtcTime` gives offset ((t1−t0)+(t2−t3))/2 and
  RTT (t3−t0)−(t2−t1), from the lowest-RTT of the last 8 samples. The
  schedule is: one sample at once, three more 1 s apart, then one every
  60 s while in a group. Each sample is followed by
  `POST /SyncPlay/Ping {round(RTT/2)}`. No player directive is issued
  before the first sample.
- **Commands** run at their `When` converted to local time. A `When`
  already past runs at once, with the position advanced by the elapsed
  time.
- **Group seeks land exactly.** The server re-sends Seek whenever a Ready
  is more than 500 ms from the target, so a keyframe-snapped landing
  (docs/18 §5.2) would loop forever. A seek that never reports landing
  reports where the player is after the 2.5 s landing timeout, since an
  owed report holds the group, and an off-target one is re-sent anyway.
- **Drift correction is hold-or-seek, never playback rate**, because rate
  changes under passthrough audio are unverified.
  - It acts at |drift| ≥ 400 ms, evaluates at most every 1.5 s, and locks
    out for 1.5 s after a landing or an Unpause.
  - It never acts while buffering, seeking, loading, paused or already
    correcting.
  - A client ahead pauses for the drift, up to a ceiling; beyond that it
    seeks back.
  - A client behind seeks forward.
- **Buffering** is reported only after 3 s of stall. A Ready owed after a
  reported stall is sent even if the group paused meanwhile.
- **A lost report is resent.** A Ready or Buffering that fails with a
  connection failure or 5xx is resent up to 3 more times, 1 s apart, ahead
  of later requests; a newer report of the same kind replaces it. A 4xx is
  final (a 403 rejoins), and so is a request timeout, since resending to a
  hung server would hold every later request for minutes.

## 6. Stall recovery

A member that never reports Ready holds the group in Waiting indefinitely
on 10.11 and 12.0. The server ships its own escape: while it intends to
resume, an `Unpause` from any member forces playback to start and stops
waiting for stragglers (`WaitingGroupState.cs`, "Forcing the playback to
start").

- **At Start,** a new queue starts the group by itself once everyone is
  ready, on both servers, so nobody sends anything extra.
- **No automatic force.** Whether the server means to resume differs
  between Jellyfin and Jellyswarrm (a paused Next stays paused on
  Jellyswarrm), so the client never starts playback on its own.
- **The client rule:** when our Ready has been sent and no command has
  arrived for 10 s, every member sees "Still waiting for someone — press
  Play to start without them". Pressing Play sends `Unpause`, which is the
  server's force. A paused group only plays because a person chose it.
  The prompt clears on the next command (the server's own Pause answering
  our Ready doesn't count) or on leaving.
- **On Jellyswarrm,** Unpause always starts at once.

## 7. Jellyswarrm

Jellyswarrm implements SyncPlay inside the proxy rather than forwarding it.

- Groups live in the proxy, and members on different backends can share
  one. All members must sign in through the same Jellyswarrm address; a
  direct-backend account and a Jellyswarrm account can't share a group.
- There is no `SyncPlayAccess` check, so any account signed in through the
  proxy can list and join.
- A dropped socket keeps membership for 30 s and replays the group on
  reconnect (§4).
- A new queue waits for everyone's Ready, then starts, as on Jellyfin.
- Every join, and every reconnect inside the grace, receives a "new
  playlist" message even when the playlist is empty, so a message type
  alone is never a start (§4).
- An item change (Next, Previous, picking an item) keeps a paused group
  paused (§6).

## 8. Cost when unused

A viewer who never opens Watch Together pays nothing:

- no requests at startup or idle;
- no timers or polling outside a group, except that the room list polls
  `/SyncPlay/List` every 5 s, only while it is on screen, and starting a
  room lists once to pick a free name;
- no always-on collectors: events arrive through a listener registered
  on group entry, not the shared change stream, whose listeners refresh
  Home, Library, Detail and the drawer;
- the idle state holds no heap, and background jobs start only on create
  or join;
- no extra composition on Home or Detail first frame.

Native library growth is measured per slice. The wire layer added about
60 KB unstripped on arm64.

## 9. Testing and shipment gates

- **Reducer:** every length-4 event sequence from seeded states, with the
  invariants checked after each step; named sequences for each lifecycle
  case above; mutation checks on the cursor reset and the never-auto-start
  rule.
- **Wire:** decode fixtures for every update type and command, plus
  request-shape tests against a local server.
- **Drift:** two emulators on the dev server play a frame-counter clip
  for 10 minutes. Pass is ≤ 120 ms plus 3× the measured screencap skew for
  95% of samples, never beyond 400 ms plus skew, and pause or seek from
  either side propagating in under 1 s.
- **Interop:** a jellyfin-web member joins the room from its own list, and
  commands propagate both ways in under 1 s.
- **Privacy:** a non-member account records what it can see during a
  session. Its capture must equal §3's server exposure, and the list
  request appears only while the room list shows.
- **Versions:** 10.11 and 12.0, plus a local Jellyswarrm in front of the
  dev server.
- **Idle cost:** `tools/perf-ab.sh` within noise on TTFF, cold start →
  Home, Detail first frame and memory.
