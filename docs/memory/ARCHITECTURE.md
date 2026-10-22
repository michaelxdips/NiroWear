# ARCHITECTURE

## High-level

Current foundation: single Android `app` module, `com.nirowear`, minSdk 30,
compile/targetSdk 36. Wear-only standalone activity using Wear Compose Material 3.
Current implementation: ChatViewModel coordinates generation and Room directly;
NiroApplication owns provider/settings/secret/database instances. No additional
repository/registry/generation framework until concrete complexity requires it.

## MVP foundation and security

Provider-neutral USER/ASSISTANT messages store raw content; assistant provenance
is profile ID plus model ID. Domain owns one system instruction. Adapters map it
to native fields and reconstruct portable text history, without cross-provider
opaque metadata. Minimal adapters: validate, listModels, streamChat. OpenAI-native
Responses is separate from compatible Chat Completions; Gemini/Anthropic native.
Room v2 auxiliary `openai_phases` preserves original assistant phase/visible-item
lengths only for matching native OpenAI profile/model/endpoint. Raw history unchanged;
no opaque reasoning persisted. Successful checkpoint and sidecar commit atomically;
message deletion cascades. See ADR-021 and PROVIDERS.md for continuation classes.

Room owns linear conversations/messages and transactional edit/truncate. DataStore
owns profiles, selected profile/model and catalog settings. `credentialRef` resolves
through SecretStore: versioned ciphertext + nonce encrypted with Android Keystore
AES-GCM. No plaintext persistence/fallback. Corruption/invalidation requires re-entry.
Replacement validates new credential before atomic encrypted replacement; failures
leave old credential untouched. Profile deletion removes configuration/selection
and encrypted payload, not historical messages. No backend, no Run/pass graphs.

Backup policy: manifest disables Android backup; extraction rules exclude current
credential-protected storage domains. Device-protected `device_*` domains are not
listed and currently unused; OEM device transfer is not universally guaranteed blocked.
Credentials use `noBackupFilesDir`. Reinstalled profiles are not promised preserved. If an
encrypted payload is restored by unsupported means without device-bound Keystore
key, fail closed and ask for key re-entry; never claim it remains connected.

One generation Job owns one request. Adapters cancel OkHttp work on coroutine
cancellation, detect protocol-specific terminal events and normalize safe failures.
Partial text remains durable after Stop/error/process recovery. No automatic
post-output replay; explicit Retry replaces linear assistant continuation.
Model discovery is optional for compatible endpoints, not authentication proof.

```text
Wear Compose UI
      |
      v
ChatViewModel
      |
      +------------------+
      |                  |
      v                  v
     Room       NiroApplication.provider(type)
                         |
                 LlmProvider adapter
                         |
                OkHttp direct stream
```

## Response pipeline

```text
provider stream
    -> normalized StreamEvent
    -> raw Markdown buffer
    -> incremental parser
    -> NiroDocument / ContentBlock[]
    -> Wear Compose renderer
```

## Important boundaries

Implemented adapters: compatible Chat Completions, native OpenAI Responses,
Gemini GenerateContent, Anthropic Messages. NiroApplication routes types directly;
native adapters share ProviderHttp HTTP/SSE framing but retain independent parsers.
Compatible transport remains unchanged. No additional registry abstraction required.
Profiles/catalog settings and secure-save settlement use DataStore; Room stores
messages only. Each text delta checkpoints raw assistant text; terminal persistence
runs NonCancellable before generation returns idle. Edit/retry reserve same generation
admission before destructive history updates, after credential retrieval succeeds.

ProviderSettings stages fresh encrypted candidates and validates replacements before
atomic metadata promotion. Rejected/cancelled candidates removed; reference-aware
startup/post-save/post-delete cleanup removes orphan .new/.bak/base blobs without
deleting referenced credentials. Settings mutex serializes cleanup with promotion.

Provider-specific wire formats must not leak into Compose UI.

Renderer must not depend directly on one math library. Use a `MathRenderer` abstraction.

Conversation persistence stores raw Markdown, not only rendered output.

## Suggested package groups

- chat/
- renderer/
- voice/
- provider/
- data/
- security/
- history/
- models/
- settings/
