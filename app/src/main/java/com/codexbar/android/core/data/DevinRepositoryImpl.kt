package com.codexbar.android.core.data

import com.codexbar.android.core.domain.model.*
import com.codexbar.android.core.domain.repository.QuotaRepository
import com.codexbar.android.core.network.RetryAfter
import com.codexbar.android.core.network.devin.DevinApiService
import com.codexbar.android.core.security.EncryptedPrefsManager
import com.codexbar.android.core.security.loadCredential
import java.io.IOException
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.Locale
import javax.inject.Inject

/** Organization consumption is available on all plans; the API does not expose a remaining quota. */
class DevinRepositoryImpl @Inject constructor(
    private val apiService: DevinApiService,
    private val prefsManager: EncryptedPrefsManager,
    private val clock: Clock = Clock.systemUTC()
) : QuotaRepository {
    override suspend fun fetchQuota(connection: AccountConnection?): Result<QuotaInfo, AppError> {
        val credential = prefsManager.loadCredential(AiService.DEVIN, connection)
            ?: return Result.Failure(AppError.CredentialNotFound(AiService.DEVIN))
        return fetchQuota(credential)
    }

    override suspend fun validateCredential(): Result<Unit, AppError> = fetchQuota().validation()
    override suspend fun validateCredential(credential: Credential): Result<Unit, AppError> = fetchQuota(credential).validation()

    private suspend fun fetchQuota(credential: Credential): Result<QuotaInfo, AppError> {
        val secret = credential as? Credential.ProviderSecretCredential ?: return authError()
        if (secret.service != AiService.DEVIN || secret.kind != ProviderSecretKind.API_KEY) return authError()
        val token = secret.accessToken.trim()
        val org = secret.accountReference?.trim().orEmpty()
        if (!token.startsWith("cog_") || token.length !in 5..4096 || token.any { it.isWhitespace() || it.isISOControl() } ||
            !org.matches(Regex("[A-Za-z0-9_-]{1,128}"))) return authError()
        val now = clock.instant().truncatedTo(ChronoUnit.SECONDS)
        return try {
            val response = apiService.getDailyConsumption(org,
                now.minus(30, ChronoUnit.DAYS).epochSecond, now.epochSecond, "Bearer $token")
            when (response.code()) {
                200 -> {
                    val total = response.body()?.totalAcus
                        ?: return Result.Failure(AppError.ParseError("Missing ACU consumption"))
                    if (!total.isFinite() || total < 0) return Result.Failure(AppError.ParseError("Invalid ACU consumption"))
                    Result.Success(QuotaInfo(service = AiService.DEVIN, windows = emptyList(), extraUsage = null,
                        tier = String.format(Locale.US, "30d · %.2f ACU used", total), fetchedAt = now))
                }
                401 -> authError()
                403 -> Result.Failure(AppError.AuthError(AiService.DEVIN, isTerminal = false, permissionDenied = true))
                429 -> Result.Failure(AppError.RateLimited(RetryAfter.parseRetryAt(response.headers()["Retry-After"])))
                in 500..599 -> Result.Failure(AppError.ServiceUnavailable)
                else -> Result.Failure(AppError.NetworkError("HTTP ${response.code()}"))
            }
        } catch (error: IOException) {
            Result.Failure(AppError.NetworkError("Devin request failed", error))
        } catch (error: Exception) {
            Result.Failure(AppError.ParseError("Invalid Devin response", error))
        }
    }

    private fun authError() = Result.Failure(AppError.AuthError(AiService.DEVIN, isTerminal = true))
    private fun Result<QuotaInfo, AppError>.validation(): Result<Unit, AppError> = when (this) {
        is Result.Success -> Result.Success(Unit)
        is Result.Failure -> Result.Failure(error)
    }
}
