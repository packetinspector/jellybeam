package tv.jellybeam.player

import tv.jellybeam.player.TrackChoice.CoreDeciding
import tv.jellybeam.player.TrackChoice.Decided
import tv.jellybeam.player.TrackChoice.Negotiating
import tv.jellybeam.player.TrackChoice.Resolving
import tv.jellybeam.player.TrackEffect.Negotiate
import tv.jellybeam.player.TrackEvent.AudioInput
import tv.jellybeam.player.TrackEvent.Gate
import tv.jellybeam.player.TrackEvent.TextInput
import uniffi.jellybeam_core.AudioStreamFfi
import uniffi.jellybeam_core.CoreException
import uniffi.jellybeam_core.EmbeddedSubtitleFfi
import uniffi.jellybeam_core.ExternalSubtitleFfi
import uniffi.jellybeam_core.PlayMethodFfi
import uniffi.jellybeam_core.PlaybackPlan
import uniffi.jellybeam_core.SubtitleActionFfi
import uniffi.jellybeam_core.TrackDecisionFfi
import uniffi.jellybeam_core.TrackDeliveryFfi
import uniffi.jellybeam_core.TrackInfo
import uniffi.jellybeam_core.TrackKindFfi
import uniffi.jellybeam_core.TrackRowFfi
import uniffi.jellybeam_core.TranscodeCauseFfi
import uniffi.jellybeam_core.TranscodeStreamsFfi

/** docs/18 §3.1: what the track choice reads of the plan on screen. */
internal data class PlanFacts(
    val session: String,
    val mediaSourceId: String,
    val transcode: Boolean,
    val embedded: List<EmbeddedSubtitleFfi>,
    val audioStreams: List<AudioStreamFfi>,
    val sidecars: List<ExternalSubtitleFfi>,
    val delivered: Int?,
    val burned: Int?,
    val policy: TrackDecisionFfi?,
    val rows: List<TrackRowFfi>,
    /** A transcode's picker rows as Media3 last announced them; null until it announces a track. */
    val announced: List<TrackInfo>? = null,
)

internal fun PlaybackPlan.trackFacts() = PlanFacts(
    session = playSessionId,
    mediaSourceId = mediaSourceId,
    transcode = playMethod == PlayMethodFfi.TRANSCODE,
    embedded = embeddedSubtitles,
    audioStreams = audioStreams,
    sidecars = externalSubtitles,
    delivered = deliveredAudioIndex,
    burned = burnedSubtitleIndex,
    policy = policyDecision,
    rows = trackRows,
)

/** docs/18 §3.1: the viewer's subtitle choice and how far the plan on screen shows it. */
internal sealed interface TextPick {
    /** The player's or server's own default. */
    data object Leave : TextPick
    data object Off : TextPick

    /** An embedded track; [stream] is the server stream a stream row named. */
    data class Track(val identity: TrackIdentity, val stream: Int?, val on: On) : TextPick
    data class Sidecar(val index: Int, val identity: TrackIdentity, val load: Load, val byUser: Boolean) : TextPick

    sealed interface On {
        /** Not matched yet: text stays off until a track announcement carries it. */
        data object Due : On
        data class Shown(val id: Long) : On
        data object Burned : On

        /** No match after a reload: text off, the choice kept. */
        data object Missing : On

        /** A rendition picked before Media3 announced: the announcement shows it, or a [live] pick negotiates for it. */
        data class Pending(val live: Boolean) : On
    }

    sealed interface Load {
        /** Fetching under [session]; a result under any other is stale. */
        data class Loading(val session: String) : Load

        /** Not shown while a renegotiation swaps sessions (refused, or loaded over a burn); fetched again once it ends. */
        data object Awaiting : Load
        data object Shown : Load
    }
}

/** docs/18 §3.1: the viewer's audio choice; a transcode's carries its server stream. */
internal sealed interface AudioPick {
    data class Track(val choice: AudioChoice, val id: Long?) : AudioPick
    data object NoAudio : AudioPick
}

/**
 * docs/18 §3.1: the one track-choice lifecycle. Every state but [Idle] holds the plan on screen; a
 * negotiation's plan replaces it only once committed (docs/18 §2.1).
 */
internal sealed interface TrackChoice {
    data object Idle : TrackChoice

    /** Direct Play, nothing announced yet; [text] and [audio] are the viewer's picks so far, which the resolve keeps. */
    data class Awaiting(val p: PlanFacts, val text: TextPick? = null, val audio: AudioPick? = null) : TrackChoice

    /** Direct Play, the plan's one resolve in flight over [rows]; picks as in [Awaiting]. */
    data class Resolving(val p: PlanFacts, val rows: List<TrackInfo>, val text: TextPick? = null, val audio: AudioPick? = null) : TrackChoice
    data class Decided(val p: PlanFacts, val text: TextPick, val audio: AudioPick) : TrackChoice

    /**
     * A transcode negotiation for [sent] retires [p]'s session; [landed] waits for its commit. [died] is the
     * playing stream's error code once it failed meanwhile.
     */
    data class Negotiating(
        val p: PlanFacts,
        val text: TextPick,
        val audio: AudioPick,
        val cause: TranscodeCauseFfi,
        val sent: TranscodeStreamsFfi,
        val died: String? = null,
        val landed: PlaybackPlan? = null,
    ) : TrackChoice

    /** A fallback while undecided: the core runs the policy (docs/18 §2). */
    data class CoreDeciding(val p: PlanFacts, val rows: List<TrackInfo>, val died: String? = null, val landed: PlaybackPlan? = null) : TrackChoice
}

