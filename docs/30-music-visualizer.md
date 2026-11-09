# 30 — Music visualizer

Spec of record for the music visualizer: a full-screen view opened from Now
Playing, and an optional style for the ambient screen (docs/29 §6). Code cites
this document by section.

## 1. Shape and idle cost

- Engine: Kotlin + OpenGL ES 3.0 in the `:visualizer` library module, which
  depends on nothing but the Kotlin stdlib, so it builds and JVM-tests without
  the Rust core. `:visualizer-lab` is a debug-only app (release disabled) that
  drives the engine from synthetic signals for the instrumented tests (§9).
- No Rust: the samples start in the JVM (Media3's audio output) and end in the
  JVM (GL calls); a native middle would add two crossings and a copy per frame
  for a CPU cost of tens of microseconds.
- A user who never opens the visualizer pays one volatile read and one counter
  add per audio buffer, and one volatile read per position read or pause (§2),
  nothing else: no thread, no GL context, no allocation, no timer.
- Video playback never carries the tap (`musicTap = false`); its audio path is
  the same as before the visualizer existed.

## 2. Tap, ring and sync

- **Tap point:** the music player's audio output wrapper
  (`PlausibleClockAudioOutput`, docs/18 §5.1) calls `PcmTap.offer` with the bytes
  the output consumed on each `write`. It sees the final PCM, float output
  included, and needs no audio reconfiguration. The buffer is read with
  absolute gets only; its position, limit and contents never change.
- **Gate:** with no listener, `offer` adds the byte count and returns. The
  count lets a later attach know which output frame comes next.
- **Encoded output:** a passthrough or offload output is `ENCODED`; nothing is
  written and the surface shows the still fallback (§7).
- **Ring:** about two seconds of float mono, left and right, indexed by output
  frame since the output was created or flushed. The output's own position
  (the guarded one, docs/18 §5.1) is the read cursor, so seeks, gapless joins
  and speed changes need no timestamp mapping.
- **Window:** the analysis window is centred on the output position,
  extrapolated from the last position report at the playback speed while the
  clock runs.
- **Events:** a flush or seek clears the ring (never pre-seek samples); the
  output's pause freezes the window at once (the tap hears it, rather than
  waiting for a position report); a reader that attaches while paused idles
  on its empty ring and is woken by the first buffer after play; a gapless join keeps the same output and frame count (no
  clear); a new output format clears and reconfigures; frames not yet written
  (underrun) read as silence.
- **Threads:** one writer (playback thread) and one reader (GL thread). The
  reader copies only the older half of the ring and rechecks the clear epoch
  and the write count afterwards, so a torn window is never returned.

## 3. Analysis

- Mono downmix (mean of all channels) for the spectrum; left and right kept
  for the XY scope.
- 1024-point real FFT, Hann window, window mean removed first (DC never shows).
- 64 log-spaced bands from 30 Hz to 16 kHz. A band is the largest bin inside
  it; a band narrower than a bin takes the magnitude interpolated at its
  centre. dB −70..0 maps to 0..1.
- Attack is instant; release falls 1.2 a second. Peak caps hold 0.5 s, then
  fall 1.5 a second, never below their band.
- Onsets run on every 512 samples played (the app feeds everything played
  since the last frame), not once per frame, so a 30 fps fallback detects
  exactly what 60 fps does. Each hop: spectral flux in three classes, kick
  (40–120 Hz), snare (150 Hz–2.5 kHz, flux-weighted) and hat (6–16 kHz), each
  against its own adaptive threshold with a refractory period; a hat-only hop
  never fires a kick. A frame publishes what its hops found (any onset, any
  beat tick, the largest flux); each onset sets a pulse that falls to 0 over
  0.25 s, and the kick keeps the v1 beat ages. An onset counts as on time from
  one frame before it is heard to 45 ms after (ITU-R BT.1359's "picture late"
  limit).
- Loudness: short (about 0.1 s) and long (about 3 s) RMS envelopes.
- Tempo (driven by the same hops): the onset envelope's autocorrelation over 60–200 BPM, with a prior
  against half- and double-time; it locks once confident, gives a beat phase
  and a beat tick, counts beats and a phrase every 16 beats, and unlocks after
  4 s of silence. Below −50 dB is silence: no onsets, and the next sound
  re-primes every threshold.
