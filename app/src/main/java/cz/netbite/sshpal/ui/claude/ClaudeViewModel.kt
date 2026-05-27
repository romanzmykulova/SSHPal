package cz.netbite.sshpal.ui.claude

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cz.netbite.sshpal.data.ClaudeSessionEntity
import cz.netbite.sshpal.data.WorkspaceRepository
import cz.netbite.sshpal.ssh.InteractiveProcess
import cz.netbite.sshpal.ssh.SessionRegistry
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

const val DEFAULT_CLAUDE_URL = "https://app.iwantteam.ai/"

sealed interface ClaudePaneState {
    /** No active workspace — user needs to connect one first. */
    data object NoSession : ClaudePaneState

    /**
     * Workspace is up. `tabs` is the ordered list of Claude sessions
     * (persisted + in-flight drafts); `selectedId` is null only when
     * `tabs` is empty. A non-empty list with no selection is invalid.
     */
    data class Loaded(
        val tabs: List<TabState>,
        val selectedId: Long?,
    ) : ClaudePaneState
}

/**
 * One tab in the Claude pane. `id > 0` = persisted (DB row);
 * `id < 0` = draft (in-flight first handshake, no DB row yet).
 * On cancel of a draft, the tab disappears. On cancel of a restart
 * of a persisted tab, the tab survives in Failed state.
 */
sealed interface TabState {
    val id: Long
    val label: String
    val cwd: String

    data class Ready(
        override val id: Long,
        override val label: String,
        override val cwd: String,
        val url: String,
    ) : TabState

    data class Connecting(
        override val id: Long,
        override val label: String,
        override val cwd: String,
        val log: String,
        /** true while no DB row exists yet (initial handshake of a new tab). */
        val isDraft: Boolean,
    ) : TabState

    data class Failed(
        override val id: Long,
        override val label: String,
        override val cwd: String,
        val message: String,
        val log: String,
        /** true if this tab was a draft and the handshake failed before
         * a DB row was created. closeTab() drops it; restartTab() retries
         * via newSession() since there's nothing to update. */
        val isDraft: Boolean,
    ) : TabState
}