/** Inputs to [reduceTracks]; a result carries the session or negotiation that started it. */
internal sealed interface TrackEvent {
    data class PlanAdopted(val plan: PlaybackPlan) : TrackEvent
    data object SessionEnded : TrackEvent
    /** [announced]: Media3 listed the loaded stream's tracks, even with no text among them. */
    data class Tracks(val session: String, val rows: List<TrackInfo>, val announced: Boolean = rows.any { it.isPlayerRow() }) : TrackEvent
    data class Resolved(val session: String, val decision: TrackDecisionFfi, val rows: List<TrackInfo>) : TrackEvent
    data class ResolveFailed(val session: String) : TrackEvent
    data class PickText(val pick: TextInput, val gate: Gate) : TrackEvent
    data class PickAudio(val pick: AudioInput, val gate: Gate) : TrackEvent
    data object Fallback : TrackEvent

    /** The playing stream failed with [code] while a negotiation was out. */
    data class PlayerDied(val code: String) : TrackEvent
    data class Landed(val from: String, val sent: TranscodeStreamsFfi, val plan: PlaybackPlan) : TrackEvent
    data class Committed(val session: String) : TrackEvent
    /** [error] is what a fatal [outcome] tells the viewer. */
    data class Failed(val from: String, val sent: TranscodeStreamsFfi, val outcome: FailureOutcome, val error: CoreException) : TrackEvent

    /** Superseded, disposed or refused at commit. */
    data class Gone(val from: String, val sent: TranscodeStreamsFfi) : TrackEvent
    data class SidecarFetched(val session: String, val index: Int, val ok: Boolean) : TrackEvent

    sealed interface TextInput {
        data object Off : TextInput
        data class Player(val row: TrackInfo) : TextInput
        data class StreamRow(val index: Int) : TextInput
        data class Sidecar(val index: Int) : TextInput
    }

    sealed interface AudioInput {
        data class Player(val row: TrackInfo) : AudioInput
        data class Stream(val index: Int) : AudioInput
    }

    /** Solo and live; in a watch-together group (docs/28 §5); or not this player's playback. */
    enum class Gate { SOLO, GROUP, NOT_LIVE }
}

/** Run in order by the owner after the new state is published. */
internal sealed interface TrackEffect {
    data class Apply(val decision: TrackDecisionFfi) : TrackEffect
    data object TextOff : TrackEffect
    data class Resolve(val session: String, val rows: List<TrackInfo>) : TrackEffect
    data class Negotiate(val why: Renegotiation, val cause: TranscodeCauseFfi, val from: String, val sent: TranscodeStreamsFfi) : TrackEffect
    /** Commits [plan], the landing of the negotiation [from] asked [sent] of, which a refusal answers. */
    data class Commit(val plan: PlaybackPlan, val from: String, val sent: TranscodeStreamsFfi) : TrackEffect
    data class Load(val plan: PlaybackPlan) : TrackEffect
    data class FetchSidecar(val session: String, val index: Int) : TrackEffect
    data class ShowSidecar(val index: Int) : TrackEffect
    data object HideSidecar : TrackEffect
    data class SidecarUnavailable(val index: Int) : TrackEffect
    data object ResetSidecarCache : TrackEffect

    /** docs/18 §3.2: the picker's rows name the old media source's sidecars, so it closes. */
    data object ClosePicker : TrackEffect

    /** docs/18 §3.1: the landed plan burns [burned] though [asked] was named; logged once, never renegotiated. */
    data class BurnIgnored(val asked: Int?, val burned: Int) : TrackEffect

    /** [row] null remembers Off. */
    data class Remember(val kind: TrackKindFfi, val row: TrackInfo?) : TrackEffect
    data object ResetDelay : TrackEffect
    data object Refused : TrackEffect

    /** A renegotiation failed and the old session plays on. */
    data object Notice : TrackEffect
    data class Fatal(val outcome: FailureOutcome, val error: CoreException) : TrackEffect

    /** The playing stream died with [code] and no negotiation replaces it: the player's own fatal error. */
    data class PlayerLost(val code: String) : TrackEffect
    data object NewPlanScope : TrackEffect
    data object CancelPlanScope : TrackEffect
}

internal data class TrackStep(val state: TrackChoice, val effects: List<TrackEffect> = emptyList())

/** The picker's selected rows and the OSD dot (docs/12 §16), derived from the choice. */
internal data class TrackSelection(val audio: Long?, val text: Long, val nonDefault: Boolean)

/** docs/18 §3.1: every track-choice transition; a result whose tag isn't current changes nothing. */
internal fun reduceTracks(state: TrackChoice, event: TrackEvent): TrackStep {
    val same = TrackStep(state)
    val p = state.plan ?: return if (event is TrackEvent.PlanAdopted) adopt(event.plan) else same
    return when (event) {
        is TrackEvent.PlanAdopted -> adopt(event.plan)
        TrackEvent.SessionEnded -> TrackStep(TrackChoice.Idle, listOf(TrackEffect.CancelPlanScope, TrackEffect.HideSidecar))
        is TrackEvent.Tracks -> if (event.session == p.session) tracks(state, p, event) else same
        is TrackEvent.Resolved -> if (state is Resolving && event.session == p.session) resolved(state, event) else same
        is TrackEvent.ResolveFailed ->
            if (state is Resolving && event.session == p.session) TrackStep(Decided(p, state.text ?: TextPick.Leave, state.audio ?: selectedAudio(state.rows))) else same
        is TrackEvent.PickText -> pickText(state, p, event)
        is TrackEvent.PickAudio -> pickAudio(state, p, event)
        TrackEvent.Fallback -> fallback(state, p)
        is TrackEvent.PlayerDied -> when (state) {
            is Negotiating -> TrackStep(state.copy(died = state.died ?: event.code))
            is CoreDeciding -> TrackStep(state.copy(died = state.died ?: event.code))
            else -> same
        }
        is TrackEvent.Landed -> if (state.asks(event.from, event.sent) && state.landed == null) landed(state, event) else same
        is TrackEvent.Committed -> state.landed?.takeIf { it.playSessionId == event.session }?.let { committed(state, it) } ?: same
        is TrackEvent.Failed -> if (state.asks(event.from, event.sent) && state.landed == null) failed(state, p, event) else same
        is TrackEvent.Gone -> if (state.asks(event.from, event.sent)) gone(state, p) else same
        is TrackEvent.SidecarFetched -> if (event.session == p.session) sidecarFetched(state, p, event) else same
    }
}

