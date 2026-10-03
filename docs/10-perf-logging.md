# 10 — Performance logging

A debug-gated metrics layer that proves out startup time, FFI-call cost,
image-load latency, and frame jank on a **measured release build** -- the
build we actually ship and profile on the target hardware. Off by default,
and effectively free when off: every hot-path call site is a single
`if (!enabled)` check on a cached `Boolean` before anything else happens
(no timer read, no string building, no allocation). See
`app/src/main/kotlin/tv/jellybeam/perf/PerfLog.kt` for the gate
and helpers; `JellybeamApp.kt`, `MainActivity.kt`, and `data/CoreGateway.kt`
are where it's wired up.

## Enabling it

The gate is the platform's own runtime log-level check, not a custom flag
-- `BuildConfig.DEBUG` doesn't work for this because it's always `false`
in the release build we're trying to measure.

```
adb shell setprop log.tag.JellybeamTV DEBUG
```

Then launch (or background/foreground) the app -- `MainActivity.onResume`
re-checks the property on every resume, so toggling it against an
already-running process works by pressing Home and reopening the app, or by
opening/closing anything that backgrounds `MainActivity` (playback
included). No process restart is required for FFI timing, `imageUrl`
timing, or Coil image-load logging. The one exception is the frame-metrics
listener itself: it's (de)registered from that same `onResume`, so it also
just needs one resume cycle, not a cold start.

To turn it back off:

```
adb shell setprop log.tag.JellybeamTV INFO
```

Read the log with the usual tag filter:

```
adb logcat -s JellybeamTV:D
```

## Mirror into the diagnostic ring

When Settings > Troubleshooting > Diagnostic logging is on, every line
below is also appended to the docs/21 ring (prefixed `perf `), so a report
taken after a measured session carries the same numbers without adb. The
gate itself stays this property; Settings never turns the perf tier on.

## What each line means

Every line starts with `perf` so they're easy to grep out of a noisier
logcat capture. `adb logcat -s JellybeamTV:D | grep '^.*perf '` narrows further
if other `JellybeamTV`-tagged lines (device-caps probe, etc.) are mixed in.

### Startup phases

```
perf startup phase=process.start atMs=1100
perf startup phase=application.onCreate.start atMs=1234
perf startup phase=application.onCreate.end atMs=1240
perf startup phase=core.ready atMs=1890
perf startup phase=activity.onCreate atMs=1245
perf startup phase=activity.firstFrame ms=612.34
perf startup phase=root.stackReady atMs=1920
perf startup phase=home.contentDrawn atMs=1935
perf startup phase=home.shelvesMounted atMs=1938
```

- `process.start` -- `Process.getStartElapsedRealtime()`, logged from
  `JellybeamApp.onCreate` on the same `atMs` clock as everything else here, so
  `process.start` -> `application.onCreate.start` is directly readable.
  API 24+ only; below that there's no equivalent call and the mark is
  skipped.
- `application.onCreate.start` / `.end` -- brackets `JellybeamApp.onCreate`.
  The gap here is deliberately tiny: `AppGraph.init` fires the real
  `JellybeamCore` construction onto a background coroutine and returns
  immediately (see that function's own doc comment), so this doesn't
  capture the actually-interesting cost.
- `core.ready` -- when `JellybeamCore`'s constructor (JNA library load +
  SQLite mirror open) actually finishes on its background coroutine. This
  is the number that matters for "how long until FFI calls stop queuing
  behind construction." The same background coroutine also warms Coil's
  `ImageLoader`/disk-cache journal and `JellybeamTheme`'s font faces off-main,
  right after core construction fires, so neither parses on the main
  thread at Home's first card or first text composition.
- `activity.onCreate` -- when `MainActivity.onCreate` runs.
- `activity.firstFrame` -- **not** an `atMs` timestamp like the others;
  this one is already a duration (`ms=`), measured from the start of
  `MainActivity.onCreate` to the first `ViewTreeObserver.OnPreDrawListener`
  callback (i.e. time to first frame actually drawn).
- `root.stackReady` -- `JellybeamRoot`'s back stack first resolving (Home or
  Sign In) after `restoreSession`/`openMirror` settle. Fires again on a
  later session epoch (a Servers-section switch/add) or an Activity
  recreated in a live process, not just on cold start.
- `home.contentDrawn` -- one-shot, the first pre-draw that actually carries
  real shelf content rather than the loading skeleton.
