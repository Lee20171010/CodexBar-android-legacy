package com.codexbar.android.core.security

import com.codexbar.android.core.domain.model.AiService
import com.codexbar.android.core.domain.model.AccountConnection
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

@Singleton
class TokenRefreshCoordinator @Inject constructor() {
    private val locks = ConcurrentHashMap<String, Mutex>()
    // ponytail: local publication is serialized globally; split by ID if large catalogs contend.
    private val publicationLock = Mutex()
    private val _revision = MutableStateFlow(0L)
    val revision = _revision.asStateFlow()

    suspend fun <T> withPublicationLock(block: suspend () -> T): T = publicationLock.withLock { block() }

    fun invalidate() {
        check(publicationLock.isLocked)
        _revision.value += 1
    }

    suspend fun <T> mutate(block: suspend () -> T): T = withPublicationLock {
        currentCoroutineContext().ensureActive()
        try {
            // Complete local persistence and cleanup together even if the editor is closed.
            withContext(NonCancellable) { block() }
        } finally {
            invalidate()
        }
    }

    suspend fun publish(expectedRevision: Long, block: suspend () -> Unit): Boolean = withPublicationLock {
        if (revision.value != expectedRevision) return@withPublicationLock false
        block()
        true
    }

    suspend fun <T> withRefreshLock(service: AiService, block: suspend () -> T): T {
        return withRefreshLock(AccountConnection.legacy(service), block)
    }

    suspend fun <T> withRefreshLock(connection: AccountConnection, block: suspend () -> T): T {
        return locks.getOrPut(connection.id) { Mutex() }.withLock {
            block()
        }
    }
}
