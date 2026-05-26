package cz.netbite.sshpal.ui.workspaces

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cz.netbite.sshpal.data.WorkspaceEntity
import cz.netbite.sshpal.data.WorkspaceRepository
import cz.netbite.sshpal.ssh.ConnectOutcome
import cz.netbite.sshpal.ssh.SessionRegistry
import cz.netbite.sshpal.ssh.SshConnector
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface ConnectStatus {
    data object Idle : ConnectStatus
    data object Connecting : ConnectStatus
    data class Connected(val whoami: String) : ConnectStatus
    data class Failure(val message: String) : ConnectStatus
}

data class WorkspaceRow(
    val workspace: WorkspaceEntity,
    val hasKey: Boolean,
    val status: ConnectStatus,
    val isActive: Boolean,
)

sealed interface WorkspaceEvent {
    data class NeedsKey(val workspaceId: Long) : WorkspaceEvent
    data class TrustHost(val workspaceId: Long, val fingerprint: String, val decision: CompletableDeferred<Boolean>) : WorkspaceEvent
    data class HostKeyMismatch(val workspaceId: Long, val newFingerprint: String, val storedFingerprint: String) : WorkspaceEvent
    data class Toast(val message: String) : WorkspaceEvent
}

class WorkspacesViewModel(
    private val repository: WorkspaceRepository,
    private val connector: SshConnector,
    private val registry: SessionRegistry,
) : ViewModel() {

    private val perRowStatus = MutableStateFlow<Map<Long, ConnectStatus>>(emptyMap())

    val rows: StateFlow<List<WorkspaceRow>> =
        combine(
            repository.observeAll(),
            perRowStatus,
            registry.activeWorkspaceId,
        ) { workspaces, statuses, activeId ->
            workspaces.map { w ->
                WorkspaceRow(
                    workspace = w,
                    hasKey = repository.keys.hasKey(w.id),
                    status = statuses[w.id] ?: ConnectStatus.Idle,
                    isActive = activeId == w.id,
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _events = Channel<WorkspaceEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    fun upsert(workspace: WorkspaceEntity, privateKeyPem: String?, passphrase: String?) {
        viewModelScope.launch {
            val id = repository.upsert(workspace)
            if (!privateKeyPem.isNullOrBlank()) {
                repository.keys.savePrivateKey(if (workspace.id == 0L) id else workspace.id, privateKeyPem, passphrase)
            }
        }
    }

    fun delete(workspace: WorkspaceEntity) {
        viewModelScope.launch {
            registry.closeAndForget(workspace.id)
            repository.delete(workspace)
        }
    }

    fun disconnect(workspaceId: Long) {
        viewModelScope.launch {
            registry.closeAndForget(workspaceId)
            setStatus(workspaceId, ConnectStatus.Idle)
        }
    }

    fun connect(workspaceId: Long) {
        viewModelScope.launch {
            val ws = repository.byId(workspaceId) ?: return@launch
            val pem = repository.keys.loadPrivateKey(workspaceId)
            if (pem.isNullOrBlank()) {
                _events.send(WorkspaceEvent.NeedsKey(workspaceId))
                return@launch
            }
            val passphrase = repository.keys.loadPassphrase(workspaceId)
            setStatus(workspaceId, ConnectStatus.Connecting)
            val outcome = connector.connect(
                workspace = ws,
                privateKeyPem = pem,
                passphrase = passphrase,
                onUnknownHost = { fingerprint ->
                    val decision = CompletableDeferred<Boolean>()
                    _events.send(WorkspaceEvent.TrustHost(workspaceId, fingerprint, decision))
                    decision.await()
                },
            )
            when (outcome) {
                is ConnectOutcome.Success -> {
                    outcome.acceptedFingerprint?.let { fp -> repository.rememberHostKey(workspaceId, fp) }
                    registry.register(workspaceId, outcome.session)
                    val whoami = runCatching { outcome.session.whoami() }.getOrElse { e -> "(whoami failed: ${e.message})" }
                    setStatus(workspaceId, ConnectStatus.Connected(whoami))
                    _events.send(WorkspaceEvent.Toast("Connected — whoami → $whoami"))
                }
                is ConnectOutcome.HostKeyMismatch -> {
                    setStatus(workspaceId, ConnectStatus.Failure("Host key changed"))
                    _events.send(WorkspaceEvent.HostKeyMismatch(workspaceId, outcome.newFingerprint, outcome.storedFingerprint))
                }
                ConnectOutcome.HostKeyRejected -> {
                    setStatus(workspaceId, ConnectStatus.Failure("Host key rejected"))
                }
                is ConnectOutcome.Failure -> {
                    setStatus(workspaceId, ConnectStatus.Failure(outcome.message))
                }
            }
        }
    }

    fun makeActive(workspaceId: Long) {
        registry.setActive(workspaceId)
    }

    private fun setStatus(id: Long, status: ConnectStatus) {
        perRowStatus.value = perRowStatus.value.toMutableMap().apply { put(id, status) }
    }

    class Factory(
        private val repository: WorkspaceRepository,
        private val connector: SshConnector,
        private val registry: SessionRegistry,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            WorkspacesViewModel(repository, connector, registry) as T
    }
}
