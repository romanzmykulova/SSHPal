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
import java.security.Security

class SshPalApp : Application() {

    lateinit var repository: WorkspaceRepository
        private set

    lateinit var sshConnector: SshConnector
        private set

    lateinit var sessions: SessionRegistry
        private set

    override fun onCreate() {
        super.onCreate()
        registerSecurityProviders()
        val db = AppDatabase.get(this)
        repository = WorkspaceRepository(db.workspaceDao(), KeyVault(this))
        sshConnector = SshConnector()
        sessions = SessionRegistry()
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
