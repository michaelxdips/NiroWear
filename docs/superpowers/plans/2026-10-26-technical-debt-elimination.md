# NiroWear Technical-Debt Elimination — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bring the NiroWear repository to a state where every gate is automated, every measured debt item is closed or explicitly accepted, and no claim in the docs outruns the evidence behind it.

**Architecture:** Seven phases, ordered by risk-of-regression rather than by tidiness. Phase 0 freezes a baseline so every later phase can be proven a no-op or an improvement. Phases 1–2 build the safety net (CI + static analysis) *before* any refactor, because refactoring a 539-line composable with no CI is how projects die. Phases 3–5 do the actual debt work. Phase 6 handles release hygiene. Phase 7 closes documentation drift.

**Tech Stack:** Kotlin 2.x, Compose for Wear OS Material3, Room 2.8.4, OkHttp 5.3.2, kotlinx-serialization, Gradle 9.6.0 (wrapper checksum pinned), AGP, JDK 17 target / JBR 21 toolchain.

## Global Constraints

- **minSdk 30, targetSdk 36, compileSdk 36.** Do not raise or lower without an explicit owner instruction.
- **No new runtime dependency** may be added without a one-line justification in the commit message and a licence entry under `docs/licenses/`. The project has a documented GPL-with-linking-exception dependency (JLaTeXMath) whose obligations are already discharged; do not add a second copyleft dependency.
- **`.gitignore` is an allowlist.** Any new file under `docs/` must be explicitly re-included or it silently will not publish. `docs/superpowers/plans/**` is now re-included for this reason.
- **Test sources are not published.** `app/src/test/**` and `app/src/androidTest/**` are gitignored by policy. Do not re-include them.
- **Never claim a build, test or device run succeeded without reading its output.** Quote literal numbers from result XML, never from memory. (AGENTS.md, verbatim.)
- **Instrumentation is destructive.** Before any `connectedAndroidTest`, prove the target is an emulator: `adb shell getprop ro.kernel.qemu` must print `1`.
- **Serialise Gradle.** Never run a second Gradle command while `connectedDebugAndroidTest` is in flight; both starve. The connected suite takes ~15–16 min.
- **The signing identity is `CN=Android Debug`.** Do not change it, and do not publish a non-prerelease build. Both are owner decisions, deferred on 2026-10-25.
- **Do not describe B3-03 as device-verified.** It is fixed and JVM-measured only.

---

## Baseline (measured 2026-10-26, not remembered)

| Metric | Value | Source |
|---|---|---|
| HEAD | `efcf8e5` (plan commit; `origin/main` matches), clean tree | `git log --oneline -1` |
| JVM tests | **124 / 0 fail / 24 suites**, XML `2026-10-26 17:03:50` | `app/build/test-results/testDebugUnitTest/` |
| Connected tests | **48 tests / 1 fail**, XML `2026-10-25T13:24:17` | `artifacts/b303-2026-10-25/connected-final.xml` |
| Lint | **0 errors / 20 issues** (2× InlinedApi, 10× GradleDependency, 7× NewerVersionAvailable, 1× AutoboxingStateCreation hint) — see correction note | `app/build/reports/lint-results-debug.xml` |
| Tracked files | 146 (68 app, 60 docs, 3 gradle, 3 fixtures, 12 root) | `git ls-files` |
| Repo on disk | **1,843 MB**, of which **1,635 MB is `artifacts/`** | directory sum |
| Lines >120 chars in main | **163** (max 340) | `artifacts/audit-2026-10-26/linelen.py` |
| `!!` non-null assertions in main | **12** across 5 files (ProviderScreen 5, MainActivity 3, ProviderSettings 2, DocumentBlock 1, ChatViewModel 1) | `findstr "!!"` |
| TODO/FIXME/HACK markers | **0** | `findstr` |
| Dead code | `ChatDao.rename` — declared, never called | `findstr rename` |
| CI | **none** (no `.github/`) | directory check |
| Formatter/linter config | **none** (no `.editorconfig`, no detekt/ktlint) | glob check |
| Largest file | `MainActivity.kt` 36.6 KB / 538 lines / one 390-line composable | line count |

### The honest scope of "no technical debt"

Zero debt is not reachable and claiming it would itself be a false claim. The defensible target is:

1. Every gate that *can* be automated, is.
2. Every measured debt item is either fixed or listed in `KNOWN_ISSUES.md` with a reason it was accepted.
3. No document claims something the evidence does not support.

That is what this plan delivers, and Phase 7 defines it as the exit criterion.

---

## Phase 0 — Freeze the baseline (no code change)

**Why first:** every later phase needs a "before" number to prove it did not regress. Without this, "we optimised it" is unfalsifiable.

### Task 0.1: Commit the outstanding site and plan work

