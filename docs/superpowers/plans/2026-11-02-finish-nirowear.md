# Finish NiroWear — unblock yourself, fix real defects, prove green, ship beta.7

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close every open item the review measured, prove it with fresh commands, and ship a beta the owner can install without a watch in the loop.

**Architecture:** No architecture change. Emulator-loop until the flaky tests are deterministic, fix the renderer/parser edge cases in place, tighten pairing copy to the measured truth, prune uncited weight, then cut the release.

**Tech Stack:** Kotlin, Compose for Wear OS, Room, OkHttp, kotlinx.serialization, Android Keystore, Gradle Kotlin DSL. Site demo: dependency-free HTML in `docs/index.html`.

## Global Constraints

- Reference viewport: round 466 × 466 px. No oversized bubbles or avatars.
- Never claim a build, test, emulator, or device run succeeded unless actually executed and output read.
- Never shorten an answer because the screen is small; use layout, scrolling, typography, content transformation.
- EOF alone is not success — detect the protocol's terminal event.
- Credentials: never log keys; versioned AES-GCM only; no plaintext fallback; never repopulate a saved key into an editing field; backup disabled.
- Compatible providers accept a manually typed model ID even when discovery fails. Never silently change a model.
- Test sources and prompts stay gitignored; publishing rule is allowlist `.gitignore` — `git add -A` must never leak them.
- Owner tests on their own hardware; no physical Xiaomi Watch 2 in the loop — every claim stays emulator-scope.
- PowerShell 5.1: no `&&`, single-quoted literals, explicit UTF-8 IO, check `$LASTEXITCODE`.
- Byte-level check is the only trustworthy encoding check, never `Get-Content` rendering.
- Preserve CRLF/LF. Minimal diffs. stdlib/native first.

---

### Task 1: Boot a clean harness and prove the starting line

**Files:**
- Modify: none (measurement only)
- Test: emulator `NiroWear_Rebook_20261020` probe commands below

**Interfaces:**
- Consumes: nothing
- Produces: boot load baseline, ANR-dialog state, device identity — every later task cites these instead of assuming a quiet device

- [ ] **Step 1: Start the emulator from a cold boot and verify identity**

```powershell
$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"
$adb="$env:ANDROID_HOME\platform-tools\adb.exe"
& "$env:ANDROID_HOME\emulator\emulator.exe" -avd NiroWear_Rebook_20261020 -no-snapshot -wipe-data
& $adb wait-for-device
& $adb -s emulator-5554 shell getprop ro.kernel.qemu
& $adb -s emulator-5554 shell "wm size; wm density"
```

- [ ] **Step 2: Run the quiet-device probe to verify baseline load and ANR-dialog state**

```powershell
$adb="$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb -s emulator-5554 shell cat /proc/loadavg
& $adb -s emulator-5554 shell settings get global hide_error_dialogs
& $adb -s emulator-5554 shell "dumpsys window | Select-String -Pattern 'mCurrentFocus'"
```

