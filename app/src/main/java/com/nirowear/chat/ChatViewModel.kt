package com.nirowear.chat

import android.app.Application
import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.nirowear.FailureToken
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

// Decouple fast in-memory UI streaming (50 ms cadence, immediate first token) from durable SQLite
// checkpoints (1000 ms) so streaming feels responsive while minimizing Room table write churn.
private const val UI_EMIT_INTERVAL_MS = 50L
private const val UI_EMIT_INTERVAL_NS = UI_EMIT_INTERVAL_MS * 1_000_000L
private const val CHECKPOINT_INTERVAL_MS = 1000L

/** Attempts before history gives up and shows the error: 1 s, 2 s, 4 s, then stop. */
private const val MAX_HISTORY_RETRIES = 3L

/**
 * A turn refused before anything was sent or written, carrying the message to show. It exists
 * because a bare `error(...)`/`check(...)` throws IllegalStateException, which the generic catch
 * turned into "Cannot send. Check provider setup or local storage." — so a stale edit target told
 * the user their provider was misconfigured and their edited text was gone with no explanation.
 */
private class TurnRefused(
    override val message: String,
) : Exception(message)

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

/**
 * Retries transient history queries up to [maxRetries] times with exponential backoff and, when
 * [catchTerminal] is true, emits an empty list after exhaustion so passive collectors do not crash.
 */
internal fun Flow<List<Conversation>>.retryHistoryReads(
    maxRetries: Long = MAX_HISTORY_RETRIES,
    backoffMs: (Long) -> Long = { attempt ->
        (1000L shl attempt.coerceAtMost(2L).toInt()).coerceAtMost(4000L)
    },
    onRetrying: () -> Unit,
    onExhausted: () -> Unit,
    catchTerminal: Boolean,
): Flow<List<Conversation>> {
    val retried =
        retryWhen { error, attempt ->
            if (error is CancellationException) throw error
            if (attempt >= maxRetries) {
                onExhausted()
                false
            } else {
                onRetrying()
                delay(backoffMs(attempt))
                true
            }
        }
    return if (catchTerminal) {
        retried.catch { error ->
            if (error is CancellationException) throw error
            onExhausted()
            emit(emptyList())
        }
    } else {
        retried
    }
}

/** Monotonically advances [current]'s raw content so an out-of-order flush never regresses streamed text. */
internal fun advanceLiveContent(
    current: StoredMessage?,
    snapshot: String,
): StoredMessage? =
    if (current != null && snapshot.length > current.rawContent.length) {
        current.copy(rawContent = snapshot)
    } else {
        current
    }

/** True when [rows] from Room have superseded [live]'s finalized non-streaming overlay. */
internal fun isLivePersisted(
    rows: List<StoredMessage>,
    live: StoredMessage?,
    id: String = live?.conversationId.orEmpty(),
): Boolean {
    if (live == null || live.conversationId != id || live.status == "STREAMING") return false
    var sawId = false
    for (row in rows) {
        if (row.id != live.id) continue
        sawId = true
        if (row.status == live.status &&
            row.rawContent == live.rawContent &&
            row.error == live.error
        ) {
            return true
        }
    }
    // A finalized overlay whose row no longer exists in Room (deleted/truncated) must not be
    // resurrected into the transcript; only an unsaved row still marked STREAMING stays overlaid.
    return !sawId
}

/** Single-pass merge of the in-memory streaming row onto [rows] for conversation [id]. */
internal fun mergeLiveMessage(
    rows: List<StoredMessage>,
    live: StoredMessage?,
    id: String,
): List<StoredMessage> {
    if (live?.conversationId != id) return rows
    val merged = ArrayList<StoredMessage>(rows.size + 1)
    var replaced = false
    for (row in rows) {
        if (row.id == live.id) {
            merged.add(live)
            replaced = true
        } else {
            merged.add(row)
        }
    }
    if (!replaced) merged.add(live)
    return merged
}

