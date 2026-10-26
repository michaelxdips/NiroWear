package com.nirowear.provider

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Native stateless Responses. Canonical portable text stays independent of OpenAI Items. */
class OpenAiProvider(
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
            val body = http.fetch(authenticated(profile, secret, "models").build())
            // Official /models has no pagination or text capability fields. Do not infer from names.
            // One malformed entry must not discard an otherwise valid list.
            val ids =
                Json
                    .parseToJsonElement(body)
                    .jsonObject["data"]
                    ?.jsonArray
                    .orEmpty()
                    .mapNotNull { (it as? JsonObject)?.get("id").stringOrNull() }
                    .filter { it.isNotBlank() && it.length <= 256 }
                    .distinct()
                    .sorted()
            // ponytail: 10k discovered IDs; raise after watch picker/heap acceptance.
            require(ids.size <= 10_000)
            if (ids.isEmpty()) {
                ModelDiscoveryResult.Unavailable(
                    ProviderError.PROTOCOL,
                )
            } else {
                ModelDiscoveryResult.Models(ids)
            }
        } catch (
            cancelled: CancellationException,
        ) {
            throw cancelled
        } catch (
            error: SafeProviderException,
        ) {
            ModelDiscoveryResult.Unavailable(error.error)
        } catch (
            _: Exception,
        ) {
            ModelDiscoveryResult.Unavailable(ProviderError.PROTOCOL)
        }

    override fun streamChat(
        request: ChatRequest,
        profile: ProviderProfile,
        secret: SecretValue,
    ): Flow<StreamEvent> {
        require(profile.selectedModelId.isNotBlank()) { "Model required" }
        val payload =
            buildJsonObject {
                put("model", profile.selectedModelId)
                put("stream", true)
                put("store", false)
                if (request.systemInstruction.isNotBlank()) put("instructions", request.systemInstruction)
                put(
                    "input",
                    buildJsonArray {
                        request.messages.forEachIndexed { index, message ->
                            val local =
                                request.openAiContinuation
                                    ?.takeIf {
                                        it.profileId == profile.id && it.modelId == profile.selectedModelId &&
                                            it.baseUrl == Endpoints.normalize(profile.baseUrl, allowLocalHttp)
                                    }?.phases
                                    ?.get(index)
                            if (local != null && message.role == ChatRole.ASSISTANT) {
                                require(
                                    local.isNotEmpty() &&
                                        local.all {
                                            it.length >= 0 &&
                                                it.phase in listOf(null, "commentary", "final_answer")
                                        },
                                )
                                require(local.sumOf { it.length.toLong() } == message.rawContent.length.toLong())
                                var offset = 0
                                local.forEach { part ->
                                    add(
                                        buildJsonObject {
                                            val end = offset + part.length
                                            require(
                                                end == 0 || end == message.rawContent.length ||
                                                    !(
                                                        message.rawContent[end - 1].isHighSurrogate() &&
                                                            message.rawContent[end].isLowSurrogate()
                                                    ),
                                            )
                                            put("role", "assistant")
                                            put("content", message.rawContent.substring(offset, end))
                                            part.phase?.let { put("phase", it) }
                                            offset = end
                                        },
                                    )
                                }
                            } else {
                                add(
                                    buildJsonObject {
                                        put("role", message.role.name.lowercase())
                                        put("content", message.rawContent)
                                    },
                                )
                            }
                        }
                    },
                )
            }
        val wire =
            authenticated(profile, secret, "responses")
                .header("Accept", "text/event-stream")
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
        // Each collection gets its own stateful decoder, including validation/retry collectors.
        return flow { emitAll(http.stream(wire, OpenAiStreamDecoder()::accept)) }
    }

    private fun authenticated(
        profile: ProviderProfile,
        secret: SecretValue,
        path: String,
    ): Request.Builder =
        Request
            .Builder()
            .url(Endpoints.compatible(profile.baseUrl, path, allowLocalHttp))
            .header("Authorization", "Bearer ${secret.forRequest()}")
}

/** Only response.completed with matching final text proves success; no DONE/EOF shortcut. */
internal class OpenAiStreamDecoder {
    private val text = StringBuilder()
    private var responseId: String? = null

