package com.codexbar.android.di

import com.codexbar.android.core.data.AntigravityRepositoryImpl
import com.codexbar.android.core.data.ClaudeRepositoryImpl
import com.codexbar.android.core.data.ChutesRepositoryImpl
import com.codexbar.android.core.data.ClinePassRepositoryImpl
import com.codexbar.android.core.data.CodexRepositoryImpl
import com.codexbar.android.core.data.CopilotRepositoryImpl
import com.codexbar.android.core.data.CursorRepositoryImpl
import com.codexbar.android.core.data.DeepSeekRepositoryImpl
import com.codexbar.android.core.data.ElevenLabsRepositoryImpl
import com.codexbar.android.core.data.GeminiRepositoryImpl
import com.codexbar.android.core.data.FireworksRepositoryImpl
import com.codexbar.android.core.data.DevinRepositoryImpl
import com.codexbar.android.core.data.KimiRepositoryImpl
import com.codexbar.android.core.data.IbmBobRepositoryImpl
import com.codexbar.android.core.data.MoonshotRepositoryImpl
import com.codexbar.android.core.data.OpenRouterRepositoryImpl
import com.codexbar.android.core.data.SyntheticRepositoryImpl
import com.codexbar.android.core.data.VeniceRepositoryImpl
import com.codexbar.android.core.data.ZaiRepositoryImpl
import com.codexbar.android.core.data.ZenMuxRepositoryImpl
import com.codexbar.android.core.domain.model.AiService
import com.codexbar.android.core.domain.repository.QuotaRepository
import com.codexbar.android.core.network.antigravity.AntigravityCompanionClient
import com.codexbar.android.core.network.claude.ClaudeCompanionClient
import com.codexbar.android.core.network.companion.LocalCompanionLocator
import com.codexbar.android.core.network.chutes.ChutesApiService
import com.codexbar.android.core.network.clinepass.ClinePassApiService
import com.codexbar.android.core.network.codex.CodexApiService
import com.codexbar.android.core.network.codex.CodexTokenRefreshService
import com.codexbar.android.core.network.codex.telemetry.CodexTelemetryClient
import com.codexbar.android.core.network.copilot.CopilotApiService
import com.codexbar.android.core.network.cursor.CursorApiService
import com.codexbar.android.core.network.deepseek.DeepSeekApiService
import com.codexbar.android.core.network.elevenlabs.ElevenLabsApiService
import com.codexbar.android.core.network.gemini.GeminiCompanionClient
import com.codexbar.android.core.network.fireworks.FireworksApiService
import com.codexbar.android.core.network.devin.DevinApiService
import com.codexbar.android.core.network.kimi.KimiApiService
import com.codexbar.android.core.network.ibmbob.IbmBobApiService
import com.codexbar.android.core.network.moonshot.MoonshotApiService
import com.codexbar.android.core.network.openrouter.OpenRouterApiService
import com.codexbar.android.core.network.synthetic.SyntheticApiService
import com.codexbar.android.core.network.venice.VeniceApiService
import com.codexbar.android.core.network.zai.ZaiApiService
import com.codexbar.android.core.network.zenmux.ZenMuxApiService
import com.codexbar.android.core.security.EncryptedPrefsManager
import com.codexbar.android.core.security.TokenRefreshCoordinator
import dagger.Module
import dagger.MapKey
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import javax.inject.Singleton

@MapKey
annotation class AiServiceKey(val value: AiService)

