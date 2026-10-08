package tv.jellybeam.ui.signin

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import tv.jellybeam.AppGraph
import tv.jellybeam.JellybeamTheme
import tv.jellybeam.R
import tv.jellybeam.player.PulsingDots
import tv.jellybeam.ui.focus.requestFocusUntilSuccess
import tv.jellybeam.ui.settings.SectionHeader
import tv.jellybeam.ui.settings.SettingsChip
import tv.jellybeam.ui.theme.JellybeamWordmark
import uniffi.jellybeam_core.DiscoveredServer

/**
 * The sign-in screen: Notte background, Archivo type, fields in a Column (D-pad focus traversal
 * falls out of Compose's default 2D focus search), a Sign in button, and an error card on
 * failure. Uses [BasicTextField] since this module has no Material dependency.
 */
@Composable
fun SignInScreen(
    onSignedIn: () -> Unit,
    reauthorizationTarget: ReauthorizationTarget? = null,
    viewModel: SignInViewModel = viewModel(
        key = reauthorizationTarget?.let { "reauthorize-${it.index}-${it.userId}" } ?: "sign-in-add",
        factory = SignInViewModelFactory(AppGraph.gateway, AppGraph.strings, reauthorizationTarget),
    ),
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(state.signedIn) {
        if (state.signedIn) onSignedIn()
    }

    // LAN server autodetection focus handoff: selecting a discovered row moves focus straight to
    // the Quick Connect button rather than leaving focus on a row that just became inert-looking.
    val firstDiscoveredRowRequester = remember { FocusRequester() }
    val quickConnectRequester = remember { FocusRequester() }
    val focusScope = rememberCoroutineScope()

    // The error card sits just above the primary action button; when it appears, scroll it into
    // view instead of leaving it clipped below the fold (measured bug, docs/13 "sign-in").
    val scrollState = rememberScrollState()
    LaunchedEffect(state.error) {
        if (state.error != null) scrollState.scrollTo(scrollState.maxValue)
    }

    // Reauthorization has no discovery list to land on, so its first action takes focus and the
    // first D-pad press acts instead of waking focus.
    LaunchedEffect(reauthorizationTarget) {
        if (reauthorizationTarget != null) requestFocusUntilSuccess { quickConnectRequester.requestFocus() }
    }

    // Only when there is a selectable row: with every result already saved, no row carries the
    // requester, and this would otherwise retry forever.
    val hasSelectableDiscoveredRow = state.discoveredServers.any { !it.alreadySaved }
    LaunchedEffect(hasSelectableDiscoveredRow) {
        if (hasSelectableDiscoveredRow && !state.serverUrlTouched) {
            requestFocusUntilSuccess { firstDiscoveredRowRequester.requestFocus() }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(JellybeamTheme.Notte),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(420.dp)
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (reauthorizationTarget == null) {
                JellybeamWordmark(size = 48.sp)
            } else {
                BasicText(
                    text = stringResource(R.string.reauthorize_title),
                    style = TextStyle(
                        fontFamily = JellybeamTheme.Archivo,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.02).em,
                        color = JellybeamTheme.Panna,
                        fontSize = 48.sp,
                    ),
                )
            }

            // While pairing the server is fixed: the discovery list and the editable address give way
            // to the address as a plain line, so the code and its status fit one TV screen.
            if (reauthorizationTarget == null && state.quickConnectActive) {
                JellybeamReadOnlyField(
                    label = stringResource(R.string.sign_in_server_url_label),
                    value = state.serverUrl,
                )
            } else if (reauthorizationTarget == null) {
                DiscoverySection(
                    state = state,
                    firstRowRequester = firstDiscoveredRowRequester,
                    onSelect = { server ->
                        viewModel.selectDiscoveredServer(server)
                        focusScope.launch { requestFocusUntilSuccess { quickConnectRequester.requestFocus() } }
                    },
                    onRetry = viewModel::retryDiscovery,
                )
                JellybeamTextField(
                    label = stringResource(R.string.sign_in_server_url_label),
                    value = state.serverUrl,
                    onValueChange = viewModel::onServerUrlChange,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                )
            } else {
                JellybeamReadOnlyField(
                    label = stringResource(R.string.sign_in_server_url_label),
                    value = state.serverUrl,
                )
                BasicText(
                    text = stringResource(R.string.reauthorize_explanation),
                    style = TextStyle(
                        fontFamily = JellybeamTheme.Archivo,
                        color = JellybeamTheme.Grigio,
                        fontSize = 14.sp,
                    ),
                )
            }

            SignInButton(
                text = stringResource(
                    if (state.quickConnectActive) R.string.quick_connect_use_password
                    else R.string.quick_connect_button,
                ),
                enabled = !state.isSigningIn,
                onClick = viewModel::toggleQuickConnect,
                modifier = Modifier.focusRequester(quickConnectRequester),
            )

            if (state.quickConnectActive) {
                state.quickConnectCode?.let { code ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        // docs/brand.md §5: the watching mascot marks the pairing screen.
                        Image(
                            painter = painterResource(R.drawable.jb_mascot_watching),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.height(96.dp).padding(bottom = 8.dp),
                        )
                        BasicText(
                            text = stringResource(R.string.quick_connect_code_label),
                            style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = JellybeamTheme.Grigio, fontSize = 13.sp),
                        )
                        BasicText(
                            text = code,
                            style = TextStyle(
                                fontFamily = JellybeamTheme.Archivo,
                                fontWeight = FontWeight.Bold,
                                color = JellybeamTheme.Pistacchio,
                                fontSize = 42.sp,
                            ),
                        )
                        BasicText(
                            text = stringResource(R.string.quick_connect_instructions),
                            style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = JellybeamTheme.Panna, fontSize = 14.sp),
                        )
                    }
                }
                if (state.isQuickConnecting) {
                    BasicText(
                        text = stringResource(
                            if (state.quickConnectCode == null) R.string.quick_connect_starting
                            else R.string.quick_connect_waiting,
                        ),
                        style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = JellybeamTheme.Grigio, fontSize = 14.sp),
                    )
                } else if (state.error != null) {
                    state.error?.let { SignInErrorCard(it) }
                    SignInButton(
                        text = stringResource(R.string.quick_connect_retry),
                        enabled = true,
                        onClick = viewModel::retryQuickConnect,
                    )
                }
            } else {
                JellybeamTextField(
                    label = stringResource(R.string.sign_in_username_label),
                    value = state.username,
                    onValueChange = viewModel::onUsernameChange,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Next),
                )
                JellybeamTextField(
                    label = stringResource(R.string.sign_in_password_label),
                    value = state.password,
                    onValueChange = viewModel::onPasswordChange,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { viewModel.signIn() }),
                )
                state.error?.let { SignInErrorCard(it) }
                SignInButton(
                    text = stringResource(
                        if (reauthorizationTarget == null) R.string.sign_in_button
                        else R.string.reauthorize_button,
                    ),
                    enabled = !state.isSigningIn,
                    onClick = viewModel::signIn,
                )
                if (state.isSigningIn) {
                    BasicText(
                        text = stringResource(
                            if (reauthorizationTarget == null) R.string.sign_in_signing_in
                            else R.string.reauthorizing,
                        ),
                        style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = JellybeamTheme.Grigio, fontSize = 14.sp),
                    )
                }
            }
        }
    }
}

