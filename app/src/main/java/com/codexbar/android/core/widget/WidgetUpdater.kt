package com.codexbar.android.core.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.compose
import com.codexbar.android.R
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Composes and publishes actual RemoteViews; scheduling a Glance session is not a render. */
object WidgetUpdater {
    private const val TAG = "CodexBarWidget"
    private const val RENDER_TIMEOUT_MILLIS = 4_000L
    private val locks = ConcurrentHashMap<Int, Mutex>()

    suspend fun updateAll(context: Context) {
        AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, QuotaWidgetReceiver::class.java))
            .forEach { update(context, it) }
    }

    suspend fun update(context: Context, appWidgetId: Int): Boolean = withContext(Dispatchers.IO) {
        // Unmanaged Glance sessions reject overlapping compositions for the same ID. Read the
        // options/configuration inside this lock so queued resizes always use the latest state.
        locks.getOrPut(appWidgetId) { Mutex() }.withLock {
            val appContext = context.applicationContext
            val manager = AppWidgetManager.getInstance(appContext)
            if (manager.getAppWidgetInfo(appWidgetId) == null) return@withLock true
            val prefs = WidgetPrefsManager(appContext)
            val options = manager.getAppWidgetOptions(appWidgetId)
            val size = "${options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)}x" +
                "${options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)} dp"
            var stage = "cache"
            try {
                prefs.recordRender(appWidgetId, stage, size)
                // Publish saved usage before any Glance work: a killed or stalled composition
                // leaves a usable, tappable view instead of the initial Loading layout.
                publishCachedView(appContext, appWidgetId, manager)
                stage = "compose"
                prefs.recordRender(appWidgetId, stage, size)
                val views = withTimeoutOrNull(RENDER_TIMEOUT_MILLIS) {
                    val id = GlanceAppWidgetManager(appContext).getGlanceIdBy(appWidgetId)
                    QuotaGlanceWidget().compose(appContext, id = id, options = options)
                }
                if (views == null) {
                    prefs.recordRender(appWidgetId, "fallback", size, "compose_timeout")
                    Log.w(TAG, "id=$appWidgetId size=$size stage=compose timeout; cached view retained")
                    return@withLock false
                }
                stage = "publish"
                prefs.recordRender(appWidgetId, stage, size)
                manager.updateAppWidget(appWidgetId, views)
                prefs.recordRender(appWidgetId, "sent", size)
                Log.i(TAG, "id=$appWidgetId size=$size stage=sent")
                true
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // Persist only the error category, never exception messages or account data.
                val reason = "${stage}_${error.javaClass.simpleName}"
                Log.e(TAG, "id=$appWidgetId size=$size stage=$stage", error)
                val fallback = runCatching { publishCachedView(appContext, appWidgetId, manager) }
                    .onFailure { Log.e(TAG, "Cached widget publication failed for id=$appWidgetId", it) }
                prefs.recordRender(appWidgetId, if (fallback.isSuccess) "fallback" else "failed", size, reason)
                false
            }
        }
    }

    private suspend fun publishCachedView(context: Context, id: Int, manager: AppWidgetManager) {
        val dependencies = WidgetDependencies.of(context)
        val redacted = dependencies.readWidgetRedaction()
        val prefs = dependencies.widgetPrefs
        val strings = ContextCompat.getContextForLanguage(context)
        val config = prefs.getWidgetConfig(id)
        val connections = dependencies.readConnections().associateBy { it.id }
        val selected = config.connectionIds.mapNotNull(connections::get)
        val rows = when {
            redacted -> listOf(strings.getString(R.string.widget_quota_hidden))
            selected.isEmpty() -> listOf(strings.getString(R.string.widget_no_services))
            else -> selected.take(3).map { connection ->
                val label = prefs.getCachedLabels(connection.id).maxByOrNull { prefs.getCachedUtilization(connection.id, it) }
                val remaining = label?.let { prefs.getCachedRemainingLabel(connection.id, it) }
                    ?: prefs.getCachedStatusMessage(connection.id)
                    ?: strings.getString(R.string.widget_waiting_for_data)
                "${connection.name} · $remaining"
            }
        }
        val openConfiguration = Intent(context, WidgetConfigurationActivity::class.java)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            .setData(android.net.Uri.parse("codexbar://widget/$id"))
        val views = RemoteViews(context.packageName, R.layout.widget_cached).apply {
            setTextViewText(R.id.widget_cached_text, rows.joinToString("\n"))
            setTextColor(R.id.widget_cached_text, config.style.foregroundArgb)
            setInt(R.id.widget_cached_root, "setBackgroundColor", config.style.backgroundArgb)
            setOnClickPendingIntent(
                R.id.widget_cached_root,
                PendingIntent.getActivity(context, id, openConfiguration,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            )
        }
        manager.updateAppWidget(id, views)
    }
}
