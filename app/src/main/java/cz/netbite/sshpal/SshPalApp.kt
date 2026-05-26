package cz.netbite.sshpal

import android.app.Application
import cz.netbite.sshpal.data.AppDatabase
import cz.netbite.sshpal.data.KeyVault
import cz.netbite.sshpal.data.WorkspaceRepository
import cz.netbite.sshpal.ssh.SshConnector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SshPalApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var repository: WorkspaceRepository
        private set

    lateinit var sshConnector: SshConnector
        private set

    override fun onCreate() {
        super.onCreate()
        val db = AppDatabase.get(this)
        repository = WorkspaceRepository(db.workspaceDao(), KeyVault(this))
        sshConnector = SshConnector()
        appScope.launch { repository.seedIfEmpty() }
    }
}
