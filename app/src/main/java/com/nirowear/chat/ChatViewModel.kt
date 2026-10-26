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
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
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

private const val CHECKPOINT_INTERVAL_MS = 120L

data class ChatState(
    val prompt: String = "",
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
    val conversations =
        dao.conversations().catch { error ->
            if (error is CancellationException) throw error
            mutableState.value = mutableState.value.copy(error = "History cannot be read. No messages deleted.")
            emit(emptyList())
        }
    val messages = MutableStateFlow<List<StoredMessage>>(emptyList())
    private val liveAnswer = MutableStateFlow<StoredMessage?>(null)
    private var history: Job? = null
    private val ready = CompletableDeferred<Unit>()
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
                mutableState.value = mutableState.value.copy(error = "History unavailable. No messages changed.")
                ready.completeExceptionally(IllegalStateException("History unavailable"))
            }
        }
    }

    fun open(id: String) {
        if (mutableState.value.generating || mutableState.value.unsaved || !ready.isCompleted) return
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
        if (mutableState.value.generating || mutableState.value.unsaved || !ready.isCompleted) return
        liveAnswer.value = null
        history?.cancel()
        messages.value = emptyList()
        mutableState.value = ChatState()
    }

    fun send(prompt: String?) = generate(prompt)

    private fun generate(
        prompt: String?,
        editId: Long? = null,
        editedText: String? = null,
        retry: Boolean = false,
    ) {
        if (prompt?.isBlank() == true || mutableState.value.unsaved || mutableState.value.generating ||
            generation?.isActive == true
        ) {
            return
        }
        liveAnswer.value = null
        mutableState.value = mutableState.value.copy(generating = true, error = null, rawMarkdown = null)
        generation =
            viewModelScope.launch {
                var assistantId: Long? = null
                val text = StringBuilder()
                var terminal = "ERROR"
                var failure: String? = null
                var localPhases: StoredOpenAiPhases? = null
                var checkpoints: Job? = null
                try {
                    ready.await()
                    val config = settings.first()
                    check(config.error == null) { "Provider settings unavailable" }
                    val profile = config.selected ?: throw SafeProviderException(ProviderError.PROVIDER_REQUIRED)
                    val provider =
                        app.provider(profile.type) ?: throw SafeProviderException(ProviderError.PROVIDER_UNSUPPORTED)
                    val secret = withContext(Dispatchers.IO) { app.secrets.read(profile.credentialRef) }
                    val conversationId =
                        mutableState.value.conversationId ?: java.util.UUID.randomUUID().toString().also { id ->
                            dao.insertConversation(
                                Conversation(
                                    id,
                                    prompt.orEmpty().take(60).ifBlank { "New chat" },
                                    System.currentTimeMillis(),
                                ),
                            )
                            mutableState.value = mutableState.value.copy(conversationId = id)
                            observe(id)
                        }
                    if (editId != null) dao.edit(conversationId, editId, requireNotNull(editedText))
                    if (retry) {
                        val user =
                            dao.snapshot(conversationId).lastOrNull { it.role == "USER" } ?: error("No user message")
                        dao.truncate(conversationId, user.id)
                    }
                    assistantId = dao.admit(conversationId, prompt?.trim(), profile)
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
                            // Never downgrade a completed answer. If the terminal event already
                            // arrived, the answer is complete and a later Stop must not rewrite it
                            // as STOPPED — that would also delete its phase record, because
                            // ChatDao.finish drops phases for any non-SUCCESS status.
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
    }

    fun edit(
        id: Long,
        text: String,
    ) {
        if (text.isBlank() || mutableState.value.conversationId == null) return
        generate(null, id, text)
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
        mutableState.value = ChatState(prompt = prompt.trim(), rawMarkdown = "", generating = true)
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
