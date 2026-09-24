package tv.jellybeam.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.jellybeam_core.GlideClamp
import uniffi.jellybeam_core.GlideDirection
import uniffi.jellybeam_core.GlideOutcome
import uniffi.jellybeam_core.GlidePhase
import uniffi.jellybeam_core.GlidePreview
import uniffi.jellybeam_core.TrickplayTileFfi

/** A [Clock] a test can advance deterministically. */
private class FakeGlideClock(var nowMs: Long = 0L) : Clock {
    override fun nowMs(): Long = nowMs
}

private fun idlePreview() = GlidePreview(
    phase = GlidePhase.IDLE,
    direction = null,
    targetMs = 0uL,
    deltaMs = 0L,
    tier = 0u,
    multiplier = 0u,
    clamp = GlideClamp.NONE,
    chapterIndex = null,
)

/** A scripted [GlideMachine]: records calls and returns the queued outcome/preview, no real Rust
 * state machine involved.
 */
private class FakeGlideMachine(private val log: MutableList<String> = mutableListOf()) : GlideMachine {
    data class RecordedKeyDown(val direction: GlideDirection, val positionBeforeMs: Long, val tapTargetMs: Long, val nowMs: Long)

    val keyDownCalls = mutableListOf<RecordedKeyDown>()
    val tickCalls = mutableListOf<Long>()
    val keyUpCalls = mutableListOf<Long>()
    val backCalls = mutableListOf<Long>()
    var resetCallCount: Int = 0
        private set
    val setChapterStartsCalls = mutableListOf<List<Long>>()
    var previewCallCount: Int = 0
        private set

    var previewValue: GlidePreview = idlePreview()
    var keyDownOutcome: GlideOutcome = GlideOutcome.None
    var keyUpOutcome: GlideOutcome = GlideOutcome.None
    var backOutcome: GlideOutcome = GlideOutcome.None

    override fun keyDown(direction: GlideDirection, positionBeforeMs: Long, tapTargetMs: Long, nowMs: Long): GlideOutcome {
        log += "keyDown"
        keyDownCalls += RecordedKeyDown(direction, positionBeforeMs, tapTargetMs, nowMs)
        return keyDownOutcome
    }

    override fun tick(nowMs: Long): GlidePreview {
        log += "tick"
        tickCalls += nowMs
        return previewValue
    }

    override fun keyUp(nowMs: Long): GlideOutcome {
        log += "keyUp"
        keyUpCalls += nowMs
        return keyUpOutcome
    }

    override fun back(nowMs: Long): GlideOutcome {
        log += "back"
        backCalls += nowMs
        return backOutcome
    }

    override fun reset() {
        log += "reset"
        resetCallCount++
    }

    override fun setChapterStarts(chapterStartsMs: List<Long>) {
        log += "setChapterStarts"
        setChapterStartsCalls += chapterStartsMs
    }

    override fun preview(): GlidePreview {
        previewCallCount++
        return previewValue
    }
}

/** A scripted [GlideHost]: records every call, same shape as [FakeGlideMachine]. */
private class FakeGlideHost(private val log: MutableList<String> = mutableListOf()) : GlideHost {
    var positionMsValue: Long = 0L
    val tapSeekCalls = mutableListOf<GlideDirection>()
    var tapSeekResult: Long = 0L
    val commitSeekCalls = mutableListOf<Pair<Long, Boolean>>()
    var noteInputCallCount: Int = 0
    val sampleTileCalls = mutableListOf<Triple<Long, Long, Long?>>()
    val sampleTileDirections = mutableListOf<GlideDirection>()
    var sampleTileResult: TrickplayTileFfi? = null
    val wantSheetsCalls = mutableListOf<Triple<Long, Int, GlideDirection>>()
    val previewStartCalls = mutableListOf<Long>()
    val previewStartDirections = mutableListOf<GlideDirection>()
    val releaseTargetCalls = mutableListOf<Long>()
    val releaseTargetDirections = mutableListOf<GlideDirection>()

    override fun positionMs(): Long = positionMsValue

    override fun tapSeek(direction: GlideDirection): Long {
        log += "tapSeek"
        tapSeekCalls += direction
        return tapSeekResult
    }

    override fun commitSeek(targetMs: Long, endClamped: Boolean) {
        log += "commitSeek"
        commitSeekCalls += targetMs to endClamped
    }

    override fun noteInput() {
        noteInputCallCount++
    }

    override fun sampleTile(targetMs: Long, nowMs: Long, lastSampleMs: Long?, direction: GlideDirection): TrickplayTileFfi? {
        sampleTileCalls += Triple(targetMs, nowMs, lastSampleMs)
        sampleTileDirections += direction
        return sampleTileResult
    }

    override fun wantSheets(targetMs: Long, rate: Int, direction: GlideDirection) {
        wantSheetsCalls += Triple(targetMs, rate, direction)
    }