**Files:**
- Modify: `.gitignore` (already edited: `docs/superpowers/plans/**` re-included)
- Add: `docs/superpowers/plans/2026-10-26-technical-debt-elimination.md`

- [ ] **Step 1: Confirm what is dirty**

```powershell
cd C:\Users\Michael\Documents\Project\NiroWear
git status --porcelain
```

Expected: `.gitignore` modified, the plan file untracked, `docs/index.html` and `docs/changelog.html` if the site fixes were not yet committed.

- [ ] **Step 2: Verify the plan file is publishable**

```powershell
git check-ignore -v docs/superpowers/plans/2026-10-26-technical-debt-elimination.md
```

Expected: exit code **1** (not ignored). If it prints a `.gitignore:` line, the re-include did not take and must be fixed before committing.

- [ ] **Step 3: Commit**

```powershell
git add .gitignore docs/superpowers/plans docs/index.html docs/changelog.html
git commit -F artifacts/audit-2026-10-26/commit-msg.txt
```

Write the message to a file first — PowerShell 5.1 has no heredoc and `git commit -F -` fails.

- [ ] **Step 4: Record the baseline as a machine-readable artifact**

```powershell
$f = Get-ChildItem app\build\test-results\testDebugUnitTest\TEST-*.xml
$t=0;$fa=0;$e=0;$s=0
foreach($x in $f){ $d=[xml](Get-Content $x.FullName -Raw)
  $t+=[int]$d.testsuite.tests; $fa+=[int]$d.testsuite.failures
  $e+=[int]$d.testsuite.errors; $s+=[int]$d.testsuite.skipped }
"suites=$($f.Count) tests=$t failures=$fa errors=$e skipped=$s"
```

Expected: `suites=24 tests=124 failures=0 errors=0 skipped=0`. Paste the literal output into `docs/memory/NEXT.md`, not a paraphrase.

---

## Phase 1 — Continuous integration (the missing safety net)

**Why second:** every subsequent phase touches production code. Doing that with no CI is how a green local build hides a broken one.

### Task 1.1: Add the JVM CI workflow

**Files:**
- Create: `.github/workflows/ci.yml`

**Interfaces:**
- Produces: a required check named `jvm` that later tasks rely on.

- [ ] **Step 1: Write the workflow**

```yaml
name: CI

on:
  push:
    branches: [main]
  pull_request:
  workflow_dispatch:

concurrency:
  group: ci-${{ github.ref }}
  cancel-in-progress: true

jobs:
  jvm:
    name: jvm
    runs-on: ubuntu-latest
    timeout-minutes: 30
    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'

      - name: Set up Android SDK
        uses: android-actions/setup-android@v3

      - name: Cache Gradle
        uses: actions/cache@v4
        with:
          path: |
            ~/.gradle/caches
            ~/.gradle/wrapper
          key: gradle-${{ runner.os }}-${{ hashFiles('**/*.gradle.kts', 'gradle/wrapper/gradle-wrapper.properties') }}
          restore-keys: gradle-${{ runner.os }}-

      - name: Unit tests
        run: ./gradlew :app:testDebugUnitTest --console=plain

      - name: Lint
        run: ./gradlew :app:lintDebug --console=plain

      - name: Assemble debug and release
        run: ./gradlew :app:assembleDebug :app:assembleRelease --console=plain

      - name: Publish test report
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: test-results
          path: app/build/test-results/testDebugUnitTest/*.xml
          if-no-files-found: error
```

- [ ] **Step 2: Verify the workflow is valid YAML before pushing**

```powershell
python -c "import yaml,sys; yaml.safe_load(open('.github/workflows/ci.yml')); print('yaml ok')"
```

Expected: `yaml ok`. If PyYAML is absent, use `python -c "import json"` fallback is **not** acceptable — install PyYAML or validate by eye against the schema. Do not push unvalidated YAML.

- [ ] **Step 3: Push and read the real run**

```powershell
git add .github/workflows/ci.yml
git commit -m "ci: add JVM test, lint and build workflow"
git push origin main
gh run list --limit 3
```

Expected: a run appears. Wait for it to finish, then:

```powershell
gh run view --log-failed
```

If it fails, fix the failure. **Do not** mark this task done because the YAML looks right.

- [ ] **Step 4: Record the first green run ID**

Paste the run URL into `docs/memory/NEXT.md`. A CI badge nobody has seen go green is decoration.

**Risk:** the project builds with AGP requiring a specific JDK and `compileSdk 36`, which may not be installed by `setup-android` by default. If `lintDebug` fails on a missing platform, add an explicit `sdkmanager --install "platforms;android-36" "build-tools;36.1.0"` step. Diagnose from the log, do not guess.

**Action versions (checked 2026-10-26):** every tag pinned above resolves (`v4` exists for all five).
Newer majors exist — checkout v7, setup-java v6, setup-android v4, cache v6, upload-artifact v7 — but
pinning the older majors is deliberate: they are the versions with the widest documented compatibility.
Do not bump them in the same commit as the workflow's first green run; upgrade separately so a failure
is attributable.

