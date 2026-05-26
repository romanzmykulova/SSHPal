package cz.netbite.sshpal.ui.git

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitScreen(viewModel: GitViewModel) {
    val state by viewModel.state.collectAsState()
    val diff by viewModel.diff.collectAsState()
    val commitMessage by viewModel.commitMessage.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    when (val s = state) {
                        is GitState.Ready -> Column {
                            Text("Git — ${s.branch}", style = MaterialTheme.typography.titleMedium)
                            Text(s.cwd, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        is GitState.NotARepo -> Text("Git — not a repo")
                        else -> Text("Git")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                GitState.NoSession -> EmptySession()
                GitState.Loading -> Loading()
                is GitState.NotARepo -> NotRepo(s)
                is GitState.DubiousOwnership -> DubiousOwnershipView(
                    state = s,
                    onTrust = viewModel::trustCurrentDirectory,
                )
                is GitState.Failure -> Failure(s.message)
                is GitState.Ready -> ReadyView(
                    state = s,
                    commitMessage = commitMessage,
                    onCommitMessageChange = viewModel::setCommitMessage,
                    onStageAll = viewModel::stageAll,
                    onUnstageAll = viewModel::unstageAll,
                    onCommit = viewModel::commit,
                    onPush = viewModel::push,
                    onPull = viewModel::pull,
                    onDiff = { viewModel.showDiff(staged = false) },
                    onDiffStaged = { viewModel.showDiff(staged = true) },
                )
            }
        }
    }

    if (diff !is DiffState.Closed) {
        DiffSheet(diff = diff, onDismiss = viewModel::closeDiff)
    }
}

@Composable
private fun EmptySession() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("No active session.", style = MaterialTheme.typography.titleMedium)
            Text("Connect a workspace first.", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun Loading() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun NotRepo(state: GitState.NotARepo) {
    Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Column {
            Text("Not a git repository.", style = MaterialTheme.typography.titleMedium)
            Text(state.cwd, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
            Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun DubiousOwnershipView(state: GitState.DubiousOwnership, onTrust: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Git refuses to touch this repo.", style = MaterialTheme.typography.titleMedium)
        Text(
            "Files in ${state.cwd} are owned by a different user than the SSH login. " +
                "Git's `safe.directory` guard blocks any operation until you explicitly trust the path.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            state.message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            "Tapping Trust will run on the server:\n  git config --global --add safe.directory ${state.cwd}",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onTrust) { Text("Trust this directory") }
    }
}

@Composable
private fun Failure(message: String) {
    Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            "Failed: $message",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun ReadyView(
    state: GitState.Ready,
    commitMessage: String,
    onCommitMessageChange: (String) -> Unit,
    onStageAll: () -> Unit,
    onUnstageAll: () -> Unit,
    onCommit: () -> Unit,
    onPush: () -> Unit,
    onPull: () -> Unit,
    onDiff: () -> Unit,
    onDiffStaged: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        state.lastOpMessage?.let { msg ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (state.lastOpFailed) MaterialTheme.colorScheme.errorContainer
                        else MaterialTheme.colorScheme.secondaryContainer,
                    )
                    .padding(12.dp),
            ) {
                Text(
                    msg,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = if (state.lastOpFailed) MaterialTheme.colorScheme.onErrorContainer
                    else MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = onDiff) { Text("Diff") }
            OutlinedButton(onClick = onDiffStaged) { Text("Diff (staged)") }
            OutlinedButton(onClick = onPull) { Text("Pull") }
        }
        HorizontalDivider()
        if (state.files.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Working tree clean.")
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = true),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(state.files, key = { it.path + it.raw }) { file ->
                    FileRow(file)
                    HorizontalDivider()
                }
            }
        }
        HorizontalDivider()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = commitMessage,
                onValueChange = onCommitMessageChange,
                label = { Text("Commit message") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 1,
                maxLines = 4,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onStageAll) { Text("Stage all") }
                OutlinedButton(onClick = onUnstageAll) { Text("Unstage all") }
                Button(
                    onClick = onCommit,
                    enabled = commitMessage.isNotBlank(),
                ) { Text("Commit") }
                Button(onClick = onPush) { Text("Push") }
            }
        }
    }
}

@Composable
private fun FileRow(file: GitFileStatus) {
    val staged = file.indexCode != ' ' && file.indexCode != '?'
    val color = when {
        file.isUntracked -> MaterialTheme.colorScheme.tertiary
        staged -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            file.raw,
            fontFamily = FontFamily.Monospace,
            color = color,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            file.path,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiffSheet(diff: DiffState, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = when (diff) {
                        DiffState.Closed -> ""
                        DiffState.Loading -> "Loading…"
                        is DiffState.Loaded -> diff.title
                        is DiffState.Failed -> diff.title
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismiss) { Text("Close") }
            }
            Box(modifier = Modifier.fillMaxSize()) {
                when (diff) {
                    DiffState.Closed -> Unit
                    DiffState.Loading -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }
                    is DiffState.Loaded -> {
                        val v = rememberScrollState()
                        val h = rememberScrollState()
                        Box(modifier = Modifier.fillMaxSize().verticalScroll(v)) {
                            Text(
                                text = diff.text,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(h)
                                    .padding(bottom = 24.dp),
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                                softWrap = false,
                            )
                        }
                    }
                    is DiffState.Failed -> Text(
                        diff.message,
                        color = MaterialTheme.colorScheme.error,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
