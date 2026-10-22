# SCOPE

## In scope for v0.1

2026-10-05 feature freeze: reader-position restoration and deterministic entry/
navigation/gesture/footer behavior are MVP quality, not feature expansion.
Saved-instance semantic anchors preferred; force-stop durable bookmarks optional,
not silently promised. ADR-025 documents current behavior. No post-v0.1 features.

Six parallel MVP gates: secure BYOK, dynamic provider/model switching, reliable
direct streaming, watch-native rendering, keyboard/voice, persistent linear chats.
Both renderer and BYOK must pass; neither excuses failure of the other.

## Android Studio / Wear acceptance — mandatory MVP gate (2026-10-04)

Validation/polish only; proven provider/security architecture stays intact. Existing
`NiroWear_466` round 466×466 touch AVD is primary target; preserve data. Debug and
release runtime, inspected screenshots, round-edge/font-scale/input/reader/network/
lifecycle/accessibility checks and measured release performance required. Canonical
matrix: TEST_MATRIX.md. Emulator, Android Studio GUI, release/debug, live APIs and
physical watch are distinct evidence classes. No new product features. Native release
math, real credentials and physical Xiaomi Watch 2 remain independent blockers.

Acceptance requires inspected round-edge captures; font-scale, short/long mixed
reader, full table cells, code/math pan endpoints, shaped scripts, touch-first
navigation, actual emulator IME/voice, network/recovery screens, release traces and
memory/startup sanity. Second round display is sanity only, reference stays 466×466.
NiroWear Small/Medium/Large settings tested only if implemented; no new font-settings
feature inferred from validation request. Matrix remains PARTIAL until evidence exists.

- text chat
- keyboard input
- voice input
- streaming AI output
- BYOK
- OpenAI
- Anthropic
- Gemini
- OpenAI-compatible providers
- model switching
- conversation history
- new chat
- rename/delete conversation
- edit previous user message
- retry assistant response
- stop generation
- copy message
- Markdown
- links
- code blocks
- Unicode/special symbols
- LaTeX/math
- matrices and cases
- tables adapted to narrow display
- readable long-form answers
- secure API-key storage

Provider setup: native masked API-key entry, validation, discovery and manual
model entry; compatible profiles additionally name and HTTPS base URL. Saved
credentials display Configured/Replace, never decrypted contents. Safe rotation,
profile deletion without deleting history, process-death persistence required.
No permanent model catalog, silent replacement, or capability guesses from names.

## Explicitly out of scope

- images
- camera
- image generation
- attachments
- PDF/file upload
- in-app browser
- web-search UI
- MCP as an end-user feature
- agent framework
- tool orchestration UI
- artifacts/canvas
- projects/workspaces
- conversation branching
- response variants
- local LLM
- token/cost UI
- memory-management UI
- prompt marketplace
- elaborate themes
- avatars
- phone companion app for v0.1

## Rule

If a desktop AI feature does not make practical sense on a smartwatch, remove it instead of reproducing it mechanically.
