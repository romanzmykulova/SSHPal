package cz.netbite.sshpal.ssh

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SessionRegistry {
    private val _sessions = MutableStateFlow<Map<Long, SshSession>>(emptyMap())
    val sessions: StateFlow<Map<Long, SshSession>> = _sessions.asStateFlow()

    private val _activeWorkspaceId = MutableStateFlow<Long?>(null)
    val activeWorkspaceId: StateFlow<Long?> = _activeWorkspaceId.asStateFlow()

    suspend fun register(workspaceId: Long, session: SshSession) {
        val previous = _sessions.value[workspaceId]
        _sessions.value = _sessions.value.toMutableMap().apply { put(workspaceId, session) }
        _activeWorkspaceId.value = workspaceId
        if (previous != null && previous !== session) {
            runCatching { previous.close() }
        }
    }

    fun get(workspaceId: Long): SshSession? = _sessions.value[workspaceId]

    fun active(): SshSession? = _activeWorkspaceId.value?.let { _sessions.value[it] }

    suspend fun closeAndForget(workspaceId: Long) {
        val session = _sessions.value[workspaceId] ?: return
        _sessions.value = _sessions.value.toMutableMap().apply { remove(workspaceId) }
        if (_activeWorkspaceId.value == workspaceId) _activeWorkspaceId.value = null
        runCatching { session.close() }
    }

    fun setActive(workspaceId: Long?) {
        if (workspaceId == null || _sessions.value.containsKey(workspaceId)) {
            _activeWorkspaceId.value = workspaceId
        }
    }
}