private val TrackChoice.plan: PlanFacts?
    get() = when (this) {
        TrackChoice.Idle -> null
        is TrackChoice.Awaiting -> p
        is Resolving -> p
        is Decided -> p
        is Negotiating -> p
        is CoreDeciding -> p
    }

private val TrackChoice.text: TextPick?
    get() = when (this) {
        is TrackChoice.Awaiting -> text
        is Resolving -> text
        is Decided -> text
        is Negotiating -> text
        else -> null
    }

private val TrackChoice.audio: AudioPick?
    get() = when (this) {
        is TrackChoice.Awaiting -> audio
        is Resolving -> audio
        is Decided -> audio
        is Negotiating -> audio
        else -> null
    }

private val TrackChoice.landed: PlaybackPlan? get() = (this as? Negotiating)?.landed ?: (this as? CoreDeciding)?.landed

/** The negotiation in flight as its results are tagged: the session it retires and what it asked; the core decides for a [CoreDeciding]. */
internal val TrackChoice.asked: Pair<String, TranscodeStreamsFfi>?
    get() = when (this) {
        is Negotiating -> p.session to sent
        is CoreDeciding -> p.session to TranscodeStreamsFfi.Policy
        else -> null
    }

private val TrackChoice.died: String? get() = (this as? Negotiating)?.died ?: (this as? CoreDeciding)?.died

/** docs/18 §2.1: the playing session died while a negotiation was out, so a failure leaves nothing playing. */
internal val TrackChoice.oldDied: Boolean get() = died != null

/** docs/18 §3.2: the sidecar whose cues the screen shows; a load or another pick leaves none until its fetch lands. */
internal val TrackChoice.shownSidecar: Int? get() = (text as? TextPick.Sidecar)?.takeIf { it.load == TextPick.Load.Shown }?.index

private fun TrackChoice.asks(from: String, sent: TranscodeStreamsFfi) = asked == (from to sent)

private fun TrackChoice.withText(text: TextPick): TrackChoice = when (this) {
    is TrackChoice.Awaiting -> copy(text = text)
    is Resolving -> copy(text = text)
    is Decided -> copy(text = text)
    is Negotiating -> copy(text = text)
    else -> this
}

private fun adopt(plan: PlaybackPlan): TrackStep {
    val p = plan.trackFacts()
    val start = listOf(TrackEffect.NewPlanScope, TrackEffect.ResetSidecarCache)
    if (!p.transcode) return TrackStep(TrackChoice.Awaiting(p), start)
    // docs/18 §3.1: the transcode's URL already carries the policy's pick, so it is final here.
    val (text, effects) = reload(p, policyText(p))
    return TrackStep(Decided(p, text, deliveredAudio(p)), start + effects)
}

private fun tracks(state: TrackChoice, p: PlanFacts, event: TrackEvent.Tracks): TrackStep {
    val rows = event.rows
    val announced = rows.any { it.isPlayerRow() && (it.kind == TrackKindFfi.AUDIO || it.kind == TrackKindFfi.SUBTITLE) }
    when (state) {
        is TrackChoice.Awaiting ->
            return if (announced) TrackStep(Resolving(state.p, rows, state.text, state.audio), listOf(TrackEffect.Resolve(state.p.session, rows))) else TrackStep(state)
        // The latest rows, so a superseded fallback resolves over what plays now.
        is CoreDeciding -> return TrackStep(if (announced) state.copy(rows = rows) else state)
        else -> Unit
    }
    // docs/18 §3.1: a transcode's picks read its rows as the picker shows them, once Media3 announced.
    val seen = if (event.announced && p.transcode) state.withAnnounced(rows) else state
    val text = seen.text as? TextPick.Track
    val on = text?.on
    // A transcode waits for Media3's announcement; Direct Play for one listing text.
    val heard = if (p.transcode) event.announced else rows.any { it.isPlayerRow() && it.kind == TrackKindFfi.SUBTITLE }
    if (text == null || !heard) return TrackStep(seen)
    if (on is TextPick.On.Shown || on == TextPick.On.Missing) return reshown(seen, p, text, rows)
    if (on == TextPick.On.Burned) return TrackStep(seen)
    shownTrack(p, TrackKindFfi.SUBTITLE, text.identity, text.stream, rows)?.let { return TrackStep(seen.withText(text.copy(on = TextPick.On.Shown(it.id))), listOf(showText(it.id))) }
    val due = text.copy(on = TextPick.On.Due)
    // docs/18 §3.1: a reload's text Media3 lacks stays off; a live pick it lacks needs the negotiation its pick would have started.
    if (on !is TextPick.On.Pending || !on.live || !needsNegotiation(seen.plan!!, due)) return TrackStep(seen.withText(text.copy(on = TextPick.On.Missing)))
    // The landing re-checks the choice, so no second negotiation starts.
    if (seen !is Decided) return TrackStep(seen.withText(due))
    val sent = wanted(seen.p, due, seen.audio)
    return TrackStep(Negotiating(seen.p, due, seen.audio, TranscodeCauseFfi.RENEGOTIATE, sent), listOf(Negotiate(Renegotiation.PICK, TranscodeCauseFfi.RENEGOTIATE, p.session, sent)))
}

/**
 * docs/18 §3.1: the Media3 track of [kind] in [rows] that shows a pick of [identity] on [stream]: on a
 * transcode only the one the overlay maps [stream] to, so no other track of its language stands in.
 */
