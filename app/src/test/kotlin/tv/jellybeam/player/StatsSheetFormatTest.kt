package tv.jellybeam.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.jellybeam_core.MediaStreamInfo
import uniffi.jellybeam_core.MediaStreamKind
import uniffi.jellybeam_core.PlaybackOsdDetail
import uniffi.jellybeam_core.PlayMethodFfi

/** [StatsSheetFormat] is pure Kotlin (docs/jellybeam-osd-handoff §8b) -- exercised directly, no Compose/ViewModel needed. */
class StatsSheetFormatTest {

    private fun stream(
        streamType: MediaStreamKind,
        codec: String? = null,
        language: String? = null,
        width: Int? = null,
        height: Int? = null,
        channels: Int? = null,
        bitRate: Int? = null,
        isDefault: Boolean = false,
        profile: String? = null,
        sampleRate: Int? = null,
        avgFrameRate: Float? = null,
    ) = MediaStreamInfo(
        index = 0,
        streamType = streamType,
        codec = codec,
        language = language,
        displayTitle = null,
        width = width,
        height = height,
        channels = channels,
        bitRate = bitRate,
        bitDepth = null,
        isDefault = isDefault,
        videoRange = null,
        videoRangeType = null,
        profile = profile,
        sampleRate = sampleRate,
        avgFrameRate = avgFrameRate,
    )

    private fun detail(
        mediaStreams: List<MediaStreamInfo> = emptyList(),
        container: String? = null,
        sizeBytes: Long? = null,
        path: String? = null,
    ) = PlaybackOsdDetail(
        container = container,
        mediaStreams = mediaStreams,
        chapters = emptyList(),
        sizeBytes = sizeBytes,
        path = path,
    )

    private fun liveStats(
        bufferedAheadMs: Long,
        droppedFrames: Long,
        allocatedBufferBytes: Long = 0L,
        bandwidthBytesPerSecond: Long = 0L,
        state: PlaybackLiveState = PlaybackLiveState.PLAYING,
    ) = PlaybackLiveStats(
        bufferedAheadMs = bufferedAheadMs,
        allocatedBufferBytes = allocatedBufferBytes,
        bandwidthBytesPerSecond = bandwidthBytesPerSecond,
        state = state,
        droppedFrames = droppedFrames,
    )

    @Test
    fun `headline defaults to Direct Play with an accented no-transcode span`() {
        val content = StatsSheetFormat.build(detail(), selectedSubtitleTitle = null, subtitleCount = 0, serverName = null, live = null)
        assertEquals(listOf(StatsSpan("Direct Play · "), StatsSpan("no transcode", accent = true)), content.headline)
    }

    // docs/18-playback-quality.md §3: "Transcoding · <transcode_reason>" once playMethod flips.
    @Test
    fun `headline reads Transcoding with the reason accented once playMethod is TRANSCODE`() {
        val content = StatsSheetFormat.build(
            detail(),
            selectedSubtitleTitle = null,
            subtitleCount = 0,
            serverName = null,
            live = null,
            playMethod = PlayMethodFfi.TRANSCODE,
            transcodeReason = "bitrate above cap",
        )
        assertEquals(listOf(StatsSpan("Transcoding · "), StatsSpan("bitrate above cap", accent = true)), content.headline)
    }

