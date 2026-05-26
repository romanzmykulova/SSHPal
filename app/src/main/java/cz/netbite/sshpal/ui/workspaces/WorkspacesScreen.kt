package cz.netbite.sshpal.ui.workspaces

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cz.netbite.sshpal.data.WorkspaceEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspacesScreen(viewModel: WorkspacesViewModel) {
    val rows by viewModel.rows.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    var editing by remember { mutableStateOf<WorkspaceEntity?>(null) }
    var editorOpen by remember { mutableStateOf(false) }
    var keyRequestFor by remember { mutableStateOf<WorkspaceEntity?>(null) }
    var trustRequest by remember { mutableStateOf<TrustPrompt?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is WorkspaceEvent.NeedsKey -> {
                    val ws = rows.firstOrNull { it.workspace.id == event.workspaceId }?.workspace
                    if (ws != null) {
                        keyRequestFor = ws
                        editing = ws
                        editorOpen = true
                    }
                }
                is WorkspaceEvent.TrustHost -> {
                    trustRequest = TrustPrompt(event.workspaceId, event.fingerprint) { trusted ->
                        event.decision.complete(trusted)
                        trustRequest = null
                    }
                }
                is WorkspaceEvent.HostKeyMismatch -> {
                    snackbar.showSnackbar("Host key changed — refusing to connect")
                }
                is WorkspaceEvent.Toast -> snackbar.showSnackbar(event.message)
            }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Workspaces") }) },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = { Text("Add") },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                onClick = {
                    editing = null
                    keyRequestFor = null
                    editorOpen = true
                },
            )
        },
    ) { padding ->
        WorkspaceList(
            rows = rows,
            padding = padding,
            onConnect = { viewModel.connect(it.id) },
            onDisconnect = { viewModel.disconnect(it.id) },
            onDuplicate = { viewModel.duplicate(it) },
            onEdit = {
                editing = it
                keyRequestFor = null
                editorOpen = true
            },
        )
    }

    if (editorOpen) {
        val existingPublic = editing?.id?.let { viewModel.publicKeyFor(it) }
        WorkspaceEditorSheet(
            initial = editing,
            askForKey = keyRequestFor != null,
            existingPublicKey = existingPublic,
            onDismiss = {
                editorOpen = false
                keyRequestFor = null
            },
            onSave = { ws, pem, passphrase, publicSshLine ->
                viewModel.upsert(ws, pem, passphrase, publicSshLine)
                editorOpen = false
                val pending = keyRequestFor
                keyRequestFor = null
                if (pending != null) viewModel.connect(pending.id)
            },
            onDelete = { ws ->
                viewModel.delete(ws)
                editorOpen = false
            },
        )
    }

    trustRequest?.let { prompt ->
        TrustHostDialog(
            fingerprint = prompt.fingerprint,
            onTrust = { prompt.decide(true) },
            onReject = { prompt.decide(false) },
        )
    }
}

private data class TrustPrompt(
    val workspaceId: Long,
    val fingerprint: String,
    val decide: (Boolean) -> Unit,
)

@Composable
private fun WorkspaceList(
    rows: List<WorkspaceRow>,
    padding: PaddingValues,
    onConnect: (WorkspaceEntity) -> Unit,
    onDisconnect: (WorkspaceEntity) -> Unit,
    onDuplicate: (WorkspaceEntity) -> Unit,
    onEdit: (WorkspaceEntity) -> Unit,
) {
    if (rows.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            Text("No workspaces yet. Tap + to add one.")
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(rows, key = { it.workspace.id }) { row ->
            WorkspaceCard(
                row = row,
                onConnect = onConnect,
                onDisconnect = onDisconnect,
                onDuplicate = onDuplicate,
                onEdit = onEdit,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WorkspaceCard(
    row: WorkspaceRow,
    onConnect: (WorkspaceEntity) -> Unit,
    onDisconnect: (WorkspaceEntity) -> Unit,
    onDuplicate: (WorkspaceEntity) -> Unit,
    onEdit: (WorkspaceEntity) -> Unit,
) {
    val ws = row.workspace
    val isConnected = row.status is ConnectStatus.Connected
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = { if (!isConnected) onConnect(ws) },
        elevation = CardDefaults.cardElevation(defaultElevation = if (row.isActive) 6.dp else 2.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(ws.name, style = MaterialTheme.typography.titleMedium)
            Text("${ws.username}@${ws.host}:${ws.port}", style = MaterialTheme.typography.bodyMedium)
            Text(ws.defaultCwd, style = MaterialTheme.typography.bodySmall)
            // Status chip + progress on its own row so it can't be eaten by
            // the action chip pile underneath on narrow screens.
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusChip(status = row.status, hasKey = row.hasKey)
                if (row.status is ConnectStatus.Connecting) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                }
            }
            // Action chips on their own row with FlowRow so they wrap to a
            // second line if they don't fit horizontally on small displays.
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (isConnected) {
                    AssistChip(
                        onClick = { onDisconnect(ws) },
                        label = { Text("Disconnect") },
                    )
                }
                AssistChip(
                    onClick = { onDuplicate(ws) },
                    label = { Text("Duplicate") },
                )
                AssistChip(
                    onClick = { onEdit(ws) },
                    label = { Text("Edit") },
                )
            }
        }
    }
}

@Composable
private fun StatusChip(status: ConnectStatus, hasKey: Boolean) {
    val (label, color) = when (status) {
        ConnectStatus.Idle -> (if (hasKey) "Ready" else "Needs key") to MaterialTheme.colorScheme.secondary
        ConnectStatus.Connecting -> "Connecting…" to MaterialTheme.colorScheme.secondary
        is ConnectStatus.Connected -> "Connected: ${status.whoami}" to MaterialTheme.colorScheme.primary
        is ConnectStatus.Failure -> "Failed: ${status.message}" to MaterialTheme.colorScheme.error
    }
    AssistChip(
        onClick = {},
        enabled = false,
        label = { Text(label) },
        colors = AssistChipDefaults.assistChipColors(
            disabledLabelColor = color,
        ),
    )
}
