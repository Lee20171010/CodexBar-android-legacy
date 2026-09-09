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
import com.codexbar.android.core.network.chutes.ChutesApiService
import com.codexbar.android.core.security.EncryptedPrefsManager
import java.io.IOException
import java.time.Instant
import javax.inject.Inject
import kotlinx.serialization.json.JsonElement
import retrofit2.Response

class ChutesRepositoryImpl @Inject constructor(
    private val apiService: ChutesApiService,
    private val prefsManager: EncryptedPrefsManager
) : QuotaRepository {

    override suspend fun fetchQuota(connection: AccountConnection?): Result<QuotaInfo, AppError> {
        val credential = prefsManager.loadCredential(AiService.CHUTES, connection)
            as? Credential.ProviderSecretCredential
            ?: return Result.Failure(AppError.CredentialNotFound(AiService.CHUTES))
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
        val authorization = "Bearer $apiKey"
        return try {
            val subscriptionResponse = apiService.getSubscriptionUsage(authorization)
            when (subscriptionResponse.code()) {
                200 -> {
                    val parsed = subscriptionResponse.body()?.let(ChutesQuotaParser::parse)
                    if (parsed != null && parsed.windows.isNotEmpty()) {
                        return Result.Success(parsed.toQuotaInfo())
                    }
                }
                401, 403 -> return terminalAuthError()
                429 -> return subscriptionResponse.rateLimited()
                404, 422 -> Unit
                else -> return subscriptionResponse.networkFailure()
            }

            val quotaResponse = apiService.getQuotas(authorization)
            when (quotaResponse.code()) {
                200 -> {
                    val parsed = quotaResponse.body()?.let(ChutesQuotaParser::parse)
                        ?: return Result.Failure(AppError.ParseError("Empty response body"))
                    if (parsed.windows.isEmpty()) {
                        return Result.Failure(
                            AppError.ParseError("Chutes response did not contain usable quota data")
                        )
                    }
                    Result.Success(parsed.toQuotaInfo())
                }
                401, 403 -> terminalAuthError()
                429 -> quotaResponse.rateLimited()
                else -> quotaResponse.networkFailure()
            }
        } catch (error: IOException) {
            Result.Failure(AppError.NetworkError(error.message ?: "Network error", error))
        } catch (error: Exception) {
            Result.Failure(AppError.ParseError(error.message ?: "Parse error", error))
        }
    }

    private fun ChutesQuotaParser.ParsedQuota.toQuotaInfo(): QuotaInfo = QuotaInfo(
        service = AiService.CHUTES,
        windows = windows,
        extraUsage = null,
        tier = tier,
        fetchedAt = Instant.now()
    )

    private fun Response<JsonElement>.rateLimited(): Result.Failure<AppError> = Result.Failure(
        AppError.RateLimited(RetryAfter.parseRetryAt(headers()["Retry-After"]))
    )

    private fun Response<JsonElement>.networkFailure(): Result.Failure<AppError> = Result.Failure(
        AppError.NetworkError("HTTP ${code()}: ${message()}")
    )

    private fun Credential.ProviderSecretCredential.apiKeyOrNull(): String? {
        if (service != AiService.CHUTES || kind != ProviderSecretKind.API_KEY) return null
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
        AppError.AuthError(AiService.CHUTES, isTerminal = true)
    )

    private companion object {
        const val MAX_API_KEY_LENGTH = 4_096
    }
}
