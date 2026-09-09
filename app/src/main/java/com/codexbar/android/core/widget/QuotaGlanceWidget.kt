package com.codexbar.android.core.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.res.Configuration
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.core.content.ContextCompat
import com.codexbar.android.MainActivity
import com.codexbar.android.R
import com.codexbar.android.core.domain.model.AccountConnection
import com.codexbar.android.core.presentation.QuotaSeverity
import com.codexbar.android.core.security.EncryptedPrefsManager
import com.codexbar.android.core.workmanager.WorkManagerInitializer
import com.codexbar.android.di.appSingletonEntryPointOrNull
import kotlinx.coroutines.withTimeoutOrNull

class QuotaGlanceWidget(private val previewConfig: WidgetDisplayConfig? = null) : GlanceAppWidget(errorUiLayout = R.layout.widget_error) {

    // Display state is in WidgetPrefsManager; no Glance DataStore is needed.
    override val stateDefinition = null

    // Use only the sizes the launcher actually requests, without a predefined variant matrix.
    override val sizeMode: SizeMode = SizeMode.Exact

    override fun onCompositionError(
        context: Context,
        glanceId: GlanceId,
        appWidgetId: Int,
        throwable: Throwable
    ) {
        Log.e(TAG, "Widget composition failed for id=$appWidgetId", throwable)
        super.onCompositionError(context, glanceId, appWidgetId, throwable)
    }

    /**
     * Nothing here may throw or block indefinitely. The launcher keeps showing
     * `widget_loading` until [provideContent] returns a composition, so a failed or slow setup
     * read has to degrade into a rendered state instead of aborting the update.
     */
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appContext = context.applicationContext
        val dependencies = WidgetDependencies.of(appContext)
        val appWidgetId = runCatching { GlanceAppWidgetManager(appContext).getAppWidgetId(id) }
            .getOrElse { error ->
                Log.w(TAG, "Could not resolve the App Widget ID for $id", error)
                AppWidgetManager.INVALID_APPWIDGET_ID
            }
        val config = previewConfig ?: runCatching { dependencies.widgetPrefs.getWidgetConfig(appWidgetId) }
            .getOrElse { error ->
                Log.e(TAG, "Could not read the configuration for id=$appWidgetId", error)
                WidgetDisplayConfig()
            }
        val redactQuotaDetails = dependencies.readWidgetRedaction()
        val strings = runCatching { WidgetStrings(ContextCompat.getContextForLanguage(context)) }
            .getOrElse { WidgetStrings(context) }
        val connections = dependencies.readConnections().associateBy { it.id }

        provideContent {
            GlanceTheme {
                WidgetContent(
                    config = config,
                    selectedConnections = config.connectionIds.mapNotNull(connections::get),
                    widgetPrefs = dependencies.widgetPrefs,
                    redactQuotaDetails = redactQuotaDetails,
                    strings = strings
                )
            }
        }
    }

    @Composable
    private fun WidgetContent(
        config: WidgetDisplayConfig,
        selectedConnections: List<AccountConnection>,
        widgetPrefs: WidgetPrefsManager,
        redactQuotaDetails: Boolean,
        strings: WidgetStrings
    ) {
        val style = config.style.normalized()
        Box(
            modifier = GlanceModifier.fillMaxSize().cornerRadius(style.cornerRadius.dp)
                .background(Color(style.backgroundArgb))
                .clickable(actionStartActivity<MainActivity>())
                .padding(horizontal = 8.dp, vertical = 3.dp)
        ) {
            when {
                redactQuotaDetails -> RedactedState(strings, style)
                config.connectionIds.isEmpty() -> EmptyState(strings, style)
                else -> WidgetTemplates(config.copy(style = style),
                    widgetPrefs.displayData(config, selectedConnections, strings.waitingForData))
            }
        }
    }

    @Composable
    private fun EmptyState(strings: WidgetStrings, style: WidgetStyle) {
        Column(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Text(strings.noServices, style = TextStyle(color = ColorProvider(Color(style.foregroundArgb)), fontSize = 12.sp))
            Text(strings.openDetails, style = TextStyle(color = ColorProvider(Color(style.foregroundArgb)), fontSize = 10.sp))
        }
    }

    @Composable
    private fun RedactedState(strings: WidgetStrings, style: WidgetStyle) {
        Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(strings.quotaHidden, style = TextStyle(color = ColorProvider(Color(style.foregroundArgb)), fontSize = 12.sp))
        }
    }

    companion object {
        private const val TAG = "CodexBarWidget"

        fun severityForUtilization(utilization: Float): QuotaSeverity {
            return when {
                utilization >= 0.85f -> QuotaSeverity.Critical
                utilization >= 0.60f -> QuotaSeverity.Warning
                else -> QuotaSeverity.Good
            }
        }

        /**
         * Severity has to stay recognizable on both a light and a dark launcher, so the colors
         * come from resources with a values-night variant rather than one fixed value.
         */
        @Composable
        fun severityColor(severity: QuotaSeverity): ColorProvider {
            val resourceId = when (severity) {
                    QuotaSeverity.Critical -> R.color.widget_severity_critical
                    QuotaSeverity.Warning -> R.color.widget_severity_warning
                    QuotaSeverity.Redacted,
                    QuotaSeverity.Unknown -> R.color.widget_severity_unknown
                    QuotaSeverity.Good -> R.color.widget_severity_good
            }
            val context = LocalContext.current
            return remember(context, resourceId) {
                fun colorForMode(nightMode: Int): Color {
                    val configuration = Configuration(context.resources.configuration).apply {
                        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightMode
                    }
                    return Color(ContextCompat.getColor(context.createConfigurationContext(configuration), resourceId))
                }
                // Let the launcher select the palette, even when its theme differs from the app.
                androidx.glance.color.ColorProvider(
                    day = colorForMode(Configuration.UI_MODE_NIGHT_NO),
                    night = colorForMode(Configuration.UI_MODE_NIGHT_YES)
                )
            }
        }
    }
}