---

## Phase 2 — Static analysis and formatting (make style non-negotiable)

**Why third:** 163 lines over 120 chars and no formatter config means every future diff argues about style. Fix that once, mechanically, before refactoring.

### Task 2.1: Add `.editorconfig`

**Files:**
- Create: `.editorconfig`

- [ ] **Step 1: Write the config**

```ini
root = true

[*]
charset = utf-8
end_of_line = lf
insert_final_newline = true
trim_trailing_whitespace = true
indent_style = space
indent_size = 4

[*.{yml,yaml,json,md}]
indent_size = 2

[*.md]
trim_trailing_whitespace = false

[*.{kt,kts}]
max_line_length = 120
ij_kotlin_allow_trailing_comma = true
ij_kotlin_allow_trailing_comma_on_call_site = true
```

- [ ] **Step 2: Commit**

```powershell
git add .editorconfig
git commit -m "chore: add editorconfig with a 120-column Kotlin limit"
```

### Task 2.2: Add ktlint via the Gradle plugin, reporting-only first

**Files:**
- Modify: `build.gradle.kts` (root)
- Modify: `gradle/libs.versions.toml` if a catalog exists, otherwise inline

**Interfaces:**
- Produces: tasks `ktlintCheck` and `ktlintFormat`.

- [ ] **Step 1: Add the plugin**

In the root `build.gradle.kts`:

```kotlin
plugins {
    // existing plugins...
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0" apply false
}
```

In `app/build.gradle.kts`:

```kotlin
plugins {
    // existing plugins...
    id("org.jlleitschuh.gradle.ktlint")
}

ktlint {
    version.set("1.8.0")
    android.set(true)
    ignoreFailures.set(true)   // reporting-only for the first pass; flipped in Task 2.3
}
```

- [ ] **Step 2: Run it and record the baseline count**

```powershell
.\gradlew.bat :app:ktlintCheck --console=plain
```

Expected: many violations on the first run. **Record the exact count** in `docs/memory/NEXT.md`. This number is the debt Phase 3 pays down.

- [ ] **Step 3: Commit**

```powershell
git add build.gradle.kts app/build.gradle.kts
git commit -m "chore: add ktlint in reporting-only mode to measure style debt"
```

### Task 2.3: Format the tree, then flip to enforcing

- [ ] **Step 1: Auto-format**

```powershell
.\gradlew.bat :app:ktlintFormat --console=plain
```

- [ ] **Step 2: Prove behaviour did not change**

Formatting must be a no-op for behaviour. The full JVM suite is the proof:

```powershell
.\gradlew.bat :app:testDebugUnitTest --rerun-tasks --console=plain
```

Expected: **124 / 0 fail**. If any test fails after a pure-format commit, the formatter changed semantics — revert and investigate before proceeding.

- [ ] **Step 3: Flip to enforcing**

```kotlin
ktlint {
    version.set("1.8.0")
    android.set(true)
    ignoreFailures.set(false)
}
```

- [ ] **Step 4: Verify it now fails on a deliberate violation**

Temporarily add `val  x=1` to any main source file, run `:app:ktlintCheck`, confirm it fails, then revert.

This step exists because a linter that cannot fail is worse than no linter — the same lesson as `StreamingParseCoalescingTest`, which passed while detecting nothing.

- [ ] **Step 5: Commit and add to CI**

Add `- name: Style\n  run: ./gradlew :app:ktlintCheck --console=plain` to `.github/workflows/ci.yml` before the lint step.

```powershell
git add -A
git commit -m "style: apply ktlint across the tree and enforce it in CI"
```

**Risk:** ktlint may reformat the `PairingServer.kt` raw-string HTML block, which contains significant whitespace. If `PAIRING_PAGE` changes, `PairingServerTest` must still pass — it asserts page contents. If it breaks, add `@Suppress("ktlint:standard:max-line-length")` or exclude that file explicitly rather than hand-editing the HTML.

**Version note (verified 2026-10-26):** the plugin's latest release is **14.2.0** and 14.0.1+ explicitly
supports Gradle 9, which this project uses (wrapper 9.6.0). The ktlint *engine* latest is **1.8.0**.
Pinterest no longer maintains ktlint; the Maven coordinates moved from `com.pinterest.ktlint` to
`io.github.ktlint`. If the plugin's default engine resolution fails, pin the engine explicitly rather
than downgrading the plugin.

---

## Phase 3 — Close the one red gate

### Task 3.1: Fix the pairing test (root cause already proven)

**Files:**
- Modify: `app/src/androidTest/java/com/nirowear/settings/PairingUiTest.kt:158-165`

**Interfaces:**
- Consumes: the advertised URL parsed from the watch screen.
- Produces: nothing; this is a test-only change.

