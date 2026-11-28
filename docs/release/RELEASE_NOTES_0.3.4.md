# NiroWear 0.3.4

Release date: 2026-11-28. Maintenance release bundling deep bug fixes, dead-code removal, Compose/GPU/allocation optimizations, and expanded parser/provider/pairing regression coverage on top of `0.3.3`.

## Artifact identity

- Package `com.nirowear`, `versionCode 20`, `versionName 0.3.4`, non-debuggable, minSdk 30, targetSdk 37.
- Signed with the NiroWear release key (`CN=NiroWear Release`), matching `0.1.1` through `0.3.3`, so upgrades install in place.
- Built and signed by `.github/workflows/release.yml` from the `v0.3.4` tag (`ktlintCheck`, `testDebugUnitTest`, `lintDebug`, and `RELEASE_NOTES_0.3.4.md` preflight all gate signing).

## What changed

- **Streaming Markdown / LaTeX parser parity (`ResponseParser`):**
  - Keep `listContentIndent`/`listIndent` frozen while inside an open code fence in `parse()` and `scanTableOverflow()` so nested YAML/Markdown lists inside fences never swallow following `$$...$$` display math.
  - Align `findSafePrefixCut()` with `parse()` across unclosed `$$` in GFM table cells, 4-space indented code `$$`, depth-2 nested-list `\[...\]`, mid-line `$$...$$` followed by fences or Type 1–5 HTML blocks (`INCREMENTAL_HTML_BLOCK`), blank lines inside list items before indented fences, and multi-line link reference definitions (`containsReferenceDef`).
- **Chat streaming & storage reliability (`ChatViewModel`, `ChatDatabase`, `ProviderSettings`, `SecretStore`):**
  - Prevent out-of-order UI regression (`advanceLiveContent()`), scope `isLivePersisted()` by `conversationId`, stop finalized overlays from resurrecting deleted rows, and publish committed rows in `retrySaving()` / `pendingSave` without flashing a stale `STREAMING` checkpoint row.
  - Add indexed `firstUserMessageId` / `lastUserMessageId` / `tryEdit` queries in `ChatDatabase` to avoid loading entire conversation transcripts on edit/retry; reuse `store.edit` output in `ProviderSettings.delete()`; delegate `SecretStore.read()` directly to `AtomicFile.readFully()` so `.bak` backup files recover seamlessly after interrupted writes.
- **Pairing, certificate, HTTP & provider adapter hardening (`ProviderScreen`, `PairingServer`, `SelfSignedCert`, `ProviderHttp`, adapters):**
  - Call `stopPairing()` when deleting a provider profile during LAN pairing so `PairingServer` and `pairingFetchScope` terminate cleanly; track `sent` in `PAIRING_PAGE` JS so completed pairing stays disabled; enforce strict `stringOrNull()` and `Long` `Content-Length` checks in `PairingServer` (rejecting JSON `null`/numbers with HTTP `400`).
  - Use `AtomicReference<Job?>` in `ProviderHttp` for thread-safe SSE cancellation across OkHttp callback and `awaitClose`; replace unsafe `.jsonPrimitive` casts in `OpenAiProvider`, `GeminiProvider`, `AnthropicProvider`, and `OpenAiCompatibleProvider` so malformed non-primitive JSON fields do not crash streams as `PROTOCOL`; ensure positive X.509 serials and wipe temporary PKCS12 passwords in `SelfSignedCert`.
- **UI, input, math/search guards & optimizations:**
  - Replace per-frame `Canvas` path allocations with cached `drawWithCache` in `ProviderScreen` QR rendering and `ReaderList` `ReaderAction`; retain `rememberMath` `Drawable` state across LRU cache eviction (`MathContent`); guard `submitEditor()` / anchors on active/unsaved states (`MainActivity`); bound optional `[raise]` in `\rule[raise]{w}{h}` (`NativeMathGuard`); fix `extractSearchSnippetPlain()` `Int` overflow and trailing whitespace (`SearchQuery`); replace regex overhead with direct scans (`LanAddress`, `RenderUnit.hasMoreLinesThan`, `SearchQuery`).
- **Tests:** Add **22** new JVM tests (including a 200-trial randomized Markdown/LaTeX streaming fuzz), update `ProviderValidationRaceTest.awaitMessage()` scroll positioning for the `v0.3.3` header layout, and deflake `PairingHostileTest#closeIsFastIdempotentAndShutsThePort` on Linux socket teardown.

## Tested for this cut

- Pre-tag host verification: EN/`in-ID` resource string parity (`143` + `8` = `151` keys) and `PAIRING_PAGE` `node --check` passed; `:app:ktlintCheck` `0` violations; `:app:testDebugUnitTest` **`343` tests across `50` suites (`342` passed, `1` skipped `LiveProviderSmokeTest`, `0` failures)**; `:app:lintDebug` **`0` errors / `0` source warnings** (`7` dependency/Gradle version notices on `app/build.gradle.kts`); release APK (`versionCode 20`) contains compiled `assets/dexopt/baseline.prof` (`6,327 B`) and excludes `RENDER_TORTURE_FIXTURE.md` (`fixture_in_release=False`).
- Physical Xiaomi Watch 2 verification and signed release SHA-256 recorded immediately after the signed `v0.3.4` workflow finishes.
