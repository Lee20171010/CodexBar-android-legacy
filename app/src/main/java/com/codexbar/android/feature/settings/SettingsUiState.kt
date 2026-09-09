package com.codexbar.android.feature.settings

import com.codexbar.android.core.domain.model.AiService
import com.codexbar.android.core.domain.model.AccountConnection
import com.codexbar.android.core.domain.model.AppThemeStyle
import com.codexbar.android.core.security.PrivacySettings
import com.codexbar.android.core.security.ConnectionHealth

data class SettingsUiState(
    val connections: List<AccountConnection> = emptyList(),
    val connectionHealth: Map<String, ConnectionHealth> = emptyMap(),
    val disconnectConfirmConnection: AccountConnection? = null,
    val serviceStates: Map<AiService, ServiceCredentialState> = AiService.entries.associateWith {
        ServiceCredentialState()
    },
    val refreshIntervalMinutes: Long = 30L,
    val persistentNotificationEnabled: Boolean = true,
    val isMonitoring: Boolean = false,
    val monitoringDurationMinutes: Long = 60L,
    val monitoringRemainingMinutes: Long? = null,
    val appThemeStyle: AppThemeStyle = AppThemeStyle.MATERIAL_3,
    val minimalDisplayEnabled: Boolean = false,
    val privacySettings: PrivacySettings = PrivacySettings(),
    val showDeleteConfirmDialog: Boolean = false,
)

data class ServiceCredentialState(
    val isLoading: Boolean = false,
    val connection: AccountConnection? = null,
    val connectionName: String = "",
    val accessToken: String = "",
    val refreshToken: String = "",
    val accountId: String = "", // Codex only
    val accountReference: String = "", // Provider-specific non-secret account selector
    val antigravityPairingCode: String = "",
    val claudePairingCode: String = "",
    val geminiPairingCode: String = "",
    val codexTelemetryPairingCode: String = "",
    val isCodexTelemetryConnected: Boolean = false,
    val isCodexTelemetryValidating: Boolean = false,
    val codexTelemetryValidationResult: ValidationResult? = null,
    val isValidating: Boolean = false,
    val isAccountLinking: Boolean = false,
    val accountLinkPrompt: AccountLinkPrompt? = null,
    val validationResult: ValidationResult? = null,
    val isConnected: Boolean = false,
    val connectionHealth: ConnectionHealth = ConnectionHealth.UNKNOWN,
    val hasUnsavedChanges: Boolean = false
)

data class AccountLinkPrompt(
    val verificationUrl: String,
    val userCode: String,
    val expiresAtDisplay: String
)

sealed class ValidationResult {
    data object Success : ValidationResult()
    data class Failure(val message: String) : ValidationResult()
}
