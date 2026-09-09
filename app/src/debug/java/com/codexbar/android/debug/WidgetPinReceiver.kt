package com.codexbar.android.debug

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.codexbar.android.core.domain.model.AiService
import com.codexbar.android.core.widget.WidgetDisplayConfig
import com.codexbar.android.core.widget.WidgetPrefsManager
import com.codexbar.android.core.widget.WidgetUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Completes a debug-only pin request using the demo cache, without provider credentials. */
class WidgetPinReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
        if (id == -1) return
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                WidgetPrefsManager(context).saveWidgetConfig(id,
                    WidgetDisplayConfig(connectionIds = listOf(AiService.CODEX.name, AiService.COPILOT.name)))
                WidgetUpdater.update(context, id)
            } finally {
                result.finish()
            }
        }
    }
}
