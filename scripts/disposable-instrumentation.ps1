param(
    [string]$Serial = 'emulator-5572',
    [string]$Class = '',
    [string]$Name = 'full',
    [string]$ExpectedAvd = 'nirowear-b'
)
$ErrorActionPreference = 'Stop'

# Guard against protected emulators and hardware devices before any install/test mutation.
$protectedAvds = @('NiroWear_Harmony_Disposable', 'NiroWear_466')
if ($Serial -eq 'emulator-5554' -or $ExpectedAvd -in $protectedAvds -or $ExpectedAvd -notmatch '^nirowear-[ab]$') {
    throw "Refusing protected or non-disposable target (Serial=$Serial, ExpectedAvd=$ExpectedAvd); no install permitted."
}
$qemu = ((& adb -s $Serial shell getprop ro.kernel.qemu) -join "`n").Trim()
if ($qemu -ne '1') {
    throw "Refusing non-emulator target (ro.kernel.qemu='$qemu'); no install permitted."
}
$avdLines = @(& adb -s $Serial emu avd name)
$avdName = if ($avdLines.Count -gt 0) { $avdLines[0].Trim() } else { '' }
if ($avdName -in $protectedAvds -or $avdName -ne $ExpectedAvd) {
    throw "Wrong AVD '$avdName' (expected '$ExpectedAvd'); no install permitted."
}

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