    @Test
    fun `a fully populated detail produces every row in order`() {
        val video = stream(
            MediaStreamKind.VIDEO,
            codec = "hevc",
            profile = "Main 10",
            width = 1920,
            height = 1080,
            bitRate = 12_300_000,
            avgFrameRate = 23.976f,
        )
        val audio = stream(
            MediaStreamKind.AUDIO,
            codec = "eac3",
            language = "eng",
            channels = 6,
            bitRate = 448_000,
            sampleRate = 48_000,
            isDefault = true,
        )
        val d = detail(
            mediaStreams = listOf(video, audio),
            container = "mkv",
            sizeBytes = 1_500_000_000L,
            path = "/media/Show/Show.S01E01.1080p.[Group].mkv",
        )

        val content = StatsSheetFormat.build(
            detail = d,
            selectedSubtitleTitle = "English SDH",
            subtitleCount = 3,
            serverName = "example-server",
            live = liveStats(
                bufferedAheadMs = 42_000L,
                allocatedBufferBytes = 32L * 1024L * 1024L,
                bandwidthBytesPerSecond = 2_000_000L,
                droppedFrames = 0L,
            ),
        )

        assertEquals(
            listOf(
                StatsRow("VIDEO", "HEVC · Main 10 · 1920×1080 · 23.976fps · 12.3Mb/s"),
                StatsRow("AUDIO", "EAC3 5.1 · 448kb/s · 48kHz · ENG"),
                StatsRow("SUBTITLES", "English SDH · 3 available"),
                StatsRow("CONTAINER", "MKV · 1.4 GB"),
                StatsRow("SOURCE", "example-server"),
                StatsRow("BUFFER", "42.0s ahead · 32.0 MiB"),
                StatsRow("NETWORK", "16.00 Mbit/s"),
                StatsRow("HEALTH", "Playing · 0 dropped"),
            ),
            content.rows,
        )
        assertEquals("Show.S01E01.1080p.[Group].mkv", content.fileName)
    }

    @Test
    fun `a sparse detail with no streams, container, server, or live stats only shows Subtitles`() {
        val content = StatsSheetFormat.build(
            detail = detail(),
            selectedSubtitleTitle = null,
            subtitleCount = 0,
            serverName = null,
            live = null,
        )
        assertEquals(listOf(StatsRow("SUBTITLES", "Off · 0 available")), content.rows)
        assertNull(content.fileName)
    }

    @Test
    fun `a null path never produces a file name`() {
        val content = StatsSheetFormat.build(detail(path = null), null, 0, null, null)
        assertNull(content.fileName)
    }

    @Test
    fun `fileName strips the directory using either slash convention`() {
        assertEquals(
            "Movie.mkv",
            StatsSheetFormat.build(detail(path = "/mnt/media/Movie.mkv"), null, 0, null, null).fileName,
        )
        assertEquals(
            "Movie.mkv",
            StatsSheetFormat.build(detail(path = "C:\\media\\Movie.mkv"), null, 0, null, null).fileName,
        )
    }

    @Test
    fun `video row drops missing pieces but keeps the ones that resolve`() {
        val video = stream(MediaStreamKind.VIDEO, codec = "h264", width = 1280, height = 720)
        val content = StatsSheetFormat.build(detail(mediaStreams = listOf(video)), null, 0, null, null)
        assertEquals("H264 · 1280×720", content.rows.first { it.label == "VIDEO" }.value)
    }

    @Test
    fun `video row preserves exact cropped dimensions`() {
        val video = stream(MediaStreamKind.VIDEO, codec = "hevc", width = 1920, height = 960)
        val content = StatsSheetFormat.build(detail(mediaStreams = listOf(video)), null, 0, null, null)
        assertEquals("HEVC · 1920×960", content.rows.first { it.label == "VIDEO" }.value)
    }

    @Test
    fun `video row falls back to a width-derived resolution when height is absent`() {
        val video = stream(MediaStreamKind.VIDEO, codec = "av1", width = 3840, height = null)
        val content = StatsSheetFormat.build(detail(mediaStreams = listOf(video)), null, 0, null, null)
        assertEquals("AV1 · 2160p", content.rows.first { it.label == "VIDEO" }.value)
    }

    @Test
    fun `no video stream at all omits the VIDEO row entirely`() {
        val audio = stream(MediaStreamKind.AUDIO, codec = "aac")
        val content = StatsSheetFormat.build(detail(mediaStreams = listOf(audio)), null, 0, null, null)
        assertTrue(content.rows.none { it.label == "VIDEO" })
    }

    @Test
    fun `no audio stream at all omits the AUDIO row entirely`() {
        val video = stream(MediaStreamKind.VIDEO, codec = "hevc")
        val content = StatsSheetFormat.build(detail(mediaStreams = listOf(video)), null, 0, null, null)
        assertTrue(content.rows.none { it.label == "AUDIO" })
    }

