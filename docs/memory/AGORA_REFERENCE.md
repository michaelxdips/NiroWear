# Agora architectural reference

- URL: https://github.com/newo-ether/Agora
- Branch: `master` (HEAD)
- Exact commit: `53989d1bfe01a4d904b2fb83d2ebfeb04cd21a8d`
- Inspected: 2026-10-03; shallow clone outside NiroWear workspace.
- License: current GPL-3.0. Concepts only; no implementation copied.
- Provider truth: current official API docs outrank Agora.

## Evidence inspected

Read README.md and ARCHITECTURE.md fully. Symbol/behavior searches located these
files under `app/src/main/java/com/newoether/agora/`:

- `api/LlmProvider.kt`: StreamEvent, ProviderConfig and actual provider contract.
- `viewmodel/ProviderRegistry.kt`: built-in/custom factory, profile resolution,
  immutable generation snapshot, version-path discovery candidates.
- `api/openai/{BaseOpenAiProvider,OpenAiProvider,CustomOpenAiProvider}.kt`:
  Chat Completions and optional Responses transport, scoped stream teardown,
  retry gated by surfaced output, semantic markers and model fetching.
- `api/BaseUrlResolver.kt`: version path helper.
- `api/anthropic/AnthropicProvider.kt`: system top-level, x-api-key,
  anthropic-version 2023-06-01, message_stop, cursor-paginated models.
- `api/gemini/GeminiProvider.kt`: systemInstruction, x-goog-api-key,
  streamGenerateContent?alt=sse, finishReason; models filter generateContent.
  Inspected fetchModels reads one page, no page-token loop in this method.
- `api/util/StreamTermination.kt`: EOF is not semantic completion;
  no surfaced-output retry, output-cap failure; tool concerns out of scope.
- `api/HttpClient.kt`: credential header guard, call.cancel/close,
  request handle registered before execute for Stop during connect/upload.
- `api/ProviderHttpError.kt`: structured raw error normalization.
- `viewmodel/ProviderModelSyncController.kt`: registry discovery, persistence,
  sync guard and failure presentation.
- `data/repository/SettingsRepository.kt`, `data/SettingsManager.kt`,
  `data/SettingsModelPreferenceStore.kt`: DataStore profile/catalog/key settings,
  SecretCrypto envelope around saved key JSON (targeted symbols inspected).
- `util/SecretCrypto.kt` fully: AES-256-GCM Keystore, enc:v1 envelope,
  plaintext legacy acceptance and deliberate encryption-failure plaintext fallback.
- `data/local/ChatEntities.kt`: Room tree messages, raw text, status/model,
  Run relations, targeted stream checkpoints.
- `data/local/ChatDao.kt` title compare-and-set query located by expectedTitle.
- `viewmodel/GenerationManager.kt`: partial STOPPED/error, checkpoint settlement,
  non-cancellable durable finalization (targeted lifecycle section read).
- `viewmodel/ConversationRuntimeResources.kt`: streamScope.cancelAll + Job.cancel.

Architecture Markdown streaming two-buffer description is documentation evidence,
not yet separately audited renderer code; do not claim implementation verified.
No automatic title generation needed in NiroWear v0.1; local first-prompt title
avoids another model request.

## Adopt / adapt / reject

| Area | Agora behavior verified | NiroWear decision | Classification | Reason |
|---|---|---|---|---|
| Provider boundary | Native adapters normalize events | Independent small adapters | Adopt | UI/domain must not parse wire formats |
| Registry | Built-ins/custom protocols and snapshot | Four types, immutable selected profile per request | Adapt | No local/tool providers or name heuristics |
| Config | Broad runtime options incl tools | ID/type/name/base/model/credentialRef | Adapt | Watch needs no parameter dashboard |
| Networking | Device HTTP handles, call cancellation | Direct OkHttp tied to coroutine | Adopt | No mandatory backend; Stop ends network |
| Completion/retry | Semantic markers and output-aware retry | Per-adapter terminal validation; no initial auto-retry | Adapt | No duplicated visible output; no tools |
| URL discovery | Several endpoint candidates | Explicit API-base normalization, no generation probes | Adapt | Avoid guessed replay/double version path |
| Discovery | Persisted registry model sync | Dynamic list/manual fallback independent of validity | Adapt | Catalog failure need not block chat |
| Gemini catalog | generateContent filter, single inspected page | Metadata filter plus pagination | Adapt | Avoid unrelated models or lost pages |
| Anthropic catalog | Cursor-paginated /models | Native pagination after official verification | Adopt | Current supported catalog |
| OpenAI transport | Shared base, optional Responses flag | Separate native Responses and compatible adapters | Adapt | Current official recommendation outranks reference |
| Settings/history | DataStore settings, Room messages | Same separation, flat linear rows | Adapt | No tree/Run schema |
| Secrets | AES-GCM Keystore envelope; plaintext fallback | AES-GCM only; failure/re-entry | Adapt/reject fallback | Security must fail closed |
| Errors | Raw provider message parsing | Typed safe failures; no raw body display/log | Adapt | Provider may echo credentials |
| Generation | Reducer/mailbox/Run/pass/two settlement barriers | One Job; cancel/terminal write before idle | Adapt | Keep safety invariant, discard framework |
| Signatures | Provider-bound protocol metadata | Portable raw history only | Adopt invariant | No cross-provider leakage |
| Titles | CAS prevents stale generated rename | Local title, no API title request | Reject runtime | Unneeded network/complexity |
| Markdown | Docs describe offscreen/latest-wins buffers | Existing semantic parser off main thread | Adapt concept | Actual NiroWear reader already works |
| Branches/tools/Compact/MCP/tasks/loops/RAG/shell/local models/export | Present in inspected docs/schema/contracts | Not product features | Reject | Explicit NiroWear scope |

## Official truth sources inspected — 2026-10-03

- https://platform.openai.com/docs/guides/migrate-to-responses
  recommends Responses for new projects; `instructions`, `input`, `store:false`.
- https://developers.openai.com/api/docs/guides/streaming-responses
  native `response.output_text.delta`, `response.completed`, `error` SSE semantics.
- https://platform.openai.com/docs/api-reference/chat/create
  supported compatible reference `/v1/chat/completions`, messages/stream,
  finish_reason, usage/DONE tail. Generic endpoints may differ; controlled tests
  prove only implemented compatibility subset, not every proxy.
- Native Anthropic/Gemini implementation deferred until current official endpoint,
  headers, model pagination and terminal-event docs are inspected.
- 2026-10-04: https://platform.openai.com/docs/api-reference/responses/create
  inspected input/instruction definitions: portable user/assistant text allowed;
  newer models can emit phase metadata that should be retained provider-locally
  if used. Native adapter not implemented yet; do not claim protocol acceptance.
