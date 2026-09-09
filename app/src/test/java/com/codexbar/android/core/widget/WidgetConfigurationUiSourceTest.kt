package com.codexbar.android.core.widget

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetConfigurationUiSourceTest {
    private val appDir: File = listOf(File("."), File("app"))
        .first { File(it, "src/main/AndroidManifest.xml").isFile }

    @Test
    fun `widget configuration uses expressive provider aware surfaces`() {
        val source = File(
            appDir,
            "src/main/java/com/codexbar/android/core/widget/WidgetConfigurationActivity.kt"
        ).readText().replace("\r\n", "\n")

        assertTrue(source.contains("BottomAppBar("))
        assertTrue(source.contains(".widthIn(max = 720.dp)"))
        assertTrue(source.contains("MaterialTheme.shapes.extraLarge"))
        assertTrue(source.contains("CodexBarStateColors.providerAccent(service.service)"))
        assertTrue(source.contains("ProviderIcon(service.service,"))
    }
}
