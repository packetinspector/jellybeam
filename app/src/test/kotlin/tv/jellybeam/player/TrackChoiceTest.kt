package tv.jellybeam.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import tv.jellybeam.player.TextPick.Load
import tv.jellybeam.player.TextPick.On
import tv.jellybeam.player.TrackChoice.Awaiting
import tv.jellybeam.player.TrackChoice.CoreDeciding
import tv.jellybeam.player.TrackChoice.Decided
import tv.jellybeam.player.TrackChoice.Idle
import tv.jellybeam.player.TrackChoice.Negotiating
import tv.jellybeam.player.TrackChoice.Resolving
import tv.jellybeam.player.TrackEffect.Apply
import tv.jellybeam.player.TrackEffect.BurnIgnored
import tv.jellybeam.player.TrackEffect.CancelPlanScope
import tv.jellybeam.player.TrackEffect.ClosePicker
import tv.jellybeam.player.TrackEffect.Commit
import tv.jellybeam.player.TrackEffect.Fatal
import tv.jellybeam.player.TrackEffect.FetchSidecar
import tv.jellybeam.player.TrackEffect.HideSidecar
import tv.jellybeam.player.TrackEffect.Negotiate
import tv.jellybeam.player.TrackEffect.NewPlanScope
import tv.jellybeam.player.TrackEffect.Notice
import tv.jellybeam.player.TrackEffect.Refused
import tv.jellybeam.player.TrackEffect.Remember
import tv.jellybeam.player.TrackEffect.ResetDelay
import tv.jellybeam.player.TrackEffect.ResetSidecarCache
import tv.jellybeam.player.TrackEffect.Resolve
import tv.jellybeam.player.TrackEffect.ShowSidecar
import tv.jellybeam.player.TrackEffect.SidecarUnavailable
import tv.jellybeam.player.TrackEffect.TextOff
import tv.jellybeam.player.TrackEvent.AudioInput
import tv.jellybeam.player.TrackEvent.Committed
import tv.jellybeam.player.TrackEvent.Failed
import tv.jellybeam.player.TrackEvent.Fallback
import tv.jellybeam.player.TrackEvent.Gate
import tv.jellybeam.player.TrackEvent.Gone
import tv.jellybeam.player.TrackEvent.Landed
import tv.jellybeam.player.TrackEvent.PickAudio
import tv.jellybeam.player.TrackEvent.PickText
import tv.jellybeam.player.TrackEvent.PlanAdopted
import tv.jellybeam.player.TrackEvent.PlayerDied
import tv.jellybeam.player.TrackEvent.ResolveFailed
import tv.jellybeam.player.TrackEvent.Resolved
import tv.jellybeam.player.TrackEvent.SessionEnded
import tv.jellybeam.player.TrackEvent.SidecarFetched
import tv.jellybeam.player.TrackEvent.TextInput
import tv.jellybeam.player.TrackEvent.Tracks
import uniffi.jellybeam_core.AudioStreamFfi
import uniffi.jellybeam_core.CoreException
import uniffi.jellybeam_core.EmbeddedSubtitleFfi
import uniffi.jellybeam_core.ExternalSubtitleFfi
import uniffi.jellybeam_core.PlayMethodFfi
import uniffi.jellybeam_core.PlaybackPlan
import uniffi.jellybeam_core.SubtitleActionFfi
import uniffi.jellybeam_core.SubtitleDeliveryFfi
import uniffi.jellybeam_core.TrackDecisionFfi
import uniffi.jellybeam_core.TrackDeliveryFfi
import uniffi.jellybeam_core.TrackInfo
import uniffi.jellybeam_core.TrackKindFfi
import uniffi.jellybeam_core.TrackRowFfi
import uniffi.jellybeam_core.TranscodeCauseFfi
import uniffi.jellybeam_core.TranscodeStreamsFfi
import uniffi.jellybeam_core.TranscodeStreamsFfi.Named
import uniffi.jellybeam_core.TranscodeStreamsFfi.Policy

// One item: audio 0 (eng, default) and 1 (jpn); subtitles 2 (ASS, default), 3 (eng SRT), 4 (jpn SRT); sidecar 6 (fre).
private val AUDIO_STREAMS = listOf(audioStream(0, "eng", "Main", default = true), audioStream(1, "jpn", "Original", default = false))
private val SIDECAR_FILE = ExternalSubtitleFfi(index = 6, codec = "srt", language = "fre", displayTitle = "French", isDefault = false, isForced = false)
private val SIDECAR_ROWS = sidecarRows(coreSidecarRows(listOf(SIDECAR_FILE)))
private val ERR = CoreException.Api("refused")

private val SIGNS = TrackIdentity("eng", "Signs", forced = false, sdh = false)
private val FULL = TrackIdentity("eng", "Full", forced = false, sdh = false)
private val FRENCH = TrackIdentity("fra", "French", forced = false, sdh = false)
private val MAIN = TrackIdentity("eng", "Main", forced = false, sdh = false)
private val ORIGINAL = TrackIdentity("jpn", "Original", forced = false, sdh = false)

private fun audioStream(index: Int, lang: String, title: String, default: Boolean) =
    AudioStreamFfi(index = index, displayTitle = title, title = title, language = lang, codec = "aac", channels = 2, isDefault = default)

private fun subtitleStreams(signs: SubtitleDeliveryFfi, text: SubtitleDeliveryFfi) = listOf(
    EmbeddedSubtitleFfi(index = 2, language = "eng", title = "Signs", forced = false, default = true, codec = "ass", displayTitle = "Signs", delivery = signs),
    EmbeddedSubtitleFfi(index = 3, language = "eng", title = "Full", forced = false, default = false, codec = "subrip", displayTitle = "Full", delivery = text),
    EmbeddedSubtitleFfi(index = 4, language = "jpn", title = "Notes", forced = false, default = false, codec = "subrip", displayTitle = "Notes", delivery = text),
)

private fun plan(
    session: String,
    method: PlayMethodFfi,
    embedded: List<EmbeddedSubtitleFfi>,
    burned: Int? = null,
    delivered: Int? = null,
    policy: TrackDecisionFfi? = null,
    sidecars: List<ExternalSubtitleFfi> = listOf(SIDECAR_FILE),
    rows: List<TrackRowFfi> = coreSidecarRows(sidecars),
) = PlaybackPlan(
    itemId = "item-1",
    itemName = "Sample",
    url = "http://example.test/videos/$session",
    mediaSourceId = "src-1",
    playSessionId = session,
    playMethod = method,
    transcodeReason = null,
    serverVerdict = null,
    transcodeFallbackAllowed = true,
    startPositionTicks = 0L,
    runtimeTicks = null,
    container = "mkv",
    itemType = "Movie",
    seriesName = null,
    parentIndexNumber = null,
    indexNumber = null,
    seriesId = null,
    trackMemorySubject = "item-1",
    externalSubtitles = sidecars,
    embeddedSubtitles = embedded,
    burnedSubtitleIndex = burned,
    audioStreams = AUDIO_STREAMS,
    deliveredAudioIndex = delivered,
    policyDecision = policy,
    trackRows = rows,
)

/** A transcode of the item: [burned] burns that stream, [renditions] when its URL named a text stream. */
private fun transcode(session: String, burned: Int?, renditions: Boolean, delivered: Int, policy: TrackDecisionFfi?, sidecars: List<ExternalSubtitleFfi> = listOf(SIDECAR_FILE)): PlaybackPlan {
    val embedded = subtitleStreams(SubtitleDeliveryFfi.ENCODE, SubtitleDeliveryFfi.HLS)
    return plan(session, PlayMethodFfi.TRANSCODE, embedded, burned, delivered, policy, sidecars, coreTrackRows(AUDIO_STREAMS, embedded, sidecars, delivered, burned, renditions))
}

private fun policy(sub: Int?, audio: Int, off: Boolean = false) =
    TrackDecisionFfi(audioTrackId = audio.toLong(), subtitleAction = if (off) SubtitleActionFfi.OFF else SubtitleActionFfi.LEAVE, subtitleTrackId = sub?.toLong())

private val DP = plan("dp", PlayMethodFfi.DIRECT_PLAY, subtitleStreams(SubtitleDeliveryFfi.EMBED, SubtitleDeliveryFfi.EMBED))
private val T = transcode("t1", burned = 2, renditions = false, delivered = 0, policy = policy(2, 0))
private val T2 = transcode("t2", burned = null, renditions = true, delivered = 1, policy = policy(3, 1))
private val T0 = transcode("t0", burned = null, renditions = false, delivered = 0, policy = policy(null, 0, off = true))
private val TS = transcode("ts", burned = null, renditions = false, delivered = 0, policy = policy(6, 0))

private fun info(id: Long, kind: TrackKindFfi, title: String?, lang: String?, codec: String?, default: Boolean = false, selected: Boolean = false) =
    TrackInfo(id = id, kind = kind, title = title, lang = lang, codec = codec, isDefault = default, isSelected = selected, isForced = false, isHearingImpaired = false)

private val DP_MAIN = info(100, TrackKindFfi.AUDIO, "Main", "en", "aac", default = true, selected = true)
private val DP_ORIGINAL = info(101, TrackKindFfi.AUDIO, "Original", "ja", "aac")
private val DP_SIGNS = info(102, TrackKindFfi.SUBTITLE, "Signs", "en", "ass", default = true, selected = true)
private val DP_FULL = info(103, TrackKindFfi.SUBTITLE, "Full", "en", "subrip")
private val DP_ROWS = listOf(DP_MAIN, DP_ORIGINAL, DP_SIGNS, DP_FULL, info(104, TrackKindFfi.SUBTITLE, "Notes", "ja", "subrip")) + SIDECAR_ROWS

/** A plan's picker rows as the owner builds them: Media3 renditions (ids 300 + stream), stream rows, sidecars. */
private fun rowsOf(p: PlanFacts): List<TrackInfo> {
    if (!p.transcode) return DP_ROWS
    return p.rows.mapNotNull {
        val id = when (it.delivery) {
            TrackDeliveryFfi.SIDECAR -> return@mapNotNull null
            TrackDeliveryFfi.RENDITION -> 300L + it.streamIndex
            else -> streamRowId(it.streamIndex)
        }
        info(id, it.kind, it.title, it.lang, it.codec, it.isDefault, it.isSelected && it.delivery != TrackDeliveryFfi.RENDITION)
    } + SIDECAR_ROWS
}

private fun delivered(p: PlaybackPlan, index: Int = p.deliveredAudioIndex!!) = AudioPick.Track(streamAudioChoice(AUDIO_STREAMS[index]), id = null)

private fun pick(input: TextInput, gate: Gate = Gate.SOLO) = PickText(input, gate)

private fun pick(input: AudioInput, gate: Gate = Gate.SOLO) = PickAudio(input, gate)

private fun show(id: Long) = Apply(TrackDecisionFfi(audioTrackId = null, subtitleAction = SubtitleActionFfi.LEAVE, subtitleTrackId = id))

private fun play(id: Long) = Apply(TrackDecisionFfi(audioTrackId = id, subtitleAction = SubtitleActionFfi.LEAVE, subtitleTrackId = null))

/** What the item's landed transcode carries for [sent]: the default ASS burns unless another stream is named. */
private fun landing(sent: TranscodeStreamsFfi, otherAudio: Boolean = false): PlaybackPlan = when (sent) {
    Policy -> transcode("q-policy", burned = 2, renditions = false, delivered = 0, policy = policy(2, 0))
    is Named -> {
        val audio = (sent.audio ?: 0).let { if (otherAudio) 1 - it else it }
        val sub = sent.subtitle
        transcode("q-$sub-$audio", burned = if (sub == null || sub == 2) 2 else null, renditions = sub == 3 || sub == 4, delivered = audio, policy = null)
    }
}

private fun run(vararg events: TrackEvent, from: TrackChoice = Idle): Pair<TrackChoice, List<TrackEffect>> =
    events.fold(from to emptyList()) { (state, effects), event -> reduceTracks(state, event).let { it.state to effects + it.effects } }

private fun negotiations(effects: List<TrackEffect>) = effects.filterIsInstance<Negotiate>().map { it.sent }

