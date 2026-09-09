package com.codexbar.android.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.codexbar.android.R
import com.codexbar.android.core.domain.model.AccountConnection
import com.codexbar.android.core.domain.model.AiService

/** Explicit, accessible controls also work when dragging conflicts with launcher scrolling. */
@Composable
fun ProviderOrderEditor(services: List<AiService>, onChange: (List<AiService>) -> Unit) {
    Column {
        services.forEachIndexed { index, service ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${index + 1}", Modifier.width(28.dp), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                ProviderIcon(service, Modifier.size(24.dp))
                Spacer(Modifier.width(10.dp))
                Text(service.displayName, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton(enabled = index > 0, onClick = { onChange(services.moved(index, index - 1)) }) {
                    Icon(Icons.Rounded.ArrowUpward, stringResource(R.string.provider_order_move_up, service.displayName))
                }
                IconButton(enabled = index < services.lastIndex,
                    onClick = { onChange(services.moved(index, index + 1)) }) {
                    Icon(Icons.Rounded.ArrowDownward, stringResource(R.string.provider_order_move_down, service.displayName))
                }
            }
        }
    }
}

private fun List<AiService>.moved(from: Int, to: Int): List<AiService> = toMutableList().apply {
    add(to, removeAt(from))
}

/** Connection-aware variant used by the widget studio, where one provider can have several accounts. */
@Composable
fun ConnectionOrderEditor(connections: List<AccountConnection>, onChange: (List<AccountConnection>) -> Unit) {
    Column {
        connections.forEachIndexed { index, connection ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${index + 1}", Modifier.width(28.dp), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                ProviderIcon(connection.service, Modifier.size(24.dp))
                Spacer(Modifier.width(10.dp))
                Text(connection.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton(enabled = index > 0, onClick = { onChange(connections.movedConnections(index, index - 1)) }) {
                    Icon(Icons.Rounded.ArrowUpward, stringResource(R.string.provider_order_move_up, connection.name))
                }
                IconButton(enabled = index < connections.lastIndex,
                    onClick = { onChange(connections.movedConnections(index, index + 1)) }) {
                    Icon(Icons.Rounded.ArrowDownward, stringResource(R.string.provider_order_move_down, connection.name))
                }
            }
        }
    }
}

private fun List<AccountConnection>.movedConnections(from: Int, to: Int): List<AccountConnection> = toMutableList().apply {
    add(to, removeAt(from))
}
