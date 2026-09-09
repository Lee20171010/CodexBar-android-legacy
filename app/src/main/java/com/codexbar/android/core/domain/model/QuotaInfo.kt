package com.codexbar.android.core.domain.model

import java.time.Instant

data class QuotaInfo(
    val service: AiService,
    val windows: List<UsageWindow>,
    val extraUsage: ExtraUsage?,
    val tier: String? = null,
    val fetchedAt: Instant,
    val notices: Set<QuotaNotice> = emptySet(),
    val codexResetCredits: CodexResetCredits? = null,
    val codexTelemetry: CodexTelemetry? = null,
    val connection: AccountConnection = AccountConnection.legacy(service)
)

data class CodexResetCredits(
    val availableCount: Int,
    val expiresAt: List<Instant>
)

sealed interface QuotaNotice {
    data class WindowLimitNotProvided(
        val windowDurationSeconds: Long
    ) : QuotaNotice
}

data class UsageWindow(
    val label: String,
    val utilization: Double, // 0.0 ~ 1.0
    val resetsAt: Instant?,
    val windowDurationSeconds: Long? = null
)

data class ExtraUsage(
    val isEnabled: Boolean,
    val monthlyLimit: Double,
    val usedCredits: Double,
    val utilization: Double,
    val currency: String
)
