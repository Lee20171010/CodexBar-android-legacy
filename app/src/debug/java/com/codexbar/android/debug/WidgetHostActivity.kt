package com.codexbar.android.debug

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.util.SizeF
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RemoteViews
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.codexbar.android.core.domain.model.AiService
import com.codexbar.android.core.domain.model.QuotaInfo
import com.codexbar.android.core.domain.model.UsageWindow
import com.codexbar.android.core.presentation.AndroidQuotaPresentationText
import com.codexbar.android.core.presentation.QuotaPresentationMapper
import com.codexbar.android.core.security.EncryptedPrefsManager
import com.codexbar.android.core.widget.QuotaWidgetReceiver
import com.codexbar.android.core.widget.WidgetStyle
import com.codexbar.android.core.widget.WidgetTemplate
import com.codexbar.android.core.widget.WidgetDisplayConfig
import com.codexbar.android.core.widget.WidgetPrefsManager
import dagger.hilt.android.AndroidEntryPoint
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.launch

/** A real Android widget host for reproducible rendering checks on a disposable emulator. */
@AndroidEntryPoint
class WidgetHostActivity : ComponentActivity() {
    @Inject lateinit var widgetPrefs: WidgetPrefsManager
    @Inject lateinit var encryptedPrefs: EncryptedPrefsManager
    private lateinit var host: AppWidgetHost

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(intent.getBooleanExtra("seed_demo", false)) { "Use only with explicit demo data" }
        val width = intent.getIntExtra("width_dp", 320).coerceIn(120, 500)
        val height = intent.getIntExtra("height_dp", 60).coerceIn(32, 500)
        val manager = AppWidgetManager.getInstance(this)
        if (intent.getBooleanExtra("pin_widget", false)) {
            val callback = android.app.PendingIntent.getBroadcast(
                this, HOST_ID, android.content.Intent(this, WidgetPinReceiver::class.java),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
            )
            check(manager.requestPinAppWidget(ComponentName(this, QuotaWidgetReceiver::class.java), null, callback))
            finish()
            return
        }
        host = object : AppWidgetHost(this, HOST_ID) {
            override fun onCreateView(context: Context, id: Int, info: android.appwidget.AppWidgetProviderInfo): AppWidgetHostView {
                return object : AppWidgetHostView(context) {
                    override fun updateAppWidget(remoteViews: RemoteViews?) {
                        super.updateAppWidget(remoteViews)
                        // Dimensions below describe content, without a launcher's outer padding.
                        setPadding(0, 0, 0, 0)
                        postDelayed({
                            val texts = collectText(this)
                            val visibleTexts = collectText(this, visibleOnly = true)
                            val result = "id=$id size=${width}x$height layout=${remoteViews?.layoutId} text=$texts"
                            Log.i(TAG, result)
                            filesDir.resolve("widget-host-result.txt").writeText(result)
                            filesDir.resolve("widget-host-result.json").writeText(org.json.JSONObject().apply {
                                put("width", width)
                                put("height", height)
                                put("text", org.json.JSONArray(texts))
                                put("visible", org.json.JSONArray(visibleTexts))
                            }.toString())
                        }, 500)
                    }
                }
            }
        }
        val prefs = getSharedPreferences("debug_widget_host", MODE_PRIVATE)
        var id = prefs.getInt("id", AppWidgetManager.INVALID_APPWIDGET_ID)
        if (manager.getAppWidgetInfo(id) == null) {
            id = host.allocateAppWidgetId()
            check(manager.bindAppWidgetIdIfAllowed(id, ComponentName(this, QuotaWidgetReceiver::class.java))) {
                "Grant binding first: adb shell appwidget grantbind --package com.codexbar.android --user 0"
            }
            prefs.edit().putInt("id", id).apply()
        }
        val hostView = host.createView(this, id, manager.getAppWidgetInfo(id)).apply {
            setPadding(0, 0, 0, 0)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp(), 40.dp(), 16.dp(), 16.dp())
            setBackgroundColor(android.graphics.Color.rgb(232, 236, 243))
            addView(TextView(this@WidgetHostActivity).apply {
                text = "CodexBar widget host · ${width} × $height dp"
                textSize = 16f
                setPadding(0, 0, 0, 16.dp())
            })
            addView(hostView, LinearLayout.LayoutParams(width.dp(), height.dp()))
        }
        setContentView(root)
        host.startListening()
        lifecycleScope.launch {
            encryptedPrefs.warmCache()
            encryptedPrefs.setPrivacySettings(encryptedPrefs.getPrivacySettings().copy(
                widgetRedactionEnabled = intent.getBooleanExtra("redacted", false)
            ))
            encryptedPrefs.setRefreshInterval(0L)
            val now = Instant.now()
            val three = intent.getBooleanExtra("three_services", false)
            val antigravity = intent.getBooleanExtra("antigravity", false)
            val secondService = if (antigravity) AiService.ANTIGRAVITY else AiService.COPILOT
            val secondWindows = if (antigravity) listOf(
                UsageWindow("Gemini 3.1 Pro (High)", 0.57, now.plusSeconds(7200)),
                UsageWindow("Gemini 3.1 Pro (Low)", 0.25, now.plusSeconds(7200))
            ) else listOf(UsageWindow("Premium", 0.26, now.plusSeconds(86400), 2592000))
            val snapshot = QuotaPresentationMapper(text = AndroidQuotaPresentationText(this@WidgetHostActivity)).map(
                quotas = listOf(
                    QuotaInfo(AiService.CODEX, listOf(UsageWindow("5-Hour", 0.38, now.plusSeconds(7200), 18000), UsageWindow("Weekly", 0.21, now.plusSeconds(273600), 604800)), null, "Plus", now),
                    QuotaInfo(secondService, secondWindows, null, null, now)
                ) + if (three) listOf(QuotaInfo(AiService.CLAUDE,
                    listOf(UsageWindow("5-Hour", 0.13, now.plusSeconds(8280), 18000),
                        UsageWindow("Weekly", 0.44, now.plusSeconds(435600), 604800)), null, "Pro", now)) else emptyList(),
                generatedAt = now
            )
            snapshot.services.forEach(widgetPrefs::cachePresentation)
            widgetPrefs.saveWidgetConfig(id, WidgetDisplayConfig(
                services = listOf(AiService.CODEX, secondService) + if (three) listOf(AiService.CLAUDE) else emptyList(),
                style = WidgetStyle(template = WidgetTemplate.fromId(intent.getStringExtra("template")),
                    showSecondary = intent.getBooleanExtra("show_secondary", true),
                    opacity = intent.getIntExtra("opacity", 68))))
            if (intent.getBooleanExtra("update_all_demo", false)) {
                manager.getAppWidgetIds(ComponentName(this@WidgetHostActivity, QuotaWidgetReceiver::class.java)).forEach { target ->
                    widgetPrefs.saveWidgetConfig(target, widgetPrefs.getWidgetConfig(id))
                }
                com.codexbar.android.core.widget.WidgetUpdater.updateAll(this@WidgetHostActivity)
            }
            val options = Bundle().apply {
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, width)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, width)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, height)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, height)
                if (intent.getBooleanExtra("legacy_sizes", false)) {
                    putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, null)
                } else {
                    putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, arrayListOf(SizeF(width.toFloat(), height.toFloat())))
                }
            }
            manager.updateAppWidgetOptions(id, options)
            sendBroadcast(android.content.Intent(this@WidgetHostActivity, QuotaWidgetReceiver::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(id))
            })
        }
    }

    override fun onDestroy() {
        if (::host.isInitialized) host.stopListening()
        super.onDestroy()
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    private fun collectText(view: View, visibleOnly: Boolean = false): List<String> = buildList {
        val bounds = android.graphics.Rect()
        if (view is TextView) {
            val layout = view.layout
            val fullyVisible = view.getGlobalVisibleRect(bounds) && bounds.height() >= view.height - 1 &&
                layout != null && layout.height <= view.height - view.compoundPaddingTop - view.compoundPaddingBottom &&
                (0 until layout.lineCount).all { layout.getEllipsisCount(it) == 0 }
            if (!visibleOnly || fullyVisible) add(view.text.toString())
        }
        if (view is ViewGroup) for (index in 0 until view.childCount) addAll(collectText(view.getChildAt(index), visibleOnly))
    }

    companion object {
        private const val HOST_ID = 901
        private const val TAG = "CodexBarWidgetHost"
    }
}
