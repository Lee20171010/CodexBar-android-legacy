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
import com.codexbar.android.core.network.moonshot.MoonshotApiService
import com.codexbar.android.core.network.moonshot.MoonshotDto
import com.codexbar.android.core.security.EncryptedPrefsManager
import java.io.IOException
import java.time.Instant
import java.util.Locale
import javax.inject.Inject

class MoonshotRepositoryImpl @Inject constructor(
    private val apiService: MoonshotApiService,
    private val prefsManager: EncryptedPrefsManager
) : QuotaRepository {

    override suspend fun fetchQuota(connection: AccountConnection?): Result<QuotaInfo, AppError> {
        val credential = prefsManager.loadCredential(AiService.MOONSHOT, connection)
            as? Credential.ProviderSecretCredential
            ?: return Result.Failure(AppError.CredentialNotFound(AiService.MOONSHOT))
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

    private fun mapToQuotaInfo(body: MoonshotDto.BalanceResponse): QuotaInfo {
        require(body.status) { "Moonshot balance request was not successful" }
        val available = body.data.availableBalance.validAmount("available")
        val voucher = body.data.voucherBalance.validAmount("voucher")
        val cash = body.data.cashBalance.validAmount("cash")
        val cashLabel = if (cash < 0.0) "cash deficit" else "cash"
        return QuotaInfo(
            service = AiService.MOONSHOT,
            windows = emptyList(),
            extraUsage = null,
            tier = buildString {
                append("Available balance ")
                append(formatAmount(available))
                append(" (")
                append(cashLabel)
                append(' ')
                append(formatAmount(cash))
                append(" + voucher ")
                append(formatAmount(voucher))
                append(')')
            },
            fetchedAt = Instant.now()
        )
    }

    private fun Double.validAmount(label: String): Double {
        require(isFinite()) { "Moonshot $label balance is invalid" }
        return this
    }

    private fun formatAmount(value: Double): String = String.format(Locale.US, "%.2f", value)

    private fun Credential.ProviderSecretCredential.apiKeyOrNull(): String? {
        if (service != AiService.MOONSHOT || kind != ProviderSecretKind.API_KEY) return null
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
        AppError.AuthError(AiService.MOONSHOT, isTerminal = true)
    )

    private companion object {
        const val MAX_API_KEY_LENGTH = 4_096
    }
}
