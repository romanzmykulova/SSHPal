package cz.netbite.sshpal.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cz.netbite.sshpal.data.WorkspaceRepository
import cz.netbite.sshpal.ssh.GrepHit
import cz.netbite.sshpal.ssh.SessionRegistry
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

sealed interface SearchState {
    data object NoSession : SearchState
    data object Idle : SearchState
    data object Loading : SearchState
    data class Results(val baseDir: String, val pattern: String, val hits: List<GrepHit>) : SearchState
    data class Failure(val message: String) : SearchState
}

class SearchViewModel(
    private val repository: WorkspaceRepository,
    private val registry: SessionRegistry,
) : ViewModel() {

    private val _pattern = MutableStateFlow("")
    val pattern: StateFlow<String> = _pattern.asStateFlow()

    private val _dir = MutableStateFlow("")
    val dir: StateFlow<String> = _dir.asStateFlow()

    private val _literal = MutableStateFlow(true)
    val literal: StateFlow<Boolean> = _literal.asStateFlow()

    private val _state = MutableStateFlow<SearchState>(SearchState.NoSession)
    val state: StateFlow<SearchState> = _state.asStateFlow()

    private var searchJob: Job? = null

    init {
        registry.activeWorkspaceId
            .onEach { id ->
                if (id == null) {
                    _state.value = SearchState.NoSession
                    return@onEach
                }
                if (_state.value is SearchState.NoSession) {
                    _state.value = SearchState.Idle
                }
                // Seed dir from active workspace's current cwd / defaultCwd
                // the first time we see this workspace, but don't clobber a
                // user-edited value.
                if (_dir.value.isBlank()) {
                    val cwd = registry.cwdFor(id) ?: repository.byId(id)?.defaultCwd
                    if (!cwd.isNullOrBlank()) _dir.value = cwd
                }
            }
            .launchIn(viewModelScope)
    }

    fun setPattern(value: String) { _pattern.value = value }
    fun setDir(value: String) { _dir.value = value }
    fun setLiteral(value: Boolean) { _literal.value = value }

    fun useCurrentCwd() {
        val id = registry.activeWorkspaceId.value ?: return
        registry.cwdFor(id)?.let { _dir.value = it }
    }

    fun search() {
        val session = registry.active() ?: return
        val pattern = _pattern.value.trim()
        val dir = _dir.value.trim()
        if (pattern.isEmpty() || dir.isEmpty()) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _state.value = SearchState.Loading
            val outcome = runCatching { session.grep(pattern, dir, literal = _literal.value) }
            _state.value = outcome.fold(
                onSuccess = { hits -> SearchState.Results(dir, pattern, hits) },
                onFailure = { e -> SearchState.Failure(e.message ?: e::class.java.simpleName) },
            )
        }
    }

    class Factory(
        private val repository: WorkspaceRepository,
        private val registry: SessionRegistry,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SearchViewModel(repository, registry) as T
    }
}