private fun shownTrack(p: PlanFacts, kind: TrackKindFfi, identity: TrackIdentity, stream: Int?, rows: List<TrackInfo>): TrackInfo? =
    if (p.transcode) stream?.let { shownRenditions(p.rows, rows)[it] }?.takeIf { it.kind == kind } else matchTrack(identity, kind, rows.filter { it.isPlayerRow() })

/** docs/18 §3.1: a shown or missing [text] follows each announcement's ids and never negotiates, so a track Media3 drops only turns text off. */
private fun reshown(state: TrackChoice, p: PlanFacts, text: TextPick.Track, rows: List<TrackInfo>): TrackStep {
    val id = (text.on as? TextPick.On.Shown)?.id
    // Direct Play keeps the track on screen while Media3 lists it, so a twin the identity match can't tell apart never drops it.
    val kept = rows.firstOrNull { !p.transcode && it.id == id && it.kind == TrackKindFfi.SUBTITLE && it.identity() == text.identity }
    val track = kept ?: shownTrack(p, TrackKindFfi.SUBTITLE, text.identity, text.stream, rows)
    return when {
        track?.id == id -> TrackStep(state)
        track != null -> TrackStep(state.withText(text.copy(on = TextPick.On.Shown(track.id))), listOf(showText(track.id)))
        else -> TrackStep(state.withText(text.copy(on = TextPick.On.Missing)), listOf(TrackEffect.TextOff))
    }
}

private fun TrackChoice.withAnnounced(rows: List<TrackInfo>): TrackChoice = when (this) {
    is Decided -> copy(p = p.copy(announced = rows))
    is Negotiating -> copy(p = p.copy(announced = rows))
    else -> this
}

private fun resolved(state: Resolving, event: TrackEvent.Resolved): TrackStep {
    val p = state.p
    val rows = event.rows
    val pickedId = (state.audio as? AudioPick.Track)?.id
    val audioRow = (pickedId ?: event.decision.audioTrackId)?.let { id -> rows.firstOrNull { it.id == id } } ?: rows.selected(TrackKindFfi.AUDIO)
    val embeddedTextSelected = rows.selected(TrackKindFfi.SUBTITLE) != null
    val (decision, sidecar) = ExternalSubtitles.route(event.decision, p.sidecars, embeddedTextSelected, audioRow?.lang)
    val shown = decision.subtitleTrackId?.let { id -> rows.firstOrNull { it.id == id } }
    val resolvedText = when {
        sidecar != null -> sidecarPick(p, sidecar, byUser = false)
        shown != null -> TextPick.Track(shown.identity(), stream = null, on = TextPick.On.Shown(shown.id))
        decision.subtitleAction == SubtitleActionFfi.OFF -> TextPick.Off
        else -> null
    } ?: TextPick.Leave
    // docs/18 §3.1: a pick made before the resolve landed stands; the resolve decides only the other kind.
    val applied = TrackDecisionFfi(
        audioTrackId = decision.audioTrackId.takeIf { state.audio == null },
        subtitleAction = if (state.text == null) decision.subtitleAction else SubtitleActionFfi.LEAVE,
        subtitleTrackId = decision.subtitleTrackId.takeIf { state.text == null },
    )
    val text = state.text ?: resolvedText
    val fetch = (resolvedText as? TextPick.Sidecar)?.takeIf { state.text == null }?.let { listOf(TrackEffect.FetchSidecar(p.session, it.index)) }.orEmpty()
    val apply = TrackEffect.Apply(applied).takeIf { state.text == null || state.audio == null }
    return TrackStep(Decided(p, text, state.audio ?: audioPick(audioRow)), listOfNotNull(apply) + fetch)
}

private fun pickText(state: TrackChoice, p: PlanFacts, event: TrackEvent.PickText): TrackStep {
    val input = event.pick
    val gate = event.gate
    val old = state.text ?: TextPick.Leave
    val picked = when (input) {
        TextInput.Off -> TextPick.Off
        is TextInput.Player -> {
            // docs/18 §3.1: a transcode's rendition carries the stream the overlay maps it from; a row it doesn't map isn't on screen.
            val stream = if (!p.transcode) null else p.announced?.let { shownRenditions(p.rows, it) }?.entries?.firstOrNull { it.value.id == input.row.id }?.key ?: return TrackStep(state)
            TextPick.Track(input.row.identity(), stream, TextPick.On.Shown(input.row.id))
        }
        is TextInput.StreamRow -> {
            val stream = p.embedded.firstOrNull { it.index == input.index } ?: return TrackStep(state)
            TextPick.Track(stream.identity(), input.index, if (input.index == p.burned) TextPick.On.Burned else TextPick.On.Due)
        }
        is TextInput.Sidecar -> (old as? TextPick.Sidecar)?.takeIf { it.index == input.index }?.copy(byUser = true)
            ?: sidecarPick(p, input.index, byUser = true) ?: return TrackStep(state)
    }
    val needs = p.transcode && needsNegotiation(p, picked)
    // docs/18 §3.1: a rendition picked before Media3 announces waits for the announcement to show it or say only a negotiation does.
    val early = !needs && p.transcode && p.announced == null && picked is TextPick.Track && picked.on == TextPick.On.Due
    // docs/28 §5: a group never renegotiates, so it refuses a pick that needs one or may until Media3 announces.
    if (groupRefusesSubtitlePick(gate == Gate.GROUP, needs || early)) return TrackStep(state, listOf(TrackEffect.Refused))
    val text = if (early) (picked as TextPick.Track).copy(on = TextPick.On.Pending(live = gate == Gate.SOLO)) else picked
    if (text == old && state.text != null) return TrackStep(state)
    // docs/18 §3.2: a sidecar is remembered once it loads.
    val remember = TrackEffect.Remember(TrackKindFfi.SUBTITLE, rememberedRow(p, input, text)).takeIf { text !is TextPick.Sidecar || text.load == TextPick.Load.Shown }
    // docs/18 §3.1: a choice that needs a renegotiation it may not start only changes memory.
    val negotiates = state is Decided && needs
    if (negotiates && gate == Gate.NOT_LIVE) return TrackStep(state, listOfNotNull(remember))
    // The player's own default shows the row Media3 selected, so picking that one keeps its delay.
    val showing = old == TextPick.Leave && (input as? TextInput.Player)?.row?.isSelected == true
    val effects = textEffects(p, old, text).filterNot { showing && it == TrackEffect.ResetDelay } + listOfNotNull(remember)
    return when (state) {
        is TrackChoice.Awaiting -> TrackStep(state.copy(text = text), effects)
        is Resolving -> TrackStep(state.copy(text = text), effects)
        is Decided -> if (!negotiates) TrackStep(state.copy(text = text), effects) else {
            val sent = wanted(p, text, state.audio)
            TrackStep(
                Negotiating(p, text, state.audio, TranscodeCauseFfi.RENEGOTIATE, sent),
                effects + Negotiate(Renegotiation.PICK, TranscodeCauseFfi.RENEGOTIATE, p.session, sent),
            )
        }
        // The landing re-checks the choice, so no second negotiation starts.
        is Negotiating -> TrackStep(state.copy(text = text), effects)
        is CoreDeciding -> TrackStep(state.decidedBy(text, selectedAudio(state.rows)), effects)
        TrackChoice.Idle -> TrackStep(state)
    }
}

