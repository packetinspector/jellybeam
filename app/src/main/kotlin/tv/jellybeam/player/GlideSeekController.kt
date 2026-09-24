package tv.jellybeam.player

import uniffi.jellybeam_core.GlideClamp
import uniffi.jellybeam_core.GlideDirection
import uniffi.jellybeam_core.GlideOutcome
import uniffi.jellybeam_core.GlidePhase
import uniffi.jellybeam_core.GlidePreview
import uniffi.jellybeam_core.GlideSeek
import uniffi.jellybeam_core.TrickplayTileFfi

/**
 * Hold-to-seek (`docs/feature-dev/PRD-hold-to-seek.md`): pure-Kotlin adapter over the Rust
 * decision half (`uniffi.jellybeam_core.GlideSeek`). Holds no decision logic; plumbs keys, ticks,
 * seeks, and the render-facing [GlideSurfaceState].
 */
interface GlideMachine {
    fun keyDown(direction: GlideDirection, positionBeforeMs: Long, tapTargetMs: Long, nowMs: Long): GlideOutcome

    /** One Rust call per tick (PRD §9); returns the post-tick preview directly. */
    fun tick(nowMs: Long): GlidePreview

    fun keyUp(nowMs: Long): GlideOutcome
    fun back(nowMs: Long): GlideOutcome

    /** Force `Idle` from any phase (PRD §4.1/§5). */
    fun reset()

    /** Swaps in chapter starts that arrived after this gesture began, without rebuilding it. */
    fun setChapterStarts(chapterStartsMs: List<Long>)

    fun preview(): GlidePreview
}

/** Production [GlideMachine]; converts Kotlin [Long] ms to the FFI's `ULong` at the boundary. */
class UniffiGlideMachine(private val seek: GlideSeek) : GlideMachine {
    override fun keyDown(direction: GlideDirection, positionBeforeMs: Long, tapTargetMs: Long, nowMs: Long): GlideOutcome =
        seek.keyDown(direction, positionBeforeMs.toULong(), tapTargetMs.toULong(), nowMs.toULong())

    override fun tick(nowMs: Long): GlidePreview = seek.tick(nowMs.toULong())

    override fun keyUp(nowMs: Long): GlideOutcome = seek.keyUp(nowMs.toULong())

    override fun back(nowMs: Long): GlideOutcome = seek.back(nowMs.toULong())

    override fun reset() = seek.reset()

    override fun setChapterStarts(chapterStartsMs: List<Long>) = seek.setChapterStarts(chapterStartsMs.map { it.toULong() })

    override fun preview(): GlidePreview = seek.preview()
}

/** [PlaybackScreen]'s wiring into [PlaybackViewModel]/trickplay; an interface for test fakes. */
interface GlideHost {
    /** The true playback position (PRD §5.1's played fill never advances to the target). */
    fun positionMs(): Long

    /** Ordinary hidden-OSD one-interval seek (PRD §4.2); also fed to [GlideMachine.keyDown]. */
    fun tapSeek(direction: GlideDirection): Long

    /** Exactly one `seekTo` per glide (PRD §4.5/§4.3). */
    fun commitSeek(targetMs: Long, endClamped: Boolean)

    /** Still-watching guard input (PRD §4.7). */
    fun noteInput()

    /**
     * Dwell-gated trickplay sample (docs/12 §11, Rust `trickplay_glide_sample`): the tile under
     * [targetMs] when a sample is due, `null` while the dwell since [lastSampleMs] still runs or
     * with no manifest. A non-null return is also the host's cue to start showing that tile.
     */
    fun sampleTile(targetMs: Long, nowMs: Long, lastSampleMs: Long?, direction: GlideDirection): TrickplayTileFfi?

    /** Sheet prefetch plan for a glide at [rate] media-seconds per real second heading
     * [direction]; called once per sample, never per tick. */
    fun wantSheets(targetMs: Long, rate: Int, direction: GlideDirection)

