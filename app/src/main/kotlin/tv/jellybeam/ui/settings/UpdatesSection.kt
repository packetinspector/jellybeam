package tv.jellybeam.ui.settings

import android.app.Activity
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tv.jellybeam.AppForeground
import tv.jellybeam.AppGraph
import tv.jellybeam.BuildConfig
import tv.jellybeam.JellybeamTheme
import tv.jellybeam.R
import tv.jellybeam.ui.focus.focusKey
import tv.jellybeam.updates.noteBlocks
import uniffi.jellybeam_core.UpdateSnapshot

private val updateCardShape = RoundedCornerShape(4.dp)
private val updateAmber = JellybeamTheme.Ambra
private val updateError = JellybeamTheme.UpdateError
private fun updateText(size: Int = 12, color: Color = JellybeamTheme.Panna, bold: Boolean = false) =
    TextStyle(fontFamily = JellybeamTheme.Archivo, fontSize = size.sp, lineHeight = (size + 4).sp,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, color = color)
private fun updateMono(size: Int = 9, color: Color = JellybeamTheme.Grigio) =
    TextStyle(fontFamily = JellybeamTheme.MartianMono, fontSize = size.sp, lineHeight = (size + 5).sp, color = color)

@Composable
fun UpdatesSectionContent(visible: Boolean = true, onAutomaticChange: (Boolean) -> Unit, onLater: () -> Unit = {}) {
    val owner = remember { AppGraph.updates }
    val state by owner.state.collectAsState()
    val automatic by owner.automaticChecks.collectAsState()
    val permission by owner.permissionRequired.collectAsState()
    val unavailable by owner.isUnavailable.collectAsState()
    val context = LocalContext.current
    LaunchedEffect(visible, AppForeground.browsing.value, AppForeground.resumeCount.intValue) {
        owner.pageVisible(visible)
        if (visible && AppForeground.browsing.value) owner.resume(context as Activity)
    }
    DisposableEffect(owner) { onDispose { owner.pageVisible(false) } }
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(visible) { while (visible) { now = System.currentTimeMillis() / 1000; delay(30_000) } }
    val snapshot = state
    val phase = snapshot?.phase ?: "Idle"
    val available = (snapshot?.versionCode ?: 0u) > 0u
    val target = snapshot?.versionLabel.orEmpty().removePrefix("v")
    val checking = phase == "Checking"
    val transferring = phase in setOf("Downloading", "Verifying", "Preparing", "Staging")
    val current = phase in setOf("Current", "Finished")
    val failed = phase == "Error"
    val status = stringResource(when {
        failed -> if (available) R.string.updates_status_failed_update else R.string.updates_status_failed_check
        checking -> R.string.updates_status_checking
        phase == "Downloading" -> R.string.updates_status_downloading
        phase in setOf("Verifying", "Preparing") -> R.string.updates_status_verifying
        phase == "Staging" -> R.string.updates_status_preparing
        phase == "AwaitingConfirmation" -> R.string.updates_status_confirmation
        current -> R.string.updates_status_current
        available -> R.string.updates_status_ready
        else -> R.string.updates_status_idle
    })
    val accent = if (failed) updateError else if (checking) updateAmber else JellybeamTheme.Pistacchio
    val headline = when {
        failed && snapshot?.failure?.startsWith("Connection failed") == true -> stringResource(R.string.updates_headline_server_down)
        failed -> stringResource(R.string.updates_headline_failed)
        checking -> stringResource(R.string.updates_headline_checking)
        phase == "Downloading" -> stringResource(R.string.updates_headline_downloading, target)
        phase in setOf("Verifying", "Preparing") -> stringResource(R.string.updates_headline_verifying)
        phase == "Staging" -> stringResource(R.string.updates_headline_preparing)
        phase == "AwaitingConfirmation" -> stringResource(R.string.updates_headline_confirmation)
        current -> stringResource(R.string.updates_headline_current)
        available -> stringResource(R.string.updates_headline_available)
        else -> stringResource(R.string.updates_headline_idle)
    }
    val lastChecked = relativeCheck(snapshot?.lastChecked ?: 0u, now)
    val detail = when {
        failed -> snapshot?.failure ?: stringResource(R.string.updates_detail_try_again)
        checking -> stringResource(R.string.updates_detail_checking)
        phase == "Downloading" -> stringResource(R.string.updates_detail_downloading)
        phase in setOf("Verifying", "Preparing") -> stringResource(R.string.updates_detail_verifying)
        phase == "Staging" -> stringResource(R.string.updates_detail_preparing)
        phase == "AwaitingConfirmation" -> stringResource(R.string.updates_detail_confirmation)
        permission -> stringResource(R.string.updates_detail_permission)
        current -> stringResource(R.string.updates_detail_checked, lastChecked)
        available -> stringResource(R.string.updates_detail_available, ((snapshot!!.total + 1048575u) / 1048576u).toInt())
        else -> stringResource(R.string.updates_detail_idle)
    }
    val action = stringResource(when {
        permission -> R.string.updates_action_open_settings
        checking || transferring -> R.string.updates_action_cancel
        phase == "AwaitingConfirmation" -> R.string.updates_action_continue
        failed -> R.string.updates_action_try_again
        available -> R.string.updates_action_install
        current -> R.string.updates_action_check_again
        else -> R.string.updates_action_check
    })
    val mascot = when {
        checking || phase in setOf("Verifying", "Preparing") -> R.drawable.jb_mascot_searching
        phase in setOf("Downloading", "Staging") -> R.drawable.jb_mascot_fast
        failed -> R.drawable.jb_mascot_update_sleeping
        current -> R.drawable.jb_mascot_update_happy
        else -> R.drawable.jb_mascot_base
    }
    val largeFont = LocalDensity.current.fontScale > 1.1f
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = if (largeFont || (failed && headline.length > 30)) 136.dp else 118.dp)
            .background(JellybeamTheme.Surface, updateCardShape).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.size(82.dp), contentAlignment = Alignment.Center) {
                UpdateMascot(mascot)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                BasicText("● $status", style = updateMono(9, accent))
                BasicText(headline, style = updateText(24, bold = true))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BasicText(when {
                        checking -> stringResource(R.string.updates_version_checking, BuildConfig.VERSION_NAME)
                        current -> stringResource(R.string.updates_version_latest, BuildConfig.VERSION_NAME)
                        available -> stringResource(R.string.updates_version_target, BuildConfig.VERSION_NAME, target)
                        else -> BuildConfig.VERSION_NAME
                    },
                        Modifier.background(JellybeamTheme.Notte, CircleShape).border(1.dp, JellybeamTheme.Hairline, CircleShape)
                            .padding(horizontal = 10.dp, vertical = 4.dp), style = updateMono(9, JellybeamTheme.Pistacchio))
                    BasicText(detail, Modifier.weight(1f), style = updateText(11, JellybeamTheme.Panna2))
                }
                if (phase == "Downloading") {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        Box(Modifier.weight(1f).height(3.dp).background(JellybeamTheme.Hairline, CircleShape)) {
                            val fraction = if (snapshot!!.total == 0uL) 0f else (snapshot.bytes.toDouble() / snapshot.total.toDouble()).toFloat().coerceIn(0f, 1f)
                            Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(JellybeamTheme.Pistacchio, CircleShape))
                        }
                        BasicText(stringResource(R.string.updates_progress, snapshot!!.bytes.toDouble() / 1048576, ((snapshot.total + 1048575u) / 1048576u).toInt()),
                            style = updateMono(9, JellybeamTheme.Panna))
                    }
                }
            }
            Column(Modifier.width(if (largeFont) 174.dp else 164.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                // docs/26 §1: once the updater is unavailable there is no valid action, so none is offered.
                if (!unavailable) {
                    UpdateButton(action, if (available && !checking && !transferring && !failed && !permission) stringResource(R.string.updates_action_install_hint) else null,
                        "updates/primary", filled = available && !checking && !transferring || failed,
                        onClick = { when { permission -> owner.openPermission(); checking || transferring -> owner.cancel(); available -> owner.update(); else -> owner.check() } })
                    if (available && !checking && !transferring && phase != "AwaitingConfirmation" && !failed && !permission) {
                        UpdateButton(stringResource(R.string.updates_action_later), stringResource(R.string.updates_action_later_hint), "updates/later", onClick = { owner.later(onLater) })
                    }
                }
            }
        }
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ReleaseNotesCard(snapshot, available, Modifier.weight(0.6f).fillMaxHeight())
            Column(Modifier.weight(0.4f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.fillMaxWidth().background(JellybeamTheme.Surface, updateCardShape).padding(15.dp)) {
                    BasicText(stringResource(R.string.updates_install_heading), style = updateMono())
                    Spacer(Modifier.height(10.dp))
                    UpdateStat(stringResource(R.string.updates_stat_installed), stringResource(R.string.updates_stat_installed_value, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE), bright = true)
                    UpdateStat(stringResource(R.string.updates_stat_channel), stringResource(R.string.updates_stat_channel_value))
                    UpdateStat(stringResource(R.string.updates_stat_source), stringResource(R.string.updates_stat_source_value))
                    UpdateStat(stringResource(R.string.updates_stat_last_checked), if (checking) stringResource(R.string.updates_stat_now) else lastChecked)
                }
                UpdateAutomatic(automatic) {
                    val next = !automatic
                    owner.setAutomatic(next)
                    onAutomaticChange(next)
                }
            }
        }
    }
}