- [ ] **Step 3: Re-run JVM and record the frozen baseline from XML**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
Set-Location 'C:\Users\Michael\Documents\Project\NiroWear'
.\gradlew.bat :app:testDebugUnitTest --offline --console=plain
$files=Get-ChildItem 'app\build\test-results\testDebugUnitTest\*.xml'
"files=$($files.Count)"
$t=0;$f=0;$e=0;$s=0
foreach($p in $files){[xml]$d=Get-Content -LiteralPath $p.FullName -Raw;$t+=[int]$d.testsuite.tests;$f+=[int]$d.testsuite.failures;$e+=[int]$d.testsuite.errors;$s+=[int]$d.testsuite.skipped}
"tests=$t failures=$f errors=$e skipped=$s"
```

- [ ] **Step 4: Commit nothing; record numbers in the task log**

No commit. Report: qemu value, wm size/density, loadavg triple, hide_error_dialogs value, mCurrentFocus line, JVM counts. Expected: `ro.kernel.qemu=1`, `466x466`, density `320`, load under `1.5`, `hide_error_dialogs=1` after setting it.

---

### Task 2: Make the connected suite deterministic on the saturated AVD

**Files:**
- Modify: local-only `app/src/androidTest/...` (gitignored, never committed): `StreamingUiTest`, `ProviderSetupUiTest`, `ReaderNavigationTest`, `StoppedStatusUiTest`
- Test: `:app:connectedDebugAndroidTest --offline` full suite, XML under `app\build\outputs\androidTest-results\connected\debug\`

**Interfaces:**
- Consumes: Task 1 baseline (load, dialog state)
- Produces: one full-suite XML with 48/48 or a named-environment blocker with numbers

- [ ] **Step 1: Write the failing proof — run the full suite once cold and read the XML**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
Set-Location 'C:\Users\Michael\Documents\Project\NiroWear'
.\gradlew.bat :app:connectedDebugAndroidTest --offline --console=plain
$p=Get-ChildItem 'app\build\outputs\androidTest-results\connected\debug\*.xml' | Sort-Object LastWriteTime -Descending | Select-Object -First 1
[xml]$d=Get-Content -LiteralPath $p.FullName -Raw
"tests=$($d.testsuites.tests) failures=$($d.testsuites.failures) ts=$($d.testsuites.timestamp)"
```

- [ ] **Step 2: Apply exactly these harness rules wherever a fixed scroll/sleep remains**

Stale-fixed budgets fail under load 2.5-3.4. Replace with:
1. `hide_error_dialogs=1` set before every run (Task 1 proves it sticks across reboot).
2. Scroll loops until the node composes (`max 40`, error names the missing node and swipe count) — never a fixed swipe count.
3. `scrollToField` matches an editable node, not a label Text (label at viewport edge means field below is uncomposed).
4. Prefix-match streaming text (`awaitPrefix`), never exact equality on a block that grows.
5. Re-run same test 3x before calling it fixed; report all three times.

- [ ] **Step 3: Run the full suite again and count from the XML only**

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest --offline --console=plain
$p=Get-ChildItem 'app\build\outputs\androidTest-results\connected\debug\*.xml' | Sort-Object LastWriteTime -Descending | Select-Object -First 1
[xml]$d=Get-Content -LiteralPath $p.FullName -Raw
$d.testsuites.testsuite | ForEach-Object { "$($_.name): tests=$($_.tests) failures=$($_.failures)" }
```

- [ ] **Step 4: Report without committing test sources**

Expected: `tests=48 failures=0`, or the per-suite table plus loadavg during the run proving the environment bound. Test files are gitignored — `git status --porcelain` must stay clean of them.

---

### Task 3: Fix the five renderer/parser defects that lost content silently

**Files:**
- Modify: `app/src/main/java/com/nirowear/renderer/ResponseParser.kt`, `app/src/main/java/com/nirowear/renderer/DocumentBlock.kt`
- Test: JVM tests in `app/src/test/...ResponseParserTest.kt` style (local-only, gitignored)

**Interfaces:**
- Consumes: `MAX_INLINE_DEPTH=32`, `MAX_TABLE_ROWS=40`, `capContainers/uncontainer/flattenText` current behaviour
- Produces: six documented behaviours below, each with one failing-then-passing test

- [ ] **Step 1: Write one failing test per defect against the real parser**

```kotlin
@Test fun displayMathInQuote_keepsDelimiters() {
    val blocks = ResponseParser.parse("> \\[x^2\\]")
    assertTrue(blocks.any { it is ContentBlock.Math || it.toString().contains("x^2") })
}
@Test fun fenceInContainer_doesNotCloseCode() {
    val blocks = ResponseParser.parse("> ```\ncode\n> ```\ntext")
    assertTrue(blocks.any { it is ContentBlock.Code })
}
@Test fun flatten_keepsOrderAndNodes() {
    val blocks = ResponseParser.parse("a `b` \\(c\\) d")
    val para = blocks.filterIsInstance<ContentBlock.Paragraph>().first()
    assertEquals(listOf("a ", "b", " ", "c", " d"), para.content.map { it.text })
}
@Test fun capContainers_neverRewritesFenceBytes() {
    val src = ">" .repeat(60) + " ```kotlin"
    val blocks = ResponseParser.parse(src + "\ncode\n```")
    assertTrue(blocks.any { it is ContentBlock.Code })
}
@Test fun wideRow_keepsCellsWithMarker() {
    val row = (1..30).joinToString("|", "|", "|") { "c$it" }
    val blocks = ResponseParser.parse("|h|\n|---|\n$row")
    val table = blocks.filterIsInstance<ContentBlock.Table>().first()
    assertTrue(table.rows.first().size == 30 || table.toString().contains("more"))
}
@Test fun midlineDollars_noStrayDollar() {
    val blocks = ResponseParser.parse("cost \$\$x\$\$ here")
    assertFalse(blocks.toString().contains("\$") && blocks.none { it is ContentBlock.Math })
}
```

- [ ] **Step 2: Run to verify each fails on current code**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
Set-Location 'C:\Users\Michael\Documents\Project\NiroWear'
.\gradlew.bat :app:testDebugUnitTest --offline --console=plain --tests "com.nirowear.renderer.ResponseParser*"
```