    /** Gesture start (from idle or by reversal): the previous gesture's tile must not linger,
     * and the tile under [tapTargetMs] is primed now so it is decoded by glide entry. */
    fun previewStart(tapTargetMs: Long, direction: GlideDirection)

    /** Release: the committed [targetMs]'s tile, biased for [direction], jumps every queue. */
    fun releaseTarget(targetMs: Long, direction: GlideDirection)
}

/** Everything the glide surface (PRD §5) needs to render one frame. */
data class GlideSurfaceState(
    val phase: GlidePhase,
    val direction: GlideDirection?,
    val targetMs: Long,
    val deltaMs: Long,
    val multiplier: Int,
    val clamp: GlideClamp,
    val chapterIndex: Int?,
    val tile: TrickplayTileFfi?,
)

/**
 * One hold-to-seek gesture's Kotlin side (PRD §5). [surface] is non-null only while
 * [lastPreview] is `GLIDING`/`PERSISTING`, refreshed synchronously so callers see the cut
 * immediately. [tick] makes exactly one [GlideMachine.tick] call per invocation (PRD §9).
 */
class GlideSeekController(
    private val machine: GlideMachine,
    private val host: GlideHost,
    private val clock: Clock = Clock.SYSTEM,
) {
    var surface: GlideSurfaceState? = null
        private set

    /** docs/12 §11: when the last dwell-gated sample was taken; reset when invisible. */
    private var lastSampleMs: Long? = null
    private var lastTile: TrickplayTileFfi? = null

    /** Cached last-known preview (see class doc); seeded `Idle` locally, skipping an FFI call. */
    private var lastPreview: GlidePreview = IDLE_PREVIEW

    /** PRD §4.3: direction that started the hold cycle; another direction's [onKeyUp] is stale. */
    private var cycleDirection: GlideDirection? = null

    val isGliding: Boolean get() = lastPreview.phase == GlidePhase.GLIDING

    /** Same as [needsTicks]; named for callers outside the tick loop (e.g. chapter-skip gate). */
    val isActive: Boolean get() = needsTicks
    val needsTicks: Boolean get() = lastPreview.phase != GlidePhase.IDLE

    /**
     * PRD §4.1/§4.3/§4.5: no-op while the OSD is visible. A direction reversal commits the
     * in-progress glide first, so `host.tapSeek` runs against the post-commit position.
     * [GlideHost.tapSeek] fires before [GlideMachine.keyDown] (tap latency, PRD §4.2).
     */
    fun onKeyDown(direction: GlideDirection, osdVisible: Boolean): Boolean {
        if (osdVisible) return false
        val now = clock.nowMs()

        val current = machine.preview()
        val reversal = current.phase == GlidePhase.GLIDING && current.direction != direction
        if (reversal) applyOutcome(machine.keyUp(now), current.direction ?: direction)

        val before = host.positionMs()
        val target = host.tapSeek(direction)
        if (current.phase == GlidePhase.IDLE || reversal) host.previewStart(target, direction)
        applyOutcome(machine.keyDown(direction, before, target, now), direction)
        cycleDirection = direction
        lastPreview = machine.preview()
        refreshSurface()
        return true
    }

    /**
     * PRD §4.5: release commits exactly one seek; `true` only when a tap or glide was in
     * progress and [direction] matches [cycleDirection] (the other key's release is stale).
     */
    fun onKeyUp(direction: GlideDirection): Boolean {
        if (cycleDirection != direction) return false
        val now = clock.nowMs()
        val phase = lastPreview.phase
        if (phase != GlidePhase.TAPPED && phase != GlidePhase.GLIDING) return false

        applyOutcome(machine.keyUp(now), direction)
        cycleDirection = null
        host.noteInput()
        lastPreview = machine.preview()
        refreshSurface()
        return true
    }

    /** PRD §4.6: cancels an active glide; no-op (`false`) once already
     * committed/persisting or idle. */
    fun cancel(): Boolean {
        val outcome = machine.back(clock.nowMs())
        if (outcome !is GlideOutcome.Cancelled) return false
        cycleDirection = null
        lastPreview = machine.preview()
        surface = null
        lastSampleMs = null
        lastTile = null
        host.noteInput()
        return true
    }

    /** PRD §4.1/§5: ends the gesture from any phase once the OSD is revealed; no-op if idle. */
    fun resetIfActive(): Boolean {
        if (lastPreview.phase == GlidePhase.IDLE) return false
        machine.reset()
        cycleDirection = null
        lastPreview = machine.preview()
        surface = null
        lastSampleMs = null
        lastTile = null
        return true
    }

    /** PRD §7: forwards late chapter starts straight to the machine, never rebuilding it. */
    fun setChapterStarts(chapterStartsMs: List<Long>) {
        machine.setChapterStarts(chapterStartsMs)
    }

    /** Advances the machine and refreshes [surface]; called on a fixed cadence
     * while [needsTicks]. */
    fun tick() {
        lastPreview = machine.tick(clock.nowMs())
        refreshSurface()
    }

    private fun applyOutcome(outcome: GlideOutcome, direction: GlideDirection) {
        if (outcome is GlideOutcome.Commit) {
            val targetMs = outcome.targetMs.toLong()
            host.commitSeek(targetMs, outcome.endClamped)
            host.releaseTarget(targetMs, direction)
        }
    }

    /**
     * Rebuilds [surface] from [lastPreview]. While gliding the tile is re-sampled only when the
     * host's dwell allows (docs/12 §11), and the sheet want-list is refreshed on each sample; the
     * persist phase keeps the last tile (the release tile was prioritised by [applyOutcome]). A
     * new [GlideSurfaceState] is allocated only when it differs field-for-field, so an unchanged
     * preview triggers no recomposition.
     */
    private fun refreshSurface() {
        val preview = lastPreview
        val visible = preview.phase == GlidePhase.GLIDING || preview.phase == GlidePhase.PERSISTING
        if (!visible) {
            surface = null
            lastSampleMs = null
            lastTile = null
            return
        }

        val targetMs = preview.targetMs.toLong()
        val direction = preview.direction
        if (preview.phase == GlidePhase.GLIDING && direction != null) {
            val now = clock.nowMs()
            val sampled = host.sampleTile(targetMs, now, lastSampleMs, direction)
            if (sampled != null) {
                lastSampleMs = now
                lastTile = sampled
                host.wantSheets(targetMs, preview.multiplier.toInt(), direction)
            }
        }

        val multiplier = preview.multiplier.toInt()
        val chapterIndex = preview.chapterIndex?.toInt()
        val current = surface
        val unchanged = current != null &&
            current.phase == preview.phase &&
            current.direction == preview.direction &&
            current.targetMs == targetMs &&
            current.deltaMs == preview.deltaMs &&
            current.multiplier == multiplier &&
            current.clamp == preview.clamp &&
            current.chapterIndex == chapterIndex &&
            current.tile == lastTile
        if (!unchanged) {
            surface = GlideSurfaceState(
                phase = preview.phase,
                direction = preview.direction,
                targetMs = targetMs,
                deltaMs = preview.deltaMs,
                multiplier = multiplier,
                clamp = preview.clamp,
                chapterIndex = chapterIndex,
                tile = lastTile,
            )
        }
    }

    private companion object {
        /** A freshly constructed `GlideSeek` is always `Idle`. */
        val IDLE_PREVIEW = GlidePreview(
            phase = GlidePhase.IDLE,
            direction = null,
            targetMs = 0uL,
            deltaMs = 0L,
            tier = 0u,
            multiplier = 0u,
            clamp = GlideClamp.NONE,
            chapterIndex = null,
        )
    }
}
