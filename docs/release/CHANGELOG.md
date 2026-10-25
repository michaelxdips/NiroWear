# Changelog

Notable changes to NiroWear. Versions follow the published APK releases at
[NiroWear-Beta](https://github.com/michaelxdips/NiroWear-Beta/releases).

## Unreleased — streaming re-parse coalesced (B3-03)

- **Streaming no longer re-parses the whole answer once per token.** The parse effect restarts on
  every emission, but a delta arrives far faster than a frame and the parse is non-cooperative once
  started, so each superseded run still parsed the entire accumulated prefix before discarding its
  result. A 12k-character answer reprocessed 4.27 million characters across 365 full re-parses. A
  one-frame throttle before the work lets the restart cancel the pending run first, collapsing a
  burst of deltas into a single parse.
- The throttle remembers when the last parse happened, so it is a throttle and not a debounce: a
  continuous stream still renders progressively instead of waiting for the provider to pause.
- Nothing is dropped: the final state is always parsed, and partial content still becomes visible
  within a frame of arriving. Completed conversations skip the wait entirely, because every row hits
  the memo.
- Scope, stated honestly: realistic answers already met the 250 ms budget before this change
  (realistic-long measured 230.1 ms). What was wrong is the wasted work — it was quadratic in delta
  count — and the synthetic stress cases (1-char deltas 781.8 ms, 64k answer 7620.7 ms) are where
  that quadratic cost was visible.
- Why not change the memo key: the same run reports a stable key parsing the identical 365 times, so
  the key was never the lever.
- Regression: `StreamingParseCoalescingTest` and `StreamingParseStarvationTest`, the former verified
  failing against the pre-fix loop and the latter pinning the debounce starvation at 0 parses.

## Unreleased — native math in release builds

- **LaTeX is typeset natively in release builds.** Previously the release variant rendered math as
  monospace source text and only debug typeset it. `jlatexmath-android` is now a runtime
  dependency; the release stub that returned `null` is gone.
- Licensing resolved: JLaTeXMath is GPL v2-or-later **with an independent-module linking
  exception**, which explicitly permits linking it from MIT code. The GPL text, the exception and
  a corresponding-source offer ship in `assets/legal/`.
- Safety behaviour unchanged: file-inclusion macros, macro definitions and unbalanced braces are
  still refused before the parser, and rendered output is bounds-checked.

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
