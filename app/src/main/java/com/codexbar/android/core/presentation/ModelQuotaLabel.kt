package com.codexbar.android.core.presentation

import java.util.Locale

/** Preserve variant differences when model quotas share a tiny widget row. */
internal fun compactModelQuotaLabel(label: String): String? {
    val gemini = Regex("Gemini ([0-9.]+) (Pro|Flash)(?: \\((High|Medium|Low)\\))?", RegexOption.IGNORE_CASE)
        .matchEntire(label)
    if (gemini != null) {
        val (version, family, variant) = gemini.destructured
        val shortFamily = if (family.equals("Flash", true)) "Fl" else "Pro"
        val shortVariant = when (variant.lowercase(Locale.ROOT)) {
            "high" -> "H"
            "medium" -> "M"
            "low" -> "L"
            else -> ""
        }
        return "G$version $shortFamily $shortVariant".trim()
    }
    val claude = Regex("Claude (Sonnet|Opus|Haiku) ([0-9.]+)(?: \\(Thinking\\))?", RegexOption.IGNORE_CASE)
        .matchEntire(label)
    if (claude != null) {
        val (family, version) = claude.destructured
        return "${family.first().uppercaseChar()}$version" + if (label.endsWith("(Thinking)", true)) " Think" else ""
    }
    val gpt = Regex("GPT-OSS ([0-9]+B)(?: \\((High|Medium|Low)\\))?", RegexOption.IGNORE_CASE)
        .matchEntire(label)
    return gpt?.let {
        val (size, variant) = it.destructured
        "GPT $size ${variant.take(1).uppercase(Locale.ROOT)}".trim()
    }
}