**Proven root cause (do not re-derive):** `PairingServer.kt:59` binds the advertised LAN address
(`10.0.2.15` on the emulator) deliberately, so the listener is unreachable on interfaces the user
never saw. The test rewrites the URL to `127.0.0.1` and gets `ConnectException`. The test's comment
claiming wildcard binding went stale in `39d734b`.

- [ ] **Step 1: Replace the loopback rewrite with the advertised host plus a negative control**

Current code (lines ~158–162):

```kotlin
assertNotNull("Pairing URL not shown on the watch screen", url)
// Loopback, because the listener binds the wildcard address; the phone uses the LAN one.
val loopback = url!!.replace(Regex("http://[0-9.]+:"), "http://127.0.0.1:")
val (pageCode, page) = http(loopback)
assertEquals(200, pageCode)
```

Replace with:

```kotlin
assertNotNull("Pairing URL not shown on the watch screen", url)
// The listener binds the advertised LAN address on purpose (PairingServer.kt), so dial exactly
// what the watch printed. Rewriting this to 127.0.0.1 makes the test dial an address nothing
// is listening on, which is what produced the historical ConnectException.
val advertised = url!!

// Negative control: the bound-address design means loopback must NOT answer. This keeps the
// security property under test instead of silently dropping it.
val loopback = advertised.replace(Regex("http://[0-9.]+:"), "http://127.0.0.1:")
val loopbackRefused = try { http(loopback); false } catch (_: java.io.IOException) { true }
assertTrue("listener must not answer on loopback", loopbackRefused)

val (pageCode, page) = http(advertised)
assertEquals(200, pageCode)
```

- [ ] **Step 2: Replace the remaining `loopback` references in the flow**

The handshake and pair calls further down also use `loopback`. Change them to `advertised`:

```kotlin
val (code, body) = http(advertised + "pair", encryptedPayload(http(advertised + "handshake").second,
```

and in the replay check:

```kotlin
val (replayCode, _) = http(advertised + "pair", encryptedPayload(http(advertised + "handshake").second,
```

- [ ] **Step 3: Prove the target is an emulator, then run the single test**

```powershell
adb shell getprop ro.kernel.qemu
```

Expected: `1`. If it prints nothing or `0`, **stop** — that is a physical device and instrumentation will destroy real data.

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat :app:connectedDebugAndroidTest --offline --console=plain `
  "-Pandroid.testInstrumentationRunnerArguments.class=com.nirowear.settings.PairingUiTest#keyArrivesFromPhoneWithoutTypingOnWatch"
```

Expected: BUILD SUCCESSFUL, 1 test, 0 failures.

- [ ] **Step 4: Run the full connected suite, alone**

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest --offline --console=plain
```

Expected: `tests="48" failures="0"`. Read it from the XML, not the console:

```powershell
Select-String -Path "app\build\outputs\androidTest-results\connected\debug\TEST-*.xml" -Pattern "<testsuites"
```

- [ ] **Step 5: Preserve the evidence and commit**

```powershell
[System.IO.File]::Copy((Resolve-Path "app\build\outputs\androidTest-results\connected\debug\TEST-NiroWear_Rebook_20261020(AVD) - 14.xml").Path,
  (Join-Path (Get-Location).Path "artifacts\debt-2026-10-26\connected-green.xml"), $true)
git add app/src/androidTest/java/com/nirowear/settings/PairingUiTest.kt
git commit -m "test: dial the advertised pairing host and assert loopback is refused"
```

Note: `app/src/androidTest` is gitignored by policy, so `git add` will not stage it. That is expected — the change lives on disk only. Record that fact in the commit message rather than pretending it was committed.

---

## Phase 4 — Structural debt: break up `MainActivity`

**Why this order:** CI and ktlint now protect the refactor. Doing this before them would be reckless.

### Task 4.1: Extract the reader list into its own composable file

**Files:**
- Create: `app/src/main/java/com/nirowear/chat/ReaderList.kt`
- Modify: `app/src/main/java/com/nirowear/MainActivity.kt`

**Interfaces:**
- Consumes: `List<Pair<Long, ContentBlock>>`, `List<StoredMessage>`, `LazyListState`, `blockHeights`
- Produces: `@Composable fun ReaderList(historyBlocks, storedMessages, listState, blockHeights, onLongPressEdit: (Long) -> Unit)`

- [ ] **Step 1: Write a failing characterisation test first**

Before moving code, pin its behaviour. Create `app/src/test/java/com/nirowear/chat/ReaderListContractTest.kt`:

```kotlin
package com.nirowear.chat

import com.nirowear.renderer.ContentBlock
import com.nirowear.renderer.InlineContent
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Characterisation test for the extraction in Task 4.1.
 *
 * The reader renders one entry per (messageId, block) pair, in order, and the flat list order is
 * what the anchor logic indexes into. If the extraction changes that order, anchors silently point
 * at the wrong block. This pins the contract before any code moves.
 */
