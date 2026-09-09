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
import com.codexbar.android.core.network.synthetic.SyntheticApiService
import com.codexbar.android.core.network.synthetic.SyntheticDto
import com.codexbar.android.core.security.EncryptedPrefsManager
import java.io.IOException
import java.time.Instant
import javax.inject.Inject

class SyntheticRepositoryImpl @Inject constructor(
    private val apiService: SyntheticApiService,
    private val prefsManager: EncryptedPrefsManager
) : QuotaRepository {

    override suspend fun fetchQuota(connection: AccountConnection?): Result<QuotaInfo, AppError> {
        val credential = prefsManager.loadCredential(AiService.SYNTHETIC, connection)
            as? Credential.ProviderSecretCredential
            ?: return Result.Failure(AppError.CredentialNotFound(AiService.SYNTHETIC))
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
            val response = apiService.getQuotas("Bearer $apiKey")
            when (response.code()) {
                200 -> {
                    val subscription = response.body()?.subscription
                        ?: return Result.Failure(
                            AppError.ParseError("Synthetic response did not contain subscription quota")
                        )
                    Result.Success(mapToQuotaInfo(subscription))
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

    private fun mapToQuotaInfo(subscription: SyntheticDto.Subscription): QuotaInfo {
        require(subscription.limit.isFinite() && subscription.limit > 0.0) {
            "Synthetic quota limit is invalid"
        }
        require(subscription.requests.isFinite() && subscription.requests >= 0.0) {
            "Synthetic request usage is invalid"
        }
        return QuotaInfo(
            service = AiService.SYNTHETIC,
            windows = listOf(
                UsageWindow(
                    label = "Subscription",
                    utilization = (subscription.requests / subscription.limit).coerceIn(0.0, 1.0),
                    resetsAt = subscription.renewsAt?.let(::parseInstantOrNull)
                )
            ),
            extraUsage = null,
            fetchedAt = Instant.now()
        )
    }

    private fun parseInstantOrNull(value: String): Instant? =
        runCatching { Instant.parse(value) }.getOrNull()

    private fun Credential.ProviderSecretCredential.apiKeyOrNull(): String? {
        if (service != AiService.SYNTHETIC || kind != ProviderSecretKind.API_KEY) return null
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
        AppError.AuthError(AiService.SYNTHETIC, isTerminal = true)
    )

    private companion object {
        const val MAX_API_KEY_LENGTH = 4_096
    }
}
