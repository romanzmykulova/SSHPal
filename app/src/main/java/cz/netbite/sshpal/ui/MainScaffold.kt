package cz.netbite.sshpal.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MergeType
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import cz.netbite.sshpal.ui.claude.ClaudeScreen
import cz.netbite.sshpal.ui.claude.ClaudeViewModel
import cz.netbite.sshpal.ui.files.FilesScreen
import cz.netbite.sshpal.ui.files.FilesViewModel
import cz.netbite.sshpal.ui.git.GitScreen
import cz.netbite.sshpal.ui.git.GitViewModel
import cz.netbite.sshpal.ui.workspaces.WorkspacesScreen
import cz.netbite.sshpal.ui.workspaces.WorkspacesViewModel

enum class MainTab(val label: String, val icon: ImageVector) {
    Workspaces("Workspaces", Icons.Default.Computer),
    Files("Files", Icons.Default.Folder),
    Git("Git", Icons.Default.MergeType),
    Claude("Claude", Icons.Default.Chat),
}

@Composable
fun MainScaffold(
    workspacesViewModel: WorkspacesViewModel,
    filesViewModel: FilesViewModel,
    gitViewModel: GitViewModel,
    claudeViewModel: ClaudeViewModel,
) {
    var tab by rememberSaveable { mutableStateOf(MainTab.Workspaces) }

    Scaffold(
        bottomBar = { CompactNavBar(selected = tab, onSelect = { tab = it }) },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                MainTab.Workspaces -> WorkspacesScreen(viewModel = workspacesViewModel)
                MainTab.Files -> FilesScreen(viewModel = filesViewModel)
                MainTab.Git -> GitScreen(viewModel = gitViewModel)
                MainTab.Claude -> ClaudeScreen(viewModel = claudeViewModel)
            }
        }
    }
}

/**
 * A taller-than-strictly-minimum but still compact bottom bar — Material's
 * NavigationBar is fixed at ~80dp tall to leave room for FAB cradles; this
 * one is ~52dp, gives the main content ~28dp more vertical real estate.
 */
@Composable
private fun CompactNavBar(selected: MainTab, onSelect: (MainTab) -> Unit) {
    // Surface paints behind the system gesture / 3-button nav area too, so
    // the bar appears continuous. Column.navigationBarsPadding lifts the
    // actual icon row above the system nav inset — without this, the
    // system buttons would cover our chips on edge-to-edge displays.
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
    ) {
        Column(modifier = Modifier.navigationBarsPadding()) {
            HorizontalDivider(thickness = 1.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (entry in MainTab.values()) {
                    CompactNavItem(
                        icon = entry.icon,
                        label = entry.label,
                        selected = selected == entry,
                        onClick = { onSelect(entry) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactNavItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val tint =
        if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(22.dp))
        Text(label, color = tint, style = MaterialTheme.typography.labelSmall)
    }
}
