package cz.netbite.sshpal.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkspaceDao {

    @Query("SELECT * FROM workspaces ORDER BY id ASC")
    fun observeAll(): Flow<List<WorkspaceEntity>>

    @Query("SELECT * FROM workspaces WHERE id = :id LIMIT 1")
    suspend fun byId(id: Long): WorkspaceEntity?

    @Query("SELECT COUNT(*) FROM workspaces")
    suspend fun count(): Int

    @Upsert
    suspend fun upsert(workspace: WorkspaceEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(workspace: WorkspaceEntity): Long

    @Delete
    suspend fun delete(workspace: WorkspaceEntity)

    @Query("UPDATE workspaces SET knownHostKeyFingerprint = :fingerprint WHERE id = :id")
    suspend fun setHostKey(id: Long, fingerprint: String)
}
