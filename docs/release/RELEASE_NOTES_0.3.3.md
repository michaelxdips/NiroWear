# NiroWear 0.3.3

Release date: 2026-11-27. Focused release improving answer directness/accuracy, startup and streaming/reader responsiveness, physical rotary-crown scrolling, and phone-assisted setup (replacing broken NFC HCE with 1-tap **Open on phone**, hardened QR/TLS pairing, and encrypted model fetching on the pairing page).

## Artifact identity

- Package `com.nirowear`, `versionCode 19`, `versionName 0.3.3`, non-debuggable, minSdk 30, targetSdk 37.
- Signed with the NiroWear release key (`CN=NiroWear Release`), matching `0.1.1` through `0.3.2`, so upgrades install in place.
- Built and signed by `.github/workflows/release.yml` from the `v0.3.3` tag (`ktlintCheck`, `testDebugUnitTest`, `lintDebug`, and `RELEASE_NOTES_0.3.3.md` preflight all gate signing).

## What changed

- **Direct, accurate watch prompt policy (`ProviderContract`):** Sharpen `SYSTEM_INSTRUCTION` so models answer accurately, directly, and strictly to the point without filler, unsolicited lecturing, moralizing, or unnecessary caveats while preserving technical completeness and structured watch formatting (`WatchFirstPolicyTest`).
- **Replace broken NFC HCE with Open on phone + hardened phone pairing & model fetcher:**
  - Remove broken `NfcPairingService`, `NdefTag`, `apduservice.xml`, and `NFC` / `nfc.hce` manifest entries.
  - Add 1-tap **Open on phone** (`PhoneLauncher` via `androidx.wear:wear-remote-interactions:1.2.0` — official Wear OS `RemoteActivityHelper` companion launcher with no custom Play Services wrapper needed) alongside a dedicated full-screen pairing flow with `FLAG_KEEP_SCREEN_ON`, bezel-safe QR canvas, live expiration countdown, and Gboard remote-typing tip.
  - Add encrypted, non-consuming `POST /<CAP>/models` support in `PairingServer` plus a **Fetch models** button and filterable model dropdown inside `PAIRING_PAGE` (manual model ID entry remains available).
  - Harden `SelfSignedCert` (`id-kp-serverAuth` EKU, ECDSA `digitalSignature` `keyUsage`, conditional IP SAN), validate pairing payloads before consuming the session, preserve edit ID during Edit-mode pairing, cancel in-flight LAN/server startup cleanly on stop/back, localize provider validation errors across EN/`in-ID` (`1:1` parity), and require a two-tap confirmation before deleting a provider.
- **Faster startup, streaming & reader performance:**
  - Keep `Room` DAO construction (`ChatViewModel`) and `SecretStore` credential-directory lookup lazy so `MainActivity` / `Application` startup stays off unnecessary Main-thread initialization while preserving reflection contracts; parallelize `ProviderSettings.recover()` (single DataStore read), Room recovery, and `latestConversation()` (`LIMIT 1`) on `Dispatchers.IO`.
  - Scope `chat.conversations` collection strictly to `historyScreen` and load History search results with `extractSearchSnippet()` via `EXISTS`-indexed queries.
  - Decouple streaming UI updates (`50 ms` coalesced `uiFlusher`, immediate first delta) from `1000 ms` SQLite checkpoints, pause redundant full Room `messages(id)` re-reads mid-stream, keep SSE frame buffer appending/trimming zero-extra-copy, move provider lookup and `streamChat()` collection to `Dispatchers.Default`, and merge reader Markdown + unit parsing into one `parseDispatcher` pass with per-message caching.
  - Pre-join `CodeChunk.text`, add `@Immutable` annotations on renderer models, fix `NativeMathRenderer` `rememberMath` cache hits + `CACHE_ENTRIES = 64` + negative-cache entries, replace `Row(IntrinsicSize.Min)` blockquotes with RTL-safe `Box(drawBehind)`, extract shared `Modifier.horizontalPanCues`, and ship `app/src/main/baseline-prof.txt` producing `assets/dexopt/baseline.prof` (`7,854 B`) in release APKs.
- **Input, provider & UI fixes:**
  - Invert/scale generic rotary crown input cleanly (`-event.getAxisValue(AXIS_SCROLL) * scaledVerticalScrollFactor`) and bind rotary scrolling to `HistoryScreen` and `ProviderScreen`.
  - Gate retry/edit/composer actions on `!chatState.unsaved`, clear stale `lastEditAttempt` cleanly when idle, avoid bottom-bar overlap on `Jump to latest`, center `Stop` label padding, handle Anthropic `"refusal"` stops + deterministic 404 metadata caching, guard `JsonNull` in Gemini/OpenAI adapters, and surface `reasoning` / `reasoning_content` status in `OpenAiCompatibleProvider`.
- **Release dev-menu verification:** Reconfirm `RenderLab` / fixture menu items remain strictly `BuildConfig.DEBUG`-only and absent from release APKs (`fixture_in_release=False`).

## Tested for this cut

- Pre-tag host verification: EN/`in-ID` resource string parity and `PAIRING_PAGE` `node --check` passed; `:app:ktlintCheck` `0` violations; `:app:testDebugUnitTest` **`321` tests across `50` suites (`320` passed, `1` skipped, `0` failures)**; `:app:lintDebug` **`0` errors / `0` source warnings** (`7` dependency/Gradle version notices in `app/build.gradle.kts`); release APK (`versionCode 19`) contains compiled `assets/dexopt/baseline.prof` (`7,854 B`) and excludes `RENDER_TORTURE_FIXTURE.md`.
- Physical Xiaomi Watch 2 verification and signed release SHA-256 recorded immediately after the signed `v0.3.3` workflow finishes.
