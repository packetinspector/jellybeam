# 21 — User reporting: diagnostic log and the LAN report page

Spec of record for how a user, with no adb and no typing on the TV, gets a
redacted diagnostic log from Jellybeam into a GitHub issue, and how a crash
gets the same treatment. The flow was walked end to end on a LAN (phone
scan, download, prefilled issue form on the public repository). Replaces
the "Debug log for troubleshooting" entry in docs/13 Planned.

Status: implemented (`tv.jellybeam.diag`, `ui/report`, `core/ffi/src/diag.rs`,
`.github/ISSUE_TEMPLATE/tv-bug.yml`); the on-device checklist in §9 is the
remaining verification.

Goals, in priority order:

1. **Free, and nothing to run.** No relay, no paste site, no OAuth app.
   The TV serves one page on the local network; GitHub Issues is the
   inbox.
2. **One reproduction, not two.** A report carries everything recorded
   since logging was turned on; the user is never told "enable logging and
   do it again" unless logging was off.
3. **Nothing identifying leaves the TV.** Redaction happens where a line
   is written, never at export time, so there is no unredacted log
   anywhere (docs/05 "Secrets & redaction").
4. **Costs nothing when off, next to nothing when on.** The recorder is a
   string append on a background thread; the server exists only while the
   report screen is visible.

## 1. User flow

### 1.1 Turning logging on

Settings gains a **Troubleshooting** section (§6). Its first row is
**Diagnostic logging**, default **off**. The subtitle reads `Off`, or
`On since <date> · <n> lines`. Turning it on starts the recorder
immediately; nothing is written before that moment, so a report after a
problem that predates the switch carries only the summary (§3.1).

### 1.2 Reporting a problem

1. Settings > Troubleshooting > **Report a problem**. Always enabled: with
   logging off it still shares the summary and any crash capture.
2. The TV takes a **snapshot**: the summary, the ring buffer as it is now,
   and the pending crash capture if one exists. The snapshot is frozen;
   a second scan or download gets identical bytes.
3. The TV opens a listening socket (§4) and shows the **report screen**:
   a QR code, the same URL as text for hand-typing, and the sentence
   "Nothing is sent until you choose to on your phone." The screen holds
   the display awake while it is visible.
4. The user scans with a phone on the same network. The phone page (§5)
   shows the exact summary and the full log, a **Download log** button and
   an **Open bug report on GitHub** button.
5. The TV screen updates as the phone acts: `Opened on a phone` after the
   page is fetched, `Log downloaded` after the file is fetched.
6. The GitHub button opens the repository's issue form with the summary
   fields prefilled. The user writes what happened, attaches the
   downloaded `jellybeam-log.txt`, and submits under their own account.
7. Leaving the report screen closes the socket. If the log was downloaded
   during this visit, one dialog asks **Turn diagnostic logging off now?**
   with Yes / Keep logging. No download, no dialog.

The report is public. The page says so in plain words (§5), and the
redaction rules (§2.3) are what make that acceptable.

### 1.3 After a crash