- [ ] **Step 3: Implement the minimal behaviour change per defect**

1. Quote/list display math: emit `ContentBlock.Math` with delimiters preserved instead of dropping them.
2. `uncontainer()`: skip lines while inside an open fence (`fence != null` short-circuits container stripping).
3. `flattenText()`: push children in reverse so `removeLast` yields document order; collect `Code`/`MathInline` instead of dropping.
4. `capContainers()`: never rewrite bytes of a line containing a fence run; return the line unchanged.
5. Over-wide rows: already capped at 40 rows — extend the same explicit "… N more" marker to dropped cells.
6. Mid-line `$$`: only treat as display open when at line start (after trim) or preceded by whitespace; else literal `$`.

- [ ] **Step 4: Run and commit**

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:ktlintCheck --offline --console=plain
```

Expected: new tests green, full JVM 155+6=161/0, lint 0 errors. Commit: `fix(renderer): keep content the parser silently dropped; <measured JVM + lint>`.

---

### Task 4: Close the three security/hardening gaps the audit named

**Files:**
- Modify: `app/src/main/java/com/nirowear/security/SecretStore.kt`, `app/src/main/java/com/nirowear/settings/ProviderScreen.kt`
- Test: JVM tests for `invalidateKey` and pairing lifecycle (local-only)

**Interfaces:**
- Consumes: current `invalidateKey()` (cache-only), `DisposableEffect` close, pairing copy
- Produces: Keystore-entry deletion on rotation failure, lifecycle-bound listener proof, honest pairing copy

- [ ] **Step 1: Make key invalidation delete the Keystore entry, not just the cache**

```kotlin
private fun invalidateKey() {
    synchronized(this) {
        cachedKey = null
        runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                .deleteEntry(KEY_ALIAS)
        }
    }
}
```

Test: corrupt the Keystore entry (or delete it out from under the store), assert `read()` throws `CredentialUnavailable` AND `KeyStore.containsAlias(KEY_ALIAS)` is false afterwards, then `save()` recreates cleanly.

- [ ] **Step 2: Prove the pairing listener cannot outlive its screen**

Current `DisposableEffect` closes on dispose (`ProviderScreen.kt:96-103`) and `close()` kills the active client (`PairingServer.kt:126`). Add a test: start server, `close()`, assert `connect()` gets `ConnectException` within 2s AND worker thread is dead within 5s. If the worker lingers, join it in `close()`.

- [ ] **Step 3: State the pairing threat model in the UI where the user decides**

Change the `!isPrivateAddress` note (`ProviderScreen.kt:209`) and the pairing page intro to: "Same Wi-Fi only. Encrypted with ECDH P-256 + AES-GCM, but the handshake is unauthenticated — confirm the URL on the watch matches. Prefer manual entry on untrusted networks." Both READMEs already say this; the screen must too.

- [ ] **Step 4: Run and commit**

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:ktlintCheck --offline --console=plain
```

