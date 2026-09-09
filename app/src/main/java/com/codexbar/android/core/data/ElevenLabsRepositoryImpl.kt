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
import com.codexbar.android.core.network.elevenlabs.ElevenLabsApiService
import com.codexbar.android.core.network.elevenlabs.ElevenLabsDto
import com.codexbar.android.core.security.EncryptedPrefsManager
import java.io.IOException
import java.time.Instant
import javax.inject.Inject

class ElevenLabsRepositoryImpl @Inject constructor(
    private val apiService: ElevenLabsApiService,
    private val prefsManager: EncryptedPrefsManager
) : QuotaRepository {

    override suspend fun fetchQuota(connection: AccountConnection?): Result<QuotaInfo, AppError> {
        val credential = prefsManager.loadCredential(AiService.ELEVENLABS, connection)
            as? Credential.ProviderSecretCredential
            ?: return Result.Failure(AppError.CredentialNotFound(AiService.ELEVENLABS))
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
            val response = apiService.getSubscription(apiKey)
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

    private fun mapToQuotaInfo(data: ElevenLabsDto.Subscription): QuotaInfo {
        val reset = data.nextCharacterCountResetUnix
            ?.takeIf { it > 0L }
            ?.let { runCatching { Instant.ofEpochSecond(it) }.getOrNull() }
        val windows = buildList {
            add(quotaWindow("Characters", data.characterCount, data.characterLimit, reset))
            optionalQuotaWindow("Voice slots", data.voiceSlotsUsed, data.voiceLimit)?.let(::add)
            optionalQuotaWindow(
                "Professional voices",
                data.professionalVoiceSlotsUsed,
                data.professionalVoiceLimit
            )?.let(::add)
        }
        val tier = buildList {
            data.tier?.trim()?.takeIf(String::isNotEmpty)?.let(::add)
            data.status?.trim()
                ?.takeIf { it.isNotEmpty() && !it.equals("active", ignoreCase = true) }
                ?.replaceFirstChar(Char::uppercase)
                ?.let(::add)
        }.joinToString(" · ").ifBlank { null }

        return QuotaInfo(
            service = AiService.ELEVENLABS,
            windows = windows,
            extraUsage = null,
            tier = tier,
            fetchedAt = Instant.now()
        )
    }

    private fun optionalQuotaWindow(
        label: String,
        used: Long?,
        limit: Long?
    ): UsageWindow? {
        if (used == null || limit == null || limit <= 0L) return null
        return quotaWindow(label, used, limit, resetsAt = null)
    }

    private fun quotaWindow(
        label: String,
        used: Long,
        limit: Long,
        resetsAt: Instant?
    ): UsageWindow {
        require(limit > 0L) { "ElevenLabs quota limit is invalid" }
        require(used >= 0L) { "ElevenLabs quota usage is invalid" }
        return UsageWindow(
            label = label,
            utilization = (used.toDouble() / limit.toDouble()).coerceIn(0.0, 1.0),
            resetsAt = resetsAt
        )
    }

    private fun Credential.ProviderSecretCredential.apiKeyOrNull(): String? {
        if (service != AiService.ELEVENLABS || kind != ProviderSecretKind.API_KEY) return null
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
        AppError.AuthError(AiService.ELEVENLABS, isTerminal = true)
    )

    private companion object {
        const val MAX_API_KEY_LENGTH = 4_096
    }
}
