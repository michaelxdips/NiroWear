# NiroWear closeout: purge, signed beta.7, live smoke, stable channel

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Finish everything after the beta.7 merge: delete only uncited weight, publish a signed beta.7, run live provider smoke on owner-typed keys, then cut a stable channel.

**Architecture:** No architecture change. Safest first: filesystem purge of ignored weight, then signed publish from the existing versionCode 8 tree, then network smoke against real adapters, then a stable tag that tells the truth about remaining gates.

**Tech Stack:** Kotlin, Compose for Wear OS, Room, OkHttp, kotlinx.serialization, Android Keystore, Gradle Kotlin DSL. Release surface: NiroWear-Beta repo (APKs + user docs + `index.html` site + `check_public.py` hygiene gate).

## Global Constraints

- Reference viewport: round 466 × 466 px. No oversized bubbles or avatars.
- Never claim a build, test, emulator, or device run succeeded unless actually executed and output read.
- Never shorten an answer because the screen is small; use layout, scrolling, typography, content transformation.
- EOF alone is not success — detect the protocol's terminal event.
- Credentials: never log keys; versioned AES-GCM only; no plaintext fallback; never repopulate a saved key into an editing field; backup disabled.
- Compatible providers accept a manually typed model ID even when discovery fails. Never silently change a model.
- Test sources and prompts stay gitignored; publishing rule is allowlist `.gitignore` — `git add -A` must never leak them.
- Owner tests on their own hardware; no physical Xiaomi Watch 2 in the loop — every claim stays emulator-scope, owner hardware via `docs/release/BETA7_TEST_SHEET.md`.
- PowerShell 5.1: no `&&`, single-quoted literals, explicit UTF-8 IO, check `$LASTEXITCODE`.
- Byte-level check is the only trustworthy encoding check, never `Get-Content` rendering.
- Preserve CRLF/LF. Minimal diffs. stdlib/native first.
- Never commit a keystore, `local.properties`, or `secrets.properties`. Never change the signing identity to manufacture a release.
- One destructive filesystem delete per command, literal path only, owner confirms the list first.

---

### Task 1: Purge uncited weight (safest first)