class ReaderListContractTest {

    private fun blocks(vararg labels: String) =
        labels.map { ContentBlock.Heading(4, listOf(InlineContent(it))) }

    @Test fun flatOrderIsStableAndMessageGrouped() {
        val history = buildList {
            addAll(blocks("a1", "a2").map { 1L to it })
            addAll(blocks("b1").map { 2L to it })
        }
        assertEquals(listOf(1L, 1L, 2L), history.map { it.first })
        assertEquals("a1", (history[0].second as ContentBlock.Heading).content.first().text)
        assertEquals("a2", (history[1].second as ContentBlock.Heading).content.first().text)
        assertEquals("b1", (history[2].second as ContentBlock.Heading).content.first().text)
    }
}
```

- [ ] **Step 2: Run it and confirm it passes against current behaviour**

```powershell
.\gradlew.bat :app:testDebugUnitTest --offline --tests "*ReaderListContractTest" --console=plain
```

Expected: PASS. This test describes existing behaviour, so it must be green *before* the refactor.

- [ ] **Step 3: Move the list rendering into `ReaderList.kt`**

Cut the `itemsIndexed(renderedHistory, key = { index, _ -> historyKeys[index] })` block and the `ReaderAction` composable out of `MainActivity.kt` into `ReaderList.kt`, taking the parameters listed in **Interfaces**. Keep the body byte-identical apart from indentation; this is a move, not a rewrite.

- [ ] **Step 4: Prove the move is behaviour-preserving**

```powershell
.\gradlew.bat :app:testDebugUnitTest --rerun-tasks --console=plain
.\gradlew.bat :app:assembleDebug --console=plain
```

Expected: **124 / 0 fail** (plus the new characterisation test = 125), and BUILD SUCCESSFUL. Any test failure means the move changed something — revert and retry smaller.

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/java/com/nirowear/chat/ReaderList.kt app/src/main/java/com/nirowear/MainActivity.kt
git commit -m "refactor: extract reader list rendering from MainActivity"
```

### Task 4.2: Extract the streaming parse effect into a testable function

**Files:**
- Create: `app/src/main/java/com/nirowear/chat/StreamParse.kt`
- Modify: `app/src/main/java/com/nirowear/MainActivity.kt`

**Interfaces:**
- Produces: `internal fun needsParse(messages: List<StoredMessage>, conversationId: String?, previous: Map<StoredMessage, List<Pair<Long, ContentBlock>>>): Boolean`
- Produces: `internal fun coalesceDelayMs(nowMs: Long, lastParseAtMs: Long, windowMs: Long): Long` returning `0` when no wait is needed.

- [ ] **Step 1: Write failing tests for the pure logic**

Create `app/src/test/java/com/nirowear/chat/StreamParseTest.kt`:

```kotlin
package com.nirowear.chat

import com.nirowear.data.StoredMessage
import com.nirowear.renderer.ContentBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B3-03 coalescing logic, extracted so it can be tested without Compose or a device.
 *
 * The rule that matters: a throttle, never a debounce. A debounce returns a positive delay for
 * every arriving delta and therefore starves under a fast stream (measured: 0 parses over 300 ms
 * at a 4 ms cadence). coalesceDelayMs must return 0 once the window has elapsed.
 */
class StreamParseTest {

    private fun msg(id: Long, content: String) = StoredMessage(
        id = id, conversationId = "c", role = "ASSISTANT", rawContent = content,
        status = "STREAMING", providerProfileId = "p", providerLabel = "l", modelId = "m", error = null,
    )

    @Test fun needsParseWhenARowIsAbsentFromTheMemo() {
        val messages = listOf(msg(1, "a"))
        assertTrue(needsParse(messages, "c", emptyMap()))
    }

    @Test fun doesNotNeedParseWhenEveryRowIsMemoised() {
        val m = msg(1, "a")
        val memo = mapOf<StoredMessage, List<Pair<Long, ContentBlock>>>(m to emptyList())
        assertFalse(needsParse(listOf(m), "c", memo))
    }

    @Test fun ignoresRowsFromOtherConversations() {
        val other = msg(1, "a").copy(conversationId = "other")
        assertFalse(needsParse(listOf(other), "c", emptyMap()))
    }

    @Test fun noWaitWhenTheWindowHasAlreadyElapsed() {
        assertEquals(0L, coalesceDelayMs(nowMs = 1_000L, lastParseAtMs = 0L, windowMs = 16L))
    }

    @Test fun waitsTheRemainderInsideTheWindow() {
        assertEquals(10L, coalesceDelayMs(nowMs = 1_006L, lastParseAtMs = 1_000L, windowMs = 16L))
    }

    @Test fun neverReturnsNegative() {
        assertEquals(0L, coalesceDelayMs(nowMs = 900L, lastParseAtMs = 1_000L, windowMs = 16L))
    }
}
```

