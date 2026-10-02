package com.codexbar.android.core.data

import com.codexbar.android.core.domain.model.AiService
import com.codexbar.android.core.domain.model.AppError
import com.codexbar.android.core.domain.model.Credential
import com.codexbar.android.core.domain.model.ProviderSecretKind
import com.codexbar.android.core.domain.model.QuotaInfo
import com.codexbar.android.core.domain.model.Result
import com.codexbar.android.core.domain.model.UsageWindow
import com.codexbar.android.core.domain.repository.QuotaRepository
import com.codexbar.android.core.network.RetryAfter
import com.codexbar.android.core.network.opencode.OpenCodeApiService
import com.codexbar.android.core.network.opencode.OpenCodeDto
import com.codexbar.android.core.security.EncryptedPrefsManager
import java.io.IOException
import java.time.Instant
import javax.inject.Inject

class OpenCodeRepositoryImpl @Inject constructor(
    private val apiService: OpenCodeApiService,
    private val prefsManager: EncryptedPrefsManager
) : QuotaRepository {

    override suspend fun fetchQuota(): Result<QuotaInfo, AppError> {
        val credential = prefsManager.loadCredential(AiService.OPENCODE_GO)
            as? Credential.ProviderSecretCredential
            ?: return Result.Failure(AppError.CredentialNotFound(AiService.OPENCODE_GO))
        return fetchQuota(credential)
    }

    override suspend fun validateCredential(): Result<Unit, AppError> = fetchQuota().asValidation()

    override suspend fun validateCredential(credential: Credential): Result<Unit, AppError> {
        val typed = credential as? Credential.ProviderSecretCredential
            ?: return terminalAuthError()
        return fetchQuota(typed).asValidation()
    }

    private suspend fun fetchQuota(
        credential: Credential.ProviderSecretCredential
    ): Result<QuotaInfo, AppError> {
        val apiKey = credential.apiKeyOrNull() ?: return terminalAuthError()
        return try {
            val response = apiService.getUsage("Bearer $apiKey")
            when (response.code()) {
                200 -> {
                    val body = response.body()
                        ?: return Result.Failure(AppError.ParseError("Empty response body"))
                    Result.Success(mapToQuotaInfo(body))
                }
                401, 403 -> terminalAuthError()
                429 -> Result.Failure(
                    AppError.RateLimited(RetryAfter.parseRetryAt(response.headers()["Retry-After"]))
                )
                in 500..599 -> Result.Failure(AppError.ServiceUnavailable)
                else -> Result.Failure(
                    AppError.NetworkError("HTTP ${response.code()}: ${response.message()}")
                )
            }
        } catch (error: IOException) {
            Result.Failure(AppError.NetworkError(error.message ?: "Network error", error))
        } catch (error: Exception) {
            Result.Failure(AppError.ParseError(error.message ?: "Parse error", error))
        }
    }

    private fun mapToQuotaInfo(payload: OpenCodeDto.UsageEnvelope): QuotaInfo {
        val usage = requireNotNull(payload.usage) { "OpenCode response is missing usage windows" }
        val windows = listOfNotNull(
            usage.rolling.toUsageWindow("5-Hour", FIVE_HOURS_SECONDS),
            usage.weekly.toUsageWindow("Weekly", WEEK_SECONDS),
            usage.monthly.toUsageWindow("Monthly", MONTH_SECONDS)
        )
        require(windows.isNotEmpty()) { "OpenCode response did not contain usage windows" }
        return QuotaInfo(
            service = AiService.OPENCODE_GO,
            windows = windows,
            extraUsage = null,
            fetchedAt = Instant.now()
        )
    }

    private fun OpenCodeDto.Window?.toUsageWindow(
        label: String,
        durationSeconds: Long
    ): UsageWindow? {
        val window = this ?: return null
        val percent = window.percent ?: return null
        require(percent.isFinite()) { "OpenCode usage percentage is invalid" }
        return UsageWindow(
            label = label,
            utilization = (percent / 100.0).coerceIn(0.0, 1.0),
            resetsAt = window.resetsAt?.let { value ->
                runCatching { Instant.parse(value) }.getOrNull()
            },
            windowDurationSeconds = durationSeconds
        )
    }

    private fun Credential.ProviderSecretCredential.apiKeyOrNull(): String? {
        if (service != AiService.OPENCODE_GO || kind != ProviderSecretKind.API_KEY) return null
        val normalized = accessToken.trim()
        return normalized.takeIf {
            it.isNotEmpty() &&
                it.length <= MAX_API_KEY_LENGTH &&
                it.none(Char::isWhitespace) &&
                it.none(Char::isISOControl)
        }
    }

    private fun Result<QuotaInfo, AppError>.asValidation(): Result<Unit, AppError> = when (this) {
        is Result.Success -> Result.Success(Unit)
        is Result.Failure -> Result.Failure(error)
    }

    private fun terminalAuthError(): Result.Failure<AppError> = Result.Failure(
        AppError.AuthError(AiService.OPENCODE_GO, isTerminal = true)
    )

    private companion object {
        const val MAX_API_KEY_LENGTH = 4_096
        const val FIVE_HOURS_SECONDS = 5L * 60L * 60L
        const val WEEK_SECONDS = 7L * 24L * 60L * 60L
        const val MONTH_SECONDS = 30L * 24L * 60L * 60L
    }
}