private val TrackChoice.facts: PlanFacts?
    get() = when (this) {
        Idle -> null
        is Awaiting -> p
        is Resolving -> p
        is Decided -> p
        is Negotiating -> p
        is CoreDeciding -> p
    }

private val TrackChoice.text: TextPick?
    get() = when (this) {
        is Awaiting -> text
        is Resolving -> text
        is Decided -> text
        is Negotiating -> text
        else -> null
    }

class TrackChoiceTest {
    private val t1 = T.trackFacts()
    private val decidedT = Decided(t1, TextPick.Track(SIGNS, 2, On.Burned), delivered(T))

    @Test
    fun `a direct play plan awaits its tracks and a transcode starts decided on its delivered audio and policy text`() {
        val start = listOf(NewPlanScope, ResetSidecarCache)
        assertEquals(TrackStep(Awaiting(DP.trackFacts()), start), reduceTracks(Idle, PlanAdopted(DP)))
        assertEquals(TrackStep(decidedT, start + TextOff), reduceTracks(Idle, PlanAdopted(T)))
        assertEquals(
            TrackStep(Decided(T2.trackFacts(), TextPick.Track(FULL, 3, On.Due), delivered(T2)), start + TextOff),
            reduceTracks(decidedT, PlanAdopted(T2)),
        )
        assertEquals(TrackStep(Decided(T0.trackFacts(), TextPick.Off, delivered(T0)), start + TextOff), reduceTracks(Idle, PlanAdopted(T0)))
        assertEquals(
            TrackStep(Decided(TS.trackFacts(), TextPick.Sidecar(6, FRENCH, Load.Loading("ts"), byUser = false), delivered(TS)), start + listOf(TextOff, FetchSidecar("ts", 6))),
            reduceTracks(Idle, PlanAdopted(TS)),
        )
    }

    @Test
    fun `a transcode's rows are the plan's, each rendition under the id of Media3's track of its label`() {
        // Media3 names a rendition by the server's label, its language two-letter.
        val full = info(500, TrackKindFfi.SUBTITLE, "Full", "en", "text/vtt", selected = true)
        val notes = info(501, TrackKindFfi.SUBTITLE, "Notes", "ja", "text/vtt")
        val rows = trackRows(T2, listOf(info(400, TrackKindFfi.AUDIO, null, "en", "aac", selected = true), full, notes))
        assertEquals(listOf(streamRowId(0), streamRowId(1), streamRowId(2), 500L, 501L, ExternalSubtitles.idFor(6)), rows.map { it.id })
        assertEquals("the delivered stream reads selected", listOf(false, true), rows.take(2).map { it.isSelected })
        assertEquals("the plan's title and flags, Media3's id and selection", T2.trackRows.first { it.streamIndex == 3 }.info(500, selected = true), rows[3])
        assertEquals("a rendition Media3 lacks is a stream row", streamRowId(4), trackRows(T2, listOf(full)).first { it.title == "Notes" }.id)
        assertTrue("the burned stream reads selected", trackRows(T, emptyList()).single { it.id == streamRowId(2) }.isSelected)
    }

    @Test
    fun `a session end idles and hides the sidecar, and idle answers only a plan`() {
        assertEquals(TrackStep(Idle, listOf(CancelPlanScope, HideSidecar)), reduceTracks(decidedT, SessionEnded))
        val others = listOf(
            SessionEnded, Tracks("dp", DP_ROWS), Resolved("dp", policy(3, 1), DP_ROWS), ResolveFailed("dp"), pick(TextInput.Off),
            pick(AudioInput.Stream(1)), Fallback, PlayerDied("E"), Landed("t1", Policy, landing(Policy)), Committed("q-policy"),
            Failed("t1", Policy, FailureOutcome.CLOSE, ERR), Gone("t1", Policy), SidecarFetched("dp", 6, true),
        )
        others.forEach { assertEquals("$it", TrackStep(Idle), reduceTracks(Idle, it)) }
    }

    @Test
    fun `announced tracks start the one direct play resolve and match a due text after a reload`() {
        val awaiting = Awaiting(DP.trackFacts())
        assertEquals(TrackStep(Resolving(awaiting.p, DP_ROWS), listOf(Resolve("dp", DP_ROWS))), reduceTracks(awaiting, Tracks("dp", DP_ROWS)))
        assertEquals("only sidecars announced", TrackStep(awaiting), reduceTracks(awaiting, Tracks("dp", SIDECAR_ROWS)))
        val resolving = Resolving(awaiting.p, DP_ROWS)
        assertEquals("one resolve per plan", TrackStep(resolving), reduceTracks(resolving, Tracks("dp", DP_ROWS)))
        assertEquals("stale session", TrackStep(awaiting), reduceTracks(awaiting, Tracks("old", DP_ROWS)))

        val due = Decided(T2.trackFacts(), TextPick.Track(FULL, 3, On.Due), delivered(T2))
        val rows = rowsOf(due.p)
        val seen = due.p.copy(announced = rows)
        assertEquals(TrackStep(due.copy(p = seen, text = TextPick.Track(FULL, 3, On.Shown(303))), listOf(show(303))), reduceTracks(due, Tracks("t2", rows)))
        assertEquals("audio only keeps it pending", TrackStep(due), reduceTracks(due, Tracks("t2", rows.filter { it.kind == TrackKindFfi.AUDIO })))
        val unmatched = due.copy(text = TextPick.Track(TrackIdentity("deu", null, false, false), null, On.Due))
        assertEquals(TrackStep(unmatched.copy(p = seen, text = TextPick.Track(TrackIdentity("deu", null, false, false), null, On.Missing))), reduceTracks(unmatched, Tracks("t2", rows)))
    }

    @Test
    fun `the resolve decides once with no memory write and a failure leaves the defaults`() {
        val resolving = Resolving(DP.trackFacts(), DP_ROWS)
        val decision = TrackDecisionFfi(audioTrackId = 101, subtitleAction = SubtitleActionFfi.LEAVE, subtitleTrackId = 103)
        assertEquals(
            TrackStep(Decided(resolving.p, TextPick.Track(FULL, null, On.Shown(103)), AudioPick.Track(AudioChoice(ORIGINAL), 101)), listOf(Apply(decision))),
            reduceTracks(resolving, Resolved("dp", decision, DP_ROWS)),
        )
        val toSidecar = TrackDecisionFfi(audioTrackId = null, subtitleAction = SubtitleActionFfi.LEAVE, subtitleTrackId = ExternalSubtitles.idFor(6))
        assertEquals(
            TrackStep(
                Decided(resolving.p, TextPick.Sidecar(6, FRENCH, Load.Loading("dp"), byUser = false), AudioPick.Track(AudioChoice(MAIN), 100)),
                listOf(Apply(toSidecar.copy(subtitleAction = SubtitleActionFfi.OFF, subtitleTrackId = null)), FetchSidecar("dp", 6)),
            ),
            reduceTracks(resolving, Resolved("dp", toSidecar, DP_ROWS)),
        )
        assertEquals(TrackStep(Decided(resolving.p, TextPick.Leave, AudioPick.Track(AudioChoice(MAIN), 100))), reduceTracks(resolving, ResolveFailed("dp")))
        val decided = Decided(resolving.p, TextPick.Off, AudioPick.Track(AudioChoice(MAIN), 100))
        assertEquals("a late resolve is stale", TrackStep(decided), reduceTracks(decided, Resolved("dp", decision, DP_ROWS)))
    }

    @Test
    fun `a text pick on direct play applies and remembers and never negotiates`() {
        val resolving = Resolving(DP.trackFacts(), DP_ROWS)
        val decided = Decided(resolving.p, TextPick.Track(FULL, null, On.Shown(103)), AudioPick.Track(AudioChoice(MAIN), 100))
        assertEquals(
            TrackStep(resolving.copy(text = decided.text), listOf(show(103), ResetDelay, Remember(TrackKindFfi.SUBTITLE, DP_FULL))),
            reduceTracks(resolving, pick(TextInput.Player(DP_FULL))),
        )
        assertEquals(
            TrackStep(decided.copy(text = TextPick.Off), listOf(TextOff, ResetDelay, Remember(TrackKindFfi.SUBTITLE, null))),
            reduceTracks(decided, pick(TextInput.Off, Gate.GROUP)),
        )
        assertEquals(
            TrackStep(decided.copy(text = TextPick.Sidecar(6, FRENCH, Load.Loading("dp"), byUser = true)), listOf(TextOff, FetchSidecar("dp", 6), ResetDelay)),
            reduceTracks(decided, pick(TextInput.Sidecar(6))),
        )
        assertEquals("the same pick", TrackStep(decided), reduceTracks(decided, pick(TextInput.Player(DP_FULL))))
    }

    @Test
    fun `a transcode text pick renegotiates only when the plan cannot show it`() {
        val off = Negotiating(t1, TextPick.Off, delivered(T), TranscodeCauseFfi.RENEGOTIATE, Named(-1, 0))
        assertEquals(
            TrackStep(off, listOf(TextOff, ResetDelay, Remember(TrackKindFfi.SUBTITLE, null), Negotiate(Renegotiation.PICK, TranscodeCauseFfi.RENEGOTIATE, "t1", Named(-1, 0)))),
            reduceTracks(decidedT, pick(TextInput.Off)),
        )
        val t2 = Decided(T2.trackFacts(), TextPick.Track(FULL, 3, On.Shown(303)), delivered(T2))
        assertEquals("off with nothing burned", t2.copy(text = TextPick.Off), reduceTracks(t2, pick(TextInput.Off)).state)
        assertEquals("the stream already burned", TrackStep(decidedT), reduceTracks(decidedT, pick(TextInput.StreamRow(2))))
        assertEquals(TrackStep(decidedT, listOf(Refused)), reduceTracks(decidedT, pick(TextInput.StreamRow(3), Gate.GROUP)))
        val fullRow = rowsOf(t1).first { it.id == streamRowId(3) }
        assertEquals("not live", TrackStep(decidedT, listOf(Remember(TrackKindFfi.SUBTITLE, fullRow))), reduceTracks(decidedT, pick(TextInput.StreamRow(3), Gate.NOT_LIVE)))

        // docs/18 §3.1: a stream row the plan delivers as a rendition waits for Media3 to announce it.
        val rendition = reduceTracks(t2, pick(TextInput.StreamRow(4)))
        assertEquals(t2.copy(text = TextPick.Track(TrackIdentity("jpn", "Notes", forced = false, sdh = false), 4, On.Pending(live = true))), rendition.state)
        assertTrue("a delivered rendition needs no negotiation", rendition.effects.none { it is Negotiate })

        val inFlight = reduceTracks(off, pick(TextInput.StreamRow(3)))
        assertEquals(off.copy(text = TextPick.Track(FULL, 3, On.Due)), inFlight.state)
        assertTrue("never a second negotiation in flight", inFlight.effects.none { it is Negotiate })

        val undecided = CoreDeciding(DP.trackFacts(), DP_ROWS)
        assertEquals(
            Negotiating(undecided.p, TextPick.Track(FULL, null, On.Shown(103)), AudioPick.Track(AudioChoice(MAIN), 100), TranscodeCauseFfi.FALLBACK, Policy),
            reduceTracks(undecided, pick(TextInput.Player(DP_FULL))).state,
        )
    }

