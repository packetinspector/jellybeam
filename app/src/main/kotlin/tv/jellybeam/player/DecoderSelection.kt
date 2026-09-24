package tv.jellybeam.player

import android.content.Context
import android.os.Handler
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import tv.jellybeam.perf.PerfLog

/** Per-session local audio-decoder overrides; none of these affect server negotiation. */
data class AudioDecoderPreferences(
    val preferFfmpegTrueHd: Boolean = false,
    val preferFfmpegDts: Boolean = false,
    val preferFfmpegDtsHd: Boolean = false,
)

/**
 * Whether Media3's platform audio renderer should stand aside for the bundled FFmpeg renderer.
 * Matroska commonly exposes DTS-HD MA as the ambiguous base [MimeTypes.AUDIO_DTS], so the DTS-HD
 * preference intentionally matches that MIME too -- either DTS switch fixes an ambiguously-labelled
 * track; a truly distinct DTS-HD/DTS:X MIME follows only its own switch.
 */
@OptIn(UnstableApi::class)
fun shouldPreferFfmpegAudio(sampleMimeType: String?, preferences: AudioDecoderPreferences): Boolean =
    when (sampleMimeType) {
        MimeTypes.AUDIO_TRUEHD -> preferences.preferFfmpegTrueHd
        MimeTypes.AUDIO_DTS -> preferences.preferFfmpegDts || preferences.preferFfmpegDtsHd
        MimeTypes.AUDIO_DTS_EXPRESS -> preferences.preferFfmpegDts
        MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_DTS_X -> preferences.preferFfmpegDtsHd
        else -> false
    }

/**
 * Bogus-declared-level tolerance (CLAUDE.md task evidence): some HEVC Main10 HDR10 files declare an
 * inflated bitstream level meant for far higher resolutions, while a device's real decoder ceiling
 * is honest and lower. [MediaCodecVideoRenderer.getDecoderInfos] (not [MediaCodecSelector], which
 * gets
 * no [Format]/level) is where a level actually gets checked; [MediaCodecInfo] rejects a hardware
 * decoder whose real level ceiling is exceeded by the bitstream's inflated claim, and a software
 * decoder for the mime then sorts ahead of it.
 *
 * [LevelTolerantMediaCodecVideoRenderer] is the fix: when normal selection leads with software,
 * retry
 * with [Format.codecs] blanked out (profile+level stripped) so the capability check has nothing to
 * reject the hardware decoder on, and use that ordering if it surfaces one.
 * [JellybeamRenderersFactory]
 * is the [DefaultRenderersFactory] override needed to install the subclass, since media3 gives no
 * narrower extension point.
 *
 * Neither class touches HDR signaling -- HDR10/DoVi metadata flows through the existing pipeline
 * unchanged; only which decoder is chosen changes. Gated behind `Settings.tolerateMislabeledLevels`
 * (default `true`, mirrored by `tolerate_mislabeled_levels` on the Rust side); off, this class
 * behaves
 * exactly like stock `MediaCodecVideoRenderer`.
 */

/**
 * Which decoder-info list [LevelTolerantMediaCodecVideoRenderer.getDecoderInfos] should hand back.
 * Pulled into its own enum + pure [chooseDecoderList] function so the "hw-with-level >
 * hw-ignoring-level
 * > software" ordering rule is JVM-testable without a real [MediaCodecInfo]/[Format] pair, which
 * isn't
 * constructible from a plain JVM test.
 */
enum class DecoderListChoice { NORMAL, LEVEL_STRIPPED }

/**
 * `normalFirstIsHardware`/`strippedFirstIsHardware`: does that list's top candidate report
 * `hardwareAccelerated` (`false` also covers an empty list). `tolerateMislabeledLevels` (the
 * `Settings.tolerateMislabeledLevels` toggle, default `true`, mirrors `tolerate_mislabeled_levels`
 * on
 * the Rust side): `false` always returns [NORMAL][DecoderListChoice.NORMAL]. Otherwise
 * hw-with-level
 * always wins outright; else [LEVEL_STRIPPED] only if it found hardware the normal pass didn't.
 */
