package tv.jellybeam.player

import uniffi.jellybeam_core.AudioStreamFfi
import uniffi.jellybeam_core.EmbeddedSubtitleFfi
import uniffi.jellybeam_core.ExternalSubtitleFfi
import uniffi.jellybeam_core.PlayMethodFfi
import uniffi.jellybeam_core.PlaybackPlan
import uniffi.jellybeam_core.SubtitleDeliveryFfi
import uniffi.jellybeam_core.TrackDeliveryFfi
import uniffi.jellybeam_core.TrackKindFfi
import uniffi.jellybeam_core.TrackRowFfi

/**
 * The test fixtures' one copy of core/ffi types.rs `transcode_track_rows`: audio, then the Hls,
 * Encode and External subtitle streams, then sidecars. [delivered] and [burned] are selected; Hls
 * streams are renditions only when [renditions] (the URL names one) and nothing burns.
 */
internal fun coreTrackRows(
    audio: List<AudioStreamFfi>,
    embedded: List<EmbeddedSubtitleFfi>,
    sidecars: List<ExternalSubtitleFfi>,
    delivered: Int?,
    burned: Int?,
    renditions: Boolean,
): List<TrackRowFfi> {
    val manifest = renditions && burned == null
    val audioRows = audio.map {
        val selected = it.index == delivered
        TrackRowFfi(
            kind = TrackKindFfi.AUDIO, streamIndex = it.index, title = it.title, displayTitle = it.displayTitle, lang = it.language, codec = it.codec,
            isDefault = it.isDefault, isForced = false, isHearingImpaired = false,
            delivery = if (selected) TrackDeliveryFfi.DELIVERED_AUDIO else TrackDeliveryFfi.SOURCE_AUDIO, isSelected = selected,
        )
    }
    val offered = setOf(SubtitleDeliveryFfi.HLS, SubtitleDeliveryFfi.ENCODE, SubtitleDeliveryFfi.EXTERNAL)
    val textRows = embedded.filter { it.delivery in offered }.map {
        val delivery = when {
            it.index == burned -> TrackDeliveryFfi.BURNED
            manifest && it.delivery == SubtitleDeliveryFfi.HLS -> TrackDeliveryFfi.RENDITION
            else -> TrackDeliveryFfi.NEEDS_NEGOTIATION
        }
        TrackRowFfi(
            kind = TrackKindFfi.SUBTITLE, streamIndex = it.index, title = it.title, displayTitle = it.displayTitle, lang = it.language, codec = it.codec,
            isDefault = it.default, isForced = it.forced, isHearingImpaired = it.hearingImpaired, delivery = delivery, isSelected = it.index == burned,
        )
    }
    return audioRows + textRows + coreSidecarRows(sidecars)
}

/** The fixtures' copy of core/ffi types.rs `sidecar_track_rows`: every plan's rows end with these. */
internal fun coreSidecarRows(sidecars: List<ExternalSubtitleFfi>): List<TrackRowFfi> = sidecars.map {
    TrackRowFfi(
        kind = TrackKindFfi.SUBTITLE, streamIndex = it.index, title = it.displayTitle, displayTitle = it.displayTitle, lang = it.language, codec = it.codec,
        isDefault = it.isDefault, isForced = it.isForced, isHearingImpaired = it.hearingImpaired, delivery = TrackDeliveryFfi.SIDECAR, isSelected = false,
    )
}

/** [this] with the rows the core gives it: its sidecars on Direct Play; a transcode's from its own streams. */
internal fun PlaybackPlan.withCoreRows(renditions: Boolean = true): PlaybackPlan = copy(
    trackRows = if (playMethod != PlayMethodFfi.TRANSCODE) {
        coreSidecarRows(externalSubtitles)
    } else {
        coreTrackRows(audioStreams, embeddedSubtitles, externalSubtitles, deliveredAudioIndex, burnedSubtitleIndex, renditions)
    },
)
