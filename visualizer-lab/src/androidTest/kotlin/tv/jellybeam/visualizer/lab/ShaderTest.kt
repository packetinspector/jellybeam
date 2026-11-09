package tv.jellybeam.visualizer.lab

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import tv.jellybeam.visualizer.gl.VizDebug

/** docs/30 §9 T19: every program compiles and links on GLES 3.0 with an empty info log. */
@RunWith(AndroidJUnit4::class)
class ShaderTest {
    @Test
    fun everyProgramCompilesAndLinksCleanly() {
        Lab().use {
            assertEquals(emptyMap<String, String>(), VizDebug.shaderLogs)
        }
    }
}
