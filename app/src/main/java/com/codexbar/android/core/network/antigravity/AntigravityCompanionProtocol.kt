package com.codexbar.android.core.network.antigravity

import kotlinx.serialization.Serializable

@Serializable
internal data class AntigravityCompanionRequest(
    val protocolVersion: Int,
    val companionId: String,
    val requestedAtEpochSeconds: Long,
    val nonce: String,
    val signature: String
)

@Serializable
internal data class AntigravityCompanionEnvelope(
    val protocolVersion: Int,
    val companionId: String,
    val requestNonce: String,
    val sentAtEpochSeconds: Long,
    val iv: String,
    val ciphertext: String
)

@Serializable
data class AntigravityCompanionSnapshot(
    val schemaVersion: Int,
    val source: String,
    val generatedAtEpochSeconds: Long,
    val cliVersion: String,
    val tier: String? = null,
    val windows: List<AntigravityCompanionWindow>
)

@Serializable
data class AntigravityCompanionWindow(
    val label: String,
    val usedFraction: Double,
    val resetsAtEpochSeconds: Long? = null
)
