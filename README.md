<div align="center">

<img src="docs/branding/nirowear-icon.png" width="112" alt="NiroWear icon" />

# NiroWear

**A bring-your-own-key AI chat client for Wear OS — built so a real answer stays readable on a 466 × 466 round watch.**

[![CI](https://github.com/michaelxdips/NiroWear/actions/workflows/ci.yml/badge.svg)](https://github.com/michaelxdips/NiroWear/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/michaelxdips/NiroWear?label=release&color=4285F4)](https://github.com/michaelxdips/NiroWear/releases)
[![License: MIT](https://img.shields.io/badge/license-MIT-3DA639.svg)](LICENSE)
![Platform](https://img.shields.io/badge/platform-Wear%20OS%20%C2%B7%20API%2030%2B-3DDC84)
![Kotlin](https://img.shields.io/badge/Kotlin-2.x-7F52FF)
![Compose](https://img.shields.io/badge/Compose%20for%20Wear%20OS-Material%203-4285F4)

[Features](#features) · [Install](#install) · [Provider setup](#provider-setup) · [Build](#build-from-source) · [Architecture](#architecture) · [Evidence](#testing-and-evidence) · [Limitations](#known-limitations)

**Website: [michaelxdips.github.io/NiroWear](https://michaelxdips.github.io/NiroWear/)**

</div>

---

## What this is

NiroWear is a standalone Wear OS chat client that talks **directly** to the AI provider you choose, with **your own API key**. No account, no relay, no NiroWear backend, no analytics. The key is encrypted on the watch and sent only to the endpoint you configured.

It exists because a watch is a bad screen for a good answer. Every other approach either mirrors a phone notification or truncates the reply. NiroWear instead treats the answer as a **document**: Markdown becomes semantic blocks, math is typeset natively, tables become labelled records, and wide content pans sideways instead of shrinking.

**Reference viewport:** 466 × 466 px, round (Xiaomi Watch 2 class). Everything is designed against that, not scaled down from a phone.

> NiroWear is an independent client by Stephen Michael — not affiliated with OpenAI, Google, or Anthropic. "Wear OS" and "Android" are trademarks of Google LLC.

<table>
<tr>
<td align="center"><img src="docs/screenshots/chat-home.png" width="214" alt="New chat screen with keyboard and voice controls" /><br /><sub><b>New chat</b></sub></td>
<td align="center"><img src="docs/screenshots/code-reader.png" width="214" alt="Kotlin code block panning horizontally in the reader" /><br /><sub><b>Code that pans</b></sub></td>
<td align="center"><img src="docs/screenshots/table-record.png" width="214" alt="Markdown table rendered as labelled vertical records" /><br /><sub><b>Tables as records</b></sub></td>
</tr>
<tr>
<td align="center"><img src="docs/screenshots/real-response.png" width="214" alt="A long mixed-markdown answer" /><br /><sub><b>Long answers</b></sub></td>
<td align="center"><img src="docs/screenshots/streaming-stop.png" width="214" alt="Streaming answer with the Stop action" /><br /><sub><b>Stream + Stop</b></sub></td>
<td align="center"><img src="docs/screenshots/history.png" width="214" alt="Conversation history with search" /><br /><sub><b>History + search</b></sub></td>
</tr>
</table>

## Features

### Reading

- **Semantic rendering, never one text node.** Output is parsed into paragraph, heading, bullet list, numbered list, quote, inline code, code block, inline math, display math, table and divider blocks. Raw Markdown is stored in Room, so the renderer can evolve without a migration.
- **Native LaTeX typesetting** in release builds: Greek letters, operators, calculus, set theory, logic, vectors, fractions, roots, powers and subscripts, integrals, sums, limits, `bmatrix`, `cases` and aligned equations.
- **Wide content pans; it does not shrink.** Code and formulas scroll horizontally inside a single vertical reader. A long line costs a swipe, not your eyesight.
- **Tables as labelled vertical records.** Every row repeats its column labels, so a table reads correctly on a dial. Rows are their own lazy items: a 120-row table is fully reachable and nothing is withheld.
- **Long code fences too.** A fence is split into 24-line chunks, each its own item, so a 200-line listing keeps every line without becoming one giant measuring pass.
- **Links stay tappable inline**, with a bounded action list per paragraph and a single actionable link group node for screen readers.

### Chat

- **Streaming with honest interruption.** Tokens render as they arrive through a 16 ms coalescing throttle (at most one re-parse per frame). Scroll up mid-answer and the reader stops following you and offers a small **Latest** action instead of yanking you back.
- **Stop means stop.** Cancellation propagates into the OkHttp call, the partial text is kept and persisted as `STOPPED`, and the next send never replays visible output.
- **Edit a previous message, linearly.** Replace the message, delete everything after it, resend through that point, generate a new continuation. No branches, no alternate variants.
- **Retry an answer** without losing the question.
- **Full history** plus search over every stored message.

### Input

- **Keyboard and voice are both first-class.** Voice delegates to the platform recogniser and always returns text you can review before sending.
- **The IME is handled.** The window declares `adjustResize` and both typing screens consume the IME inset, because `targetSdk 37` is edge-to-edge on Android 15+ where the keyboard no longer shrinks the window.
- **Set the key up from your phone.** A one-time LAN listener serves an `https://` page so you paste the key into a real browser form instead of tapping 200 opaque characters on a 1.4-inch keyboard. The payload is ECDH P-256 + AES-GCM encrypted and single-use; the listener binds only to a private address.

### Your keys

- **BYOK, stored properly.** AES-256-GCM under a non-exportable Android Keystore key; version byte + 12-byte IV + ciphertext, AAD-bound to the credential reference. Only ciphertext and nonce touch disk — **no plaintext fallback**. A decrypt failure demands re-entry.
- **Never logged, never in a URL, never repopulated.** `Authorization`, `x-api-key` and `x-goog-api-key` are redacted from anything that could be printed; provider screens set `FLAG_SECURE`; a saved key is never loaded back into an editing field.
- **Backup is disabled**, because the key is device-bound by design.
- **No analytics, no crash reporting, no backend.**

### Interface

- **Fully localized.** 89 user-visible strings in English and Indonesian (`values/` + `values-in/`), on top of the renderer's own resource set.
- **Accessibility that is tested, not asserted.** The link group node is actionable, the composer field carries its own label, credential labels are announced once, and the provider header grows instead of clipping at large font scales. TalkBack *audio* acceptance still needs a real watch — see [Limitations](#known-limitations).

## Install

1. Download `app-release.apk` from the [latest release](https://github.com/michaelxdips/NiroWear/releases/latest).
2. Install it on a Wear OS 3+ watch (`minSdk 30`).

**Updating.** `0.1.1` and later share one signing key, so they update in place. A `0.1.0` install used the Android debug lineage and needs an uninstall first — that deletes local chats and saved keys, and keys are device-bound, so re-enter them.

Verify what you installed:

```bash
aapt2 dump badging app-release.apk | head -3    # versionCode 13, versionName 0.2.0
sha256sum app-release.apk                       # compare with the hash in the release body
```

## Provider setup

Four adapters, four genuinely different protocols:

| Adapter | Protocol | Instruction mapping | Terminal event | Model discovery |
|---|---|---|---|---|
| **OpenAI** | native Responses | `instructions` | `response.completed` | `GET /models` |
| **OpenAI-compatible** | Chat Completions | leading `system` message | `finish_reason` / `[DONE]` | optional; manual model always works |
| **Gemini** | `generateContent` (`alt=sse`) | `systemInstruction` | `finishReason` | filtered by generation capability |
| **Anthropic** | native Messages | top-level `system` | `message_stop` | `GET /models` |

The protocol is chosen by the provider type you select — never guessed from a model name. Each adapter owns its SSE framing and its own terminal event; **EOF alone is never success**.

To add one: **Menu → Providers / model → Add …**, then fill name, base URL, model and key.

- **Save is not gated on a live call.** Validate is advisory: a watch behind a captive portal must still be able to save a profile. An unvalidated save says so on screen, and a wrong key surfaces at the first send as an error row on the conversation.
- **Discovery failing never blocks manual entry.** Type the model ID yourself; a compatible endpoint may legitimately not implement `/models`.
- **A model is never silently substituted.** Changing a key on an existing profile does not retarget your next send; switching provider is an explicit action.

## Build from source

Requirements: JDK 17+ (developed against Android Studio's JBR), Android SDK 37, and network access for the first dependency resolve.

```bash
git clone https://github.com/michaelxdips/NiroWear.git
cd NiroWear

# exactly what CI runs
./gradlew :app:testDebugUnitTest :app:lintDebug :app:ktlintCheck \
          :app:assembleDebug :app:assembleRelease
```

On Windows use `gradlew.bat` and set `JAVA_HOME` first.

**Instrumented tests** need an emulator or device and are destructive to app state, so never point them at a watch holding real conversations:

```bash
./gradlew :app:connectedDebugAndroidTest
```

A Wear OS AVD needs the size override, because the tests drive 466-px coordinates:

```bash
adb shell wm size 466x466 && adb shell wm density 320
```

**Signing.** Releases are signed from the environment (`NIROWEAR_KEYSTORE`, `NIROWEAR_KEYSTORE_PASSWORD`, `NIROWEAR_KEY_ALIAS`, `NIROWEAR_KEY_PASSWORD`). With no keystore present the build emits `app-release-unsigned.apk`. No signing material lives in this repository.

## Architecture

One `:app` module: Kotlin, Compose for Wear OS Material 3, Coroutines/Flow, Room, DataStore, OkHttp, kotlinx.serialization.

```
com.nirowear
├── chat/       ChatViewModel — turn lifecycle, streaming, edit/retry/stop
│   ├── StreamParse.kt     coalescing throttle + parse memo (bounded, 3 conversations)
│   └── ReaderAnchor.kt    saved-instance reading position
├── data/       Room schema (conversations, messages, phases) + ProviderSettings (DataStore)
├── provider/   one adapter per protocol; SSE framing, terminal events, error mapping
├── renderer/   ResponseParser → ContentBlock → RenderUnit → Compose
│   ├── RenderUnit.kt      lazy units: one per table row, 24 lines per code chunk
│   └── NativeMathRenderer JLaTeXMath, bounded bitmap, readable source fallback
├── security/   SecretStore — AES-GCM payloads, Keystore key, no plaintext fallback
└── settings/   provider screen, pairing listener, credential entry
```

Load-bearing decisions:

- **Canonical history is provider-neutral.** USER/ASSISTANT raw content only; assistant rows record the profile and model that produced them. Switching providers preserves one linear history.
- **Adapters own instruction mapping, SSE parsing and semantic completion.** A closed socket without the protocol's completion event is reported as an interruption, not a finished answer.
- **The renderer is a subsystem, not formatting.** Blocks are semantic, composition is per *unit*, and scroll anchors are per *block*, translated through unit ranges.
- **Small screens are solved with layout, not truncation.** The client never shortens an answer for the display.

Deeper notes: [`ARCHITECTURE.md`](docs/memory/ARCHITECTURE.md) · [`PROVIDERS.md`](docs/memory/PROVIDERS.md) · [`RENDERER.md`](docs/memory/RENDERER.md) · [`SCOPE.md`](docs/memory/SCOPE.md) · [`docs/adr/`](docs/adr/)

## Testing and evidence

Every number below came from a command run on this tree. Anything not measured is labelled as such.

| Check | Result | Source |
|---|---|---|
| JVM unit tests | **269 / 269**, 43 suites, 0 failures | `app/build/test-results/testDebugUnitTest/*.xml` |
| Android Lint | **0 errors, 0 warnings** | `app/build/reports/lint-results-debug.xml` |
| ktlint | **0 violations** | `:app:ktlintCheck` |
| Assemblies | `assembleDebug` + `assembleRelease` pass | Gradle output |
| Instrumented suite (Wear OS 37 AVD, 466×466) | **66 tests, 2 failing, 0 errors** | `app/build/outputs/androidTest-results/connected/debug/*.xml` |

The two instrumented failures are **environmental, not defects**:

| Test | Why an emulator cannot pass it |
|---|---|
| `RotaryInputTest` | No rotary/bezel input source exists to drive. |
| `PairingUiTest` | Needs a real LAN and a phone browser to click through a self-signed certificate warning. |

Two published figures used to be wrong and are corrected rather than quietly dropped: `"48 / 48"` came from an older emulator run that no longer matched the suite, and `"14 failing"` was arithmetic (18 minus four tests re-run in isolation) rather than a full-suite measurement. A full run reported 18; the current figure is 2.

**Other measured facts**

- **History search at volume.** 5,000 messages across 200 conversations seeded on the AVD: worst query **7 ms**, common word 0–1 ms, against a 250 ms debounce. It stays a plain `LIKE` scan with no index — FTS5 is unavailable on this platform (`no such module: fts5`), and an index would buy nothing a user could feel.
- **Published artifact.** `v0.1.2` `app-release.apk`, 3,621,257 B, SHA-256 `96e3c2ae…285110e6`. A local build of the same tree is **not** bit-identical; trust the hash in the release body, not a local cut.
- **Signing identity.** `CN=NiroWear Release`, SHA-256 `0D:C5:0F:C0:19:…:86:54:3E`, valid to 2056.

**Explicitly unverified.** A physical watch has never run this build: battery, thermals, real touch and bezel input, and TalkBack *audio* are unmeasured. The pairing certificate click-through on a real phone is unmeasured. Six `@HostDrivenGate` instrumentation methods need a host script to seed process-death state and have never executed. All are tracked as issues below.

## Known limitations

Stated plainly, because a README that hides these is useless.

- **Physical device acceptance is incomplete.** All timing and input claims were measured on a Wear OS 37 x86_64 emulator at 466 × 466.
- **TalkBack audio is unaccepted.** Semantics and target geometry are tested and four measured dead stops are fixed, but nobody has listened to a real watch.
- **Voice transcription quality is the platform's.** The intent launch/return path is tested; recognition accuracy is not ours to claim.
- **Link taps** open through the system handler; destination completion on a real paired device is unverified.
- **Error text is still English-only.** UI chrome is fully localized; `ProviderError` messages and the credential-entry hints are not — tracked as issue #31.
- **The connected suite is a local gate, not a CI gate.** CI runs JVM tests, lint, ktlint and both assembles, because no emulator is available there. Run `connectedDebugAndroidTest` before a release; nothing will remind you.
- **CI cannot compile the instrumented tests**, because `app/src/androidTest` is gitignored by policy. That is how several of them rotted for months before the first full run — tracked as issue #30.
- **Math limits.** Rendering is cached in memory, so a formula does not flash its raw LaTeX when it scrolls back. A formula wider than the bitmap bound is typeset once at a smaller size — never below half — before falling back to readable source text. File-inclusion macros, macro definitions, unbalanced braces and oversized `\rule` dimensions are refused outright.
- **Renderer limits, still true.** A fence opened inside a quote can be closed by a `> ``` ` line inside its own sample. A table row wider than its header has extra cells dropped by the Markdown parser; the count is announced, but only for the block shapes the overflow scan has tests for.

## Tracked work

Remaining gaps are filed rather than described:

- **#27** Physical watch acceptance: battery, thermals, rotary, real keyboard, TalkBack audio
- **#28** Pairing: verify the self-signed certificate click-through on a real phone
- **#29** Six `@HostDrivenGate` instrumentation methods have never executed
- **#30** CI never compiles the instrumented tests, so they rot invisibly
- **#31** Localize error text: `ProviderError` causes and credential-entry hints

## Contributing

Read [CONTRIBUTING.md](CONTRIBUTING.md) first — it pins the build gate, the test layout and the rules that are not negotiable (terminal-event handling, credential storage, the renderer contract). [AGENTS.md](AGENTS.md) describes the product contract in more detail than this README does.

Short version: no new third-party dependency for something the standard library already does; a fix ships with the check that would have caught it; never shorten an answer to fit the screen.

## Security

Keys leave the device only to the provider you configured. There is no NiroWear backend. Threat model, credential storage and the pairing transport are documented in [SECURITY.md](SECURITY.md) and [docs/release/PRIVACY.md](docs/release/PRIVACY.md). To report a vulnerability, use GitHub **Security → Report a vulnerability**.

## License

Application source is **MIT** — see [LICENSE](LICENSE).

Bundled third-party components keep their own licenses. The full notices ship inside the APK under `assets/legal/`, including the GPL-2.0 text for JLaTeXMath (used with its independent-module linking exception), the MPL-2.0 source offer for the Public Suffix List, and Apache-2.0 notices for AndroidX. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and [docs/licenses/](docs/licenses/).

---

<div align="center">
<sub>Bring your own key. The answer is yours.</sub>
</div>
