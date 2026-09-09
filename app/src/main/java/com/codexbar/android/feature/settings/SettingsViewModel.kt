package com.codexbar.android.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import com.codexbar.android.R
import com.codexbar.android.core.auth.AccountLinkManager
import com.codexbar.android.core.auth.DeviceAuthSession
import com.codexbar.android.core.data.QuotaHistoryStore
import com.codexbar.android.core.data.QuotaRepositoryRegistry
import com.codexbar.android.core.domain.model.AiService
import com.codexbar.android.core.domain.model.AccountConnection
import com.codexbar.android.core.domain.model.AppThemeStyle
import com.codexbar.android.core.domain.model.AppError
import com.codexbar.android.core.domain.model.Credential
import com.codexbar.android.core.domain.model.ProviderAuthMode
import com.codexbar.android.core.domain.model.Result
import com.codexbar.android.core.domain.model.providerMetadata
import com.codexbar.android.core.monitoring.MonitoringSessionStore
import com.codexbar.android.core.network.gemini.GeminiCompanionPairing
import com.codexbar.android.core.network.antigravity.AntigravityCompanionPairing
import com.codexbar.android.core.network.claude.ClaudeCompanionPairing
import com.codexbar.android.core.network.codex.telemetry.CodexTelemetryClient
import com.codexbar.android.core.network.codex.telemetry.CodexTelemetryPairing
import com.codexbar.android.core.notification.QuotaNotificationService
import com.codexbar.android.core.security.EncryptedPrefsManager
import com.codexbar.android.core.security.ConnectionHealth
import com.codexbar.android.core.security.ConnectionHealthStore
import com.codexbar.android.core.security.TokenRefreshCoordinator
import com.codexbar.android.core.security.TokenRefreshStateStore
import com.codexbar.android.core.security.PrivacySettings
import com.codexbar.android.core.security.toConnectionHealth
import com.codexbar.android.core.widget.WidgetPrefsManager
import com.codexbar.android.core.widget.WidgetUpdater
import com.codexbar.android.core.workmanager.RefreshIntervalPolicy
import com.codexbar.android.core.workmanager.WorkManagerInitializer
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.UnknownHostException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repositoryRegistry: QuotaRepositoryRegistry,
    private val accountLinkManager: AccountLinkManager,
    private val codexTelemetryClient: CodexTelemetryClient,
    private val prefsManager: EncryptedPrefsManager,
    private val connectionHealthStore: ConnectionHealthStore,
    private val publicationGate: TokenRefreshCoordinator,
    private val tokenRefreshStateStore: TokenRefreshStateStore,
    private val quotaHistoryStore: QuotaHistoryStore,
    private val widgetPrefsManager: WidgetPrefsManager,
    private val monitoringSessionStore: MonitoringSessionStore,
    private val notificationService: QuotaNotificationService,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()
    private var antigravityPairingJob: Job? = null
    private var antigravityPairingGeneration = 0L
    private val antigravityPairingMutex = Mutex()
    private val accountOperations = mutableMapOf<AiService, Job>()
    private val editorVersions = mutableMapOf<AiService, Long>()

    init {
        viewModelScope.launch {
            connectionHealthStore.health.collect(::applyConnectionHealth)
        }
        viewModelScope.launch {
            prefsManager.warmCache()
            loadSavedCredentials()
            val monitoringSession = monitoringSessionStore.activeSession()
            _uiState.update {
                it.copy(
                    refreshIntervalMinutes = RefreshIntervalPolicy.normalize(
                        prefsManager.getRefreshInterval()
                    ),
                    persistentNotificationEnabled = prefsManager.isPersistentNotificationEnabled(),
                    isMonitoring = monitoringSession != null,
                    monitoringDurationMinutes = monitoringSessionStore.preferredDurationMinutes(),
                    monitoringRemainingMinutes = monitoringSession?.remainingMinutes(),
                    appThemeStyle = prefsManager.appThemeStyle.value,
                    minimalDisplayEnabled = prefsManager.minimalDisplayEnabled.value,
                    privacySettings = prefsManager.getPrivacySettings()
                )
            }
        }
    }

    private suspend fun loadSavedCredentials() {
        publicationGate.withPublicationLock {
        val connections = prefsManager.loadConnections()
        _uiState.update { it.copy(connections = connections) }
        for (connection in connections.distinctBy { it.service }) {
            if (connection.service !in editorVersions) loadConnection(connection.service, connection, null)
        }
        }
    }

    fun selectConnection(service: AiService, connection: AccountConnection?) {
        require(connection == null || connection.service == service)
        editorVersions[service] = (editorVersions[service] ?: 0) + 1
        val editorVersion = editorVersions[service]
        _uiState.update { it.copy(serviceStates = it.serviceStates + (service to ServiceCredentialState(isLoading = true))) }
        launchAccountOperation(service) {
            publicationGate.withPublicationLock {
                val connections = prefsManager.loadConnections()
                currentCoroutineContext().ensureActive()
                _uiState.update { it.copy(connections = connections) }
                loadConnection(service, connection?.let { selected -> connections.firstOrNull { it.id == selected.id } }, editorVersion)
            }
        }
    }

    private suspend fun loadConnection(service: AiService, connection: AccountConnection?, expectedEditorVersion: Long?) {
            val credential = connection?.let { prefsManager.loadCredential(it) }
            val state = when (credential) {
                is Credential.AntigravityCompanionCredential -> ServiceCredentialState(
                    isConnected = true,
                    connectionHealth = connectionHealthStore.current(service)
                )
                is Credential.ClaudeCompanionCredential -> ServiceCredentialState(
                    isConnected = true,
                    connectionHealth = connectionHealthStore.current(service)
                )
                is Credential.CodexCredential -> ServiceCredentialState(
                    accessToken = credential.accessToken,
                    refreshToken = credential.refreshToken,
                    accountId = credential.accountId ?: "",
                    isConnected = true,
                    connectionHealth = connectionHealthStore.current(service)
                )
                is Credential.GeminiCompanionCredential -> ServiceCredentialState(
                    isConnected = true,
                    connectionHealth = connectionHealthStore.current(service)
                )
                is Credential.CopilotCredential -> ServiceCredentialState(
                    accessToken = credential.accessToken,
                    isConnected = true,
                    connectionHealth = connectionHealthStore.current(service)
                )
                is Credential.ProviderSecretCredential -> ServiceCredentialState(
                    accessToken = credential.accessToken,
                    accountReference = credential.accountReference ?: "",
                    isConnected = true,
                    connectionHealth = connectionHealthStore.current(service)
                )
                null -> ServiceCredentialState()
            }.copy(
                connection = connection,
                connectionName = connection?.name ?: service.displayName,
                isConnected = connection != null,
                connectionHealth = connection?.let(connectionHealthStore::current)
                    ?: ConnectionHealth.UNKNOWN,
                isCodexTelemetryConnected = connection?.let {
                    service == AiService.CODEX && prefsManager.loadCodexTelemetryCredential(it) != null
                } ?: false
            )
            currentCoroutineContext().ensureActive()
            if (editorVersions[service] != expectedEditorVersion) return
            _uiState.update {
                it.copy(serviceStates = it.serviceStates + (service to state))
            }
    }

    private fun launchAccountOperation(service: AiService, block: suspend () -> Unit) {
        accountOperations.remove(service)?.cancel()
        accountOperations[service] = viewModelScope.launch {
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                _uiState.update { state ->
                    val current = state.serviceStates[service] ?: ServiceCredentialState()
                    state.copy(serviceStates = state.serviceStates + (service to current.copy(
                        isValidating = false,
                        isAccountLinking = false,
                        isCodexTelemetryValidating = false,
                        validationResult = ValidationResult.Failure(
                            error.message ?: appContext.getString(R.string.validation_unknown)
                        )
                    )))
                }
            }
        }
    }

    private suspend fun saveAccountCredential(
        service: AiService,
        state: ServiceCredentialState,
        credential: Credential
    ) {
        publicationGate.mutate {
            val name = state.connectionName.trim().ifBlank { service.displayName }
            val connection = state.connection?.let {
                prefsManager.saveCredential(it, credential)
                prefsManager.renameConnection(it, name)
                it.copy(name = name)
            } ?: prefsManager.createConnection(service, name, credential)
            if (service == AiService.CODEX && state.connection == null &&
                _uiState.value.connections.none { it.service == AiService.CODEX }
            ) {
                prefsManager.loadCodexTelemetryCredential()?.let {
                    prefsManager.saveCodexTelemetryCredential(connection, it)
                }
            }
            connectionHealthStore.update(connection, ConnectionHealth.CONNECTED)
            tokenRefreshStateStore.reset(connection)
            widgetPrefsManager.deleteConnectionCache(connection)
            notificationService.cancelResetNotifications(connection)
            notificationService.cancelQuotaNotification()
            notificationService.cancelMonitoringNotification()
            val connections = prefsManager.loadConnections()
            _uiState.update { ui ->
                val current = ui.serviceStates[service] ?: ServiceCredentialState()
                ui.copy(connections = connections, serviceStates = ui.serviceStates + (
                    service to current.copy(connection = connection, connectionName = connection.name)
                ))
            }
        }
        currentCoroutineContext().ensureActive()
    }

    fun renameConnection(service: AiService) {
        if (_uiState.value.serviceStates[service]?.isLoading == true) return
        val state = _uiState.value.serviceStates[service] ?: return
        val connection = state.connection ?: return
        val editorVersion = editorVersions[service]
        launchAccountOperation(service) {
            publicationGate.mutate {
                prefsManager.renameConnection(connection, state.connectionName.trim())
                val connections = prefsManager.loadConnections()
                _uiState.update { it.copy(connections = connections) }
                loadConnection(service, connection.copy(name = state.connectionName.trim()), editorVersion)
            }
            WorkManagerInitializer.enqueueManualQuotaRefresh(appContext, "connection_renamed")
        }
    }

    fun updateField(service: AiService, field: String, value: String) {
        if (_uiState.value.serviceStates[service]?.isLoading == true) return
        _uiState.update { state ->
            val current = state.serviceStates[service] ?: ServiceCredentialState()
            val updated = when (field) {
                "connectionName" -> current.copy(connectionName = value.take(80))
                "accessToken" -> current.copy(accessToken = value, validationResult = null, hasUnsavedChanges = true)
                "refreshToken" -> current.copy(refreshToken = value, validationResult = null, hasUnsavedChanges = true)
                "accountId" -> current.copy(accountId = value, validationResult = null, hasUnsavedChanges = true)
                "accountReference" -> current.copy(
                    accountReference = value,
                    validationResult = null,
                    hasUnsavedChanges = true
                )
                else -> current
            }
            state.copy(serviceStates = state.serviceStates + (service to updated))
        }
    }

    private fun buildCredential(service: AiService, state: ServiceCredentialState): Credential? {
        if (service == AiService.CLAUDE || service == AiService.GEMINI || service == AiService.ANTIGRAVITY) return null
        if (state.accessToken.isBlank()) return null

        return when {
            service == AiService.CODEX -> {
                if (state.refreshToken.isBlank()) return null
                Credential.CodexCredential(
                    accessToken = state.accessToken.trim(),
                    refreshToken = state.refreshToken.trim(),
                    accountId = state.accountId.trim().ifBlank { null }
                )
            }
            service == AiService.COPILOT -> Credential.CopilotCredential(
                accessToken = state.accessToken
            )
            service.providerMetadata.secretKind != null -> {
                if (service.providerMetadata.requiresAccountReference && state.accountReference.isBlank()) {
                    return null
                }
                Credential.ProviderSecretCredential(
                    service = service,
                    kind = checkNotNull(service.providerMetadata.secretKind),
                    accessToken = state.accessToken.trim(),
                    accountReference = state.accountReference.trim().ifBlank { null }
                )
            }
            else -> null
        }
    }

    fun validateCredential(service: AiService) {
        if (_uiState.value.serviceStates[service]?.isLoading == true) return
        val repo = repositoryFor(service)

        val state = _uiState.value.serviceStates[service] ?: return
        val credential = buildCredential(service, state)
        if (credential == null) {
            _uiState.update { currentState ->
                val current = currentState.serviceStates[service] ?: ServiceCredentialState()
                currentState.copy(
                    serviceStates = currentState.serviceStates + (service to current.copy(
                        validationResult = ValidationResult.Failure(
                            appContext.getString(R.string.validation_required_fields)
                        )
                    ))
                )
            }
            return
        }

        _uiState.update { state ->
            val current = state.serviceStates[service] ?: ServiceCredentialState()
            state.copy(
                serviceStates = state.serviceStates + (service to current.copy(isValidating = true, validationResult = null))
            )
        }

        launchAccountOperation(service) {
            val hadPreviousCredential = state.connection != null
            val result = repo.validateCredential(credential)
            currentCoroutineContext().ensureActive()
            val validationResult = when (result) {
                is Result.Success -> {
                    saveAccountCredential(service, state, credential)
                    ValidationResult.Success
                }
                is Result.Failure -> {
                    if (hadPreviousCredential && !state.hasUnsavedChanges) {
                        state.connection?.let { connectionHealthStore.update(it, result.error.toConnectionHealth()) }
                    }
                    ValidationResult.Failure(formatAppError(result.error))
                }
            }

            _uiState.update { state ->
                val current = state.serviceStates[service] ?: ServiceCredentialState()
                state.copy(
                    serviceStates = state.serviceStates + (service to current.copy(
                        isValidating = false,
                        validationResult = validationResult,
                        isConnected = validationResult is ValidationResult.Success || hadPreviousCredential,
                        connectionHealth = if (validationResult is ValidationResult.Success) {
                            ConnectionHealth.CONNECTED
                        } else {
                            current.connectionHealth
                        },
                        hasUnsavedChanges = validationResult !is ValidationResult.Success
                    ))
                )
            }
        }
    }

    fun startAccountLink(service: AiService) {
        if (_uiState.value.serviceStates[service]?.isLoading == true) return
        if (!service.supportsDeviceAccountLink()) return
        val target = _uiState.value.serviceStates[service] ?: return

        _uiState.update { state ->
            val current = state.serviceStates[service] ?: ServiceCredentialState()
            state.copy(
                serviceStates = state.serviceStates + (service to current.copy(
                    isAccountLinking = true,
                    accountLinkPrompt = null,
                    validationResult = null,
                    hasUnsavedChanges = false
                ))
            )
        }

        launchAccountOperation(service) {
            try {
                val hadPreviousCredential = target.connection != null
                val session = accountLinkManager.requestDeviceCode(service)
                currentCoroutineContext().ensureActive()
                _uiState.updateAccountLinkPrompt(service, session)

                val credential = accountLinkManager.completeDeviceCode(session)
                val validationResult = when (
                    val result = repositoryFor(service).validateCredential(credential)
                ) {
                    is Result.Success -> {
                        saveAccountCredential(service, target, credential)
                        ValidationResult.Success
                    }
                    is Result.Failure -> ValidationResult.Failure(formatAppError(result.error))
                }
                val validationSucceeded = validationResult is ValidationResult.Success

                _uiState.update { state ->
                    val current = state.serviceStates[service] ?: ServiceCredentialState()
                    state.copy(
                        serviceStates = state.serviceStates + (service to current.copy(
                            accessToken = if (validationSucceeded) credential.accessToken else current.accessToken,
                            refreshToken = if (validationSucceeded) {
                                credential.refreshToken ?: ""
                            } else {
                                current.refreshToken
                            },
                            isAccountLinking = false,
                            accountLinkPrompt = null,
                            validationResult = validationResult,
                            isConnected = validationSucceeded || hadPreviousCredential,
                            connectionHealth = if (validationSucceeded) {
                                ConnectionHealth.CONNECTED
                            } else {
                                current.connectionHealth
                            },
                            hasUnsavedChanges = false
                        ))
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                _uiState.update { state ->
                    val current = state.serviceStates[service] ?: ServiceCredentialState()
                    state.copy(
                        serviceStates = state.serviceStates + (service to current.copy(
                            isAccountLinking = false,
                            accountLinkPrompt = null,
                            validationResult = ValidationResult.Failure(
                                if (e.hasUnknownHostCause()) {
                                    appContext.getString(
                                        R.string.validation_account_link_dns_failed,
                                        service.displayName
                                    )
                                } else {
                                    e.message?.let {
                                        appContext.getString(
                                            R.string.validation_account_link_failed_detail,
                                            it
                                        )
                                    } ?: appContext.getString(
                                        R.string.validation_account_link_failed
                                    )
                                }
                            ),
                            isConnected = current.isConnected
                        ))
                    )
                }
            }
        }
    }

    fun setRefreshInterval(minutes: Long) {
        val normalizedMinutes = RefreshIntervalPolicy.normalize(minutes)
        _uiState.update { it.copy(refreshIntervalMinutes = normalizedMinutes) }
        viewModelScope.launch {
            prefsManager.setRefreshInterval(normalizedMinutes)
            WorkManagerInitializer.applyRefreshPolicy(appContext, normalizedMinutes)
        }
    }

    fun refreshLocalizedSurfaces() {
        WorkManagerInitializer.enqueueManualQuotaRefresh(
            context = appContext,
            source = "language_changed"
        )
    }

    fun setPersistentNotificationEnabled(enabled: Boolean) {
        _uiState.update {
            it.copy(persistentNotificationEnabled = enabled)
        }
        viewModelScope.launch {
            prefsManager.setPersistentNotificationEnabled(enabled)
            if (enabled) {
                WorkManagerInitializer.enqueueManualQuotaRefresh(
                    appContext,
                    source = "persistent_notification_enabled"
                )
            } else {
                notificationService.cancelQuotaNotification()
            }
        }
    }

    fun setMonitoringDuration(minutes: Long) {
        val bounded = minutes.coerceIn(
            MonitoringSessionStore.MIN_DURATION_MINUTES,
            MonitoringSessionStore.MAX_DURATION_MINUTES
        )
        monitoringSessionStore.setPreferredDurationMinutes(bounded)
        _uiState.update { it.copy(monitoringDurationMinutes = bounded) }
    }

    fun startMonitoring() {
        viewModelScope.launch {
            val session = WorkManagerInitializer.startMonitoringSession(
                context = appContext,
                durationMinutes = _uiState.value.monitoringDurationMinutes
            )
            notificationService.showMonitoringPlaceholder(session)
            _uiState.update {
                it.copy(
                    isMonitoring = true,
                    monitoringRemainingMinutes = session.remainingMinutes()
                )
            }
        }
    }

    fun stopMonitoring() {
        WorkManagerInitializer.stopMonitoringSession(appContext)
        notificationService.cancelMonitoringNotification()
        _uiState.update {
            it.copy(isMonitoring = false, monitoringRemainingMinutes = null)
        }
    }

    fun syncMonitoringState() {
        val session = monitoringSessionStore.activeSession()
        _uiState.update {
            it.copy(
                isMonitoring = session != null,
                monitoringRemainingMinutes = session?.remainingMinutes()
            )
        }
    }

    fun setPrivacySettings(settings: PrivacySettings) {
        _uiState.update { it.copy(privacySettings = settings) }
        viewModelScope.launch {
            prefsManager.setPrivacySettings(settings)
            notificationService.refreshPrivacySettings(monitoringSessionStore.activeSession())
            WidgetUpdater.updateAll(appContext)
        }
    }

    fun setMinimalDisplayEnabled(enabled: Boolean) {
        _uiState.update { it.copy(minimalDisplayEnabled = enabled) }
        viewModelScope.launch {
            prefsManager.setMinimalDisplayEnabled(enabled)
        }
    }

    fun setAppThemeStyle(style: AppThemeStyle) {
        _uiState.update { it.copy(appThemeStyle = style) }
        viewModelScope.launch {
            prefsManager.setAppThemeStyle(style)
        }
    }

    fun showDeleteConfirmDialog() {
        _uiState.update { it.copy(showDeleteConfirmDialog = true) }
    }

    fun dismissDeleteConfirmDialog() {
        _uiState.update { it.copy(showDeleteConfirmDialog = false) }
    }

    fun showDisconnectConfirmDialog(service: AiService) {
        val connection = _uiState.value.serviceStates[service]?.connection ?: return
        _uiState.update { it.copy(disconnectConfirmConnection = connection) }
    }

    fun dismissDisconnectConfirmDialog() {
        _uiState.update { it.copy(disconnectConfirmConnection = null) }
    }

    fun disconnectConnection(connection: AccountConnection) {
        if (connection.service == AiService.ANTIGRAVITY) cancelAntigravityPairing()
        editorVersions[connection.service] = (editorVersions[connection.service] ?: 0) + 1
        val editorVersion = editorVersions[connection.service]
        accountOperations.remove(connection.service)?.cancel()
        dismissDisconnectConfirmDialog()
        viewModelScope.launch {
            publicationGate.mutate {
                if (connection.service == AiService.ANTIGRAVITY) {
                    antigravityPairingMutex.withLock { prefsManager.deleteCredential(connection) }
                } else {
                    prefsManager.deleteCredential(connection)
                }
                clearConnectionData(connection)
                val connections = prefsManager.loadConnections()
                _uiState.update { it.copy(connections = connections) }
                if (editorVersions[connection.service] == editorVersion) {
                    loadConnection(connection.service, connections.firstOrNull { it.service == connection.service }, editorVersion)
                }
            }
            WorkManagerInitializer.enqueueManualQuotaRefresh(appContext, "connection_deleted")
        }
    }

    private fun clearConnectionData(connection: AccountConnection) {
        connectionHealthStore.clear(connection)
        quotaHistoryStore.deleteConnection(connection)
        widgetPrefsManager.deleteConnectionCache(connection)
        tokenRefreshStateStore.reset(connection)
        notificationService.cancelResetNotifications(connection)
        notificationService.cancelQuotaNotification()
        notificationService.cancelMonitoringNotification()
    }

    fun deleteAllCredentials() {
        cancelAntigravityPairing()
        AiService.entries.forEach { editorVersions[it] = (editorVersions[it] ?: 0) + 1 }
        accountOperations.values.forEach { it.cancel() }
        accountOperations.clear()
        _uiState.update {
            SettingsUiState(
                refreshIntervalMinutes = it.refreshIntervalMinutes,
                persistentNotificationEnabled = it.persistentNotificationEnabled,
                isMonitoring = it.isMonitoring,
                monitoringDurationMinutes = it.monitoringDurationMinutes,
                monitoringRemainingMinutes = it.monitoringRemainingMinutes,
                appThemeStyle = it.appThemeStyle,
                minimalDisplayEnabled = it.minimalDisplayEnabled,
                privacySettings = it.privacySettings
            )
        }
        viewModelScope.launch {
            publicationGate.mutate {
                val connections = prefsManager.loadConnections()
                antigravityPairingMutex.withLock { prefsManager.deleteAllCredentials() }
                connections.forEach(::clearConnectionData)
                connectionHealthStore.clearAll()
                AiService.entries.forEach { quotaHistoryStore.deleteService(it) }
                widgetPrefsManager.deleteAllServiceCaches()
                notificationService.cancelAllNotifications()
                _uiState.update {
                    it.copy(
                        connections = emptyList(),
                        serviceStates = AiService.entries.associateWith { ServiceCredentialState() },
                        connectionHealth = emptyMap()
                    )
                }
            }
            WorkManagerInitializer.enqueueManualQuotaRefresh(appContext, "connections_deleted")
        }
    }

    private fun formatExpiryMs(expiresAtMs: Long): String {
        return try {
            val instant = Instant.ofEpochMilli(expiresAtMs)
            val locale = appContext.resources.configuration.locales[0]
            val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
                .withLocale(locale)
                .withZone(ZoneId.systemDefault())
            formatter.format(instant)
        } catch (_: Exception) {
            appContext.getString(R.string.validation_unknown)
        }
    }

    private fun repositoryFor(service: AiService) = repositoryRegistry.repositoryFor(service)

    private fun AiService.supportsDeviceAccountLink(): Boolean {
        return providerMetadata.authMode == ProviderAuthMode.DEVICE_SIGN_IN
    }

    fun updateGeminiPairingCode(value: String) {
        if (_uiState.value.serviceStates[AiService.GEMINI]?.isLoading == true) return
        _uiState.update { state ->
            val current = state.serviceStates[AiService.GEMINI] ?: ServiceCredentialState()
            state.copy(
                serviceStates = state.serviceStates + (
                    AiService.GEMINI to current.copy(
                        geminiPairingCode = value.take(MAX_PAIRING_CODE_LENGTH),
                        validationResult = null,
                        hasUnsavedChanges = true
                    )
                )
            )
        }
    }

    fun importGeminiPairingCode(value: String) {
        updateGeminiPairingCode(value)
    }

    fun updateCodexTelemetryPairingCode(value: String) {
        if (_uiState.value.serviceStates[AiService.CODEX]?.isLoading == true) return
        _uiState.update { state ->
            val current = state.serviceStates[AiService.CODEX] ?: ServiceCredentialState()
            state.copy(
                serviceStates = state.serviceStates + (
                    AiService.CODEX to current.copy(
                        codexTelemetryPairingCode = value.take(MAX_PAIRING_CODE_LENGTH),
                        codexTelemetryValidationResult = null
                    )
                )
            )
        }
    }

    fun importCodexTelemetryPairingCode(value: String) {
        updateCodexTelemetryPairingCode(value)
    }

    fun connectCodexTelemetryCompanion() {
        val state = _uiState.value.serviceStates[AiService.CODEX] ?: return
        val connection = state.connection ?: return
        val credential = runCatching {
            CodexTelemetryPairing.parse(state.codexTelemetryPairingCode)
        }.getOrElse { error ->
            updateCodexTelemetryValidation(
                isValidating = false,
                result = ValidationResult.Failure(
                    appContext.getString(
                        R.string.validation_codex_telemetry_pairing_invalid,
                        error.message ?: appContext.getString(R.string.validation_unknown)
                    )
                )
            )
            return
        }

        updateCodexTelemetryValidation(isValidating = true, result = null)
        launchAccountOperation(AiService.CODEX) {
            try {
                codexTelemetryClient.fetchSnapshot(credential)
                currentCoroutineContext().ensureActive()
                publicationGate.mutate {
                    currentCoroutineContext().ensureActive()
                    prefsManager.saveCodexTelemetryCredential(connection, credential)
                    clearCodexTelemetryCache()
                }
                currentCoroutineContext().ensureActive()
                updateCodexTelemetryValidation(
                    isValidating = false,
                    result = ValidationResult.Success,
                    connected = true,
                    clearPairingCode = true
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                updateCodexTelemetryValidation(
                    isValidating = false,
                    result = ValidationResult.Failure(
                        appContext.getString(
                            R.string.validation_codex_telemetry_failed,
                            error.message ?: appContext.getString(R.string.validation_unknown)
                        )
                    )
                )
            }
        }
    }

    fun disconnectCodexTelemetryCompanion() {
        val connection = _uiState.value.serviceStates[AiService.CODEX]?.connection ?: return
        launchAccountOperation(AiService.CODEX) {
            publicationGate.mutate {
                prefsManager.deleteCodexTelemetryCredential(connection)
                clearCodexTelemetryCache()
            }
            currentCoroutineContext().ensureActive()
            updateCodexTelemetryValidation(
                isValidating = false,
                result = null,
                connected = false,
                replaceConnectedState = true,
                clearPairingCode = true
            )
        }
    }

    private suspend fun clearCodexTelemetryCache() {
        prefsManager.loadConnections().filter { it.service == AiService.CODEX }
            .forEach(widgetPrefsManager::deleteConnectionCache)
        notificationService.cancelQuotaNotification()
        notificationService.cancelMonitoringNotification()
        WorkManagerInitializer.enqueueManualQuotaRefresh(appContext, "codex_telemetry_changed")
    }

    private fun updateCodexTelemetryValidation(
        isValidating: Boolean,
        result: ValidationResult?,
        connected: Boolean = false,
        replaceConnectedState: Boolean = false,
        clearPairingCode: Boolean = false
    ) {
        _uiState.update { state ->
            val current = state.serviceStates[AiService.CODEX] ?: ServiceCredentialState()
            state.copy(
                serviceStates = state.serviceStates + (
                    AiService.CODEX to current.copy(
                        codexTelemetryPairingCode = if (clearPairingCode) {
                            ""
                        } else {
                            current.codexTelemetryPairingCode
                        },
                        isCodexTelemetryConnected = if (replaceConnectedState) {
                            connected
                        } else {
                            current.isCodexTelemetryConnected || connected
                        },
                        isCodexTelemetryValidating = isValidating,
                        codexTelemetryValidationResult = result
                    )
                )
            )
        }
    }

    fun connectGeminiCompanion() {
        if (_uiState.value.serviceStates[AiService.GEMINI]?.isLoading == true) return
        val state = _uiState.value.serviceStates[AiService.GEMINI] ?: return
        val credential = runCatching {
            GeminiCompanionPairing.parse(state.geminiPairingCode)
        }.getOrElse { error ->
            updateGeminiValidation(
                isValidating = false,
                validationResult = ValidationResult.Failure(
                    appContext.getString(
                        R.string.validation_gemini_pairing_invalid,
                        error.message ?: appContext.getString(R.string.validation_unknown)
                    )
                ),
                keepExistingConnection = true
            )
            return
        }

        updateGeminiValidation(
            isValidating = true,
            validationResult = null,
            keepExistingConnection = true
        )
        launchAccountOperation(AiService.GEMINI) {
            val hadPreviousConnection = state.connection != null
            val result = repositoryFor(AiService.GEMINI).validateCredential(credential)
            currentCoroutineContext().ensureActive()
            when (result) {
                is Result.Success -> {
                    saveAccountCredential(AiService.GEMINI, state, credential)
                    updateGeminiValidation(
                        isValidating = false,
                        validationResult = ValidationResult.Success,
                        keepExistingConnection = false,
                        connected = true,
                        clearPairingCode = true
                    )
                    WorkManagerInitializer.enqueueManualQuotaRefresh(
                        appContext,
                        source = "gemini_companion_connected"
                    )
                }
                is Result.Failure -> {
                    updateGeminiValidation(
                        isValidating = false,
                        validationResult = ValidationResult.Failure(
                            appContext.getString(
                                R.string.validation_gemini_companion_failed,
                                formatAppError(result.error)
                            )
                        ),
                        keepExistingConnection = true,
                        connected = hadPreviousConnection
                    )
                }
            }
        }
    }

    private fun updateGeminiValidation(
        isValidating: Boolean,
        validationResult: ValidationResult?,
        keepExistingConnection: Boolean,
        connected: Boolean = false,
        clearPairingCode: Boolean = false
    ) {
        _uiState.update { state ->
            val current = state.serviceStates[AiService.GEMINI] ?: ServiceCredentialState()
            state.copy(
                serviceStates = state.serviceStates + (
                    AiService.GEMINI to current.copy(
                        geminiPairingCode = if (clearPairingCode) "" else current.geminiPairingCode,
                        isValidating = isValidating,
                        validationResult = validationResult,
                        isConnected = if (keepExistingConnection) {
                            current.isConnected || connected
                        } else {
                            connected
                        },
                        hasUnsavedChanges = !clearPairingCode && current.geminiPairingCode.isNotBlank()
                    )
                )
            )
        }
    }

    private fun MutableStateFlow<SettingsUiState>.updateAccountLinkPrompt(
        service: AiService,
        session: DeviceAuthSession
    ) {
        update { state ->
            val current = state.serviceStates[service] ?: ServiceCredentialState()
            state.copy(
                serviceStates = state.serviceStates + (service to current.copy(
                    isAccountLinking = true,
                    accountLinkPrompt = AccountLinkPrompt(
                        verificationUrl = session.verificationUrl,
                        userCode = session.userCode,
                        expiresAtDisplay = formatExpiryMs(session.expiresAtEpochMs)
                    )
                ))
            )
        }
    }

    private fun formatAppError(error: AppError): String {
        return when (error) {
            is AppError.NetworkError -> appContext.getString(
                R.string.validation_network_error,
                error.message
            )
            is AppError.AuthError -> appContext.getString(
                if (error.permissionDenied) {
                    R.string.validation_permission_denied
                } else if (error.isTerminal) {
                    R.string.validation_authentication_required
                } else {
                    R.string.validation_authentication_error
                }
            )
            is AppError.RateLimited -> error.retryAt?.let {
                appContext.getString(R.string.validation_rate_limited_until, it)
            } ?: appContext.getString(R.string.validation_rate_limited)
            is AppError.ParseError -> appContext.getString(
                R.string.validation_parse_error,
                error.message
            )
            is AppError.CredentialNotFound -> appContext.getString(
                R.string.validation_no_credentials
            )
            is AppError.ServiceUnavailable -> appContext.getString(
                R.string.validation_service_unavailable
            )
        }
    }

    fun updateAntigravityPairingCode(value: String) {
        _uiState.update { state ->
            val current = state.serviceStates[AiService.ANTIGRAVITY] ?: ServiceCredentialState()
            state.copy(
                serviceStates = state.serviceStates + (
                    AiService.ANTIGRAVITY to current.copy(
                        antigravityPairingCode = value.take(MAX_PAIRING_CODE_LENGTH),
                        validationResult = null
                    )
                )
            )
        }
    }

    /**
     * Accepts a pairing code that arrived whole, from the in-app scanner or the clipboard, and
     * pairs immediately. Asking for a second tap after a successful scan only adds a step, and a
     * malformed code reports the same error it would report from the button.
     */
    fun importAntigravityPairingCode(value: String) {
        updateAntigravityPairingCode(value)
        connectAntigravityCompanion()
    }

    fun reportAntigravityPairingClipboardEmpty() {
        updateAntigravityValidation(
            isValidating = false,
            validationResult = ValidationResult.Failure(
                appContext.getString(R.string.validation_antigravity_clipboard_empty)
            ),
            keepExistingConnection = true
        )
    }

    fun reportAntigravityPairingScanFailure() {
        updateAntigravityValidation(
            isValidating = false,
            validationResult = ValidationResult.Failure(
                appContext.getString(R.string.validation_antigravity_scanner_failed)
            ),
            keepExistingConnection = true
        )
    }

    fun connectAntigravityCompanion() {
        cancelAntigravityPairing()
        val generation = antigravityPairingGeneration
        val state = _uiState.value.serviceStates[AiService.ANTIGRAVITY] ?: return
        val credential = runCatching {
            AntigravityCompanionPairing.parse(state.antigravityPairingCode)
        }.getOrElse { error ->
            updateAntigravityValidation(
                isValidating = false,
                validationResult = ValidationResult.Failure(
                    appContext.getString(
                        R.string.validation_antigravity_pairing_invalid,
                        error.message ?: appContext.getString(R.string.validation_unknown)
                    )
                ),
                keepExistingConnection = true
            )
            return
        }

        updateAntigravityValidation(
            isValidating = true,
            validationResult = null,
            keepExistingConnection = true
        )
        antigravityPairingJob = viewModelScope.launch {
            val hadPreviousConnection = prefsManager.loadCredential(AiService.ANTIGRAVITY) != null
            val result = repositoryFor(AiService.ANTIGRAVITY).validateCredential(credential)
            if (generation != antigravityPairingGeneration) return@launch
            when (result) {
                is Result.Success -> {
                    antigravityPairingMutex.withLock {
                        if (generation != antigravityPairingGeneration) return@launch
                        prefsManager.saveCredential(AiService.ANTIGRAVITY, credential)
                    }
                    if (generation != antigravityPairingGeneration) return@launch
                    connectionHealthStore.update(AiService.ANTIGRAVITY, ConnectionHealth.CONNECTED)
                    updateAntigravityValidation(
                        isValidating = false,
                        validationResult = ValidationResult.Success,
                        keepExistingConnection = false,
                        connected = true,
                        clearPairingCode = true
                    )
                    WorkManagerInitializer.enqueueManualQuotaRefresh(
                        appContext,
                        source = "antigravity_companion_connected"
                    )
                }
                is Result.Failure -> {
                    updateAntigravityValidation(
                        isValidating = false,
                        validationResult = ValidationResult.Failure(
                            appContext.getString(
                                R.string.validation_antigravity_companion_failed,
                                formatAppError(result.error)
                            )
                        ),
                        keepExistingConnection = true,
                        connected = hadPreviousConnection
                    )
                }
            }
        }
    }

    private fun cancelAntigravityPairing() {
        antigravityPairingGeneration++
        antigravityPairingJob?.cancel()
        antigravityPairingJob = null
    }

    private fun updateAntigravityValidation(
        isValidating: Boolean,
        validationResult: ValidationResult?,
        keepExistingConnection: Boolean,
        connected: Boolean = false,
        clearPairingCode: Boolean = false
    ) {
        _uiState.update { state ->
            val current = state.serviceStates[AiService.ANTIGRAVITY] ?: ServiceCredentialState()
            state.copy(
                serviceStates = state.serviceStates + (
                    AiService.ANTIGRAVITY to current.copy(
                        accessToken = if (clearPairingCode) "" else current.accessToken,
                        refreshToken = if (clearPairingCode) "" else current.refreshToken,
                        antigravityPairingCode = if (clearPairingCode) {
                            ""
                        } else {
                            current.antigravityPairingCode
                        },
                        isValidating = isValidating,
                        validationResult = validationResult,
                        isConnected = if (keepExistingConnection) {
                            current.isConnected || connected
                        } else {
                            connected
                        },
                        connectionHealth = if (connected) {
                            ConnectionHealth.CONNECTED
                        } else {
                            current.connectionHealth
                        },
                        hasUnsavedChanges = if (clearPairingCode) {
                            false
                        } else {
                            current.hasUnsavedChanges
                        }
                    )
                )
            )
        }
    }

    fun updateClaudePairingCode(value: String) {
        if (_uiState.value.serviceStates[AiService.CLAUDE]?.isLoading == true) return
        _uiState.update { state ->
            val current = state.serviceStates[AiService.CLAUDE] ?: ServiceCredentialState()
            state.copy(
                serviceStates = state.serviceStates + (
                    AiService.CLAUDE to current.copy(
                        claudePairingCode = value.take(MAX_PAIRING_CODE_LENGTH),
                        validationResult = null
                    )
                )
            )
        }
    }

    /**
     * Accepts a pairing code that arrived whole, from the in-app scanner or the clipboard, and
     * pairs immediately. Asking for a second tap after a successful scan only adds a step, and a
     * malformed code reports the same error it would report from the button.
     */
    fun importClaudePairingCode(value: String) {
        updateClaudePairingCode(value)
        connectClaudeCompanion()
    }

    fun reportClaudePairingClipboardEmpty() {
        updateClaudeValidation(
            isValidating = false,
            validationResult = ValidationResult.Failure(
                appContext.getString(R.string.validation_claude_clipboard_empty)
            ),
            keepExistingConnection = true
        )
    }

    fun captureClaudePairingScan(): (String?) -> Unit {
        val version = editorVersions[AiService.CLAUDE]
        return { value ->
            if (editorVersions[AiService.CLAUDE] == version) {
                if (value == null) reportClaudePairingScanFailure() else importClaudePairingCode(value)
            }
        }
    }

    fun reportClaudePairingScanFailure() {
        updateClaudeValidation(
            isValidating = false,
            validationResult = ValidationResult.Failure(
                appContext.getString(R.string.validation_claude_scanner_failed)
            ),
            keepExistingConnection = true
        )
    }

    fun connectClaudeCompanion() {
        if (_uiState.value.serviceStates[AiService.CLAUDE]?.isLoading == true) return
        val state = _uiState.value.serviceStates[AiService.CLAUDE] ?: return
        val credential = runCatching {
            ClaudeCompanionPairing.parse(state.claudePairingCode)
        }.getOrElse { error ->
            updateClaudeValidation(
                isValidating = false,
                validationResult = ValidationResult.Failure(
                    appContext.getString(
                        R.string.validation_claude_pairing_invalid,
                        error.message ?: appContext.getString(R.string.validation_unknown)
                    )
                ),
                keepExistingConnection = true
            )
            return
        }

        updateClaudeValidation(
            isValidating = true,
            validationResult = null,
            keepExistingConnection = true
        )
        launchAccountOperation(AiService.CLAUDE) {
            val hadPreviousConnection = state.connection != null
            val result = repositoryFor(AiService.CLAUDE).validateCredential(credential)
            currentCoroutineContext().ensureActive()
            when (result) {
                is Result.Success -> {
                    saveAccountCredential(AiService.CLAUDE, state, credential)
                    updateClaudeValidation(
                        isValidating = false,
                        validationResult = ValidationResult.Success,
                        keepExistingConnection = false,
                        connected = true,
                        clearPairingCode = true
                    )
                    WorkManagerInitializer.enqueueManualQuotaRefresh(
                        appContext,
                        source = "claude_companion_connected"
                    )
                }
                is Result.Failure -> {
                    updateClaudeValidation(
                        isValidating = false,
                        validationResult = ValidationResult.Failure(
                            appContext.getString(
                                R.string.validation_claude_companion_failed,
                                formatAppError(result.error)
                            )
                        ),
                        keepExistingConnection = true,
                        connected = hadPreviousConnection
                    )
                }
            }
        }
    }

    private fun updateClaudeValidation(
        isValidating: Boolean,
        validationResult: ValidationResult?,
        keepExistingConnection: Boolean,
        connected: Boolean = false,
        clearPairingCode: Boolean = false
    ) {
        _uiState.update { state ->
            val current = state.serviceStates[AiService.CLAUDE] ?: ServiceCredentialState()
            state.copy(
                serviceStates = state.serviceStates + (
                    AiService.CLAUDE to current.copy(
                        accessToken = if (clearPairingCode) "" else current.accessToken,
                        refreshToken = if (clearPairingCode) "" else current.refreshToken,
                        claudePairingCode = if (clearPairingCode) {
                            ""
                        } else {
                            current.claudePairingCode
                        },
                        isValidating = isValidating,
                        validationResult = validationResult,
                        isConnected = if (keepExistingConnection) {
                            current.isConnected || connected
                        } else {
                            connected
                        },
                        connectionHealth = if (connected) {
                            ConnectionHealth.CONNECTED
                        } else {
                            current.connectionHealth
                        },
                        hasUnsavedChanges = if (clearPairingCode) {
                            false
                        } else {
                            current.hasUnsavedChanges
                        }
                    )
                )
            )
        }
    }

    private fun applyConnectionHealth(health: Map<String, ConnectionHealth>) {
        _uiState.update { state ->
            state.copy(
                connectionHealth = health,
                serviceStates = state.serviceStates.mapValues { (service, credentialState) ->
                    if (credentialState.isConnected) {
                        credentialState.copy(
                            connectionHealth = health[credentialState.connection?.id] ?: ConnectionHealth.UNKNOWN
                        )
                    } else {
                        credentialState.copy(connectionHealth = ConnectionHealth.UNKNOWN)
                    }
                }
            )
        }
    }
}

internal fun Throwable.hasUnknownHostCause(): Boolean {
    val visited = mutableSetOf<Throwable>()
    var current: Throwable? = this
    while (current != null && visited.add(current)) {
        if (current is UnknownHostException) return true
        current = current.cause
    }
    return false
}

private const val MAX_PAIRING_CODE_LENGTH = 2048
