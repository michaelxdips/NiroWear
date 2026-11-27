# NiroWear 0.3.2

Release date: 2026-11-26. Shipping release bundling the post-`0.3.1` concurrency, parser, math, and Compose optimizations together with the Xiaomi Watch 2 hardware-verified fixes (`MainActivity` reader notice scroll-to-reveal, `PairingUiTest` post-close TLS/socket refusal tolerance, and `InputResultUiTest` launch transition settle).

## Artifact identity

- Package `com.nirowear`, `versionCode 18`, `versionName 0.3.2`, non-debuggable, minSdk 30, targetSdk 37.
- Signed with the NiroWear release key (`CN=NiroWear Release`), matching `0.1.1` through `0.3.1`, so upgrades install in place.
- Built and signed by `.github/workflows/release.yml` from the `v0.3.2` tag (`ktlintCheck`, `testDebugUnitTest`, `lintDebug`, and `RELEASE_NOTES_0.3.2.md` preflight all gate signing).

## What changed

- **State & concurrency hardening:** Wrap `openEditor` via `rememberUpdatedState` in `MainActivity` so repeated long-press edits load the latest stored prompt; fix `(created as? PairingServer)?.close()`, dispose cancellation leak, and `busy` contention in `ProviderScreen` (`startPairing`); synchronize post-`close()` rejection in `PairingServer.dispatch` (`PairingCloseTest`); map `CompatibleStreamDecoder` JSON errors through `openAiError` while ignoring explicit `"error": null` (`DecoderRobustnessTest`); fix inline-math trailing-backslash newline bleed in `ResponseParser` (`ResponseParserTest`); guard non-positive intrinsic math bounds and double-check cache inside `NativeMathRenderer` `mutex`.
- **Reader notice visibility & live-region semantics:** Scroll `LazyColumn` to index `0` via `LaunchedEffect(notice)` when a reader notice appears (`Draft not saved. Try again.`, clipboard confirmation, voice errors) without pulling the reader back to the bottom of a long conversation, and add polite `LiveRegionMode.Polite` semantics on the notice row (`MainActivity`).
- **Compose, streaming & hygiene:** Move `historyKeysFor`, `renderUnitsFor`, and `unitRangeByBlock` onto `parseDispatcher` off the Main thread; hoist shared `TextMeasurer` in `ChatReader`, `Quote`, and `Table`; add `contentType` and `LazyColumn` item keys (`MainActivity`, `ProviderScreen`); remove unused `ReaderAnchor.index`, redundant `renderedHistory` alias, unused imports, and obsolete comments.
- **Physical Watch 2 instrumentation alignment:** Treat `SocketException`, `SSLHandshakeException`, and `EOFException` on post-close replay in `PairingUiTest` as refused alongside asserting no second profile persists; wait `800 ms` after `startActivitySync` in `InputResultUiTest.launch()` so Wear OS launch transitions settle before the first tap.

## Tested for this cut

Read from result files and tool output on this tree, not from memory:

- **JVM unit tests:** **320 tests across 51 suites, 319 passed, 0 failures, 0 errors, 1 skipped** (`LiveProviderSmokeTest#streamsFromConfiguredEndpoint`, skipped without opt-in live credentials).
- **Lint & ktlint:** `ktlintCheck` **0 violations**; `lintDebug` **0 errors, 0 source-code warnings** (`7` pinned dependency advisories in `app/build.gradle.kts`).
- **Physical Xiaomi Watch 2 debug instrumentation (`192.168.8.18:5555`, 466 x 466 @ 320 dpi, API 34, `screen_off_timeout` raised to `600000` ms during tests and restored to `15000` ms after):**
  - Post-final-edit pass: `InputResultUiTest` **8/8** (`Final2`), `IndonesianReaderNoticesTest` **1/1** (under temporary `id-ID` per-app locale, restored to `empty` after), `CompatibleChatTest` **4/4**, `ComposerSendReachabilityTest` **2/2**, `PairingUiTest` **1/1**, `ComposerImeCommitTest` **1/1**, `ComposerSendButtonTest` **1/1**, `InputControlsUiTest` **1/1**, `TalkBackTraversalOrderTest` **1/1**, `ReaderSemanticsTest` **1/1**, and `QuoteSemanticsTest` **1/1**.
  - On the same branch immediately prior to adding `LaunchedEffect(notice)` (which no-ops when `notice` is empty): `ReaderNavigationTest` **7/7** (`5/7` direct runs + `measuredAnswersKeepBeginningAndReachFinalLineAndRetry` and `wideMathAndCodeUseHorizontalPanInsideSingleVerticalReader` detached on-device).
- **CI instrumented smoke (`Wear OS 5, API 34, 466x466`):** Gated on PR before merge to `main`.

## Not tested for this cut

- **Human microphone voice dictation (`#27`) & phone Chrome LAN pairing cert tap (`#28`):** Require live human speech / separate phone browser interaction.
- **5,000-message signed upgrade (`O4`):** Direct disk injection into the signed shipping APK stays blocked by `allowBackup="false"` and `debuggable=false`.