    @Test
    fun `a transcode audio pick renegotiates for another stream and a group is refused`() {
        val original = AudioPick.Track(streamAudioChoice(AUDIO_STREAMS[1]), null)
        val originalRow = T.trackRows.first { it.kind == TrackKindFfi.AUDIO && it.streamIndex == 1 }.info(selected = false)
        assertEquals(
            TrackStep(
                Negotiating(t1, decidedT.text, original, TranscodeCauseFfi.RENEGOTIATE, Named(2, 1)),
                listOf(Remember(TrackKindFfi.AUDIO, originalRow), Negotiate(Renegotiation.AUDIO_PICK, TranscodeCauseFfi.RENEGOTIATE, "t1", Named(2, 1))),
            ),
            reduceTracks(decidedT, pick(AudioInput.Stream(1))),
        )
        assertEquals("the delivered stream", TrackStep(decidedT), reduceTracks(decidedT, pick(AudioInput.Stream(0))))
        assertEquals("the delivered track", TrackStep(decidedT), reduceTracks(decidedT, pick(AudioInput.Player(rowsOf(t1).first { it.kind == TrackKindFfi.AUDIO }))))
        assertEquals(TrackStep(decidedT, listOf(Refused)), reduceTracks(decidedT, pick(AudioInput.Stream(1), Gate.GROUP)))
        assertEquals("not live", TrackStep(decidedT, listOf(Remember(TrackKindFfi.AUDIO, originalRow))), reduceTracks(decidedT, pick(AudioInput.Stream(1), Gate.NOT_LIVE)))

        val dp = Decided(DP.trackFacts(), TextPick.Leave, AudioPick.Track(AudioChoice(MAIN), 100))
        assertEquals(
            TrackStep(dp.copy(audio = AudioPick.Track(AudioChoice(ORIGINAL), 101)), listOf(play(101), Remember(TrackKindFfi.AUDIO, DP_ORIGINAL))),
            reduceTracks(dp, pick(AudioInput.Player(DP_ORIGINAL))),
        )
        assertEquals(Awaiting(dp.p, audio = AudioPick.Track(AudioChoice(ORIGINAL), 101)), reduceTracks(Awaiting(dp.p), pick(AudioInput.Player(DP_ORIGINAL))).state)
    }

    @Test
    fun `a fallback names the decided streams, or asks the core for the policy while undecided`() {
        val dp = DP.trackFacts()
        val policyAsk = Negotiate(Renegotiation.FALLBACK, TranscodeCauseFfi.FALLBACK, "dp", Policy)
        // The plan's scope is renewed, so a resolve that started before the fallback never answers.
        assertEquals(TrackStep(CoreDeciding(dp, emptyList()), listOf(NewPlanScope, policyAsk)), reduceTracks(Awaiting(dp), Fallback))
        assertEquals(TrackStep(CoreDeciding(dp, DP_ROWS), listOf(NewPlanScope, policyAsk)), reduceTracks(Resolving(dp, DP_ROWS), Fallback))
        val decided = Decided(dp, TextPick.Track(FULL, null, On.Shown(103)), AudioPick.Track(AudioChoice(ORIGINAL), 101))
        assertEquals(
            TrackStep(
                Negotiating(dp, decided.text, decided.audio, TranscodeCauseFfi.FALLBACK, Named(3, 1)),
                listOf(Negotiate(Renegotiation.FALLBACK, TranscodeCauseFfi.FALLBACK, "dp", Named(3, 1))),
            ),
            reduceTracks(decided, Fallback),
        )
        listOf(Idle, CoreDeciding(dp, DP_ROWS), Negotiating(dp, decided.text, decided.audio, TranscodeCauseFfi.FALLBACK, Named(3, 1))).forEach {
            assertEquals("$it", TrackStep(it), reduceTracks(it, Fallback))
        }
    }

    @Test
    fun `a landing commits when it carries the choice and renegotiates when a later pick contradicts it`() {
        val off = Negotiating(t1, TextPick.Off, delivered(T), TranscodeCauseFfi.RENEGOTIATE, Named(-1, 0))
        val q = landing(Named(-1, 0))
        assertEquals(TrackStep(off.copy(landed = q), listOf(Commit(q, "t1", Named(-1, 0)))), reduceTracks(off, Landed("t1", Named(-1, 0), q)))
        val changed = off.copy(text = TextPick.Track(FULL, 3, On.Due))
        assertEquals(
            TrackStep(changed.copy(sent = Named(3, 0)), listOf(Negotiate(Renegotiation.CHANGED, TranscodeCauseFfi.RENEGOTIATE, "t1", Named(3, 0)))),
            reduceTracks(changed, Landed("t1", Named(-1, 0), q)),
        )
        val audio = Negotiating(t1, decidedT.text, AudioPick.Track(streamAudioChoice(AUDIO_STREAMS[1]), null), TranscodeCauseFfi.RENEGOTIATE, Named(2, 1))
        val otherAudio = landing(Named(2, 1), otherAudio = true)
        assertEquals("a landing on other audio than asked", TrackStep(audio.copy(landed = otherAudio), listOf(Commit(otherAudio, "t1", Named(2, 1)))), reduceTracks(audio, Landed("t1", Named(2, 1), otherAudio)))
        val undecided = CoreDeciding(DP.trackFacts(), DP_ROWS)
        val qp = landing(Policy)
        assertEquals(TrackStep(undecided.copy(landed = qp), listOf(Commit(qp, "dp", Policy))), reduceTracks(undecided, Landed("dp", Policy, qp)))
        listOf(Landed("t0", Named(-1, 0), q), Landed("t1", Named(3, 0), q)).forEach { assertEquals("$it", TrackStep(off), reduceTracks(off, it)) }
        assertEquals("already landed", TrackStep(off.copy(landed = q)), reduceTracks(off.copy(landed = q), Landed("t1", Named(-1, 0), q)))
    }

    @Test
    fun `a commit loads the landed plan with the choice reloaded onto it`() {
        val q = landing(Named(-1, 0))
        val off = Negotiating(t1, TextPick.Off, delivered(T), TranscodeCauseFfi.RENEGOTIATE, Named(-1, 0), landed = q)
        assertEquals(TrackStep(Decided(q.trackFacts(), TextPick.Off, delivered(q)), listOf(TrackEffect.Load(q), NewPlanScope, TextOff)), reduceTracks(off, Committed(q.playSessionId)))
        assertEquals("another session", TrackStep(off), reduceTracks(off, Committed("t1")))
        val otherAudio = landing(Named(2, 1), otherAudio = true)
        val askedOriginal = Negotiating(t1, TextPick.Track(SIGNS, 2, On.Burned), AudioPick.Track(streamAudioChoice(AUDIO_STREAMS[1]), null), TranscodeCauseFfi.RENEGOTIATE, Named(2, 1), landed = otherAudio)
        assertEquals(
            "a landing on other audio than asked shows the delivered stream",
            TrackStep(Decided(otherAudio.trackFacts(), TextPick.Track(SIGNS, 2, On.Burned), delivered(otherAudio)), listOf(TrackEffect.Load(otherAudio), NewPlanScope, TextOff)),
            reduceTracks(askedOriginal, Committed(otherAudio.playSessionId)),
        )

        val qp = landing(Policy)
        assertEquals(
            TrackStep(Decided(qp.trackFacts(), TextPick.Track(SIGNS, 2, On.Burned), delivered(qp)), listOf(TrackEffect.Load(qp), NewPlanScope, TextOff)),
            reduceTracks(CoreDeciding(DP.trackFacts(), DP_ROWS, landed = qp), Committed("q-policy")),
        )

        val sidecar = off.copy(text = TextPick.Sidecar(6, FRENCH, Load.Loading("t1"), byUser = true))
        assertEquals(
            TrackStep(
                Decided(q.trackFacts(), TextPick.Sidecar(6, FRENCH, Load.Loading(q.playSessionId), byUser = true), delivered(q)),
                listOf(TrackEffect.Load(q), NewPlanScope, TextOff, FetchSidecar(q.playSessionId, 6)),
            ),
            reduceTracks(sidecar, Committed(q.playSessionId)),
        )
        val noSidecars = transcode("q-bare", burned = null, renditions = false, delivered = 0, policy = null, sidecars = emptyList())
        assertEquals(
            "a sidecar the landed plan lacks",
            TrackStep(Decided(noSidecars.trackFacts(), TextPick.Off, delivered(noSidecars)), listOf(TrackEffect.Load(noSidecars), NewPlanScope, HideSidecar, TextOff)),
            reduceTracks(sidecar.copy(landed = noSidecars), Committed("q-bare")),
        )

        // Picked between the landing and its commit: the landed plan loads, then renegotiates for the pick.
        val late = off.copy(text = TextPick.Track(FULL, 3, On.Due))
        val qf = q.trackFacts()
        assertEquals(
            TrackStep(
                Negotiating(qf, TextPick.Track(FULL, 3, On.Due), delivered(q), TranscodeCauseFfi.RENEGOTIATE, Named(3, 0)),
                listOf(TrackEffect.Load(q), NewPlanScope, TextOff, Negotiate(Renegotiation.CHANGED, TranscodeCauseFfi.RENEGOTIATE, q.playSessionId, Named(3, 0))),
            ),
            reduceTracks(late, Committed(q.playSessionId)),
        )
    }

    @Test
    fun `a commit onto another media source drops its cached sidecars and closes the picker`() {
        val shown = TextPick.Sidecar(6, FRENCH, Load.Shown, byUser = true)
        val same = landing(Named(-1, 0))
        val elsewhere = same.copy(mediaSourceId = "src-2")
        val sidecar = Negotiating(t1, shown, delivered(T), TranscodeCauseFfi.RENEGOTIATE, Named(-1, 0), landed = elsewhere)
        assertEquals(
            TrackStep(
                Decided(elsewhere.trackFacts(), shown.copy(load = Load.Loading(elsewhere.playSessionId)), delivered(elsewhere)),
                listOf(TrackEffect.Load(elsewhere), NewPlanScope, ResetSidecarCache, ClosePicker, TextOff, FetchSidecar(elsewhere.playSessionId, 6)),
            ),
            reduceTracks(sidecar, Committed(elsewhere.playSessionId)),
        )
        // docs/18 §3.2: the load cleared the overlay, so even the same source's cues show again, from the owner's cache.
        assertEquals(
            TrackStep(Decided(same.trackFacts(), shown.copy(load = Load.Loading(same.playSessionId)), delivered(same)), listOf(TrackEffect.Load(same), NewPlanScope, TextOff, FetchSidecar(same.playSessionId, 6))),
            reduceTracks(sidecar.copy(landed = same), Committed(same.playSessionId)),
        )
    }

    @Test
    fun `a landing that burns a stream the choice did not name shows it and logs it once`() {
        val q = transcode("q-burn", burned = 2, renditions = false, delivered = 0, policy = null)
        val off = Negotiating(t1, TextPick.Off, delivered(T), TranscodeCauseFfi.RENEGOTIATE, Named(-1, 0), landed = q)
        val step = reduceTracks(off, Committed("q-burn"))
        assertEquals(
            TrackStep(Decided(q.trackFacts(), TextPick.Track(SIGNS, 2, On.Burned), delivered(q)), listOf(TrackEffect.Load(q), NewPlanScope, BurnIgnored(-1, 2), TextOff)),
            step,
        )
        assertTrue("never renegotiated", step.effects.none { it is Negotiate })
    }

    @Test
    fun `a failed renegotiation keeps playing what the plan shows and says so, and a fallback failure is fatal`() {
        val off = Negotiating(t1, TextPick.Off, delivered(T), TranscodeCauseFfi.RENEGOTIATE, Named(-1, 0))
        assertEquals(TrackStep(decidedT, listOf(TextOff, Notice)), reduceTracks(off, Failed("t1", Named(-1, 0), FailureOutcome.FINISH_WITH_MESSAGE, ERR)))
        val pending = off.copy(text = TextPick.Track(FULL, 3, On.Due))
        assertEquals(
            TrackStep(pending.copy(sent = Named(3, 0)), listOf(Negotiate(Renegotiation.CHANGED, TranscodeCauseFfi.RENEGOTIATE, "t1", Named(3, 0)))),
            reduceTracks(pending, Failed("t1", Named(-1, 0), FailureOutcome.FINISH_WITH_MESSAGE, ERR)),
        )
        val fatal = { outcome: FailureOutcome -> TrackStep(Idle, listOf(Fatal(outcome, ERR), CancelPlanScope, HideSidecar)) }
        val dp = DP.trackFacts()
        val fallback = Negotiating(dp, TextPick.Off, AudioPick.Track(AudioChoice(MAIN), 100), TranscodeCauseFfi.FALLBACK, Named(-1, 0))
        assertEquals(fatal(FailureOutcome.FINISH_WITH_MESSAGE), reduceTracks(fallback, Failed("dp", Named(-1, 0), FailureOutcome.FINISH_WITH_MESSAGE, ERR)))
        assertEquals(fatal(FailureOutcome.FINISH_WITH_MESSAGE), reduceTracks(CoreDeciding(dp, DP_ROWS), Failed("dp", Policy, FailureOutcome.FINISH_WITH_MESSAGE, ERR)))
        assertEquals("the old player died", fatal(FailureOutcome.FINISH_WITH_MESSAGE), reduceTracks(off.copy(died = "E"), Failed("t1", Named(-1, 0), FailureOutcome.FINISH_WITH_MESSAGE, ERR)))
        listOf(FailureOutcome.CLOSE, FailureOutcome.REAUTHORIZE, FailureOutcome.QUIET_FINISH).forEach {
            assertEquals("$it", fatal(it), reduceTracks(off, Failed("t1", Named(-1, 0), it, ERR)))
        }
        assertEquals("ignored is gone", reduceTracks(off, Gone("t1", Named(-1, 0))), reduceTracks(off, Failed("t1", Named(-1, 0), FailureOutcome.IGNORE, ERR)))
        assertEquals("stale", TrackStep(off), reduceTracks(off, Failed("t1", Named(3, 0), FailureOutcome.CLOSE, ERR)))
    }

