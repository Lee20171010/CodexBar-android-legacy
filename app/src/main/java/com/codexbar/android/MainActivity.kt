package com.codexbar.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codexbar.android.core.domain.model.AiService
import com.codexbar.android.core.security.EncryptedPrefsManager
import com.codexbar.android.core.update.AvailableUpdate
import com.codexbar.android.core.update.GitHubReleaseUpdateChecker
import com.codexbar.android.ui.theme.CodexBarTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private var pendingGeminiPairingUri by mutableStateOf<String?>(null)
    private var pendingCodexTelemetryPairingUri by mutableStateOf<String?>(null)
    private var pendingDashboardService by mutableStateOf<AiService?>(null)

    @Inject
    lateinit var prefsManager: EncryptedPrefsManager

    @Inject
    lateinit var releaseUpdateChecker: GitHubReleaseUpdateChecker

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val launchUri = intent?.data
        val initialDestination = startDestinationForHost(launchUri?.host)
        pendingGeminiPairingUri = geminiPairingUriOrNull(launchUri)
        pendingCodexTelemetryPairingUri = codexTelemetryPairingUriOrNull(launchUri)
        pendingDashboardService = dashboardServiceOrNull(launchUri)
        if (pendingGeminiPairingUri != null || pendingCodexTelemetryPairingUri != null) {
            intent?.data = null
        }
        applyScreenPrivacy(prefsManager.getPrivacySettings().screenPrivacyEnabled)
        lifecycleScope.launch {
            prefsManager.warmCache()
            applyScreenPrivacy(prefsManager.getPrivacySettings().screenPrivacyEnabled)
        }
        enableEdgeToEdge()

        setContent {
            val appThemeStyle by prefsManager.appThemeStyle.collectAsStateWithLifecycle()
            val minimalDisplayEnabled by prefsManager.minimalDisplayEnabled.collectAsStateWithLifecycle()
            CodexBarTheme(style = appThemeStyle) {
                var availableUpdate by remember { mutableStateOf<AvailableUpdate?>(null) }

                LaunchedEffect(Unit) {
                    availableUpdate = releaseUpdateChecker.checkForUpdate(BuildConfig.VERSION_NAME)
                }

                availableUpdate?.let { update ->
                    AlertDialog(
                        onDismissRequest = { availableUpdate = null },
                        title = { Text(stringResource(R.string.update_available_title)) },
                        text = {
                            Text(
                                stringResource(
                                    R.string.update_available_message,
                                    update.version
                                )
                            )
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    availableUpdate = null
                                    openUpdateDownload(update)
                                }
                            ) {
                                Text(stringResource(R.string.action_download_update))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { availableUpdate = null }) {
                                Text(stringResource(R.string.action_later))
                            }
                        }
                    )
                }

                CodexBarApp(
                    minimalDisplayEnabled = minimalDisplayEnabled,
                    initialDestination = initialDestination,
                    initialGeminiPairingUri = pendingGeminiPairingUri,
                    onGeminiPairingConsumed = { pendingGeminiPairingUri = null },
                    initialCodexTelemetryPairingUri = pendingCodexTelemetryPairingUri,
                    onCodexTelemetryPairingConsumed = {
                        pendingCodexTelemetryPairingUri = null
                    },
                    initialDashboardService = pendingDashboardService,
                    onDashboardServiceConsumed = { pendingDashboardService = null },
                    onScreenPrivacyChanged = ::applyScreenPrivacy
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val pairingUri = geminiPairingUriOrNull(intent.data)
        val codexTelemetryPairingUri = codexTelemetryPairingUriOrNull(intent.data)
        if (pairingUri != null) {
            pendingGeminiPairingUri = pairingUri
            intent.data = null
        }
        if (codexTelemetryPairingUri != null) {
            pendingCodexTelemetryPairingUri = codexTelemetryPairingUri
            intent.data = null
        }
        dashboardServiceOrNull(intent.data)?.let { service ->
            pendingDashboardService = service
        }
        setIntent(intent)
    }

    private fun applyScreenPrivacy(enabled: Boolean) {
        if (enabled) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    private fun openUpdateDownload(update: AvailableUpdate) {
        val urls = listOf(update.downloadUrl, update.releasePageUrl).distinct()
        for (url in urls) {
            val opened = runCatching {
                startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        .addCategory(Intent.CATEGORY_BROWSABLE)
                )
            }.isSuccess
            if (opened) return
        }
    }
}

internal fun startDestinationForHost(host: String?): String {
    return when {
        host.equals("settings", ignoreCase = true) -> "settings"
        host.equals("connections", ignoreCase = true) ||
            host.equals("gemini-pair", ignoreCase = true) ||
            host.equals("codex-telemetry-pair", ignoreCase = true) -> "connections"
        else -> "dashboard"
    }
}

/**
 * Reads the provider a notification or Now Bar entry was opened for, so the dashboard can show
 * that provider's detail instead of only the top of the list.
 */
internal fun dashboardServiceOrNull(uri: Uri?): AiService? {
    if (uri == null || uri.toString().length > 2048) return null
    if (!uri.scheme.equals("codexbar", ignoreCase = true)) return null
    if (!uri.host.equals("dashboard", ignoreCase = true)) return null
    val name = runCatching { uri.getQueryParameter(EXTRA_DASHBOARD_SERVICE) }.getOrNull() ?: return null
    return AiService.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
}

internal const val EXTRA_DASHBOARD_SERVICE = "service"

internal fun codexTelemetryPairingUriOrNull(uri: Uri?): String? {
    if (uri == null || uri.toString().length > 2048) return null
    return uri.toString().takeIf {
        uri.scheme.equals("codexbar", ignoreCase = true) &&
            uri.host.equals("codex-telemetry-pair", ignoreCase = true)
    }
}

internal fun geminiPairingUriOrNull(uri: Uri?): String? {
    if (uri == null || uri.toString().length > 2048) return null
    return uri.toString().takeIf {
        uri.scheme.equals("codexbar", ignoreCase = true) &&
            uri.host.equals("gemini-pair", ignoreCase = true)
    }
}
