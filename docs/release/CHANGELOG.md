# Changelog

Notable changes to NiroWear. Versions follow the published APK releases at
[NiroWear releases](https://github.com/michaelxdips/NiroWear/releases).

## 0.1.0 — stable cut, 2026-11-03

Stable cut from green tree `1b389a4` (versionCode 10). Signed with the beta
lineage identity (cert SHA-256
`7D:4E:6E:99:82:1D:90:4F:23:90:0B:D4:E0:CA:D5:B6:4B:1A:27:33:A2:82:43:DC:C8:AB:9D:6E:27:68:AB:E3`).

Measured on the stable tree: JVM suite green, release assemble clean, connected
suite 48/48 green on emulator (same tree as beta.8 verdict, versionCode bump
only).

Honest remaining gates (disclosed, not blocking): live provider smoke skipped
per owner order (no owner-typed key on emulator; route to `kenari.id` proven
reachable via TLS at smoke time); owner BETA7 sheet hardware steps pending;
TalkBack pass open; native-math licence review open (JLaTeXMath exception
ships in `assets/legal/`).

## 0.1.0-beta.8 — 2026-11-02

- **Renderer keeps content the parser silently dropped.** Display math inside a quote or list
  now emits a Math block with delimiters preserved; container stripping skips fenced lines;
  flattening preserves document order and keeps code/math nodes; over-wide rows get an
  explicit "… N more" marker; mid-line `$$` is literal unless line-start or post-whitespace.
- **Security hardening.** Keystore-entry deletion on rotation failure (fail-closed, recreate
  cleanly); pairing screen states the real threat model (same-Wi-Fi, ECDH+AES-GCM payload,
  unauthenticated handshake, manual entry avoids the network).
- **Bounded reads and memory.** Pairing body read rejects short streams and enforces the
  deadline inside the loop; per-conversation parse memo is LRU-capped at 3 conversations.
- **Site demo is a watch.** `docs/index.html` wraps the replay in a 466×466 round frame with
  STOPPED copy, Retry, model switch, and a measured follow/scroll readout — zero console
  errors in Edge.
- Measured: JVM 176/176, ktlint 0 errors, connected suite 48/48 on emulator
  (XML ts=2026-11-02T06:58:50, per Task 2 report; not re-run after Tasks 3-5).

## Unreleased — pairing hardening, memo retention, a11y/i18n passes

- **A slow TLS client can no longer take the pairing session.** Over TLS the first `read()` is where
  JSSE runs the handshake, so the request wall clock could not run inside it and `soTimeout` bounds
  one read rather than a handshake: a peer dribbling a partial ClientHello held the single worker and
  the phone's real request was never accepted (page never loads, nothing said on the watch). Each
  request now runs on its own thread joined with a hard bound; pinned by
  `aDribblingClientCannotHoldTheSession`, verified red without the bound.
- **Certificate validity is fixed, not clock-derived.** A watch can boot with no real-time clock;
  `now - 7 days` landed in 1969, which UTCTime reads back as 2069 — an inverted window and
  `ERR_CERT_DATE_INVALID` on the phone. The window is now 2020..2049, inside UTCTime's range, and the
  certificate asserts nothing by date (it is self-signed and unknown to every client).
- **`close()` no longer self-joins** when the worker ends the session itself (five wasted seconds of a
  daemon thread), and the dead no-op tamper in `PairingServerTest` is gone.
- **The parse memo actually retains conversations.** The reader effect stored `rows` — the open
  conversation's messages — so the retention bound could never keep another one and History A -> B -> A
  re-parsed all of A. It merges now (`mergeParsed`), pinned by test and mutation-verified.
- **Deleting a provider no longer destroys a selection this build cannot decode.** The comparison used
  the decoded list only, so an id present solely in the stored document was treated as gone and
  overwritten with the first survivor.
- **Accessibility/localization:** the live status row ("Streaming…" / "Stopped · partial answer kept")
  now comes from resources instead of hardcoded English, and carries a polite live region so a screen
  reader learns when streaming starts or stops; link buttons no longer announce their URL twice or
  register a duplicate action; the "Ask anything" empty state waits for the conversation's parse so
  opening a long chat no longer flashes it.
- Honest scope: Compose behaviour in this list (live region, empty-state gating, link semantics) has no
  unit test in this repo and was not exercised on a device — only compiled and lint-checked.
- Measured: JVM 227/227, 38 suites, ktlint 0, Android Lint 1 (GradleDependency), release APK
  3,590,509 B.

## Unreleased — LAN pairing served over TLS

- **Pairing works in a browser again.** The page needed a secure context for `crypto.subtle` and was
  served over plain HTTP from a LAN IP, where it was measured absent (`isSecureContext === false`,
  `typeof crypto.subtle === "undefined"`), so the ECDH handshake could never run. The listener now
  mints a self-signed certificate per session and serves https; the phone shows its usual
  "not private" warning and the user accepts it. The certificate is written as DER in
  `settings/SelfSignedCert.kt` (~70 lines, no dependency, no BouncyCastle) and proven by parsing,
  verifying its own signature and completing a real TLS handshake in `SelfSignedCertTest`.
- Honest scope: the certificate is not trusted by anyone and the handshake stays unauthenticated —
  the capability token in the URL is still the authorisation. The phone-side click-through has not
  been exercised on a physical watch (no device available); the TLS handshake and the https page are
  measured on the JVM.

## Unreleased — output-cap and policy cuts are disclosed

- **A cut answer no longer looks finished.** The compatible adapter reported `finish_reason:
  "length"` as a clean completion (the only adapter that never disclosed a cut), Gemini reported a
  policy finish after visible text, and any unknown `finishReason`, as finished too; Anthropic failed
  a whole delivered answer when `stop_reason` was absent and saved a blank SUCCESS row when nothing
  arrived. Each now reports `Truncated` (SUCCESS + visible note) or fails.
- **The idle deadline is live again.** A blank SSE line with no data field behind it reset the 120 s
  bound, so `: ping` keepalives kept a stalled answer alive forever.
- **Renderer content loss, measured on the parser.** A top-level fence whose body mixed one plain
  line with marked ones had its list markers stripped (`- build` → `build`); a fence indented inside
  a nested list was invisible, so its `$$…$$` body was torn out as display math; tables built inside
  list items were dropped from the overflow scan, so a clamped table reported no hidden cells and the
  next table claimed its count. All four are pinned in `RendererFenceAndOverflowTest`.
- Reader: a drag no longer detaches follow on a conversation that fits the screen; History's rows,
  New chat and search hits are disabled while an answer is unsaved instead of closing History on a
  refused tap; a refused edit target reports the real reason; a Stop on retry/edit before its
  truncate ran no longer appends an empty STOPPED row over the finished answer.
- Measured: JVM 227/227, 38 suites, ktlint 0, Android Lint 1 (GradleDependency), release APK
  3,590,509 B.

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
  AES-GCM, single-use, 120 s TTL, 16 KiB cap, foreground-only. Manual entry remains available.
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