    @Test
    fun `a failure reverts to a text the plan delivers`() {
        val t2 = T2.trackFacts()
        val shown = TextPick.Track(FULL, 3, On.Shown(303))
        val sidecar = TextPick.Sidecar(6, FRENCH, Load.Shown, byUser = true)
        val burned = TextPick.Track(SIGNS, 2, On.Burned)
        // plan, choice, the subtitle stream it asked -> what remains on screen
        data class Row(val p: PlanFacts, val choice: TextPick, val asked: Int?, val kept: TextPick)
        val rows = listOf(
            Row(t2, TextPick.Off, -1, TextPick.Off),
            Row(t2, TextPick.Leave, null, TextPick.Leave),
            Row(t2, shown, 3, shown),
            Row(t2, sidecar, -1, sidecar),
            Row(t2, TextPick.Track(SIGNS, 2, On.Due), 2, TextPick.Off),
            Row(t1, TextPick.Off, -1, burned),
            Row(t1, sidecar, -1, burned),
            Row(t1, TextPick.Track(SIGNS, null, On.Due), 2, burned),
            Row(t1, TextPick.Track(FULL, 3, On.Due), 3, burned),
        )
        rows.forEach { (p, choice, asked, kept) ->
            val audio = AudioPick.Track(streamAudioChoice(AUDIO_STREAMS[p.delivered!!]), null)
            val sent = Named(asked, p.delivered)
            val step = reduceTracks(Negotiating(p, choice, audio, TranscodeCauseFfi.RENEGOTIATE, sent), Failed(p.session, sent, FailureOutcome.FINISH_WITH_MESSAGE, ERR))
            assertEquals("$choice on ${p.session}", Decided(p, kept, audio), step.state)
            assertEquals("$choice on ${p.session}", Notice, step.effects.last())
        }
    }

    @Test
    fun `a superseded negotiation leaves the plan on screen showing what it delivers`() {
        val off = Negotiating(t1, TextPick.Off, delivered(T), TranscodeCauseFfi.RENEGOTIATE, Named(-1, 0))
        assertEquals(TrackStep(decidedT, listOf(TextOff)), reduceTracks(off, Gone("t1", Named(-1, 0))))
        assertEquals("refused at commit", TrackStep(decidedT, listOf(TextOff)), reduceTracks(off.copy(landed = landing(Named(-1, 0))), Gone("t1", Named(-1, 0))))
        val dp = DP.trackFacts()
        assertEquals(TrackStep(Resolving(dp, DP_ROWS), listOf(Resolve("dp", DP_ROWS))), reduceTracks(CoreDeciding(dp, DP_ROWS), Gone("dp", Policy)))
        assertEquals(TrackStep(Awaiting(dp)), reduceTracks(CoreDeciding(dp, emptyList()), Gone("dp", Policy)))
        assertEquals("stale", TrackStep(off), reduceTracks(off, Gone("dp", Named(-1, 0))))
    }

    @Test
    fun `a sidecar shows when its fetch lands under the current session`() {
        val dp = DP.trackFacts()
        val audio = AudioPick.Track(AudioChoice(MAIN), 100)
        val loading = Decided(dp, TextPick.Sidecar(6, FRENCH, Load.Loading("dp"), byUser = true), audio)
        val shown = loading.copy(text = TextPick.Sidecar(6, FRENCH, Load.Shown, byUser = true))
        assertEquals(TrackStep(shown, listOf(ShowSidecar(6), Remember(TrackKindFfi.SUBTITLE, SIDECAR_ROWS.single()))), reduceTracks(loading, SidecarFetched("dp", 6, true)))
        val automatic = loading.copy(text = TextPick.Sidecar(6, FRENCH, Load.Loading("dp"), byUser = false))
        assertEquals(listOf(ShowSidecar(6)), reduceTracks(automatic, SidecarFetched("dp", 6, true)).effects)
        assertEquals(TrackStep(loading.copy(text = TextPick.Off), listOf(SidecarUnavailable(6), HideSidecar)), reduceTracks(loading, SidecarFetched("dp", 6, false)))
        val negotiating = Negotiating(t1, TextPick.Sidecar(6, FRENCH, Load.Loading("t1"), byUser = true), delivered(T), TranscodeCauseFfi.FALLBACK, Named(-1, 0))
        assertEquals(TrackStep(negotiating.copy(text = TextPick.Sidecar(6, FRENCH, Load.Awaiting, byUser = true))), reduceTracks(negotiating, SidecarFetched("t1", 6, false)))
        assertEquals("stale", TrackStep(loading), reduceTracks(loading, SidecarFetched("old", 6, true)))
        val other = Decided(dp, TextPick.Off, audio)
        // docs/18 §3.2: the fetch cached its cues as they landed, so picking the file again needs none.
        assertEquals("no longer chosen", TrackStep(other), reduceTracks(other, SidecarFetched("dp", 6, true)))
        assertEquals(TrackStep(other), reduceTracks(other, SidecarFetched("dp", 6, false)))
    }

    @Test
    fun `a player death only marks the negotiation`() {
        val off = Negotiating(t1, TextPick.Off, delivered(T), TranscodeCauseFfi.RENEGOTIATE, Named(-1, 0))
        assertEquals(TrackStep(off.copy(died = "E")), reduceTracks(off, PlayerDied("E")))
        assertEquals("the first death names it", TrackStep(off.copy(died = "E")), reduceTracks(off.copy(died = "E"), PlayerDied("F")))
        assertEquals(TrackStep(CoreDeciding(DP.trackFacts(), DP_ROWS, died = "E")), reduceTracks(CoreDeciding(DP.trackFacts(), DP_ROWS), PlayerDied("E")))
        assertEquals(TrackStep(decidedT), reduceTracks(decidedT, PlayerDied("E")))
    }

    @Test
    fun `mid-negotiation the picker shows the asked rows, and after an unmatched reload it shows Off`() {
        val asked = Negotiating(t1, TextPick.Track(FULL, 3, On.Due), AudioPick.Track(streamAudioChoice(AUDIO_STREAMS[1]), null), TranscodeCauseFfi.RENEGOTIATE, Named(3, 1))
        assertEquals(TrackSelection(audio = streamRowId(1), text = streamRowId(3), nonDefault = true), selection(asked, rowsOf(t1)))
        val missing = Decided(T2.trackFacts(), TextPick.Track(SIGNS, null, On.Missing), delivered(T2))
        assertEquals(TRACK_PICKER_SUBTITLE_OFF_ID, selection(missing, rowsOf(missing.p)).text)
        val dp = Decided(DP.trackFacts(), TextPick.Leave, AudioPick.Track(AudioChoice(MAIN), 100))
        assertEquals(TrackSelection(audio = 100, text = 102, nonDefault = false), selection(dp, DP_ROWS))
        assertEquals("the default subtitle turned off", TrackSelection(audio = 100, text = TRACK_PICKER_SUBTITLE_OFF_ID, nonDefault = true), selection(dp.copy(text = TextPick.Off), DP_ROWS))
        assertEquals(TrackSelection(audio = 100, text = ExternalSubtitles.idFor(6), nonDefault = true), selection(dp.copy(text = TextPick.Sidecar(6, FRENCH, Load.Loading("dp"), true)), DP_ROWS))
        assertEquals("undecided shows the player's own", TrackSelection(audio = 100, text = 102, nonDefault = false), selection(Awaiting(dp.p), DP_ROWS))
    }

