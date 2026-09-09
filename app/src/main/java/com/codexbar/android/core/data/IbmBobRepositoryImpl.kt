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
import com.codexbar.android.core.network.ibmbob.IbmBobApiService
import com.codexbar.android.core.network.ibmbob.IbmBobDto
import com.codexbar.android.core.security.EncryptedPrefsManager
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64
import java.util.Locale
import javax.inject.Inject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import retrofit2.Response

class IbmBobRepositoryImpl @Inject constructor(
    private val apiService: IbmBobApiService,
    private val prefsManager: EncryptedPrefsManager
) : QuotaRepository {

    override suspend fun fetchQuota(connection: AccountConnection?): Result<QuotaInfo, AppError> {
        val credential = prefsManager.loadCredential(AiService.IBM_BOB, connection)
            as? Credential.ProviderSecretCredential
            ?: return Result.Failure(AppError.CredentialNotFound(AiService.IBM_BOB))
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
        val authorization = if (isIbmBobJwt(apiKey)) "Bearer $apiKey" else "Apikey $apiKey"
        return try {
            val profileResponse = apiService.getProfile(authorization)
            profileResponse.errorOrNull()?.let { return Result.Failure(it) }
            val profile = profileResponse.body()
                ?: return Result.Failure(AppError.ParseError("Empty profile response body"))
            require(profile.instances.size <= MAX_PROFILE_INSTANCES) {
                "IBM Bob profile returned too many instances"
            }
            require(profile.instances.sumOf { it.teams.size } <= MAX_PROFILE_TEAMS) {
                "IBM Bob profile returned too many teams"
            }

            val teamUsages = buildList {
                profile.instances.forEach { instance ->
                    val instanceId = instance.instanceId.safeIdentifierOrNull() ?: return@forEach
                    val userId = instance.userId.safeIdentifierOrNull() ?: return@forEach
                    val regionalBaseUrl = trustedIbmBobRegionBaseUrl(instance.regionDomain)
                    instance.teams.forEach teamLoop@{ team ->
                        val teamId = team.id.safeIdentifierOrNull() ?: return@teamLoop
                        val url = regionalBaseUrl.newBuilder()
                            .addPathSegments("admin/v1/teams")
                            .addPathSegment(teamId)
                            .addPathSegment("users")
                            .addPathSegment(userId)
                            .build()
                        val budgetResponse = apiService.getTeamBudget(
                            url = url,
                            authorization = authorization,
                            instanceId = instanceId,
                            teamId = teamId
                        )
                        budgetResponse.errorOrNull()?.let { throw ResponseFailure(it) }
                        val budget = budgetResponse.body()
                            ?: error("IBM Bob returned an empty team budget")
                        require(budget.usage.isFinite()) { "IBM Bob usage is invalid" }
                        val limit = (budget.budgetLimit ?: team.budgetLimit)
                            ?.takeIf { it.isFinite() && it >= 0.0 }
                        add(
                            TeamUsage(
                                planName = instance.planName.nonEmptyOrNull(),
                                used = budget.usage.coerceAtLeast(0.0),
                                limit = limit,
                                resetsAt = parseRefreshAt(instance.refreshAt)
                            )
                        )
                    }
                }
            }
            require(teamUsages.isNotEmpty()) {
                "IBM Bob returned no subscription instances or teams"
            }
            Result.Success(teamUsages.toQuotaInfo())
        } catch (error: ResponseFailure) {
            Result.Failure(error.appError)
        } catch (error: IOException) {
            Result.Failure(AppError.NetworkError(error.message ?: "Network error", error))
        } catch (error: Exception) {
            Result.Failure(AppError.ParseError(error.message ?: "Parse error", error))
        }
    }

    private fun List<TeamUsage>.toQuotaInfo(): QuotaInfo {
        val used = sumOf { it.used }
        require(used.isFinite()) { "IBM Bob aggregate usage is invalid" }
        val limits = mapNotNull { it.limit }
        val limit = limits.takeIf { it.size == size && it.isNotEmpty() }
            ?.sum()
            ?.takeIf { it.isFinite() && it > 0.0 }
        val utilization = limit?.let { (used / it).coerceIn(0.0, 1.0) }
        val resetsAt = mapNotNull { it.resetsAt }.minOrNull()
        val plans = mapNotNull { it.planName }.distinct().sorted()
        val usageSummary = limit?.let { value ->
            "${formatBobcoins(used)} / ${formatBobcoins(value)} Bobcoins"
        } ?: "${formatBobcoins(used)} Bobcoins used"
        val teamSummary = if (size == 1) "1 team" else "$size teams"
        val tier = (plans + teamSummary + usageSummary).joinToString(" · ")

        return QuotaInfo(
            service = AiService.IBM_BOB,
            windows = if (utilization != null) {
                listOf(
                    UsageWindow(
                        label = "Monthly Bobcoins",
                        utilization = utilization,
                        resetsAt = resetsAt,
                        windowDurationSeconds = MONTH_SECONDS
                    )
                )
            } else {
                emptyList()
            },
            extraUsage = if (limit != null && utilization != null) {
                ExtraUsage(
                    isEnabled = true,
                    monthlyLimit = limit,
                    usedCredits = used,
                    utilization = utilization,
                    currency = "Bobcoins"
                )
            } else {
                null
            },
            tier = tier,
            fetchedAt = Instant.now()
        )
    }

    private fun <T> Response<T>.errorOrNull(): AppError? = when (code()) {
        in 200..299 -> null
        401, 403 -> AppError.AuthError(AiService.IBM_BOB, isTerminal = true)
        429 -> AppError.RateLimited(RetryAfter.parseRetryAt(headers()["Retry-After"]))
        in 500..599 -> AppError.ServiceUnavailable
        else -> AppError.NetworkError("HTTP ${code()}: ${message()}")
    }

    private fun Credential.ProviderSecretCredential.apiKeyOrNull(): String? {
        if (service != AiService.IBM_BOB || kind != ProviderSecretKind.API_KEY) return null
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
        AppError.AuthError(AiService.IBM_BOB, isTerminal = true)
    )

    private data class TeamUsage(
        val planName: String?,
        val used: Double,
        val limit: Double?,
        val resetsAt: Instant?
    )

    private class ResponseFailure(val appError: AppError) : Exception()

    private companion object {
        const val MAX_API_KEY_LENGTH = 4_096
        const val MAX_PROFILE_INSTANCES = 50
        const val MAX_PROFILE_TEAMS = 200
        const val MONTH_SECONDS = 30L * 24L * 60L * 60L
    }
}