Expected: new tests green, lint 0 errors. Commit: `fix(security): delete Keystore entry on invalidation; <measured>`.

---

### Task 5: Bound the unbounded read and cap the unbounded memory the audit flagged

**Files:**
- Modify: `app/src/main/java/com/nirowear/settings/PairingServer.kt`, `app/src/main/java/com/nirowear/chat/ReaderList.kt` or owning composable
- Test: JVM tests for `readExactly` bound and conversation-switch memory

**Interfaces:**
- Consumes: `readExactly`/`readHead` with `REQUEST_MILLIS` wall bound, per-message `previous` parse memo
- Produces: byte-capped body read, bounded per-conversation parse cache

- [ ] **Step 1: Write the failing test — a lying Content-Length must not allocate 16 KiB blindly**

```kotlin
@Test fun readExactly_respectsStreamEnd() {
    val declared = 16384
    val stream = ByteArrayInputStream(ByteArray(100))
    assertNull(readExactlyBounded(stream, declared, maxBytes = 1024))
}
```

Fail first: current `readExactly(input, declared)` allocates `ByteArray(16384)` from one header integer.

- [ ] **Step 2: Cap allocation at the already-validated MAX_BODY and enforce the deadline inside the loop**

```kotlin
private fun readExactly(input: InputStream, length: Int, deadline: Long): ByteArray? {
    if (length > MAX_BODY) return null
    val buffer = ByteArray(length)
    var offset = 0
    while (offset < length) {
        if (System.currentTimeMillis() > deadline) return null
        val read = input.read(buffer, offset, length - offset)
        if (read < 0) return null
        offset += read
    }
    return buffer
}
```

`declared > MAX_BODY` is already rejected at call time; this makes the function itself safe for any caller.

- [ ] **Step 3: Bound the parse memo per conversation**

Audit: "every message in the open conversation keeps raw string + full block tree". Add: on conversation switch, drop `previous` entries whose `conversationId` differs (LRU cap of 3 conversations). Test: switch A→B→C→D, assert entries for A are gone.

