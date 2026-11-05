package com.nirowear.provider

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
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

class GeminiProvider(
    client: OkHttpClient = providerHttpClient(),
    private val allowLocalHttp: Boolean = false,
) : LlmProvider {
    private val http = ProviderHttp(client)

    override suspend fun validate(
        profile: ProviderProfile,
        secret: SecretValue,
    ): ProviderError? = validateWithProbe(profile, secret)

    override suspend fun listModels(
        profile: ProviderProfile,
        secret: SecretValue,
    ): ModelDiscoveryResult =
        try {
            val ids = mutableSetOf<String>()
            val tokens = mutableSetOf<String>()
            var token: String? = null
            do {
                val url =
                    Endpoints
                        .compatible(profile.baseUrl, "models", allowLocalHttp)
                        .newBuilder()
                        .addQueryParameter(
                            "pageSize",
                            "1000",
                        ).apply { token?.let { addQueryParameter("pageToken", it) } }
                        .build()
                val root = Json.parseToJsonElement(http.fetch(authenticated(url, secret).build())).jsonObject
                // One malformed entry must not discard an otherwise valid list.
                root["models"]?.jsonArray?.forEach { element ->
                    val model = element as? JsonObject ?: return@forEach
                    val methods = model["supportedGenerationMethods"] as? JsonArray ?: return@forEach
                    if (methods.any { it.stringOrNull() == null }) return@forEach
                    if (methods.any { it.stringOrNull() == "generateContent" }) {
                        val id = model["name"].stringOrNull()?.removePrefix("models/")
                        if (id != null && id.isNotBlank() && id.length <= 256 && '/' !in id) ids += id
                        // ponytail: 10k discovered IDs; raise after watch picker/heap acceptance.
                        require(ids.size <= 10_000)
                    }
                }
                val next = root["nextPageToken"]
                require(next == null || next == JsonNull || next.stringOrNull() != null)
                token = next.stringOrNull()?.takeIf { it.isNotEmpty() }
                // ponytail: 4096 cursor chars; raise only for documented larger provider tokens.
                require(
                    token == null ||
                        (token.isNotBlank() && token.length <= 4096 && tokens.size < 100 && tokens.add(token)),
                )
            } while (token != null)
            if (ids.isEmpty()) {
                ModelDiscoveryResult.Unavailable(
                    ProviderError.PROTOCOL,
                )
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
    ): Flow<StreamEvent> {
        val model = profile.selectedModelId.removePrefix("models/")
        require(model.isNotBlank() && model.length <= 256 && '/' !in model) { "Invalid model ID" }

        fun content(
            text: String,
            role: String? = null,
        ) = buildJsonObject {
            role?.let { put("role", it) }
            put("parts", buildJsonArray { add(buildJsonObject { put("text", text) }) })
        }
        val payload =
            buildJsonObject {
                // GenerateContentRequest.store is a real boolean: "Configures the logging behavior for
                // a given request. If set, it takes precedence over the project-level logging config."
                // It is absent from the published proto mirror and from ai.google.dev, which is how a
                // previous audit wrongly concluded the field was illegal — it is in the deployed
                // discovery schema, and an unauthenticated probe confirmed only genuinely unknown
                // fields are rejected ("Unknown name ..."), while store parsed and reached auth.
                // Chat text must not be retained upstream, so the opt-out is sent explicitly.
                put("store", false)
                if (request.systemInstruction.isNotBlank()) put("systemInstruction", content(request.systemInstruction))
                put(
                    "contents",
                    buildJsonArray {
                        // GenerateContent rejects non-alternating roles: "ensure that multiturn requests alternate".
                        request.messages.withAlternatingRoles().forEach {
                            add(
                                content(
                                    it.rawContent,
                                    if (it.role ==
                                        ChatRole.USER
                                    ) {
                                        "user"
                                    } else {
                                        "model"
                                    },
                                ),
                            )
                        }
                    },
                )
            }
        val url =
            Endpoints
                .compatible(profile.baseUrl, "models/$model:streamGenerateContent", allowLocalHttp)
                .newBuilder()
                .addQueryParameter("alt", "sse")
                .build()
        val wire =
            authenticated(url, secret)
                .header("Accept", "text/event-stream")
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
        return flow { emitAll(http.stream(wire, GeminiStreamDecoder()::accept)) }
    }

    private fun authenticated(
        url: HttpUrl,
        secret: SecretValue,
    ) = Request
        .Builder()
        .url(url)
        .header("x-goog-api-key", secret.forRequest())
}

internal class GeminiStreamDecoder {
    private var hasText = false

    fun accept(payload: String): List<StreamEvent> =
        try {
            val root = Json.parseToJsonElement(payload).jsonObject
            root["error"]?.let {
                val body = it.jsonObject
                val code = body["code"]?.jsonPrimitive?.intOrNull
                // Gemini answers a rejected key with HTTP 400 and INVALID_ARGUMENT, and a missing model
                // with 404. The status code alone therefore cannot separate "wrong key" from "bad
                // request", so the reason string is checked first — it is the only place
                // API_KEY_INVALID appears.
                val status = body["status"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val reasons =
                    body["details"]?.jsonArray.orEmpty().mapNotNull {
                        it.jsonObject["reason"]?.jsonPrimitive?.contentOrNull
                    }
                val mapped =
                    when {
                        status == "UNAUTHENTICATED" || "API_KEY_INVALID" in reasons ||
                            "API_KEY_EXPIRED" in reasons || "API_KEY_SERVICE_BLOCKED" in reasons -> {
                            ProviderError.INVALID_CREDENTIAL
                        }

                        status == "PERMISSION_DENIED" -> {
                            ProviderError.PERMISSION_DENIED
                        }

                        code == 400 && status == "INVALID_ARGUMENT" -> {
                            ProviderError.HTTP
                        }

                        code != null -> {
                            httpError(code)
                        }

                        else -> {
                            ProviderError.HTTP
                        }
                    }
                return listOf(StreamEvent.Failure(mapped))
            }
            // `promptFeedback.blockReason` means the PROMPT was filtered, not that the request was
            // malformed. `!= null` also matched JSON null, which is a legal absent value.
            if (root["promptFeedback"]?.jsonObject?.get("blockReason")?.stringOrNull() != null) {
                return listOf(StreamEvent.Failure(ProviderError.PROMPT_BLOCKED))
            }
            val candidates = root["candidates"]?.jsonArray ?: return emptyList()
            if (candidates.isEmpty()) return emptyList()
            val candidate = candidates.first().jsonObject
            val text =
                buildString {
                    candidate["content"]?.jsonObject?.let { content ->
                        // role may legitimately be absent on a single-turn reply.
                        require(content["role"]?.jsonPrimitive?.contentOrNull in listOf(null, "model"))
                        content["parts"]?.jsonArray?.forEach { element ->
                            val part = element.jsonObject
                            // No key whitelist: Part has many documented members (inlineData,
                            // fileData, functionCall, executableCode, ...). Requiring an exact set
                            // turned one unrecognised-but-legal field into a failed answer. Only
                            // text contributes canonical content; thought parts are not rendered.
                            if (part["thought"]?.jsonPrimitive?.booleanOrNull != true) {
                                part["text"].stringOrNull()?.let { append(it) }
                            }
                        }
                    }
                }
            if (text.isNotEmpty()) hasText = true
            buildList {
                if (text.isNotEmpty()) add(StreamEvent.TextDelta(text))
                when (candidate["finishReason"]?.jsonPrimitive?.contentOrNull) {
                    null, "FINISH_REASON_UNSPECIFIED" -> {
                        Unit
                    }

                    // Truncation and content filters are terminal states, not framing faults. The
                    // answer that arrived is kept instead of being relabelled "Stream interrupted" — the
                    // text is what the provider actually sent.
                    //
                    // MAX_TOKENS and CONTINUATION are reported as Truncated: the text stays SUCCESS and
                    // gains the visible "reached the output limit" note. Reporting them as Completed
                    // made a cut answer indistinguishable from a finished one.
                    "STOP" -> {
                        add(if (hasText) StreamEvent.Completed else StreamEvent.Failure(ProviderError.PROTOCOL))
                    }

                    "MAX_TOKENS", "CONTINUATION" -> {
                        add(if (hasText) StreamEvent.Truncated else StreamEvent.Failure(ProviderError.PROTOCOL))
                    }

                    "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII",
                    "IMAGE_SAFETY", "IMAGE_PROHIBITED_CONTENT", "IMAGE_RECITATION",
                    -> {
                        // Text already on screen here means the reply was CUT by policy, not that it
                        // finished. Reporting Completed hid the cut behind a plausible-looking
                        // ending; Truncated keeps the text and adds the visible note.
                        add(
                            if (hasText) StreamEvent.Truncated else StreamEvent.Failure(ProviderError.PROMPT_BLOCKED),
                        )
                    }

                    // Reasons that ABORT a generation part-way. These were falling into the generic
                    // branch and reported as Completed, so an answer that Gemini itself called
                    // malformed, escalated or policy-limited was stored SUCCESS with no note — the same
                    // undisclosed-cut class the Truncated event was added to remove. The text that
                    // arrived is still kept (Failure preserves partial content).
                    "MALFORMED_FUNCTION_CALL", "MALFORMED_RESPONSE", "MISSING_THOUGHT_SIGNATURE",
                    "UNEXPECTED_TOOL_CALL", "TOO_MANY_TOOL_CALLS", "ESCALATION",
                    "PUP_LIMITED_DISABLED",
                    -> {
                        add(StreamEvent.Failure(ProviderError.PROTOCOL))
                    }

                    // A reason this build does not know (LANGUAGE, OTHER, IMAGE_OTHER, NO_IMAGE, and
                    // whatever the next API version adds) is a terminal state of unknown cause. With
                    // text it is a cut of unknown cause — say so instead of guessing "finished".
                    else -> {
                        add(if (hasText) StreamEvent.Truncated else StreamEvent.Failure(ProviderError.PROTOCOL))
                    }
                }
            }
        } catch (_: Exception) {
            listOf(StreamEvent.Failure(ProviderError.PROTOCOL))
        }
}
