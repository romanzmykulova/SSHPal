package cz.netbite.sshpal.ui.claude

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cz.netbite.sshpal.data.WorkspaceRepository
import cz.netbite.sshpal.ssh.SessionRegistry
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class ClaudeViewModel(
    private val repository: WorkspaceRepository,
    private val registry: SessionRegistry,
) : ViewModel() {

    /**
     * URL the Claude WebView should load. Resolves to:
     *   - the active workspace's `claudeUrl` field, if non-blank
     *   - otherwise `DEFAULT_CLAUDE_URL` (the project's CRM site)
     */
    val claudeUrl: StateFlow<String> = registry.activeWorkspaceId
        .map { id ->
            val ws = id?.let { repository.byId(it) }
            ws?.claudeUrl?.takeIf { it.isNotBlank() } ?: DEFAULT_CLAUDE_URL
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DEFAULT_CLAUDE_URL)

    class Factory(
        private val repository: WorkspaceRepository,
        private val registry: SessionRegistry,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ClaudeViewModel(repository, registry) as T
    }
}
