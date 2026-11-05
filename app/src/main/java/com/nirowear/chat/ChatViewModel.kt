package com.nirowear.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nirowear.NiroApplication
import com.nirowear.data.Conversation
import com.nirowear.data.StoredMessage
import com.nirowear.data.StoredOpenAiPhases
import com.nirowear.provider.ChatMessage
import com.nirowear.provider.ChatRequest
import com.nirowear.provider.ChatRole
import com.nirowear.provider.Endpoints
import com.nirowear.provider.OpenAiContinuation
import com.nirowear.provider.OpenAiPhase
import com.nirowear.provider.ProviderError
import com.nirowear.provider.ProviderType
import com.nirowear.provider.SafeProviderException
import com.nirowear.provider.StreamEvent
import com.nirowear.security.CredentialUnavailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

// Durability vs cost. Each checkpoint is an UPDATE carrying the WHOLE answer so far, and Room
// invalidates the conversation's message query with it, so the reader re-reads and re-lists every
// message. At 120 ms that was ~8 full writes and 8 full reads per second while streaming — O(n²)
// bytes for one answer. 400 ms keeps process-death loss under half a second of text, which is the
// only thing this interval buys, while cutting that traffic by two thirds.
private const val CHECKPOINT_INTERVAL_MS = 400L

/** Attempts before history gives up and shows the error: 1 s, 2 s, 4 s, then stop. */
private const val MAX_HISTORY_RETRIES = 3L

data class ChatState(
    val rawMarkdown: String? = null,
    val generating: Boolean = false,
    val interrupted: Boolean = false,
    val error: String? = null,
    val conversationId: String? = null,
    val unsaved: Boolean = false,
)

/** Deterministic local stream, not a provider response. */
fun fakeStream(source: String) =
    flow {
        var offset = 0
        var chunk = 0
        val sizes = intArrayOf(32, 1, 47, 7, 53)
        while (offset < source.length) {
            var end = (offset + sizes[chunk++ % sizes.size]).coerceAtMost(source.length)
            if (end < source.length && source[end - 1].isHighSurrogate() && source[end].isLowSurrogate()) end++
            emit(source.substring(offset, end))
            offset = end
            delay(80)
        }
    }

class ChatViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val app = application as NiroApplication
    private val dao = app.database.chats()
    val settings = app.settings.state

    /**
     * History list, resilient to a transient Room failure.
     *
     * `catch { emit(emptyList()) }` reported the error and then COMPLETED the flow: one SQLITE_BUSY
     * or disk hiccup left History permanently empty for the life of this ViewModel (which survives
     * rotation, so until process death), and the message was then overwritten by the next
     * conversation open. `retryWhen` keeps the upstream alive and backs off instead.
     */
    val conversations =
        dao
            .conversations()
            .retryWhen { error, attempt ->
                if (error is CancellationException) throw error
                if (attempt >= MAX_HISTORY_RETRIES) {
                    // Bounded on purpose: retrying every few seconds forever is a radio-and-CPU leak
                    // on a watch when the database is genuinely broken. The error stays on screen,
                    // and reopening the app starts a fresh subscription.
                    mutableState.value =
                        mutableState.value.copy(error = "History cannot be read. No messages deleted.")
                    false
                } else {
                    mutableState.value = mutableState.value.copy(error = "History cannot be read. Retrying.")
                    delay((1000L shl attempt.coerceAtMost(2L).toInt()).coerceAtMost(4000L))
                    true
                }
            }
    val messages = MutableStateFlow<List<StoredMessage>>(emptyList())
    private val liveAnswer = MutableStateFlow<StoredMessage?>(null)
    private var history: Job? = null
    private val ready = CompletableDeferred<Unit>()

    /** True once init has failed. A completed-exceptionally deferred looks "completed" to isCompleted. */
    private var initFailed = false

    private val mutableState = MutableStateFlow(ChatState())
    val state = mutableState.asStateFlow()
    private var generation: Job? = null
    private var pendingSave: (suspend () -> Unit)? = null

    init {
        viewModelScope.launch {
            try {
                try {
                    app.settings.recover()
                } catch (
                    cancelled: CancellationException,
                ) {
                    throw cancelled
                } catch (_: Exception) {
                    mutableState.value =
                        mutableState.value.copy(
                            error = "Encrypted-key cleanup pending. History kept; reopen app to retry.",
                        )
                }
                dao.recoverInterrupted()
                conversations.first().firstOrNull()?.let {
                    mutableState.value = mutableState.value.copy(conversationId = it.id)
                    observe(it.id)
                }
                ready.complete(Unit)
            } catch (_: Exception) {
                initFailed = true
                mutableState.value = mutableState.value.copy(error = "History unavailable. No messages changed.")
                ready.completeExceptionally(IllegalStateException("History unavailable"))
            }
        }
    }

    fun open(id: String) {
        // `initFailed` matters here too: a deferred completed exceptionally still reports
        // isCompleted, so this used to pass and then wipe the only diagnostic ("History unavailable")
        // with a fresh empty ChatState while starting an observer that can never deliver.
        if (initFailed || mutableState.value.generating || mutableState.value.unsaved || !ready.isCompleted) return
        liveAnswer.value = null
        mutableState.value = ChatState(conversationId = id)
        observe(id)
    }

    private fun observe(id: String) {
        history?.cancel()
        messages.value = emptyList()
        history =
            viewModelScope.launch {
                try {
                    combine(dao.messages(id), liveAnswer) { rows, live ->
                        if (live?.conversationId != id) {
                            rows
                        } else {
                            rows.map { if (it.id == live.id) live else it } +
                                if (rows.none { it.id == live.id }) listOf(live) else emptyList()
                        }
                    }.retryWhen { error, attempt ->
                        // Same reason as `conversations`: one transient read error must not end the
                        // observation of the open conversation forever — but bounded, so a broken
                        // database does not retry forever in the background.
                        if (error is CancellationException) throw error
                        if (attempt >= MAX_HISTORY_RETRIES) {
                            mutableState.value =
                                mutableState.value.copy(error = "History cannot be read. No messages deleted.")
                            false
                        } else {
                            mutableState.value =
                                mutableState.value.copy(error = "History cannot be read. Retrying.")
                            delay((1000L shl attempt.coerceAtMost(2L).toInt()).coerceAtMost(4000L))
                            true
                        }
                    }.collect { messages.value = it }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    mutableState.value =
                        mutableState.value.copy(error = "History cannot be read. No messages deleted.")
                }
            }
    }

    fun newChat() {
        if (initFailed || mutableState.value.generating || mutableState.value.unsaved || !ready.isCompleted) return
        liveAnswer.value = null
        history?.cancel()
        messages.value = emptyList()
        mutableState.value = ChatState()
    }

    /** True when the turn actually started, so the caller knows whether the text was accepted. */
    fun send(prompt: String?): Boolean = generate(prompt)

    private fun generate(
        prompt: String?,
        editId: Long? = null,
        editedText: String? = null,
        retry: Boolean = false,
    ): Boolean {
        if (prompt?.isBlank() == true || mutableState.value.unsaved || mutableState.value.generating ||
            generation?.isActive == true
        ) {
            return false
        }
        // `open`/`newChat` refuse to run before init settles; a send must too. Without this a failed
        // init still created a conversation, observed it, then threw at `ready.await()`, so the user
        // saw a provider-setup error over a "History unavailable. No messages changed." state — and
        // a conversation row had in fact been written.
        if (initFailed || !ready.isCompleted) {
            mutableState.value = mutableState.value.copy(error = "History unavailable. No messages changed.")
            return false
        }
        liveAnswer.value = null
        mutableState.value = mutableState.value.copy(generating = true, error = null, rawMarkdown = null)
        // The conversation is created as the FIRST thing the job does, before the settings and
        // credential reads. It used to be created after them, so a failure earlier than that (no
        // profile, missing key, unreadable settings) left `conversationId` null and the typed prompt
        // only in the composer — which the reader unmounts while generating. That send silently cost
        // the user their question.
        val newConversationId =
            if (mutableState.value.conversationId == null) {
                java.util.UUID
                    .randomUUID()
                    .toString()
            } else {
                null
            }
        generation =
            viewModelScope.launch {
                var assistantId: Long? = null
                val text = StringBuilder()
                var terminal = "ERROR"
                var failure: String? = null
                var localPhases: StoredOpenAiPhases? = null
                var checkpoints: Job? = null
                // Which profile this turn was aimed at, so a failure row still has provenance.
                var usedProfile: com.nirowear.provider.ProviderProfile? = null
                try {
                    if (newConversationId != null) {
                        dao.insertConversation(
                            Conversation(
                                newConversationId,
                                prompt.orEmpty().take(60).ifBlank { "New chat" },
                                System.currentTimeMillis(),
                            ),
                        )
                        mutableState.value = mutableState.value.copy(conversationId = newConversationId)
                        observe(newConversationId)
                    }
                    ready.await()
                    val config = settings.first()
                    check(config.error == null) { "Provider settings unavailable" }
                    val profile = config.selected ?: throw SafeProviderException(ProviderError.PROVIDER_REQUIRED)
                    usedProfile = profile
                    val provider =
                        app.provider(profile.type) ?: throw SafeProviderException(ProviderError.PROVIDER_UNSUPPORTED)
                    val secret = withContext(Dispatchers.IO) { app.secrets.read(profile.credentialRef) }
                    // Created above for a new chat; edit/retry only run on an existing one.
                    val conversationId = mutableState.value.conversationId ?: error("No conversation")
                    if (editId != null) {
                        // Verify the target belongs to THIS conversation BEFORE mutating anything. An
                        // edit target can outlive its conversation (a restored screen state, or a
                        // conversation switch that did not clear it); the failing `require` inside
                        // dao.edit threw after the caller had already cleared the composer, so the
                        // typed text was lost and a spurious ERROR row landed in the open conversation.
                        val owned = dao.snapshot(conversationId).any { it.id == editId && it.role == "USER" }
                        if (!owned) {
                            // Generic failure text, not "add a provider": the provider is fine, the
                            // edit simply belongs to another conversation.
                            error("Edit target is not part of this conversation")
                        }
                        dao.edit(conversationId, editId, requireNotNull(editedText))
                    }
                    if (retry) {
                        val user =
                            dao.snapshot(conversationId).lastOrNull { it.role == "USER" } ?: error("No user message")
                        dao.truncate(conversationId, user.id)
                    }
                    val newAssistantId = dao.admit(conversationId, prompt?.trim(), profile)
                    assistantId = newAssistantId
                    // Name the chat from its FIRST prompt. The previous version used `prompt` (the
                    // current message) whether or not a title existed, so every send RENAMED an
                    // existing conversation to its latest message and History stopped being
                    // recognisable. The `title = 'New chat'` guard makes it a no-op once named.
                    prompt?.trim()?.takeIf { it.isNotEmpty() }?.let { text ->
                        runCatching { dao.retitleIfUnnamed(conversationId, text.take(60)) }
                    }
                    val admitted = dao.snapshot(conversationId)
                    liveAnswer.value = admitted.first { it.id == assistantId }
                    val rows = admitted.filter { it.id != assistantId && it.rawContent.isNotEmpty() }
                    checkpoints =
                        launch {
                            var publishedLength = 0
                            while (isActive) {
                                delay(CHECKPOINT_INTERVAL_MS)
                                if (text.length == publishedLength) continue
                                val content = text.toString()
                                publishedLength = text.length
                                liveAnswer.value = liveAnswer.value?.copy(rawContent = content)
                                try {
                                    dao.checkpoint(assistantId, content, "STREAMING")
                                } catch (
                                    cancelled: CancellationException,
                                ) {
                                    throw cancelled
                                } catch (
                                    _: Exception,
                                ) {
                                    // Best-effort checkpoint; terminal save reports storage failure.
                                }
                            }
                        }
                    val base = Endpoints.normalize(profile.baseUrl, com.nirowear.BuildConfig.DEBUG)
                    val continuation =
                        if (profile.type == ProviderType.OPENAI) {
                            val saved =
                                dao
                                    .openAiPhases(
                                        conversationId,
                                        profile.id,
                                        profile.selectedModelId,
                                        base,
                                    ).associateBy { it.messageId }
                            OpenAiContinuation(
                                profile.id,
                                profile.selectedModelId,
                                base,
                                rows
                                    .mapIndexedNotNull { index, row ->
                                        saved[row.id]?.let {
                                            index to
                                                Json.parseToJsonElement(it.phases).jsonArray.map { element ->
                                                    val part = element.jsonObject
                                                    OpenAiPhase(
                                                        part.getValue("length").jsonPrimitive.int,
                                                        part["phase"]?.jsonPrimitive?.contentOrNull,
                                                    )
                                                }
                                        }
                                    }.toMap(),
                            )
                        } else {
                            null
                        }
                    val context = rows.map { ChatMessage(ChatRole.valueOf(it.role), it.rawContent) }
                    provider
                        .streamChat(
                            ChatRequest(context, openAiContinuation = continuation),
                            profile,
                            secret,
                        ).collect { event ->
                            when (event) {
                                is StreamEvent.TextDelta -> {
                                    text.append(event.text)
                                }

                                is StreamEvent.Failure -> {
                                    failure = event.error.userMessage
                                    terminal = "ERROR"
                                }

                                is StreamEvent.OpenAiPhases -> {
                                    check(profile.type == ProviderType.OPENAI)
                                    localPhases =
                                        StoredOpenAiPhases(
                                            assistantId,
                                            profile.id,
                                            profile.selectedModelId,
                                            base,
                                            JsonArray(
                                                event.phases.map { part ->
                                                    buildJsonObject {
                                                        put("length", part.length)
                                                        part.phase?.let { put("phase", it) }
                                                    }
                                                },
                                            ).toString(),
                                        )
                                }

                                StreamEvent.Completed -> {
                                    if (failure == null) terminal = "SUCCESS"
                                }
                            }
                        }
                    if (terminal != "SUCCESS" && failure == null) failure = ProviderError.PROTOCOL.userMessage
                } catch (cancelled: CancellationException) {
                    terminal = "STOPPED"
                    throw cancelled
                } catch (_: CredentialUnavailable) {
                    failure = ProviderError.CREDENTIAL_REENTRY.userMessage
                } catch (error: SafeProviderException) {
                    failure = error.error.userMessage
                } catch (_: Exception) {
                    failure = "Cannot send. Check provider setup or local storage."
                } finally {
                    val streamJob = currentCoroutineContext()[Job]!!
                    withContext(NonCancellable) {
                        checkpoints?.cancelAndJoin()

                        fun settleCancellation() {
                            // Do NOT downgrade a finished answer. StreamEvent.Completed already set
                            // terminal = "SUCCESS" (:309) and filled localPhases; a Stop that lands
                            // between the terminal event and this write must not rewrite it as
                            // STOPPED, because ChatDao.finish deletes phase records for any
                            // non-SUCCESS status (:116) — that loses the continuation record of a
                            // complete answer AND renders "Stopped · partial answer kept" under it.
                            //
                            // Stop BEFORE the terminal event is still honoured: terminal is not yet
                            // SUCCESS there, so the branch below runs and the partial text is kept
                            // and marked stopped.
                            if (streamJob.isCancelled && terminal != "SUCCESS") {
                                terminal = "STOPPED"
                                failure = null
                                localPhases = null
                            }
                        }
                        try {
                            assistantId?.let {
                                ChatStorageProbe.finishing()
                                // Decide the status ONCE, before the write. The previous version
                                // settled cancellation, wrote, then checked again and rewrote the
                                // row if cancellation had arrived in between. That second write
                                // turned a SUCCESS into STOPPED and, because ChatDao.finish deletes
                                // phase records for any non-SUCCESS status, destroyed the
                                // continuation data of a fully completed answer.
                                settleCancellation()
                                dao.finish(it, text.toString(), terminal, failure, localPhases)
                            }
                        } catch (_: Exception) {
                            settleCancellation()
                            val savedText = text.toString()
                            val savedId = assistantId
                            val savedStatus = terminal
                            val savedFailure = failure
                            val savedPhases = localPhases
                            pendingSave = {
                                savedId?.let {
                                    ChatStorageProbe.finishing()
                                    dao.finish(it, savedText, savedStatus, savedFailure, savedPhases)
                                }
                            }
                            failure =
                                "Answer not saved. Keep app open; Copy unsaved answer or Retry saving. New chat/send blocked until saved."
                            mutableState.value = mutableState.value.copy(rawMarkdown = savedText, unsaved = true)
                        }
                        // A provider failure with no partial text left the USER row already written
                        // with nothing after it, so the next send read as a continuation of a
                        // question whose answer never happened. Record the failure on the
                        // conversation so history agrees with what the reader shows. Sends only:
                        // edit and retry already truncate their own turn and show it transiently.
                        //
                        // `admit()` never ran, so NOTHING exists for this turn yet. Record it — for a Stop as well as a
                        // failure. Without this: a new chat was left as an empty conversation with the
                        // typed question gone while the reader still said "Stopped · partial answer
                        // kept"; an edit had already replaced the message and truncated everything after
                        // it, so the turn vanished and the next send appended USER,USER.
                        if (assistantId == null) {
                            mutableState.value.conversationId?.let { id ->
                                runCatching {
                                    dao.recordFailure(
                                        id,
                                        prompt?.trim(),
                                        usedProfile,
                                        failure,
                                        // A user cancel is STOPPED, not ERROR: the reader renders the
                                        // same line it already shows for a stopped answer.
                                        status = if (failure == null) "STOPPED" else "ERROR",
                                    )
                                }
                            }
                        }
                        liveAnswer.value =
                            liveAnswer.value?.copy(rawContent = text.toString(), status = terminal, error = failure)
                        mutableState.value =
                            mutableState.value.copy(
                                generating = false,
                                interrupted = terminal == "STOPPED",
                                error = failure,
                            )
                    }
                }
            }
        // The turn was accepted: the job owns the text from here.
        return true
    }

    /** True when the edit was accepted and the turn started. */
    fun edit(
        id: Long,
        text: String,
    ): Boolean {
        if (text.isBlank() || mutableState.value.conversationId == null) return false
        return generate(null, id, text)
    }

    fun retry() {
        if (mutableState.value.conversationId == null) return
        generate(null, retry = true)
    }

    fun retrySaving() {
        val save = pendingSave ?: return
        if (generation?.isActive == true || mutableState.value.generating) return
        mutableState.value = mutableState.value.copy(generating = true)
        generation =
            viewModelScope.launch {
                try {
                    save()
                    pendingSave = null
                    mutableState.value = mutableState.value.copy(rawMarkdown = null, unsaved = false, error = null)
                } catch (cancelled: CancellationException) {
                    // Stop during a save retry must not be reported as a storage failure: the write is
                    // a @Transaction that still commits, and swallowing cancellation left pendingSave
                    // set with a "Still cannot save" message over text that was in fact saved.
                    throw cancelled
                } catch (_: Exception) {
                    mutableState.value =
                        mutableState.value.copy(error = "Still cannot save. Keep app open and copy unsaved answer.")
                } finally {
                    mutableState.value = mutableState.value.copy(generating = false)
                }
            }
    }

    fun start(
        prompt: String,
        source: String,
    ) {
        if (prompt.isBlank() || mutableState.value.unsaved || mutableState.value.generating ||
            generation?.isActive == true
        ) {
            return
        }
        history?.cancel()
        messages.value = emptyList()
        mutableState.value = ChatState(rawMarkdown = "", generating = true)
        generation =
            viewModelScope.launch {
                try {
                    fakeStream(source).collect { chunk ->
                        mutableState.value =
                            mutableState.value.copy(rawMarkdown = mutableState.value.rawMarkdown.orEmpty() + chunk)
                    }
                } finally {
                    mutableState.value = mutableState.value.copy(generating = false)
                }
            }
    }

    fun stop() {
        generation?.cancel()
        mutableState.value = mutableState.value.copy(interrupted = true)
    }
}
