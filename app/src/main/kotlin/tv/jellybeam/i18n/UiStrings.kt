package tv.jellybeam.i18n

import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalResources

/**
 * docs/27 §3: string lookup for code that builds UI text outside Compose (formatters, ViewModels),
 * so it stays JVM-testable; tests back it with the real res/values files.
 */
interface UiStrings {
    fun get(@StringRes id: Int, vararg args: Any): String

    fun plural(@PluralsRes id: Int, count: Int, vararg args: Any): String
}

class AndroidUiStrings(private val resources: Resources) : UiStrings {
    override fun get(id: Int, vararg args: Any): String = resources.getString(id, *args)

    override fun plural(id: Int, count: Int, vararg args: Any): String =
        resources.getQuantityString(id, count, *args)
}

/** The composition's [UiStrings], following configuration changes with [LocalResources]. */
@Composable
fun rememberUiStrings(): UiStrings {
    val resources = LocalResources.current
    return remember(resources) { AndroidUiStrings(resources) }
}