fun chooseDecoderList(
    normalFirstIsHardware: Boolean,
    strippedFirstIsHardware: Boolean,
    tolerateMislabeledLevels: Boolean = true,
): DecoderListChoice =
    if (!tolerateMislabeledLevels) {
        DecoderListChoice.NORMAL
    } else if (!normalFirstIsHardware && strippedFirstIsHardware) {
        DecoderListChoice.LEVEL_STRIPPED
    } else {
        DecoderListChoice.NORMAL
    }

@OptIn(UnstableApi::class)
private class LevelTolerantMediaCodecVideoRenderer(
    builder: MediaCodecVideoRenderer.Builder,
    /**
     * Advanced-settings toggle (`Settings.tolerateMislabeledLevels`, default `true`); a supplier
     * rather
     * than a plain value because this renderer is built once for the process lifetime inside
     * [PlayerHolder]'s shared player, so a settings change can take effect without a rebuild.
     */
    private val tolerateMislabeledLevels: () -> Boolean,
) : MediaCodecVideoRenderer(builder) {

    @OptIn(UnstableApi::class)
    override fun getDecoderInfos(
        mediaCodecSelector: MediaCodecSelector,
        format: Format,
        requiresSecureDecoder: Boolean,
    ): List<MediaCodecInfo> {
        val normal = super.getDecoderInfos(mediaCodecSelector, format, requiresSecureDecoder)
        val firstChoice = normal.firstOrNull()
        val tolerate = tolerateMislabeledLevels()

        // Toggle off: fully inert, stock media3 selection stands. Toggle on and normal selection
        // already leads with hardware: nothing to retry either way.
        if (!tolerate || firstChoice == null || firstChoice.hardwareAccelerated || format.codecs.isNullOrEmpty()) {
            logDecoderChoice(firstChoice, levelStripped = false)
            return normal
        }

        // media3 led with software: retry with profile+level blanked out so a decoder that only
        // failed on the (possibly bogus) level gets a fair second look.
        val levelStrippedFormat = format.buildUpon().setCodecs(null).build()
        val stripped = super.getDecoderInfos(mediaCodecSelector, levelStrippedFormat, requiresSecureDecoder)
        val strippedChoice = stripped.firstOrNull()

        return when (
            chooseDecoderList(
                normalFirstIsHardware = false, // already excluded above
                strippedFirstIsHardware = strippedChoice?.hardwareAccelerated == true,
                tolerateMislabeledLevels = tolerate, // already true here, but keep the call site honest
            )
        ) {
            DecoderListChoice.LEVEL_STRIPPED -> {
                // Hardware claims the mime once the disputed level stops being checked.
                logDecoderChoice(strippedChoice, levelStripped = true)
                stripped
            }
            DecoderListChoice.NORMAL -> {
                // No hardware candidate at any level; media3's own choice stands.
                logDecoderChoice(firstChoice, levelStripped = false)
                normal
            }
        }
    }

    /** One grep-able line per playback start, not a hot per-frame path. `adb logcat -s JellybeamTV:D |
     * grep "perf decoder"` verifies the fix is live on-device.
     */
    private fun logDecoderChoice(info: MediaCodecInfo?, levelStripped: Boolean) {
        if (!PerfLog.enabled) return
        Log.d(
            PerfLog.TAG,
            "perf decoder name=${info?.name ?: "none"} hardware=${info?.hardwareAccelerated ?: false} levelStripped=$levelStripped",
        )
    }
}

/**
 * [DefaultRenderersFactory] override that swaps in [LevelTolerantMediaCodecVideoRenderer] for the
 * video renderer ([PlayerHolder.buildPlayer] uses this in place of a plain
 * `DefaultRenderersFactory`).
 * [buildVideoRenderers] is fully replaced rather than composed: media3 gives no narrower extension
 * point, and [MediaCodecVideoRenderer]'s constructor is `protected` and `Builder`-based, so a
 * subclass
 * must build the same `Builder` itself.
 *
 * Never adds a video decoder extension renderer: Jellybeam TV ships only the ffmpeg *audio* extension.
 */
