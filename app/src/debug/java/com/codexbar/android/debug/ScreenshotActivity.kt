package com.codexbar.android.debug

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codexbar.android.CodexBarApp
import com.codexbar.android.core.domain.model.AiService
import com.codexbar.android.core.domain.model.AppThemeStyle
import com.codexbar.android.core.domain.model.CodexContextUsage
import com.codexbar.android.core.domain.model.CodexDailyTokenUsage
import com.codexbar.android.core.domain.model.CodexModelTokenUsage
import com.codexbar.android.core.domain.model.CodexResetCredits
import com.codexbar.android.core.domain.model.CodexTelemetry
import com.codexbar.android.core.domain.model.CodexTokenTotals
import com.codexbar.android.core.domain.model.CodexTokenUsage
import com.codexbar.android.core.domain.model.ExtraUsage
import com.codexbar.android.core.domain.model.QuotaInfo
import com.codexbar.android.core.domain.model.UsageWindow
import com.codexbar.android.core.monitoring.MonitoringSession
import com.codexbar.android.core.notification.QuotaNotificationService
import com.codexbar.android.core.presentation.AndroidQuotaPresentationText
import com.codexbar.android.core.presentation.PacePresentation
import com.codexbar.android.core.presentation.QuotaHistorySample
import com.codexbar.android.core.presentation.QuotaPaceCalculator
import com.codexbar.android.core.presentation.QuotaPresentationMapper
import com.codexbar.android.core.presentation.QuotaPresentationSnapshot
import com.codexbar.android.core.security.EncryptedPrefsManager
import com.codexbar.android.ui.theme.CodexBarTheme
import dagger.hilt.android.AndroidEntryPoint
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.launch

/** Debug-only deterministic surface used to produce release screenshots from the real UI. */
@AndroidEntryPoint
class ScreenshotActivity : AppCompatActivity() {
    @Inject lateinit var prefsManager: EncryptedPrefsManager
    @Inject lateinit var notificationService: QuotaNotificationService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val style = AppThemeStyle.fromStoredValue(intent.getStringExtra(EXTRA_THEME))
        val darkTheme = intent.getBooleanExtra(EXTRA_DARK_THEME, false)
        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !darkTheme
            isAppearanceLightNavigationBars = !darkTheme
        }
        val snapshot = createScreenshotSnapshot(Instant.now())

        // Opt in only on a disposable screenshot emulator with notification permission granted.
        if (intent.getBooleanExtra(EXTRA_NOTIFICATION, false)) {
            lifecycleScope.launch {
                prefsManager.warmCache()
                if (intent.hasExtra("lock_screen_redacted")) {
                    prefsManager.setPrivacySettings(prefsManager.getPrivacySettings().copy(
                        lockScreenRedactionEnabled = intent.getBooleanExtra("lock_screen_redacted", true)
                    ))
                }
                val now = System.currentTimeMillis()
                notificationService.showMonitoringNotification(
                    snapshot,
                    MonitoringSession(now, now + 60 * 60_000L, 1L)
                )
                kotlinx.coroutines.delay(500)
                val notification = getSystemService(android.app.NotificationManager::class.java)
                    .activeNotifications.first { it.id == QuotaNotificationService.MONITORING_NOTIFICATION_ID }.notification
                val redacted = prefsManager.getPrivacySettings().lockScreenRedactionEnabled
                check(notification.visibility == if (redacted) android.app.Notification.VISIBILITY_PRIVATE else android.app.Notification.VISIBILITY_PUBLIC)
                check((notification.publicVersion != null) == redacted)
                android.util.Log.i("CodexBarNotificationTest", "PASS visibility=${notification.visibility} redacted=$redacted")
                if (intent.getBooleanExtra("verify_privacy_toggle", false)) {
                    val coldService = QuotaNotificationService(this@ScreenshotActivity, prefsManager)
                    val originalSettings = prefsManager.getPrivacySettings()
                    val manager = getSystemService(android.app.NotificationManager::class.java)
                    prefsManager.setPrivacySettings(originalSettings.copy(notificationRedactionEnabled = true))
                    coldService.refreshPrivacySettings(null)
                    kotlinx.coroutines.delay(300)
                    val hidden = manager.activeNotifications.first { it.id == QuotaNotificationService.MONITORING_NOTIFICATION_ID }.notification
                    check(hidden.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString().contains("hidden", ignoreCase = true))
                    check(hidden.actions.size == notification.actions.size)
                    prefsManager.setPrivacySettings(originalSettings)
                    coldService.refreshPrivacySettings(null)
                    kotlinx.coroutines.delay(300)
                    val restored = manager.activeNotifications.first { it.id == QuotaNotificationService.MONITORING_NOTIFICATION_ID }.notification
                    check(restored.extras.getCharSequence(android.app.Notification.EXTRA_TITLE).toString() == notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE).toString())
                    android.util.Log.i("CodexBarNotificationTest", "PASS cold-instance hide/show preserves details and actions")
                }
            }
        }

        setContent {
            val minimalDisplayEnabled by prefsManager.minimalDisplayEnabled.collectAsStateWithLifecycle()
            CodexBarTheme(
                darkTheme = darkTheme,
                dynamicColor = false,
                style = style
            ) {
                CodexBarApp(
                    initialDestination = "dashboard",
                    dashboardPreviewSnapshot = snapshot,
                    minimalDisplayEnabled = minimalDisplayEnabled,
                    onScreenPrivacyChanged = {}
                )
            }
        }
    }

    companion object {
        const val EXTRA_THEME = "theme"
        const val EXTRA_DARK_THEME = "dark_theme"
        const val EXTRA_NOTIFICATION = "notification"
    }
}

