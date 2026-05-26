package cz.netbite.sshpal.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "workspaces")
data class WorkspaceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    val defaultCwd: String,
    val knownHostKeyFingerprint: String? = null,
    val claudeUrl: String? = null,
)
