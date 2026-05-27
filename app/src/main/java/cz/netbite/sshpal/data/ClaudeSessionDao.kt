package cz.netbite.sshpal.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface ClaudeSessionDao {

    @Query("SELECT * FROM claude_sessions WHERE workspaceId = :workspaceId ORDER BY startedAt ASC, id ASC")
    suspend fun byWorkspace(workspaceId: Long): List<ClaudeSessionEntity>

    @Query("SELECT * FROM claude_sessions WHERE id = :id LIMIT 1")
    suspend fun byId(id: Long): ClaudeSessionEntity?

    @Insert
    suspend fun insert(session: ClaudeSessionEntity): Long

    @Update
    suspend fun update(session: ClaudeSessionEntity)

    @Query("UPDATE claude_sessions SET url = :url WHERE id = :id")
    suspend fun setUrl(id: Long, url: String)

    @Query("UPDATE claude_sessions SET label = :label WHERE id = :id")
    suspend fun setLabel(id: Long, label: String?)

    @Delete
    suspend fun delete(session: ClaudeSessionEntity)

    @Query("DELETE FROM claude_sessions WHERE id = :id")
    suspend fun deleteById(id: Long)
}
