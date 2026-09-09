package com.codexbar.android.core.data

import com.codexbar.android.core.domain.model.AiService
import com.codexbar.android.core.domain.model.AccountConnection
import com.codexbar.android.core.security.loadCredential
import com.codexbar.android.core.domain.model.AppError
import com.codexbar.android.core.domain.model.Credential
import com.codexbar.android.core.domain.model.QuotaInfo
import com.codexbar.android.core.domain.model.Result
import com.codexbar.android.core.domain.model.UsageWindow
import com.codexbar.android.core.domain.repository.QuotaRepository
import com.codexbar.android.core.network.RetryAfter
import com.codexbar.android.core.network.copilot.CopilotApiService
import com.codexbar.android.core.network.copilot.CopilotDto
import com.codexbar.android.core.security.EncryptedPrefsManager
import java.io.IOException
import java.time.Instant
import javax.inject.Inject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class CopilotRepositoryImpl @Inject constructor(
    private val apiService: CopilotApiService,
    private val prefsManager: EncryptedPrefsManager
) : QuotaRepository {

    override suspend fun fetchQuota(connection: AccountConnection?): Result<QuotaInfo, AppError> {
        val credential = prefsManager.loadCredential(AiService.COPILOT, connection)
            as? Credential.CopilotCredential
            ?: return Result.Failure(AppError.CredentialNotFound(AiService.COPILOT))

        return try {
            val response = apiService.getUsage("token ${credential.accessToken}")

            when (response.code()) {
                200 -> {
                    val body = response.body()
                        ?: return Result.Failure(AppError.ParseError("Empty response body"))
                    Result.Success(mapToQuotaInfo(body))
                }
                401, 403 -> Result.Failure(AppError.AuthError(AiService.COPILOT, isTerminal = true))
                429 -> Result.Failure(AppError.RateLimited(RetryAfter.parseRetryAt(response.headers()["Retry-After"])))
                else -> Result.Failure(
                    AppError.NetworkError("HTTP ${response.code()}: ${response.message()}")
                )
            }
        } catch (e: IOException) {
            Result.Failure(AppError.NetworkError(e.message ?: "Network error", e))
        } catch (e: Exception) {
            Result.Failure(AppError.ParseError(e.message ?: "Parse error", e))
        }
    }

    override suspend fun validateCredential(): Result<Unit, AppError> {
        return when (val result = fetchQuota()) {
            is Result.Success -> Result.Success(Unit)
            is Result.Failure -> Result.Failure(result.error)
        }
    }

    override suspend fun validateCredential(credential: Credential): Result<Unit, AppError> {
        val typed = credential as? Credential.CopilotCredential
            ?: return Result.Failure(AppError.AuthError(AiService.COPILOT, isTerminal = true))

        return try {
            val response = apiService.getUsage("token ${typed.accessToken}")
            when (response.code()) {
                200 -> Result.Success(Unit)
                401, 403 -> Result.Failure(AppError.AuthError(AiService.COPILOT, isTerminal = true))
                429 -> Result.Failure(AppError.RateLimited(RetryAfter.parseRetryAt(response.headers()["Retry-After"])))
                else -> Result.Failure(AppError.NetworkError("HTTP ${response.code()}: ${response.message()}"))
            }
        } catch (e: IOException) {
            Result.Failure(AppError.NetworkError(e.message ?: "Network error", e))
        } catch (e: Exception) {
            Result.Failure(AppError.ParseError(e.message ?: "Parse error", e))
        }
    }

    private fun mapToQuotaInfo(response: CopilotDto.UsageResponse): QuotaInfo {
        val snapshots = response.snapshots
        val windows = buildList {
            snapshots?.snapshotFor("premiumInteractions", "premium_interactions")?.let { snapshot ->
                makeWindow("Premium", snapshot)?.let(::add)
            }
            snapshots?.snapshotFor("chat")?.let { snapshot ->
                makeWindow("Chat", snapshot)?.let(::add)
            }
        }

        return QuotaInfo(
            service = AiService.COPILOT,
            windows = windows,
            extraUsage = null,
            tier = response.plan?.replaceFirstChar { it.uppercase() },
            fetchedAt = Instant.now()
        )
    }

    private fun makeWindow(label: String, snapshot: JsonObject): UsageWindow? {
        val isPlaceholder = snapshot.booleanValue("isPlaceholder", "is_placeholder") ?: false
        if (isPlaceholder) return null

        val percentRemaining = snapshot.doubleValue("percentRemaining", "percent_remaining")
            ?: return null
        val used = ((100.0 - percentRemaining.coerceIn(0.0, 100.0)) / 100.0)
            .coerceIn(0.0, 1.0)

        return UsageWindow(
            label = label,
            utilization = used,
            resetsAt = null
        )
    }

    private fun JsonObject.snapshotFor(vararg keys: String): JsonObject? {
        return keys.firstNotNullOfOrNull { key -> this[key]?.jsonObjectOrNull() }
    }

    private fun JsonObject.doubleValue(vararg keys: String): Double? {
        return keys.firstNotNullOfOrNull { key ->
            val primitive = this[key]?.jsonPrimitiveOrNull() ?: return@firstNotNullOfOrNull null
            primitive.doubleOrNull ?: primitive.content.toDoubleOrNull()
        }
    }

    private fun JsonObject.booleanValue(vararg keys: String): Boolean? {
        return keys.firstNotNullOfOrNull { key ->
            val primitive = this[key]?.jsonPrimitiveOrNull() ?: return@firstNotNullOfOrNull null
            primitive.booleanOrNull ?: primitive.content.toBooleanStrictOrNull()
        }
    }

    private fun JsonElement.jsonObjectOrNull(): JsonObject? {
        return try {
            jsonObject
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun JsonElement.jsonPrimitiveOrNull(): JsonPrimitive? {
        return try {
            jsonPrimitive
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
