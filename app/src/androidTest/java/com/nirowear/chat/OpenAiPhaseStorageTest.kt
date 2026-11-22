package com.nirowear.chat

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.nirowear.data.*
import com.nirowear.provider.ProviderProfile
import com.nirowear.provider.ProviderType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class OpenAiPhaseStorageTest {
    @Test fun draftMigrationPreservesV2HistoryAndPhases(): Unit = verifyUpgrade(2)

    @Test fun upgradePreservesHistoryAndPhaseCommitIsAtomic(): Unit = verifyUpgrade(1)

    @Test fun sendConsumesDraftOnlyWithCommittedTurn(): Unit =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val database = Room.inMemoryDatabaseBuilder(context, ChatDatabase::class.java).build()
            try {
                val dao = database.chats()
                val conversation = Conversation("draft-turn", "Draft turn", 1)
                dao.insertConversation(conversation)
                val profile = ProviderProfile("profile", ProviderType.OPENAI, "OpenAI", "https://api.openai.com/v1", "model", "ref")
                val db = database.openHelper.writableDatabase
                for (fallback in listOf(false, true)) {
                    val before = dao.snapshot(conversation.id)
                    dao.saveComposerDraft("  Repeat this  ")
                    suspend fun storeTurn() {
                        if (fallback) {
                            dao.recordFailure(conversation.id, "Repeat this", profile, "fixture", consumeComposerDraft = true)
                        } else {
                            dao.admit(conversation.id, "Repeat this", profile, consumeComposerDraft = true)
                        }
                    }
                    db.execSQL(
                        "CREATE TRIGGER reject_draft_answer BEFORE INSERT ON messages WHEN NEW.role = 'ASSISTANT' " +
                            "BEGIN SELECT RAISE(ABORT, 'fixture'); END",
                    )
                    try {
                        assertThrows(android.database.sqlite.SQLiteConstraintException::class.java) {
                            runBlocking { storeTurn() }
                        }
                        assertEquals(before, dao.snapshot(conversation.id))
                        assertEquals("  Repeat this  ", dao.readComposerDraft())
                    } finally {
                        db.execSQL("DROP TRIGGER reject_draft_answer")
                    }
                    storeTurn()
                    assertNull(dao.readComposerDraft())
                    val added = dao.snapshot(conversation.id).drop(before.size)
                    assertEquals(listOf("USER", "ASSISTANT"), added.map { it.role })
                    assertEquals("Repeat this", added.first().rawContent)
                }
            } finally {
                database.close()
            }
        }

    private fun verifyUpgrade(version: Int): Unit =
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
                if (version == 2) {
                    db.execSQL(
                        "CREATE TABLE openai_phases (messageId INTEGER NOT NULL, profileId TEXT NOT NULL, modelId TEXT NOT NULL, baseUrl TEXT NOT NULL, phases TEXT NOT NULL, PRIMARY KEY(messageId), FOREIGN KEY(messageId) REFERENCES messages(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
                    )
                    db.execSQL("UPDATE room_master_table SET identity_hash = '9ae11899e595ca28a8541b8ea9124c94' WHERE id = 42")
                    db.execSQL("INSERT INTO openai_phases VALUES (2,'profile','model','https://api.openai.com/v1','[]')")
                }
                db.version = version
            }
            val database =
                Room
                    .databaseBuilder(
                        context,
                        ChatDatabase::class.java,
                        name,
                    ).addMigrations(ChatDatabase.MIGRATION_1_2, ChatDatabase.MIGRATION_2_3)
                    .build()
            try {
                val dao = database.chats()
                assertEquals(listOf("Question", "Answer"), dao.snapshot("conversation").map { it.rawContent })
                assertEquals("profile", dao.snapshot("conversation").last().providerProfileId)
                assertNull(dao.readComposerDraft())
                if (version == 2) {
                    val original = StoredOpenAiPhases(2, "profile", "model", "https://api.openai.com/v1", "[]")
                    assertEquals(listOf(original), dao.openAiPhases("conversation", "profile", "model", original.baseUrl))
                    return@runBlocking
                }
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
