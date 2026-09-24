package tv.jellybeam

import tv.jellybeam.data.FakeCoreGateway
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import uniffi.jellybeam_core.DeviceCaps
import uniffi.jellybeam_core.ProfileLevelCaps
import uniffi.jellybeam_core.VideoCaps
import uniffi.jellybeam_core.VideoCodecId

/** [AppGraph.init] needs Robolectric to test directly, so this exercises the extracted "probe then
 * push" function instead.
 */
class PushDeviceCapsTest {

    @Test
    fun `pushes the probed caps to the gateway`() = runTest {
        val gateway = FakeCoreGateway()
        val caps = DeviceCaps(
            maxStreamingBitrate = null,
            video = listOf(
                VideoCaps(
                    codec = VideoCodecId.HEVC,
                    profiles = listOf("main", "main 10"),
                    maxLevel = 120,
                    profileLevels = listOf(ProfileLevelCaps(profile = "main 10", maxLevel = 120)),
                    maxWidth = 3840u,
                    maxHeight = 2160u,
                    unsupportedVideoRanges = listOf("DOVI"),
                ),
            ),
        )

        pushDeviceCaps(gateway, probe = { caps })

        assertEquals(1, gateway.setDeviceCapsCalls.size)
        assertSame(caps, gateway.setDeviceCapsCalls.single())
    }

    @Test
    fun `an empty probe result still reaches the gateway -- the Rust floor takes over there, not here`() = runTest {
        val gateway = FakeCoreGateway()
        val empty = DeviceCaps(maxStreamingBitrate = null, video = emptyList())

        pushDeviceCaps(gateway, probe = { empty })

        assertEquals(listOf(empty), gateway.setDeviceCapsCalls)
    }
}
