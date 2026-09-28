package com.codexbar.android.core.domain.model

enum class ProviderCategory {
    CODING,
    MODEL_API,
    ROUTER,
    MEDIA
}

enum class ProviderAuthMode {
    DEVICE_SIGN_IN,
    LOCAL_COMPANION,
    API_KEY,
    SESSION_COOKIE,
    ACCESS_TOKEN
}

data class ProviderMetadata(
    val category: ProviderCategory,
    val authMode: ProviderAuthMode,
    val aliases: Set<String>,
    val guideAnchor: String,
    val recommended: Boolean = false,
    val requiresAccountReference: Boolean = false
) {
    val secretKind: ProviderSecretKind?
        get() = when (authMode) {
            ProviderAuthMode.API_KEY -> ProviderSecretKind.API_KEY
            ProviderAuthMode.SESSION_COOKIE -> ProviderSecretKind.COOKIE_HEADER
            else -> null
        }
}

object ProviderCatalog {
    private val metadataByService = mapOf(
        AiService.ANTIGRAVITY to ProviderMetadata(
            category = ProviderCategory.CODING,
            authMode = ProviderAuthMode.LOCAL_COMPANION,
            aliases = setOf("google", "antigravity ide", "agy"),
            guideAnchor = "antigravity",
            recommended = true
        ),
        AiService.CLAUDE to ProviderMetadata(
            category = ProviderCategory.CODING,
            authMode = ProviderAuthMode.LOCAL_COMPANION,
            aliases = setOf("anthropic", "claude code"),
            guideAnchor = "claude-anthropic",
            recommended = true
        ),
        AiService.CODEX to ProviderMetadata(
            category = ProviderCategory.CODING,
            authMode = ProviderAuthMode.DEVICE_SIGN_IN,
            aliases = setOf("openai", "chatgpt", "codex cli"),
            guideAnchor = "codex-openai--chatgpt",
            recommended = true
        ),
        AiService.GEMINI to ProviderMetadata(
            category = ProviderCategory.CODING,
            authMode = ProviderAuthMode.LOCAL_COMPANION,
            aliases = setOf("google", "gemini cli"),
            guideAnchor = "gemini-google",
            recommended = true
        ),
        AiService.COPILOT to ProviderMetadata(
            category = ProviderCategory.CODING,
            authMode = ProviderAuthMode.DEVICE_SIGN_IN,
            aliases = setOf("github", "vscode", "visual studio code"),
            guideAnchor = "github-copilot",
            recommended = true
        ),
        AiService.CURSOR to ProviderMetadata(
            category = ProviderCategory.CODING,
            authMode = ProviderAuthMode.SESSION_COOKIE,
            aliases = setOf("cursor editor", "cursor ide"),
            guideAnchor = "cursor"
        ),
        AiService.ZAI to ProviderMetadata(
            category = ProviderCategory.MODEL_API,
            authMode = ProviderAuthMode.API_KEY,
            aliases = setOf("zhipu", "glm", "bigmodel"),
            guideAnchor = "zai"
        ),
        AiService.ZENMUX to ProviderMetadata(
            category = ProviderCategory.ROUTER,
            authMode = ProviderAuthMode.API_KEY,
            aliases = setOf("zen mux", "model router", "gateway"),
            guideAnchor = "zenmux"
        ),
        AiService.KIMI to ProviderMetadata(
            category = ProviderCategory.CODING,
            authMode = ProviderAuthMode.API_KEY,
            aliases = setOf("kimi code", "moonshot ai coding"),
            guideAnchor = "kimi"
        ),
        AiService.ELEVENLABS to ProviderMetadata(
            category = ProviderCategory.MEDIA,
            authMode = ProviderAuthMode.API_KEY,
            aliases = setOf("eleven labs", "text to speech", "tts", "voice ai"),
            guideAnchor = "elevenlabs"
        ),
        AiService.OPENROUTER to ProviderMetadata(
            category = ProviderCategory.ROUTER,
            authMode = ProviderAuthMode.API_KEY,
            aliases = setOf("open router", "llm router", "model gateway"),
            guideAnchor = "openrouter"
        ),
        AiService.SYNTHETIC to ProviderMetadata(
            category = ProviderCategory.MODEL_API,
            authMode = ProviderAuthMode.API_KEY,
            aliases = setOf("synthetic new", "hf inference", "api quota"),
            guideAnchor = "synthetic"
        ),
        AiService.CHUTES to ProviderMetadata(
            category = ProviderCategory.MODEL_API,
            authMode = ProviderAuthMode.API_KEY,
            aliases = setOf("chutes ai", "bittensor", "serverless inference"),
            guideAnchor = "chutes"
        ),
        AiService.DEEPSEEK to ProviderMetadata(
            category = ProviderCategory.MODEL_API,
            authMode = ProviderAuthMode.API_KEY,
            aliases = setOf("deep seek", "deepseek api", "deepseek coder"),
            guideAnchor = "deepseek"
        ),
        AiService.VENICE to ProviderMetadata(
            category = ProviderCategory.MODEL_API,
            authMode = ProviderAuthMode.API_KEY,
            aliases = setOf("venice ai", "diem", "private inference"),
            guideAnchor = "venice"
        ),
        AiService.MOONSHOT to ProviderMetadata(
            category = ProviderCategory.MODEL_API,
            authMode = ProviderAuthMode.API_KEY,
            aliases = setOf("moonshot ai", "kimi api", "kimi k2", "international"),
            guideAnchor = "moonshot-api-international"
        ),
        AiService.CLINEPASS to ProviderMetadata(
            category = ProviderCategory.CODING,
            authMode = ProviderAuthMode.API_KEY,
            aliases = setOf("cline", "cline pass", "cline bot", "coding subscription"),
            guideAnchor = "cline"
        ),
        AiService.IBM_BOB to ProviderMetadata(
            category = ProviderCategory.CODING,
            authMode = ProviderAuthMode.API_KEY,
            aliases = setOf("ibm", "bob", "bob shell", "bobcoins", "bobshell"),
            guideAnchor = "ibm-bob"
        ),
        AiService.DEVIN to ProviderMetadata(
            category = ProviderCategory.CODING,
            authMode = ProviderAuthMode.API_KEY,
            aliases = setOf("cognition", "acu", "devin", "organization consumption"),
            guideAnchor = "devin",
            requiresAccountReference = true
        ),
        AiService.FIREWORKS to ProviderMetadata(
            category = ProviderCategory.MODEL_API,
            authMode = ProviderAuthMode.API_KEY,
            aliases = setOf("fireworks", "fireworks ai", "serverless inference", "billing spend"),
            guideAnchor = "fireworks-ai",
            requiresAccountReference = true
        )
    )

    init {
        require(metadataByService.keys == AiService.entries.toSet()) {
            "Provider metadata must cover every AiService"
        }
    }

    fun metadataFor(service: AiService): ProviderMetadata =
        checkNotNull(metadataByService[service]) { "Missing provider metadata for $service" }
}

val AiService.providerMetadata: ProviderMetadata
    get() = ProviderCatalog.metadataFor(this)
