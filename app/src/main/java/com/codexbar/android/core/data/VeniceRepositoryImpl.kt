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
import com.codexbar.android.core.network.venice.VeniceApiService
import com.codexbar.android.core.network.venice.VeniceDto
import com.codexbar.android.core.security.EncryptedPrefsManager
import java.io.IOException
import java.time.Instant
import java.util.Locale
import javax.inject.Inject

class VeniceRepositoryImpl @Inject constructor(
    private val apiService: VeniceApiService,
    private val prefsManager: EncryptedPrefsManager
) : QuotaRepository {

    override suspend fun fetchQuota(connection: AccountConnection?): Result<QuotaInfo, AppError> {
        val credential = prefsManager.loadCredential(AiService.VENICE, connection)
            as? Credential.ProviderSecretCredential
            ?: return Result.Failure(AppError.CredentialNotFound(AiService.VENICE))
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
            val response = apiService.getBalance("Bearer $apiKey")
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

    private fun mapToQuotaInfo(body: VeniceDto.BalanceResponse): QuotaInfo {
        val diem = body.balances.diem?.validBalance("DIEM")
        val usd = body.balances.usd?.validBalance("USD")
        require(diem != null || usd != null) { "Venice returned no balances" }
        val allocation = body.diemEpochAllocation
        require(allocation.isFinite() && allocation >= 0.0) {
            "Venice DIEM epoch allocation is invalid"
        }
        val used = if (diem != null && allocation > 0.0) {
            (allocation - diem).coerceIn(0.0, allocation)
        } else {
            null
        }
        val utilization = used?.div(allocation)
        val status = if (body.canConsume) "API enabled" else "API balance unavailable"
        val activeCurrency = body.consumptionCurrency
            ?.trim()
            ?.uppercase(Locale.ROOT)
            ?.takeIf { it in SUPPORTED_CURRENCIES }
        val balances = buildList {
            diem?.let { add("DIEM ${formatAmount(it)}") }
            usd?.let { add("USD ${formatAmount(it)}") }
        }

        return QuotaInfo(
            service = AiService.VENICE,
            windows = if (utilization != null) {
                listOf(
                    UsageWindow(
                        label = "DIEM epoch",
                        utilization = utilization,
                        resetsAt = null,
                        windowDurationSeconds = DAY_SECONDS
                    )
                )
            } else {
                emptyList()
            },
            extraUsage = if (used != null && utilization != null) {
                ExtraUsage(
                    isEnabled = body.canConsume,
                    monthlyLimit = allocation,
                    usedCredits = used,
                    utilization = utilization,
                    currency = "DIEM"
                )
            } else {
                null
            },
            tier = buildList {
                add(status)
                activeCurrency?.let { add("Uses $it") }
                addAll(balances)
            }.joinToString(" · "),
            fetchedAt = Instant.now()
        )
    }

    private fun Double.validBalance(label: String): Double {
        require(isFinite() && this >= 0.0) { "Venice $label balance is invalid" }
        return this
    }

    private fun formatAmount(value: Double): String = String.format(Locale.US, "%.2f", value)

    private fun Credential.ProviderSecretCredential.apiKeyOrNull(): String? {
        if (service != AiService.VENICE || kind != ProviderSecretKind.API_KEY) return null
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
        AppError.AuthError(AiService.VENICE, isTerminal = true)
    )

    private companion object {
        val SUPPORTED_CURRENCIES = setOf("USD", "VCU", "DIEM", "BUNDLED_CREDITS")
        const val DAY_SECONDS = 24L * 60L * 60L
        const val MAX_API_KEY_LENGTH = 4_096
    }
}
