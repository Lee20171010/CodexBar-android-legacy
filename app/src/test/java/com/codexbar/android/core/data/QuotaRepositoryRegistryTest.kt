package com.codexbar.android.core.data

import com.codexbar.android.core.domain.model.AiService
import com.codexbar.android.core.domain.model.AccountConnection
import com.codexbar.android.core.domain.model.AppError
import com.codexbar.android.core.domain.model.Credential
import com.codexbar.android.core.domain.model.QuotaInfo
import com.codexbar.android.core.domain.model.Result
import com.codexbar.android.core.domain.repository.QuotaRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class QuotaRepositoryRegistryTest {
    private val repositories = AiService.entries.associateWith { FakeQuotaRepository() }

    @Test
    fun `registry preserves service order and resolves every provider`() {
        val registry = QuotaRepositoryRegistry(repositories)

        assertEquals(AiService.entries, registry.entries().map { it.first })
        AiService.entries.forEach { service ->
            assertSame(repositories.getValue(service), registry.repositoryFor(service))
        }
    }

    @Test(expected = IllegalStateException::class)
    fun `registry fails closed when a provider binding is missing`() {
        QuotaRepositoryRegistry(repositories - AiService.entries.last())
    }

    private class FakeQuotaRepository : QuotaRepository {
        override suspend fun fetchQuota(connection: AccountConnection?): Result<QuotaInfo, AppError> = error("not used")

        override suspend fun validateCredential(): Result<Unit, AppError> = error("not used")

        override suspend fun validateCredential(credential: Credential): Result<Unit, AppError> = error("not used")
    }
}
