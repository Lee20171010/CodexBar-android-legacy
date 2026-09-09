package com.codexbar.android.core.domain.model

import java.util.UUID

data class AccountConnection(
    val id: String,
    val service: AiService,
    val name: String = service.displayName
) {
    init {
        require(id == service.name || runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)) {
            "Invalid connection ID"
        }
        require(name.isNotBlank() && name.length <= 80) { "Connection name must contain 1 to 80 characters" }
    }

    companion object {
        fun legacy(service: AiService) = AccountConnection(service.name, service)
    }
}
