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
import com.codexbar.android.core.network.zai.ZaiApiService
import com.codexbar.android.core.network.zai.ZaiDto
import com.codexbar.android.core.security.EncryptedPrefsManager
import java.io.IOException
import java.time.Instant
import javax.inject.Inject

class ZaiRepositoryImpl @Inject constructor(
    private val apiService: ZaiApiService,
    private val prefsManager: EncryptedPrefsManager
) : QuotaRepository {

    override suspend fun fetchQuota(connection: AccountConnection?): Result<QuotaInfo, AppError> {
        val credential = prefsManager.loadCredential(AiService.ZAI, connection)
            as? Credential.ProviderSecretCredential
            ?: return Result.Failure(AppError.CredentialNotFound(AiService.ZAI))
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
            val response = apiService.getQuotaLimits("Bearer $apiKey")
            when (response.code()) {
                200 -> mapSuccessfulResponse(response.body())
                401, 403 -> terminalAuthError()
                429 -> Result.Failure(
                    AppError.RateLimited(RetryAfter.parseRetryAt(response.headers()["Retry-After"]))
                )
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

    private fun mapSuccessfulResponse(
        envelope: ZaiDto.QuotaEnvelope?
    ): Result<QuotaInfo, AppError> {
        if (envelope == null) return Result.Failure(AppError.ParseError("Empty response body"))
        if (!envelope.success || envelope.code != HTTP_OK) {
            if (envelope.code == 401 || envelope.code == 403) return terminalAuthError()
            val message = envelope.msg?.trim()?.takeIf(String::isNotEmpty)
                ?: "z.ai quota API returned code ${envelope.code}"
            return Result.Failure(AppError.NetworkError(message))
        }
        val data = envelope.data
            ?: return Result.Failure(AppError.ParseError("Missing z.ai quota data"))
        return Result.Success(mapToQuotaInfo(data))
    }

    private fun mapToQuotaInfo(data: ZaiDto.QuotaData): QuotaInfo {
        val tokenLimits = data.limits
            .filter { it.type == TOKENS_LIMIT }
            .sortedBy { it.durationSeconds() ?: Long.MAX_VALUE }
        val timeLimit = data.limits.lastOrNull { it.type == TIME_LIMIT }

        val primaryToken = tokenLimits.lastOrNull()
        val sessionToken = tokenLimits.takeIf { it.size >= 2 }?.firstOrNull()
        val windows = buildList {
            primaryToken?.let { limit ->
                add(limit.toUsageWindow(primaryTokenLabel(limit, tokenLimits.size)))
            }
            timeLimit?.let { limit -> add(limit.toUsageWindow("MCP")) }
            sessionToken?.let { limit ->
                add(limit.toUsageWindow(limit.durationLabel() ?: "Session"))
            }
        }
        require(windows.isNotEmpty()) { "z.ai response did not contain a supported quota" }

        return QuotaInfo(
            service = AiService.ZAI,
            windows = windows,
            extraUsage = null,
            tier = data.resolvedPlanName,
            fetchedAt = Instant.now()
        )
    }

    private fun ZaiDto.Limit.toUsageWindow(label: String): UsageWindow {
        return UsageWindow(
            label = label,
            utilization = utilization(),
            resetsAt = nextResetTime?.let(::instantFromEpochMillisOrNull),
            windowDurationSeconds = durationSeconds()
        )
    }

    private fun ZaiDto.Limit.utilization(): Double {
        val limit = usage
        if (limit != null && limit > 0) {
            val usedFromRemaining = remaining?.let { limit - it }
            val used = listOfNotNull(usedFromRemaining, currentValue)
                .maxOrNull()
                ?.coerceIn(0L, limit)
            if (used != null) return used.toDouble() / limit.toDouble()
        }
        require(percentage.isFinite()) { "Invalid z.ai usage percentage" }
        return (percentage / 100.0).coerceIn(0.0, 1.0)
    }

    private fun ZaiDto.Limit.durationSeconds(): Long? {
        if (number <= 0) return null
        val secondsPerUnit = when (unit) {
            UNIT_DAYS -> 24L * 60L * 60L
            UNIT_HOURS -> 60L * 60L
            UNIT_MINUTES -> 60L
            UNIT_WEEKS -> 7L * 24L * 60L * 60L
            else -> return null
        }
        return runCatching { Math.multiplyExact(number.toLong(), secondsPerUnit) }
            .getOrNull()
    }

    private fun ZaiDto.Limit.durationLabel(): String? {
        if (number <= 0) return null
        val unitLabel = when (unit) {
            UNIT_DAYS -> "Day"
            UNIT_HOURS -> "Hour"
            UNIT_MINUTES -> "Minute"
            UNIT_WEEKS -> "Week"
            else -> return null
        }
        return "$number-$unitLabel"
    }

    private fun primaryTokenLabel(limit: ZaiDto.Limit, tokenLimitCount: Int): String {
        val duration = limit.durationLabel()
        return if (tokenLimitCount >= 2 && duration != null) "Tokens · $duration" else "Tokens"
    }

    private fun instantFromEpochMillisOrNull(value: Long): Instant? {
        if (value <= 0L) return null
        return runCatching { Instant.ofEpochMilli(value) }.getOrNull()
    }

    private fun Credential.ProviderSecretCredential.apiKeyOrNull(): String? {
        if (service != AiService.ZAI || kind != ProviderSecretKind.API_KEY) return null
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
        AppError.AuthError(AiService.ZAI, isTerminal = true)
    )

    private companion object {
        const val HTTP_OK = 200
        const val TOKENS_LIMIT = "TOKENS_LIMIT"
        const val TIME_LIMIT = "TIME_LIMIT"
        const val UNIT_DAYS = 1
        const val UNIT_HOURS = 3
        const val UNIT_MINUTES = 5
        const val UNIT_WEEKS = 6
        const val MAX_API_KEY_LENGTH = 4_096
    }
}
