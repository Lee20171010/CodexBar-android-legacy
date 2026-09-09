package com.codexbar.android.core.data

import android.content.Context
import android.content.SharedPreferences
import com.codexbar.android.core.domain.model.AiService
import com.codexbar.android.core.domain.model.AccountConnection
import com.codexbar.android.core.domain.model.QuotaInfo
import com.codexbar.android.core.presentation.AndroidQuotaPresentationText
import com.codexbar.android.core.presentation.PacePresentation
import com.codexbar.android.core.presentation.QuotaHistorySample
import com.codexbar.android.core.presentation.QuotaHistorySeries
import com.codexbar.android.core.presentation.QuotaPaceCalculator
import com.codexbar.android.core.presentation.QuotaPresentationMapper
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class QuotaHistoryStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val calculator = QuotaPaceCalculator(AndroidQuotaPresentationText(context))

    fun record(quotas: List<QuotaInfo>) {
        val editor = prefs.edit()
        for (quota in quotas) {
            for (window in quota.windows) {
                val key = historyKey(quota.connection, window.label)
                val sample = QuotaHistorySample(
                    fetchedAt = quota.fetchedAt,
                    utilization = window.utilization.coerceIn(0.0, 1.0),
                    resetsAt = window.resetsAt
                )
                val samples = QuotaHistorySeries.compact(
                    samples = loadSamples(key) + sample,
                    now = sample.fetchedAt
                )
                editor.putString(key, samples.joinToString("\n") { it.serialize() })
            }
        }
        editor.apply()
    }

    fun paceFor(quotas: List<QuotaInfo>, now: Instant): Map<String, PacePresentation> {
        return buildMap {
            for (quota in quotas) {
                for (window in quota.windows) {
                    put(
                        QuotaPresentationMapper.metricKey(quota.connection, window.label),
                        calculator.calculate(
                            samples = loadSamples(historyKey(quota.connection, window.label)),
                            currentWindow = window,
                            now = now
                        )
                    )
                }
            }
        }
    }

    fun historyFor(quotas: List<QuotaInfo>): Map<String, List<QuotaHistorySample>> {
        return buildMap {
            for (quota in quotas) {
                for (window in quota.windows) {
                    put(
                        QuotaPresentationMapper.metricKey(quota.connection, window.label),
                        QuotaHistorySeries.forChart(
                            loadSamples(historyKey(quota.connection, window.label))
                        )
                    )
                }
            }
        }
    }

    fun deleteService(service: AiService) {
        deleteConnection(AccountConnection.legacy(service))
    }

    fun deleteConnection(connection: AccountConnection) {
        val prefix = "${connection.id}:"
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(prefix) }.forEach { editor.remove(it) }
        editor.apply()
    }

    private fun loadSamples(key: String): List<QuotaHistorySample> {
        val raw = prefs.getString(key, null) ?: return emptyList()
        return raw.lineSequence()
            .mapNotNull { line -> line.deserializeSampleOrNull() }
            .toList()
    }

    private fun historyKey(connection: AccountConnection, label: String): String {
        return "${connection.id}:${URLEncoder.encode(label, StandardCharsets.UTF_8.name())}"
    }

    private fun QuotaHistorySample.serialize(): String {
        return listOf(
            fetchedAt.toEpochMilli().toString(),
            utilization.toString(),
            resetsAt?.epochSecond?.toString().orEmpty()
        ).joinToString("|")
    }

    private fun String.deserializeSampleOrNull(): QuotaHistorySample? {
        val parts = split("|")
        if (parts.size < 2) return null
        return runCatching {
            QuotaHistorySample(
                fetchedAt = Instant.ofEpochMilli(parts[0].toLong()),
                utilization = parts[1].toDouble(),
                resetsAt = parts.getOrNull(2)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { Instant.ofEpochSecond(it.toLong()) }
            )
        }.getOrNull()
    }

    internal companion object {
        const val PREFS_NAME = "codexbar_quota_history"
        const val BACKUP_PATH = "$PREFS_NAME.xml"
    }
}
