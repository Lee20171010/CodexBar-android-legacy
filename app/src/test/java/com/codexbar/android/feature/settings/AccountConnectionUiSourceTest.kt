package com.codexbar.android.feature.settings

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountConnectionUiSourceTest {
    private val appDir: File = listOf(File("."), File("app"))
        .first { File(it, "src/main/AndroidManifest.xml").isFile }

    @Test
    fun `device code is selectable copyable and opens the sign-in page`() {
        val source = settingsSource()

        assertTrue(source.contains("SelectionContainer(modifier = Modifier.weight(1f))"))
        assertTrue(source.contains("deviceCodeForClipboard(prompt.userCode)"))
        assertTrue(source.contains("onCopyAccountCode(clipboardCode)"))
        assertTrue(source.contains("copiedCode = clipboardCode"))
        assertTrue(source.contains("onOpenAccountLink(prompt.verificationUrl)"))
        assertFalse(source.contains("lastOpenedCode"))
        assertFalse(source.contains("LaunchedEffect(prompt?.userCode)"))

        val promptCard = source.indexOf("prompt?.let")
        val copyAction = source.indexOf("onCopyAccountCode(clipboardCode)", promptCard)
        val openAction = source.indexOf("onOpenAccountLink(prompt.verificationUrl)", copyAction)
        assertTrue("copy action must be presented before browser navigation", copyAction in 0..<openAction)
    }

    @Test
    fun `safe account flow precedes an explicit manual fallback`() {
        val source = settingsSource()
        val accountLink = source.indexOf("AccountLinkControls(")
        val manualFields = source.indexOf("ManualCredentialFields(")

        assertTrue(accountLink >= 0)
        assertTrue(manualFields > accountLink)
        assertTrue(source.contains("R.string.account_manual_setup"))
        assertTrue(source.contains("R.string.account_security_note"))
    }

    @Test
    fun `device-code prompt explains the complete safe sign-in sequence`() {
        val source = settingsSource()

        assertTrue(source.contains("R.string.account_link_steps_title"))
        assertTrue(source.contains("R.string.account_link_step_copy"))
        assertTrue(source.contains("R.string.account_link_step_open"))
        assertTrue(source.contains("R.string.account_link_step_authorize"))
        assertTrue(source.contains("R.string.account_link_step_return"))
        assertTrue(source.contains("R.string.account_link_retry_hint"))
        assertTrue(source.contains("R.string.account_link_code_safety"))

        val copyStep = source.indexOf("R.string.account_link_step_copy")
        val openStep = source.indexOf("R.string.account_link_step_open", copyStep)
        val authorizeStep = source.indexOf("R.string.account_link_step_authorize", openStep)
        val returnStep = source.indexOf("R.string.account_link_step_return", authorizeStep)
        assertTrue(copyStep in 0..<openStep)
        assertTrue(openStep < authorizeStep)
        assertTrue(authorizeStep < returnStep)
    }

    @Test
    fun `companion providers never expose direct provider secret setup`() {
        val source = settingsSource()

        assertTrue(source.contains("service == AiService.GEMINI -> GeminiCompanionSetup("))
        assertTrue(source.contains("R.string.credential_gemini_companion_body"))
        assertTrue(source.contains("R.string.credential_gemini_pairing_code"))
        assertTrue(source.contains("PasswordVisualTransformation()"))
        assertTrue(
            source.contains(
                "if (service != AiService.GEMINI && service != AiService.CLAUDE && service != AiService.ANTIGRAVITY)"
            )
        )
        assertFalse(source.contains("GEMINI_STATS_COMMAND"))
        assertFalse(source.contains("R.string.credential_google_client_support"))
        assertFalse(source.contains("R.string.credential_oauth_client_id"))
    }

    private fun settingsSource(): String {
        return File(
            appDir,
            "src/main/java/com/codexbar/android/feature/settings/SettingsScreen.kt"
        ).readText().replace("\r\n", "\n")
    }
}