    override fun previewStart(tapTargetMs: Long, direction: GlideDirection) {
        previewStartCalls += tapTargetMs
        previewStartDirections += direction
    }

    override fun releaseTarget(targetMs: Long, direction: GlideDirection) {
        releaseTargetCalls += targetMs
        releaseTargetDirections += direction
    }
}

private fun sampleTile(imageIndex: UInt = 0u) = TrickplayTileFfi(imageIndex = imageIndex, x = 0u, y = 0u)

class GlideSeekControllerTest {

    private val log = mutableListOf<String>()
    private val machine = FakeGlideMachine(log)
    private val host = FakeGlideHost(log)
    private val clock = FakeGlideClock()
    private val controller = GlideSeekController(machine, host, clock)

    @Test
    fun `osdVisible makes onKeyDown a no-op that never touches the machine or host`() {
        val consumed = controller.onKeyDown(GlideDirection.FORWARD, osdVisible = true)

        assertFalse(consumed)
        assertTrue("machine must be untouched", machine.keyDownCalls.isEmpty())
        assertTrue(host.tapSeekCalls.isEmpty())
    }

    @Test
    fun `onKeyDown calls host tapSeek before machine keyDown and returns true`() {
        machine.previewValue = idlePreview()
        host.tapSeekResult = 110_000L

        val consumed = controller.onKeyDown(GlideDirection.FORWARD, osdVisible = false)

        assertTrue(consumed)
        assertEquals(listOf("tapSeek", "keyDown"), log)
        assertEquals(1, machine.keyDownCalls.size)
        assertEquals(110_000L, machine.keyDownCalls.single().tapTargetMs)
    }

    @Test
    fun `keyUp with a Commit outcome issues exactly one commitSeek`() {
        machine.previewValue = idlePreview().copy(phase = GlidePhase.GLIDING, direction = GlideDirection.FORWARD)
        controller.onKeyDown(GlideDirection.FORWARD, osdVisible = false)
        machine.keyUpOutcome = GlideOutcome.Commit(targetMs = 250_000uL, endClamped = false)

        val consumed = controller.onKeyUp(GlideDirection.FORWARD)

        assertTrue(consumed)
        assertEquals(listOf(250_000L to false), host.commitSeekCalls)
    }

    @Test
    fun `keyUp with outcome None issues no commit`() {
        machine.previewValue = idlePreview().copy(phase = GlidePhase.TAPPED)
        controller.onKeyDown(GlideDirection.BACK, osdVisible = false)
        machine.keyUpOutcome = GlideOutcome.None

        val consumed = controller.onKeyUp(GlideDirection.BACK)

        assertTrue("a tap release is still consumed", consumed)
        assertTrue(host.commitSeekCalls.isEmpty())
    }

    @Test
    fun `keyUp forwards an end-clamped commit's endClamped flag`() {
        machine.previewValue = idlePreview().copy(phase = GlidePhase.GLIDING, direction = GlideDirection.FORWARD)
        controller.onKeyDown(GlideDirection.FORWARD, osdVisible = false)
        machine.keyUpOutcome = GlideOutcome.Commit(targetMs = 999uL, endClamped = true)

        controller.onKeyUp(GlideDirection.FORWARD)

        assertEquals(listOf(999L to true), host.commitSeekCalls)
    }

    /** PRD §4.3 reversal: a key-up for a direction that didn't start the current cycle is stale and
     * must not touch the machine.
     */
    @Test
    fun `keyUp for a direction other than the one that started the cycle is ignored`() {
        machine.previewValue = idlePreview().copy(phase = GlidePhase.GLIDING, direction = GlideDirection.FORWARD)
        controller.onKeyDown(GlideDirection.FORWARD, osdVisible = false)

        val consumed = controller.onKeyUp(GlideDirection.BACK)

        assertFalse(consumed)
        assertTrue("the machine's keyUp must never be called for a stale release", machine.keyUpCalls.isEmpty())
    }

    /** PRD §4.3: reversal (hold Right, press Left) commits the old cycle via `machine.keyUp`;
     * Right's later release must not also end Left's new cycle.
     */
    @Test
    fun `a reversal's own commit does not let the old key's later release touch the machine again`() {
        machine.previewValue = idlePreview().copy(phase = GlidePhase.GLIDING, direction = GlideDirection.FORWARD)
        controller.onKeyDown(GlideDirection.FORWARD, osdVisible = false)

        // previewValue stays GLIDING/FORWARD; reversal detection reads the machine's pre-reversal
        // state.
        machine.keyUpOutcome = GlideOutcome.Commit(targetMs = 1_000uL, endClamped = false)
        controller.onKeyDown(GlideDirection.BACK, osdVisible = false)

        val keyUpCallsAfterReversal = machine.keyUpCalls.size
        assertEquals("the reversal itself must call machine.keyUp once", 1, keyUpCallsAfterReversal)

        val staleRelease = controller.onKeyUp(GlideDirection.FORWARD)
        assertFalse(staleRelease)
        assertEquals("Right's stale release must not call the machine again", keyUpCallsAfterReversal, machine.keyUpCalls.size)

        machine.keyUpOutcome = GlideOutcome.Commit(targetMs = 2_000uL, endClamped = false)
        val liveRelease = controller.onKeyUp(GlideDirection.BACK)
        assertTrue(liveRelease)
        assertEquals(keyUpCallsAfterReversal + 1, machine.keyUpCalls.size)
    }

