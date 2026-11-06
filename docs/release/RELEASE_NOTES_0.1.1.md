# NiroWear 0.1.1

Release date: 2026-11-06. Fix release on top of the 0.1.0 stable cut — no new product surface.

## Artifact identity

- Package `com.nirowear`, `versionCode 11`, `versionName 0.1.1`, non-debuggable, minSdk 30, targetSdk 37.
- Signed with the dedicated NiroWear release key, certificate SHA-256
  `0D:C5:0F:C0:19:1B:36:F3:74:33:07:4F:C7:89:8B:4D:16:52:98:CA:73:65:4F:41:6F:8F:50:F0:51:86:54:3E`
  (`CN=NiroWear Release`), valid to 2056.
- Built and signed by `.github/workflows/release.yml` from the `v0.1.1` tag, so the uploaded APK and its
  certificate are reproducible from the tag instead of depending on a local machine.
- Measured on the published asset: 3,621,253 B, APK SHA-256
  `0BA7DDEFD7CA51528C0D3083B23BD7EE9221715A017CD7058FA8875EFB30B207`. A local cut of the same tree
  is **not** bit-identical (it measured 3,619,181 B), so the hash that matters is the one in this
  release's body, printed by the workflow that built it.

## Read this before updating

**The signing identity changed.** 0.1.0 shipped under the `CN=Android Debug` lineage
(`7D:4E:6E:99:…:AB:E3`); this release uses the dedicated release key. Android refuses an in-place
update across a different signature, so **uninstall 0.1.0 first**. Uninstalling removes local chats and
saved credentials — keys are device-bound by design, so they must be re-entered. Install 0.1.1 clean and
re-add your provider.

## What changed (all measured on this tree)

**Credentials / data safety**

- A transient Keystore failure while saving a key (Keystore IPC error, daemon restart, first access
  after boot) no longer deletes the shared key entry. It used to, which made every other saved
  provider's ciphertext permanently undecryptable to save one. Only a key that genuinely cannot
  encrypt is replaced.

**Turn recovery**

- A Stop or failure after an edit/retry already touched the conversation now records a STOPPED/ERROR
  row. Before, it deleted the previous answer and left no row, no status line and no Retry.
- A Stop inside a brand-new chat's first insert keeps the typed prompt instead of leaving an empty chat
  with a phantom History entry.
- An edit whose turn is refused re-opens the editor with the text you typed.
- Editing a chat's first message renames it in History.

**Provider truth**

- A reasoning/thinking phase no longer reads as a dead socket: the stream idle deadline now sees those
  frames, so a long think is not killed as "Provider timed out" with an empty answer.
- An answer cut by Anthropic `pause_turn` or `tool_use` is disclosed instead of stored as a finished
  answer; "Partial answer kept." is only shown when text actually survived.
- A captive-portal page is reported as an unreachable provider, not as a broken stream.
- Non-2xx reasons are read from the body for Anthropic, OpenAI-native and compatible gateways, so a
  rejected key no longer reads as "check configuration".
- Anthropic model metadata is cached per endpoint and model instead of fetched on every send.

**Renderer**

- A table inside a list item reports the cells it had to clamp (they used to vanish silently); `$$`
  inside a table row no longer shreds the table; an HTML block no longer steals the next table's notice.
- A prose line containing backticks is not a fence; a mid-line display is top-level; `\hspace`,
  `\vspace`, `\hskip`, `\vskip`, `\kern`, `\rule` typeset instead of degrading to source.
- History A→B→A no longer disables the STOPPED/error prune, and the Stop button no longer clips at a
  2.0× font scale.

## Evidence

- JVM suite: 264 tests, 0 failures, 0 errors, 0 skipped, 42 suites (result XML).
- `ktlintCheck` 0, Android Lint 1 warning (`GradleDependency`: `androidx.test:runner` 1.5.0→1.5.2, not in
  the offline cache), `assembleDebug` + `assembleRelease` successful.
- CI (`jvm`: unit-test step, lint, style, both assembles) green on `main` before tagging.
- Every parser/adapter fix in this release carries a regression test built from the measured input and
  was mutation-checked (production line reverted → red → restored).

## Not verified here (stated, not implied)

- The 48 connected instrumentation tests and the 6 `@HostDrivenGate` tests are written and **have never
  executed** — no device or AVD in this environment. `ByokPersistenceTest` included.
- The Keystore, Room and Compose paths fixed above have no JVM test; a device run is the proof.
- The phone-side click-through of the self-signed pairing certificate has not been exercised on hardware.

## Known open work

- Accessibility/i18n project: ~94 user-visible English literals in four Compose files, no
  `res/values/strings.xml`; a few dead ends (composer label, an empty user-row click target, the "Links"
  group action, repeated entry labels, the 130 dp provider header at large font scales).
- Table rows beyond `MAX_TABLE_ROWS = 40` are not rendered; making rows lazy is a reader-model refactor.
- History search is a `LIKE` scan (FTS5 is unavailable on Android).
