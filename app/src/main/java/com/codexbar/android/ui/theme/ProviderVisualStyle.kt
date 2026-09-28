package com.codexbar.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import com.codexbar.android.core.domain.model.AiService

data class ProviderVisualStyle(
    val accent: Color,
    val onAccent: Color,
    val container: Color,
    val shape: Shape
)

@Composable
fun providerVisualStyle(service: AiService): ProviderVisualStyle {
    val colors = MaterialTheme.colorScheme
    val isDark = colors.surface.luminance() < 0.5f
    val accent = when (service) {
        AiService.ANTIGRAVITY -> if (isDark) Color(0xFFBBC3FF) else Color(0xFF3559C7)
        AiService.CLAUDE -> if (isDark) Color(0xFFFFB77A) else Color(0xFF8A4F17)
        AiService.CODEX -> if (isDark) Color(0xFF63DBB6) else Color(0xFF006B53)
        AiService.GEMINI -> if (isDark) Color(0xFFBBC3FF) else Color(0xFF3559C7)
        AiService.COPILOT -> if (isDark) Color(0xFFC6C5D0) else Color(0xFF555E6D)
        AiService.CURSOR -> if (isDark) Color(0xFF65DDB8) else Color(0xFF006B53)
        AiService.ZAI -> if (isDark) Color(0xFFFFB2B8) else Color(0xFF9F273C)
        AiService.ZENMUX -> if (isDark) Color(0xFFD0BCFF) else Color(0xFF6750A4)
        AiService.KIMI -> if (isDark) Color(0xFF6FE7D8) else Color(0xFF006B62)
        AiService.ELEVENLABS -> if (isDark) Color(0xFFC8BFFF) else Color(0xFF5545B5)
        AiService.OPENROUTER -> if (isDark) Color(0xFFB9C3FF) else Color(0xFF304BC0)
        AiService.SYNTHETIC -> if (isDark) Color(0xFFFFAFD7) else Color(0xFF9B2765)
        AiService.CHUTES -> if (isDark) Color(0xFF75DBB5) else Color(0xFF006B50)
        AiService.DEEPSEEK -> if (isDark) Color(0xFFB9C3FF) else Color(0xFF304BC0)
        AiService.VENICE -> if (isDark) Color(0xFFD0BCFF) else Color(0xFF6941C6)
        AiService.MOONSHOT -> if (isDark) Color(0xFFE2E8F0) else Color(0xFF334155)
        AiService.CLINEPASS -> if (isDark) Color(0xFFFFB59E) else Color(0xFF9B3818)
        AiService.IBM_BOB -> if (isDark) Color(0xFFB4C5FF) else Color(0xFF0050D8)
        AiService.DEVIN -> if (isDark) Color(0xFF72BBEF) else Color(0xFF176091)
        AiService.FIREWORKS -> if (isDark) Color(0xFFFFB5A0) else Color(0xFF9D3216)
    }
    val tintAlpha = if (isDark) 0.16f else 0.09f
    return ProviderVisualStyle(
        accent = accent,
        onAccent = if (accent.luminance() > 0.45f) Color(0xFF101014) else Color.White,
        container = accent.copy(alpha = tintAlpha).compositeOver(colors.surfaceContainerLow),
        shape = MaterialTheme.shapes.large
    )
}
