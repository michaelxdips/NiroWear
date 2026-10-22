package com.nirowear.provider

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class GeminiProvider(client: OkHttpClient = providerHttpClient(), private val allowLocalHttp: Boolean = false) : LlmProvider {
    private val http = ProviderHttp(client)

    override suspend fun validate(profile: ProviderProfile, secret: SecretValue): ProviderError? {
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

    override suspend fun listModels(profile: ProviderProfile, secret: SecretValue): ModelDiscoveryResult = try {
        val ids = mutableSetOf<String>()
        val tokens = mutableSetOf<String>()
        var token: String? = null
        do {
            val url = Endpoints.compatible(profile.baseUrl, "models", allowLocalHttp).newBuilder()
                .addQueryParameter("pageSize", "1000").apply { token?.let { addQueryParameter("pageToken", it) } }.build()
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
            require(token == null || (token.isNotBlank() && token.length <= 4096 && tokens.size < 100 && tokens.add(token)))
        } while (token != null)
        if (ids.isEmpty()) ModelDiscoveryResult.Unavailable(ProviderError.PROTOCOL) else ModelDiscoveryResult.Models(ids.sorted())
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (error: SafeProviderException) { ModelDiscoveryResult.Unavailable(error.error) }
    catch (_: Exception) { ModelDiscoveryResult.Unavailable(ProviderError.PROTOCOL) }

    override fun streamChat(request: ChatRequest, profile: ProviderProfile, secret: SecretValue): Flow<StreamEvent> {
        val model = profile.selectedModelId.removePrefix("models/")
        require(model.isNotBlank() && model.length <= 256 && '/' !in model) { "Invalid model ID" }
        fun content(text: String, role: String? = null) = buildJsonObject {
            role?.let { put("role", it) }
            put("parts", buildJsonArray { add(buildJsonObject { put("text", text) }) })
        }
        val payload = buildJsonObject {
            put("store", false)
            if (request.systemInstruction.isNotBlank()) put("systemInstruction", content(request.systemInstruction))
            put("contents", buildJsonArray {
                // GenerateContent rejects non-alternating roles: "ensure that multiturn requests alternate".
                request.messages.withAlternatingRoles().forEach { add(content(it.rawContent, if (it.role == ChatRole.USER) "user" else "model")) }
            })
        }
        val url = Endpoints.compatible(profile.baseUrl, "models/$model:streamGenerateContent", allowLocalHttp)
            .newBuilder().addQueryParameter("alt", "sse").build()
        val wire = authenticated(url, secret).header("Accept", "text/event-stream")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        return flow { emitAll(http.stream(wire, GeminiStreamDecoder()::accept)) }
    }

    private fun authenticated(url: HttpUrl, secret: SecretValue) = Request.Builder().url(url)
        .header("x-goog-api-key", secret.forRequest())
}

internal class GeminiStreamDecoder {
    private var hasText = false
    fun accept(payload: String): List<StreamEvent> = try {
        val root = Json.parseToJsonElement(payload).jsonObject
        root["error"]?.let {
            val code = it.jsonObject["code"]?.jsonPrimitive?.intOrNull
            return listOf(StreamEvent.Failure(if (code != null) httpError(code) else ProviderError.HTTP))
        }
        if (root["promptFeedback"]?.jsonObject?.get("blockReason") != null) return listOf(StreamEvent.Failure(ProviderError.HTTP))
        val candidates = root["candidates"]?.jsonArray ?: return emptyList()
        if (candidates.isEmpty()) return emptyList()
        require(candidates.size == 1)
        val candidate = candidates.single().jsonObject
        require(candidate["index"]?.jsonPrimitive?.intOrNull in listOf(null, 0))
        val text = buildString {
            candidate["content"]?.jsonObject?.let { content ->
                require(content["role"]?.jsonPrimitive?.contentOrNull in listOf(null, "model"))
                content["parts"]?.jsonArray?.forEach { element ->
                    val part = element.jsonObject
                    require(part.keys.all { it in setOf("text", "thought", "thoughtSignature") })
                    if (part["thought"]?.jsonPrimitive?.booleanOrNull != true) {
                        val value = part["text"]?.jsonPrimitive
                        if (value != null) { require(value.isString); append(value.content) }
                    }
                }
            }
        }
        if (text.isNotEmpty()) hasText = true
        buildList {
            if (text.isNotEmpty()) add(StreamEvent.TextDelta(text))
            when (candidate["finishReason"]?.jsonPrimitive?.contentOrNull) {
                null, "FINISH_REASON_UNSPECIFIED" -> Unit
                "STOP" -> add(if (hasText) StreamEvent.Completed else StreamEvent.Failure(ProviderError.PROTOCOL))
                else -> add(StreamEvent.Failure(ProviderError.PROTOCOL))
            }
        }
    } catch (_: Exception) { listOf(StreamEvent.Failure(ProviderError.PROTOCOL)) }
}