private fun ScreenshotActivity.createScreenshotSnapshot(now: Instant): QuotaPresentationSnapshot {
    val fiveHourReset = now.plusSeconds(2L * 60L * 60L + 18L * 60L)
    val weeklyReset = now.plusSeconds(3L * 24L * 60L * 60L + 4L * 60L * 60L)
    val codexWindows = listOf(
        UsageWindow("5-Hour", 0.38, fiveHourReset, 5L * 60L * 60L),
        UsageWindow("7-Day", 0.71, weeklyReset, 7L * 24L * 60L * 60L),
        UsageWindow("GPT-5.4", 0.46, weeklyReset, 7L * 24L * 60L * 60L)
    )
    val claudeWindows = listOf(
        UsageWindow("5-Hour", 0.28, now.plusSeconds(3L * 60L * 60L), 5L * 60L * 60L),
        UsageWindow("7-Day", 0.43, now.plusSeconds(4L * 24L * 60L * 60L), 7L * 24L * 60L * 60L)
    )
    val clineWindows = listOf(
        UsageWindow("5-Hour", 0.31, now.plusSeconds(2L * 60L * 60L), 5L * 60L * 60L),
        UsageWindow("7-Day", 0.44, now.plusSeconds(5L * 24L * 60L * 60L), 7L * 24L * 60L * 60L)
    )
    val quotas = listOf(
        QuotaInfo(service = AiService.COPILOT,
            windows = listOf(UsageWindow("Premium", 0.26, now.plusSeconds(86400), 2592000)),
            extraUsage = null, tier = "Pro", fetchedAt = now.minusSeconds(30)),
        QuotaInfo(
            service = AiService.CODEX,
            windows = codexWindows,
            extraUsage = null,
            tier = "Plus · Codex",
            fetchedAt = now.minusSeconds(34),
            codexResetCredits = CodexResetCredits(
                availableCount = 3,
                expiresAt = listOf(
                    now.plusSeconds(26L * 60L * 60L),
                    now.plusSeconds(5L * 24L * 60L * 60L)
                )
            ),
            codexTelemetry = demoCodexTelemetry(now)
        ),
        QuotaInfo(
            service = AiService.CLAUDE,
            windows = claudeWindows,
            extraUsage = null,
            tier = "Max",
            fetchedAt = now.minusSeconds(41)
        ),
        QuotaInfo(
            service = AiService.CLINEPASS,
            windows = clineWindows,
            extraUsage = null,
            tier = "Cline Pro",
            fetchedAt = now.minusSeconds(55)
        ),
        QuotaInfo(
            service = AiService.IBM_BOB,
            windows = listOf(
                UsageWindow("Monthly Bobcoins", 0.175, now.plusSeconds(18L * 24L * 60L * 60L), 30L * 24L * 60L * 60L)
            ),
            extraUsage = ExtraUsage(
                isEnabled = true,
                monthlyLimit = 200.0,
                usedCredits = 35.0,
                utilization = 0.175,
                currency = "Bobcoins"
            ),
            tier = "Pro+ · 2 teams",
            fetchedAt = now.minusSeconds(62)
        ),
        QuotaInfo(
            service = AiService.FIREWORKS,
            windows = emptyList(),
            extraUsage = null,
            tier = "Last 30 days: USD 12.84",
            fetchedAt = now.minusSeconds(68)
        )
    ).let { data ->
        if (intent.getBooleanExtra("antigravity", false)) data + QuotaInfo(
            service = AiService.ANTIGRAVITY,
            windows = listOf(
                UsageWindow("Gemini 3.1 Pro (High)", 0.57, now.plusSeconds(7200)),
                UsageWindow("Gemini 3.1 Pro (Low)", 0.25, now.plusSeconds(7200)),
                UsageWindow("Gemini 3.6 Flash (Medium)", 0.18, now.plusSeconds(7200)),
                UsageWindow("Claude Sonnet 4.6 (Thinking)", 0.32, now.plusSeconds(14400))
            ),
            extraUsage = null,
            tier = null,
            fetchedAt = now.minusSeconds(24)
        ) else data
    }.let { data ->
        if (intent.getBooleanExtra("personal", false)) data.filter {
            it.service in listOf(AiService.CODEX, AiService.COPILOT, AiService.CLAUDE, AiService.ANTIGRAVITY)
        } else data
    }

    val text = AndroidQuotaPresentationText(this)
    val paceCalculator = QuotaPaceCalculator(text)
    val history = buildMap<String, List<QuotaHistorySample>> {
        quotas.forEach { quota ->
            quota.windows.forEach { window ->
                put(
                    QuotaPresentationMapper.metricKey(quota.service, window.label),
                    demoHistory(window, now)
                )
            }
        }
    }
    val pace = buildMap<String, PacePresentation> {
        quotas.forEach { quota ->
            quota.windows.forEach { window ->
                val key = QuotaPresentationMapper.metricKey(quota.service, window.label)
                put(key, paceCalculator.calculate(history[key].orEmpty(), window, now))
            }
        }
    }
    return QuotaPresentationMapper(text = text).map(
        quotas = quotas,
        generatedAt = now,
        paceByMetricKey = pace,
        historyByMetricKey = history
    )
}

