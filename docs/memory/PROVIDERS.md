# Provider contracts

How NiroWear talks to AI providers. This is the engineering contract every adapter must satisfy; it is not a changelog.

## Source of truth

Current official API docs outrank SDK/reference docs, inspected reference implementations, this source, and model memory — in that order. Record the inspected URLs and date before changing an adapter.

Never infer a wire protocol from a model name. OpenAI-native Responses and generic OpenAI-compatible Chat Completions are **different adapters with different completion semantics**, even though both are "OpenAI-ish".

## The adapter interface

```kotlin
interface LlmProvider {
    suspend fun validate(profile: ProviderProfile, secret: SecretValue): ProviderError?
    suspend fun listModels(profile: ProviderProfile, secret: SecretValue): ModelDiscoveryResult
    fun streamChat(request: ChatRequest, profile: ProviderProfile, secret: SecretValue): Flow<StreamEvent>
}
```

- `ProviderProfile` carries ID, type, display name, base URL, selected model ID and `credentialRef` — **never the key**.
- History is provider-neutral USER/ASSISTANT raw content plus one domain-level system instruction. Each adapter maps that to its own wire fields.
- Events are `TextDelta`, `Failure`, `Completed`. Add fields only when a feature needs them.
- Errors are a closed set (`INVALID_CREDENTIAL`, `PERMISSION_DENIED`, `RATE_LIMITED`, `MODEL_UNAVAILABLE`, `NETWORK_UNAVAILABLE`, `TIMEOUT`, `PROTOCOL`, `SERVER`, `HTTP`, …). Each carries user-safe text; no raw provider payload reaches the UI or the log.

## Completion is protocol-specific

Each adapter proves success from its own terminal event:

| Adapter | Wire protocol | Terminal event |
|---|---|---|
| OpenAI | Responses | `response.completed` |
| Anthropic | Messages | `message_stop` |
| Gemini | `generateContent` (SSE) | candidate with `finishReason` |
| OpenAI-compatible | Chat Completions | `finish_reason` |

**EOF is not success.** A stream that merely stops is an interruption and must be surfaced as such. `[DONE]` alone is not proof of a completed answer in the compatible subset.

## Stop and retry

`Stop` cancels the underlying OkHttp call, keeps whatever text already arrived, and persists it as stopped content. The next send waits for that settlement. Visible output is **never** replayed automatically — a retry is always an explicit user action that replaces the assistant continuation.

## OpenAI-compatible configuration

Compatible endpoints legitimately vary, so the adapter accepts:

- API key
- base URL
- manual model ID
- discovered models, when the endpoint advertises them

Rules:

- Do not assume a fixed model catalog.
- Discovery failure does **not** invalidate a generation configuration. Manual entry must permit chat without `/models`.
- Structural validity, reachability, discovery and selected-model usability are four separate states.
- No silent fallback model, ever.
- Use capability metadata, not model-name heuristics.
- Central URL construction handles slash/version variants; preserve an explicit custom path. Never probe or replay generation across guessed paths.
- Release requires HTTPS with platform trust. Debug cleartext is limited to local test endpoints.

## Mid-conversation switching

Changing provider or model inside one conversation is allowed. Every assistant message records the profile ID and model ID that produced it, so history stays honest after a switch. The canonical history remains one linear provider-neutral sequence — switching never forks it.

## Credentials in adapters

Adapters receive a `SecretValue` and must only use it to build an auth header or field. They must never:

- log it, or log a request/response body containing it;
- put it in a URL or query string;
- include it in an error message;
- retain it beyond the request.

## Testing an adapter

Controlled tests use a local mock server and assert on the bytes actually sent — method, path, headers, body shape — plus the events produced for a given SSE script. Mock-server coverage is protocol proof; it is not proof that the real provider behaves identically. Say which one you have.
