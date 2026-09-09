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
import com.codexbar.android.core.network.zenmux.ZenMuxApiService
import com.codexbar.android.core.network.zenmux.ZenMuxDto
import com.codexbar.android.core.security.EncryptedPrefsManager
import java.io.IOException
import java.time.Instant
import javax.inject.Inject

class ZenMuxRepositoryImpl @Inject constructor(
    private val apiService: ZenMuxApiService,
    private val prefsManager: EncryptedPrefsManager
) : QuotaRepository {

    override suspend fun fetchQuota(connection: AccountConnection?): Result<QuotaInfo, AppError> {
        val credential = prefsManager.loadCredential(AiService.ZENMUX, connection)
            as? Credential.ProviderSecretCredential
            ?: return Result.Failure(AppError.CredentialNotFound(AiService.ZENMUX))
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
        val key = credential.apiKeyOrNull() ?: return terminalAuthError()
        return try {
            val response = apiService.getSubscription("Bearer $key")
            when (response.code()) {
                200 -> {
                    val body = response.body()
                        ?: return Result.Failure(AppError.ParseError("Empty response body"))
                    if (!body.success) {
                        return Result.Failure(AppError.ParseError("ZenMux reported an unsuccessful response"))
                    }
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

    private fun mapToQuotaInfo(data: ZenMuxDto.SubscriptionData): QuotaInfo {
        val tier = buildList {
            data.plan.tier.trim().takeIf(String::isNotEmpty)?.let(::add)
            data.accountStatus.trim()
                .takeIf { it.isNotEmpty() && !it.equals("healthy", ignoreCase = true) }
                ?.replaceFirstChar(Char::uppercase)
                ?.let(::add)
        }.joinToString(" · ").ifBlank { null }

        return QuotaInfo(
            service = AiService.ZENMUX,
            windows = listOf(
                data.fiveHour.toUsageWindow("5-Hour", FIVE_HOURS_SECONDS),
                data.sevenDay.toUsageWindow("7-Day", SEVEN_DAYS_SECONDS)
            ),
            extraUsage = null,
            tier = tier,
            fetchedAt = Instant.now()
        )
    }

    private fun ZenMuxDto.Quota.toUsageWindow(
        label: String,
        durationSeconds: Long
    ): UsageWindow {
        require(usagePercentage.isFinite()) { "Invalid ZenMux usage percentage" }
        require(maxFlows.isFinite() && usedFlows.isFinite() && remainingFlows.isFinite()) {
            "Invalid ZenMux flow values"
        }
        return UsageWindow(
            label = label,
            utilization = usagePercentage.coerceIn(0.0, 1.0),
            resetsAt = resetsAt?.let(::parseInstantOrNull),
            windowDurationSeconds = durationSeconds
        )
    }

    private fun parseInstantOrNull(value: String): Instant? =
        runCatching { Instant.parse(value) }.getOrNull()

    private fun Credential.ProviderSecretCredential.apiKeyOrNull(): String? {
        if (service != AiService.ZENMUX || kind != ProviderSecretKind.API_KEY) return null
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
        AppError.AuthError(AiService.ZENMUX, isTerminal = true)
    )

    private companion object {
        const val FIVE_HOURS_SECONDS = 5L * 60L * 60L
        const val SEVEN_DAYS_SECONDS = 7L * 24L * 60L * 60L
        const val MAX_API_KEY_LENGTH = 4_096
    }
}
