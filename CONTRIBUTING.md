# Contributing to NiroWear

Thanks for looking. This is a small, opinionated Wear OS app; the goal is a coherent watch client, not a broad AI platform.

## Before you start

Read these, in order:

1. [README.md](README.md) — what the app is and what it deliberately is not.
2. [docs/memory/SCOPE.md](docs/memory/SCOPE.md) — the out-of-scope list. Adding anything from it needs a discussion before code.
3. [AGENTS.md](AGENTS.md) — the product, UX, renderer and engineering contracts.
4. [docs/memory/ARCHITECTURE.md](docs/memory/ARCHITECTURE.md) and [docs/memory/PROVIDERS.md](docs/memory/PROVIDERS.md) — how the pieces fit and how adapters work.

## Ground rules

**Never commit a real API key**, token, keystore or `local.properties`. Use your own watch's provider setup screen for credentials; nothing in the build needs a key checked in.

**Do not weaken the credential path.** Keys are AES-GCM ciphertext under an Android Keystore key. No plaintext fallback, no key in a URL, no key in a log, no key echoed back to a sender, no saved key repopulated into an editing field. A change that makes setup easier by relaxing any of that will be rejected.

**Do not log credentials.** `Authorization`, `x-api-key` and `x-goog-api-key` must never appear in logs, and provider error bodies must not be logged raw.

**Protocol follows provider type.** Never infer a wire protocol from a model name. OpenAI-native Responses and OpenAI-compatible Chat Completions are different adapters with different completion semantics.

**EOF is not success.** Streaming adapters must detect their protocol's terminal event. A stream that simply stops is an interruption, not a completed answer.

## Build and test

```powershell
# Compile and run the unit suite
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest

# Lint
.\gradlew.bat :app:lintDebug
```

Instrumentation tests need a Wear OS emulator or device on API 30+:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest
```

Instrumentation is destructive — it installs, drives and uninstalls the app. Do not run it against a watch that holds credentials or conversations you care about. Use a dedicated emulator.

Reference device for layout work is **466 × 466 @ 320 dpi, round**. If a change looks fine at another size, check it there before claiming it works.

## Pull requests

- Keep the diff focused. One concern per PR.
- State what you ran, not what you believe. "Built and ran `:app:testDebugUnitTest`" is useful; "should work" is not.
- If you touch rendering, include a screenshot from the 466 × 466 reference viewport.
- If you touch the credential path or an adapter, explain the security or protocol reasoning explicitly.
- New dependencies need a one-line justification. Prefer the standard library and the platform; this app deliberately has very few dependencies.

## Reporting bugs

Include the Wear OS version, watch model (or emulator image and size), the provider type and model, and the exact steps. **Never paste an API key** into an issue — redact it, and rotate it if you think it leaked.

## License

By contributing you agree your contribution is licensed under the project's [MIT license](LICENSE).