- `home.shelvesMounted` -- one-shot, once every shelf below the hero has
  entered composition. Composition finishing isn't the same as those
  shelves' art having decoded or drawn a frame; see `artwork.firstDecoded`
  and `home.contentDrawn` for those.

`restoreSession`, `openMirror` and the first `homeSnapshot` do not wait for the Activity:
`LaunchWarmup` starts them from `AppGraph.init`, behind `core.ready`, so they overlap
`activity.firstFrame`. The warm-up reads the persisted Home layout from settings (in memory) and
prefetches that layout's snapshot (docs/25 §6.1). `JellybeamRoot` and the Home layout's feed each
take their result once; an account
switch, an added server or a recreated Activity finds nothing to take and runs the same calls
itself. A cold launch therefore shows one `ffi.homeSnapshot` before `home.dataReady`, from the
warm-up, and a second only when the mirror changed in between.

The `atMs` marks share one clock (`SystemClock.elapsedRealtime()`), so
subtracting any two of them gives a real elapsed duration; `atMs` values are
not wall-clock time and are meaningless compared across separate app runs.

Home also emits `home.dataReady` when its first snapshot reaches composition,
`home.focusReady` when loaded content (or the empty-state fallback) gains focus,
and `home.revealEnd` when the reveal timer retires the skeleton. Use the first
focus mark per cold launch for input readiness; later focus marks include returns
from the drawer or other screens. None of these proves that artwork has decoded,
and the reveal timer is not a GPU presentation timestamp.

`artwork.firstDecoded` marks the first successful Coil image result in the process
while timing is enabled. It measures image availability, not a visible GPU frame
or completion of all above-the-fold artwork; it may include a memory-cache result.

The manifest strips androidx's `EmojiCompatInitializer` (`AndroidManifest.xml`,
`tools:node="remove"`) because the app renders no emoji and the initializer's
post-resume font fetch was invalidating every Home text node right around
`home.focusReady`.

Playback negotiation failures emit `plan.refusedTranscode`, `plan.unauthorized`,
`plan.stale`, or `plan.failed`. These contain no server-provided details; a refusal
is not a successful first-frame sample.

### FFI call timing

```
perf section=ffi.homeSnapshot ms=8.42
perf section=ffi.preparePlayback ms=41.07
```

