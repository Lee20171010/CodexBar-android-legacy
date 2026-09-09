package com.codexbar.android.core.widget

import androidx.compose.runtime.saveable.mapSaver
import com.codexbar.android.core.domain.model.AiService

/** Save an unfinished edit across rotation without applying it to the launcher. */
internal val WidgetConfigSaver = mapSaver(
    save = { config: WidgetDisplayConfig -> mapOf(
        "connections" to config.connectionIds.joinToString(","),
        "reset" to config.showReset, "pace" to config.showPace, "freshness" to config.showFreshness,
        "template" to config.style.template.name, "background" to config.style.backgroundRgb,
        "opacity" to config.style.opacity, "foreground" to config.style.foreground.name,
        "radius" to config.style.cornerRadius, "font" to config.style.fontScale,
        "secondary" to config.style.showSecondary,
        "colors" to config.style.providerColors.entries.joinToString(",") { "${it.key.name}:${it.value}" }
    ) },
    restore = { values -> WidgetDisplayConfig(
        connectionIds = (values["connections"] as String).split(",").filter { it.isNotEmpty() },
        showReset = values["reset"] as Boolean, showPace = values["pace"] as Boolean,
        showFreshness = values["freshness"] as Boolean, maxRows = 2,
        style = WidgetStyle(template = WidgetTemplate.fromId(values["template"] as String),
            backgroundRgb = values["background"] as Int, opacity = values["opacity"] as Int,
            foreground = WidgetForeground.valueOf(values["foreground"] as String),
            cornerRadius = values["radius"] as Int, fontScale = values["font"] as Float,
            showSecondary = values["secondary"] as Boolean,
            providerColors = (values["colors"] as String).split(",").mapNotNull { entry ->
                val parts = entry.split(":")
                val service = AiService.entries.firstOrNull { it.name == parts.firstOrNull() }
                val rgb = parts.getOrNull(1)?.toIntOrNull()
                if (service != null && rgb != null) service to rgb else null
            }.toMap()).normalized()
    ) }
)