@Composable
private fun UpdateMascot(resource: Int) {
    val resources = LocalContext.current.resources
    val target = with(LocalDensity.current) { 82.dp.roundToPx() }
    val bitmap by produceState<ImageBitmap?>(null, resource, target) {
        value = withContext(Dispatchers.IO) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeResource(resources, resource, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= target && bounds.outHeight / (sample * 2) >= target) sample *= 2
            BitmapFactory.decodeResource(resources, resource, BitmapFactory.Options().apply { inSampleSize = sample }).asImageBitmap()
        }
    }
    bitmap?.let { Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
}

@Composable
private fun relativeCheck(time: ULong, now: Long): String = when {
    time == 0uL -> stringResource(R.string.updates_checked_never)
    now - time.toLong() < 60 -> stringResource(R.string.updates_checked_just_now)
    now - time.toLong() < 3600 -> stringResource(R.string.updates_checked_minutes, ((now - time.toLong()) / 60).toInt())
    now - time.toLong() < 86400 -> stringResource(R.string.updates_checked_hours, ((now - time.toLong()) / 3600).toInt())
    else -> stringResource(R.string.updates_checked_days, ((now - time.toLong()) / 86400).toInt())
}

@Composable
private fun UpdateStat(label: String, value: String, bright: Boolean = false) {
    Box(Modifier.fillMaxWidth().height(1.dp).background(JellybeamTheme.Hairline))
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BasicText(label, Modifier.weight(1f), style = updateText(11, JellybeamTheme.Grigio))
        BasicText(value, style = updateMono(11, if (bright) JellybeamTheme.Panna else JellybeamTheme.Grigio))
    }
}

