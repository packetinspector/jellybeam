package tv.jellybeam.player

import androidx.media3.common.MediaItem
import androidx.media3.common.StreamKey
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.jellybeam.player.ass.AssFontSink
import tv.jellybeam.player.ass.AttachmentsBlock
import tv.jellybeam.player.ass.ItemBoundMediaSourceFactory

/** docs/18 §5.2: a resume positions once, at the keyframe its first track selection snaps to. */
@androidx.annotation.OptIn(UnstableApi::class)
class ResumeSnapMediaSourceTest {
    /** Keyframes every 2 s; selectTracks answers its position plus 7 µs so a passed-through result is visible. */
    private class FakePeriod : MediaPeriod {
        val selected = mutableListOf<Long>()
        val adjustedWith = mutableListOf<SeekParameters>()
        var callback: MediaPeriod.Callback? = null

        override fun prepare(callback: MediaPeriod.Callback, positionUs: Long) {
            this.callback = callback
        }

        override fun selectTracks(
            selections: Array<out ExoTrackSelection?>,
            mayRetainStreamFlags: BooleanArray,
            streams: Array<SampleStream?>,
            streamResetFlags: BooleanArray,
            positionUs: Long,
        ): Long {
            selected += positionUs
            return positionUs + 7L
        }

        override fun getAdjustedSeekPositionUs(positionUs: Long, seekParameters: SeekParameters): Long {
            adjustedWith += seekParameters
            return positionUs - positionUs % KEYFRAME_US
        }

        override fun maybeThrowPrepareError() = Unit
        override fun getTrackGroups(): TrackGroupArray = TrackGroupArray.EMPTY
        override fun discardBuffer(positionUs: Long, toKeyframe: Boolean) = Unit
        override fun readDiscontinuity(): Long = 0L
        override fun seekToUs(positionUs: Long): Long = positionUs
        override fun getBufferedPositionUs(): Long = 0L
        override fun getNextLoadPositionUs(): Long = 0L
        override fun continueLoading(loadingInfo: LoadingInfo): Boolean = false
        override fun isLoading(): Boolean = false
        override fun reevaluateBuffer(positionUs: Long) = Unit
        override fun getStreamKeys(trackSelections: List<ExoTrackSelection>): List<StreamKey> = emptyList()
    }

    private val periods = mutableListOf<FakePeriod>()
    private val released = mutableListOf<Any?>()
    private val inner: MediaSource = proxy { name, args ->
        when (name) {
            "createPeriod" -> FakePeriod().also { periods += it }
            "releasePeriod" -> {
                released += args?.get(0)
                null
            }
            else -> null
        }
    }
    private val source = ResumeSnapMediaSource(inner)

    private inline fun <reified T : Any> proxy(crossinline answer: (String, Array<out Any?>?) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, m, args -> answer(m.name, args) } as T

    private fun newPeriod(): MediaPeriod =
        source.createPeriod(MediaSource.MediaPeriodId(Any()), proxy<Allocator> { _, _ -> null }, 0L)

    private fun MediaPeriod.select(positionUs: Long): Long =
        selectTracks(arrayOfNulls(0), BooleanArray(0), arrayOfNulls(0), BooleanArray(0), positionUs)

    @Test
    fun `the first selection starts at the keyframe before the resume and returns the inner answer`() {
        val period = newPeriod()
        assertEquals(42_000_007L, period.select(43_500_000L))
        assertEquals(listOf(42_000_000L), periods.single().selected)
        assertEquals(listOf(SeekParameters.PREVIOUS_SYNC), periods.single().adjustedWith)
    }

    @Test
    fun `later selections pass their position through unchanged`() {
        val period = newPeriod()
        period.select(43_500_000L)
        assertEquals(51_300_007L, period.select(51_300_000L))
        assertEquals(listOf(42_000_000L, 51_300_000L), periods.single().selected)
    }

    @Test
    fun `a first selection at zero is untouched and spends the snap`() {
        val period = newPeriod()
        assertEquals(7L, period.select(0L))
        period.select(43_500_000L)
        assertEquals(listOf(0L, 43_500_000L), periods.single().selected)
        assertTrue(periods.single().adjustedWith.isEmpty())
    }

    @Test
    fun `a period re-created after the first keeps its exact position`() {
        newPeriod().select(43_500_000L)
        newPeriod().select(43_500_000L)
        assertEquals(listOf(43_500_000L), periods[1].selected)
    }

    @Test
    fun `prepare callbacks report the outer period and release unwraps it`() {
        val period = newPeriod()
        val prepared = mutableListOf<MediaPeriod>()
        val continued = mutableListOf<MediaPeriod>()
        period.prepare(
            object : MediaPeriod.Callback {
                override fun onPrepared(mediaPeriod: MediaPeriod) {
                    prepared += mediaPeriod
                }

                override fun onContinueLoadingRequested(source: MediaPeriod) {
                    continued += source
                }
            },
            43_500_000L,
        )
        val fake = periods.single()
        fake.callback!!.onPrepared(fake)
        fake.callback!!.onContinueLoadingRequested(fake)
        assertSame(period, prepared.single())
        assertSame(period, continued.single())
        source.releasePeriod(period)
        assertSame(fake, released.single())
    }

    /** A Media3 upgrade that adds a MediaPeriod method must not fall through to its default and skip the inner period. */
    @Test
    fun `every MediaPeriod method is forwarded`() {
        val declared = newPeriod()::class.java.declaredMethods.map { it.name to it.parameterTypes.toList() }.toSet()
        val missing = MediaPeriod::class.java.methods
            .filter { !Modifier.isStatic(it.modifiers) }
            .filter { (it.name to it.parameterTypes.toList()) !in declared }
            .map { it.name }
        assertTrue("not forwarded: $missing", missing.isEmpty())
    }

    @Test
    fun `the factory wraps a source only while a snap is wanted`() {
        var snap = true
        val fonts = object : AssFontSink {
            override fun wantsFonts(): Boolean = false

            override fun attachments(block: AttachmentsBlock) = Unit
        }
        val factory = ItemBoundMediaSourceFactory(fonts, snapFirstSelection = { snap }) {
            proxy<MediaSource.Factory> { name, _ -> if (name == "createMediaSource") inner else null }
        }
        val item = MediaItem.Builder().setMediaId("a").build()
        val wrapped = factory.createMediaSource(item)
        wrapped.createPeriod(MediaSource.MediaPeriodId(Any()), proxy<Allocator> { _, _ -> null }, 0L).select(43_500_000L)
        assertEquals(listOf(42_000_000L), periods.single().selected)
        snap = false
        assertSame(inner, factory.createMediaSource(item))
    }

    private companion object {
        const val KEYFRAME_US = 2_000_000L
    }
}
