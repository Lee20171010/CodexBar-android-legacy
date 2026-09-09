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
import com.codexbar.android.core.network.kimi.KimiApiService
import com.codexbar.android.core.network.kimi.KimiDto
import com.codexbar.android.core.security.EncryptedPrefsManager
import java.io.IOException
import java.time.Instant
import java.util.Locale
import javax.inject.Inject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

class KimiRepositoryImpl @Inject constructor(
    private val apiService: KimiApiService,
    private val prefsManager: EncryptedPrefsManager
) : QuotaRepository {

    override suspend fun fetchQuota(connection: AccountConnection?): Result<QuotaInfo, AppError> {
        val credential = prefsManager.loadCredential(AiService.KIMI, connection)
            as? Credential.ProviderSecretCredential
            ?: return Result.Failure(AppError.CredentialNotFound(AiService.KIMI))
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

    private fun mapToQuotaInfo(body: KimiDto.UsageEnvelope): QuotaInfo {
        val rateWindow = body.limits.orEmpty()
            .mapNotNull { limit ->
                val durationSeconds = limit.window.durationSecondsOrNull() ?: return@mapNotNull null
                runCatching {
                    limit.detail.toUsageWindow(
                        label = durationLabel(durationSeconds),
                        durationSeconds = durationSeconds
                    )
                }.getOrNull()
            }
            .minByOrNull { it.windowDurationSeconds ?: Long.MAX_VALUE }

        val weekly = body.usage.toUsageWindow(
            label = "7-Day",
            durationSeconds = SEVEN_DAYS_SECONDS
        )
        return QuotaInfo(
            service = AiService.KIMI,
            windows = listOfNotNull(rateWindow, weekly),
            extraUsage = null,
            fetchedAt = Instant.now()
        )
    }

    private fun KimiDto.UsageDetail.toUsageWindow(
        label: String,
        durationSeconds: Long
    ): UsageWindow {
        val limitValue = limit.numberOrNull()
            ?.takeIf { it.isFinite() && it > 0.0 }
            ?: error("Kimi quota limit is invalid")
        val explicitUsed = used.numberOrNull()
        val usedFromRemaining = remaining.numberOrNull()?.let { limitValue - it }
        val usedValue = explicitUsed ?: usedFromRemaining
            ?: error("Kimi quota usage is missing")
        require(usedValue.isFinite()) { "Kimi quota usage is invalid" }

        return UsageWindow(
            label = label,
            utilization = (usedValue / limitValue).coerceIn(0.0, 1.0),
            resetsAt = resolvedResetTime?.let(::parseInstantOrNull),
            windowDurationSeconds = durationSeconds
        )
    }

    private fun KimiDto.Window.durationSecondsOrNull(): Long? {
        val durationValue = duration.numberOrNull()
            ?.takeIf { it.isFinite() && it > 0.0 && it % 1.0 == 0.0 }
            ?.toLong()
            ?: return null
        val secondsPerUnit = when (resolvedTimeUnit?.uppercase(Locale.ROOT)) {
            "TIME_UNIT_SECOND", "SECOND", "SECONDS" -> 1L
            "TIME_UNIT_MINUTE", "MINUTE", "MINUTES" -> 60L
            "TIME_UNIT_HOUR", "HOUR", "HOURS" -> 60L * 60L
            "TIME_UNIT_DAY", "DAY", "DAYS" -> 24L * 60L * 60L
            else -> return null
        }
        return runCatching { Math.multiplyExact(durationValue, secondsPerUnit) }.getOrNull()
    }

    private fun durationLabel(durationSeconds: Long): String = when (durationSeconds) {
        FIVE_HOURS_SECONDS -> "5-Hour"
        else -> when {
            durationSeconds % (24L * 60L * 60L) == 0L -> {
                "${durationSeconds / (24L * 60L * 60L)}-Day"
            }
            durationSeconds % (60L * 60L) == 0L -> "${durationSeconds / (60L * 60L)}-Hour"
            durationSeconds % 60L == 0L -> "${durationSeconds / 60L}-Minute"
            else -> "$durationSeconds-Second"
        }
    }

    private fun JsonPrimitive?.numberOrNull(): Double? {
        if (this == null || this is JsonNull) return null
        return content.toDoubleOrNull()
    }

    private fun parseInstantOrNull(value: String): Instant? =
        runCatching { Instant.parse(value) }.getOrNull()

    private fun Credential.ProviderSecretCredential.apiKeyOrNull(): String? {
        if (service != AiService.KIMI || kind != ProviderSecretKind.API_KEY) return null
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
        AppError.AuthError(AiService.KIMI, isTerminal = true)
    )

    private companion object {
        const val FIVE_HOURS_SECONDS = 5L * 60L * 60L
        const val SEVEN_DAYS_SECONDS = 7L * 24L * 60L * 60L
        const val MAX_API_KEY_LENGTH = 4_096
    }
}
