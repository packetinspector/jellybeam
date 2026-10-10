package tv.jellybeam.player.ass

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.CmcdConfiguration
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.util.ReleasableExecutor
import androidx.media3.extractor.text.SubtitleParser
import com.google.common.base.Supplier

/**
 * docs/13: builds a fresh factory per item, each around `fonts.forItem()`, so the sink's generation is
 * taken when ExoPlayer creates the item's media source (application thread, inside `setMediaItem`, after
 * `load`'s generation bump), not when a loader thread makes the extractor, which an old item can do late.
 * Setters are remembered and applied to every factory built; the last call per setter wins.
 */
@OptIn(UnstableApi::class)
class ItemBoundMediaSourceFactory(
    private val fonts: AssFontSink,
    private val build: (AssFontSink) -> MediaSource.Factory,
) : MediaSource.Factory {
    private val settings = LinkedHashMap<String, (MediaSource.Factory) -> Unit>()

    private val probe: MediaSource.Factory by lazy { build(fonts) }

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val factory = build(fonts.forItem())
        settings.values.forEach { it(factory) }
        return factory.createMediaSource(mediaItem)
    }

    override fun getSupportedTypes(): IntArray = probe.supportedTypes

    override fun setDrmSessionManagerProvider(drmSessionManagerProvider: DrmSessionManagerProvider): MediaSource.Factory =
        remember("drm") { it.setDrmSessionManagerProvider(drmSessionManagerProvider) }

    override fun setLoadErrorHandlingPolicy(loadErrorHandlingPolicy: LoadErrorHandlingPolicy): MediaSource.Factory =
        remember("policy") { it.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy) }

    override fun setCmcdConfigurationFactory(cmcdConfigurationFactory: CmcdConfiguration.Factory): MediaSource.Factory =
        remember("cmcd") { it.setCmcdConfigurationFactory(cmcdConfigurationFactory) }

    override fun setSubtitleParserFactory(subtitleParserFactory: SubtitleParser.Factory): MediaSource.Factory =
        remember("subtitleParser") { it.setSubtitleParserFactory(subtitleParserFactory) }

    @Deprecated("Media3 deprecation; forwarded as-is.")
    @Suppress("DEPRECATION")
    @androidx.annotation.OptIn(androidx.media3.common.util.ExperimentalApi::class)
    override fun experimentalParseSubtitlesDuringExtraction(parseSubtitlesDuringExtraction: Boolean): MediaSource.Factory =
        remember("parseSubtitles") { it.experimentalParseSubtitlesDuringExtraction(parseSubtitlesDuringExtraction) }

    @androidx.annotation.OptIn(androidx.media3.common.util.ExperimentalApi::class)
    override fun experimentalSetCodecsToParseWithinGopSampleDependencies(codecsToParseWithinGopSampleDependencies: Int): MediaSource.Factory =
        remember("gopCodecs") { it.experimentalSetCodecsToParseWithinGopSampleDependencies(codecsToParseWithinGopSampleDependencies) }

    override fun setDownloadExecutor(downloadExecutor: Supplier<ReleasableExecutor>): MediaSource.Factory =
        remember("downloadExecutor") { it.setDownloadExecutor(downloadExecutor) }

    private fun remember(key: String, apply: (MediaSource.Factory) -> Unit): MediaSource.Factory {
        settings[key] = apply
        return this
    }
}
