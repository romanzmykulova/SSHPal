package cz.netbite.sshpal.ssh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.schmizz.sshj.connection.channel.direct.Session

/**
 * A live SSH shell channel with allocated PTY, suitable for interactive
 * tools like `claude` that misbehave under a non-TTY exec. Output bytes
 * are decoded as UTF-8 and emitted to `output` as they arrive.
 *
 * The process is NOT serialized through SshSession's mutex — it runs on
 * its own SSH channel so SFTP / git ops on the parent session keep working.
 */
class InteractiveProcess internal constructor(
    private val session: Session,
    private val shell: Session.Shell,
) {
    private val readerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _output = MutableSharedFlow<String>(replay = 200, extraBufferCapacity = 200)
    val output: SharedFlow<String> = _output.asSharedFlow()

    @Volatile private var closed = false

    init {
        readerScope.launch {
            val reader = shell.inputStream.bufferedReader(Charsets.UTF_8)
            val buf = CharArray(4096)
            try {
                while (!closed) {
                    val n = reader.read(buf)
                    if (n <= 0) break
                    _output.emit(String(buf, 0, n))
                }
            } catch (_: Throwable) {
                // channel closed under us — that's expected on close()
            }
        }
    }

    suspend fun writeLine(text: String) = withContext(Dispatchers.IO) {
        if (closed) return@withContext
        val bytes = (text + "\n").toByteArray(Charsets.UTF_8)
        shell.outputStream.write(bytes)
        shell.outputStream.flush()
    }

    fun close() {
        if (closed) return
        closed = true
        runCatching { shell.close() }
        runCatching { session.close() }
        readerScope.cancel()
    }
}
