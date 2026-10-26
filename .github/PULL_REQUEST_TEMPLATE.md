## What this changes

<!-- One or two sentences. Link the issue if there is one. -->

## Contract check

The contracts that matter here live in `AGENTS.md`. Tick what you actually verified — not what you
believe.

- [ ] **Canonical history** — I did not make the app depend on provider-specific state that would be
      lost on provider switch.
- [ ] **Editing is linear** — edit replaces, deletes everything after, resends, continues. No branching.
- [ ] **Stop** — partial text stays visible and is persisted; nothing is resent automatically.
- [ ] **Streaming** — final state is always parsed, even after an interruption.
- [ ] **Adapter truth** — EOF is not treated as success; the protocol's terminal event is required.
- [ ] **Credentials** — no key reaches a log, exception message, filename, URL or notification, and
      there is no plaintext fallback.

## Gates actually run

Paste the literal last line of each command. A green compile does not imply a green style gate.

```
./gradlew :app:testDebugUnitTest        ->
./gradlew :app:ktlintCheck              ->
./gradlew :app:lintDebug                ->
./gradlew :app:assembleRelease          ->
```

- [ ] `ktlintCheck` passes (CI fails on it)
- [ ] JVM tests pass, and the count did not drop without explanation
- [ ] If I touched UI, lifecycle, or credential promotion: I ran the `@HostDrivenGate` tests via
      `scripts/disposable-instrumentation.ps1` or `scripts/test-clean-wear.py`, because
      `connectedDebugAndroidTest` silently skips them.

## Honesty check

- [ ] Any new claim in a README or doc is backed by something I can point at: a command output, a
      file, or a test.
- [ ] If I could not verify something (no device, no live key, no stable channel), I said so rather
      than implying it works.
