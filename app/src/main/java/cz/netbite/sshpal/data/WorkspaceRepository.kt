package cz.netbite.sshpal.data

import kotlinx.coroutines.flow.Flow

class WorkspaceRepository(
    private val dao: WorkspaceDao,
    val keys: KeyVault,
) {
    fun observeAll(): Flow<List<WorkspaceEntity>> = dao.observeAll()

    suspend fun byId(id: Long): WorkspaceEntity? = dao.byId(id)

    suspend fun upsert(workspace: WorkspaceEntity): Long = dao.upsert(workspace)

    suspend fun delete(workspace: WorkspaceEntity) {
        dao.delete(workspace)
        keys.clear(workspace.id)
    }

    suspend fun rememberHostKey(workspaceId: Long, fingerprint: String) {
        dao.setHostKey(workspaceId, fingerprint)
    }
}