internal fun trustedIbmBobRegionBaseUrl(regionDomain: String?): HttpUrl {
    val normalized = regionDomain.nonEmptyOrNull() ?: DEFAULT_IBM_BOB_BASE_HOST
    require(normalized.length <= MAX_HOST_LENGTH) { "IBM Bob regional host is too long" }
    require(normalized.none(Char::isWhitespace) && normalized.none(Char::isISOControl)) {
        "IBM Bob returned an invalid regional host"
    }
    require(normalized.none { it in FORBIDDEN_HOST_CHARACTERS }) {
        "IBM Bob returned an invalid regional host"
    }
    val host = (if (normalized.startsWith("api.", ignoreCase = true)) {
        normalized
    } else {
        "api.$normalized"
    }).lowercase(Locale.ROOT)
    require(host == "bob.ibm.com" || host.endsWith(".bob.ibm.com")) {
        "IBM Bob returned an untrusted regional host"
    }
    require(host.split('.').all { label ->
        label.isNotEmpty() &&
            label.length <= 63 &&
            !label.startsWith('-') &&
            !label.endsWith('-') &&
            label.all { it.isLetterOrDigit() || it == '-' }
    }) { "IBM Bob returned an invalid regional host" }
    val url = "https://$host/".toHttpUrlOrNull()
        ?: error("IBM Bob returned an invalid regional host")
    require(
        url.isHttps &&
            url.host == host &&
            url.port == 443 &&
            url.username.isEmpty() &&
            url.password.isEmpty() &&
            url.encodedPath == "/" &&
            url.query == null &&
            url.fragment == null
    ) { "IBM Bob returned an invalid regional host" }
    return url
}

internal fun isIbmBobJwt(token: String): Boolean {
    val parts = token.split('.')
    if (parts.size != 3) return false
    return runCatching {
        val payload = parts[1] + "=".repeat((4 - parts[1].length % 4) % 4)
        val decoded = Base64.getUrlDecoder().decode(payload)
        Json.parseToJsonElement(String(decoded, StandardCharsets.UTF_8)) is JsonObject
    }.getOrDefault(false)
}

private fun String?.nonEmptyOrNull(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

private fun String?.safeIdentifierOrNull(): String? = this?.trim()?.takeIf {
    it.isNotEmpty() &&
        it.length <= 512 &&
        it.none(Char::isISOControl)
}

private fun parseRefreshAt(value: kotlinx.serialization.json.JsonPrimitive?): Instant? {
    if (value == null) return null
    return if (value.isString) {
        runCatching { Instant.parse(value.content.trim()) }.getOrNull()
    } else {
        value.content.toDoubleOrNull()
            ?.takeIf { it.isFinite() && it > 0.0 }
            ?.let { seconds ->
                runCatching { Instant.ofEpochMilli((seconds * 1_000.0).toLong()) }.getOrNull()
            }
    }
}

private fun formatBobcoins(value: Double): String = if (value % 1.0 == 0.0) {
    String.format(Locale.US, "%.0f", value)
} else {
    String.format(Locale.US, "%.2f", value)
}

private const val DEFAULT_IBM_BOB_BASE_HOST = "api.us-east.bob.ibm.com"
private const val MAX_HOST_LENGTH = 253
private val FORBIDDEN_HOST_CHARACTERS = setOf('/', '\\', ':', '@', '?', '#', '[', ']')
