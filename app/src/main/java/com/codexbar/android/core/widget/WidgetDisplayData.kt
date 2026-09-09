package com.codexbar.android.core.widget

import com.codexbar.android.core.domain.model.AccountConnection
import com.codexbar.android.core.domain.model.AiService

internal data class WidgetMetric(
    val label: String,
    val remaining: Float?,
    val reset: String?,
    val pace: String?,
    val resetAdvice: String? = null
) {
    val percent: String get() = widgetPercent(remaining)
    val shortLabel: String get() = widgetWindowLabel(label)
}

internal data class WidgetProvider(
    val connectionId: String,
    val service: AiService,
    val name: String,
    val metrics: List<WidgetMetric>,
    val status: String?,
    val message: String,
    val age: String?
) {
    val primary: WidgetMetric? get() = metrics.minByOrNull { it.remaining ?: Float.MAX_VALUE }
    val secondary: WidgetMetric? get() = metrics.firstOrNull { it != primary }
    val needsAttention: Boolean get() = status != null && status != "Fresh"
}

internal fun WidgetPrefsManager.displayData(
    config: WidgetDisplayConfig,
    connections: List<AccountConnection>,
    waiting: String,
    now: Long = System.currentTimeMillis() / 1000
): List<WidgetProvider> = connections.map { connection ->
    val updated = getCachedUpdatedAt(connection.id) / 1000
    val ageMinutes = ((now - updated).coerceAtLeast(0) / 60)
    WidgetProvider(connection.id, connection.service, connection.widgetName(),
        selectWidgetMetrics(getCachedLabels(connection.id).map { label ->
            WidgetMetric(label, getCachedRemainingFraction(connection.id, label),
                widgetCountdown(getCachedResetsAt(connection.id, label), now), getCachedPaceLabel(connection.id, label), getCachedResetPlanLabel(connection.id, label))
        }, config.maxRows), getCachedStatus(connection.id), getCachedStatusMessage(connection.id) ?: waiting,
        if (updated <= 0) null else when {
            ageMinutes >= 1440 -> "${ageMinutes / 1440}d"
            ageMinutes >= 60 -> "${ageMinutes / 60}h"
            else -> "${ageMinutes}m"
        })
}

private fun AccountConnection.widgetName(): String =
    if (service == AiService.COPILOT && name == service.displayName) "Copilot" else name

internal fun selectWidgetMetrics(metrics: List<WidgetMetric>, maximum: Int): List<WidgetMetric> =
    metrics.sortedBy { it.remaining ?: Float.MAX_VALUE }.take(maximum.coerceAtLeast(1))
