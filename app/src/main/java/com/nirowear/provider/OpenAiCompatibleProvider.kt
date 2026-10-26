package com.nirowear.provider

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
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
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

private val jsonMedia = "application/json; charset=utf-8".toMediaType()

// JSON escaping can use six wire bytes per UTF-16 unit; metadata keeps a fixed allowance.
private const val MAX_COMPATIBLE_SSE_BYTES = 6 * MAX_RESPONSE_CHARS + 256 * 1024

class OpenAiCompatibleProvider(
    private val client: OkHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .build(),
    private val allowLocalHttp: Boolean = false,
) : LlmProvider {
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
            val body = fetch(request)
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
    }

    @OptIn(DelicateCoroutinesApi::class)
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
        val httpRequest =
            Request
                .Builder()
                .url(Endpoints.compatible(profile.baseUrl, "chat/completions", allowLocalHttp))
                .header("Authorization", "Bearer ${secret.forRequest()}")
                .header("Accept", "text/event-stream")
                .post(payload.toString().toRequestBody(jsonMedia))
                .build()
        return callbackFlow {
            val call = client.newCall(httpRequest)
            var reader: Job? = null
            call.enqueue(
                object : Callback {
                    override fun onFailure(
                        call: Call,
                        e: IOException,
                    ) {
                        if (!call.isCanceled()) trySend(StreamEvent.Failure(networkError(e)))
                        close()
                    }

                    override fun onResponse(
                        call: Call,
                        response: Response,
                    ) {
                        if (!isActive) {
                            response.close()
                            return
                        }
                        // Enter try/finally before cancellation can skip coroutine body and leak response.
                        reader =
                            launch(Dispatchers.IO, start = CoroutineStart.ATOMIC) {
                                try {
                                    response.use {
                                        if (!it.isSuccessful) {
                                            send(StreamEvent.Failure(httpError(it.code)))
                                            return@launch
                                        }
                                        if (!it
                                                .header(
                                                    "Content-Type",
                                                ).orEmpty()
                                                .startsWith("text/event-stream", ignoreCase = true)
                                        ) {
                                            send(StreamEvent.Failure(ProviderError.PROTOCOL))
                                            return@launch
                                        }
                                        val source = it.body.source()
                                        val decoder = CompatibleStreamDecoder()
                                        val frame = StringBuilder()
                                        var visibleChars = 0

                                        suspend fun dispatch(): Boolean {
                                            if (frame.isEmpty()) return false
                                            val events = decoder.accept(frame.toString().removeSuffix("\n"))
                                            frame.clear()
                                            for (event in events) {
                                                if (event is StreamEvent.TextDelta) {
                                                    if (event.text.length > MAX_RESPONSE_CHARS - visibleChars) {
                                                        send(StreamEvent.Failure(ProviderError.RESPONSE_TOO_LARGE))
                                                        return true
                                                    }
                                                    visibleChars += event.text.length
                                                }
                                                send(event)
                                            }
                                            return events.any { event ->
                                                event is StreamEvent.Failure ||
                                                    event == StreamEvent.Completed
                                            }
                                        }
                                        while (isActive) {
                                            val line =
                                                source.readUtf8LineStrictOrNull(MAX_COMPATIBLE_SSE_BYTES.toLong())
                                                    ?: break
                                            if (line.isEmpty()) {
                                                if (dispatch()) return@launch
                                            } else if (line.startsWith("data:")) {
                                                frame.append(line.removePrefix("data:").removePrefix(" ")).append('\n')
                                                if (frame.length >
                                                    MAX_COMPATIBLE_SSE_BYTES
                                                ) {
                                                    throw SafeProviderException(ProviderError.RESPONSE_TOO_LARGE)
                                                }
                                            }
                                        }
                                        // EOF is never promoted. A complete final frame still requires semantic stop.
                                        if (!dispatch()) send(StreamEvent.Failure(ProviderError.PROTOCOL))
                                    }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (error: Exception) {
                                    if (isActive && !call.isCanceled()) {
                                        send(
                                            StreamEvent.Failure(
                                                if (error is SafeProviderException) {
                                                    error.error
                                                } else if (error is IOException) {
                                                    networkError(error)
                                                } else {
                                                    ProviderError.PROTOCOL
                                                },
                                            ),
                                        )
                                    }
                                } finally {
                                    response.close()
                                    close()
                                }
                            }
                    }
                },
            )
            awaitClose {
                call.cancel()
                reader?.cancel()
            }
        }
    }

    private suspend fun fetch(request: Request): String =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(
                        call: Call,
                        e: IOException,
                    ) {
                        if (continuation.isActive) {
                            continuation.resumeWith(
                                Result.failure(SafeProviderException(networkError(e))),
                            )
                        }
                    }

                    override fun onResponse(
                        call: Call,
                        response: Response,
                    ) {
                        val result =
                            runCatching {
                                response.use {
                                    if (!it.isSuccessful) throw SafeProviderException(httpError(it.code))
                                    val source = it.body.source()
                                    if (source.request(
                                            1024 * 1024 + 1L,
                                        )
                                    ) {
                                        throw SafeProviderException(ProviderError.PROTOCOL)
                                    }
                                    source.readUtf8()
                                }
                            }
                        if (continuation.isActive) continuation.resumeWith(result)
                    }
                },
            )
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
                        listOf(delta["tool_calls"], delta["function_call"]).all { it == null || it == JsonNull },
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
                        when (finish) {
                            null -> {
                                Unit
                            }

                            "stop" -> {
                                add(
                                    if (hasText) StreamEvent.Completed else StreamEvent.Failure(ProviderError.PROTOCOL),
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
