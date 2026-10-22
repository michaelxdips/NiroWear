package com.nirowear.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "openai_phases", foreignKeys = [ForeignKey(entity = StoredMessage::class,
    parentColumns = ["id"], childColumns = ["messageId"], onDelete = ForeignKey.CASCADE)])
data class StoredOpenAiPhases(@PrimaryKey val messageId: Long, val profileId: String,
    val modelId: String, val baseUrl: String, val phases: String)

@Entity(tableName = "conversations")
data class Conversation(@PrimaryKey val id: String, val title: String, val updatedAt: Long)

@Entity(tableName = "messages", indices = [Index("conversationId")], foreignKeys = [ForeignKey(
    entity = Conversation::class, parentColumns = ["id"], childColumns = ["conversationId"], onDelete = ForeignKey.CASCADE,
)])
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

@Dao
interface ChatDao {
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC") fun conversations(): Flow<List<Conversation>>
    @Query("SELECT * FROM messages WHERE conversationId = :id ORDER BY id") fun messages(id: String): Flow<List<StoredMessage>>
    @Query("SELECT * FROM messages WHERE conversationId = :id ORDER BY id") suspend fun snapshot(id: String): List<StoredMessage>
    @Insert suspend fun insertConversation(conversation: Conversation)
    @Insert suspend fun insertMessage(message: StoredMessage): Long
    @Insert suspend fun insertOpenAiPhases(value: StoredOpenAiPhases)
    @Query("DELETE FROM openai_phases WHERE messageId = :id") suspend fun deleteOpenAiPhases(id: Long)
    @Query("SELECT p.* FROM openai_phases p JOIN messages m ON m.id = p.messageId WHERE m.conversationId = :id AND m.role = 'ASSISTANT' AND m.status = 'SUCCESS' AND p.profileId = :profileId AND p.modelId = :modelId AND p.baseUrl = :baseUrl")
    suspend fun openAiPhases(id: String, profileId: String, modelId: String, baseUrl: String): List<StoredOpenAiPhases>
    @Query("UPDATE messages SET rawContent = :text, status = :status, error = :error WHERE id = :id")
    suspend fun checkpoint(id: Long, text: String, status: String, error: String? = null)
    @Transaction
    suspend fun finish(id: Long, text: String, status: String, error: String?, phases: StoredOpenAiPhases?) {
        checkpoint(id, text, status, error)
        if (status != "SUCCESS") deleteOpenAiPhases(id)
        if (status == "SUCCESS" && phases != null) {
            require(phases.messageId == id)
            insertOpenAiPhases(phases)
        }
    }
    @Query("UPDATE conversations SET updatedAt = :time WHERE id = :id") suspend fun touch(id: String, time: Long)
    @Query("UPDATE messages SET status = 'STOPPED' WHERE status = 'STREAMING'") suspend fun recoverInterrupted()
    @Query("DELETE FROM messages WHERE conversationId = :conversationId AND id > :id") suspend fun truncate(conversationId: String, id: Long)
    @Query("UPDATE messages SET rawContent = :text WHERE id = :id AND role = 'USER'") suspend fun replaceUser(id: Long, text: String): Int
    @Query("DELETE FROM conversations WHERE id = :id") suspend fun deleteConversation(id: String)
    @Query("UPDATE conversations SET title = :title WHERE id = :id") suspend fun rename(id: String, title: String)

    @Transaction
    suspend fun edit(conversationId: String, id: Long, text: String) {
        require(text.isNotBlank())
        require(snapshot(conversationId).any { it.id == id && it.role == "USER" })
        check(replaceUser(id, text) == 1)
        truncate(conversationId, id)
    }

    @Transaction
    suspend fun admit(conversationId: String, prompt: String?, profile: com.nirowear.provider.ProviderProfile): Long {
        if (prompt != null) insertMessage(StoredMessage(conversationId = conversationId, role = "USER", rawContent = prompt))
        touch(conversationId, System.currentTimeMillis())
        return insertMessage(StoredMessage(conversationId = conversationId, role = "ASSISTANT", rawContent = "", status = "STREAMING",
            providerProfileId = profile.id, providerLabel = profile.displayName, modelId = profile.selectedModelId))
    }
}

@Database(entities = [Conversation::class, StoredMessage::class, StoredOpenAiPhases::class], version = 2, exportSchema = true)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun chats(): ChatDao
    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS openai_phases (messageId INTEGER NOT NULL, profileId TEXT NOT NULL, modelId TEXT NOT NULL, baseUrl TEXT NOT NULL, phases TEXT NOT NULL, PRIMARY KEY(messageId), FOREIGN KEY(messageId) REFERENCES messages(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
            }
        }
    }
}