- The per-frame path allocates nothing.

## 4. Colour and flashes

- The visualizer surface is content, like video (brand §3): trails and
  anti-aliased edges are allowed, and its palettes may be multi-hue as long as
  every hue derives from the user's accent. There is no bloom or glow pass.
- Five families per accent, built in OKLCH: analogous (accent ±40°), triad,
  split complementary, fire (black → a deep hue → accent → warm near-white)
  and ice (black → accent +150° → the sheen). Each is a 128-position ×
  32-level table: level 0 is true black (#000000) everywhere, luminance rises
  strictly with level, the top reaches 7:1 contrast on black, every entry is
  in sRGB gamut, and the positions form a closed loop.
- The whole visualizer surface is true black, not NOTTE: the cleared layer,
  the composite background, crossfades, and the screen and ambient
  containers behind the GL surface. Idle pixels then emit no light on OLED
  and projector panels. The rest of the app stays NOTTE.
- The layer stores intensity and palette position; the composite looks the
  colour up, so every lit pixel is a palette colour. Overlay edges blend two
  palette colours by coverage.
- Each pattern picks a family. The palette position cycles slowly (one turn
  per 20–60 s, faster with energy) and steps a quarter turn on a phrase when
  the flash limiter allows.
- Flash limiter (WCAG 2.3.1), always on, no setting: a change of the frame's
  mean relative luminance above 0.10 is a flash, red-saturated ones counted
  the same, and at most 3 happen in any second; a transition is a
  same-direction swing past 0.10 over any number of frames. Before each
  composite, a 32×18 stats pass measures the mean luminance the frame would
  show at eight gains (0–4; kaleido fold included, square-root coded so dark
  frames keep precision) and the CPU reads it at once: a late reading lets a
  fast full-field swing through. The limiter picks the luminance to show
  (holding a refused swing 0.07 from where it began) and the gain nearest 1
  that shows it. Each kick strobe also asks first, and a refused one is
  softened.


## 5. Patterns

Ten, in Left/Right order (Bars first, Ripples last, wrapping); each picks a palette family.

| Pattern | Family | How it draws |
|---|---|---|
| Bars | analogous | 64 bars and peak caps over trails that rise and fade, colour walking across the bars |
| Radial | triad | 128 mirrored spokes on a ring that pulses on kicks; a slow swirl curls the trails |
| Waterfall | ice | 40 perspective history rows with a soft glow trail |
| Kaleidoscope | split | a waveform ring and band strokes in one wedge, warped, folded 6 ways |
| Swirl | triad | zoom-rotate feedback with the waveform ring injected; kicks launch sparks |
| Fire | fire | the spectrum burns along the bottom; heat rises, jitters and cools; kicks throw sparks |
| Phosphor | ice | an afterglowing XY / oscilloscope beam, swapping on each phrase |
| Superscope | split | 1024 points on a sphere, torus or helix (changing each phrase), radius by spectrum, with trails |
| Ridgeline | analogous | 64 stacked perspective ridges with hidden lines removed, the newest as an aurora |
| Ripples | ice | a 120×68 wave grid; kicks and snares drop ripples, shaded by slope |

- The stored key is the pattern's `key`; retired keys open their
  successor (§8). A fresh profile opens Swirl.
- Every pattern's passes, draws and fill are fixed and bounded by one table
  (`PatternSpecs.budget`, §6).
- Kick flashes use only the flash-limited kick pulse (§4).
- All motion follows audio time, so a paused frame is still.
- **Automatic changes** (on by default, Settings): the next pattern starts on
  a beat once 64 beats have passed with the tempo locked, or after 45 s
  without a lock, never in silence, with a 1.5 s crossfade on the shared
  layer (warps mix, both patterns draw, the palettes blend). Left/Right switch
  at once, clear the layer and restart the count. An automatic change shows
  the name but is not remembered.

## 6. Renderer and pacing

- A `SurfaceView` at window size (1080p on most boxes; never forced to 4K).
  The engine (GL thread, EGL context, vsync callback) exists from
  surfaceCreated to surfaceDestroyed, so Back, Home and screen-off end it.
- Feedback layer: two 480×270 RG8 textures (intensity, palette position)
  ping-pong. Each frame warps last frame through a 32×24 grid (zoom, rotate,
  swirl, translate, ripple, jitter, decay) or paints a per-pixel field, then
  draws the pattern's shapes into it (`MAX` blending). The composite upscales
  it bilinearly through the palette (a kaleidoscope pattern folds it 6 ways),
  and a crisp native overlay draws on top.
- Budget per frame (one table, `PatternSpecs.budget`): at most 3 low-res
  passes, 1 composite and the overlay, at most 8 draw calls, fill at most
  3 × 129 600 + 2 073 600 pixels; a crossfade may use twice the passes and
  draws. Everything the visualizer allocates on the GPU stays under 1 MB.
- Each pass reads its own slot of one uniform buffer; ripples step a
  120×68 wave grid first.
- Choreographer vsync at the display's rate (60 fps on most boxes); a miss is
  a gap over 1.5 periods of that rate, and after three consecutive misses it
  renders every other vsync for the rest of the session. Paused, it draws the
  frozen frame once and then requests no vsync until the clock runs again.

## 7. Screens and controls

- **Entry:** a Visualizer button on Now Playing opens it full screen. GLES 3.0
  missing: no button and no ambient option.
- **Keys:** Left/Right = previous/next pattern (wraps; remembered); Up/Down
  = next/previous track (the transport's own rule: Previous restarts first);
  Centre = play/pause; each acts on the first key down. Page/Channel keys
  change track through the music layer (docs/29 §9 Keys). Back closes it through the
  back dispatcher (so predictive back closes the overlay, not Now Playing)
  and focus returns to the Visualizer button; media keys pass through. It
  shows only while Now Playing is the top screen. On a switch the pattern name shows for 1.5 s (Martian
  Mono, GRIGIO, bottom left). Each open first shows the key hint
  "◀ ▶ PATTERN   ▲ ▼ TRACK" there for 2 s; a pattern name replaces it at
  once. The ambient visualizer shows no hint.
- **Still fallback:** passthrough or encoded output draws the flat bars at the
  floor and the line "Visualizer needs decoded audio" (Archivo 14 sp,
  PANNA-2).
- **Ambient:** with Settings "Ambient screen: Visualizer" the ambient screen
  draws the visualizer instead of the cover art, timed by the same owner
  (docs/29 §6); any key exits it, as today.

## 8. Settings

- `visualizer_pattern` (string key, default `swirl`), `visualizer_auto_change`
  (default on: "Change patterns automatically") and `ambient_style` (`cover`
  default / `visualizer`) in the settings record (docs/09). Kotlin resolves the
  keys: retired keys open their successor, one way (v1: scope, xy → phosphor;
  tunnel, spiral → swirl; pulse, starfield, ring, ribbon → superscope; v2,
  removed after UAT: plasma, tube → swirl), anything else unknown opens bars.
- The rows live last in Settings › Playback, under a Music header, with Normalize
  volume (docs/29 §1); the header shows only when one of its rows does.


## 9. Tests

- JVM (`:visualizer`): FFT accuracy, tones, sweep, edges, PCM formats,
  dynamics, beats, zero allocation, tap transparency and gate, ring sync and
  events, encoded detection, threading, palette, pattern geometry bounds,
  budgets, cycle.
- Emulator (`:visualizer-lab`, swiftshader): shader compile, goldens, pixel
  invariants (silence, tone column, kaleidoscope symmetry, palette-surface
  membership), flash transitions under a 3 Hz loud/silent strobe (a
  guards-off control is logged; no roster pattern exceeds 3/s even unguarded,
  so the limiter itself is pinned by JVM tests), crossfades, pacing, lifecycle leaks,
  CPU timing, sync against a real ExoPlayer, gapless, zero idle.
- App: the video factory carries no tap; settings round trip; the GLES 3.0 and
  encoded-fallback decisions.
