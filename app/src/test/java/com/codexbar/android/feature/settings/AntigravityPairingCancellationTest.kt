package com.codexbar.android.feature.settings

import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.work.WorkManager
import com.codexbar.android.core.auth.AccountLinkManager
import com.codexbar.android.core.data.QuotaHistoryStore
import com.codexbar.android.core.data.QuotaRepositoryRegistry
import com.codexbar.android.core.domain.model.*
import com.codexbar.android.core.domain.repository.QuotaRepository
import com.codexbar.android.core.monitoring.MonitoringSessionStore
import com.codexbar.android.core.network.antigravity.AntigravityCompanionPairing
import com.codexbar.android.core.network.codex.telemetry.CodexTelemetryClient
import com.codexbar.android.core.notification.QuotaNotificationService
import com.codexbar.android.core.security.*
import com.codexbar.android.core.widget.WidgetPrefsManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.assertFalse
import org.junit.Test
import org.mockito.Mockito.*

@OptIn(ExperimentalCoroutinesApi::class)
class AntigravityPairingCancellationTest {
    @Test fun `delete all does not resurrect a pairing when validation returns late`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        val color = mockStatic(android.graphics.Color::class.java)
        val workManager = mockStatic(WorkManager::class.java)
        color.`when`<Int> { android.graphics.Color.rgb(anyInt(), anyInt(), anyInt()) }
            .thenAnswer { call -> (0xFF shl 24) or (call.getArgument<Int>(0) shl 16) or
                (call.getArgument<Int>(1) shl 8) or call.getArgument<Int>(2) }
        try {
            val response = CompletableDeferred<Unit>()
            val repository = object : QuotaRepository {
                override suspend fun fetchQuota(connection: AccountConnection?): Result<QuotaInfo, AppError> = error("unused")
                override suspend fun validateCredential(): Result<Unit, AppError> = error("unused")
                override suspend fun validateCredential(credential: Credential): Result<Unit, AppError> {
                    withContext(NonCancellable) { response.await() }
                    return Result.Success(Unit)
                }
            }
            val prefs = mock(EncryptedPrefsManager::class.java)
            `when`(prefs.appThemeStyle).thenReturn(MutableStateFlow(AppThemeStyle.MATERIAL_3))
            `when`(prefs.minimalDisplayEnabled).thenReturn(MutableStateFlow(false))
            `when`(prefs.getPrivacySettings()).thenReturn(PrivacySettings())
            doReturn(emptyList<AccountConnection>()).`when`(prefs).loadConnections()
            val health = mock(ConnectionHealthStore::class.java)
            `when`(health.health).thenReturn(MutableStateFlow(emptyMap()))
            val context = mock(Context::class.java)
            `when`(context.getString(anyInt())).thenReturn("test")
            workManager.`when`<WorkManager> { WorkManager.getInstance(context) }
                .thenReturn(mock(WorkManager::class.java))
            val vm = SettingsViewModel(
                QuotaRepositoryRegistry(AiService.entries.associateWith { repository }),
                mock(AccountLinkManager::class.java), mock(CodexTelemetryClient::class.java),
                prefs, health, TokenRefreshCoordinator(),
                mock(TokenRefreshStateStore::class.java),
                mock(QuotaHistoryStore::class.java), mock(WidgetPrefsManager::class.java),
                mock(MonitoringSessionStore::class.java), mock(QuotaNotificationService::class.java),
                context
            )
            store.put("test", vm)
            runCurrent()
            val code = "CBANTIGRAVITY1|127.0.0.1|43824|5b017391-6dc4-4ab7-b0ad-2255dada62d7|AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
            vm.importAntigravityPairingCode(code)
            runCurrent()
            vm.deleteAllCredentials()
            runCurrent()
            response.complete(Unit)
            runCurrent()
            verify(prefs).deleteAllCredentials()
            verify(prefs, never()).saveCredential(AiService.ANTIGRAVITY, AntigravityCompanionPairing.parse(code))
            assertFalse(vm.uiState.value.serviceStates.getValue(AiService.ANTIGRAVITY).isConnected)
        } finally {
            store.clear()
            color.close()
            workManager.close()
            Dispatchers.resetMain()
        }
    }
}
