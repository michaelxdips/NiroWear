package com.nirowear.provider

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

enum class ProviderType { OPENAI_COMPATIBLE, OPENAI, GEMINI, ANTHROPIC }

// Brand casing, not title-casing: "Openai"/"Gemini" from name.lowercase() read as typos.
val ProviderType.label: String
    get() =
        when (this) {
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

data class ChatMessage(
    val role: ChatRole,
    val rawContent: String,
)

data class ChatRequest(
    val messages: List<ChatMessage>,
    val systemInstruction: String = SYSTEM_INSTRUCTION,
    val openAiContinuation: OpenAiContinuation? = null,
)

data class OpenAiPhase(
    val length: Int,
    val phase: String? = null,
)

/** Auxiliary phase boundaries only. No opaque Items, reasoning, or canonical text. */
data class OpenAiContinuation(
    val profileId: String,
    val modelId: String,
    val baseUrl: String,
    val phases: Map<Int, List<OpenAiPhase>>,
)

const val SYSTEM_INSTRUCTION =
    // AGENTS.md, UX contract: "Never shorten an answer merely because the screen is small. Solve small
    // screens with layout, scrolling, typography and content transformation." The previous text asked
    // for "the shortest answer" BY DEFAULT *because* the reader is a watch, which is exactly the
    // forbidden reason — it capped answer length for the device rather than for the question. This
    // version asks for STRUCTURE instead of brevity: the reader renders headings, lists, tables, code
    // and display math as separate blocks on a 466 px round screen, so a long answer stays navigable
    // without being cut.
    "You are answering on a smartwatch. Length is set by the question, never by the screen: give the " +
        "full answer the question needs, and never drop required facts, caveats, steps, examples, code, " +
        "math or table rows to make it look shorter. Make a long answer readable with STRUCTURE: lead " +
        "with the answer, then use short paragraphs, headings, bullet or numbered lists, tables, fenced " +
        "code and display math instead of one wall of prose. Avoid repetition, unnecessary preamble, " +
        "and generic closing offers. " +
        "Do not destructively shorten requested code, math, tables, procedures, structured output, " +
        "or other tasks where completeness is the task. " +
        "Use Markdown and LaTeX when useful."

class SecretValue(
    private val value: String,
) {
    init {
        require(
            value.isNotBlank() && value.length <= 8192 && value.all { it.code in 33..126 },
        ) { "Invalid credential format" }
    }

    fun forRequest(): String = value

    override fun toString() = "[REDACTED]"
}

enum class ProviderError(
    val userMessage: String,
) {
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
    PROMPT_BLOCKED("Request blocked by the provider's safety filter. Rephrase it."),
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
        } else {
            merged += message
        }
    }
    return merged
}

sealed interface StreamEvent {
    data class TextDelta(
        val text: String,
    ) : StreamEvent

    data class Failure(
        val error: ProviderError,
    ) : StreamEvent

    data class OpenAiPhases(
        val phases: List<OpenAiPhase>,
    ) : StreamEvent

    data object Completed : StreamEvent

    /**
     * Terminal: the stream ended because the model hit its OUTPUT CAP, not because it finished.
     *
     * Every adapter used to report these as [Completed], so a cut answer was indistinguishable from a
     * complete one: it was stored as SUCCESS with no status line, and the reader believed it was whole.
     * AGENTS.md forbids shortening an answer, so the one shortening the app cannot prevent — a protocol
     * output limit — must at least be visible. Distinct from [Failure] because the text is valid and the
     * turn succeeded; only its ending is cut.
     */
    data object Truncated : StreamEvent
}

/** Shown under an answer that ended at the provider's output cap. */
const val TRUNCATION_NOTE = "Answer reached the model's output limit and may be cut short."

sealed interface ModelDiscoveryResult {
    data class Models(
        val ids: List<String>,
    ) : ModelDiscoveryResult

    data class Unavailable(
        val error: ProviderError,
    ) : ModelDiscoveryResult
}

interface LlmProvider {
    suspend fun validate(
        profile: ProviderProfile,
        secret: SecretValue,
    ): ProviderError?

    suspend fun listModels(
        profile: ProviderProfile,
        secret: SecretValue,
    ): ModelDiscoveryResult

    fun streamChat(
        request: ChatRequest,
        profile: ProviderProfile,
        secret: SecretValue,
    ): Flow<StreamEvent>
}

/**
 * The one implementation of validate(): a real generation proves the key AND the selected model,
 * which listing alone cannot. It is protocol-neutral — only the terminal event an adapter emits
 * decides success — so the four byte-identical copies in the adapters were four places to drift.
 */
suspend fun LlmProvider.validateWithProbe(
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
