package cz.netbite.sshpal.ui.files

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cz.netbite.sshpal.ssh.RemoteEntry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(viewModel: FilesViewModel) {
    val state by viewModel.state.collectAsState()
    val viewer by viewModel.viewer.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    when (val s = state) {
                        FilesState.NoSession -> Text("Files")
                        is FilesState.Browsing -> Column {
                            Text(s.workspaceLabel, style = MaterialTheme.typography.titleMedium)
                            Text(
                                s.path,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    val canGoUp = (state as? FilesState.Browsing)?.let { FilesViewModel.parentOf(it.path) != null } == true
                    if (canGoUp) {
                        IconButton(onClick = { viewModel.upDir() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Up")
                        }
                    }
                },
                actions = {
                    if (state is FilesState.Browsing) {
                        IconButton(onClick = { viewModel.refreshCurrent() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                FilesState.NoSession -> EmptySession()
                is FilesState.Browsing -> BrowsingView(
                    state = s,
                    onDir = viewModel::openDir,
                    onFile = viewModel::openFile,
                )
            }
        }
    }

    if (viewer !is ViewerState.Closed) {
        FileViewerSheet(viewer = viewer, onDismiss = { viewModel.closeViewer() })
    }
}

@Composable
private fun EmptySession() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("No active session.", style = MaterialTheme.typography.titleMedium)
            Text(
                "Connect a workspace first (Workspaces tab → tap a card).",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun BrowsingView(
    state: FilesState.Browsing,
    onDir: (RemoteEntry) -> Unit,
    onFile: (RemoteEntry) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (state.error != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                Text(
                    "Error: ${state.error}",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        if (state.loading && state.entries.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Column
        }
        if (state.entries.isEmpty() && !state.loading && state.error == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Empty directory.")
            }
            return@Column
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            items(state.entries, key = { it.path }) { entry ->
                EntryRow(
                    entry = entry,
                    onClick = { if (entry.isDirectory) onDir(entry) else onFile(entry) },
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun EntryRow(entry: RemoteEntry, onClick: () -> Unit) {
    val icon = when {
        entry.isSymlink -> Icons.Default.Link
        entry.isDirectory -> Icons.Default.Folder
        else -> Icons.Default.InsertDriveFile
    }
    val tail = if (entry.isDirectory) "dir" else "${entry.sizeBytes} B"
    ListItem(
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(entry.name) },
        supportingContent = {
            Text(tail, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        tonalElevation = 0.dp,
    )
}
