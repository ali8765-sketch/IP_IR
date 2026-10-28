package com.v2ray.ang.ui.chapar

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.core.LauncherManager
import androidx.appcompat.app.AppCompatDelegate
import com.v2ray.ang.enums.PermissionType
import com.v2ray.ang.handler.AppLocaleManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.helper.PermissionHelper
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.R
import com.v2ray.ang.extension.toast
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Chapar's launcher screen: Google sign-in, then one big button and two modes (D-038, D-041, D-042).
 * Starting and stopping goes through upstream's [LauncherManager], like v2rayNG's own main screen.
 */
class ChaparActivity : BaseComponentActivity() {

    private val viewModel: ChaparViewModel by viewModels { ChaparViewModel.Factory(application) }

    // Only the notification permission is needed; upstream's helper activity also brings a QR scanner and file
    // pickers. Created with the activity, because it registers for an activity result.
    private val permissions = PermissionHelper(this)

    private val requestVpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) startCore() else viewModel.onStartRejected()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissions.request(PermissionType.POST_NOTIFICATIONS) {}
        // The ViewModel gets the key; the activity only does the parts that need an Activity.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.startRequests.collect { if (viewModel.startStillWanted()) requestStart() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }

    @Composable
    override fun ScreenContent() {
        BackHandler { moveTaskToBack(false) }
        val state by viewModel.uiState.collectAsStateWithLifecycle()
        ChaparTheme {
            ChaparScreen(
                state = state,
                versionName = BuildConfig.VERSION_NAME,
                language = currentLanguage(),
                onAction = ::handleAction,
            )
        }
    }

    private fun handleAction(action: ChaparAction) {
        when (action) {
            ChaparAction.ToggleConnection -> viewModel.toggleConnection()
            is ChaparAction.SelectMode -> viewModel.selectMode(action.mode)
            ChaparAction.SignIn -> lifecycleScope.launch {
                viewModel.onGoogleSignInStarted()
                val token = try {
                    googleIdToken()
                } catch (cancelled: CancellationException) {
                    // The activity was recreated while the account picker was open: do not leave a spinner behind.
                    viewModel.onGoogleSignInFailed(null)
                    throw cancelled
                }
                token?.let(viewModel::signIn)
            }

            is ChaparAction.SubmitNickname -> viewModel.submitNickname(action.nickname)
            ChaparAction.CancelNickname -> viewModel.cancelNickname()
            ChaparAction.SignOut -> {
                viewModel.signOut()
                // Forget the chosen Google account too, so the next sign-in offers the account picker again.
                lifecycleScope.launch {
                    runCatching { CredentialManager.create(this@ChaparActivity).clearCredentialState(ClearCredentialStateRequest()) }
                }
            }

            ChaparAction.DeleteAccount -> viewModel.deleteAccount()
            ChaparAction.OpenReport -> viewModel.openReport()
            ChaparAction.CloseReport -> viewModel.closeReport()
            ChaparAction.CopyReport -> viewModel.uiState.value.report?.let { report ->
                copySensitive(report)
                toast(R.string.chapar_report_copied)
            }

            ChaparAction.OpenBypass -> viewModel.openBypass()
            is ChaparAction.SetBypassFilter -> viewModel.setBypassFilter(action.text)
            is ChaparAction.ToggleBypassApp -> viewModel.toggleBypassApp(action.packageName)
            ChaparAction.ClearBypass -> viewModel.clearBypass()
            ChaparAction.SaveBypass -> viewModel.saveBypass()
            ChaparAction.CloseBypass -> viewModel.closeBypass()
            ChaparAction.OpenVpnSettings -> openVpnSettings()
            ChaparAction.OpenFeedback -> viewModel.openFeedback()
            is ChaparAction.SetFeedbackKind -> viewModel.setFeedbackKind(action.kind)
            is ChaparAction.SetFeedbackText -> viewModel.setFeedbackText(action.text)
            is ChaparAction.SetFeedbackReport -> viewModel.setFeedbackReport(action.attach)
            ChaparAction.SendFeedback -> viewModel.sendFeedback()
            ChaparAction.CloseFeedback -> viewModel.closeFeedback()
            // AppCompat stores the choice and recreates this activity in the new language (D-057).
            is ChaparAction.SetLanguage -> AppLocaleManager.setApplicationLanguage(action.language.code)
        }
    }

    /** The language chosen in the app, or SYSTEM when it follows the phone. */
    private fun currentLanguage(): ChaparLanguage =
        ChaparLanguage.fromCode(AppCompatDelegate.getApplicationLocales().get(0)?.language)

    /** The report names the account and when Tor was used: mark it sensitive so Android hides it in clipboard previews. */
    private fun copySensitive(text: String) {
        val clip = ClipData.newPlainText(getString(R.string.chapar_report_title), text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        }
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
    }

    /** Android's VPN settings, where "Always-on VPN" and "Block connections without VPN" are turned on. */
    private fun openVpnSettings() {
        val opened = runCatching { startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) }.isSuccess ||
            runCatching { startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }.isSuccess
        if (!opened) toast(R.string.toast_failure)
    }

    /**
     * Shows Google's account picker and returns the ID token for our Web client ID (the backend's
     * expected audience), or null after reporting why not. The token is passed on, never stored or logged.
     */
    private suspend fun googleIdToken(): String? {
        val clientId = BuildConfig.CHAPAR_WEB_CLIENT_ID
        if (clientId.isBlank()) {
            LogUtil.e(AppConfig.TAG, "Chapar: CHAPAR_WEB_CLIENT_ID is missing from chapar.local.properties")
            viewModel.onGoogleSignInFailed(AccountError.FAILED)
            return null
        }
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(GetSignInWithGoogleOption.Builder(clientId).build())
            .build()
        return try {
            val credential = CredentialManager.create(this).getCredential(this, request).credential
            if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                GoogleIdTokenCredential.createFrom(credential.data).idToken
            } else {
                viewModel.onGoogleSignInFailed(AccountError.FAILED)
                null
            }
        } catch (e: GetCredentialCancellationException) {
            viewModel.onGoogleSignInFailed(null)
            null
        } catch (e: GetCredentialException) {
            LogUtil.e(AppConfig.TAG, "Chapar: Google sign-in failed (${e.type})", e)
            viewModel.onGoogleSignInFailed(AccountError.FAILED)
            null
        } catch (e: GoogleIdTokenParsingException) {
            LogUtil.e(AppConfig.TAG, "Chapar: Google sign-in returned an unreadable credential", e)
            viewModel.onGoogleSignInFailed(AccountError.FAILED)
            null
        }
    }

    private fun requestStart() {
        if (!SettingsManager.isVpnMode()) {
            startCore()
            return
        }
        val intent = VpnService.prepare(this)
        if (intent == null) startCore() else requestVpnPermission.launch(intent)
    }

    private fun startCore() {
        viewModel.onStartRequested()
        if (!LauncherManager.startServiceFromToggle(this)) viewModel.onStartRejected()
    }
}
