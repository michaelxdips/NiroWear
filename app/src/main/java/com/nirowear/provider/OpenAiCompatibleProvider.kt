package com.nirowear.provider

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.SocketTimeoutException

private val jsonMedia = "application/json; charset=utf-8".toMediaType()

/**
 * Generic [OI]-compatible Chat Completions adapter.
 *
 * HTTP framing, redirects, idle timeout and cancellation live in [ProviderHttp], shared with the
 * three native adapters. This file used to carry its own copy of that loop; the copy drifted (its
 * own hardcoded idle value, different EOF classification) and is gone.
 */
class OpenAiCompatibleProvider(
    client: OkHttpClient = providerHttpClient(),
    private val allowLocalHttp: Boolean = false,
) : LlmProvider {
    private val http = ProviderHttp(client)

    override suspend fun validate(
        profile: ProviderProfile,
        secret: SecretValue,
    ): ProviderError? {
        // Selected-model generation tests auth separately from optional model listing.
        val request = ChatRequest(listOf(ChatMessage(ChatRole.USER, "Reply OK.")))
        var result: ProviderError? = ProviderError.PROTOCOL
        streamChat(request, profile, secret).collect { event ->
            when (event) {
                StreamEvent.Completed -> result = null
                is StreamEvent.Failure -> result = event.error
                else -> Unit
            }
        }
        return result
    }

    override suspend fun listModels(
        profile: ProviderProfile,
        secret: SecretValue,
    ): ModelDiscoveryResult {
        val request =
            Request
                .Builder()
                .url(Endpoints.compatible(profile.baseUrl, "models", allowLocalHttp))
                .header("Authorization", "Bearer ${secret.forRequest()}")
                .build()
        return try {
            val body = http.fetch(request)
            val ids =
                Json
                    .parseToJsonElement(body)
                    .jsonObject
                    .getValue("data")
                    .jsonArray
                    .mapNotNull { (it as? JsonObject)?.get("id").stringOrNull() }
                    .filter { it.isNotBlank() && it.length <= 256 }
                    .distinct()
                    .sorted()
            // ponytail: 10k discovered IDs; raise after watch picker/heap acceptance.
            require(ids.size <= 10_000)
            if (ids.isEmpty()) {
                ModelDiscoveryResult.Unavailable(ProviderError.PROTOCOL)
            } else {
                ModelDiscoveryResult.Models(ids)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: SafeProviderException) {
            ModelDiscoveryResult.Unavailable(error.error)
        } catch (_: Exception) {
            ModelDiscoveryResult.Unavailable(ProviderError.PROTOCOL)
        }
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
                put(
                    "messages",
                    buildJsonArray {
                        if (request.systemInstruction.isNotBlank()) {
                            add(
                                buildJsonObject {
                                    put("role", "system")
                                    put("content", request.systemInstruction)
                                },
                            )
                        }
                        request.messages.forEach { message ->
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
        val wire =
            Request
                .Builder()
                .url(Endpoints.compatible(profile.baseUrl, "chat/completions", allowLocalHttp))
                .header("Authorization", "Bearer ${secret.forRequest()}")
                .header("Accept", "text/event-stream")
                .post(payload.toString().toRequestBody(jsonMedia))
                .build()
        // A fresh decoder per collection: it is stateful (hasText across frames).
        return flow { emitAll(http.stream(wire, CompatibleStreamDecoder()::accept)) }
    }
}

private fun okio.BufferedSource.readUtf8LineStrictOrNull(limit: Long): String? {
    if (exhausted()) return null
    if (indexOf('\n'.code.toByte(), 0, limit + 1) == -1L && request(limit + 1)) {
        throw SafeProviderException(ProviderError.RESPONSE_TOO_LARGE)
    }
    return readUtf8LineStrict(limit)
}

class SafeProviderException(
    val error: ProviderError,
) : Exception(error.userMessage)

fun httpError(code: Int): ProviderError =
    when (code) {
        401 -> ProviderError.INVALID_CREDENTIAL
        403 -> ProviderError.PERMISSION_DENIED
        404 -> ProviderError.MODEL_UNAVAILABLE
        429 -> ProviderError.RATE_LIMITED
        in 500..599 -> ProviderError.SERVER
        else -> ProviderError.HTTP
    }

private fun networkError(error: IOException) =
    if (error is SocketTimeoutException) ProviderError.TIMEOUT else ProviderError.NETWORK_UNAVAILABLE

/** Compatible text subset: only finish_reason=stop proves successful completion. */
class CompatibleStreamDecoder {
    private var hasText = false

    fun accept(payload: String): List<StreamEvent> =
        try {
            if (payload == "[DONE]") {
                // `[DONE]` IS the documented terminator for Chat Completions, so treating it as a
                // protocol error made a compliant gateway unable to ever succeed: its answer would
                // be saved as ERROR with "Stream interrupted". Success still requires text — an
                // empty stream ending in [DONE] is a real failure, just not the same one.
                listOf(
                    if (hasText) {
                        StreamEvent.Completed
                    } else {
                        StreamEvent.Failure(ProviderError.PROTOCOL)
                    },
                )
            } else {
                val root = Json.parseToJsonElement(payload).jsonObject
                if (root["error"] != null) return listOf(StreamEvent.Failure(ProviderError.HTTP))
                val choices = root.getValue("choices").jsonArray
                if (choices.isEmpty()) {
                    emptyList()
                } else {
                    // Arbitrary BYOK endpoints may emit several choices; only index 0 is rendered.
                    choices.forEach { entry ->
                        val index = (entry as? JsonObject)?.get("index")
                        require(
                            index == null || index == JsonNull ||
                                (index is JsonPrimitive && !index.isString && index.intOrNull != null),
                        )
                    }
                    val choice =
                        choices
                            .map { it.jsonObject }
                            .firstOrNull { (it["index"] as? JsonPrimitive)?.intOrNull == 0 }
                            ?: choices.first().jsonObject
                    val delta = choice.getValue("delta").jsonObject
                    // JsonNull is not Kotlin null. A gateway that serialises absent optionals as
                    // explicit nulls (`"tool_calls": null`) is emitting legal JSON, but the old
                    // `== null` check threw on the very first chunk and turned the whole answer
                    // into "Stream interrupted". The next require already used the right form.
                    require(
                        // An empty array is legal JSON for "no tool call"; only a non-empty one is
                        // genuinely unrenderable here.
                        listOf(delta["tool_calls"], delta["function_call"]).all {
                            it == null || it == JsonNull || (it as? JsonArray)?.isEmpty() == true
                        },
                    )
                    require(
                        listOf(delta["content"], delta["refusal"], choice["finish_reason"]).all {
                            it == null || it == JsonNull || it.stringOrNull() != null
                        },
                    )
                    val text = delta["content"].stringOrNull() ?: delta["refusal"].stringOrNull()
                    val finish = choice["finish_reason"].stringOrNull()
                    if (!text.isNullOrEmpty()) hasText = true
                    buildList {
                        if (!text.isNullOrEmpty()) add(StreamEvent.TextDelta(text))
                        // `length` is the documented "hit the output cap" reason. That answer is
                        // finished, not interrupted — the text is exactly what the provider sent — so
                        // it completes instead of becoming an ERROR row. tool_calls/function_call are
                        // genuinely unrenderable here, and content_filter stopped on policy.
                        when (finish) {
                            null -> {
                                Unit
                            }

                            "stop", "length" -> {
                                add(
                                    if (hasText) StreamEvent.Completed else StreamEvent.Failure(ProviderError.PROTOCOL),
                                )
                            }

                            "content_filter" -> {
                                add(
                                    if (hasText) {
                                        StreamEvent.Completed
                                    } else {
                                        StreamEvent.Failure(ProviderError.PROMPT_BLOCKED)
                                    },
                                )
                            }

                            else -> {
                                add(StreamEvent.Failure(ProviderError.PROTOCOL))
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
            listOf(StreamEvent.Failure(ProviderError.PROTOCOL))
        }
}