/** The sign-in error card: always the element directly above the primary action button, in both
 * password and Quick Connect mode (docs/13 "sign-in"). Multi-line -- no [BasicText.maxLines]. */
@Composable
private fun SignInErrorCard(error: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(JellybeamTheme.Surface, RoundedCornerShape(8.dp))
            .padding(16.dp),
    ) {
        BasicText(
            text = error,
            style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = JellybeamTheme.Panna, fontSize = 14.sp),
        )
    }
}

@Composable
private fun JellybeamReadOnlyField(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BasicText(
            text = label,
            style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = JellybeamTheme.Grigio, fontSize = 12.sp),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(JellybeamTheme.Surface, RoundedCornerShape(8.dp))
                .border(2.dp, JellybeamTheme.Hairline, RoundedCornerShape(8.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            BasicText(
                text = value,
                style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = JellybeamTheme.Panna, fontSize = 16.sp),
            )
        }
    }
}

@Composable
private fun JellybeamTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val borderColor = if (isFocused) JellybeamTheme.Pistacchio else JellybeamTheme.Hairline

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BasicText(
            text = label,
            style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = JellybeamTheme.Grigio, fontSize = 12.sp),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(JellybeamTheme.Surface, RoundedCornerShape(8.dp))
                .border(2.dp, borderColor, RoundedCornerShape(8.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                textStyle = TextStyle(
                    fontFamily = JellybeamTheme.Archivo,
                    color = JellybeamTheme.Panna,
                    fontSize = 16.sp,
                ),
                singleLine = true,
                cursorBrush = SolidColor(JellybeamTheme.Pistacchio),
                visualTransformation = visualTransformation,
                keyboardOptions = keyboardOptions,
                keyboardActions = keyboardActions,
                interactionSource = interactionSource,
            )
        }
    }
}

