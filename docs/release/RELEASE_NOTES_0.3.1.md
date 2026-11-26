# NiroWear 0.3.1

Release date: 2026-11-26. Maintenance and hardening release on `0.3.0`, resolving `ChatViewModel`, `MainActivity`, and `ProviderScreen` state/concurrency bugs, removing dead code (`0` source-code lint warnings), tightening GitHub Pages without pre-entry animation, and updating `compileSdk 37.0`-compatible dependencies.

## Artifact identity

- Package `com.nirowear`, `versionCode 17`, `versionName 0.3.1`, non-debuggable, minSdk 30, targetSdk 37.
- Signed with the NiroWear release key (`CN=NiroWear Release`), matching `0.1.1` through `0.3.0`, so upgrades install in place.
- Built and signed by `.github/workflows/release.yml` from the `v0.3.1` tag (`ktlintCheck`, `testDebugUnitTest`, `lintDebug`, and `RELEASE_NOTES_0.3.1.md` preflight all gate signing).

## What changed

- **`ChatViewModel` & `ProviderScreen` fixes:** Clear `liveAnswer` once `retrySaving()` commits so `FailureToken.ANSWER_NOT_SAVED` disappears immediately (`CompatibleChatTest#retrySavingClearsStaleLiveAnswerError`); rethrow `CancellationException` cleanly in `init`; make `SavedStateHandle? = null` (`lintDebug` `VisibleForTests` eliminated); guard `ProviderScreen` `Delete` with `busy` so concurrent `Save` actions cannot race deletion.
- **Reader parse cache thread confinement:** Run locale-change `streamPrefixCache.clear()` strictly inside `parseDispatcher` (`MainActivity`, pinned by `StreamParseTest#confinedPrefixCacheSurvivesRepeatedLocaleClearWithoutConcurrentModification`).
- **Dead-code & dependency updates:** Remove unused `TENF_WELL_KNOWN` constant (`NdefTag`) and 12 unused imports across 7 files; update `androidx.room` `2.8.5`, `kotlinx-serialization-json` `1.13.0`, `com.google.zxing:core` `3.5.4`, `androidx.test:runner` & `core` `1.7.0`, and `androidx.test.ext:junit` `1.3.0`. (`compose-bom:2026.11.01` remains held on `compileSdk 37.0` until SDK 37.1 adoption.)
- **Website & docs sync:** Tighten `docs/index.html` into 5 chapters without any `#boot` splash overlay, sync `docs/changelog.html`, `README.md`, and `docs/release/BUILD_AND_RECOVERY.md`.

## Tested for this cut

Read from result files and tool output on this tree, not from memory:

- **JVM unit tests:** **317 tests across 51 suites, 316 passed, 0 failures, 0 errors, 1 skipped** (`LiveProviderSmokeTest#streamsFromConfiguredEndpoint`, skipped without opt-in live credentials).
- **Lint & ktlint:** `ktlintCheck` **0 violations**; `lintDebug` **0 errors, 0 source-code warnings** (`7` pinned dependency-update advisories: `compose-bom 2026.11.01`, `lifecycle 2.11.1`, `wear-compose 1.7.2` requiring `compileSdk 37.1`, and `commonmark 0.33.0` pinned by contract).
- **CI instrumented smoke (`Wear OS 5, API 34, 466x466`):** Required PR check passes `10/10` classes including `CompatibleChatTest#retrySavingClearsStaleLiveAnswerError` before merge to `main`.

## Not tested for this cut

- **Physical Xiaomi Watch 2 hardware checks (`D0..D6`, `I1`, `I2`, watch `H1`/`J1`/`J2`):** Physical watch hardware testing remains skipped until a physical Xiaomi Watch 2 device is connected (`#27`, `#28`, `#47`, `#62`, `#65`, `#84`, `#89`).
- **5,000-message signed upgrade (`O4`):** Schema `2.json -> 3.json` migration (`200` chats / `5,000` messages) ran in `286.48 ms` on AVD `nirowear-b`; direct disk injection into the signed shipping APK stays intentionally blocked by `allowBackup="false"` and `debuggable=false`.
