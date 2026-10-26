param([string]$Serial = 'emulator-5560', [string]$Class = '', [string]$Name = 'full')
$ErrorActionPreference = 'Stop'
if ($Serial -eq 'emulator-5554' -or ((& adb -s $Serial emu avd name) -join "`n") -notmatch '^NiroWear_Harmony_Disposable') { throw 'Wrong AVD; no install permitted.' }
$out = "artifacts/software-rc-2026-10-09/$Name"
New-Item -ItemType Directory -Force $out | Out-Null
$analyzer = "$env:LOCALAPPDATA/Android/Sdk/cmdline-tools/latest/bin/apkanalyzer.bat"
$app = 'app/build/outputs/apk/debug/app-debug.apk'
$test = 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
$manifest = (& $analyzer manifest print $app) -join "`n"
$testManifest = (& $analyzer manifest print $test) -join "`n"
if ($manifest -notmatch 'package="com.nirowear"' -or $testManifest -notmatch 'android:targetPackage="com.nirowear"') { throw 'Unexpected package/target; no install permitted.' }
$manifest | Set-Content "$out/app-manifest.txt"
$testManifest | Set-Content "$out/test-manifest.txt"
& adb -s $Serial install -r $app
if ($LASTEXITCODE -ne 0) { throw 'App install failed' }
& adb -s $Serial install -r $test
if ($LASTEXITCODE -ne 0) { throw 'Test install failed' }
$args = @('-s', $Serial, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'annotation', 'com.nirowear.HostDrivenGate')
if ($Class) { $args += @('-e', 'class', $Class) }
$args += 'com.nirowear.test/androidx.test.runner.AndroidJUnitRunner'
& adb @args 2>&1 | Tee-Object "$out/literal-instrumentation.txt"
if ($LASTEXITCODE -ne 0 -or ((Get-Content "$out/literal-instrumentation.txt" -Raw) -notmatch 'OK \([0-9]+ tests?\)')) { throw 'Instrumentation did not pass; literal output preserved.' }
