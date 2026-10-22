# Changelog

Notable changes to NiroWear. Versions follow the published APK releases at
[NiroWear-Beta](https://github.com/michaelxdips/NiroWear-Beta/releases).

## Unreleased — source published under MIT

- Application source released publicly under the **MIT license**.
- **Credential transport:** new LAN pairing page. The watch serves a one-time URL; you paste the key
  into a normal form on a phone or laptop. The payload is encrypted with ephemeral ECDH P-256 and
  AES-GCM, single-use, 120 s TTL, 4 KiB cap, foreground-only. Manual entry remains available.
- **Precise validation errors:** `Validate · uses API` now reports the actual cause — a rejected key
  reads "API key rejected", a missing model reads "Model unavailable" — instead of one message
  blaming three fields at once.
- Key normalization at the input boundary strips non-printable characters, so a pasted trailing
  newline no longer becomes an invisible unsavable character.
- Provider-prefix hint warns when a key's shape contradicts the selected provider. Advisory only;
  never blocks a save, and silent for compatible endpoints.

## 0.1.0-beta.4 — 2026-10-21

- Launcher mark replaced with **Thread**.
- Menu and reader bezel geometry tightened (worst ink radius 230.7 → 225.6 and 231.3 → 217.0 px).
- `Add Openai` → `Add OpenAI` label fix.

## 0.1.0-beta.2 — 2026-10-11

- First publicly distributed beta.

## 0.1.0 — validation candidate, 2026-10-10

Initial feature-complete candidate: four provider adapters (OpenAI Responses, Anthropic Messages,
Gemini GenerateContent, OpenAI-compatible Chat Completions), secure BYOK, dynamic model selection,
streaming with Stop, linear Retry/Edit, conversation history, keyboard and system voice intent,
structured reader with Markdown, tables, code, Unicode and horizontal pan, and the `Latest` control.

Native math typesetting remains debug-only; release builds use the source-fallback renderer.
