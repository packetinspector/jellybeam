package tv.jellybeam.ui.server

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import tv.jellybeam.JellybeamTheme
import tv.jellybeam.R
import tv.jellybeam.ui.focus.requestFocusUntilSuccess
import tv.jellybeam.ui.nav.accountDrawerLabel
import uniffi.jellybeam_core.AccountInfo

private val DestructiveFocus = Color(0xFFD66A57)

/** Dedicated D-pad surface for saved-account management. Selecting a row opens safe account actions
 * first; removal requires a second, confirmation-gated screen. Server URLs and usernames render
 * verbatim, matching the drawer.
 */
@Composable
fun ServerManagementScreen(
    accounts: List<AccountInfo>,
    activeAccountIndex: UInt?,
    removingIndex: UInt?,
    error: String?,
    onReauthorize: (UInt) -> Unit,
    onRemove: (UInt) -> Unit,
    onClose: () -> Unit,
) {
    var selected by remember { mutableStateOf<AccountInfo?>(null) }
    var confirmingRemoval by remember { mutableStateOf(false) }
    val selectedIndex = selectedAccountIndex(accounts, selected)

    BackHandler {
        when {
            removingIndex != null -> Unit
            selectedIndex == null -> onClose()
            confirmingRemoval -> confirmingRemoval = false
            else -> selected = null
        }
    }

    Box(
        modifier = Modifier.fillMaxSize().background(JellybeamTheme.Notte),
        contentAlignment = Alignment.Center,
    ) {
        if (selectedIndex == null) {
            ServerList(
                accounts = accounts,
                activeAccountIndex = activeAccountIndex,
                onSelect = { index ->
                    selected = accounts.getOrNull(index.toInt())
                    confirmingRemoval = false
                },
                onClose = onClose,
            )
        } else if (confirmingRemoval) {
            RemoveConfirmation(
                account = accounts[selectedIndex.toInt()],
                isActive = selectedIndex == activeAccountIndex,
                removing = removingIndex != null,
                error = error,
                onCancel = { confirmingRemoval = false },
                onConfirm = { onRemove(selectedIndex) },
            )
        } else {
            AccountActions(
                account = accounts[selectedIndex.toInt()],
                isActive = selectedIndex == activeAccountIndex,
                onReauthorize = { onReauthorize(selectedIndex) },
                onRemove = { confirmingRemoval = true },
                onCancel = { selected = null },
            )
        }
    }
}

/** Where [account] sits in this list now, matched by server and user; -1 once it is gone. */
internal fun List<AccountInfo>.indexOfAccount(account: AccountInfo): Int =
    indexOfFirst { it.serverUrl == account.serverUrl && it.userId == account.userId }

/** The [selected] account's index in [accounts] now, or `null` once it is gone: a removal shifts
 * the list, so a selection held by position would land on the next account, Remove still focused.
 */
internal fun selectedAccountIndex(accounts: List<AccountInfo>, selected: AccountInfo?): UInt? =
    selected?.let(accounts::indexOfAccount)?.takeIf { it >= 0 }?.toUInt()

@Composable
private fun AccountActions(
    account: AccountInfo,
    isActive: Boolean,
    onReauthorize: () -> Unit,
    onRemove: () -> Unit,
    onCancel: () -> Unit,
) {
    val reauthorizeRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { requestFocusUntilSuccess { reauthorizeRequester.requestFocus() } }
    Column(
        modifier = Modifier.width(620.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        BasicText(
            text = stringResource(R.string.server_account_actions_title),
            style = TextStyle(fontFamily = JellybeamTheme.Archivo, fontWeight = FontWeight.Bold, letterSpacing = (-0.02).em, color = JellybeamTheme.Panna, fontSize = 40.sp),
        )
        BasicText(
            text = accountDrawerLabel(account),
            style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = JellybeamTheme.Pistacchio, fontSize = 18.sp),
        )
        if (isActive) {
            BasicText(
                text = stringResource(R.string.server_management_active),
                style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = JellybeamTheme.Grigio, fontSize = 14.sp),
            )
        }
        ServerAction(
            text = stringResource(R.string.server_reauthorize),
            detail = stringResource(R.string.server_reauthorize_detail),
            modifier = Modifier.focusRequester(reauthorizeRequester),
            onClick = onReauthorize,
        )
        ServerAction(
            text = stringResource(R.string.server_remove_confirm),
            destructive = true,
            onClick = onRemove,
        )
        ServerAction(
            text = stringResource(R.string.server_remove_cancel),
            onClick = onCancel,
        )
    }
}

