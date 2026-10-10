package tv.jellybeam.player.ass

import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** docs/13: an item's sink is bound when its media source is created, so a late loader thread can't bind a newer item's generation. */
@androidx.annotation.OptIn(UnstableApi::class)
class ItemBoundMediaSourceFactoryTest {
    private var generation = 1L
    private val attachments = mutableListOf<AttachmentsBlock>()
    private val cues = mutableListOf<SubtitleCues>()
    private var forItemCalls = 0
    private val root = object : AssFontSink {
        private val scoped = ItemScopedFontSink(
            generation = { generation },
            lock = Any(),
            wants = { true },
            onAttachments = { attachments += it },
            onCues = { c, _ -> cues += c },
        )

        override fun wantsFonts(): Boolean = scoped.wantsFonts()

        override fun attachments(block: AttachmentsBlock) = scoped.attachments(block)

        override fun forItem(): AssFontSink {
            forItemCalls++
            return scoped.forItem()
        }
    }

    /** Records the sink and settings each built factory got; every factory hands back its own stub source. */
    private inner class FakeFactory(val sink: AssFontSink) : MediaSource.Factory {
        var policy: LoadErrorHandlingPolicy? = null
        var drm: DrmSessionManagerProvider? = null
        val sources = mutableListOf<MediaSource>()

        override fun setDrmSessionManagerProvider(drmSessionManagerProvider: DrmSessionManagerProvider): MediaSource.Factory {
            drm = drmSessionManagerProvider
            return this
        }

        override fun setLoadErrorHandlingPolicy(loadErrorHandlingPolicy: LoadErrorHandlingPolicy): MediaSource.Factory {
            policy = loadErrorHandlingPolicy
            return this
        }

        override fun getSupportedTypes(): IntArray = intArrayOf(7)

        override fun createMediaSource(mediaItem: MediaItem): MediaSource = stub<MediaSource>().also { sources += it }
    }

    private val built = mutableListOf<FakeFactory>()
    private val factory = ItemBoundMediaSourceFactory(root) { sink -> FakeFactory(sink).also { built += it } }

    private inline fun <reified T : Any> stub(): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, _, _ -> null } as T

    private val itemA = MediaItem.Builder().setMediaId("a").build()
    private val itemB = MediaItem.Builder().setMediaId("b").build()

    @Test
    fun `a source made before the bump keeps its generation when its sink is used after it`() {
        val sourceA = factory.createMediaSource(itemA)
        val sinkA = built.single().sink
        generation++ // load(B) bumps, then A's loader thread reaches its extractor late
        val sourceB = factory.createMediaSource(itemB)
        val sinkB = built.last().sink
        sinkA.attachments(AttachmentsBlock(1, 10))
        sinkA.subtitleCues(SubtitleCues(setOf(1)), 1_000_000)
        sinkB.attachments(AttachmentsBlock(2, 20))
        sinkB.subtitleCues(SubtitleCues(setOf(2)), 1_000_000)
        assertSame(built[0].sources.single(), sourceA)
        assertSame(built[1].sources.single(), sourceB)
        assertEquals(listOf(2L), attachments.map { it.offset })
        assertEquals(listOf(setOf(2)), cues.map { it.tracks })
    }

    @Test
    fun `each item takes forItem once and gets its own factory`() {
        factory.createMediaSource(itemA)
        factory.createMediaSource(itemB)
        assertEquals(2, forItemCalls)
        assertEquals(2, built.size)
        assertTrue(built[0] !== built[1])
        assertTrue(built[0].sink !== built[1].sink)
    }

    @Test
    fun `stored policy and drm provider reach every built factory, the last call wins`() {
        val first = stub<LoadErrorHandlingPolicy>()
        val policy = stub<LoadErrorHandlingPolicy>()
        val drm = stub<DrmSessionManagerProvider>()
        assertSame(factory, factory.setLoadErrorHandlingPolicy(first))
        assertSame(factory, factory.setLoadErrorHandlingPolicy(policy))
        assertSame(factory, factory.setDrmSessionManagerProvider(drm))
        factory.createMediaSource(itemA)
        factory.createMediaSource(itemB)
        assertEquals(2, built.size)
        built.forEach {
            assertSame(policy, it.policy)
            assertSame(drm, it.drm)
        }
    }

    @Test
    fun `supported types come from one probe that binds no item`() {
        assertEquals(listOf(7), factory.supportedTypes.toList())
        factory.supportedTypes
        assertEquals(1, built.size)
        assertSame(root, built.single().sink)
        assertEquals(0, forItemCalls)
    }

    /** A Media3 upgrade that adds a Factory method must not fall through to its no-op default and skip the built factories. */
    @Test
    fun `every MediaSource Factory method is overridden`() {
        val declared = ItemBoundMediaSourceFactory::class.java.declaredMethods.map { it.name to it.parameterTypes.toList() }.toSet()
        val missing = MediaSource.Factory::class.java.methods
            .filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .filter { (it.name to it.parameterTypes.toList()) !in declared }
            .map { it.name }
        assertTrue("not overridden: $missing", missing.isEmpty())
    }
}
