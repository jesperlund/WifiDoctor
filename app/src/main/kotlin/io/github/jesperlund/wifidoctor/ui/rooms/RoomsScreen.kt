package io.github.jesperlund.wifidoctor.ui.rooms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jesperlund.wifidoctor.core.history.RoomSummary
import io.github.jesperlund.wifidoctor.ui.components.DiagnosisCard
import io.github.jesperlund.wifidoctor.ui.components.SectionTitle
import io.github.jesperlund.wifidoctor.ui.components.SeverityBadge
import io.github.jesperlund.wifidoctor.ui.components.SignalBars
import io.github.jesperlund.wifidoctor.ui.components.signalColor
import io.github.jesperlund.wifidoctor.ui.theme.LocalStatusColors

@Composable
fun RoomsScreen(snackbar: SnackbarHostState, viewModel: RoomsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val rooms by viewModel.rooms.collectAsStateWithLifecycle()
    var pendingDelete by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.messageShown()
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { WalkCard(state, viewModel) }
        (state.phase as? WalkPhase.Done)?.let { done ->
            item { DiagnosisCard(done.diagnosis, title = "Result for ${done.room}") }
        }
        item {
            SectionTitle("Rooms, best to worst")
            if (rooms.isEmpty()) {
                Text(
                    "No rooms yet. Stand in a room, name it and start a walk test. Repeat in each room to find dead zones.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        itemsIndexed(rooms, key = { _, r -> r.roomName.lowercase() }) { index, room ->
            RoomRow(index + 1, room, onDelete = { pendingDelete = room.roomName })
        }
    }

    pendingDelete?.let { name ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete $name?") },
            text = { Text("All walk tests for this room will be removed from history.") },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteRoom(name); pendingDelete = null }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun WalkCard(state: RoomsUiState, viewModel: RoomsViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle("Walk test")
            Text(
                "Go to a room, enter its name and hold the phone as you normally would. WifiDoctor measures for 10 seconds.",
                style = MaterialTheme.typography.bodyMedium,
            )
            when (val phase = state.phase) {
                is WalkPhase.Measuring -> {
                    Text("Measuring ${phase.room}… ${phase.secondsLeft} s left", style = MaterialTheme.typography.titleMedium)
                    phase.latestRssi?.let { Text("Current signal: $it dBm", style = MaterialTheme.typography.bodyMedium) }
                    LinearProgressIndicator(
                        progress = { phase.fraction },
                        modifier = Modifier.fillMaxWidth().semantics {
                            contentDescription = "Walk test progress"
                            stateDescription = "${phase.secondsLeft} seconds left"
                        },
                    )
                    OutlinedButton(onClick = viewModel::cancel, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text("Cancel")
                    }
                }
                else -> {
                    OutlinedTextField(
                        value = state.roomName,
                        onValueChange = viewModel::onRoomNameChange,
                        label = { Text("Room name") },
                        placeholder = { Text("e.g. Kitchen") },
                        singleLine = true,
                        isError = state.nameError != null,
                        supportingText = state.nameError?.let { error -> @Composable { Text(error) } },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { viewModel.startWalk() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(onClick = { viewModel.startWalk() }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                        Text("Start 10-second measurement")
                    }
                }
            }
        }
    }
}

@Composable
private fun RoomRow(rank: Int, room: RoomSummary, onDelete: () -> Unit) {
    val quality = room.quality
    val description = buildString {
        append("Rank $rank, ${room.roomName}. ${quality.label} signal")
        room.rssiDbm?.let { append(", $it dBm") }
        room.gatewayLossPercent?.let { append(", ${it.toInt()} percent loss") }
        if (room.isDeadZone) append(". Dead zone")
    }
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .heightIn(min = 56.dp)
                .semantics(mergeDescendants = true) { contentDescription = description },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("$rank", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.width(32.dp))
            SignalBars(room.rssiDbm)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(room.roomName, style = MaterialTheme.typography.titleMedium)
                Text(
                    buildString {
                        append(quality.label)
                        room.rssiDbm?.let { append(" · $it dBm") }
                        room.gatewayAvgMs?.let { append(" · ${it.toInt()} ms") }
                        room.gatewayLossPercent?.takeIf { it > 0 }?.let { append(" · ${it.toInt()}% loss") }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = signalColor(quality),
                )
                if (room.isDeadZone) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Warning, null, tint = LocalStatusColors.current.fail, modifier = Modifier.padding(end = 4.dp))
                        Text("Dead zone", style = MaterialTheme.typography.labelMedium, color = LocalStatusColors.current.fail)
                    }
                } else {
                    SeverityBadge(room.severity)
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete ${room.roomName}")
            }
        }
    }
}
