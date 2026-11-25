# NiroWear 0.3.0

Release date: 2026-11-26. Stability, accessibility, localization, and performance cut building on 0.2.2 with atomic composer draft persistence, streaming parser optimization, full Indonesian reader notices, TalkBack traversal ordering, and release/CI hardening.

## Artifact identity

- Package `com.nirowear`, `versionCode 16`, `versionName 0.3.0`, non-debuggable, minSdk 30, targetSdk 37.
- Signed with the NiroWear release key (`CN=NiroWear Release`), the same identity as 0.1.1 through 0.2.2, so upgrades install in place.
- Built and signed by `.github/workflows/release.yml` from the `v0.3.0` tag (`ktlintCheck`, `testDebugUnitTest`, `lintDebug`, and `RELEASE_NOTES_0.3.0.md` preflight all gate signing).

## What changed

- **Atomic composer draft persistence (`I4`).** Exit-saved drafts live in Room (`ChatDatabase` v3) so Back-exit drafts survive process death and `Send` commits the `USER` row and clears the draft atomically in one transaction (`InputResultUiTest`).
- **History Flow resilience (`P1`) and SSE monotonic timing (`P2`, `P3`).** Exhausted Room retries no longer crash passive `conversations` collectors (`HistoryFlowRetryTest`); SSE idle deadlines use monotonic `System.nanoTime()`; superseded message parse entries are evicted (`Task5ParseMemoTest`).
- **Streaming parser performance (`J4`).** Added `ResponseParser.needsPreScan`, container/table fast-path guards, `ResponseParser.parseIncremental` safe top-level prefix caching (`3,755` bytes cached on 4 KB mixed Markdown), and single-flight `parseDispatcher` (`ResponseParserStreamingPerfTest` 6/6).
- **Localized unsaved-answer banner (`N4`) and header clipping fix (`N6`).** Moved the unsaved-answer banner behind `FailureToken.ANSWER_NOT_SAVED` (`failure_answer_not_saved`); `values/` and `values-in/` match at **127 keys per locale, zero missing** (`IndonesianReaderNoticesTest`, `IndonesianHeaderAndInputTest`).
- **Accessibility & horizontal scroll affordances (`M4`, `M6`).** Conditional horizontal pan fade and slate edge cue added to code blocks (`CodeBlockPanCueTest`); top-level reader `isTraversalGroup = true` and `traversalIndex` + composable ordering ensure TalkBack visits `Menu -> Reader/Menu -> Footer` with every interactive control labelled (`TalkBackTraversalOrderTest`).
- **Like-for-like cancel scroll preservation (`I3`).** Verified cancelling composer preserves exact first-row reading position and Turn 2 settles onto the new response (`ComposerSendButtonTest`).
- **CI, release workflow, and documentation sync (`P4..P12`, `A1..A8`, `B1..B4`, `K4`, `D6` #84).** Release workflow verifies `RELEASE_NOTES_<version>.md` before keystore decode and requires unit tests, lint, and ktlint before signing; CI requires explicit test sources and enforces both `jvm` and `instrumented`; website adds 1200x630 `og-card.png`, accessible lightbox focus/Tab trap/Escape restore, hidden-tab and skipped-intro overlay cleanup, HTML balance validation, and reconciles closed `#50` under `#84`.

## Tested for this cut

Read from result files and tool output on this tree, not from memory:

- **JVM unit tests:** **316 tests across 51 suites, 0 failures, 0 errors, 1 skipped** (`LiveProviderSmokeTest#streamsFromConfiguredEndpoint`, skipped without opt-in live credentials); `ktlintCheck` **0 violations**; `lintDebug` **0 errors, 1 warning** (`VisibleForTests` on `ChatViewModel` test `SavedStateHandle` default).
- **Debug Wear OS 5 AVD (`nirowear-b`, API 34, 466×466 @ 320 dpi):** verified `TalkBackTraversalOrderTest`, `MenuTargetUiTest`, `InputResultUiTest`, `IndonesianReaderNoticesTest`, `IndonesianHeaderAndInputTest`, `ComposerSendButtonTest`, `StoppedStatusUiTest`, `CodeBlockPanCueTest`, `HistoryReadFailureTest`, `ReaderSemanticsTest`, `PairingUiTest`, plus `I5`, `I6`, `I7`, `M2`, `M5`, `N1`, `N2`, `N3`, `N5`, `N6`, `M7`, and `J4` (`parse_pipefree` P50/P95 `5.015/11.735 ms`, `parse_mixed` P50/P95 `5.230/12.077 ms`, same-process `singleflight_inc_mixed` P50 `0.463 ms` / P95 `1.080 ms` vs full `2.050 / 4.937 ms`).
- **Signed release AVD (`nirowear-b`, API 34):** baseline, cold start, scroll jank, and `v0.2.1 -> v0.3.0` Room v2->v3 upgrade verified against the signed release APK built by this tag (recorded in `STATE.md` / `#65` / `#89`).

## Not tested for this cut

- **Physical Xiaomi Watch 2 hardware checks (`D0..D5`, `I1`, `I2`, watch rows of `H1`, `J1`, `J2`):** physical watch testing was explicitly skipped for this emulator-first release cut (battery drain in percent, thermal status above 0, TalkBack audio, real Gboard hardware dictation/taps, phone LAN pairing click-through `#28`, and physical rotary absence `#47`).
- **5,000-message signed upgrade (`O4`) and non-debuggable release `J3`/`O3` probes:** blocked without weakening `allowBackup="false"` or `debuggable=false` in the release build (`J3`/`J5`/`O3` debug AVD runs remain on record).
