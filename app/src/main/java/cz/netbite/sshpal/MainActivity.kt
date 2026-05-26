package cz.netbite.sshpal

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import cz.netbite.sshpal.ui.theme.SshPalTheme
import cz.netbite.sshpal.ui.workspaces.WorkspacesScreen
import cz.netbite.sshpal.ui.workspaces.WorkspacesViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: WorkspacesViewModel by viewModels {
        val app = application as SshPalApp
        WorkspacesViewModel.Factory(app.repository, app.sshConnector)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SshPalTheme {
                WorkspacesScreen(viewModel = viewModel)
            }
        }
    }
}
