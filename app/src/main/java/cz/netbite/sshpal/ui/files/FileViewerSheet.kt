package cz.netbite.sshpal.ui.files

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileViewerSheet(
    viewer: ViewerState,
    onDismiss: () -> Unit,
    onDraftChange: (String) -> Unit,
    onSave: () -> Unit,
    onForceSave: () -> Unit,
    onReload: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ViewerHeader(
                viewer = viewer,
                onSave = onSave,
                onReload = onReload,
            )
            (viewer as? ViewerState.Text)?.let { ConflictBanner(it, onForceSave = onForceSave, onReload = onReload) }
            Box(modifier = Modifier.fillMaxSize()) {
                when (viewer) {
                    ViewerState.Closed -> Unit
                    is ViewerState.Loading -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }
                    is ViewerState.Text -> EditorBody(text = viewer, onDraftChange = onDraftChange)
                    is ViewerState.Binary -> Placeholder("Binary file — preview unavailable (${viewer.sizeBytes} B).")
                    is ViewerState.TooLarge -> Placeholder("File too large to preview (${viewer.sizeBytes} B).")
                    is ViewerState.Failed -> Placeholder("Failed: ${viewer.message}", isError = true)
                }
            }
        }
    }
}

@Composable
private fun ViewerHeader(
    viewer: ViewerState,
    onSave: () -> Unit,
    onReload: () -> Unit,
) {
    val path = when (viewer) {
        ViewerState.Closed -> ""
        is ViewerState.Loading -> viewer.path
        is ViewerState.Text -> viewer.path
        is ViewerState.Binary -> viewer.path
        is ViewerState.TooLarge -> viewer.path
        is ViewerState.Failed -> viewer.path
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            val title = when {
                viewer is ViewerState.Text && viewer.isDirty -> "$path  •"
                else -> path
            }
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            (viewer as? ViewerState.Text)?.saveStatus?.let { status ->
                val label = when (status) {
                    SaveStatus.Idle -> if (viewer.isDirty) "Unsaved" else "Saved"
                    SaveStatus.Saving -> "Saving…"
                    is SaveStatus.Conflict -> "Remote changed since open"
                    is SaveStatus.SavedAt -> "Saved"
                    is SaveStatus.Failed -> "Save failed: ${status.message}"
                }
                val color = when (status) {
                    is SaveStatus.Failed, is SaveStatus.Conflict -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Text(label, style = MaterialTheme.typography.bodySmall, color = color)
            }
        }
        if (viewer is ViewerState.Text) {
            IconButton(onClick = onReload) {
                Icon(Icons.Default.Refresh, contentDescription = "Reload")
            }
            IconButton(
                onClick = onSave,
                enabled = viewer.isDirty && viewer.saveStatus !is SaveStatus.Saving,
            ) {
                Icon(Icons.Default.Save, contentDescription = "Save")
            }
        }
    }
}

@Composable
private fun ConflictBanner(
    text: ViewerState.Text,
    onForceSave: () -> Unit,
    onReload: () -> Unit,
) {
    val conflict = text.saveStatus as? SaveStatus.Conflict ?: return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "Remote file changed since you opened it (mtime ${conflict.remoteMtime}).",
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onForceSave) { Text("Overwrite") }
            OutlinedButton(onClick = onReload) { Text("Reload") }
        }
    }
}

@Composable
private fun EditorBody(text: ViewerState.Text, onDraftChange: (String) -> Unit) {
    val vScroll = rememberScrollState()
    val hScroll = rememberScrollState()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(vScroll),
    ) {
        BasicTextField(
            value = text.draft,
            onValueChange = onDraftChange,
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(hScroll)
                .padding(bottom = 24.dp),
            textStyle = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = MaterialTheme.typography.bodySmall.fontSize,
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        )
    }
}

@Composable
private fun Placeholder(message: String, isError: Boolean = false) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            message,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
}
