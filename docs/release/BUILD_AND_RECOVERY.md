# Build and recovery

## Toolchain

Single Android application module. Gradle wrapper 9.6.0; Android Gradle plugin 9.4.1; Kotlin Compose plugin 2.3.21; KSP 2.3.9. `gradle/gradle-daemon-jvm.properties` requests JDK 25. Application Java source/target compatibility is 17. Android SDK compile/target36, min30. SDK location belongs in ignored `local.properties`, never committed.

```powershell
.\gradlew.bat --version
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease :app:lintDebug
```

Commands match project tasks; historical current-source build evidence preserved. This operation does not repeat full unchanged-source assembly. Host-only presentation/package checks are separate from Android tests.

Unsigned release: `app/build/outputs/apk/release/app-release-unsigned.apk`. Release APKs are signed by the maintainer; this repository does not carry signing material. Never commit a keystore, and never change the signing identity to manufacture a release. Never package private keystores or change established identity to manufacture release approval.

## Safe device work

1. Create a new credential-free Wear OS AVD. Do not copy protected userdata or snapshots.
2. Run `adb devices -l`, then `adb -s <serial> emu avd name`. Verify actual runtime name before every operation.
3. Install/test only on that new AVD. Avoid unscoped Gradle connected tests; attached protected devices must never receive instrumentation.
4. Existing scripts assume their documented disposable AVD. Do not run destructive helpers on either `NiroWear_466` or `NiroWear_Harmony_Disposable`: both now contain protected data.

No destructive testing required for documentation-only changes. Production source fingerprint remains locked. Native debug math tests do not establish native release behavior.

## Recovery

- Keep original APKs, candidate lock, regression XML and release archive. No historic artifact is replaced by this operation.
- Before future production edits, create a new verified source-only recovery ZIP. Exclude secrets, signing keys, local.properties, emulator data and authenticated logs.
- Do not uninstall or clear an installation holding valuable conversations/credentials. APK rollback may need database-version compatibility; verify on a fresh AVD first and obtain separate data-loss authorization if required.
- Keys use device-bound Android Keystore encryption and backup is disabled. Broken/inaccessible keys require normal masked re-entry; no plaintext fallback or key extraction.
- Source workspace has no Git metadata or authorized remote. File fingerprints identify tested source; do not invent commit/tag provenance.

## Acceptance identity

Source/config/test fingerprint: `44020a80487861d8d252cee338ffc0a117bc32f290495480266a1ff7cad5a883` (68 locked files).

APK SHA-256: `8B29475E2F75550AE16177107870BC8F28EE0FD3DB226D4BCC801B775D94AF72`.

Certificate SHA-256: `7d4e6e99821d904f23900bd4e0cad5b64b1a2733a28243dcc8ab9d6e2768abe3`.

Documentation/gallery inventory has a separate fingerprint in final package. Documentation changes do not alter compiled candidate source identity.
