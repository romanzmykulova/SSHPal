package cz.netbite.sshpal

import android.app.Application
import android.util.Log
import cz.netbite.sshpal.data.AppDatabase
import cz.netbite.sshpal.data.KeyVault
import cz.netbite.sshpal.data.WorkspaceRepository
import cz.netbite.sshpal.ssh.SessionRegistry
import cz.netbite.sshpal.ssh.SshConnector
import net.i2p.crypto.eddsa.EdDSASecurityProvider
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.File
import java.security.Security
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SshPalApp : Application() {

    lateinit var repository: WorkspaceRepository
        private set

    lateinit var sshConnector: SshConnector
        private set

    lateinit var sessions: SessionRegistry
        private set

    override fun onCreate() {
        super.onCreate()
        installCrashLogger()
        registerSecurityProviders()
        val db = AppDatabase.get(this)
        repository = WorkspaceRepository(db.workspaceDao(), db.claudeSessionDao(), KeyVault(this))
        sshConnector = SshConnector()
        sessions = SessionRegistry()
    }

    /**
     * Write any uncaught exception to `filesDir/crash.log` before the
     * default handler kills the process. Pull off-device with:
     *   adb shell run-as cz.netbite.sshpal cat files/crash.log
     * The file is truncated on each crash so we always have the latest.
     */
    private fun installCrashLogger() {
        val default = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, ex ->
            runCatching {
                val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
                val device = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} " +
                    "(API ${android.os.Build.VERSION.SDK_INT})"
                val body = buildString {
                    append("=== SSHPal crash $ts ===\n")
                    append("device: $device\n")
                    append("thread: ${thread.name}\n\n")
                    append(Log.getStackTraceString(ex))
                }
                File(filesDir, "crash.log").writeText(body)
                Log.e(TAG, "Uncaught exception written to ${filesDir}/crash.log", ex)
            }
            default?.uncaughtException(thread, ex)
        }
    }

    /**
     * sshj's auto-registration is unreliable on Android. Without these,
     * `Signature.getInstance("NONEwithEdDSA")` blows up with "no such
     * algorithm" the moment you authenticate with an Ed25519 key.
     *
     * - EdDSA: net.i2p.crypto.eddsa provider, supplies NONEwithEdDSA.
     * - BC:    Android ships a stripped-down "BC" stub for KeyStore only,
     *          so we drop it and insert the real bcprov-jdk18on at the top
     *          of the search order.
     */
    private fun registerSecurityProviders() {
        if (Security.getProvider("EdDSA") == null) {
            runCatching { Security.addProvider(EdDSASecurityProvider()) }
                .onFailure { Log.w(TAG, "EdDSA provider registration failed", it) }
        }
        runCatching {
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        }.onFailure { Log.w(TAG, "BouncyCastle provider registration failed", it) }
    }

    companion object {
        private const val TAG = "SshPalApp"
    }
}
