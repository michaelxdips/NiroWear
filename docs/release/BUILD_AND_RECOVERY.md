# Build and recovery

## Toolchain

Single Android application module, `:app`. Gradle wrapper 9.6.0 (`gradle/wrapper/gradle-wrapper.properties`); Android Gradle plugin 9.4.1; Kotlin Compose compiler plugin 2.3.21; KSP 2.3.9; ktlint plugin 14.2.0 driving ktlint 1.8.0. `gradle/gradle-daemon-jvm.properties` requests JDK 25. Application Java source/target compatibility is 17. Android SDK `compileSdk`/`targetSdk` 37, `minSdk` 30. SDK location belongs in ignored `local.properties`, never committed.

```powershell
.\gradlew.bat --version
.\gradlew.bat :app:testDebugUnitTest :app:ktlintCheck :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease :app:lintDebug
```

`app/src/test` and `app/src/androidTest` are gitignored by policy (`.gitignore:83-84`), so on a fresh clone `:app:testDebugUnitTest` reports NO-SOURCE and `:app:assembleDebugAndroidTest` has nothing to compile. `.github/workflows/ci.yml` runs that unit-test step anyway and comments the NO-SOURCE case rather than treating it as proof; its real gates are `assembleDebug`, `assembleRelease`, `lintDebug` and `ktlintCheck`.

Release signing is environment-driven (`app/build.gradle.kts:55-65`): `NIROWEAR_KEYSTORE`, `NIROWEAR_KEYSTORE_PASSWORD`, `NIROWEAR_KEY_ALIAS`, `NIROWEAR_KEY_PASSWORD`. `.github/workflows/release.yml` decodes the keystore from the `NIROWEAR_KEYSTORE_BASE64` secret, signs, verifies `certificate DN: CN=NiroWear Release`, then deletes the keystore before any upload step. With no keystore in the environment the same task produces `app/build/outputs/apk/release/app-release-unsigned.apk`. No keystore or password is committed, and the signing identity must not be changed to manufacture a release.

## Safe device work

1. Create a new credential-free Wear OS AVD. Do not copy protected userdata or snapshots.
2. Run `adb devices -l`, then `adb -s <serial> emu avd name`. Verify actual runtime name before every operation.
3. Install/test only on that new AVD. Avoid unscoped Gradle connected tests; attached protected devices must never receive instrumentation.
4. Existing scripts assume their documented disposable AVD. Do not run destructive helpers on either `NiroWear_466` or `NiroWear_Harmony_Disposable`: both now contain protected data.

No destructive testing required for documentation-only changes. A local fingerprint identifies nothing a
shipped artifact can be matched to; the tag and the workflow run do (see Acceptance identity). Native
debug math tests do not establish native release behavior.

## Recovery

- Keep original APKs, candidate lock, regression XML and release archive. No historic artifact is replaced by this operation.
- Before future production edits, create a new verified source-only recovery ZIP. Exclude secrets, signing keys, local.properties, emulator data and authenticated logs.
- Do not uninstall or clear an installation holding valuable conversations/credentials. APK rollback may need database-version compatibility; verify on a fresh AVD first and obtain separate data-loss authorization if required.
- Keys use device-bound Android Keystore encryption and backup is disabled. Broken/inaccessible keys require normal masked re-entry; no plaintext fallback or key extraction.
- The workspace is a Git clone of `https://github.com/michaelxdips/NiroWear.git`; released tags are `v0.1.0`, `v0.1.1`, `v0.1.2`. Provenance for a shipped artifact is its tag, the workflow run that built it and the APK SHA-256 that run printed — not a local file fingerprint.

## Acceptance identity

The 0.1.2 artifact identity is the one the tag workflow records: it verifies
`certificate DN: CN=NiroWear Release`, prints the certificate SHA-256 from `apksigner verify --print-certs`,
and appends the APK SHA-256 (`sha256sum`) to the release body. `docs/release/RELEASE_NOTES_0.1.2.md`
discloses the signer. Nothing in this file re-measures any of them. The `7d4e6e99…8abe3` certificate
fingerprint that used to be printed here belongs to the 0.1.0 debug-signed lineage and must not be used to
identify a 0.1.1+ artifact.
