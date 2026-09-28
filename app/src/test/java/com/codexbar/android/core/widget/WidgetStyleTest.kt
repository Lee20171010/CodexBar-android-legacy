package com.codexbar.android.core.widget

import org.junit.Assert.*
import org.junit.Test

class WidgetStyleTest {
    @Test fun `compact model names retain family version and reasoning variant`() {
        assertEquals("G3.1 Pro H", widgetWindowLabel("Gemini 3.1 Pro (High)"))
        assertEquals("G3.1 Pro L", widgetWindowLabel("Gemini 3.1 Pro (Low)"))
        assertEquals("G3.6 Fl M", widgetWindowLabel("Gemini 3.6 Flash (Medium)"))
        assertEquals("S4.6 Think", widgetWindowLabel("Claude Sonnet 4.6 (Thinking)"))
        assertEquals("O4.6 Think", widgetWindowLabel("Claude Opus 4.6 (Thinking)"))
        assertEquals("GPT 120B M", widgetWindowLabel("GPT-OSS 120B (Medium)"))
        assertEquals("5h", widgetWindowLabel("5-Hour"))
    }
    @Test fun `invalid stored styles safely migrate to the default`() {
        assertEquals(WidgetTemplate.LEDGER, WidgetTemplate.fromId("obsolete"))
        assertEquals(WidgetTemplate.RINGS, WidgetTemplate.fromId("RINGS"))
        val style = WidgetStyle(opacity = 900, cornerRadius = -1, fontScale = Float.NaN).normalized()
        assertEquals(100, style.opacity)
        assertEquals(0, style.cornerRadius)
        assertEquals(1f, style.fontScale)
        assertEquals(0, WidgetStyle(opacity = 0).backgroundArgb ushr 24)
        assertEquals(255, style.backgroundArgb ushr 24)
    }

    @Test fun `missing or unbounded usage never becomes a full remaining quota`() {
        assertEquals("—", widgetPercent(null))
        assertEquals("—", widgetPercent(Float.NaN))
        assertEquals("0%", widgetPercent(0f))
        assertEquals("62%", widgetPercent(.62f))
        assertEquals("100%", widgetPercent(1.2f))
    }

    @Test fun `countdowns recalculate with time and never go negative`() {
        assertNull(widgetCountdown(null, 100))
        assertEquals("0m", widgetCountdown(99, 100))
        assertEquals("1m", widgetCountdown(101, 100))
        assertEquals("2h 18m", widgetCountdown(8380, 100))
        assertEquals("3d 4h", widgetCountdown(273700, 100))
    }
}