@Composable
private fun ServerList(
    accounts: List<AccountInfo>,
    activeAccountIndex: UInt?,
    onSelect: (UInt) -> Unit,
    onClose: () -> Unit,
) {
    val firstRequester = remember(accounts) { FocusRequester() }
    LaunchedEffect(accounts) { requestFocusUntilSuccess { firstRequester.requestFocus() } }

    Column(
        modifier = Modifier.width(720.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BasicText(
            text = stringResource(R.string.server_management_title),
            style = TextStyle(
                fontFamily = JellybeamTheme.Archivo,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.02).em,
                color = JellybeamTheme.Panna,
                fontSize = 42.sp,
            ),
        )
        BasicText(
            text = stringResource(R.string.server_management_hint),
            style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = JellybeamTheme.Grigio, fontSize = 14.sp),
        )
        accounts.forEachIndexed { index, account ->
            ServerAction(
                text = accountDrawerLabel(account),
                detail = if (index.toUInt() == activeAccountIndex) stringResource(R.string.server_management_active) else null,
                modifier = if (index == 0) Modifier.focusRequester(firstRequester) else Modifier,
                onClick = { onSelect(index.toUInt()) },
            )
        }
        ServerAction(
            text = stringResource(R.string.server_management_done),
            modifier = if (accounts.isEmpty()) Modifier.focusRequester(firstRequester) else Modifier,
            onClick = onClose,
        )
    }
}

@Composable
private fun RemoveConfirmation(
    account: AccountInfo,
    isActive: Boolean,
    removing: Boolean,
    error: String?,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    val cancelRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { requestFocusUntilSuccess { cancelRequester.requestFocus() } }
    Column(
        modifier = Modifier.width(620.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        BasicText(
            text = stringResource(R.string.server_remove_title),
            style = TextStyle(fontFamily = JellybeamTheme.Archivo, fontWeight = FontWeight.Bold, letterSpacing = (-0.02).em, color = JellybeamTheme.Panna, fontSize = 40.sp),
        )
        BasicText(
            text = accountDrawerLabel(account),
            style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = JellybeamTheme.Pistacchio, fontSize = 18.sp),
        )
        BasicText(
            text = stringResource(
                if (isActive) R.string.server_remove_active_warning else R.string.server_remove_warning,
            ),
            style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = JellybeamTheme.Grigio, fontSize = 15.sp),
        )
        error?.let {
            BasicText(
                text = it,
                style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = JellybeamTheme.Panna, fontSize = 14.sp),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ServerAction(
                text = stringResource(R.string.server_remove_cancel),
                enabled = !removing,
                modifier = Modifier.weight(1f).focusRequester(cancelRequester),
                onClick = onCancel,
            )
            ServerAction(
                text = stringResource(if (removing) R.string.server_removing else R.string.server_remove_confirm),
                enabled = !removing,
                modifier = Modifier.weight(1f),
                destructive = true,
                onClick = onConfirm,
            )
        }
    }
}

@Composable
private fun ServerAction(
    text: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    enabled: Boolean = true,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val background = when {
        focused && destructive -> DestructiveFocus
        focused -> JellybeamTheme.Pistacchio
        else -> JellybeamTheme.Surface
    }
    val foreground = if (focused) JellybeamTheme.Notte else JellybeamTheme.Panna
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(background, RoundedCornerShape(8.dp))
            .border(2.dp, if (focused) background else JellybeamTheme.Hairline, RoundedCornerShape(8.dp))
            .clickable(
                interactionSource = interactions,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = 18.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = text,
            style = TextStyle(
                fontFamily = JellybeamTheme.Archivo,
                fontWeight = FontWeight.SemiBold,
                color = foreground,
                fontSize = 16.sp,
            ),
        )
        detail?.let {
            BasicText(
                text = it,
                style = TextStyle(fontFamily = JellybeamTheme.Archivo, color = foreground, fontSize = 13.sp),
            )
        }
    }
}
