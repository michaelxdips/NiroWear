package com.nirowear.chat

import androidx.test.platform.app.InstrumentationRegistry
import com.nirowear.NiroApplication
import com.nirowear.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Host-only persisted documents; release uses ordinary history/reader, no fake adapter. */
class WearAcceptanceSeedTest {
    @com.nirowear.HostDrivenGate
    @Test fun verifyDocuments() =
        runBlocking {
            assumeTrue(InstrumentationRegistry.getArguments().getString("wearAcceptance") == "verify")
            val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NiroApplication
            val dao = app.database.chats()
            val conversations = dao.conversations().first()
            val canonical = StringBuilder()
            assertEquals(
                "No duplicate fixture conversations",
                13,
                conversations.count { it.id.startsWith("wear-acceptance-") },
            )
            (0..12).forEach { index ->
                val rows = dao.snapshot("wear-acceptance-$index")
                assertEquals("Exactly two rows for fixture $index", 2, rows.size)
                assertEquals(listOf("USER", "ASSISTANT"), rows.map { it.role })
                assertEquals(
                    if (index == 10) {
                        "STOPPED"
                    } else if (index == 11) {
                        "ERROR"
                    } else {
                        "SUCCESS"
                    },
                    rows.last().status,
                )
                assertTrue(rows.last().rawContent.isNotBlank())
                assertEquals("document-fixture", rows.last().modelId)
                canonical
                    .append(
                        "$index:${rows.first().id}:${rows.last().id}:${rows.last().status}:${rows.last().rawContent.length}:",
                    ).append(rows.last().rawContent)
                    .append('\n')
            }
            samples().entries.forEachIndexed { index, (title, body) ->
                assertEquals("Show $title", dao.snapshot("wear-acceptance-$index").first().rawContent)
                assertEquals(body, dao.snapshot("wear-acceptance-$index").last().rawContent)
                assertEquals("Acceptance · $title", conversations.single { it.id == "wear-acceptance-$index" }.title)
            }
            val digest =
                java.security.MessageDigest
                    .getInstance("SHA-256")
                    .digest(canonical.toString().toByteArray())
                    .joinToString("") { "%02x".format(it) }
            java.io.File(app.getExternalFilesDir(null), "closure-fixture-sha256.txt").writeText(digest)
            Unit
        }

    @com.nirowear.HostDrivenGate
    @Test fun cleanupDocuments() =
        runBlocking {
            assumeTrue(InstrumentationRegistry.getArguments().getString("wearAcceptance") == "cleanup")
            val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NiroApplication
            (0..12).forEach { app.database.chats().deleteConversation("wear-acceptance-$it") }
        }

    @com.nirowear.HostDrivenGate
    @Test fun seedDocuments() =
        runBlocking {
            assumeTrue(InstrumentationRegistry.getArguments().getString("wearAcceptance") == "seed")
            val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as NiroApplication
            val dao = app.database.chats()
            samples().entries.forEachIndexed { index, (title, body) ->
                val id = "wear-acceptance-$index"
                dao.deleteConversation(id) // Only fixed, explicitly owned fixture rows.
                dao.insertConversation(Conversation(id, "Acceptance · $title", 1000L - index))
                dao.insertMessage(StoredMessage(conversationId = id, role = "USER", rawContent = "Show $title"))
                dao.insertMessage(
                    StoredMessage(
                        conversationId = id,
                        role = "ASSISTANT",
                        rawContent = body,
                        status =
                            if (title == "Interrupted") {
                                "STOPPED"
                            } else if (title == "Errors") {
                                "ERROR"
                            } else {
                                "SUCCESS"
                            },
                        providerProfileId = "wear-acceptance",
                        providerLabel = "Local acceptance",
                        modelId = "document-fixture",
                        error =
                            if (title ==
                                "Errors"
                            ) {
                                "Provider unreachable. Check connection and try again."
                            } else {
                                null
                            },
                    ),
                )
            }
            Unit
        }

    private fun samples(): Map<String, String> {
        val fixture =
            InstrumentationRegistry
                .getInstrumentation()
                .context.assets
                .open("RENDER_TORTURE_FIXTURE.md")
                .bufferedReader()
                .use { it.readText() }
        return linkedMapOf(
            "One line" to "Gravity attracts mass.",
            "Three lines" to "Gravity attracts objects with mass. Earth pulls us toward its center.",
            "Half viewport" to
                "## Gravity\n\nMass attracts mass.\n\n- Earth pulls downward.\n- Orbit is continuous falling.",
            "Near viewport" to
                "## Gravity\n\nMass attracts mass.\n\nEarth pulls downward.\n\nOrbit is continuous falling.\n\nWeight depends on gravity.",
            "Over viewport" to
                "## Gravity\n\nMass attracts mass.\n\nEarth pulls downward.\n\nOrbit is continuous falling.\n\nWeight depends on gravity.\n\nThe Moon has weaker gravity.",
            "Long mixed" to (1..25).joinToString("\n\n") { "## Section $it\n\n$fixture" },
            "Math" to "## Inline math" + fixture.substringAfter("## Inline math").substringBefore("## Code"),
            "Unicode" to
                "## Unicode and symbols" +
                fixture.substringAfter("## Unicode and symbols").substringBefore("## Inline math"),
            "Table" to
                "| Field | Value | Meaning |\n|---|---|---|\n| Long gravitational acceleration label | 9.81 m/s² | Near Earth |\n| Mass | 2 kg | Object |\n| Force | F=ma | Result |\n\n| Item | Value |\n|---|---|\n| Radius | r |\n| Area | πr² |",
            "Code" to
                "## Kotlin\n```kotlin\nfun veryLongIdentifierForHorizontalPanAcceptance(parameterWithLongName: String) = parameterWithLongName\n    if (true) {\n        println(\"Indent preserved\")\n    }\n```\n\n## Python\n```python\nfor item in items:\n    if item:\n        print(item)\n```\n\n## JSON\n```json\n{\"long_property_name_for_pan\": {\"nested\": [1, 2, 3]}}\n```\n\n## Shell\n```sh\nprintf '%s\\n' \"long shell value with spaces\"\n```",
            "Interrupted" to "## Partial answer\n\nVisible prefix remains after interruption. No replay.",
            "Errors" to "Provider unreachable. Check connection and try again.",
            "Canonical fixture" to fixture,
        )
    }
}
