# NiroWear 0.3.5

Release date: 2026-11-28. UX and multi-watch adaptive release on top of `0.3.4`: full-dial Wear OS layout engine for round (`384–480 px`) and square watches, redesigned dark teal phone pairing web app, and high-MVP wrist usability upgrades with zero new dependencies.

## Artifact identity

- Package `com.nirowear`, `versionCode 21`, `versionName 0.3.5`, non-debuggable, minSdk 30, targetSdk 37.
- Signed with the NiroWear release key (`CN=NiroWear Release`), matching `0.1.1` through `0.3.4`, so upgrades install in place.
- Built and signed by `.github/workflows/release.yml` from the `v0.3.5` tag (`ktlintCheck`, `testDebugUnitTest`, `lintDebug`, and `RELEASE_NOTES_0.3.5.md` preflight all gate signing).

## What changed

- **Full-dial multi-watch adaptive layout (`WatchMetrics`, `MainActivity`, `ProviderScreen`):**
  - Compute exact circular chord clearances (`chordWidthDp`, `minOffsetForWidthDp`) across round (`isScreenRound == true`) and square/rectangular (`isScreenRound == false`) Wear OS screens so content fills all usable dial width without bezel clipping.
  - Switch the reader `LazyColumn` to full-bleed `contentPadding` framed by soft top/bottom black fade scrims over always-visible header and footer controls (preserving TalkBack order and compact targets).
  - Add subtle `roundEdgeTaper` scaling/alpha curvature on items in the top and bottom `18%` edge bands of round watches, exact 2- vs 3-icon footer clearances (`footerBottomTwoIconsDp` / `footerBottomThreeIconsDp`), and curved Wear Material 3 `ScrollIndicator` on Reader, History, and Providers.
- **Redesigned phone pairing web UI (`PairingServer.PAIRING_PAGE`):**
  - Replace the plain pairing form with a self-contained dark teal card interface featuring an inline SVG Thread logo, live `"Local Wi-Fi · ECDH P-256"` status pill, 4 tappable provider quick-select chips synced with `#type`, API key Show/Hide + 1-tap clipboard Paste, collapsible Security & self-signed certificate disclosure preserving every security notice, animated `Fetch models` spinner + filterable selector, and color-coded status banners with zero external CDNs.
- **High-MVP wrist usability & visual polish (`MainActivity`, `DocumentBlock`, `ProviderSettings`, `ChatViewModel`):**
  - Keep watch screen awake (`FLAG_KEEP_SCREEN_ON`) while streaming replies (`chatState.generating`).
  - Add **Text size** (`Compact` `0.88x` / `Standard` `1.0x` / `Large` `1.15x`) persisted in `ProviderSettings`, plus 1-tap **Quick Switch** provider chips in the Menu when 2+ providers exist.
  - Add two-tap conversation deletion (`deleteConversation`), empty-history state, 2-line titles, relative timestamps (`Just now`, `Xm ago`, `Xh ago`, `Xd ago`), and active conversation highlighting in `HistoryScreen`.
  - Add `Copy answer` beside `Retry`, `Copy` buttons on fenced code block headers (`DocumentBlock`), NiroWear OLED teal Material 3 `ColorScheme`, hairline turn dividers with tinted role captions, calm pulsing `"Thinking…"` indicator before the first streamed delta, rounded Composer card, thumb-first pairing layout, subtle haptic feedback, and 1:1 EN/`in-ID` localization (`158` + `8` = `166` strings).

## Tested for this cut

- Pre-tag host verification: EN/`in-ID` localization parity (`158` `strings.xml` + `8` `renderer_strings.xml` `<string>` + `3` `<plurals>`), `PAIRING_PAGE` `node --check` + Node v26.12.1 WebCrypto ECDH/AES-GCM end-to-end simulation (`WEBCRYPTO_PAIRING_JS_OK`), `:app:ktlintCheck` `0` violations, `:app:testDebugUnitTest` **`352` tests across `53` suites (`351` passed, `1` skipped `LiveProviderSmokeTest`, `0` failures)**, `:app:lintDebug` **`0` errors / `0` source warnings** (`7` dependency/Gradle version notices), PR `#106` CI `jvm` (`4m50s`) + `instrumented` (`6m24s`) green, and release APK (`versionCode 21`) contains compiled `assets/dexopt/baseline.prof` (`7,972 B`) and excludes `RENDER_TORTURE_FIXTURE.md` (`fixture_in_release=False`).
- Signed artifact (`v0.3.5`, published `2026-11-28T10:07:31Z`): `app-release.apk` **`4,148,043 B`**, SHA-256 **`c5ea10db79208050fa0694f25eb2ed6ea42900877c8c63aeab1f4ca007d6c254`**, `CN=NiroWear Release`.
- Physical Xiaomi Watch 2 (`axolotlaxie`, API 34, `armeabi-v7a`) post-cut verification: in-place `v0.3.4 -> v0.3.5` upgrade **`0 FATAL`**, `versionCode=21`, `versionName=0.3.5`, non-`DEBUGGABLE`, `dumpsys nfc` clean (`nfc_has_nirowear=False`), Menu dump confirms `Providers / model`, `History`, `New chat`, `Text size` (and zero RenderLab items), cold start median **`593 ms`** (`598, 522, 535, 593, 606 ms`; `WaitTime` median **`599 ms`**), warm `HOT` median **`65 ms`** (`77, 79, 61, 63, 65 ms`; `WaitTime` median **`71 ms`**), **`TOTAL PSS 40,175 KB`** (`RSS 125,224 KB`, `SwapPss 337 KB`), Providers 18-swipe `gfxinfo` **`665` frames, `1` janky (`0.15%`)** (`P50 13 ms, P90 14 ms, P95 14 ms, P99 14 ms`), `Thermal Status: 0`, battery `level 71`, `temperature 319`. Closes `#27` and `#28`.
