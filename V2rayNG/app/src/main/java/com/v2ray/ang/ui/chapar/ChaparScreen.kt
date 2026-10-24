package com.v2ray.ang.ui.chapar

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.ui.semantics.disabled
import com.v2ray.ang.R

@Composable
fun ChaparScreen(
    state: ChaparUiState,
    versionName: String,
    onAction: (ChaparAction) -> Unit,
) {
    val extras = LocalChaparColors.current
    val colors = MaterialTheme.colorScheme
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(0f to extras.glow, 0.45f to colors.background, 1f to colors.background))
    ) {
        // The gradient is drawn on a plain Box, which sets no content color; without this, text defaults to black in dark mode.
        CompositionLocalProvider(LocalContentColor provides colors.onBackground) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val account = state.account
            TopBar(onSettings = { showSettings = true }, settingsEnabled = account is AccountState.SignedIn && !state.busy)
            when {
                !state.loaded || account == AccountState.Loading -> CenteredProgress()
                account == AccountState.SignedOut -> SignInContent(state.busy, state.accountError, onAction)
                account == AccountState.NeedsNickname -> NicknameContent(state.busy, state.accountError, onAction)
                account is AccountState.SignedIn -> HomeContent(state, account, onAction)
            }
            Spacer(Modifier.height(24.dp))
        }
        }
    }

    if (showSettings) {
        SettingsSheet(
            nickname = (state.account as? AccountState.SignedIn)?.nickname.orEmpty(),
            versionName = versionName,
            accountActionsEnabled = state.accountActionsEnabled,
            onDismiss = { showSettings = false },
            onAction = { action ->
                showSettings = false
                if (action == ChaparAction.DeleteAccount) confirmDelete = true else onAction(action)
            },
        )
    }

    state.report?.let { report -> ReportDialog(report, onAction) }
    state.bypass?.let { bypass -> BypassDialog(bypass, onAction) }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.chapar_delete_title)) },
            text = { Text(stringResource(R.string.chapar_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onAction(ChaparAction.DeleteAccount)
                }) { Text(stringResource(R.string.chapar_delete_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.chapar_cancel)) }
            },
        )
    }
}

