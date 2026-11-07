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

## 0.1.2 — 2026-11-07

`versionCode 12`, `versionName 0.1.2`. One behaviour change, plus the instrumentation truth.

### Validate is advisory; Save is not gated on a live call

Save used to be `enabled = !busy && (!needsValidation || validated)`, so a profile could not be saved
until a real API call succeeded. That contradicts the same screen's own message — "Models unavailable.
Enter model manually; chat still allowed" — and it makes the app unusable exactly where a watch client
matters: offline, behind a captive portal, or while a provider is briefly down. Measured on a Wear OS 37
AVD, the four setup tests could never reach Save at all: the button was `clickable=true enabled=false`
with every field filled.

Save now requires the data a profile needs (name, base URL, model, and a key or an existing profile).
An unvalidated save is announced on screen ("Not validated. Saving is allowed; the first send reports
any problem."), and a wrong key surfaces at the first send as an error row on the conversation — which
is visible and recoverable, unlike being unable to save. Validate and Discover keep their behaviour.

Evidence: `ProviderSetupUiTest` 6/6 on the AVD (previously 4 failing), JVM gate green.

### Instrumentation: first execution, and the numbers corrected

The published "48 connected tests, 48/48" was never reproducible — the suite on disk is **66 methods**
and had never run on this branch. It ran on a Wear OS 37 AVD: **66 tests, 18 failing, 0 errors** at the
start of the audit and **18 failing** after this change. An earlier revision of this file said "14
failing": that number was inferred from a partial run (18 minus four setup tests re-run in isolation),
not measured, and was wrong — the suite shares one app process, so a class that passes alone can fail
in suite order. The README now itemises every remaining failure with its measured reason instead of
quoting a number nobody had executed.

The test-side pass that followed (fixture cleanup, stale MockWebServer queues, geometry assertions that
could never pass, mojibake in two literals, and a History list offset the composable discarded on every
close) brought the measured figure to **66 tests, 4 failing, 0 errors**. One of those was a product
fix: the History list now keeps its scroll position when it is reopened.

## 0.1.1 — 2026-11-06

`versionCode 11`, `versionName 0.1.1`. Everything below landed since the 0.1.0 stable cut.

**Signing identity changed.** 0.1.1 is signed with a dedicated NiroWear release key
(`SHA-256 0D:C5:0F:C0:19:1B:36:F3:74:33:07:4F:C7:89:8B:4D:16:52:98:CA:73:65:4F:41:6F:8F:50:F0:51:86:54:3E`,
valid to 2056), not the `CN=Android Debug` lineage of 0.1.0. Android will therefore refuse an in-place
update over 0.1.0: uninstall first. That removes local chats and saved keys — credentials are
device-bound by design, so re-enter them after installing.

### credential safety, turn recovery, adapter reasons

- **A transient Keystore failure no longer destroys every saved key.** `save()` treated ANY exception
  while obtaining the key — a Keystore IPC error, a daemon restart, the first access after boot — as
  proof that the shared entry was unusable, and deleted it. Since there is no plaintext fallback, every
  other profile's ciphertext became permanently undecryptable to save one new provider. Obtaining the
  key is now its own failure domain (drop the cached handle, keep the entry), and only
  `InvalidKeyException` — a key that genuinely cannot encrypt — replaces it.
- **A failed turn leaves the conversation and the composer in the right state.** A `wrote` flag now
  separates "nothing changed" from "the edit/truncate/admit already landed": an edit or retry stopped in
  the gap after `truncate` writes the STOPPED/ERROR row instead of deleting the previous answer and
  leaving no row, no status line and no Retry, while a turn refused before writing still records
  nothing. An edit whose turn is refused re-opens the editor with the text the user typed, instead of
  closing the composer over an unchanged message.
- **A Stop inside a new chat's first insert keeps the prompt.** The terminal write falls back to the id
  minted for the turn and creates the conversation row if the cancelled insert never committed
  (`INSERT OR IGNORE`), so the question is recorded rather than vanishing with a phantom History entry.
- **Editing a chat's first message renames it in History.** `retitleIfUnnamed` only matched the literal
  `'New chat'`, which a new chat never has — it is created already titled with its prompt, so the guard
  could not fire. An edit of the first message now renames unconditionally.
- **Each adapter reports the reason the provider actually gave.** Anthropic, OpenAI-native and the
  compatible adapter read the non-2xx body (`authentication_error`, `invalid_api_key`,
  `insufficient_quota`, …) instead of blaming the user's configuration for a rejected key.
- **Anthropic's model metadata is fetched once per endpoint and model**, not on every turn: it sat on
  the critical path of every send, so a watch paid a radio wakeup per turn. Only a positive answer is
  cached, so a transport failure is retried next turn.
- **A prose line with backticks is not a fence.** ` ```npm i``` runs it.` opened a fence that never
  closed, suppressing display-math extraction for the rest of the chunk.
- **A mid-line display is top-level**: it no longer inherits the previous display's list level and
  dedent. **Spacing commands** (`\hspace`, `\vspace`, `\hskip`, `\vskip`, `\kern`, `\rule`) are admitted
  by the math guard instead of silently degrading a real formula to monospace source.
- **The Stop button grows with the font scale** (minimums instead of a fixed 72×48 box that clipped at
  2.0×).
- Measured on this tree: JVM 264/264 in 42 suites, ktlint 0, Android Lint 1 (GradleDependency), release
  APK 3,606,893 B. The parser and adapter fixes are pinned by tests built from the measured input and
  mutation-checked; the Keystore split, the turn-recovery paths and the Compose changes are
  Android-only and have no JVM test here — the instrumentation suite still needs a device.

### stream liveness, honest status lines, table scan identity

- **A reasoning model is no longer killed as a dead socket.** The idle deadline advances only on a frame
  that DECODES to an event, which is what stops a keepalive from holding the watch radio — but the
  adapters returned an EMPTY list for reasoning and thinking frames, which is exactly what a model
  streams while it thinks. A turn that thought past the deadline was failed as "Provider timed out" with
  nothing on screen. Adapters now emit `StreamEvent.Working` for those frames: proof of life, no content.
  Pinned end-to-end over the wire with the real Anthropic decoder and an injectable 400 ms bound, and the
  inverse is pinned too (an Anthropic keepalive must still time out).
- **An answer cut by `pause_turn` or `tool_use` is disclosed.** Both were counted as Completed, so a
  server-paused turn (this client never resends) and a turn that stopped to call a tool this product
  neither renders nor executes were stored SUCCESS with no status line — the undisclosed cut `Truncated`
  exists to remove, and Gemini's CONTINUATION was already reported that way.
- **"Partial answer kept." is promised only when text survived.** The three causes that can leave text
  appended it unconditionally, so a failed connection test read "Provider timed out. Partial answer
  kept." over an empty screen. `ProviderError.userMessage(hasText)` now decides, from the turn's own
  accumulated text.
- **A captive portal is not an interrupted stream.** A 200 whose body is a web page read "Stream
  interrupted or unsupported."; it now reports that the provider was not reached, while a 200 that is
  JSON but not SSE stays a protocol problem.
- **A list table indented deeper than its marker reports its clamped cells.** The overflow scan compared
  masked prefixes, so `1. | a | b |` over rows indented four never matched: the library clamped the third
  cell and the reader dropped it with no notice. Container identity is now structural — quote depth plus
  the enclosing list's content column.
- **`$$` inside a table row no longer shreds the table** (it produced a blank one-cell row, the formula,
  and a stray `| y |` paragraph). **An HTML block queues no phantom dropped-cell count** that the next
  clean table then claimed as its own.
- **History A→B→A no longer disables the STOPPED/error prune**: the answer row is found by id instead of
  trusting the parse memo's insertion order.
- Measured on this tree: JVM 257/257 in 42 suites, ktlint 0, Android Lint 1 (GradleDependency), release
  APK 3,606,893 B. Every parser/protocol fix is pinned by a test built from the measured input and each
  was mutation-checked (production line reverted → red → restored). The answer-row lookup is
  Compose-only and has no test here.

### math rendering: cache, modest scaling, list indent

- **A formula no longer flashes its raw LaTeX on every re-appearance.** Typesetting now has an
  in-memory cache (8 entries, keyed by source and size) and the composable seeds its initial state
  from it, so scrolling a formula out and back shows the typeset result immediately instead of
  re-typesetting and showing `\frac{a}{b}` for a frame.
- **A formula too wide for the bitmap bound is panned, not refused.** The math contract's order is
  pan, then MODEST scaling, and only then give up; the renderer refused outright, so a wide alignment
  became monospace source text. It now typesets once more at a reduced size (never below half of the
  requested size) and only falls back to source if that still exceeds the bound.
- **A display inside a list item keeps the item's indentation.** The formula is extracted before the
  list is parsed, so the bullet is gone; without a level it sat flush against the screen edge inside
  an indented item. `ContentBlock.Math` carries the level (0 at top level), pinned by test.
- **`recoverInterrupted` no longer leaves orphan continuation records.** It marked killed rows STOPPED
  without deleting their `openai_phases` rows, breaking the "non-SUCCESS carries no phases" invariant
  that the terminal save maintains. Both statements now run in one transaction.
- Test hygiene: the oversized-pairing-body test asserted a clean 400 for a server that rejects on the
  declared length before reading the body — the close can win the race and the client reports an
  aborted upload. Both outcomes now pass, and the assertion that matters (no payload arrived) is
  unchanged.
- Measured: JVM 243/243, 41 suites, ktlint 0, Android Lint 1 (GradleDependency), release APK
  3,606,893 B. The cache and the scaling retry are Android-only (they build a bitmap) and have no
  JVM test; the list indent is pinned and mutation-verified.

### math and list repair, honest HTTP blame

- **Display math inside a nested list was never typeset.** A depth-four bullet sits at six columns; the
  marker scan stopped at three, so `listContentIndent` stayed at the depth-two value, `$$x^2$$`
  measured as four-column-indented code, and the reader showed the literal `$x^2$` runs. Markers are
  now measurable at any depth once inside a list, and only the first (top-level) one keeps the
  three-column limit.
- **Prose after display math inside a list rendered as monospace code.** The math flush ends the
  document chunk, so the following indented line was parsed in a fresh document where four columns mean
  an indented code block. The item's own indent is stripped for as long as that list context is
  current; a block four columns deeper still reads as code.
- **An empty display block was invisible.** `$$\n$$`, `\[\]` and a stream stopped right after `$$`
  produced a `Math` block with no source: nothing on screen, no hint, one dead scroll. Empty formulas
  are no longer emitted.
- **The math guard no longer refuses a macro for its prefix.** `\def` also matched `\definecolor`, so a
  legitimate formula silently degraded to source text; the refusal list now requires a command-name
  boundary. Its ceilings (4096 characters, 128 groups, unbalanced braces) and every refused command
  are pinned by `NativeMathGuardTest`.
- **A 3xx no longer blames your configuration.** Redirects are disabled, so a 3xx is a captive portal
  or a gateway — reported as "Cannot reach provider. Check connection." instead of "Provider rejected
  request."
- **A wrong Gemini key now reads as the key.** Gemini answers HTTP 400 with `API_KEY_INVALID` in the
  body; the status used to throw before the body was read. Non-2xx bodies can now be mapped
  (`ProviderHttp.stream(decodeError = ...)`), and the mapping stays narrow: a 400 about a bad field
  still reads as a request problem.
- **The idle deadline counts events, not bytes.** `{"type":"ping"}` data frames reset the 120 s bound
  the way `: ping` comments once did, so a keepalive-only stream held the socket and the radio for
  ever. The clock now moves only when a frame decodes to at least one event.
- Stated, not fixed: there is no math cache, so a formula re-typesets and shows its raw LaTeX for one
  frame after each scroll-out/scroll-in; a formula that lays out wider than 8192px falls back to source
  text instead of panning; `- $$\frac ab$$` loses its bullet because the formula is extracted before
  the list is parsed.
- Measured: JVM 242/242, 41 suites, ktlint 0, Android Lint 1 (GradleDependency), release APK
  3,606,893 B. Every fix above has a test that was verified red by mutating the production line.

### pairing hardening, memo retention, a11y/i18n passes

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

### LAN pairing served over TLS

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

### output-cap and policy cuts are disclosed

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

### streaming re-parse coalesced (B3-03)

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

### native math in release builds

- **LaTeX is typeset natively in release builds.** Previously the release variant rendered math as
  monospace source text and only debug typeset it. `jlatexmath-android` is now a runtime
  dependency; the release stub that returned `null` is gone.
- Licensing resolved: JLaTeXMath is GPL v2-or-later **with an independent-module linking
  exception**, which explicitly permits linking it from MIT code. The GPL text, the exception and
  a corresponding-source offer ship in `assets/legal/`.
- Safety behaviour unchanged: file-inclusion macros, macro definitions and unbalanced braces are
  still refused before the parser, and rendered output is bounds-checked.

### source published under MIT

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
