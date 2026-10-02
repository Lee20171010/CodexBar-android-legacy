package com.codexbar.android.core.domain.model

enum class AiService(
    val displayName: String,
    val brandColor: Long,
    val baseUrl: String,
    val requiresManualCredentials: Boolean
) {
    CLAUDE(
        displayName = "Claude",
        brandColor = 0xFFD4A574,
        baseUrl = "codexbar://claude-companion/",
        requiresManualCredentials = false
    ),
    CODEX(
        displayName = "Codex",
        brandColor = 0xFF10A37F,
        baseUrl = "https://chatgpt.com/",
        requiresManualCredentials = false
    ),
    GEMINI(
        displayName = "Gemini",
        brandColor = 0xFF4285F4,
        baseUrl = "codexbar://gemini-companion/",
        requiresManualCredentials = false
    ),
    COPILOT(
        displayName = "GitHub Copilot",
        brandColor = 0xFF6E7681,
        baseUrl = "https://api.github.com/",
        requiresManualCredentials = false
    ),
    CURSOR(
        displayName = "Cursor",
        brandColor = 0xFF00A67E,
        baseUrl = "https://cursor.com/",
        requiresManualCredentials = true
    ),
    ZAI(
        displayName = "z.ai",
        brandColor = 0xFFE85A6A,
        baseUrl = "https://api.z.ai/",
        requiresManualCredentials = true
    ),
    ZENMUX(
        displayName = "ZenMux",
        brandColor = 0xFF6750A4,
        baseUrl = "https://zenmux.ai/api/v1/management/",
        requiresManualCredentials = true
    ),
    KIMI(
        displayName = "Kimi Code",
        brandColor = 0xFF00B8A9,
        baseUrl = "https://api.kimi.com/",
        requiresManualCredentials = true
    ),
    ELEVENLABS(
        displayName = "ElevenLabs",
        brandColor = 0xFF6C5CE7,
        baseUrl = "https://api.elevenlabs.io/",
        requiresManualCredentials = true
    ),
    OPENROUTER(
        displayName = "OpenRouter",
        brandColor = 0xFF4D6BFE,
        baseUrl = "https://openrouter.ai/api/v1/",
        requiresManualCredentials = true
    ),
    SYNTHETIC(
        displayName = "Synthetic",
        brandColor = 0xFFEF5DA8,
        baseUrl = "https://api.synthetic.new/",
        requiresManualCredentials = true
    ),
    CHUTES(
        displayName = "Chutes",
        brandColor = 0xFF21A179,
        baseUrl = "https://api.chutes.ai/",
        requiresManualCredentials = true
    ),
    DEEPSEEK(
        displayName = "DeepSeek",
        brandColor = 0xFF4D6BFE,
        baseUrl = "https://api.deepseek.com/",
        requiresManualCredentials = true
    ),
    VENICE(
        displayName = "Venice",
        brandColor = 0xFF7C3AED,
        baseUrl = "https://api.venice.ai/api/v1/",
        requiresManualCredentials = true
    ),
    MOONSHOT(
        displayName = "Moonshot API (International)",
        brandColor = 0xFF111827,
        baseUrl = "https://api.moonshot.ai/",
        requiresManualCredentials = true
    ),
    CLINEPASS(
        displayName = "Cline",
        brandColor = 0xFFF36F45,
        baseUrl = "https://api.cline.bot/",
        requiresManualCredentials = true
    ),
    IBM_BOB(
        displayName = "IBM Bob",
        brandColor = 0xFF0E61FA,
        baseUrl = "https://api.us-east.bob.ibm.com/",
        requiresManualCredentials = true
    ),
    FIREWORKS(
        displayName = "Fireworks AI",
        brandColor = 0xFFF45B3A,
        baseUrl = "https://api.fireworks.ai/",
        requiresManualCredentials = true
    ),
    DEVIN(
        displayName = "Devin",
        brandColor = 0xFF72BBEF,
        baseUrl = "https://api.devin.ai/",
        requiresManualCredentials = true
    ),
    ANTIGRAVITY(
        displayName = "Antigravity",
        brandColor = 0xFF4285F4,
        baseUrl = "codexbar://antigravity-companion/",
        requiresManualCredentials = false
    ),
    OPENCODE_GO(
        displayName = "OpenCode Go",
        brandColor = 0xFF2563EB,
        baseUrl = "https://opencode.ai/",
        requiresManualCredentials = true
    )
}
