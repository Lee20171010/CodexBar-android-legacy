package com.codexbar.android.core.presentation

import com.codexbar.android.core.domain.model.AiService
import com.codexbar.android.core.domain.model.AccountConnection
import java.time.Instant

data class QuotaPresentationSnapshot(
    val generatedAt: Instant,
    val services: List<ServiceQuotaPresentation>,
    val source: RefreshSourcePresentation = RefreshSourcePresentation.Unknown,
    val privacy: PrivacyPresentation = PrivacyPresentation()
)

data class ServiceQuotaPresentation(
    val service: AiService,
    val accountLabel: String?,
    val tier: String?,
    val status: ServiceQuotaStatus,
    val primaryMetric: QuotaMetricPresentation?,
    val metrics: List<QuotaMetricPresentation>,
    val extraUsage: ExtraUsagePresentation?,
    val insights: List<ServiceInsightPresentation> = emptyList(),
    val freshness: FreshnessPresentation,
    val supportedActions: Set<QuotaAction>,
    val codexResetCredits: CodexResetCreditsPresentation? = null,
    val codexTelemetry: CodexTelemetryPresentation? = null,
    val connection: AccountConnection = AccountConnection.legacy(service)
)

data class CodexResetCreditsPresentation(
    val availableCount: Int,
    val availableLabel: String,
    val nextExpiryLabel: String?,
    val expiryLabels: List<String>,
    val noExpiryCount: Int
)

data class CodexTelemetryPresentation(
    val generatedAt: Instant,
    val currentContext: CodexContextPresentation?,
    val tokenUsage: CodexTokenUsagePresentation
)

data class CodexContextPresentation(
    val model: String,
    val usedTokens: Long,
    val contextWindowTokens: Long,
    val sessionTokens: Long,
    val usedFraction: Float,
    val usedPercent: Int,
    val usageLabel: String,
    val capturedAt: Instant
)

data class CodexTokenUsagePresentation(
    val today: CodexTokenTotalsPresentation,
    val last7Days: CodexTokenTotalsPresentation,
    val last30Days: CodexTokenTotalsPresentation,
    val daily: List<CodexDailyTokenPresentation>,
    val models: List<CodexModelTokenPresentation>
)

data class CodexTokenTotalsPresentation(
    val totalTokens: Long,
    val totalLabel: String,
    val inputLabel: String,
    val cachedInputLabel: String,
    val outputLabel: String,
    val reasoningOutputLabel: String
)

data class CodexDailyTokenPresentation(
    val date: java.time.LocalDate,
    val totalTokens: Long,
    val totalLabel: String
)

data class CodexModelTokenPresentation(
    val model: String,
    val totalTokens: Long,
    val totalLabel: String,
    val shareFraction: Float
)

data class ServiceInsightPresentation(
    val title: String,
    val message: String
)

data class QuotaMetricPresentation(
    val id: String,
    val label: String,
    val usedFraction: Double?,
    val remainingFraction: Double?,
    val usedPercent: Int?,
    val remainingPercent: Int?,
    val usedLabel: String,
    val remainingLabel: String,
    val barProgress: Float,
    val severity: QuotaSeverity,
    val resetsAt: Instant?,
    val resetLabel: String?,
    val pace: PacePresentation,
    val resetPlan: QuotaResetPlanPresentation? = null,
    val history: QuotaHistoryPresentation = QuotaHistoryPresentation()
)

data class QuotaHistoryPresentation(
    val points: List<QuotaHistoryPointPresentation> = emptyList()
)

data class QuotaHistoryPointPresentation(
    val capturedAt: Instant,
    val usedFraction: Float,
    val startsNewCycle: Boolean
)

data class QuotaResetPlanPresentation(
    val action: ResetPlanAction,
    val deadlineLabel: String,
    val budgetLabel: String,
    val actionLabel: String,
    val compactActionLabel: String
)

data class ExtraUsagePresentation(
    val label: String,
    val usedCreditsLabel: String,
    val limitLabel: String,
    val remainingLabel: String,
    val utilizationFraction: Double,
    val severity: QuotaSeverity
)

data class FreshnessPresentation(
    val fetchedAt: Instant?,
    val ageLabel: String,
    val state: FreshnessState,
    val staleReason: String? = null,
    val nextRetryAt: Instant? = null
)

data class PacePresentation(
    val state: PaceState,
    val label: String,
    val cycleProgressLabel: String? = null,
    val usageRateLabel: String? = null,
    val paceMultiplierLabel: String? = null,
    val reserveLabel: String? = null,
    val forecastLabel: String? = null
)

data class PrivacyPresentation(
    val redactSensitiveValues: Boolean = false,
    val lockScreenRedacted: Boolean = false,
    val widgetRedacted: Boolean = false
)

sealed class RefreshSourcePresentation {
    data object Unknown : RefreshSourcePresentation()
    data class Trigger(val name: String) : RefreshSourcePresentation()
}

enum class ServiceQuotaStatus {
    Fresh,
    Stale,
    Loading,
    AuthRequired,
    RateLimited,
    Offline,
    ProviderError,
    Disconnected,
    Redacted
}

enum class FreshnessState {
    Fresh,
    Stale,
    Unknown,
    Error,
    RateLimited
}

enum class PaceState {
    Unknown,
    CollectingHistory,
    OnTrack,
    AtRisk,
    Exhausting
}

enum class QuotaSeverity {
    Good,
    Warning,
    Critical,
    Unknown,
    Redacted
}

enum class QuotaAction {
    OpenDashboard,
    Refresh,
    StartMonitoring,
    StopMonitoring,
    Reauthenticate,
    Disconnect,
    ConfigureWidget
}
