package com.codexbar.android.core.data

import com.codexbar.android.core.domain.model.AiService
import com.codexbar.android.core.domain.model.AppError
import com.codexbar.android.core.domain.model.Credential
import com.codexbar.android.core.domain.model.QuotaInfo
import com.codexbar.android.core.domain.model.Result
import com.codexbar.android.core.domain.model.UsageWindow
import com.codexbar.android.core.domain.repository.QuotaRepository
import com.codexbar.android.core.network.antigravity.AntigravityCompanionAuthenticationException
import com.codexbar.android.core.network.antigravity.AntigravityCompanionClient
import com.codexbar.android.core.network.antigravity.AntigravityCompanionProtocolException
import com.codexbar.android.core.network.antigravity.AntigravityCompanionSnapshot
import com.codexbar.android.core.network.companion.LocalCompanionLocator
import com.codexbar.android.core.security.EncryptedPrefsManager
import java.io.IOException
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.CancellationException

class AntigravityRepositoryImpl @Inject constructor(
    private val companionClient: AntigravityCompanionClient,
    private val prefsManager: EncryptedPrefsManager,
    private val companionLocator: LocalCompanionLocator,
    private val nowMillis: () -> Long = System::currentTimeMillis
) : QuotaRepository {

    @Volatile
    private var nextRelocationAtMillis = 0L

    override suspend fun fetchQuota(): Result<QuotaInfo, AppError> {
        val credential = prefsManager.loadCredential(AiService.ANTIGRAVITY)
            as? Credential.AntigravityCompanionCredential
            ?: return Result.Failure(AppError.CredentialNotFound(AiService.ANTIGRAVITY))
        val direct = fetchCompanionQuota(credential)
        if (direct is Result.Success || !direct.isCompanionUnreachable()) return direct
        return relocateCompanion(credential) ?: direct
    }

    override suspend fun validateCredential(): Result<Unit, AppError> {
        return fetchQuota().toUnitResult()
    }

    override suspend fun validateCredential(credential: Credential): Result<Unit, AppError> {
        val companion = credential as? Credential.AntigravityCompanionCredential
            ?: return Result.Failure(
                AppError.AuthError(AiService.ANTIGRAVITY, isTerminal = true)
            )
        // An address the user just paired is current by definition, so it is never relocated.
        return fetchCompanionQuota(companion).toUnitResult()
    }

    /**
     * Finds the companion again after its computer received a new DHCP address.
     *
     * The stored pairing key still has to authenticate the snapshot, so a candidate that merely
     * listens on the same port cannot take over the connection. Only a host that answers with a
     * valid encrypted envelope is persisted.
     */
    private suspend fun relocateCompanion(
        credential: Credential.AntigravityCompanionCredential
    ): Result<QuotaInfo, AppError>? {
        val startedAt = nowMillis()
        if (startedAt < nextRelocationAtMillis) return null
        nextRelocationAtMillis = startedAt + RELOCATION_COOLDOWN_MILLIS

        val hosts = runCatching {
            companionLocator.reachableHosts(
                port = credential.port,
                previousHost = credential.host
            )
        }.getOrDefault(emptyList())

        for (host in hosts) {
            if (host == credential.host) continue
            val candidate = credential.copy(host = host)
            val result = fetchCompanionQuota(candidate)
            if (result is Result.Success) {
                if (!prefsManager.updateAntigravityCompanionHostIfCurrent(credential, host)) {
                    return null
                }
                nextRelocationAtMillis = 0L
                return result
            }
        }
        return null
    }

    private fun Result<QuotaInfo, AppError>.isCompanionUnreachable(): Boolean {
        return this is Result.Failure && error is AppError.NetworkError
    }

    private suspend fun fetchCompanionQuota(
        credential: Credential.AntigravityCompanionCredential
    ): Result<QuotaInfo, AppError> {
        return try {
            Result.Success(companionClient.fetchSnapshot(credential).toQuotaInfo())
        } catch (error: CancellationException) {
            throw error
        } catch (error: AntigravityCompanionAuthenticationException) {
            Result.Failure(
                AppError.AuthError(
                    service = AiService.ANTIGRAVITY,
                    isTerminal = true,
                    message = error.message.orEmpty()
                )
            )
        } catch (error: AntigravityCompanionProtocolException) {
            Result.Failure(AppError.ParseError(error.message.orEmpty(), error))
        } catch (error: IllegalArgumentException) {
            Result.Failure(AppError.ParseError(error.message.orEmpty(), error))
        } catch (error: IOException) {
            Result.Failure(
                AppError.NetworkError(
                    message = error.message ?: "Antigravity companion unavailable",
                    cause = error
                )
            )
        } catch (error: Exception) {
            Result.Failure(
                AppError.NetworkError(
                    message = error.message ?: "Antigravity companion unavailable",
                    cause = error
                )
            )
        }
    }

    private fun AntigravityCompanionSnapshot.toQuotaInfo(): QuotaInfo {
        return QuotaInfo(
            service = AiService.ANTIGRAVITY,
            windows = windows.map { window ->
                UsageWindow(
                    label = window.label,
                    utilization = window.usedFraction,
                    resetsAt = window.resetsAtEpochSeconds?.let(Instant::ofEpochSecond),
                    windowDurationSeconds = null
                )
            },
            extraUsage = null,
            tier = tier,
            fetchedAt = Instant.ofEpochSecond(generatedAtEpochSeconds)
        )
    }

    private fun Result<QuotaInfo, AppError>.toUnitResult(): Result<Unit, AppError> {
        return when (this) {
            is Result.Success -> Result.Success(Unit)
            is Result.Failure -> Result.Failure(error)
        }
    }

    private companion object {
        /** Keeps a companion that is simply switched off from causing a scan on every refresh. */
        const val RELOCATION_COOLDOWN_MILLIS = 10L * 60L * 1000L
    }
}
