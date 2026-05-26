package cz.netbite.sshpal

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import cz.netbite.sshpal.ui.MainScaffold
import cz.netbite.sshpal.ui.claude.ClaudeViewModel
import cz.netbite.sshpal.ui.files.FilesViewModel
import cz.netbite.sshpal.ui.git.GitViewModel
import cz.netbite.sshpal.ui.theme.SshPalTheme
import cz.netbite.sshpal.ui.workspaces.WorkspacesViewModel

class MainActivity : ComponentActivity() {

    private val workspacesViewModel: WorkspacesViewModel by viewModels {
        val app = application as SshPalApp
        WorkspacesViewModel.Factory(app.repository, app.sshConnector, app.sessions)
    }

    private val filesViewModel: FilesViewModel by viewModels {
        val app = application as SshPalApp
        FilesViewModel.Factory(app.repository, app.sessions)
    }

    private val gitViewModel: GitViewModel by viewModels {
        val app = application as SshPalApp
        GitViewModel.Factory(app.repository, app.sessions)
    }

    private val claudeViewModel: ClaudeViewModel by viewModels {
        val app = application as SshPalApp
        ClaudeViewModel.Factory(app.repository, app.sessions)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SshPalTheme {
                MainScaffold(
                    workspacesViewModel = workspacesViewModel,
                    filesViewModel = filesViewModel,
                    gitViewModel = gitViewModel,
                    claudeViewModel = claudeViewModel,
                )
            }
        }
    }
}