class ClaudeViewModel(
    private val repository: WorkspaceRepository,
    private val registry: SessionRegistry,
) : ViewModel() {

    private val _state = MutableStateFlow<ClaudePaneState>(ClaudePaneState.NoSession)
    val state: StateFlow<ClaudePaneState> = _state.asStateFlow()

    private var currentWorkspaceId: Long? = null

    /** Per-tab SSH PTY running `claude` on the server. Killed when the tab closes. */
    private val processes: MutableMap<Long, InteractiveProcess> = mutableMapOf()

    /** Per-tab in-flight handshake coroutine. Cancelled on cancelHandshake / closeTab / workspace switch. */
    private val handshakeJobs: MutableMap<Long, Job> = mutableMapOf()

    /** Source for temp ids on draft tabs. Always negative so we can't collide with DB ids. */
    private val draftIdSeq = AtomicLong(-1L)

    init {
        // React to (active workspace, sessions) changes. When the user
        // connects a workspace or switches between them, load that
        // workspace's persisted tabs from DB. If the workspace has zero
        // tabs, kick a first-tab handshake automatically — that matches
        // the pre-multi-session behavior of "open Claude tab on a fresh
        // workspace = /remote-control fires immediately".
        combine(registry.activeWorkspaceId, registry.sessions) { id, sessions ->
            id?.let { sessions[it] }?.let { id }
        }
            .onEach { id ->
                if (id == null) {
                    closeAllProcesses()
                    currentWorkspaceId = null
                    _state.value = ClaudePaneState.NoSession
                    return@onEach
                }
                if (id == currentWorkspaceId) return@onEach
                currentWorkspaceId = id
                closeAllProcesses()

                val rows = repository.claudeSessions(id)
                if (rows.isEmpty()) {
                    _state.value = ClaudePaneState.Loaded(tabs = emptyList(), selectedId = null)
                    newSession()
                } else {
                    val tabs = rows.mapIndexed { idx, row -> row.toReadyTab(idx) }
                    _state.value = ClaudePaneState.Loaded(
                        tabs = tabs,
                        selectedId = tabs.first().id,
                    )
                }
            }
            .launchIn(viewModelScope)
    }

    // ---------- public actions ----------

    /** Spawn a new tab. Creates a draft (negative-id) Connecting tab and kicks a handshake. */
    fun newSession() {
        val workspaceId = currentWorkspaceId ?: return
        registry.get(workspaceId) ?: return
        viewModelScope.launch {
            val cwd = registry.cwdFor(workspaceId)
                ?: repository.byId(workspaceId)?.defaultCwd?.takeIf { it.isNotBlank() }
                ?: "~"
            val loaded = (_state.value as? ClaudePaneState.Loaded) ?: return@launch
            val draftId = draftIdSeq.getAndDecrement()
            val nextIndex = loaded.tabs.size
            val draft = TabState.Connecting(
                id = draftId,
                label = defaultLabel(nextIndex),
                cwd = cwd,
                log = "",
                isDraft = true,
            )
            _state.value = ClaudePaneState.Loaded(
                tabs = loaded.tabs + draft,
                selectedId = draftId,
            )
            kickHandshake(workspaceId, draftId, isDraft = true)
        }
    }

    /** Switch the visible tab. */
    fun selectTab(tabId: Long) {
        val loaded = (_state.value as? ClaudePaneState.Loaded) ?: return
        if (loaded.selectedId == tabId) return
        if (loaded.tabs.none { it.id == tabId }) return
        _state.value = loaded.copy(selectedId = tabId)
    }

    /**
     * Restart a tab — kill its remote `claude`, re-run /remote-control,
     * update its url in DB on success. Tab keeps its id and label.
     * For a draft tab (id < 0), this is equivalent to firing the
     * handshake again (no DB row to update yet).
     */
    fun restartTab(tabId: Long) {
        val workspaceId = currentWorkspaceId ?: return
        val loaded = (_state.value as? ClaudePaneState.Loaded) ?: return
        val tab = loaded.tabs.firstOrNull { it.id == tabId } ?: return

        // Tear down whatever was there.
        handshakeJobs.remove(tabId)?.cancel()
        processes.remove(tabId)?.close()

        val isDraft = tab.id < 0 || (tab is TabState.Failed && tab.isDraft) ||
            (tab is TabState.Connecting && tab.isDraft)

        _state.value = loaded.copy(
            tabs = loaded.tabs.map { existing ->
                if (existing.id != tabId) existing
                else TabState.Connecting(
                    id = existing.id,
                    label = existing.label,
                    cwd = existing.cwd,
                    log = "",
                    isDraft = isDraft,
                )
            },
            selectedId = tabId,
        )

        kickHandshake(workspaceId, tabId, isDraft = isDraft)
    }

    /**
     * Cancel an in-flight handshake on a tab.
     *  - Draft tab → tab is removed entirely.
     *  - Persisted tab being restarted → tab flips to Failed("cancelled").
     *  - Ready tab → no-op (nothing to cancel).
     */
    fun cancelHandshake(tabId: Long) {
        val loaded = (_state.value as? ClaudePaneState.Loaded) ?: return
        val tab = loaded.tabs.firstOrNull { it.id == tabId } ?: return
        if (tab !is TabState.Connecting) return

        handshakeJobs.remove(tabId)?.cancel()
        processes.remove(tabId)?.close()

        if (tab.isDraft) {
            removeTab(tabId)
        } else {
            updateTab(tabId) {
                TabState.Failed(
                    id = it.id,
                    label = it.label,
                    cwd = it.cwd,
                    message = "Cancelled.",
                    log = if (it is TabState.Connecting) it.log else "",
                    isDraft = false,
                )
            }
        }
    }

    /**
     * Close a tab — kill its remote `claude`, delete the DB row, remove
     * from the list. If it was selected, select the next remaining tab
     * (or null if none).
     */
    fun closeTab(tabId: Long) {
        val loaded = (_state.value as? ClaudePaneState.Loaded) ?: return
        if (loaded.tabs.none { it.id == tabId }) return

        handshakeJobs.remove(tabId)?.cancel()
        processes.remove(tabId)?.close()

        if (tabId > 0) {
            viewModelScope.launch { repository.deleteClaudeSession(tabId) }
        }

        removeTab(tabId)
    }

    /** Rename a tab. Empty string clears the label (UI falls back to "Session N"). */
    fun renameTab(tabId: Long, newLabel: String) {
        val loaded = (_state.value as? ClaudePaneState.Loaded) ?: return
        val tab = loaded.tabs.firstOrNull { it.id == tabId } ?: return

        val trimmed = newLabel.trim()
        val label = trimmed.ifEmpty {
            val idx = loaded.tabs.indexOfFirst { it.id == tabId }
            defaultLabel(if (idx >= 0) idx else 0)
        }

        updateTab(tabId) { tab.withLabel(label) }

        if (tabId > 0) {
            viewModelScope.launch {
                repository.setClaudeSessionLabel(tabId, trimmed.ifEmpty { null })
            }
        }
    }

    override fun onCleared() {
        closeAllProcesses()
        super.onCleared()
    }

    // ---------- internals ----------

    private fun kickHandshake(workspaceId: Long, tabId: Long, isDraft: Boolean) {
        val session = registry.get(workspaceId) ?: return
        val tab = (_state.value as? ClaudePaneState.Loaded)
            ?.tabs?.firstOrNull { it.id == tabId } ?: return
        val cwd = tab.cwd

        handshakeJobs[tabId]?.cancel()
        handshakeJobs[tabId] = viewModelScope.launch {
            val buf = StringBuilder()
            try {
                val proc = session.startInteractive(cwd.takeIf { it.isNotBlank() && it != "~" })
                processes[tabId] = proc

                val trustChannel = Channel<Unit>(Channel.CONFLATED)
                val urlChannel = Channel<String>(Channel.CONFLATED)
                var trustSent = false

                val collector = launch {
                    proc.output.collect { chunk ->
                        buf.append(chunk)
                        val stripped = stripAnsi(buf.toString())
                        updateTab(tabId) { existing ->
                            if (existing is TabState.Connecting) {
                                existing.copy(log = stripped)
                            } else existing
                        }
                        if (!trustSent && trustPromptDetected(stripped)) {
                            trustChannel.trySend(Unit)
                        }
                        findUrl(stripped)?.let { urlChannel.trySend(it) }
                    }
                }

                delay(500)
                proc.writeLine("claude")

                val sawTrust = withTimeoutOrNull(6_000) { trustChannel.receive() }
                if (sawTrust != null) {
                    trustSent = true
                    proc.write("\r")
                    delay(2_500)
                }

                proc.type("/remote-control")
                delay(400)
                proc.write("\r")

                val urlOrNull = withTimeoutOrNull(30_000) { urlChannel.receive() }
                collector.cancel()

                if (urlOrNull != null) {
                    onHandshakeSuccess(workspaceId, tabId, urlOrNull, isDraft)
                } else {
                    updateTab(tabId) { existing ->
                        TabState.Failed(
                            id = existing.id,
                            label = existing.label,
                            cwd = existing.cwd,
                            message = "No URL captured. Is `claude` installed and reachable on PATH on the server, and does /remote-control print a URL in this version?",
                            log = stripAnsi(buf.toString()),
                            isDraft = isDraft,
                        )
                    }
                    processes.remove(tabId)?.close()
                }
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                updateTab(tabId) { existing ->
                    TabState.Failed(
                        id = existing.id,
                        label = existing.label,
                        cwd = existing.cwd,
                        message = "Failed: ${e.message ?: e::class.java.simpleName}",
                        log = stripAnsi(buf.toString()),
                        isDraft = isDraft,
                    )
                }
                processes.remove(tabId)?.close()
            } finally {
                handshakeJobs.remove(tabId)
            }
        }
    }

    private suspend fun onHandshakeSuccess(
        workspaceId: Long,
        tabId: Long,
        url: String,
        wasDraft: Boolean,
    ) {
        if (wasDraft) {
            // Promote draft → persisted: insert a DB row, swap the tab's
            // id (and re-key process/job maps) to the new DB id.
            val tab = (_state.value as? ClaudePaneState.Loaded)
                ?.tabs?.firstOrNull { it.id == tabId } ?: return
            val newId = repository.insertClaudeSession(
                ClaudeSessionEntity(
                    workspaceId = workspaceId,
                    url = url,
                    label = null, // label only persisted if user renames
                    cwd = tab.cwd,
                    startedAt = System.currentTimeMillis(),
                ),
            )
            processes.remove(tabId)?.let { processes[newId] = it }
            // Replace tab with Ready, keyed by the new DB id.
            val loaded = (_state.value as? ClaudePaneState.Loaded) ?: return
            val selectedId = if (loaded.selectedId == tabId) newId else loaded.selectedId
            _state.value = loaded.copy(
                tabs = loaded.tabs.map {
                    if (it.id == tabId) {
                        TabState.Ready(
                            id = newId,
                            label = it.label,
                            cwd = it.cwd,
                            url = url,
                        )
                    } else it
                },
                selectedId = selectedId,
            )
        } else {
            repository.setClaudeSessionUrl(tabId, url)
            updateTab(tabId) { existing ->
                TabState.Ready(
                    id = existing.id,
                    label = existing.label,
                    cwd = existing.cwd,
                    url = url,
                )
            }
        }
    }

    private fun updateTab(tabId: Long, transform: (TabState) -> TabState) {
        val loaded = (_state.value as? ClaudePaneState.Loaded) ?: return
        val newTabs = loaded.tabs.map { if (it.id == tabId) transform(it) else it }
        _state.value = loaded.copy(tabs = newTabs)
    }

    private fun removeTab(tabId: Long) {
        val loaded = (_state.value as? ClaudePaneState.Loaded) ?: return
        val idx = loaded.tabs.indexOfFirst { it.id == tabId }
        if (idx < 0) return
        val newTabs = loaded.tabs.filterIndexed { i, _ -> i != idx }
        val newSelected = when {
            newTabs.isEmpty() -> null
            loaded.selectedId != tabId -> loaded.selectedId
            else -> newTabs[idx.coerceAtMost(newTabs.size - 1)].id
        }
        _state.value = loaded.copy(tabs = newTabs, selectedId = newSelected)
    }

    private fun closeAllProcesses() {
        handshakeJobs.values.forEach { it.cancel() }
        handshakeJobs.clear()
        processes.values.forEach { it.close() }
        processes.clear()
    }

    private fun ClaudeSessionEntity.toReadyTab(index: Int): TabState.Ready = TabState.Ready(
        id = id,
        label = label ?: defaultLabel(index),
        cwd = cwd,
        url = url,
    )

    private fun TabState.withLabel(label: String): TabState = when (this) {
        is TabState.Ready -> copy(label = label)
        is TabState.Connecting -> copy(label = label)
        is TabState.Failed -> copy(label = label)
    }

    companion object {
        // Strips standard ANSI CSI escapes (color codes, cursor moves) plus
        // common OSC sequences. Permissive — we only care that URLs aren't
        // chopped up by stray escape bytes.
        private val ANSI_REGEX = Regex("\\[[0-9;?]*[a-zA-Z]|\\][^]*")
        private val URL_REGEX = Regex("https?://[^\\s]+")

        fun stripAnsi(s: String): String = ANSI_REGEX.replace(s, "")

        fun defaultLabel(index: Int): String = "Session ${index + 1}"

        /**
         * Heuristic: Claude Code's first-run trust prompt for a directory
         * contains the phrase "trust this code" alongside numbered options.
         * We treat any of these markers as a sign that Enter should be sent
         * to accept the default "Yes" choice.
         */
        fun trustPromptDetected(strippedLog: String): Boolean {
            val lower = strippedLog.lowercase()
            return lower.contains("trust this code") ||
                lower.contains("enter to confirm") ||
                (lower.contains("yes, i trust") && lower.contains("no, exit"))
        }

        fun findUrl(s: String): String? {
            val match = URL_REGEX.find(s) ?: return null
            return match.value.trimEnd('.', ',', ')', ']', '}', '\'', '"', ';', ':')
        }
    }

    class Factory(
        private val repository: WorkspaceRepository,
        private val registry: SessionRegistry,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ClaudeViewModel(repository, registry) as T
    }
}
