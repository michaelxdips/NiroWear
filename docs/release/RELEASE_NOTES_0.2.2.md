# NiroWear 0.2.2

Release date: 2026-11-17. A fix cut driven by measurements on the physical watch: a centered `Stop`
label, the composer keeping a full text-field value, complete Indonesian on the provider screen, and
a stale localization expectation corrected against the #49 decision.

## Artifact identity

- Package `com.nirowear`, `versionCode 15`, `versionName 0.2.2`, non-debuggable, minSdk 30, targetSdk 37.
- Signed with the NiroWear release key (`CN=NiroWear Release`), the same identity as 0.1.1 through
  0.2.1, so it updates in place.
- Built and signed by `.github/workflows/release.yml` from the `v0.2.2` tag.

## What changed

- **"Stop" label is centered.** Measured on the watch before the fix: the label sat at
  `Rect(189,344,243,380)` inside its button at `Rect(161,314,305,410)` — 17 px left of centre, because
  Compose for Wear OS Material 3 pads a button for an optional leading icon. The label now claims the
  button's full width with `TextAlign.Center`; measured after the fix: `Rect(161,344,305,380)` against
  `Rect(161,314,305,410)`, **dx = 0, dy = 0** (`StopButtonCenterTest`).
- **Composer state holds the whole `TextFieldValue`** (text + selection + composition) for the draft
  and the edit body. Measured cause: the editor handed the app `text='hi' composition=(0,2)` and the
  app kept only the text, so the value returned to the field carried `composition=null`. The reported
  lost-word symptom (#62) is **not** reproduced on hardware through real typing — identical Gboard
  taps keep both words on the previous build too; the issue stays open with that evidence.
- **Provider screen fully localized (#66).** The in-ID title read `Tambah compatible`; provider type
  names are resources now (`Tambah kompatibel`), and the key-shape advisory plus the remote-typing tip
  moved from Kotlin strings to `provider_key_mismatch` / `provider_remote_typing`.
- **`RendererLocalizationTest` matched to #49.** It expected Arabic-Indic digits inside the English
  fallback sentence, contradicting the decision that the hidden-cell note formats with
  `Locale.ENGLISH`; the expectation now follows the decision.

## What was run for this cut

Read from result files and command output, not from memory:

- JVM unit tests **303 / 303, 0 failures, 0 errors, 49 suites, 1 skipped** (the opt-in live provider
  smoke, skipped without its environment variables); `ktlintCheck` **0 errors**.
- Physical watch (Xiaomi Watch 2, Android 14 / API 34, 466x466 @ 320 dpi), full suite per class with
  `pm clear` between classes: **38 of 45 classes passed**. `PairingUiTest` fails without a phone
  browser (#28); `RendererLocalizationTest` was the stale expectation fixed above; four classes
  produced no JUnit summary because the runner died at teardown with
  `DeadObjectException` from `UiAutomation.disconnect` (`ReaderNavigationTest`, `ProviderSetupUiTest`
  — the two #50 already tracks — plus `StoppedStatusUiTest` and `ProviderValidationRaceTest`).
- Other measured numbers on that watch: cold start **median 4373 ms** over four valid runs (#65,
  versus 723 ms on 2026-11-16); scroll **761 frames, 1.18 % janky**, 50th 8 ms / 90th 13 ms / 99th 44
  ms; a `454x454` override keeps every control at 96 px (48 dp).
- **20-minute session** (149 swipe/tap cycles over the stored answers): `TOTAL PSS 73,642 → 76,949 KB`
  (+3.2 MB), `RSS 151,936 → 158,352 KB`, and `dumpsys thermalservice` reported `Thermal Status: 0`
  before and after — the device never left the nominal thermal level, so throttling was not reached.
- **NOT TESTED for this cut: battery drain in percent, TalkBack audio, the pairing certificate
  click-through on a phone, NFC tap and QR scan, voice input.** No phone was available on the LAN, no
  sentence was dictated and no audio was listened to; those stay open as #28, #27 and #47.
