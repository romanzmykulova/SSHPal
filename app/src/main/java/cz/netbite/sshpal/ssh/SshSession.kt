package cz.netbite.sshpal.ssh

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.SFTPClient
import java.io.ByteArrayOutputStream

data class RemoteEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val isSymlink: Boolean,
    val sizeBytes: Long,
    val mtimeEpochSec: Long,
)

class SshSession internal constructor(
    private val client: SSHClient,
    private val sftp: SFTPClient,
) {
    private val mutex = Mutex()
    @Volatile private var closed = false

    val isClosed: Boolean get() = closed

    suspend fun whoami(): String = exec("whoami")

    suspend fun exec(command: String): String = mutex.withLock {
        ensureOpen()
        withContext(Dispatchers.IO) {
            val session = client.startSession()
            try {
                val cmd = session.exec(command)
                val output = cmd.inputStream.bufferedReader().readText()
                cmd.join()
                output.trimEnd()
            } finally {
                runCatching { session.close() }
            }
        }
    }

    suspend fun execFull(command: String, cwd: String? = null): ExecResult = mutex.withLock {
        ensureOpen()
        withContext(Dispatchers.IO) {
            val wrapped = if (cwd.isNullOrBlank()) command else "cd ${shellQuote(cwd)} && $command"
            val session = client.startSession()
            try {
                val cmd = session.exec(wrapped)
                val stdout = cmd.inputStream.bufferedReader().readText()
                val stderr = cmd.errorStream.bufferedReader().readText()
                cmd.join()
                ExecResult(
                    exitStatus = cmd.exitStatus ?: -1,
                    stdout = stdout,
                    stderr = stderr,
                )
            } finally {
                runCatching { session.close() }
            }
        }
    }

    data class ExecResult(val exitStatus: Int, val stdout: String, val stderr: String) {
        val isSuccess: Boolean get() = exitStatus == 0
        val combined: String get() = buildString {
            if (stdout.isNotEmpty()) append(stdout)
            if (stderr.isNotEmpty()) {
                if (isNotEmpty() && !endsWith('\n')) append('\n')
                append(stderr)
            }
        }.trimEnd()
    }

    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    suspend fun listDir(path: String): List<RemoteEntry> = mutex.withLock {
        ensureOpen()
        withContext(Dispatchers.IO) {
            sftp.ls(path).map { res ->
                val attrs = res.attributes
                RemoteEntry(
                    name = res.name,
                    path = res.path,
                    isDirectory = attrs.type == FileMode.Type.DIRECTORY,
                    isSymlink = attrs.type == FileMode.Type.SYMLINK,
                    sizeBytes = attrs.size,
                    mtimeEpochSec = attrs.mtime,
                )
            }.sortedWith(compareByDescending<RemoteEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
        }
    }

    suspend fun canonicalize(path: String): String = mutex.withLock {
        ensureOpen()
        withContext(Dispatchers.IO) { sftp.canonicalize(path) }
    }

    suspend fun readBytes(path: String, maxBytes: Long): ReadResult = mutex.withLock {
        ensureOpen()
        withContext(Dispatchers.IO) {
            val handle = sftp.open(path)
            try {
                val size = handle.length()
                if (size > maxBytes) return@withContext ReadResult.TooLarge(size)
                val buf = ByteArray(64 * 1024)
                val out = ByteArrayOutputStream(size.coerceAtMost(maxBytes).toInt())
                var offset = 0L
                while (offset < size) {
                    val read = handle.read(offset, buf, 0, buf.size)
                    if (read <= 0) break
                    out.write(buf, 0, read)
                    offset += read
                }
                ReadResult.Loaded(out.toByteArray())
            } finally {
                runCatching { handle.close() }
            }
        }
    }

    suspend fun stat(path: String): RemoteEntry? = mutex.withLock {
        ensureOpen()
        withContext(Dispatchers.IO) {
            runCatching {
                val attrs = sftp.stat(path)
                RemoteEntry(
                    name = path.substringAfterLast('/').ifEmpty { path },
                    path = path,
                    isDirectory = attrs.type == FileMode.Type.DIRECTORY,
                    isSymlink = attrs.type == FileMode.Type.SYMLINK,
                    sizeBytes = attrs.size,
                    mtimeEpochSec = attrs.mtime,
                )
            }.getOrNull()
        }
    }

    suspend fun writeBytes(path: String, bytes: ByteArray): Long = mutex.withLock {
        ensureOpen()
        withContext(Dispatchers.IO) {
            val handle = sftp.open(
                path,
                java.util.EnumSet.of(
                    net.schmizz.sshj.sftp.OpenMode.WRITE,
                    net.schmizz.sshj.sftp.OpenMode.CREAT,
                    net.schmizz.sshj.sftp.OpenMode.TRUNC,
                ),
            )
            try {
                handle.write(0, bytes, 0, bytes.size)
            } finally {
                runCatching { handle.close() }
            }
            sftp.stat(path).mtime
        }
    }

    suspend fun close() = mutex.withLock {
        if (closed) return@withLock
        closed = true
        withContext(Dispatchers.IO) {
            runCatching { sftp.close() }
            runCatching { if (client.isConnected) client.disconnect() }
        }
    }

    private fun ensureOpen() {
        if (closed) error("Session is closed")
    }

    sealed interface ReadResult {
        data class Loaded(val bytes: ByteArray) : ReadResult
        data class TooLarge(val actualSize: Long) : ReadResult
    }
}
