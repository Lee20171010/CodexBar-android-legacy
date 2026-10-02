package com.codexbar.android.di

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.codexbar.android.BuildConfig
import com.codexbar.android.core.domain.model.AiService
import com.codexbar.android.core.network.RetryInterceptor
import com.codexbar.android.core.network.ResponseSizeLimitInterceptor
import com.codexbar.android.core.network.chutes.ChutesApiService
import com.codexbar.android.core.network.clinepass.ClinePassApiService
import com.codexbar.android.core.network.codex.CodexApiService
import com.codexbar.android.core.network.codex.CodexTokenRefreshService
import com.codexbar.android.core.network.copilot.CopilotApiService
import com.codexbar.android.core.network.cursor.CursorApiService
import com.codexbar.android.core.network.deepseek.DeepSeekApiService
import com.codexbar.android.core.network.elevenlabs.ElevenLabsApiService
import com.codexbar.android.core.network.fireworks.FireworksApiService
import com.codexbar.android.core.network.devin.DevinApiService
import com.codexbar.android.core.network.kimi.KimiApiService
import com.codexbar.android.core.network.ibmbob.IbmBobApiService
import com.codexbar.android.core.network.moonshot.MoonshotApiService
import com.codexbar.android.core.network.oauth.CodexDeviceAuthService
import com.codexbar.android.core.network.oauth.GitHubDeviceAuthService
import com.codexbar.android.core.network.opencode.OpenCodeApiService
import com.codexbar.android.core.network.openrouter.OpenRouterApiService
import com.codexbar.android.core.network.synthetic.SyntheticApiService
import com.codexbar.android.core.network.venice.VeniceApiService
import com.codexbar.android.core.network.zai.ZaiApiService
import com.codexbar.android.core.network.zenmux.ZenMuxApiService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class CodexClient

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class CodexTokenClient

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class CopilotClient

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class CodexDeviceAuthClient

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class GitHubDeviceAuthClient

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ProviderCredentialClient

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    private fun baseOkHttpBuilder(includeDebugLogging: Boolean): OkHttpClient.Builder {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(RetryInterceptor())
            .addInterceptor(ResponseSizeLimitInterceptor())

        if (BuildConfig.IS_DEBUG && includeDebugLogging) {
            builder.addInterceptor(createMetadataLoggingInterceptor())
        }

        return builder
    }

    private fun credentialOkHttpBuilder(): OkHttpClient.Builder {
        return baseOkHttpBuilder(includeDebugLogging = false)
            .followRedirects(false)
            .followSslRedirects(false)
    }

    internal fun createMetadataLoggingInterceptor(
        logger: HttpLoggingInterceptor.Logger = HttpLoggingInterceptor.Logger.DEFAULT
    ): HttpLoggingInterceptor = HttpLoggingInterceptor(logger).apply {
        redactHeader("Authorization")
        redactHeader("Cookie")
        redactHeader("Set-Cookie")
        redactHeader("X-Api-Key")
        level = HttpLoggingInterceptor.Level.BASIC
    }

    // --- Codex ---

    @Provides
    @Singleton
    @CodexClient
    fun provideCodexOkHttpClient(): OkHttpClient = baseOkHttpBuilder(includeDebugLogging = true).build()

    @Provides
    @Singleton
    fun provideCodexApiService(
        @CodexClient client: OkHttpClient,
        json: Json
    ): CodexApiService {
        return Retrofit.Builder()
            .baseUrl(AiService.CODEX.baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(CodexApiService::class.java)
    }

    @Provides
    @Singleton
    @CodexTokenClient
    fun provideCodexTokenOkHttpClient(): OkHttpClient = credentialOkHttpBuilder().build()

    @Provides
    @Singleton
    fun provideCodexTokenRefreshService(
        @CodexTokenClient client: OkHttpClient,
        json: Json
    ): CodexTokenRefreshService {
        return Retrofit.Builder()
            .baseUrl(CodexTokenRefreshService.BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(CodexTokenRefreshService::class.java)
    }

    @Provides
    @Singleton
    @CodexDeviceAuthClient
    fun provideCodexDeviceAuthOkHttpClient(): OkHttpClient = credentialOkHttpBuilder().build()

    @Provides
    @Singleton
    fun provideCodexDeviceAuthService(
        @CodexDeviceAuthClient client: OkHttpClient,
        json: Json
    ): CodexDeviceAuthService {
        return Retrofit.Builder()
            .baseUrl(CodexDeviceAuthService.BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(CodexDeviceAuthService::class.java)
    }

    // --- GitHub Copilot ---

    @Provides
    @Singleton
    @CopilotClient
    fun provideCopilotOkHttpClient(): OkHttpClient = baseOkHttpBuilder(includeDebugLogging = true).build()

    @Provides
    @Singleton
    fun provideCopilotApiService(
        @CopilotClient client: OkHttpClient,
        json: Json
    ): CopilotApiService {
        return Retrofit.Builder()
            .baseUrl(AiService.COPILOT.baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(CopilotApiService::class.java)
    }

    @Provides
    @Singleton
    @GitHubDeviceAuthClient
    fun provideGitHubDeviceAuthOkHttpClient(): OkHttpClient = credentialOkHttpBuilder().build()

    @Provides
    @Singleton
    fun provideGitHubDeviceAuthService(
        @GitHubDeviceAuthClient client: OkHttpClient,
        json: Json
    ): GitHubDeviceAuthService {
        return Retrofit.Builder()
            .baseUrl(GitHubDeviceAuthService.BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(GitHubDeviceAuthService::class.java)
    }

    @Provides
    @Singleton
    @ProviderCredentialClient
    fun provideProviderCredentialOkHttpClient(): OkHttpClient = credentialOkHttpBuilder().build()

    // --- Cursor ---

    @Provides
    @Singleton
    fun provideCursorApiService(
        @ProviderCredentialClient client: OkHttpClient,
        json: Json
    ): CursorApiService {
        return Retrofit.Builder()
            .baseUrl(AiService.CURSOR.baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(CursorApiService::class.java)
    }

    // --- z.ai ---

    @Provides
    @Singleton
    fun provideZaiApiService(
        @ProviderCredentialClient client: OkHttpClient,
        json: Json
    ): ZaiApiService {
        return Retrofit.Builder()
            .baseUrl(AiService.ZAI.baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ZaiApiService::class.java)
    }

    // --- ZenMux ---

    @Provides
    @Singleton
    fun provideZenMuxApiService(
        @ProviderCredentialClient client: OkHttpClient,
        json: Json
    ): ZenMuxApiService {
        return Retrofit.Builder()
            .baseUrl(AiService.ZENMUX.baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ZenMuxApiService::class.java)
    }

    // --- Kimi ---

    @Provides
    @Singleton
    fun provideKimiApiService(
        @ProviderCredentialClient client: OkHttpClient,
        json: Json
    ): KimiApiService {
        return Retrofit.Builder()
            .baseUrl(AiService.KIMI.baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(KimiApiService::class.java)
    }

    // --- ElevenLabs ---

    @Provides
    @Singleton
    fun provideElevenLabsApiService(
        @ProviderCredentialClient client: OkHttpClient,
        json: Json
    ): ElevenLabsApiService {
        return Retrofit.Builder()
            .baseUrl(AiService.ELEVENLABS.baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ElevenLabsApiService::class.java)
    }

    // --- OpenRouter ---

    @Provides
    @Singleton
    fun provideOpenRouterApiService(
        @ProviderCredentialClient client: OkHttpClient,
        json: Json
    ): OpenRouterApiService {
        return Retrofit.Builder()
            .baseUrl(AiService.OPENROUTER.baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(OpenRouterApiService::class.java)
    }

    @Provides
    @Singleton
    fun provideOpenCodeApiService(
        @ProviderCredentialClient client: OkHttpClient,
        json: Json
    ): OpenCodeApiService {
        return Retrofit.Builder()
            .baseUrl(AiService.OPENCODE_GO.baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(OpenCodeApiService::class.java)
    }

    // --- Synthetic ---

    @Provides
    @Singleton
    fun provideSyntheticApiService(
        @ProviderCredentialClient client: OkHttpClient,
        json: Json
    ): SyntheticApiService {
        return Retrofit.Builder()
            .baseUrl(AiService.SYNTHETIC.baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(SyntheticApiService::class.java)
    }

    // --- Chutes ---

    @Provides
    @Singleton
    fun provideChutesApiService(
        @ProviderCredentialClient client: OkHttpClient,
        json: Json
    ): ChutesApiService {
        return Retrofit.Builder()
            .baseUrl(AiService.CHUTES.baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ChutesApiService::class.java)
    }

    // --- DeepSeek ---

    @Provides
    @Singleton
    fun provideDeepSeekApiService(
        @ProviderCredentialClient client: OkHttpClient,
        json: Json
    ): DeepSeekApiService {
        return Retrofit.Builder()
            .baseUrl(AiService.DEEPSEEK.baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(DeepSeekApiService::class.java)
    }

    // --- Venice ---

    @Provides
    @Singleton
    fun provideVeniceApiService(
        @ProviderCredentialClient client: OkHttpClient,
        json: Json
    ): VeniceApiService {
        return Retrofit.Builder()
            .baseUrl(AiService.VENICE.baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(VeniceApiService::class.java)
    }

    // --- Moonshot API (International) ---

    @Provides
    @Singleton
    fun provideMoonshotApiService(
        @ProviderCredentialClient client: OkHttpClient,
        json: Json
    ): MoonshotApiService {
        return Retrofit.Builder()
            .baseUrl(AiService.MOONSHOT.baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(MoonshotApiService::class.java)
    }

    // --- Cline ---

    @Provides
    @Singleton
    fun provideClinePassApiService(
        @ProviderCredentialClient client: OkHttpClient,
        json: Json
    ): ClinePassApiService {
        return Retrofit.Builder()
            .baseUrl(AiService.CLINEPASS.baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ClinePassApiService::class.java)
    }

    // --- IBM Bob ---

    @Provides
    @Singleton
    fun provideIbmBobApiService(
        @ProviderCredentialClient client: OkHttpClient,
        json: Json
    ): IbmBobApiService {
        return Retrofit.Builder()
            .baseUrl(AiService.IBM_BOB.baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(IbmBobApiService::class.java)
    }

    // --- Devin AI ---

    @Provides
    @Singleton
    fun provideDevinApiService(
        @ProviderCredentialClient client: OkHttpClient,
        json: Json
    ): DevinApiService {
        return Retrofit.Builder()
            .baseUrl(AiService.DEVIN.baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(DevinApiService::class.java)
    }
    // --- Fireworks AI ---

    @Provides
    @Singleton
    fun provideFireworksApiService(
        @ProviderCredentialClient client: OkHttpClient,
        json: Json
    ): FireworksApiService {
        return Retrofit.Builder()
            .baseUrl(AiService.FIREWORKS.baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(FireworksApiService::class.java)
    }
}