@OptIn(UnstableApi::class)
class JellybeamRenderersFactory(
    context: Context,
    /** Threaded straight through to [LevelTolerantMediaCodecVideoRenderer]'s identically-named
     * parameter.
     */
    private val tolerateMislabeledLevels: () -> Boolean = { true },
    /** Read at format-selection time so the shared player need not be rebuilt after a settings
     * change.
     */
    private val audioDecoderPreferences: () -> AudioDecoderPreferences = { AudioDecoderPreferences() },
) : DefaultRenderersFactory(context) {

    /**
     * Platform renderer that opts out only for a user-selected audio codec. Required even for
     * TrueHD
     * passthrough: MediaCodecAudioRenderer otherwise accepts a sink-supported format before
     * consulting
     * MediaCodecSelector. The stock FFmpeg extension renderer, next in the list, decodes it locally
     * instead.
     */
    private class PreferenceAwareMediaCodecAudioRenderer(
        context: Context,
        codecAdapterFactory: MediaCodecAdapter.Factory,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        audioSink: AudioSink,
        private val preferences: () -> AudioDecoderPreferences,
    ) : MediaCodecAudioRenderer(
        context,
        codecAdapterFactory,
        mediaCodecSelector,
        enableDecoderFallback,
        eventHandler,
        eventListener,
        audioSink,
    ) {
        override fun supportsFormat(mediaCodecSelector: MediaCodecSelector, format: Format): Int =
            if (shouldPreferFfmpegAudio(format.sampleMimeType, preferences())) {
                RendererCapabilities.create(C.FORMAT_UNSUPPORTED_SUBTYPE)
            } else {
                super.supportsFormat(mediaCodecSelector, format)
            }
    }

    /**
     * docs/18 §5.1: the stock sink with its outputs wrapped in [PlausibleClockAudioOutput], so a
     * freshly created passthrough track can't report a position ahead of what it has played.
     */
    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParams: Boolean): AudioSink =
        DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
            .setAudioOutputProvider(PlausibleClockAudioOutputProvider(AudioTrackAudioOutputProvider.Builder(context).build()))
            .build()

    @OptIn(UnstableApi::class)
    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>,
    ) {
        out.add(
            LevelTolerantMediaCodecVideoRenderer(
                MediaCodecVideoRenderer.Builder(context)
                    .setMediaCodecSelector(mediaCodecSelector)
                    .setCodecAdapterFactory(codecAdapterFactory)
                    .setAllowedJoiningTimeMs(allowedVideoJoiningTimeMs)
                    .setEnableDecoderFallback(enableDecoderFallback)
                    .setEventHandler(eventHandler)
                    .setEventListener(eventListener)
                    .setMaxDroppedFramesToNotify(DefaultRenderersFactory.MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY),
                tolerateMislabeledLevels = tolerateMislabeledLevels,
            ),
        )
    }

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>,
    ) {
        // Let Media3 load/order its FFmpeg extension, then replace only the stock platform
        // renderer.
        val stock = ArrayList<Renderer>()
        super.buildAudioRenderers(
            context,
            extensionRendererMode,
            mediaCodecSelector,
            enableDecoderFallback,
            audioSink,
            eventHandler,
            eventListener,
            stock,
        )
        val platformIndex = stock.indexOfFirst { it is MediaCodecAudioRenderer }
        check(platformIndex >= 0) { "Media3 did not create its platform audio renderer" }
        stock[platformIndex] = PreferenceAwareMediaCodecAudioRenderer(
            context = context,
            codecAdapterFactory = codecAdapterFactory,
            mediaCodecSelector = mediaCodecSelector,
            enableDecoderFallback = enableDecoderFallback,
            eventHandler = eventHandler,
            eventListener = eventListener,
            audioSink = audioSink,
            preferences = audioDecoderPreferences,
        )
        out.addAll(stock)
    }
}
