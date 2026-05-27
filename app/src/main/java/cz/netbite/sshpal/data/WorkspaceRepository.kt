package cz.netbite.sshpal.data

import kotlinx.coroutines.flow.Flow

class WorkspaceRepository(
    private val dao: WorkspaceDao,
    private val claudeDao: ClaudeSessionDao,
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

    suspend fun claudeSessions(workspaceId: Long): List<ClaudeSessionEntity> =
        claudeDao.byWorkspace(workspaceId)

    suspend fun insertClaudeSession(session: ClaudeSessionEntity): Long =
        claudeDao.insert(session)

    suspend fun setClaudeSessionUrl(id: Long, url: String) =
        claudeDao.setUrl(id, url)

    suspend fun setClaudeSessionLabel(id: Long, label: String?) =
        claudeDao.setLabel(id, label)

    suspend fun deleteClaudeSession(id: Long) =
        claudeDao.deleteById(id)
}
