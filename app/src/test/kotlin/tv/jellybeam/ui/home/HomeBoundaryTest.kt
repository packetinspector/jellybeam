package tv.jellybeam.ui.home

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** docs/25 §5.6: the app is one Gradle module, so `internal` can't keep layouts apart; this does. */
class HomeBoundaryTest {

    private val mainSources = File("src/main/kotlin")
    private val homeDir = File(mainSources, "tv/jellybeam/ui/home")

    /** Every subpackage of `ui/home` except the shared one is a layout. */
    private val layouts = homeDir.listFiles { f -> f.isDirectory && f.name != "common" }!!.map { it.name }

    private fun kotlinFiles(root: File) = root.walkTopDown().filter { it.extension == "kt" }.toList()

    private fun importedLayouts(file: File): Set<String> = file.readLines()
        .filter { it.startsWith("import tv.jellybeam.ui.home.") }
        .mapNotNull { line -> layouts.firstOrNull { line.startsWith("import tv.jellybeam.ui.home.$it.") } }
        .toSet()

    @Test
    fun `there is at least one layout to check`() {
        assertTrue("layouts found: $layouts", layouts.contains("classic"))
    }

    @Test
    fun `a layout never imports another layout`() {
        val violations = layouts.flatMap { layout ->
            kotlinFiles(File(homeDir, layout)).flatMap { file ->
                (importedLayouts(file) - layout).map { "${file.name} imports $it" }
            }
        }
        assertEquals(emptyList<String>(), violations)
    }

    @Test
    fun `only HomeLayouts names a layout from outside it`() {
        val violations = kotlinFiles(mainSources)
            .filter { file -> layouts.none { file.path.contains("ui/home/$it/") } && file.name != "HomeLayouts.kt" }
            .flatMap { file -> importedLayouts(file).map { "${file.path} imports $it" } }
        assertEquals(emptyList<String>(), violations)
    }

    @Test
    fun `only the feed and the launch prefetch ask the core for a Home snapshot`() {
        val callers = kotlinFiles(mainSources)
            .filter { file -> file.readLines().any { "homeSnapshot(" in it && !it.trimStart().startsWith("*") && !it.trimStart().startsWith("//") } }
            .map { it.name }
            .toSet()
        assertEquals(setOf("CoreGateway.kt", "HomeFeed.kt", "LaunchWarmup.kt"), callers)
    }
}