    /** docs/18 §3.1 negotiation budget: each scenario's negotiations, in order, as (subtitle, audio). */
    @Test
    fun `the negotiation budget holds for every scenario`() {
        val dpDecided = arrayOf(PlanAdopted(DP), Tracks("dp", DP_ROWS), Resolved("dp", TrackDecisionFfi(101, SubtitleActionFfi.LEAVE, 103), DP_ROWS))
        val table: List<Triple<String, Array<TrackEvent>, List<TranscodeStreamsFfi>>> = listOf(
            Triple("DP start, any DP pick", dpDecided + arrayOf(pick(TextInput.Off), pick(TextInput.Player(DP_FULL)), pick(TextInput.Sidecar(6)), pick(AudioInput.Player(DP_MAIN))), emptyList()),
            Triple("transcode start burned, then tracks announce", arrayOf(PlanAdopted(T), Tracks("t1", rowsOf(t1))), emptyList()),
            Triple("transcode start Hls, then tracks announce", arrayOf(PlanAdopted(T2), Tracks("t2", rowsOf(T2.trackFacts()))), emptyList()),
            Triple("transcode start Off, then tracks announce", arrayOf(PlanAdopted(T0), Tracks("t0", rowsOf(T0.trackFacts()))), emptyList()),
            Triple("transcode on audio 1, pick the ASS stream", arrayOf(PlanAdopted(T2), pick(TextInput.StreamRow(2))), listOf(Named(2, 1))),
            Triple("transcode, pick audio 1", arrayOf(PlanAdopted(T), pick(AudioInput.Stream(1))), listOf(Named(2, 1))),
            Triple("burned to Off", arrayOf(PlanAdopted(T), pick(TextInput.Off)), listOf(Named(-1, 0))),
            Triple("burned to a manifest text stream", arrayOf(PlanAdopted(T), pick(TextInput.StreamRow(3))), listOf(Named(3, 0))),
            Triple("a rendition picked before Media3 announces, which lacks it", arrayOf(PlanAdopted(T2), pick(TextInput.StreamRow(4)), Tracks("t2", trackRows(T2, emptyList()), announced = true)), listOf(Named(4, 1))),
            Triple("group, a rendition picked before Media3 announces", arrayOf(PlanAdopted(T2), pick(TextInput.StreamRow(4), Gate.GROUP), Tracks("t2", trackRows(T2, emptyList()), announced = true)), emptyList()),
            Triple("a stream row of a delivered rendition", arrayOf(PlanAdopted(T2), pick(TextInput.StreamRow(4)), Tracks("t2", rowsOf(T2.trackFacts()))), emptyList()),
            Triple("burned to a sidecar", arrayOf(PlanAdopted(T), pick(TextInput.Sidecar(6))), listOf(Named(-1, 0))),
            Triple("text to text, both renditions", arrayOf(PlanAdopted(T2), Tracks("t2", rowsOf(T2.trackFacts())), pick(TextInput.Player(rowsOf(T2.trackFacts()).first { it.id == 304L })), pick(TextInput.Player(rowsOf(T2.trackFacts()).first { it.id == 303L }))), emptyList()),
            Triple("off with nothing burned", arrayOf(PlanAdopted(T2), pick(TextInput.Off)), emptyList()),
            Triple("pick the stream already burned", arrayOf(PlanAdopted(T), pick(TextInput.StreamRow(2))), emptyList()),
            Triple("fallback after a decision", dpDecided + Fallback, listOf(Named(3, 1))),
            Triple("fallback while undecided", arrayOf(PlanAdopted(DP), Fallback), listOf(Policy)),
            Triple("pick A then B in flight", arrayOf(PlanAdopted(T), pick(TextInput.StreamRow(3)), pick(TextInput.Off), Landed("t1", Named(3, 0), landing(Named(3, 0)))), listOf(Named(3, 0), Named(-1, 0))),
            Triple("A fails with B pending", arrayOf(PlanAdopted(T), pick(TextInput.StreamRow(3)), pick(TextInput.Off), Failed("t1", Named(3, 0), FailureOutcome.FINISH_WITH_MESSAGE, ERR)), listOf(Named(3, 0), Named(-1, 0))),
            Triple("renegotiation fails", arrayOf(PlanAdopted(T), pick(TextInput.Off), Failed("t1", Named(-1, 0), FailureOutcome.FINISH_WITH_MESSAGE, ERR)), listOf(Named(-1, 0))),
            Triple("fallback fails", arrayOf(PlanAdopted(DP), Fallback, Failed("dp", Policy, FailureOutcome.FINISH_WITH_MESSAGE, ERR)), listOf(Policy)),
            Triple("item change", arrayOf(PlanAdopted(T), Tracks("t1", rowsOf(t1)), PlanAdopted(T2), Tracks("t2", rowsOf(T2.trackFacts()))), emptyList()),
            Triple("group pick needing negotiation", arrayOf(PlanAdopted(T), pick(TextInput.StreamRow(3), Gate.GROUP), pick(AudioInput.Stream(1), Gate.GROUP)), emptyList()),
            Triple("group, a delivered rendition", arrayOf(PlanAdopted(T2), Tracks("t2", rowsOf(T2.trackFacts())), pick(TextInput.Player(rowsOf(T2.trackFacts()).first { it.id == 304L }), Gate.GROUP)), emptyList()),
            Triple("re-announce with new Media3 ids", arrayOf(PlanAdopted(T2), Tracks("t2", rowsOf(T2.trackFacts())), Tracks("t2", trackRows(T2, listOf(info(600, TrackKindFfi.SUBTITLE, "Full", "en", "text/vtt"))))), emptyList()),
        )
        table.forEach { (name, events, expected) -> assertEquals(name, expected, negotiations(run(*events).second)) }

        val (afterFailure, failureEffects) = run(PlanAdopted(T), pick(TextInput.Off), Failed("t1", Named(-1, 0), FailureOutcome.FINISH_WITH_MESSAGE, ERR))
        assertEquals("a failed renegotiation stays on its plan", "t1", afterFailure.facts?.session)
        assertTrue(Notice in failureEffects)
        val (afterFallback, fallbackEffects) = run(PlanAdopted(DP), Fallback, Failed("dp", Policy, FailureOutcome.FINISH_WITH_MESSAGE, ERR))
        assertEquals(Idle, afterFallback)
        assertTrue(fallbackEffects.any { it is Fatal })
        assertTrue(Refused in run(PlanAdopted(T), pick(TextInput.StreamRow(3), Gate.GROUP)).second)
        val adopted = reduceTracks(Idle, PlanAdopted(T2)).state
        assertEquals("group, a rendition before Media3 announces: refused, nothing remembered", TrackStep(adopted, listOf(Refused)), reduceTracks(adopted, pick(TextInput.StreamRow(4), Gate.GROUP)))
        val (groupRendition, groupEffects) = run(PlanAdopted(T2), Tracks("t2", rowsOf(T2.trackFacts())), pick(TextInput.Player(rowsOf(T2.trackFacts()).first { it.id == 304L }), Gate.GROUP))
        assertEquals("a group's delivered rendition is applied", On.Shown(304), (groupRendition.text as TextPick.Track).on)
        assertTrue(show(304) in groupEffects && Refused !in groupEffects)
    }

    /** docs/28 §5: a group refuses exactly the picks that need a negotiation. */
    @Test
    fun `a group refuses only a pick that needs a negotiation`() {
        val t2 = Decided(T2.trackFacts().let { it.copy(announced = rowsOf(it)) }, TextPick.Track(FULL, 3, On.Shown(303)), delivered(T2))
        val dp = Decided(DP.trackFacts(), TextPick.Leave, AudioPick.Track(AudioChoice(MAIN), 100))
        // state, pick -> refused
        val cases = listOf(
            Triple(decidedT, TextInput.StreamRow(3), true),
            Triple(decidedT, TextInput.Off, true),
            Triple(decidedT, TextInput.Sidecar(6), true),
            Triple(decidedT, TextInput.StreamRow(2), false),
            Triple(t2, TextInput.StreamRow(2), true),
            Triple(t2, TextInput.Player(rowsOf(t2.p).first { it.id == 304L }), false),
            Triple(t2, TextInput.Off, false),
            Triple(t2.copy(p = t2.p.copy(announced = null)), TextInput.StreamRow(4), true),
            Triple(dp, TextInput.Player(DP_FULL), false),
            Triple(dp, TextInput.Sidecar(6), false),
        )
        for ((state, input, refused) in cases) {
            val step = reduceTracks(state, pick(input, Gate.GROUP))
            assertEquals("$input on ${state.p.session}", refused, step == TrackStep(state, listOf(Refused)))
            assertTrue("$input on ${state.p.session}", step.effects.none { it is Negotiate })
            assertTrue("solo is never refused", Refused !in reduceTracks(state, pick(input)).effects)
        }
    }

    @Test
    fun `a direct play pick before the resolve lands keeps the resolve's choice of the other kind`() {
        val dp = DP.trackFacts()
        val decision = TrackDecisionFfi(audioTrackId = 101, subtitleAction = SubtitleActionFfi.LEAVE, subtitleTrackId = 103)
        val (afterText, textEffects) = run(PlanAdopted(DP), pick(TextInput.Off), Tracks("dp", DP_ROWS), Resolved("dp", decision, DP_ROWS))
        assertEquals("the remembered audio still applies", Decided(dp, TextPick.Off, AudioPick.Track(AudioChoice(ORIGINAL), 101)), afterText)
        assertEquals(listOf(Resolve("dp", DP_ROWS), play(101)), textEffects.filter { it is Resolve || it is Apply })

        val (afterAudio, audioEffects) = run(pick(AudioInput.Player(DP_MAIN)), Resolved("dp", decision, DP_ROWS), from = Resolving(dp, DP_ROWS))
        assertEquals(Decided(dp, TextPick.Track(FULL, null, On.Shown(103)), AudioPick.Track(AudioChoice(MAIN), 100)), afterAudio)
        assertEquals("the resolve shows its text and leaves the picked audio", show(103), audioEffects.last())

        val failed = run(pick(TextInput.Off), ResolveFailed("dp"), from = Resolving(dp, DP_ROWS)).first
        assertEquals(Decided(dp, TextPick.Off, AudioPick.Track(AudioChoice(MAIN), 100)), failed)
        // A fallback while undecided keeps the pick and asks the core for the policy.
        val fallback = run(pick(TextInput.Off), Fallback, from = Resolving(dp, DP_ROWS)).first
        assertEquals(Negotiating(dp, TextPick.Off, AudioPick.Track(AudioChoice(MAIN), 100), TranscodeCauseFfi.FALLBACK, Policy), fallback)
    }

    @Test
    fun `a rendition takes the one Media3 track of its language when no label matches, and an ambiguous one stays a stream row`() {
        val notes = info(501, TrackKindFfi.SUBTITLE, "Notes", "ja", "text/vtt")
        val relabelled = info(500, TrackKindFfi.SUBTITLE, "Full - English - WebVTT", "en", "text/vtt")
        val rows = trackRows(T2, listOf(relabelled, notes))
        assertEquals(listOf(500L, 501L), rows.filter { it.kind == TrackKindFfi.SUBTITLE && it.title in setOf("Full", "Notes") }.map { it.id })
        val other = info(502, TrackKindFfi.SUBTITLE, "Commentary", "en", "text/vtt")
        assertEquals(streamRowId(3), trackRows(T2, listOf(relabelled, other, notes)).first { it.title == "Full" }.id)
        // The manifest names a rendition by the server's label.
        val labelled = T2.copy(trackRows = T2.trackRows.map { if (it.streamIndex == 3) it.copy(displayTitle = "Full - English - SUBRIP") else it })
        assertEquals(500L, trackRows(labelled, listOf(info(500, TrackKindFfi.SUBTITLE, "Full - English - SUBRIP", "en", "text/vtt"), other)).first { it.title == "Full" }.id)
    }

    @Test
    fun `a negotiation that ends while the stream on screen is dead ends playback with the player's error`() {
        val off = Negotiating(t1, TextPick.Off, delivered(T), TranscodeCauseFfi.RENEGOTIATE, Named(-1, 0), died = "E")
        val lost = TrackStep(Idle, listOf(TrackEffect.PlayerLost("E"), CancelPlanScope, HideSidecar))
        assertEquals(lost, reduceTracks(off, Gone("t1", Named(-1, 0))))
        assertEquals(lost, reduceTracks(off, Failed("t1", Named(-1, 0), FailureOutcome.IGNORE, ERR)))
        assertEquals(lost, reduceTracks(CoreDeciding(DP.trackFacts(), DP_ROWS, died = "E"), Gone("dp", Policy)))
    }

    @Test
    fun `a fallback in flight keeps the latest announced rows for the resolve its supersession starts`() {
        val dp = DP.trackFacts()
        val core = CoreDeciding(dp, emptyList())
        assertEquals(TrackStep(core.copy(rows = DP_ROWS)), reduceTracks(core, Tracks("dp", DP_ROWS)))
        assertEquals("an empty announcement keeps them", TrackStep(core.copy(rows = DP_ROWS)), reduceTracks(core.copy(rows = DP_ROWS), Tracks("dp", emptyList())))
        assertEquals(TrackStep(Resolving(dp, DP_ROWS), listOf(Resolve("dp", DP_ROWS))), run(Tracks("dp", DP_ROWS), Gone("dp", Policy), from = core).let { TrackStep(it.first, it.second) })
    }

    @Test
    fun `a text stream picked over a burn shows only once its plan commits`() {
        val step = reduceTracks(decidedT, pick(TextInput.StreamRow(3)))
        assertEquals(TextPick.Track(FULL, 3, On.Due), (step.state as Negotiating).text)
        assertTrue("never shown on the burned plan", step.effects.none { it is Apply && it.decision.subtitleTrackId != null })
        assertEquals(listOf(Named(3, 0)), negotiations(step.effects))
        val q = landing(Named(3, 0))
        val (afterCommit, _) = run(Landed("t1", Named(3, 0), q), Committed(q.playSessionId), Tracks(q.playSessionId, rowsOf(q.trackFacts())), from = step.state)
        assertEquals(On.Shown(303), (afterCommit.text as TextPick.Track).on)
    }

    @Test
    fun `a rendition Media3 lacks needs a negotiation, and a group refuses it`() {
        // Media3 announces Full but not Notes, so the picker lists Notes as a stream row.
        val announced = trackRows(T2, listOf(info(500, TrackKindFfi.SUBTITLE, "Full", "en", "text/vtt", selected = true)))
        assertEquals(streamRowId(4), announced.first { it.title == "Notes" }.id)
        val seen = run(PlanAdopted(T2), Tracks("t2", announced)).first
        assertEquals(listOf(Named(4, 1)), negotiations(reduceTracks(seen, pick(TextInput.StreamRow(4))).effects))
        assertEquals(TrackStep(seen, listOf(Refused)), reduceTracks(seen, pick(TextInput.StreamRow(4), Gate.GROUP)))
        assertTrue("a matched rendition stays delivered", negotiations(reduceTracks(seen, pick(TextInput.StreamRow(3))).effects).isEmpty())
        assertTrue("before Media3 announces, a rendition waits for it", negotiations(run(PlanAdopted(T2), pick(TextInput.StreamRow(4))).second).isEmpty())
    }

    @Test
    fun `an announcement listing no rendition still makes a rendition need a negotiation`() {
        val seen = run(PlanAdopted(T2), Tracks("t2", trackRows(T2, emptyList()), announced = true)).first
        assertEquals(listOf(Named(4, 1)), negotiations(reduceTracks(seen, pick(TextInput.StreamRow(4))).effects))
    }

