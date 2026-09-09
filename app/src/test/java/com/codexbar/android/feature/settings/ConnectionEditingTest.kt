package com.codexbar.android.feature.settings

import android.content.Context
import androidx.work.WorkManager
import com.codexbar.android.core.auth.AccountLinkManager
import com.codexbar.android.core.data.QuotaHistoryStore
import com.codexbar.android.core.data.QuotaRepositoryRegistry
import com.codexbar.android.core.domain.model.*
import com.codexbar.android.core.domain.repository.QuotaRepository
import com.codexbar.android.core.monitoring.MonitoringSessionStore
import com.codexbar.android.core.network.codex.telemetry.CodexTelemetryClient
import com.codexbar.android.core.notification.QuotaNotificationService
import com.codexbar.android.core.security.*
import com.codexbar.android.core.widget.WidgetPrefsManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ConnectionEditingTest {
    @Test
    fun `account editing preserves IDs and rejects late validation and scans`() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val workManager = mockStatic(WorkManager::class.java)
        try {
            val first = AccountConnection("00000000-0000-0000-0000-000000000001", AiService.DEEPSEEK, "Work")
            val second = first.copy(id = "00000000-0000-0000-0000-000000000002", name = "Personal")
            val prefs = mock(EncryptedPrefsManager::class.java)
            `when`(prefs.loadConnections()).thenReturn(listOf(first, second))
            `when`(prefs.appThemeStyle).thenReturn(MutableStateFlow(AppThemeStyle.MATERIAL_3))
            `when`(prefs.minimalDisplayEnabled).thenReturn(MutableStateFlow(false))
            `when`(prefs.getPrivacySettings()).thenReturn(PrivacySettings())
            val health = mock(ConnectionHealthStore::class.java)
            `when`(health.health).thenReturn(MutableStateFlow(emptyMap()))
            `when`(health.current(first)).thenReturn(ConnectionHealth.UNKNOWN)
            `when`(health.current(second)).thenReturn(ConnectionHealth.UNKNOWN)
            var response = CompletableDeferred<Unit>()
            val repository = object : QuotaRepository {
                override suspend fun fetchQuota(connection: AccountConnection?): Result<QuotaInfo, AppError> = error("unused")
                override suspend fun validateCredential(): Result<Unit, AppError> = error("unused")
                override suspend fun validateCredential(credential: Credential): Result<Unit, AppError> {
                    withContext(NonCancellable) { response.await() }
                    return Result.Success(Unit)
                }
            }
            val registry = mock(QuotaRepositoryRegistry::class.java)
            `when`(registry.repositoryFor(AiService.DEEPSEEK)).thenReturn(repository)
            val context = mock(Context::class.java)
            `when`(context.getString(anyInt())).thenReturn("test")
            workManager.`when`<WorkManager> { WorkManager.getInstance(context) }
                .thenReturn(mock(WorkManager::class.java))
            val gate = TokenRefreshCoordinator()
            val retry = mock(TokenRefreshStateStore::class.java)
            val history = mock(QuotaHistoryStore::class.java)
            val widgets = mock(WidgetPrefsManager::class.java)
            val notifications = mock(QuotaNotificationService::class.java)
            val vm = SettingsViewModel(
                registry, mock(AccountLinkManager::class.java), mock(CodexTelemetryClient::class.java),
                prefs, health, gate, retry, history, widgets,
                mock(MonitoringSessionStore::class.java), notifications, context
            )
            val releaseSelection = CompletableDeferred<Unit>()
            val selectionLock = launch(UnconfinedTestDispatcher(testScheduler)) {
                gate.withPublicationLock { releaseSelection.await() }
            }
            vm.selectConnection(AiService.DEEPSEEK, null)
            vm.updateField(AiService.DEEPSEEK, "accessToken", "must-not-save")
            vm.validateCredential(AiService.DEEPSEEK)
            assertEquals("", vm.uiState.value.serviceStates[AiService.DEEPSEEK]?.accessToken)
            releaseSelection.complete(Unit)
            selectionLock.join()
            runCurrent()
            assertEquals(null, vm.uiState.value.serviceStates[AiService.DEEPSEEK]?.connection)
            vm.selectConnection(AiService.DEEPSEEK, first)
            vm.updateField(AiService.DEEPSEEK, "accessToken", "candidate")
            vm.validateCredential(AiService.DEEPSEEK)
            vm.selectConnection(AiService.DEEPSEEK, second)
            response.complete(Unit)
            runCurrent()
            assertEquals(second, vm.uiState.value.serviceStates[AiService.DEEPSEEK]?.connection)
            assertTrue(mockingDetails(prefs).invocations.none { it.method.name == "saveCredential" })

            vm.selectConnection(AiService.DEEPSEEK, null)
            vm.updateField(AiService.DEEPSEEK, "accessToken", "candidate")
            response = CompletableDeferred()
            vm.validateCredential(AiService.DEEPSEEK)
            vm.deleteAllCredentials()
            response.complete(Unit)
            runCurrent()
            assertEquals(emptyList<AccountConnection>(), vm.uiState.value.connections)
            assertTrue(mockingDetails(prefs).invocations.none { it.method.name == "createConnection" })

            val scan = vm.captureClaudePairingScan()
            vm.selectConnection(AiService.CLAUDE, null)
            scan("old-pairing")
            assertEquals("", vm.uiState.value.serviceStates[AiService.CLAUDE]?.claudePairingCode)

            val candidate = Credential.ProviderSecretCredential(AiService.DEEPSEEK, ProviderSecretKind.API_KEY, "candidate")
            response = CompletableDeferred(Unit)
            `when`(prefs.loadConnections()).thenReturn(listOf(first))
            `when`(prefs.createConnection(AiService.DEEPSEEK, first.name, candidate)).thenReturn(first)
            vm.selectConnection(AiService.DEEPSEEK, null)
            vm.updateField(AiService.DEEPSEEK, "connectionName", first.name)
            vm.updateField(AiService.DEEPSEEK, "accessToken", candidate.accessToken)
            vm.validateCredential(AiService.DEEPSEEK)
            assertEquals(first, vm.uiState.value.serviceStates[AiService.DEEPSEEK]?.connection)
            assertEquals(ValidationResult.Success, vm.uiState.value.serviceStates[AiService.DEEPSEEK]?.validationResult)

            `when`(prefs.loadConnections()).thenReturn(listOf(first, second))
            `when`(prefs.createConnection(AiService.DEEPSEEK, second.name, candidate)).thenReturn(second)
            vm.selectConnection(AiService.DEEPSEEK, null)
            vm.updateField(AiService.DEEPSEEK, "connectionName", second.name)
            vm.updateField(AiService.DEEPSEEK, "accessToken", candidate.accessToken)
            vm.validateCredential(AiService.DEEPSEEK)
            assertEquals(listOf(first, second), vm.uiState.value.connections)
            assertEquals(second, vm.uiState.value.serviceStates[AiService.DEEPSEEK]?.connection)

            vm.selectConnection(AiService.DEEPSEEK, first)
            vm.updateField(AiService.DEEPSEEK, "accessToken", candidate.accessToken)
            vm.validateCredential(AiService.DEEPSEEK)
            verify(prefs).saveCredential(first, candidate)
            assertEquals(first, vm.uiState.value.serviceStates[AiService.DEEPSEEK]?.connection)

            val renamed = first.copy(name = "Renamed")
            `when`(health.current(renamed)).thenReturn(ConnectionHealth.UNKNOWN)
            `when`(prefs.loadConnections()).thenReturn(listOf(renamed, second))
            vm.updateField(AiService.DEEPSEEK, "connectionName", renamed.name)
            vm.renameConnection(AiService.DEEPSEEK)
            verify(prefs).renameConnection(first, renamed.name)
            assertEquals(renamed, vm.uiState.value.serviceStates[AiService.DEEPSEEK]?.connection)

            `when`(prefs.loadConnections()).thenReturn(listOf(second))
            val releaseDeletion = CompletableDeferred<Unit>()
            val deletionLock = launch(UnconfinedTestDispatcher(testScheduler)) {
                gate.withPublicationLock { releaseDeletion.await() }
            }
            clearInvocations(history, widgets, retry, notifications)
            vm.disconnectConnection(renamed)
            vm.selectConnection(AiService.DEEPSEEK, second)
            releaseDeletion.complete(Unit)
            deletionLock.join()
            runCurrent()
            verify(prefs).deleteCredential(renamed)
            verify(history).deleteConnection(renamed)
            verify(widgets).deleteConnectionCache(renamed)
            verify(retry).reset(renamed)
            verify(notifications).cancelResetNotifications(renamed)
            assertEquals(listOf(second), vm.uiState.value.connections)
            assertEquals(second, vm.uiState.value.serviceStates[AiService.DEEPSEEK]?.connection)
            var pendingRead: Continuation<Credential?>? = null
            doAnswer { invocation ->
                @Suppress("UNCHECKED_CAST")
                val continuation = invocation.rawArguments.last() as Continuation<Credential?>
                pendingRead = continuation
                COROUTINE_SUSPENDED
            }.`when`(prefs).loadCredential(second)
            vm.disconnectConnection(renamed)
            assertTrue(pendingRead != null)
            vm.selectConnection(AiService.DEEPSEEK, null)
            val states = mutableListOf<ServiceCredentialState>()
            val observation = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                vm.uiState.collect { states.add(it.serviceStates.getValue(AiService.DEEPSEEK)) }
            }
            pendingRead!!.resume(null)
            runCurrent()
            assertTrue("old fallback must not publish into newer Add editor", states.none { it.connection == second })
            assertTrue(states.first().isLoading)
            assertEquals(null, states.last().connection)
            assertEquals(false, states.last().isLoading)
            observation.cancel()
            androidx.lifecycle.ViewModelStore().apply { put("settings", vm) }.clear()
        } finally {
            workManager.close()
            Dispatchers.resetMain()
        }
    }
}
