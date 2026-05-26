package cz.netbite.sshpal.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cz.netbite.sshpal.ssh.GrepHit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    onOpenResult: (path: String, line: Int) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val pattern by viewModel.pattern.collectAsState()
    val dir by viewModel.dir.collectAsState()
    val literal by viewModel.literal.collectAsState()

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { TopAppBar(title = { Text("Search") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = pattern,
                    onValueChange = viewModel::setPattern,
                    label = { Text("Pattern") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { viewModel.search() }),
                )
                OutlinedTextField(
                    value = dir,
                    onValueChange = viewModel::setDir,
                    label = { Text("Directory") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = viewModel::search,
                        enabled = pattern.isNotBlank() && dir.isNotBlank() && state !is SearchState.NoSession,
                    ) { Text("Search") }
                    OutlinedButton(onClick = viewModel::useCurrentCwd) { Text("Use cwd") }
                    FilterChip(
                        selected = !literal,
                        onClick = { viewModel.setLiteral(!literal) },
                        label = { Text("regex") },
                    )
                }
            }
            HorizontalDivider()
            ResultsArea(state = state, onOpenResult = onOpenResult)
        }
    }
}

@Composable
private fun ResultsArea(
    state: SearchState,
    onOpenResult: (path: String, line: Int) -> Unit,
) {
    when (state) {
        SearchState.NoSession -> CenteredText("No active session. Connect a workspace first.")
        SearchState.Idle -> CenteredText("Enter a pattern, optionally adjust the directory, then tap Search.")
        SearchState.Loading -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) { CircularProgressIndicator() }
        is SearchState.Failure -> CenteredText(
            "Search failed: ${state.message}",
            isError = true,
        )
        is SearchState.Results -> {
            if (state.hits.isEmpty()) {
                CenteredText("No matches for '${state.pattern}' in ${state.baseDir}.")
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 4.dp),
                ) {
                    items(state.hits) { hit ->
                        SearchResultRow(
                            hit = hit,
                            baseDir = state.baseDir,
                            onClick = { onOpenResult(hit.path, hit.line) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultRow(hit: GrepHit, baseDir: String, onClick: () -> Unit) {
    val displayPath = run {
        val prefix = if (baseDir.endsWith("/")) baseDir else "$baseDir/"
        if (hit.path.startsWith(prefix)) hit.path.removePrefix(prefix) else hit.path
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            "$displayPath:${hit.line}",
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            hit.content.trim(),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CenteredText(text: String, isError: Boolean = false) {
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isError) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
