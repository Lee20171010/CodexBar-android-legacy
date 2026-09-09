package com.codexbar.android.core.security

import android.content.Context
import com.codexbar.android.core.domain.model.AiService
import com.codexbar.android.core.domain.model.AccountConnection
import com.codexbar.android.core.domain.model.AppError
import com.codexbar.android.core.domain.model.Result
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ConnectionHealth {
    UNKNOWN,
    CONNECTED,
    OFFLINE,
    NEEDS_REAUTHENTICATION
}

@Singleton
class ConnectionHealthStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = Any()
    private val _health = MutableStateFlow(loadAll())
    val health: StateFlow<Map<String, ConnectionHealth>> = _health.asStateFlow()

    fun current(service: AiService): ConnectionHealth {
        return current(AccountConnection.legacy(service))
    }

    fun current(connection: AccountConnection): ConnectionHealth =
        _health.value[connection.id] ?: ConnectionHealth.UNKNOWN

    fun record(service: AiService, result: Result<*, AppError>) {
        record(AccountConnection.legacy(service), result)
    }

    fun record(connection: AccountConnection, result: Result<*, AppError>) {
        when (result) {
            is Result.Success -> update(connection, ConnectionHealth.CONNECTED)
            is Result.Failure -> update(connection, result.error.toConnectionHealth())
        }
    }

    fun update(service: AiService, value: ConnectionHealth) {
        update(AccountConnection.legacy(service), value)
    }

    fun update(connection: AccountConnection, value: ConnectionHealth) {
        synchronized(lock) {
            val updated = if (value == ConnectionHealth.UNKNOWN) {
                _health.value - connection.id
            } else {
                _health.value + (connection.id to value)
            }
            prefs.edit().apply {
                if (value == ConnectionHealth.UNKNOWN) {
                    remove(connection.id)
                } else {
                    putString(connection.id, value.name)
                }
            }.apply()
            _health.value = updated
        }
    }

    fun clear(service: AiService) {
        update(service, ConnectionHealth.UNKNOWN)
    }

    fun clear(connection: AccountConnection) {
        update(connection, ConnectionHealth.UNKNOWN)
    }

    fun clearAll() {
        synchronized(lock) {
            prefs.edit().clear().apply()
            _health.value = emptyMap()
        }
    }

    private fun loadAll(): Map<String, ConnectionHealth> {
        return prefs.all.keys.mapNotNull { id ->
            val value = prefs.getString(id, null)
                ?.let { runCatching { ConnectionHealth.valueOf(it) }.getOrNull() }
                ?.takeUnless { it == ConnectionHealth.UNKNOWN }
                ?: return@mapNotNull null
            id to value
        }.toMap()
    }

    companion object {
        const val PREFS_NAME = "codexbar_connection_health"
        const val BACKUP_PATH = "$PREFS_NAME.xml"
    }
}

internal fun AppError.toConnectionHealth(): ConnectionHealth {
    return when (this) {
        is AppError.AuthError -> if (isTerminal) {
            ConnectionHealth.NEEDS_REAUTHENTICATION
        } else {
            ConnectionHealth.OFFLINE
        }
        is AppError.CredentialNotFound -> ConnectionHealth.UNKNOWN
        is AppError.NetworkError,
        is AppError.ParseError,
        is AppError.RateLimited,
        AppError.ServiceUnavailable -> ConnectionHealth.OFFLINE
    }
}
