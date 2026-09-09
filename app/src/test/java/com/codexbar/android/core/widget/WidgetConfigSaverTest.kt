package com.codexbar.android.core.widget

import androidx.compose.runtime.saveable.SaverScope
import com.codexbar.android.core.domain.model.AiService
import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetConfigSaverTest {
    @Test fun `rotation restores an unapplied appearance and service order`() {
        val config = WidgetDisplayConfig(connectionIds = listOf(AiService.COPILOT.name, AiService.CODEX.name),
            showReset = false, showPace = false, showFreshness = false, maxRows = 2,
            style = WidgetStyle(template = WidgetTemplate.RINGS, backgroundRgb = 0xABCDEF,
                opacity = 37, foreground = WidgetForeground.DARK, cornerRadius = 8, fontScale = .9f,
                providerColors = mapOf(AiService.CODEX to 0x123456), showSecondary = false))
        val scope = object : SaverScope { override fun canBeSaved(value: Any) = true }
        WidgetTemplate.entries.forEach { template ->
            val themed = config.copy(style = config.style.copy(template = template))
            val saved = with(WidgetConfigSaver) { scope.save(themed) }!!
            assertEquals(themed, WidgetConfigSaver.restore(saved))
        }
    }
}