private fun demoHistory(window: UsageWindow, now: Instant): List<QuotaHistorySample> {
    val points = 12
    val start = (window.utilization * 0.32).coerceAtLeast(0.03)
    return (0 until points).map { index ->
        val progress = index.toDouble() / (points - 1).toDouble()
        QuotaHistorySample(
            fetchedAt = now.minusSeconds((points - 1L - index) * 30L * 60L),
            utilization = start + (window.utilization - start) * progress,
            resetsAt = window.resetsAt
        )
    }
}

private fun demoCodexTelemetry(now: Instant): CodexTelemetry {
    val today = LocalDate.now(ZoneId.systemDefault())
    val daily = (6 downTo 0).map { daysAgo ->
        val scale = 7 - daysAgo
        CodexDailyTokenUsage(
            date = today.minusDays(daysAgo.toLong()),
            totals = tokenTotals(total = 110_000L + scale * 38_000L)
        )
    }
    return CodexTelemetry(
        generatedAt = now.minusSeconds(20),
        currentContext = CodexContextUsage(
            capturedAt = now.minusSeconds(18),
            model = "gpt-5.4",
            usedTokens = 78_420,
            contextWindowTokens = 128_000,
            sessionTokens = 1_248_730
        ),
        tokenUsage = CodexTokenUsage(
            today = tokenTotals(286_400),
            last7Days = tokenTotals(1_742_900),
            last30Days = tokenTotals(6_834_200),
            daily = daily,
            models = listOf(
                CodexModelTokenUsage("gpt-5.4", tokenTotals(4_282_000)),
                CodexModelTokenUsage("gpt-5.3-codex", tokenTotals(1_904_000)),
                CodexModelTokenUsage("gpt-5.2", tokenTotals(648_200))
            )
        )
    )
}

private fun tokenTotals(total: Long): CodexTokenTotals {
    val input = (total * 0.58).toLong()
    val cached = (total * 0.21).toLong()
    val output = (total * 0.16).toLong()
    val reasoning = (total - input - cached - output).coerceAtLeast(0)
    return CodexTokenTotals(
        inputTokens = input,
        cachedInputTokens = cached,
        outputTokens = output,
        reasoningOutputTokens = reasoning,
        totalTokens = total
    )
}