/**
 * Resolves the application-scoped managers the widget renders from.
 *
 * Constructing them per render re-ran the legacy-preferences migration and warmed a second
 * credential cache on every launcher update, which is exactly the work that can outlast a
 * broadcast. Hilt already owns one instance of each; direct construction stays as a fallback for
 * hosts that update the widget before the application component exists.
 */
internal class WidgetDependencies private constructor(
    val widgetPrefs: WidgetPrefsManager,
    private val encryptedPrefs: EncryptedPrefsManager?
) {
    /**
     * Returns whether quota values must stay hidden. A failed or slow read keeps the
     * fail-closed default from [EncryptedPrefsManager] rather than blocking the composition.
     */
    suspend fun readWidgetRedaction(): Boolean {
        val prefsManager = encryptedPrefs ?: return true
        val warmed = runCatching {
            withTimeoutOrNull(SETTINGS_TIMEOUT_MILLIS) { prefsManager.warmCache() }
        }.getOrNull()
        if (warmed == null) {
            Log.w(TAG, "Widget privacy settings were unavailable within the render deadline")
        }
        return runCatching { prefsManager.getPrivacySettings().widgetRedactionEnabled }
            .getOrDefault(true)
    }

    suspend fun readConnections(): List<AccountConnection> = runCatching {
        withTimeoutOrNull(SETTINGS_TIMEOUT_MILLIS) { encryptedPrefs?.loadConnections() }
    }.getOrNull().orEmpty()

    companion object {
        private const val TAG = "CodexBarWidget"
        private const val SETTINGS_TIMEOUT_MILLIS = 2_000L

        fun of(appContext: Context): WidgetDependencies {
            val entryPoint = appSingletonEntryPointOrNull(appContext)
            val widgetPrefs = runCatching { entryPoint?.widgetPrefsManager() }.getOrNull()
                ?: WidgetPrefsManager(appContext)
            val prefsManager = runCatching { entryPoint?.encryptedPrefsManager() }.getOrNull()
                ?: runCatching { EncryptedPrefsManager(appContext) }.getOrNull()
            return WidgetDependencies(widgetPrefs, prefsManager)
        }
    }
}

private class WidgetStrings(private val context: Context) {
    val noServices: String = context.getString(R.string.widget_no_services)
    val quotaHidden: String = context.getString(R.string.widget_quota_hidden)
    val openDetails: String = context.getString(R.string.widget_open_details)
    val waitingForData: String = context.getString(R.string.widget_waiting_for_data)
    val refreshDescription: String = context.getString(R.string.widget_refresh_description)

    fun moreServices(count: Int): String = context.getString(R.string.widget_more_services, count)

    fun updated(freshness: String): String = context.getString(R.string.widget_updated, freshness)
}

class RefreshWidgetAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        WorkManagerInitializer.enqueueManualQuotaRefresh(context, source = "widget")
        WidgetUpdater.update(context, GlanceAppWidgetManager(context).getAppWidgetId(glanceId))
    }
}
