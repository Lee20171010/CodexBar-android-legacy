package com.codexbar.android.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.codexbar.android.R
import com.codexbar.android.core.domain.model.AiService

/** Bundled first-party artwork; provenance and notices are in docs/provider-icons.md. */
@DrawableRes
fun AiService.providerIcon(): Int = when (this) {
    AiService.ANTIGRAVITY -> R.drawable.provider_antigravity
    AiService.CLAUDE -> R.drawable.provider_claude
    AiService.CODEX -> R.drawable.provider_codex
    AiService.GEMINI -> R.drawable.provider_gemini
    AiService.COPILOT -> R.drawable.provider_copilot
    AiService.CURSOR -> R.drawable.provider_cursor
    AiService.ZAI -> R.drawable.provider_zai
    AiService.ZENMUX -> R.drawable.provider_zenmux
    AiService.KIMI -> R.drawable.provider_kimi
    AiService.ELEVENLABS -> R.drawable.provider_elevenlabs
    AiService.OPENROUTER -> R.drawable.provider_openrouter
    AiService.SYNTHETIC -> R.drawable.provider_synthetic
    AiService.CHUTES -> R.drawable.provider_chutes
    AiService.DEEPSEEK -> R.drawable.provider_deepseek
    AiService.VENICE -> R.drawable.provider_venice
    AiService.MOONSHOT -> R.drawable.provider_moonshot
    AiService.CLINEPASS -> R.drawable.provider_clinepass
    AiService.IBM_BOB -> R.drawable.provider_ibm_bob
    AiService.FIREWORKS -> R.drawable.provider_fireworks
    AiService.DEVIN -> R.drawable.provider_devin
    AiService.OPENCODE_GO -> R.drawable.provider_opencode_go
}

@Composable
fun ProviderIcon(service: AiService, modifier: Modifier = Modifier.size(24.dp)) {
    // A neutral light plate preserves the official colors and contrast in either app theme.
    Surface(modifier, shape = RoundedCornerShape(4.dp), color = Color.White) {
        Image(painterResource(service.providerIcon()), contentDescription = null,
            modifier = Modifier.padding(2.dp))
    }
}
