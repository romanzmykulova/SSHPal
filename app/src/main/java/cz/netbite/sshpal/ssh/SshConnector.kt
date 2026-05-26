package cz.netbite.sshpal.ssh

import cz.netbite.sshpal.data.WorkspaceEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.password.PasswordUtils
import java.io.IOException
import java.security.PublicKey

sealed interface ConnectOutcome {
    data class Success(val session: SshSession, val acceptedFingerprint: String?) : ConnectOutcome
    data class HostKeyMismatch(val newFingerprint: String, val storedFingerprint: String) : ConnectOutcome
    data object HostKeyRejected : ConnectOutcome
    data class Failure(val message: String) : ConnectOutcome
}

class SshConnector {

    suspend fun connect(
        workspace: WorkspaceEntity,
        privateKeyPem: String,
        passphrase: String?,
        onUnknownHost: suspend (fingerprint: String) -> Boolean,
    ): ConnectOutcome = withContext(Dispatchers.IO) {
        val verifier = TofuVerifier(workspace.knownHostKeyFingerprint, onUnknownHost)
        val client = SSHClient().apply {
            connectTimeout = 10_000
            timeout = 15_000
            addHostKeyVerifier(verifier)
        }
        try {
            client.connect(workspace.host, workspace.port)
            val keyProvider = if (passphrase.isNullOrEmpty()) {
                client.loadKeys(privateKeyPem, null, null)
            } else {
                client.loadKeys(privateKeyPem, null, PasswordUtils.createOneOff(passphrase.toCharArray()))
            }
            client.authPublickey(workspace.username, keyProvider)

            verifier.mismatch?.let { mismatch ->
                runCatching { client.disconnect() }
                return@withContext ConnectOutcome.HostKeyMismatch(mismatch.newFp, mismatch.storedFp)
            }

            val sftp = client.newSFTPClient()
            val session = SshSession(client, sftp)
            ConnectOutcome.Success(session, verifier.accepted)
        } catch (_: HostKeyRejectedException) {
            runCatching { client.disconnect() }
            ConnectOutcome.HostKeyRejected
        } catch (e: IOException) {
            runCatching { client.disconnect() }
            ConnectOutcome.Failure(e.message ?: "Connection failed")
        } catch (e: Exception) {
            runCatching { client.disconnect() }
            ConnectOutcome.Failure(e.message ?: e::class.java.simpleName)
        }
    }

    private class HostKeyRejectedException : RuntimeException()

    private data class HostKeyMismatchInfo(val newFp: String, val storedFp: String)

    private class TofuVerifier(
        private val stored: String?,
        private val onUnknownHost: suspend (String) -> Boolean,
    ) : HostKeyVerifier {
        var accepted: String? = null
        var mismatch: HostKeyMismatchInfo? = null

        override fun verify(hostname: String?, port: Int, key: PublicKey?): Boolean {
            if (key == null) return false
            val fp = normalize(SecurityUtils.getFingerprint(key))
            return when {
                stored == null -> {
                    val trusted = runBlocking { onUnknownHost(fp) }
                    if (!trusted) throw HostKeyRejectedException()
                    accepted = fp
                    true
                }
                stored == fp -> true
                else -> {
                    mismatch = HostKeyMismatchInfo(newFp = fp, storedFp = stored)
                    false
                }
            }
        }

        override fun findExistingAlgorithms(hostname: String?, port: Int): List<String> = emptyList()

        private fun normalize(raw: String): String =
            if (raw.startsWith("SHA256:")) raw else "SHA256:$raw"
    }
}