@Composable
private fun UpdateButton(label: String, subtitle: String?, key: String, filled: Boolean = false, onClick: () -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val memory = LocalSettingsFocusMemory.current
    val entry = LocalSettingsPaneEntryKey.current
    val ink = if (filled) JellybeamTheme.Notte else JellybeamTheme.Panna
    Column(Modifier.fillMaxWidth().padding(3.dp)
        .border(if (focused) 2.dp else 1.dp, if (focused) JellybeamTheme.Pistacchio else JellybeamTheme.Hairline, CircleShape)
        .padding(3.dp).background(if (filled) JellybeamTheme.Pistacchio else JellybeamTheme.Notte, CircleShape)
        .let { if (memory == null) it else it.focusKey(memory, key) }.paneEntry(entry, key)
        .clickable(interactionSource = interactions, indication = null, onClick = onClick)
        .padding(horizontal = 8.dp, vertical = if (subtitle == null) 12.dp else 7.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        BasicText(label, style = updateText(14, ink, true).copy(textAlign = TextAlign.Center))
        if (subtitle != null) BasicText(subtitle, style = updateText(9, if (filled) JellybeamTheme.Notte else JellybeamTheme.Grigio).copy(textAlign = TextAlign.Center))
    }
}

@Composable
private fun UpdateAutomatic(value: Boolean, onToggle: () -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val memory = LocalSettingsFocusMemory.current
    Row(Modifier.fillMaxWidth().border(if (focused) 2.dp else 0.dp, if (focused) JellybeamTheme.Pistacchio else Color.Transparent, updateCardShape)
        .background(JellybeamTheme.Surface, updateCardShape)
        .let { if (memory == null) it else it.focusKey(memory, "updates/automatic") }
        .toggleable(value, interactionSource = interactions, indication = null, role = Role.Switch, onValueChange = { onToggle() })
        .padding(15.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            BasicText(stringResource(R.string.updates_automatic_title), style = updateText(13, bold = true))
            BasicText(stringResource(R.string.updates_automatic_hint), style = updateText(10, JellybeamTheme.Grigio))
        }
        Box(Modifier.size(36.dp, 20.dp).background(if (value) JellybeamTheme.Pistacchio else JellybeamTheme.Grigio, CircleShape).padding(2.dp)) {
            Box(Modifier.size(16.dp).align(if (value) Alignment.CenterEnd else Alignment.CenterStart).background(JellybeamTheme.Panna, CircleShape))
        }
    }
}

@Composable
private fun ReleaseNotesCard(snapshot: UpdateSnapshot?, available: Boolean, modifier: Modifier) {
    val blocks = remember(snapshot?.notes) { noteBlocks(snapshot?.notes.orEmpty()) }
    val date = remember(snapshot?.publishedAt) {
        runCatching { LocalDate.parse(snapshot?.publishedAt.orEmpty().take(10)).format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)).uppercase(Locale.ROOT) }.getOrDefault("")
    }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val memory = LocalSettingsFocusMemory.current
    Column(modifier.background(JellybeamTheme.Surface, updateCardShape)
        .border(if (focused) 2.dp else 0.dp, if (focused) JellybeamTheme.Pistacchio else Color.Transparent, updateCardShape)
        .onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) false else {
                val delta = when (event.key) {
                    Key.DirectionDown -> if (scroll.canScrollForward) 100 else null
                    Key.DirectionUp -> if (scroll.canScrollBackward) -100 else null
                    else -> null
                }
                if (delta == null) false else { scope.launch { scroll.scrollTo((scroll.value + delta).coerceIn(0, scroll.maxValue)) }; true }
            }
        }.let { if (memory == null) it else it.focusKey(memory, "updates/notes") }.focusable(interactionSource = interactions).padding(15.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText(if (available) stringResource(R.string.updates_notes_heading_new, snapshot?.versionLabel.orEmpty().removePrefix("v")) else stringResource(R.string.updates_notes_heading_yours, BuildConfig.VERSION_NAME),
                Modifier.weight(1f), style = updateMono())
            if (date.isNotEmpty()) BasicText(date, style = updateMono())
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(JellybeamTheme.Hairline))
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (blocks.isEmpty()) BasicText(stringResource(R.string.updates_notes_empty), style = updateText(12, JellybeamTheme.Grigio))
            blocks.forEachIndexed { index, block ->
                if (block.heading) {
                    if (index > 0) { Spacer(Modifier.height(5.dp)); Box(Modifier.fillMaxWidth().height(1.dp).background(JellybeamTheme.Hairline)); Spacer(Modifier.height(3.dp)) }
                    BasicText(block.text.text.uppercase(Locale.ROOT), style = updateMono(9, if (index == 0) JellybeamTheme.Pistacchio else JellybeamTheme.Grigio))
                } else if (block.text.text.startsWith("• ")) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        BasicText("•", style = updateText(12, JellybeamTheme.Grigio))
                        BasicText(block.text.subSequence(2, block.text.length), Modifier.weight(1f), style = updateText(12, JellybeamTheme.Panna2))
                    }
                } else BasicText(block.text, style = updateText(12, JellybeamTheme.Panna2))
            }
            if (blocks.isNotEmpty()) BasicText(stringResource(R.string.updates_notes_end), style = updateMono(8))
        }
        BasicText(stringResource(R.string.updates_notes_scroll_hint), Modifier.align(Alignment.End), style = updateMono(8))
    }
}
