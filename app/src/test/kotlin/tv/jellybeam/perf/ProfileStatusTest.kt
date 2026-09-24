package tv.jellybeam.perf

import org.junit.Assert.assertEquals
import org.junit.Test

/** [ProfileStatus] is pure int-to-string mapping and string formatting -- no Android dependency to
 * fake.
 */
class ProfileStatusTest {

    @Test
    fun `known result codes map to their documented names`() {
        assertEquals("NO_PROFILE", ProfileStatus.name(0))
        assertEquals("COMPILED_WITH_PROFILE", ProfileStatus.name(1))
        assertEquals("PROFILE_ENQUEUED_FOR_COMPILATION", ProfileStatus.name(2))
        assertEquals("COMPILED_WITH_PROFILE_NON_MATCHING", ProfileStatus.name(3))
        assertEquals("ERROR_PACKAGE_NAME_DOES_NOT_EXIST", ProfileStatus.name(65536))
        assertEquals("ERROR_CACHE_FILE_EXISTS_BUT_CANNOT_BE_READ", ProfileStatus.name(131072))
        assertEquals("ERROR_CANT_WRITE_PROFILE_VERIFICATION_RESULT_CACHE_FILE", ProfileStatus.name(196608))
        assertEquals("ERROR_UNSUPPORTED_API_VERSION", ProfileStatus.name(262144))
        assertEquals("ERROR_NO_PROFILE_EMBEDDED", ProfileStatus.name(327680))
    }

    @Test
    fun `an unrecognized result code maps to UNKNOWN`() {
        assertEquals("UNKNOWN", ProfileStatus.name(-1))
        assertEquals("UNKNOWN", ProfileStatus.name(999))
    }

    @Test
    fun `line formats status, code, compiled and enqueued in the documented order`() {
        assertEquals(
            "perf profile status=COMPILED_WITH_PROFILE code=1 compiled=true enqueued=false",
            ProfileStatus.line(resultCode = 1, compiledWithProfile = true, enqueued = false),
        )
    }

    @Test
    fun `line uses UNKNOWN for an unrecognized code while still reporting the raw int`() {
        assertEquals(
            "perf profile status=UNKNOWN code=999 compiled=false enqueued=true",
            ProfileStatus.line(resultCode = 999, compiledWithProfile = false, enqueued = true),
        )
    }
}
