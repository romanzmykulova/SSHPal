package cz.netbite.sshpal.ui.claude

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
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

const val DEFAULT_CLAUDE_URL = "https://app.iwantteam.ai/"

sealed interface ClaudePaneState {
    /** No active workspace — user needs to connect one first. */
    data object NoSession : ClaudePaneState

    /** Session is up but no URL resolved yet — auto-launches remote-control when user lands here. */
    data object Idle : ClaudePaneState

    /** Claude is running, /remote-control sent, watching stdout for the URL. */
    data class Connecting(val log: String) : ClaudePaneState

    /** URL resolved (either from saved workspace.claudeUrl or just-captured remote-control). */
    data class Loaded(val url: String, val savedToWorkspace: Boolean) : ClaudePaneState

    /** Remote-control flow failed or timed out — show the full Claude stdout so the user can see what went wrong. */
    data class Failed(val message: String, val log: String) : ClaudePaneState
}

class ClaudeViewModel(
    private val repository: WorkspaceRepository,
    private val registry: SessionRegistry,
) : ViewModel() {

    private val _state = MutableStateFlow<ClaudePaneState>(ClaudePaneState.NoSession)
    val state: StateFlow<ClaudePaneState> = _state.asStateFlow()

    private var currentWorkspaceId: Long? = null
    private var currentProcess: InteractiveProcess? = null
    private var remoteControlJob: Job? = null

    init {
        // React to (active workspace, sessions) changes. When the user
        // connects a workspace or switches between them, decide whether
        // to show Loaded (saved URL), Idle (let user trigger), or NoSession.
        combine(registry.activeWorkspaceId, registry.sessions) { id, sessions ->
            id?.let { sessions[it] }?.let { id }
        }
            .onEach { id ->
                if (id == null) {
                    closeProcess()
                    currentWorkspaceId = null
                    _state.value = ClaudePaneState.NoSession
                    return@onEach
                }
                if (id == currentWorkspaceId) return@onEach
                currentWorkspaceId = id
                closeProcess()
                val ws = repository.byId(id)
                val saved = ws?.claudeUrl?.takeIf { it.isNotBlank() }
                _state.value = if (saved != null) {
                    ClaudePaneState.Loaded(saved, savedToWorkspace = true)
                } else {
                    ClaudePaneState.Idle
                }
            }
            .launchIn(viewModelScope)
    }

    /**
     * Kick off the remote-control handshake on the active session:
     *   1. Open a PTY shell channel
     *   2. `cd` to the workspace's active cwd
     *   3. Launch `claude`, wait a moment for it to come up
     *   4. Send `/remote-control`
     *   5. Scan stdout for the first https?:// URL
     *   6. On match: save to workspace.claudeUrl and flip to Loaded
     *   7. On timeout / failure: flip to Failed with full stdout captured
     */
    fun startRemoteControl() {
        val id = currentWorkspaceId ?: return
        val session = registry.get(id) ?: return
        remoteControlJob?.cancel()
        remoteControlJob = viewModelScope.launch {
            closeProcess()
            val buf = StringBuilder()
            _state.value = ClaudePaneState.Connecting(buf.toString())

            try {
                val cwd = registry.cwdFor(id)
                    ?: repository.byId(id)?.defaultCwd?.takeIf { it.isNotBlank() }
                val proc = session.startInteractive(cwd)
                currentProcess = proc

                // Channel-driven coordination: the collector emits when it
                // sees the trust prompt and when it sees the URL. The main
                // flow waits on those signals instead of blind delays.
                val trustChannel = Channel<Unit>(Channel.CONFLATED)
                val urlChannel = Channel<String>(Channel.CONFLATED)
                var trustSent = false

                val collector = launch {
                    proc.output.collect { chunk ->
                        buf.append(chunk)
                        val stripped = stripAnsi(buf.toString())
                        _state.value = ClaudePaneState.Connecting(stripped)

                        if (!trustSent && trustPromptDetected(stripped)) {
                            trustChannel.trySend(Unit)
                        }
                        findUrl(stripped)?.let { urlChannel.trySend(it) }
                    }
                }

                // Give the user's shell a beat, then launch claude.
                delay(500)
                proc.writeLine("claude")

                // If claude prompts to trust this directory (first run in a
                // new cwd), confirm with Enter. If no trust prompt within
                // 6s, assume already trusted and continue.
                val sawTrust = withTimeoutOrNull(6_000) { trustChannel.receive() }
                if (sawTrust != null) {
                    trustSent = true
                    proc.write("\r")
                    delay(2_500) // let claude finish initializing post-trust
                }

                // Claude Code's TUI has bracketed paste mode enabled. A bulk
                // `writeLine("/remote-control")` is treated as a paste — the
                // command palette pops up but the command never executes.
                // Mimic typing instead: each char as its own byte with a
                // small delay, then \r as a separate write to fire Enter.
                proc.type("/remote-control")
                delay(400)
                proc.write("\r")

                // Wait up to 30s for a URL to appear.
                val urlOrNull = withTimeoutOrNull(30_000) { urlChannel.receive() }
                collector.cancel()

                if (urlOrNull != null) {
                    repository.byId(id)?.let { ws ->
                        if (ws.claudeUrl != urlOrNull) {
                            repository.upsert(ws.copy(claudeUrl = urlOrNull))
                        }
                    }
                    _state.value = ClaudePaneState.Loaded(urlOrNull, savedToWorkspace = true)
                    // DON'T closeProcess() here — claude needs to keep
                    // running on the server so the remote-control URL has
                    // a live session to attach to. Killing the process
                    // archives the session and the URL becomes useless.
                    // The process is closed on: reset(), workspace change
                    // in the init combine, or ViewModel onCleared.
                } else {
                    _state.value = ClaudePaneState.Failed(
                        message = "No URL captured. Is `claude` installed and reachable on PATH on the server, and does /remote-control print a URL in this version?",
                        log = stripAnsi(buf.toString()),
                    )
                    closeProcess()
                }
            } catch (e: Throwable) {
                _state.value = ClaudePaneState.Failed(
                    message = "Failed: ${e.message ?: e::class.java.simpleName}",
                    log = stripAnsi(buf.toString()),
                )
                closeProcess()
            }
        }
    }

    /**
     * Clear any saved claudeUrl on the active workspace so the next time the
     * Claude tab is opened, remote-control fires fresh. Also kills any
     * currently running interactive process.
     */
    fun reset() {
        val id = currentWorkspaceId ?: return
        viewModelScope.launch {
            closeProcess()
            repository.byId(id)?.let { ws ->
                if (ws.claudeUrl != null) {
                    repository.upsert(ws.copy(claudeUrl = null))
                }
            }
            _state.value = ClaudePaneState.Idle
        }
    }

    /**
     * Abort the in-flight `/remote-control` handshake. Cancels the running
     * coroutine, kills the SSH process (and the not-yet-finished `claude`
     * invocation), and drops back to Idle so the user can tap Start again.
     * Safe to call from any state — no-op if no handshake is running.
     */
    fun cancelHandshake() {
        remoteControlJob?.cancel()
        remoteControlJob = null
        closeProcess()
        _state.value = ClaudePaneState.Idle
    }

    override fun onCleared() {
        closeProcess()
        super.onCleared()
    }

    private fun closeProcess() {
        currentProcess?.close()
        currentProcess = null
    }

    companion object {
        // Strips standard ANSI CSI escapes (color codes, cursor moves) plus
        // common OSC sequences. Permissive — we only care that URLs aren't
        // chopped up by stray escape bytes.
        private val ANSI_REGEX = Regex("\\[[0-9;?]*[a-zA-Z]|\\][^]*")
        private val URL_REGEX = Regex("https?://[^\\s]+")

        fun stripAnsi(s: String): String = ANSI_REGEX.replace(s, "")

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
            // Trim trailing punctuation that the regex would greedily
            // include (URL at end of a sentence in Claude's output).
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
