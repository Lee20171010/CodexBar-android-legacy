package com.codexbar.android.core.widget

import android.content.Context
import android.content.SharedPreferences
import com.codexbar.android.core.domain.model.AccountConnection
import com.codexbar.android.core.domain.model.AiService
import com.codexbar.android.core.domain.model.QuotaInfo
import com.codexbar.android.core.domain.model.UsageWindow
import com.codexbar.android.core.data.QuotaHistoryStore
import com.codexbar.android.core.presentation.QuotaPresentationMapper
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.mock

class ConnectionWidgetTest {
    @Test
    fun `legacy selections and two Codex caches and histories survive independent deletion`() {
        val values = mutableMapOf<String, Any>(
            "widget_1_services_order" to "CODEX,CLAUDE",
            "cache_CODEX_labels" to "5h",
            "cache_CODEX_5h_util" to 0.25f
        )
        val editor = mock(SharedPreferences.Editor::class.java) { invocation ->
            val key = invocation.arguments.firstOrNull() as? String
            when (invocation.method.name) {
                "commit" -> true
                "apply" -> null
                "remove" -> { values.remove(key); invocation.mock }
                else -> { values[checkNotNull(key)] = invocation.arguments[1]; invocation.mock }
            }
        }
        val prefs = mock(SharedPreferences::class.java) { invocation ->
            when (invocation.method.name) {
                "edit" -> editor
                "getAll" -> values.toMap()
                else -> values[invocation.arguments[0]] ?: invocation.arguments[1]
            }
        }
        val context = mock(Context::class.java) { prefs }
        val store = WidgetPrefsManager(context)
        val first = AccountConnection.legacy(AiService.CODEX)
        val second = AccountConnection("00000000-0000-0000-0000-000000000002", AiService.CODEX, "Personal")

        assertEquals(listOf("CODEX", "CLAUDE"), store.getWidgetConfig(1).connectionIds)

        values["CODEX:5h"] = "1000|0.25|"
        val history = QuotaHistoryStore(context)
        val legacyQuota = QuotaInfo(
            service = AiService.CODEX,
            windows = listOf(UsageWindow("5h", 0.5, null)),
            extraUsage = null,
            tier = null,
            fetchedAt = Instant.ofEpochMilli(2000),
            connection = first
        )
        val personalQuota = legacyQuota.copy(connection = second)
        history.record(listOf(legacyQuota, personalQuota))
        val samples = history.historyFor(listOf(legacyQuota, personalQuota))
        assertEquals(listOf(0.25, 0.5), samples.getValue("CODEX|5h").map { it.utilization })
        assertEquals(1, samples.getValue(QuotaPresentationMapper.metricKey(second, "5h")).size)
        history.deleteConnection(first)
        val renamedQuota = personalQuota.copy(connection = second.copy(name = "Renamed"))
        val remaining = history.historyFor(listOf(legacyQuota, renamedQuota))
        assertEquals(0, remaining.getValue("CODEX|5h").size)
        assertEquals(1, remaining.getValue(QuotaPresentationMapper.metricKey(second, "5h")).size)
        assertEquals(0.25f, store.getCachedUtilization(first.id, "5h"))
        store.saveWidgetConfig(2, WidgetDisplayConfig(connectionIds = listOf(first.id, second.id)))
        store.cacheAllQuotaData(second.id, listOf(Triple("5h", 0.75, null)))
        assertEquals(0.75f, store.getCachedUtilization(second.id, "5h"))
        assertEquals(0.25f, store.getCachedUtilization(first.id, "5h"))
        store.deleteConnectionCache(first)
        assertEquals(emptyList<String>(), store.getCachedLabels(first.id))
        assertEquals(listOf("5h"), store.getCachedLabels(second.id))
        assertEquals(listOf(first.id, second.id), store.getWidgetConfig(2).connectionIds)
        assertEquals(listOf("CODEX", "CLAUDE"), store.getWidgetConfig(1).connectionIds)
    }
}