An uncaught exception in the app process writes a **crash capture** to
app-private storage before the process dies: the summary, the stack trace
(class names and frames only, never the exception's own message) with app
frames first, and the ring buffer if logging was on. On the next
launch that reaches the Home screen (never a launch that goes straight
into playback, such as the `jellybeam://play` deep link or a launcher
play-next intent), one dialog appears:

> Jellybeam closed unexpectedly last time.
> **Report now** · **Later** · **Discard**

The frames are R8-minified; retrace them with the archived mapping whose
`pg_map_id` the build carries (docs/06, `internal/mappings/`). Report now
opens the report screen with the crash pinned at the top of the
summary. Later keeps the capture and shows the dialog again on at most
the next two Home launches, then falls silent while the capture stays
available from Report a problem. Discard deletes the capture. A new crash
replaces an older capture.

**Crash reports** is its own Troubleshooting switch, default on. Off means
the exception handler writes nothing and the dialog never appears; there
is no "capture silently" mode. The choice persists as a marker file, read
at construction, so a startup crash before Settings loads still honors it.
A user who opted out before the marker existed is still honored on the
first launch after upgrade: construction checks the Rust core's persisted
`crash_reports_enabled: false` when no marker exists yet and creates the
marker on the spot.

## 2. What is recorded

Two tiers share one ring. The **event tier** is what Diagnostic logging
turns on. The **perf tier** is docs/10's `PerfLog`, gated as today by the
platform log-level property; when both are on, perf lines also land in
the ring so a report from a measured session includes them. Diagnostic
logging does not enable the perf tier and Settings does not expose it.
That gate stays adb-only on purpose: anyone measuring frames has adb, and
the report page already replaces adb for the one thing adb could not do,
pulling a file from a release build.

Ring lines also mirror to logcat under the `JellybeamTV` tag at INFO while
logging is on, so an adb session sees them live.

### 2.1 Event tier (Kotlin)

One line per state change or decision, never per frame or per item.
Fixed event names, `key=value` fields, no free text from the server.

| Area | Events | Fields |
|---|---|---|
| Process | `app.start`, `app.background`, `app.foreground` | version, cold, elapsedMs |
| Session | `auth.restore`, `auth.reauth`, `auth.signin`, `auth.signout` | server alias, ms, result, reason (fixed enum, e.g. `401`) |
| Sync | `sync.pass` | server alias, kind (home/library/nextup/…), items, ms, result |
| Websocket | `ws.connect`, `ws.drop`, `ws.reconnect` | server alias, ms, attempt |
| Playback | `playback.prepare`, `playback.plan`, `playback.stop` | item alias, item type, mode (DirectPlay/Transcode), container, video/audio codec, subtitle source, reasons enum, posMs |
| Player | `player.state`, `player.decoder`, `player.track`, `player.error` | state, decoder name, track index and codec, error code, posMs, ms since previous state |
| Segments | `segment.skip` | kind, action |
| UI | `focus.restore.miss`, `nav.screen` | screen name, reason |
| Settings | `settings.change` | field name, old, new (never server addresses or API keys) |
| Errors | `error` | where, code, posMs |
| Core calls | `ffi.error` | section (the fixed `ffi.<method>` name), kind (the error variant as a fixed word, never its text) -- recorded for every failed core call, since most callers fail open |

Rust `tracing` WARN and ERROR records reach the same ring through a
uniffi callback interface registered once at core construction, formatted
`core.<target> level=warn msg=<fixed message>` with structured fields
appended; the message text is the crate's static string, never an
interpolated URL or path. Numeric and bool fields are always appended;
an `error`/`err` field forwards only a fixed classification label computed
from its text (e.g. `timeout`, `http_500`), never the text itself; any
other string/Debug/Error field is appended only when its name is on a
fixed text allow-list, still scrubbed. INFO and below are not forwarded.

### 2.2 Sizing and cost

- Capacity: 2000 lines or 256 KB, whichever fills first; oldest dropped.
- Writers call a non-blocking `enqueue` that hands the preformatted
  arguments to one single-thread executor; formatting, alias lookup and
  the ring append happen there. A hot-path call site pays one volatile
  read of the enabled flag and, when on, one small object allocation.
- Flushed to `files/diag/ring.txt` (app-private) on `app.background`,
  on report snapshot, and by the crash handler. Not on a timer: the
  system delivers `onStop` before killing a backgrounded process in
  practice, and losing the tail after a hard kill is accepted.
- On start with logging on, the previous flush is read back so a report
  spans the last two processes. The summary states both process start
  times.

### 2.3 Redaction, enforced at the write site

The recorder API takes typed values, not strings, so a caller cannot pass
a title or a URL by accident:

- Server and item identity go through `alias(kind, id)`: first-seen
  numbering per process (`server#1`, `item#7`). Aliases are stable within
  a process and reset when the ring is cleared.
- Accepted field types: enums, integers, durations, booleans, codec and
  container names, fixed error codes, screen names. A `String` field
  must come from a compile-time allow-list (`reason`, `state`, decoder
  names from the platform).
- Never recorded: usernames, server names or addresses, tokens, API keys,
  item names, file paths, stream URLs, device names or serials, IP
  addresses of either the TV or the server, Jellyfin user or session ids.
- The summary's hardware field is `Build.MODEL` and `Build.MANUFACTURER`
  only.
- A unit test feeds the recorder every field type with a known synthetic
  hostname, token, title and path inside them and asserts none appear in
  the dump. A second test dumps a seeded session and asserts the file
  matches a golden fixture line for line.
- Crash captures apply the same rule outside the ring: `Throwable.message`
  is never written, only the exception's class name and its
  `StackTraceElement` frames.

## 3. The snapshot

### 3.1 Summary

Ten-ish lines a maintainer reads before opening the log:

| Field | Source |
|---|---|
| App version and build | BuildConfig |
| Android version and API level | Build.VERSION |
| Hardware | Build.MANUFACTURER, Build.MODEL |
| Server version | the persisted per-server value (docs/13 Server compatibility); `unknown` until the first refresh |
| Servers signed in | count only |
| Playback mode | Settings quality chip value |
| Last playback plan | most recent `playback.plan` line |
| Last reauth | most recent `auth.reauth` line |
| Last error | most recent `error` or `player.error` line |
| Crash | `none`, or the exception class and the first app frame |
| Logging | `off`, or on-since date and line count |
| Process starts covered | one or two timestamps |

### 3.2 Files served

- `jellybeam-log.txt`: the summary as `# key: value` header lines, a blank
  line, then the ring oldest first, then `--- crash ---` and the stack
  trace when a capture is pinned. Plain UTF-8, LF line endings.
- `report.json`: the same summary as an object and the lines as an array,
  for scripts. Same token, same lifetime.

## 4. The local server

- **Bind**: `0.0.0.0`, fixed port **8765**. If the port is taken, try the
  next four and show whichever bound; the QR always encodes the real port.
- **Address shown**: the TV's site-local IPv4 on the interface that
  routes outward (a UDP connect to an unroutable address, then read the
  local socket name). If none is found the screen says the TV must be on
  the same network as the phone and offers nothing else (accepted for
  v1; multi-interface and USB fallbacks are not built).
- **Credential**: an 8-character URL-safe random path segment generated
  per snapshot: `http://<tv-ip>:8765/r/<token>`. Every other path,
  including `/r/` with a wrong token, answers 404 with an empty body. The
  token is the only secret; no cookies, no auth headers.
- **Routes**: `GET /r/<token>` (page), `GET /r/<token>/jellybeam-log.txt`
  (attachment), `GET /r/<token>/report.json`. GET only; anything else is
  405.
- **Lifetime**: closed when the report screen leaves the foreground for
  any reason (Back, Home, launcher, Assistant, playback start) and by a
  **10-minute** timer counted from the screen opening. Re-entering the
  screen takes a new snapshot and a new token. Closing revokes every
  connection already accepted, queued or in flight, so none gets a
  response after the screen leaves.
- **Concurrency**: any number of clients while live; no single-use
  enforcement. The served indicator counts page fetches and file fetches.
- **Bounds**: request line capped at 2048 bytes and headers at 32 lines /
  8192 bytes total, each read off the socket rather than an unbounded
  readline; a 10-second per-connection deadline plus a 2-thread/8-deep
  queue budget close or reject anything past it without a response.
- **Implementation**: `java.net.ServerSocket` on one background thread
  with a small per-connection handler, request line and headers parsed
  by hand, response built from string templates in resources. Two routes
  do not justify a web-server dependency. Response size is bounded by the
  ring cap. No WebView anywhere; the page is served to the phone's
  browser, and the TV draws only the QR.
- **QR**: `com.google.zxing:core` (Apache 2.0) encodes the URL to a bit
  matrix drawn with Compose `Canvas`. Error-correction level M, module
  size chosen so the code is at least 260 dp on a 1080p layout.

## 5. The phone page

Served inline, no external resources, no script beyond a disclosure
toggle. Renders on any phone browser.

1. Heading and one paragraph: "This is exactly what will be shared.
   Nothing else leaves the TV. Names, titles, addresses and sign-in
   details are never recorded. The bug report you file will be public."
2. The summary table (§3.1).
3. **Full log** as a collapsed `<details>` block holding every line that
   the download contains.
4. **1. Download log (n KB)**, a link with the `download` attribute to
   `jellybeam-log.txt`.
5. **2. Open bug report on GitHub**, a link to the issue form (§7) with
   the summary fields carried in the query string. Opens in a new tab.
6. Footer: the page stops working after ten minutes or when the TV leaves
   the report screen.

Order matters: download first, then the form, because the form's
attachment step needs the file on the phone.

## 6. Settings: Troubleshooting section

New `SettingsSection.TROUBLESHOOTING` after Discover. Rows:

| Row | Kind | Default | Behaviour |
|---|---|---|---|
| Diagnostic logging | switch with status subtitle | off | Starts or stops the recorder. Off also deletes the ring and the flushed file. |
| Crash reports | switch | on | Off uninstalls the capture path; no dialog ever. |
| Report a problem | action | | Opens the report screen (§1.2). |
| Clear log | action | | Deletes ring, flushed file and crash capture; resets aliases. Disabled when there is nothing. |

Both switches live in the Rust-owned `Settings` record (docs/09) as
`diagnostic_logging_enabled` and `crash_reports_enabled`, so they persist
with the rest and load before the first frame. The recorder reads them
from the settings flow; the crash handler is installed at process start
from the loaded value.

## 7. GitHub side

The repository is public. Under `.github/ISSUE_TEMPLATE/` an issue form
`tv-bug.yml` with:

- `category` dropdown: Playback, Sign-in, Browsing, Performance, Crash,
  Other. Prefilled to Crash when a capture is pinned, otherwise to the
  area of the last error, otherwise Other.
- `what_happened`, required textarea.
- `steps`, optional textarea.
- `summary`, a textarea prefilled with the summary block (markdown list),
  marked "filled in by the TV, edit if you like".
- Body text above the fields: "Attach jellybeam-log.txt from your phone's
  downloads below."

Prefill uses GitHub's documented query parameters for issue forms
(`title` and one parameter per field id). The form itself applies `bug`
and `needs-triage`; the TV sends no `labels` parameter, since GitHub drops
it for reporters without triage access. The query string stays under 8 KB by construction:
the summary is short and the log is never in the URL.

## 8. Ownership

- **Rust**: the two settings fields and their defaults and migration; the
  tracing forwarder (a `tracing_subscriber` layer that calls the uniffi
  callback for WARN+); nothing else. The recorder, server and screen are
  presentation and process concerns and stay in Kotlin (docs/01).
- **Kotlin**: `diag/DiagLog` (recorder, ring, aliasing, flush, read-back),
  `diag/CrashCapture` (handler and next-launch state), `diag/ReportServer`
  (socket, routes, templates), `diag/ReportSnapshot` (summary, files),
  `ui/settings` Troubleshooting rows, `ui/report/ReportScreen` (QR,
  indicator, lifecycle), the Home-launch crash dialog.
- **Repository**: the issue form.

## 9. Verification

Unit tests: redaction allow-list and golden dump (§2.3); ring cap and
oldest-first eviction; alias numbering and reset; summary derivation from
a seeded ring including the "last plan / last error / crash" fields;
server request parsing (valid token, wrong token, wrong method, expired);
`report.json` shape; issue URL builder stays under 8 KB with a full
summary; crash capture file format; the Later counter.

On-device checklist, release build:

1. Logging off, Report a problem: page shows summary only, download is a
   header-only file, no disable dialog on Back.
2. Logging on, play something, stop it, report: `playback.plan`,
   `player.state` and `playback.stop` present with aliases, no title.
3. Scan from a phone on the LAN: page opens, indicator flips, download
   lands, GitHub form opens prefilled, category matches.
4. Wrong token and expired token from the phone: 404 and the expiry text.
5. Home while the screen is up: socket closed within a second.
6. Force a crash (debug hook): next Home launch shows the dialog; Report
   now pins the stack; Later twice then silence; Discard deletes.
7. Crash reports off: the same forced crash leaves no file and no dialog.
8. Frame time on Home with logging on is unchanged from off (docs/10 frame
   histogram, same session).

## 10. Not built, and why

- **GitHub Device Flow (one button, no phone).** Would need an OAuth app
  registration whose client id ships in the APK. That is normal for open
  source and not a secret, but it adds an account prompt, token handling
  and a gist upload for something the LAN page does with zero
  infrastructure. Reconsider only if scan-and-attach proves too hard for
  users in practice.
- **Persistent developer endpoint.** Not superior to adb: adb streams
  live, drives the perf gate and records the screen. The one gap, pulling
  a file from a release build, is closed by the report screen itself.
- **USB export, share sheet, email.** TV share targets are near-empty and
  USB handling adds storage permissions for a rare case.
- **Media3 event logger in the ring.** Too verbose for a 2000-line ring;
  a stutter investigation uses the perf tier over adb.
- **Multiple interface addresses.** Show the routed one; a VPN on the TV
  is rare and the typed URL still lets the user substitute another
  address the TV shows in system settings.
