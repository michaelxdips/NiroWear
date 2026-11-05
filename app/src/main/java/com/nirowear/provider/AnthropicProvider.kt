package com.nirowear.provider

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Pinned per the reference implementation; the API version header is exact, never "latest". */
private const val ANTHROPIC_VERSION = "2026-10-21"

/**
 * Documented StopReason values. All of them end the message; only the presence of `message_stop`
 * proves the stream completed, so none of these may be treated as a failure.
 */
private val KNOWN_STOP_REASONS =
    setOf(
        "end_turn",
        "max_tokens",
        "stop_sequence",
        "tool_use",
        "pause_turn",
        "refusal",
        "model_context_window_exceeded",
    )

class AnthropicProvider(
    client: OkHttpClient = providerHttpClient(),
    private val allowLocalHttp: Boolean = false,
) : LlmProvider {
    private val http = ProviderHttp(client)

    override suspend fun validate(
        profile: ProviderProfile,
        secret: SecretValue,
    ): ProviderError? {
        var error: ProviderError? = ProviderError.PROTOCOL
        streamChat(ChatRequest(listOf(ChatMessage(ChatRole.USER, "Reply OK."))), profile, secret).collect {
            when (it) {
                StreamEvent.Completed -> error = null
                is StreamEvent.Failure -> error = it.error
                else -> Unit
            }
        }
        return error
    }

    override suspend fun listModels(
        profile: ProviderProfile,
        secret: SecretValue,
    ): ModelDiscoveryResult =
        try {
            val ids = mutableSetOf<String>()
            val cursors = mutableSetOf<String>()
            var cursor: String? = null
            do {
                val url =
                    Endpoints
                        .compatible(profile.baseUrl, "models", allowLocalHttp)
                        .newBuilder()
                        .addQueryParameter("limit", "1000")
                        .apply { cursor?.let { addQueryParameter("after_id", it) } }
                        .build()
                val root = Json.parseToJsonElement(http.fetch(authenticated(url, secret).build())).jsonObject
                // One malformed entry must not discard an otherwise valid list.
                root["data"]?.jsonArray.orEmpty().forEach { element ->
                    val id = (element as? JsonObject)?.get("id").stringOrNull()
                    if (id != null && id.isNotBlank() && id.length <= 256) ids += id
                    // ponytail: 10k discovered IDs; raise after watch picker/heap acceptance.
                    require(ids.size <= 10_000)
                }
                val hasMore = root["has_more"]
                require(
                    hasMore == null || (hasMore is JsonPrimitive && !hasMore.isString && hasMore.booleanOrNull != null),
                )
                cursor =
                    if (hasMore?.jsonPrimitive?.booleanOrNull ==
                        true
                    ) {
                        requireNotNull(root["last_id"].stringOrNull())
                    } else {
                        null
                    }
                // ponytail: 4096 cursor chars; raise only for documented larger provider tokens.
                require(
                    cursor == null ||
                        (cursor.isNotBlank() && cursor.length <= 4096 && cursors.size < 100 && cursors.add(cursor)),
                )
            } while (cursor != null)
            if (ids.isEmpty()) {
                ModelDiscoveryResult.Unavailable(ProviderError.PROTOCOL)
            } else {
                ModelDiscoveryResult.Models(ids.sorted())
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: SafeProviderException) {
            ModelDiscoveryResult.Unavailable(error.error)
        } catch (_: Exception) {
            ModelDiscoveryResult.Unavailable(ProviderError.PROTOCOL)
        }

    override fun streamChat(
        request: ChatRequest,
        profile: ProviderProfile,
        secret: SecretValue,
    ): Flow<StreamEvent> =
        flow {
            require(profile.selectedModelId.isNotBlank()) { "Model required" }
            try {
                // Messages requires max_tokens. Model metadata is the only honest source; never guess a
                // limit from a model name. Discovery and generation are separate, so a metadata failure
                // falls back to the documented default instead of blocking the turn. A bad credential or
                // unreachable host still fails on the messages call itself.
                val reportedMaxTokens =
                    try {
                        val modelUrl =
                            Endpoints
                                .compatible(profile.baseUrl, "models", allowLocalHttp)
                                .newBuilder()
                                .addPathSegment(profile.selectedModelId)
                                .build()
                        Json
                            .parseToJsonElement(http.fetch(authenticated(modelUrl, secret).build()))
                            .jsonObject["max_tokens"]
                            ?.jsonPrimitive
                            ?.intOrNull
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                // ponytail: null or unusable metadata uses 8192; replace with provider limit metadata when available.
                val maxTokens = reportedMaxTokens?.takeIf { it > 0 } ?: 8192
                val payload =
                    buildJsonObject {
                        put("model", profile.selectedModelId)
                        put("max_tokens", maxTokens)
                        put("stream", true)
                        if (request.systemInstruction.isNotBlank()) put("system", request.systemInstruction)
                        put(
                            "messages",
                            buildJsonArray {
                                // Messages requires alternating turns on Bedrock/Vertex; first-party merges them.
                                request.messages.withAlternatingRoles().forEach { message ->
                                    add(
                                        buildJsonObject {
                                            put("role", message.role.name.lowercase())
                                            put("content", message.rawContent)
                                        },
                                    )
                                }
                            },
                        )
                    }
                val url = Endpoints.compatible(profile.baseUrl, "messages", allowLocalHttp)
                val wire =
                    authenticated(url, secret)
                        .header("Accept", "text/event-stream")
                        .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                        .build()
                emitAll(http.stream(wire, AnthropicStreamDecoder()::accept))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: SafeProviderException) {
                emit(StreamEvent.Failure(error.error))
            } catch (_: Exception) {
                emit(StreamEvent.Failure(ProviderError.PROTOCOL))
            }
        }

    private fun authenticated(
        url: HttpUrl,
        secret: SecretValue,
    ) = Request
        .Builder()
        .url(url)
        // Both headers authenticate an API key. The API overview documents
        // "Authorization: Bearer <key>" as primary ("required unless x-api-key is set") and
        // "x-api-key" as a legacy fallback that is still supported; the get-started cURL example for
        // the API-key tab uses x-api-key, which is why this adapter sends it, while the official SDKs
        // send Bearer. Neither is wrong — do not "correct" one to the other without reading both.
        .header("x-api-key", secret.forRequest())
        .header("anthropic-version", ANTHROPIC_VERSION)
}

internal class AnthropicStreamDecoder {
    private var started = false
    private var block: Int? = null
    private var nextIndex = 0
    private var reason: String? = null
    private var hasText = false

    /** Indices of blocks we deliberately do not render (thinking, tool_use, ...). */
    private val skipped = mutableSetOf<Int>()

    fun accept(payload: String): List<StreamEvent> =
        try {
            val root = Json.parseToJsonElement(payload).jsonObject
            when (root.getValue("type").jsonPrimitive.content) {
                "ping" -> {
                    emptyList()
                }

                "message_start" -> {
                    require(!started)
                    val message = root.getValue("message").jsonObject
                    require(
                        message.getValue("role").jsonPrimitive.content == "assistant" &&
                            message.getValue("content").jsonArray.isEmpty(),
                    )
                    started = true
                    emptyList()
                }

                "content_block_start" -> {
                    require(started && block == null && reason == null)
                    val index = root.getValue("index").jsonPrimitive.int
                    require(index == nextIndex++)
                    val content = root.getValue("content_block").jsonObject
                    // A gateway with extended thinking or tools enabled sends thinking,
                    // redacted_thinking, tool_use or server_tool_use blocks. The old code required
                    // every block to be text, so the first such block aborted a perfectly good
                    // answer. Track the block index and skip non-text blocks instead: message_stop
                    // is still the only thing that proves success.
                    if (content.getValue("type").jsonPrimitive.content != "text") {
                        skipped.add(index)
                        emptyList()
                    } else {
                        block = index
                        val text = requireNotNull(content["text"].stringOrNull())
                        if (text.isNotEmpty()) {
                            hasText = true
                            listOf(StreamEvent.TextDelta(text))
                        } else {
                            emptyList()
                        }
                    }
                }

                "content_block_delta" -> {
                    val index = root.getValue("index").jsonPrimitive.int
                    // Deltas for a skipped (non-text) block are expected; ignore them rather than
                    // failing the stream.
                    if (index in skipped) {
                        emptyList()
                    } else {
                        require(started && block != null && index == block)
                        val delta = root.getValue("delta").jsonObject
                        require(delta.getValue("type").jsonPrimitive.content == "text_delta")
                        val text = delta.getValue("text").jsonPrimitive
                        require(text.isString)
                        if (text.content.isNotEmpty()) hasText = true
                        listOf(StreamEvent.TextDelta(text.content))
                    }
                }

                "content_block_stop" -> {
                    val index = root.getValue("index").jsonPrimitive.int
                    if (index in skipped) {
                        skipped.remove(index)
                        emptyList()
                    } else {
                        require(block != null && index == block)
                        block = null
                        emptyList()
                    }
                }

                "message_delta" -> {
                    require(started && block == null)
                    root
                        .getValue("delta")
                        .jsonObject["stop_reason"]
                        ?.jsonPrimitive
                        ?.contentOrNull
                        ?.let { reason = it }
                    // Every StopReason value is a documented terminal state, not a framing fault:
                    // end_turn, max_tokens, stop_sequence, tool_use, pause_turn, refusal,
                    // model_context_window_exceeded. Rejecting max_tokens (the common case once the
                    // answer reaches the model's cap) relabelled a complete answer as
                    // "Stream interrupted". Only a reason this build has never heard of is suspicious,
                    // and message_stop still has the final word.
                    emptyList()
                }

                "message_stop" -> {
                    // `hasText` is deliberately NOT required: a refusal, or a reply made only of
                    // thinking blocks, legitimately ends with no text. message_stop plus a known
                    // stop_reason is the protocol's own completion proof.
                    require(started && block == null && reason in KNOWN_STOP_REASONS)
                    listOf(StreamEvent.Completed)
                }

                "error" -> {
                    listOf(
                        StreamEvent.Failure(
                            when (
                                root
                                    .getValue("error")
                                    .jsonObject
                                    .getValue("type")
                                    .jsonPrimitive.content
                            ) {
                                "authentication_error" -> ProviderError.INVALID_CREDENTIAL
                                "permission_error" -> ProviderError.PERMISSION_DENIED
                                "not_found_error" -> ProviderError.MODEL_UNAVAILABLE
                                "rate_limit_error" -> ProviderError.RATE_LIMITED
                                "api_error", "overloaded_error" -> ProviderError.SERVER
                                else -> ProviderError.HTTP
                            },
                        ),
                    )
                }

                // Native API allows new informational event types; none can prove completion.
                else -> {
                    emptyList()
                }
            }
        } catch (_: Exception) {
            listOf(StreamEvent.Failure(ProviderError.PROTOCOL))
        }
}