private fun CoreDeciding.decidedBy(text: TextPick, audio: AudioPick) =
    Negotiating(p, text, audio, TranscodeCauseFfi.FALLBACK, TranscodeStreamsFfi.Policy, died, landed)

private fun pickAudio(state: TrackChoice, p: PlanFacts, event: TrackEvent.PickAudio): TrackStep {
    val input = event.pick
    if (!p.transcode) {
        val row = (input as? AudioInput.Player)?.row ?: return TrackStep(state)
        val audio = audioPick(row)
        if (audio == state.audio) return TrackStep(state)
        val effects = listOf(playAudio(row.id), TrackEffect.Remember(TrackKindFfi.AUDIO, row))
        return when (state) {
            is TrackChoice.Awaiting -> TrackStep(state.copy(audio = audio), effects)
            is Resolving -> TrackStep(state.copy(audio = audio), effects)
            is Decided -> TrackStep(state.copy(audio = audio), effects)
            is Negotiating -> TrackStep(state.copy(audio = audio), effects)
            is CoreDeciding -> TrackStep(state.decidedBy(TextPick.Leave, audio), effects)
            TrackChoice.Idle -> TrackStep(state)
        }
    }
    // docs/18 §3.1: a transcode's HLS carries one audio stream, so its player track is the delivered one.
    val index = (input as? AudioInput.Stream)?.index ?: p.delivered ?: return TrackStep(state)
    val stream = p.audioStreams.firstOrNull { it.index == index } ?: return TrackStep(state)
    val audio = AudioPick.Track(streamAudioChoice(stream), id = null)
    val remember = p.rows.firstOrNull { it.kind == TrackKindFfi.AUDIO && it.streamIndex == index }?.let { TrackEffect.Remember(TrackKindFfi.AUDIO, it.info(selected = false)) }
    val negotiating = state is Negotiating
    return when (audioStreamPick(index, p.delivered, negotiating, inGroup = event.gate == Gate.GROUP)) {
        AudioStreamPick.UNCHANGED -> TrackStep(state)
        AudioStreamPick.REFUSED -> TrackStep(state, listOf(TrackEffect.Refused))
        AudioStreamPick.RENEGOTIATE -> when {
            state is Negotiating -> if (audio == state.audio) TrackStep(state) else TrackStep(state.copy(audio = audio), listOfNotNull(remember))
            state !is Decided || event.gate == Gate.NOT_LIVE -> TrackStep(state, listOfNotNull(remember))
            else -> {
                val sent = TranscodeStreamsFfi.Named(subtitleIndex(p, state.text), index)
                TrackStep(
                    Negotiating(p, state.text, audio, TranscodeCauseFfi.RENEGOTIATE, sent),
                    listOfNotNull(remember, Negotiate(Renegotiation.AUDIO_PICK, TranscodeCauseFfi.RENEGOTIATE, p.session, sent)),
                )
            }
        }
    }
}

private fun fallback(state: TrackChoice, p: PlanFacts): TrackStep {
    // docs/18 §2: undecided, the core runs the policy as a normal prepare would, and a pick made so far
    // stands as one made after; the plan's resolve is cancelled, its answer now meaningless.
    fun undecided(rows: List<TrackInfo>, text: TextPick?, audio: AudioPick?): TrackStep {
        val core = CoreDeciding(p, rows)
        val next = if (text == null && audio == null) core else core.decidedBy(text ?: TextPick.Leave, audio ?: selectedAudio(rows))
        return TrackStep(next, listOf(TrackEffect.NewPlanScope, Negotiate(Renegotiation.FALLBACK, TranscodeCauseFfi.FALLBACK, p.session, TranscodeStreamsFfi.Policy)))
    }
    return when (state) {
        is TrackChoice.Awaiting -> undecided(emptyList(), state.text, state.audio)
        is Resolving -> undecided(state.rows, state.text, state.audio)
        is Decided -> {
            val sent = wanted(p, state.text, state.audio)
            TrackStep(
                Negotiating(p, state.text, state.audio, TranscodeCauseFfi.FALLBACK, sent),
                listOf(Negotiate(Renegotiation.FALLBACK, TranscodeCauseFfi.FALLBACK, p.session, sent)),
            )
        }
        else -> TrackStep(state)
    }
}

