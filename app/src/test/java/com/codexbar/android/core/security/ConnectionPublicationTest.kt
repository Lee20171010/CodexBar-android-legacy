package com.codexbar.android.core.security

import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionPublicationTest {
    @Test
    fun `cancelled mutation finishes cleanup and rejects a stale refresh`() = runTest {
        val coordinator = TokenRefreshCoordinator()
        val oldRevision = coordinator.revision.value
        val deleted = CompletableDeferred<Unit>()
        val finishCleanup = CompletableDeferred<Unit>()
        var cachePresent = true
        val mutation = async {
            coordinator.mutate {
                deleted.complete(Unit)
                finishCleanup.await()
                cachePresent = false
            }
        }
        deleted.await()
        mutation.cancel()
        val lateRefresh = async { coordinator.publish(oldRevision) { cachePresent = true } }
        finishCleanup.complete(Unit)
        mutation.join()
        assertFalse(lateRefresh.await())
        assertFalse(cachePresent)
        assertTrue(coordinator.publish(coordinator.revision.value) { cachePresent = true })
        assertTrue(cachePresent)
    }

    @Test
    fun `mutation invalidates in flight publication and serializes cleanup`() = runTest {
        val coordinator = TokenRefreshCoordinator()
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val generation = coordinator.revision.value
        var cached = false
        val publication = async {
            coordinator.withPublicationLock {
                started.complete(Unit)
                finish.await()
                cached = true
            }
        }
        started.await()
        val deletion = async {
            coordinator.withPublicationLock {
                coordinator.invalidate()
                cached = false
            }
        }
        finish.complete(Unit)
        publication.await()
        deletion.await()
        assertFalse(cached)
        assertEquals(generation + 1, coordinator.revision.value)
        coordinator.withPublicationLock {
            if (generation == coordinator.revision.value) cached = true
        }
        assertFalse(cached)
    }
}
