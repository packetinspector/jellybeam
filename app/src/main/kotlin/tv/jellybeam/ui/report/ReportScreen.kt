package tv.jellybeam.ui.report

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.jellybeam.AppGraph
import tv.jellybeam.JellybeamTheme
import tv.jellybeam.R
import tv.jellybeam.data.CoreGateway
import tv.jellybeam.diag.ReportServer
import tv.jellybeam.diag.ReportSnapshot
import tv.jellybeam.diag.SummaryInputs
import tv.jellybeam.ui.focus.requestFocusUntilSuccess
import uniffi.jellybeam_core.PlaybackQuality

/** docs/21 §4's server lifetime -- independent of (but numerically equal to) [ReportServer]'s own
 * default `ttlMs`; this screen owns its own timer so the status line can update without a poll. */
private const val REPORT_EXPIRY_MS = 600_000L

/** docs/21 §4's QR module count and target size (§4's "at least 260 dp"). */
private const val QR_MODULE_COUNT = 33
private val QR_CANVAS_SIZE = 260.dp

/** docs/21 §1.2 step 5's status line, in the one direction it can move -- [WAITING] never returns
 * once a phone has fetched the page or the log. */
private enum class ReportStatus { WAITING, OPENED, DOWNLOADED, EXPIRED }

/** [PlaybackQuality]'s report-summary name -- an explicit map, not `::class.simpleName`, so a
 * release build's obfuscation can never change what a redacted report says. */
private fun playbackModeName(quality: PlaybackQuality?): String = when (quality) {
    null -> "unknown"
    is PlaybackQuality.DirectPlay -> "DirectPlay"
    is PlaybackQuality.Auto -> "Auto"
    is PlaybackQuality.Cap -> "Cap"
}

