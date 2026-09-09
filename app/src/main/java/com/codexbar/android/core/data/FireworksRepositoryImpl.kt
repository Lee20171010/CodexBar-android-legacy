package com.codexbar.android.core.data

import com.codexbar.android.core.domain.model.AiService
import com.codexbar.android.core.domain.model.AccountConnection
import com.codexbar.android.core.security.loadCredential
import com.codexbar.android.core.domain.model.AppError
import com.codexbar.android.core.domain.model.Credential
import com.codexbar.android.core.domain.model.ProviderSecretKind
import com.codexbar.android.core.domain.model.QuotaInfo
import com.codexbar.android.core.domain.model.Result
import com.codexbar.android.core.domain.repository.QuotaRepository
import com.codexbar.android.core.network.RetryAfter
import com.codexbar.android.core.network.fireworks.FireworksApiService
import com.codexbar.android.core.network.fireworks.FireworksDto
import com.codexbar.android.core.security.EncryptedPrefsManager
import java.io.IOException
import java.text.NumberFormat
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale
import javax.inject.Inject

class FireworksRepositoryImpl @Inject constructor(
    private val apiService: FireworksApiService,
    private val prefsManager: EncryptedPrefsManager,
    private val clock: Clock = Clock.systemUTC()
) : QuotaRepository {

    override suspend fun fetchQuota(connection: AccountConnection?): Result<QuotaInfo, AppError> {
        val credential = prefsManager.loadCredential(AiService.FIREWORKS, connection)
            as? Credential.ProviderSecretCredential
            ?: return Result.Failure(AppError.CredentialNotFound(AiService.FIREWORKS))
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
        val accountSlug = credential.accountSlugOrNull() ?: return terminalAuthError()
        val endTime = clock.instant().truncatedTo(ChronoUnit.SECONDS)
        val startTime = endTime.minus(LOOKBACK_DAYS, ChronoUnit.DAYS)
        return try {
            val response = apiService.getBillingSummary(
                accountSlug = accountSlug,
                startTime = startTime.toString(),
                endTime = endTime.toString(),
                authorization = "Bearer $apiKey"
            )
            when (response.code()) {
                200 -> {
                    val body = response.body()
                        ?: return Result.Failure(AppError.ParseError("Empty response body"))
                    Result.Success(mapToQuotaInfo(body, endTime))
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

    private fun mapToQuotaInfo(
        body: FireworksDto.BillingSummaryResponse,
        fetchedAt: Instant
    ): QuotaInfo {
        var currency: String? = null
        var total = 0.0
        body.lineItems.orEmpty().forEach { item ->
            val cost = item.totalCost ?: return@forEach
            val units = cost.units?.toDoubleOrNull()?.takeIf(Double::isFinite) ?: return@forEach
            val nanos = cost.nanos ?: return@forEach
            val code = cost.currencyCode?.trim()
                ?.takeIf { it.isNotEmpty() && it.length <= MAX_CURRENCY_CODE_LENGTH }
                ?.takeIf { it.none(Char::isISOControl) }
                ?.uppercase(Locale.ROOT)
                ?: return@forEach
            if (currency == null) currency = code
            if (code != currency) return@forEach
            val amount = units + nanos.toDouble() / NANOS_PER_UNIT
            if (amount.isFinite()) total += amount
        }
        require(total.isFinite()) { "Fireworks billing total is invalid" }
        val tier = if (currency == null) {
            "No rated spend in the last 30 days"
        } else {
            "Last 30 days: $currency ${formatSpend(total)}"
        }
        return QuotaInfo(
            service = AiService.FIREWORKS,
            windows = emptyList(),
            extraUsage = null,
            tier = tier,
            fetchedAt = fetchedAt
        )
    }

    private fun Credential.ProviderSecretCredential.apiKeyOrNull(): String? {
        if (service != AiService.FIREWORKS || kind != ProviderSecretKind.API_KEY) return null
        val normalized = accessToken.trim()
        return normalized.takeIf {
            it.isNotEmpty() &&
                it.length <= MAX_API_KEY_LENGTH &&
                it.none(Char::isWhitespace) &&
                it.none(Char::isISOControl)
        }
    }

    private fun Credential.ProviderSecretCredential.accountSlugOrNull(): String? {
        val slug = accountReference?.trim() ?: return null
        return slug.takeIf {
            it.length in 1..MAX_ACCOUNT_SLUG_LENGTH &&
                it.all { character ->
                    character in 'a'..'z' ||
                        character in 'A'..'Z' ||
                        character in '0'..'9' ||
                        character == '.' ||
                        character == '_' ||
                        character == '-'
                }
        }
    }

    private fun formatSpend(value: Double): String = NumberFormat.getNumberInstance(Locale.US).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 6
        isGroupingUsed = false
    }.format(value)

    private fun Result<QuotaInfo, AppError>.asValidation(): Result<Unit, AppError> = when (this) {
        is Result.Success -> Result.Success(Unit)
        is Result.Failure -> Result.Failure(error)
    }

    private fun terminalAuthError(): Result.Failure<AppError> = Result.Failure(
        AppError.AuthError(AiService.FIREWORKS, isTerminal = true)
    )

    private companion object {
        const val LOOKBACK_DAYS = 30L
        const val NANOS_PER_UNIT = 1_000_000_000.0
        const val MAX_API_KEY_LENGTH = 4_096
        const val MAX_ACCOUNT_SLUG_LENGTH = 128
        const val MAX_CURRENCY_CODE_LENGTH = 16
    }
}
