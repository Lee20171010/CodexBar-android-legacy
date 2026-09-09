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
import com.codexbar.android.core.network.cursor.CursorApiService
import com.codexbar.android.core.network.cursor.CursorDto
import com.codexbar.android.core.security.EncryptedPrefsManager
import java.io.IOException
import java.time.Duration
import java.time.Instant
import javax.inject.Inject

class CursorRepositoryImpl @Inject constructor(
    private val apiService: CursorApiService,
    private val prefsManager: EncryptedPrefsManager
) : QuotaRepository {

    override suspend fun fetchQuota(connection: AccountConnection?): Result<QuotaInfo, AppError> {
        val credential = prefsManager.loadCredential(AiService.CURSOR, connection)
            as? Credential.ProviderSecretCredential
            ?: return Result.Failure(AppError.CredentialNotFound(AiService.CURSOR))
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
        val cookieHeader = credential.cookieHeaderOrNull() ?: return terminalAuthError()
        return try {
            val summaryResponse = apiService.getUsageSummary(cookieHeader)
            when (summaryResponse.code()) {
                200 -> {
                    val summary = summaryResponse.body()
                        ?: return Result.Failure(AppError.ParseError("Empty response body"))
                    val userInfo = runCatching { apiService.getUserInfo(cookieHeader) }
                        .getOrNull()
                        ?.takeIf { it.isSuccessful }
                        ?.body()
                    val legacyUsage = userInfo?.sub
                        ?.trim()
                        ?.takeIf(String::isNotEmpty)
                        ?.let { userId ->
                            runCatching { apiService.getLegacyUsage(userId, cookieHeader) }
                                .getOrNull()
                                ?.takeIf { it.isSuccessful }
                                ?.body()
                        }
                    Result.Success(mapToQuotaInfo(summary, legacyUsage))
                }

                401, 403 -> terminalAuthError()
                429 -> Result.Failure(
                    AppError.RateLimited(
                        RetryAfter.parseRetryAt(summaryResponse.headers()["Retry-After"])
                    )
                )

                else -> Result.Failure(
                    AppError.NetworkError(
                        "HTTP ${summaryResponse.code()}: ${summaryResponse.message()}"
                    )
                )
            }
        } catch (error: IOException) {
            Result.Failure(AppError.NetworkError(error.message ?: "Network error", error))
        } catch (error: Exception) {
            Result.Failure(AppError.ParseError(error.message ?: "Parse error", error))
        }
    }

    private fun mapToQuotaInfo(
        summary: CursorDto.UsageSummary,
        legacyUsage: CursorDto.LegacyUsageResponse?
    ): QuotaInfo {
        val resetAt = summary.billingCycleEnd?.let(::parseInstantOrNull)
        val startAt = summary.billingCycleStart?.let(::parseInstantOrNull)
        val durationSeconds = if (startAt != null && resetAt != null && resetAt.isAfter(startAt)) {
            Duration.between(startAt, resetAt).seconds
        } else {
            null
        }

        val legacy = legacyUsage?.gpt4
            ?.takeIf { (it.maxRequestUsage ?: 0) > 0 }
        val totalUtilization = legacy?.let { usage ->
            val used = usage.numRequestsTotal ?: usage.numRequests ?: 0
            used.toDouble() / requireNotNull(usage.maxRequestUsage).toDouble()
        } ?: modernTotalUtilization(summary)

        val windows = buildList {
            add(
                UsageWindow(
                    label = "Total",
                    utilization = totalUtilization.coerceIn(0.0, 1.0),
                    resetsAt = resetAt,
                    windowDurationSeconds = durationSeconds
                )
            )
            if (legacy == null) {
                summary.individualUsage?.plan?.autoPercentUsed
                    ?.toUtilizationOrNull()
                    ?.let { utilization ->
                        add(
                            UsageWindow(
                                label = "Auto",
                                utilization = utilization,
                                resetsAt = resetAt,
                                windowDurationSeconds = durationSeconds
                            )
                        )
                    }
                summary.individualUsage?.plan?.apiPercentUsed
                    ?.toUtilizationOrNull()
                    ?.let { utilization ->
                        add(
                            UsageWindow(
                                label = "API",
                                utilization = utilization,
                                resetsAt = resetAt,
                                windowDurationSeconds = durationSeconds
                            )
                        )
                    }
            }
        }

        return QuotaInfo(
            service = AiService.CURSOR,
            windows = windows,
            extraUsage = summary.toExtraUsage(),
            tier = summary.membershipType?.toCursorTier(),
            fetchedAt = Instant.now()
        )
    }

    private fun modernTotalUtilization(summary: CursorDto.UsageSummary): Double {
        val plan = summary.individualUsage?.plan
        plan?.totalPercentUsed?.toUtilizationOrNull()?.let { return it }

        val auto = plan?.autoPercentUsed?.toUtilizationOrNull()
        val api = plan?.apiPercentUsed?.toUtilizationOrNull()
        if (auto != null && api != null) return (auto + api) / 2.0
        if (api != null) return api
        if (auto != null) return auto

        ratioOrNull(plan?.used, plan?.limit)?.let { return it }
        ratioOrNull(
            summary.individualUsage?.overall?.used,
            summary.individualUsage?.overall?.limit
        )?.let { return it }
        ratioOrNull(summary.teamUsage?.pooled?.used, summary.teamUsage?.pooled?.limit)
            ?.let { return it }
        return 0.0
    }

    private fun CursorDto.UsageSummary.toExtraUsage(): ExtraUsage? {
        val personal = individualUsage?.onDemand
        val team = teamUsage?.onDemand
        val source = when {
            personal.hasFinitePositiveLimit() -> personal
            team.hasFinitePositiveLimit() -> team
            else -> null
        } ?: return null

        val limit = requireNotNull(source.limit)
        val used = source.used?.takeIf(Double::isFinite)?.coerceAtLeast(0.0) ?: 0.0
        return ExtraUsage(
            isEnabled = source.enabled != false,
            monthlyLimit = limit / CENTS_PER_DOLLAR,
            usedCredits = used / CENTS_PER_DOLLAR,
            utilization = (used / limit).coerceIn(0.0, 1.0),
            currency = "USD"
        )
    }

    private fun CursorDto.MeteredUsage?.hasFinitePositiveLimit(): Boolean =
        this?.limit?.let { it.isFinite() && it > 0.0 } == true

    private fun ratioOrNull(used: Double?, limit: Double?): Double? {
        if (used == null || limit == null || !used.isFinite() || !limit.isFinite() || limit <= 0.0) {
            return null
        }
        return (used / limit).coerceIn(0.0, 1.0)
    }

    private fun Double.toUtilizationOrNull(): Double? =
        takeIf(Double::isFinite)?.div(100.0)?.coerceIn(0.0, 1.0)

    private fun String.toCursorTier(): String? {
        val normalized = trim()
        if (normalized.isEmpty()) return null
        return "Cursor ${normalized.lowercase().replaceFirstChar(Char::uppercase)}"
    }

    private fun parseInstantOrNull(value: String): Instant? =
        runCatching { Instant.parse(value) }.getOrNull()

    private fun Credential.ProviderSecretCredential.cookieHeaderOrNull(): String? {
        if (service != AiService.CURSOR || kind != ProviderSecretKind.COOKIE_HEADER) return null
        val normalized = accessToken
            .trim()
            .removePrefixIgnoreCase("Cookie:")
            .trim()
        if (
            normalized.isEmpty() ||
            normalized.length > MAX_COOKIE_HEADER_LENGTH ||
            normalized.any(Char::isISOControl)
        ) {
            return null
        }
        val cookiePairs = normalized.split(';').map(String::trim).filter(String::isNotEmpty)
        if (cookiePairs.isEmpty() || cookiePairs.any { !it.isValidCookiePair() }) return null
        return cookiePairs.joinToString("; ")
    }

    private fun String.isValidCookiePair(): Boolean {
        val separator = indexOf('=')
        if (separator <= 0 || separator == lastIndex) return false
        val name = substring(0, separator)
        return COOKIE_NAME.matches(name)
    }

    private fun String.removePrefixIgnoreCase(prefix: String): String =
        if (startsWith(prefix, ignoreCase = true)) substring(prefix.length) else this

    private fun Result<QuotaInfo, AppError>.asValidation(): Result<Unit, AppError> = when (this) {
        is Result.Success -> Result.Success(Unit)
        is Result.Failure -> Result.Failure(error)
    }

    private fun terminalAuthError(): Result.Failure<AppError> = Result.Failure(
        AppError.AuthError(AiService.CURSOR, isTerminal = true)
    )

    private companion object {
        const val MAX_COOKIE_HEADER_LENGTH = 16_384
        const val CENTS_PER_DOLLAR = 100.0
        val COOKIE_NAME = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")
    }
}
