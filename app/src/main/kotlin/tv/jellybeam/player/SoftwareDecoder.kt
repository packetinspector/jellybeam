package tv.jellybeam.player

/**
 * docs/18-playback-quality.md §1.1's third local-evidence signal for the Auto/Cap transcode
 * fallback: a software-only video decoder counts as "no hardware decoder on this TV" (see
 * [PlaybackViewModel]'s `onVideoDecoderInitialized` hook, via [isSoftwareOnly]). Per §3, this
 * object never touches `MediaCodecList` itself -- [DeviceCapsProbe.probeUnsafe] enumerates it once
 * at app start, publishing via [rememberProbedSoftwareDecoders].
 */
internal object SoftwareDecoder {
    /** Decoder name prefixes treated as software, even unseen by [probedSoftwareDecoders]. */
    private val SOFTWARE_PREFIXES = listOf(
        "OMX.google.",
        "c2.android.",
        "OMX.ffmpeg.",
        "c2.ffmpeg.",
    )

    /** Substrings some OEM software decoders carry instead of/alongside [SOFTWARE_PREFIXES]. */
    private val SOFTWARE_SUBSTRINGS = listOf(".sw.", ".SW.", ".soft.")

    /**
     * Software-only decoder names published by [DeviceCapsProbe]'s startup probe via
     * [rememberProbedSoftwareDecoders]. `@Volatile`: probed on a background coroutine, read from
     * the player's callback thread; empty until it lands, when [isSoftwareOnly] falls back to
     * [matchesNameHeuristic].
     */
    @Volatile
    private var probedSoftwareDecoders: Set<String> = emptySet()

    /** Publishes [DeviceCapsProbe]'s probed names, at the end of `probeUnsafe()` so a partial throw
     * never publishes a half-built set.
     */
    fun rememberProbedSoftwareDecoders(names: Set<String>) {
        probedSoftwareDecoders = names
    }

    /** `true` when [decoderName] matches [SOFTWARE_PREFIXES]/[SOFTWARE_SUBSTRINGS] --
     * [isSoftwareOnly]'s fallback, and how [DeviceCapsProbe.probeUnsafe] classifies below API 29.
     */
    fun matchesNameHeuristic(decoderName: String): Boolean =
        SOFTWARE_PREFIXES.any { decoderName.startsWith(it) } || SOFTWARE_SUBSTRINGS.any { decoderName.contains(it) }

    /** `true` when [decoderName] names a software-only decoder: a probed hit wins, else
     * [matchesNameHeuristic] catches what the probe missed.
     */
    fun isSoftwareOnly(decoderName: String): Boolean =
        decoderName in probedSoftwareDecoders || matchesNameHeuristic(decoderName)
}
