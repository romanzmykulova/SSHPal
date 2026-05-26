package cz.netbite.sshpal.ui.files

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cz.netbite.sshpal.data.WorkspaceRepository
import cz.netbite.sshpal.ssh.RemoteEntry
import cz.netbite.sshpal.ssh.SessionRegistry
import cz.netbite.sshpal.ssh.SshSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.nio.charset.StandardCharsets

sealed interface FilesState {
    data object NoSession : FilesState
    data class Browsing(
        val workspaceLabel: String,
        val path: String,
        val entries: List<RemoteEntry>,
        val loading: Boolean,
        val error: String?,
    ) : FilesState
}

sealed interface ViewerState {
    data object Closed : ViewerState
    data class Loading(val path: String) : ViewerState

    data class Text(
        val path: String,
        val original: String,
        val draft: String,
        val originalMtime: Long,
        val saveStatus: SaveStatus,
    ) : ViewerState {
        val isDirty: Boolean get() = draft != original
    }

    data class Binary(val path: String, val sizeBytes: Long) : ViewerState
    data class TooLarge(val path: String, val sizeBytes: Long) : ViewerState
    data class Failed(val path: String, val message: String) : ViewerState
}

sealed interface SaveStatus {
    data object Idle : SaveStatus
    data object Saving : SaveStatus
    data class Conflict(val remoteMtime: Long) : SaveStatus
    data class SavedAt(val mtime: Long) : SaveStatus
    data class Failed(val message: String) : SaveStatus
}

