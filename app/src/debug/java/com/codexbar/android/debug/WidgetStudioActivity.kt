package com.codexbar.android.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.codexbar.android.core.domain.model.AccountConnection
import com.codexbar.android.core.domain.model.AiService
import com.codexbar.android.core.widget.*
import com.codexbar.android.ui.theme.CodexBarTheme

/** Disposable-emulator editor surface; it cannot connect accounts or ship in release builds. */
class WidgetStudioActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(intent.getBooleanExtra("seed_demo", false))
        setContent {
            CodexBarTheme {
                val connections = listOf(
                    AccountConnection.legacy(AiService.CODEX),
                    AccountConnection.legacy(AiService.COPILOT),
                    AccountConnection.legacy(AiService.CLAUDE)
                )
                var config by remember { mutableStateOf(WidgetDisplayConfig(
                    connectionIds = connections.map { it.id })) }
                Scaffold(topBar = { TopAppBar(title = { Text("Widget studio · demo") }) }) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        WidgetStyleEditor(config, connections) { config = it }
                    }
                }
            }
        }
    }
}
