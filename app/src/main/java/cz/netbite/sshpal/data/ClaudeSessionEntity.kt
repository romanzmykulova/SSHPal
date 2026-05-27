package cz.netbite.sshpal.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One persisted Claude remote-control session pinned to a workspace.
 * Multiple rows per workspace = multiple parallel tabs in the Claude pane,
 * each with its own remote `claude` process and captured chat URL.
 *
 * `cwd` is the directory `claude` was launched in (defaults to the
 * workspace's defaultCwd at spawn time; per-tab override is post-v1).
 * `label` is null until the user renames the tab — the UI falls back to
 * "Session N" by index.
 */
@Entity(
    tableName = "claude_sessions",
    foreignKeys = [
        ForeignKey(
            entity = WorkspaceEntity::class,
            parentColumns = ["id"],
            childColumns = ["workspaceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("workspaceId")],
)
data class ClaudeSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workspaceId: Long,
    val url: String,
    val label: String? = null,
    val cwd: String,
    val startedAt: Long,
)