- [ ] **Step 2: Run and confirm they fail**

```powershell
.\gradlew.bat :app:testDebugUnitTest --offline --tests "*StreamParseTest" --console=plain
```

Expected: FAIL — `unresolved reference: needsParse` / `coalesceDelayMs`.

- [ ] **Step 3: Implement in `StreamParse.kt`**

```kotlin
package com.nirowear.chat

import com.nirowear.data.StoredMessage
import com.nirowear.renderer.ContentBlock

/**
 * B3-03: coalescing decisions, extracted from the Compose effect so they can be tested on the JVM.
 *
 * The renderer only ever needs the LATEST accumulated text, so intermediate snapshots are waste.
 * But the wait must be a throttle, not a debounce: a debounce starves under a fast stream because
 * every arrival cancels the pending run before it parses.
 */
internal fun needsParse(
    messages: List<StoredMessage>,
    conversationId: String?,
    previous: Map<StoredMessage, List<Pair<Long, ContentBlock>>>,
): Boolean = messages.any { it.conversationId == conversationId && previous[it] == null }

/** Milliseconds to wait before parsing. 0 means "parse now". */
internal fun coalesceDelayMs(nowMs: Long, lastParseAtMs: Long, windowMs: Long): Long {
    val elapsed = nowMs - lastParseAtMs
    return if (elapsed >= windowMs) 0L else (windowMs - elapsed).coerceAtLeast(0L)
}
```

- [ ] **Step 4: Rewire `MainActivity` to call them**

```kotlin
if (needsParse(messages, id, previous)) {
    val wait = coalesceDelayMs(System.currentTimeMillis(), lastParseAt, STREAM_PARSE_COALESCE_MS)
    if (wait > 0) kotlinx.coroutines.delay(wait)
}
```

- [ ] **Step 5: Run the full suite**

```powershell
.\gradlew.bat :app:testDebugUnitTest --rerun-tasks --console=plain
```

Expected: **131 / 0 fail** (124 + 1 characterisation + 6 new). If the number differs, count from the XML and explain the delta rather than asserting a total.

- [ ] **Step 6: Commit**

```powershell
git add app/src/main/java/com/nirowear/chat/StreamParse.kt app/src/main/java/com/nirowear/MainActivity.kt
git commit -m "refactor: extract B3-03 coalescing decisions into testable functions"
```

---

## Phase 5 — Correctness and dead-code cleanup

### Task 5.1: Delete the dead DAO method

**Files:**
- Modify: `app/src/main/java/com/nirowear/data/ChatDatabase.kt:58`

- [ ] **Step 1: Prove it is unused**

```powershell
cmd /c findstr /s /n /c:"rename" app\src\main\java\com\nirowear\data\*.kt
```

Expected: exactly one hit — the declaration itself. Any call site means it is not dead; stop.

- [ ] **Step 2: Delete the line and rebuild**

```powershell
.\gradlew.bat :app:assembleDebug --console=plain
```

Expected: BUILD SUCCESSFUL. Room regenerates the DAO implementation; a compile error here would mean the method was used reflectively, which Room does not do.

- [ ] **Step 3: Commit**

```powershell
git add app/src/main/java/com/nirowear/data/ChatDatabase.kt
git commit -m "chore: remove unused ChatDao.rename"
```

### Task 5.2: Replace the `!!` assertions with safe access

**Files:**
- Modify: `app/src/main/java/com/nirowear/MainActivity.kt:311, 428, 448`
- Modify: `app/src/main/java/com/nirowear/settings/ProviderScreen.kt:143, 202`
- Modify: `app/src/main/java/com/nirowear/data/ProviderSettings.kt:58, 66`

- [ ] **Step 1: Handle each site by its actual invariant, not by a blanket change**

`MainActivity.kt:428` and `ProviderScreen.kt:143` follow the shape `if (x != null) Text(x!!)` — replace with a local val:

```kotlin
chatState.error?.let { msg -> item { Text(msg, fontSize = 14.sp) } }
```

`MainActivity.kt:311` is `if (editedId != null) { ... chat.edit(editedId!!, draft) }` — replace with:

```kotlin
val editing = editedId
if (editing != null) { anchors.remove(chatState.conversationId); chat.edit(editing, draft) } else chat.send(draft)
```

`ProviderSettings.kt:58` (`previous!!`) and `:66` (`validateReplacement!!`) are inside `if (previous != null)` guards. Restructure so the compiler can see it:

```kotlin
val prev = previous ?: error("unreachable: guarded by caller")
val ref = if (newSecret == null) prev.credentialRef else UUID.randomUUID().toString()
```

Use `error(...)` only where the invariant is genuinely guaranteed by an enclosing branch and a comment explains it. **Do not** replace `!!` with `?: return` where the null case must not be silently skipped.

