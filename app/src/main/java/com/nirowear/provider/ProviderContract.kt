package com.nirowear.provider

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

enum class ProviderType { OPENAI_COMPATIBLE, OPENAI, GEMINI, ANTHROPIC }

// Brand casing, not title-casing: "Openai"/"Gemini" from name.lowercase() read as typos.
val ProviderType.label: String
    get() = when (this) {
        ProviderType.OPENAI_COMPATIBLE -> "compatible"
        ProviderType.OPENAI -> "OpenAI"
        ProviderType.GEMINI -> "Gemini"
        ProviderType.ANTHROPIC -> "Anthropic"
    }
enum class ChatRole { USER, ASSISTANT }

data class ProviderProfile(
    val id: String,
    val type: ProviderType,
    val displayName: String,
    val baseUrl: String,
    val selectedModelId: String,
    val credentialRef: String,
)

data class ChatMessage(val role: ChatRole, val rawContent: String)
data class ChatRequest(val messages: List<ChatMessage>, val systemInstruction: String = SYSTEM_INSTRUCTION,
    val openAiContinuation: OpenAiContinuation? = null)

data class OpenAiPhase(val length: Int, val phase: String? = null)

/** Auxiliary phase boundaries only. No opaque Items, reasoning, or canonical text. */
data class OpenAiContinuation(val profileId: String, val modelId: String, val baseUrl: String,
    val phases: Map<Int, List<OpenAiPhase>>)

const val SYSTEM_INSTRUCTION = "You are answering on a smartwatch. By default, give the shortest answer that preserves " +
    "the core answer, required facts, important caveats, and next action. Lead with the answer. " +
    "Prefer a short paragraph or at most three compact bullets. Avoid repetition, unnecessary preamble, " +
    "and generic closing offers. " +
    "If the user explicitly asks for more detail, explanation, reasoning, steps, examples, comparison, " +
    "edge cases, a tutorial, or a comprehensive answer, expand fully for that turn. " +
    "If the user repeats or materially rephrases the same question after receiving a concise answer, " +
    "treat that as a likely request for deeper explanation unless the user explicitly asks to remain brief. " +
    "Never omit safety-critical, task-critical, uncertainty, constraint, or tradeoff information merely to be shorter. " +
    "Do not destructively shorten requested code, math, tables, procedures, structured output, " +
    "or other tasks where completeness is the task. " +
    "Use Markdown and LaTeX when useful."

class SecretValue(private val value: String) {
    init { require(value.isNotBlank() && value.length <= 8192 && value.all { it.code in 33..126 }) { "Invalid credential format" } }
    fun forRequest(): String = value
    override fun toString() = "[REDACTED]"
}

enum class ProviderError(val userMessage: String) {
    PROVIDER_REQUIRED("Add or select provider from menu."),
    PROVIDER_UNSUPPORTED("Provider adapter not available yet."),
    INVALID_CREDENTIAL("API key rejected. Check your key."),
    PERMISSION_DENIED("Provider permission denied."),
    RATE_LIMITED("Rate limited. Retry later."),
    MODEL_UNAVAILABLE("Model unavailable. Choose another model."),
    NETWORK_UNAVAILABLE("Cannot reach provider. Check connection."),
    TIMEOUT("Provider timed out. Partial answer kept."),
    RESPONSE_TOO_LARGE("Response exceeded watch safety limit. Partial answer kept."),
    PROTOCOL("Stream interrupted or unsupported. Partial answer kept."),
    SERVER("Provider server error. Retry later."),
    HTTP("Provider rejected request. Check configuration."),
    CREDENTIAL_REENTRY("Secure credential unavailable. Re-enter API key."),
}

// ponytail: 1M UTF-16 units per answer; raise only after watch heap/renderer acceptance.
internal const val MAX_RESPONSE_CHARS = 1_048_576

/**
 * Null-safe, type-safe read of a JSON string field. `JsonPrimitive.content` returns the literal
 * string "null" for JSON null, and `.jsonPrimitive` throws for objects/arrays, so untrusted
 * provider payloads must go through this instead.
 */
internal fun JsonElement?.stringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

/**
 * Native Messages/GenerateContent require alternating roles; first-party combines consecutive
 * same-role turns and Bedrock/Vertex reject them. Canonical history stays provider-neutral, so the
 * adapters that need alternation merge adjacent turns at the wire boundary.
 */
internal fun List<ChatMessage>.withAlternatingRoles(): List<ChatMessage> {
    val merged = mutableListOf<ChatMessage>()
    forEach { message ->
        val previous = merged.lastOrNull()
        if (previous != null && previous.role == message.role) {
            merged[merged.lastIndex] = previous.copy(rawContent = previous.rawContent + "\n" + message.rawContent)
        } else merged += message
    }
    return merged
}

sealed interface StreamEvent {
    data class TextDelta(val text: String) : StreamEvent
    data class Failure(val error: ProviderError) : StreamEvent
    data class OpenAiPhases(val phases: List<OpenAiPhase>) : StreamEvent
    data object Completed : StreamEvent
}

sealed interface ModelDiscoveryResult {
    data class Models(val ids: List<String>) : ModelDiscoveryResult
    data class Unavailable(val error: ProviderError) : ModelDiscoveryResult
}

interface LlmProvider {
    suspend fun validate(profile: ProviderProfile, secret: SecretValue): ProviderError?
    suspend fun listModels(profile: ProviderProfile, secret: SecretValue): ModelDiscoveryResult
    fun streamChat(request: ChatRequest, profile: ProviderProfile, secret: SecretValue): Flow<StreamEvent>
}
