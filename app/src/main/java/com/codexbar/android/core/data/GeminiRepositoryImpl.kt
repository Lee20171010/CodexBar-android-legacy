package com.codexbar.android.core.data

import com.codexbar.android.core.domain.model.AiService
import com.codexbar.android.core.domain.model.AccountConnection
import com.codexbar.android.core.security.loadCredential
import com.codexbar.android.core.domain.model.AppError
import com.codexbar.android.core.domain.model.Credential
import com.codexbar.android.core.domain.model.QuotaInfo
import com.codexbar.android.core.domain.model.Result
import com.codexbar.android.core.domain.model.UsageWindow
import com.codexbar.android.core.domain.repository.QuotaRepository
import com.codexbar.android.core.network.gemini.GeminiCompanionAuthenticationException
import com.codexbar.android.core.network.gemini.GeminiCompanionClient
import com.codexbar.android.core.network.gemini.GeminiCompanionProtocolException
import com.codexbar.android.core.network.gemini.GeminiCompanionSnapshot
import com.codexbar.android.core.security.EncryptedPrefsManager
import java.io.IOException
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.CancellationException

class GeminiRepositoryImpl @Inject constructor(
    private val companionClient: GeminiCompanionClient,
    private val prefsManager: EncryptedPrefsManager
) : QuotaRepository {

    override suspend fun fetchQuota(connection: AccountConnection?): Result<QuotaInfo, AppError> {
        val credential = prefsManager.loadCredential(AiService.GEMINI, connection)
            as? Credential.GeminiCompanionCredential
            ?: return Result.Failure(AppError.CredentialNotFound(AiService.GEMINI))
        return fetchWithCredential(credential)
    }

    override suspend fun validateCredential(): Result<Unit, AppError> {
        return when (val result = fetchQuota()) {
            is Result.Success -> Result.Success(Unit)
            is Result.Failure -> Result.Failure(result.error)
        }
    }

    override suspend fun validateCredential(credential: Credential): Result<Unit, AppError> {
        val companionCredential = credential as? Credential.GeminiCompanionCredential
            ?: return Result.Failure(
                AppError.AuthError(AiService.GEMINI, isTerminal = true)
            )
        return when (val result = fetchWithCredential(companionCredential)) {
            is Result.Success -> Result.Success(Unit)
            is Result.Failure -> Result.Failure(result.error)
        }
    }

    private suspend fun fetchWithCredential(
        credential: Credential.GeminiCompanionCredential
    ): Result<QuotaInfo, AppError> {
        return try {
            Result.Success(companionClient.fetchSnapshot(credential).toQuotaInfo())
        } catch (error: CancellationException) {
            throw error
        } catch (error: GeminiCompanionAuthenticationException) {
            Result.Failure(
                AppError.AuthError(
                    service = AiService.GEMINI,
                    isTerminal = true,
                    message = error.message.orEmpty()
                )
            )
        } catch (error: GeminiCompanionProtocolException) {
            Result.Failure(AppError.ParseError(error.message.orEmpty(), error))
        } catch (error: IllegalArgumentException) {
            Result.Failure(AppError.ParseError(error.message.orEmpty(), error))
        } catch (error: IOException) {
            Result.Failure(
                AppError.NetworkError(
                    message = error.message ?: "Gemini companion unavailable",
                    cause = error
                )
            )
        } catch (error: Exception) {
            Result.Failure(
                AppError.NetworkError(
                    message = error.message ?: "Gemini companion unavailable",
                    cause = error
                )
            )
        }
    }

    private fun GeminiCompanionSnapshot.toQuotaInfo(): QuotaInfo {
        return QuotaInfo(
            service = AiService.GEMINI,
            windows = windows.map { window ->
                UsageWindow(
                    label = window.label,
                    utilization = window.usedFraction,
                    resetsAt = window.resetsAtEpochSeconds?.let(Instant::ofEpochSecond)
                )
            },
            extraUsage = null,
            tier = tier,
            fetchedAt = Instant.ofEpochSecond(generatedAtEpochSeconds)
        )
    }
}