private fun landed(state: TrackChoice, event: TrackEvent.Landed): TrackStep {
    val plan = event.plan
    val commit = listOf(TrackEffect.Commit(plan, event.from, event.sent))
    return when (state) {
        is CoreDeciding -> TrackStep(state.copy(landed = plan), commit)
        is Negotiating -> {
            // docs/18 §3.1: a landing the choice contradicts never loads; it is renegotiated before anything starts.
            val want = wanted(plan.trackFacts(), state.text, state.audio)
            if (!contradicts(want, carried(plan.trackFacts(), state.sent))) TrackStep(state.copy(landed = plan), commit)
            else TrackStep(state.copy(sent = want), listOf(Negotiate(Renegotiation.CHANGED, state.cause, state.p.session, want)))
        }
        else -> TrackStep(state)
    }
}

private fun committed(state: TrackChoice, plan: PlaybackPlan): TrackStep {
    val q = plan.trackFacts()
    val n = state as? Negotiating
    val choice = n?.text?.takeUnless { it == TextPick.Leave && n.sent == TranscodeStreamsFfi.Policy } ?: policyText(q)
    // docs/18 §3.2: sidecar indexes and their cues mean something only within one media source.
    val newSource = q.mediaSourceId != state.plan?.mediaSourceId
    val (text, textEffects) = reload(q, choice)
    val load = listOfNotNull(
        TrackEffect.Load(plan),
        TrackEffect.NewPlanScope,
        TrackEffect.ResetSidecarCache.takeIf { newSource },
        TrackEffect.ClosePicker.takeIf { newSource },
    )
    val effects = load + textEffects
    // A pick made while the commit ran is renegotiated from the plan now on screen.
    if (n == null || !contradicts(wanted(q, text, n.audio), carried(q, n.sent))) {
        val burned = q.burned
        val asked = subtitleIndex(q, text)
        if (burned == null || text == TextPick.Leave || asked == burned) return TrackStep(Decided(q, text, deliveredAudio(q)), effects)
        // docs/18 §3.1: a landing that burns a stream the choice didn't name shows that stream, and says so in the log.
        val (shown, shownEffects) = revert(q, text)
        return TrackStep(Decided(q, shown, deliveredAudio(q)), load + TrackEffect.BurnIgnored(asked, burned) + shownEffects)
    }
    val stream = q.audioStreams.firstOrNull { it.index == audioIndex(q, n.audio) }
    val audio = stream?.let { AudioPick.Track(streamAudioChoice(it), id = null) } ?: deliveredAudio(q)
    val sent = wanted(q, text, audio)
    return TrackStep(
        Negotiating(q, text, audio, TranscodeCauseFfi.RENEGOTIATE, sent),
        effects + Negotiate(Renegotiation.CHANGED, TranscodeCauseFfi.RENEGOTIATE, q.session, sent),
    )
}

private fun failed(state: TrackChoice, p: PlanFacts, event: TrackEvent.Failed): TrackStep {
    val outcome = event.outcome
    if (outcome == FailureOutcome.IGNORE) return gone(state, p)
    val n = state as? Negotiating
    val closing = outcome == FailureOutcome.CLOSE || outcome == FailureOutcome.REAUTHORIZE || outcome == FailureOutcome.QUIET_FINISH
    // docs/18 §2.1: a failed fallback or a dead player leaves nothing playing.
    if (n == null || n.cause == TranscodeCauseFfi.FALLBACK || n.died != null || closing) return ended(TrackEffect.Fatal(outcome, event.error))
    val want = wanted(p, n.text, n.audio)
    if (contradicts(want, carried(p, n.sent))) return TrackStep(n.copy(sent = want), listOf(Negotiate(Renegotiation.CHANGED, n.cause, p.session, want)))
    val step = gone(n, p)
    return step.copy(effects = step.effects + TrackEffect.Notice)
}

private fun gone(state: TrackChoice, p: PlanFacts): TrackStep {
    // docs/18 §2.1: the stream on screen is dead, so nothing is left to play on.
    state.died?.let { return ended(TrackEffect.PlayerLost(it)) }
    return when (state) {
        is Negotiating -> {
            val (text, effects) = revert(p, state.text)
            TrackStep(Decided(p, text, if (p.transcode) deliveredAudio(p) else state.audio), effects)
        }
        is CoreDeciding -> tracks(TrackChoice.Awaiting(p), p, TrackEvent.Tracks(p.session, state.rows))
        else -> TrackStep(state)
    }
}

/** Playback ends: [fatal] tells the viewer, and the plan's work and sidecar stop. */
private fun ended(fatal: TrackEffect) = TrackStep(TrackChoice.Idle, listOf(fatal, TrackEffect.CancelPlanScope, TrackEffect.HideSidecar))

private fun sidecarFetched(state: TrackChoice, p: PlanFacts, event: TrackEvent.SidecarFetched): TrackStep {
    val text = (state.text as? TextPick.Sidecar)?.takeIf { it.index == event.index && it.load == TextPick.Load.Loading(event.session) }
        // docs/18 §3.2: a file no longer chosen changes nothing; the fetch cached its cues as they landed.
        ?: return TrackStep(state)
    val remember = sidecarRow(p, text.index)?.takeIf { text.byUser }?.let { TrackEffect.Remember(TrackKindFfi.SUBTITLE, it) }
    return when {
        // docs/18 §3.1: never over the burn still on screen; the commit's reload shows it, remembered now.
        event.ok && state is Negotiating && p.burned != null ->
            TrackStep(state.copy(text = text.copy(load = TextPick.Load.Awaiting, byUser = false)), listOfNotNull(remember))
        event.ok -> TrackStep(state.withText(text.copy(load = TextPick.Load.Shown)), listOfNotNull(TrackEffect.ShowSidecar(text.index), remember))
        // docs/18 §3.2: a refusal while sessions swap is the old session's, so it is fetched again once the swap ends.
        state is Negotiating -> TrackStep(state.copy(text = text.copy(load = TextPick.Load.Awaiting)))
        else -> TrackStep(state.withText(TextPick.Off), listOf(TrackEffect.SidecarUnavailable(text.index), TrackEffect.HideSidecar))
    }
}

