package com.codexbar.android.core.network.opencode

import kotlinx.serialization.Serializable

object OpenCodeDto {
    @Serializable
    data class UsageEnvelope(
        val usage: Usage? = null
    )

    @Serializable
    data class Usage(
        val rolling: Window? = null,
        val weekly: Window? = null,
        val monthly: Window? = null
    )

    @Serializable
    data class Window(
        val percent: Double? = null,
        val resetsAt: String? = null
    )
}