@Composable
private fun SignInButton(text: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val background = if (isFocused) JellybeamTheme.Pistacchio else JellybeamTheme.Surface
    val textColor = if (isFocused) JellybeamTheme.Notte else JellybeamTheme.Panna

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(background, RoundedCornerShape(8.dp))
            .then(
                if (!isFocused) {
                    Modifier.border(2.dp, JellybeamTheme.Hairline, RoundedCornerShape(8.dp))
                } else {
                    Modifier
                },
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = text,
            style = TextStyle(fontFamily = JellybeamTheme.Archivo, fontWeight = FontWeight.SemiBold, color = textColor, fontSize = 16.sp),
        )
    }
}

// -- LAN server autodetection -------------------------------------------
// Strictly an accelerator: never delays render, never gates the manual URL field.

/**
 * The section rendered above the manual server URL field, only when there's no
 * [ReauthorizationTarget]: searching with nothing yet (pulsing-dots loader), one or more results
 * (header + rows + retry chip), or finished empty (just the retry chip).
 */
@Composable
private fun DiscoverySection(
    state: SignInUiState,
    firstRowRequester: FocusRequester,
    onSelect: (DiscoveredServer) -> Unit,
    onRetry: () -> Unit,
) {
    when {
        state.isDiscovering && state.discoveredServers.isEmpty() -> {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PulsingDots()
                BasicText(
                    text = stringResource(R.string.sign_in_discovery_searching),
                    style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = JellybeamTheme.Grigio, fontSize = 13.sp),
                )
            }
        }

        state.discoveredServers.isNotEmpty() -> {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeader(stringResource(R.string.sign_in_discovery_header))
                val firstSelectableIndex = state.discoveredServers.indexOfFirst { !it.alreadySaved }
                state.discoveredServers.forEachIndexed { index, server ->
                    DiscoveredServerRow(
                        server = server,
                        modifier = if (index == firstSelectableIndex) Modifier.focusRequester(firstRowRequester) else Modifier,
                        onSelect = { onSelect(server) },
                    )
                }
                if (!state.isDiscovering) {
                    SettingsChip(
                        label = stringResource(R.string.sign_in_discovery_retry),
                        selected = false,
                        onSelect = onRetry,
                    )
                }
            }
        }

        !state.isDiscovering -> {
            SettingsChip(
                label = stringResource(R.string.sign_in_discovery_retry),
                selected = false,
                onSelect = onRetry,
            )
        }
    }
}

/** One discovered server. [server]'s `name`/`address` render verbatim, never trimmed. A row
 * already [DiscoveredServer.alreadySaved] renders inert (SurfacePanel, tertiary text, no click). */
@Composable
private fun DiscoveredServerRow(
    server: DiscoveredServer,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(8.dp)

    if (server.alreadySaved) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .background(JellybeamTheme.SurfacePanel, shape)
                .border(2.dp, JellybeamTheme.Hairline, shape)
                .padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DiscoveredServerLabel(server, saved = true)
            BasicText(
                text = stringResource(R.string.sign_in_discovery_saved),
                style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = JellybeamTheme.PannaTertiary, fontSize = 12.sp),
            )
        }
        return
    }

    var isFocused by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(JellybeamTheme.SurfaceRaised, shape)
            .border(2.dp, if (isFocused) JellybeamTheme.Pistacchio else JellybeamTheme.Hairline, shape)
            .onFocusChanged { focusState -> isFocused = focusState.isFocused }
            .clickable(interactionSource = interactionSource, indication = null, onClick = onSelect)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DiscoveredServerLabel(server, saved = false)
    }
}

@Composable
private fun DiscoveredServerLabel(server: DiscoveredServer, saved: Boolean) {
    val nameColor = if (saved) JellybeamTheme.PannaTertiary else JellybeamTheme.Panna
    val addressColor = if (saved) JellybeamTheme.PannaTertiary else JellybeamTheme.Grigio
    Column {
        BasicText(
            text = server.name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontFamily = JellybeamTheme.Archivo, fontWeight = FontWeight.Medium, color = nameColor, fontSize = 16.sp),
        )
        BasicText(
            text = server.address,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontFamily = JellybeamTheme.MartianMono, color = addressColor, fontSize = 11.sp),
        )
    }
}
