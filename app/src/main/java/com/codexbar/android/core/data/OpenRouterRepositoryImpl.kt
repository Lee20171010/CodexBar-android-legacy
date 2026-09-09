package com.codexbar.android.core.data

import com.codexbar.android.core.domain.model.AiService
import com.codexbar.android.core.domain.model.AccountConnection
import com.codexbar.android.core.security.loadCredential
import com.codexbar.android.core.domain.model.AppError
import com.codexbar.android.core.domain.model.Credential
import com.codexbar.android.core.domain.model.ExtraUsage
import com.codexbar.android.core.domain.model.ProviderSecretKind
import com.codexbar.android.core.domain.model.QuotaInfo
import com.codexbar.android.core.domain.model.Result
import com.codexbar.android.core.domain.model.UsageWindow
import com.codexbar.android.core.domain.repository.QuotaRepository
import com.codexbar.android.core.network.RetryAfter
import com.codexbar.android.core.network.openrouter.OpenRouterApiService
import com.codexbar.android.core.network.openrouter.OpenRouterDto
import com.codexbar.android.core.security.EncryptedPrefsManager
import java.io.IOException
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Locale
import javax.inject.Inject

class OpenRouterRepositoryImpl @Inject constructor(
    private val apiService: OpenRouterApiService,
    private val prefsManager: EncryptedPrefsManager
) : QuotaRepository {

    override suspend fun fetchQuota(connection: AccountConnection?): Result<QuotaInfo, AppError> {
        val credential = prefsManager.loadCredential(AiService.OPENROUTER, connection)
            as? Credential.ProviderSecretCredential
            ?: return Result.Failure(AppError.CredentialNotFound(AiService.OPENROUTER))
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
            val response = apiService.getCurrentKey("Bearer $apiKey")
            when (response.code()) {
                200 -> {
                    val body = response.body()
                        ?: return Result.Failure(AppError.ParseError("Empty response body"))
                    Result.Success(mapToQuotaInfo(body.data))
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

    private fun mapToQuotaInfo(data: OpenRouterDto.KeyData): QuotaInfo {
        require(
            listOf(data.usage, data.usageDaily, data.usageWeekly, data.usageMonthly)
                .all { it.isFinite() && it >= 0.0 }
        ) { "OpenRouter usage values are invalid" }
        val now = Instant.now()
        val limit = data.limit?.takeIf { it.isFinite() && it > 0.0 }
        val used = limit?.let {
            val fromRemaining = data.limitRemaining
                ?.takeIf(Double::isFinite)
                ?.let { remaining -> it - remaining }
            (fromRemaining ?: data.usage).coerceIn(0.0, it)
        }
        val utilization = if (limit != null && used != null) used / limit else null
        val resetKind = data.limitReset?.trim()?.lowercase(Locale.ROOT)
        val window = utilization?.let {
            UsageWindow(
                label = resetKind.budgetLabel(),
                utilization = it,
                resetsAt = resetKind.nextResetAt(now),
                windowDurationSeconds = resetKind.durationSeconds()
            )
        }
        val tier = when {
            limit == null -> buildString {
                append(formatUsd(data.usage))
                append(" used · No spending cap")
                if (data.isFreeTier) append(" · Free tier")
            }
            data.isFreeTier -> "Free tier"
            else -> resetKind?.replaceFirstChar(Char::uppercase)?.let { "$it cap" }
        }

        return QuotaInfo(
            service = AiService.OPENROUTER,
            windows = listOfNotNull(window),
            extraUsage = if (limit != null && used != null && utilization != null) {
                ExtraUsage(
                    isEnabled = true,
                    monthlyLimit = limit,
                    usedCredits = used,
                    utilization = utilization,
                    currency = "USD"
                )
            } else {
                null
            },
            tier = tier,
            fetchedAt = now
        )
    }

    private fun String?.budgetLabel(): String = when (this) {
        "daily" -> "Daily budget"
        "weekly" -> "Weekly budget"
        "monthly" -> "Monthly budget"
        else -> "API key budget"
    }

    private fun String?.durationSeconds(): Long? = when (this) {
        "daily" -> DAY_SECONDS
        "weekly" -> WEEK_SECONDS
        else -> null
    }

    private fun String?.nextResetAt(now: Instant): Instant? {
        val utc = ZonedDateTime.ofInstant(now, ZoneOffset.UTC)
        return when (this) {
            "daily" -> utc.toLocalDate().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()
            "weekly" -> {
                val daysUntilMonday = (DayOfWeek.MONDAY.value - utc.dayOfWeek.value + 7) % 7
                utc.toLocalDate()
                    .plusDays(if (daysUntilMonday == 0) 7L else daysUntilMonday.toLong())
                    .atStartOfDay(ZoneOffset.UTC)
                    .toInstant()
            }
            "monthly" -> utc.toLocalDate()
                .withDayOfMonth(1)
                .plusMonths(1)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
            else -> null
        }
    }

    private fun formatUsd(value: Double): String = String.format(Locale.US, "USD %.2f", value)

    private fun Credential.ProviderSecretCredential.apiKeyOrNull(): String? {
        if (service != AiService.OPENROUTER || kind != ProviderSecretKind.API_KEY) return null
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
        AppError.AuthError(AiService.OPENROUTER, isTerminal = true)
    )

    private companion object {
        const val DAY_SECONDS = 24L * 60L * 60L
        const val WEEK_SECONDS = 7L * DAY_SECONDS
        const val MAX_API_KEY_LENGTH = 4_096
    }
}