One line per call, logged from `RealCoreGateway`'s suspend wrappers via
`PerfLog.timed`. These are logged per-call (not aggregated) because in
practice they're seconds apart -- Home/Library loads, sign-in, playback
prep -- not a hot loop. `section` is always `ffi.<methodName>`, never
anything derived from an argument (server addresses, item titles, and
tokens are never logged, per this app's standing privacy rule).

The device-caps probe (`MediaCodecList` walk, binder calls) is timed the
same way but isn't an FFI call, so it logs `perf section=deviceCaps.probe
ms=...` instead, and only starts once `activity.firstFrame` has fired
rather than at process start, so it no longer races core init, first
frame or Home composition.

### Playback-start timeline

Each playback attempt emits monotonic phase marks that bracket the path
from `PlaybackActivity` creation through the first rendered video frame:

```
perf playback phase=activity.create atMs=2000
perf playback phase=viewModel.start atMs=2012
perf section=gateway.preparePlayback.total ms=48.30
perf section=ffi.preparePlayback ms=47.91
perf playback phase=plan.ready atMs=2061
perf playback phase=settings.ready atMs=2064
perf playback phase=player.load atMs=2065
perf playback phase=exo.setMediaItem atMs=2066
perf playback phase=exo.prepare atMs=2068
perf playback phase=ui.install atMs=2070
perf playback phase=surface.ready atMs=2085
perf playback phase=resume.seek atMs=2240
perf playback phase=exo.tracks atMs=2250
perf playback phase=decoder.video initMs=18 atMs=2265
perf playback phase=decoder.audio initMs=6 atMs=2268
perf playback phase=exo.ready atMs=2400
perf playback phase=firstFrame atMs=2420
perf playback phase=firstFrame.resume atMs=2420
perf playback loads count=3 bytes=1048576
perf playback phase=mediaSession.ready atMs=2421
```

All `atMs` values use `SystemClock.elapsedRealtime()`. Subtract
`viewModel.start` from `exo.prepare` to measure the pre-ExoPlayer dwell;
subtract `exo.prepare` from `firstFrame` to isolate media loading and decoder
startup. `gateway.preparePlayback.total` includes waiting for the asynchronous
device-capability probe, while its nested `ffi.preparePlayback` line does not;
the difference identifies an early-start capability-probe stall.
`ui.install` is intentionally after `exo.prepare`: the initial Compose player
screen is not allowed to hold a ready playback continuation off ExoPlayer's
main-thread API. `mediaSession.ready` is intentionally no earlier than
`firstFrame`, keeping system-integration construction off both the prepare and
visible-playback critical paths.

`surface.ready` is the first `onSurfaceSizeChanged` Media3 reports with a
non-zero size; `exo.tracks`/`exo.ready` are the first `onTracksChanged`/
`STATE_READY` from the shared player, and `decoder.video`/`decoder.audio`
carry `AnalyticsListener`'s own `initMs=` for that decoder, all from
`PlayerHolder`. `resume.seek` is logged only for a resume (docs/18 §5.2):
the one extra `seekTo` issued once `exo.tracks` fires, landing the resume on
its preceding keyframe instead of decoding through every frame back to it.
`firstFrame.resume` duplicates `firstFrame`'s timestamp but only appears on a
resume, so a filtered capture can isolate resume-specific first-frame timing
without cross-referencing `player.load`'s start position. The `loads`
line (`PerfLog.line`, not a `phase=` mark) totals this item's HTTP loads --
count from `onLoadStarted`, bytes from the bandwidth meter's samples (a
progressive load completes only at end of file, so `onLoadCompleted` has
nothing by the first frame) -- logged once alongside `firstFrame`.

The reference target-device trace that established this ordering measured a
baseline of 89–93ms from `viewModel.start` to `exo.prepare`. The final sequence
measured 17–37ms (28ms median), about a 70% median reduction. End-to-end
`playback.click` to `exo.prepare` fell from 118–122ms to 41–64ms (53ms median),
about a 56% median reduction. Settings are read concurrently with playback
negotiation under the same structured-cancellation scope; this removes a
second IO-to-main resumption without weakening session consistency.

### Detail push timeline

Each push onto (or sibling-replace of) a `Screen.Detail` entry emits a
one-shot timeline, tagged `detail.<phase>`:

```
perf startup phase=detail.push atMs=3000
perf startup phase=detail.firstDraw ms=14.20
perf startup phase=detail.focus ms=22.50
perf startup phase=detail.backdrop ms=180.30
perf startup phase=detail.poster ms=210.75
```

- `detail.push` -- the only `atMs=` line in this group, logged from
  `MainActivity` at the moment `Screen.Detail` is pushed or sibling-replaced
  (never from a Back pop revealing one already on the stack) and the
  reference point every other line below measures its `ms=` against.
- `detail.firstDraw` -- the first pre-draw of `DetailScreen`'s own content
  for this push.
- `detail.focus` -- the moment initial focus lands (the seed/restore
  target -- primary pill, season chip, or the `···` door, whichever this
  push seeds).
- `detail.poster` -- the hero poster's first successful Coil decode
  (Series/Movie only; Episode has no poster figure).
- `detail.backdrop` -- the header backdrop's first successful Coil decode,
  on every item type.

A second push always measures from *its own* `detail.push`, never a stale
one left over from the previous screen. Like `activity.firstFrame`, these
are already durations (`ms=`), not `atMs=` timestamps.

### `imageUrl` (per-card art URL formatting)

`CoreGateway.imageUrl` is a plain synchronous FFI call, invoked once per
visible card -- Home's card grid can call it dozens of times a second while
scrolling, so it gets a count-only accumulator instead of a per-call line:

```
perf ffi.imageUrl count=214 success=214 avgMs=0.31 maxMs=1.02
perf ffi.imageUrl outlier ms=6.77 id=a1b2c3d4
```

- The `count=...` line is a rolled-up summary, flushed at most once every
  10 seconds (and only while calls are still happening -- a `count=0`
  window logs nothing).
- The `outlier ms=... id=...` line fires immediately, standalone, whenever
  a single call takes more than 5ms (this call should be sub-millisecond;
  anything past 5ms is a real anomaly, not normal variance). `id` is the
  item id the card art was requested for -- never a title or URL.

### Image loads (Coil)

```
perf image.load count=42 success=41 avgMs=118.6 maxMs=980.12 bytes=6291456
```

One rolled-up summary per ~10-second window, from a Coil `EventListener`
attached to the app's `ImageLoader`. Deliberately count-only, no outlier
line and no per-image detail -- a single slow network image load is normal,
not an anomaly, so logging one line per image would just be spam. `bytes`
is the summed `Bitmap.byteCount` of every successfully decoded image in the
window (omitted entirely if nothing decoded that window); it's a decode-time
field read, not a recomputation, so it doesn't add real cost.

Both accumulators above self-register with `PerfLog` on construction, and
`MainActivity`'s frame-metrics tick calls `PerfLog.flushAllIfDue()` every
10 seconds alongside its own `perf frames` summary. This is what flushes a
burst that ends before its own 10-second window closes -- e.g. a grid
scroll that stops mid-window -- since a `PerfAccumulator`'s only other
flush trigger is a `record()` call that, by then, is never going to arrive.

### Frame jank

```
perf frames count=587 janky=3 p50Ms=8.3 p90Ms=15.1
```

One line every 10 seconds while `MainActivity` is resumed and perf logging
is enabled (API 24+ only -- `Window.OnFrameMetricsAvailableListener`, guarded
by a `Build.VERSION` check since this app's minSdk is 23). `count` is frames
observed in the window; `janky` is how many exceeded ~16.7ms (a single
missed 60Hz vsync); `p50Ms`/`p90Ms` are bucketed-histogram percentile
estimates (±1ms resolution) of total frame duration. Never a per-frame
line, and the summary itself is computed and logged from the frame-metrics
callback's own background thread (a dedicated `HandlerThread`), never the
main thread.

### Profile status

```
perf profile status=COMPILED_WITH_PROFILE code=1 compiled=true enqueued=true
```

Once per process, requested from the first `MainActivity.onResume` with
logging enabled and landing a few seconds later (ProfileInstaller's own
delayed startup work runs first, on its background thread): `androidx.profileinstaller.ProfileVerifier`'s verdict on whether
the embedded Baseline Profile (`app/src/main/baseline-prof.txt`, merged
with the profiles Compose/Coil/lifecycle ship) was actually compiled into
the installed app. `status` is the `ProfileVerifier.CompilationStatus`
result code by name (`code` is the raw int; `ProfileStatus.kt` holds the
mapping), `compiled` mirrors `isCompiledWithProfile`, `enqueued` mirrors
`hasProfileEnqueuedForCompilation`.

What to expect on a device:

- `./build.sh install` (APK + `.dm` via `adb install-multiple`) -- ART
  compiles against the profile during installation. `adb shell dumpsys
  package dexopt` shows `[status=speed-profile] [reason=install-dm]` for
  the app immediately, and this line reads `COMPILED_WITH_PROFILE` on the
  first launch (`enqueued=true` there is expected: ProfileInstaller still
  stages its own copy for the next background dexopt, which is a no-op
  merge). Verified on a reference target device: `cmd package
  dump-profiles --dump-classes-and-methods tv.jellybeam` showed the installed
  reference profile matching the build's combined profile rule-for-rule.
- Plain `adb install` / file-manager sideload -- the first launch reads
  `PROFILE_ENQUEUED_FOR_COMPILATION`: ProfileInstaller has copied the
  profile into ART's reference-profile location and the next background
  dexopt (the platform's idle/charging job, typically overnight on a TV)
  compiles it. To force that without waiting:

  ```
  adb shell cmd package compile -r bg-dexopt tv.jellybeam
  ```

  then relaunch; the next line reads `COMPILED_WITH_PROFILE`.
- `COMPILED_WITH_PROFILE_NON_MATCHING` means the on-disk odex came from a
  profile for a different build (an in-place update whose old odex is still
  live); the next bg-dexopt replaces it.
- `ERROR_NO_PROFILE_EMBEDDED` means the APK itself has no
  `assets/dexopt/baseline.prof` -- a packaging regression; check with
  `unzip -l app-release.apk | grep dexopt`. `NO_PROFILE` means the APK has
  one but ART holds no profile for the app (ProfileInstaller hasn't run
  yet, or the device is below API 28).

#### Measured: wildcard profile vs full AOT vs JIT-cold

Same release build, same reference target device (API 34, 32-bit
userspace), three dexopt states, five cold launches each (`am force-stop`,
launcher intent, 9s) followed by one scripted D-pad pass over Home (two
rounds of row moves plus shelf left/right moves, 25ms apart). Frame
figures are the `perf frames` summaries over that pass, count-weighted.

| state | `activity.firstFrame` median (min–max) | `core.ready` median | frames | janky | p50 | p90 |
|---|---|---|---|---|---|---|
| Baseline Profile via `.dm` (`speed-profile`, install-dm) | 83ms (82–110) | 53ms | 374 | 39% | 13.7ms | 31.5ms |
| `cmd package compile -m speed -f` (everything AOT) | 134ms (123–141) | 62ms | 315 | 47% | 16.7ms | 33.1ms |
| plain `adb install`, first launches (`verify`, JIT only) | 99ms (94–145) | 65ms | 284 | 63% | 23.2ms | 53.7ms |

Reading it: the profile build matches full AOT on browse frame times
(both roughly half the JIT-cold p90) and beats it on cold start by ~50ms
-- full `speed` compiles every method of every library into one large
odex, and paging that in costs more at launch than it saves. The
JIT-cold row is what a sideloading user gets on day one before the
overnight dexopt; the top row is what they get after it (or immediately
with `./build.sh install`). Fewer frames in the slower rows is the same
key sequence producing fewer, longer frames.

Media3/`org.jellyfin.media3`/OkHttp/Okio are `HP` (hot + post-startup, no
`L` class-load line) rather than `HSPL`, since the player is first used
~3s after core-ready and shouldn't be paged in as a startup class.

The profile is hand-authored wildcard rules over the app's own packages,
the uniffi/JNA bridge, Media3, OkHttp, and the Kotlin stdlib -- the same
coverage the previous manual `cmd package compile -m speed -f tv.jellybeam`
step gave (that command still works and remains a strict superset; it is
no longer part of the install ritual). There is no Macrobenchmark
generator module: the build container has no emulator or managed device,
and a wildcard over a small app's own code is maintenance-free across
renames where a generated method list silently rots. A narrower captured
profile could only trim cold start further (smaller odex), never browse
frame times (those already match full AOT above) -- deliberately not
pursued; if cold start ever becomes the target, the capture path is
`cmd package dump-profiles --dump-classes-and-methods` against a
non-minified build (works non-rooted on API 33+).

## Worked example: reading one session

A capture from cold launch through browsing Home, opening a library, and
starting playback might look like (trimmed, illustrative):

```
perf startup phase=application.onCreate.start atMs=1200
perf startup phase=application.onCreate.end atMs=1206
perf startup phase=activity.onCreate atMs=1208
perf startup phase=core.ready atMs=1850
perf startup phase=activity.firstFrame ms=430.00
perf section=ffi.restoreSession ms=3.10
perf section=ffi.openMirror ms=12.44
perf section=ffi.getSettings ms=0.88
perf section=ffi.views ms=1.02
perf section=ffi.homeSnapshot ms=9.77
perf ffi.imageUrl count=48 success=48 avgMs=0.28 maxMs=0.91
perf image.load count=48 success=48 avgMs=142.3 maxMs=610.90 bytes=7340032
perf frames count=598 janky=1 p50Ms=7.8 p90Ms=12.4
perf section=ffi.children ms=6.31
perf ffi.imageUrl count=36 success=36 avgMs=0.25 maxMs=0.60
perf image.load count=36 success=36 avgMs=88.1 maxMs=310.20 bytes=5505024
perf frames count=601 janky=0 p50Ms=7.1 p90Ms=10.9
perf section=ffi.preparePlayback ms=63.40
```

Reading this: the app was drawing its first frame ~430ms after
`MainActivity.onCreate`, well before `core.ready` at 1850ms (relative to the
same `atMs` clock as `application.onCreate.start` at 1200ms, i.e. the mirror
finished opening ~650ms after `Application.onCreate` began) -- consistent
with `AppGraph.init`'s doc comment: the UI shows up before `JellybeamCore`
construction finishes, and the first FFI call (`restoreSession`) is what
actually waits on it. Home's initial load cost ~9.8ms of FFI time plus 48
image loads averaging 142ms each (the disk/memory cache warms up: the next
screen's 36 loads average 88ms). Frame summaries stayed comfortably under
one janky frame per ~600, and `preparePlayback` -- the one call every
playback start pays -- came in at 63ms.