**Files:**
- Delete: uncited directories under `C:\Users\Michael\Documents\Project\NiroWear\artifacts\` and uncited loose files in its root (filesystem only; `artifacts/` is gitignored, nothing to `git rm`).
- Delete: `C:\Users\Michael\Documents\Project\NiroWear\artifactsbeta3-uiux-2026-10-18before\` if uncited (filesystem only).
- Modify: none.
- Test: `git status --porcelain` clean of tracked changes; cited files still byte-present; `check_public.py`-style allowlist unaffected (source repo has no such gate; beta repo untouched).

**Interfaces:**
- Consumes: Task 2 report baseline in `docs/superpowers/plans/2026-11-02-finish-nirowear.md` (nothing to re-prove).
- Produces: measured freed bytes, surviving cited set with byte counts, clean `git status`.

- [ ] **Step 1: Enumerate every artifacts child with size and citation state**

Run (no `Set-Location`; full paths only):

```powershell
$ErrorActionPreference = 'Stop'
$root = 'C:\Users\Michael\Documents\Project\NiroWear'
$art = Join-Path $root 'artifacts'
foreach ($d in (Get-ChildItem -LiteralPath $art -Directory)) {
  $size = (Get-ChildItem -LiteralPath $d.FullName -Recurse -File -ErrorAction SilentlyContinue | Measure-Object -Property Length -Sum).Sum
  $hits = git -C $root grep -l -- $d.Name 2>$null
  if ($hits) { 'CITED: {0} ({1:N1} MB)' -f $d.Name, ($size/1MB) } else { 'UNCITED: {0} ({1:N1} MB)' -f $d.Name, ($size/1MB) }
}
if ($LASTEXITCODE -gt 1) { throw "git grep failed: $LASTEXITCODE" }
```

Expected: each of the ~40 directories prints exactly one line. Known CITED (do not delete): `software-rc-2026-10-09` (cited by `THIRD_PARTY_NOTICES.md:43,45` and `docs/licenses/evidence.json`), `final-ship-2026-10-10` (cited by `docs/screenshots/provenance.json`). `docs/memory/SCOPE.md:76` cites `artifacts/canvas` as a generic product-scope example, not a directory — it cites no directory on disk.

- [ ] **Step 2: Enumerate loose files in artifacts root with citation state**

```powershell
$ErrorActionPreference = 'Stop'
$root = 'C:\Users\Michael\Documents\Project\NiroWear'
$art = Join-Path $root 'artifacts'
foreach ($f in (Get-ChildItem -LiteralPath $art -File)) {
  $hits = git -C $root grep -l -- $f.Name 2>$null
  if ($hits) { 'CITED-FILE: {0} ({1:N0} B)' -f $f.Name, $f.Length } else { 'UNCITED-FILE: {0} ({1:N0} B)' -f $f.Name, $f.Length }
}
```

Expected: root PNGs (`milestone*.png`, `renderlab-*.png`, `wear-current.png`, etc.), `gateD-connected.txt`, `streaming-diag-logcat.txt`, `window.xml`, `app-release-validation.apk*` all print UNCITED-FILE. The cited APK path is the nested `artifacts/software-rc-2026-10-09/ultimate-audit/final-release/app-release-validation.apk`, not the loose root copy — the root copy is still UNCITED unless this step proves otherwise.

- [ ] **Step 3: Check the stray root sibling for citations**

```powershell
$ErrorActionPreference = 'Stop'
$root = 'C:\Users\Michael\Documents\Project\NiroWear'
git -C $root grep -l -- 'artifactsbeta3-uiux-2026-10-18before'
echo "exit=$LASTEXITCODE"
Get-ChildItem -LiteralPath (Join-Path $root 'artifactsbeta3-uiux-2026-10-18before') -Force | Select-Object -First 5 Name, Length
```

Expected: `git grep` prints nothing with exit 1 (uncited), and the directory lists ~0 entries (measured 0 MB). If either differs, stop and report instead of deleting.

- [ ] **Step 4: Owner confirms the UNCITED list, then delete exactly those — one literal path per command**

Owner pastes which UNCITED lines to delete. Then, for each confirmed name, run exactly one command per directory (never a loop-delete, never a wildcard):

```powershell
$ErrorActionPreference = 'Stop'
Remove-Item -LiteralPath 'C:\Users\Michael\Documents\Project\NiroWear\artifacts\<confirmed-name>' -Recurse -Force
```

Repeat per confirmed directory; same shape for each confirmed loose file (without `-Recurse`) and for the stray sibling. Never touch the two CITED directories or any file inside them.

- [ ] **Step 5: Prove the purge — cited bytes survive, tree clean, freed space measured**

```powershell
$ErrorActionPreference = 'Stop'
$root = 'C:\Users\Michael\Documents\Project\NiroWear'
git -C $root status --porcelain
echo '---CITED STILL PRESENT---'
Get-Item -LiteralPath (Join-Path $root 'artifacts\software-rc-2026-10-09\ultimate-audit\final-release\app-release-validation.apk') | Select-Object Name, Length
Get-Item -LiteralPath (Join-Path $root 'artifacts\software-rc-2026-10-09\autonomous-closure\release-handoff\release-dependencies.txt') | Select-Object Name, Length
Get-ChildItem -LiteralPath (Join-Path $root 'artifacts\final-ship-2026-10-10') -Recurse -File | Measure-Object -Property Length -Sum | Select-Object Count, Sum
echo '---REMAINING SIZE---'
(Get-ChildItem -LiteralPath (Join-Path $root 'artifacts') -Recurse -File -ErrorAction SilentlyContinue | Measure-Object -Property Length -Sum).Sum
```

Expected: `git status --porcelain` prints nothing tracked (purge is ignored-weight only); the two cited files exist with nonzero bytes; remaining sum is small (cited set only, down from 1634.5 MB). Commit: none (ignored files do not commit).

---

### Task 2: Signed beta.7 publish to NiroWear-Beta

**Files:**
- Modify (beta repo `C:\Users\Michael\Documents\Project\NiroWear-Beta`): `CHANGELOG.md` (beta.7 entry: versionCode 8, measured APK bytes, signing identity fingerprint), `index.html` (beta.7 download link + bytes), release APK asset via GitHub release.
- Modify (source repo): nothing unless the build exposes a defect; version stays `versionCode = 8`, `versionName = "0.1.0-beta.7"` in `app/build.gradle.kts:34-35`.
- Test: `apksigner verify --print-certs`, `gh release view`, Pages `built` status, `check_public.py` PASS on the beta tree.

**Interfaces:**
- Consumes: Task 1 clean tree; beta.7 changelog content in `docs/release/CHANGELOG.md` (source repo).
- Produces: signed `v0.1.0-beta.7` GitHub release on NiroWear-Beta, updated site, green hygiene gate, measured APK bytes + certificate SHA-256.

- [ ] **Step 1: Gate — owner provides the production signing decision before any build**

Ask the owner exactly one question and do not proceed without the answer: production keystore path (or confirmation to reuse the established beta signing identity) plus explicit consent to sign. Never invent a keystore, never generate a new key to "fix" identity, never commit `*.keystore/*.jks/local.properties/secrets.properties` (all gitignored in both repos). Record the decision (path redacted to basename, consent timestamp) in the task log, not in the repo.

- [ ] **Step 2: Build the signed release from the clean tree and record bytes**

```powershell
$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
& 'C:\Users\Michael\Documents\Project\NiroWear\gradlew.bat' -p 'C:\Users\Michael\Documents\Project\NiroWear' :app:assembleRelease --offline --console=plain
if ($LASTEXITCODE -ne 0) { throw "assembleRelease failed: $LASTEXITCODE" }
Get-Item -LiteralPath 'C:\Users\Michael\Documents\Project\NiroWear\app\build\outputs\apk\release\app-release.apk' | Select-Object Name, Length
```

Expected: `BUILD SUCCESSFUL`, one APK path with `Length` near 3582786 B (unsigned beta.7 measured that; signed differs by signature block — report the actual number, never copy the old one).

- [ ] **Step 3: Verify the signing identity matches the established beta lineage**

```powershell
$ErrorActionPreference = 'Stop'
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
& "$sdk\build-tools\36.0.0\apksigner.bat" verify --print-certs 'C:\Users\Michael\Documents\Project\NiroWear\app\build\outputs\apk\release\app-release.apk'
```

Expected: `Verified using v1/v2/v3 scheme`, one certificate SHA-256. Compare against the lineage fingerprint recorded for earlier betas; on mismatch STOP — do not publish, do not rotate, report to owner. (Reference: validation-candidate debug cert was `7d4e6e9…`; beta lineage fingerprint is whatever the last published beta used — read it from the previous release notes, never assume.)

- [ ] **Step 4: Create the beta.7 GitHub release with the measured numbers**

```powershell
$ErrorActionPreference = 'Stop'
gh release create v0.1.0-beta.7 'C:\Users\Michael\Documents\Project\NiroWear\app\build\outputs\apk\release\app-release.apk#NiroWear-beta.7.apk' --repo michaelxdips/NiroWear-Beta --title '0.1.0-beta.7' --notes 'Beta.7: renderer keeps dropped content, security hardening, bounded reads, watch-frame demo. JVM 176/176, lint 0 err, connected 48/48 (Task 2 XML ts=2026-11-02T06:58:50). Install: uninstall a differently-signed beta first if install fails.'
```

Expected: release URL printed. Verify with `gh release view v0.1.0-beta.7 --repo michaelxdips/NiroWear-Beta --json tagName,assets`.

- [ ] **Step 5: Point the beta site and changelog at beta.7, keep the hygiene gate green**

Edit in the beta repo: `CHANGELOG.md` gains the beta.7 section (copy from source `docs/release/CHANGELOG.md` beta.7 entry plus measured signed bytes + cert fingerprint); `index.html` download anchor points at the new release asset with the measured byte count. Then:

```powershell
$ErrorActionPreference = 'Stop'
python 'C:\Users\Michael\Documents\Project\NiroWear-Beta\.github\check_public.py'
if ($LASTEXITCODE -ne 0) { throw "hygiene gate red: $LASTEXITCODE" }
git -C 'C:\Users\Michael\Documents\Project\NiroWear-Beta' add CHANGELOG.md index.html
git -C 'C:\Users\Michael\Documents\Project\NiroWear-Beta' commit -m 'release: beta.7 signed <APK bytes + cert fp short>'
git -C 'C:\Users\Michael\Documents\Project\NiroWear-Beta' push origin main
if ($LASTEXITCODE -ne 0) { throw "push failed: $LASTEXITCODE" }
gh api repos/michaelxdips/NiroWear-Beta/pages --jq '{status, html_url}'
```

Expected: `PASS: 54 exact allowlisted files` (or the current pinned count if the allowlist grew — report actual), commit hash printed, Pages `status: built`.

---

### Task 3: Live provider smoke on owner-typed keys

**Files:**
- Create (local-only, gitignored, never committed): one host script, e.g. `C:\Users\Michael\Documents\Project\NiroWear\.private\live-smoke.ps1` (ignored via `.private/`).
- Modify: none in `app/src/main`. No test sources committed.
- Test: one short streamed prompt per configured adapter on the emulator AVD; assertions on terminal events and error mapping only — key material never appears in output, files, URLs, or logcat captures.

**Interfaces:**
- Consumes: Task 2 signed APK installed on a credential-free AVD (`NiroWear_Rebook_20261020` or fresh; never `NiroWear_466` / `NiroWear_Harmony_Disposable` which hold protected data).
- Produces: per-adapter table (streamed bytes, terminal event observed, error string on failure), plus a `no-key-in-logs` proof line.

- [ ] **Step 1: Owner types keys on-device; agent verifies presence without reading values**

Owner opens NiroWear → Menu → Providers → Add on the emulator and types each provider key manually (or pairs over LAN on home Wi-Fi per `BETA7_TEST_SHEET.md` step 7). The agent then checks only metadata, never values:

```powershell
$ErrorActionPreference = 'Stop'
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb -s emulator-5554 shell dumpsys notification | Select-Object -First 3
```

plus in-app: Providers list shows `Configured` per profile. If a profile does not show `Configured`, stop — owner retypes, agent does not paste keys from chat into files.

- [ ] **Step 2: Run one short prompt per adapter and capture only framing evidence**

For each configured provider (OpenAI Responses, Anthropic Messages, Gemini GenerateContent, OpenAI-compatible Chat Completions): send the same short prompt (`Reply with exactly: smoke-ok`), wait for the terminal event, record streamed character count and completion state. Capture with `adb logcat -d` filtered to the app tag, then immediately strip to counters (no bodies archived). Failing fast is fine — a precise error is a passing measurement for the error-mapping half.

- [ ] **Step 3: Assert the four terminal events and the two error strings**

Pass criteria, all four required: (a) each adapter reaches its protocol terminal event (OpenAI `response.completed`, Anthropic `message_stop`, Gemini `finishReason`, compatible `data: [DONE]`/stop reason) — EOF alone is not success; (b) a rejected key surfaces exactly `API key rejected`; (c) an unknown model surfaces exactly `Model unavailable`; (d) partial content remains visible after Stop with `Stopped · partial answer kept`. Report a 4-row table with `adapter | terminal event seen | chars | error string`. Any red row names the adapter + event + observed string, never the key.

- [ ] **Step 4: Prove no key material leaked into logs or artifacts**

```powershell
$ErrorActionPreference = 'Stop'
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb -s emulator-5554 logcat -d | Select-String -Pattern 'sk-|x-api-key|Authorization|Bearer [A-Za-z0-9]' | Measure-Object | Select-Object Count
```

Expected: `Count: 0`. Also `git -C 'C:\Users\Michael\Documents\Project\NiroWear' status --porcelain` shows no new files outside `.private/` (which is ignored). Report both. Commit: none.

---

### Task 4: Cut the stable channel

**Files:**
- Modify (source): `app/build.gradle.kts` (`versionCode = 9`, `versionName = "0.1.0"`), `docs/release/CHANGELOG.md` (stable section: what beta.7 proved + smoke table + honest remaining gates), `docs/release/RELEASE_NOTES_0.1.0.md` (replace candidate language with measured release identity).
- Modify (beta): `CHANGELOG.md`, `index.html` (stable APK first, beta history kept), GitHub `v0.1.0` release.
- Test: full gates re-run on the stable tree (JVM + lint + release assembly + hygiene), Pages built.

**Interfaces:**
- Consumes: Task 2 signed beta.7 lineage, Task 3 smoke table, owner BETA7 sheet results.
- Produces: `v0.1.0` tag + release, site serving stable first, no unmeasured claim.

- [ ] **Step 1: Write the stable gate list with owners — no silent promotion**

Stable ships only when ALL are true: (a) Task 3 smoke table 4/4 terminal events; (b) owner BETA7 sheet steps 1–8 reported back with no content-loss defect; (c) JVM + lint green on the exact stable tree; (d) signing identity equals the beta lineage from Task 2. Known open items that do NOT block stable but must be disclosed on the site: TalkBack pass, native-math licence review (JLaTeXMath exception ships in `assets/legal/`, review open). If any of (a)–(d) is red, stop — publish another beta, not stable.

- [ ] **Step 2: Bump, build, verify identity, tag**

```powershell
$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
& 'C:\Users\Michael\Documents\Project\NiroWear\gradlew.bat' -p 'C:\Users\Michael\Documents\Project\NiroWear' :app:testDebugUnitTest :app:ktlintCheck :app:assembleRelease --offline --console=plain
if ($LASTEXITCODE -ne 0) { throw "stable gates failed: $LASTEXITCODE" }
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
& "$sdk\build-tools\36.0.0\apksigner.bat" verify --print-certs 'C:\Users\Michael\Documents\Project\NiroWear\app\build\outputs\apk\release\app-release.apk'
```

Expected: tests green with literal counts read from XML (report `tests=X failures=0 errors=0`), lint 0 errors, cert fingerprint identical to Task 2. Then `git tag v0.1.0`, `git push origin main --tags`.

- [ ] **Step 3: Publish stable to NiroWear-Beta and make the site install-first-stable**

`gh release create v0.1.0` with the signed APK, update beta `CHANGELOG.md` + `index.html` (stable download first with bytes, beta.7 kept in history), run `check_public.py` PASS, push, Pages `built`. Same command shapes as Task 2 Step 5 — report the actual PASS line and release URL.

- [ ] **Step 4: Close the loop — final status lines in both repos**

Source `docs/release/CHANGELOG.md` stable entry records: JVM counts, lint, smoke table reference, APK bytes, cert fingerprint, tag. Verify `git -C <both repos> status --porcelain` clean and `git log --oneline -3` shows the release commits. Report: both log tails, both release URLs, site URL with HTTP 200.

---

## Self-Review

**Spec coverage:** AGENTS.md product musts (chat/keyboard/voice/BYOK/switching/streaming/history/edit/retry/long answers/Markdown/Unicode/code/LaTeX/matrices/tables/links) — proven by the beta.7 merge (Tasks 1–7) and re-proved live by this plan's Task 3 smoke; no out-of-scope feature added (no images, agents, MCP, browser, artifacts UI). Engineering contract — terminal events (Task 3a), Stop persistence (Task 3d), credentials (Tasks 2–3 gates), manual model ID (smoke uses owner-typed IDs). Renderer/math/streaming contracts — Task 3d + owner sheet steps 2–3. Verification discipline — every task reads XML/output/cert/HTTP before claiming. Publishing rule — allowlist + hygiene gate re-run in Tasks 2–4.

**Placeholder scan:** every step has literal paths, literal commands, literal expected output. No TBD/TODO. Error handling specified (identity mismatch stops publish; missing `Configured` stops smoke; red gate stops stable). Keystore path redacted to basename in logs.

**Type consistency:** `versionCode 8 → 9`, `0.1.0-beta.7 → 0.1.0` consistent across `app/build.gradle.kts`, changelogs, tags, release titles. `v0.1.0-beta.7` then `v0.1.0` tag names match `gh release create` arguments. No function signatures change anywhere in this plan.

## Execution Handoff

**Plan complete and saved to `docs/superpowers/plans/2026-11-02-closeout-publish.md`. Two execution options:**

**1. Subagent-Driven (recommended)** - I dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints

**Which approach?**
