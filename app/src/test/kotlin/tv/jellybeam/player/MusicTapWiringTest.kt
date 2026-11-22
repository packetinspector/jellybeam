package tv.jellybeam.player

import android.content.ContextWrapper
import androidx.media3.common.util.Clock
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.jellybeam.visualizer.tap.PcmTap
import tv.jellybeam.visualizer.tap.SampleRing

/** docs/30 §9 T28: the video factory builds no tap; the music output hands the tap exactly what it consumed. */
class MusicTapWiringTest {
    /** An output that consumes [consume] bytes per write (all when null) and reports [positionUs]. */
    private fun fakeOutput(consume: Int? = null, positionUs: () -> Long = { 0L }): AudioOutput =
        Proxy.newProxyInstance(AudioOutput::class.java.classLoader, arrayOf(AudioOutput::class.java)) { _, method, args ->
            when (method.name) {
                "write" -> {
                    val b = args[0] as ByteBuffer
                    b.position(minOf(b.limit(), b.position() + (consume ?: b.remaining())))
                    !b.hasRemaining()
                }
                "getPositionUs" -> positionUs()
                "getPlaybackParameters" -> androidx.media3.common.PlaybackParameters.DEFAULT
                "isOffloadedPlayback", "isStalled", "canReuseAudioOutput" -> false
                "getAudioSessionId", "getSampleRate" -> 0
                "getBufferSizeInFrames" -> 0L
                else -> null
            }
        } as AudioOutput

    private fun output(delegate: AudioOutput, tap: PcmTap?) = PlausibleClockAudioOutput(
        delegate = delegate,
        clock = Clock.DEFAULT,
        reporter = ClockDiagReporter(outputId = 1, startMs = 0L, log = {}, enabled = { false }, emit = {}),
        tap = tap,
    )

    private fun pcm(frames: Int): ByteBuffer {
        val b = ByteBuffer.allocate(frames * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) b.putShort((i + 1).toShort())
        b.flip()
        return b
    }

    @Test
    fun videoFactoryBuildsAProviderWithNoTap() {
        val context = ContextWrapper(null)
        assertFalse(JellybeamRenderersFactory(context).audioOutputProvider(context).hasTap)
        assertTrue(JellybeamRenderersFactory(context, musicTap = PcmTap()).audioOutputProvider(context).hasTap)
    }

    @Test
    fun outputWithoutTapForwardsWritesUnchanged() {
        val out = output(fakeOutput(consume = 100), tap = null)
        val b = pcm(256)
        assertFalse(out.write(b, 1, 0L))
        assertEquals(100, b.position())
        assertEquals(512, b.limit())
    }

    @Test
    fun musicOutputOffersOnlyTheConsumedBytes() {
        val tap = PcmTap()
        tap.configure(androidEncoding = 2, sampleRate = 48_000, channelCount = 1, offload = false)
        val ring = SampleRing()
        tap.attach(ring)
        val out = output(fakeOutput(consume = 200), tap)
        val b = pcm(256)
        out.write(b, 1, 0L) // consumes frames 0..99
        out.write(b, 1, 0L) // consumes frames 100..199
        val mono = FloatArray(4)
        assertTrue(ring.read(198, mono, FloatArray(1), FloatArray(1)))
        // Frames 196..199 hold samples 197..200; nothing past the consumed 200 frames reached the ring.
        assertEquals(197f / 32768f, mono[0], 1e-7f)
        assertEquals(200f / 32768f, mono[3], 1e-7f)
        assertFalse(ring.read(260, FloatArray(4), FloatArray(1), FloatArray(1)))
    }

    @Test
    fun flushClearsTheTapsRing() {
        val tap = PcmTap()
        tap.configure(2, 48_000, 1, offload = false)
        val ring = SampleRing()
        tap.attach(ring)
        val out = output(fakeOutput(), tap)
        out.write(pcm(1000), 1, 0L)
        out.flush()
        assertFalse(ring.read(500, FloatArray(64), FloatArray(1), FloatArray(1)))
    }

    @Test
    fun aLyingPositionIsOnlyObserved() {
        // docs/18 §5.1: the patched Media3 (docs/06) fixed the false wrap; the guard logs, the raw position is returned.
        val logged = mutableListOf<String>()
        val out = PlausibleClockAudioOutput(
            delegate = fakeOutput { 44_739_000_000L },
            clock = Clock.DEFAULT,
            reporter = ClockDiagReporter(outputId = 1, startMs = 0L, log = { logged += it }, enabled = { false }, emit = {}),
        )
        out.play()
        assertEquals(44_739_000_000L, out.positionUs)
        assertTrue(logged.any { it.startsWith("clock would-clamp rule=start") })
    }

    @Test
    fun passthroughSupportIsLoggedOnlyWhenItChanges() {
        // docs/18 §5.1: a mid-day loss of passthrough must be visible in logcat, once, when it happens.
        var level = AudioOutputProvider.FORMAT_SUPPORTED_DIRECTLY
        val delegate = Proxy.newProxyInstance(AudioOutputProvider::class.java.classLoader, arrayOf(AudioOutputProvider::class.java)) { _, method, _ ->
            when (method.name) {
                "getFormatSupport" -> AudioOutputProvider.FormatSupport.Builder().setFormatSupportLevel(level).build()
                "getAudioOutput" -> fakeOutput()
                else -> null
            }
        } as AudioOutputProvider
        val logged = mutableListOf<String>()
        val provider = PlausibleClockAudioOutputProvider(delegate, tap = null, log = { logged += it })
        fun ask(mime: String) = provider.getFormatSupport(
            AudioOutputProvider.FormatConfig.Builder(androidx.media3.common.Format.Builder().setSampleMimeType(mime).build()).build(),
        )
        repeat(3) { ask("audio/eac3") }
        ask("audio/raw")
        level = AudioOutputProvider.FORMAT_UNSUPPORTED
        repeat(2) { ask("audio/eac3") }
        assertEquals(
            listOf("audio support mime=audio/eac3 level=2 offload=false", "audio support mime=audio/eac3 level=0 offload=false"),
            logged,
        )
    }

    @Test
    fun providerConfiguresTheTapFromTheOutputConfig() {
        val tap = PcmTap()
        val delegate = Proxy.newProxyInstance(AudioOutputProvider::class.java.classLoader, arrayOf(AudioOutputProvider::class.java)) { _, method, _ ->
            if (method.name == "getAudioOutput") fakeOutput() else null
        } as AudioOutputProvider
        val provider = PlausibleClockAudioOutputProvider(delegate, tap)
        val config = AudioOutputProvider.OutputConfig.Builder()
            .setEncoding(5)
            .setSampleRate(48_000)
            .setChannelMask(0xC)
            .build()
        provider.getAudioOutput(config)
        assertEquals(tv.jellybeam.visualizer.tap.TapState.ENCODED, tap.state)
    }
}
