package com.codexbar.android.core.security

import android.content.Context
import androidx.work.WorkerParameters
import com.codexbar.android.core.domain.model.AccountConnection
import com.codexbar.android.core.domain.model.AiService
import com.codexbar.android.core.domain.model.Credential
import com.codexbar.android.core.network.codex.CodexTokenRefreshService
import com.codexbar.android.core.workmanager.TokenRefreshWorker
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TokenWorkerPublicationTest {
    @Test
    fun `worker cannot restore retry state after deletion while publication waits`() = runTest {
        val gate = TokenRefreshCoordinator()
        val connection = AccountConnection.legacy(AiService.COPILOT)
        val credential = Credential.CopilotCredential("synthetic")
        val prefs = mock(EncryptedPrefsManager::class.java)
        `when`(prefs.loadConnections()).thenReturn(listOf(connection))
        `when`(prefs.loadCredential(connection)).thenReturn(credential)
        val retry = mock(TokenRefreshStateStore::class.java)
        `when`(retry.fingerprintFor(connection.service, credential)).thenReturn("synthetic-fingerprint")
        val health = mock(ConnectionHealthStore::class.java)
        val release = CompletableDeferred<Unit>()
        val deletion = launch {
            gate.mutate {
                release.await()
                prefs.deleteCredential(connection)
                retry.reset(connection)
                health.clear(connection)
            }
        }
        runCurrent()
        try {
        val worker = TokenRefreshWorker(
            mock(Context::class.java), mock(WorkerParameters::class.java, RETURNS_DEEP_STUBS),
            mock(CodexTokenRefreshService::class.java), prefs, health, gate, retry
        )
        val refresh = async { worker.doWork() }
        runCurrent()
        assertTrue(!refresh.isCompleted)
        release.complete(Unit)
        deletion.join()
        refresh.await()
        verify(retry).reset(connection)
        assertTrue(mockingDetails(retry).invocations.none { it.method.name == "save" })
        assertTrue(mockingDetails(health).invocations.none { it.method.name == "update" })
        } finally {
            release.complete(Unit)
        }
    }
}
