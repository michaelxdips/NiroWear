package com.nirowear.chat

import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import com.nirowear.FailureToken
import com.nirowear.NiroApplication
import com.nirowear.data.ChatDao
import com.nirowear.data.Conversation
import com.nirowear.data.StoredMessage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.UUID

class HistoryReadFailureTest {
    @Test fun failedMessageCollectorShowsErrorWithoutDeletingHistoryAndCanReopen() =
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val app = instrumentation.targetContext.applicationContext as NiroApplication
            val dao = app.database.chats()
            val id = "history-fault-${UUID.randomUUID()}"
            var vm: ChatViewModel? = null
            try {
                dao.insertConversation(Conversation(id, "Controlled history read", System.currentTimeMillis()))
                dao.insertMessage(StoredMessage(conversationId = id, role = "USER", rawContent = "Keep this row"))
                instrumentation.runOnMainSync { vm = ChatViewModel(app) }
                val chat = vm!!
                val ready =
                    ChatViewModel::class.java
                        .getDeclaredField("ready")
                        .apply {
                            isAccessible = true
                        }.get(chat) as CompletableDeferred<*>
                withTimeout(5000) { ready.await() }
                val field = ChatViewModel::class.java.getDeclaredField("dao").apply { isAccessible = true }
                val failing =
                    Proxy.newProxyInstance(
                        ChatDao::class.java.classLoader,
                        arrayOf(ChatDao::class.java),
                    ) { _, method, args ->
                        if (method.name ==
                            "messages"
                        ) {
                            flow<List<StoredMessage>> { throw IOException("Controlled read failure") }
                        } else {
                            method.invoke(dao, *(args ?: emptyArray()))
                        }
                    } as ChatDao
                field.set(chat, failing)
                withContext(Dispatchers.Main) { chat.open(id) }
                // NOT 5 s. HISTORY_UNREADABLE is only emitted once the retry budget is spent:
                // MAX_HISTORY_RETRIES = 3 with 1 s + 2 s + 4 s backoff = ≥7 s after open(), so a 5 s
                // deadline always fired first and the test failed on a timeout it had guaranteed.
                withTimeout(20000) { chat.state.first { it.error == FailureToken.HISTORY_UNREADABLE } }
                assertEquals("Keep this row", dao.snapshot(id).single().rawContent)
                field.set(chat, dao)
                withContext(Dispatchers.Main) { chat.open(id) }
                withTimeout(5000) { chat.messages.first { it.size == 1 } }
                assertNull(chat.state.value.error)
                assertEquals(
                    "Keep this row",
                    chat.messages.value
                        .single()
                        .rawContent,
                )
            } finally {
                instrumentation.runOnMainSync { vm?.viewModelScope?.cancel() }
                dao.deleteConversation(id)
            }
        }
}