@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {

    @Provides
    @Singleton
    @IntoMap
    @AiServiceKey(AiService.ANTIGRAVITY)
    fun provideAntigravityRepository(
        companionClient: AntigravityCompanionClient,
        prefsManager: EncryptedPrefsManager,
        companionLocator: LocalCompanionLocator
    ): QuotaRepository = AntigravityRepositoryImpl(companionClient, prefsManager, companionLocator)

    @Provides
    @Singleton
    @IntoMap
    @AiServiceKey(AiService.CLAUDE)
    fun provideClaudeRepository(
        companionClient: ClaudeCompanionClient,
        prefsManager: EncryptedPrefsManager,
        companionLocator: LocalCompanionLocator
    ): QuotaRepository = ClaudeRepositoryImpl(companionClient, prefsManager, companionLocator)

    @Provides
    @Singleton
    @IntoMap
    @AiServiceKey(AiService.CODEX)
    fun provideCodexRepository(
        apiService: CodexApiService,
        tokenRefreshService: CodexTokenRefreshService,
        prefsManager: EncryptedPrefsManager,
        tokenRefreshCoordinator: TokenRefreshCoordinator,
        codexTelemetryClient: CodexTelemetryClient
    ): QuotaRepository = CodexRepositoryImpl(
        apiService,
        tokenRefreshService,
        prefsManager,
        tokenRefreshCoordinator,
        codexTelemetryClient
    )

    @Provides
    @Singleton
    @IntoMap
    @AiServiceKey(AiService.GEMINI)
    fun provideGeminiRepository(
        companionClient: GeminiCompanionClient,
        prefsManager: EncryptedPrefsManager
    ): QuotaRepository = GeminiRepositoryImpl(companionClient, prefsManager)

    @Provides
    @Singleton
    @IntoMap
    @AiServiceKey(AiService.COPILOT)
    fun provideCopilotRepository(
        apiService: CopilotApiService,
        prefsManager: EncryptedPrefsManager
    ): QuotaRepository = CopilotRepositoryImpl(apiService, prefsManager)

    @Provides
    @Singleton
    @IntoMap
    @AiServiceKey(AiService.CURSOR)
    fun provideCursorRepository(
        apiService: CursorApiService,
        prefsManager: EncryptedPrefsManager
    ): QuotaRepository = CursorRepositoryImpl(apiService, prefsManager)

    @Provides
    @Singleton
    @IntoMap
    @AiServiceKey(AiService.ZAI)
    fun provideZaiRepository(
        apiService: ZaiApiService,
        prefsManager: EncryptedPrefsManager
    ): QuotaRepository = ZaiRepositoryImpl(apiService, prefsManager)

    @Provides
    @Singleton
    @IntoMap
    @AiServiceKey(AiService.ZENMUX)
    fun provideZenMuxRepository(
        apiService: ZenMuxApiService,
        prefsManager: EncryptedPrefsManager
    ): QuotaRepository = ZenMuxRepositoryImpl(apiService, prefsManager)

    @Provides
    @Singleton
    @IntoMap
    @AiServiceKey(AiService.KIMI)
    fun provideKimiRepository(
        apiService: KimiApiService,
        prefsManager: EncryptedPrefsManager
    ): QuotaRepository = KimiRepositoryImpl(apiService, prefsManager)

    @Provides
    @Singleton
    @IntoMap
    @AiServiceKey(AiService.ELEVENLABS)
    fun provideElevenLabsRepository(
        apiService: ElevenLabsApiService,
        prefsManager: EncryptedPrefsManager
    ): QuotaRepository = ElevenLabsRepositoryImpl(apiService, prefsManager)

    @Provides
    @Singleton
    @IntoMap
    @AiServiceKey(AiService.OPENROUTER)
    fun provideOpenRouterRepository(
        apiService: OpenRouterApiService,
        prefsManager: EncryptedPrefsManager
    ): QuotaRepository = OpenRouterRepositoryImpl(apiService, prefsManager)

    @Provides
    @Singleton
    @IntoMap
    @AiServiceKey(AiService.SYNTHETIC)
    fun provideSyntheticRepository(
        apiService: SyntheticApiService,
        prefsManager: EncryptedPrefsManager
    ): QuotaRepository = SyntheticRepositoryImpl(apiService, prefsManager)

    @Provides
    @Singleton
    @IntoMap
    @AiServiceKey(AiService.CHUTES)
    fun provideChutesRepository(
        apiService: ChutesApiService,
        prefsManager: EncryptedPrefsManager
    ): QuotaRepository = ChutesRepositoryImpl(apiService, prefsManager)

    @Provides
    @Singleton
    @IntoMap
    @AiServiceKey(AiService.DEEPSEEK)
    fun provideDeepSeekRepository(
        apiService: DeepSeekApiService,
        prefsManager: EncryptedPrefsManager
    ): QuotaRepository = DeepSeekRepositoryImpl(apiService, prefsManager)

    @Provides
    @Singleton
    @IntoMap
    @AiServiceKey(AiService.VENICE)
    fun provideVeniceRepository(
        apiService: VeniceApiService,
        prefsManager: EncryptedPrefsManager
    ): QuotaRepository = VeniceRepositoryImpl(apiService, prefsManager)

    @Provides
    @Singleton
    @IntoMap
    @AiServiceKey(AiService.MOONSHOT)
    fun provideMoonshotRepository(
        apiService: MoonshotApiService,
        prefsManager: EncryptedPrefsManager
    ): QuotaRepository = MoonshotRepositoryImpl(apiService, prefsManager)

    @Provides
    @Singleton
    @IntoMap
    @AiServiceKey(AiService.CLINEPASS)
    fun provideClinePassRepository(
        apiService: ClinePassApiService,
        prefsManager: EncryptedPrefsManager
    ): QuotaRepository = ClinePassRepositoryImpl(apiService, prefsManager)

    @Provides
    @Singleton
    @IntoMap
    @AiServiceKey(AiService.IBM_BOB)
    fun provideIbmBobRepository(
        apiService: IbmBobApiService,
        prefsManager: EncryptedPrefsManager
    ): QuotaRepository = IbmBobRepositoryImpl(apiService, prefsManager)

    @Provides
    @Singleton
    @IntoMap
    @AiServiceKey(AiService.DEVIN)
    fun provideDevinRepository(
        apiService: DevinApiService,
        prefsManager: EncryptedPrefsManager
    ): QuotaRepository = DevinRepositoryImpl(apiService, prefsManager)

    @Provides
    @Singleton
    @IntoMap
    @AiServiceKey(AiService.FIREWORKS)
    fun provideFireworksRepository(
        apiService: FireworksApiService,
        prefsManager: EncryptedPrefsManager
    ): QuotaRepository = FireworksRepositoryImpl(apiService, prefsManager)
}
