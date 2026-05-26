package cz.netbite.sshpal

import android.app.Application
import cz.netbite.sshpal.data.AppDatabase
import cz.netbite.sshpal.data.KeyVault
import cz.netbite.sshpal.data.WorkspaceRepository
import cz.netbite.sshpal.ssh.SessionRegistry
import cz.netbite.sshpal.ssh.SshConnector

class SshPalApp : Application() {

    lateinit var repository: WorkspaceRepository
        private set

    lateinit var sshConnector: SshConnector
        private set

    lateinit var sessions: SessionRegistry
        private set

    override fun onCreate() {
        super.onCreate()
        val db = AppDatabase.get(this)
        repository = WorkspaceRepository(db.workspaceDao(), KeyVault(this))
        sshConnector = SshConnector()
        sessions = SessionRegistry()
    }
}
