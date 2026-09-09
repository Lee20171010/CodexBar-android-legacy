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
import com.codexbar.android.core.network.deepseek.DeepSeekApiService
import com.codexbar.android.core.network.deepseek.DeepSeekDto
import com.codexbar.android.core.security.EncryptedPrefsManager
import java.io.IOException
import java.time.Instant
import java.util.Locale
import javax.inject.Inject

class DeepSeekRepositoryImpl @Inject constructor(
    private val apiService: DeepSeekApiService,
    private val prefsManager: EncryptedPrefsManager
) : QuotaRepository {

    override suspend fun fetchQuota(connection: AccountConnection?): Result<QuotaInfo, AppError> {
        val credential = prefsManager.loadCredential(AiService.DEEPSEEK, connection)
            as? Credential.ProviderSecretCredential
            ?: return Result.Failure(AppError.CredentialNotFound(AiService.DEEPSEEK))
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

    private fun mapToQuotaInfo(body: DeepSeekDto.BalanceResponse): QuotaInfo {
        require(body.balanceInfos.isNotEmpty()) { "DeepSeek returned no balance information" }
        val balances = body.balanceInfos.map { info ->
            val currency = info.currency.trim().uppercase(Locale.ROOT)
                .takeIf { it in SUPPORTED_CURRENCIES }
                ?: error("Unsupported DeepSeek balance currency")
            val total = info.totalBalance.amountOrNull()
                ?: error("Invalid DeepSeek total balance")
            val granted = info.grantedBalance.amountOrNull()
                ?: error("Invalid DeepSeek granted balance")
            val toppedUp = info.toppedUpBalance.amountOrNull()
                ?: error("Invalid DeepSeek topped-up balance")
            require(total >= 0.0 && granted >= 0.0 && toppedUp >= 0.0) {
                "DeepSeek balance values must not be negative"
            }
            "$currency ${formatAmount(total)} (paid ${formatAmount(toppedUp)} + grant ${formatAmount(granted)})"
        }
        val availability = if (body.isAvailable) "Available" else "Unavailable for API calls"
        return QuotaInfo(
            service = AiService.DEEPSEEK,
            windows = emptyList(),
            extraUsage = null,
            tier = "$availability · ${balances.joinToString(" · ")}",
            fetchedAt = Instant.now()
        )
    }

    private fun String.amountOrNull(): Double? = trim().toDoubleOrNull()?.takeIf(Double::isFinite)

    private fun formatAmount(value: Double): String = String.format(Locale.US, "%.2f", value)

    private fun Credential.ProviderSecretCredential.apiKeyOrNull(): String? {
        if (service != AiService.DEEPSEEK || kind != ProviderSecretKind.API_KEY) return null
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
        AppError.AuthError(AiService.DEEPSEEK, isTerminal = true)
    )

    private companion object {
        val SUPPORTED_CURRENCIES = setOf("USD", "CNY")
        const val MAX_API_KEY_LENGTH = 4_096
    }
}
