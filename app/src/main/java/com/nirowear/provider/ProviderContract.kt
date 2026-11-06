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
    private val cause: String,
    /**
     * True when the cause is one that can leave text behind. "Partial answer kept." is a promise about
     * THIS turn, and it is false whenever no text arrived — a failed connection test, a dead socket
     * before the first token, a portal page. The property is cause-only on purpose (validation paths
     * have no answer at all); [userMessage] is the form that knows whether text survived.
     */
    private val keepsPartial: Boolean = false,
) {
    PROVIDER_REQUIRED("Add or select provider from menu."),
    PROVIDER_UNSUPPORTED("Provider adapter not available yet."),
    INVALID_CREDENTIAL("API key rejected. Check your key."),
    PERMISSION_DENIED("Provider permission denied."),
    RATE_LIMITED("Rate limited. Retry later."),
    MODEL_UNAVAILABLE("Model unavailable. Choose another model."),
    NETWORK_UNAVAILABLE("Cannot reach provider. Check connection."),
    TIMEOUT("Provider timed out.", keepsPartial = true),
    RESPONSE_TOO_LARGE("Response exceeded watch safety limit.", keepsPartial = true),
    PROTOCOL("Stream interrupted or unsupported.", keepsPartial = true),
    PROMPT_BLOCKED("Request blocked by the provider's safety filter. Rephrase it."),
    SERVER("Provider server error. Retry later."),
    HTTP("Provider rejected request. Check configuration."),
    CREDENTIAL_REENTRY("Secure credential unavailable. Re-enter API key."),
    ;

    /** Cause only. Use [userMessage] where an answer may exist. */
    val userMessage: String get() = cause

    /**
     * The same cause, with the "partial answer kept" promise added only when text actually survived
     * this turn. [hasText] must be measured, never assumed: an empty answer with that promise on
     * screen is the app telling the user something false.
     */
    fun userMessage(hasText: Boolean): String = if (keepsPartial && hasText) "$cause Partial answer kept." else cause
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
     * The provider is WORKING and has nothing to render yet: reasoning/thinking deltas, thought parts
     * and other internal frames the adapters deliberately do not display.
     *
     * The stream idle deadline advances only on a frame that decodes to an EVENT, so a keepalive
     * cannot hold the socket (see [ProviderHttp]); that rule is right, but it made a reasoning model
     * look dead. A turn that thinks for longer than the deadline — testable with an injectable bound —
     * decoded every frame to an empty list and was killed as "Provider timed out" with nothing on
     * screen. Liveness is the adapter's judgement, not the transport's, so it travels as an event.
     */
    data object Working : StreamEvent

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

/**
 * Shown under an answer that ended before it finished.
 *
 * Deliberately CAUSE-NEUTRAL. The first wording said "reached the model's output limit", which was
 * untrue for Gemini's CONTINUATION: that finishReason returns a `continuationToken` to resume the
 * generation, and this client never reads it, so the answer is genuinely incomplete but was NOT cut at
 * a length limit. A wrong cause is worse than a general one — the reader only needs to know the answer
 * is not the whole answer.
 */
const val TRUNCATION_NOTE = "Answer ended early and may be incomplete."

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

            // "Reply OK." cannot reach a length cap unless the cap is absurd, but a probe that
            // answered and was merely cut DID prove credentials and reachability. Leaving this in
            // `else` reported "Stream interrupted or unsupported" for a working provider.
            StreamEvent.Truncated -> error = null

            is StreamEvent.Failure -> error = it.error

            else -> Unit
        }
    }
    return error
}