/** Unwraps a Compose [Context] to the owning [Activity] (Compose's `LocalContext` can be a
 * `ContextWrapper`) -- needed only for [WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON]. */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * docs/21-user-reporting.md §1.2/§4/§5: the LAN report page's TV side. Builds one frozen
 * [ReportSnapshot] on first composition, serves it from a [ReportServer] for as long as this
 * screen is visible, and shows a QR code plus the same URL as text -- the phone does everything
 * else. No media titles or server names appear anywhere on this screen.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ReportScreen(
    onExit: () -> Unit,
    isTop: Boolean = true,
    /** JellybeamRoot's focus gate for this entry -- see [tv.jellybeam.ui.settings.SettingsScreen]'s own
     * param doc. Only the "Turn diagnostic logging off now?" dialog places focus explicitly. */
    focusGate: MutableState<Boolean> = remember { mutableStateOf(true) },
    gateway: CoreGateway = AppGraph.gateway,
) {
    val context = LocalContext.current

    var status by remember { mutableStateOf(ReportStatus.WAITING) }
    var reportUrl by remember { mutableStateOf<String?>(null) }
    var noNetwork by remember { mutableStateOf(false) }
    var logDownloaded by remember { mutableStateOf(false) }
    var showLoggingOffDialog by remember { mutableStateOf(false) }
    val serverHolder = remember { arrayOfNulls<ReportServer>(1) }

    // docs/21 §1.2 step 3: the report screen holds the display awake for as long as it's visible.
    DisposableEffect(Unit) {
        val window = context.findActivity()?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    // Stops the server the moment this screen leaves the foreground for any reason (docs/21 §4's
    // lifetime rule) -- Home/launcher/Assistant/playback-start all route through onStop. `close()`
    // is idempotent, so this racing the expiry timer's own close below is harmless.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) serverHolder[0]?.close()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Closes on dispose (Back/pop) independent of the ON_STOP path above -- both call the same
    // idempotent close().
    DisposableEffect(Unit) {
        onDispose { serverHolder[0]?.close() }
    }

    LaunchedEffect(Unit) {
        val snapshot = withContext(Dispatchers.IO) {
            val packageManager = context.packageManager
            val packageInfo = packageManager.getPackageInfo(context.packageName, 0)
            val settings = runCatching { gateway.getSettings() }.getOrNull()
            val inputs = SummaryInputs(
                appVersion = packageInfo.versionName ?: "unknown",
                buildNumber = PackageInfoCompat.getLongVersionCode(packageInfo),
                androidRelease = android.os.Build.VERSION.RELEASE ?: "unknown",
                sdkInt = android.os.Build.VERSION.SDK_INT,
                manufacturer = android.os.Build.MANUFACTURER,
                model = android.os.Build.MODEL,
                serverVersion = runCatching { gateway.serverVersion() }.getOrNull(),
                serverCount = runCatching { gateway.listAccounts().size }.getOrDefault(1).coerceAtLeast(1),
                playbackMode = playbackModeName(settings?.playbackQuality),
            )
            ReportSnapshot(inputs, AppGraph.diag, AppGraph.crash)
        }

        val listener = object : ReportServer.Listener {
            // Called from the server's own background thread (docs/21 §4) -- state writes must
            // reach Compose's main-thread snapshot observer, hence the explicit post.
            override fun onPageServed() {
                mainThreadHandler.post { if (status == ReportStatus.WAITING) status = ReportStatus.OPENED }
            }

            override fun onLogServed() {
                mainThreadHandler.post {
                    status = ReportStatus.DOWNLOADED
                    logDownloaded = true
                }
            }
        }

        val server = ReportServer(snapshot, listener = listener)
        serverHolder[0] = server
        val info = withContext(Dispatchers.IO) { server.start() }
        if (info.url == null) {
            noNetwork = true
        } else {
            reportUrl = info.url
        }

        delay(REPORT_EXPIRY_MS)
        server.close()
        status = ReportStatus.EXPIRED
    }

    // docs/21 §1.2 step 7: only when a download actually happened this visit, and only if logging
    // is still on -- turning it off already happened, or was never on, needs no prompt.
    BackHandler(enabled = !showLoggingOffDialog) {
        if (logDownloaded && AppGraph.diag.enabled) {
            showLoggingOffDialog = true
        } else {
            onExit()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(JellybeamTheme.Notte).padding(48.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(48.dp), verticalAlignment = Alignment.CenterVertically) {
            val url = reportUrl
            if (!noNetwork && url != null) {
                Box(
                    modifier = Modifier
                        .background(Color.White, RoundedCornerShape(12.dp))
                        .padding(16.dp),
                ) {
                    QrCode(url = url, modifier = Modifier.size(QR_CANVAS_SIZE))
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                BasicText(
                    text = stringResource(R.string.report_title),
                    style = TextStyle(fontFamily = JellybeamTheme.Archivo, fontWeight = FontWeight.Bold, color = JellybeamTheme.Panna, fontSize = 28.sp),
                )
                if (noNetwork) {
                    BasicText(
                        text = stringResource(R.string.report_no_network),
                        style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = JellybeamTheme.Grigio, fontSize = 16.sp),
                    )
                } else {
                    reportUrl?.let { url ->
                        BasicText(
                            text = url,
                            style = TextStyle(fontFamily = JellybeamTheme.MartianMono, color = JellybeamTheme.Panna, fontSize = 18.sp),
                        )
                    }
                    BasicText(
                        text = stringResource(reportStatusLabel(status)),
                        style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = JellybeamTheme.Pistacchio, fontSize = 15.sp),
                    )
                }
                BasicText(
                    text = stringResource(R.string.report_nothing_sent),
                    style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = JellybeamTheme.Grigio, fontSize = 13.sp),
                )
                BasicText(
                    text = stringResource(R.string.report_back_hint),
                    style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = JellybeamTheme.Grigio, fontSize = 13.sp),
                )
            }
        }

        if (showLoggingOffDialog) {
            LoggingOffDialog(
                focusGate = focusGate,
                onTurnOff = {
                    AppGraph.diag.setEnabled(false)
                    // docs/21 §1.2 step 7: fired on `processScope`, not this composable's own
                    // scope -- onExit() below disposes this screen (and any rememberCoroutineScope
                    // job with it) before a `composableScope`-launched write could land.
                    AppGraph.processScope.launch(Dispatchers.IO) {
                        runCatching {
                            val current = gateway.getSettings()
                            gateway.setSettings(current.copy(diagnosticLoggingEnabled = false))
                        }
                    }
                    showLoggingOffDialog = false
                    onExit()
                },
                onKeepLogging = {
                    showLoggingOffDialog = false
                    onExit()
                },
            )
        }
    }
}