    @Test
    fun `cancel on a Cancelled outcome issues no commit and clears the surface`() {
        machine.previewValue = idlePreview().copy(phase = GlidePhase.GLIDING)
        controller.tick()

        machine.backOutcome = GlideOutcome.Cancelled
        val cancelled = controller.cancel()

        assertTrue(cancelled)
        assertNull(controller.surface)
        assertTrue(host.commitSeekCalls.isEmpty())
        assertEquals(1, host.noteInputCallCount)
    }

    @Test
    fun `a direction reversal mid-glide commits via keyUp before the new tap seek`() {
        machine.previewValue = idlePreview().copy(phase = GlidePhase.GLIDING, direction = GlideDirection.FORWARD)
        machine.keyUpOutcome = GlideOutcome.Commit(targetMs = 150_000uL, endClamped = false)

        controller.onKeyDown(GlideDirection.BACK, osdVisible = false)

        val commitIndex = log.indexOf("commitSeek")
        val tapSeekIndex = log.indexOf("tapSeek")
        assertTrue("keyUp must run", log.contains("keyUp"))
        assertTrue("commit must precede the new tap seek", commitIndex in 0 until tapSeekIndex)
        assertEquals(listOf(150_000L to false), host.commitSeekCalls)
    }

    @Test
    fun `every gliding tick offers a sample, lastSampleMs advances only when the host takes one`() {
        clock.nowMs = 1_000L
        host.sampleTileResult = null
        machine.previewValue = idlePreview().copy(phase = GlidePhase.GLIDING, targetMs = 500uL, direction = GlideDirection.FORWARD, multiplier = 30u)
        controller.tick()
        clock.nowMs = 1_033L
        host.sampleTileResult = sampleTile(1u)
        controller.tick()
        clock.nowMs = 1_066L
        host.sampleTileResult = null
        controller.tick()

        assertEquals(listOf(Triple(500L, 1_000L, null), Triple(500L, 1_033L, null), Triple(500L, 1_066L, 1_033L)), host.sampleTileCalls)
        assertEquals(sampleTile(1u), controller.surface?.tile)
    }

    @Test
    fun `the sheet want-list is refreshed once per taken sample, never per tick`() {
        host.sampleTileResult = sampleTile(2u)
        machine.previewValue = idlePreview().copy(phase = GlidePhase.GLIDING, targetMs = 900uL, direction = GlideDirection.BACK, multiplier = 120u)
        controller.tick()
        host.sampleTileResult = null
        controller.tick()
        controller.tick()

        assertEquals(listOf(Triple(900L, 120, GlideDirection.BACK)), host.wantSheetsCalls)
    }

    @Test
    fun `persisting keeps the last tile without sampling`() {
        host.sampleTileResult = sampleTile(3u)
        machine.previewValue = idlePreview().copy(phase = GlidePhase.GLIDING, targetMs = 900uL, direction = GlideDirection.FORWARD, multiplier = 6u)
        controller.tick()
        machine.previewValue = idlePreview().copy(phase = GlidePhase.PERSISTING, targetMs = 900uL)
        host.sampleTileResult = sampleTile(9u)
        controller.tick()

        assertEquals(1, host.sampleTileCalls.size)
        assertEquals(sampleTile(3u), controller.surface?.tile)
    }

    @Test
    fun `a commit hands the release target to the host after the seek`() {
        machine.previewValue = idlePreview().copy(phase = GlidePhase.GLIDING, direction = GlideDirection.FORWARD)
        machine.keyUpOutcome = GlideOutcome.Commit(targetMs = 4_200uL, endClamped = false)
        controller.onKeyDown(GlideDirection.FORWARD, osdVisible = false)
        controller.onKeyUp(GlideDirection.FORWARD)

        assertEquals(listOf(4_200L), host.releaseTargetCalls)
        assertEquals(listOf(GlideDirection.FORWARD), host.releaseTargetDirections)
    }

