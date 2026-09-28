package com.codexbar.android.core.widget

import com.codexbar.android.core.domain.model.AiService
import com.codexbar.android.core.presentation.compactModelQuotaLabel
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Stable IDs are persisted; never use enum ordinals for a widget's appearance. */
enum class WidgetTemplate {
    LEDGER, METERS, COLUMNS, TILES, RINGS, SEGMENTS, VERTICAL, FOCUS, DUAL, RESET, DUAL_SEGMENTS, SEGMENTS_DUAL;

    companion object {
        fun fromId(id: String?): WidgetTemplate = entries.firstOrNull { it.name == id } ?: LEDGER
    }
}

enum class WidgetForeground { LIGHT, DARK }

data class WidgetStyle(
    val template: WidgetTemplate = WidgetTemplate.LEDGER,
    val backgroundRgb: Int = 0x303238,
    val opacity: Int = 68,
    val foreground: WidgetForeground = WidgetForeground.LIGHT,
    val cornerRadius: Int = 16,
    val fontScale: Float = 1f,
    val providerColors: Map<AiService, Int> = emptyMap(),
    val showSecondary: Boolean = true
) {
    val backgroundArgb: Int get() = ((opacity.coerceIn(0, 100) * 255 / 100) shl 24) or (backgroundRgb and 0xFFFFFF)
    val foregroundArgb: Int get() = if (foreground == WidgetForeground.LIGHT) 0xFFF4F5F7.toInt() else 0xFF181A20.toInt()
    fun accent(service: AiService): Int = 0xFF000000.toInt() or
        (providerColors[service] ?: when (service) {
            AiService.CODEX -> 0x79DABC
            AiService.COPILOT -> 0xB9BDF4
            AiService.CLAUDE -> 0xEDB48F
            else -> service.brandColor.toInt()
        } and 0xFFFFFF)

    fun normalized() = copy(opacity = opacity.coerceIn(0, 100),
        backgroundRgb = backgroundRgb and 0xFFFFFF, cornerRadius = cornerRadius.coerceIn(0, 28),
        fontScale = if (fontScale.isFinite()) fontScale.coerceIn(0.8f, 1.2f) else 1f)
}

/** Epoch based countdowns are recomputed at every render, including resizes. */
internal fun widgetCountdown(resetsAt: Long?, now: Long): String? {
    resetsAt ?: return null
    if (resetsAt <= now) return "0m"
    val minutes = ceil((resetsAt - now) / 60.0).toLong()
    return when {
        minutes >= 1440 -> "${minutes / 1440}d ${minutes % 1440 / 60}h"
        minutes >= 60 -> "${minutes / 60}h ${minutes % 60}m"
        else -> "${minutes}m"
    }
}

internal fun widgetPercent(remaining: Float?): String = remaining
    ?.takeIf { it.isFinite() }?.let { "${(it.coerceIn(0f, 1f) * 100).roundToInt()}%" } ?: "—"

internal fun widgetWindowLabel(label: String): String {
    compactModelQuotaLabel(label)?.let { return it }
    val key = label.lowercase(Locale.ROOT)
    return when {
        "5-hour" in key || "5 hour" in key || "5h" in key || "5時間" in key || "session" in key -> "5h"
        "week" in key || "7-day" in key || "週間" in key || "週" in key -> "7d"
        "month" in key || "月" in key || "premium" in key -> "1mo"
        "day" in key || "daily" in key || "日次" in key -> "1d"
        else -> label.take(12)
    }
}
