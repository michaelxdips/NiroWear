package com.nirowear.chat

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.nirowear.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class OpenAiPhaseStorageTest {
    @Test fun upgradePreservesHistoryAndPhaseCommitIsAtomic() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val name = "phase-migration-test.db"
            context.deleteDatabase(name)
            val file = context.getDatabasePath(name)
            file.parentFile!!.mkdirs()
            SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
                db.execSQL(
                    "CREATE TABLE conversations (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, updatedAt INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, conversationId TEXT NOT NULL, role TEXT NOT NULL, rawContent TEXT NOT NULL, status TEXT NOT NULL, providerProfileId TEXT, providerLabel TEXT, modelId TEXT, error TEXT, FOREIGN KEY(conversationId) REFERENCES conversations(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL("CREATE INDEX index_messages_conversationId ON messages(conversationId)")
                db.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
                db.execSQL("INSERT INTO room_master_table VALUES(42, 'aee71b73f7ec5175c8ba40a983074a68')")
                db.execSQL("INSERT INTO conversations VALUES('conversation','Original',1)")
                db.execSQL(
                    "INSERT INTO messages VALUES(1,'conversation','USER','Question','SUCCESS',NULL,NULL,NULL,NULL)",
                )
                db.execSQL(
                    "INSERT INTO messages VALUES(2,'conversation','ASSISTANT','Answer','SUCCESS','profile','OpenAI','model',NULL)",
                )
                db.version = 1
            }
            val database =
                Room
                    .databaseBuilder(
                        context,
                        ChatDatabase::class.java,
                        name,
                    ).addMigrations(ChatDatabase.MIGRATION_1_2)
                    .build()
            try {
                val dao = database.chats()
                assertEquals(listOf("Question", "Answer"), dao.snapshot("conversation").map { it.rawContent })
                assertEquals("profile", dao.snapshot("conversation").last().providerProfileId)
                val phase =
                    StoredOpenAiPhases(
                        2,
                        "profile",
                        "model",
                        "https://api.openai.com/v1",
                        "[{\"length\":6,\"phase\":\"final_answer\"}]",
                    )
                dao.finish(2, "Answer", "SUCCESS", null, phase)
                assertEquals(listOf(phase), dao.openAiPhases("conversation", "profile", "model", phase.baseUrl))
                assertTrue(dao.openAiPhases("conversation", "other", "model", phase.baseUrl).isEmpty())
                assertTrue(dao.openAiPhases("conversation", "profile", "other", phase.baseUrl).isEmpty())
                assertTrue(runCatching { dao.finish(2, "Must roll back", "SUCCESS", null, phase) }.isFailure)
                assertEquals("Answer", dao.snapshot("conversation").last().rawContent)
                dao.checkpoint(2, "Answer", "STOPPED")
                assertTrue(dao.openAiPhases("conversation", "profile", "model", phase.baseUrl).isEmpty())
                dao.edit("conversation", 1, "Edited")
                assertEquals(1, dao.snapshot("conversation").size)
                database.openHelper.readableDatabase.query("SELECT count(*) FROM openai_phases").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(0, cursor.getInt(0))
                }
            } finally {
                database.close()
                context.deleteDatabase(name)
            }
        }
}
