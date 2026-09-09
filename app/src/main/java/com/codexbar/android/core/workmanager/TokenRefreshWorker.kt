package com.codexbar.android.core.workmanager

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.codexbar.android.core.auth.codexAccountId
import com.codexbar.android.core.auth.codexTokenExpiresAt
import com.codexbar.android.core.domain.model.AiService
import com.codexbar.android.core.domain.model.AccountConnection
import com.codexbar.android.core.domain.model.Credential
import com.codexbar.android.core.network.codex.CodexDto
import com.codexbar.android.core.network.codex.CodexTokenRefreshService
import com.codexbar.android.core.network.RetryAfter
import com.codexbar.android.core.security.EncryptedPrefsManager
import com.codexbar.android.core.security.ConnectionHealth
import com.codexbar.android.core.security.ConnectionHealthStore
import com.codexbar.android.core.security.TokenRefreshAttemptDecision
import com.codexbar.android.core.security.TokenRefreshCoordinator
import com.codexbar.android.core.security.TokenRefreshRetryPolicy
import com.codexbar.android.core.security.TokenRefreshStateStore
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.time.Instant

@HiltWorker
class TokenRefreshWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val codexTokenRefreshService: CodexTokenRefreshService,
    private val prefsManager: EncryptedPrefsManager,
    private val connectionHealthStore: ConnectionHealthStore,
    private val tokenRefreshCoordinator: TokenRefreshCoordinator,
    private val tokenRefreshStateStore: TokenRefreshStateStore
) : CoroutineWorker(context, workerParams) {

    private val retryPolicy = TokenRefreshRetryPolicy()

    override suspend fun doWork(): Result {
        val revision = tokenRefreshCoordinator.revision.value
        val results = coroutineScope {
            prefsManager.loadConnections()
                .mapNotNull { connection ->
                    prefsManager.loadCredential(connection)?.let { credential -> connection to credential }
                }
                .map { (connection, credential) -> async { refreshIfDue(connection, credential, revision) } }
                .awaitAll()
        }

        return if (results.any { it.shouldRetryWork }) Result.retry() else Result.success()
    }

    private suspend fun refreshIfDue(connection: AccountConnection, credential: Credential, revision: Long): RefreshRunResult {
        val nowMillis = System.currentTimeMillis()
        val credentialFingerprint = tokenRefreshStateStore.fingerprintFor(connection.service, credential)
        val previousState = tokenRefreshStateStore.load(connection)
        return when (retryPolicy.decision(previousState, credentialFingerprint, nowMillis)) {
            TokenRefreshAttemptDecision.SkipTerminal,
            TokenRefreshAttemptDecision.SkipUntilDue -> RefreshRunResult.Skipped

            TokenRefreshAttemptDecision.Attempt -> {
                val outcome = refreshIfNeeded(connection, credential)
                var runResult = RefreshRunResult.Skipped
                tokenRefreshCoordinator.publish(revision) {
                val savedCredential = prefsManager.loadCredential(connection)
                    ?: return@publish
                val expectedCredential = (outcome as? RefreshOutcome.Success)?.credential ?: credential
                if (savedCredential != expectedCredential) return@publish
                runResult = when (outcome) {
                    is RefreshOutcome.Success,
                    is RefreshOutcome.NotNeeded -> {
                        val currentCredential = when (outcome) {
                            is RefreshOutcome.Success -> outcome.credential
                            is RefreshOutcome.NotNeeded -> {
                                savedCredential
                            }
                            is RefreshOutcome.Failure -> error("unreachable")
                        }
                        val currentFingerprint = tokenRefreshStateStore.fingerprintFor(
                            connection.service,
                            currentCredential
                        )
                        tokenRefreshStateStore.save(
                            connection,
                            retryPolicy.success(
                                credentialFingerprint = currentFingerprint,
                                nextAttemptAtMillis = nextRefreshDueMillis(
                                    currentCredential,
                                    nowMillis
                                )
                            )
                        )
                        if (outcome is RefreshOutcome.Success) {
                            connectionHealthStore.update(connection, ConnectionHealth.CONNECTED)
                        }
                        RefreshRunResult.Succeeded
                    }

                    is RefreshOutcome.Failure -> {
                        tokenRefreshStateStore.save(
                            connection,
                            retryPolicy.failure(
                                previousState = previousState,
                                credentialFingerprint = credentialFingerprint,
                                nowMillis = nowMillis,
                                terminal = outcome.terminal,
                                retryAtMillis = outcome.retryAtMillis
                            )
                        )
                        connectionHealthStore.update(
                            connection,
                            if (outcome.terminal) {
                                ConnectionHealth.NEEDS_REAUTHENTICATION
                            } else {
                                ConnectionHealth.OFFLINE
                            }
                        )
                        RefreshRunResult(shouldRetryWork = !outcome.terminal)
                    }
                }
                }
                runResult
            }
        }
    }

    private suspend fun refreshIfNeeded(connection: AccountConnection, credential: Credential): RefreshOutcome {
        return when (credential) {
            is Credential.AntigravityCompanionCredential -> RefreshOutcome.NotNeeded
            is Credential.ClaudeCompanionCredential -> RefreshOutcome.NotNeeded
            is Credential.CodexCredential -> refreshCodex(connection, credential)
            is Credential.GeminiCompanionCredential -> RefreshOutcome.NotNeeded
            is Credential.CopilotCredential -> RefreshOutcome.NotNeeded
            is Credential.ProviderSecretCredential -> RefreshOutcome.NotNeeded
        }
    }

    private suspend fun refreshCodex(connection: AccountConnection, credential: Credential.CodexCredential): RefreshOutcome {
        val credentialExpiry = credential.expiresAt ?: codexTokenExpiresAt(credential.accessToken)
        if (credentialExpiry == null || Instant.now().isBefore(
                credentialExpiry.minusSeconds(REFRESH_BUFFER_SECONDS)
            )
        ) {
            return RefreshOutcome.NotNeeded
        }

        return tokenRefreshCoordinator.withRefreshLock(connection) {
            val activeCredential = prefsManager.loadCredential(connection)
                as? Credential.CodexCredential
                ?: return@withRefreshLock RefreshOutcome.NotNeeded

            if (!activeCredential.matchesRefreshSubject(credential)) {
                return@withRefreshLock RefreshOutcome.NotNeeded
            }

            val activeExpiry = activeCredential.expiresAt
                ?: codexTokenExpiresAt(activeCredential.accessToken)
            if (activeExpiry == null || Instant.now().isBefore(
                    activeExpiry.minusSeconds(REFRESH_BUFFER_SECONDS)
                )
            ) {
                return@withRefreshLock RefreshOutcome.NotNeeded
            }

            try {
                val request = CodexDto.TokenRefreshRequest(
                    clientId = CodexDto.CODEX_CLIENT_ID,
                    grantType = "refresh_token",
                    refreshToken = activeCredential.refreshToken
                )
                val response = codexTokenRefreshService.refreshToken(request)
                if (response.isSuccessful) {
                    val body = response.body() ?: return@withRefreshLock RefreshOutcome.Failure()
                    if (body.accessToken.isBlank()) {
                        return@withRefreshLock RefreshOutcome.Failure()
                    }
                    val newCredential = Credential.CodexCredential(
                        accessToken = body.accessToken,
                        refreshToken = body.refreshToken ?: activeCredential.refreshToken,
                        accountId = activeCredential.accountId
                            ?: codexAccountId(idToken = null, accessToken = body.accessToken),
                        expiresAt = body.expiresIn
                            ?.takeIf { it > 0 }
                            ?.let { Instant.now().plusSeconds(it.toLong()) }
                            ?: codexTokenExpiresAt(body.accessToken)
                    )
                    if (prefsManager.replaceCredential(connection, activeCredential, newCredential)) {
                        RefreshOutcome.Success(newCredential)
                    } else {
                        RefreshOutcome.NotNeeded
                    }
                } else {
                    val errorBody = response.errorBody()?.string() ?: ""
                    RefreshOutcome.Failure(
                        terminal = CodexDto.isTerminalRefreshFailure(response.code(), errorBody),
                        retryAtMillis = if (response.code() == 429) {
                            RetryAfter.parseRetryAt(response.headers()["Retry-After"])?.toEpochMilli()
                        } else {
                            null
                        }
                    )
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Exception) {
                RefreshOutcome.Failure()
            }
        }
    }

    private fun Credential.CodexCredential.matchesRefreshSubject(
        other: Credential.CodexCredential
    ): Boolean {
        return refreshToken == other.refreshToken
    }

    private fun nextRefreshDueMillis(credential: Credential, nowMillis: Long): Long {
        val minimumDue = nowMillis + MIN_REFRESH_GAP_MILLIS
        return when (credential) {
            is Credential.AntigravityCompanionCredential -> Long.MAX_VALUE
            is Credential.ClaudeCompanionCredential -> Long.MAX_VALUE

            is Credential.CodexCredential -> {
                val fallback = nowMillis + DEFAULT_PROACTIVE_REFRESH_MILLIS
                (credential.expiresAt ?: codexTokenExpiresAt(credential.accessToken))
                    ?.minusSeconds(REFRESH_BUFFER_SECONDS)
                    ?.toEpochMilli()
                    ?.coerceAtLeast(minimumDue)
                    ?: fallback
            }

            is Credential.GeminiCompanionCredential -> Long.MAX_VALUE

            is Credential.CopilotCredential -> Long.MAX_VALUE

            is Credential.ProviderSecretCredential -> Long.MAX_VALUE
        }
    }

    private data class RefreshRunResult(
        val shouldRetryWork: Boolean
    ) {
        companion object {
            val Skipped = RefreshRunResult(shouldRetryWork = false)
            val Succeeded = RefreshRunResult(shouldRetryWork = false)
        }
    }

    private sealed class RefreshOutcome {
        data class Success(val credential: Credential) : RefreshOutcome()
        data object NotNeeded : RefreshOutcome()
        data class Failure(
            val terminal: Boolean = false,
            val retryAtMillis: Long? = null
        ) : RefreshOutcome()
    }

    companion object {
        /** Refresh buffer: refresh tokens that expire within this many seconds. */
        const val REFRESH_BUFFER_SECONDS = 600L // 10 minutes
        private const val MIN_REFRESH_GAP_MILLIS = 15 * 60 * 1000L
        private const val DEFAULT_PROACTIVE_REFRESH_MILLIS = 6 * 60 * 60 * 1000L
    }
}