private fun reportStatusLabel(status: ReportStatus): Int = when (status) {
    ReportStatus.WAITING -> R.string.report_status_waiting
    ReportStatus.OPENED -> R.string.report_status_opened
    ReportStatus.DOWNLOADED -> R.string.report_status_downloaded
    ReportStatus.EXPIRED -> R.string.report_status_expired
}

/** docs/21 §4's QR code: [QRCodeWriter] encodes once per [url] change, drawn as filled squares --
 * no bitmap allocation, no external QR-rendering dependency beyond the encoder itself. */
@Composable
private fun QrCode(url: String, modifier: Modifier = Modifier) {
    val bitMatrix = remember(url) {
        QRCodeWriter().encode(
            url,
            BarcodeFormat.QR_CODE,
            QR_MODULE_COUNT,
            QR_MODULE_COUNT,
            mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 0,
            ),
        )
    }
    Canvas(modifier = modifier) {
        val moduleWidth = size.width / bitMatrix.width
        val moduleHeight = size.height / bitMatrix.height
        for (y in 0 until bitMatrix.height) {
            for (x in 0 until bitMatrix.width) {
                if (bitMatrix.get(x, y)) {
                    drawRect(
                        color = Color.Black,
                        topLeft = Offset(x * moduleWidth, y * moduleHeight),
                        size = Size(moduleWidth, moduleHeight),
                    )
                }
            }
        }
    }
}

/** docs/21 §1.2 step 7's Back dialog: "Turn off" / "Keep logging", initial focus on "Keep
 * logging". */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun LoggingOffDialog(focusGate: MutableState<Boolean>, onTurnOff: () -> Unit, onKeepLogging: () -> Unit) {
    val keepLoggingRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { requestFocusUntilSuccess(focusGate) { keepLoggingRequester.requestFocus() } }
    BackHandler(onBack = onKeepLogging)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(JellybeamTheme.Notte.copy(alpha = 0.85f))
            .focusProperties { exit = { FocusRequester.Cancel } }
            .focusGroup(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.width(480.dp).background(JellybeamTheme.Surface, RoundedCornerShape(12.dp)).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            BasicText(
                text = stringResource(R.string.report_turn_off_logging_title),
                style = TextStyle(fontFamily = JellybeamTheme.Archivo, fontWeight = FontWeight.Bold, color = JellybeamTheme.Panna, fontSize = 20.sp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LoggingOffDialogButton(
                    label = stringResource(R.string.report_turn_off_logging_keep),
                    onClick = onKeepLogging,
                    modifier = Modifier.focusRequester(keepLoggingRequester),
                )
                LoggingOffDialogButton(label = stringResource(R.string.report_turn_off_logging_confirm), onClick = onTurnOff, primary = true)
            }
        }
    }
}

/** [LoggingOffDialog]'s pill button -- same fill-on-focus recipe as
 * [tv.jellybeam.ui.discover.DiscoverDetailScreen]'s private `ActionPill`. */
@Composable
private fun LoggingOffDialogButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    Box(
        modifier = modifier
            .background(if (isFocused) JellybeamTheme.Pistacchio else Color.Transparent, RoundedCornerShape(50))
            .let {
                if (!isFocused) {
                    it.border(1.dp, if (primary) JellybeamTheme.Pistacchio else JellybeamTheme.Hairline, RoundedCornerShape(50))
                } else {
                    it
                }
            }
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        BasicText(
            text = label,
            style = TextStyle(
                fontFamily = JellybeamTheme.Archivo,
                fontWeight = FontWeight.SemiBold,
                color = if (isFocused) JellybeamTheme.Notte else JellybeamTheme.Panna,
                fontSize = 14.sp,
            ),
        )
    }
}

/** [ReportServer.Listener]'s callbacks arrive on the server's own background thread -- this posts
 * them to the main looper so the Compose state writes above are safe. */
private val mainThreadHandler = android.os.Handler(android.os.Looper.getMainLooper())
