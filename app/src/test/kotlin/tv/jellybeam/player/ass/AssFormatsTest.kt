package tv.jellybeam.player.ass

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.jellybeam_core.AssVideoMatrix

class AssFormatsTest {
    @Test
    fun `only raw ssa counts, not media3 cues transcoded from it`() {
        assertTrue(isRawSsa(Format.Builder().setSampleMimeType(MimeTypes.TEXT_SSA).build()))
        assertFalse(
            isRawSsa(
                Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES).setCodecs(MimeTypes.TEXT_SSA).build(),
            ),
        )
        assertFalse(isRawSsa(null))
    }

    @Test
    fun `header is the codec private, the last initialization entry`() {
        val format = "Format: Start, End".toByteArray()
        val header = "[Script Info]".toByteArray()
        assertArrayEquals(header, assHeader(listOf(format, header)))
        assertArrayEquals(ByteArray(0), assHeader(emptyList()))
    }

    @Test
    fun `track key drops the period prefix`() {
        assertEquals("3", assTrackKey(Format.Builder().setId("1:3").build()))
        assertEquals("3", assTrackKey(Format.Builder().setId("3").build()))
        assertEquals("ass", assTrackKey(Format.Builder().build()))
    }

    @Test
    fun `font attachments match by mime or by extension when the mime is generic`() {
        assertTrue(isFontAttachment("application/x-truetype-font", "a.ttf"))
        assertTrue(isFontAttachment("font/OTF", "b"))
        assertTrue(isFontAttachment("application/octet-stream", "c.TTC"))
        assertTrue(isFontAttachment(null, "d.otf"))
        assertFalse(isFontAttachment(null, "e.woff2"), "substation reads no WOFF")
        assertFalse(isFontAttachment("font/woff", "f"), "substation reads no WOFF")
        assertFalse(isFontAttachment("image/jpeg", "cover.ttf"))
        assertFalse(isFontAttachment("application/octet-stream", "notes.txt"))
    }

    @Test
    fun `positions are sent on play state changes and every step`() {
        assertTrue(shouldSendPosition(Long.MIN_VALUE, false, 0, false))
        assertTrue(shouldSendPosition(1_000_000, true, 1_000_000, false))
        assertFalse(shouldSendPosition(1_000_000, true, 1_000_000 + POSITION_STEP_US - 1, true))
        assertTrue(shouldSendPosition(1_000_000, true, 1_000_000 + POSITION_STEP_US, true))
        assertTrue(shouldSendPosition(1_000_000, false, 0, false), "a seek back while paused")
    }

    @Test
    fun `osd lift applies only while the osd is up`() {
        assertEquals(OSD_LINE_LIFT_PERCENT, assLineLiftPercent(true), 0.0)
        assertEquals(0.0, assLineLiftPercent(false), 0.0)
    }

    @Test
    fun `matroska flags mirror the default factory`() {
        assertEquals(0, matroskaFlags(transcoding = true, parseHagc = true))
        assertTrue(matroskaFlags(transcoding = false, parseHagc = true) != 0)
    }

    @Test
    fun `range is inclusive of the last byte`() {
        assertEquals("bytes=97-196", rangeHeader(97, 100))
    }

    @Test
    fun `refetch stops at the budget in file order`() {
        val fonts = listOf(AssFontLocation("a", 0, 40), AssFontLocation("b", 40, 40), AssFontLocation("c", 80, 40))
        assertEquals(listOf("a", "b"), fontsToFetch(fonts, budgetBytes = 100).map { it.name })
        assertEquals(emptyList<AssFontLocation>(), fontsToFetch(listOf(AssFontLocation("big", 0, 200)), budgetBytes = 100))
    }

    @Test
    fun `samples beyond the lookahead wait`() {
        assertTrue(withinLookahead(2_000_000, 0))
        assertTrue(withinLookahead(SAMPLE_LOOKAHEAD_US, 0))
        assertFalse(withinLookahead(SAMPLE_LOOKAHEAD_US + 1, 0))
        assertTrue(withinLookahead(0, 60_000_000), "past samples always pass")
    }

    private fun assertTrue(value: Boolean, message: String) = org.junit.Assert.assertTrue(message, value)

    private fun assertFalse(value: Boolean, message: String) = org.junit.Assert.assertFalse(message, value)

    @Test
    fun `a styled track holds its frames while fonts are awaited or arriving, but not past the wait`() {
        for (fetch in AssFontFetch.entries) {
            val busy = fetch == AssFontFetch.AWAITING_CHOICE || fetch == AssFontFetch.FETCHING
            assertEquals("$fetch", busy, assFramesHeld(fetch, waitExpired = false))
            assertFalse("$fetch past the wait", assFramesHeld(fetch, waitExpired = true))
        }
    }

    @Test
    fun `fonts are fetched only while a styled track shows and its choice is settled`() {
        val await = AssFontFetchStep.AWAIT
        val start = AssFontFetchStep.START
        val stop = AssFontFetchStep.STOP
        val none = AssFontFetchStep.NONE
        // (fetch, showing, has attachments, choice settled) -> step, every combination.
        val table = mapOf(
            listOf(AssFontFetch.IDLE, true, true, true) to start,
            listOf(AssFontFetch.IDLE, true, true, false) to await,
            listOf(AssFontFetch.IDLE, true, false, true) to none,
            listOf(AssFontFetch.IDLE, true, false, false) to none,
            listOf(AssFontFetch.IDLE, false, true, true) to none,
            listOf(AssFontFetch.IDLE, false, true, false) to none,
            listOf(AssFontFetch.IDLE, false, false, true) to none,
            listOf(AssFontFetch.IDLE, false, false, false) to none,
            listOf(AssFontFetch.AWAITING_CHOICE, true, true, true) to start,
            listOf(AssFontFetch.AWAITING_CHOICE, true, true, false) to none,
            listOf(AssFontFetch.AWAITING_CHOICE, true, false, true) to start,
            listOf(AssFontFetch.AWAITING_CHOICE, true, false, false) to none,
            listOf(AssFontFetch.AWAITING_CHOICE, false, true, true) to stop,
            listOf(AssFontFetch.AWAITING_CHOICE, false, true, false) to stop,
            listOf(AssFontFetch.AWAITING_CHOICE, false, false, true) to stop,
            listOf(AssFontFetch.AWAITING_CHOICE, false, false, false) to stop,
            listOf(AssFontFetch.FETCHING, true, true, true) to none,
            listOf(AssFontFetch.FETCHING, true, true, false) to none,
            listOf(AssFontFetch.FETCHING, true, false, true) to none,
            listOf(AssFontFetch.FETCHING, true, false, false) to none,
            listOf(AssFontFetch.FETCHING, false, true, true) to stop,
            listOf(AssFontFetch.FETCHING, false, true, false) to stop,
            listOf(AssFontFetch.FETCHING, false, false, true) to stop,
            listOf(AssFontFetch.FETCHING, false, false, false) to stop,
            listOf(AssFontFetch.DONE, true, true, true) to none,
            listOf(AssFontFetch.DONE, true, true, false) to none,
            listOf(AssFontFetch.DONE, true, false, true) to none,
            listOf(AssFontFetch.DONE, true, false, false) to none,
            listOf(AssFontFetch.DONE, false, true, true) to none,
            listOf(AssFontFetch.DONE, false, true, false) to none,
            listOf(AssFontFetch.DONE, false, false, true) to none,
            listOf(AssFontFetch.DONE, false, false, false) to none,
        )
        assertEquals(AssFontFetch.entries.size * 8, table.size)
        for ((input, step) in table) {
            assertEquals("$input", step, assFontFetchStep(input[0] as AssFontFetch, input[1] as Boolean, input[2] as Boolean, input[3] as Boolean))
        }
    }

    @Test
    fun `a step moves the fetch, and a stop from the wait ends it`() {
        assertEquals(AssFontFetch.AWAITING_CHOICE, assFontFetchNext(AssFontFetch.IDLE, AssFontFetchStep.AWAIT))
        assertEquals(AssFontFetch.FETCHING, assFontFetchNext(AssFontFetch.AWAITING_CHOICE, AssFontFetchStep.START))
        assertEquals(AssFontFetch.IDLE, assFontFetchNext(AssFontFetch.AWAITING_CHOICE, AssFontFetchStep.STOP))
        assertEquals(AssFontFetch.IDLE, assFontFetchNext(AssFontFetch.FETCHING, AssFontFetchStep.STOP))
        for (fetch in AssFontFetch.entries) assertEquals(fetch, assFontFetchNext(fetch, AssFontFetchStep.NONE))
    }

    @Test
    fun `the font wait arms once on leaving idle, never on awaiting to fetching or once expired`() {
        fun timers(from: AssFontFetch, to: AssFontFetch, expired: Boolean = false, grace: Boolean = false) =
            assFontTimers(from, to, expired, grace)
        assertEquals(AssFontTimers(armWait = true, cancelWait = false, armGrace = true), timers(AssFontFetch.IDLE, AssFontFetch.AWAITING_CHOICE))
        assertEquals(AssFontTimers(armWait = true, cancelWait = false, armGrace = false), timers(AssFontFetch.IDLE, AssFontFetch.FETCHING))
        assertEquals(AssFontTimers(armWait = false, cancelWait = false, armGrace = false), timers(AssFontFetch.AWAITING_CHOICE, AssFontFetch.FETCHING))
        assertEquals(AssFontTimers(armWait = false, cancelWait = true, armGrace = false), timers(AssFontFetch.AWAITING_CHOICE, AssFontFetch.IDLE))
        assertEquals(AssFontTimers(armWait = false, cancelWait = true, armGrace = false), timers(AssFontFetch.FETCHING, AssFontFetch.DONE))
        assertEquals("already expired: no second blank", false, timers(AssFontFetch.IDLE, AssFontFetch.FETCHING, expired = true).armWait)
        assertEquals("grace arms once per item", false, timers(AssFontFetch.IDLE, AssFontFetch.AWAITING_CHOICE, grace = true).armGrace)
    }

    @Test
    fun `a transition ends the request on a stop, starts a thread on a start, and a start with no url changes nothing`() {
        fun t(fetch: AssFontFetch, showing: Boolean, attachments: Boolean, settled: Boolean, url: Boolean = true) =
            assFontFetchTransition(fetch, showing, attachments, settled, url)
        val idle = AssFontFetch.IDLE
        val awaiting = AssFontFetch.AWAITING_CHOICE
        val fetching = AssFontFetch.FETCHING
        assertEquals(AssFontTransition(awaiting, endRequest = false, startThread = false), t(idle, true, true, false))
        assertEquals(AssFontTransition(fetching, endRequest = false, startThread = true), t(idle, true, true, true))
        assertEquals(AssFontTransition(fetching, endRequest = false, startThread = true), t(awaiting, true, false, true))
        assertEquals(AssFontTransition(idle, endRequest = true, startThread = false), t(awaiting, false, true, false))
        assertEquals(AssFontTransition(idle, endRequest = true, startThread = false), t(fetching, false, true, true))
        assertEquals(AssFontTransition(fetching, endRequest = false, startThread = false), t(fetching, true, true, true))
        assertEquals(AssFontTransition(AssFontFetch.DONE, endRequest = false, startThread = false), t(AssFontFetch.DONE, true, true, true))
        // No url: a start leaves the state alone; the await and the stop don't need one.
        assertEquals(AssFontTransition(idle, endRequest = false, startThread = false), t(idle, true, true, true, url = false))
        assertEquals(AssFontTransition(awaiting, endRequest = false, startThread = false), t(awaiting, true, false, true, url = false))
        assertEquals(AssFontTransition(awaiting, endRequest = false, startThread = false), t(idle, true, true, false, url = false))
        assertEquals(AssFontTransition(idle, endRequest = true, startThread = false), t(awaiting, false, true, true, url = false))
    }

    /** The holder's funnel over the pure pieces: what a sequence of events does, without a player. */
    private class FontSequence {
        var fetch = AssFontFetch.IDLE
        var showing = false
        var hasAttachments = false
        var settled = false
        var waitExpired = false
        var graceArmed = false
        var requests = 0
        val held get() = assFramesHeld(fetch, waitExpired)

        fun evaluate() {
            val t = assFontFetchTransition(fetch, showing, hasAttachments, settled, hasUrl = true)
            if (t.next == fetch) return
            if (t.startThread) requests++
            val next = t.next
            val timers = assFontTimers(fetch, next, waitExpired, graceArmed)
            if (timers.armGrace) graceArmed = true
            fetch = next
        }

        fun tracks(showing: Boolean) { this.showing = showing; evaluate() }
        fun attachments() { hasAttachments = true; evaluate() }
        fun decision(showingAfter: Boolean) { settled = true; showing = showingAfter; evaluate() }
        fun graceFires() { settled = true; evaluate() }
        fun waitFires() { waitExpired = true }
    }

    @Test
    fun `a styled default the choice turns off starts no request and releases the hold`() {
        val seq = FontSequence()
        seq.tracks(showing = true)
        seq.attachments()
        assertEquals(AssFontFetch.AWAITING_CHOICE, seq.fetch)
        assertEquals("no request before the choice", 0, seq.requests)
        assertTrue("held while the choice is awaited", seq.held)
        seq.decision(showingAfter = false)
        assertEquals(AssFontFetch.IDLE, seq.fetch)
        assertEquals(0, seq.requests)
        assertFalse(seq.held)
    }

    @Test
    fun `a choice matching the styled default starts the fetch and keeps the hold`() {
        val seq = FontSequence()
        seq.attachments()
        seq.tracks(showing = true)
        seq.decision(showingAfter = true)
        assertEquals(AssFontFetch.FETCHING, seq.fetch)
        assertEquals(1, seq.requests)
        assertTrue(seq.held)
    }

    @Test
    fun `a choice that never arrives settles on the grace and fetches`() {
        val seq = FontSequence()
        seq.tracks(showing = true)
        seq.attachments()
        assertEquals(AssFontFetch.AWAITING_CHOICE, seq.fetch)
        seq.graceFires()
        assertEquals(AssFontFetch.FETCHING, seq.fetch)
        assertEquals(1, seq.requests)
    }

    @Test
    fun `after the wait ran out a toggle off and on does not blank again`() {
        val seq = FontSequence()
        seq.decision(showingAfter = true)
        seq.attachments()
        assertTrue(seq.held)
        seq.waitFires()
        assertFalse(seq.held)
        seq.tracks(showing = false)
        seq.tracks(showing = true)
        assertEquals(AssFontFetch.FETCHING, seq.fetch)
        assertFalse("still released: the wait is per item", seq.held)
        assertEquals(2, seq.requests)
    }

    @Test
    fun `the embedded track after a decision follows disabled text and overrides`() {
        val ssa = TrackGroup(Format.Builder().setSampleMimeType(MimeTypes.TEXT_SSA).build())
        val srt = TrackGroup(Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_SUBRIP).build())
        val builder = { TrackSelectionParameters.Builder() }
        assertTrue("untouched keeps what plays", embeddedRawSsaAfter(builder().build(), current = true))
        assertFalse(embeddedRawSsaAfter(builder().build(), current = false))
        assertFalse("off", embeddedRawSsaAfter(builder().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build(), current = true))
        assertTrue("an SSA pick", embeddedRawSsaAfter(builder().setOverrideForType(TrackSelectionOverride(ssa, 0)).build(), current = false))
        assertFalse("another text pick", embeddedRawSsaAfter(builder().setOverrideForType(TrackSelectionOverride(srt, 0)).build(), current = true))
        assertTrue(
            "an audio override says nothing about text",
            embeddedRawSsaAfter(
                builder().setOverrideForType(TrackSelectionOverride(TrackGroup(Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).build()), 0)).build(),
                current = true,
            ),
        )
    }

    @Test
    fun `only a range starting at the offset and within the size counts`() {
        assertEquals(500, servedRangeLength("bytes 1000-1499/90000", offset = 1000, size = 500))
        assertEquals(500, servedRangeLength("bytes 1000-1499/*", offset = 1000, size = 500))
        assertEquals("shorter at the end of the file", 90, servedRangeLength("bytes 1000-1089/1090", offset = 1000, size = 500))
        assertNull("whole file", servedRangeLength("bytes 0-89999/90000", offset = 1000, size = 500))
        assertNull("longer than asked", servedRangeLength("bytes 1000-1999/90000", offset = 1000, size = 500))
        assertNull("another start", servedRangeLength("bytes 999-1498/90000", offset = 1000, size = 500))
        assertNull("no range header", servedRangeLength(null, offset = 1000, size = 500))
    }

    @Test
    fun `the video colour space comes from Media3's colour info`() {
        fun video(space: Int, range: Int, transfer: Int) = Format.Builder().setHeight(1080).setColorInfo(
            androidx.media3.common.ColorInfo.Builder().setColorSpace(space).setColorRange(range).setColorTransfer(transfer).build(),
        ).build()
        assertEquals(
            AssVideoColour(AssVideoMatrix.BT709, fullRange = false, hdr = false, height = 1080),
            assVideoColour(video(C.COLOR_SPACE_BT709, C.COLOR_RANGE_LIMITED, C.COLOR_TRANSFER_SDR)),
        )
        assertEquals(AssVideoMatrix.BT601, assVideoColour(video(C.COLOR_SPACE_BT601, C.COLOR_RANGE_FULL, C.COLOR_TRANSFER_SDR)).matrix)
        assertTrue(assVideoColour(video(C.COLOR_SPACE_BT601, C.COLOR_RANGE_FULL, C.COLOR_TRANSFER_SDR)).fullRange, "full range")
        assertTrue(assVideoColour(video(C.COLOR_SPACE_BT2020, C.COLOR_RANGE_LIMITED, C.COLOR_TRANSFER_ST2084)).hdr, "PQ is HDR")
        assertTrue(assVideoColour(video(C.COLOR_SPACE_BT2020, C.COLOR_RANGE_LIMITED, C.COLOR_TRANSFER_HLG)).hdr, "HLG is HDR")
        assertEquals(
            "nothing signalled",
            AssVideoColour(AssVideoMatrix.UNKNOWN, fullRange = false, hdr = false, height = 480),
            assVideoColour(Format.Builder().setHeight(480).build()),
        )
    }
}
