package cz.netbite.sshpal.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import cz.netbite.sshpal.ui.claude.ClaudeScreen
import cz.netbite.sshpal.ui.files.FilesScreen
import cz.netbite.sshpal.ui.files.FilesViewModel
import cz.netbite.sshpal.ui.workspaces.WorkspacesScreen
import cz.netbite.sshpal.ui.workspaces.WorkspacesViewModel

enum class MainTab(val label: String) {
    Workspaces("Workspaces"),
    Files("Files"),
    Claude("Claude"),
}

@Composable
fun MainScaffold(
    workspacesViewModel: WorkspacesViewModel,
    filesViewModel: FilesViewModel,
) {
    var tab by rememberSaveable { mutableStateOf(MainTab.Workspaces) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == MainTab.Workspaces,
                    onClick = { tab = MainTab.Workspaces },
                    icon = { Icon(Icons.Default.Computer, contentDescription = null) },
                    label = { Text(MainTab.Workspaces.label) },
                )
                NavigationBarItem(
                    selected = tab == MainTab.Files,
                    onClick = { tab = MainTab.Files },
                    icon = { Icon(Icons.Default.Folder, contentDescription = null) },
                    label = { Text(MainTab.Files.label) },
                )
                NavigationBarItem(
                    selected = tab == MainTab.Claude,
                    onClick = { tab = MainTab.Claude },
                    icon = { Icon(Icons.Default.Chat, contentDescription = null) },
                    label = { Text(MainTab.Claude.label) },
                )
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                MainTab.Workspaces -> WorkspacesScreen(viewModel = workspacesViewModel)
                MainTab.Files -> FilesScreen(viewModel = filesViewModel)
                MainTab.Claude -> ClaudeScreen()
            }
        }
    }
}