    @Test
    fun `a rendition picked before Media3 announces negotiates once the announcement lacks it`() {
        val announced = trackRows(T2, listOf(info(500, TrackKindFfi.SUBTITLE, "Full", "en", "text/vtt")))
        val picked = run(PlanAdopted(T2), pick(TextInput.StreamRow(4)))
        assertEquals(listOf(Named(4, 1)), negotiations(run(Tracks("t2", announced), from = picked.first).second))
    }

    @Test
    fun `a sidecar shown before a same-source renegotiation shows again once it commits`() {
        val q = landing(Named(-1, 1))
        val (state, screen) = watch(
            PlanAdopted(TS), SidecarFetched("ts", 6, true), pick(AudioInput.Stream(1)), Landed("ts", Named(-1, 1), q), Committed(q.playSessionId),
            SidecarFetched(q.playSessionId, 6, true),
        )
        assertEquals(Load.Shown, (state.text as TextPick.Sidecar).load)
        assertEquals("the load cleared the overlay, so the sidecar shows again", Screen(sidecar = 6), screen)
    }

    @Test
    fun `a sidecar picked over a burn shows only once its plan commits, and a failure leaves the burn`() {
        val fetched = watch(PlanAdopted(T), pick(TextInput.Sidecar(6)), SidecarFetched("t1", 6, true))
        assertEquals("never over the burn still on screen", Screen(), fetched.second)
        val q = landing(Named(-1, 0))
        val committed = watch(Landed("t1", Named(-1, 0), q), Committed(q.playSessionId), SidecarFetched(q.playSessionId, 6, true), from = fetched)
        assertEquals(Screen(sidecar = 6), committed.second)
        val failed = watch(Failed("t1", Named(-1, 0), FailureOutcome.FINISH_WITH_MESSAGE, ERR), from = fetched)
        assertEquals(TextPick.Track(SIGNS, 2, On.Burned) to Screen(), failed.first.text to failed.second)
    }

    /** docs/18 §3.1: a transcode shows a rendition only under the Media3 id the overlay maps its stream to. */
    @Test
    fun `a rendition picked before Media3 announces never shows another track of its language`() {
        // Full and Notes are both English; Media3 announces Full alone, so only a negotiation shows Notes.
        val english = subtitleStreams(SubtitleDeliveryFfi.ENCODE, SubtitleDeliveryFfi.HLS).map { if (it.index == 4) it.copy(language = "eng") else it }
        val te = plan("te", PlayMethodFfi.TRANSCODE, english, delivered = 1, policy = policy(3, 1), rows = coreTrackRows(AUDIO_STREAMS, english, listOf(SIDECAR_FILE), 1, null, true))
        val announced = trackRows(te, listOf(info(500, TrackKindFfi.SUBTITLE, "Full", "en", "text/vtt")))
        val notes = TextPick.Track(TrackIdentity("eng", "Notes", forced = false, sdh = false), 4, On.Due)
        val (solo, effects) = run(PlanAdopted(te), pick(TextInput.StreamRow(4)), Tracks("te", announced))
        assertTrue("Full never shows for Notes", show(500) !in effects)
        assertEquals(listOf(Named(4, 1)), negotiations(effects))
        assertEquals(notes, solo.text)

        // docs/28 §5: a group can't negotiate, so the pick is refused at once and remembers nothing.
        val adopted = reduceTracks(Idle, PlanAdopted(te)).state
        assertEquals(TrackStep(adopted, listOf(Refused)), reduceTracks(adopted, pick(TextInput.StreamRow(4), Gate.GROUP)))
    }

    /** docs/18 §3.1: one matcher per method; the identity match is Direct Play's branch of shownTrack alone. */
    @Test
    fun `a transcode never matches a track by identity`() {
        val calls = java.io.File("src/main/kotlin/tv/jellybeam/player").listFiles { f -> f.name in setOf("TrackChoice.kt", "TrackRows.kt") }!!
            .flatMap { it.readLines() }.filter { "matchTrack(" in it }
        assertEquals(1, calls.size)
        assertTrue(calls.single(), calls.single().trimStart().startsWith("if (p.transcode) stream?.let { shownRenditions("))
    }

    /** docs/28 §5: a refused group pick leaves the sidecar's fetch to land and show. */
    @Test
    fun `a group rendition pick before Media3 announces leaves a loading sidecar to show`() {
        val (state, screen) = watch(
            PlanAdopted(T2), pick(TextInput.Sidecar(6), Gate.GROUP), pick(TextInput.StreamRow(4), Gate.GROUP), SidecarFetched("t2", 6, true),
            pick(TextInput.Sidecar(6), Gate.GROUP),
        )
        assertEquals(Load.Shown, (state.text as TextPick.Sidecar).load)
        assertEquals("the cues show", Screen(sidecar = 6), screen)
    }

    @Test
    fun `untitled renditions and tracks never match by label`() {
        val untitled = T2.copy(trackRows = T2.trackRows.map { if (it.streamIndex == 3) it.copy(title = null, displayTitle = null) else it })
        val notes = info(502, TrackKindFfi.SUBTITLE, "Notes", "ja", "text/vtt")
        val two = listOf(info(500, TrackKindFfi.SUBTITLE, null, "en", "text/vtt"), info(501, TrackKindFfi.SUBTITLE, null, "en", "text/vtt"), notes)
        assertEquals("two untitled English tracks are ambiguous", streamRowId(3), trackRows(untitled, two).first { it.kind == TrackKindFfi.SUBTITLE && it.title == null }.id)
        assertEquals("the one English track still matches by language", 500L, trackRows(untitled, two - two[1]).first { it.kind == TrackKindFfi.SUBTITLE && it.title == null }.id)
        val french = listOf(info(500, TrackKindFfi.SUBTITLE, null, "fr", "text/vtt"), notes)
        assertEquals("an untitled track of another language never", streamRowId(3), trackRows(untitled, french).first { it.kind == TrackKindFfi.SUBTITLE && it.title == null }.id)
    }

    /**
     * docs/18 §3.1: JF 10.11 and 12 name each subtitle rendition by its stream's DisplayTitle verbatim
     * ("Styled - English - Default - ASS", "English Forced - SUBRIP") and list no audio rendition.
     */
    @Test
    fun `a rendition matches Media3's track by its display title alone`() {
        // S2 is titled "B" and shows as "A"; S3 is titled "C" and shows as "B"; Media3 announces S3 alone.
        val streams = listOf(
            EmbeddedSubtitleFfi(index = 2, language = "eng", title = "B", forced = false, default = false, codec = "subrip", displayTitle = "A", delivery = SubtitleDeliveryFfi.HLS),
            EmbeddedSubtitleFfi(index = 3, language = "eng", title = "C", forced = false, default = false, codec = "subrip", displayTitle = "B", delivery = SubtitleDeliveryFfi.HLS),
        )
        val tb = plan("tb", PlayMethodFfi.TRANSCODE, streams, delivered = 0, sidecars = emptyList(), rows = coreTrackRows(AUDIO_STREAMS, streams, emptyList(), 0, null, true))
        val rows = trackRows(tb, listOf(info(500, TrackKindFfi.SUBTITLE, "B", "en", "text/vtt")))
        assertEquals("S3 takes the track, S2 stays a stream row", listOf(streamRowId(2), 500L), rows.filter { it.kind == TrackKindFfi.SUBTITLE }.map { it.id })
        val (state, effects) = run(PlanAdopted(tb), Tracks("tb", rows), pick(TextInput.StreamRow(2)))
        assertEquals("a choice of S2 negotiates", listOf(Named(2, 0)), negotiations(effects))
        assertTrue("and never shows S3", show(500) !in effects && (state.text as TextPick.Track).on !is On.Shown)
    }

    @Test
    fun `a label more renditions than Media3 tracks share, or the reverse, matches nothing`() {
        val twins = T2.copy(trackRows = T2.trackRows.map { if (it.streamIndex == 4) it.copy(displayTitle = "Full", lang = "eng") else it })
        val one = trackRows(twins, listOf(info(500, TrackKindFfi.SUBTITLE, "Full", "en", "text/vtt")))
        assertEquals("two renditions shown as Full, one track", listOf(streamRowId(3), streamRowId(4)), one.filter { it.title in setOf("Full", "Notes") }.map { it.id })
        val notes = info(502, TrackKindFfi.SUBTITLE, "Notes", "ja", "text/vtt")
        val two = trackRows(T2, listOf(info(500, TrackKindFfi.SUBTITLE, "Full", "en", "text/vtt"), info(501, TrackKindFfi.SUBTITLE, "Full", "en", "text/vtt"), notes))
        assertEquals("one rendition shown as Full, two tracks", listOf(streamRowId(3), 502L), two.filter { it.title in setOf("Full", "Notes") }.map { it.id })
    }

    /** docs/18 §3.1: Media3 lists a manifest's subtitle renditions in its order, the plan's row order. */
    @Test
    fun `twin renditions pair with twin Media3 tracks in order`() {
        val twins = T2.copy(trackRows = T2.trackRows.map { if (it.streamIndex == 4) it.copy(displayTitle = "Full", lang = "eng") else it })
        val tracks = listOf(info(500, TrackKindFfi.SUBTITLE, "Full", "en", "text/vtt"), info(501, TrackKindFfi.SUBTITLE, "Full", "en", "text/vtt"))
        val rows = trackRows(twins, tracks)
        assertEquals("stream 3 the first, stream 4 the second", listOf(500L, 501L), rows.filter { it.kind == TrackKindFfi.SUBTITLE && it.title in setOf("Full", "Notes") }.map { it.id })
        val (seen, effects) = run(PlanAdopted(twins), Tracks("t2", rows))
        assertEquals("the policy text shows", On.Shown(500), (seen.text as TextPick.Track).on)
        assertTrue(show(500) in effects && negotiations(effects).isEmpty())
        val second = reduceTracks(seen, pick(TextInput.Player(rows.single { it.id == 501L })))
        assertEquals("the second twin shows", On.Shown(501), (second.state.text as TextPick.Track).on)
        assertTrue(show(501) in second.effects && negotiations(second.effects).isEmpty())
        val first = reduceTracks(second.state, pick(TextInput.Player(rows.single { it.id == 500L })))
        assertEquals("and the first again", On.Shown(500), (first.state.text as TextPick.Track).on)
        assertTrue(show(500) in first.effects && negotiations(first.effects).isEmpty())
    }

    /** docs/18 §3.1: a shown or missing text follows every announcement and never negotiates for it. */
    @Test
    fun `a shown or missing text follows Media3's ids on every announcement`() {
        val t2 = T2.trackFacts()
        val shown = run(PlanAdopted(T2), Tracks("t2", rowsOf(t2))).first
        assertEquals(On.Shown(303), (shown.text as TextPick.Track).on)
        val renumbered = trackRows(T2, listOf(info(600, TrackKindFfi.SUBTITLE, "Full", "en", "text/vtt"), info(601, TrackKindFfi.SUBTITLE, "Notes", "ja", "text/vtt")))
        val moved = reduceTracks(shown, Tracks("t2", renumbered))
        assertEquals("new ids", Decided(t2.copy(announced = renumbered), TextPick.Track(FULL, 3, On.Shown(600)), delivered(T2)), moved.state)
        assertEquals(listOf(show(600)), moved.effects)
        assertEquals("the same ids again change nothing", listOf<TrackEffect>(), reduceTracks(moved.state, Tracks("t2", renumbered)).effects)
        val lacking = trackRows(T2, listOf(info(601, TrackKindFfi.SUBTITLE, "Notes", "ja", "text/vtt")))
        val lost = reduceTracks(moved.state, Tracks("t2", lacking))
        assertEquals("unmatched turns text off, the choice kept", TrackStep(Decided(t2.copy(announced = lacking), TextPick.Track(FULL, 3, On.Missing), delivered(T2)), listOf(TextOff)), lost)
        val back = trackRows(T2, listOf(info(700, TrackKindFfi.SUBTITLE, "Full", "en", "text/vtt")))
        assertEquals("a missing text that matches again shows", TrackStep(Decided(t2.copy(announced = back), TextPick.Track(FULL, 3, On.Shown(700)), delivered(T2)), listOf(show(700))), reduceTracks(lost.state, Tracks("t2", back)))

        val dp = Decided(DP.trackFacts(), TextPick.Track(FULL, null, On.Shown(103)), AudioPick.Track(AudioChoice(MAIN), 100))
        val dpMoved = DP_ROWS.map { if (it.id == 103L) it.copy(id = 203) else it }
        assertEquals("Direct Play follows a new id", TrackStep(dp.copy(text = TextPick.Track(FULL, null, On.Shown(203))), listOf(show(203))), reduceTracks(dp, Tracks("dp", dpMoved)))
        // Twin tracks are ambiguous to the identity match, so the one on screen stays while Media3 lists it.
        val twins = DP_ROWS + info(105, TrackKindFfi.SUBTITLE, "Full", "en", "subrip")
        val second = dp.copy(text = TextPick.Track(FULL, null, On.Shown(105)))
        assertEquals("a twin on screen stays", TrackStep(second), reduceTracks(second, Tracks("dp", twins)))
    }

