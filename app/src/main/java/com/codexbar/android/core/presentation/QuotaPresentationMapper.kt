package com.codexbar.android.core.presentation

import com.codexbar.android.core.domain.model.AiService
import com.codexbar.android.core.domain.model.AccountConnection
import com.codexbar.android.core.domain.model.AppError
import com.codexbar.android.core.domain.model.CodexTelemetry
import com.codexbar.android.core.domain.model.CodexTokenTotals
import com.codexbar.android.core.domain.model.ExtraUsage
import com.codexbar.android.core.domain.model.QuotaInfo
import com.codexbar.android.core.domain.model.QuotaNotice
import com.codexbar.android.core.domain.model.UsageWindow
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.text.NumberFormat
import kotlin.math.roundToInt

class QuotaPresentationMapper(
    private val clock: Clock = Clock.systemDefaultZone(),
    private val text: QuotaPresentationText = EnglishQuotaPresentationText,
    private val resetPlanCalculator: QuotaResetPlanCalculator = QuotaResetPlanCalculator()
) {
    fun map(
        quotas: List<QuotaInfo>,
        errors: Map<AiService, AppError> = emptyMap(),
        generatedAt: Instant = clock.instant(),
        locale: Locale = text.locale,
        privacy: PrivacyPresentation = PrivacyPresentation(),
        source: RefreshSourcePresentation = RefreshSourcePresentation.Unknown,
        paceByMetricKey: Map<String, PacePresentation> = emptyMap(),
        historyByMetricKey: Map<String, List<QuotaHistorySample>> = emptyMap(),
        connectionErrors: Map<AccountConnection, AppError> = emptyMap()
    ): QuotaPresentationSnapshot {
        val successfulServices = quotas.map { quota ->
            val metrics = quota.windows.mapIndexed { index, window ->
                mapWindow(
                    service = quota.service,
                    connection = quota.connection,
                    window = window,
                    index = index,
                    generatedAt = generatedAt,
                    locale = locale,
                    privacy = privacy,
                    paceByMetricKey = paceByMetricKey,
                    historyByMetricKey = historyByMetricKey
                )
            }
            val primary = metrics.maxWithOrNull(
                compareBy<QuotaMetricPresentation> { it.usedFraction ?: -1.0 }
                    .thenBy { it.label }
            )
            ServiceQuotaPresentation(
                service = quota.service,
                accountLabel = quota.connection.name.takeUnless { privacy.redactSensitiveValues },
                connection = quota.connection,
                tier = if (privacy.redactSensitiveValues) null else quota.tier,
                status = if (privacy.redactSensitiveValues) ServiceQuotaStatus.Redacted else ServiceQuotaStatus.Fresh,
                primaryMetric = primary,
                metrics = metrics,
                extraUsage = quota.extraUsage?.let { mapExtraUsage(it, locale, privacy) },
                insights = quota.notices.mapNotNull(::mapNotice),
                freshness = FreshnessPresentation(
                    fetchedAt = quota.fetchedAt,
                    ageLabel = formatAge(quota.fetchedAt, generatedAt),
                    state = FreshnessState.Fresh
                ),
                supportedActions = setOf(
                    QuotaAction.OpenDashboard,
                    QuotaAction.Refresh,
                    QuotaAction.StartMonitoring,
                    QuotaAction.Disconnect
                ),
                codexResetCredits = quota.codexResetCredits
                    ?.takeUnless { privacy.redactSensitiveValues }
                    ?.let { credits ->
                        CodexResetCreditsPresentation(
                            availableCount = credits.availableCount,
                            availableLabel = text.resetCreditsAvailable(credits.availableCount),
                            nextExpiryLabel = credits.expiresAt.firstOrNull()
                                ?.let(text::resetCreditNextExpiresAt),
                            expiryLabels = credits.expiresAt.map(text::resetCreditExpiresAt),
                            noExpiryCount = (credits.availableCount - credits.expiresAt.size)
                                .coerceAtLeast(0)
                            )
                    },
                codexTelemetry = quota.codexTelemetry
                    ?.takeUnless { privacy.redactSensitiveValues }
                    ?.let { mapCodexTelemetry(it, locale) }
            )
        }

        val failedServices = (errors.mapKeys { AccountConnection.legacy(it.key) } + connectionErrors)
            .filterKeys { connection -> successfulServices.none { it.connection.id == connection.id } }
            .map { (connection, error) ->
                mapError(connection.service, error, generatedAt).copy(
                    connection = connection,
                    accountLabel = connection.name.takeUnless { privacy.redactSensitiveValues }
                )
            }

        val services = (successfulServices + failedServices)
            .sortedWith(
                compareByDescending<ServiceQuotaPresentation> {
                    it.primaryMetric?.usedFraction ?: -1.0
                }.thenBy { it.service.ordinal }
            )

        return QuotaPresentationSnapshot(
            generatedAt = generatedAt,
            services = services,
            source = source,
            privacy = privacy
        )
    }

    private fun mapWindow(
        service: AiService,
        connection: AccountConnection,
        window: UsageWindow,
        index: Int,
        generatedAt: Instant,
        locale: Locale,
        privacy: PrivacyPresentation,
        paceByMetricKey: Map<String, PacePresentation>,
        historyByMetricKey: Map<String, List<QuotaHistorySample>>
    ): QuotaMetricPresentation {
        val used = window.utilization.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0)
        val remaining = used?.let { 1.0 - it }
        val usedPercent = used?.toPercent()
        val remainingPercent = remaining?.toPercent()
        val severity = when {
            privacy.redactSensitiveValues -> QuotaSeverity.Redacted
            used == null -> QuotaSeverity.Unknown
            used >= CRITICAL_USED_FRACTION -> QuotaSeverity.Critical
            used >= WARNING_USED_FRACTION -> QuotaSeverity.Warning
            else -> QuotaSeverity.Good
        }
        val label = window.label.ifBlank { text.window(index + 1) }
        val pace = paceByMetricKey[metricKey(connection, label)] ?: PacePresentation(
            state = PaceState.CollectingHistory,
            label = text.collectingPaceHistory()
        )
        val resetPlan = if (service == AiService.CODEX && !privacy.redactSensitiveValues) {
            resetPlanCalculator.calculate(window, pace.state, generatedAt)?.let(::mapResetPlan)
        } else {
            null
        }
        val history = if (privacy.redactSensitiveValues) {
            QuotaHistoryPresentation()
        } else {
            mapHistory(historyByMetricKey[metricKey(connection, label)].orEmpty())
        }
        return QuotaMetricPresentation(
            id = label.lowercase(locale).replace(Regex("[^a-z0-9]+"), "-").trim('-')
                .ifBlank { "window-${index + 1}" },
            label = label,
            usedFraction = if (privacy.redactSensitiveValues) null else used,
            remainingFraction = if (privacy.redactSensitiveValues) null else remaining,
            usedPercent = if (privacy.redactSensitiveValues) null else usedPercent,
            remainingPercent = if (privacy.redactSensitiveValues) null else remainingPercent,
            usedLabel = if (privacy.redactSensitiveValues || usedPercent == null) {
                text.usedHidden()
            } else {
                text.percentUsed(usedPercent)
            },
            remainingLabel = if (privacy.redactSensitiveValues || remainingPercent == null) {
                text.remainingHidden()
            } else {
                text.percentRemaining(remainingPercent)
            },
            barProgress = if (privacy.redactSensitiveValues) 0f else (remaining ?: 0.0).toFloat(),
            severity = severity,
            resetsAt = if (privacy.redactSensitiveValues) null else window.resetsAt,
            resetLabel = if (privacy.redactSensitiveValues) null else window.resetsAt?.let {
                formatResetLabel(it, generatedAt)
            },
            pace = pace,
            resetPlan = resetPlan,
            history = history
        )
    }

    private fun mapHistory(samples: List<QuotaHistorySample>): QuotaHistoryPresentation {
        var previous: QuotaHistorySample? = null
        val points = samples
            .filter { it.utilization.isFinite() }
            .sortedBy(QuotaHistorySample::fetchedAt)
            .map { sample ->
                val prior = previous
                val startsNewCycle = prior != null && (
                    prior.resetsAt != sample.resetsAt || sample.utilization < prior.utilization
                )
                previous = sample
                QuotaHistoryPointPresentation(
                    capturedAt = sample.fetchedAt,
                    usedFraction = sample.utilization.coerceIn(0.0, 1.0).toFloat(),
                    startsNewCycle = startsNewCycle
                )
            }
        return QuotaHistoryPresentation(points)
    }

    private fun mapResetPlan(plan: QuotaResetPlan): QuotaResetPlanPresentation {
        val perHour = formatDecimal(plan.sustainablePercentPerHour)
        val budgetLabel = if (plan.minutesUntilReset >= MINUTES_PER_DAY) {
            text.resetBudgetPerHourAndDay(
                percentPerHour = perHour,
                percentPerDay = formatDecimal(plan.sustainablePercentPerDay)
            )
        } else {
            text.resetBudgetPerHour(perHour)
        }
        val actionLabel = when (plan.action) {
            ResetPlanAction.AlmostUsed -> text.resetAlmostUsed(
                plan.remainingPercent,
                plan.resetAt
            )
            ResetPlanAction.UseNow -> text.resetUseNow(plan.remainingPercent, plan.resetAt)
            ResetPlanAction.SlowDown -> text.resetSlowDown(
                plan.checkpointBudgetPercent,
                plan.checkpointAt
            )
            ResetPlanAction.KeepPace -> text.resetKeepPace(
                plan.checkpointBudgetPercent,
                plan.checkpointAt
            )
            ResetPlanAction.UseByCheckpoint -> text.resetUseByCheckpoint(
                plan.checkpointBudgetPercent,
                plan.checkpointAt
            )
        }
        val compactActionLabel = when (plan.action) {
            ResetPlanAction.AlmostUsed -> text.resetCompactAlmostUsed(plan.remainingPercent)
            ResetPlanAction.UseNow -> text.resetCompactUseNow(plan.remainingPercent)
            ResetPlanAction.SlowDown -> text.resetCompactSlowDown(
                plan.checkpointBudgetPercent,
                plan.checkpointAt
            )
            ResetPlanAction.KeepPace -> text.resetCompactKeepPace(
                plan.checkpointBudgetPercent,
                plan.checkpointAt
            )
            ResetPlanAction.UseByCheckpoint -> text.resetCompactUseByCheckpoint(
                plan.checkpointBudgetPercent,
                plan.checkpointAt
            )
        }
        return QuotaResetPlanPresentation(
            action = plan.action,
            deadlineLabel = text.resetDeadline(plan.resetAt),
            budgetLabel = budgetLabel,
            actionLabel = actionLabel,
            compactActionLabel = compactActionLabel
        )
    }

    private fun mapExtraUsage(
        extraUsage: ExtraUsage,
        locale: Locale,
        privacy: PrivacyPresentation
    ): ExtraUsagePresentation {
        val used = extraUsage.usedCredits.coerceAtLeast(0.0)
        val limit = extraUsage.monthlyLimit.coerceAtLeast(0.0)
        val remaining = (limit - used).coerceAtLeast(0.0)
        val utilization = extraUsage.utilization.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.0
        return ExtraUsagePresentation(
            label = text.credits(),
            usedCreditsLabel = if (privacy.redactSensitiveValues) {
                text.usedHidden()
            } else {
                text.currencyUsed(
                    extraUsage.currency,
                    String.format(locale, "%.2f", used)
                )
            },
            limitLabel = if (privacy.redactSensitiveValues) {
                text.limitHidden()
            } else {
                text.currencyLimit(
                    extraUsage.currency,
                    String.format(locale, "%.2f", limit)
                )
            },
            remainingLabel = if (privacy.redactSensitiveValues) {
                text.remainingHidden()
            } else {
                text.currencyRemaining(
                    extraUsage.currency,
                    String.format(locale, "%.2f", remaining)
                )
            },
            utilizationFraction = if (privacy.redactSensitiveValues) 0.0 else utilization,
            severity = when {
                privacy.redactSensitiveValues -> QuotaSeverity.Redacted
                utilization >= CRITICAL_USED_FRACTION -> QuotaSeverity.Critical
                utilization >= WARNING_USED_FRACTION -> QuotaSeverity.Warning
                else -> QuotaSeverity.Good
            }
        )
    }

    private fun mapCodexTelemetry(
        telemetry: CodexTelemetry,
        locale: Locale
    ): CodexTelemetryPresentation {
        val numberFormat = NumberFormat.getIntegerInstance(locale)
        val currentContext = telemetry.currentContext?.let { context ->
            val fraction = context.usedTokens.toDouble()
                .div(context.contextWindowTokens.toDouble())
                .takeIf(Double::isFinite)
                ?.coerceIn(0.0, 1.0)
                ?: 0.0
            CodexContextPresentation(
                model = context.model,
                usedTokens = context.usedTokens,
                contextWindowTokens = context.contextWindowTokens,
                sessionTokens = context.sessionTokens,
                usedFraction = fraction.toFloat(),
                usedPercent = (fraction * 100).roundToInt().coerceIn(0, 100),
                usageLabel = "${numberFormat.format(context.usedTokens)} / " +
                    "${numberFormat.format(context.contextWindowTokens)}",
                capturedAt = context.capturedAt
            )
        }
        val thirtyDayTotal = telemetry.tokenUsage.last30Days.totalTokens.coerceAtLeast(1L)
        return CodexTelemetryPresentation(
            generatedAt = telemetry.generatedAt,
            currentContext = currentContext,
            tokenUsage = CodexTokenUsagePresentation(
                today = mapTokenTotals(telemetry.tokenUsage.today, numberFormat),
                last7Days = mapTokenTotals(telemetry.tokenUsage.last7Days, numberFormat),
                last30Days = mapTokenTotals(telemetry.tokenUsage.last30Days, numberFormat),
                daily = telemetry.tokenUsage.daily.map { entry ->
                    CodexDailyTokenPresentation(
                        date = entry.date,
                        totalTokens = entry.totals.totalTokens,
                        totalLabel = numberFormat.format(entry.totals.totalTokens)
                    )
                },
                models = telemetry.tokenUsage.models.map { entry ->
                    CodexModelTokenPresentation(
                        model = entry.model,
                        totalTokens = entry.totals.totalTokens,
                        totalLabel = numberFormat.format(entry.totals.totalTokens),
                        shareFraction = entry.totals.totalTokens.toDouble()
                            .div(thirtyDayTotal.toDouble())
                            .takeIf(Double::isFinite)
                            ?.coerceIn(0.0, 1.0)
                            ?.toFloat()
                            ?: 0f
                    )
                }
            )
        )
    }

    private fun mapTokenTotals(
        totals: CodexTokenTotals,
        numberFormat: NumberFormat
    ): CodexTokenTotalsPresentation {
        return CodexTokenTotalsPresentation(
            totalTokens = totals.totalTokens,
            totalLabel = numberFormat.format(totals.totalTokens),
            inputLabel = numberFormat.format(totals.inputTokens),
            cachedInputLabel = numberFormat.format(totals.cachedInputTokens),
            outputLabel = numberFormat.format(totals.outputTokens),
            reasoningOutputLabel = numberFormat.format(totals.reasoningOutputTokens)
        )
    }

    private fun mapError(
        service: AiService,
        error: AppError,
        generatedAt: Instant
    ): ServiceQuotaPresentation {
        val status = when (error) {
            is AppError.AuthError -> if (error.isTerminal) ServiceQuotaStatus.AuthRequired else ServiceQuotaStatus.ProviderError
            is AppError.CredentialNotFound -> ServiceQuotaStatus.Disconnected
            is AppError.NetworkError -> ServiceQuotaStatus.Offline
            is AppError.RateLimited -> ServiceQuotaStatus.RateLimited
            is AppError.ParseError,
            AppError.ServiceUnavailable -> ServiceQuotaStatus.ProviderError
        }
        val freshnessState = when (error) {
            is AppError.RateLimited -> FreshnessState.RateLimited
            else -> FreshnessState.Error
        }
        return ServiceQuotaPresentation(
            service = service,
            accountLabel = null,
            tier = null,
            status = status,
            primaryMetric = null,
            metrics = emptyList(),
            extraUsage = null,
            insights = emptyList(),
            freshness = FreshnessPresentation(
                fetchedAt = null,
                ageLabel = text.noFreshData(),
                state = freshnessState,
                staleReason = error.toPresentationMessage(),
                nextRetryAt = (error as? AppError.RateLimited)?.retryAt
            ),
            supportedActions = setOf(
                QuotaAction.OpenDashboard,
                QuotaAction.Refresh,
                QuotaAction.Reauthenticate,
                QuotaAction.Disconnect
            )
        )
    }

    private fun mapNotice(notice: QuotaNotice): ServiceInsightPresentation? {
        return when (notice) {
            is QuotaNotice.WindowLimitNotProvided -> {
                val hours = notice.windowDurationSeconds / SECONDS_PER_HOUR
                if (hours <= 0L || notice.windowDurationSeconds % SECONDS_PER_HOUR != 0L) {
                    null
                } else {
                    ServiceInsightPresentation(
                        title = text.limitNotProvidedTitle(hours),
                        message = text.limitNotProvidedMessage()
                    )
                }
            }
        }
    }

    private fun AppError.toPresentationMessage(): String {
        return when (this) {
            is AppError.AuthError -> if (permissionDenied) {
                text.permissionDenied()
            } else if (isTerminal) {
                text.reauthenticationRequired()
            } else {
                text.authenticationFailed()
            }
            is AppError.CredentialNotFound -> text.notConnected()
            is AppError.NetworkError -> text.networkUnavailable()
            is AppError.RateLimited -> retryAt?.let(text::rateLimitedUntil) ?: text.rateLimited()
            is AppError.ParseError -> text.providerResponseInvalid()
            AppError.ServiceUnavailable -> text.providerUnavailable()
        }
    }

    private fun formatAge(fetchedAt: Instant?, now: Instant): String {
        if (fetchedAt == null) return text.noFreshData()
        val age = Duration.between(fetchedAt, now)
        if (age.isNegative || age.toMinutes() < 1) return text.justNow()
        return when {
            age.toMinutes() < 60 -> text.minutesAgo(age.toMinutes())
            age.toHours() < 24 -> text.hoursAgo(age.toHours())
            else -> text.daysAgo(age.toDays())
        }
    }

    private fun formatResetLabel(resetsAt: Instant, now: Instant): String? {
        val duration = Duration.between(now, resetsAt)
        if (duration.isNegative || duration.isZero) return null
        val minutes = duration.toMinutes()
        val hours = duration.toHours()
        val days = duration.toDays()
        return when {
            days > 0 -> text.resetsInDays(days, hours % 24)
            hours > 0 -> text.resetsInHours(hours, minutes % 60)
            else -> text.resetsInMinutes(minutes)
        }
    }

    private fun Double.toPercent(): Int = (this * 100.0).roundToInt().coerceIn(0, 100)

    private fun formatDecimal(value: Double): String {
        return String.format(text.locale, "%.1f", value.coerceAtLeast(0.0))
    }

    companion object {
        fun metricKey(service: AiService, label: String): String = "${service.name}|$label"
        fun metricKey(connection: AccountConnection, label: String): String = "${connection.id}|$label"

        private const val WARNING_USED_FRACTION = 0.60
        private const val CRITICAL_USED_FRACTION = 0.85
        private const val SECONDS_PER_HOUR = 3600L
        private const val MINUTES_PER_DAY = 24L * 60L
    }
}
