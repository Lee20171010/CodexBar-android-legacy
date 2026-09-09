package com.codexbar.android.core.data

import com.codexbar.android.core.domain.model.AiService
import com.codexbar.android.core.domain.model.AccountConnection
import com.codexbar.android.core.security.loadCredential
import com.codexbar.android.core.domain.model.AppError
import com.codexbar.android.core.domain.model.Credential
import com.codexbar.android.core.domain.model.ProviderSecretKind
import com.codexbar.android.core.domain.model.QuotaInfo
import com.codexbar.android.core.domain.model.Result
import com.codexbar.android.core.domain.model.UsageWindow
import com.codexbar.android.core.domain.repository.QuotaRepository
import com.codexbar.android.core.network.RetryAfter
import com.codexbar.android.core.network.clinepass.ClinePassApiService
import com.codexbar.android.core.network.clinepass.ClinePassDto
import com.codexbar.android.core.security.EncryptedPrefsManager
import java.io.IOException
import java.time.Instant
import javax.inject.Inject

class ClinePassRepositoryImpl @Inject constructor(
    private val apiService: ClinePassApiService,
    private val prefsManager: EncryptedPrefsManager
) : QuotaRepository {

    override suspend fun fetchQuota(connection: AccountConnection?): Result<QuotaInfo, AppError> {
        val credential = prefsManager.loadCredential(AiService.CLINEPASS, connection)
            as? Credential.ProviderSecretCredential
            ?: return Result.Failure(AppError.CredentialNotFound(AiService.CLINEPASS))
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
            val response = apiService.getUsageLimits("Bearer $apiKey")
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

    private fun mapToQuotaInfo(body: ClinePassDto.UsageLimitsEnvelope): QuotaInfo {
        require(body.success) { "Cline response reported an unsuccessful request" }
        val limitsByType = body.data?.limits.orEmpty().associateBy { it.type }
        val windows = WINDOW_TYPES.mapNotNull { definition ->
            limitsByType[definition.type]?.toUsageWindow(definition)
        }
        require(windows.isNotEmpty()) { "Cline response did not contain supported usage limits" }
        return QuotaInfo(
            service = AiService.CLINEPASS,
            windows = windows,
            extraUsage = null,
            fetchedAt = Instant.now()
        )
    }

    private fun ClinePassDto.UsageLimit.toUsageWindow(definition: WindowDefinition): UsageWindow {
        require(percentUsed.isFinite()) { "Cline usage percentage is invalid" }
        return UsageWindow(
            label = definition.label,
            utilization = (percentUsed / 100.0).coerceIn(0.0, 1.0),
            resetsAt = resetsAt?.let { value ->
                runCatching { Instant.parse(value) }.getOrNull()
            },
            windowDurationSeconds = definition.durationSeconds
        )
    }

    private fun Credential.ProviderSecretCredential.apiKeyOrNull(): String? {
        if (service != AiService.CLINEPASS || kind != ProviderSecretKind.API_KEY) return null
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
        AppError.AuthError(AiService.CLINEPASS, isTerminal = true)
    )

    private data class WindowDefinition(
        val type: String,
        val label: String,
        val durationSeconds: Long
    )

    private companion object {
        const val MAX_API_KEY_LENGTH = 4_096
        val WINDOW_TYPES = listOf(
            WindowDefinition("five_hour", "5-Hour", 5L * 60L * 60L),
            WindowDefinition("weekly", "7-Day", 7L * 24L * 60L * 60L),
            WindowDefinition("monthly", "Monthly", 30L * 24L * 60L * 60L)
        )
    }
}
