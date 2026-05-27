package cz.netbite.sshpal.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [WorkspaceEntity::class, ClaudeSessionEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun workspaceDao(): WorkspaceDao
    abstract fun claudeSessionDao(): ClaudeSessionDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE workspaces ADD COLUMN claudeUrl TEXT")
            }
        }

        /**
         * v2 → v3: Claude pane gains multi-session support. Each workspace
         * can now hold N persisted remote-control sessions (= tabs in the
         * UI), not just one URL on the workspace row. The existing
         * `workspaces.claudeUrl` column is kept as legacy until a future
         * release — the new code reads `claude_sessions` instead, but
         * leaving the column around means a forced downgrade still works.
         *
         * Backfill: for every workspace whose claudeUrl is set, seed one
         * row in claude_sessions so the user's existing tab survives the
         * upgrade. cwd is the workspace's defaultCwd (best guess —
         * pre-v3 we never persisted per-session cwd).
         */
        /**
         * SQL tightened to match what Room's codegen would emit byte-for-byte:
         * backticked identifiers, explicit `ON UPDATE NO ACTION` on the FK,
         * and a leading DROP to neutralise any half-created table left over
         * from a previous failed run. Room's schema validator otherwise
         * throws an IllegalStateException at first DB query, which is what
         * crashed the app on the first multi-session push.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `claude_sessions`")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `claude_sessions` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `workspaceId` INTEGER NOT NULL,
                        `url` TEXT NOT NULL,
                        `label` TEXT,
                        `cwd` TEXT NOT NULL,
                        `startedAt` INTEGER NOT NULL,
                        FOREIGN KEY(`workspaceId`) REFERENCES `workspaces`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_claude_sessions_workspaceId` " +
                        "ON `claude_sessions` (`workspaceId`)",
                )
                val now = System.currentTimeMillis()
                db.execSQL(
                    """
                    INSERT INTO `claude_sessions` (`workspaceId`, `url`, `label`, `cwd`, `startedAt`)
                    SELECT `id`, `claudeUrl`, NULL, `defaultCwd`, $now
                    FROM `workspaces`
                    WHERE `claudeUrl` IS NOT NULL AND `claudeUrl` <> ''
                    """.trimIndent(),
                )
            }
        }

        /**
         * Get the DB, with a self-heal escape hatch. Room only triggers
         * migration + schema validation on first query, so we force that
         * with a synchronous probe right here. If validation fails (= we
         * shipped a buggy migration in a previous version), we wipe the
         * sqlite file and rebuild fresh — the user loses the workspaces
         * row (re-paste PEM on next launch) but the app actually boots.
         * Without this, an IllegalStateException from the validator kills
         * the process at first DB observation.
         *
         * The wipe-retry is gated by `retryOnFailure = true` on the first
         * attempt only, so a second failure surfaces normally instead of
         * looping.
         */
        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: buildWithProbe(context, retryOnFailure = true).also { instance = it }
        }

        private fun buildWithProbe(context: Context, retryOnFailure: Boolean): AppDatabase {
            val db = Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "sshpal.db",
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .fallbackToDestructiveMigration()
                .fallbackToDestructiveMigrationOnDowngrade()
                .build()

            return try {
                kotlinx.coroutines.runBlocking { db.workspaceDao().count() }
                db
            } catch (e: Throwable) {
                android.util.Log.e(
                    "AppDatabase",
                    "DB probe failed; wiping and rebuilding",
                    e,
                )
                runCatching { db.close() }
                if (!retryOnFailure) throw e
                runCatching { context.deleteDatabase("sshpal.db") }
                buildWithProbe(context, retryOnFailure = false)
            }
        }
    }
}