    /** inv 16: what the viewer sees, folded from the effects; the plan on screen's burn is read from the state. */
    private data class Screen(val text: Long? = null, val sidecar: Int? = null)

    /** The owner loads a plan before adopting it, and a session end stops the player, so both clear the screen. */
    private fun Screen.after(e: TrackEvent, step: TrackStep): Screen {
        if (step.state == Idle) return Screen()
        var s = if (e is PlanAdopted) Screen() else this
        for (effect in step.effects) {
            s = when (effect) {
                is TrackEffect.Load -> Screen()
                is Apply -> when {
                    effect.decision.subtitleTrackId != null -> s.copy(text = effect.decision.subtitleTrackId)
                    effect.decision.subtitleAction == SubtitleActionFfi.OFF -> s.copy(text = null)
                    else -> s
                }
                TextOff -> s.copy(text = null)
                is ShowSidecar -> s.copy(sidecar = effect.index)
                HideSidecar -> s.copy(sidecar = null)
                else -> s
            }
        }
        return s
    }

    private fun watch(vararg events: TrackEvent, from: Pair<TrackChoice, Screen> = Idle to Screen()): Pair<TrackChoice, Screen> =
        events.fold(from) { (state, screen), e -> reduceTracks(state, e).let { it.state to screen.after(e, it) } }

    // Sequence tests (skill §1b): every sequence up to length 4 over the alphabet, invariants after each step.

    /**
     * What the sequence so far has done since its last plan (invariants 2 and 9), the negotiation tags
     * answered since (6), each commit's tag, and the last step's event when it was an accepted result.
     */
    private data class History(
        val calm: Boolean = true,
        val textOff: Boolean = false,
        val retired: Set<Pair<String, TranscodeStreamsFfi>> = emptySet(),
        val commits: Map<String, Pair<String, TranscodeStreamsFfi>> = emptyMap(),
        val lastResult: TrackEvent? = null,
        val screen: Screen = Screen(),
        val announced: List<TrackInfo>? = null,
    )

    private fun TrackChoice.sessionOrNone() = facts?.session ?: "none"

    /** A row the viewer can pick: from the plan's latest announcement, else its rows as fully announced. */
    private fun playerRow(s: TrackChoice, h: History, kind: TrackKindFfi, notFirst: Boolean): TrackInfo {
        val rows = (h.announced ?: s.facts?.let(::rowsOf) ?: DP_ROWS).filter { it.kind == kind && it.isMedia3Row() }
        return (if (notFirst) rows.getOrNull(1) else null) ?: rows.firstOrNull() ?: if (kind == TrackKindFfi.AUDIO) DP_ORIGINAL else DP_FULL
    }

    private fun TrackInfo.isMedia3Row() = id >= 0 && ExternalSubtitles.indexOf(id) == null

    /** [p] as the plan the owner holds, for the real [trackRows]. */
    private fun planOf(p: PlanFacts) =
        plan(p.session, if (p.transcode) PlayMethodFfi.TRANSCODE else PlayMethodFfi.DIRECT_PLAY, p.embedded, p.burned, p.delivered, p.policy, p.sidecars, p.rows)

    /** The picker rows when Media3 announces every rendition of [p] but stream 3's. */
    private fun lackingFull(p: PlanFacts): List<TrackInfo> {
        if (!p.transcode) return DP_ROWS
        val media3 = p.rows.filter { it.delivery == TrackDeliveryFfi.RENDITION && it.streamIndex != 3 }.map { info(300L + it.streamIndex, TrackKindFfi.SUBTITLE, it.title, it.lang, "text/vtt") }
        return trackRows(planOf(p), media3)
    }

    private fun ev(name: String, event: (TrackChoice, History) -> TrackEvent) = name to event

    /** A negotiation result for the one in flight, else for a session never on screen. */
    private fun answer(s: TrackChoice, result: (String, TranscodeStreamsFfi) -> TrackEvent) = s.asked?.let { (from, sent) -> result(from, sent) } ?: result("old", Policy)

    private fun failed(outcome: FailureOutcome) = { s: TrackChoice, _: History -> answer(s) { from, sent -> Failed(from, sent, outcome, ERR) } }

    private val alphabet = listOf(
        ev("adopt dp") { _, _ -> PlanAdopted(DP) },
        ev("adopt burned transcode") { _, _ -> PlanAdopted(T) },
        ev("adopt rendition transcode") { _, _ -> PlanAdopted(T2) },
        ev("session ended") { _, _ -> SessionEnded },
        ev("tracks") { s, _ -> Tracks(s.sessionOrNone(), s.facts?.let(::rowsOf) ?: DP_ROWS) },
        ev("tracks empty") { s, _ -> Tracks(s.sessionOrNone(), emptyList()) },
        ev("tracks lacking a rendition") { s, _ -> Tracks(s.sessionOrNone(), s.facts?.let(::lackingFull) ?: DP_ROWS) },
        ev("tracks announcing no rendition") { s, _ -> Tracks(s.sessionOrNone(), s.facts?.let { trackRows(planOf(it), emptyList()) }.orEmpty(), announced = true) },
        ev("tracks stale") { _, _ -> Tracks("old", DP_ROWS) },
        ev("resolved") { s, _ -> Resolved(s.sessionOrNone(), TrackDecisionFfi(101, SubtitleActionFfi.LEAVE, 103), DP_ROWS) },
        ev("resolved sidecar") { s, _ -> Resolved(s.sessionOrNone(), TrackDecisionFfi(null, SubtitleActionFfi.LEAVE, ExternalSubtitles.idFor(6)), DP_ROWS) },
        ev("resolve failed") { s, _ -> ResolveFailed(s.sessionOrNone()) },
        ev("pick off") { _, _ -> pick(TextInput.Off) },
        ev("pick player text") { s, h -> pick(TextInput.Player(playerRow(s, h, TrackKindFfi.SUBTITLE, notFirst = true))) },
        ev("pick player text not live") { s, h -> pick(TextInput.Player(playerRow(s, h, TrackKindFfi.SUBTITLE, notFirst = false)), Gate.NOT_LIVE) },
        ev("pick stream row") { _, _ -> pick(TextInput.StreamRow(3)) },
        ev("pick sidecar") { _, _ -> pick(TextInput.Sidecar(6)) },
        ev("pick stream row in a group") { _, _ -> pick(TextInput.StreamRow(3), Gate.GROUP) },
        ev("pick player audio") { s, h -> pick(AudioInput.Player(playerRow(s, h, TrackKindFfi.AUDIO, notFirst = true))) },
        ev("pick audio stream") { _, _ -> pick(AudioInput.Stream(1)) },
        ev("pick audio stream not live") { _, _ -> pick(AudioInput.Stream(1), Gate.NOT_LIVE) },
        ev("pick audio stream in a group") { _, _ -> pick(AudioInput.Stream(1), Gate.GROUP) },
        ev("fallback") { _, _ -> Fallback },
        ev("player died") { _, _ -> PlayerDied("E") },
        ev("landed") { s, _ -> answer(s) { from, sent -> Landed(from, sent, landing(sent)) } },
        ev("landed on other audio") { s, _ -> answer(s) { from, sent -> Landed(from, sent, landing(sent, otherAudio = true)) } },
        ev("landed elsewhere, burning") { s, _ ->
            answer(s) { from, sent ->
                Landed(from, sent, transcode("q-src2", burned = 2, renditions = false, delivered = (sent as? Named)?.audio ?: 0, policy = null).copy(mediaSourceId = "src-2"))
            }
        },
        ev("committed") { _, h -> Committed(h.commits.keys.lastOrNull() ?: "none") },
        ev("failed") { s, h -> failed(FailureOutcome.FINISH_WITH_MESSAGE)(s, h) },
        ev("failed ignored") { s, h -> failed(FailureOutcome.IGNORE)(s, h) },
        ev("failed close") { s, h -> failed(FailureOutcome.CLOSE)(s, h) },
        ev("failed reauthorize") { s, h -> failed(FailureOutcome.REAUTHORIZE)(s, h) },
        ev("failed quietly") { s, h -> failed(FailureOutcome.QUIET_FINISH)(s, h) },
        ev("gone") { s, _ -> answer(s) { from, sent -> Gone(from, sent) } },
        ev("sidecar fetched") { s, _ -> SidecarFetched(((s.text as? TextPick.Sidecar)?.load as? Load.Loading)?.session ?: s.sessionOrNone(), 6, true) },
        ev("sidecar failed") { s, _ -> SidecarFetched(((s.text as? TextPick.Sidecar)?.load as? Load.Loading)?.session ?: s.sessionOrNone(), 6, false) },
        ev("sidecar stale") { _, _ -> SidecarFetched("old", 6, true) },
        ev("result again") { _, h -> h.lastResult ?: Gone("old", Policy) },
    )

    /** Starts the length-4 sweep can't reach, each explored three events further: a sidecar shown before a renegotiation. */
    private val seeds = listOf(
        listOf(PlanAdopted(TS), SidecarFetched("ts", 6, true), pick(AudioInput.Stream(1))),
        listOf(PlanAdopted(DP), Tracks("dp", DP_ROWS), Resolved("dp", TrackDecisionFfi(101, SubtitleActionFfi.LEAVE, 103), DP_ROWS), pick(TextInput.Sidecar(6)), SidecarFetched("dp", 6, true)),
    )

    private val reachedStates = mutableSetOf<String>()
    private val emittedEffects = mutableSetOf<String>()
    private var steps = 0

    @Test
    fun `every sequence up to four events keeps every invariant`() {
        explore(Idle, History(), depth = 4, trail = emptyList())
        assertTrue("sweep ran: $steps steps", steps >= 1_500_000)
        for (seed in seeds) {
            var state: TrackChoice = Idle
            var history = History()
            val trail = mutableListOf<String>()
            for (e in seed) {
                val step = reduceTracks(state, e)
                trail += "$e"
                history = verify(state, e, step, history, trail.toList())
                state = step.state
            }
            explore(state, history, depth = 3, trail = trail)
        }
        val states = setOf("Idle", "Awaiting", "Resolving", "Decided", "Negotiating", "CoreDeciding")
        assertEquals("every state reached", states, reachedStates)
        val effects = setOf(
            "Apply", "TextOff", "Resolve", "Negotiate", "Commit", "Load", "FetchSidecar", "ShowSidecar", "HideSidecar", "SidecarUnavailable",
            "ResetSidecarCache", "ClosePicker", "BurnIgnored", "Remember", "ResetDelay", "Refused", "Notice", "Fatal", "PlayerLost",
            "NewPlanScope", "CancelPlanScope",
        )
        assertEquals("every effect emitted", effects, emittedEffects)
    }

    private fun explore(state: TrackChoice, history: History, depth: Int, trail: List<String>) {
        if (depth == 0) return
        for ((name, event) in alphabet) {
            val e = event(state, history)
            val step = reduceTracks(state, e)
            steps++
            val path = trail + name
            val next = verify(state, e, step, history, path)
            explore(step.state, next, depth - 1, path)
        }
    }

