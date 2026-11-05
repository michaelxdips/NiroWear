package com.nirowear.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "openai_phases",
    foreignKeys = [
        ForeignKey(
            entity = StoredMessage::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class StoredOpenAiPhases(
    @PrimaryKey val messageId: Long,
    val profileId: String,
    val modelId: String,
    val baseUrl: String,
    val phases: String,
)

@Entity(tableName = "conversations")
data class Conversation(
    @PrimaryKey val id: String,
    val title: String,
    val updatedAt: Long,
)

@Entity(
    tableName = "messages",
    indices = [Index("conversationId")],
    foreignKeys = [
        ForeignKey(
            entity = Conversation::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class StoredMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: String,
    val role: String,
    val rawContent: String,
    val status: String = "SUCCESS",
    val providerProfileId: String? = null,
    val providerLabel: String? = null,
    val modelId: String? = null,
    val error: String? = null,
)

/**
 * One FTS5 match.
 *
 * A virtual table cannot be a Room entity, and Room's processor refuses a `@Query` against a table it
 * did not create, so search goes through [searchMessages] and the raw driver instead. The table, its
 * triggers and its backfill live in [ChatDatabase.MIGRATION_2_3]. FTS5 ships inside the platform
 * SQLite, so search costs no dependency.
 */
data class SearchHit(
    val messageId: Long,
    val conversationId: String,
    val role: String,
    val rawContent: String,
)

/**
 * Full-text search over stored message text.
 *
 * Returns an empty list for a blank or unparseable expression rather than throwing: a search box must
 * never crash the screen, and FTS5 raises on malformed syntax.
 */
fun ChatDatabase.searchMessages(
    expression: String,
    limit: Int = 40,
): List<SearchHit> =
    runCatching<List<SearchHit>> {
        openHelper.readableDatabase
            .query(
                "SELECT rowid, conversationId, role, rawContent FROM messages_fts " +
                    "WHERE messages_fts MATCH ? ORDER BY rank LIMIT ?",
                arrayOf<Any?>(expression, limit),
            ).use { cursor ->
                val hits = mutableListOf<SearchHit>()
                while (cursor.moveToNext()) {
                    hits += SearchHit(cursor.getLong(0), cursor.getString(1), cursor.getString(2), cursor.getString(3))
                }
                hits
            }
    }.getOrElse { emptyList() }

@Dao
interface ChatDao {
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun conversations(): Flow<List<Conversation>>

    @Query("SELECT * FROM messages WHERE conversationId = :id ORDER BY id")
    fun messages(id: String): Flow<List<StoredMessage>>

    @Query("SELECT * FROM messages WHERE conversationId = :id ORDER BY id")
    suspend fun snapshot(id: String): List<StoredMessage>

    @Insert suspend fun insertConversation(conversation: Conversation)

    @Insert suspend fun insertMessage(message: StoredMessage): Long

    @Insert suspend fun insertOpenAiPhases(value: StoredOpenAiPhases)

    @Query("DELETE FROM openai_phases WHERE messageId = :id")
    suspend fun deleteOpenAiPhases(id: Long)

    @Query(
        "SELECT p.* FROM openai_phases p JOIN messages m ON m.id = p.messageId WHERE m.conversationId = :id AND m.role = 'ASSISTANT' AND m.status = 'SUCCESS' AND p.profileId = :profileId AND p.modelId = :modelId AND p.baseUrl = :baseUrl",
    )
    suspend fun openAiPhases(
        id: String,
        profileId: String,
        modelId: String,
        baseUrl: String,
    ): List<StoredOpenAiPhases>

    @Query("UPDATE messages SET rawContent = :text, status = :status, error = :error WHERE id = :id")
    suspend fun checkpoint(
        id: Long,
        text: String,
        status: String,
        error: String? = null,
    )

    @Transaction
    suspend fun finish(
        id: Long,
        text: String,
        status: String,
        error: String?,
        phases: StoredOpenAiPhases?,
    ) {
        checkpoint(id, text, status, error)
        if (status != "SUCCESS") deleteOpenAiPhases(id)
        if (status == "SUCCESS" && phases != null) {
            require(phases.messageId == id)
            insertOpenAiPhases(phases)
        }
    }

    @Query("UPDATE conversations SET updatedAt = :time WHERE id = :id")
    suspend fun touch(
        id: String,
        time: Long,
    )

    @Query("UPDATE conversations SET title = :title WHERE id = :id")
    suspend fun retitle(
        id: String,
        title: String,
    )

    @Query("UPDATE messages SET status = 'STOPPED' WHERE status = 'STREAMING'")
    suspend fun recoverInterrupted()

    /**
     * A turn that failed before any assistant row existed. The pending USER row stays (the user did
     * ask), and this row carries the failure the reader shows, so the transcript and history do not
     * imply an answer that never arrived.
     */
    @Transaction
    suspend fun recordFailure(
        conversationId: String,
        error: String?,
    ) {
        insertMessage(
            StoredMessage(
                conversationId = conversationId,
                role = "ASSISTANT",
                rawContent = "",
                status = "ERROR",
                error = error,
            ),
        )
        touch(conversationId, System.currentTimeMillis())
    }

    @Query("DELETE FROM messages WHERE conversationId = :conversationId AND id > :id")
    suspend fun truncate(
        conversationId: String,
        id: Long,
    )

    @Query("UPDATE messages SET rawContent = :text WHERE id = :id AND role = 'USER'")
    suspend fun replaceUser(
        id: Long,
        text: String,
    ): Int

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteConversation(id: String)

    @Transaction
    suspend fun edit(
        conversationId: String,
        id: Long,
        text: String,
    ) {
        require(text.isNotBlank())
        require(snapshot(conversationId).any { it.id == id && it.role == "USER" })
        check(replaceUser(id, text) == 1)
        truncate(conversationId, id)
    }

    @Transaction
    suspend fun admit(
        conversationId: String,
        prompt: String?,
        profile: com.nirowear.provider.ProviderProfile,
    ): Long {
        if (prompt !=
            null
        ) {
            insertMessage(StoredMessage(conversationId = conversationId, role = "USER", rawContent = prompt))
        }
        touch(conversationId, System.currentTimeMillis())
        return insertMessage(
            StoredMessage(
                conversationId = conversationId,
                role = "ASSISTANT",
                rawContent = "",
                status = "STREAMING",
                providerProfileId = profile.id,
                providerLabel = profile.displayName,
                modelId = profile.selectedModelId,
            ),
        )
    }
}

@Database(
    entities = [Conversation::class, StoredMessage::class, StoredOpenAiPhases::class],
    version = 3,
    exportSchema = true,
)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun chats(): ChatDao

    companion object {
        val MIGRATION_1_2 =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS openai_phases (messageId INTEGER NOT NULL, profileId TEXT NOT NULL, modelId TEXT NOT NULL, baseUrl TEXT NOT NULL, phases TEXT NOT NULL, PRIMARY KEY(messageId), FOREIGN KEY(messageId) REFERENCES messages(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
                    )
                }
            }

        /**
         * Search index over message text, backfilled from existing rows, plus the triggers that keep
         * it in step. A virtual table cannot be an ordinary entity, so the schema is raw SQL here and
         * `SearchHit` only maps the SELECT results.
         */
        val MIGRATION_2_3 =
            object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    val columns = "rowid, conversationId, role, rawContent"
                    db.execSQL(
                        "CREATE VIRTUAL TABLE IF NOT EXISTS messages_fts USING fts5(" +
                            "conversationId UNINDEXED, role UNINDEXED, rawContent, " +
                            "content='messages', content_rowid='id', tokenize='unicode61')",
                    )
                    // Backfill: the index only sees writes made after it exists. The triggers below
                    // then keep every future insert and edit in step.
                    db.execSQL(
                        "INSERT INTO messages_fts($columns) " +
                            "SELECT id, conversationId, role, rawContent FROM messages",
                    )
                    db.execSQL(
                        "CREATE TRIGGER IF NOT EXISTS messages_fts_sync AFTER INSERT ON messages BEGIN " +
                            "INSERT INTO messages_fts($columns) VALUES " +
                            "(new.id, new.conversationId, new.role, new.rawContent); END",
                    )
                    db.execSQL(
                        "CREATE TRIGGER IF NOT EXISTS messages_fts_edit AFTER UPDATE ON messages BEGIN " +
                            "INSERT INTO messages_fts(messages_fts, $columns) VALUES " +
                            "('delete', old.id, old.conversationId, old.role, old.rawContent); " +
                            "INSERT INTO messages_fts($columns) VALUES " +
                            "(new.id, new.conversationId, new.role, new.rawContent); END",
                    )
                }
            }
    }
}
