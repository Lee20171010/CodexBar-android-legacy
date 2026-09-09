package com.codexbar.android.feature.dashboard

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codexbar.android.core.data.QuotaHistoryStore
import com.codexbar.android.core.data.QuotaRepositoryRegistry
import com.codexbar.android.core.domain.model.AiService
import com.codexbar.android.core.domain.model.AccountConnection
import com.codexbar.android.core.domain.model.AppError
import com.codexbar.android.core.domain.model.Result
import com.codexbar.android.core.monitoring.MonitoringSessionStore
import com.codexbar.android.core.notification.QuotaNotificationService
import com.codexbar.android.core.presentation.AndroidQuotaPresentationText
import com.codexbar.android.core.presentation.PrivacyPresentation
import com.codexbar.android.core.presentation.QuotaPresentationMapper
import com.codexbar.android.core.presentation.QuotaPresentationSnapshot
import com.codexbar.android.core.security.EncryptedPrefsManager
import com.codexbar.android.core.security.ConnectionHealthStore
import com.codexbar.android.core.security.TokenRefreshCoordinator
import com.codexbar.android.core.widget.WidgetUpdater
import com.codexbar.android.core.widget.WidgetPrefsManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val repositoryRegistry: QuotaRepositoryRegistry,
    private val prefsManager: EncryptedPrefsManager,
    private val connectionHealthStore: ConnectionHealthStore,
    private val publicationGate: TokenRefreshCoordinator,
    private val quotaHistoryStore: QuotaHistoryStore,
    private val monitoringSessionStore: MonitoringSessionStore,
    private val notificationService: QuotaNotificationService,
    private val widgetPrefsManager: WidgetPrefsManager,
    private val orderPrefs: DashboardOrderPrefs,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    private val presentationMapper = QuotaPresentationMapper(
        text = AndroidQuotaPresentationText(appContext)
    )

    private val _uiState = MutableStateFlow<DashboardUiState>(DashboardUiState.Loading)
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _providerOrder = MutableStateFlow(orderPrefs.load())
    val providerOrder: StateFlow<List<AiService>> = _providerOrder.asStateFlow()

    fun setProviderOrder(order: List<AiService>) {
        _providerOrder.value = orderPrefs.save(order)
    }

    init {
        viewModelScope.launch {
            publicationGate.revision.collectLatest {
                _uiState.value = DashboardUiState.Loading
                _isRefreshing.first { !it }
                refresh()
            }
        }
    }

    fun refresh() {
        if (_isRefreshing.value) return
        _isRefreshing.value = true

        viewModelScope.launch {
            try {
                val revision = publicationGate.revision.value
                val connections = prefsManager.loadConnections()

                if (connections.isEmpty()) {
                    val snapshot = presentationMapper.map(
                        emptyList(),
                        generatedAt = Instant.now()
                    )
                    publicationGate.publish(revision) { publishSnapshot(snapshot) }
                    return@launch
                }

                val deferreds = connections.map { connection ->
                    async { connection to repositoryRegistry.fetchQuota(connection) }
                }

                val results = deferreds.map { it.await() }

                publicationGate.publish(revision) {
                val successfulQuotas = mutableListOf<com.codexbar.android.core.domain.model.QuotaInfo>()
                val errors = mutableMapOf<AccountConnection, AppError>()
                val activeIds = prefsManager.loadConnections().map { it.id }.toSet()

                for ((connection, result) in results) {
                    if (connection.id !in activeIds) continue
                    connectionHealthStore.record(connection, result)
                    when (result) {
                        is Result.Success -> {
                            successfulQuotas.add(result.value)
                        }
                        is Result.Failure -> {
                            errors[connection] = result.error
                        }
                    }
                }

                val now = Instant.now()
                quotaHistoryStore.record(successfulQuotas)
                val paceByMetricKey = quotaHistoryStore.paceFor(successfulQuotas, now)
                val historyByMetricKey = quotaHistoryStore.historyFor(successfulQuotas)
                val privacySettings = prefsManager.getPrivacySettings()
                val privacy = PrivacyPresentation(
                    redactSensitiveValues = false,
                    lockScreenRedacted = privacySettings.lockScreenRedactionEnabled,
                    widgetRedacted = privacySettings.widgetRedactionEnabled
                )

                val snapshot = presentationMapper.map(
                    quotas = successfulQuotas,
                    connectionErrors = errors,
                    generatedAt = now,
                    privacy = privacy,
                    paceByMetricKey = paceByMetricKey,
                    historyByMetricKey = historyByMetricKey
                )
                publishSnapshot(snapshot)
                }
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    private suspend fun publishSnapshot(snapshot: QuotaPresentationSnapshot) {
        _uiState.value = DashboardUiState.Content(snapshot)
        notificationService.publishSnapshot(
            snapshot = snapshot,
            monitoringSession = monitoringSessionStore.activeSession()
        )

        // The dashboard already owns the freshest provider response. Persist and render that
        // exact snapshot instead of waiting for a second WorkManager network request.
        try {
            snapshot.services.forEach(widgetPrefsManager::cachePresentation)
            WidgetUpdater.updateAll(appContext)
        } catch (error: Exception) {
            // Widget rendering must not turn a successful dashboard refresh into an app error.
            Log.e(TAG, "Widget render failed after dashboard refresh", error)
        }
    }

    companion object {
        private const val TAG = "CodexBarDashboard"
    }
}