class FilesViewModel(
    private val repository: WorkspaceRepository,
    private val registry: SessionRegistry,
) : ViewModel() {

    private val _state = MutableStateFlow<FilesState>(FilesState.NoSession)
    val state: StateFlow<FilesState> = _state.asStateFlow()

    private val _viewer = MutableStateFlow<ViewerState>(ViewerState.Closed)
    val viewer: StateFlow<ViewerState> = _viewer.asStateFlow()

    private var currentPath: String = "/"
    private var currentWorkspaceId: Long? = null
    private var listingJob: Job? = null
    private var viewerJob: Job? = null

    init {
        combine(registry.activeWorkspaceId, registry.sessions) { id, sessions ->
            id?.let { sessions[it] }
        }
            .onEach { session ->
                val id = registry.activeWorkspaceId.value
                if (session == null || id == null) {
                    currentWorkspaceId = null
                    _state.value = FilesState.NoSession
                    _viewer.value = ViewerState.Closed
                } else if (id != currentWorkspaceId) {
                    currentWorkspaceId = id
                    val ws = repository.byId(id)
                    currentPath = ws?.defaultCwd?.takeIf { it.isNotBlank() } ?: "/"
                    refresh(session)
                }
            }
            .launchIn(viewModelScope)
    }

    fun openDir(entry: RemoteEntry) {
        if (!entry.isDirectory) return
        val session = registry.active() ?: return
        currentPath = entry.path
        refresh(session)
    }

    fun navigateTo(path: String) {
        val session = registry.active() ?: return
        currentPath = path
        refresh(session)
    }

    fun upDir() {
        val parent = parentOf(currentPath) ?: return
        val session = registry.active() ?: return
        currentPath = parent
        refresh(session)
    }

    fun refreshCurrent() {
        val session = registry.active() ?: return
        refresh(session)
    }

    fun openFile(entry: RemoteEntry) {
        if (entry.isDirectory) return
        val session = registry.active() ?: return
        viewerJob?.cancel()
        viewerJob = viewModelScope.launch {
            _viewer.value = ViewerState.Loading(entry.path)
            val outcome = runCatching { session.readBytes(entry.path, MAX_PREVIEW_BYTES) }
            _viewer.value = outcome.fold(
                onSuccess = { result ->
                    when (result) {
                        is SshSession.ReadResult.TooLarge -> ViewerState.TooLarge(entry.path, result.actualSize)
                        is SshSession.ReadResult.Loaded -> classify(entry.path, result.bytes, entry.sizeBytes, entry.mtimeEpochSec)
                    }
                },
                onFailure = { e -> ViewerState.Failed(entry.path, e.message ?: e::class.java.simpleName) },
            )
        }
    }

    fun updateDraft(newDraft: String) {
        val current = _viewer.value
        if (current is ViewerState.Text) {
            _viewer.value = current.copy(
                draft = newDraft,
                saveStatus = if (current.saveStatus is SaveStatus.SavedAt) SaveStatus.Idle else current.saveStatus,
            )
        }
    }

    fun saveFile(force: Boolean = false) {
        val current = _viewer.value as? ViewerState.Text ?: return
        val session = registry.active() ?: return
        viewerJob?.cancel()
        viewerJob = viewModelScope.launch {
            _viewer.value = current.copy(saveStatus = SaveStatus.Saving)
            if (!force) {
                val remote = runCatching { session.stat(current.path) }.getOrNull()
                if (remote != null && remote.mtimeEpochSec > current.originalMtime) {
                    _viewer.value = current.copy(saveStatus = SaveStatus.Conflict(remote.mtimeEpochSec))
                    return@launch
                }
            }
            val bytes = current.draft.toByteArray(StandardCharsets.UTF_8)
            val outcome = runCatching { session.writeBytes(current.path, bytes) }
            _viewer.value = outcome.fold(
                onSuccess = { newMtime ->
                    current.copy(
                        original = current.draft,
                        originalMtime = newMtime,
                        saveStatus = SaveStatus.SavedAt(newMtime),
                    )
                },
                onFailure = { e ->
                    current.copy(saveStatus = SaveStatus.Failed(e.message ?: e::class.java.simpleName))
                },
            )
            // Refresh listing in case file size changed
            registry.active()?.let { refresh(it) }
        }
    }

    fun reloadFile() {
        val current = _viewer.value as? ViewerState.Text ?: return
        val session = registry.active() ?: return
        viewerJob?.cancel()
        viewerJob = viewModelScope.launch {
            _viewer.value = ViewerState.Loading(current.path)
            val outcome = runCatching { session.readBytes(current.path, MAX_PREVIEW_BYTES) }
            val stat = runCatching { session.stat(current.path) }.getOrNull()
            val mtime = stat?.mtimeEpochSec ?: current.originalMtime
            val size = stat?.sizeBytes ?: 0L
            _viewer.value = outcome.fold(
                onSuccess = { result ->
                    when (result) {
                        is SshSession.ReadResult.TooLarge -> ViewerState.TooLarge(current.path, result.actualSize)
                        is SshSession.ReadResult.Loaded -> classify(current.path, result.bytes, size, mtime)
                    }
                },
                onFailure = { e -> ViewerState.Failed(current.path, e.message ?: e::class.java.simpleName) },
            )
        }
    }

    fun closeViewer() {
        viewerJob?.cancel()
        _viewer.value = ViewerState.Closed
    }

    private fun classify(path: String, bytes: ByteArray, declaredSize: Long, mtimeEpochSec: Long): ViewerState {
        val probeLimit = minOf(bytes.size, BINARY_PROBE_BYTES)
        val looksBinary = (0 until probeLimit).any { bytes[it] == 0.toByte() }
        if (looksBinary) return ViewerState.Binary(path, declaredSize)
        val text = try {
            String(bytes, StandardCharsets.UTF_8)
        } catch (_: Throwable) {
            return ViewerState.Binary(path, declaredSize)
        }
        return ViewerState.Text(
            path = path,
            original = text,
            draft = text,
            originalMtime = mtimeEpochSec,
            saveStatus = SaveStatus.Idle,
        )
    }

    private fun refresh(session: SshSession) {
        listingJob?.cancel()
        val workspaceLabel = registry.activeWorkspaceId.value?.let { id ->
            _state.value.workspaceLabelOrNull() ?: "workspace #$id"
        } ?: "workspace"
        _state.value = FilesState.Browsing(
            workspaceLabel = workspaceLabel,
            path = currentPath,
            entries = (_state.value as? FilesState.Browsing)?.entries.orEmpty(),
            loading = true,
            error = null,
        )
        listingJob = viewModelScope.launch {
            val labelDeferred = currentWorkspaceId?.let { id -> repository.byId(id)?.name } ?: workspaceLabel
            val outcome = runCatching { session.listDir(currentPath) }
            _state.value = outcome.fold(
                onSuccess = { entries ->
                    FilesState.Browsing(
                        workspaceLabel = labelDeferred,
                        path = currentPath,
                        entries = entries,
                        loading = false,
                        error = null,
                    )
                },
                onFailure = { e ->
                    FilesState.Browsing(
                        workspaceLabel = labelDeferred,
                        path = currentPath,
                        entries = emptyList(),
                        loading = false,
                        error = e.message ?: e::class.java.simpleName,
                    )
                },
            )
        }
    }

    private fun FilesState.workspaceLabelOrNull(): String? =
        (this as? FilesState.Browsing)?.workspaceLabel

    companion object {
        private const val MAX_PREVIEW_BYTES = 1L * 1024 * 1024
        private const val BINARY_PROBE_BYTES = 64 * 1024

        fun parentOf(path: String): String? {
            if (path.isEmpty() || path == "/") return null
            val trimmed = path.trimEnd('/')
            val idx = trimmed.lastIndexOf('/')
            return when {
                idx <= 0 -> "/"
                else -> trimmed.substring(0, idx)
            }
        }
    }

    class Factory(
        private val repository: WorkspaceRepository,
        private val registry: SessionRegistry,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            FilesViewModel(repository, registry) as T
    }
}