- [ ] **Step 2: Prove behaviour is unchanged**

```powershell
.\gradlew.bat :app:testDebugUnitTest --rerun-tasks --console=plain
```

Expected: same count as after Task 4.2, 0 failures.

- [ ] **Step 3: Confirm the count dropped**

```powershell
cmd /c findstr /s /n /c:"!!" app\src\main\java\*.kt
```

Record the before/after counts. Some `!!` may legitimately remain — for example `ChatViewModel.kt:201`'s `currentCoroutineContext()[Job]!!`, where the Job always exists inside a coroutine. Leave those and note why.

- [ ] **Step 4: Commit**

```powershell
git add -A app/src/main/java
git commit -m "refactor: replace unsafe !! assertions with checked access"
```

### Task 5.3: Silence the InlinedApi warnings honestly

**Files:**
- Modify: `app/src/main/java/com/nirowear/MainActivity.kt:439-441`

**Verified fact:** `EXTRA_IS_SENSITIVE` is `public static final String = "android.content.extra.IS_SENSITIVE"` — a compile-time constant, inlined to a literal. It cannot throw on API 30. The warning is cosmetic.

- [ ] **Step 1: Add a targeted annotation with the reason**

```kotlin
@Suppress("InlinedApi") // compile-time String constant, inlined to a literal; safe on minSdk 30
private fun markClipSensitive(clip: android.content.ClipData) {
    clip.description.extras = android.os.PersistableBundle().apply {
        putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
    }
}
```

Call it from the button. This also shortens the 340-char line at the worst offender.

- [ ] **Step 2: Verify the warning count dropped**

```powershell
.\gradlew.bat :app:lintDebug --console=plain
$d=[xml](Get-Content app\build\reports\lint-results-debug.xml -Raw); $d.issues.issue | Group-Object id | ForEach-Object { "$($_.Name): $($_.Count)" }
```

Expected: `InlinedApi` gone; `GradleDependency` ×2 remain (compileSdk 37 and test runner 1.5.2 are informational; do not chase them without an owner decision on SDK 37).

- [ ] **Step 3: Commit**

```powershell
git add app/src/main/java/com/nirowear/MainActivity.kt
git commit -m "fix: annotate the API-33 clipboard constant as the inlined literal it is"
```

---

## Phase 6 — Repository hygiene

### Task 6.1: Prune the evidence directory, keeping the trail

**Files:**
- Create: `artifacts/README.md`
- Delete: selected subdirectories under `artifacts/`

**Measured:** 1,635 MB of `artifacts/`, untracked, from campaigns between 2026-10-03 and 2026-10-25.

- [ ] **Step 1: Inventory before deleting anything**

```powershell
Get-ChildItem artifacts -Directory | ForEach-Object {
  $s = (Get-ChildItem $_.FullName -Recurse -File -ErrorAction SilentlyContinue | Measure-Object Length -Sum).Sum
  "{0,9:N1} MB  {1}" -f ($s/1MB), $_.Name
} | Sort-Object -Descending
```

- [ ] **Step 2: Write `artifacts/README.md` naming what each surviving directory proves**

```markdown
# Artifacts

Local evidence only. `artifacts/` is gitignored; nothing here is published.

Retention rule: keep what a current claim depends on, delete what nothing cites.
Before deleting a directory, grep the docs for its name.

| Directory | What it proves | Keep? |
|---|---|---|
| `beta6-2026-10-22/` | the shipped beta.6 APK identity | yes |
| `b303-2026-10-25/` | B3-03 fix and the connected baseline | yes |
| `debt-2026-10-26/` | this campaign | yes |
| `audit-2026-10-26/` | the audit scripts behind the plan | yes |
| everything else | superseded campaign evidence | delete after grep |
```

- [ ] **Step 3: Delete only after grepping**

```powershell
cmd /c findstr /s /i /m /c:"beta3-final-reconciliation" docs\*.md docs\**\*.md
```

If nothing references a directory, it is safe to remove. Use one literal path per `rm` call — the shell's safety policy rejects computed paths.

- [ ] **Step 4: Confirm the size drop and record it**

```powershell
"{0:N1} MB" -f ((Get-ChildItem -Recurse -Force -ErrorAction SilentlyContinue | Measure-Object Length -Sum).Sum / 1MB)
```

### Task 6.2: Add a `.gitattributes` to stop line-ending churn

**Files:**
- Create: `.gitattributes`

**Why:** every commit currently warns `LF will be replaced by CRLF`. That is noise that hides real diffs.

- [ ] **Step 1: Write it**

```gitattributes
* text=auto eol=lf

*.bat text eol=crlf
*.ps1 text eol=crlf
*.png binary
*.apk binary
*.jar binary
*.keystore binary
```

- [ ] **Step 2: Renormalise once, in an isolated commit**

