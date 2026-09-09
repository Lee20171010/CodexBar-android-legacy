package com.codexbar.android.core.widget

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.ContextCompat
import com.codexbar.android.R
import com.codexbar.android.core.domain.model.AiService
import com.codexbar.android.core.domain.model.AccountConnection
import com.codexbar.android.core.presentation.QuotaMetricPresentation
import com.codexbar.android.core.presentation.ServiceQuotaPresentation
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class WidgetDisplayConfig(
    val connectionIds: List<String> = emptyList(),
    val showReset: Boolean = true,
    val showPace: Boolean = true,
    val showFreshness: Boolean = true,
    val maxRows: Int = 4,
    val style: WidgetStyle = WidgetStyle()
)

/**
 * Manages per-widget configuration (selected services) and cached quota data.
 * Uses plain SharedPreferences (not encrypted) since widget data is non-sensitive display info.
 */
@Singleton
class WidgetPrefsManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // --- Per-widget service selection ---

    fun saveWidgetConfig(appWidgetId: Int, config: WidgetDisplayConfig) {
        val prefix = "widget_${appWidgetId}"
        val style = config.style.normalized()
        prefs.edit()
            .putString("${prefix}_services_order", config.connectionIds.distinct().joinToString(","))
            .putStringSet("${prefix}_services", config.connectionIds.toSet())
            .putBoolean("${prefix}_show_reset", config.showReset)
            .putBoolean("${prefix}_show_pace", config.showPace)
            .putBoolean("${prefix}_show_freshness", config.showFreshness)
            .putInt("${prefix}_max_rows", config.maxRows.coerceIn(1, 12))
            .putString("${prefix}_template", style.template.name)
            .putInt("${prefix}_background", style.backgroundRgb)
            .putInt("${prefix}_opacity", style.opacity)
            .putString("${prefix}_foreground", style.foreground.name)
            .putInt("${prefix}_radius", style.cornerRadius)
            .putFloat("${prefix}_font_scale", style.fontScale)
            .putBoolean("${prefix}_secondary", style.showSecondary)
            .putString("${prefix}_colors", style.providerColors.entries.joinToString(",") { "${it.key.name}:${it.value}" })
            .commit()
    }

    fun getWidgetConfig(appWidgetId: Int): WidgetDisplayConfig {
        val prefix = "widget_${appWidgetId}"
        val orderedServices = prefs.getString("${prefix}_services_order", null)
            ?.split(",")
            ?.filter { it.isNotBlank() }
            ?: prefs.getStringSet("${prefix}_services", null)
                ?.sortedBy { name -> AiService.entries.indexOfFirst { it.name == name } }
            ?: emptyList()

        return WidgetDisplayConfig(
            connectionIds = orderedServices.distinct(),
            showReset = prefs.getBoolean("${prefix}_show_reset", true),
            showPace = prefs.getBoolean("${prefix}_show_pace", true),
            showFreshness = prefs.getBoolean("${prefix}_show_freshness", true),
            maxRows = prefs.getInt("${prefix}_max_rows", 4).coerceIn(1, 12),
            style = WidgetStyle(
                template = WidgetTemplate.fromId(prefs.getString("${prefix}_template", null)),
                backgroundRgb = prefs.getInt("${prefix}_background", 0x303238),
                opacity = prefs.getInt("${prefix}_opacity", 68),
                foreground = WidgetForeground.entries.firstOrNull {
                    it.name == prefs.getString("${prefix}_foreground", null)
                } ?: WidgetForeground.LIGHT,
                cornerRadius = prefs.getInt("${prefix}_radius", 16),
                fontScale = prefs.getFloat("${prefix}_font_scale", 1f),
                showSecondary = prefs.getBoolean("${prefix}_secondary", true),
                providerColors = prefs.getString("${prefix}_colors", "").orEmpty().split(",")
                    .mapNotNull { entry ->
                        val parts = entry.split(":")
                        val service = parts.firstOrNull()?.toAiServiceOrNull()
                        val rgb = parts.getOrNull(1)?.toIntOrNull()
                        if (service != null && rgb != null) service to (rgb and 0xFFFFFF) else null
                    }.toMap()
            ).normalized()
        )
    }

    fun deleteWidgetConfig(appWidgetId: Int) {
        val editor = prefs.edit()
        prefs.all.keys
            .filter { it.startsWith("widget_${appWidgetId}_") }
            .forEach { editor.remove(it) }
        editor.apply()
    }

    fun recordRender(appWidgetId: Int, stage: String, size: String, reason: String = "") {
        prefs.edit()
            .putString("widget_${appWidgetId}_render", "$stage | $size | $reason")
            .putLong("widget_${appWidgetId}_render_at", System.currentTimeMillis())
            .apply()
    }

    fun renderDiagnostics(appWidgetId: Int): String? {
        val state = prefs.getString("widget_${appWidgetId}_render", null) ?: return null
        val time = prefs.getLong("widget_${appWidgetId}_render_at", 0L)
        val timestamp = java.text.DateFormat.getDateTimeInstance().format(java.util.Date(time))
        return "#$appWidgetId · $timestamp\n$state"
    }

    fun deleteServiceCache(service: AiService) {
        deleteConnectionCache(AccountConnection.legacy(service))
    }

    fun deleteConnectionCache(connection: AccountConnection) {
        val prefix = "cache_${connection.id}_"
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(prefix) }.forEach { editor.remove(it) }
        editor.apply()
    }

    fun deleteAllServiceCaches() {
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith("cache_") }.forEach { editor.remove(it) }
        editor.apply()
    }

    // --- Cached quota data for widgets ---

    fun cacheQuotaData(service: AiService, label: String, utilization: Double, resetsAtEpochSecond: Long?) {
        val prefix = "cache_${service.name}"
        prefs.edit()
            .putString("${prefix}_labels", getCachedLabels(service.name).plus(label).joinToString(","))
            .putFloat("${prefix}_${label}_util", utilization.toFloat())
            .apply {
                if (resetsAtEpochSecond != null) {
                    putLong("${prefix}_${label}_resets", resetsAtEpochSecond)
                } else {
                    remove("${prefix}_${label}_resets")
                }
            }
            .apply()
    }

    fun cacheAllQuotaData(service: AiService, windows: List<Triple<String, Double, Long?>>) {
        cacheAllQuotaData(service.name, windows)
    }

    fun cacheAllQuotaData(connectionId: String, windows: List<Triple<String, Double, Long?>>) {
        val prefix = "cache_${connectionId}"
        val editor = prefs.edit()
        // Clear old cache for this service
        prefs.all.keys.filter { it.startsWith("${prefix}_") }.forEach { editor.remove(it) }

        val labels = windows.map { it.first }
        editor.putString("${prefix}_labels", labels.joinToString(","))
        for ((label, utilization, resetsAt) in windows) {
            editor.putFloat("${prefix}_${label}_util", utilization.toFloat())
            if (resetsAt != null) {
                editor.putLong("${prefix}_${label}_resets", resetsAt)
            }
        }
        editor.putLong("${prefix}_updated_at", System.currentTimeMillis())
        editor.apply()
    }

    fun cachePresentation(service: ServiceQuotaPresentation) {
        val prefix = "cache_${service.connection.id}"
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith("${prefix}_") }.forEach { editor.remove(it) }

        val labels = service.metrics.map { it.label }
        editor.putString("${prefix}_labels", labels.joinToString(","))
        editor.putLong("${prefix}_updated_at", service.freshness.fetchedAt?.toEpochMilli() ?: 0L)
        editor.putString("${prefix}_status", service.status.name)
        editor.putString("${prefix}_freshness", service.freshness.ageLabel)
        service.freshness.staleReason?.let {
            editor.putString("${prefix}_status_message", it)
        }

        service.tier?.let { editor.putString("${prefix}_tier", it) }
        if (service.metrics.isEmpty()) {
            val message = service.tier
                ?: ContextCompat.getContextForLanguage(context)
                    .getString(R.string.widget_no_bounded_quota)
            editor.putString("${prefix}_status_message", message)
        }

        for (metric in service.metrics) {
            editor.cacheMetric(prefix, metric)
        }
        editor.apply()
    }

    private fun SharedPreferences.Editor.cacheMetric(prefix: String, metric: QuotaMetricPresentation) {
        val label = metric.label
        putFloat("${prefix}_${label}_util", (metric.usedFraction ?: 0.0).toFloat())
        putBoolean("${prefix}_${label}_bounded", metric.remainingFraction?.isFinite() == true)
        metric.remainingFraction?.takeIf { it.isFinite() }?.let {
            putFloat("${prefix}_${label}_remaining", it.toFloat())
        }
        putFloat("${prefix}_${label}_bar", metric.barProgress)
        putString("${prefix}_${label}_remaining_label", metric.remainingLabel)
        putString("${prefix}_${label}_used_label", metric.usedLabel)
        putString("${prefix}_${label}_reset_label", metric.resetLabel)
        putString("${prefix}_${label}_pace_label", metric.pace.label)
        putString("${prefix}_${label}_reset_plan_label", metric.resetPlan?.compactActionLabel)
        putString("${prefix}_${label}_severity", metric.severity.name)
        metric.resetsAt?.let { putLong("${prefix}_${label}_resets", it.epochSecond) }
    }

    fun getCachedLabels(connectionId: String): List<String> {
        val raw = prefs.getString("cache_${connectionId}_labels", null) ?: return emptyList()
        return raw.split(",").filter { it.isNotEmpty() }
    }

    fun getCachedUtilization(connectionId: String, label: String): Float {
        return prefs.getFloat("cache_${connectionId}_${label}_util", 0f)
    }

    fun getCachedRemainingFraction(connectionId: String, label: String): Float? {
        val prefix = "cache_${connectionId}_${label}"
        if (prefs.contains("${prefix}_bounded")) {
            return if (prefs.getBoolean("${prefix}_bounded", false))
                prefs.getFloat("${prefix}_remaining", Float.NaN).takeIf { it.isFinite() } else null
        }
        // Migrate old caches only when they explicitly described a bounded quota.
        if (getCachedSeverity(connectionId, label) in listOf(null, "Unknown", "Redacted")) return null
        return (1f - getCachedUtilization(connectionId, label)).coerceIn(0f, 1f)
    }

    fun getCachedStatus(connectionId: String): String? = prefs.getString("cache_${connectionId}_status", null)

    fun getCachedBarProgress(connectionId: String, label: String): Float {
        return prefs.getFloat("cache_${connectionId}_${label}_bar", (1f - getCachedUtilization(connectionId, label)).coerceIn(0f, 1f))
    }

    fun getCachedRemainingLabel(connectionId: String, label: String): String {
        return prefs.getString("cache_${connectionId}_${label}_remaining_label", null)
            ?: ContextCompat.getContextForLanguage(context).getString(
                R.string.quota_remaining_percent,
                ((1f - getCachedUtilization(connectionId, label)) * 100).toInt().coerceIn(0, 100)
            )
    }

    fun getCachedResetLabel(connectionId: String, label: String): String? {
        return prefs.getString("cache_${connectionId}_${label}_reset_label", null)
    }

    fun getCachedPaceLabel(connectionId: String, label: String): String? {
        return prefs.getString("cache_${connectionId}_${label}_pace_label", null)
    }

    fun getCachedResetPlanLabel(connectionId: String, label: String): String? {
        return prefs.getString("cache_${connectionId}_${label}_reset_plan_label", null)
    }

    fun getCachedSeverity(connectionId: String, label: String): String? {
        return prefs.getString("cache_${connectionId}_${label}_severity", null)
    }

    fun getCachedFreshness(connectionId: String): String? {
        return prefs.getString("cache_${connectionId}_freshness", null)
    }

    fun getCachedStatusMessage(connectionId: String): String? {
        return prefs.getString("cache_${connectionId}_status_message", null)
    }

    fun cacheStatusMessageIfEmpty(service: AiService, message: String) {
        cacheStatusMessageIfEmpty(AccountConnection.legacy(service), message)
    }

    fun cacheStatusMessageIfEmpty(connection: AccountConnection, message: String) {
        val prefix = "cache_${connection.id}"
        if (!prefs.getString("${prefix}_labels", null).isNullOrEmpty()) return
        prefs.edit()
            .putString("${prefix}_status_message", message)
            .putLong("${prefix}_updated_at", System.currentTimeMillis())
            .apply()
    }

    fun getCachedResetsAt(connectionId: String, label: String): Long? {
        val value = prefs.getLong("cache_${connectionId}_${label}_resets", -1L)
        return if (value > 0) value else null
    }

    fun getCachedUpdatedAt(connectionId: String): Long {
        return prefs.getLong("cache_${connectionId}_updated_at", 0L)
    }

    /** Returns the highest utilization across all cached windows for this service. */
    fun getMaxCachedUtilization(connectionId: String): Float {
        val labels = getCachedLabels(connectionId)
        if (labels.isEmpty()) return 0f
        return labels.maxOf { getCachedUtilization(connectionId, it) }
    }

    fun cacheTier(service: AiService, tier: String?) {
        val key = "cache_${service.name}_tier"
        if (tier != null) {
            prefs.edit().putString(key, tier).apply()
        } else {
            prefs.edit().remove(key).apply()
        }
    }

    fun getCachedTier(connectionId: String): String? {
        return prefs.getString("cache_${connectionId}_tier", null)
    }

    private fun String.toAiServiceOrNull(): AiService? {
        return try {
            AiService.valueOf(this)
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val PREFS_NAME = "codexbar_widget_prefs"
        const val BACKUP_PATH = "$PREFS_NAME.xml"
    }
}
