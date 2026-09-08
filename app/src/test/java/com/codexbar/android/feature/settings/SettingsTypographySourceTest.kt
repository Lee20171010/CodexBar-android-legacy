package com.codexbar.android.feature.settings

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsTypographySourceTest {
    @Test
    fun `minimal display has its own preference card and localized appearance intro`() {
        val appDir = listOf(File("."), File("app"))
            .first { File(it, "src/main/AndroidManifest.xml").isFile }
        val screen = File(
            appDir,
            "src/main/java/com/codexbar/android/feature/settings/SettingsScreen.kt"
        ).readText()
        val section = screen.substringAfter("onStyleSelected = viewModel::setAppThemeStyle")
            .substringBefore("LanguageSection(")
        assertTrue(section.contains("Card("))
        assertTrue(section.contains("shape = MaterialTheme.shapes.large"))
        assertTrue(section.contains("containerColor = MaterialTheme.colorScheme.surfaceContainerLow"))
        assertTrue(section.contains("Modifier.padding(16.dp)"))
        assertTrue(section.contains("R.string.minimal_display_title"))
        assertTrue(section.contains("onCheckedChange = viewModel::setMinimalDisplayEnabled"))
        for ((locale, phrase) in listOf("values" to "display mode", "values-ja" to "表示モード")) {
            val strings = File(appDir, "src/main/res/$locale/strings.xml").readText()
            val intro = strings.substringAfter("name=\"settings_preferences_description\">")
                .substringBefore("</string>")
            assertTrue("$locale appearance intro must mention display mode", intro.contains(phrase))
        }
    }

    @Test
    fun `settings toggles use the same themed text styles as preference sections`() {
        val appDir = listOf(File("."), File("app"))
            .first { File(it, "src/main/AndroidManifest.xml").isFile }
        val screen = File(
            appDir,
            "src/main/java/com/codexbar/android/feature/settings/SettingsScreen.kt"
        ).readText().replace("\r\n", "\n")
        val toggle = screen.substringAfter("private fun SettingsToggle(")
            .substringBefore("\n@Composable")

        assertTrue(
            Regex(
                "text = title,\\s+style = MaterialTheme\\.typography\\.titleMedium," +
                    "\\s+color = MaterialTheme\\.colorScheme\\.onSurface\\s*\\)"
            ).containsMatchIn(toggle)
        )
        assertTrue(
            Regex(
                "text = subtitle,\\s+style = MaterialTheme\\.typography\\.bodySmall," +
                    "\\s+color = MaterialTheme\\.colorScheme\\.onSurfaceVariant"
            ).containsMatchIn(toggle)
        )
    }
}