    fun accept(payload: String): List<StreamEvent> =
        try {
            val root = Json.parseToJsonElement(payload).jsonObject
            when (root.getValue("type").jsonPrimitive.content) {
                // Official event list includes response.queued; it precedes created and carries no text.
                "response.created", "response.in_progress", "response.queued" -> {
                    val id =
                        root
                            .getValue("response")
                            .jsonObject
                            .getValue("id")
                            .jsonPrimitive.content
                    require(responseId == null || responseId == id)
                    responseId = id
                    emptyList()
                }

                "response.output_text.delta", "response.refusal.delta" -> {
                    val delta = root.getValue("delta").jsonPrimitive
                    require(delta.isString)
                    if (delta.content.length > MAX_RESPONSE_CHARS - text.length) {
                        listOf(StreamEvent.Failure(ProviderError.RESPONSE_TOO_LARGE))
                    } else {
                        text.append(delta.content)
                        listOf(StreamEvent.TextDelta(delta.content))
                    }
                }

                "response.completed" -> {
                    val phases = mutableListOf<OpenAiPhase>()
                    val response = root.getValue("response").jsonObject
                    require(response.getValue("status").jsonPrimitive.content == "completed")
                    require(responseId == null || response.getValue("id").jsonPrimitive.content == responseId)
                    require(response["error"] == null || response["error"] == JsonNull)
                    val finalText =
                        buildString {
                            response.getValue("output").jsonArray.forEach { element ->
                                val item = element.jsonObject
                                when (item.getValue("type").jsonPrimitive.content) {
                                    "reasoning" -> {
                                        Unit
                                    }

                                    "message" -> {
                                        val phase = phase(item)
                                        var length = 0
                                        require(item.getValue("role").jsonPrimitive.content == "assistant")
                                        require(item.getValue("status").jsonPrimitive.content == "completed")
                                        item.getValue("content").jsonArray.forEach { content ->
                                            val part = content.jsonObject
                                            val visible =
                                                when (part.getValue("type").jsonPrimitive.content) {
                                                    "output_text" -> requireNotNull(part["text"].stringOrNull())
                                                    "refusal" -> requireNotNull(part["refusal"].stringOrNull())
                                                    else -> error("Unsupported content")
                                                }
                                            length += visible.length
                                            append(visible)
                                        }
                                        phases.add(OpenAiPhase(length, phase))
                                    }

                                    else -> {
                                        error("Unsupported item")
                                    }
                                }
                            }
                        }
                    require(finalText.isNotEmpty() && finalText == text.toString())
                    if (phases.any { it.phase != null }) {
                        listOf(StreamEvent.OpenAiPhases(phases), StreamEvent.Completed)
                    } else {
                        listOf(StreamEvent.Completed)
                    }
                }

                "response.failed" -> {
                    listOf(StreamEvent.Failure(openAiError(root.getValue("response").jsonObject["error"])))
                }

                "error" -> {
                    listOf(StreamEvent.Failure(openAiError(root)))
                }

                "response.incomplete" -> {
                    listOf(StreamEvent.Failure(ProviderError.PROTOCOL))
                }

                "response.output_item.added", "response.output_item.done" -> {
                    val item = root.getValue("item").jsonObject
                    require(item.getValue("type").jsonPrimitive.content in setOf("message", "reasoning"))
                    if (item.getValue("type").jsonPrimitive.content == "message") phase(item)
                    emptyList()
                }

                "response.content_part.added", "response.content_part.done" -> {
                    require(
                        root
                            .getValue("part")
                            .jsonObject
                            .getValue("type")
                            .jsonPrimitive.content in
                            setOf("output_text", "refusal"),
                    )
                    emptyList()
                }

                "response.output_text.done", "response.refusal.done", "response.output_text.annotation.added",
                "response.reasoning_summary_part.added", "response.reasoning_summary_part.done",
                "response.reasoning_summary_text.delta", "response.reasoning_summary_text.done",
                "response.reasoning_text.delta", "response.reasoning_text.done",
                -> {
                    emptyList()
                }

                // Unknown events *inside the response namespace* are informational and must not abort
                // an otherwise valid stream (B3-01 was this exact class). Ignoring them is safe because
                // response.completed restates the full text and line below requires it to equal the
                // accumulated deltas, and a missing terminal still fails at EOF. Anything outside the
                // response namespace stays fatal.
                else -> {
                    if (root
                            .getValue("type")
                            .jsonPrimitive.content
                            .startsWith("response.")
                    ) {
                        emptyList()
                    } else {
                        listOf(StreamEvent.Failure(ProviderError.PROTOCOL))
                    }
                }
            }
        } catch (_: Exception) {
            listOf(StreamEvent.Failure(ProviderError.PROTOCOL))
        }

    private fun phase(item: JsonObject): String? {
        val value = item["phase"] ?: return null
        if (value == JsonNull) return null
        require(value is JsonPrimitive && value.isString && value.content in setOf("commentary", "final_answer"))
        return value.content
    }
}

private fun openAiError(element: JsonElement?): ProviderError =
    when ((element as? JsonObject)?.get("code")?.jsonPrimitive?.contentOrNull) {
        "invalid_api_key" -> ProviderError.INVALID_CREDENTIAL
        "permission_denied" -> ProviderError.PERMISSION_DENIED
        "model_not_found" -> ProviderError.MODEL_UNAVAILABLE
        "rate_limit_exceeded", "insufficient_quota", "credit_balance_exhausted" -> ProviderError.RATE_LIMITED
        "server_error", "server_is_overloaded" -> ProviderError.SERVER
        else -> ProviderError.HTTP
    }
