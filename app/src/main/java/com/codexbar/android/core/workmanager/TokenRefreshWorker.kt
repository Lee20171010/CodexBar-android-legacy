package com.codexbar.android.core.workmanager

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.codexbar.android.core.auth.codexAccountId
import com.codexbar.android.core.auth.codexTokenExpiresAt
import com.codexbar.android.core.domain.model.AiService
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
        val results = coroutineScope {
            AiService.entries
                .mapNotNull { service ->
                    prefsManager.loadCredential(service)?.let { credential -> service to credential }
                }
                .map { (service, credential) -> async { refreshIfDue(service, credential) } }
                .awaitAll()
        }

        return if (results.any { it.shouldRetryWork }) Result.retry() else Result.success()
    }

    private suspend fun refreshIfDue(service: AiService, credential: Credential): RefreshRunResult {
        val nowMillis = System.currentTimeMillis()
        val credentialFingerprint = tokenRefreshStateStore.fingerprintFor(service, credential)
        val previousState = tokenRefreshStateStore.load(service)
        return when (retryPolicy.decision(previousState, credentialFingerprint, nowMillis)) {
            TokenRefreshAttemptDecision.SkipTerminal,
            TokenRefreshAttemptDecision.SkipUntilDue -> RefreshRunResult.Skipped

            TokenRefreshAttemptDecision.Attempt -> {
                val outcome = refreshIfNeeded(credential)
                when (outcome) {
                    is RefreshOutcome.Success,
                    is RefreshOutcome.NotNeeded -> {
                        val currentCredential = when (outcome) {
                            is RefreshOutcome.Success -> outcome.credential
                            is RefreshOutcome.NotNeeded -> {
                                prefsManager.loadCredential(service) ?: credential
                            }
                            is RefreshOutcome.Failure -> error("unreachable")
                        }
                        val currentFingerprint = tokenRefreshStateStore.fingerprintFor(
                            service,
                            currentCredential
                        )
                        tokenRefreshStateStore.save(
                            service,
                            retryPolicy.success(
                                credentialFingerprint = currentFingerprint,
                                nextAttemptAtMillis = nextRefreshDueMillis(
                                    currentCredential,
                                    nowMillis
                                )
                            )
                        )
                        if (outcome is RefreshOutcome.Success) {
                            connectionHealthStore.update(service, ConnectionHealth.CONNECTED)
                        }
                        RefreshRunResult.Succeeded
                    }

                    is RefreshOutcome.Failure -> {
                        tokenRefreshStateStore.save(
                            service,
                            retryPolicy.failure(
                                previousState = previousState,
                                credentialFingerprint = credentialFingerprint,
                                nowMillis = nowMillis,
                                terminal = outcome.terminal,
                                retryAtMillis = outcome.retryAtMillis
                            )
                        )
                        connectionHealthStore.update(
                            service,
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
        }
    }

    private suspend fun refreshIfNeeded(credential: Credential): RefreshOutcome {
        return when (credential) {
            is Credential.AntigravityCompanionCredential -> RefreshOutcome.NotNeeded
            is Credential.ClaudeCompanionCredential -> RefreshOutcome.NotNeeded
            is Credential.CodexCredential -> refreshCodex(credential)
            is Credential.GeminiCompanionCredential -> RefreshOutcome.NotNeeded
            is Credential.CopilotCredential -> RefreshOutcome.NotNeeded
            is Credential.ProviderSecretCredential -> RefreshOutcome.NotNeeded
        }
    }

    private suspend fun refreshCodex(credential: Credential.CodexCredential): RefreshOutcome {
        val credentialExpiry = credential.expiresAt ?: codexTokenExpiresAt(credential.accessToken)
        if (credentialExpiry == null || Instant.now().isBefore(
                credentialExpiry.minusSeconds(REFRESH_BUFFER_SECONDS)
            )
        ) {
            return RefreshOutcome.NotNeeded
        }

        return tokenRefreshCoordinator.withRefreshLock(AiService.CODEX) {
            val activeCredential = prefsManager.loadCredential(AiService.CODEX)
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
                    val currentCredential = prefsManager.loadCredential(AiService.CODEX)
                        as? Credential.CodexCredential
                    if (currentCredential?.matchesRefreshSubject(activeCredential) == true) {
                        prefsManager.saveCredential(AiService.CODEX, newCredential)
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
