package io.github.jesperlund.wifidoctor.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.jesperlund.wifidoctor.core.model.EndpointResult
import io.github.jesperlund.wifidoctor.core.model.ServiceCheck
import io.github.jesperlund.wifidoctor.core.model.ServiceResult
import io.github.jesperlund.wifidoctor.ui.theme.LocalStatusColors

/** "DNS 12 · connect 25 · TLS 40 · response 60 ms" or the failure. */
fun EndpointResult.phases(): String =
    if (!ok) {
        (failure?.label ?: "Failed") + (if (failure?.name == "DNS_BLOCKED") " (${addresses.joinToString()})" else "")
    } else {
        "DNS ${dnsMs?.toInt()} · connect ${connectMs?.toInt()} · TLS ${tlsMs?.toInt()} · response ${firstByteMs?.toInt()} ms"
    }

@Composable
fun ServicesCard(check: ServiceCheck, modifier: Modifier = Modifier) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionTitle("Instagram & Teams servers")
            Text(
                "Each server is reached the way the app does: name lookup, connection, secure handshake and first response." +
                    (check.baseline?.totalMs?.let { " Reference server: ${it.toInt()} ms in total." } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            check.results.forEachIndexed { i, r ->
                if (i > 0) HorizontalDivider()
                ServiceSection(r)
            }
        }
    }
}

@Composable
private fun ServiceSection(r: ServiceResult) {
    val c = LocalStatusColors.current
    val (icon, color, label) = when {
        r.allFailed -> Triple(Icons.Filled.Clear, c.fail, "Unreachable")
        r.failed.isNotEmpty() -> Triple(Icons.Filled.Warning, c.warn, "${r.failed.size} of ${r.endpoints.size} servers failing")
        else -> Triple(Icons.Filled.CheckCircle, c.ok, "OK · ${r.medianTotalMs?.toInt()} ms typical")
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.semantics(mergeDescendants = true) {}) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(r.target.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = color)
        }
        r.endpoints.forEach { e ->
            val status = if (e.ok) "OK" else "Problem"
            Column(
                Modifier.semantics(mergeDescendants = true) {
                    contentDescription = "${e.endpoint.role}, ${e.endpoint.host}: $status. ${e.phases()}"
                },
            ) {
                Text(
                    (if (e.ok) "✓ " else "✗ ") + e.endpoint.role,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (e.ok) MaterialTheme.colorScheme.onSurface else c.fail,
                )
                Text(
                    "${e.endpoint.host} · ${e.phases()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