    @Test
    fun `the default audio stream is preferred over the first stream when both exist`() {
        val nonDefault = stream(MediaStreamKind.AUDIO, codec = "ac3", isDefault = false)
        val default = stream(MediaStreamKind.AUDIO, codec = "truehd", isDefault = true)
        val content = StatsSheetFormat.build(detail(mediaStreams = listOf(nonDefault, default)), null, 0, null, null)
        assertTrue(content.rows.first { it.label == "AUDIO" }.value.startsWith("TRUEHD"))
    }

    @Test
    fun `fps formatting trims trailing zeros including a whole-number rate`() {
        val whole = stream(MediaStreamKind.VIDEO, codec = "h264", avgFrameRate = 24.0f)
        val fractional = stream(MediaStreamKind.VIDEO, codec = "h264", avgFrameRate = 29.97f)
        assertEquals(
            "H264 · 24fps",
            StatsSheetFormat.build(detail(mediaStreams = listOf(whole)), null, 0, null, null).rows.first { it.label == "VIDEO" }.value,
        )
        assertEquals(
            "H264 · 29.97fps",
            StatsSheetFormat.build(detail(mediaStreams = listOf(fractional)), null, 0, null, null).rows.first { it.label == "VIDEO" }.value,
        )
    }

    @Test
    fun `kHz formatting shows one decimal only when non-integral`() {
        val integral = stream(MediaStreamKind.AUDIO, codec = "aac", sampleRate = 48_000)
        val fractional = stream(MediaStreamKind.AUDIO, codec = "flac", sampleRate = 44_100)
        assertEquals(
            "AAC · 48kHz",
            StatsSheetFormat.build(detail(mediaStreams = listOf(integral)), null, 0, null, null).rows.first { it.label == "AUDIO" }.value,
        )
        assertEquals(
            "FLAC · 44.1kHz",
            StatsSheetFormat.build(detail(mediaStreams = listOf(fractional)), null, 0, null, null).rows.first { it.label == "AUDIO" }.value,
        )
    }

    @Test
    fun `container row drops the size when absent and drops entirely when both are absent`() {
        val withExtOnly = detail(container = "mp4")
        assertEquals(
            "MP4",
            StatsSheetFormat.build(withExtOnly, null, 0, null, null).rows.first { it.label == "CONTAINER" }.value,
        )
        val withNeither = detail()
        assertTrue(StatsSheetFormat.build(withNeither, null, 0, null, null).rows.none { it.label == "CONTAINER" })
    }

    @Test
    fun `SOURCE row is omitted when serverName is null`() {
        val content = StatsSheetFormat.build(detail(), null, 0, serverName = null, live = null)
        assertTrue(content.rows.none { it.label == "SOURCE" })
    }

    @Test
    fun `live rows are omitted while live is null and preserve every available metric`() {
        val staticRows = StatsSheetFormat.build(detail(), null, 0, null, live = null).rows
        assertTrue(staticRows.none { it.label in setOf("BUFFER", "NETWORK", "HEALTH") })

        val content = StatsSheetFormat.build(
            detail(),
            null,
            0,
            null,
            live = liveStats(
                bufferedAheadMs = 7_250L,
                allocatedBufferBytes = 3L * 1024L * 1024L,
                bandwidthBytesPerSecond = 1_250_000L,
                state = PlaybackLiveState.BUFFERING,
                droppedFrames = 12L,
            ),
        )
        assertEquals("7.3s ahead · 3.0 MiB", content.rows.first { it.label == "BUFFER" }.value)
        assertEquals("10.00 Mbit/s", content.rows.first { it.label == "NETWORK" }.value)
        assertEquals("Buffering · 12 dropped", content.rows.first { it.label == "HEALTH" }.value)
    }

    @Test
    fun `unknown network estimate remains visible as unavailable`() {
        val content = StatsSheetFormat.build(detail(), null, 0, null, live = liveStats(0L, 0L))
        assertEquals("—", content.rows.first { it.label == "NETWORK" }.value)
    }
}
