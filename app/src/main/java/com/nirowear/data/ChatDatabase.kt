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
 * One search hit: a message whose text matched.
 *
 * Search does not use FTS5. Measured on the Wear emulator (Android 14), Android's bundled SQLite
 * answers `no such module: fts5` — it ships FTS3/FTS4 only — so an FTS table would crash the app on
 * the very migration that creates it. A LIKE scan over the messages table is the portable option, and
 * at this data size (one user's chat history) it is also the cheapest: no virtual table, no
 * triggers, no migration, nothing to keep in sync.
 */
data class SearchHit(
    val messageId: Long,
    val conversationId: String,
    val role: String,
    val rawContent: String,
)

/**
 * ASCII case-insensitive substring search over stored message text.
 *
 * "ASCII" is the honest bound: SQLite's LIKE folds case only for ASCII, so `café` does not match
 * `Café`. Folding in Kotlin would need a normalised column and a migration; not worth it for search
 * on a watch.
 *
 * Every term must appear somewhere in the message (implicit AND), which is what a person means by
 * typing two words. `%`, `_` and the escape character in the input are escaped, so a query for
 * "100%" is a literal match and not a wildcard. Returns empty rather than throwing: a search box must
 * never crash its screen.
 */
fun ChatDatabase.searchMessages(
    input: String,
    limit: Int = 40,
): List<SearchHit> {
    val terms = likeTerms(input)
    if (terms.isEmpty()) return emptyList()
    val where = terms.joinToString(" AND ") { "rawContent LIKE ? ESCAPE '\\'" }
    val args = terms.map { "%$it%" }
    return runCatching<List<SearchHit>> {
        openHelper.readableDatabase
            .query(
                "SELECT id, conversationId, role, rawContent FROM messages WHERE $where " +
                    "ORDER BY id DESC LIMIT ?",
                (args + limit.toString()).toTypedArray(),
            ).use { cursor ->
                val hits = mutableListOf<SearchHit>()
                while (cursor.moveToNext()) {
                    hits += SearchHit(cursor.getLong(0), cursor.getString(1), cursor.getString(2), cursor.getString(3))
                }
                hits
            }
    }.getOrElse { emptyList() }
}

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

    /**
     * Streaming checkpoint.
     *
     * Guarded on the row still being STREAMING. `checkpoints?.cancelAndJoin()` orders the coroutine,
     * not Room's submitted write: a cancel that lands inside an in-flight UPDATE cannot stop it, so a
     * late checkpoint could commit AFTER `finish()` and leave the answer as a stale prefix marked
     * STREAMING with its phase row already inserted. The WHERE clause makes the terminal write win
     * regardless of commit order.
     */
    @Query(
        "UPDATE messages SET rawContent = :text, status = :status, error = :error " +
            "WHERE id = :id AND (:status != 'STREAMING' OR status = 'STREAMING')",
    )
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

    /**
     * Name a conversation that still carries the "New chat" fallback, and only such a conversation.
     *
     * The guard is the point: calling this on every send re-labelled every chat with its latest
     * message, so History stopped showing the question that opened each conversation.
     */
    @Query("UPDATE conversations SET title = :title WHERE id = :id AND title = 'New chat'")
    suspend fun retitleIfUnnamed(
        id: String,
        title: String,
    )

    /**
     * Marks rows a killed process left mid-stream, and drops their continuation records.
     *
     * The status write alone broke the "non-SUCCESS rows carry no phases" invariant that `finish`
     * maintains, leaving orphan rows behind that only the SUCCESS join kept invisible. One transaction,
     * so a crash between the two cannot leave the pair inconsistent again.
     */
    @Transaction
    suspend fun recoverInterrupted() {
        markStreamingStopped()
        deletePhasesOfNonSuccessful()
    }

    @Query("UPDATE messages SET status = 'STOPPED' WHERE status = 'STREAMING'")
    suspend fun markStreamingStopped()

    @Query("DELETE FROM openai_phases WHERE messageId IN (SELECT id FROM messages WHERE status != 'SUCCESS')")
    suspend fun deletePhasesOfNonSuccessful()

    /**
     * A turn that failed before any assistant row existed.
     *
     * Called when `admit()` never ran, which includes a Stop landing during the credential read. In that
     * window nothing had been written for the turn: a new chat was left as an EMPTY conversation with
     * the typed question gone and the reader claiming "Stopped · partial answer kept" over nothing, and
     * an edit had already replaced the message and truncated the rest, leaving a dangling question.
     * So the USER row is written here (it is what makes the turn exist) and the ASSISTANT row carries
     * the status the turn actually ended in.
     *
     * @param status "STOPPED" for a user cancel, "ERROR" for a failure.
     */
    @Transaction
    suspend fun recordFailure(
        conversationId: String,
        prompt: String?,
        profile: com.nirowear.provider.ProviderProfile?,
        error: String?,
        status: String = "ERROR",
    ) {
        // No duplicate check on the prompt. This function runs only when `admit()` never ran
        // (ChatViewModel gates it on assistantId == null), and admit is what writes the USER row — so
        // there is no row for this attempt. A text-equality check over the whole conversation was
        // worse than useless: asking "continue" or "yes" a second time matched an OLDER identical
        // message, skipped the insert, and hung the error row under a question the user had not just
        // sent.
        if (prompt != null) {
            insertMessage(StoredMessage(conversationId = conversationId, role = "USER", rawContent = prompt))
        }
        insertMessage(
            StoredMessage(
                conversationId = conversationId,
                role = "ASSISTANT",
                rawContent = "",
                status = status,
                providerProfileId = profile?.id,
                providerLabel = profile?.displayName,
                modelId = profile?.selectedModelId,
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

    /**
     * Test-support only.
     *
     * The product has no delete-a-conversation affordance (History lists and opens, the menu offers New
     * chat), so nothing in `app/src/main` calls this. It stays because the instrumentation suite uses it
     * to isolate fixtures between cases; deleting it would force rewriting those cases for no
     * user-visible gain. Marked rather than removed so it does not read as an orphan.
     */
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
    version = 2,
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
    }
}