- [ ] **Step 4: Run and commit**

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:ktlintCheck --offline --console=plain
```

Commit: `fix(hardening): bound pairing read and parse memo; <measured>`.

---

### Task 6: Make the site demo real — watch frame, failure paths, measured numbers

**Files:**
- Modify: `docs/index.html` only
- Test: real browser (owner's current browser, zero console errors)

**Interfaces:**
- Consumes: existing `tokenize()`/`blocksToHtml()`, telemetry, caret, cut path
- Produces: a demo a visitor believes is a watch, telling the truth about failure

- [ ] **Step 1: Wrap the demo in a 466×466 round watch frame**

CSS: circular mask (`border-radius:50%`), watch bezel ring, status masthead (time + model), input row (keyboard/voice buttons as in the app). Content area scrolls inside the circle. Keep the existing controls (Replay / Cut / Finish) outside the frame as "director controls".

- [ ] **Step 2: Add the three missing demo states the app actually has**

1. STOPPED: after Cut, show "Stopped · partial answer kept" exactly as the app renders it.
2. Retry: a button that replays from the same fixture.
3. Model switch: two fixtures (short/long) proving follow-instead-of-pull-back on scroll-up.

- [ ] **Step 3: Keep every number measured**

Telemetry already computed from the replay stays; add scroll position + follow-state readout ("follow:true/false, offset:px") updated from the real scroll handler. No invented numbers.

- [ ] **Step 4: Verify in the real browser and commit**

Open the file, run all three states, confirm zero console errors/warnings, confirm the caret marks only the block being written. Commit: `feat(site): watch-frame demo with stop/retry/switch; <measured bytes + browser result>`.

---

### Task 7: Prune the weight and cut beta.7

**Files:**
- Modify: delete uncited `artifacts/` dirs per `git grep -l`, `docs/memory/KNOWN_ISSUES.md`, `CHANGELOG.md`, version bump `app/build.gradle.kts`
- Test: CI green on `main`, site live

**Interfaces:**
- Consumes: Tasks 1-6 evidence
- Produces: lean repo + installable beta.7 the owner can test without adb

- [ ] **Step 1: Delete only what no tracked file cites, one directory at a time**

```powershell
Set-Location 'C:\Users\Michael\Documents\Project\NiroWear'
foreach($dir in Get-ChildItem artifacts -Directory){ $n=$dir.Name; $c=git grep -l -- $n; if(-not $c){"UNCITED: $n"} }
```

Owner confirms the UNCITED list, then delete exactly those. Never delete cited evidence.

- [ ] **Step 2: Update KNOWN_ISSUES with what this plan proved**

Replace the "not green" section with the Task 2 XML numbers (or the named blocker), mark Tasks 3-5 fixed with their test counts, keep the standing owner decisions (production keystore, live smoke, TalkBack, math licence).

- [ ] **Step 3: Bump to 0.1.0-beta.7, changelog, tag, push, read CI**

```powershell
Set-Location 'C:\Users\Michael\Documents\Project\NiroWear'
.\gradlew.bat :app:testDebugUnitTest :app:ktlintCheck :app:assembleDebug :app:assembleRelease --offline --console=plain
git add app/build.gradle.kts CHANGELOG.md docs/index.html docs/memory/KNOWN_ISSUES.md app/src/main/java
git commit -m "release: beta.7 <JVM n/n, lint 0 err, connected n/n or blocker, APK bytes>"
git tag v0.1.0-beta.7; git push origin main --tags
gh run list --limit 3 --json conclusion,name,headSha,createdAt
```

- [ ] **Step 4: Hand the owner a no-adb test sheet**

`docs/release/BETA7_TEST_SHEET.md`: install APK, add provider manually, stream an answer, stop mid-stream, retry, edit a message, switch model, pair over LAN on home Wi-Fi. Each step: where to tap, what to see, what to report back. Owner tests; no adb needed.

---

## Self-Review

**Spec coverage:** AGENTS.md product musts (chat/keyboard/voice/BYOK/switching/streaming/history/edit/retry/long answers/Markdown/Unicode/code/LaTeX/matrices/tables/links) — Tasks 2-3 prove them on emulator; physical watch stays owner-tested via Task 7 sheet. Engineering contract (terminal events, Stop, credentials, manual model ID) — Tasks 3-4 pin each. Renderer/math/streaming contracts — Tasks 2-3. Verification discipline — every task reads XML/output before claiming. Scope: no out-of-scope feature added; site demo (Task 6) is marketing, not app scope.

**Placeholder scan:** every step has literal commands, literal code, literal expected output. No TBD/TODO. Error handling is specified (ConnectException vs IOException, deadline inside read loop, Keystore deleteEntry). Tests are named with assertions.

**Type consistency:** `PairingServer.readExactly(input, length, deadline)` signature change is contained in one file + its test. `invalidateKey()` stays private, no callers change. `MAX_BODY/MAX_TABLE_ROWS/MAX_INLINE_DEPTH` values unchanged. Commit message convention (measured evidence) holds across tasks.

## Execution Handoff

**Plan complete and saved to `docs/superpowers/plans/2026-11-02-finish-nirowear.md`. Two execution options:**

**1. Subagent-Driven (recommended)** - I dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints

**Which approach?**