/**
 * docs/18 §3.1/§3.2: [text] on freshly loaded [q]: a stream burned in or left due, a sidecar fetched
 * again under [q], since the load cleared the overlay; the owner serves cues it cached without a fetch.
 */
private fun reload(q: PlanFacts, text: TextPick): Pair<TextPick, List<TrackEffect>> = when (text) {
    TextPick.Leave -> text to emptyList()
    TextPick.Off -> text to listOf(TrackEffect.TextOff)
    is TextPick.Track -> {
        // The stream carrying the choice on [q], whose rendition the overlay then shows; none matched shows nothing.
        val stream = subtitleIndex(q, text)?.takeIf { it != NO_SUBTITLE_STREAM }
        TextPick.Track(text.identity, stream, if (stream != null && stream == q.burned) TextPick.On.Burned else TextPick.On.Due) to listOf(TrackEffect.TextOff)
    }
    is TextPick.Sidecar -> {
        val rows = sidecarRows(q.rows)
        val same = rows.firstOrNull { it.id == ExternalSubtitles.idFor(text.index) && it.identity() == text.identity }
        val match = same ?: rows.firstOrNull { it.identity() == text.identity }
        val index = ExternalSubtitles.indexOf(match?.id)
        when {
            index == null -> TextPick.Off to listOf(TrackEffect.HideSidecar, TrackEffect.TextOff)
            else -> text.copy(index = index, load = TextPick.Load.Loading(q.session)) to listOf(TrackEffect.TextOff, TrackEffect.FetchSidecar(q.session, index))
        }
    }
}

/** docs/18 §3.1: what a failed or superseded negotiation leaves on [p]: only a text [p] delivers. */
private fun revert(p: PlanFacts, text: TextPick): Pair<TextPick, List<TrackEffect>> {
    val burned = p.burned
    val delivered = text == TextPick.Leave ||
        burned == null && (text == TextPick.Off || text is TextPick.Sidecar || (text as? TextPick.Track)?.on.let { it is TextPick.On.Shown || it is TextPick.On.Pending })
    if (delivered) {
        val sidecar = text as? TextPick.Sidecar
        if (sidecar == null || sidecar.load == TextPick.Load.Shown) return text to emptyList()
        return sidecar.copy(load = TextPick.Load.Loading(p.session)) to listOf(TrackEffect.FetchSidecar(p.session, sidecar.index))
    }
    val stream = p.embedded.firstOrNull { it.index == burned }
    val kept = when {
        stream == null -> TextPick.Off
        text is TextPick.Track && subtitleIndex(p, text) == burned -> text.copy(stream = burned, on = TextPick.On.Burned)
        else -> TextPick.Track(stream.identity(), burned, TextPick.On.Burned)
    }
    if (kept == text) return text to emptyList()
    return kept to listOfNotNull(TrackEffect.HideSidecar.takeIf { text is TextPick.Sidecar }, TrackEffect.TextOff)
}

/** docs/18 §3.1: the player effects of moving from [old] to [text] on [p], plus a delay reset for another track. */
private fun textEffects(p: PlanFacts, old: TextPick, text: TextPick): List<TrackEffect> {
    val sameSidecar = old is TextPick.Sidecar && text is TextPick.Sidecar && old.index == text.index
    val hide = TrackEffect.HideSidecar.takeIf { old is TextPick.Sidecar && !sameSidecar }
    val player = when (text) {
        TextPick.Leave -> emptyList()
        TextPick.Off -> listOf(TrackEffect.TextOff)
        is TextPick.Track -> listOf((text.on as? TextPick.On.Shown)?.let { showText(it.id) } ?: TrackEffect.TextOff)
        is TextPick.Sidecar -> if (sameSidecar) emptyList() else listOf(TrackEffect.TextOff, TrackEffect.FetchSidecar(p.session, text.index))
    }
    // docs/12 §16: a different subtitle starts its own sync.
    val reset = TrackEffect.ResetDelay.takeIf { rowId(old) != rowId(text) }
    return listOfNotNull(hide) + player + listOfNotNull(reset)
}

/** The picker row [text] names; null for the player's own default. */
private fun rowId(text: TextPick): Long? = when (text) {
    TextPick.Leave -> null
    TextPick.Off -> TRACK_PICKER_SUBTITLE_OFF_ID
    is TextPick.Sidecar -> ExternalSubtitles.idFor(text.index)
    is TextPick.Track -> (text.on as? TextPick.On.Shown)?.id ?: text.stream?.let(::streamRowId)
}

private fun rememberedRow(p: PlanFacts, input: TextInput, text: TextPick): TrackInfo? = when (input) {
    TextInput.Off -> null
    is TextInput.Player -> input.row
    is TextInput.StreamRow -> p.embedded.first { it.index == input.index }.let { it.toTrackInfo(streamRowId(it.index), isDefault = it.default, isSelected = false) }
    is TextInput.Sidecar -> sidecarRow(p, (text as TextPick.Sidecar).index)
}

/**
 * docs/18 §3.1: a transcode renegotiates when the choice names a stream [p]'s rows, as the picker
 * shows them, say only a negotiation delivers, or moves off the burned one; before Media3 announces, a rendition waits for it.
 */
private fun needsNegotiation(p: PlanFacts, text: TextPick): Boolean {
    val wanted = subtitleIndex(p, text) ?: return false
    val rows = p.announced?.let { overlaidRows(p.rows, it) } ?: p.rows
    val uncarried = rows.any { it.kind == TrackKindFfi.SUBTITLE && it.streamIndex == wanted && it.delivery == TrackDeliveryFfi.NEEDS_NEGOTIATION }
    return wanted != p.burned && (p.burned != null || uncarried)
}

/** The streams [q] must carry for the choice; null leaves the server's pick. */
private fun wanted(q: PlanFacts, text: TextPick, audio: AudioPick) = TranscodeStreamsFfi.Named(subtitleIndex(q, text), audioIndex(q, audio))