class ChatViewModel(
    application: Application,
    savedState: SavedStateHandle? = null,
) : AndroidViewModel(application) {
    private val app = application as NiroApplication

    // Kept as a direct nullable field (not `by lazy` delegate) so reflection in HistoryReadFailureTest
    // can swap `dao` while still deferring Room database open off the Main thread on cold start.
    @Volatile private var dao: com.nirowear.data.ChatDao? = null

    private fun requireDao(): com.nirowear.data.ChatDao = dao ?: app.database.chats().also { dao = it }

    val settings get() = app.settings.state

    /**
     * History list, resilient to a transient Room failure.
     *
     * `catch { emit(emptyList()) }` reported the error and then COMPLETED the flow: one SQLITE_BUSY
     * or disk hiccup left History permanently empty for the life of this ViewModel (which survives
     * rotation, so until process death), and the message was then overwritten by the next
     * conversation open. `retryWhen` keeps the upstream alive and backs off instead.
     */
    private fun retryingConversations(): Flow<List<Conversation>> =
        requireDao().conversations().retryHistoryReads(
            onRetrying = {
                mutableState.update { it.copy(error = FailureToken.HISTORY_RETRYING) }
            },
            onExhausted = {
                // Bounded on purpose: retrying every few seconds forever is a radio-and-CPU leak
                // on a watch when the database is genuinely broken. The error stays on screen,
                // and reopening the app starts a fresh subscription.
                mutableState.update { it.copy(error = FailureToken.HISTORY_UNREADABLE) }
            },
            catchTerminal = true,
        )

    // Lazy cold Flow: never opens Room on the Main thread during ChatViewModel construction.
    val conversations: Flow<List<Conversation>> = flow { emitAll(retryingConversations()) }
    val messages = MutableStateFlow<List<StoredMessage>>(emptyList())
    private val liveAnswer = MutableStateFlow<StoredMessage?>(null)
    private val streamingBaseRows = MutableStateFlow<List<StoredMessage>?>(null)
    private var history: Job? = null
    private val ready = CompletableDeferred<Unit>()

    /** True once init has failed. A completed-exceptionally deferred looks "completed" to isCompleted. */
    private var initFailed = false

    private val mutableState = MutableStateFlow(ChatState())
    val state = mutableState.asStateFlow()
    private var generation: Job? = null
    private var pendingSave: (suspend () -> Unit)? = null

    init {
        // Background key cleanup is serialized against save()/delete() by ProviderSettings.mutex, so it
        // runs independently without blocking startup readiness or the first Send.
        viewModelScope.launch(Dispatchers.IO) {
            try {
                app.settings.recover()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(error = FailureToken.KEY_CLEANUP_PENDING) }
            }
        }
        viewModelScope.launch {
            try {
                val latestConversation =
                    withContext(Dispatchers.IO) {
                        val chats = requireDao()
                        chats.recoverInterrupted()
                        chats.latestConversation()
                    }
                latestConversation?.let {
                    mutableState.update { state -> state.copy(conversationId = it.id) }
                    observe(it.id)
                }
                ready.complete(Unit)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                initFailed = true
                mutableState.update { it.copy(error = FailureToken.HISTORY_UNAVAILABLE) }
                ready.completeExceptionally(IllegalStateException("History unavailable"))
            }
        }
    }

    fun open(id: String) {
        // `initFailed` matters here too: a deferred completed exceptionally still reports
        // isCompleted, so this used to pass and then wipe the only diagnostic ("History unavailable")
        // with a fresh empty ChatState while starting an observer that can never deliver.
        if (initFailed || mutableState.value.generating || mutableState.value.unsaved || !ready.isCompleted) return
        streamingBaseRows.value = null
        liveAnswer.value = null
        mutableState.value = ChatState(conversationId = id)
        observe(id)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observe(id: String) {
        history?.cancel()
        messages.value = emptyList()
        history =
            viewModelScope.launch {
                try {
                    streamingBaseRows
                        .flatMapLatest { frozen ->
                            if (frozen != null && frozen.firstOrNull()?.conversationId == id) {
                                // While streaming, merge in-memory deltas onto the admitted snapshot
                                // without re-querying the full Room messages table on every checkpoint.
                                liveAnswer.map { live -> mergeLiveMessage(frozen, live, id) }
                            } else {
                                combine(requireDao().messages(id), liveAnswer) { rows, live ->
                                    if (isLivePersisted(rows, live, id)) {
                                        // Room now holds the finalized row (or the row was deleted); release
                                        // the in-memory copy without a stale-status or blank-flash window.
                                        liveAnswer.compareAndSet(live, null)
                                        rows
                                    } else {
                                        mergeLiveMessage(rows, live, id)
                                    }
                                }
                            }
                        }.retryWhen { error, attempt ->
                            // Same reason as `conversations`: one transient read error must not end the
                            // observation of the open conversation forever — but bounded, so a broken
                            // database does not retry forever in the background.
                            if (error is CancellationException) throw error
                            if (attempt >= MAX_HISTORY_RETRIES) {
                                mutableState.update { it.copy(error = FailureToken.HISTORY_UNREADABLE) }
                                false
                            } else {
                                mutableState.update { it.copy(error = FailureToken.HISTORY_RETRYING) }
                                delay((1000L shl attempt.coerceAtMost(2L).toInt()).coerceAtMost(4000L))
                                true
                            }
                        }.collect { messages.value = it }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    mutableState.update { it.copy(error = FailureToken.HISTORY_UNREADABLE) }
                }
            }
    }

    fun newChat() {
        if (initFailed || mutableState.value.generating || mutableState.value.unsaved || !ready.isCompleted) return
        streamingBaseRows.value = null
        liveAnswer.value = null
        history?.cancel()
        messages.value = emptyList()
        mutableState.value = ChatState()
    }

    fun deleteConversation(id: String) {
        val decision =
            decideConversationDelete(
                initFailed = initFailed,
                generating = mutableState.value.generating,
                unsaved = mutableState.value.unsaved,
                readyCompleted = ready.isCompleted,
                activeConversationId = mutableState.value.conversationId,
                targetId = id,
            )
        if (decision == DeleteDecision.BLOCKED) return
        if (decision == DeleteDecision.DELETE_AND_CLEAR_ACTIVE) {
            streamingBaseRows.value = null
            liveAnswer.value = null
            history?.cancel()
            messages.value = emptyList()
            mutableState.value = ChatState()
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { requireDao().deleteConversation(id) }
                .onFailure { if (it is CancellationException) throw it }
        }
    }

    // Task state can be captured while Send is still writing. Keep its text, but use it only
    // if Room still has the revision this editor opened on. A save or committed Send supersedes it.
    private var restoredComposer = savedState?.get<Bundle>("composer")?.takeIf { it.containsKey("text") }
    private var composerRevision: String? = null
    private var composerLoaded = false
    var composerField by mutableStateOf(TextFieldValue())
    var composerBusy by mutableStateOf(false)
    var composerLive = false
    var pendingDictation: String? = null

    init {
        savedState?.setSavedStateProvider("composer", ::composerTaskSnapshot)
    }

    internal fun composerTaskSnapshot(): Bundle {
        if (!composerLoaded) return restoredComposer ?: Bundle()
        return Bundle().apply {
            if (composerLive) {
                putString("text", composerField.text)
                putInt("start", composerField.selection.start)
                putInt("end", composerField.selection.end)
                putString("revision", composerRevision)
            }
        }
    }

    suspend fun loadComposerDraft() {
        if (composerLoaded) return
        val stored = withContext(Dispatchers.IO) { requireDao().composerDraft() }
        val restored = restoredComposer
        composerRevision = stored?.revision
        composerField =
            if (restored != null && restored.getString("revision") == composerRevision) {
                TextFieldValue(
                    restored.getString("text").orEmpty(),
                    TextRange(restored.getInt("start"), restored.getInt("end")),
                )
            } else {
                val text = stored?.text.orEmpty()
                TextFieldValue(text, TextRange(text.length))
            }
        composerLive = restored != null && restored.getString("revision") == composerRevision
        restoredComposer = null
        composerLoaded = true
    }

    suspend fun saveComposerDraft(text: String) {
        val revision =
            java.util.UUID
                .randomUUID()
                .toString()
        // Once the write starts, publish its revision even if Activity recreation cancels Back.
        withContext(NonCancellable) {
            withContext(Dispatchers.IO) { requireDao().saveComposerDraft(text, revision) }
            composerRevision = revision
            composerLive = false
        }
    }

    private fun composerCommitted() {
        composerField = TextFieldValue()
        composerLoaded = false
        composerLive = false
    }

    /** True means the job started, not that a message committed. */
    fun send(prompt: String?): Boolean = generate(prompt)

    /** Send closes the editor only after the latest text is durable in the draft slot. */
    fun sendComposer(
        prompt: String,
        onDraftSaved: (Boolean) -> Unit,
    ): Boolean = generate(prompt, onDraftSaved = onDraftSaved)

    private fun generate(
        prompt: String?,
        editId: Long? = null,
        editedText: String? = null,
        retry: Boolean = false,
        onDraftSaved: ((Boolean) -> Unit)? = null,
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
            mutableState.update { it.copy(error = FailureToken.HISTORY_UNAVAILABLE) }
            return false
        }
        liveAnswer.value = null
        streamingBaseRows.value = null
        mutableState.update { it.copy(generating = true, error = null, rawMarkdown = null) }
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
                val chats = requireDao()
                var assistantId: Long? = null
                val text = StringBuilder()
                var terminal = "ERROR"
                var failure: String? = null
                var localPhases: StoredOpenAiPhases? = null
                var checkpoints: Job? = null
                var uiFlusher: Job? = null
                var usedProfile: com.nirowear.provider.ProviderProfile? = null
                var nothingSent = false
                var wrote = false
                try {
                    if (onDraftSaved != null) {
                        nothingSent = true
                        try {
                            saveComposerDraft(requireNotNull(prompt))
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            onDraftSaved(false)
                            return@launch
                        } finally {
                            composerBusy = false
                        }
                        nothingSent = false
                        onDraftSaved(true)
                    }
                    if (newConversationId != null) {
                        chats.insertConversation(
                            Conversation(
                                newConversationId,
                                prompt.orEmpty().take(60).ifBlank { "New chat" },
                                System.currentTimeMillis(),
                            ),
                        )
                        mutableState.update { it.copy(conversationId = newConversationId) }
                        observe(newConversationId)
                    }
                    ready.await()
                    val config = settings.first()
                    check(config.error == null) { "Provider settings unavailable" }
                    val profile = config.selected ?: throw SafeProviderException(ProviderError.PROVIDER_REQUIRED)
                    usedProfile = profile
                    val secret = withContext(Dispatchers.IO) { app.secrets.read(profile.credentialRef) }
                    val conversationId =
                        mutableState.value.conversationId ?: newConversationId ?: error("No conversation")
                    if (editId != null) {
                        val firstUserId = chats.firstUserMessageId(conversationId)
                        if (!chats.tryEdit(conversationId, editId, requireNotNull(editedText))) {
                            nothingSent = true
                            throw TurnRefused(FailureToken.MESSAGE_GONE)
                        }
                        wrote = true
                        if (firstUserId == editId) {
                            runCatching { chats.retitle(conversationId, requireNotNull(editedText).trim().take(60)) }
                        }
                    }
                    if (retry) {
                        val userId =
                            chats.lastUserMessageId(conversationId)
                                ?: throw TurnRefused(FailureToken.NOTHING_TO_RETRY)
                        chats.truncate(conversationId, userId)
                        wrote = true
                    }
                    currentCoroutineContext().ensureActive()
                    val newAssistantId =
                        withContext(NonCancellable) {
                            chats.admit(conversationId, prompt?.trim(), profile, onDraftSaved != null).also {
                                assistantId = it
                                if (onDraftSaved != null) composerCommitted()
                            }
                        }
                    currentCoroutineContext().ensureActive()
                    val admitted = chats.snapshot(conversationId)
                    liveAnswer.value = admitted.last { it.id == newAssistantId }
                    streamingBaseRows.value = admitted
                    val rows = admitted.filter { it.id != newAssistantId && it.rawContent.isNotEmpty() }
                    var lastUiPublishNs = 0L
                    var publishedUiLength = 0
                    uiFlusher =
                        launch(Dispatchers.Default) {
                            while (isActive) {
                                delay(UI_EMIT_INTERVAL_MS)
                                val snapshot =
                                    synchronized(text) {
                                        if (text.length == publishedUiLength) {
                                            null
                                        } else {
                                            publishedUiLength = text.length
                                            lastUiPublishNs = System.nanoTime()
                                            text.toString()
                                        }
                                    }
                                if (snapshot != null) {
                                    liveAnswer.update { advanceLiveContent(it, snapshot) }
                                }
                            }
                        }
                    checkpoints =
                        launch(Dispatchers.IO) {
                            var checkpointedLength = 0
                            while (isActive) {
                                delay(CHECKPOINT_INTERVAL_MS)
                                val content =
                                    synchronized(text) {
                                        if (text.length == checkpointedLength) {
                                            null
                                        } else {
                                            checkpointedLength = text.length
                                            text.toString()
                                        }
                                    } ?: continue
                                try {
                                    chats.checkpoint(newAssistantId, content, "STREAMING")
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    // Best-effort checkpoint; terminal save reports storage failure.
                                }
                            }
                        }
                    withContext(Dispatchers.Default) {
                        val provider =
                            app.provider(profile.type)
                                ?: throw SafeProviderException(ProviderError.PROVIDER_UNSUPPORTED)
                        val base = Endpoints.normalize(profile.baseUrl, com.nirowear.BuildConfig.DEBUG)
                        val continuation =
                            if (profile.type == ProviderType.OPENAI) {
                                val saved =
                                    chats
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
                                        val immediate =
                                            synchronized(text) {
                                                text.append(event.text)
                                                val now = System.nanoTime()
                                                val thresholdPassed =
                                                    lastUiPublishNs == 0L ||
                                                        now - lastUiPublishNs >= UI_EMIT_INTERVAL_NS
                                                if (text.isNotEmpty() && thresholdPassed) {
                                                    lastUiPublishNs = now
                                                    publishedUiLength = text.length
                                                    text.toString()
                                                } else {
                                                    null
                                                }
                                            }
                                        if (immediate != null) {
                                            liveAnswer.update { advanceLiveContent(it, immediate) }
                                        }
                                    }

                                    is StreamEvent.Failure -> {
                                        val hasAnyText = synchronized(text) { text.isNotEmpty() }
                                        failure = event.error.code(hasText = hasAnyText)
                                        terminal = "ERROR"
                                    }

                                    is StreamEvent.OpenAiPhases -> {
                                        check(profile.type == ProviderType.OPENAI)
                                        localPhases =
                                            StoredOpenAiPhases(
                                                newAssistantId,
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

                                    StreamEvent.Working -> {
                                        Unit
                                    }

                                    StreamEvent.Truncated -> {
                                        if (failure == null) {
                                            terminal = "SUCCESS"
                                            failure = FailureToken.TRUNCATED
                                        }
                                    }
                                }
                            }
                    }
                    if (terminal != "SUCCESS" && failure == null) {
                        val hasAnyText = synchronized(text) { text.isNotEmpty() }
                        failure = ProviderError.PROTOCOL.code(hasText = hasAnyText)
                    }
                } catch (cancelled: CancellationException) {
                    if (terminal != "SUCCESS") terminal = "STOPPED"
                    throw cancelled
                } catch (refused: TurnRefused) {
                    failure = refused.message
                } catch (_: CredentialUnavailable) {
                    failure = ProviderError.CREDENTIAL_REENTRY.code(hasText = false)
                } catch (error: SafeProviderException) {
                    val hasAnyText = synchronized(text) { text.isNotEmpty() }
                    failure = error.error.code(hasText = hasAnyText)
                } catch (_: Exception) {
                    failure = FailureToken.SEND_FAILED
                } finally {
                    val streamJob = currentCoroutineContext()[Job]!!
                    withContext(NonCancellable) {
                        uiFlusher?.cancelAndJoin()
                        checkpoints?.cancelAndJoin()
                        val finalText = synchronized(text) { text.toString() }

                        fun settleCancellation() {
                            if (streamJob.isCancelled && terminal != "SUCCESS") {
                                terminal = "STOPPED"
                                failure = null
                                localPhases = null
                            }
                        }
                        try {
                            assistantId?.let {
                                ChatStorageProbe.finishing()
                                settleCancellation()
                                chats.finish(it, finalText, terminal, failure, localPhases)
                            }
                        } catch (_: Exception) {
                            settleCancellation()
                            val savedId = assistantId
                            val savedStatus = terminal
                            val savedFailure = failure
                            val savedPhases = localPhases
                            pendingSave = {
                                savedId?.let {
                                    ChatStorageProbe.finishing()
                                    chats.finish(it, finalText, savedStatus, savedFailure, savedPhases)
                                    liveAnswer.update { current ->
                                        if (current?.id == it) {
                                            current.copy(
                                                rawContent = finalText,
                                                status = savedStatus,
                                                error = savedFailure,
                                            )
                                        } else {
                                            current
                                        }
                                    }
                                }
                            }
                            failure = FailureToken.ANSWER_NOT_SAVED
                            mutableState.update { it.copy(rawMarkdown = finalText, unsaved = true) }
                        }
                        val recordingId = mutableState.value.conversationId ?: newConversationId
                        if (assistantId == null && !nothingSent && recordingId != null &&
                            ((editId == null && !retry) || wrote)
                        ) {
                            runCatching {
                                chats.ensureConversation(
                                    Conversation(
                                        recordingId,
                                        prompt.orEmpty().take(60).ifBlank { "New chat" },
                                        System.currentTimeMillis(),
                                    ),
                                )
                                chats.recordFailure(
                                    recordingId,
                                    if (editId == null && !retry) prompt?.trim() else null,
                                    usedProfile,
                                    failure,
                                    status = if (failure == null) "STOPPED" else "ERROR",
                                    consumeComposerDraft = onDraftSaved != null,
                                )
                                if (onDraftSaved != null) composerCommitted()
                            }
                        }
                        // Publish the finalized row BEFORE unfreezing streamingBaseRows so the UI
                        // never flashes blank or stale STREAMING status while Room delivers the commit;
                        // observe() clears liveAnswer once Room emits the matching persisted row.
                        liveAnswer.value =
                            liveAnswer.value?.copy(rawContent = finalText, status = terminal, error = failure)
                        streamingBaseRows.value = null
                        mutableState.update {
                            it.copy(
                                generating = false,
                                interrupted = terminal == "STOPPED",
                                error = failure,
                            )
                        }
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
        mutableState.update { it.copy(generating = true) }
        generation =
            viewModelScope.launch {
                try {
                    save()
                    pendingSave = null
                    // Observe releases liveAnswer once Room emits the committed row without flashing STREAMING.
                    mutableState.update { it.copy(rawMarkdown = null, unsaved = false, error = null) }
                } catch (cancelled: CancellationException) {
                    // Stop during a save retry must not be reported as a storage failure: the write is
                    // a @Transaction that still commits, and swallowing cancellation left pendingSave
                    // set with a "Still cannot save" message over text that was in fact saved.
                    throw cancelled
                } catch (_: Exception) {
                    mutableState.update { it.copy(error = FailureToken.SAVE_STILL_FAILING) }
                } finally {
                    mutableState.update { it.copy(generating = false) }
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
        streamingBaseRows.value = null
        liveAnswer.value = null
        history?.cancel()
        messages.value = emptyList()
        mutableState.value = ChatState(rawMarkdown = "", generating = true)
        generation =
            viewModelScope.launch {
                try {
                    fakeStream(source).collect { chunk ->
                        mutableState.update { it.copy(rawMarkdown = it.rawMarkdown.orEmpty() + chunk) }
                    }
                } finally {
                    mutableState.update { it.copy(generating = false) }
                }
            }
    }

    fun stop() {
        generation?.cancel()
        mutableState.update { it.copy(interrupted = true) }
    }
}

internal enum class DeleteDecision {
    BLOCKED,
    DELETE_KEEP_ACTIVE,
    DELETE_AND_CLEAR_ACTIVE,
}

internal fun decideConversationDelete(
    initFailed: Boolean,
    generating: Boolean,
    unsaved: Boolean,
    readyCompleted: Boolean,
    activeConversationId: String?,
    targetId: String,
): DeleteDecision =
    if (initFailed || generating || unsaved || !readyCompleted) {
        DeleteDecision.BLOCKED
    } else if (activeConversationId == targetId) {
        DeleteDecision.DELETE_AND_CLEAR_ACTIVE
    } else {
        DeleteDecision.DELETE_KEEP_ACTIVE
    }