/** "Report a problem": what the app recorded, which the user may copy and send (no keys, addresses, or sites). */
@Composable
private fun ReportDialog(report: String, onAction: (ChaparAction) -> Unit) {
    AlertDialog(
        onDismissRequest = { onAction(ChaparAction.CloseReport) },
        title = { Text(stringResource(R.string.chapar_report_title)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.chapar_report_body), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                Text(report, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        },
        confirmButton = {
            TextButton(onClick = { onAction(ChaparAction.CopyReport) }) { Text(stringResource(R.string.chapar_report_copy)) }
        },
        dismissButton = {
            TextButton(onClick = { onAction(ChaparAction.CloseReport) }) { Text(stringResource(R.string.chapar_report_close)) }
        },
    )
}

/** "Apps that skip the VPN": the chosen apps use the phone's own connection and real address (D-047). */
@Composable
private fun BypassDialog(state: BypassState, onAction: (ChaparAction) -> Unit) {
    AlertDialog(
        onDismissRequest = { onAction(ChaparAction.CloseBypass) },
        title = { Text(stringResource(R.string.chapar_bypass_title)) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).imePadding()) {
                Text(stringResource(R.string.chapar_bypass_warning), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = state.filter,
                    onValueChange = { onAction(ChaparAction.SetBypassFilter(it)) },
                    label = { Text(stringResource(R.string.chapar_bypass_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                if (state.loading) {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else {
                    LazyColumn(Modifier.fillMaxWidth()) {
                        items(state.visible, key = { it.packageName }) { app ->
                            val chosen = app.packageName in state.selected
                            val chosenText = stringResource(if (chosen) R.string.chapar_bypass_on else R.string.chapar_bypass_off)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .toggleable(value = chosen, role = Role.Checkbox) {
                                        onAction(ChaparAction.ToggleBypassApp(app.packageName))
                                    }
                                    .clearAndSetSemantics {
                                        contentDescription = app.label
                                        stateDescription = chosenText
                                        role = Role.Checkbox
                                        onClick { onAction(ChaparAction.ToggleBypassApp(app.packageName)); true }
                                    },
                            ) {
                                Checkbox(checked = chosen, onCheckedChange = null)
                                Spacer(Modifier.size(8.dp))
                                Text(app.label, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAction(ChaparAction.SaveBypass) }, enabled = !state.loading) {
                Text(stringResource(R.string.chapar_bypass_save))
            }
        },
        dismissButton = {
            TextButton(onClick = { onAction(ChaparAction.CloseBypass) }) { Text(stringResource(R.string.chapar_cancel)) }
        },
    )
}

@Composable
private fun CenteredProgress() {
    Box(Modifier.fillMaxWidth().padding(top = 120.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
private fun accountErrorText(error: AccountError): String = stringResource(
    when (error) {
        AccountError.NETWORK -> R.string.chapar_error_network
        AccountError.GMAIL_ONLY -> R.string.chapar_error_gmail_only
        AccountError.SIGNUP_CLOSED -> R.string.chapar_error_signup_closed
        AccountError.NICKNAME_TAKEN -> R.string.chapar_error_nickname_taken
        AccountError.INVALID_NICKNAME -> R.string.chapar_error_invalid_nickname
        AccountError.DISABLED -> R.string.chapar_account_disabled
        AccountError.RATE_LIMITED -> R.string.chapar_error_rate_limited
        AccountError.SERVER_BUSY -> R.string.chapar_error_server_busy
        AccountError.INSECURE -> R.string.chapar_error_insecure
        AccountError.LINK_REFUSED -> R.string.chapar_connect_failed
        AccountError.FAILED -> R.string.chapar_error_failed
    }
)

@Composable
private fun Notice(text: String, error: Boolean = true) {
    Surface(
        color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Text(text = text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp))
    }
}

@Composable
private fun TopBar(onSettings: () -> Unit, settingsEnabled: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SunMark(Modifier.size(32.dp))
        Spacer(Modifier.size(10.dp))
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onSettings, enabled = settingsEnabled) {
            Icon(
                painter = painterResource(R.drawable.ic_settings_24dp),
                contentDescription = stringResource(R.string.chapar_settings),
            )
        }
    }
}

@Composable
private fun SignInContent(busy: Boolean, error: AccountError?, onAction: (ChaparAction) -> Unit) {
    Column(
        modifier = Modifier
            .widthIn(max = 480.dp)
            .padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(30.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 2.dp,
            modifier = Modifier.size(96.dp),
        ) {
            Box(contentAlignment = Alignment.Center) { SunMark(Modifier.size(60.dp)) }
        }
        Text(
            text = stringResource(R.string.chapar_welcome_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.chapar_welcome_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (error != null) Notice(accountErrorText(error))
        Spacer(Modifier.height(4.dp))
        // Users connect only through this app after signing in with a verified Gmail account (D-041, D-042).
        if (busy) {
            CircularProgressIndicator()
        } else {
            Button(
                onClick = { onAction(ChaparAction.SignIn) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
            ) { Text(stringResource(R.string.chapar_sign_in), style = MaterialTheme.typography.titleMedium) }
        }
        Text(
            text = stringResource(R.string.chapar_privacy_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun NicknameContent(busy: Boolean, error: AccountError?, onAction: (ChaparAction) -> Unit) {
    var nickname by rememberSaveable { mutableStateOf("") }
    Column(
        modifier = Modifier
            .widthIn(max = 480.dp)
            .padding(top = 32.dp)
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = stringResource(R.string.chapar_nickname_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = stringResource(R.string.chapar_nickname_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val fieldError = error == AccountError.NICKNAME_TAKEN || error == AccountError.INVALID_NICKNAME
        OutlinedTextField(
            value = nickname,
            onValueChange = { nickname = it.take(20) },
            label = { Text(stringResource(R.string.chapar_nickname_label)) },
            supportingText = {
                Text(if (fieldError) accountErrorText(error!!) else stringResource(R.string.chapar_nickname_hint))
            },
            isError = fieldError,
            singleLine = true,
            enabled = !busy,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onAction(ChaparAction.SubmitNickname(nickname.trim())) }),
            modifier = Modifier.fillMaxWidth(),
        )
        if (error != null && !fieldError) Notice(accountErrorText(error))
        if (busy) {
            CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
        } else {
            Button(
                onClick = { onAction(ChaparAction.SubmitNickname(nickname.trim())) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp),
            ) { Text(stringResource(R.string.chapar_continue), style = MaterialTheme.typography.titleMedium) }
            TextButton(
                onClick = { onAction(ChaparAction.CancelNickname) },
                modifier = Modifier.align(Alignment.CenterHorizontally).heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.chapar_cancel)) }
        }
    }
}

@Composable
private fun HomeContent(state: ChaparUiState, account: AccountState.SignedIn, onAction: (ChaparAction) -> Unit) {
    val connection = state.connection
    val statusText = stringResource(
        when (connection) {
            ConnectionState.Disconnected -> R.string.chapar_status_disconnected
            ConnectionState.Connecting -> R.string.chapar_status_connecting
            ConnectionState.Connected -> R.string.chapar_status_connected
            ConnectionState.Disconnecting -> R.string.chapar_status_disconnecting
            is ConnectionState.Failed -> R.string.chapar_status_failed
        }
    )
    val busy = connection == ConnectionState.Connecting || connection == ConnectionState.Disconnecting

    Column(
        modifier = Modifier.widthIn(max = 480.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(40.dp))
        Text(
            text = statusText,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        Spacer(Modifier.height(28.dp))
        PowerButton(
            connection = connection,
            // While connecting, a tap cancels; only "disconnecting" cannot be interrupted.
            enabled = connection != ConnectionState.Disconnecting && !state.busy &&
                (state.canConnect || state.isRunning || connection == ConnectionState.Connecting),
            onClick = { onAction(ChaparAction.ToggleConnection) },
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(
                when {
                    connection == ConnectionState.Connecting -> R.string.chapar_hint_cancel
                    busy -> R.string.chapar_hint_wait
                    state.isRunning -> R.string.chapar_hint_disconnect
                    else -> R.string.chapar_hint_connect
                }
            ),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (connection is ConnectionState.Failed) {
            Spacer(Modifier.height(16.dp))
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.chapar_failed_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                )
            }
        }
        Spacer(Modifier.height(40.dp))
        ModeSelector(
            selected = state.selectedMode,
            available = state.availableModes,
            enabled = !busy && !state.busy,
            onSelect = { onAction(ChaparAction.SelectMode(it)) },
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(
                if (state.selectedMode == ChaparMode.TOR) R.string.chapar_mode_tor_note
                else R.string.chapar_mode_iran_note
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        account.info?.let { info ->
            Spacer(Modifier.height(24.dp))
            when (info.status) {
                "over_quota" -> Notice(stringResource(R.string.chapar_usage_over, ChaparAccount.resetDay(info)))
                "disabled" -> Notice(stringResource(R.string.chapar_account_disabled))
                else -> Text(
                    text = stringResource(R.string.chapar_usage_line, ChaparAccount.gigabytesLeft(info), ChaparAccount.resetDay(info)),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun PowerButton(connection: ConnectionState, enabled: Boolean, onClick: () -> Unit) {
    val extras = LocalChaparColors.current
    val colors = MaterialTheme.colorScheme
    val (fill, iconColor) = when (connection) {
        ConnectionState.Connected -> extras.ok to extras.onOk
        ConnectionState.Connecting, ConnectionState.Disconnecting -> extras.sun to colors.onSecondary
        is ConnectionState.Failed -> colors.error to colors.onError
        ConnectionState.Disconnected -> colors.surface to colors.onSurfaceVariant
    }
    val pulsing = connection == ConnectionState.Connecting || connection == ConnectionState.Disconnecting
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 1f,
        targetValue = if (pulsing) 0.55f else 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "pulseAlpha",
    )
    val label = stringResource(
        when (connection) {
            ConnectionState.Connected, ConnectionState.Disconnecting -> R.string.chapar_action_disconnect
            ConnectionState.Connecting -> R.string.chapar_action_cancel
            else -> R.string.chapar_action_connect
        }
    )
    val status = stringResource(
        when (connection) {
            ConnectionState.Connected -> R.string.chapar_status_connected
            ConnectionState.Connecting -> R.string.chapar_status_connecting
            ConnectionState.Disconnecting -> R.string.chapar_status_disconnecting
            is ConnectionState.Failed -> R.string.chapar_status_failed
            ConnectionState.Disconnected -> R.string.chapar_status_disconnected
        }
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(184.dp)
            .clip(CircleShape)
            .background(extras.sun.copy(alpha = 0.16f))
            .padding(14.dp)
            .clip(CircleShape)
            .background(fill.copy(alpha = if (pulsing) pulse else 1f))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .clearAndSetSemantics {
                role = Role.Button
                contentDescription = label
                stateDescription = status
                if (enabled) onClick(label) { onClick(); true } else disabled()
            },
    ) {
        PowerGlyph(color = iconColor, modifier = Modifier.size(64.dp))
    }
}

@Composable
private fun ModeSelector(
    selected: ChaparMode,
    available: Set<ChaparMode>,
    enabled: Boolean,
    onSelect: (ChaparMode) -> Unit,
) {
    val modes = ChaparMode.entries
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        modes.forEachIndexed { index, mode ->
            val inLink = mode in available
            val name = stringResource(
                when (mode) {
                    ChaparMode.IRAN -> R.string.chapar_mode_iran
                    ChaparMode.TOR -> R.string.chapar_mode_tor
                }
            )
            val reason = if (inLink) null else stringResource(R.string.chapar_mode_unavailable)
            SegmentedButton(
                selected = mode == selected,
                onClick = { onSelect(mode) },
                enabled = enabled && inLink,
                shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .semantics { if (reason != null) stateDescription = reason },
                label = { Text(name) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class) // ModalBottomSheet; reevaluate when it is stable in the pinned Compose BOM.
@Composable
private fun SettingsSheet(
    nickname: String,
    versionName: String,
    accountActionsEnabled: Boolean,
    onDismiss: () -> Unit,
    onAction: (ChaparAction) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            val itemColors = ListItemDefaults.colors(containerColor = Color.Transparent)
            ListItem(
                headlineContent = { Text(stringResource(R.string.chapar_signed_in_as, nickname)) },
                colors = itemColors,
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.chapar_bypass_title)) },
                supportingContent = { Text(stringResource(R.string.chapar_bypass_note)) },
                colors = itemColors,
                modifier = Modifier.clickable(role = Role.Button) { onAction(ChaparAction.OpenBypass) },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.chapar_always_on_title)) },
                supportingContent = { Text(stringResource(R.string.chapar_always_on_note)) },
                colors = itemColors,
                modifier = Modifier.clickable(role = Role.Button) { onAction(ChaparAction.OpenVpnSettings) },
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            // Signing out or deleting waits while a connection is being set up or taken down.
            ListItem(
                headlineContent = { Text(stringResource(R.string.chapar_sign_out)) },
                colors = itemColors,
                modifier = Modifier.clickable(enabled = accountActionsEnabled, role = Role.Button) { onAction(ChaparAction.SignOut) },
            )
            ListItem(
                headlineContent = {
                    Text(
                        stringResource(R.string.chapar_delete_account),
                        color = if (accountActionsEnabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                colors = itemColors,
                modifier = Modifier.clickable(enabled = accountActionsEnabled, role = Role.Button) { onAction(ChaparAction.DeleteAccount) },
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            ListItem(
                headlineContent = { Text(stringResource(R.string.chapar_report_problem)) },
                supportingContent = { Text(stringResource(R.string.chapar_report_problem_note)) },
                colors = itemColors,
                modifier = Modifier.clickable(role = Role.Button) { onAction(ChaparAction.OpenReport) },
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            ListItem(
                overlineContent = { Text(stringResource(R.string.chapar_settings_about)) },
                headlineContent = { Text(stringResource(R.string.chapar_about_version, versionName)) },
                supportingContent = { Text(stringResource(R.string.chapar_about_license)) },
                colors = itemColors,
            )
        }
    }
}

/** The Chapar mark: a half sun on the horizon with three short rays (32-unit grid, as in the web logo). */
@Composable
fun SunMark(modifier: Modifier = Modifier) {
    val sun = LocalChaparColors.current.sun
    val horizon = MaterialTheme.colorScheme.onBackground
    Canvas(modifier) {
        val u = size.minDimension / 32f
        fun p(x: Float, y: Float) = Offset(x * u, y * u)
        drawArc(
            color = sun,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = true,
            topLeft = p(8.5f, 14f),
            size = Size(15f * u, 15f * u),
        )
        val ray = 2.5f * u
        drawLine(sun, p(16f, 4.5f), p(16f, 8.5f), ray, StrokeCap.Round)
        drawLine(sun, p(7.2f, 9.2f), p(9.8f, 11.8f), ray, StrokeCap.Round)
        drawLine(sun, p(24.8f, 9.2f), p(22.2f, 11.8f), ray, StrokeCap.Round)
        drawLine(horizon, p(3.5f, 26f), p(28.5f, 26f), ray, StrokeCap.Round)
    }
}

@Composable
private fun PowerGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.minDimension
        val stroke = w * 0.09f
        val inset = stroke / 2 + w * 0.08f
        drawArc(
            color = color,
            startAngle = -60f,
            sweepAngle = 300f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = Size(w - 2 * inset, w - 2 * inset),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        drawLine(color, Offset(w / 2, w * 0.06f), Offset(w / 2, w * 0.46f), stroke, StrokeCap.Round)
    }
}
