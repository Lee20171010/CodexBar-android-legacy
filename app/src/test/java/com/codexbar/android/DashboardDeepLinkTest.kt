package com.codexbar.android

import android.net.Uri
import com.codexbar.android.core.domain.model.AiService
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class DashboardDeepLinkTest {

    @Test
    fun `a dashboard link names the provider to open`() {
        assertEquals(
            AiService.CLAUDE,
            dashboardServiceOrNull(uri("codexbar", "dashboard", "CLAUDE"))
        )
        assertEquals(
            AiService.CODEX,
            dashboardServiceOrNull(uri("codexbar", "dashboard", "codex"))
        )
    }

    @Test
    fun `links without a known provider open the dashboard as before`() {
        assertNull(dashboardServiceOrNull(null))
        assertNull(dashboardServiceOrNull(uri("codexbar", "dashboard", null)))
        assertNull(dashboardServiceOrNull(uri("codexbar", "dashboard", "NOT_A_PROVIDER")))
        assertNull(dashboardServiceOrNull(uri("https", "dashboard", "CLAUDE")))
        assertNull(dashboardServiceOrNull(uri("codexbar", "settings", "CLAUDE")))
    }

    @Test
    fun `the dashboard opens the linked provider detail`() {
        val appDir = listOf(File("."), File("app"))
            .first { File(it, "src/main/AndroidManifest.xml").isFile }
        val dashboard = File(
            appDir,
            "src/main/java/com/codexbar/android/feature/dashboard/DashboardScreen.kt"
        ).readText().replace("\r\n", "\n")

        assertTrue(dashboard.contains("initialSelectedService: AiService? = null"))
        assertTrue(dashboard.contains("firstOrNull { it.service == initialSelectedService }"))
        assertTrue(dashboard.contains("selectedConnectionId = target.connection.id"))
        assertTrue(dashboard.contains("onInitialSelectionConsumed()"))
    }

    private fun uri(scheme: String, host: String, service: String?): Uri {
        val uri = mock(Uri::class.java)
        `when`(uri.scheme).thenReturn(scheme)
        `when`(uri.host).thenReturn(host)
        `when`(uri.toString()).thenReturn("$scheme://$host")
        `when`(uri.getQueryParameter(EXTRA_DASHBOARD_SERVICE)).thenReturn(service)
        return uri
    }
}
