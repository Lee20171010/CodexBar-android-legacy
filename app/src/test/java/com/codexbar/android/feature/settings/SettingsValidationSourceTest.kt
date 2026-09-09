package com.codexbar.android.feature.settings

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsValidationSourceTest {
    private val appDir: File = listOf(File("."), File("app"))
        .first { File(it, "src/main/AndroidManifest.xml").isFile }

    @Test
    fun `manual credential validation does not overwrite active credential before success`() {
        val source = File(
            appDir,
            "src/main/java/com/codexbar/android/feature/settings/SettingsViewModel.kt"
        ).readText().replace("\r\n", "\n")

        val validationCall = source.indexOf("val result = repo.validateCredential(credential)")
        val successBranch = source.indexOf("is Result.Success -> {", startIndex = validationCall)
        val saveCredential = source.indexOf("saveAccountCredential(service, state, credential)", startIndex = successBranch)
        val saveHelper = source.substringAfter("private suspend fun saveAccountCredential(")
            .substringBefore("fun renameConnection(")
        val connectedHealth = saveHelper.indexOf("connectionHealthStore.update(connection, ConnectionHealth.CONNECTED)")
        val failureBranch = source.indexOf("is Result.Failure ->", startIndex = successBranch)

        assertTrue("manual validation must validate the candidate credential", validationCall >= 0)
        assertTrue("manual validation must save only from the success branch", successBranch > validationCall)
        assertTrue("manual validation must persist the credential after success", saveCredential > successBranch)
        assertTrue(
            "manual validation must mark the saved credential connected only in the success branch",
            connectedHealth > saveHelper.indexOf("prefsManager.saveCredential(it, credential)") &&
                connectedHealth > saveHelper.indexOf("prefsManager.createConnection(service, name, credential)")
        )
        assertTrue("manual validation must not save from the failure branch", failureBranch == -1 || saveCredential < failureBranch)
        assertFalse(source.contains("val result = repo.validateCredential()\n"))
    }

    @Test
    fun `device account linking validates transient credential before persistence`() {
        val source = File(
            appDir,
            "src/main/java/com/codexbar/android/feature/settings/SettingsViewModel.kt"
        ).readText().replace("\r\n", "\n")
        val deviceFlow = source
            .substringAfter("fun startAccountLink(service: AiService)")
            .substringBefore("\n    fun setRefreshInterval")

        val transientValidation = deviceFlow.indexOf(
            "repositoryFor(service).validateCredential(credential)"
        )
        val successBranch = deviceFlow.indexOf("is Result.Success -> {", transientValidation)
        val persistence = deviceFlow.indexOf(
            "saveAccountCredential(service, target, credential)",
            successBranch
        )
        val cancellationCatch = deviceFlow.indexOf("catch (e: CancellationException)")
        val generalCatch = deviceFlow.indexOf("catch (e: Exception)")

        assertTrue(transientValidation >= 0)
        assertTrue(successBranch > transientValidation)
        assertTrue(persistence > successBranch)
        assertTrue(cancellationCatch > persistence)
        assertTrue(generalCatch > cancellationCatch)
        assertFalse(deviceFlow.contains("repositoryFor(service).validateCredential()"))
        assertFalse(deviceFlow.contains("prefsManager.deleteCredential(service)"))
    }
}