```powershell
git add --renormalize .
git status --porcelain
```

If this touches hundreds of files, that is expected once. Commit it alone so it never pollutes a code review.

```powershell
git commit -m "chore: normalise line endings with gitattributes"
```

---

## Phase 7 — Documentation truth pass and the exit criterion

### Task 7.1: Reconcile every claim with its evidence

**Files:**
- Modify: `docs/memory/KNOWN_ISSUES.md`
- Modify: `docs/release/CHANGELOG.md`
- Modify: `README.md`

- [ ] **Step 1: Build the claim list mechanically**

```powershell
cmd /c findstr /s /i /n /c:"verified" /c:"PASS" /c:"passes" docs\memory\ARCHITECTURE.md docs\memory\SCOPE.md README.md docs\release\CHANGELOG.md
```

For each hit, ask: does an artifact in `artifacts/` or a result XML support it? If not, either produce the evidence or downgrade the wording to "not verified".

- [ ] **Step 2: Record accepted debt**

Add to `docs/memory/KNOWN_ISSUES.md`, at the top (not buried):

```markdown
## Accepted debt — 2026-10-26

Each item is deliberately not fixed. The reason is the point.

- `compileSdk 36` with lint noting 37 is available: upgrading changes the target platform and
  needs a full acceptance pass. Owner decision, not a code task.
- `!!` in `ChatViewModel.kt:201` (`currentCoroutineContext()[Job]!!`): the Job always exists inside
  a coroutine; the assertion documents an invariant.
- Connected tests need a device: they cannot run in CI on a standard runner without an emulator
  action. JVM tests are the CI gate; connected is a local pre-release gate.
- No physical Xiaomi Watch 2 in the loop: every acceptance claim remains emulator-scope.
```

- [ ] **Step 3: Commit**

```powershell
git add docs/memory/KNOWN_ISSUES.md docs/release/CHANGELOG.md README.md
git commit -m "docs: reconcile claims with evidence and record accepted debt"
```

### Task 7.2: Define and verify the exit criterion

The campaign is done when **all** of these are literally true:

- [ ] `.github/workflows/ci.yml` exists and its latest run on `main` is green (`gh run list --limit 1`).
- [ ] `ktlintCheck` is enforcing (`ignoreFailures = false`) and passes.
- [ ] JVM tests pass with a count read from XML, and that count is ≥ the baseline 124.
- [ ] Connected tests: `tests="48" failures="0"`, read from XML.
- [ ] Lint: `0 errors`, and every remaining warning is either a dependency-freshness advisory or one of the two documented `InlinedApi` cosmetic ones. (Amended 2026-10-26: the original wording — "only the two `GradleDependency` advisories" — was based on a 4-warning baseline that does not reproduce; a real run reports 17 freshness advisories.)
- [ ] `cmd /c findstr /s /c:"TODO" app\src\main\java\*.kt` returns nothing.
- [ ] `ChatDao.rename` no longer exists.
- [ ] `KNOWN_ISSUES.md` lists every remaining item with a reason it was accepted.
- [ ] No document claims a stable channel exists while `releases/latest` returns 404.
- [ ] No document describes B3-03 as device-verified.

- [ ] **Step 1: Run every check above and paste the literal outputs into `docs/memory/NEXT.md`**

Not a summary. The literal lines. A claim with no pasted output is a rumour.

- [ ] **Step 2: State plainly what is still not done**

Write one paragraph in `NEXT.md` naming: physical-device verification, live provider smoke, production keystore, and any item accepted in Task 7.1. Zero technical debt in the codebase does not mean zero open risk in the product, and conflating them would be the exact failure this plan exists to prevent.

---

## Order of work and rationale

| Phase | Why here |
|---|---|
| 0 Baseline | without a "before", no later claim is falsifiable |
| 1 CI | the safety net must exist before the refactor, not after |
| 2 Style | mechanical, whole-tree, best done before hand-editing 500-line files |
| 3 Red gate | small, root-caused, gets the connected suite green before structural work |
| 4 Structure | the big refactor, now protected by CI + ktlint + characterisation tests |
| 5 Cleanup | dead code and `!!`, low risk, high signal |
| 6 Hygiene | disk and line endings, zero behaviour risk |
| 7 Truth pass | cannot be done earlier; the numbers do not exist until the work is finished |

## What this plan deliberately does not do

- **Does not chase compileSdk 37.** Platform upgrade needs its own acceptance pass.
- **Does not add a second copyleft dependency.** JLaTeXMath's obligations are discharged; do not reopen that.
- **Does not change the signing identity or publish a stable release.** Owner decision, deferred.
- **Does not run connected tests in CI.** Emulator-in-CI is possible but slow and flaky; it stays a local gate with the reason recorded.
- **Does not claim zero debt.** It claims every item is fixed or explicitly accepted, which is checkable.
