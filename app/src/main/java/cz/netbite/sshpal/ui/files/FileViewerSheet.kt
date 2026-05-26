package cz.netbite.sshpal.ui.files

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileViewerSheet(viewer: ViewerState, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ViewerHeader(viewer)
            Box(modifier = Modifier.fillMaxSize()) {
                when (viewer) {
                    ViewerState.Closed -> Unit
                    is ViewerState.Loading -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }
                    is ViewerState.Text -> TextBody(viewer)
                    is ViewerState.Binary -> Placeholder("Binary file — preview unavailable (${viewer.sizeBytes} B).")
                    is ViewerState.TooLarge -> Placeholder("File too large to preview (${viewer.sizeBytes} B).")
                    is ViewerState.Failed -> Placeholder("Failed: ${viewer.message}", isError = true)
                }
            }
        }
    }
}

@Composable
private fun ViewerHeader(viewer: ViewerState) {
    val path = when (viewer) {
        ViewerState.Closed -> ""
        is ViewerState.Loading -> viewer.path
        is ViewerState.Text -> viewer.path
        is ViewerState.Binary -> viewer.path
        is ViewerState.TooLarge -> viewer.path
        is ViewerState.Failed -> viewer.path
    }
    Text(
        path,
        style = MaterialTheme.typography.titleSmall,
        fontFamily = FontFamily.Monospace,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun TextBody(text: ViewerState.Text) {
    val vScroll = rememberScrollState()
    val hScroll = rememberScrollState()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(vScroll),
    ) {
        Text(
            text = text.content,
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(hScroll)
                .padding(bottom = 24.dp),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            softWrap = false,
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