    /** Invariants 1-19 (v2 numbering) after one step; returns the history for the next. */
    private fun verify(before: TrackChoice, e: TrackEvent, step: TrackStep, history: History, path: List<String>): History {
        val after = step.state
        val effects = step.effects
        reachedStates += after::class.simpleName!!
        effects.forEach { emittedEffects += it::class.simpleName!! }
        // The message is built only on failure: the sweep runs millions of steps.
        fun ok(invariant: Int, condition: Boolean) {
            if (!condition) fail("invariant $invariant after ${path.joinToString(" > ")}: $before -> $step")
        }

        val asks = effects.filterIsInstance<Negotiate>()
        val picked = e is PickText || e is PickAudio
        // 1. At most one negotiation per step, only for a fallback, a pick, or a contradicted or failed attempt.
        ok(1, asks.size <= 1)
        ok(1, asks.isEmpty() || picked || e is Fallback || e is Landed || e is Failed || e is Committed || e is Tracks && pendingPick(before) != null)
        // 2. From a plan with no pick or fallback since, nothing negotiates.
        val calm = when {
            e is PlanAdopted -> true
            picked || e is Fallback -> false
            else -> history.calm
        }
        ok(2, !calm || asks.isEmpty())
        // 3. A transcode's decided audio is its delivered stream; a negotiation's audio names a stream.
        val p = after.facts
        if (after is Decided && p!!.transcode) ok(3, (after.audio as? AudioPick.Track)?.choice?.streamIndex == p.delivered)
        if (after is Negotiating && p!!.transcode) ok(3, (after.audio as? AudioPick.Track)?.choice?.streamIndex != null)
        // 4. A negotiation's audio is the choice's stream, never null while the choice carries one.
        asks.forEach { ask ->
            val sent = ask.sent as? Named
            if (sent == null) {
                ok(4, after is CoreDeciding || after is Negotiating)
                return@forEach
            }
            val choice = ((after as Negotiating).audio as? AudioPick.Track)?.choice
            ok(4, sent.audio == choice?.let { audioStreamIndexFor(it, after.p.audioStreams) })
            if (choice?.streamIndex != null) ok(4, sent.audio != null)
        }
        // 5. Memory is written only by a pick or a viewer's sidecar that loaded, never by a refusal.
        if (effects.any { it is Remember }) {
            val sidecarLoaded = e is SidecarFetched && e.ok && (before.text as? TextPick.Sidecar)?.byUser == true
            ok(5, picked || sidecarLoaded)
        }
        if (Refused in effects) ok(5, step == TrackStep(before, listOf(Refused)))
        // 6. A result for a session never on screen, for a negotiation already answered, or heard twice changes nothing.
        val stale = when (e) {
            is Tracks -> e.session == "old"
            is SidecarFetched -> e.session == "old"
            is Landed -> e.from == "old" || (e.from to e.sent) in history.retired
            is Failed -> e.from == "old" || (e.from to e.sent) in history.retired
            is Gone -> e.from == "old" || (e.from to e.sent) in history.retired
            is Committed -> history.commits[e.session]?.let { it in history.retired } ?: true
            else -> false
        }
        if (stale || e == history.lastResult) ok(6, step == TrackStep(before))
        // 7. A resolve starts only from an undecided Direct Play plan.
        val resolves = effects.filterIsInstance<Resolve>()
        ok(7, resolves.size <= 1)
        if (resolves.isNotEmpty()) ok(7, after is Resolving && !after.p.transcode && (before is Awaiting || before is CoreDeciding))
        // 8. A plan loads only when committed, burning nothing the choice doesn't show unless renegotiated at once.
        effects.filterIsInstance<TrackEffect.Load>().forEach { load ->
            ok(8, e is Committed)
            val burned = load.plan.burnedSubtitleIndex
            val text = after.text
            ok(8, burned == null || text == TextPick.Leave || (text as? TextPick.Track)?.let { it.on == On.Burned && it.stream == burned } == true || asks.isNotEmpty())
        }
        // 9. A due or missing text follows a TextOff.
        var textOff = if (e is PlanAdopted) false else history.textOff
        effects.forEach {
            when {
                it == TextOff -> textOff = true
                it is TrackEffect.Apply && it.decision.subtitleTrackId != null -> textOff = false
                it is TrackEffect.Apply && it.decision.subtitleAction == SubtitleActionFfi.OFF -> textOff = true
            }
        }
        val on = (after.text as? TextPick.Track)?.on
        if (on == On.Due || on == On.Missing || on is On.Pending) ok(9, textOff)
        // 10. A loading sidecar loads under the plan on screen.
        ((after.text as? TextPick.Sidecar)?.load as? Load.Loading)?.let { ok(10, it.session == p?.session) }
        // 11. Idle answers only a plan.
        if (before == Idle && e !is PlanAdopted) ok(11, step == TrackStep(Idle))
        // 12. A transcode's choice comes from its one policy run: never a Kotlin resolve (with 7; the list itself is pinned in Rust).
        if (p?.transcode == true) ok(12, resolves.isEmpty())
        // 13. A non-fatal failure ends decided on a text its plan delivers.
        if (e is Failed && Notice in effects) ok(13, after is Decided && delivers(after.p, after.text))
        // 14. Playback ends only for a fallback's or the core's failure, a dead player, or a closing outcome.
        val died = (before as? Negotiating)?.died ?: (before as? CoreDeciding)?.died
        if (effects.any { it is Fatal }) {
            val closing = (e as? Failed)?.outcome in setOf(FailureOutcome.CLOSE, FailureOutcome.REAUTHORIZE, FailureOutcome.QUIET_FINISH)
            ok(14, e is Failed && (before is CoreDeciding || (before as? Negotiating)?.cause == TranscodeCauseFfi.FALLBACK || died != null || closing))
        }
        if (effects.any { it is TrackEffect.PlayerLost }) ok(14, (e is Gone || e is Failed) && died != null)
        // 15. The plan on screen changes only for a new plan or a commit.
        val was = before.facts
        if (was != null && p != null && was.copy(announced = null) != p.copy(announced = null)) ok(15, e is PlanAdopted || e is Committed)
        // 16. What the state says shows is what the screen shows, never two text sources at once, a burn included.
        val screen = history.screen.after(e, step)
        ok(16, screenAgrees(after, screen))
        // 17. A transcode text pick negotiates, or a group refuses it, exactly when the picker's rows say only a negotiation shows it.
        val needs = (e as? PickText)?.takeIf { before is Decided && before.p.transcode && step != TrackStep(before) }?.let { needsNegotiation(before.facts!!, history.announced, it.pick) }
        if (needs != null) {
            when ((e as PickText).gate) {
                Gate.SOLO -> ok(17, asks.isNotEmpty() == needs)
                Gate.GROUP -> ok(17, (Refused in effects) == (needs || early(before.facts!!, history.announced, e.pick)) && asks.isEmpty())
                Gate.NOT_LIVE -> ok(17, asks.isEmpty())
            }
        }
        // 17. A pending pick's announcement negotiates exactly when the announced rows lack its stream and the pick was live.
        val pending = pendingPick(before)
        if (e is Tracks && pending != null && e.session == p?.session && e.announced) {
            val lacking = overlaidRows(before.facts!!.rows, e.rows).any { it.kind == TrackKindFfi.SUBTITLE && it.streamIndex == pending.stream && it.delivery == TrackDeliveryFfi.NEEDS_NEGOTIATION }
            ok(17, asks.isNotEmpty() == (lacking && (pending.on as On.Pending).live && before is Decided))
        }
        // The latest announcement of the plan on screen; a new plan has announced nothing.
        val announced = when {
            e is PlanAdopted || p?.session != was?.session -> null
            e is Tracks && e.session == p?.session && e.announced -> e.rows
            else -> history.announced
        }
        // 18. A transcode text that comes to show reads the overlay alone: the Media3 id its stream maps to in the latest announcement.
        val shown = ((after.text as? TextPick.Track)?.on as? On.Shown)?.takeIf { p?.transcode == true && after.text != before.text }
        if (shown != null) {
            val stream = (after.text as TextPick.Track).stream
            ok(18, stream != null && announced != null && shownRenditions(p!!.rows, announced)[stream]?.id == shown.id)
        }
        // 19. An announcement never negotiates for a shown or missing text: it shows the new id or turns text off.
        val held = (before.text as? TextPick.Track)?.on
        if (e is Tracks && (held is On.Shown || held == On.Missing)) {
            ok(19, asks.isEmpty())
            if (before is Decided || before is Negotiating) ok(19, effects.all { it == TextOff || it is Apply && it.decision.subtitleTrackId != null })
        }

        val retiredNow = before.asked?.takeIf { it != after.asked }
        val commits = effects.filterIsInstance<Commit>().associate { it.plan.playSessionId to (it.from to it.sent) }
        val result = e is Resolved || e is ResolveFailed || e is Landed || e is Committed || e is Failed || e is Gone || e is SidecarFetched
        return History(
            calm = calm,
            textOff = textOff,
            retired = (history.retired + listOfNotNull(retiredNow)) - setOfNotNull(after.asked),
            commits = history.commits + commits,
            lastResult = e.takeIf { result && step != TrackStep(before) },
            screen = screen,
            announced = announced,
        )
    }

    private fun pendingPick(s: TrackChoice) = (s.text as? TextPick.Track)?.takeIf { it.on is On.Pending && s.facts?.transcode == true }

    /** inv 16: the text [state] says shows is on [screen]; a negotiation's text may wait behind the plan still on screen. */
    private fun screenAgrees(state: TrackChoice, screen: Screen): Boolean {
        val p = state.facts ?: return true
        val burned = p.burned
        if (listOfNotNull(screen.text, screen.sidecar, burned).size > 1) return false
        val clear = screen.text == null && screen.sidecar == null && (state is Negotiating || burned == null)
        return when (val text = state.text) {
            null, TextPick.Leave -> true
            TextPick.Off -> clear
            is TextPick.Sidecar -> if (text.load == Load.Shown) screen.sidecar == text.index else clear
            is TextPick.Track -> when (val on = text.on) {
                is On.Shown -> screen.text == on.id
                On.Burned -> burned == text.stream
                On.Due, On.Missing, is On.Pending -> clear
            }
        }
    }

    /**
     * inv 17 (v2 C3): whether a pick of [input] on [p] needs a negotiation, read from its rows overlaid with
     * Media3's [announced]; null for a player row it never announced, which no picker offers.
     */
    private fun needsNegotiation(p: PlanFacts, announced: List<TrackInfo>?, input: TextInput): Boolean? {
        val rows = announced?.let { overlaidRows(p.rows, it) } ?: p.rows
        val stream = when (input) {
            TextInput.Off, is TextInput.Sidecar -> -1
            is TextInput.StreamRow -> input.index
            // A row Media3 shows is delivered unless the plan burns another stream.
            is TextInput.Player -> return (p.burned != null).takeIf { announced.orEmpty().any { it.id == input.row.id } }
        }
        val uncarried = rows.any { it.kind == TrackKindFfi.SUBTITLE && it.streamIndex == stream && it.delivery == TrackDeliveryFfi.NEEDS_NEGOTIATION }
        return stream != p.burned && (p.burned != null || uncarried)
    }

    /** inv 17: a rendition stream row picked before Media3 announced, which a group refuses until the announcement says it shows. */
    private fun early(p: PlanFacts, announced: List<TrackInfo>?, input: TextInput) = announced == null && input is TextInput.StreamRow &&
        p.rows.any { it.kind == TrackKindFfi.SUBTITLE && it.streamIndex == input.index && it.delivery == TrackDeliveryFfi.RENDITION }

    /** docs/18 §3.1: what a plan shows without renegotiating, read from its rows' delivery. */
    private fun delivers(p: PlanFacts, text: TextPick): Boolean {
        val burned = p.rows.firstOrNull { it.delivery == TrackDeliveryFfi.BURNED }?.streamIndex
        return when (text) {
            TextPick.Leave -> true
            TextPick.Off, is TextPick.Sidecar -> burned == null
            is TextPick.Track -> if (burned == null) text.on is On.Shown || text.on is On.Pending else text.on == On.Burned && text.stream == burned
        }
    }
}