    /** docs/12 §11: a reversal commits the dying glide under ITS own direction, so the release
     * tile is biased the way that glide was travelling, not the new one. */
    @Test
    fun `a reversal releases the old glide under the old direction`() {
        machine.previewValue = idlePreview().copy(phase = GlidePhase.GLIDING, direction = GlideDirection.FORWARD)
        machine.keyUpOutcome = GlideOutcome.Commit(targetMs = 7_000uL, endClamped = false)
        controller.onKeyDown(GlideDirection.BACK, osdVisible = false)

        assertEquals(listOf(7_000L), host.releaseTargetCalls)
        assertEquals(listOf(GlideDirection.FORWARD), host.releaseTargetDirections)
        assertEquals(listOf(GlideDirection.BACK), host.previewStartDirections)
    }

    @Test
    fun `a key down from idle and a reversal mid-glide each start the preview at the tap target`() {
        machine.previewValue = idlePreview()
        host.tapSeekResult = 10_000L
        controller.onKeyDown(GlideDirection.FORWARD, osdVisible = false)
        machine.previewValue = idlePreview().copy(phase = GlidePhase.GLIDING, direction = GlideDirection.FORWARD)
        host.tapSeekResult = 90_000L
        controller.onKeyDown(GlideDirection.BACK, osdVisible = false)
        // A same-direction key down while gliding is neither: no restart.
        machine.previewValue = idlePreview().copy(phase = GlidePhase.GLIDING, direction = GlideDirection.BACK)
        controller.onKeyDown(GlideDirection.BACK, osdVisible = false)

        assertEquals(listOf(10_000L, 90_000L), host.previewStartCalls)
    }

    @Test
    fun `surface is null whenever the tick's phase is not GLIDING or PERSISTING`() {
        machine.previewValue = idlePreview().copy(phase = GlidePhase.TAPPED)

        controller.tick()

        assertNull(controller.surface)
    }

    // -- PRD §9 perf: one machine.tick call per tick, no extra FFI reads ----

    @Test
    fun `N ticks make exactly N machine tick calls and zero preview calls`() {
        machine.previewValue = idlePreview().copy(phase = GlidePhase.GLIDING, targetMs = 1_000uL)

        repeat(5) { controller.tick() }

        assertEquals(5, machine.tickCalls.size)
        assertEquals(0, machine.previewCallCount)
    }

    @Test
    fun `identical consecutive previews keep the same surface instance`() {
        machine.previewValue = idlePreview().copy(phase = GlidePhase.GLIDING, targetMs = 5_000uL, deltaMs = 5_000L, multiplier = 6u)

        controller.tick()
        val first = controller.surface
        controller.tick()
        val second = controller.surface

        assertTrue(first != null)
        assertSame("an unchanged preview must not allocate a new GlideSurfaceState", first, second)
    }

    @Test
    fun `a changed preview allocates a new surface instance`() {
        machine.previewValue = idlePreview().copy(phase = GlidePhase.GLIDING, targetMs = 5_000uL)
        controller.tick()
        val first = controller.surface

        machine.previewValue = idlePreview().copy(phase = GlidePhase.GLIDING, targetMs = 6_000uL)
        controller.tick()
        val second = controller.surface

        assertTrue(first !== second)
        assertEquals(6_000L, second!!.targetMs)
    }

    // -- PRD §4.1/§5: resetIfActive ------------------------------------------

    @Test
    fun `resetIfActive on a Tapped phase forces Idle -- a later keyUp is then a no-op`() {
        machine.previewValue = idlePreview().copy(phase = GlidePhase.TAPPED)
        controller.onKeyDown(GlideDirection.FORWARD, osdVisible = false)

        val reset = controller.resetIfActive()
        assertTrue(reset)
        assertEquals(1, machine.resetCallCount)

        machine.previewValue = idlePreview()
        val consumed = controller.onKeyUp(GlideDirection.FORWARD)
        assertFalse("a keyUp after reset must not commit", consumed)
        assertTrue(host.commitSeekCalls.isEmpty())
    }

    @Test
    fun `resetIfActive on a Persisting phase clears the surface`() {
        machine.previewValue = idlePreview().copy(phase = GlidePhase.PERSISTING, targetMs = 42_000uL)
        controller.tick()
        assertTrue(controller.surface != null)

        machine.previewValue = idlePreview()
        val reset = controller.resetIfActive()

        assertTrue(reset)
        assertNull(controller.surface)
    }

    @Test
    fun `resetIfActive on an already-idle machine is a no-op`() {
        machine.previewValue = idlePreview()

        val reset = controller.resetIfActive()

        assertFalse(reset)
        assertEquals(0, machine.resetCallCount)
    }

    // -- PRD §7: chapters forwarded without rebuilding -----------------------

    @Test
    fun `setChapterStarts forwards straight to the machine`() {
        controller.setChapterStarts(listOf(0L, 600_000L, 1_200_000L))

        assertEquals(listOf(listOf(0L, 600_000L, 1_200_000L)), machine.setChapterStartsCalls)
    }
}
