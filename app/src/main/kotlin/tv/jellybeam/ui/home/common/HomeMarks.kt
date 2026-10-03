package tv.jellybeam.ui.home.common

import tv.jellybeam.perf.PerfLog

/** docs/25 §5.3: the startup marks every layout emits, once per cold launch, named in one place
 * so docs/10's names have a single source. A mark only one layout has stays in that layout.
 */
internal object HomeMarks {
    fun dataReady() = PerfLog.markStartup("home.dataReady")
    fun contentDrawn() = PerfLog.markStartup("home.contentDrawn")
    fun focusReady() = PerfLog.markStartup("home.focusReady")
    fun revealEnd() = PerfLog.markStartup("home.revealEnd")
}
