<div align="center">

# NiroWear

**BYOK AI chat for Wear OS. Your provider, your key, your watch.**

Streamed answers that read like a document — Markdown, code, math, tables and links — on a 466 × 466 round display.

[![License: MIT](https://img.shields.io/badge/License-MIT-3DA639.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Wear%20OS%203%2B-4285F4.svg)](#requirements)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.x-7F52FF.svg)](https://kotlinlang.org)

**Website: [michaelxdips.github.io/NiroWear](https://michaelxdips.github.io/NiroWear/)**

[Features](#features) · [Install](#install) · [Provider setup](#provider-setup) · [Architecture](#architecture) · [Evidence](#evidence) · [Contributing](#contributing)

</div>

---

## What this is

NiroWear is a standalone Wear OS chat client that talks **directly** to the AI provider you choose, using **your own API key**. There is no NiroWear account, no backend, no relay and no analytics. Your key is encrypted on the watch with the Android Keystore and is sent only to the endpoint you configured.

It exists because every other way of reading an AI answer on a watch is a compromise: mirrored phone notifications truncate, voice-only assistants can't show a code block, and mobile apps assume a 6-inch screen. NiroWear treats the watch as the primary device.

**Reference viewport:** 466 × 466 px, round (Xiaomi Watch 2 class). Everything is designed against that, not scaled down from a phone.

> NiroWear is an independent client by Stephen Michael — not affiliated with OpenAI, Google, or Anthropic. "Wear OS" and "Android" are trademarks of Google LLC.

## Features

**Four real adapters, four real protocols.** OpenAI Responses, Anthropic Messages, Google Gemini GenerateContent, and generic OpenAI-compatible Chat Completions. The protocol is chosen by the provider type you select — never guessed from a model name. Adding a model never silently changes the wire format. Instruction mapping differs per protocol (`instructions` vs `system` vs `systemInstruction` vs leading `system` message); each adapter owns its SSE framing and its own terminal event. EOF alone is never success — a closed socket without the protocol's completion event is reported as an interruption, not a finished answer.

**Bring your own key, stored properly.** Keys are encrypted with AES-256-GCM under a non-exportable Android Keystore key (randomized IV, version byte + 12 B IV + ciphertext, AAD-bound to the credential reference). Only versioned ciphertext and nonce touch disk; a profile stores a credential *reference*, never the key. A decrypt failure demands re-entry — there is no plaintext fallback, and a saved key is never repopulated into an editing field. Entry is masked (`PasswordVisualTransformation` + `KeyboardType.Password`) and the provider screens set `FLAG_SECURE`. See [Security & privacy](#security--privacy).

**LAN pairing for long keys — served over TLS so a browser will run its crypto.** The watch serves a
one-time page on your local network so a key can be pasted into a real form instead of tapped on a
1.4-inch keyboard: ephemeral ECDH P-256 + AES-GCM before it leaves the browser, gated by a 128-bit
single-use capability token shown only on the watch screen, valid for 120 s (+5 s delivery grace), and
closed the moment you leave the screen. The handshake is unauthenticated, so it is not "end-to-end
encrypted" in the strict sense — the capability token, not a certificate, is what authorises a
payload.

> **Why TLS, and what the certificate warning means.** Browsers expose the Web Crypto API
> (`crypto.subtle`) only on a secure origin, and a plain-HTTP LAN page is not one: measured on the
> page served that way, `window.isSecureContext` was `false` and `typeof crypto.subtle` was
> `undefined`, so the handshake could never run. The listener therefore minted its own certificate
> per session and serves https. Android's public API cannot issue a certificate and a crypto library
> would be a new dependency, so the certificate is written as DER by hand in
> `settings/SelfSignedCert.kt` (~70 lines) and proven by parsing it back, verifying its own signature
> and completing a real TLS handshake in `SelfSignedCertTest`. **Your phone will warn that the
> certificate is not trusted — that is expected, accept it to load the page.** The phone-side
> click-through has not been exercised on a physical watch in this repository (no device is
> available); the TLS handshake and the page-over-https path are measured on the JVM. Manual entry
> remains the fallback and the safer choice on untrusted networks.

**Streaming with honest interruption.** Tokens render as they arrive through a 16 ms coalescing throttle (at most one re-parse per frame, never a debounce that starves the view blank). Scroll up and the view stops following you, with a small `Latest` control to jump back. `Stop` cancels the underlying OkHttp call and keeps whatever partial answer had already arrived — it is never replayed automatically. If the provider had already signalled completion, the answer is kept as complete rather than relabelled as stopped: a finished answer is never downgraded. Partial content survives rotation, process death (interrupted streams resume as STOPPED on next launch), and errors.

**One linear conversation.** Editing an earlier user message replaces it, deletes everything after it, and regenerates from that point. Retry truncates the failed assistant message and resends. There are no branches and no alternate-response variants to choose between. Switching provider or model keeps the same history, and each assistant message records which profile and model produced it.

**A reader, not a bubble.** Assistant output is parsed into semantic blocks — headings, paragraphs, lists, quotes, inline code, fenced code, inline/display math, tables, dividers, links — and rendered as a document. Raw Markdown is stored in Room, so the renderer can improve without a migration. Tables become labelled vertical records; a 1000-row table renders at most 40 rows with an explicit "… N more rows" line rather than an ANR.

**Wide content stays readable.** Code and formulas are never shrunk into illegibility. They pan horizontally inside a single vertical reader, so a long line costs a swipe rather than your eyesight. Display math is promoted to a pannable block when it overflows; anything the math renderer deliberately refuses (file-inclusion macros, macro definitions, unbalanced braces) falls back to readable source text, never a blank.

**Math that covers the syllabus.** Greek letters, operators, calculus, set theory, logic, vectors, fractions, roots, powers, subscripts, integrals/sums/limits, matrices (`bmatrix`), cases, and aligned equations — natively typeset in release builds through JLaTeXMath (GPL v2-or-later **with an independent-module linking exception**, which is what permits linking it from MIT code; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and `assets/legal/`).

**Searchable history.** History carries a full-text search over every stored message. It is a plain
SQLite `LIKE` scan with escaped wildcards — no dependency, no virtual table, no migration, no extra
permission. FTS5 was tried first and measured impossible here: Android's bundled SQLite answers
`no such module: fts5` on a Wear OS emulator, so an FTS index would have crashed the upgrade.

**Answers sized by the question, never by the screen.** The system instruction asks for the full answer the question needs — facts, caveats, steps, examples, code, math, table rows — and to make a long answer navigable with structure (headings, lists, tables, fences, display math) rather than shorter. The client never truncates an answer to fit the display; the earlier instruction asked for the shortest answer *because* the device is a watch, which is exactly the reason the UX contract forbids.

**Keyboard and voice.** Both are first-class entry points. Voice delegates to the platform recognizer and always returns text you can review before sending.

## Screenshots

Uncropped 466 × 466 captures from the app running against a real HTTPS endpoint.

<table>
  <tr>
    <td align="center"><img src="docs/screenshots/chat-home.png" width="233" height="233" alt="New chat screen with keyboard and voice controls" /><br /><strong>New chat</strong></td>
    <td align="center"><img src="docs/screenshots/real-response.png" width="233" height="233" alt="Streamed answer to a real question" /><br /><strong>Streamed answer</strong></td>
  </tr>
  <tr>
    <td align="center"><img src="docs/screenshots/streaming-stop.png" width="233" height="233" alt="Active streaming with Stop control" /><br /><strong>Streaming + Stop</strong></td>
    <td align="center"><img src="docs/screenshots/code-reader.png" width="233" height="233" alt="Kotlin code block in a horizontally pannable reader" /><br /><strong>Code reader</strong></td>
  </tr>
  <tr>
    <td align="center"><img src="docs/screenshots/table-record.png" width="233" height="233" alt="Markdown table rendered as labelled vertical records" /><br /><strong>Tables as records</strong></td>
    <td align="center"><img src="docs/screenshots/history.png" width="233" height="233" alt="Conversation history screen" /><br /><strong>History</strong></td>
  </tr>
</table>

## Requirements

| | |
|---|---|
| Device | Wear OS 3 or newer (API 30+), round display recommended |
| Build JDK | 25 (the Gradle daemon toolchain pins it; app bytecode targets Java 17) |
| Android SDK | Platform 37, build-tools 36.1.0 |
| Gradle | Provided by the checked-in wrapper — do not install separately |

NiroWear is a **standalone** Wear app: it does not require a phone companion and works over the watch's own Wi-Fi or its Bluetooth bridge.

## Install

There is no store listing. Two supported routes:

**Prebuilt APK** — grab `app-release.apk` from [NiroWear releases](https://github.com/michaelxdips/NiroWear/releases) (latest `v0.1.2`). Signed, non-debuggable, versionCode 12, and it updates 0.1.1 in place (same signing identity). The source in *this* repository builds the same app; the release workflow signs it from the tag.

Verify before installing:

```powershell
Get-FileHash .\app-release.apk -Algorithm SHA256
# compare with the SHA-256 printed in that release's body — the workflow records the
# exact size and hash of the APK it built and signed, and a local build is not bit-identical
```

```powershell
# Verify the published signature before installing (android build-tools)
apksigner verify --print-certs .\app-release.apk
# expect: certificate DN: CN=NiroWear Release, OU=Android, O=NiroWear, L=Jakarta, C=ID
# expect: certificate SHA-256 digest: 0dc50fc0191b36f37433074fc7898b4d165298ca73654f416f8f50f05186543e
```

```powershell
adb install -r .\app-release.apk
```

> **Updating from 0.1.0 requires an uninstall.** 0.1.0 was signed with the Android debug lineage
> (`7D:4E:6E:99:…:AB:E3`); 0.1.1 is signed with the NiroWear release key, so Android refuses an in-place
> update. Uninstalling removes local conversations and saved keys — credentials are device-bound by
> design — so re-enter your provider afterwards.

> Stable releases live at [`michaelxdips/NiroWear`](https://github.com/michaelxdips/NiroWear) from `v0.1.0` on. From `v0.1.1` the release key is a dedicated NiroWear identity (`CN=NiroWear Release`, SHA-256 `0D:C5:0F:C0:19:1B:36:F3:74:33:07:4F:C7:89:8B:4D:16:52:98:CA:73:65:4F:41:6F:8F:50:F0:51:86:54:3E`, valid to 2056), built and signed by `.github/workflows/release.yml`; `v0.1.0` used the Android debug lineage, so **0.1.0 installs must be uninstalled before installing 0.1.1** (uninstalling removes local chats and saved keys). Historical beta APKs remain at [`NiroWear-Beta`](https://github.com/michaelxdips/NiroWear-Beta) (archived, read-only).

**From source**

```powershell
.\gradlew.bat :app:assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Then open NiroWear and configure a provider (below).

> Both variants typeset LaTeX natively through JLaTeXMath, which is GPL v2-or-later **with an independent-module linking exception** — that exception is what permits linking it from MIT code. The GPL text, the exception and a corresponding-source offer ship inside the app under `assets/legal/`. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Provider setup

Open **Menu → Providers / model** and pick a preset:

| Preset | Default base URL | Wire protocol |
|---|---|---|
| OpenAI | `https://api.openai.com/v1` | Responses |
| Anthropic | `https://api.anthropic.com/v1` | Messages |
| Gemini | `https://generativelanguage.googleapis.com/v1beta` | GenerateContent (SSE) |
| OpenAI-compatible | *(you supply)* | Chat Completions |

Name and base URL come prefilled. Then get the key onto the watch: **open the provider, type the key, tap Validate, tap Save.** That path always works, and it is the fallback when the phone route below is inconvenient.

> **Pairing from a phone works over TLS, and the screen warns about the certificate.** Tapping
> **Set up from phone** starts a one-time listener and shows an `https://` URL. A browser will only
> run Web Crypto in a secure context, and plain HTTP from a LAN IP is not one (measured:
> `window.isSecureContext` was `false`, `typeof crypto.subtle` was `undefined`), so the listener mints
> a self-signed certificate for the session and serves TLS. The browser therefore shows its
> "not private" warning: **accept it** and the page loads and encrypts normally. The listener is valid
> for two minutes, single use, and closes when you leave the screen.

**Validate · uses API** makes one real (billable) call against the selected model to prove the key and model work together — **advisory, not a gate**: you can save a profile without it, which is what makes the app usable on a watch that is offline or behind a captive portal. Saving unvalidated says so on the screen, and a wrong key then surfaces at the first send as an error row on the conversation. **Discover models** lists what the provider advertises; discovery failure never blocks manual entry, because compatible endpoints legitimately vary. The app never silently substitutes a model: authentication and listing are separate, and a deprecated or mistyped model surfaces as an error you can read.

### Why keys are annoying, and what we tried

An API key is 40–200 opaque characters. Typing that on a 1.4-inch keyboard is not a friction problem, it is a correctness problem — the failure mode is a silent typo. So the transport looked like the thing to fix, not the keyboard: put the key on a device with a real keyboard and have the watch only receive it.

That design is implemented, and it needed a transport fix to work: a browser refuses to expose `crypto.subtle` to a plain-HTTP LAN page, so the page now has nothing to encrypt with unless the listener serves TLS. It does — with a certificate minted per session on the watch and accepted by clicking through the browser warning. Typing the key on the watch once remains a first-class path, and `Validate` proves it immediately rather than at first send. Key normalisation at the boundary strips stray whitespace, and a key whose shape contradicts the selected preset shows a warning — nothing is switched automatically.

## Architecture

One application module, no over-engineering (29 Kotlin sources, ~8.2k lines, under `app/src/main`):

```
Wear Compose UI  →  ChatViewModel  →  LlmProvider adapter  →  OkHttp → provider
                         ↓
                  Room (history)  +  DataStore (profiles)  +  SecretStore (Keystore)
```

- **Kotlin + Jetpack Compose for Wear OS Material 3**, Coroutines/Flow, Room, DataStore, OkHttp, kotlinx.serialization. No Firebase, no analytics, no crash SDK — `grep` the build files and find nothing.
- **Canonical history is provider-neutral**: USER/ASSISTANT raw content only. Adapters own instruction mapping, request construction, SSE parsing and semantic completion. EOF alone is not success — adapters detect each protocol's terminal event.
- **One generation job per request.** Cancellation propagates into the OkHttp call (`awaitClose { call.cancel() }`). Partial text is durable across Stop, error and process death: checkpoints every 120 ms, final save in `NonCancellable`, interrupted streams marked STOPPED on next launch.
- **Renderer** parses semantic blocks off the main thread (throttled to one parse per 16 ms frame); raw Markdown is retained so the renderer can evolve. The parse memo keeps the last 3 conversations; table rendering caps at 40 rows.
- **No backend.** The only network calls are to the endpoint you configured — HTTPS-only in release (`usesCleartextTraffic=false`), redirects and automatic retries disabled, so a key cannot leak via a 302. Credential-bearing URL query strings are rejected at the endpoint layer.

Deeper reading: [ARCHITECTURE.md](docs/memory/ARCHITECTURE.md), [PROVIDERS.md](docs/memory/PROVIDERS.md), [RENDERER.md](docs/memory/RENDERER.md), [SCOPE.md](docs/memory/SCOPE.md).

## Evidence

Every number below was read from a result file produced on this tree, not quoted from memory. Reproduce any of them:

```powershell
# Unit suite + lint + assemble (CI runs exactly this)
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:ktlintCheck :app:assembleDebug :app:assembleRelease
```

| Check | Result (measured) | Where |
|---|---|---|
| JVM unit tests | 264 / 264, 0 failures, 0 errors, 0 skipped (42 suites) | `app/build/test-results/testDebugUnitTest/*.xml` |
| Connected suite (Wear OS 37 AVD, destructive — not a CI gate) | **66 tests, 14 failing, 0 errors**, measured 2026-11-07 on `emulator-5554` (`NiroWear_Audit`, x86_64, 466×466). The previously published "48 / 48" was a count from an older emulator run and no longer matches the suite (66 methods). **14 are still red** — see the list below; nothing here is claimed green | `app/build/outputs/androidTest-results/connected/debug/*.xml` |
| Host-gated instrumentation (7 `@HostDrivenGate` methods) | written, **never executed** (they need a host script to seed state) | `app/src/androidTest` (gitignored by policy) |
| Lint | 0 errors, 1 warning: `GradleDependency` (test runner 1.5.0→1.5.2) | `app/build/reports/lint-results-debug.xml` |
| Release APK | Published `v0.1.1` asset: 3,621,253 B, SHA-256 `0BA7DDEF…B207`, `com.nirowear` v11 / `0.1.1`, minSdk 30, targetSdk 37 — built and signed by `.github/workflows/release.yml`. A local cut is **not** bit-identical (a local build of the same tree measured 3,619,181 B); the hash of the asset that ships is the one in that release's body. Signing is driven by the environment (`NIROWEAR_KEYSTORE`/`_PASSWORD`, `NIROWEAR_KEY_ALIAS`, `NIROWEAR_KEY_PASSWORD`); with no keystore present the build emits an unsigned `app-release-unsigned.apk` | `app/build/outputs/apk/release/`, `output-metadata.json` |
| Signing identity | `CN=NiroWear Release`, SHA-256 `0D:C5:0F:C0:19:…:86:54:3E` (valid to 2056); 0.1.0 was `CN=Android Debug` `7D:4E:6E:99:…:AB:E3`, so 0.1.0 installs need an uninstall before updating | `apksigner verify --print-certs`, release notes |
| Fuzz probes | parser/SSE/UTF-8/hostile-LaTeX/zero-width-key corpus: 21/21 PASS, 3000-mutation run clean, 10 MB payload ~360 ms | throwaway harness, not shipped |
| Live site | zero console errors (Edge + mobile shape), all links 200, numbers match release XML | [michaelxdips.github.io/NiroWear](https://michaelxdips.github.io/NiroWear/) |

CI is green on `main` (JVM + lint + ktlint + both assembles); the connected suite is a local pre-release gate because it needs an emulator. Read the full per-claim table — including what is explicitly **unverified** (real-watch battery/thermals, TalkBack audio, production-credential sweeps) — on the [site's evidence section](https://michaelxdips.github.io/NiroWear/#status) and in [docs/release/RELEASE_NOTES_0.1.1.md](docs/release/RELEASE_NOTES_0.1.1.md).

**Instrumentation status, measured 2026-11-06** (Wear OS 37 AVD, 66 methods, 18 failing). What an
emulator cannot prove is marked as such; the rest is open work, not a green claim:

| Class | Failing | Why (measured, not guessed) |
|---|---|---|
| `ReaderNavigationTest` | 4 | Advanced past the lookup bug (text compares now strip the renderer's FSI/PDI marks); remaining failures are scroll-geometry assertions and an IME prompt that never reaches the field. |
| `CompatibleChatTest`, `HistoryReadFailureTest` | 2 | 10 s coroutine budgets against a software-GPU emulator; a cold start can exceed them. |
| `NativeProviderSwitchTest` | 1 | Asserts a stored status is null where the run produces "Stream interrupted or unsupported." — unexplained, needs a decoder trace. |
| `StoppedStatusUiTest` | 1 | Reaches the status text but the viewport/reachability geometry assertion fails at font scale 1.0. |
| `RendererRowLocalizationTest` | 1 | The "… N more table rows" notice is not found for a header-less table fixture. |
| `ProviderValidationRaceTest`, `SettingsRecoveryTest` | 2 | Require a virgin app; a previous test's profile survives (`found: [2222…]`). Per-test `pm clear` is the fix (the AndroidX orchestrator did not help). |
| `RotaryInputTest` | 1 | Emulator has no rotary/bezel input source — environment, not a defect. |
| `PairingUiTest` | 1 | Needs a real LAN/phone browser; the emulator cannot exercise the certificate click-through. |
| `ByokPersistenceTest` | 1 | Process-restart probe; emulator keystore/restart semantics differ from a watch. |

## Security & privacy

- Keys encrypted with **AES-256-GCM** under an Android Keystore key; versioned ciphertext + nonce only, no plaintext fallback. Decrypt failure = re-entry.
- Stored keys are never logged (`Authorization` / `x-api-key` / `x-goog-api-key` redacted), never in URLs/filenames/notifications/errors, never repopulated into editing fields.
- Masked entry by default; provider screens set `FLAG_SECURE` so they cannot be screenshotted by other apps.
- Release networking requires **HTTPS** with platform trust. Redirects and automatic retries are disabled. Credential-bearing URL query strings are rejected.
- Pairing transport is TLS with a per-session self-signed certificate (the phone shows its usual
  warning and you accept it); the *payload* is additionally ECDH+AES-GCM encrypted and single-use.
  See the threat-model note under [Features](#features).
- `android:allowBackup="false"` + `data_extraction_rules` exclude everything — credentials are device-bound, so clearing data or uninstalling removes them permanently.
- **No analytics, no crash reporting, no NiroWear backend.**
- Chat text and the key go directly to the endpoint you chose; that provider's retention policy then applies, not ours.

Full facts: [PRIVACY.md](docs/release/PRIVACY.md). To report a vulnerability, use GitHub **Security → Report a vulnerability** (see [SECURITY.md](SECURITY.md)).

## Known limitations

Stated plainly, because a README that hides these is useless:

- **Native math typesetting is in release builds now**, verified by execution on a release APK (a seeded answer produced `Math:` canvas nodes for a display integral and inline `E = mc^2`). The fallback still exists for input the renderer deliberately refuses: file-inclusion macros, macro definitions, and unbalanced braces.
- **Physical Xiaomi Watch 2 acceptance is incomplete.** Emulator evidence is extensive; real-device battery, thermals and input are not yet verified.
- **Voice transcription quality is unaccepted.** The intent launch/return path is tested; recognition accuracy is the platform's.
- **Link taps** open via the system handler; destination completion on a real paired device is unverified.
- **Live smoke tests** are documented against controlled endpoints and one third-party Responses endpoint — not against every official provider with production credentials.
- **TalkBack audio** acceptance is incomplete. Two dead stops remain in the reader: a linked
  paragraph's "Links" group node carries no action, and the composer's field relies on the visible
  "Prompt" heading rather than a label of its own. A credential entry's label is also read twice, and
  the provider header (`130 dp`, `maxLines = 1`) truncates at large font scales.
- **Releases are signed by the NiroWear release key, not the debug key.** `v0.1.1` uses
  `CN=NiroWear Release` (SHA-256 `0D:C5:0F:C0:19:…:86:54:3E`, valid to 2056), built and signed by
  `.github/workflows/release.yml` from the tag. `v0.1.0` used the Android debug lineage, so an in-place
  update from 0.1.0 needs an uninstall.
- **The connected suite (66 methods, 18 failing) is a local gate, not a CI gate.** It needs an
  emulator, so CI runs the JVM tests, lint, ktlint and both assembles only. Run
  `connectedDebugAndroidTest` before a release; nothing will remind you. The 2026-11-06 run on a Wear
  OS 37 AVD is the first time it ever executed; the per-class failure list is in Evidence.
- **Seven further instrumentation methods are excluded from the default run.** They carry
  `@HostDrivenGate` and need a host script to seed state. Run them with
  `adb shell am instrument -w -r -e annotation com.nirowear.HostDrivenGate com.nirowear.test/androidx.test.runner.AndroidJUnitRunner`.
- **Known math limits.** Rendering is cached (8 entries, in memory) so a formula no longer flashes
  its raw LaTeX when it scrolls back into view, and a formula wider than the bitmap bound is typeset
  once at a smaller size — never below half — before falling back to source text. A display inside a
  list item keeps the item's indentation; the bullet itself is not reproduced, because the formula is
  extracted before the list is parsed.
- **Known renderer limits.** A fence opened inside a quote can still be closed by a `> ``` ` line inside
  its own sample (the top-level case is fixed and pinned). A table row wider than its header has its
  extra cells dropped by the Markdown library; the parser counts them and the reader says "… N more
  cells" — correct for the list, quote and HTML-block shapes the overflow scan has tests for, unverified
  for shapes beyond those. Tables are capped at 40 rendered rows with an explicit "… N more rows" line.
- **Not verified on hardware.** The Keystore, Room and Compose work in 0.1.1 — the split of a failed key
  save from a failed key, the STOPPED/ERROR recovery of an interrupted edit or retry, the editor recovery
  after a refused edit, and the Stop-button sizing — has no JVM test and no device run behind it. It
  compiles and is reasoned from the code; a physical watch is the proof.
- **Pairing needs the certificate warning accepted.** The watch mints a certificate per session, so
  the phone browser shows "not private" until you proceed; the page says so before you try. The
  phone-side click-through itself has not been exercised on a physical watch here (no device is
  available) — the TLS handshake and the https page are measured on the JVM.
- **A physical watch has not run this build.** Every timing and input claim here was measured on an
  emulator.

## Contributing

Issues and pull requests are welcome. Read [CONTRIBUTING.md](CONTRIBUTING.md) and [AGENTS.md](AGENTS.md) first — the latter documents the product and engineering contracts that keep the app coherent.

Two rules that matter more than style: **never commit a real API key**, and **do not add a feature from the out-of-scope list** in [SCOPE.md](docs/memory/SCOPE.md) without discussing it first.

## License

Application source is **MIT** — see [LICENSE](LICENSE). Copyright © 2026 Stephen Michael.

Third-party dependencies keep their own licenses; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and [docs/licenses](docs/licenses/README.md). One dependency is non-permissive: `jlatexmath-android` is GPL v2-or-later **with an independent-module linking exception**, which explicitly permits linking it with MIT code and distributing the result under terms of your choice. The GPL text and the library notice ship in `app/src/main/assets/legal/`.

"Wear OS" and "Android" are trademarks of Google LLC. NiroWear is not affiliated with or endorsed by Google, OpenAI, Anthropic, or any model provider.