/** The streams a negotiation for [sent] put in [q]: the named ones, or the policy's run at prepare. */
private fun carried(q: PlanFacts, sent: TranscodeStreamsFfi): TranscodeStreamsFfi.Named = when (sent) {
    is TranscodeStreamsFfi.Named -> sent
    TranscodeStreamsFfi.Policy -> TranscodeStreamsFfi.Named(subtitleIndex(q, policyText(q)), q.delivered)
}

private fun contradicts(want: TranscodeStreamsFfi.Named, got: TranscodeStreamsFfi.Named) =
    (want.subtitle != null && want.subtitle != got.subtitle) || (want.audio != null && want.audio != got.audio)

private fun subtitleIndex(p: PlanFacts, text: TextPick): Int? = subtitleStreamIndexFor(
    when (text) {
        TextPick.Leave -> TextChoice.Leave
        TextPick.Off -> TextChoice.Off
        is TextPick.Sidecar -> TextChoice.Sidecar
        is TextPick.Track -> TextChoice.Embedded(text.identity, text.stream)
    },
    p.embedded,
)

private fun audioIndex(p: PlanFacts, audio: AudioPick): Int? = (audio as? AudioPick.Track)?.let { audioStreamIndexFor(it.choice, p.audioStreams) }

/** docs/18 §3.1: the choice [p]'s policy decision names, its ids being stream indexes. */
private fun policyText(p: PlanFacts): TextPick {
    val decision = p.policy ?: return TextPick.Leave
    val index = decision.subtitleTrackId?.toInt()
        ?: return if (decision.subtitleAction == SubtitleActionFfi.OFF) TextPick.Off else TextPick.Leave
    sidecarPick(p, index, byUser = false)?.let { return it }
    val stream = p.embedded.firstOrNull { it.index == index } ?: return TextPick.Leave
    return TextPick.Track(stream.identity(), index, TextPick.On.Due)
}

private fun sidecarRow(p: PlanFacts, index: Int): TrackInfo? = sidecarRows(p.rows).firstOrNull { it.id == ExternalSubtitles.idFor(index) }

private fun sidecarPick(p: PlanFacts, index: Int, byUser: Boolean): TextPick.Sidecar? =
    sidecarRow(p, index)?.let { TextPick.Sidecar(index, it.identity(), TextPick.Load.Loading(p.session), byUser) }

private fun deliveredAudio(p: PlanFacts): AudioPick =
    p.audioStreams.firstOrNull { it.index == p.delivered }?.let { AudioPick.Track(streamAudioChoice(it), id = null) } ?: AudioPick.NoAudio

private fun audioPick(row: TrackInfo?): AudioPick = row?.let { AudioPick.Track(audioChoiceOf(it, delivered = null), it.id) } ?: AudioPick.NoAudio

private fun selectedAudio(rows: List<TrackInfo>) = audioPick(rows.selected(TrackKindFfi.AUDIO))

/** A track Media3 announced, not a sidecar or a stream only a renegotiation shows. */
private fun TrackInfo.isPlayerRow() = id >= 0 && ExternalSubtitles.indexOf(id) == null

private fun List<TrackInfo>.selected(kind: TrackKindFfi) = firstOrNull { it.kind == kind && it.isSelected && it.isPlayerRow() }

private fun showText(id: Long) = TrackEffect.Apply(TrackDecisionFfi(audioTrackId = null, subtitleAction = SubtitleActionFfi.LEAVE, subtitleTrackId = id))

private fun playAudio(id: Long) = TrackEffect.Apply(TrackDecisionFfi(audioTrackId = id, subtitleAction = SubtitleActionFfi.LEAVE, subtitleTrackId = null))

/** docs/12 §16: what the picker marks selected and whether the dot shows, for [rows] as announced. */
internal fun selection(state: TrackChoice, rows: List<TrackInfo>): TrackSelection {
    val audioRows = rows.filter { it.kind == TrackKindFfi.AUDIO }
    val textRows = rows.filter { it.kind == TrackKindFfi.SUBTITLE }
    fun listed(id: Long?) = id?.takeIf { rows.any { row -> row.id == it } }
    val p = state.plan
    val audio = when (val pick = state.audio) {
        null -> audioRows.firstOrNull { it.isSelected }?.id
        AudioPick.NoAudio -> null
        is AudioPick.Track -> listed(pick.id) ?: listed(pick.choice.streamIndex?.let(::streamRowId))
            ?: p?.let { shownTrack(it, TrackKindFfi.AUDIO, pick.choice.identity, pick.choice.streamIndex, rows) }?.id
    }
    val text = when (val pick = state.text) {
        null, TextPick.Leave -> textRows.firstOrNull { it.isSelected }?.id
        TextPick.Off -> null
        is TextPick.Sidecar -> ExternalSubtitles.idFor(pick.index)
        is TextPick.Track -> when (val on = pick.on) {
            is TextPick.On.Shown -> on.id
            TextPick.On.Missing -> null
            TextPick.On.Due, TextPick.On.Burned, is TextPick.On.Pending -> listed(pick.stream?.let(::streamRowId))
                ?: p?.let { shownTrack(it, TrackKindFfi.SUBTITLE, pick.identity, pick.stream, rows) }?.id
        }
    } ?: TRACK_PICKER_SUBTITLE_OFF_ID
    // The dot: a non-default pick, or a default subtitle turned off; audio only where some track is flagged default.
    val selectedAudio = audioRows.firstOrNull { it.id == audio }
    val selectedText = textRows.firstOrNull { it.id == text }
    val nonDefault = selectedAudio != null && !selectedAudio.isDefault && audioRows.any { it.isDefault } ||
        selectedText != null && !selectedText.isDefault ||
        selectedText == null && textRows.any { it.isDefault }
    return TrackSelection(audio, text, nonDefault)
}
